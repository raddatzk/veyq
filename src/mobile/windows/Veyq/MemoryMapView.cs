// A Memory's or a chapter's Map view (ADR 0143 decision 8; the web's MemoryTracks tab, Apple's MemoryMapView, Linux's
// memory_map_view.rs), Fluent's way: behind the page's SelectorBar beside Photos and Music, the map (RasterMap: the photos'
// dots and the node's own GPX tracks, each in its colour, dashed by its activity, the selected one heavier) and under it
// "Add GPX track" where whoever looks may add (an open picker filtered to .gpx, several; each accepted file's name and
// activity asked in a dialog before anything is uploaded, Cancel uploading nothing), then the tracks as a ListView — the
// show/hide switch, its colour swatch with the activity's dash, the activity above the name, distance, duration, climb and
// descent; a click selects it (its map frames it), again unselects; each row's menu (a MenuFlyout from its More button
// and its context menu): Edit (name and activity), Download GPX (the save picker, the shell's progress), Remove (the
// gallery's trash with its Undo in the shell's InfoBar), Report (an ITEM, the report sheet). Without map services the list
// alone; offline read-only, no map (decision 9).
//
// Every state is the core's TrackList (Tracks.Open, Watched.Tracks; the map Watched.Map); every action one call.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Path = System.IO.Path;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Storage.Pickers;

namespace Veyq;

internal sealed class MemoryMapView : IDisposable
{
    private readonly Window _window;
    private readonly Action<bool, string> _say;
    private readonly Action<long> _saving;
    private readonly StackPanel _root = new() { Spacing = 8 };
    private readonly Border _mapSlot = new();
    private readonly Button _add;
    private readonly ProgressRing _checking = new() { IsActive = false, Width = 20, Height = 20, Visibility = Visibility.Collapsed };
    private readonly ListView _list = new() { SelectionMode = ListViewSelectionMode.None, IsItemClickEnabled = true };
    private readonly TextBlock _empty = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed, Margin = new Thickness(0, 12, 0, 0) };
    private readonly ProgressRing _loading = new() { IsActive = true, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(24) };
    private RasterMap? _map;
    private long? _id;
    private Watch? _watch;
    private TracksShown? _shown;
    private string? _saidShown;
    private bool _asking;
    private bool _closed;

    public UIElement View => _root;

    public MemoryMapView(Window window, long gallery, Action<bool, string> say, Action<long> saving)
    {
        _window = window;
        _say = say;
        _saving = saving;
        _root.Children.Add(_mapSlot);
        _add = new Button
        {
            Content = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                Spacing = 8,
                Children = { new FontIcon { Glyph = "", FontSize = 16 }, new TextBlock { Text = Words.TrackAdd() } },
            },
            Visibility = Visibility.Collapsed,
        };
        AutomationProperties.SetAutomationId(_add, "track-add");
        AutomationProperties.SetName(_add, Words.TrackAdd());
        _add.Click += (_, _) => Add();
        var top = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, Margin = new Thickness(0, 8, 0, 0) };
        top.Children.Add(_add);
        AutomationProperties.SetAutomationId(_checking, "track-checking");
        top.Children.Add(_checking);
        _root.Children.Add(top);
        AutomationProperties.SetAutomationId(_list, "track-list");
        AutomationProperties.SetName(_list, Words.TrackTitle());
        _list.ItemClick += (_, e) =>
        {
            var row = e.ClickedItem as ListViewItem ?? (e.ClickedItem as FrameworkElement)?.Parent as ListViewItem;
            if (row?.Tag is TrackShown t && _id is { } tracks)
            {
                Log.Write($"track view: select {(t.selected ? "none" : t.mediaId)}");
                Act.Send(new Call.TracksSelect { tracks = tracks, mediaId = t.selected ? null : t.mediaId });
            }
        };
        _root.Children.Add(_loading);
        AutomationProperties.SetAutomationId(_empty, "track-empty");
        _root.Children.Add(_empty);
        _root.Children.Add(_list);
        AutomationProperties.SetAutomationId(_root, "map-view");
        Open(gallery);
    }

    private async void Open(long gallery)
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.TracksOpen { gallery = gallery });
            if (answer is not Answer.TracksOpened { tracks: var tracks, map: var map })
            {
                Log.Write($"the Map view did not open in the core ({answer.GetType().Name})");
                return;
            }
            if (_closed)
            {
                Act.Send(new Call.TracksClose { tracks = tracks });
                return;
            }
            _id = tracks;
            Log.Write($"map view {tracks} open, map {map?.ToString() ?? "none"}");
            if (map is { } m)
            {
                _map = new RasterMap(_window, m, closesItself: false);
                _map.View.Height = 360;
                _mapSlot.Child = _map.View;
            }
            _watch = Core.Watch(new Watched.Tracks { tracks = tracks }, Updated, e => Log.Write($"a Map view update: {e.Message}"));
        }
        catch (Exception e)
        {
            Log.Write($"the Map view did not open ({e.GetType().Name})");
        }
    }

    private void Updated(Update update)
    {
        if (_closed || update is not Update.Tracks t) return;
        if (Same(_shown, t.shown)) return;
        _shown = t.shown;
        if (t.shown is { } s)
        {
            Log.Write($"tracks shown: {s.tracks?.Count ?? -1} tracks ({string.Join(", ", (s.tracks ?? []).Select(x => $"{x.mediaId} {x.status}{(x.shown ? "" : " hidden")}{(x.selected ? " selected" : "")} {x.activity ?? "-"} \"{x.name}\""))}), add {s.canAdd}, edit {s.canEdit}, offline {s.offline}, checking {s.checking}, asking {s.asking.Count}, said {s.said?.msg ?? "-"}");
        }
        Draw();
    }

    private static bool Same(TracksShown? a, TracksShown? b) =>
        ReferenceEquals(a, b) || a is not null && b is not null
        && (a.tracks is null ? b.tracks is null : b.tracks is not null && a.tracks.Count == b.tracks.Count
            && a.tracks.Zip(b.tracks).All(p => p.First with { facts = [], dash = null } == p.Second with { facts = [], dash = null }
                && p.First.facts.SequenceEqual(p.Second.facts) && (p.First.dash ?? []).SequenceEqual(p.Second.dash ?? [])))
        && a.asking.SequenceEqual(b.asking) && a.activities.SequenceEqual(b.activities)
        && a with { tracks = null, asking = [], activities = [] } == b with { tracks = null, asking = [], activities = [] };

    private void Draw()
    {
        if (_shown is not { } s) return;
        _add.Visibility = s.canAdd && !s.offline ? Visibility.Visible : Visibility.Collapsed;
        _add.IsEnabled = !s.checking;
        _checking.IsActive = s.checking;
        _checking.Visibility = s.checking ? Visibility.Visible : Visibility.Collapsed;
        _loading.Visibility = s.tracks is null ? Visibility.Visible : Visibility.Collapsed;
        _empty.Visibility = s.tracks is { Count: 0 } ? Visibility.Visible : Visibility.Collapsed;
        _empty.Text = s.offline || !s.canAdd ? Words.TrackEmpty() : Words.TrackEmptyHint();
        _list.Items.Clear();
        foreach (var t in s.tracks ?? []) _list.Items.Add(Row(t, s));
        if (s.said is null) _saidShown = null;
        else if (_id is { } tracks)
        {
            var said = Said.Notice(s.said);
            if (said != _saidShown)
            {
                _saidShown = said;
                _say(false, said);
            }
            Act.Send(new Call.TracksSeen { tracks = tracks });
        }
        if (s.asking.Count > 0 && !_asking) AskLabels(s);
    }

    /// <summary>A track's name as the list says it: its own, else its number's.</summary>
    private static string Name(TrackShown t) => t.name ?? Words.TrackUnnamed(t.number);

    /// <summary>An activity's words by its sealed token (core's TrackLabel.ACTIVITIES); null for none or one this app does
    /// not know (kept until edited).</summary>
    public static string? Activity(string? token) =>
        token is null ? null : Words.Say($"track_activity_{token.Replace('-', '_')}", []);

    /// <summary>A track's facts on one line: its distance in the culture's numbers, then the core's phrases.</summary>
    public static string Facts(IEnumerable<TrackFactShown> facts) =>
        string.Join(" · ", facts.Select(f => f.distance is { } d
            ? f.kilometres ? Words.TrackDistanceKm(d.ToString("N1", CultureInfo.CurrentCulture)) : Words.TrackDistanceM(d.ToString("N0", CultureInfo.CurrentCulture))
            : f.words is { } w ? Said.Notice(w) : "").Where(x => x.Length > 0));

    private FrameworkElement Row(TrackShown t, TracksShown s)
    {
        var name = Name(t);
        var row = new Grid { ColumnSpacing = 12, Padding = new Thickness(0, 6, 0, 6) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(28) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        // the show/hide switch, as the web's checkbox beside each track
        var show = new CheckBox { IsChecked = t.shown, MinWidth = 0, VerticalAlignment = VerticalAlignment.Center, IsEnabled = !t.blocked };
        AutomationProperties.SetAutomationId(show, $"track-show:{t.mediaId}");
        AutomationProperties.SetName(show, Words.TrackShow(name));
        ToolTipService.SetToolTip(show, Words.TrackShow(name));
        show.Click += (_, _) =>
        {
            if (_id is { } tracks) Act.Send(new Call.TracksShow { tracks = tracks, mediaId = t.mediaId, shown = show.IsChecked == true });
        };
        row.Children.Add(show);
        // its swatch: the colour with the activity's dash, as its line is drawn
        var swatch = new Line { X1 = 0, Y1 = 0, X2 = 28, Y2 = 0, Stroke = new SolidColorBrush(RasterMap.Argb(t.color)), StrokeThickness = 4, VerticalAlignment = VerticalAlignment.Center };
        if (t.dash is { Count: > 0 } dash) swatch.StrokeDashArray = RasterMap.Dash(dash, 4);
        Grid.SetColumn(swatch, 1);
        row.Children.Add(swatch);
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        if (Activity(t.activity) is { } activity)
        {
            text.Children.Add(new TextBlock { Text = activity, Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"], Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"] });
        }
        text.Children.Add(new TextBlock
        {
            Text = name,
            TextTrimming = TextTrimming.CharacterEllipsis,
            Style = (Style)Application.Current.Resources[t.selected ? "BodyStrongTextBlockStyle" : "BodyTextBlockStyle"],
        });
        var line = t.blocked ? Words.MemoryBlocked()
            : t.status == TrackStatus.Loading ? Words.TrackLoading()
            : t.status == TrackStatus.Failed ? Words.TrackUnreadable()
            : Facts(t.facts);
        if (line.Length > 0)
        {
            var facts = new TextBlock { Text = line, TextWrapping = TextWrapping.Wrap, Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"], Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"] };
            AutomationProperties.SetAutomationId(facts, $"track-facts:{t.mediaId}");
            text.Children.Add(facts);
        }
        Grid.SetColumn(text, 2);
        row.Children.Add(text);
        var menu = Menu(t, s, name);
        if (menu.Items.Count > 0)
        {
            var more = new Button
            {
                Content = new FontIcon { Glyph = "", FontSize = 16 },
                Flyout = menu,
                Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
                BorderThickness = new Thickness(0),
                VerticalAlignment = VerticalAlignment.Center,
            };
            AutomationProperties.SetAutomationId(more, $"track-more:{t.mediaId}");
            AutomationProperties.SetName(more, Words.MusicActions(name));
            ToolTipService.SetToolTip(more, Words.MusicActions(name));
            Grid.SetColumn(more, 3);
            row.Children.Add(more);
            row.ContextFlyout = Menu(t, s, name);
        }
        var item = new ListViewItem { Content = row, Tag = t, HorizontalContentAlignment = HorizontalAlignment.Stretch, IsSelected = false };
        if (t.selected) item.Background = (Brush)Application.Current.Resources["SubtleFillColorSecondaryBrush"];
        AutomationProperties.SetAutomationId(item, $"track:{t.mediaId}");
        AutomationProperties.SetName(item, name);
        return item;
    }

    private MenuFlyout Menu(TrackShown t, TracksShown s, string name)
    {
        var menu = new MenuFlyout();
        void Item(string text, string glyph, string id, Action act)
        {
            var item = new MenuFlyoutItem { Text = text, Icon = new FontIcon { Glyph = glyph } };
            AutomationProperties.SetAutomationId(item, $"{id}:{t.mediaId}");
            item.Click += (_, _) => act();
            menu.Items.Add(item);
        }
        if (s.canEdit && !s.offline && !t.blocked) Item(Words.TrackEdit(), "", "track-edit", () => Edit(t, s));
        if (s.canDownload && !t.blocked) Item(Words.TrackDownload(), "", "track-download", () => Download(t));
        if (t.canRemove && !s.offline) Item(Words.TrackRemove(), "", "track-remove", () => Remove(t));
        if (s.canReport && !s.offline)
        {
            if (menu.Items.Count > 0) menu.Items.Add(new MenuFlyoutSeparator());
            Item(Words.ReportAction(), "", "track-report", () => Report(t));
        }
        return menu;
    }

    /// <summary>An activity picker over <paramref name="tokens"/>, "No activity" first, <paramref name="chosen"/> selected.</summary>
    private static ComboBox ActivityPicker(IReadOnlyList<string> tokens, string? chosen, string id)
    {
        var box = new ComboBox { Header = Words.TrackActivityLabel(), MinWidth = 240 };
        AutomationProperties.SetAutomationId(box, id);
        box.Items.Add(new ComboBoxItem { Content = Words.TrackActivityNone(), Tag = null });
        foreach (var token in tokens)
        {
            var item = new ComboBoxItem { Content = Activity(token) ?? token, Tag = token };
            AutomationProperties.SetAutomationId(item, $"{id}:{token}");
            box.Items.Add(item);
        }
        box.SelectedIndex = chosen is null ? 0 : Math.Max(0, tokens.ToList().IndexOf(chosen) + 1);
        return box;
    }

    private static string? Picked(ComboBox box) => (box.SelectedItem as ComboBoxItem)?.Tag as string;

    /// <summary>Edit: the track's own name (empty: named by its file, the hint says so) and its activity.</summary>
    private async void Edit(TrackShown t, TracksShown s)
    {
        if (_id is not { } tracks) return;
        try
        {
            var name = new TextBox { Header = Words.TrackNameLabel(), Text = t.ownName ?? "", PlaceholderText = t.fileTrackName ?? Name(t), Description = Words.TrackNameHint(), MinWidth = 320 };
            AutomationProperties.SetAutomationId(name, "track-name");
            var activity = ActivityPicker(s.activities, t.activity, "track-activity");
            var panel = new StackPanel { Spacing = 12 };
            panel.Children.Add(name);
            panel.Children.Add(activity);
            var dialog = new ContentDialog
            {
                Title = Words.TrackEditTitle(),
                Content = panel,
                PrimaryButtonText = Words.EntrySave(),
                CloseButtonText = Words.EntryCancel(),
                DefaultButton = ContentDialogButton.Primary,
            };
            AutomationProperties.SetAutomationId(dialog, "track-edit-dialog");
            if (await Dialogs.Show(_window, dialog) != ContentDialogResult.Primary || _closed) return;
            var typed = name.Text.Trim();
            var label = new TrackLabelShown { name = typed.Length == 0 ? null : typed, activity = Picked(activity) };
            Log.Write($"track view: edit {t.mediaId} ({label.activity ?? "-"})");
            var (answer, _) = await Core.CallAsync(new Call.TracksEdit { tracks = tracks, mediaId = t.mediaId, label = label });
            if (answer is Answer.Said { notice: { } n }) _say(false, Said.Notice(n));
            Log.Write($"Tracks.Edit: {(answer is Answer.Said { notice: { } m } ? m.msg : answer.GetType().Name)}");
        }
        catch (Exception e)
        {
            Log.Write($"Tracks.Edit failed ({e.GetType().Name})");
        }
    }

    /// <summary>The asked files' labels (TracksShown.asking): a name and an activity each, then Tracks.Confirm — or
    /// Tracks.Cancel, which uploads nothing.</summary>
    private async void AskLabels(TracksShown s)
    {
        if (_id is not { } tracks) return;
        _asking = true;
        try
        {
            var many = s.asking.Count > 1;
            var panel = new StackPanel { Spacing = 16 };
            panel.Children.Add(new TextBlock { Text = Words.TrackAddHint(), TextWrapping = TextWrapping.Wrap });
            var fields = new List<(TextBox Name, ComboBox Activity)>();
            for (var i = 0; i < s.asking.Count; i++)
            {
                var asked = s.asking[i];
                var group = new StackPanel { Spacing = 8 };
                if (many) group.Children.Add(new TextBlock { Text = asked.fileName, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] });
                var name = new TextBox { Header = Words.TrackNameLabel(), PlaceholderText = asked.fileTrackName ?? Path.GetFileNameWithoutExtension(asked.fileName), MinWidth = 320 };
                AutomationProperties.SetAutomationId(name, $"track-add-name:{i}");
                var activity = ActivityPicker(s.activities, null, $"track-add-activity:{i}");
                group.Children.Add(name);
                group.Children.Add(activity);
                panel.Children.Add(group);
                fields.Add((name, activity));
            }
            var dialog = new ContentDialog
            {
                Title = many ? Words.TrackAddTitleMany() : Words.TrackAddTitle(),
                Content = new ScrollViewer { Content = panel, MaxHeight = 480 },
                PrimaryButtonText = many ? Words.TrackAddConfirmMany() : Words.TrackAddConfirm(),
                CloseButtonText = Words.EntryCancel(),
                DefaultButton = ContentDialogButton.Primary,
            };
            AutomationProperties.SetAutomationId(dialog, "track-add-dialog");
            var result = await Dialogs.Show(_window, dialog);
            if (_closed) return;
            if (result != ContentDialogResult.Primary)
            {
                Log.Write("track view: add cancelled");
                Act.Send(new Call.TracksCancel { tracks = tracks });
                return;
            }
            var labels = fields.Select(f => new TrackLabelShown { name = f.Name.Text.Trim() is { Length: > 0 } n ? n : null, activity = Picked(f.Activity) }).ToList();
            Log.Write($"track view: add {labels.Count} ({string.Join(", ", labels.Select(l => l.activity ?? "-"))})");
            var (answer, _) = await Core.CallAsync(new Call.TracksConfirm { tracks = tracks, labels = labels });
            Uploads.Queued(answer, "Tracks.Confirm", _say);
        }
        catch (Exception e)
        {
            Log.Write($"the labels were not asked ({e.GetType().Name})");
        }
        finally
        {
            _asking = false;
        }
    }

    /// <summary>Download GPX: the save picker proposes the file's name; the core writes it (Tracks.Download) with the shell's
    /// progress and Cancel.</summary>
    private async void Download(TrackShown t)
    {
        if (_id is not { } tracks) return;
        try
        {
            var file = t.fileName ?? $"{Name(t)}.gpx";
            var picker = new FileSavePicker { SuggestedStartLocation = PickerLocationId.DocumentsLibrary, SuggestedFileName = Path.GetFileNameWithoutExtension(file) };
            picker.FileTypeChoices.Add(Words.TrackDownload(), [".gpx"]);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var chosen = await picker.PickSaveFileAsync();
            if (chosen is null) return;
            var (answer, _) = await Core.CallAsync(new Call.TracksDownload { tracks = tracks, mediaId = t.mediaId, path = chosen.Path });
            if (answer is Answer.Saving { save: var save })
            {
                Log.Write($"saving a GPX track (save {save})");
                _saving(save);
                return;
            }
            Log.Write($"Tracks.Download answered {answer.GetType().Name}");
            _say(false, Words.TrackDownloadFailed());
        }
        catch (Exception e)
        {
            Log.Write($"the track was not saved: {e.GetType().Name}");
            _say(false, Words.TrackDownloadFailed());
        }
    }

    /// <summary>Remove: the gallery's trash (its Undo in the shell's InfoBar while the gallery offers it).</summary>
    private async void Remove(TrackShown t)
    {
        if (_id is not { } tracks) return;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.TracksRemove { tracks = tracks, mediaId = t.mediaId });
            if (answer is Answer.Trashed { undoable: var undoable }) Log.Write($"track remove: Trashed, undo {undoable}");
            else if (answer is Answer.Said { notice: { } n }) _say(false, Said.Notice(n));
            else Log.Write($"Tracks.Remove answered {answer.GetType().Name}");
        }
        catch (Exception e)
        {
            Log.Write($"Tracks.Remove failed ({e.GetType().Name})");
        }
    }

    private void Report(TrackShown t)
    {
        if (_id is { } tracks) ReportDialog.Open(_window, new Call.TracksReport { tracks = tracks, mediaId = t.mediaId });
    }

    /// <summary>"Add GPX track": the open picker filtered to .gpx (several), their paths to the core (Tracks.Add), what it
    /// refused said; the rest asked for their labels.</summary>
    private async void Add()
    {
        if (_id is not { } tracks) return;
        try
        {
            var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.DocumentsLibrary, ViewMode = PickerViewMode.List };
            picker.FileTypeFilter.Add(".gpx");
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var picked = await picker.PickMultipleFilesAsync();
            if (picked is null || picked.Count == 0 || _closed) return;
            Log.Write($"track add: {picked.Count} files");
            var (answer, _) = await Core.CallAsync(new Call.TracksAdd { tracks = tracks, paths = picked.Select(f => f.Path).ToList() });
            if (answer is Answer.TracksAdded { said: var said })
            {
                Log.Write($"tracks added: {said.Count} refused ({string.Join(", ", said.Select(n => n.msg))})");
                // the last refusal is the view's own said as well (TracksShown.said), which says it once: the rest here
                foreach (var n in said.Take(said.Count - 1)) _say(false, Said.Notice(n));
            }
            else Log.Write($"Tracks.Add answered {answer.GetType().Name}");
        }
        catch (Exception e)
        {
            Log.Write($"add a track failed: {e.GetType().Name}: {e.Message}");
        }
    }

    public void Dispose()
    {
        if (_closed) return;
        _closed = true;
        _watch?.Dispose();
        _map?.Dispose();
        _mapSlot.Child = null;
        if (_id is { } tracks) Act.Send(new Call.TracksClose { tracks = tracks });
    }
}
