// A Memory's or a chapter's Music view (ADR 0142 decisions 6–11; the web's MemoryMusic tab, Linux's music_view.rs), Fluent's
// way: beside the photos behind the page's SelectorBar, "Add music" on top where whoever looks may add (an open picker
// filtered to audio files: Music.Add, the core refuses and says what is too large, no audio or unreadable here), then the
// tracks as a ListView — the title (an untitled one by its number), its length, the one playing marked, one that did not play
// on this device this session marked "doesn't play on this device" — a click plays it first and the rest shuffled
// (Music.Play), and each row's menu (a MenuFlyout from its More button and its context menu): Rename, Download, Remove (the
// gallery's trash with its Undo in the shell's InfoBar), Report (an ITEM, the report sheet). Offline it is read-only.
//
// Every state is the core's MusicModel (Music.Open, Watched.Music); every action one call.
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Storage.Pickers;

namespace Veyq;

internal sealed class MusicView : IDisposable
{
    /// <summary>The file types "Add music" offers: the audio containers the core sniffs (Sniff.stream).</summary>
    private static readonly string[] AudioTypes = [".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".oga", ".opus"];

    private readonly Window _window;
    private readonly Action<bool, string> _say;
    private readonly Action<long> _saving;
    /// <summary>The view, in the page's scroll under its head and its SelectorBar.</summary>
    private readonly StackPanel _root = new() { Spacing = 8 };
    private readonly Button _add;
    private readonly ProgressRing _checking = new() { IsActive = false, Width = 20, Height = 20, Visibility = Visibility.Collapsed };
    private readonly ListView _list = new() { SelectionMode = ListViewSelectionMode.None, IsItemClickEnabled = true };
    private readonly TextBlock _empty = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed, Margin = new Thickness(0, 12, 0, 0) };
    private readonly ProgressRing _loading = new() { IsActive = true, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(24) };
    private long? _id;
    private Watch? _watch;
    private MusicShown? _shown;
    private string? _saidShown;
    private bool _closed;

    public UIElement View => _root;

    /// <summary>Gallery <paramref name="gallery"/>'s Music view; what its actions do said through <paramref name="say"/>, a
    /// download followed by <paramref name="saving"/>.</summary>
    public MusicView(Window window, long gallery, Action<bool, string> say, Action<long> saving)
    {
        _window = window;
        _say = say;
        _saving = saving;
        _add = new Button
        {
            Content = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                Spacing = 8,
                Children = { new FontIcon { Glyph = "", FontSize = 16 }, new TextBlock { Text = Words.MusicAdd() } },
            },
        };
        AutomationProperties.SetAutomationId(_add, "music-add");
        AutomationProperties.SetName(_add, Words.MusicAdd());
        _add.Click += (_, _) => Add();
        var top = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
        top.Children.Add(_add);
        AutomationProperties.SetAutomationId(_checking, "music-checking");
        top.Children.Add(_checking);
        _root.Children.Add(top);
        AutomationProperties.SetAutomationId(_list, "music-tracks");
        AutomationProperties.SetName(_list, Words.MusicViewMusic());
        _list.ItemClick += (_, e) =>
        {
            var row = e.ClickedItem as ListViewItem ?? (e.ClickedItem as FrameworkElement)?.Parent as ListViewItem;
            if (row?.Tag is MusicTrackShown t && !t.blocked && _id is { } music)
            {
                Log.Write($"music view: play {t.mediaId}");
                Act.Send(new Call.MusicPlay { music = music, mediaId = t.mediaId });
            }
        };
        _root.Children.Add(_loading);
        AutomationProperties.SetAutomationId(_empty, "music-empty");
        _root.Children.Add(_empty);
        _root.Children.Add(_list);
        AutomationProperties.SetAutomationId(_root, "music-view");
        Open(gallery);
    }

    private async void Open(long gallery)
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.MusicOpen { gallery = gallery });
            if (answer is not Answer.MusicOpened { music: var music })
            {
                Log.Write($"the Music view did not open in the core ({answer.GetType().Name})");
                return;
            }
            if (_closed)
            {
                Act.Send(new Call.MusicClose { music = music });
                return;
            }
            _id = music;
            _watch = Core.Watch(new Watched.Music { music = music }, Updated, e => Log.Write($"a Music view update: {e.Message}"));
        }
        catch (Exception e)
        {
            Log.Write($"the Music view did not open ({e.GetType().Name})");
        }
    }

    private void Updated(Update update)
    {
        if (_closed || update is not Update.Music m) return;
        // the model sends its state again as the player's position moves: only a change is drawn (a row redrawn under an
        // open menu would close it)
        if (Same(_shown, m.shown)) return;
        _shown = m.shown;
        if (m.shown is { } s)
        {
            Log.Write($"music shown: {s.tracks?.Count ?? -1} tracks ({string.Join(", ", (s.tracks ?? []).Select(t => $"{t.mediaId}{(t.playing ? " playing" : "")}{(t.unplayable ? " unplayable" : "")}"))}), add {s.canAdd}, rename {s.canRename}, download {s.canDownload}, report {s.canReport}, offline {s.offline}, checking {s.checking}, said {s.said?.msg ?? "-"}");
        }
        Draw();
    }

    /// <summary>Whether two states draw alike: every field, the tracks row by row.</summary>
    private static bool Same(MusicShown? a, MusicShown? b) =>
        ReferenceEquals(a, b) || a is not null && b is not null
        && (a.tracks is null ? b.tracks is null : b.tracks is not null && a.tracks.SequenceEqual(b.tracks))
        && a with { tracks = null } == b with { tracks = null };

    private void Draw()
    {
        if (_shown is not { } s) return;
        var adds = s.canAdd && !s.offline;
        _add.Visibility = adds ? Visibility.Visible : Visibility.Collapsed;
        _add.IsEnabled = !s.checking;
        _checking.IsActive = s.checking;
        _checking.Visibility = s.checking ? Visibility.Visible : Visibility.Collapsed;
        _loading.Visibility = s.tracks is null ? Visibility.Visible : Visibility.Collapsed;
        _empty.Visibility = s.tracks is { Count: 0 } ? Visibility.Visible : Visibility.Collapsed;
        _empty.Text = Words.MusicEmptyHint();
        _list.Items.Clear();
        foreach (var t in s.tracks ?? []) _list.Items.Add(Row(t, s));
        if (s.said is null) _saidShown = null;
        else if (_id is { } music)
        {
            var said = Said.Notice(s.said);
            if (said != _saidShown)
            {
                _saidShown = said;
                _say(false, said);
            }
            Act.Send(new Call.MusicSeen { music = music });
        }
    }

    private static string Title(MusicTrackShown t) => t.title ?? (t.untitled is { } n ? Said.Notice(n) : t.fileName ?? "");

    private FrameworkElement Row(MusicTrackShown t, MusicShown s)
    {
        var title = Title(t);
        var row = new Grid { ColumnSpacing = 12, Padding = new Thickness(0, 6, 0, 6) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(24) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        // the one playing: the speaker, as Media Player marks its current item; a blocked one: the blocked glyph
        var mark = new FontIcon
        {
            Glyph = t.blocked ? "" : t.playing ? "" : "",
            FontSize = 16,
            Foreground = t.playing ? (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["AccentTextFillColorPrimaryBrush"] : null,
            VerticalAlignment = VerticalAlignment.Center,
        };
        if (mark.Foreground is null) mark.ClearValue(IconElement.ForegroundProperty);
        row.Children.Add(mark);
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        var name = new TextBlock
        {
            Text = title,
            TextTrimming = TextTrimming.CharacterEllipsis,
            Style = (Style)Application.Current.Resources[t.playing ? "BodyStrongTextBlockStyle" : "BodyTextBlockStyle"],
        };
        text.Children.Add(name);
        if (t.unplayable)
        {
            var unplayable = new TextBlock
            {
                Text = Words.MusicTrackUnplayable(),
                Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
                Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
            };
            AutomationProperties.SetAutomationId(unplayable, $"music-unplayable:{t.mediaId}");
            text.Children.Add(unplayable);
        }
        Grid.SetColumn(text, 1);
        row.Children.Add(text);
        var length = new TextBlock
        {
            Text = t.length ?? "",
            VerticalAlignment = VerticalAlignment.Center,
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
        };
        Grid.SetColumn(length, 2);
        row.Children.Add(length);
        var menu = Menu(t, s, title);
        if (menu.Items.Count > 0)
        {
            var more = new Button
            {
                Content = new FontIcon { Glyph = "", FontSize = 16 },
                Flyout = menu,
                Background = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Transparent),
                BorderThickness = new Thickness(0),
                VerticalAlignment = VerticalAlignment.Center,
            };
            AutomationProperties.SetAutomationId(more, $"music-more:{t.mediaId}");
            AutomationProperties.SetName(more, Words.MusicActions(title));
            ToolTipService.SetToolTip(more, Words.MusicActions(title));
            Grid.SetColumn(more, 3);
            row.Children.Add(more);
            row.ContextFlyout = Menu(t, s, title);
        }
        // the row's container carries its name and id: UI Automation invokes a ListViewItem, not what it holds
        var item = new ListViewItem { Content = row, Tag = t, HorizontalContentAlignment = HorizontalAlignment.Stretch };
        AutomationProperties.SetAutomationId(item, $"music-track:{t.mediaId}");
        AutomationProperties.SetName(item, t.blocked ? title : Words.MusicPlayFrom(title));
        return item;
    }

    private MenuFlyout Menu(MusicTrackShown t, MusicShown s, string title)
    {
        var menu = new MenuFlyout();
        void Item(string text, string glyph, string id, Action act)
        {
            var item = new MenuFlyoutItem { Text = text, Icon = new FontIcon { Glyph = glyph } };
            AutomationProperties.SetAutomationId(item, $"{id}:{t.mediaId}");
            item.Click += (_, _) => act();
            menu.Items.Add(item);
        }
        if (s.canRename && !s.offline) Item(Words.MusicRename(), "", "music-rename", () => Rename(t, title));
        if (s.canDownload && t.fileName is not null) Item(Words.MusicDownload(), "", "music-download", () => Download(t));
        if (t.canRemove && !s.offline) Item(Words.MusicRemove(), "", "music-remove", () => Remove(t));
        if (s.canReport && !s.offline)
        {
            if (menu.Items.Count > 0) menu.Items.Add(new MenuFlyoutSeparator());
            Item(Words.ReportAction(), "", "music-report", () => Report(t));
        }
        return menu;
    }

    /// <summary>Rename: a dialog with the track's own name (empty: named by its file again, the hint says so).</summary>
    private async void Rename(MusicTrackShown t, string title)
    {
        if (_id is not { } music) return;
        var box = new TextBox
        {
            Header = Words.MusicNameLabel(),
            Text = t.ownName ?? "",
            PlaceholderText = title,
            Description = Words.MusicNameHint(),
            MinWidth = 320,
        };
        AutomationProperties.SetAutomationId(box, "dialog-name");
        box.SelectAll();
        box.Loaded += (_, _) => box.Focus(FocusState.Programmatic);
        var dialog = new ContentDialog
        {
            Title = Words.MusicRenameTitle(),
            Content = box,
            PrimaryButtonText = Words.MusicRename(),
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Primary,
        };
        AutomationProperties.SetAutomationId(dialog, "music-rename-dialog");
        if (await Dialogs.Show(_window, dialog) != ContentDialogResult.Primary || _closed) return;
        var name = box.Text.Trim();
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.MusicRename { music = music, mediaId = t.mediaId, name = name.Length == 0 ? null : name });
            Log.Write(answer is Answer.Said { notice: { } n } ? $"Music.Rename: {n.msg}" : $"Music.Rename: {answer.GetType().Name}");
        }
        catch (Exception e)
        {
            Log.Write($"Music.Rename failed ({e.GetType().Name})");
        }
    }

    /// <summary>Download: the save picker proposes the track's file name; the core writes it (Music.Download) with the
    /// shell's progress and Cancel.</summary>
    private async void Download(MusicTrackShown t)
    {
        if (_id is not { } music || t.fileName is not { } file) return;
        try
        {
            var picker = new FileSavePicker { SuggestedStartLocation = PickerLocationId.MusicLibrary, SuggestedFileName = Path.GetFileNameWithoutExtension(file) };
            var extension = Path.GetExtension(file);
            picker.FileTypeChoices.Add(Words.MusicDownload(), [extension.Length > 1 ? extension : ".bin"]);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var chosen = await picker.PickSaveFileAsync();
            if (chosen is null) return;
            var (answer, _) = await Core.CallAsync(new Call.MusicDownload { music = music, mediaId = t.mediaId, path = chosen.Path });
            if (answer is Answer.Saving { save: var save })
            {
                Log.Write($"saving a track (save {save})");
                _saving(save);
                return;
            }
            Log.Write($"Music.Download answered {answer.GetType().Name}");
            _say(false, Words.MusicDownloadFailed());
        }
        catch (Exception e)
        {
            Log.Write($"the track was not saved: {e.GetType().Name}");
            _say(false, Words.MusicDownloadFailed());
        }
    }

    /// <summary>Remove: the gallery's trash (its Undo in the shell's InfoBar while the gallery offers it).</summary>
    private async void Remove(MusicTrackShown t)
    {
        if (_id is not { } music) return;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.MusicRemove { music = music, mediaId = t.mediaId });
            if (answer is Answer.Trashed { undoable: var undoable }) Log.Write($"music remove: Trashed, undo {undoable}");
            else if (answer is Answer.Said { notice: { } n }) _say(false, Said.Notice(n));
            else Log.Write($"Music.Remove answered {answer.GetType().Name}");
        }
        catch (Exception e)
        {
            Log.Write($"Music.Remove failed ({e.GetType().Name})");
        }
    }

    private void Report(MusicTrackShown t)
    {
        if (_id is { } music) ReportDialog.Open(_window, new Call.MusicReport { music = music, mediaId = t.mediaId });
    }

    /// <summary>"Add music": the open picker for audio files (several), their paths to the core (Music.Add), what it
    /// refused said.</summary>
    private async void Add()
    {
        if (_id is not { } music) return;
        try
        {
            var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.MusicLibrary, ViewMode = PickerViewMode.List };
            foreach (var type in AudioTypes) picker.FileTypeFilter.Add(type);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var picked = await picker.PickMultipleFilesAsync();
            if (picked is null || picked.Count == 0 || _closed) return;
            Log.Write($"music add: {picked.Count} files");
            var (answer, _) = await Core.CallAsync(new Call.MusicAdd { music = music, paths = picked.Select(f => f.Path).ToList() });
            if (answer is Answer.MusicAdded { said: var said })
            {
                Log.Write($"music added: {said.Count} refused ({string.Join(", ", said.Select(n => n.msg))})");
                foreach (var n in said) _say(false, Said.Notice(n));
            }
            else Log.Write($"Music.Add answered {answer.GetType().Name}");
        }
        catch (Exception e)
        {
            Log.Write($"add music failed: {e.GetType().Name}: {e.Message}");
        }
    }

    public void Dispose()
    {
        if (_closed) return;
        _closed = true;
        _watch?.Dispose();
        if (_id is { } music) Act.Send(new Call.MusicClose { music = music });
    }
}
