// The shell (ADR 0122; ADR 0123's Consequences, the Windows app), Fluent's way, as Windows' own Settings and Explorer
// draw theirs: a NavigationView whose pane is Left, LeftCompact or LeftMinimal by the window's width (its own
// thresholds), holding the one switcher (spaces and tenants; the signed-in member's picture beside them) and the drawer's
// New below it (ADR 0122 decision 2: what it makes lands at the space's top, ShellShown.drawerCreates) as its header,
// Search as its AutoSuggestBox (Ctrl+E, Ctrl+F; SearchBox, its chips a row at the top of the content), Home, Favorites, Recent and the applications as its items, and Settings
// at its foot, which opens the account's Settings (AccountSettingsPage) in the content. The content shows what the core's
// shell says is open — Home, an application's list (or the application's own, the registry's ApplicationList), an
// object's page the registry holds for its kind (ADR 0154 decision 3: the body an application registered — a Pot, a file,
// a drop box, a Memory or a chapter — in the core's frame: the header and its hero, the notices, the bar), or an object
// this app does not draw yet — under its title and a CommandBar, the level's head: the level's New first and its tools
// (the space's trash, the view switch) after the header's commands, as the core's LevelPlan places them (ShellShown.level,
// ADR 0154 decision 2); a
// kept link's page (BookmarkPage, ADR 0150 decision 6; the shell's state names it, Shell.bookmark) shows there too under
// its header (Open on its own page among its commands), opened by its row and left by Back; an
// open Pot's Download is among its secondary commands (the bar's own More), an InfoBar under the bar says how a download
// went, and another follows a file's Save a copy with its progress and Cancel. A list keeps where it was scrolled to when
// the shell draws it anew — after an object opened from it closed, after an update — by its place (ListScrolls).
//
// Offline (ADR 0140 decision 10) an InfoBar under the bar says so, as of when (ShellShown.offlineBanner), not closable while
// it lasts, with the count of the writes made offline that wait to be sent under it (ShellShown.queuedBanner, ADR 0153);
// New offers offline only what queues and the header's other writes are gone (the core's). The reconnect's notices — what
// was sent, what needs the person — are said once (ShellShown.queuedSaid). An item no longer available offline
// (ShellModel.State's gone) is said once in an InfoBar of its own, then told seen (Shell.OfflineGoneSeen).
//
// Every state is the core's (ShellModel.State and ShellShown); every action one call. Which item is selected, whether
// back is enabled and what the title says follow the state, as Linux's shell.rs draws it.

namespace Veyq.Core;

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

internal sealed class ShellView
{
    private readonly NavigationView _nav;
    private readonly DropDownButton _switcher;
    /// <summary>The drawer's New below the switcher (DrawerSection.New, ShellShown.drawerCreates).</summary>
    private readonly DropDownButton _drawerNew;
    private readonly TextBlock _switcherTitle;
    private readonly TextBlock _switcherSubtitle;
    private readonly ProgressRing _switching;
    private readonly AutoSuggestBox _search;
    /// <summary>The search over every space (ADR 0146): the box's list, the chips' row, the model in the core.</summary>
    private readonly SearchBox _searchBox;
    private readonly TextBlock _title;
    private readonly CommandBar _commands;
    private readonly PersonPicture _me;
    private readonly InfoBar _said;
    /// <summary>The notices under the bar, each living as the core's kind says (ADR 0154 decision 4).</summary>
    private readonly NoticeBar _notices;
    /// <summary>"Offline — content as of …", open while the core shows the lists from the offline store.</summary>
    private readonly InfoBar _offline;
    private string _queuedSaid = "";
    /// <summary>The items no longer available offline, said once; and what it said last.</summary>
    private readonly InfoBar _gone;
    private string _goneSaid = "";
    /// <summary>A file's Save a copy under way: its progress and Cancel (Watched.Save, Save.Cancel), then how it ended.</summary>
    private readonly InfoBar _saving;
    /// <summary>What the uploads say once (a folder's outcome, a replace offered with its action).</summary>
    private readonly InfoBar _offer;
    /// <summary>The upload queue: its command in the bar while it lists anything, the pickers that fill it.</summary>
    private readonly Uploads _uploads;
    private readonly MusicBar _musicBar;
    private readonly ListScrolls _scrolls = new();
    /// <summary>The page of the open object's kind, as its application registered it (Registry.Page), while one is open.</summary>
    private KindBody? _page;
    /// <summary>A kept link's page while the shell's state names one (Shell.bookmark, its row opened it; back leaves it).</summary>
    private BookmarkPage? _bookmark;
    /// <summary>A person's profile while the shell's state names one (Shell.person, ADR 0144 decision 11).</summary>
    private PersonPage? _person;
    /// <summary>Each application's own list once drawn (Registry's ApplicationUi.List), kept while the shell stands.</summary>
    private readonly Dictionary<ApplicationId, ApplicationList> _lists = new();
    /// <summary>Whether the last state had an object open (a page closing back onto a list asks the rows' dates again).</summary>
    private bool _openBefore;
    /// <summary>The account's Settings while the pane's Settings item shows it, in place of what the shell says.</summary>
    private AccountSettingsPage? _account;
    private readonly Window _window;
    private readonly ContentControl _body;
    private readonly NavigationViewItem _settings;
    private readonly Action _manage;
    /// <summary>The header of the node on screen (ShellShown.headed), and one whose page left while its trash's Undo stands.</summary>
    private NodeHeader? _header;
    private NodeHeader? _undoing;
    private ShellShown? _shown;
    private ShellModelState? _state;
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
        // the drawer's New, as Outlook's pane starts with its New mail: its menu is what it makes (DrawDrawerNew)
        _drawerNew = CreateAction.Drawer(Words.DrawerNew(), "drawer-new");
        _drawerNew.Visibility = Visibility.Collapsed;
        var paneHeader = new StackPanel();
        paneHeader.Children.Add(_switcher);
        paneHeader.Children.Add(_drawerNew);

        _search = new AutoSuggestBox { PlaceholderText = Words.SearchHint(), QueryIcon = new SymbolIcon(Symbol.Find), UpdateTextOnSelect = false };
        AutomationProperties.SetAutomationId(_search, "search");
        AutomationProperties.SetName(_search, Words.TabSearch());
        // a result opened shows the content; a settings result opens the account's Settings at its section
        _searchBox = new SearchBox(_search)
        {
            Opened = inContent =>
            {
                // a node or a person shows in the content: Settings, where it showed, gives way
                if (inContent) CloseSettings();
                ClosePaneIfOverlay();
            },
            Settings = OpenSettingsAt,
        };

        // Settings at the foot, in the app's own words (the pane's built-in item says WinUI's): the account's page, in the
        // content as Windows' own apps show their settings
        _settings = new NavigationViewItem { Content = Words.DrawerSettings(), Icon = HomeView.Glyph(""), Tag = new Target.Settings() };
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
        _offline = new InfoBar { IsOpen = false, IsClosable = false, Severity = InfoBarSeverity.Warning, Margin = new Thickness(12, 0, 12, 4) };
        AutomationProperties.SetAutomationId(_offline, "offline-banner");
        _gone = new InfoBar { IsOpen = false, IsClosable = true, Severity = InfoBarSeverity.Informational, Margin = new Thickness(12, 0, 12, 4) };
        AutomationProperties.SetAutomationId(_gone, "offline-gone");
        _said = new InfoBar { IsOpen = false, IsClosable = true, Margin = new Thickness(12, 0, 12, 4) };
        AutomationProperties.SetAutomationId(_said, "said");
        _notices = new NoticeBar(_said);
        _saving = new InfoBar { IsOpen = false, IsClosable = true, Margin = new Thickness(12, 0, 12, 4) };
        AutomationProperties.SetAutomationId(_saving, "saving");
        _offer = new InfoBar { IsOpen = false, IsClosable = true, Margin = new Thickness(12, 0, 12, 4) };
        AutomationProperties.SetAutomationId(_offer, "uploads-said");
        _uploads = new Uploads(window, _offer) { Changed = DrawCommands, Opens = OpenQueued, Says = Say };
        // a level row's menu runs as the header of its node: its dialogs over this window, its Undo under the bar
        RowMenu.Window = window;
        RowMenu.Say = Say;
        RowMenu.UndoBar = _notices;
        _body = new ContentControl { HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch };
        var content = new Grid();
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        content.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        // the search's chips at the top of the content frame while one is set (ADR 0146 decision 12)
        var chips = (FrameworkElement)_searchBox.Chips;
        content.Children.Add(chips);
        Grid.SetRow(head, 1);
        content.Children.Add(head);
        Grid.SetRow(_offline, 2);
        content.Children.Add(_offline);
        Grid.SetRow(_gone, 3);
        content.Children.Add(_gone);
        Grid.SetRow(_said, 4);
        content.Children.Add(_said);
        Grid.SetRow(_saving, 5);
        content.Children.Add(_saving);
        Grid.SetRow(_offer, 6);
        content.Children.Add(_offer);
        Grid.SetRow(_body, 7);
        content.Children.Add(_body);
        // the music bar along the window's bottom edge while the account's player holds a queue (ADR 0142 decision 5)
        _musicBar = new MusicBar(SayForAWhile);
        Grid.SetRow(_musicBar.View, 8);
        content.Children.Add(_musicBar.View);

        _nav = new NavigationView
        {
            PaneDisplayMode = NavigationViewPaneDisplayMode.Auto,
            IsSettingsVisible = false,
            IsBackButtonVisible = NavigationViewBackButtonVisible.Auto,
            PaneHeader = paneHeader,
            AutoSuggestBox = _search,
            Content = content,
            IsTitleBarAutoPaddingEnabled = false,
            // no tooltip naming Ctrl+E pops up over the window whenever a key is pressed
            KeyboardAcceleratorPlacementMode = KeyboardAcceleratorPlacementMode.Hidden,
        };
        AutomationProperties.SetAutomationId(_nav, "shell");
        _nav.FooterMenuItems.Add(_settings);
        // LeftMinimal lays back and the pane's button over the content's top left: the title moves beside them
        _nav.DisplayModeChanged += (_, e) =>
        {
            var minimal = e.DisplayMode == NavigationViewDisplayMode.Minimal;
            head.Padding = minimal ? MinimalHead : Head;
            // the chips' row is the content's top: it moves beside them too
            chips.Margin = new Thickness(minimal ? MinimalHead.Left - Head.Left : 0, 0, 0, 0);
        };
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
        // back from Settings is back to what the shell shows
        _nav.BackRequested += (_, _) =>
        {
            if (_account is not null) CloseSettings();
            else Act.Send(new Call.ShellBack());
        };

        // F5 and Ctrl+R refresh an open Pot or Memory (its CommandBar's Refresh), else the shell's lists, as Explorer and Edge refresh
        foreach (var (key, modifiers) in new[] { (VirtualKey.F5, VirtualKeyModifiers.None), (VirtualKey.R, VirtualKeyModifiers.Control) })
        {
            var accelerator = new KeyboardAccelerator { Key = key, Modifiers = modifiers };
            accelerator.Invoked += (_, e) =>
            {
                e.Handled = true;
                if (_page?.Refresh is { } refresh) refresh.Run();
                else RefreshLists();
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

        public sealed record Settings : Target;
    }

    private void Choose(Target target)
    {
        if (target is Target.Settings)
        {
            OpenSettings();
            ClosePaneIfOverlay();
            return;
        }
        // anything else chosen leaves Settings: what the shell shows comes back, also where the choice changes nothing
        if (_account is not null) CloseSettings();
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

    /// <summary>Opens the account's Settings for the connection the shell shows, in the content.</summary>
    private void OpenSettings() => OpenSettingsAt(null);

    /// <summary>Opens the account's Settings scrolled to <paramref name="section"/> (SettingsSection.id, a search's
    /// settings result); a page already open scrolls there.</summary>
    private void OpenSettingsAt(string? section)
    {
        if (_account is { } open)
        {
            if (section is not null) open.ScrollTo(section);
            return;
        }
        if (_shown is not { } shown) return;
        _account = AccountSettingsPage.Open(_window, shown.connection, () => CloseSettings(), section);
        Redraw();
    }

    /// <summary>Edit profile on one's own person page: Settings opened at Your profile, the flow open in its frame.</summary>
    private void EditProfile()
    {
        OpenSettingsAt(AccountFlows.Id(AccountFlow.Profile));
        _account?.OpenFlow(AccountFlow.Profile);
    }

    /// <summary>Leaves Settings (back, another item, or the core closed it): the page closes, the shell's content returns.</summary>
    private void CloseSettings()
    {
        if (_account is null) return;
        _account.Dispose();
        _account = null;
        Redraw();
    }

    /// <summary>The kept link's page let go (closed in the core, its in-place link with it).</summary>
    private void DropBookmark()
    {
        _bookmark?.Dispose();
        _bookmark = null;
    }

    private void Redraw()
    {
        if (_state is not { } state || _shown is not { } shown) return;
        DrawPane(state, shown);
        DrawContent(state, shown);
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
        _state = state;
        Avatars.Listed($"{shown.connection}\n{shown.tenant.tenantId}", shown.pictures, shown.picturesRound);
        DrawOffline(state, shown);
        DrawSwitcher(state, shown);
        DrawPane(state, shown);
        DrawContent(state, shown);
        // the rows' dates: asked when the objects change, and again when a page closes back onto a list (an edited date)
        var back = _openBefore && state.shell.openObject is null;
        _openBefore = state.shell.openObject is not null;
        RowLines.Ask($"{shown.connection}\n{shown.tenant.tenantId}\n{string.Join(",", state.lists.objects.Select(o => o.nodeId))}", back, Redraw);
        if (first && shown.drawerOpensAtStart && _nav.DisplayMode != NavigationViewDisplayMode.Expanded) _nav.IsPaneOpen = true;
    }

    /// <summary>The offline banner, and each item no longer available offline said once, then told seen.</summary>
    private void DrawOffline(ShellModelState state, ShellShown shown)
    {
        // the writes made offline waiting to be sent, counted under the offline banner (ShellModel.queuedBanner)
        _offline.Message = string.Join("\n", new[] { shown.offlineBanner, shown.queuedBanner }.OfType<Notice>().Select(Said.Notice));
        _offline.IsOpen = shown.offlineBanner is not null || shown.queuedBanner is not null;
        // what the reconnect sent and what now needs the person, said once, then told seen (Shell.QueuedSaidSeen)
        var sent = string.Join("\n", shown.queuedSaid.Select(Said.Notice));
        if (sent != _queuedSaid)
        {
            _queuedSaid = sent;
            if (sent.Length > 0)
            {
                foreach (var n in shown.queuedSaid) Log.Write($"queued said: {n.msg}");
                Say(Spoken.Of(shown.queuedSaid, sent));
                Act.Send(new Call.ShellQueuedSaidSeen());
            }
        }
        // compared as said: a record's list compares by reference, and each update is read anew
        var said = string.Join("\n", state.gone.Select(Said.Notice));
        if (said == _goneSaid) return;
        _goneSaid = said;
        if (state.gone.Count == 0) return;
        foreach (var n in state.gone) Log.Write($"offline gone: {n.msg}");
        _gone.Message = said;
        _gone.IsOpen = true;
        Act.Send(new Call.ShellOfflineGoneSeen());
    }

    /// <summary>The switcher: the space shown and its tenant and server; the spaces, then the tenants (one row each: name
    /// and where it is), then Manage servers (ManageDialog). The space's trash is the level's (LevelTool.Trash).</summary>
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
        // the tenants, each one row: its name and where it is (ShellWords.switcher, owner 2026-11-19)
        if (shown.switcher.Count > 0) menu.Items.Add(new MenuFlyoutSeparator());
        foreach (var e in shown.switcher)
        {
            var item = new RadioMenuFlyoutItem { Text = e.line, GroupName = "tenant", IsChecked = e.current };
            AutomationProperties.SetAutomationId(item, $"tenant:{e.name}");
            var (connection, tenant, current) = (e.connection, e.tenant.tenantId, e.current);
            item.Click += (_, _) =>
            {
                if (current) return;
                Act.Send(new Call.SessionSwitchTo { connection = connection, tenantId = tenant });
            };
            menu.Items.Add(item);
        }
        menu.Items.Add(new MenuFlyoutSeparator());
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
        void Listed(string title, IReadOnlyList<ObjectRef> refs, string prefix)
        {
            items.Add(new NavigationViewItemHeader { Content = title });
            foreach (var r in refs) Add(Item(r.name, HomeView.KindGlyph(r.kind), $"{prefix}:{r.name}", new Target.Object(r)), Opened(r));
        }
        // the drawer's sections, every one in the core's order (ShellModel.drawerSections; only those that hold something,
        // owner 2026-11-19): New below the switcher, then Favorites, Recent and the applications
        var offersNew = false;
        foreach (var section in shown.drawerSections)
        {
            Action draw = section switch
            {
                DrawerSection.New => () => offersNew = true,
                DrawerSection.Favorites => () => Listed(Words.DrawerFavorites(), state.lists.drawer.favorites, "favorite"),
                DrawerSection.Recent => () => Listed(Words.DrawerRecent(), state.lists.drawer.recent, "recent"),
                DrawerSection.Applications => () =>
                {
                    items.Add(new NavigationViewItemHeader { Content = Words.DrawerApplications() });
                    foreach (var app in state.lists.applications)
                    {
                        var chosen = shell.application == app && shell.openObject is null;
                        Add(Item(HomeView.AppName(shown, app), HomeView.AppGlyph(app), $"app:{HomeView.WireName(app)}", new Target.Application(app)), chosen);
                    }
                },
            };
            draw();
        }
        DrawDrawerNew(offersNew ? shown : null);
        _drawing = true;
        try
        {
            _nav.MenuItemsSource = items;
            _nav.SelectedItem = _account is not null ? _settings : selected;
        }
        finally
        {
            _drawing = false;
        }
    }

    /// <summary>The drawer's New (DrawerSection.New) where <paramref name="shown"/> offers it: each of drawerCreates, made
    /// at the space's top whatever the shell shows — Home is chosen first (ADR 0122 decision 2), as Android's requestNew.</summary>
    private void DrawDrawerNew(ShellShown? shown)
    {
        if (shown is null)
        {
            _drawerNew.Visibility = Visibility.Collapsed;
            _drawerNew.Flyout = null;
            return;
        }
        var menu = new MenuFlyout { Placement = Microsoft.UI.Xaml.Controls.Primitives.FlyoutPlacementMode.BottomEdgeAlignedLeft };
        foreach (var c in shown.drawerCreates)
        {
            var create = Creating(c.create, TopLevel, top: true);
            var item = new MenuFlyoutItem { Text = Said.Key(c.word), IsEnabled = create is not null };
            AutomationProperties.SetAutomationId(item, $"drawer-new:{c.create}");
            item.Click += (_, _) =>
            {
                ClosePaneIfOverlay();
                // at the space's top: Home there first, then what it makes lands where the shell now is
                Act.Send(new Call.ShellChoose { application = null });
                create?.Invoke();
            };
            menu.Items.Add(item);
        }
        _drawerNew.Flyout = menu;
        _drawerNew.Visibility = Visibility.Visible;
    }

    private void DrawContent(ShellModelState state, ShellShown shown)
    {
        if (_account is { } account)
        {
            // the account's Settings in place of what the shell shows; back leaves it
            _shown = shown;
            _title.Text = Words.DrawerSettings();
            _nav.IsBackEnabled = true;
            CloseLists();
            if (!ReferenceEquals(_body.Content, account.Page)) _body.Content = account.Page;
            DrawCommands();
            return;
        }
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
        if (shell.person is { } person)
        {
            // a person's profile over whatever was on screen: a screen of the core's own, back the shell's
            _title.Text = Words.PeopleProfile();
            DropPage();
            DropBookmark();
            if (_person is null || _person.UserId != person)
            {
                _person?.Dispose();
                _person = new PersonPage(_window, person, mine: shown.me == person);
                Log.Write("shell: a person's profile");
            }
            body = _person.Page;
            CloseLists();
            if (!ReferenceEquals(_body.Content, body)) _body.Content = body;
            DrawCommands();
            return;
        }
        if (_person is not null)
        {
            _person.Dispose();
            _person = null;
        }
        if (shell.bookmark is { } kept)
        {
            // a kept link's page over what was on screen (ADR 0150 decision 6, #1032): back returns there
            _title.Text = kept.name;
            DropPage();
            if (_bookmark is null || _bookmark.Node.nodeId != kept.nodeId || _bookmark.Node.scopeId != kept.scopeId)
            {
                DropBookmark();
                // its own entries join its header's plan once it opened (Header.Facts' bookmark)
                _bookmark = new BookmarkPage(_window, kept) { Changed = DrawCommands, Opened = id => _header?.Bookmark(id) };
                Log.Write("shell: a kept link's page");
            }
            CloseLists();
            if (!ReferenceEquals(_body.Content, _bookmark.Page)) _body.Content = _bookmark.Page;
            DrawCommands();
            return;
        }
        DropBookmark();
        ApplicationList? drew = null;
        if (shell.openObject is { } opened && Registry.Shared.Page(opened.kind)?.Body is { } page)
        {
            // the opened object's page: the body its kind's application registered, in the core's frame
            if (_page is null || !_page.Shows(opened))
            {
                DropPage();
                _page = page(opened, HostForPage());
                if (_page.SaysUnderBar) _said.IsOpen = false;
                // the tray's Open of a held entry: its conflict's editor once its page opened
                if (_heldEntry is { } held && opened.nodeId == _heldPot)
                {
                    _heldEntry = null;
                    _page.OpenHeld(held);
                }
            }
            _page.Hero?.Place(_header?.Hero);
            _page.Tabs(shown.tabs, shown.selectedTab);
            body = _page.Page;
        }
        else
        {
            DropPage();
            if (shell.openObject is { } other) body = NotBuilt(other);
            else if (shell.application is { } chosen && ListOf(chosen) is { } own && own.Draw(state, shown) is { } drawn)
            {
                drew = own;
                body = drawn;
            }
            else
            {
                // a folder's level starts with its head, as the Pot's and the Memory's pages (none on a space's root)
                body = shell.application is not null ? HomeView.List(shown.list, shown.view == ViewModeShown.Tiles) : HomeView.Home(state, shown, LevelDrops(), _header?.Hero);
                if (body is ScrollViewer list) Keep(list, ListScrolls.Place(shell.application, shell.space, shell.path.Count > 0 ? shell.path[^1].nodeId : null, shell.filter));
            }
        }
        CloseLists(drew);
        if (!ReferenceEquals(_body.Content, body)) _body.Content = body;
        DrawCommands();
    }

    /// <summary>Application <paramref name="app"/>'s own list (Registry's ApplicationUi.List), made once; null: core's.</summary>
    private ApplicationList? ListOf(ApplicationId app)
    {
        if (_lists.TryGetValue(app, out var made)) return made;
        if (Registry.Shared.Application(app)?.List is not { } list) return null;
        made = list(new ListHost
        {
            Window = _window,
            Keep = Keep,
            Shown = () => _shown,
            Showing = e => ReferenceEquals(_body.Content, e),
        });
        _lists[app] = made;
        return made;
    }

    /// <summary>Every application's own list but <paramref name="drawn"/> (the one on screen) let go of what it holds open.</summary>
    private void CloseLists(ApplicationList? drawn = null)
    {
        foreach (var list in _lists.Values)
        {
            if (!ReferenceEquals(list, drawn)) list.Close();
        }
    }

    /// <summary>What the frame hands a kind's page (Registry's KindPage.Body).</summary>
    private PageHost HostForPage() => new()
    {
        Window = _window,
        Say = Say,
        Said = _notices,
        Saving = Saving,
        // read at the call: the header of the node on screen then
        Facts = (facts, file, gallery) => _header?.Facts(facts, file, gallery),
        Changed = DrawCommands,
        RefreshLists = RefreshLists,
    };

    /// <summary>The open object's page let go (closed in the core), its hero handed back; what it said under the bar
    /// goes with it where it is the page's own (KindBody.SaysUnderBar).</summary>
    private void DropPage()
    {
        if (_page is { SaysUnderBar: true }) _said.IsOpen = false;
        _page?.Dispose();
        _page = null;
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
            // a folder's page knows all it needs; a Pot's and a file's tell once they are read
            var facts = Registry.Shared.Page(node.kind) is { FactsWhenRead: true } ? null : new Facts { canWrite = true, hasContent = true };
            _header = new NodeHeader(_window, node, facts, DrawCommands, Say) { UndoBar = _notices };
        }
    }

    /// <summary>The CommandBar, the level's head as the web's LevelView heads it: the level's New first (ShellShown.level,
    /// the core's LevelPlan: none on an object's page) — a Pot's or a Memory's page its own Add instead —, the upload
    /// queue, the top level's share, the header's primary commands, the page's Refresh, then the level's tools in the
    /// plan's order (the space's trash, the view switch); the header's secondary ones behind the bar's own More. Every
    /// entry of each plan is drawn (ADR 0154 decision 2).</summary>
    private void DrawCommands()
    {
        _commands.PrimaryCommands.Clear();
        _commands.SecondaryCommands.Clear();
        // one's own profile: Edit profile (ADR 0148 decision 10), the same page as Settings' Your profile
        if (_person is { Mine: true } && _account is null)
        {
            var edit = new AppBarButton { Icon = new SymbolIcon(Symbol.Edit), Label = Words.ProfileEdit() };
            AutomationProperties.SetAutomationId(edit, "person-edit-profile");
            edit.Click += (_, _) => EditProfile();
            _commands.PrimaryCommands.Add(edit);
        }
        // a person's profile is no level: nothing is created or acted on there
        if (_shown is not { } shown || _account is not null || _person is not null) return;
        var page = _page;
        // the level's New: what the core offers here (ShellModel.levelPlan: a folder, a Pot, a Memory, a kept link, files
        // and a folder uploaded), a menu of its entries
        if (shown.level is { creates.Count: > 0 } level)
        {
            _commands.PrimaryCommands.Add(CreateAction.Menu(Words.DrawerNew(), "new",
                level.creates.Select(c => (Said.Key(c.word), $"new:{c.create}", Creating(c.create, Level)))));
        }
        // the page's own creating action (a Pot's Add entry, a Memory's Add chapter), first, where it offers one
        if (page?.Creating() is { } creating) _commands.PrimaryCommands.Add(creating);
        // the upload queue while it lists anything
        if (_uploads.Button is { } uploads) _commands.PrimaryCommands.Add(uploads);
        // a space's top level is no link's root: its share offers "Add files only" alone (the web's level share)
        if (shown.topLevelShare && _state?.shell.space is { } space)
        {
            var share = new AppBarButton { Icon = new SymbolIcon(Symbol.Share), Label = Words.ShareRoleUploader() };
            AutomationProperties.SetAutomationId(share, "top-share");
            share.Click += (_, _) => ShareDialog.OpenTopLevel(_window, space);
            _commands.PrimaryCommands.Add(share);
        }
        if (_header is not null)
        {
            var kept = _bookmark;
            var (primary, secondary) = _header.Commands(new PageHooks
            {
                Download = page is null ? null : () => page.Download(WinRT.Interop.WindowNative.GetWindowHandle(_window)),
                Replace = page is null ? null : page.Replace,
                Upload = page is null ? null : page.Upload,
                Music = page is null ? null : page.Music,
                Banner = page is null ? null : page.Banner,
                OwnPage = kept is null ? null : () => kept.OwnPage(false),
                ReportShared = kept is null ? null : () => kept.OwnPage(true),
            });
            foreach (var c in primary) _commands.PrimaryCommands.Add(c);
            foreach (var c in secondary) _commands.SecondaryCommands.Add(c);
        }
        if (page?.Refresh is { } pageRefresh)
        {
            // the page's Refresh: desktops have no pull gesture (F5 and Ctrl+R, as Explorer and Edge refresh)
            var refresh = new AppBarButton { Icon = new SymbolIcon(Symbol.Refresh), Label = Words.PotRefresh() };
            AutomationProperties.SetAutomationId(refresh, pageRefresh.AutomationId);
            refresh.Click += (_, _) => pageRefresh.Run();
            _commands.PrimaryCommands.Add(refresh);
        }
        // the level's tools, in the plan's order (the web's tools row: the trash, the view switch)
        foreach (var tool in shown.level?.tools ?? [])
        {
            var command = tool switch
            {
                LevelTool.Trash => TrashCommand(),
                LevelTool.View => LevelTiles.ViewCommand(shown),
            };
            if (command is not null) _commands.PrimaryCommands.Add(command);
        }
    }

    /// <summary>The level's trash (LevelTool.Trash): the space's trash sheet (TrashDialog).</summary>
    private AppBarButton? TrashCommand() =>
        _state?.shell.space is { } space ? Command(Words.TrashTitle(), "level-trash", Symbol.Delete, () => TrashDialog.Open(_window, space)) : null;

    /// <summary>What New's entry <paramref name="c"/> does at the level <paramref name="at"/> names (read when it runs):
    /// a folder made where the shell is, a kept link made there, files or a folder uploaded there, an application's
    /// object through its own dialog (the registry's ApplicationUi.Creates; null where this build registers none). The drawer's
    /// New (<paramref name="top"/>) makes its folder at the space's top, whatever level the shell still shows.</summary>
    private Action? Creating(Create c, Func<UploadLevel?> at, bool top = false) => c switch
    {
        Create.Folder => () => NewFolder(top),
        // New → Bookmark (ADR 0150 decision 6, inbox #1020): the made bookmark shows in the level read anew
        Create.Bookmark => () => { if (at() is { } l) BookmarkCreateDialog.Open(_window, l.ScopeId, l.ParentId, _ => RefreshLists()); },
        Create.UploadFiles => () => { if (at() is { } l) _uploads.PickFiles(l, Say); },
        Create.UploadFolder => () => { if (at() is { } l) _uploads.PickFolder(l, Say); },
        Create.Pot or Create.Memory or Create.Chapter => Registry.Shared.Create(c) is { } open ? () => open(HostForCreate()) : null,
    };

    private static AppBarButton Command(string label, string automationId, Symbol symbol, Action act)
    {
        var button = new AppBarButton { Label = label, Icon = new SymbolIcon(symbol) };
        AutomationProperties.SetAutomationId(button, automationId);
        button.Click += (_, _) => act();
        return button;
    }

    /// <summary>The shell's lists read anew from the server (Shell.Refresh): a drop box's page, Home's level and every list follow.</summary>
    private static async void RefreshLists()
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.ShellRefresh());
            Log.Write($"Shell.Refresh: {answer.GetType().Name}");
        }
        catch (Exception e)
        {
            Log.Write($"Shell.Refresh failed ({e.GetType().Name})");
        }
    }

    /// <summary>The level shown, where New's uploads go: its space, the folder there (null: its top), the batch's label.</summary>
    private UploadLevel? Level()
    {
        if (_state is not { } state || _shown is not { } shown || state.shell.space is not { } space) return null;
        var folder = state.shell.path.Count > 0 ? state.shell.path[^1] : null;
        return new UploadLevel(space, folder?.nodeId, folder?.name ?? Said.Piece(shown.spaceName));
    }

    /// <summary>The shown space's top, where the drawer's New makes what it makes.</summary>
    private UploadLevel? TopLevel() =>
        _state?.shell.space is { } space && _shown is { } shown ? new UploadLevel(space, null, Said.Piece(shown.spaceName)) : null;

    /// <summary>Where a drop onto the level shown goes: the level where the core offers one (ShellShown.dropsHere), each
    /// folder's row it names (ShellShown.dropRows); none where it offers neither.</summary>
    private Drops? LevelDrops()
    {
        if (_shown is not { } shown || (!shown.dropsHere && shown.dropRows.Count == 0)) return null;
        return Level() is { } level ? new Drops(_window, level.Label, shown.dropsHere, shown.dropRows.ToHashSet(), Say) : null;
    }

    /// <summary>What New hands an application's create (Registry's ApplicationUi.Creates).</summary>
    private CreateHost HostForCreate() => new() { Window = _window, Say = Say, State = () => _state };

    /// <summary>New's folder (Shell.CreateFolder), named in a dialog the core's answer keeps open with its problem
    /// (ADR 0154 decision 1), at the space's top where <paramref name="top"/>; a notice is said as it closes, a folder made
    /// offline (queued) reads as made.</summary>
    private async void NewFolder(bool top)
    {
        try
        {
            var named = await Dialogs.AskNamed(_window, "new-folder", Words.FolderNew(), Words.FolderName(), "", Words.FolderCreate(),
                typed => Dialogs.Named(new Call.ShellCreateFolder { name = typed, top = top }, "Shell.CreateFolder"));
            if (named?.notice is { } n) Say(Spoken.Of(n));
        }
        catch (Exception e)
        {
            Log.Write($"new folder failed ({e.GetType().Name})");
        }
    }

    private string? _heldEntry;
    private string? _heldPot;

    /// <summary>The tray's Open of a held write made offline (Answer.QueuedOpens): where the core says it is decided — a
    /// Pot entry's conflict editor, the page a create or capture time was made on, the node whose name was taken.</summary>
    private void OpenQueued(QueuedOpen open)
    {
        switch (open)
        {
            case QueuedOpen.QueuedOpenEntry e:
                if (_page is { } shownPage && shownPage.NodeId == e.pot) shownPage.OpenHeld(e.write);
                else
                {
                    (_heldEntry, _heldPot) = (e.write, e.pot);
                    OpenNode(e.scope, e.pot);
                }
                break;
            case QueuedOpen.QueuedOpenRename r:
                // the node's page or folder, whose header offers Rename (the name the person chose is the tray's line)
                OpenNode(r.scope, r.node);
                break;
            case QueuedOpen.QueuedOpenPlace p:
                OpenNode(p.scope, p.node);
                break;
        }
    }

    /// <summary>Node <paramref name="node"/> of space <paramref name="scope"/> opened where it lives (Shell.OpenAt: the
    /// core finds it — an object in its application, a folder on Home; null: the space's top).</summary>
    private static void OpenNode(string scope, string? node) => Act.Send(new Call.ShellOpenAt { scopeId = scope, nodeId = node });

    /// <summary>How an action went, under the bar for as long as its kind lives (a success the core's while, a refusal
    /// until it is closed) or until the object leaves.</summary>
    public void Say(Spoken said) => _notices.Say(said);

    /// <summary>What the music bar says (a queue none of whose tracks played here), under the bar for the core's
    /// "nothing plays" while (the web's AudioBar).</summary>
    private void SayForAWhile(string message) => _notices.SayFor(message, Lives.NothingPlays);

    /// <summary>A file's Save a copy under way: the InfoBar says how far it is with a ProgressBar and Cancel
    /// (Save.Cancel), then how it ended; it stays when the page leaves.</summary>
    private void Saving(long save)
    {
        var bar = new ProgressBar { Minimum = 0, Maximum = 1, Margin = new Thickness(0, 0, 0, 8) };
        AutomationProperties.SetAutomationId(bar, "saving-progress");
        var cancel = new Button { Content = Words.DownloadCancel() };
        AutomationProperties.SetAutomationId(cancel, "saving-cancel");
        cancel.Click += (_, _) =>
        {
            Log.Write($"save {save}: cancel pressed");
            Act.Send(new Call.SaveCancel { save = save });
        };
        _saving.Severity = InfoBarSeverity.Informational;
        _saving.Message = "";
        _saving.Content = bar;
        _saving.ActionButton = cancel;
        _saving.IsClosable = false;
        _saving.IsOpen = true;
        Watch? watch = null;
        var logged = -1;
        watch = Core.Watch(new Watched.Save { save = save }, update =>
        {
            if (update is not Update.Save { shown: { } s }) return;
            if (s.end is null)
            {
                _saving.Message = Said.Notice(s.notice);
                bar.Value = s.fraction;
                // a line in the log each tenth, for a pass to read the progress from
                var tenth = (int)Math.Floor(s.fraction * 10);
                if (tenth != logged)
                {
                    logged = tenth;
                    Log.Write($"save {save}: {s.written} of {s.total} B");
                }
                return;
            }
            Log.Write($"save {save} ended {s.end}: {s.written} of {s.total} B");
            _saving.Content = null;
            _saving.ActionButton = null;
            _saving.IsClosable = true;
            _saving.Severity = s.end == SaveEnd.Saved ? InfoBarSeverity.Success : s.end == SaveEnd.Cancelled ? InfoBarSeverity.Informational : InfoBarSeverity.Error;
            _saving.Message = Said.Notice(s.notice);
            watch?.Dispose();
        }, e => Log.Write($"a save's update: {e.Message}"));
    }

    /// <summary>The list of <paramref name="place"/> put back where that place's list stood, and followed from now on.</summary>
    private void Keep(ScrollViewer list, string place)
    {
        list.ViewChanged += (_, _) =>
        {
            // a list leaving the window says nothing of where it stood
            if (list.IsLoaded) _scrolls.Moved(place, list.VerticalOffset);
        };
        if (_scrolls.At(place) is not { } target) return;
        var pending = true;
        void Restore(bool settled)
        {
            if (!pending) return;
            if (ListScrolls.PutBack(target, list.ExtentHeight, list.ViewportHeight, settled) is not { } at) return;
            pending = false;
            list.ChangeView(null, at, null, disableAnimation: true);
            Log.Write($"list {place} put back at {at:0}");
        }
        // put back once it is laid out tall enough to stand there; a list grown shorter meanwhile stands at its end
        list.SizeChanged += (_, _) => Restore(false);
        list.LayoutUpdated += (_, _) => Restore(false);
        list.Loaded += (_, _) =>
        {
            var timer = _window.DispatcherQueue.CreateTimer();
            timer.Interval = TimeSpan.FromMilliseconds(400);
            timer.IsRepeating = false;
            timer.Tick += (_, _) => Restore(true);
            timer.Start();
        };
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
