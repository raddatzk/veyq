// The shell (ADR 0122; ADR 0123's Consequences, the Windows app), Fluent's way, as Windows' own Settings and Explorer
// draw theirs: a NavigationView whose pane is Left, LeftCompact or LeftMinimal by the window's width (its own
// thresholds), holding the one switcher (spaces and tenants; the signed-in member's picture beside them) as its header,
// Search as its AutoSuggestBox (Ctrl+E, Ctrl+F; SearchBox, its chips a row at the top of the content), Home, Favorites, Recent and the applications as its items, and Settings
// at its foot, which opens the account's Settings (AccountSettingsPage) in the content. The content shows what the core's
// shell says is open — Home, an application's list, a Pot, a file
// (FilePage), a drop box (DropBoxPage), a Memory or a chapter (MemoryPage, its lightbox LightboxView), or an object this app does not draw yet — under its title and a CommandBar whose first command is New; a
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
    /// <summary>The search over every space (ADR 0146): the box's list, the chips' row, the model in the core.</summary>
    private readonly SearchBox _searchBox;
    private readonly TextBlock _title;
    private readonly CommandBar _commands;
    private readonly PersonPicture _me;
    private readonly InfoBar _said;
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
    private Microsoft.UI.Dispatching.DispatcherQueueTimer? _sayTimer;
    private readonly ListScrolls _scrolls = new();
    private FilePage? _file;
    /// <summary>A kept link's page while the shell's state names one (Shell.bookmark, its row opened it; back leaves it).</summary>
    private BookmarkPage? _bookmark;
    private DropBoxPage? _dropBox;
    private MemoryPage? _memory;
    /// <summary>A person's profile while the shell's state names one (Shell.person, ADR 0144 decision 11).</summary>
    private PersonPage? _person;
    /// <summary>The Memories application's places (its bar's tabs, ADR 0143 decision 6): the SelectorBar over the list or
    /// the Memories map, what the bar was drawn from, and the map while its tab is chosen.</summary>
    private Grid? _places;
    private string _placesDrawn = "";
    private readonly Grid _mapHolder = new();
    private readonly StackPanel _mapEmpty = new() { Spacing = 4, Padding = new Thickness(16), Margin = new Thickness(16), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, CornerRadius = new CornerRadius(8), Visibility = Visibility.Collapsed };
    private RasterMap? _memoriesMap;
    private bool _mapOpening;
    /// <summary>Whether the last state had an object open (a page closing back onto a list asks the rows' dates again).</summary>
    private bool _openBefore;
    /// <summary>The account's Settings while the pane's Settings item shows it, in place of what the shell says.</summary>
    private AccountSettingsPage? _account;
    private readonly Window _window;
    private readonly ContentControl _body;
    private readonly NavigationViewItem _settings;
    private readonly Action _manage;
    private PotPage? _pot;
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
        _saving = new InfoBar { IsOpen = false, IsClosable = true, Margin = new Thickness(12, 0, 12, 4) };
        AutomationProperties.SetAutomationId(_saving, "saving");
        _offer = new InfoBar { IsOpen = false, IsClosable = true, Margin = new Thickness(12, 0, 12, 4) };
        AutomationProperties.SetAutomationId(_offer, "uploads-said");
        _uploads = new Uploads(window, _offer) { Changed = DrawCommands, Opens = OpenQueued, Says = Say };
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
                if (_pot is { } pot) pot.Refresh();
                else if (_memory is { } memory) memory.Reload();
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
        RowDates.Ask($"{shown.connection}\n{shown.tenant.tenantId}\n{string.Join(",", state.lists.objects.Select(o => o.nodeId))}", back, Redraw);
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
                Say(false, sent);
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
    /// and where it is), then the trash and Manage servers (ManageDialog).</summary>
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
        // only the sections that hold something (Lists.drawerSections, owner 2026-11-19): no empty Favorites, Recent or
        // Applications
        foreach (var section in shown.drawerSections)
        {
            switch (section)
            {
                case DrawerSection.Favorites or DrawerSection.Recent:
                {
                    var favorites = section == DrawerSection.Favorites;
                    var (title, refs, prefix) = favorites ? (Words.DrawerFavorites(), state.lists.drawer.favorites, "favorite") : (Words.DrawerRecent(), state.lists.drawer.recent, "recent");
                    items.Add(new NavigationViewItemHeader { Content = title });
                    foreach (var r in refs) Add(Item(r.name, HomeView.KindGlyph(r.kind), $"{prefix}:{r.name}", new Target.Object(r)), Opened(r));
                    break;
                }
                case DrawerSection.Applications:
                    items.Add(new NavigationViewItemHeader { Content = Words.DrawerApplications() });
                    foreach (var app in state.lists.applications)
                    {
                        var chosen = shell.application == app && shell.openObject is null;
                        Add(Item(HomeView.AppName(shown, app), HomeView.AppGlyph(app), $"app:{HomeView.WireName(app)}", new Target.Application(app)), chosen);
                    }
                    break;
            }
        }
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

    private void DrawContent(ShellModelState state, ShellShown shown)
    {
        if (_account is { } account)
        {
            // the account's Settings in place of what the shell shows; back leaves it
            _shown = shown;
            _title.Text = Words.DrawerSettings();
            _nav.IsBackEnabled = true;
            CloseMemoriesMap();
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
            if (_pot is not null) _said.IsOpen = false;
            _pot?.Dispose();
            _pot = null;
            _file?.Dispose();
            _file = null;
            _dropBox?.Dispose();
            _dropBox = null;
            DropMemory();
            DropBookmark();
            if (_person is null || _person.UserId != person)
            {
                _person?.Dispose();
                _person = new PersonPage(_window, person, mine: shown.me == person);
                Log.Write("shell: a person's profile");
            }
            body = _person.Page;
            CloseMemoriesMap();
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
            if (_pot is not null) _said.IsOpen = false;
            _pot?.Dispose();
            _pot = null;
            _file?.Dispose();
            _file = null;
            _dropBox?.Dispose();
            _dropBox = null;
            DropMemory();
            if (_bookmark is null || _bookmark.Node.nodeId != kept.nodeId || _bookmark.Node.scopeId != kept.scopeId)
            {
                DropBookmark();
                _bookmark = new BookmarkPage(_window, kept) { Changed = DrawCommands };
                Log.Write("shell: a kept link's page");
            }
            CloseMemoriesMap();
            if (!ReferenceEquals(_body.Content, _bookmark.Page)) _body.Content = _bookmark.Page;
            DrawCommands();
            return;
        }
        DropBookmark();
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
                // the tray's Open of a held entry: its conflict's editor once this Pot opened
                if (_heldEntry is { } held && pot.nodeId == _heldPot)
                {
                    _heldEntry = null;
                    _pot.OpenHeld(held);
                }
            }
            _pot.Hero(_header?.Hero);
            _pot.Places(shown.tabs, shown.selectedTab);
            _file?.Dispose();
            _file = null;
            _dropBox?.Dispose();
            _dropBox = null;
            DropMemory();
            body = _pot.Page;
        }
        else if (shell.openObject is { kind: "FILE" } file)
        {
            if (_pot is not null) _said.IsOpen = false;
            _pot?.Dispose();
            _pot = null;
            _dropBox?.Dispose();
            _dropBox = null;
            DropMemory();
            var node = new NodeAt { scopeId = file.scopeId, nodeId = file.nodeId, kind = file.kind, name = file.name };
            if (_file is null || _file.Node != node)
            {
                _file?.Dispose();
                // the file read: its header learns what it may do (FileModel.Header.facts)
                _file = new FilePage(node) { Read = (facts, id) => _header?.Facts(facts, id) };
            }
            body = _file.Page;
        }
        else if (shell.openObject is { kind: "DEPOT_DROPBOX" } box)
        {
            if (_pot is not null) _said.IsOpen = false;
            _pot?.Dispose();
            _pot = null;
            _file?.Dispose();
            _file = null;
            DropMemory();
            var node = new NodeAt { scopeId = box.scopeId, nodeId = box.nodeId, kind = box.kind, name = box.name };
            if (_dropBox is null || _dropBox.Node != node)
            {
                _dropBox?.Dispose();
                _dropBox = new DropBoxPage(_window, node, _said);
            }
            _dropBox.Hero(_header?.Hero);
            body = _dropBox.Page;
        }
        else if (shell.openObject is { kind: "MEMORY" or "CHAPTER" } memory)
        {
            if (_pot is not null) _said.IsOpen = false;
            _pot?.Dispose();
            _pot = null;
            _file?.Dispose();
            _file = null;
            _dropBox?.Dispose();
            _dropBox = null;
            var node = new NodeAt { scopeId = memory.scopeId, nodeId = memory.nodeId, kind = memory.kind, name = memory.name };
            if (_memory is null || _memory.Node != node)
            {
                DropMemory();
                long? told = null;
                _memory = new MemoryPage(_window, node, Say, _said, Saving)
                {
                    // each state: its "Add chapter", and once its id, the header's own upload (Header.Facts' gallery)
                    Changed = (_, gallery) =>
                    {
                        if (told != gallery)
                        {
                            told = gallery;
                            _header?.Facts(new Facts { canWrite = true, hasContent = true }, null, gallery);
                        }
                        DrawCommands();
                    },
                };
            }
            _memory.Hero(_header?.Hero);
            body = _memory.Page;
        }
        else
        {
            if (_pot is not null) _said.IsOpen = false;
            _pot?.Dispose();
            _pot = null;
            _file?.Dispose();
            _file = null;
            _dropBox?.Dispose();
            _dropBox = null;
            DropMemory();
            if (shell.openObject is { } other) body = NotBuilt(other);
            else if (shell.application is ApplicationId.Memories && shown.tabs.Count > 1) body = MemoriesPlaces(state, shown);
            else
            {
                // a folder's level starts with its head, as the Pot's and the Memory's pages (none on a space's root)
                body = shell.application is not null ? HomeView.List(shown.list, shown.view == ViewModeShown.Tiles) : HomeView.Home(state, shown, LevelDrops(), _header?.Hero);
                if (body is ScrollViewer list) Keep(list, ListScrolls.Place(shell.application, shell.space, shell.path.Count > 0 ? shell.path[^1].nodeId : null, shell.filter));
            }
        }
        if (!ReferenceEquals(body, _places)) CloseMemoriesMap();
        if (!ReferenceEquals(_body.Content, body)) _body.Content = body;
        DrawCommands();
    }

    /// <summary>The Memories application's places: its bar's tabs as a SelectorBar (as a Pot's places; a choice is the
    /// shell's), under it the list or the Memories map — every located Memory a pin (Map.OpenMemories), kept while its tab
    /// stays chosen.</summary>
    private UIElement MemoriesPlaces(ShellModelState state, ShellShown shown)
    {
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
        if (shown.selectedTab?.key == "memories.map")
        {
            if (_memoriesMap is null) OpenMemoriesMap();
            content = _mapHolder;
        }
        else
        {
            CloseMemoriesMap();
            content = HomeView.List(shown.list, shown.view == ViewModeShown.Tiles);
            if (content is ScrollViewer list) Keep(list, ListScrolls.Place(state.shell.application, state.shell.space, null, state.shell.filter));
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
            if (_shown?.selectedTab?.key != "memories.map" || _memoriesMap is not null || !ReferenceEquals(_body.Content, _places))
            {
                Act.Send(new Call.MapClose { map = map });
                return;
            }
            Log.Write($"memories map {map} open");
            _memoriesMap = new RasterMap(_window, map, closesItself: true)
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

    private void CloseMemoriesMap()
    {
        if (_memoriesMap is null) return;
        _mapHolder.Children.Clear();
        _memoriesMap.Dispose();
        _memoriesMap = null;
        _mapEmpty.Visibility = Visibility.Collapsed;
        Log.Write("memories map closed");
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
            var facts = node.kind is "EXPENSES_POT" or "FILE" ? null : new Facts { canWrite = true, hasContent = true };
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
        var page = _pot;
        // a Pot's page creates one thing, an entry (as Android's and Apple's Pot screens): the level's New is not offered
        // beside it — two Add commands side by side would ask which one adds what
        var memory = _memory;
        // a Memory's page creates one thing too, a chapter (its "Add chapter", where its gallery offers one): New is not
        // offered beside it, as on a Pot's page
        if (page is null && memory is null && _bookmark is null && shown.canCreateHere && shown.creates.Count > 0)
        {
            // what the core offers here (AppKinds.createsWhere): a folder, a Pot and a Memory, on Home files and a folder uploaded too
            _commands.PrimaryCommands.Add(CreateAction.Menu(Words.DrawerNew(), "new",
                // a chapter is a Memory's page's own, never a level's
                shown.creates.Where(c => c.create is not Create.Chapter).Select(c => (Said.Key(c.word), $"new:{c.create}", c.create switch
                {
                    Create.Folder => (Action?)NewFolder,
                    Create.Memory => NewMemory,
                    // New → Bookmark (ADR 0150 decision 6, inbox #1020): the made bookmark shows in the level read anew
                    Create.Bookmark => () => { if (Level() is { } l) BookmarkCreateDialog.Open(_window, l.ScopeId, l.ParentId, _ => RefreshLists()); },
                    Create.Pot => () => PotCreateDialog.Open(_window, Say),
                    Create.UploadFiles => () => { if (Level() is { } l) _uploads.PickFiles(l, Say); },
                    Create.UploadFolder => () => { if (Level() is { } l) _uploads.PickFolder(l, Say); },
                    _ => null,
                }))));
        }
        // an open Pot's "Add entry" is the content's creating action, first, where its places show and it takes writes
        if (page is { OffersAdd: true }) _commands.PrimaryCommands.Add(CreateAction.Button(Words.PotAdd(), "pot-add", page.AddEntry));
        if (memory?.Shown is { } gallery && gallery.creates.Any(c => c.create == Create.Chapter))
        {
            _commands.PrimaryCommands.Add(CreateAction.Button(Words.MemoryAddChapter(), "memory-add-chapter", memory.CreateChapter));
        }
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
        // a level's View (Explorer's): List or Tiles, the account's one choice (ADR 0149 decision 8) — a level only, never an
        // object's page nor the Memories map
        var view = page is null && memory is null && _file is null && _dropBox is null && _bookmark is null && _state?.shell.openObject is null
            && shown.selectedTab?.key != "memories.map" ? LevelTiles.ViewCommand(shown) : null;
        if (_header is null)
        {
            if (view is not null) _commands.PrimaryCommands.Add(view);
            return;
        }
        var file = _file;
        var kept = _bookmark;
        var (primary, secondary) = _header.Commands(() =>
        {
            var window = WinRT.Interop.WindowNative.GetWindowHandle(_window);
            if (page is not null) page.Download(window, Say);
            else file?.Save(window, Say, Saving);
        }, () =>
        {
            if (file?.Id is { } id) _uploads.Replace(id, Say);
        }, () => memory?.Add(), () => memory?.Music(), () => kept?.OwnPage(false));
        // a kept link's page: Open on its own page first, once its meta read (the header's where its plan names it)
        bool Offered(string id) => primary.Concat(secondary).OfType<FrameworkElement>().Any(e => AutomationProperties.GetAutomationId(e) == id);
        if (kept is { OffersActions: true } && !Offered("header:open-own-page"))
        {
            _commands.PrimaryCommands.Add(Command(Words.BookmarkOpenOwnPage(), "bookmark-own-page", Symbol.NewWindow, () => kept.OwnPage(false)));
        }
        foreach (var c in primary) _commands.PrimaryCommands.Add(c);
        if (view is not null) _commands.PrimaryCommands.Add(view);
        if (page is not null)
        {
            // an open Pot's Refresh: desktops have no pull gesture (F5 and Ctrl+R, as Explorer and Edge refresh)
            var refresh = new AppBarButton { Icon = new SymbolIcon(Symbol.Refresh), Label = Words.PotRefresh() };
            AutomationProperties.SetAutomationId(refresh, "pot-refresh");
            refresh.Click += (_, _) => page.Refresh();
            _commands.PrimaryCommands.Add(refresh);
        }
        else if (memory is not null)
        {
            // a Memory's Refresh: its gallery read anew (F5 and Ctrl+R too)
            var refresh = new AppBarButton { Icon = new SymbolIcon(Symbol.Refresh), Label = Words.PotRefresh() };
            AutomationProperties.SetAutomationId(refresh, "memory-refresh");
            refresh.Click += (_, _) => memory.Reload();
            _commands.PrimaryCommands.Add(refresh);
        }
        else if (_dropBox is not null)
        {
            // a drop box's Refresh: the shell's lists read anew, so a guest's new folder shows (F5 and Ctrl+R too)
            var refresh = new AppBarButton { Icon = new SymbolIcon(Symbol.Refresh), Label = Words.PotRefresh() };
            AutomationProperties.SetAutomationId(refresh, "refresh");
            refresh.Click += (_, _) => RefreshLists();
            _commands.PrimaryCommands.Add(refresh);
        }
        foreach (var c in secondary) _commands.SecondaryCommands.Add(c);
        if (kept is not null)
        {
            // a kept link's page: Report shared content (to the share's own operator) once its meta read; Report name
            // where the header offers no report of its own (the name alone: AppKinds.reportsNameOnly)
            if (kept.OffersActions) _commands.SecondaryCommands.Add(Command(Words.BookmarkReportShare(), "bookmark-report-share", Symbol.Flag, () => kept.OwnPage(true)));
            if (!Offered("header:report")) _commands.SecondaryCommands.Add(Command(Words.BookmarkReportName(), "bookmark-report-name", Symbol.Flag, kept.ReportName));
        }
        // a Memory's banner (ADR 0144 decision 5): "Add banner" or "Change banner", where its gallery offers the dialog
        if (memory?.Shown is { editsBanner: true } offers)
        {
            var banner = new AppBarButton { Icon = new FontIcon { Glyph = "\uE91B" }, Label = offers.banner is null ? Words.MemoryBannerAdd() : Words.MemoryBannerChange() };
            AutomationProperties.SetAutomationId(banner, "memory-banner-edit");
            banner.Click += (_, _) => memory.EditBanner();
            _commands.SecondaryCommands.Add(banner);
        }
    }

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

    /// <summary>Where a drop onto the level shown goes: the level where the core offers one (ShellShown.dropsHere), each
    /// folder's row it names (ShellShown.dropRows); none where it offers neither.</summary>
    private Drops? LevelDrops()
    {
        if (_shown is not { } shown || (!shown.dropsHere && shown.dropRows.Count == 0)) return null;
        return Level() is { } level ? new Drops(_window, level.Label, shown.dropsHere, shown.dropRows.ToHashSet(), Say) : null;
    }

    /// <summary>The Memory's page let go (closed in the core), its hero handed back.</summary>
    private void DropMemory()
    {
        _memory?.Dispose();
        _memory = null;
    }

    /// <summary>New Memory (Shell.CreateMemory) in the level shown, named in a dialog that asks again with the core's
    /// problem; the Memory made opens (the web's MemoryNodeDialog).</summary>
    private async void NewMemory() => await NewMemory("", null, null, null);

    private async System.Threading.Tasks.Task NewMemory(string name, string? problem, bool? wasThereBefore, bool? placeBefore)
    {
        if (_state is not { } state || state.shell.space is not { } space) return;
        var parent = state.shell.path.Count > 0 ? state.shell.path[^1].nodeId : null;
        // "I was there", ticked, where the core offers it (a tenant or group scope: ADR 0144 decision 2); "Determine location
        // automatically", unticked, where the map services are shown (uc.memory.place A1); neither remembered
        var offered = false;
        var offersPlace = false;
        try
        {
            if ((await Core.CallAsync(new Call.ShellOffersWasThere { scopeId = space })).Item1 is Answer.WasThere w)
            {
                offered = w.offered;
                offersPlace = w.autoPlace;
            }
        }
        catch (Exception e)
        {
            Log.Write($"Shell.OffersWasThere failed ({e.GetType().Name})");
        }
        CheckBox? Check(bool shown, string id, string label, string hint, bool ticked)
        {
            if (!shown) return null;
            var box = new CheckBox { Content = label, IsChecked = ticked };
            AutomationProperties.SetAutomationId(box, id);
            ToolTipService.SetToolTip(box, hint);
            AutomationProperties.SetHelpText(box, hint);
            return box;
        }
        var place = Check(offersPlace, "new-memory-auto-place", Words.MemoryPlaceAuto(), Words.MemoryPlaceAutoHint(), placeBefore ?? false);
        var wasThere = Check(offered, "new-memory-was-there", Words.MemoryParticipantsWasThere(), Words.MemoryParticipantsWasThereHint(), wasThereBefore ?? true);
        StackPanel? extra = null;
        if (place is not null || wasThere is not null)
        {
            extra = new StackPanel();
            if (place is not null) extra.Children.Add(place);
            if (wasThere is not null) extra.Children.Add(wasThere);
        }
        var typed = await Dialogs.AskName(_window, "new-memory", Words.MemoryCreateMemoryTitle(), Words.NodeName(), name, problem, Words.FolderCreate(), extra);
        if (typed is null) return;
        var there = wasThere?.IsChecked == true;
        var armed = place?.IsChecked == true;
        Log.Write($"new memory: I was there offered {offered}, ticked {there}; the place offered {offersPlace}, ticked {armed}");
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.ShellCreateMemory { scopeId = space, parentId = parent, name = typed, wasThere = there, autoPlace = armed });
            switch (answer)
            {
                case Answer.Created { node: { } made }:
                    Log.Write("new memory: made");
                    MemoryPage.OpenNode(made);
                    break;
                case Answer.Created { notice: { } n }:
                    Log.Write($"new memory: {n.msg}");
                    await NewMemory(typed, Said.Notice(n), there, armed);
                    break;
                default:
                    Log.Write($"Shell.CreateMemory answered {answer.GetType().Name}");
                    break;
            }
        }
        catch (Exception e)
        {
            Log.Write($"Shell.CreateMemory failed ({e.GetType().Name})");
        }
    }

    /// <summary>New's folder (Shell.CreateFolder), named in a dialog the core's answer keeps open with its problem
    /// (ADR 0154 decision 1); a notice is said as it closes, a folder made offline (queued) reads as made.</summary>
    private async void NewFolder()
    {
        try
        {
            var named = await Dialogs.AskNamed(_window, "new-folder", Words.FolderNew(), Words.FolderName(), "", Words.FolderCreate(),
                typed => Dialogs.Named(new Call.ShellCreateFolder { name = typed }, "Shell.CreateFolder"));
            if (named?.notice is { } n) Say(false, Said.Notice(n));
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
                if (_pot is { } shownPot && shownPot.Ref.nodeId == e.pot) shownPot.OpenHeld(e.write);
                else
                {
                    (_heldEntry, _heldPot) = (e.write, e.pot);
                    OpenNode(e.scope, e.pot);
                }
                break;
            case QueuedOpen.QueuedOpenRename r:
                // the node's page or folder, whose header offers Rename
                OpenNode(r.scope, r.node);
                break;
            case QueuedOpen.QueuedOpenPlace p:
                OpenNode(p.scope, p.node);
                break;
        }
    }

    /// <summary>Node <paramref name="node"/> of space <paramref name="scope"/> shown (null: the space's top): an object
    /// opened, a folder entered — as the lists name it.</summary>
    private void OpenNode(string scope, string? node)
    {
        if (_state is not { } state) return;
        if (state.shell.space != scope) Act.Send(new Call.ShellSwitchSpace { scopeId = scope });
        if (node is null) return;
        if (state.lists.objects.FirstOrDefault(o => o.scopeId == scope && o.nodeId == node) is { } r) Act.Send(new Call.ShellOpen { Ref = r });
        else if (state.lists.nodes.FirstOrDefault(n => n.scopeId == scope && n.nodeId == node) is { } f)
        {
            Act.Send(new Call.ShellEnter { folder = new FolderRef { nodeId = f.nodeId, name = f.name } });
        }
        else Log.Write("queued open: the node is not listed here");
    }

    /// <summary>How an action went, under the bar until it is closed or the object leaves.</summary>
    public void Say(bool done, string message)
    {
        _said.Content = null;
        _said.ActionButton = null;
        _said.Severity = done ? InfoBarSeverity.Success : InfoBarSeverity.Error;
        _said.Message = message;
        _said.IsOpen = true;
    }

    /// <summary>What the music bar says (a queue none of whose tracks played here), under the bar for 5 s (the web's).</summary>
    private void SayForAWhile(string message)
    {
        Say(false, message);
        _sayTimer?.Stop();
        var timer = _sayTimer = _nav.DispatcherQueue.CreateTimer();
        timer.Interval = TimeSpan.FromSeconds(5);
        timer.IsRepeating = false;
        timer.Tick += (_, _) =>
        {
            if (Equals(_said.Message, message)) _said.IsOpen = false;
        };
        timer.Start();
    }

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
