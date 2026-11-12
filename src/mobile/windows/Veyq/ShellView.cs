// The shell (ADR 0122; ADR 0123's Consequences, the Windows app), Fluent's way, as Windows' own Settings and Explorer
// draw theirs: a NavigationView whose pane is Left, LeftCompact or LeftMinimal by the window's width (its own
// thresholds), holding the one switcher (spaces and tenants; the signed-in member's picture beside them) as its header,
// Search as its AutoSuggestBox (Ctrl+E, Ctrl+F), Home, Favorites, Recent and the applications as its items, and Settings
// at its foot. The content shows what the core's shell says is open — Home, an application's list, a Pot, or an object
// this app does not draw yet — under its title and a CommandBar whose first command is New; an open Pot's Download is
// among its secondary commands (the bar's own More), and an InfoBar under the bar says how a download went.
//
// Every state is the core's (ShellModel.State and ShellShown); every action one call. Which item is selected, whether
// back is enabled and what the title says follow the state, as Linux's shell.rs draws it.
using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using ApplicationId = Veyq.Bridge.Wire.ApplicationId;
using Windows.System;

namespace Veyq;

internal sealed class ShellView
{
    private readonly NavigationView _nav;
    private readonly DropDownButton _switcher;
    private readonly TextBlock _switcherTitle;
    private readonly TextBlock _switcherSubtitle;
    private readonly ProgressRing _switching;
    private readonly AutoSuggestBox _search;
    private readonly TextBlock _title;
    private readonly CommandBar _commands;
    private readonly PersonPicture _me;
    private readonly InfoBar _said;
    private readonly Window _window;
    private readonly ContentControl _body;
    private readonly NavigationViewItem _settings;
    private readonly Action _manage;
    private PotPage? _pot;
    /// <summary>The header of the node on screen (ShellShown.headed), and one whose page left while its trash's Undo stands.</summary>
    private NodeHeader? _header;
    private NodeHeader? _undoing;
    private ShellShown? _shown;
    private IReadOnlyList<ObjectRef>? _hits;
    private bool _started;
    private bool _drawing;

    public UIElement Page => _nav;

    private static readonly Thickness Head = new(16, 8, 8, 4);
    private static readonly Thickness MinimalHead = new(100, 8, 8, 4);

    public ShellView(Window window, Action manage)
    {
        _window = window;
        _manage = manage;
        _switcherTitle = new TextBlock { Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], TextTrimming = TextTrimming.CharacterEllipsis };
        _switcherSubtitle = new TextBlock
        {
            Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
            TextTrimming = TextTrimming.CharacterEllipsis,
        };
        _switching = new ProgressRing { IsActive = false, Width = 16, Height = 16, Visibility = Visibility.Collapsed };
        var label = new StackPanel();
        label.Children.Add(_switcherTitle);
        label.Children.Add(_switcherSubtitle);
        // the signed-in member's picture where the core holds one, else the tenant's initials (as Linux's switcher)
        _me = new PersonPicture { Width = 24, Height = 24, VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetAutomationId(_me, "me");
        var switcherContent = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        switcherContent.Children.Add(_me);
        switcherContent.Children.Add(label);
        switcherContent.Children.Add(_switching);
        _switcher = new DropDownButton
        {
            Content = switcherContent,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Left,
            Margin = new Thickness(4, 0, 4, 0),
        };
        AutomationProperties.SetAutomationId(_switcher, "switcher");
        AutomationProperties.SetName(_switcher, Words.SwitcherOpen());
        ToolTipService.SetToolTip(_switcher, Words.SwitcherOpen());

        _search = new AutoSuggestBox { PlaceholderText = Words.SearchHint(), QueryIcon = new SymbolIcon(Symbol.Find), UpdateTextOnSelect = false };
        AutomationProperties.SetAutomationId(_search, "search");
        AutomationProperties.SetName(_search, Words.TabSearch());
        _search.TextChanged += (_, _) => Query(_search.Text);
        _search.QuerySubmitted += (_, e) =>
        {
            // the box hands over the item or, chosen by a click, its content: both carry the hit
            if (e.ChosenSuggestion is FrameworkElement { Tag: ObjectRef hit }) OpenHit(hit);
            else if (e.ChosenSuggestion is not null) Log.Write($"a chosen hit reached no hit ({e.ChosenSuggestion.GetType().Name})");
        };

        // Settings at the foot, in the app's own words (the pane's built-in item says WinUI's); it does nothing yet, as
        // on the other apps
        _settings = new NavigationViewItem { Content = Words.DrawerSettings(), Icon = HomeView.Glyph(""), SelectsOnInvoked = false };
        AutomationProperties.SetAutomationId(_settings, "settings");

        _title = new TextBlock { Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"], TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetAutomationId(_title, "title");
        AutomationProperties.SetHeadingLevel(_title, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level1);
        _commands = new CommandBar { DefaultLabelPosition = CommandBarDefaultLabelPosition.Right, IsDynamicOverflowEnabled = true, VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetAutomationId(_commands, "commands");
        var head = new Grid { Padding = Head, ColumnSpacing = 8 };
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.Children.Add(_title);
        Grid.SetColumn(_commands, 1);
        head.Children.Add(_commands);
        _said = new InfoBar { IsOpen = false, IsClosable = true, Margin = new Thickness(12, 0, 12, 4) };
        AutomationProperties.SetAutomationId(_said, "said");
        _body = new ContentControl { HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch };
        var content = new Grid();
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        content.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        content.Children.Add(head);
        Grid.SetRow(_said, 1);
        content.Children.Add(_said);
        Grid.SetRow(_body, 2);
        content.Children.Add(_body);

        _nav = new NavigationView
        {
            PaneDisplayMode = NavigationViewPaneDisplayMode.Auto,
            IsSettingsVisible = false,
            IsBackButtonVisible = NavigationViewBackButtonVisible.Auto,
            PaneHeader = _switcher,
            AutoSuggestBox = _search,
            Content = content,
            IsTitleBarAutoPaddingEnabled = false,
            // no tooltip naming Ctrl+E pops up over the window whenever a key is pressed
            KeyboardAcceleratorPlacementMode = KeyboardAcceleratorPlacementMode.Hidden,
        };
        AutomationProperties.SetAutomationId(_nav, "shell");
        _nav.FooterMenuItems.Add(_settings);
        // LeftMinimal lays back and the pane's button over the content's top left: the title moves beside them
        _nav.DisplayModeChanged += (_, e) => head.Padding = e.DisplayMode == NavigationViewDisplayMode.Minimal ? MinimalHead : Head;
        // A choice is a selection: a click, the keyboard or UI Automation's SelectionItem (a pane item has no Invoke)
        // all select. ItemInvoked comes first, before the selection moves, so it acts only for the item already
        // selected (Home again, out of a folder), which selects nothing new; a selection the state draws sends nothing.
        _nav.ItemInvoked += (_, e) =>
        {
            if (ReferenceEquals(e.InvokedItemContainer, _nav.SelectedItem) && e.InvokedItemContainer?.Tag is Target target) Choose(target);
        };
        _nav.SelectionChanged += (_, e) =>
        {
            if (!_drawing && e.SelectedItemContainer?.Tag is Target target) Choose(target);
        };
        _nav.BackRequested += (_, _) => Act.Send(new Call.ShellBack());

        // F5 and Ctrl+R refresh an open Pot (its CommandBar's Refresh), as Explorer and Edge refresh
        foreach (var (key, modifiers) in new[] { (VirtualKey.F5, VirtualKeyModifiers.None), (VirtualKey.R, VirtualKeyModifiers.Control) })
        {
            var accelerator = new KeyboardAccelerator { Key = key, Modifiers = modifiers };
            accelerator.Invoked += (_, e) =>
            {
                if (_pot is not { } pot) return;
                e.Handled = true;
                pot.Refresh();
            };
            _nav.KeyboardAccelerators.Add(accelerator);
        }

        // Ctrl+E and Ctrl+F focus Search, as in Explorer; a closed pane opens for it
        foreach (var key in new[] { VirtualKey.E, VirtualKey.F })
        {
            var accelerator = new KeyboardAccelerator { Key = key, Modifiers = VirtualKeyModifiers.Control };
            accelerator.Invoked += (_, e) =>
            {
                e.Handled = true;
                FocusSearch();
            };
            _nav.KeyboardAccelerators.Add(accelerator);
        }
    }

    /// <summary>What a pane item opens.</summary>
    private abstract record Target
    {
        public sealed record Home : Target;

        public sealed record Application(ApplicationId Id) : Target;

        public sealed record Object(ObjectRef Ref) : Target;
    }

    private void Choose(Target target)
    {
        switch (target)
        {
            case Target.Home:
                Act.Send(new Call.ShellChoose { application = null });
                break;
            case Target.Application a:
                Act.Send(new Call.ShellChoose { application = a.Id });
                break;
            case Target.Object o:
                // the pane beside the content opens a top-level destination, no back (Shell.openAtTop)
                Act.Send(new Call.ShellOpenAtTop { Ref = o.Ref });
                break;
        }
        ClosePaneIfOverlay();
    }

    private void ClosePaneIfOverlay()
    {
        if (_nav.DisplayMode != NavigationViewDisplayMode.Expanded) _nav.IsPaneOpen = false;
    }

    private void FocusSearch()
    {
        if (_nav.DisplayMode != NavigationViewDisplayMode.Expanded) _nav.IsPaneOpen = true;
        _search.Focus(FocusState.Keyboard);
    }

    private void OpenHit(ObjectRef hit)
    {
        Act.Send(new Call.ShellOpenAtTop { Ref = hit });
        // the query is done once its hit opened, as Explorer's box is
        _search.Text = "";
        _search.IsSuggestionListOpen = false;
        ClosePaneIfOverlay();
    }

    /// <summary>Search over every object's name (ShellWords.search): its hits as the box's suggestions while a query stands.</summary>
    private async void Query(string query)
    {
        if (string.IsNullOrWhiteSpace(query))
        {
            _hits = null;
            _search.ItemsSource = null;
            return;
        }
        Answer answer;
        try
        {
            (answer, _) = await Core.CallAsync(new Call.SearchQuery { query = query });
        }
        catch (Exception e)
        {
            Log.Write($"Search failed ({e.GetType().Name})");
            return;
        }
        // only the answer to the query still standing
        if (_search.Text != query) return;
        if (answer is not Answer.Hits { hits: var hits })
        {
            Log.Write($"Search answered {answer.GetType().Name}");
            return;
        }
        _hits = hits;
        DrawHits();
    }

    private void DrawHits()
    {
        var items = new List<ListViewItem>();
        if (_hits is { Count: 0 })
        {
            var none = new ListViewItem { Content = Words.ListEmpty(), IsEnabled = false };
            AutomationProperties.SetAutomationId(none, "hits-empty");
            items.Add(none);
        }
        foreach (var h in _hits ?? [])
        {
            var line = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, Tag = h };
            line.Children.Add(HomeView.Glyph(HomeView.KindGlyph(h.kind)));
            line.Children.Add(new TextBlock { Text = h.name });
            var item = new ListViewItem { Content = line, Tag = h };
            AutomationProperties.SetAutomationId(item, $"hit:{h.name}");
            AutomationProperties.SetName(item, h.name);
            items.Add(item);
        }
        _search.ItemsSource = items;
        // hits anew under the lists' change open no list the person is not in
        _search.IsSuggestionListOpen = items.Count > 0 && _search.FocusState != FocusState.Unfocused;
    }

    /// <summary>A switch to another tenant is under way (Gate.Signed.switching).</summary>
    public void Switching(bool on)
    {
        _switching.IsActive = on;
        _switching.Visibility = on ? Visibility.Visible : Visibility.Collapsed;
        _switcher.IsEnabled = !on;
    }

    /// <summary>Draws the core's shell: <paramref name="state"/> with what the core says it shows beside it.</summary>
    public void Show(ShellModelState state, ShellShown shown)
    {
        var first = !_started;
        _started = true;
        Avatars.Listed($"{shown.connection}\n{shown.tenant.tenantId}", shown.pictures);
        DrawSwitcher(state, shown);
        DrawPane(state, shown);
        DrawContent(state, shown);
        // the lists changed under a standing query: its hits anew
        if (_hits is not null && _search.Text.Length > 0) Query(_search.Text);
        if (first && shown.drawerOpensAtStart && _nav.DisplayMode != NavigationViewDisplayMode.Expanded) _nav.IsPaneOpen = true;
    }

    /// <summary>The switcher: the space shown and its tenant and server; the spaces, then each server's tenants in its
    /// menu, then Manage servers (ManageDialog).</summary>
    private void DrawSwitcher(ShellModelState state, ShellShown shown)
    {
        _switcherTitle.Text = Said.Piece(shown.spaceName);
        _switcherSubtitle.Text = shown.switcherSubtitle;
        _me.DisplayName = shown.tenant.name;
        Avatars.Show(_me, shown.me);
        var menu = new MenuFlyout { Placement = Microsoft.UI.Xaml.Controls.Primitives.FlyoutPlacementMode.BottomEdgeAlignedLeft };
        foreach (var s in shown.spaces)
        {
            var name = Said.Piece(s.name);
            var item = new RadioMenuFlyoutItem { Text = name, GroupName = "space", IsChecked = s.space.scopeId == state.shell.space };
            AutomationProperties.SetAutomationId(item, $"space:{name}");
            var scope = s.space.scopeId;
            item.Click += (_, _) =>
            {
                Act.Send(new Call.ShellSwitchSpace { scopeId = scope });
                ClosePaneIfOverlay();
            };
            menu.Items.Add(item);
        }
        foreach (var server in shown.servers)
        {
            menu.Items.Add(new MenuFlyoutSeparator());
            foreach (var t in server.tenants)
            {
                var current = server.connection == shown.connection && t.tenantId == shown.tenant.tenantId;
                var item = new RadioMenuFlyoutItem { Text = t.name, GroupName = "tenant", IsChecked = current };
                ToolTipService.SetToolTip(item, server.label);
                AutomationProperties.SetAutomationId(item, $"tenant:{t.name}");
                var (connection, tenant) = (server.connection, t.tenantId);
                item.Click += (_, _) =>
                {
                    if (!current) Act.Send(new Call.SessionSwitchTo { connection = connection, tenantId = tenant });
                };
                menu.Items.Add(item);
            }
        }
        menu.Items.Add(new MenuFlyoutSeparator());
        // the space's trash (TrashDialog)
        var trash = new MenuFlyoutItem { Text = Words.TrashTitle(), Icon = new SymbolIcon(Symbol.Delete) };
        AutomationProperties.SetAutomationId(trash, "switcher-trash");
        var space = state.shell.space;
        trash.Click += (_, _) =>
        {
            if (space is not null) TrashDialog.Open(_window, space);
            ClosePaneIfOverlay();
        };
        trash.IsEnabled = space is not null;
        menu.Items.Add(trash);
        var manage = new MenuFlyoutItem { Text = Words.SwitcherManage() };
        AutomationProperties.SetAutomationId(manage, "switcher-manage");
        manage.Click += (_, _) =>
        {
            _manage();
            ClosePaneIfOverlay();
        };
        menu.Items.Add(manage);
        _switcher.Flyout = menu;
    }

    private NavigationViewItem Item(string text, string glyph, string automationId, Target? target)
    {
        var item = new NavigationViewItem { Content = text, Icon = HomeView.Glyph(glyph), Tag = target, IsEnabled = target is not null };
        AutomationProperties.SetAutomationId(item, automationId);
        return item;
    }

    private void DrawPane(ShellModelState state, ShellShown shown)
    {
        var shell = state.shell;
        bool Opened(ObjectRef r) => shell.openObject is { } o && o.scopeId == r.scopeId && o.nodeId == r.nodeId;
        var items = new List<object>();
        NavigationViewItem? selected = null;
        void Add(NavigationViewItem item, bool chosen)
        {
            items.Add(item);
            if (chosen) selected = item;
        }

        Add(Item(Words.DrawerHome(), "", "home", new Target.Home()), shown.isHome);
        foreach (var (title, refs, prefix) in new[] { (Words.DrawerFavorites(), state.lists.drawer.favorites, "favorite"), (Words.DrawerRecent(), state.lists.drawer.recent, "recent") })
        {
            items.Add(new NavigationViewItemHeader { Content = title });
            if (refs.Count == 0) Add(Item(Words.DrawerNothingYet(), "", $"{prefix}-none", null), false);
            foreach (var r in refs) Add(Item(r.name, HomeView.KindGlyph(r.kind), $"{prefix}:{r.name}", new Target.Object(r)), Opened(r));
        }
        items.Add(new NavigationViewItemHeader { Content = Words.DrawerApplications() });
        foreach (var app in state.lists.applications)
        {
            var chosen = shell.application == app && shell.openObject is null;
            Add(Item(HomeView.AppName(shown, app), HomeView.AppGlyph(app), $"app:{HomeView.WireName(app)}", new Target.Application(app)), chosen);
        }
        _drawing = true;
        try
        {
            _nav.MenuItemsSource = items;
            _nav.SelectedItem = selected;
        }
        finally
        {
            _drawing = false;
        }
    }

    private void DrawContent(ShellModelState state, ShellShown shown)
    {
        var shell = state.shell;
        // the title as every app heads the screen: the open object, the chosen application, the folder, the space
        _title.Text = shell.openObject is { } open ? open.name
            : shell.application is { } app ? HomeView.AppName(shown, app)
            : shell.path.Count > 0 ? shell.path[^1].name
            : Said.Piece(shown.spaceName);
        _nav.IsBackEnabled = shown.canGoBack;

        _shown = shown;
        DrawHeader(shown);
        UIElement body;
        if (shell.openObject is { kind: "EXPENSES_POT" } pot)
        {
            if (_pot is null || _pot.Ref != pot)
            {
                _pot?.Dispose();
                _pot = new PotPage(pot, _window, Say);
                // the Pot read: the header learns whether it takes writes (AppKinds.Facts)
                _pot.Read = canWrite => _header?.Facts(new Facts { canWrite = canWrite, hasContent = true });
                _pot.Changed = DrawCommands;
                _said.IsOpen = false;
            }
            _pot.Hero(_header?.Hero);
            _pot.Places(shown.tabs, shown.selectedTab);
            body = _pot.Page;
        }
        else
        {
            if (_pot is not null) _said.IsOpen = false;
            _pot?.Dispose();
            _pot = null;
            body = shell.openObject is { } other ? NotBuilt(other)
                : shell.application is not null ? HomeView.List(shown.list)
                : HomeView.Home(state, shown);
        }
        if (!ReferenceEquals(_body.Content, body)) _body.Content = body;
        DrawCommands();
    }

    /// <summary>The header of the node the content heads (ShellShown.headed): opened anew when the node (or its name)
    /// changed, none on a space's root. A header whose trash's Undo stands is kept until another takes its place.</summary>
    private void DrawHeader(ShellShown shown)
    {
        if (_header?.Node == shown.headed) return;
        if (_header is { Undoing: true })
        {
            _undoing?.Dispose();
            _undoing = _header;
        }
        else _header?.Dispose();
        _header = null;
        if (shown.headed is { } node)
        {
            // a folder's page knows all it needs; a Pot's tells once it is read
            var facts = node.kind == "EXPENSES_POT" ? null : new Facts { canWrite = true, hasContent = true };
            _header = new NodeHeader(_window, node, facts, DrawCommands, Say) { UndoBar = _said };
        }
    }

    /// <summary>The CommandBar: New first where the shell may create (canCreateHere) — on a Pot's page its Add entry
    /// instead —, then the header's primary commands;
    /// the header's secondary ones behind the bar's own More.</summary>
    private void DrawCommands()
    {
        _commands.PrimaryCommands.Clear();
        _commands.SecondaryCommands.Clear();
        if (_shown is not { } shown) return;
        var page = _pot;
        // a Pot's page creates one thing, an entry (as Android's and Apple's Pot screens): the level's New is not offered
        // beside it — two Add commands side by side would ask which one adds what
        if (page is null && shown.canCreateHere && shown.creates.Count > 0)
        {
            // a folder is made here; the others are later CRs' (their entry stays disabled)
            _commands.PrimaryCommands.Add(CreateAction.Menu(Words.DrawerNew(), "new",
                shown.creates.Select(c => (Said.Key(c.word), $"new:{c.create}", c.create == Create.Folder ? (Action?)NewFolder : null))));
        }
        // an open Pot's "Add entry" is the content's creating action, first, where its places show and it takes writes
        if (page is { CanAdd: true }) _commands.PrimaryCommands.Add(CreateAction.Button(Words.PotAdd(), "pot-add", page.AddEntry));
        if (_header is null) return;
        var (primary, secondary) = _header.Commands(() => page?.Download(WinRT.Interop.WindowNative.GetWindowHandle(_window), Say));
        foreach (var c in primary) _commands.PrimaryCommands.Add(c);
        if (page is not null)
        {
            // an open Pot's Refresh: desktops have no pull gesture (F5 and Ctrl+R, as Explorer and Edge refresh)
            var refresh = new AppBarButton { Icon = new SymbolIcon(Symbol.Refresh), Label = Words.PotRefresh() };
            AutomationProperties.SetAutomationId(refresh, "pot-refresh");
            refresh.Click += (_, _) => page.Refresh();
            _commands.PrimaryCommands.Add(refresh);
        }
        foreach (var c in secondary) _commands.SecondaryCommands.Add(c);
    }

    /// <summary>New's folder (Shell.CreateFolder), named in a dialog that asks again with the core's problem.</summary>
    private async void NewFolder() => await NewFolder("", null);

    private async System.Threading.Tasks.Task NewFolder(string name, string? problem)
    {
        var typed = await Dialogs.AskName(_window, "new-folder", Words.FolderNew(), Words.FolderName(), name, problem, Words.FolderCreate());
        if (typed is null) return;
        Answer answer;
        try
        {
            (answer, _) = await Core.CallAsync(new Call.ShellCreateFolder { name = typed });
        }
        catch (Exception e)
        {
            Log.Write($"Shell.CreateFolder failed ({e.GetType().Name})");
            return;
        }
        if (answer is Answer.Said { notice: { } n })
        {
            Log.Write($"new folder: {n.msg}");
            await NewFolder(typed, Said.Notice(n));
        }
        else Log.Write($"Shell.CreateFolder: {(answer is Answer.Said ? "Done" : answer.GetType().Name)}");
    }

    /// <summary>How an action went, under the bar until it is closed or the object leaves.</summary>
    public void Say(bool done, string message)
    {
        _said.ActionButton = null;
        _said.Severity = done ? InfoBarSeverity.Success : InfoBarSeverity.Error;
        _said.Message = message;
        _said.IsOpen = true;
    }

    private static UIElement NotBuilt(ObjectRef o)
    {
        var column = new StackPanel { Spacing = 12, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Padding = new Thickness(24) };
        var glyph = HomeView.Glyph(HomeView.KindGlyph(o.kind));
        glyph.FontSize = 48;
        column.Children.Add(glyph);
        var text = new TextBlock { Text = Words.NotBuilt(), TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center };
        AutomationProperties.SetAutomationId(text, "not-built");
        column.Children.Add(text);
        return column;
    }
}
