// A header's plan as the bar lays it out (ADR 0154 decision 2): the plan and its words are the core's (NodeHeaderModel
// through Header.*), every entry drawn, in its order — the buttons as the bar's primary commands, then the menu and, after a
// separator where both hold one, the last group as its secondary ones (Delete last). Which entries a node offers is the
// core's alone: nothing here filters, sorts or whitelists (scripts/validate_mobile_structure.py). Linux's header.rs, rule
// for rule. A switch over HeaderAction is a switch expression without a discard, so an entry the core adds fails this
// build until it is drawn (CS8509 an error, Directory.Build.props).

namespace Veyq.Core;

using System.Collections.Generic;
using Veyq.Bridge.Wire;

internal static class HeaderPlan
{
    /// <summary>An entry's wire id (its serial name): the automation ids ("header:&lt;id&gt;") and the log spell it.</summary>
    public static string Wire(HeaderAction a) => a switch
    {
        HeaderAction.Favorite => "favorite",
        HeaderAction.Share => "share",
        HeaderAction.Rename => "rename",
        HeaderAction.Move => "move",
        HeaderAction.Copy => "copy",
        HeaderAction.Cover => "cover",
        HeaderAction.Download => "download",
        HeaderAction.Offline => "offline",
        HeaderAction.Report => "report",
        HeaderAction.Delete => "delete",
        HeaderAction.Upload => "upload",
        HeaderAction.Music => "music",
        HeaderAction.Banner => "banner",
        HeaderAction.Replace => "replace",
        HeaderAction.Send => "send",
        HeaderAction.OpenOwnPage => "open-own-page",
        HeaderAction.ReportShared => "report-shared",
    };

    /// <summary>Whether a node of <paramref name="kind"/> downloads through its page where it shows one: a Pot's
    /// (Pot.Download) and a file's (Save a copy, File.Save, with its progress and Cancel). A row's menu has no page: the
    /// header's own download (Header.Download, a file as itself) serves it.</summary>
    public static bool DownloadsByPage(string kind) => kind is "EXPENSES_POT" or "FILE";

    /// <summary>The bar's primary commands: the plan's buttons, whole.</summary>
    public static IReadOnlyList<PlanAction> Primary(Plan plan) => plan.buttons;

    /// <summary>The bar's secondary commands: the plan's menu, then — after a separator (null) where both groups hold
    /// one — its last group.</summary>
    public static List<PlanAction?> Secondary(Plan plan) => Grouped(plan.menu, plan.last);

    /// <summary>Two groups as one menu, a separator (null) between them where both hold an entry (a row's menu too).</summary>
    public static List<PlanAction?> Grouped(IReadOnlyList<PlanAction> menu, IReadOnlyList<PlanAction> last)
    {
        var all = new List<PlanAction?>(menu);
        if (menu.Count > 0 && last.Count > 0) all.Add(null);
        all.AddRange(last);
        return all;
    }
}
