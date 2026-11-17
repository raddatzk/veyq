// A person's profile on Windows (ADR 0144 decisions 11–14; Linux's person_page.rs, the web's PersonProfile.vue): a page of
// the shell while its state names a person (Shell.person), back the shell's. Core's frame — their picture at its largest,
// their name (the former or unknown member's words), "~username" under a real name — then Memories' section, "Memories with
// {name}": the person's Memories as the level's rows (cover, name, date, place, the group), newest start day first, a row
// opening its Memory (Shell.Open); a SelectorBar List | Map once something is listed (not stored: it opens on the list),
// the map ADR 0143's Memories map over the person's located Memories (Person.Map, RasterMap); loading, Try again, empty and
// partial in the web's words. Every state is the core's (Person.*, Watched.Person). Nothing about it is written to disk.
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

internal sealed class PersonPage : IDisposable
{
    private readonly Window _window;
    private readonly ScrollViewer _scroll;
    private readonly StackPanel _column = new() { Spacing = 12, Padding = new Thickness(24, 12, 24, 32) };
    private readonly PersonPicture _picture = new() { Width = 96, Height = 96, HorizontalAlignment = HorizontalAlignment.Left };
    private readonly TextBlock _name = new() { Style = (Style)Application.Current.Resources["TitleTextBlockStyle"], TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _username = new() { Style = (Style)Application.Current.Resources["BodyTextBlockStyle"], Visibility = Visibility.Collapsed };
    private readonly TextBlock _hint = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
    private readonly StackPanel _section = new() { Spacing = 8, Visibility = Visibility.Collapsed };
    private readonly TextBlock _heading = new() { Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"], TextWrapping = TextWrapping.Wrap };
    private readonly SelectorBar _views = new() { Visibility = Visibility.Collapsed };
    private readonly SelectorBarItem _listItem = new() { Text = Words.NodeViewList(), IsSelected = true };
    private readonly SelectorBarItem _mapItem = new() { Text = Words.MemoryViewMap() };
    private readonly TextBlock _partial = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
    private readonly ContentControl _status = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch };
    private readonly ListView _rows = new() { SelectionMode = ListViewSelectionMode.None, IsItemClickEnabled = true };
    private readonly Grid _mapHolder = new() { Height = 440, Visibility = Visibility.Collapsed };
    private readonly StackPanel _mapEmpty = new() { Spacing = 4, Padding = new Thickness(16), Margin = new Thickness(16), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, CornerRadius = new CornerRadius(8), Visibility = Visibility.Collapsed };
    private long? _id;
    private Watch? _watch;
    private PersonShown? _shown;
    private string _rowsDrawn = "";
    private RasterMap? _map;
    private bool _mapOpening;
    private bool _drawing;
    private bool _closed;

    public string UserId { get; }

    public UIElement Page => _scroll;

    public PersonPage(Window window, string userId)
    {
        _window = window;
        UserId = userId;
        AutomationProperties.SetAutomationId(_picture, "person-picture");
        _column.Children.Add(_picture);
        AutomationProperties.SetAutomationId(_name, "person-name");
        AutomationProperties.SetHeadingLevel(_name, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level1);
        _column.Children.Add(_name);
        _username.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        AutomationProperties.SetAutomationId(_username, "person-username");
        _column.Children.Add(_username);
        _hint.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        AutomationProperties.SetAutomationId(_hint, "person-hint");
        _column.Children.Add(_hint);
        AutomationProperties.SetAutomationId(_heading, "person-memories-heading");
        AutomationProperties.SetHeadingLevel(_heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        _section.Margin = new Thickness(0, 12, 0, 0);
        _section.Children.Add(_heading);
        AutomationProperties.SetAutomationId(_views, "person-views");
        AutomationProperties.SetAutomationId(_listItem, "person-view-list");
        AutomationProperties.SetAutomationId(_mapItem, "person-view-map");
        _views.Items.Add(_listItem);
        _views.Items.Add(_mapItem);
        _views.SelectionChanged += (_, _) =>
        {
            if (_drawing || _id is not { } person) return;
            var view = ReferenceEquals(_views.SelectedItem, _mapItem) ? MemoriesView.Map : MemoriesView.List;
            Log.Write($"person page: view {view}");
            Act.Send(new Call.PersonView { person = person, view = view });
        };
        _section.Children.Add(_views);
        _partial.Text = Words.MemoryPeoplePartial();
        _partial.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        AutomationProperties.SetAutomationId(_partial, "person-partial");
        _section.Children.Add(_partial);
        _section.Children.Add(_status);
        AutomationProperties.SetAutomationId(_rows, "person-memories");
        _rows.ItemClick += (_, e) =>
        {
            var row = e.ClickedItem as ListViewItem ?? (e.ClickedItem as FrameworkElement)?.Parent as ListViewItem;
            if (row?.Tag is ObjectRef r)
            {
                Log.Write("person page: a Memory opened");
                Act.Send(new Call.ShellOpen { Ref = r });
            }
        };
        _section.Children.Add(_rows);
        AutomationProperties.SetAutomationId(_mapHolder, "person-map");
        AutomationProperties.SetAutomationId(_mapEmpty, "person-map-empty");
        _mapEmpty.Background = (Brush)Application.Current.Resources["AcrylicInAppFillColorDefaultBrush"];
        _mapEmpty.Children.Add(new TextBlock { Text = Words.MemoriesMapEmpty(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], HorizontalAlignment = HorizontalAlignment.Center });
        _section.Children.Add(_mapHolder);
        _column.Children.Add(_section);
        _scroll = new ScrollViewer { Content = _column, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
        AutomationProperties.SetAutomationId(_scroll, "person-page");
        Open();
    }

    private async void Open()
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.PersonOpen { userId = UserId });
            if (answer is not Answer.PersonOpened { person: var person })
            {
                Log.Write($"Person.Open answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                return;
            }
            if (_closed)
            {
                Act.Send(new Call.PersonClose { person = person });
                return;
            }
            _id = person;
            Log.Write($"person page {person} open");
            _watch = Core.Watch(new Watched.Person { person = person }, Updated, e => Log.Write($"a person page update: {e.Message}"));
        }
        catch (Exception e)
        {
            Log.Write($"the person page did not open ({e.GetType().Name})");
        }
    }

    private void Updated(Update update)
    {
        if (_closed || update is not Update.Person { shown: var s }) return;
        _shown = s;
        if (s is null) return;
        var m = s.memories;
        Log.Write($"person shown: named {s.name is not null}, former {s.former}, picture {s.picture}, username {s.username is not null}, sections {string.Join(",", s.sections)}"
            + (m is null ? "" : $"; memories: loading {m.loading}, failed {m.failed}, empty {m.empty}, partial {m.partial}, views {m.offersViews} {m.view}, {m.rows.Count} rows, {m.located} located"));
        _drawing = true;
        try
        {
            var title = CurationText.PersonTitle(s);
            _name.Text = title;
            _picture.DisplayName = title;
            Avatars.Show(_picture, s.picture ? s.userId : null);
            _username.Text = s.username is { } u ? $"~{u}" : "";
            _username.Visibility = s.username is null ? Visibility.Collapsed : Visibility.Visible;
            _hint.Text = s.former ? Words.PeopleFormerMemberHint() : "";
            _hint.Visibility = s.former ? Visibility.Visible : Visibility.Collapsed;
            _section.Visibility = m is null ? Visibility.Collapsed : Visibility.Visible;
            if (m is null) return;
            _heading.Text = CurationText.MemoriesWith(s);
            _views.Visibility = m.offersViews ? Visibility.Visible : Visibility.Collapsed;
            _views.SelectedItem = m.view == MemoriesView.Map ? _mapItem : _listItem;
            _partial.Visibility = m.partial ? Visibility.Visible : Visibility.Collapsed;
            _status.Content = m switch
            {
                { loading: true } => new ProgressRing { IsActive = true, Margin = new Thickness(24), HorizontalAlignment = HorizontalAlignment.Left },
                { failed: true } => Failed(),
                { empty: true } => new TextBlock { Text = Words.MemoryPeopleEmpty(), TextWrapping = TextWrapping.Wrap },
                _ => null,
            };
            var map = m.view == MemoriesView.Map && m.offersViews;
            _rows.Visibility = map ? Visibility.Collapsed : Visibility.Visible;
            _mapHolder.Visibility = map ? Visibility.Visible : Visibility.Collapsed;
            if (map) OpenMap();
            else CloseMap();
            DrawRows(m.rows);
        }
        finally
        {
            _drawing = false;
        }
    }

    private UIElement Failed()
    {
        var column = new StackPanel { Spacing = 8 };
        var text = new TextBlock { Text = Words.MemoryPeopleError(), TextWrapping = TextWrapping.Wrap };
        AutomationProperties.SetAutomationId(text, "person-error");
        column.Children.Add(text);
        var retry = new Button { Content = Words.MemoryPeopleRetry() };
        AutomationProperties.SetAutomationId(retry, "person-retry");
        retry.Click += (_, _) =>
        {
            if (_id is { } person) Act.Send(new Call.PersonRetry { person = person });
        };
        column.Children.Add(retry);
        return column;
    }

    /// <summary>The rows, drawn anew only when they changed: the level's (its cover, name, date, place, the group).</summary>
    private void DrawRows(IReadOnlyList<PersonRowShown> rows)
    {
        var key = string.Join("|", rows.Select(r => $"{r.Ref.nodeId}:{r.Ref.name}:{MemoryFacts.DateText(r.date)}:{r.place}:{r.groupName?.ToString()}"));
        if (key == _rowsDrawn) return;
        _rowsDrawn = key;
        _rows.Items.Clear();
        foreach (var r in rows)
        {
            var line = new Grid { ColumnSpacing = 12 };
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            line.Children.Add(RowCovers.Prefix(HomeView.Glyph(HomeView.KindGlyph(r.Ref.kind)), r.Ref.nodeId));
            var texts = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            texts.Children.Add(new TextBlock { Text = r.Ref.name, TextTrimming = TextTrimming.CharacterEllipsis });
            var under = string.Join(" · ", new[] { MemoryFacts.DateText(r.date), r.place, r.groupName is { } g ? Said.Piece(g) : null }.Where(t => !string.IsNullOrEmpty(t)));
            if (under.Length > 0)
            {
                texts.Children.Add(new TextBlock
                {
                    Text = under,
                    TextTrimming = TextTrimming.CharacterEllipsis,
                    Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
                    Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
                });
            }
            Grid.SetColumn(texts, 1);
            line.Children.Add(texts);
            var row = new ListViewItem { Content = line, Tag = r.Ref, MinHeight = 48 };
            AutomationProperties.SetAutomationId(row, $"person-memory:{r.Ref.name}");
            AutomationProperties.SetName(row, under.Length > 0 ? $"{r.Ref.name}, {under}" : r.Ref.name);
            _rows.Items.Add(row);
        }
    }

    private async void OpenMap()
    {
        if (_map is not null || _mapOpening || _id is not { } person) return;
        _mapOpening = true;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.PersonMap { person = person });
            if (answer is not Answer.MapOpened { map: var map })
            {
                Log.Write($"Person.Map answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                return;
            }
            if (_closed || _mapHolder.Visibility != Visibility.Visible)
            {
                Act.Send(new Call.MapClose { map = map });
                return;
            }
            Log.Write($"person page: map {map} open");
            _map = new RasterMap(_window, map, closesItself: true)
            {
                Changed = s => _mapEmpty.Visibility = s is { state: MapStatus.MapStateShown, clusters.Count: 0 } ? Visibility.Visible : Visibility.Collapsed,
            };
            _mapHolder.Children.Clear();
            _mapHolder.Children.Add(_map.View);
            _mapHolder.Children.Add(_mapEmpty);
        }
        catch (Exception e)
        {
            Log.Write($"the person's map did not open ({e.GetType().Name})");
        }
        finally
        {
            _mapOpening = false;
        }
    }

    private void CloseMap()
    {
        if (_map is null) return;
        _mapHolder.Children.Clear();
        _map.Dispose();
        _map = null;
        _mapEmpty.Visibility = Visibility.Collapsed;
        Log.Write("person page: map closed");
    }

    public void Dispose()
    {
        if (_closed) return;
        _closed = true;
        CloseMap();
        _watch?.Dispose();
        if (_id is { } person) Act.Send(new Call.PersonClose { person = person });
    }
}
