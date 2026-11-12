// Drag and drop onto a level and onto a folder's row (ADR 0123's Consequences, the Windows app's Depot whole), Fluent's way: the level's scroller and each
// folder's row take dropped storage items (Explorer's files and folders) as a copy, the row under the pointer highlighted
// as a list's hovered row is; what was dropped is sorted by DropSort and handed to the core by path (ADR 0130 decision 3:
// the core opens and reads each at offsets, nothing of a file crosses). A dropped veyq download is uploaded as the file
// it is (until import is built, native-apps § Open).
using System;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;

namespace Veyq;

/// <summary>Where a drop onto the level shown goes, saying through <paramref name="Say"/>: the level itself, and each of
/// its folders' rows into that folder.</summary>
internal sealed record Drops(UploadLevel Level, Action<bool, string> Say)
{
    /// <summary>The level's own drop: onto its scroller, wherever no folder's row takes it.</summary>
    public void OntoLevel(UIElement scroller) => Attach(scroller, Level, null);

    /// <summary>The drop onto the row of the folder <paramref name="nodeId"/> named <paramref name="name"/>.</summary>
    public void OntoFolder(Control row, string nodeId, string name) => Attach(row, Level with { ParentId = nodeId, Label = name }, row);

    private void Attach(UIElement target, UploadLevel level, Control? highlighted)
    {
        target.AllowDrop = true;
        // said once per entry, so a pass reads whether a drag reached the app before its shot
        target.DragEnter += (_, e) => Log.Write($"a drag entered {level.Label}: files {e.DataView.Contains(StandardDataFormats.StorageItems)}");
        target.DragOver += (_, e) =>
        {
            if (!e.DataView.Contains(StandardDataFormats.StorageItems)) return;
            e.AcceptedOperation = DataPackageOperation.Copy;
            e.DragUIOverride.Caption = level.Label;
            e.Handled = true;
        };
        if (highlighted is not null)
        {
            var resting = highlighted.Background;
            highlighted.DragEnter += (_, e) =>
            {
                if (e.DataView.Contains(StandardDataFormats.StorageItems))
                {
                    highlighted.Background = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["SubtleFillColorSecondaryBrush"];
                }
            };
            highlighted.DragLeave += (_, _) => highlighted.Background = resting;
            highlighted.Drop += (_, _) => highlighted.Background = resting;
        }
        target.Drop += async (_, e) =>
        {
            if (!e.DataView.Contains(StandardDataFormats.StorageItems)) return;
            // the folder's row takes it; the level beneath does not take it again
            e.Handled = true;
            try
            {
                DropSort.Sorted sorted;
                var deferral = e.GetDeferral();
                try
                {
                    var items = await e.DataView.GetStorageItemsAsync();
                    sorted = DropSort.Sort(items.Select(i => new DroppedItem(i.Name, i.Path, i.IsOfType(StorageItemTypes.Folder))));
                }
                finally
                {
                    deferral.Complete();
                }
                Log.Write($"dropped onto {level.Label}: {sorted.Files.Count} files, {sorted.Folders.Count} folders, {sorted.Refused.Count} refused");
                foreach (var name in sorted.Refused) Say(false, Words.DepotDropNotLocal(name));
                foreach (var call in DropSort.Calls(level, sorted))
                {
                    var (answer, _) = await Core.CallAsync(call);
                    Uploads.Queued(answer, call is Call.UploadFolder ? "Upload.Folder" : "Upload.Files", Say);
                }
            }
            catch (Exception ex)
            {
                // an exception out of an async handler would end the app in combase, with nothing in the log
                Log.Write($"a drop failed: {ex.GetType().Name}: {ex.Message}");
            }
        };
    }
}
