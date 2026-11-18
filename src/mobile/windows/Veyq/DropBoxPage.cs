// A drop box's page for a member (Depot's DropBoxDetail.vue; ADR 0123's Consequences, the Windows app's Depot whole; Linux's drop_box.rs), Fluent's
// way: the hint, then each guest's folder — its name as a heading, how many files it holds, a More button whose MenuFlyout
// holds Move out… and Delete — over a ListView of its files, each opening the file's page; nobody's yet, the core's words.
// A folder's delete is asked in a ContentDialog (one at a time, Dialogs.Show), then trashed with Undo in the shell's
// InfoBar; Move out opens the move dialog on the folder (MoveDialog, which asks before it leaves the drop box).
//
// Everything is the core's DropBoxPage (DropBox.Open, Watched.DropBox): the folders are read anew on each revision of the
// shell's lists, and while the page stands its live poll (Watched.DropBoxLive) reads them anew when the scope's tree moved,
// so a guest's new folder shows without a Refresh; a delete is done only where it was asked. The page starts with the drop
// box's head, its cover (NodeHeader.Hero), as the Pot's and the Memory's pages.
using System;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

internal sealed class DropBoxPage : IDisposable
{
    private readonly Window _window;
    private readonly InfoBar _bar;
    private readonly StackPanel _content = new() { Spacing = 4, Padding = new Thickness(12, 0, 12, 24) };
    /// <summary>Where the head stands, above the folders (drawn anew on each state).</summary>
    private readonly Border _heroSlot = new() { Padding = new Thickness(12, 0, 12, 0) };
    private UIElement? _hero;
    private readonly ScrollViewer _root;
    private long? _id;
    private Watch? _watch;
    private Watch? _live;
    private DropBoxShown? _shown;
    private bool _closed;
    /// <summary>The folder whose delete is asked on screen: a later state with the same question shows no second dialog.</summary>
    private string? _asked;

    public NodeAt Node { get; }

    public UIElement Page => _root;

    public DropBoxPage(Window window, NodeAt node, InfoBar bar)
    {
        _window = window;
        _bar = bar;
        Node = node;
        var column = new StackPanel();
        column.Children.Add(_heroSlot);
        column.Children.Add(_content);
        _root = new ScrollViewer { Content = column, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
        AutomationProperties.SetAutomationId(_root, "dropbox");
        Draw();
        Open();
    }

    private async void Open()
    {
        Answer answer;
        try
        {
            (answer, _) = await Core.CallAsync(new Call.DropBoxOpen { node = Node });
        }
        catch (Exception e)
        {
            Log.Write($"the drop box did not open in the core ({e.GetType().Name})");
            return;
        }
        if (answer is not Answer.DropBoxOpened { box: var box })
        {
            Log.Write($"the drop box did not open in the core ({answer.GetType().Name})");
            return;
        }
        if (_closed)
        {
            Act.Send(new Call.DropBoxClose { box = box });
            return;
        }
        _id = box;
        _live = Core.Watch(new Watched.DropBoxLive { box = box }, _ => { }, e => Log.Write($"a drop box's poll: {e.Message}"));
        _watch = Core.Watch(new Watched.DropBox { box = box }, update =>
        {
            if (_closed || update is not Update.DropBox u) return;
            _shown = u.shown;
            if (u.shown is { } s)
            {
                Log.Write($"drop box: {s.folders?.Count ?? 0} folders [{string.Join(", ", (s.folders ?? []).Select(f => $"{f.label} ({f.files.Count})"))}], failed {s.failed}, deleting {s.deleting is not null}");
            }
            Draw();
            AskDelete();
        }, e => Log.Write($"a drop box update: {e.Message}"));
    }

    /// <summary>The node's head (NodeHeader.Hero) placed first in the page's scroll, as the Pot page places it.</summary>
    public void Hero(UIElement? hero)
    {
        if (ReferenceEquals(hero, _hero)) return;
        _heroSlot.Child = null;
        _hero = hero;
        if (hero is FrameworkElement { Parent: Border other }) other.Child = null;
        if (hero is FrameworkElement { Parent: Panel panel }) panel.Children.Remove(hero);
        _heroSlot.Child = hero;
    }

    public void Dispose()
    {
        _closed = true;
        _live?.Dispose();
        _live = null;
        _watch?.Dispose();
        _watch = null;
        if (_id is { } box) Act.Send(new Call.DropBoxClose { box = box });
        _id = null;
    }

    private void Draw()
    {
        _content.Children.Clear();
        if (_shown is not { folders: { } folders } s)
        {
            _content.Children.Add(new ProgressRing { IsActive = true, Margin = new Thickness(0, 24, 0, 0) });
            return;
        }
        if (folders.Count == 0)
        {
            var empty = new TextBlock { Text = Said.Key(s.empty), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(12, 24, 12, 0) };
            AutomationProperties.SetAutomationId(empty, "dropbox-empty");
            _content.Children.Add(empty);
            return;
        }
        var hint = new TextBlock
        {
            Text = Said.Key(s.hint), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(12, 12, 12, 4),
            Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
        };
        _content.Children.Add(hint);
        foreach (var f in folders) _content.Children.Add(Folder(f));
        if (s.failed) _content.Children.Add(new TextBlock { Text = Words.ShareFailed(), Margin = new Thickness(12, 8, 12, 0) });
    }

    /// <summary>One guest's folder: its heading with the count and the More button, then its files.</summary>
    private UIElement Folder(ShownGuestFolder f)
    {
        var panel = new StackPanel { Spacing = 2, Margin = new Thickness(0, 12, 0, 0) };
        var head = new Grid { ColumnSpacing = 12, Margin = new Thickness(12, 0, 4, 0) };
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var names = new StackPanel();
        var title = new TextBlock { Text = f.label, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
        AutomationProperties.SetHeadingLevel(title, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        AutomationProperties.SetAutomationId(title, $"guest:{f.label}");
        names.Children.Add(title);
        names.Children.Add(new TextBlock { Text = Said.Notice(f.count), Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"] });
        head.Children.Add(names);
        var menu = new MenuFlyout();
        var moveOut = new MenuFlyoutItem { Text = Said.Notice(f.moveOut), Icon = new SymbolIcon(Symbol.MoveToFolder) };
        AutomationProperties.SetAutomationId(moveOut, $"move-out:{f.label}");
        moveOut.Click += (_, _) =>
        {
            if (_id is { } box) MoveDialog.OpenWith(_window, f.label, new Call.DropBoxMoveOut { box = box, folder = f.id }, _bar);
        };
        menu.Items.Add(moveOut);
        var delete = new MenuFlyoutItem { Text = Said.Notice(f.delete), Icon = new SymbolIcon(Symbol.Delete) };
        AutomationProperties.SetAutomationId(delete, $"delete:{f.label}");
        delete.Click += async (_, _) =>
        {
            if (_id is not { } box) return;
            var (answer, _) = await Core.CallAsync(new Call.DropBoxAskDelete { box = box, folder = f.id });
            Log.Write($"DropBox.AskDelete: {answer.GetType().Name}");
        };
        menu.Items.Add(delete);
        var more = new Button { Content = new SymbolIcon(Symbol.More), Flyout = menu, VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetName(more, Words.NodeMore());
        AutomationProperties.SetAutomationId(more, $"more:{f.label}");
        Grid.SetColumn(more, 1);
        head.Children.Add(more);
        panel.Children.Add(head);
        var list = new ListView { SelectionMode = ListViewSelectionMode.None, IsItemClickEnabled = true };
        foreach (var file in f.files) list.Items.Add(HomeView.ObjectRow(file));
        list.ItemClick += (_, e) =>
        {
            var row = e.ClickedItem as ListViewItem ?? (e.ClickedItem as FrameworkElement)?.Parent as ListViewItem;
            if (row?.Tag is Action open) open();
        };
        panel.Children.Add(list);
        return panel;
    }

    /// <summary>The delete the core asks: a ContentDialog once per question; Delete trashes it (Undo in the InfoBar),
    /// Cancel or a dismissal declines it.</summary>
    private async void AskDelete()
    {
        if (_shown is not { } s || _id is not { } box) return;
        if (s.deleting is not { } folder)
        {
            _asked = null;
            return;
        }
        if (_asked == folder || s.deleteTitle is not { } title || s.deleteQuestion is not { } question) return;
        _asked = folder;
        var dialog = new ContentDialog
        {
            Title = Said.Notice(title),
            Content = new TextBlock { Text = Said.Notice(question), TextWrapping = TextWrapping.Wrap },
            PrimaryButtonText = Said.Key(s.deleteAccept),
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Close,
        };
        AutomationProperties.SetAutomationId(dialog, "dropbox-delete");
        var result = await Dialogs.Show(_window, dialog);
        if (_closed) return;
        if (result != ContentDialogResult.Primary)
        {
            Act.Send(new Call.DropBoxCancelAsk { box = box });
            return;
        }
        Answer answer;
        try
        {
            (answer, _) = await Core.CallAsync(new Call.DropBoxDelete { box = box, folder = folder });
        }
        catch (Exception e)
        {
            Log.Write($"DropBox.Delete failed ({e.GetType().Name})");
            return;
        }
        Log.Write($"drop box folder trashed: {(answer as Answer.Trashed)?.notice.msg ?? answer.GetType().Name}, undo offered: {(answer as Answer.Trashed)?.undoable}");
        if (answer is not Answer.Trashed { notice: var notice, undoable: var undoable }) return;
        _bar.Content = null;
        _bar.Severity = InfoBarSeverity.Informational;
        _bar.Message = Said.Notice(notice);
        if (undoable)
        {
            var undo = new Button { Content = Words.TrashUndo() };
            AutomationProperties.SetAutomationId(undo, "undo");
            undo.Click += async (_, _) =>
            {
                _bar.ActionButton = null;
                _bar.IsOpen = false;
                var (undone, _) = await Core.CallAsync(new Call.DropBoxUndo { box = box });
                Log.Write($"drop box undo: {undone.GetType().Name}");
            };
            _bar.ActionButton = undo;
        }
        else _bar.ActionButton = null;
        _bar.IsOpen = true;
    }
}
