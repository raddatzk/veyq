// A Memory's date as Memories says it (ADR 0143 decision 5; Apple's MemoryDateText): its facts line, a person's rows, and
// — asked of the core (Memories.Dates) and handed to the core UI's rows as their second line (`ApplicationUi.RowLines`,
// inbox #1066) — the rows of Home and Memories' list.

namespace Veyq.Apps.Memories;

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Veyq.Core;

internal static class MemoryDates
{
    /// <summary>A Memory's date as a row's line and Memories' facts line say it (Apple's MemoryDateText): one day, a
    /// range of two, "Since" a day; null where it has none.</summary>
    public static string? DateText(MemoryDateShown? d)
    {
        if (d is not { text.Length: > 0 }) return null;
        return d.mode == DateMode.Ongoing ? Words.MemoryDateSince(d.text) : d.text;
    }

    /// <summary>Every Memory's and chapter's date the core unseals from the meta it holds (Memories.Dates), as each row's
    /// line by node id; null where the core did not answer.</summary>
    public static async Task<IReadOnlyDictionary<string, string>?> Lines()
    {
        var (answer, _) = await Core.CallAsync(new Call.MemoriesDates());
        if (answer is not Answer.MemoryDates { dates: var dates })
        {
            Log.Write($"Memories.Dates answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
            return null;
        }
        var lines = new Dictionary<string, string>();
        foreach (var d in dates)
        {
            if (!lines.ContainsKey(d.nodeId) && DateText(d.date) is { } text) lines[d.nodeId] = text;
        }
        Log.Write($"memory dates: {lines.Count} of {dates.Select(d => d.nodeId).Distinct().Count()} dated");
        return lines;
    }
}
