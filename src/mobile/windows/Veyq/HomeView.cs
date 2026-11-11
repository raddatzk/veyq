// Home and an application's list (ADR 0122): Home's level as the core groups it — the folders, then the contents — under
// the filters the core offers (a SelectorBar), and an application's own list in its Favorites, Recent and the rest.
// Drawn as Explorer's Home draws its sections: a heading over each group's list. Linux's home.rs, in Fluent.
using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge.Wire;
using ApplicationId = Veyq.Bridge.Wire.ApplicationId;

namespace Veyq;

internal static class HomeView
{
    /// <summary>A node's kind as a Segoe Fluent Icons glyph — Android's kindIcon, Apple's Names.kindSymbol.</summary>
    public static string KindGlyph(string kind) => kind switch
    {
        "FOLDER" => "",
        "EXPENSES_POT" => "",
        "MEMORY" => "",
        "CHAPTER" => "",
        "DEPOT_DROPBOX" => "",
        "SHARE_BOOKMARK" => "",
        _ => "",
    };

    /// <summary>An application's glyph.</summary>
    public static string AppGlyph(ApplicationId app) => app switch
    {
        ApplicationId.Memories => "",
        ApplicationId.Depot => "",
        ApplicationId.Expenses => "",
        _ => "",
    };

    /// <summary>An application's name, as the core names it (ShellShown.applicationWords).</summary>
    public static string AppName(ShellShown shown, ApplicationId app) =>
        shown.applicationWords.FirstOrDefault(w => w.application == app) is { } w ? Said.Key(w.word) : "";

    /// <summary>An application's id as the wire spells it, for an AutomationId.</summary>
    public static string WireName(ApplicationId? app) => app switch
    {
        ApplicationId.Memories => "MEMORIES",
        ApplicationId.Depot => "DEPOT",
        ApplicationId.Expenses => "EXPENSES",
        _ => "all",
    };

    public static FontIcon Glyph(string glyph) => new() { Glyph = glyph, FontSize = 16 };

    /// <summary>A list row: the kind's glyph, the name, a chevron where it enters.</summary>
    private static ListViewItem Row(string glyph, string name, string automationId, bool enters, Action open)
    {
        var line = new Grid { ColumnSpacing = 12 };
        line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        line.Children.Add(Glyph(glyph));
        var title = new TextBlock { Text = name, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
        Grid.SetColumn(title, 1);
        line.Children.Add(title);
        if (enters)
        {
            var chevron = Glyph("");
            chevron.FontSize = 12;
            Grid.SetColumn(chevron, 2);
            line.Children.Add(chevron);
        }
        var row = new ListViewItem { Content = line, Tag = open, MinHeight = 44 };
        AutomationProperties.SetAutomationId(row, automationId);
        AutomationProperties.SetName(row, name);
        return row;
    }

    /// <summary>A row that opens <paramref name="r"/> in its application.</summary>
    public static ListViewItem ObjectRow(ObjectRef r) =>
        Row(KindGlyph(r.kind), r.name, $"object:{r.name}", false, () => Act.Send(new Call.ShellOpen { Ref = r }));

    private static ListViewItem FolderRow(NodeRef n) =>
        Row(KindGlyph(n.kind), n.name, $"folder:{n.name}", true,
            () => Act.Send(new Call.ShellEnter { folder = new FolderRef { nodeId = n.nodeId, name = n.name } }));

    /// <summary>A section: its heading (if any), then its rows as one list whose item click opens the row.</summary>
    private static void Section(StackPanel content, string? heading, IEnumerable<ListViewItem> rows, bool apart = false)
    {
        if (heading is null && apart)
        {
            // a group without a heading under another (an application's rest under its Favorites or Recent) is set apart
            // by Fluent's divider, as a heading sets the others apart; without it the rows ran on as one list
            content.Children.Add(new Microsoft.UI.Xaml.Shapes.Rectangle
            {
                Height = 1,
                Margin = new Thickness(12, 12, 12, 8),
                Fill = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["DividerStrokeColorDefaultBrush"],
            });
        }
        if (heading is not null)
        {
            var title = new TextBlock { Text = heading, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], Margin = new Thickness(12, 8, 0, 0) };
            AutomationProperties.SetHeadingLevel(title, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
            content.Children.Add(title);
        }
        var list = new ListView { SelectionMode = ListViewSelectionMode.None, IsItemClickEnabled = true };
        foreach (var row in rows) list.Items.Add(row);
        list.ItemClick += (_, e) =>
        {
            // the clicked item is the row itself (a ListViewItem is its own container), or its content
            var row = e.ClickedItem as ListViewItem ?? (e.ClickedItem as FrameworkElement)?.Parent as ListViewItem;
            if (row?.Tag is Action open) open();
            else Log.Write($"a row's click reached no row ({e.ClickedItem?.GetType().Name})");
        };
        content.Children.Add(list);
    }

    private static (ScrollViewer Scroll, StackPanel Content) Column()
    {
        var content = new StackPanel { Spacing = 4, Padding = new Thickness(12, 0, 12, 24) };
        return (new ScrollViewer { Content = content, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled }, content);
    }

    private static TextBlock Empty()
    {
        var empty = new TextBlock { Text = Words.ListEmpty(), Margin = new Thickness(12, 24, 12, 0), Style = (Style)Application.Current.Resources["BodyTextBlockStyle"] };
        empty.Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        AutomationProperties.SetAutomationId(empty, "list-empty");
        return empty;
    }

    /// <summary>Home: the filters (where the core offers more than "all"), then the level's groups (Home.groups): a
    /// folder enters, an object opens in its application.</summary>
    public static UIElement Home(ShellModelState state, ShellShown shown)
    {
        var (scroll, content) = Column();
        if (state.lists.filters.Count > 1) content.Children.Add(Filters(state, shown));
        if (shown.groups.Count == 0) content.Children.Add(Empty());
        foreach (var g in shown.groups)
        {
            var rows = new List<ListViewItem>();
            foreach (var n in g.nodes)
            {
                if (g.folders) rows.Add(FolderRow(n));
                else if (state.lists.objects.FirstOrDefault(o => o.scopeId == n.scopeId && o.nodeId == n.nodeId) is { } o) rows.Add(ObjectRow(o));
            }
            Section(content, g.heading is { } h ? Said.Key(h) : null, rows);
        }
        return scroll;
    }

    private static SelectorBar Filters(ShellModelState state, ShellShown shown)
    {
        var bar = new SelectorBar();
        AutomationProperties.SetAutomationId(bar, "filters");
        SelectorBarItem? chosen = null;
        foreach (var f in state.lists.filters)
        {
            var item = new SelectorBarItem { Text = f is { } app ? AppName(shown, app) : Words.FilterAll(), Tag = f };
            AutomationProperties.SetAutomationId(item, $"filter:{WireName(f)}");
            bar.Items.Add(item);
            if (f == state.shell.filter) chosen = item;
        }
        bar.SelectedItem = chosen;
        var current = state.shell.filter;
        // attached after the selection the state says, so drawing it sends nothing
        bar.SelectionChanged += (b, _) =>
        {
            if (b.SelectedItem is { } item && (ApplicationId?)item.Tag != current) Act.Send(new Call.ShellFilter { application = (ApplicationId?)item.Tag });
        };
        return bar;
    }

    /// <summary>An application's own list: Favorites, Recent, then the rest, each where it holds something.</summary>
    public static UIElement List(ArrangedList? list)
    {
        var (scroll, content) = Column();
        if (list is null || (list.favorites.Count == 0 && list.recent.Count == 0 && list.all.Count == 0))
        {
            content.Children.Add(Empty());
            return scroll;
        }
        var drawn = false;
        foreach (var (title, refs) in new (string?, IReadOnlyList<ObjectRef>)[] { (Words.DrawerFavorites(), list.favorites), (Words.DrawerRecent(), list.recent), (null, list.all) })
        {
            if (refs.Count == 0) continue;
            Section(content, title, refs.Select(ObjectRow), apart: drawn);
            drawn = true;
        }
        return scroll;
    }
}
