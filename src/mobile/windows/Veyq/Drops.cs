// Drag and drop onto a level, a folder's row and a gallery (ADR 0154 decision 1; ADR 0123's Consequences, the Windows
// app's Depot whole), Fluent's way: the target takes dropped storage items (Explorer's files and folders) as a copy, the
// row under the pointer highlighted as a list's hovered row is. Where a drop is taken is the core's alone — the level where
// ShellShown.dropsHere says so, a folder's row where ShellShown.dropRows names it (never a row by its kind), a gallery where
// its rights upload —, and so is everything after it: the app tells a file from a folder by the file system and hands each
// by path to the core's one drop (Shell.Drop; ADR 0137 decision 3: the core opens and reads each at offsets, nothing of a
// file crosses), which judges the target again, sorts, uploads and offers one veyq download per drop as an import (ADR
// 0152 decision 1). The app sorts nothing and says only what the core answered.
using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;

namespace Veyq;

/// <summary>Where uploads go from New's pickers: a space, the folder there (null: its top) and the batch's label.</summary>
internal sealed record UploadLevel(string ScopeId, string? ParentId, string Label);

/// <summary>The drops of the level shown (titled <paramref name="Label"/>): onto the level itself where
/// <paramref name="Level"/> (ShellShown.dropsHere), and onto each folder's row <paramref name="Rows"/> names
/// (ShellShown.dropRows); an import offered opens over <paramref name="Window"/>, the rest said through
/// <paramref name="Say"/>.</summary>
internal sealed record Drops(Window Window, string Label, bool Level, IReadOnlySet<string> Rows, Action<bool, string> Say)
{
    /// <summary>The level's own drop: onto its scroller, wherever no folder's row takes it; nothing where the core
    /// offers none.</summary>
    public void OntoLevel(UIElement scroller)
    {
        var on = new DropOn.DropOnLevel { label = Label };
        if (Level) Attach(scroller, () => on, Label, null, Window, Say);
    }

    /// <summary>The drop onto the row of folder <paramref name="folder"/>, where the core offers one.</summary>
    public void OntoFolder(Control row, NodeRef folder)
    {
        if (!Rows.Contains(folder.nodeId)) return;
        var at = new NodeAt { scopeId = folder.scopeId, nodeId = folder.nodeId, kind = folder.kind, name = folder.name };
        var on = new DropOn.DropOnRow { folder = at };
        Attach(row, () => on, folder.name, row, Window, Say);
    }

    /// <summary>Makes <paramref name="target"/> take a drop onto what <paramref name="on"/> answers at each drag — null
    /// where nothing is offered now (a gallery not open yet, or whose rights upload nothing) — with the drag's caption
    /// <paramref name="caption"/>, <paramref name="highlighted"/> lit while a drag is over it.</summary>
    public static void Attach(UIElement target, Func<DropOn?> on, string caption, Control? highlighted, Window window, Action<bool, string> say)
    {
        target.AllowDrop = true;
        // said once per entry, so a pass reads whether a drag reached the app before its shot
        target.DragEnter += (_, e) => Log.Write($"a drag entered {on()?.GetType().Name ?? "nothing offered"}: files {e.DataView.Contains(StandardDataFormats.StorageItems)}");
        target.DragOver += (_, e) =>
        {
            if (!e.DataView.Contains(StandardDataFormats.StorageItems) || on() is null) return;
            e.AcceptedOperation = DataPackageOperation.Copy;
            e.DragUIOverride.Caption = caption;
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
            if (!e.DataView.Contains(StandardDataFormats.StorageItems) || on() is not { } onto) return;
            // the folder's row takes it; the level beneath does not take it again
            e.Handled = true;
            try
            {
                List<DropItem> items;
                var deferral = e.GetDeferral();
                try
                {
                    items = Items(await e.DataView.GetStorageItemsAsync());
                }
                finally
                {
                    deferral.Complete();
                }
                await Send(window, onto, items, say);
            }
            catch (Exception ex)
            {
                // an exception out of an async handler would end the app in combase, with nothing in the log
                Log.Write($"a drop failed: {ex.GetType().Name}: {ex.Message}");
            }
        };
    }

    /// <summary>Each dropped or picked storage item as the file system tells it: a folder, a file, or — with no local path
    /// (a phone's, a cloud's virtual item) — an item said by its name. Nothing is sorted or judged here.</summary>
    public static List<DropItem> Items(IEnumerable<IStorageItem> items) => items.Select(Item).ToList();

    private static DropItem Item(IStorageItem i) =>
        string.IsNullOrEmpty(i.Path) ? new DropItem.DroppedNotLocal { name = i.Name }
        : i.IsOfType(StorageItemTypes.Folder) ? new DropItem.DroppedFolder { path = i.Path }
        : new DropItem.DroppedFile { path = i.Path };

    /// <summary>The core's one drop (Shell.Drop) of <paramref name="items"/> onto <paramref name="on"/>: each notice said in
    /// order, an import offered opened over <paramref name="window"/>.</summary>
    public static async Task Send(Window window, DropOn on, IReadOnlyList<DropItem> items, Action<bool, string> say)
    {
        if (items.Count == 0) return;
        Log.Write($"dropped onto {on.GetType().Name}: {items.Count(i => i is DropItem.DroppedFile)} files, {items.Count(i => i is DropItem.DroppedFolder)} folders, {items.Count(i => i is DropItem.DroppedNotLocal)} not local");
        // where each dropped path lies and what the file system says of it, never the path itself: a drop the core cannot
        // read says why in the log (the packaged app's drop, 2026-11-13)
        foreach (var path in items.Select(i => i switch { DropItem.DroppedFile f => f.path, DropItem.DroppedFolder d => d.path, _ => null }).OfType<string>())
        {
            Log.Write($"a dropped path: {Facts(path)}");
        }
        var (answer, _) = await Core.CallAsync(new Call.ShellDrop { on = on, items = items.ToList(), deletedName = Words.PotDeletedPerson() });
        if (answer is not Answer.Dropped dropped)
        {
            Log.Write($"Shell.Drop answered {answer.GetType().Name}");
            return;
        }
        Log.Write($"Shell.Drop: {dropped.queued} queued, import {dropped.import?.ToString() ?? "-"}, refused {dropped.refused}, said {string.Join(",", dropped.notices.Select(n => n.msg))}");
        foreach (var n in dropped.notices) say(false, Said.Notice(n));
        if (dropped.import is { } import) ImportDialog.Open(window, import, say);
    }

    /// <summary>What the file system says of a dropped <paramref name="path"/>, for the log — its root's kind and the item's
    /// attributes, never the path or a name: "Fixed C:, Archive", "Fixed C:, FileNotFoundException".</summary>
    private static string Facts(string path)
    {
        string root;
        try
        {
            var r = System.IO.Path.GetPathRoot(path) ?? "";
            root = r.StartsWith(@"\\", StringComparison.Ordinal) ? $"unc {r}"
                : r.Length > 0 ? $"{new System.IO.DriveInfo(r).DriveType} {r.TrimEnd('\\')}" : "relative";
        }
        catch (Exception e)
        {
            root = $"unrooted ({e.GetType().Name})";
        }
        try
        {
            return $"{root}, {System.IO.File.GetAttributes(path)}";
        }
        catch (Exception e)
        {
            return $"{root}, {e.GetType().Name}";
        }
    }
}
