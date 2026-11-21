// What a Memory's page says of its marks and its filter (ADR 0149 decisions 4 and 5), without a widget: whether the filter
// narrows, what shows where it leaves nothing (the web's MemoryDetail filterEmptyText), and what a tile's marks' badge says
// to a screen reader. GalleryTextTest holds them.

namespace Veyq.Apps.Memories;

using System.Linq;
using Veyq.Bridge.Wire;
using Veyq.Core;

internal static class GalleryText
{
    /// <summary>Whether <paramref name="f"/> narrows the grid: a chip, a text, a marks' mode.</summary>
    public static bool Narrows(FilterShown f) => f.chips.Count > 0 || f.text.Trim().Length > 0 || f.mode != MarkMode.All;

    /// <summary>What shows where the filter leaves nothing: the marks' own words where only the mode narrows, else "Nothing
    /// in this Memory matches …" naming the chips, the text and the mode (the web's media.filter.empty).</summary>
    public static string Empty(FilterShown f)
    {
        if (f.chips.Count == 0 && f.text.Trim().Length == 0)
        {
            if (f.mode == MarkMode.Mine) return Words.MediaMarksEmptyMine();
            if (f.mode == MarkMode.Everyone) return Words.MediaMarksEmptyEveryone();
        }
        var parts = f.chips.Select(ChipLabel).ToList();
        if (f.text.Trim().Length > 0) parts.Add(Words.MediaFilterText(f.text.Trim()));
        if (f.mode == MarkMode.Mine) parts.Add(Words.MediaFilterMarksMine());
        else if (f.mode == MarkMode.Everyone) parts.Add(Words.MediaFilterMarksEveryone());
        return Words.MediaFilterEmpty(string.Join(", ", parts));
    }

    /// <summary>A chip as it reads: the core's label as it shows it (a day chip's day written by the core, Shown.day).</summary>
    public static string ChipLabel(ChipShown c) => Said.Piece(c.labelShown);

    /// <summary>What a tile's badge says to a screen reader: marked by the caller, how often; null for no badge.</summary>
    public static string? MarkWords(GalleryRow row)
    {
        // marked by the caller alone: "Marked by you"; by others too: both; by others only: how often
        if (row.marks is int n && n > (row.mine ? 1 : 0)) return row.mine ? $"{Words.MediaMarksYours()}, {Words.MediaMarksCount(n)}" : Words.MediaMarksCount(n);
        return row.mine ? Words.MediaMarksYours() : null;
    }
}
