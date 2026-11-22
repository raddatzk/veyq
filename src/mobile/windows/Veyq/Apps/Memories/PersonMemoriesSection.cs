// Memories' section of a person's profile (ADR 0144 decisions 11–14; Linux's person_page.rs, the web's PersonProfile.vue),
// under core's frame (PersonPage): "Memories with {name}": the person's Memories as the level's rows (cover, name, date,
// place, the group), newest start day first, a row opening its Memory (Shell.Open); a SelectorBar List | Tiles | Map once
// something is listed — the account's one view choice on this device (ADR 0149 decision 8: Person.View keeps it, Tiles
// before any) —, the tiles the level's (LevelTiles), the map ADR 0143's Memories map over the person's located Memories
// (Person.Map, RasterMap); loading, Try again, empty and partial in the web's words. Every state is the core's
// (PersonShown.memories).

namespace Veyq.Apps.Memories;

using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Veyq.Core;

internal sealed class PersonMemoriesSection : PersonSection
{
    private readonly Window _window;
    private readonly StackPanel _section = new() { Spacing = 8, Visibility = Visibility.Collapsed };
    private readonly TextBlock _heading = new() { Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"], TextWrapping = TextWrapping.Wrap };
    private readonly SelectorBar _views = new() { Visibility = Visibility.Collapsed };
    private readonly SelectorBarItem _listItem = new() { Text = Words.NodeViewList(), IsSelected = true };
    private readonly SelectorBarItem _tilesItem = new() { Text = Words.NodeViewTiles() };
    private readonly SelectorBarItem _mapItem = new() { Text = Words.MemoryViewMap() };
    /// <summary>The Memories as tiles, while the Tiles view shows.</summary>
    private readonly ContentControl _tiles = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch, Visibility = Visibility.Collapsed };
    private readonly TextBlock _partial = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
    private readonly ContentControl _status = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch };
    private readonly ListView _rows = new() { SelectionMode = ListViewSelectionMode.None, IsItemClickEnabled = true };
    private readonly Grid _mapHolder = new() { Height = 440, Visibility = Visibility.Collapsed };
    private readonly StackPanel _mapEmpty = new() { Spacing = 4, Padding = new Thickness(16), Margin = new Thickness(16), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, CornerRadius = new CornerRadius(8), Visibility = Visibility.Collapsed };
    private long? _id;
    private string _rowsDrawn = "";
    private RasterMap? _map;
    private bool _mapOpening;
    private bool _drawing;
    private bool _closed;

    public override UIElement View => _section;

    public PersonMemoriesSection(Window window)
    {
        _window = window;
        AutomationProperties.SetAutomationId(_heading, "person-memories-heading");
        AutomationProperties.SetHeadingLevel(_heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        _section.Margin = new Thickness(0, 12, 0, 0);
        _section.Children.Add(_heading);
        AutomationProperties.SetAutomationId(_views, "person-views");
        AutomationProperties.SetAutomationId(_listItem, "person-view-list");
        AutomationProperties.SetAutomationId(_tilesItem, "person-view-tiles");
        AutomationProperties.SetAutomationId(_mapItem, "person-view-map");
        _views.Items.Add(_listItem);
        _views.Items.Add(_tilesItem);
        _views.Items.Add(_mapItem);
        _views.SelectionChanged += (_, _) =>
        {
            if (_drawing || _id is not { } person) return;
            var view = ReferenceEquals(_views.SelectedItem, _mapItem) ? MemoriesView.Map
                : ReferenceEquals(_views.SelectedItem, _tilesItem) ? MemoriesView.Tiles : MemoriesView.List;
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
        AutomationProperties.SetAutomationId(_tiles, "person-tiles");
        _section.Children.Add(_tiles);
        AutomationProperties.SetAutomationId(_mapHolder, "person-map");
        AutomationProperties.SetAutomationId(_mapEmpty, "person-map-empty");
        _mapEmpty.Background = (Brush)Application.Current.Resources["AcrylicInAppFillColorDefaultBrush"];
        _mapEmpty.Children.Add(new TextBlock { Text = Words.MemoriesMapEmpty(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], HorizontalAlignment = HorizontalAlignment.Center });
        _section.Children.Add(_mapHolder);
    }

    public override void Shown(long person, PersonShown s)
    {
        _id = person;
        var m = s.memories;
        _drawing = true;
        try
        {
            _section.Visibility = m is null ? Visibility.Collapsed : Visibility.Visible;
            if (m is null) return;
            _heading.Text = CurationText.MemoriesWith(s);
            _views.Visibility = m.offersViews ? Visibility.Visible : Visibility.Collapsed;
            // every view the core names has its item: Tiles, the first view before any choice, too
            _views.SelectedItem = m.view switch { MemoriesView.Map => _mapItem, MemoriesView.Tiles => _tilesItem, _ => _listItem };
            _partial.Visibility = m.partial ? Visibility.Visible : Visibility.Collapsed;
            _status.Content = m switch
            {
                { loading: true } => new ProgressRing { IsActive = true, Margin = new Thickness(24), HorizontalAlignment = HorizontalAlignment.Left },
                { failed: true } => Failed(),
                { empty: true } => new TextBlock { Text = Words.MemoryPeopleEmpty(), TextWrapping = TextWrapping.Wrap },
                _ => null,
            };
            var map = m.view == MemoriesView.Map && m.offersViews;
            var tiles = m.view == MemoriesView.Tiles && m.offersViews;
            _rows.Visibility = map || tiles ? Visibility.Collapsed : Visibility.Visible;
            _tiles.Visibility = tiles ? Visibility.Visible : Visibility.Collapsed;
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
        var key = string.Join("|", rows.Select(r => $"{r.Ref.nodeId}:{r.Ref.name}:{MemoryDates.DateText(r.date)}:{r.place}:{r.groupName?.ToString()}"));
        if (key == _rowsDrawn) return;
        _rowsDrawn = key;
        _rows.Items.Clear();
        var tiles = new List<LevelTiles.Item>();
        foreach (var r in rows)
        {
            var line = new Grid { ColumnSpacing = 12 };
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            line.Children.Add(RowCovers.Prefix(HomeView.Glyph(HomeView.KindGlyph(r.Ref.kind)), r.Ref.nodeId));
            var texts = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            texts.Children.Add(new TextBlock { Text = r.Ref.name, TextTrimming = TextTrimming.CharacterEllipsis });
            var under = Lives.Joined([MemoryDates.DateText(r.date), r.place, r.groupName is { } g ? Said.Piece(g) : null]);
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
            var opened = r.Ref;
            tiles.Add(new LevelTiles.Item(HomeView.KindGlyph(r.Ref.kind), r.Ref.nodeId, r.Ref.name, under, $"person-memory:{r.Ref.name}", () =>
            {
                Log.Write("person page: a Memory opened");
                Act.Send(new Call.ShellOpen { Ref = opened });
            }));
        }
        _tiles.Content = tiles.Count > 0 ? LevelTiles.Grid(tiles, "person-memories-tiles") : null;
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

    public override void Dispose()
    {
        if (_closed) return;
        _closed = true;
        CloseMap();
    }
}
