// Settings' Group invitations (core's SettingsSection.GROUP_INVITATIONS over TenantGroups.invitations;
// uc.spaces.consent-group-add, the web's GroupInvitationsView): the caller's pending consent proposals in the open space,
// in a ContentDialog over the page as Groups is — each its group's name as it opened with this account's identity (or *A
// group*), who invited, and Accept and Decline. What an answer says is an InfoBar at the dialog's top; the list reads anew
// once answered. Every word is the catalog's (Words.g.cs).

namespace Veyq.Core;

using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal static class GroupInvitationsDialog
{
    public static async void Open(Window window)
    {
        try
        {
            var (said, outer, body) = GroupsDialog.Frame("invitations-notice");
            var dialog = new ContentDialog
            {
                Title = Words.InvitationsTitle(),
                Content = new ScrollViewer { Content = outer, MaxHeight = 560 },
                CloseButtonText = Words.ShareDone(),
                DefaultButton = ContentDialogButton.Close,
            };
            AutomationProperties.SetAutomationId(dialog, "group-invitations");
            async void Reply(string scopeId, bool accept)
            {
                body.IsHitTestVisible = false;
                var (answer, _) = await Core.CallAsync(new Call.SettingsGroupInvitationAnswer { scopeId = scopeId, accept = accept });
                body.IsHitTestVisible = true;
                if (answer is Answer.GroupChanged { outcome: { } o })
                {
                    Log.Write($"an invitation answered (accept {accept}): done {o.done}, {o.notice?.msg}");
                    if (o.notice is { } n)
                    {
                        said.Message = Said.Notice(n);
                        said.Severity = o.done ? InfoBarSeverity.Success : InfoBarSeverity.Error;
                        said.IsOpen = true;
                    }
                    else if (o.done) said.IsOpen = false;
                    if (o.done) Load();
                }
                else Log.Write($"an invitation's answer answered {answer?.GetType().Name}");
            }
            async void Load()
            {
                body.Children.Clear();
                body.Children.Add(TermsText.Line(Words.InvitationsSub(), true));
                var ring = GroupsDialog.Loading(Words.InvitationsLoading());
                body.Children.Add(ring);
                var (answer, _) = await Core.CallAsync(new Call.SettingsGroupInvitations());
                body.Children.Remove(ring);
                if (answer is not Answer.GroupInvitations { invitations: { } invitations })
                {
                    Log.Write($"Settings.GroupInvitations answered {answer?.GetType().Name}");
                    GroupsDialog.Line(body, Words.InvitationsLoadFailed(), "invitations-said", Load);
                    return;
                }
                Log.Write($"group invitations: {invitations.rows.Count}, said {invitations.said}");
                if (invitations.said is { } line)
                {
                    GroupsDialog.Line(body, ReceivedText.Said(line), "invitations-said", invitations.rows.Count == 0 && line == Msg.InvitationsLoadFailed ? Load : null);
                    return;
                }
                var list = new StackPanel { Spacing = 4 };
                AutomationProperties.SetAutomationId(list, "invitations-list");
                foreach (var row in invitations.rows) list.Children.Add(Row(row, Reply));
                body.Children.Add(list);
            }
            Load();
            await Dialogs.Show(window, dialog);
        }
        catch (Exception e)
        {
            Log.Write($"the group invitations did not open ({e.GetType().Name})");
        }
    }

    /// <summary>One invitation: the group's icon, its name, who invited, Accept and Decline.</summary>
    private static UIElement Row(Invitation row, Action<string, bool> answer)
    {
        var grid = new Grid { ColumnSpacing = 12, Padding = new Thickness(0, 6, 0, 6) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        AutomationProperties.SetAutomationId(grid, $"invitation-{row.scopeId}");
        grid.Children.Add(new FontIcon { Glyph = "\uE902", FontSize = 20, VerticalAlignment = VerticalAlignment.Center });
        var title = row.titleWord is { } w ? ReceivedText.Said(w) : row.name ?? "";
        var column = new StackPanel { Spacing = 2 };
        Grid.SetColumn(column, 1);
        var name = new TextBlock { Text = title, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], TextWrapping = TextWrapping.Wrap };
        AutomationProperties.SetAutomationId(name, "invitation-title");
        column.Children.Add(name);
        var by = TermsText.Line(Said.Notice(row.addedByLine), true);
        AutomationProperties.SetAutomationId(by, "invitation-added-by");
        column.Children.Add(by);
        grid.Children.Add(column);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
        var accept = new Button { Content = Words.InvitationsAccept(), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetName(accept, Lives.Joined([Words.InvitationsAccept(), title]));
        AutomationProperties.SetAutomationId(accept, $"invitation-accept-{row.scopeId}");
        accept.Click += (_, _) => answer(row.scopeId, true);
        var decline = new Button { Content = Words.InvitationsDecline() };
        AutomationProperties.SetName(decline, Lives.Joined([Words.InvitationsDecline(), title]));
        AutomationProperties.SetAutomationId(decline, $"invitation-decline-{row.scopeId}");
        decline.Click += (_, _) => answer(row.scopeId, false);
        buttons.Children.Add(accept);
        buttons.Children.Add(decline);
        Grid.SetColumn(buttons, 2);
        grid.Children.Add(buttons);
        return grid;
    }
}
