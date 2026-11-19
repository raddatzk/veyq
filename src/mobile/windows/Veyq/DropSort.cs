// What a drop onto a level or a folder's row is sorted into (ADR 0123's Consequences, the Windows app's Depot whole), the one rule the Windows side holds about a
// drop (Linux's drop.rs, rule for rule): each dropped storage item with a local path goes to the core as a picked one does —
// the files as one batch, each folder walked by the core, both screened for a veyq download first (Import.Screen,
// Import.ScreenFolder; ADR 0152 decision 1), one import per drop as on the web —, and an item with no path (a
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

    /// <summary>What the file system says of a dropped <paramref name="path"/>, for the log — its root's kind and the
    /// item's attributes, never the path or a name: "Fixed C:, Archive", "Fixed C:, FileNotFoundException".</summary>
    public static string Facts(string path)
    {
        string root;
        try
        {
            var r = System.IO.Path.GetPathRoot(path) ?? "";
            root = r.StartsWith(@"\\", System.StringComparison.Ordinal) ? $"unc {r}"
                : r.Length > 0 ? $"{new System.IO.DriveInfo(r).DriveType} {r.TrimEnd('\\')}" : "relative";
        }
        catch (System.Exception e)
        {
            root = $"unrooted ({e.GetType().Name})";
        }
        try
        {
            var attributes = System.IO.File.GetAttributes(path);
            return $"{root}, {attributes}";
        }
        catch (System.Exception e)
        {
            return $"{root}, {e.GetType().Name}";
        }
    }

    /// <summary>The calls a sorted drop onto <paramref name="level"/> makes: the files as one batch, then one per folder,
    /// each screened for a download (<see cref="Plain"/> once the drop offered one).</summary>
    public static IReadOnlyList<Call> Calls(UploadLevel level, Sorted sorted)
    {
        var calls = new List<Call>();
        if (sorted.Files.Count > 0) calls.Add(Screen(level, sorted.Files));
        foreach (var path in sorted.Folders) calls.Add(ScreenFolder(level, path));
        return calls;
    }

    /// <summary>Picked or dropped files screened into <paramref name="level"/> (Import.Screen); an imported Pot's erased
    /// person named by the host's word.</summary>
    public static Call Screen(UploadLevel level, IReadOnlyList<string> files) =>
        new Call.ImportScreen { scopeId = level.ScopeId, parentId = level.ParentId, label = level.Label, paths = files.ToList(), deletedName = Words.PotDeletedPerson() };

    /// <summary>A picked or dropped directory screened into <paramref name="level"/> (Import.ScreenFolder).</summary>
    public static Call ScreenFolder(UploadLevel level, string path) =>
        new Call.ImportScreenFolder { scopeId = level.ScopeId, parentId = level.ParentId, label = level.Label, path = path, deletedName = Words.PotDeletedPerson() };

    /// <summary>A screened call as the ordinary upload it would be: what a drop sends after it offered one import (the web
    /// offers one per drop, the rest is the ordinary upload).</summary>
    public static Call Plain(Call call) => call switch
    {
        Call.ImportScreen s => new Call.UploadFiles { scopeId = s.scopeId, parentId = s.parentId, label = s.label, paths = s.paths },
        Call.ImportScreenFolder f => new Call.UploadFolder { scopeId = f.scopeId, parentId = f.parentId, path = f.path },
        _ => call,
    };

    /// <summary>What a drop or a pick onto gallery <paramref name="gallery"/> sends (Linux's memory_page::calls): the files as
    /// one Memory.Upload — the core leaves out what is no photo or video and says so —, nothing for a folder (owner question
    /// 3: said, not taken) or for an item with no local path.</summary>
    public static Call? GalleryCall(long gallery, Sorted sorted) =>
        sorted.Files.Count > 0 ? new Call.MemoryUpload { gallery = gallery, paths = sorted.Files.ToList() } : null;

    /// <summary>What a drop or a pick onto a gallery says at once, before the core answers: each folder not taken, each item
    /// that is not on this computer.</summary>
    public static IReadOnlyList<string> GallerySaid(Sorted sorted) =>
        sorted.Folders.Select(f => Words.MemoryDropFolder(System.IO.Path.GetFileName(f.TrimEnd('\\', '/')) is { Length: > 0 } n ? n : f))
            .Concat(sorted.Refused.Select(Words.DepotDropNotLocal)).ToList();
}
