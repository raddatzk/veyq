// A node's move and copy sheet (ADR 0127 decision 2; core's MoveSheet, the web's MoveNodeDialog and CopyNodesDialog),
// Fluent's way as Explorer's "Move to" picks a place: a ContentDialog (WinUI shows one at a time, so the guest folder's
// "Move out?" is asked in place) with a BreadcrumbBar for the core's trail, one place at a time listed under it — the node
// itself and what lies below it listed but not to be opened —, why the place takes nothing (or a copy's missing room)
// under the list, and "Move here" / "Copy here" as its primary button. Once done the dialog closes and the InfoBar says so,
// a move's with Undo for exactly as long as the core offers it (no timer here: it closes when the state's undo does).
// Every state is the core's (Move.*, watched by id); this lays it out.
using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

internal sealed class MoveDialog
{
    private readonly Window _window;
    private readonly InfoBar? _bar;
    private readonly StackPanel _body = new() { Spacing = 12, MinWidth = 440 };
    private readonly BreadcrumbBar _trail = new();
    private ContentDialog? _dialog;
    private long? _id;
    private Watch? _watch;
    private MoveShown? _shown;
    private bool _closed;
    private bool _undoing;
    private bool _seenUndo;
    private bool _sending;

    private MoveDialog(Window window, InfoBar? bar)
    {
        _window = window;
        _bar = bar;
        AutomationProperties.SetAutomationId(_trail, "move-trail");
        // a crumb chosen: the core shows that place (each crumb but the last)
        _trail.ItemClicked += (_, args) =>
        {
            if (_id is { } m && _shown is { } s && args.Index < s.trail.Count - 1) Act.Send(new Call.MoveGo { move = m, at = s.trail[args.Index].nodeId });
        };
        // the crumbs are found by the pass's driver by their names (an item's own AutomationId is not reachable otherwise)
        _trail.LayoutUpdated += (_, _) =>
        {
            foreach (var item in Descendants(_trail).OfType<BreadcrumbBarItem>())
            {
                if (item.Content is string name) AutomationProperties.SetAutomationId(item, $"crumb:{name}");
            }
        };
    }

    /// <summary>Opens the move (<paramref name="copy"/> false) or copy sheet of <paramref name="node"/> over
    /// <paramref name="window"/>, saying what it did in <paramref name="bar"/>.</summary>
    public static void Open(Window window, NodeAt node, bool copy, InfoBar? bar) => new MoveDialog(window, bar).Show(node, copy);

    private async void Show(NodeAt node, bool copy)
    {
        if (Dialogs.Open) return;
        var dialog = new ContentDialog
        {
            Title = node.name,
            Content = new ScrollViewer { Content = _body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(0, 0, 16, 0) },
            PrimaryButtonText = copy ? Words.CopySubmit() : Words.MoveSubmit(),
            IsPrimaryButtonEnabled = false,
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Primary,
        };
        AutomationProperties.SetAutomationId(dialog, copy ? "copy" : "move");
        // the primary button sends and keeps the dialog open until the core answered; done, it closes it
        dialog.PrimaryButtonClick += (_, args) =>
        {
            args.Cancel = true;
            Send(new Call.MoveHere { move = _id ?? 0 }, "Move.Here");
        };
        _dialog = dialog;
        Draw();
        Watch(node, copy);
        await Dialogs.Show(_window, dialog);
        _closed = true;
        // a move's Undo standing keeps the sheet in the core until its offer ends
        if (!_undoing) End();
    }

    private async void Watch(NodeAt node, bool copy)
    {
        Answer answer;
        try
        {
            (answer, _) = await Core.CallAsync(new Call.MoveOpen { node = node, copy = copy, deletedName = Words.PotDeletedPerson() });
        }
        catch (Exception e)
        {
            Log.Write($"the move sheet did not open in the core ({e.GetType().Name})");
            return;
        }
        if (answer is not Answer.MoveOpened { move: var m })
        {
            Log.Write($"the move sheet did not open in the core ({answer.GetType().Name})");
            return;
        }
        if (_closed)
        {
            Act.Send(new Call.MoveClose { move = m });
            return;
        }
        _id = m;
        _watch = Core.Watch(new Watched.Move { move = m }, update =>
        {
            if (update is not Update.Move u) return;
            _shown = u.shown;
            if (u.shown is { } s)
            {
                Log.Write($"move sheet{(s.copying ? " (copy)" : "")}: at {s.trail.LastOrDefault()?.nodeId}, {s.places.Count} places, here {s.here}, problem {s.problem?.msg}, " +
                    $"no room {s.noRoom is not null}, busy {s.busy}, confirming {s.confirming}, done {s.done is not null}, undo {s.undo is not null}");
            }
            // a move's undo: the InfoBar's Undo goes when the core's offer ends (once a state showed it), the sheet with it
            if (u.shown?.undo is not null) _seenUndo = true;
            else if (_seenUndo)
            {
                _seenUndo = false;
                if (_undoing)
                {
                    _undoing = false;
                    Log.Write("move undo offer ended");
                    if (_bar is { } bar)
                    {
                        bar.ActionButton = null;
                        bar.IsOpen = false;
                    }
                    if (_closed) End();
                }
            }
            if (!_closed) Draw();
        }, e => Log.Write($"a move sheet update: {e.Message}"));
    }

    /// <summary>The core's sheet closed and its watch ended.</summary>
    private void End()
    {
        _watch?.Dispose();
        _watch = null;
        if (_id is { } m) Act.Send(new Call.MoveClose { move = m });
        _id = null;
    }

    private void Draw()
    {
        _body.Children.Clear();
        if (_dialog is not { } dialog) return;
        if (_shown is not { loaded: true } s || _id is not { } m)
        {
            _body.Children.Add(new ProgressRing { IsActive = true });
            return;
        }
        dialog.Title = Said.Notice(s.title);
        dialog.PrimaryButtonText = Said.Key(s.submit);
        dialog.IsPrimaryButtonEnabled = s.canChoose && !_sending;
        _body.Children.Add(Text(Said.Key(s.hint), "move-hint", "TextFillColorSecondaryBrush"));
        if (s.copying)
        {
            _body.Children.Add(Lines(Words.CopyLosses(), s.losses.Select(Said.Notice), "move-losses"));
            if (s.leftOut.Count > 0) _body.Children.Add(Lines(Words.CopyLeftOut(), s.leftOut.Select(Said.Notice), "move-left-out"));
        }
        _trail.ItemsSource = s.trail.Select(c => Said.Piece(c.name)).ToList();
        _body.Children.Add(_trail);
        if (s.empty is { } empty) _body.Children.Add(Text(Said.Notice(empty), "move-empty", "TextFillColorSecondaryBrush"));
        foreach (var place in s.places)
        {
            var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
            row.Children.Add(HomeView.Glyph(HomeView.KindGlyph(place.kind)));
            var text = new StackPanel();
            text.Children.Add(new TextBlock { Text = place.name, TextTrimming = TextTrimming.CharacterEllipsis });
            if (place.blocked is { } word)
            {
                text.Children.Add(new TextBlock { Text = Said.Key(word), Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"] });
            }
            row.Children.Add(text);
            var go = new Button
            {
                Content = row,
                HorizontalAlignment = HorizontalAlignment.Stretch,
                HorizontalContentAlignment = HorizontalAlignment.Left,
                IsEnabled = place.blocked is null && !s.busy,
                Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"],
            };
            AutomationProperties.SetAutomationId(go, $"place:{place.name}");
            AutomationProperties.SetName(go, place.name);
            var at = place.nodeId;
            go.Click += (_, _) => Act.Send(new Call.MoveGo { move = m, at = at });
            _body.Children.Add(go);
        }
        if ((s.problem ?? s.noRoom) is { } danger)
        {
            _body.Children.Add(Text(Said.Notice(danger), "move-problem", "SystemFillColorCriticalBrush"));
        }
        else if (s.here is { } here)
        {
            _body.Children.Add(Text(Said.Key(here), "move-here-said", "TextFillColorSecondaryBrush"));
        }
        if (s.confirming) _body.Children.Add(AskConfirm(s, m));
    }

    /// <summary>A move that ends a guest's folder, asked in place (WinUI shows one ContentDialog at a time).</summary>
    private UIElement AskConfirm(MoveShown s, long m)
    {
        var panel = new StackPanel { Spacing = 8 };
        panel.Children.Add(new TextBlock { Text = Words.MoveIdentityTitle(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] });
        panel.Children.Add(new TextBlock { Text = Said.Notice(s.confirmQuestion), TextWrapping = TextWrapping.Wrap });
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var accept = new Button { Content = Words.MoveIdentityAccept(), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetAutomationId(accept, "move-confirm");
        accept.Click += (_, _) => Send(new Call.MoveConfirm { move = m }, "Move.Confirm");
        var cancel = new Button { Content = Words.TrashCancel() };
        AutomationProperties.SetAutomationId(cancel, "move-confirm-cancel");
        cancel.Click += (_, _) => Act.Send(new Call.MoveCancelConfirm { move = m });
        buttons.Children.Add(accept);
        buttons.Children.Add(cancel);
        panel.Children.Add(buttons);
        return panel;
    }

    /// <summary>A write sent: done, the dialog closes and the InfoBar says it (a move's with Undo); else the state says why.</summary>
    private async void Send(Call call, string what)
    {
        if (_id is not { } m || _sending) return;
        _sending = true;
        Answer? answered;
        try
        {
            answered = (await Core.CallAsync(call)).Answer;
        }
        catch (Exception e)
        {
            Log.Write($"{what} failed: {e.GetType().Name}");
            answered = null;
        }
        _sending = false;
        switch (answered)
        {
            case Answer.MoveSent { done: { } done, undoable: var undoable }:
                Log.Write($"{what}: {done.msg}, undo offered: {undoable}");
                Offer(Said.Notice(done), undoable, m);
                _dialog?.Hide();
                break;
            case Answer.MoveSent { confirming: true }:
                Log.Write($"{what}: the guest folder's confirm asked");
                break;
            case Answer.MoveSent { problem: var p }:
                Log.Write($"{what}: not sent ({p?.msg})");
                break;
            default:
                Log.Write($"{what} answered {answered?.GetType().Name}");
                break;
        }
        if (!_closed) Draw();
    }

    /// <summary>What the move or copy said, with Undo where the core offers it now: the InfoBar's action until the offer ends.</summary>
    private void Offer(string text, bool undoable, long m)
    {
        if (_bar is not { } bar) return;
        bar.Severity = InfoBarSeverity.Success;
        bar.Message = text;
        if (undoable)
        {
            var undo = new Button { Content = Words.TrashUndo() };
            AutomationProperties.SetAutomationId(undo, "undo");
            undo.Click += async (_, _) =>
            {
                bar.ActionButton = null;
                bar.IsOpen = false;
                _undoing = false;
                Answer? answered;
                try
                {
                    answered = (await Core.CallAsync(new Call.MoveUndo { move = m })).Answer;
                }
                catch (Exception e)
                {
                    Log.Write($"Move.Undo failed: {e.GetType().Name}");
                    answered = null;
                }
                Log.Write($"move undo: {(answered is Answer.Said { notice: { } n } ? n.msg : answered?.GetType().Name)}");
                if (answered is Answer.Said { notice: { } said })
                {
                    bar.Severity = InfoBarSeverity.Error;
                    bar.Message = Said.Notice(said);
                    bar.IsOpen = true;
                }
                if (_closed) End();
            };
            bar.ActionButton = undo;
            _undoing = true;
        }
        else bar.ActionButton = null;
        bar.IsOpen = true;
    }

    private static TextBlock Text(string text, string id, string brush)
    {
        var t = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources[brush] };
        AutomationProperties.SetAutomationId(t, id);
        return t;
    }

    /// <summary>A heading and its lines, as a copy says what it leaves behind or out.</summary>
    private static StackPanel Lines(string heading, IEnumerable<string> items, string id)
    {
        var panel = new StackPanel { Spacing = 2 };
        AutomationProperties.SetAutomationId(panel, id);
        panel.Children.Add(new TextBlock { Text = heading, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], TextWrapping = TextWrapping.Wrap });
        foreach (var item in items)
        {
            panel.Children.Add(new TextBlock { Text = "• " + item, TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"] });
        }
        return panel;
    }

    private static IEnumerable<DependencyObject> Descendants(DependencyObject root)
    {
        var count = VisualTreeHelper.GetChildrenCount(root);
        for (var i = 0; i < count; i++)
        {
            var child = VisualTreeHelper.GetChild(root, i);
            yield return child;
            foreach (var d in Descendants(child)) yield return d;
        }
    }
}
