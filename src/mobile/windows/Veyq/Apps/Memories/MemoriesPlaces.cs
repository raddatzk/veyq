// The Memories application's places in the shell (ADR 0143 decision 6), its own list in place of core's while its bar
// has more than one tab: the tabs as a SelectorBar (as a Pot's places; a choice is the shell's), under it the list or the
// Memories map — every located Memory a pin (Map.OpenMemories), kept while its tab stays chosen.

namespace Veyq.Apps.Memories;

using System;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Veyq.Core;

internal sealed class MemoriesPlaces : ApplicationList
{
    private readonly ListHost _host;
    /// <summary>The SelectorBar over the list or the Memories map, what the bar was drawn from, and the map while its
    /// tab is chosen.</summary>
    private Grid? _places;
    private string _placesDrawn = "";
    private readonly Grid _mapHolder = new();
    private readonly StackPanel _mapEmpty = new() { Spacing = 4, Padding = new Thickness(16), Margin = new Thickness(16), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, CornerRadius = new CornerRadius(8), Visibility = Visibility.Collapsed };
    private RasterMap? _memoriesMap;
    private bool _mapOpening;

    public MemoriesPlaces(ListHost host) => _host = host;

    public override UIElement? Draw(ShellModelState state, ShellShown shown)
    {
        if (shown.tabs.Count <= 1) return null;
        if (_places is null)
        {
            _places = new Grid();
            _places.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            _places.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            AutomationProperties.SetAutomationId(_mapEmpty, "memories-map-empty");
            _mapEmpty.Background = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["AcrylicInAppFillColorDefaultBrush"];
            _mapEmpty.Children.Add(new TextBlock { Text = Words.MemoriesMapEmpty(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], HorizontalAlignment = HorizontalAlignment.Center });
            _mapEmpty.Children.Add(new TextBlock { Text = Words.MemoriesMapEmptyHint(), TextWrapping = TextWrapping.Wrap, MaxWidth = 360, TextAlignment = TextAlignment.Center });
        }
        var drawn = string.Join(",", shown.tabs.Select(t => $"{t.tab.key}={t.word}")) + "|" + shown.selectedTab?.key;
        if (drawn != _placesDrawn)
        {
            _placesDrawn = drawn;
            foreach (var old in _places.Children.Where(c => Grid.GetRow((FrameworkElement)c) == 0).ToList()) _places.Children.Remove(old);
            var bar = new SelectorBar { Margin = new Thickness(12, 0, 12, 0) };
            AutomationProperties.SetAutomationId(bar, "memories-places");
            SelectorBarItem? chosen = null;
            foreach (var t in shown.tabs)
            {
                var item = new SelectorBarItem { Text = Said.Key(t.word), Tag = t.tab.key };
                AutomationProperties.SetAutomationId(item, $"place:{t.tab.key}");
                bar.Items.Add(item);
                if (t.tab.key == shown.selectedTab?.key) chosen = item;
            }
            bar.SelectedItem = chosen;
            var current = shown.selectedTab?.key;
            bar.SelectionChanged += (b, _) =>
            {
                if (b.SelectedItem?.Tag is string key && key != current) Act.Send(new Call.ShellSelect { tab = new BarTab { key = key } });
            };
            _places.Children.Add(bar);
        }
        UIElement content;
        if (shown.selectedTab?.key == Map)
        {
            if (_memoriesMap is null) OpenMemoriesMap();
            content = _mapHolder;
        }
        else
        {
            Close();
            content = HomeView.List(shown.list, shown.view == ViewModeShown.Tiles);
            if (content is ScrollViewer list) _host.Keep(list, ListScrolls.Place(state.shell.application, state.shell.space, null, state.shell.filter));
        }
        foreach (var old in _places.Children.Where(c => Grid.GetRow((FrameworkElement)c) == 1 && !ReferenceEquals(c, content)).ToList()) _places.Children.Remove(old);
        if (!_places.Children.Contains(content))
        {
            if (content is FrameworkElement { Parent: Panel p }) p.Children.Remove(content);
            Grid.SetRow((FrameworkElement)content, 1);
            _places.Children.Add(content);
        }
        return _places;
    }

    /// <summary>The bar's Map tab (the core's BarTab key).</summary>
    private const string Map = "memories.map";

    /// <summary>No level's View beside the Memories map.</summary>
    public override bool OffersView(ShellShown shown) => shown.selectedTab?.key != Map;

    private async void OpenMemoriesMap()
    {
        if (_mapOpening) return;
        _mapOpening = true;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.MapOpenMemories());
            if (answer is not Answer.MapOpened { map: var map })
            {
                Log.Write($"Map.OpenMemories answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                return;
            }
            if (_host.Shown()?.selectedTab?.key != Map || _memoriesMap is not null || _places is null || !_host.Showing(_places))
            {
                Act.Send(new Call.MapClose { map = map });
                return;
            }
            Log.Write($"memories map {map} open");
            _memoriesMap = new RasterMap(_host.Window, map, closesItself: true)
            {
                Changed = s => _mapEmpty.Visibility = s is { state: MapStatus.MapStateShown, clusters.Count: 0 } ? Visibility.Visible : Visibility.Collapsed,
            };
            _mapHolder.Children.Clear();
            _mapHolder.Children.Add(_memoriesMap.View);
            _mapHolder.Children.Add(_mapEmpty);
        }
        catch (Exception e)
        {
            Log.Write($"the Memories map did not open ({e.GetType().Name})");
        }
        finally
        {
            _mapOpening = false;
        }
    }

    public override void Close()
    {
        if (_memoriesMap is null) return;
        _mapHolder.Children.Clear();
        _memoriesMap.Dispose();
        _memoriesMap = null;
        _mapEmpty.Visibility = Visibility.Collapsed;
        Log.Write("memories map closed");
    }
}
