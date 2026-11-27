// Settings' Groups (core's SettingsSection.GROUPS over TenantGroups; en.spaces.group-scope, uc.spaces.add-group-member,
// uc.spaces.consent-group-add, uc.spaces.manage-group-members, uc.spaces.rename-group, uc.spaces.delete-group): the
// caller's groups in the open space, in a ContentDialog over the page as Members is — each row its name (or *Untitled
// group*), the caller's role and the trash's mark of a deleted group or the key's of one behind its epoch; *New group*
// where the core says the caller may create one. A row opens the group's page, the list giving way to it (one ContentDialog
// at a time, Dialogs.cs) and coming back when it closes — the web's GroupMembersPanel: the group's identity header
// (ScopeActs.Header: its icon, which an owner adds, changes — the page giving way to the square crop and opening again
// with what the act said — or removes) with an owner's Rename in place, the members with their trust badge and their
// acts in each row's MenuFlyout (Make co-owner, Remove co-owner, Remove asked in place with the turn-off-every-link
// option, Withdraw for a proposal), Add member by username, where the core says (wholeActs) the *Links* and *New lock*
// sections with the group's words (ScopeActs.LinksAndLock), and the danger zone's Delete group asked in place first or,
// deleted, the day it is deleted as a warning and Restore (a deleted row in the list says that day too); a member
// reads the page with the core's read-only note. *New group* asks the name (the web's CreateGroupDialog) and opens the new group's page. What an act says is an
// InfoBar at the dialog's top, every rule the core's. Every word is the catalog's (Words.g.cs).

namespace Veyq.Core;

using System;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal static class GroupsDialog
{
    /// <summary>What a dialog gave way to: the next dialog, or null where the person closed it.</summary>
    private delegate Task<Step?> Step();

    public static async void Open(Window window)
    {
        try
        {
            Step? next = () => List(window);
            while (next is not null) next = await next();
        }
        catch (Exception e)
        {
            Log.Write($"the groups did not open ({e.GetType().Name})");
        }
    }

    // ---- the list ----

    /// <summary>The caller's groups once; what the list gave way to (a group's page, *New group*), or null where it closed.</summary>
    private static async Task<Step?> List(Window window)
    {
        Step? after = null;
        var (_, outer, body) = Frame("groups-notice");
        var dialog = new ContentDialog
        {
            Title = Words.GroupsTitle(),
            Content = new ScrollViewer { Content = outer, MaxHeight = 560 },
            CloseButtonText = Words.ShareDone(),
            DefaultButton = ContentDialogButton.Close,
        };
        AutomationProperties.SetAutomationId(dialog, "groups");
        async void Load()
        {
            body.Children.Clear();
            var ring = Loading(Words.MembersLoading());
            body.Children.Add(ring);
            var (answer, _) = await Core.CallAsync(new Call.SettingsGroups());
            body.Children.Remove(ring);
            if (answer is not Answer.GroupsListed { groups: { } groups })
            {
                Log.Write($"Settings.Groups answered {answer?.GetType().Name}");
                Line(body, Words.MembersLoadFailed(), "groups-said", Load);
                return;
            }
            Log.Write($"groups: {groups.rows.Count} rows, create {groups.canCreate}, said {groups.said}");
            if (groups.canCreate)
            {
                // the creating action, Fluent's: New as the first command of the content's CommandBar (CreateAction.cs)
                var bar = new CommandBar { DefaultLabelPosition = CommandBarDefaultLabelPosition.Right, HorizontalAlignment = HorizontalAlignment.Left };
                bar.PrimaryCommands.Add(CreateAction.Button(Words.GroupsCreateTitle(), "groups-create", () =>
                {
                    after = () => Create(window);
                    dialog.Hide();
                }));
                body.Children.Add(bar);
            }
            if (groups.said is { } line)
            {
                Line(body, ReceivedText.Said(line), "groups-said", Load);
                return;
            }
            if (groups.rows.Count == 0)
            {
                Line(body, Words.GroupsNoneOwned(), "groups-said", null);
                return;
            }
            var list = new StackPanel { Spacing = 4 };
            AutomationProperties.SetAutomationId(list, "groups-list");
            foreach (var row in groups.rows)
            {
                var open = GroupRow(row);
                open.Click += (_, _) =>
                {
                    after = () => Page(window, row.scopeId);
                    dialog.Hide();
                };
                list.Children.Add(open);
            }
            body.Children.Add(list);
        }
        Load();
        await Dialogs.Show(window, dialog);
        return after;
    }

    /// <summary>One group in the list: its name, the caller's role, a deleted group's trash and a group behind its epoch's
    /// line — a button across the list that opens its page.</summary>
    private static Button GroupRow(GroupRow row)
    {
        var title = row.titleWord is { } w ? ReceivedText.Said(w) : row.name;
        var grid = new Grid { ColumnSpacing = 12 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.Children.Add(new FontIcon { Glyph = "\uE902", FontSize = 20, VerticalAlignment = VerticalAlignment.Center });
        var column = new StackPanel { Spacing = 2 };
        Grid.SetColumn(column, 1);
        var name = new TextBlock { Text = title, Style = Style("BodyStrongTextBlockStyle"), TextWrapping = TextWrapping.Wrap };
        AutomationProperties.SetAutomationId(name, "group-row-title");
        column.Children.Add(name);
        column.Children.Add(TermsText.Line(row.owner ? Words.MembersRoleOwner() : Words.MembersRoleMember(), true));
        if (row.deleted && row.deletion is { } day)
        {
            var deletion = Badge(Said.Notice(day));
            AutomationProperties.SetAutomationId(deletion, "group-row-deletion");
            column.Children.Add(deletion);
        }
        if (row.behind)
        {
            var behind = Badge(Words.MembersPending());
            AutomationProperties.SetAutomationId(behind, "group-row-behind");
            column.Children.Add(behind);
        }
        grid.Children.Add(column);
        if (row.deleted)
        {
            // the web's roster marks a group on its way out with the trash
            var trash = new FontIcon { Glyph = "\uE74D", FontSize = 16, VerticalAlignment = VerticalAlignment.Center };
            AutomationProperties.SetName(trash, row.deletion is { } when ? Said.Notice(when) : Words.GroupsPendingSub());
            AutomationProperties.SetAutomationId(trash, "group-row-deleted");
            Grid.SetColumn(trash, 2);
            grid.Children.Add(trash);
        }
        var button = new Button
        {
            Content = grid,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            Padding = new Thickness(12, 8, 12, 8),
        };
        AutomationProperties.SetName(button, title);
        AutomationProperties.SetAutomationId(button, $"group-{row.scopeId}");
        return button;
    }

    /// <summary>*New group*: its name asked (the web's CreateGroupDialog), the core's refusal under the field for another
    /// try; the new group's page once made, the list again where it was cancelled.</summary>
    private static async Task<Step?> Create(Window window)
    {
        string? problem = null;
        var name = "";
        while (true)
        {
            var typed = await Dialogs.AskName(window, "group-create-ask", Words.GroupsCreateTitle(), Words.GroupsNameLabel(), name, problem, Words.FolderCreate());
            if (typed is null) return () => List(window);
            name = typed;
            var (answer, _) = await Core.CallAsync(new Call.SettingsGroupCreate { name = typed });
            if (answer is Answer.GroupChanged { outcome: { done: true, scopeId: { } made } })
            {
                Log.Write("a group created");
                return () => Page(window, made);
            }
            var o = (answer as Answer.GroupChanged)?.outcome;
            Log.Write($"a group's create: {o?.notice?.msg ?? answer?.GetType().Name}");
            problem = o?.notice is { } n ? Said.Notice(n) : Words.GroupsCreateFailed();
        }
    }

    // ---- a group's page ----

    /// <summary>A group's page once, saying <paramref name="opened"/> first; what it gave way to (the icon's crop), else
    /// the list once it is closed.</summary>
    private static async Task<Step?> Page(Window window, string scopeId, (string Said, bool Done)? opened = null)
    {
        Step? after = null;
        var (said, outer, body) = Frame("group-notice");
        var dialog = new ContentDialog
        {
            Title = Words.GroupsTitle(),
            Content = new ScrollViewer { Content = outer, MaxHeight = 560 },
            CloseButtonText = Words.ShareDone(),
            DefaultButton = ContentDialogButton.Close,
        };
        AutomationProperties.SetAutomationId(dialog, "group-page");
        // what an act says is a success where it went through ("Invitation sent"), else its failure
        void SayText(string text, bool done)
        {
            said.Message = text;
            said.Severity = done ? InfoBarSeverity.Success : InfoBarSeverity.Error;
            said.IsOpen = true;
        }
        void Say(Notice n, bool done) => SayText(Said.Notice(n), done);
        if (opened is { } o0) SayText(o0.Said, o0.Done);
        var asks = new ScopeActs.Asks();
        void PickIcon(string title)
        {
            after = async () =>
            {
                var told = await ScopeActs.PickIcon(window, scopeId, title);
                return () => Page(window, scopeId, told);
            };
            dialog.Hide();
        }
        async Task Run(Call call)
        {
            body.IsHitTestVisible = false;
            var (answer, _) = await Core.CallAsync(call);
            body.IsHitTestVisible = true;
            if (answer is Answer.GroupChanged { outcome: { } o })
            {
                Log.Write($"a group's act {call.GetType().Name}: done {o.done}, {o.notice?.msg}");
                if (o.notice is { } n) Say(n, o.done);
                else if (o.done) said.IsOpen = false;
                if (o.done) Load();
            }
            else if (answer is Answer.IconChanged { notice: { } icon } cleared)
            {
                Log.Write($"a group's icon act: done {cleared.done}");
                Say(icon, cleared.done);
                if (cleared.done) Load();
            }
            else Log.Write($"a group's act answered {answer?.GetType().Name}");
        }
        async void Load()
        {
            body.Children.Clear();
            asks.Drop();
            var ring = Loading(Words.MembersLoading());
            body.Children.Add(ring);
            var (answer, _) = await Core.CallAsync(new Call.SettingsGroup { scopeId = scopeId });
            body.Children.Remove(ring);
            if (answer is not Answer.GroupPage { page: { } page })
            {
                Log.Write($"Settings.Group answered {answer?.GetType().Name}");
                Line(body, Words.MembersLoadFailed(), "group-said", Load);
                return;
            }
            Log.Write($"group page: owner {page.owner}, deleted {page.deleted} (day {page.deletion is not null}), whole acts {page.wholeActs}, {page.members.Count} members ({System.Linq.Enumerable.Count(page.members, m => m.badgeWord is not null)} badged), said {page.said}");
            Draw(body, page, call => _ = Run(call), Run, asks, PickIcon);
        }
        Load();
        await Dialogs.Show(window, dialog);
        return after ?? (() => List(window));
    }

    /// <summary>The page's content: the header (the icon, the name with an owner's Rename), a deleted group's day, the
    /// members with Add member, where the core says the Links and New lock sections, and an owner's danger zone.</summary>
    private static void Draw(StackPanel body, GroupPage page, Action<Call> run, Func<Call, Task> running, ScopeActs.Asks asks, Action<string> pickIcon)
    {
        var title = page.name.Length > 0 ? page.name : Words.GroupsUntitled();
        Button? rename = null;
        if (page.owner)
        {
            rename = new Button { Content = Words.GroupsRename() };
            AutomationProperties.SetAutomationId(rename, "group-rename");
            rename.Click += (_, _) => AskRename(body, page, run);
        }
        var header = ScopeActs.Header(page.scopeId, title, page.owner, asks, pickIcon, running, "group-name", rename);
        AutomationProperties.SetAutomationId((FrameworkElement)header, "group-header");
        body.Children.Add(header);

        if (page.deleted)
        {
            // the day it is deleted, where the core knows it (the web's warning line); the danger zone keeps the line under it
            var pending = new InfoBar { IsOpen = true, IsClosable = false, Severity = InfoBarSeverity.Warning, Message = page.deletion is { } day ? Said.Notice(day) : Words.GroupsPendingSub() };
            AutomationProperties.SetAutomationId(pending, "group-deleted");
            body.Children.Add(pending);
        }

        var heading = new TextBlock { Text = Words.GroupsMembers(), Style = Style("BodyStrongTextBlockStyle"), Margin = new Thickness(0, 8, 0, 0) };
        AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        body.Children.Add(heading);
        body.Children.Add(TermsText.Line(Words.GroupsMembersSub(), true));
        if (page.readonlyNote is { } note)
        {
            var readOnly = new InfoBar { IsOpen = true, IsClosable = false, Severity = InfoBarSeverity.Informational, Message = ReceivedText.Said(note) };
            AutomationProperties.SetAutomationId(readOnly, "group-readonly");
            body.Children.Add(readOnly);
        }
        if (page.owner && !page.deleted) body.Children.Add(AddMember(page, run));
        if (page.said is { } said)
        {
            Line(body, ReceivedText.Said(said), "group-said", null);
        }
        else
        {
            var list = new StackPanel { Spacing = 4 };
            AutomationProperties.SetAutomationId(list, "group-members");
            foreach (var member in page.members) list.Children.Add(Row(page.scopeId, member, run, body));
            body.Children.Add(list);
        }

        if (page.wholeActs)
        {
            body.Children.Add(ScopeActs.LinksAndLock(Words.SpaceLockSubGroup(), Words.SpaceLockConfirmGroup(),
                new Call.SettingsGroupRevokeLinks { scopeId = page.scopeId }, new Call.SettingsGroupNewLock { scopeId = page.scopeId }, asks, running));
        }

        if (page.owner)
        {
            var danger = new StackPanel { Spacing = 8, Margin = new Thickness(0, 16, 0, 0) };
            AutomationProperties.SetAutomationId(danger, "group-danger");
            var zone = new TextBlock { Text = Words.GroupsDangerZone(), Style = Style("BodyStrongTextBlockStyle") };
            AutomationProperties.SetHeadingLevel(zone, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
            danger.Children.Add(zone);
            danger.Children.Add(TermsText.Line(page.deleted ? Words.GroupsPendingSub() : Words.GroupsDeleteSub(), true));
            if (page.deleted)
            {
                var restore = new Button { Content = Words.GroupsRestore(), Style = Style("AccentButtonStyle") };
                AutomationProperties.SetAutomationId(restore, "group-restore");
                restore.Click += (_, _) => run(new Call.SettingsGroupDelete { scopeId = page.scopeId, restore = true });
                danger.Children.Add(restore);
            }
            else
            {
                var delete = new Button { Content = Words.GroupsDeleteGroup() };
                AutomationProperties.SetAutomationId(delete, "group-delete");
                delete.Click += (_, _) => AskDelete(body, page, run);
                danger.Children.Add(delete);
            }
            body.Children.Add(danger);
        }
    }

    /// <summary>An owner's *Add member*: the web's line, the username's box and its button; the box cleared once added.</summary>
    private static UIElement AddMember(GroupPage page, Action<Call> run)
    {
        var panel = new StackPanel { Spacing = 6 };
        AutomationProperties.SetAutomationId(panel, "group-add");
        var intro = TermsText.Line(Words.GroupsAddMemberIntro(), true);
        panel.Children.Add(intro);
        var row = new Grid { ColumnSpacing = 8 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var box = new TextBox { PlaceholderText = Words.GroupsUsernamePlaceholder() };
        AutomationProperties.SetName(box, Words.GroupsAddMemberIntro());
        AutomationProperties.SetAutomationId(box, "group-add-username");
        row.Children.Add(box);
        var add = new Button { Content = Words.GroupsAddMember(), Style = Style("AccentButtonStyle"), IsEnabled = false };
        AutomationProperties.SetAutomationId(add, "group-add-submit");
        Grid.SetColumn(add, 1);
        row.Children.Add(add);
        box.TextChanged += (_, _) => add.IsEnabled = box.Text.Trim().Length > 0;
        void Submit()
        {
            if (box.Text.Trim().Length == 0) return;
            run(new Call.SettingsGroupAdd { scopeId = page.scopeId, username = box.Text });
        }
        add.Click += (_, _) => Submit();
        box.KeyDown += (_, e) =>
        {
            if (e.Key != global::Windows.System.VirtualKey.Enter) return;
            e.Handled = true;
            Submit();
        };
        panel.Children.Add(row);
        return panel;
    }

    /// <summary>One member: the picture, the title, the line under it (@username, the role), the badges, and the acts the
    /// core offers in the row's flyout.</summary>
    private static UIElement Row(string scopeId, GroupMember row, Action<Call> run, StackPanel body)
    {
        var grid = new Grid { ColumnSpacing = 12, Padding = new Thickness(0, 6, 0, 6) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        AutomationProperties.SetAutomationId(grid, $"group-member-{row.userId}");
        var picture = Avatars.Person(row.realName ?? row.username, row.userId, 40);
        picture.VerticalAlignment = VerticalAlignment.Top;
        grid.Children.Add(picture);
        var column = new StackPanel { Spacing = 2 };
        Grid.SetColumn(column, 1);
        column.Children.Add(new TextBlock { Text = row.title, Style = Style("BodyStrongTextBlockStyle") });
        column.Children.Add(TermsText.Line(Lives.Joined([row.usernameLine, ReceivedText.Said(row.roleWord)]), true));
        var badges = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var status = new TextBlock
        {
            Text = ReceivedText.Said(row.statusWord),
            Foreground = (Brush)Application.Current.Resources[row.pendingConsent ? "TextFillColorSecondaryBrush" : "SystemFillColorSuccessBrush"],
            Style = Style("CaptionTextBlockStyle"),
        };
        AutomationProperties.SetAutomationId(status, "group-member-status");
        badges.Children.Add(status);
        if (row.pendingWord is { } pending)
        {
            var badge = Badge(ReceivedText.Said(pending));
            AutomationProperties.SetAutomationId(badge, "group-member-pending");
            badges.Children.Add(badge);
        }
        column.Children.Add(badges);
        ScopeActs.Badge(column, row.badgeWord, row.badgeNote);
        grid.Children.Add(column);
        if (row.promote || row.demote || row.remove || row.rescind)
        {
            var menu = new MenuFlyout();
            if (row.promote) menu.Items.Add(Item(Words.GroupsPromote(), "\uE74A", "group-member-promote", () => run(new Call.SettingsGroupRole { scopeId = scopeId, userId = row.userId, owner = true })));
            if (row.demote) menu.Items.Add(Item(Words.GroupsDemote(), "\uE74B", "group-member-demote", () => run(new Call.SettingsGroupRole { scopeId = scopeId, userId = row.userId, owner = false })));
            if (row.remove) menu.Items.Add(Item(Words.GroupsRemove(), "\uE8E6", "group-member-remove", () => AskRemove(body, scopeId, row, run)));
            if (row.rescind) menu.Items.Add(Item(Words.GroupsRescind(), "\uE711", "group-member-rescind", () => run(new Call.SettingsGroupRemove { scopeId = scopeId, userId = row.userId, revokeLinks = false, rescind = true })));
            var more = new Button { Content = new FontIcon { Glyph = "\uE712", FontSize = 14 }, Flyout = menu, VerticalAlignment = VerticalAlignment.Top };
            AutomationProperties.SetName(more, row.title);
            AutomationProperties.SetAutomationId(more, $"group-member-menu-{row.userId}");
            Grid.SetColumn(more, 2);
            grid.Children.Add(more);
        }
        return grid;
    }

    // ---- the questions asked in place ----

    /// <summary>An owner's *Rename*, asked in place at the page's top (the web's inline rename): the name, Save and Cancel.</summary>
    private static void AskRename(StackPanel body, GroupPage page, Action<Call> run)
    {
        var ask = Card("group-rename-ask");
        var box = new TextBox { Header = Words.GroupsNameLabel(), PlaceholderText = Words.GroupsRenamePlaceholder(), Text = page.name };
        AutomationProperties.SetAutomationId(box, "group-rename-field");
        ask.Children.Add(box);
        var save = new Button { Content = Words.EntrySave(), Style = Style("AccentButtonStyle"), IsEnabled = page.name.Trim().Length > 0 };
        AutomationProperties.SetAutomationId(save, "group-rename-save");
        box.TextChanged += (_, _) => save.IsEnabled = box.Text.Trim().Length > 0;
        save.Click += (_, _) =>
        {
            body.Children.Remove(ask);
            run(new Call.SettingsGroupRename { scopeId = page.scopeId, name = box.Text });
        };
        ask.Children.Add(Buttons(save, Words.EntryCancel(), "group-rename-cancel", () => body.Children.Remove(ask)));
        Place(body, ask);
        box.Loaded += (_, _) =>
        {
            box.Focus(FocusState.Programmatic);
            box.SelectAll();
        };
    }

    /// <summary>*Remove*'s question, asked in place (the web's inline confirm): its words, the opt-in to turn off every link
    /// of the group, Remove and Cancel. The removal is the group's rotation, the core's.</summary>
    private static void AskRemove(StackPanel body, string scopeId, GroupMember row, Action<Call> run)
    {
        var ask = Card("group-remove-ask");
        ask.Children.Add(new TextBlock { Text = row.title, Style = Style("BodyStrongTextBlockStyle") });
        ask.Children.Add(TermsText.Line(Words.GroupsRemoveConfirm(), false));
        var links = new CheckBox { Content = Words.GroupsRemoveRevokeLinks() };
        AutomationProperties.SetAutomationId(links, "group-remove-links");
        ask.Children.Add(links);
        ask.Children.Add(TermsText.Line(Words.GroupsRemoveRevokeLinksHint(), true));
        var remove = new Button { Content = Words.GroupsRemoveConfirmYes(), Style = Style("AccentButtonStyle") };
        AutomationProperties.SetAutomationId(remove, "group-remove-confirm");
        remove.Click += (_, _) =>
        {
            body.Children.Remove(ask);
            run(new Call.SettingsGroupRemove { scopeId = scopeId, userId = row.userId, revokeLinks = links.IsChecked == true, rescind = false });
        };
        ask.Children.Add(Buttons(remove, Words.SigninCancel(), "group-remove-cancel", () => body.Children.Remove(ask)));
        Place(body, ask);
    }

    /// <summary>*Delete group*'s question, asked in place first: its words, Delete and Cancel.</summary>
    private static void AskDelete(StackPanel body, GroupPage page, Action<Call> run)
    {
        var ask = Card("group-delete-ask");
        ask.Children.Add(new TextBlock { Text = Words.GroupsDeleteConfirm(), Style = Style("BodyStrongTextBlockStyle"), TextWrapping = TextWrapping.Wrap });
        ask.Children.Add(TermsText.Line(Words.GroupsDeleteSub(), false));
        var delete = new Button { Content = Words.GroupsDeleteConfirmYes(), Style = Style("AccentButtonStyle") };
        AutomationProperties.SetAutomationId(delete, "group-delete-confirm");
        delete.Click += (_, _) =>
        {
            body.Children.Remove(ask);
            run(new Call.SettingsGroupDelete { scopeId = page.scopeId, restore = false });
        };
        ask.Children.Add(Buttons(delete, Words.SigninCancel(), "group-delete-cancel", () => body.Children.Remove(ask)));
        Place(body, ask);
    }

    private static readonly string[] Asks = ["group-rename-ask", "group-remove-ask", "group-delete-ask"];

    /// <summary>A question asked in place: one at a time, at the page's top (under its header).</summary>
    private static void Place(StackPanel body, StackPanel ask)
    {
        foreach (var child in body.Children)
        {
            if (child is StackPanel s && Array.IndexOf(Asks, AutomationProperties.GetAutomationId(s)) >= 0)
            {
                body.Children.Remove(s);
                break;
            }
        }
        body.Children.Insert(Math.Min(1, body.Children.Count), ask);
    }

    // ---- shared pieces (Members' look) ----

    /// <summary>A dialog's content: the InfoBar that says an act's outcome over the body.</summary>
    internal static (InfoBar Said, StackPanel Outer, StackPanel Body) Frame(string noticeId)
    {
        var said = new InfoBar { IsOpen = false, IsClosable = true };
        AutomationProperties.SetAutomationId(said, noticeId);
        var body = new StackPanel { Spacing = 12, MinWidth = 400 };
        var outer = new StackPanel { Spacing = 12 };
        outer.Children.Add(said);
        outer.Children.Add(body);
        return (said, outer, body);
    }

    internal static StackPanel Loading(string words)
    {
        var ring = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        ring.Children.Add(new ProgressRing { IsActive = true, Width = 20, Height = 20 });
        ring.Children.Add(TermsText.Line(words, true));
        return ring;
    }

    /// <summary>What the core said in a list's place, with Try again where it can be read anew.</summary>
    internal static void Line(StackPanel body, string text, string id, Action? again)
    {
        var line = TermsText.Line(text, false);
        AutomationProperties.SetAutomationId(line, id);
        body.Children.Add(line);
        if (again is null) return;
        var button = new Button { Content = Words.PeopleRetry() };
        AutomationProperties.SetAutomationId(button, $"{id}-retry");
        button.Click += (_, _) => again();
        body.Children.Add(button);
    }

    private static TextBlock Badge(string words) => new()
    {
        Text = words,
        Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
        Style = Style("CaptionTextBlockStyle"),
        TextWrapping = TextWrapping.Wrap,
    };

    private static StackPanel Card(string id)
    {
        var ask = new StackPanel { Spacing = 8, Padding = new Thickness(12), CornerRadius = new CornerRadius(4), Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"] };
        AutomationProperties.SetAutomationId(ask, id);
        return ask;
    }

    private static StackPanel Buttons(Button confirm, string cancelWords, string cancelId, Action cancel)
    {
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var no = new Button { Content = cancelWords };
        AutomationProperties.SetAutomationId(no, cancelId);
        no.Click += (_, _) => cancel();
        buttons.Children.Add(confirm);
        buttons.Children.Add(no);
        return buttons;
    }

    private static MenuFlyoutItem Item(string text, string glyph, string id, Action click)
    {
        var item = new MenuFlyoutItem { Text = text, Icon = new FontIcon { Glyph = glyph } };
        AutomationProperties.SetAutomationId(item, id);
        item.Click += (_, _) => click();
        return item;
    }

    private static Style Style(string key) => (Style)Application.Current.Resources[key];
}
