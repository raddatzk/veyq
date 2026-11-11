// Which of a header's planned actions this app draws, and in which groups (the header CR): the plan and its words are the
// core's (NodeHeaderModel through Header.*); an application's own action is its page's. Linux's header::drawn, rule for rule. The bar's secondary
// commands are the plan's menu, then its last group after a separator — read from the plan's groups, so a folder's More
// and a Pot's are laid out alike whatever each holds.
using System.Collections.Generic;
using System.Linq;
using Veyq.Bridge.Wire;

namespace Veyq;

internal static class HeaderPlan
{
    /// <summary>The ids drawn so far.</summary>
    public static readonly string[] Drawn = ["favorite", "share", "rename", "delete", "download", "move", "copy", "report", "cover"];

    /// <summary>Whether <paramref name="id"/> is drawn for a node of <paramref name="kind"/>: a download where it is the
    /// node's archive (a file's is its own bytes, Depot's page).</summary>
    public static bool IsDrawn(string id, string kind) =>
        Drawn.Contains(id) && (id != "download" || kind != "FILE");

    /// <summary>The plan's buttons this app draws: the bar's primary commands.</summary>
    public static List<PlanAction> Primary(Plan plan, string kind) => plan.buttons.Where(a => IsDrawn(a.id, kind)).ToList();

    /// <summary>The bar's secondary commands from the plan's groups: the menu's drawn actions, then — after a separator
    /// (null) where both groups hold one — the last group's.</summary>
    public static List<PlanAction?> Secondary(Plan plan, string kind)
    {
        var menu = plan.menu.Where(a => IsDrawn(a.id, kind)).ToList();
        var last = plan.last.Where(a => IsDrawn(a.id, kind)).ToList();
        var all = new List<PlanAction?>(menu);
        if (menu.Count > 0 && last.Count > 0) all.Add(null);
        all.AddRange(last);
        return all;
    }
}
