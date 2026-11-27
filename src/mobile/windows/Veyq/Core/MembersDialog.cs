// Settings' Members (core's SettingsSection.MEMBERS over TenantMembers, the web's TenantMembersPanel roster;
// uc.spaces.remove-tenant-member): every member of the open space with their role and state, in a ContentDialog over the
// page as Who's who is. A row's acts — "Make co-owner", "Remove co-owner", "Remove member" — are its "More" button's
// MenuFlyout, offered as the core says; the removal is asked in place (one ContentDialog at a time, Dialogs.cs), with the
// option to turn off every link of the space. What an act says is an InfoBar at the dialog's top. Linux's core_ui::members.
// An owner also invites (*Invite member*, InviteDialog: the Members dialog gives way to it — one ContentDialog at a time —
// and opens again after) and sees the pending invites under the roster (uc.spaces.manage-member-invites, the web's
// TenantMembersPanel): each row's title, its expiry, the *Invited* badge, and its "More" flyout — Rename (a link invite,
// asked in place), Send again / New link (its new link or code shown once, InviteDialog.ShowResult, as the invite's is),
// Cancel invite (asked in place as the removal is).

namespace Veyq.Core;

using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal static class MembersDialog
{
    public static async void Open(Window window)
    {
        try
        {
            // what the dialog gave way to (Invite member, a re-issue's result), then the dialog again
            while (await Once(window) is { } next) await next();
        }
        catch (Exception e)
        {
            Log.Write($"the members did not open ({e.GetType().Name})");
        }
    }

    /// <summary>The dialog once; what it gave way to, or null where it was closed.</summary>
    private static async System.Threading.Tasks.Task<Func<System.Threading.Tasks.Task>?> Once(Window window)
    {
        Func<System.Threading.Tasks.Task>? after = null;
        {
            var said = new InfoBar { IsOpen = false, IsClosable = true };
            AutomationProperties.SetAutomationId(said, "members-notice");
            var body = new StackPanel { Spacing = 12 };
            var outer = new StackPanel { Spacing = 12 };
            outer.Children.Add(said);
            outer.Children.Add(body);
            var dialog = new ContentDialog
            {
                Title = Words.MembersTitle(),
                Content = new ScrollViewer { Content = outer, MaxHeight = 560 },
                CloseButtonText = Words.ShareDone(),
                DefaultButton = ContentDialogButton.Close,
            };
            AutomationProperties.SetAutomationId(dialog, "members");
            void Say(Notice n)
            {
                said.Message = Said.Notice(n);
                said.Severity = n.kind == NoticeKind.Said ? InfoBarSeverity.Success : InfoBarSeverity.Error;
                said.IsOpen = true;
            }
            async void Run(Call call)
            {
                body.IsHitTestVisible = false;
                var (answer, _) = await Core.CallAsync(call);
                body.IsHitTestVisible = true;
                if (answer is Answer.MembersChanged { notice: { } n } changed)
                {
                    Say(n);
                    if (changed.done) Load();
                }
                else if (answer is Answer.Invited { outcome: { } o })
                {
                    Log.Write($"an invite's act: {o.notice.msg}, done {o.done}");
                    if (o.done && (o.address is not null || o.code is not null))
                    {
                        // a re-issue's new link or code, shown once over the page; the roster reads anew after
                        after = () => InviteDialog.ShowResult(window, o, ShellView.OpenTenantName);
                        dialog.Hide();
                        return;
                    }
                    Say(o.notice);
                    if (o.done) Load();
                }
                else Log.Write($"a member's act answered {answer?.GetType().Name}");
            }
            async void Load()
            {
                body.Children.Clear();
                body.Children.Add(TermsText.Line(Words.MembersSub(), true));
                var ring = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
                ring.Children.Add(new ProgressRing { IsActive = true, Width = 20, Height = 20 });
                ring.Children.Add(TermsText.Line(Words.MembersLoading(), true));
                body.Children.Add(ring);
                var (answer, _) = await Core.CallAsync(new Call.SettingsMembers());
                body.Children.Remove(ring);
                if (answer is not Answer.Members { roster: { } roster })
                {
                    Log.Write($"Members.Read answered {answer?.GetType().Name}");
                    Line(body, Words.MembersLoadFailed(), retry: true, Load);
                    return;
                }
                if (roster.said is { } line)
                {
                    Line(body, ReceivedText.Said(line), roster.retry, Load);
                    return;
                }
                if (roster.owner)
                {
                    var invite = new Button { Content = Words.MembersInvite(), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
                    var inviteContent = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
                    inviteContent.Children.Add(new FontIcon { Glyph = "\uE8FA", FontSize = 14 });
                    inviteContent.Children.Add(new TextBlock { Text = Words.MembersInvite() });
                    invite.Content = inviteContent;
                    AutomationProperties.SetName(invite, Words.MembersInvite());
                    AutomationProperties.SetAutomationId(invite, "members-invite");
                    invite.Click += (_, _) =>
                    {
                        after = async () => await InviteDialog.Open(window, ShellView.OpenTenantName);
                        dialog.Hide();
                    };
                    body.Children.Add(invite);
                }
                var list = new StackPanel { Spacing = 4 };
                AutomationProperties.SetAutomationId(list, "members-list");
                foreach (var row in roster.rows) list.Children.Add(Row(row, Run, body));
                body.Children.Add(list);
                if (roster.owner && roster.invites.Count > 0) body.Children.Add(Pending(roster.invites, Run, body));
            }
            Load();
            await Dialogs.Show(window, dialog);
        }
        return after;
    }

    /// <summary>The owners' pending invites under the roster: the heading, its line, and one row each.</summary>
    private static UIElement Pending(System.Collections.Generic.IReadOnlyList<PendingInvite> invites, Action<Call> run, StackPanel body)
    {
        var panel = new StackPanel { Spacing = 4, Margin = new Thickness(0, 12, 0, 0) };
        AutomationProperties.SetAutomationId(panel, "members-pending");
        var heading = new TextBlock { Text = Words.MembersPendingTitle(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
        AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        panel.Children.Add(heading);
        panel.Children.Add(TermsText.Line(Words.MembersPendingSub(), true));
        foreach (var invite in invites) panel.Children.Add(InviteRow(invite, run, body));
        return panel;
    }

    /// <summary>One pending invite: a link's or a mail's icon, the title (the address, the label, or *Link invitation*),
    /// the line under it, the *Invited* badge, and the acts in its flyout.</summary>
    private static UIElement InviteRow(PendingInvite invite, Action<Call> run, StackPanel body)
    {
        var grid = new Grid { ColumnSpacing = 12, Padding = new Thickness(0, 6, 0, 6) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        AutomationProperties.SetAutomationId(grid, $"invite-{invite.inviteId}");
        var icon = new FontIcon { Glyph = invite.link ? "\uE71B" : "\uE715", FontSize = 20, Width = 40, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, 4, 0, 0) };
        grid.Children.Add(icon);
        var title = invite.title ?? (invite.titleWord is { } w ? ReceivedText.Said(w) : "");
        var column = new StackPanel { Spacing = 2 };
        Grid.SetColumn(column, 1);
        var name = new TextBlock { Text = title, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], TextWrapping = TextWrapping.Wrap };
        AutomationProperties.SetAutomationId(name, "invite-title");
        column.Children.Add(name);
        var line = invite.subtitle is { } parts ? Lives.Joined(System.Linq.Enumerable.Select(parts, Said.Notice)) : Said.Notice(invite.expires);
        var sub = TermsText.Line(line, true);
        AutomationProperties.SetAutomationId(sub, "invite-subtitle");
        column.Children.Add(sub);
        var badge = new TextBlock { Text = Words.MembersStatusInvited(), Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"], Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"] };
        AutomationProperties.SetAutomationId(badge, "invite-status");
        column.Children.Add(badge);
        grid.Children.Add(column);
        var menu = new MenuFlyout();
        if (invite.link) menu.Items.Add(Item(Words.MembersLabelEdit(), "\uE70F", "invite-rename", () => AskLabel(body, invite, run)));
        menu.Items.Add(Item(invite.link ? Words.MembersReissueLink() : Words.MembersReissue(), "\uE72C", "invite-reissue", () => run(new Call.SettingsInviteReissue { invite = invite })));
        menu.Items.Add(Item(Words.MembersRevoke(), "\uE711", "invite-revoke", () => AskRevoke(body, invite, run)));
        var more = new Button { Content = new FontIcon { Glyph = "\uE712", FontSize = 14 }, Flyout = menu, VerticalAlignment = VerticalAlignment.Top };
        AutomationProperties.SetName(more, title);
        AutomationProperties.SetAutomationId(more, $"invite-menu-{invite.inviteId}");
        Grid.SetColumn(more, 2);
        grid.Children.Add(more);
        return grid;
    }

    /// <summary>A link invite's *Rename*, asked in place at the list's top: its label (empty clears it), Save and Cancel.</summary>
    private static void AskLabel(StackPanel body, PendingInvite invite, Action<Call> run)
    {
        var ask = Card("invite-label-ask");
        ask.Children.Add(new TextBlock { Text = Words.MembersLabelTitle(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] });
        var box = new TextBox { Header = Words.MembersLinkLabel(), Description = InviteDialog.Hint(Words.MembersLinkLabelHint()), Text = invite.label ?? "", MaxLength = InviteDialog.LabelMax };
        AutomationProperties.SetAutomationId(box, "invite-label-field");
        ask.Children.Add(box);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var save = new Button { Content = Words.EntrySave(), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetAutomationId(save, "invite-label-save");
        var cancel = new Button { Content = Words.EntryCancel() };
        AutomationProperties.SetAutomationId(cancel, "invite-label-cancel");
        save.Click += (_, _) =>
        {
            body.Children.Remove(ask);
            run(new Call.SettingsInviteLabel { inviteId = invite.inviteId, label = box.Text.Trim().Length > 0 ? box.Text : null });
        };
        cancel.Click += (_, _) => body.Children.Remove(ask);
        buttons.Children.Add(save);
        buttons.Children.Add(cancel);
        ask.Children.Add(buttons);
        Place(body, ask);
        box.Loaded += (_, _) => box.Focus(FocusState.Programmatic);
    }

    /// <summary>*Cancel invite*'s question, asked in place at the list's top: its words, Cancel invite and Cancel.</summary>
    private static void AskRevoke(StackPanel body, PendingInvite invite, Action<Call> run)
    {
        var ask = Card("invite-revoke-ask");
        ask.Children.Add(new TextBlock { Text = Words.MembersRevokeConfirmTitle(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] });
        ask.Children.Add(TermsText.Line(Words.MembersRevokeConfirm(), false));
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var revoke = new Button { Content = Words.MembersRevoke(), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetAutomationId(revoke, "invite-revoke-confirm");
        var cancel = new Button { Content = Words.SigninCancel() };
        revoke.Click += (_, _) =>
        {
            body.Children.Remove(ask);
            run(new Call.SettingsInviteRevoke { inviteId = invite.inviteId });
        };
        cancel.Click += (_, _) => body.Children.Remove(ask);
        buttons.Children.Add(revoke);
        buttons.Children.Add(cancel);
        ask.Children.Add(buttons);
        Place(body, ask);
    }

    private static StackPanel Card(string id)
    {
        var ask = new StackPanel { Spacing = 8, Padding = new Thickness(12), CornerRadius = new CornerRadius(4), Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"] };
        AutomationProperties.SetAutomationId(ask, id);
        return ask;
    }

    /// <summary>A question asked in place: one at a time, at the list's top (under the section's line).</summary>
    private static void Place(StackPanel body, StackPanel ask)
    {
        foreach (var child in body.Children)
        {
            if (child is StackPanel s && AutomationProperties.GetAutomationId(s) is "member-remove-ask" or "invite-label-ask" or "invite-revoke-ask")
            {
                body.Children.Remove(s);
                break;
            }
        }
        body.Children.Insert(Math.Min(1, body.Children.Count), ask);
    }

    private static void Line(StackPanel body, string text, bool retry, Action again)
    {
        var line = TermsText.Line(text, false);
        AutomationProperties.SetAutomationId(line, "members-said");
        body.Children.Add(line);
        if (!retry) return;
        var button = new Button { Content = Words.PeopleRetry() };
        AutomationProperties.SetAutomationId(button, "members-retry");
        button.Click += (_, _) => again();
        body.Children.Add(button);
    }

    /// <summary>One member: the picture, the title, the line under it (@username, the address, the role), the badges, and
    /// the acts the core offers in the row's flyout.</summary>
    private static UIElement Row(RosterMember row, Action<Call> run, StackPanel body)
    {
        var grid = new Grid { ColumnSpacing = 12, Padding = new Thickness(0, 6, 0, 6) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        AutomationProperties.SetAutomationId(grid, $"member-{row.userId}");
        var picture = Avatars.Person(row.realName ?? row.username, row.userId, 40);
        picture.VerticalAlignment = VerticalAlignment.Top;
        grid.Children.Add(picture);
        var column = new StackPanel { Spacing = 2 };
        Grid.SetColumn(column, 1);
        column.Children.Add(new TextBlock { Text = row.title, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] });
        var parts = new System.Collections.Generic.List<string>();
        if (row.usernameLine is { } at) parts.Add(at);
        if (row.email is { } mail) parts.Add(mail);
        parts.Add(ReceivedText.Said(row.roleWord));
        column.Children.Add(TermsText.Line(Lives.Joined(parts), true));
        var badges = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        badges.Children.Add(new TextBlock { Text = ReceivedText.Said(row.statusWord), Foreground = (Brush)Application.Current.Resources["SystemFillColorSuccessBrush"], Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"] });
        if (row.pendingWord is { } pending)
        {
            var badge = new TextBlock { Text = ReceivedText.Said(pending), Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"], Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"] };
            AutomationProperties.SetAutomationId(badge, "member-pending");
            badges.Children.Add(badge);
        }
        column.Children.Add(badges);
        grid.Children.Add(column);
        if (row.promote || row.demote || row.remove)
        {
            var menu = new MenuFlyout();
            if (row.promote) menu.Items.Add(Item(Words.MembersPromote(), "", "member-promote", () => run(new Call.SettingsMemberRole { userId = row.userId, owner = true })));
            if (row.demote) menu.Items.Add(Item(Words.MembersDemote(), "", "member-demote", () => run(new Call.SettingsMemberRole { userId = row.userId, owner = false })));
            if (row.remove) menu.Items.Add(Item(Words.MembersRemove(), "", "member-remove", () => AskRemove(body, row, run)));
            var more = new Button { Content = new FontIcon { Glyph = "", FontSize = 14 }, Flyout = menu, VerticalAlignment = VerticalAlignment.Top };
            AutomationProperties.SetName(more, row.title);
            AutomationProperties.SetAutomationId(more, $"member-menu-{row.userId}");
            Grid.SetColumn(more, 2);
            grid.Children.Add(more);
        }
        return grid;
    }

    private static MenuFlyoutItem Item(string text, string glyph, string id, Action click)
    {
        var item = new MenuFlyoutItem { Text = text, Icon = new FontIcon { Glyph = glyph } };
        AutomationProperties.SetAutomationId(item, id);
        item.Click += (_, _) => click();
        return item;
    }

    /// <summary>The removal's question, asked in place at the list's top (the web's askWithOptions): its words, the
    /// opt-in to turn off every link of the space, Remove and Cancel.</summary>
    private static void AskRemove(StackPanel body, RosterMember row, Action<Call> run)
    {
        var ask = new StackPanel { Spacing = 8, Padding = new Thickness(12), CornerRadius = new CornerRadius(4), Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"] };
        AutomationProperties.SetAutomationId(ask, "member-remove-ask");
        var title = new TextBlock { Text = Words.MembersRemove(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
        ask.Children.Add(title);
        ask.Children.Add(TermsText.Line(Words.MembersRemoveConfirm(), false));
        var links = new CheckBox { Content = Words.MembersRemoveRevokeLinks() };
        AutomationProperties.SetAutomationId(links, "member-remove-links");
        ask.Children.Add(links);
        ask.Children.Add(TermsText.Line(Words.MembersRemoveRevokeLinksHint(), true));
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var remove = new Button { Content = Words.MembersRemove(), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetAutomationId(remove, "member-remove-confirm");
        var cancel = new Button { Content = Words.SigninCancel() };
        remove.Click += (_, _) =>
        {
            body.Children.Remove(ask);
            run(new Call.SettingsMemberRemove { userId = row.userId, revokeLinks = links.IsChecked == true });
        };
        cancel.Click += (_, _) => body.Children.Remove(ask);
        buttons.Children.Add(remove);
        buttons.Children.Add(cancel);
        ask.Children.Add(buttons);
        Place(body, ask);
    }
}
