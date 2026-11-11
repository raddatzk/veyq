// Which of a header's planned actions this app draws (the header CR's Phase 2): the plan and its words are the core's
// (NodeHeaderModel through Header.*); the move, the copy, the share, the report, the cover and a folder's download are
// the CR's later phases, left out until then rather than drawn as a command that answers "not yet". Linux's
// header::drawn, rule for rule.
using System;
using System.Linq;

namespace Veyq;

internal static class HeaderPlan
{
    /// <summary>The ids this phase draws.</summary>
    public static readonly string[] Drawn = ["favorite", "rename", "delete", "download"];

    /// <summary>Whether <paramref name="id"/> is drawn for a node of <paramref name="kind"/>: a download only where this
    /// app writes one (a Pot's, Pot.Download).</summary>
    public static bool IsDrawn(string id, string kind) =>
        Drawn.Contains(id) && (id != "download" || kind == "EXPENSES_POT");
}
