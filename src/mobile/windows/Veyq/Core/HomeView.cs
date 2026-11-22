// Home and an application's list (ADR 0122): Home's level as the core groups it — the folders, then the contents — under
// the filters the core offers (a SelectorBar), and an application's own list in its Favorites, Recent and the rest.
// Drawn as Explorer's Home draws its sections: a heading over each group's list — or, where the account chose Tiles
// (ShellShown.view, ADR 0149 decision 8), over each group's tiles (LevelTiles). A row's and a tile's context menu is the
// core's plan for its node (RowMenu). Linux's home.rs, in Fluent.

namespace Veyq.Core;

using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge.Wire;
using ApplicationId = Veyq.Bridge.Wire.ApplicationId;

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

    /// <summary>A kept link's row (ADR 0150 decision 6, #1032: Home.level lists it): no application's object, so its page
    /// opens over what is on screen (Shell.OpenBookmark), back returning there.</summary>
    private static ListViewItem BookmarkRow(NodeRef n) =>
        Row(KindGlyph(n.kind), n.name, $"bookmark:{n.name}", true, () => OpenBookmark(n), node: At(n));

    private static LevelTiles.Item BookmarkTile(NodeRef n) =>
        new(KindGlyph(n.kind), n.nodeId, n.name, null, $"bookmark:{n.name}", () => OpenBookmark(n), Node: At(n));

    private static void OpenBookmark(NodeRef n) => Act.Send(new Call.ShellOpenBookmark { node = At(n) });

    private static NodeAt At(NodeRef n) => new() { scopeId = n.scopeId, nodeId = n.nodeId, kind = n.kind, name = n.name };

    private static NodeAt At(ObjectRef r) => new() { scopeId = r.scopeId, nodeId = r.nodeId, kind = r.kind, name = r.name };

    /// <summary>A list row: the kind's glyph, the name, a chevron where it enters; <paramref name="node"/>'s menu (RowMenu).</summary>
    private static ListViewItem Row(string glyph, string name, string automationId, bool enters, Action open, Func<FontIcon, UIElement>? prefix = null, string? subtitle = null, NodeAt? node = null)
    {
        var line = new Grid { ColumnSpacing = 12 };
        line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        line.Children.Add(prefix is null ? Glyph(glyph) : prefix(Glyph(glyph)));
        var title = new TextBlock { Text = name, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
        FrameworkElement text = title;
        if (subtitle is not null)
        {
            // a Memory's date under its name (ADR 0143 decision 5), Fluent's two-line list item
            var lines = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            lines.Children.Add(title);
            lines.Children.Add(new TextBlock
            {
                Text = subtitle,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
                Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
            });
            text = lines;
        }
        Grid.SetColumn(text, 1);
        line.Children.Add(text);
        if (enters)
        {
            var chevron = Glyph("");
            chevron.FontSize = 12;
            Grid.SetColumn(chevron, 2);
            line.Children.Add(chevron);
        }
        var row = new ListViewItem { Content = line, Tag = open, MinHeight = 44 };
        AutomationProperties.SetAutomationId(row, automationId);
        AutomationProperties.SetName(row, subtitle is null ? name : $"{name}, {subtitle}");
        if (node is not null) RowMenu.Attach(row, node);
        return row;
    }

    /// <summary>A row that opens <paramref name="r"/> in its application: its kind's glyph, replaced by its cover where it
    /// has one (RowCovers), as every row on the phones.</summary>
    public static ListViewItem ObjectRow(ObjectRef r) =>
        Row(KindGlyph(r.kind), r.name, $"object:{r.name}", false, () => Act.Send(new Call.ShellOpen { Ref = r }),
            glyph => RowCovers.Prefix(glyph, r.nodeId),
            r.kind is "MEMORY" or "CHAPTER" ? RowLines.Line(r.nodeId) : null, At(r));

    /// <summary><paramref name="r"/> as a tile: what its row shows and does (LevelTiles).</summary>
    private static LevelTiles.Item ObjectTile(ObjectRef r) =>
        new(KindGlyph(r.kind), r.nodeId, r.name, r.kind is "MEMORY" or "CHAPTER" ? RowLines.Line(r.nodeId) : null, $"object:{r.name}",
            () => Act.Send(new Call.ShellOpen { Ref = r }), Node: At(r));

    private static LevelTiles.Item FolderTile(NodeRef n, Drops? drops) =>
        new(KindGlyph(n.kind), n.nodeId, n.name, null, $"folder:{n.name}",
            () => Act.Send(new Call.ShellEnter { folder = new FolderRef { nodeId = n.nodeId, name = n.name } }),
            drops is not null && drops.Rows.Contains(n.nodeId) ? tile => drops.OntoFolder(tile, n) : null, At(n));

    private static ListViewItem FolderRow(NodeRef n) =>
        Row(KindGlyph(n.kind), n.name, $"folder:{n.name}", true,
            () => Act.Send(new Call.ShellEnter { folder = new FolderRef { nodeId = n.nodeId, name = n.name } }),
            glyph => RowCovers.Prefix(glyph, n.nodeId), node: At(n));

    /// <summary>A section: its heading (if any), then its rows as one list whose item click opens the row.</summary>
    private static void Section(StackPanel content, string? heading, IEnumerable<ListViewItem> rows, bool apart = false) =>
        SectionOf(content, heading, apart, () =>
        {
            var list = new ListView { SelectionMode = ListViewSelectionMode.None, IsItemClickEnabled = true };
            foreach (var row in rows) list.Items.Add(row);
            list.ItemClick += (_, e) =>
            {
                // the clicked item is the row itself (a ListViewItem is its own container), or its content
                var row = e.ClickedItem as ListViewItem ?? (e.ClickedItem as FrameworkElement)?.Parent as ListViewItem;
                if (row?.Tag is Action open) open();
                else Log.Write($"a row's click reached no row ({e.ClickedItem?.GetType().Name})");
            };
            return list;
        });

    /// <summary>A section of tiles: its heading (if any), then its tiles at the core's frames.</summary>
    private static void TileSection(StackPanel content, string? heading, IReadOnlyList<LevelTiles.Item> tiles, int index, bool apart = false) =>
        SectionOf(content, heading, apart, () => LevelTiles.Grid(tiles, $"tiles:{index}"));

    private static void SectionOf(StackPanel content, string? heading, bool apart, Func<UIElement> body)
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
        content.Children.Add(body());
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

    /// <summary>Home: the filters (where the core offers more than "all"), a folder's head (<paramref name="hero"/>, its
    /// cover: the folder's header's, as the Pot's and the Memory's pages place it), then the level's groups
    /// (Home.groups): a folder enters, an object opens in its application. With <paramref name="drops"/>, files and folders
    /// dropped onto the level upload there, and onto a folder's row into that folder, where the core offers each (ADR 0123's Consequences, the Windows
    /// app's Depot whole). Drawn as tiles where the account chose them (ShellShown.view).</summary>
    public static UIElement Home(ShellModelState state, ShellShown shown, Drops? drops = null, UIElement? hero = null)
    {
        var tiles = shown.view == ViewModeShown.Tiles;
        // the level picks (the core's selection, inbox #1049): a row or a tile turns its node, no drop and no menu meanwhile
        var selecting = shown.selection;
        if (selecting is not null) drops = null;
        var (scroll, content) = Column();
        // the scroller holds only the column's height: the level takes a drop under its last row too
        scroll.Background = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Transparent);
        AutomationProperties.SetAutomationId(scroll, "level");
        drops?.OntoLevel(scroll);
        if (state.lists.filters.Count > 1) content.Children.Add(Filters(state, shown));
        if (hero is not null)
        {
            // the head is the header's one element: taken from the level drawn before; under the filters, as the phones place it
            if (hero is FrameworkElement { Parent: Panel panel }) panel.Children.Remove(hero);
            if (hero is FrameworkElement { Parent: Border border }) border.Child = null;
            content.Children.Add(hero);
        }
        if (shown.groups.Count == 0 && shown.queuedHere.Count == 0) content.Children.Add(Empty());
        var index = 0;
        foreach (var g in shown.groups)
        {
            var heading = g.heading is { } h ? Said.Key(h) : null;
            if (tiles)
            {
                var items = new List<LevelTiles.Item>();
                foreach (var n in g.nodes)
                {
                    LevelTiles.Item? item = null;
                    if (g.folders) item = FolderTile(n, drops);
                    else if (state.lists.objects.FirstOrDefault(o => o.scopeId == n.scopeId && o.nodeId == n.nodeId) is { } o) item = ObjectTile(o);
                    else if (n.kind == BookmarkPage.Kind) item = BookmarkTile(n);
                    if (item is null) continue;
                    // while the level picks, the tile turns its node instead of opening it
                    if (selecting is not null) item = item with { Open = Selection.Toggle(n.nodeId), Droppable = null, Node = null, Selected = selecting.selected.Contains(n.nodeId) };
                    items.Add(item);
                }
                TileSection(content, heading, items, index++);
                continue;
            }
            var rows = new List<ListViewItem>();
            foreach (var n in g.nodes)
            {
                if (selecting is not null)
                {
                    rows.Add(Row(KindGlyph(n.kind), n.name, $"select:{n.name}", false, Selection.Toggle(n.nodeId), glyph => Selection.Check(glyph, selecting.selected.Contains(n.nodeId))));
                    continue;
                }
                if (g.folders)
                {
                    var row = FolderRow(n);
                    // a folder's row takes a drop only where the core offers it (ShellShown.dropRows), never by its kind
                    drops?.OntoFolder(row, n);
                    rows.Add(row);
                }
                else if (state.lists.objects.FirstOrDefault(o => o.scopeId == n.scopeId && o.nodeId == n.nodeId) is { } o) rows.Add(ObjectRow(o));
                else if (n.kind == BookmarkPage.Kind) rows.Add(BookmarkRow(n));
            }
            Section(content, heading, rows);
        }
        // what was made here offline and waits to be sent (ShellShown.queuedHere, ADR 0153 decision 5): in place, marked
        if (shown.queuedHere.Count > 0) Section(content, null, shown.queuedHere.Select(QueuedRow), apart: true);
        if (selecting is not null)
        {
            // the selection's bar under the level while it picks
            var page = new Grid();
            page.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            page.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            page.Children.Add(scroll);
            var bar = Selection.Bar(selecting);
            Grid.SetRow(bar, 1);
            page.Children.Add(bar);
            return page;
        }
        return scroll;
    }

    /// <summary>A write made offline in the level shown, waiting to be sent: its title and its line (Waiting to send, or
    /// why it is held) as the core says them; the uploads tray holds its actions. A Memory made offline opens its page
    /// before it is sent (QueuedItem.page, #1040).</summary>
    private static ListViewItem QueuedRow(QueuedItem q) =>
        Row(q.op switch
        {
            QueuedOp.FolderCreate => KindGlyph("FOLDER"),
            QueuedOp.MemoryCreate => KindGlyph("MEMORY"),
            _ => "\uE8A5",
        }, Said.Notice(q.title), $"queued:{q.id}", false,
            () => { if (q.page is { } page) Act.Send(new Call.ShellOpen { Ref = page }); }, subtitle: Said.Notice(q.said));

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

    /// <summary>An application's own list: Favorites, Recent, then the rest, each where it holds something — as rows, or as
    /// tiles where the account chose them (<paramref name="tiles"/>).</summary>
    public static UIElement List(ArrangedList? list, bool tiles = false)
    {
        var (scroll, content) = Column();
        if (list is null || (list.favorites.Count == 0 && list.recent.Count == 0 && list.all.Count == 0))
        {
            content.Children.Add(Empty());
            return scroll;
        }
        var drawn = false;
        var index = 0;
        foreach (var (title, refs) in new (string?, IReadOnlyList<ObjectRef>)[] { (Words.DrawerFavorites(), list.favorites), (Words.DrawerRecent(), list.recent), (null, list.all) })
        {
            if (refs.Count == 0) continue;
            if (tiles) TileSection(content, title, refs.Select(ObjectTile).ToList(), index++, apart: drawn);
            else Section(content, title, refs.Select(ObjectRow), apart: drawn);
            drawn = true;
        }
        return scroll;
    }
}
