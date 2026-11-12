// What a drop onto a level or a folder's row is sorted into (the Depot CR's D8), the one rule the Windows side holds about a
// drop (Linux's drop.rs, rule for rule): each dropped storage item with a local path goes to the core as a picked one does —
// the files as one batch (Upload.Files), each folder walked by the core (Upload.Folder) —, and an item with no path (a
// phone's or a cloud's virtual item) is refused under its name. Where a drop is taken at all is the core's: a level takes
// one where its New offers Upload files (ShellShown.creates). The events that feed it are HomeView's (Drops.Attach).
using System.Collections.Generic;
using System.Linq;
using Veyq.Bridge.Wire;

namespace Veyq;

/// <summary>Where uploads go: a space, the folder there (null: its top) and the batch's label.</summary>
internal sealed record UploadLevel(string ScopeId, string? ParentId, string Label);

/// <summary>A dropped storage item as the drop handed it: its shown name, its local path ("" or null when it has none), and
/// whether it is a folder.</summary>
internal readonly record struct DroppedItem(string Name, string? Path, bool IsFolder);

internal static class DropSort
{
    /// <summary>A drop sorted: the files' paths, the folders' paths, the names of what has no local path.</summary>
    public sealed record Sorted(IReadOnlyList<string> Files, IReadOnlyList<string> Folders, IReadOnlyList<string> Refused);

    public static Sorted Sort(IEnumerable<DroppedItem> items)
    {
        List<string> files = [], folders = [], refused = [];
        foreach (var item in items)
        {
            if (string.IsNullOrEmpty(item.Path)) refused.Add(item.Name);
            else if (item.IsFolder) folders.Add(item.Path);
            else files.Add(item.Path);
        }
        return new Sorted(files, folders, refused);
    }

    /// <summary>The calls a sorted drop onto <paramref name="level"/> makes: the files as one batch, then one folder upload
    /// per folder.</summary>
    public static IReadOnlyList<Call> Calls(UploadLevel level, Sorted sorted)
    {
        var calls = new List<Call>();
        if (sorted.Files.Count > 0)
        {
            calls.Add(new Call.UploadFiles { scopeId = level.ScopeId, parentId = level.ParentId, label = level.Label, paths = sorted.Files.ToList() });
        }
        foreach (var path in sorted.Folders)
        {
            calls.Add(new Call.UploadFolder { scopeId = level.ScopeId, parentId = level.ParentId, path = path });
        }
        return calls;
    }
}
