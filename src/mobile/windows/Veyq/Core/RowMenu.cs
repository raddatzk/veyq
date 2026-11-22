// A level row's menu (ADR 0154 decision 2; the web's levelRow.ts): the core's plan for the row's node (Row.Plan —
// rename and move first, then the rest in their merge order, a separator, report and the delete last), every entry drawn
// in its order as the row's context menu, Explorer's way: a right click, Shift+F10 or the menu key on the row or the tile
// under it. The plan is asked when the menu is asked for, so it is the core's of that moment. An entry runs as the header
// of that node runs it (NodeHeader.Run over a header opened for the row, on the row's facts as the plan was made): the same
// dialogs and sheets, the same Undo under the bar. The apps draw no row buttons.

namespace Veyq.Core;

using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal static class RowMenu
{
    /// <summary>The shell's window, where an entry's dialogs open.</summary>
    public static Window? Window { get; set; }

    /// <summary>Where an entry says what it did (the shell's InfoBar under the bar).</summary>
    public static Action<Spoken>? Say { get; set; }

    /// <summary>The notice bar a delete's Undo stands in (the shell's).</summary>
    public static NoticeBar? UndoBar { get; set; }

    /// <summary>The header the last entry ran through, and one whose trash's Undo still stands.</summary>
    private static NodeHeader? _running;
    private static NodeHeader? _undoing;

    /// <summary>Gives <paramref name="target"/> (a row, a tile) the menu of <paramref name="node"/>.</summary>
    public static void Attach(UIElement target, NodeAt node)
    {
        target.ContextRequested += async (_, e) =>
        {
            e.Handled = true;
            var at = e.TryGetPosition(target, out var point) ? point : (Windows.Foundation.Point?)null;
            try
            {
                var (answer, _) = await Core.CallAsync(new Call.RowPlan { node = node });
                if (answer is not Answer.RowPlan plan)
                {
                    Log.Write($"Row.Plan answered {answer.GetType().Name}");
                    return;
                }
                var menu = new MenuFlyout();
                foreach (var a in HeaderPlan.Grouped(plan.menu, plan.last)) menu.Items.Add(a is null ? new MenuFlyoutSeparator() : Item(a, node));
                Log.Write($"row menu {node.kind}: {plan.menu.Count} + {plan.last.Count} entries");
                if (menu.Items.Count == 0) return;
                if (at is { } p) menu.ShowAt(target, new FlyoutShowOptions { Position = p });
                else menu.ShowAt((FrameworkElement)target);
            }
            catch (Exception ex)
            {
                // an exception out of an async handler would end the app in combase, with nothing in the log
                Log.Write($"Row.Plan failed ({ex.GetType().Name})");
            }
        };
    }

    private static MenuFlyoutItem Item(PlanAction a, NodeAt node)
    {
        var item = new MenuFlyoutItem { Text = a.word is { } w ? Said.Key(w) : HeaderPlan.Wire(a.id), Icon = NodeHeader.Icon(a, null) };
        AutomationProperties.SetAutomationId(item, $"row:{HeaderPlan.Wire(a.id)}");
        var id = a.id;
        item.Click += (_, _) => Run(node, id);
        return item;
    }

    /// <summary>A kept link's row's Report shared content: the link's screen with its report asked (Row.ReportShared).</summary>
    private static async void ReportShared(NodeAt node)
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.RowReportShared { node = node });
            if (answer is Answer.ReceivedOpened { received: var received }) ReceivedView.Present(received, null);
            else Log.Write($"the kept link's row's report did not open ({answer.GetType().Name})");
        }
        catch (Exception e)
        {
            Log.Write($"the kept link's row's report did not open ({e.GetType().Name})");
        }
    }

    /// <summary>Entry <paramref name="id"/> of <paramref name="node"/>'s menu, run by a header of that node once it shows.</summary>
    private static void Run(NodeAt node, HeaderAction id)
    {
        if (Window is not { } window || Say is not { } say) return;
        if (_running is { Undoing: true })
        {
            _undoing?.Dispose();
            _undoing = _running;
        }
        else _running?.Dispose();
        var ran = false;
        NodeHeader? header = null;
        // the row's facts as Row.Plan made the plan (AppKinds.Facts' defaults): no page read it
        header = new NodeHeader(window, node, new Facts { canWrite = true, hasContent = true }, () =>
        {
            if (ran || header?.Shown is not { gone: false }) return;
            ran = true;
            Log.Write($"row menu: {HeaderPlan.Wire(id)} on a {node.kind}");
            // a kept link's row offers its share's report where its link reads (core's row plan): no page is open, so the
            // core reads its meta from the node (Row.ReportShared)
            header.Run(id, new PageHooks { ReportShared = () => ReportShared(node) });
        }, say) { UndoBar = UndoBar };
        _running = header;
    }
}
