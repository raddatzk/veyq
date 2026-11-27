// Settings' Members (core's SettingsSection.MEMBERS over TenantMembers, the web's TenantMembersPanel roster;
// uc.spaces.remove-tenant-member): every member of the open space with their role and state, in a ContentDialog over the
// page as Who's who is. A row's acts — "Make co-owner", "Remove co-owner", "Remove member" — are its "More" button's
// MenuFlyout, offered as the core says; the removal is asked in place (one ContentDialog at a time, Dialogs.cs), with the
// option to turn off every link of the space. What an act says is an InfoBar at the dialog's top. Linux's core_ui::members.

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
                var list = new StackPanel { Spacing = 4 };
                AutomationProperties.SetAutomationId(list, "members-list");
                foreach (var row in roster.rows) list.Children.Add(Row(row, Run, body));
                body.Children.Add(list);
            }
            Load();
            await Dialogs.Show(window, dialog);
        }
        catch (Exception e)
        {
            Log.Write($"the members did not open ({e.GetType().Name})");
        }
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
        foreach (var child in body.Children) if (child is StackPanel s && AutomationProperties.GetAutomationId(s) == "member-remove-ask") { body.Children.Remove(s); break; }
        body.Children.Insert(Math.Min(1, body.Children.Count), ask);
    }
}
