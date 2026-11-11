// A space's trash (the header CR's Phase 2; core's TrashSheet, the web's SpaceTrashDialog.vue), Fluent's way: a
// ContentDialog from the switcher's menu, each deleted subtree's root a row with Restore and "Delete forever". WinUI
// shows one ContentDialog at a time, so "Delete forever?" is asked in place under its row, as is another name where a
// row's own was taken meanwhile. Every state is the core's (Trash.*, watched while the dialog is open).
using System;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

internal sealed class TrashDialog
{
    private readonly Window _window;
    private readonly StackPanel _rows = new() { Spacing = 8 };
    private long? _id;
    private Watch? _watch;
    private bool _closed;
    private TrashShown? _shown;
    /// <summary>The row whose "Delete forever" asks now (in place).</summary>
    private string? _purging;
    /// <summary>The new name's box, kept across the redraws so typing keeps its place.</summary>
    private TextBox? _retry;

    private TrashDialog(Window window) => _window = window;

    /// <summary>Opens the trash of <paramref name="scopeId"/> over <paramref name="window"/>; the core's sheet closes with it.</summary>
    public static void Open(Window window, string scopeId) => new TrashDialog(window).Show(scopeId);

    private async void Show(string scopeId)
    {
        if (Dialogs.Open) return;
        var dialog = new ContentDialog
        {
            Title = Words.TrashTitle(),
            CloseButtonText = Words.ShareDone(),
            DefaultButton = ContentDialogButton.Close,
            Content = new ScrollViewer { Content = _rows, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(0, 0, 16, 0), MinWidth = 480 },
        };
        AutomationProperties.SetAutomationId(dialog, "trash");
        Draw();
        Watch(scopeId);
        await Dialogs.Show(_window, dialog);
        _closed = true;
        _watch?.Dispose();
        if (_id is { } trash) Act.Send(new Call.TrashClose { trash = trash });
    }

    private async void Watch(string scopeId)
    {
        Answer answer;
        try
        {
            (answer, _) = await Core.CallAsync(new Call.TrashOpen { scopeId = scopeId });
        }
        catch (Exception e)
        {
            Log.Write($"the trash did not open in the core ({e.GetType().Name})");
            return;
        }
        if (answer is not Answer.TrashOpened { trash: var trash })
        {
            Log.Write($"the trash did not open in the core ({answer.GetType().Name})");
            return;
        }
        if (_closed)
        {
            Act.Send(new Call.TrashClose { trash = trash });
            return;
        }
        _id = trash;
        _watch = Core.Watch(new Watched.Trash { trash = trash }, update =>
        {
            if (_closed || update is not Update.Trash t) return;
            _shown = t.shown;
            if (t.shown is { } s) Log.Write($"trash: {s.rows?.Count ?? -1} rows, busy {s.busy is not null}, error {s.error}, renaming {s.renaming is not null}");
            Draw();
        }, e => Log.Write($"a trash update: {e.Message}"));
    }

    private void Draw()
    {
        _rows.Children.Clear();
        TextBlock Note(string text, string id)
        {
            var t = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap };
            AutomationProperties.SetAutomationId(t, id);
            return t;
        }
        if (_shown is not { } s || _id is not { } trash)
        {
            _rows.Children.Add(new ProgressRing { IsActive = true });
            return;
        }
        if (s.failed)
        {
            _rows.Children.Add(Note(Words.TrashLoadFailed(), "trash-failed"));
            return;
        }
        if (s.rows is not { } rows)
        {
            _rows.Children.Add(new ProgressRing { IsActive = true });
            return;
        }
        if (rows.Count == 0)
        {
            _rows.Children.Add(Note(Words.TrashEmpty(), "trash-empty"));
            return;
        }
        var hint = Note(Words.TrashHint(), "trash-hint");
        hint.Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        _rows.Children.Add(hint);
        foreach (var row in rows)
        {
            var busy = s.busy == row.id;
            var line = new Grid { ColumnSpacing = 12, Padding = new Thickness(12, 8, 12, 8), IsHitTestVisible = !busy, Opacity = busy ? 0.5 : 1 };
            line.Background = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"];
            line.CornerRadius = new CornerRadius(4);
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            line.Children.Add(HomeView.Glyph(HomeView.KindGlyph(row.kind)));
            var text = new StackPanel();
            text.Children.Add(new TextBlock { Text = row.name, TextTrimming = TextTrimming.CharacterEllipsis });
            var under = s.errorRow == row.id && s.error is { } e ? Said.Key(e)
                : Words.TrashRowDates(Said.InstantDay(row.deletedAt), Said.InstantDay(row.purgeAfter));
            var caption = new TextBlock { Text = under, TextWrapping = TextWrapping.Wrap, Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"] };
            AutomationProperties.SetAutomationId(caption, $"trash-row-said:{row.name}");
            text.Children.Add(caption);
            Grid.SetColumn(text, 1);
            line.Children.Add(text);
            var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4, VerticalAlignment = VerticalAlignment.Center };
            var id = row.id;
            if (row.restorable && s.renaming != row.id)
            {
                var restore = new Button { Content = Words.TrashRestore() };
                AutomationProperties.SetAutomationId(restore, $"trash-restore:{row.name}");
                restore.Click += (_, _) => Act.Send(new Call.TrashRestore { trash = trash, row = id });
                actions.Children.Add(restore);
            }
            var purge = new Button { Content = Words.TrashPurge() };
            AutomationProperties.SetAutomationId(purge, $"trash-purge:{row.name}");
            purge.Click += (_, _) =>
            {
                _purging = id;
                Draw();
            };
            actions.Children.Add(purge);
            Grid.SetColumn(actions, 2);
            line.Children.Add(actions);
            _rows.Children.Add(line);

            if (s.renaming == row.id) _rows.Children.Add(Retry(s, row, trash));
            if (_purging == row.id) _rows.Children.Add(AskPurge(row, trash));
        }
    }

    /// <summary>Another name for a row whose own was taken meanwhile, typed in place, and its restore under it.</summary>
    private UIElement Retry(TrashShown s, Row row, long trash)
    {
        if (_retry is null)
        {
            _retry = new TextBox { Header = Words.TrashRetryName() };
            AutomationProperties.SetAutomationId(_retry, "trash-retry-name");
            var box = _retry;
            box.TextChanged += (_, _) => Act.Send(new Call.TrashType { trash = trash, name = box.Text });
        }
        if (_retry.Text != s.retryName) _retry.Text = s.retryName;
        (_retry.Parent as Panel)?.Children.Remove(_retry);
        var panel = new StackPanel { Spacing = 8, Margin = new Thickness(36, 0, 0, 0) };
        panel.Children.Add(_retry);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var again = new Button { Content = Words.TrashRestore(), IsEnabled = row.retryable, Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetAutomationId(again, "trash-retry");
        var id = row.id;
        again.Click += (_, _) => Act.Send(new Call.TrashRetry { trash = trash, row = id });
        var cancel = new Button { Content = Words.TrashCancel() };
        cancel.Click += (_, _) => Act.Send(new Call.TrashCancelRename { trash = trash });
        buttons.Children.Add(again);
        buttons.Children.Add(cancel);
        panel.Children.Add(buttons);
        return panel;
    }

    /// <summary>"Delete forever?" under its row, naming it; confirmed, the row itself goes to the core (Trash.Purge).</summary>
    private UIElement AskPurge(Row row, long trash)
    {
        var panel = new StackPanel { Spacing = 8, Margin = new Thickness(36, 0, 0, 0) };
        var title = new TextBlock { Text = Words.TrashPurgeConfirmTitle(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
        panel.Children.Add(title);
        panel.Children.Add(new TextBlock { Text = Words.TrashPurgeConfirm(row.name), TextWrapping = TextWrapping.Wrap });
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var confirm = new Button { Content = Words.TrashPurge(), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetAutomationId(confirm, "trash-purge-confirm");
        var id = row.id;
        confirm.Click += (_, _) =>
        {
            _purging = null;
            Act.Send(new Call.TrashPurge { trash = trash, row = id });
        };
        var cancel = new Button { Content = Words.TrashCancel() };
        AutomationProperties.SetAutomationId(cancel, "trash-purge-cancel");
        cancel.Click += (_, _) =>
        {
            _purging = null;
            Draw();
        };
        buttons.Children.Add(confirm);
        buttons.Children.Add(cancel);
        panel.Children.Add(buttons);
        return panel;
    }
}
