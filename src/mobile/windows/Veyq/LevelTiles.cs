// A level's, an application list's and a person's Memories drawn as tiles (ADR 0149 decision 8; cr-app-memories-rest
// phase 10), as Explorer's Large icons and Photos' album grid draw them: each node a square cover (its cover where the core
// names one, RowCovers; else its kind's glyph on a card), its name below and the row's second line under it. The grid's
// frame is the core's (View.Tiles: TileGrid — its columns at the width the grid has, every square's place), asked anew when
// the width changes; a tile sits in a Border placed on the Canvas at its frame (src/mobile/CLAUDE.md § Each platform's
// guidelines), the Border holding the tile's Button. A tile does what its row does: a folder enters (and takes a drop), an
// object opens. Nothing is decided here.
using System;
using System.Collections.Generic;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

internal static class LevelTiles
{
    /// <summary>The name and the second line below a cover, in effective pixels (TileGrid's labelHeight).</summary>
    public const double LabelHeight = 46;

    /// <summary>One tile: its kind's <paramref name="Glyph"/>, the node whose cover it shows, its name and second line, its
    /// AutomationId (the row's), what a click does, and a folder's drop (Drops.OntoFolder) where it takes one.</summary>
    public sealed record Item(string Glyph, string NodeId, string Name, string? Subtitle, string AutomationId, Action Open, Action<Control>? Droppable = null);

    /// <summary>The tiles of <paramref name="items"/> at the core's frames, laid out anew when the grid's width changes.</summary>
    public static FrameworkElement Grid(IReadOnlyList<Item> items, string automationId)
    {
        var canvas = new Canvas();
        var host = new Grid { HorizontalAlignment = HorizontalAlignment.Stretch, Margin = new Thickness(12, 4, 12, 8) };
        AutomationProperties.SetAutomationId(host, automationId);
        host.Children.Add(canvas);
        var laid = -1.0;
        var asked = 0;
        host.SizeChanged += async (_, e) =>
        {
            var width = e.NewSize.Width;
            if (width <= 0 || Math.Abs(width - laid) < 0.5) return;
            laid = width;
            var ask = ++asked;
            try
            {
                var (answer, _) = await Core.CallAsync(new Call.ViewTiles { width = width, count = items.Count, labelHeight = LabelHeight });
                // a later width asked meanwhile: its answer places the tiles
                if (ask != asked) return;
                if (answer is not Answer.Tiles t)
                {
                    Log.Write($"View.Tiles answered {answer.GetType().Name}");
                    return;
                }
                Place(canvas, host, items, t);
            }
            catch (Exception ex)
            {
                // an exception out of an async handler would end the app in combase, with nothing in the log
                Log.Write($"View.Tiles failed ({ex.GetType().Name})");
            }
        };
        return host;
    }

    private static void Place(Canvas canvas, Grid host, IReadOnlyList<Item> items, Answer.Tiles t)
    {
        canvas.Children.Clear();
        var bottom = 0.0;
        for (var i = 0; i < items.Count && i < t.frames.Count; i++)
        {
            var f = t.frames[i];
            var cell = new Border { Width = f.size, Height = f.size + LabelHeight, Child = Tile(items[i], f.size) };
            Canvas.SetLeft(cell, f.x);
            Canvas.SetTop(cell, f.y);
            canvas.Children.Add(cell);
            bottom = Math.Max(bottom, f.y + f.size + LabelHeight);
        }
        host.Height = Math.Ceiling(bottom);
        Log.Write($"tiles laid out: {items.Count} in {t.columns} columns");
    }

    /// <summary>One tile: the square cover, the name, the second line; invoking it does what its row does.</summary>
    private static Button Tile(Item item, double size)
    {
        var glyph = HomeView.Glyph(item.Glyph);
        glyph.FontSize = Math.Min(48, size / 3);
        glyph.HorizontalAlignment = HorizontalAlignment.Center;
        glyph.VerticalAlignment = VerticalAlignment.Center;
        glyph.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        var square = new Border
        {
            Width = size,
            Height = size,
            CornerRadius = new CornerRadius(8),
            Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"],
            BorderBrush = (Brush)Application.Current.Resources["CardStrokeColorDefaultBrush"],
            BorderThickness = new Thickness(1),
            Child = RowCovers.Prefix(glyph, item.NodeId, size, size),
        };
        var column = new StackPanel { Spacing = 2 };
        column.Children.Add(square);
        column.Children.Add(new TextBlock { Text = item.Name, TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(2, 4, 2, 0) });
        if (item.Subtitle is { Length: > 0 } under)
        {
            column.Children.Add(new TextBlock
            {
                Text = under,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Margin = new Thickness(2, 0, 2, 0),
                Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
                Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
            });
        }
        var tile = new Button
        {
            Content = column,
            Padding = new Thickness(0),
            BorderThickness = new Thickness(0),
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            VerticalAlignment = VerticalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Left,
            VerticalContentAlignment = VerticalAlignment.Top,
        };
        AutomationProperties.SetAutomationId(tile, item.AutomationId);
        AutomationProperties.SetName(tile, item.Subtitle is { Length: > 0 } s ? $"{item.Name}, {s}" : item.Name);
        ToolTipService.SetToolTip(tile, item.Name);
        tile.Click += (_, _) => item.Open();
        item.Droppable?.Invoke(tile);
        return tile;
    }

    /// <summary>The CommandBar's View (Explorer's): List or Tiles as the account chose (ShellShown.view), each a radio item
    /// that sends View.Set; drawn only where the level offers more than one view.</summary>
    public static AppBarButton? ViewCommand(ShellShown shown)
    {
        if (shown.views.Count < 2) return null;
        var menu = new MenuFlyout { Placement = Microsoft.UI.Xaml.Controls.Primitives.FlyoutPlacementMode.BottomEdgeAlignedRight };
        foreach (var mode in shown.views)
        {
            var item = new RadioMenuFlyoutItem { Text = Word(mode), GroupName = "view-mode", IsChecked = mode == shown.view, Icon = new FontIcon { Glyph = ModeGlyph(mode) } };
            AutomationProperties.SetAutomationId(item, $"view:{mode}");
            var chosen = mode;
            item.Click += (_, _) =>
            {
                if (chosen == shown.view) return;
                Log.Write($"view chosen: {chosen}");
                Act.Send(new Call.ViewSet { mode = chosen });
            };
            menu.Items.Add(item);
        }
        var button = new AppBarButton { Icon = new FontIcon { Glyph = ModeGlyph(shown.view) }, Label = Words.NodeViewMode(), Flyout = menu };
        AutomationProperties.SetAutomationId(button, "view-mode");
        ToolTipService.SetToolTip(button, Words.NodeViewMode());
        return button;
    }

    /// <summary>A view's word (ViewMode.word: the web's node.viewMode).</summary>
    public static string Word(ViewModeShown mode) => mode switch
    {
        ViewModeShown.List => Words.NodeViewList(),
        ViewModeShown.Tiles => Words.NodeViewTiles(),
        _ => Words.MemoryViewMap(),
    };

    /// <summary>A view's Segoe Fluent Icons glyph: List, GridView, Map.</summary>
    public static string ModeGlyph(ViewModeShown mode) => mode switch
    {
        ViewModeShown.List => "\uE8FD",
        ViewModeShown.Tiles => "\uF0E2",
        _ => "\uE707",
    };
}
