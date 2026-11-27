// What the space's Members and a group's page share (the web's TenantMembersPanel and GroupMembersPanel; Linux's
// core_ui::scope_acts): the scope's identity header (Header: its icon opened by the core, its initial otherwise, as a
// PersonPicture draws a person, beside its name) with an owner's *Add icon* / *Change icon* and *Remove icon*, a member
// row's trust badge (Badge), and an owner's *Links* and *New lock* sections (LinksAndLock), each a danger button behind
// its question. The icon is the core's to seal and open (Settings.Icon, IconSet, IconClear over ScopeIcons); its pick is
// the app's one square crop (SquareCropDialog.Pick), a ContentDialog, so the page gives way to it and opens again with
// what the act said (PickIcon). A question is asked in place under its section, one at a time on a page (Asks).

namespace Veyq.Core;

using System;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal static class ScopeActs
{
    /// <summary>A page's questions asked in place: one at a time, the last one asked replacing the one before.</summary>
    internal sealed class Asks
    {
        private (Panel Host, UIElement Card)? _open;

        public void Show(Panel host, UIElement card)
        {
            Drop();
            host.Children.Add(card);
            _open = (host, card);
        }

        public void Drop()
        {
            if (_open is { } o) o.Host.Children.Remove(o.Card);
            _open = null;
        }
    }

    /// <summary>The header of <paramref name="scopeId"/> named <paramref name="name"/>: the icon (asked of the core now),
    /// and where <paramref name="owner"/> its acts — <paramref name="pick"/> gives the page's way to the square crop (handed
    /// the button's words, the crop's title),
    /// Remove icon asks in place and runs through <paramref name="cleared"/>. <paramref name="beside"/> joins the acts (a
    /// group's Rename).</summary>
    public static UIElement Header(string scopeId, string name, bool owner, Asks asks, Action<string> pick, Func<Call, Task> cleared, string nameId, UIElement? beside = null)
    {
        var outer = new StackPanel { Spacing = 8 };
        var row = new Grid { ColumnSpacing = 16 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        AutomationProperties.SetAutomationId(row, "scope-header");
        var picture = new PersonPicture { DisplayName = name, Width = 64, Height = 64, VerticalAlignment = VerticalAlignment.Top };
        AutomationProperties.SetName(picture, name);
        AutomationProperties.SetAutomationId(picture, "scope-icon");
        row.Children.Add(picture);
        var side = new StackPanel { Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
        Grid.SetColumn(side, 1);
        var title = new TextBlock { Text = name, Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"], TextWrapping = TextWrapping.Wrap };
        AutomationProperties.SetHeadingLevel(title, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level1);
        AutomationProperties.SetAutomationId(title, nameId);
        side.Children.Add(title);
        Button? choose = null, remove = null;
        if (owner || beside is not null)
        {
            var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            if (owner)
            {
                choose = new Button { Content = Words.SpaceIconAdd() };
                AutomationProperties.SetAutomationId(choose, "scope-icon-choose");
                choose.Click += (_, _) => pick(choose.Content as string ?? Words.SpaceIconAdd());
                remove = new Button { Content = Words.SpaceIconRemove(), Visibility = Visibility.Collapsed };
                AutomationProperties.SetAutomationId(remove, "scope-icon-remove");
                remove.Click += (_, _) => Ask(asks, outer, "scope-icon-remove-ask", Words.SpaceIconRemove(), Words.SpaceIconRemoveConfirm(), Words.SpaceIconRemove(), "scope-icon-remove-confirm",
                    () => cleared(new Call.SettingsIconClear { scopeId = scopeId }));
                buttons.Children.Add(choose);
                buttons.Children.Add(remove);
            }
            if (beside is not null) buttons.Children.Add(beside);
            side.Children.Add(buttons);
        }
        row.Children.Add(side);
        outer.Children.Add(row);
        ReadIcon(scopeId, picture, choose, remove);
        return outer;
    }

    /// <summary>The icon as the core opens it now (refused: none, the initial); the owner's buttons follow whether one is held.</summary>
    private static async void ReadIcon(string scopeId, PersonPicture picture, Button? choose, Button? remove)
    {
        var held = false;
        try
        {
            var (answer, bytes) = await Core.CallAsync(new Call.SettingsIcon { scopeId = scopeId });
            if (answer is Answer.Bytes && bytes is not null)
            {
                picture.ProfilePicture = await Avatars.Decode(bytes);
                Array.Clear(bytes);
                held = true;
            }
            else Log.Write($"Settings.Icon answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
        }
        catch (Exception e)
        {
            Log.Write($"a scope's icon did not decode ({e.GetType().Name})");
        }
        Log.Write($"a scope's icon: held {held}");
        if (choose is not null) choose.Content = held ? Words.SpaceIconChange() : Words.SpaceIconAdd();
        if (remove is not null) remove.Visibility = held ? Visibility.Visible : Visibility.Collapsed;
    }

    /// <summary>*Add icon* / *Change icon* while the page has given way: the square pick, its picture set as the icon of
    /// <paramref name="scopeId"/> (Settings.IconSet); what to say on the page's return (its words, whether it went through),
    /// or null where nothing was chosen.</summary>
    public static async Task<(string Said, bool Done)?> PickIcon(Window window, string scopeId, string title)
    {
        try
        {
            var picked = await SquareCropDialog.Pick(window, title);
            if (picked is SquareCropDialog.NotAPicture) return (Words.ProfilePictureNotImage(), false);
            if (picked is not SquareCropDialog.Picked chosen) return null;
            var (answer, _) = await Core.CallAsync(new Call.SettingsIconSet { scopeId = scopeId, path = chosen.Path, pose = chosen.Pose });
            if (answer is Answer.IconChanged { notice: { } n } changed)
            {
                Log.Write($"Settings.IconSet: done {changed.done}");
                return (Said.Notice(n), changed.done);
            }
            Log.Write($"Settings.IconSet answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
            return (Words.SpaceIconFailed(), false);
        }
        catch (Exception e)
        {
            Log.Write($"a scope's icon was not chosen ({e.GetType().Name})");
            return (Words.SpaceIconFailed(), false);
        }
    }

    /// <summary>A member row's trust badge (<paramref name="word"/>) and its note (an anomaly's, <paramref name="note"/>)
    /// under it; none: nothing.</summary>
    public static void Badge(StackPanel column, Msg? word, Msg? note)
    {
        if (word is not { } shown) return;
        var badge = new TextBlock
        {
            Text = ReceivedText.Said(shown),
            Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
            Foreground = (Brush)Application.Current.Resources[note is null ? "AccentTextFillColorPrimaryBrush" : "SystemFillColorCautionBrush"],
        };
        AutomationProperties.SetAutomationId(badge, "member-trust");
        column.Children.Add(badge);
        if (note is not { } why) return;
        var line = TermsText.Line(ReceivedText.Said(why), true);
        line.Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"];
        AutomationProperties.SetAutomationId(line, "member-trust-note");
        column.Children.Add(line);
    }

    /// <summary>An owner's *Links* and *New lock* sections: *Turn off every link* (<paramref name="links"/>) and *Give it a
    /// new lock* (<paramref name="newLock"/>), each behind its question and busy while it runs through
    /// <paramref name="run"/>; <paramref name="lockSub"/> and <paramref name="lockConfirm"/> the space's or the group's words.</summary>
    public static UIElement LinksAndLock(string lockSub, string lockConfirm, Call links, Call newLock, Asks asks, Func<Call, Task> run)
    {
        var panel = new StackPanel { Spacing = 16, Margin = new Thickness(0, 16, 0, 0) };
        panel.Children.Add(Section("scope-links", Words.SpaceLinksTitle(), Words.SpaceLinksSub(), Words.SpaceLinksRevokeAll(), "", "scope-links-revoke",
            (host, busy) => Ask(asks, host, "scope-links-ask", Words.SpaceLinksRevokeAll(), Words.SpaceLinksRevokeAllConfirm(), Words.SpaceLinksRevokeAll(), "scope-links-revoke-confirm", () => busy(run(links)))));
        panel.Children.Add(Section("scope-lock", Words.SpaceLockTitle(), lockSub, Words.SpaceLockAction(), "", "scope-new-lock",
            (host, busy) => Ask(asks, host, "scope-lock-ask", Words.SpaceLockConfirmTitle(), lockConfirm, Words.SpaceLockAction(), "scope-new-lock-confirm", () => busy(run(newLock)))));
        return panel;
    }

    /// <summary>One section: its heading, its line and its danger button; <paramref name="clicked"/> is handed the section
    /// (its question's place) and how to show it busy while a task runs.</summary>
    private static UIElement Section(string id, string title, string sub, string action, string glyph, string buttonId, Action<Panel, Func<Task, Task>> clicked)
    {
        var section = new StackPanel { Spacing = 8 };
        AutomationProperties.SetAutomationId(section, id);
        var heading = new TextBlock { Text = title, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
        AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        section.Children.Add(heading);
        section.Children.Add(TermsText.Line(sub, true));
        var content = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        content.Children.Add(new FontIcon { Glyph = glyph, FontSize = 14 });
        content.Children.Add(new TextBlock { Text = action });
        var button = Danger(content);
        AutomationProperties.SetName(button, action);
        AutomationProperties.SetAutomationId(button, buttonId);
        var ring = new ProgressRing { IsActive = false, Width = 20, Height = 20, Visibility = Visibility.Collapsed };
        AutomationProperties.SetAutomationId(ring, $"{id}-busy");
        var line = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
        line.Children.Add(button);
        line.Children.Add(ring);
        section.Children.Add(line);
        async Task Busy(Task running)
        {
            button.IsEnabled = false;
            ring.IsActive = true;
            ring.Visibility = Visibility.Visible;
            try
            {
                await running;
            }
            finally
            {
                button.IsEnabled = true;
                ring.IsActive = false;
                ring.Visibility = Visibility.Collapsed;
            }
        }
        button.Click += (_, _) => clicked(section, Busy);
        return section;
    }

    /// <summary>A danger act's question in place under <paramref name="host"/>: <paramref name="title"/>,
    /// <paramref name="body"/>, <paramref name="accept"/> in the danger look beside Cancel; <paramref name="then"/> on accept.</summary>
    private static void Ask(Asks asks, Panel host, string id, string title, string body, string accept, string acceptId, Func<Task> then)
    {
        var card = new StackPanel { Spacing = 8, Padding = new Thickness(12), CornerRadius = new CornerRadius(4), Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"] };
        AutomationProperties.SetAutomationId(card, id);
        card.Children.Add(new TextBlock { Text = title, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], TextWrapping = TextWrapping.Wrap });
        card.Children.Add(TermsText.Line(body, false));
        var yes = Danger(accept);
        AutomationProperties.SetAutomationId(yes, acceptId);
        var no = new Button { Content = Words.SigninCancel() };
        AutomationProperties.SetAutomationId(no, $"{id}-cancel");
        yes.Click += async (_, _) =>
        {
            asks.Drop();
            try
            {
                await then();
            }
            catch (Exception e)
            {
                Log.Write($"a scope's act failed ({e.GetType().Name})");
            }
        };
        no.Click += (_, _) => asks.Drop();
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        buttons.Children.Add(yes);
        buttons.Children.Add(no);
        card.Children.Add(buttons);
        asks.Show(host, card);
    }

    /// <summary>A button in Fluent's critical look, white on the critical fill (the danger act's, as the locked screen's).</summary>
    private static Button Danger(object content) => new()
    {
        Content = content,
        Foreground = new SolidColorBrush(Microsoft.UI.Colors.White),
        Background = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"],
    };
}
