// A Memories list row's second line, its date (ADR 0143 decision 5; Linux's home row, Apple's MemoryDateText.row): every
// Memory's and chapter's date the core unseals from the meta it holds (Memories.Dates), held by node id and asked again
// when the shell's objects change or a page closes back onto a list (an edited date shows then). Said as the facts line
// says it (DateText); the list is drawn anew once dates arrive that differ.

namespace Veyq.Core;

using System;
using System.Collections.Generic;
using System.Linq;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal static class RowDates
{
    /// <summary>A Memory's date as a row's line and Memories' facts line say it (Apple's MemoryDateText): one day, a
    /// range of two, "Since" a day; null where it has none.</summary>
    public static string? DateText(MemoryDateShown? d)
    {
        if (d?.from is not { Length: > 0 } from) return null;
        if (d.mode == DateMode.Ongoing) return Words.MemoryDateSince(Said.Day(from));
        if (d.to is not { Length: > 0 } to || string.CompareOrdinal(to, from) <= 0) return Said.Day(from);
        return $"{Said.Day(from)} – {Said.Day(to)}";
    }

    private static Dictionary<string, MemoryDateShown> _dates = [];
    private static string _asked = "";
    private static bool _asking;

    /// <summary>Node <paramref name="nodeId"/>'s date as its row's second line; null where it has none (or none is held yet).</summary>
    public static string? Line(string nodeId) => _dates.TryGetValue(nodeId, out var d) ? DateText(d) : null;

    /// <summary>The dates asked for the objects named by <paramref name="key"/> unless already asked (or
    /// <paramref name="again"/>); <paramref name="changed"/> runs once they arrived and differ from those held.</summary>
    public static async void Ask(string key, bool again, Action changed)
    {
        if (_asking || (key == _asked && !again)) return;
        _asking = true;
        _asked = key;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.MemoriesDates());
            if (answer is not Answer.MemoryDates { dates: var dates })
            {
                Log.Write($"Memories.Dates answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                return;
            }
            var next = dates.GroupBy(d => d.nodeId).ToDictionary(g => g.Key, g => g.First().date);
            var same = next.Count == _dates.Count && next.All(p => _dates.TryGetValue(p.Key, out var d) && d == p.Value);
            _dates = next;
            if (same) return;
            Log.Write($"memory dates: {next.Count(p => DateText(p.Value) is not null)} of {next.Count} dated");
            changed();
        }
        catch (Exception e)
        {
            Log.Write($"Memories.Dates failed ({e.GetType().Name})");
        }
        finally
        {
            _asking = false;
        }
    }
}
