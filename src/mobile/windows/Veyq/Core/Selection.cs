// A level's selection (the core's LevelSelection, the web's LevelView Select mode, inbox #1049), Fluent's way — Explorer's
// check boxes: the CommandBar's Select toggle turns it on and off, each row and tile a check its click turns, and a
// CommandBar under the level with the count, All, None, every action of the core's plan in its order (enabled where some
// selected row allows it, what it cannot reach above the bar) and Cancel. Each action opens the core's own sheet over the
// selected rows it reaches: the move and copy sheet (Select.Move), the report (Select.Report), the delete's one question
// (Select.Delete) and its one Undo under the shell's bar.

namespace Veyq.Core;

using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal static class Selection
{
    /// <summary>A tile's status while it is selected (read with its name).</summary>
    public static string CheckedStatus => Words.SelectBar();

    /// <summary>The level's Select tool: checked while the level picks.</summary>
    public static AppBarToggleButton Tool(bool on, Func<bool, Call>? send = null)
    {
        var b = new AppBarToggleButton { Label = Words.SelectAction(), Icon = new FontIcon { Glyph = "" }, IsChecked = on };
        AutomationProperties.SetAutomationId(b, "level-select");
        b.Click += (_, _) => Act.Send(send is not null ? send(b.IsChecked == true) : b.IsChecked == true ? (Call)new Call.SelectStart() : new Call.SelectCancel());
        return b;
    }

    /// <summary>The Select tool of a link guest's level (#1063): the member's, its calls the guest level's.</summary>
    public static AppBarToggleButton GuestTool(bool on, long received) =>
        Tool(on, start => start ? new Call.ReceivedSelectStart { received = received } : (Call)new Call.ReceivedSelectCancel { received = received });

    /// <summary>The calls a selection's bar sends and where its sheets open: a member's level's (Select.*, <see cref="Member"/>)
    /// or a link guest's (Received.Select*, <see cref="Guest"/>, #1063) — the same bar, its actions the core's plan.</summary>
    internal sealed record Calls(Call All, Call None, Call Cancel, Call MoveTo, Window? Window = null, NoticeBar? Said = null);

    /// <summary>The shell's level's selection.</summary>
    public static Calls Member => new(new Call.SelectAll(), new Call.SelectNone(), new Call.SelectCancel(), new Call.SelectMove { copy = false, deletedName = Words.PotDeletedPerson() });

    /// <summary>The selection of received link <paramref name="received"/>'s guest level, its sheets over <paramref name="window"/>,
    /// said in <paramref name="said"/>: its plan holds a move only (a guest copies, reports and deletes no selection).</summary>
    public static Calls Guest(long received, Window window, NoticeBar said) => new(
        new Call.ReceivedSelectAll { received = received }, new Call.ReceivedSelectNone { received = received },
        new Call.ReceivedSelectCancel { received = received }, new Call.ReceivedSelectMove { received = received }, window, said);

    /// <summary>What a row's or a tile's click does while the level picks: its node turned.</summary>
    public static Action Toggle(string nodeId) => () => Act.Send(new Call.SelectToggle { nodeId = nodeId });

    /// <summary>A row's start while the level picks: its check before its kind's glyph.</summary>
    public static UIElement Check(FontIcon glyph, bool selected)
    {
        var start = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        start.Children.Add(new CheckBox { IsChecked = selected, IsHitTestVisible = false, IsTabStop = false, MinWidth = 0 });
        start.Children.Add(glyph);
        return start;
    }

    /// <summary>The selection's bar: what an action cannot reach, then the CommandBar with the count, All, None, the plan's
    /// actions in order and Cancel.</summary>
    public static FrameworkElement Bar(ShownSelection sel) => Bar(sel, Member);

    /// <summary><see cref="Bar(ShownSelection)"/> sending <paramref name="calls"/>.</summary>
    public static FrameworkElement Bar(ShownSelection sel, Calls calls)
    {
        var column = new StackPanel { Spacing = 2, Padding = new Thickness(12, 4, 12, 4) };
        AutomationProperties.SetName(column, Words.SelectBar());
        AutomationProperties.SetAutomationId(column, "selection-bar");
        foreach (var a in sel.actions)
        {
            if (a.cannot is not { } cannot) continue;
            column.Children.Add(new TextBlock
            {
                Text = Said.Notice(cannot),
                TextWrapping = TextWrapping.Wrap,
                Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
                Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
            });
        }
        var count = new TextBlock { Text = Said.Notice(sel.count), VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(8, 0, 8, 0), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
        AutomationProperties.SetLiveSetting(count, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Polite);
        var bar = new CommandBar { DefaultLabelPosition = CommandBarDefaultLabelPosition.Right, HorizontalAlignment = HorizontalAlignment.Stretch, Content = count, IsDynamicOverflowEnabled = true };
        bar.PrimaryCommands.Add(Button(Words.SelectAll(), "select-all", true, () => Act.Send(calls.All)));
        bar.PrimaryCommands.Add(Button(Words.SelectNone(), "select-none", true, () => Act.Send(calls.None)));
        foreach (var a in sel.actions)
        {
            var word = Said.Key(a.word);
            bar.PrimaryCommands.Add(a.id switch
            {
                SelectionAction.Move => Button(word, "select-move", a.enabled, () => MoveTo(calls, Said.Notice(sel.count))),
                SelectionAction.Copy => Button(word, "select-copy", a.enabled, () => Move(true, Said.Notice(sel.count))),
                SelectionAction.Report => Button(word, "select-report", a.enabled, Report),
                SelectionAction.Delete => Button(word, "select-delete", a.enabled, Delete),
            });
        }
        bar.PrimaryCommands.Add(Button(Words.EntryCancel(), "select-cancel", true, () => Act.Send(calls.Cancel)));
        column.Children.Add(bar);
        return column;
    }

    private static AppBarButton Button(string label, string automationId, bool enabled, Action act)
    {
        var b = new AppBarButton { Label = label, IsEnabled = enabled };
        AutomationProperties.SetAutomationId(b, automationId);
        b.Click += (_, _) => act();
        return b;
    }

    /// <summary>The selection's Move to… over the window and the bar <paramref name="calls"/> names (a member's: the shell's).</summary>
    private static void MoveTo(Calls calls, string title)
    {
        if ((calls.Window ?? RowMenu.Window) is not { } window) return;
        MoveDialog.OpenWith(window, title, calls.MoveTo, calls.Said ?? RowMenu.UndoBar);
    }

    private static void Move(bool copy, string title)
    {
        if (RowMenu.Window is not { } window) return;
        MoveDialog.OpenWith(window, title, new Call.SelectMove { copy = copy, deletedName = Words.PotDeletedPerson() }, RowMenu.UndoBar);
    }

    private static void Report()
    {
        if (RowMenu.Window is { } window) ReportDialog.Open(window, new Call.SelectReport());
    }

    /// <summary>The selection's delete: its one question, then each selected node to the trash, what came of it said under
    /// the bar with its one Undo while the core offers it.</summary>
    private static async void Delete()
    {
        try
        {
            if (RowMenu.Window is not { } window) return;
            var (asked, _) = await Core.CallAsync(new Call.SelectDelete());
            if (asked is not Answer.SelectionDelete { delete: var id, title: var title, question: var question })
            {
                Log.Write($"Select.Delete answered {asked.GetType().Name}");
                return;
            }
            if (!await Dialogs.Confirm(window, "select-delete", Said.Notice(title), Said.Notice(question), Words.EntryDelete()))
            {
                Act.Send(new Call.SelectDeleteClose { delete = id });
                return;
            }
            var (ran, _) = await Core.CallAsync(new Call.SelectDeleteRun { delete = id });
            if (ran is not Answer.Trashed { notice: var said, undoable: var undoable } || RowMenu.UndoBar is not { } bar)
            {
                Log.Write($"Select.DeleteRun answered {ran.GetType().Name}");
                Act.Send(new Call.SelectDeleteClose { delete = id });
                return;
            }
            Log.Write($"selection delete: {said.msg}, undo offered: {undoable}");
            if (!undoable)
            {
                bar.Say(Spoken.Of(said));
                Act.Send(new Call.SelectDeleteClose { delete = id });
                return;
            }
            Watch? watch = null;
            var undo = new Button { Content = Words.TrashUndo() };
            AutomationProperties.SetAutomationId(undo, "undo");
            undo.Click += async (_, _) =>
            {
                bar.EndOffer();
                watch?.Dispose();
                try
                {
                    var (answered, _) = await Core.CallAsync(new Call.SelectDeleteUndo { delete = id });
                    if (answered is Answer.Said { notice: { } failed }) bar.Say(Spoken.Of(failed));
                }
                catch (Exception e)
                {
                    Log.Write($"Select.DeleteUndo failed ({e.GetType().Name})");
                }
                Act.Send(new Call.SelectDeleteClose { delete = id });
            };
            bar.Offer(Spoken.Of(said), undo);
            // the Undo goes when the core's offer ends (once a state showed it), the core's delete with it
            var seen = false;
            watch = Core.Watch(new Watched.SelectionDelete { delete = id }, update =>
            {
                if (update is not Update.SelectionDelete u) return;
                if (u.undo is not null) seen = true;
                else if (seen)
                {
                    seen = false;
                    bar.EndOffer();
                    watch?.Dispose();
                    Act.Send(new Call.SelectDeleteClose { delete = id });
                }
            }, e => Log.Write($"the selection delete's follow: {e.Message}"));
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"the selection's delete failed ({e.GetType().Name})");
        }
    }
}
