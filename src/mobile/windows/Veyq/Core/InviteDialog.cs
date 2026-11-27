// *Invite member* (uc.spaces.add-member; core's TenantInvites, the web's InviteMemberDialog and InviteLinkResult): a
// ContentDialog through Dialogs.Show with the two ways, a link first (ADR 0129 decision 1) — a SelectorBar Link | Email —:
// the link's optional label and Create link, or an address with a generated code or a password the owner chooses
// (≥ 10, masked) and Send. The invite is made on this device by the core (Settings.InviteLink / Settings.InviteMail,
// "Securing the invitation on this device…" meanwhile); a refusal says why under the form and keeps it, one that went
// through turns the same dialog into its result, shown once: a link with its warning and the message around it —
// editable, copied, opened in a new mail (mailto: with the subject word) —, or the mailed invite's code with Copy (an
// owner's own password is not shown back). A pending invite's *Send again* / *New link* shows its result the same way
// (InviteDialog.Result). The link and the code are made to be handed on (src/mobile/CLAUDE.md § Secrets on screen): no
// guard, the owner's chosen password is a PasswordBox. Android's InviteScreens.kt, Apple's InvitesView.swift.

namespace Veyq.Core;

using System;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.ApplicationModel.DataTransfer;

internal static class InviteDialog
{
    /// <summary>The account policy's floor for a password the owner chooses (core's PasswordChange.MIN_PASSWORD_LENGTH).</summary>
    private const int MinPassword = 10;

    /// <summary>The longest label (core's TenantInvites.LABEL_MAX_LENGTH).</summary>
    internal const int LabelMax = 80;

    /// <summary>*Invite member* over <paramref name="window"/> for space <paramref name="spaceName"/>; whether an invite
    /// was made (the roster then reads anew).</summary>
    public static async Task<bool> Open(Window window, string spaceName)
    {
        var made = false;
        try
        {
            var ways = new SelectorBar();
            var linkWay = new SelectorBarItem { Text = Words.MembersWayLink(), IsSelected = true };
            var mailWay = new SelectorBarItem { Text = Words.MembersWayMail() };
            AutomationProperties.SetAutomationId(linkWay, "invite-way-link");
            AutomationProperties.SetAutomationId(mailWay, "invite-way-mail");
            ways.Items.Add(linkWay);
            ways.Items.Add(mailWay);

            var linkIntro = Line(Words.MembersLinkIntro(), true);
            var label = new TextBox { Header = Words.MembersLinkLabel(), Description = Hint(Words.MembersLinkLabelHint()), MaxLength = LabelMax };
            AutomationProperties.SetAutomationId(label, "invite-label");
            var link = new StackPanel { Spacing = 12 };
            link.Children.Add(linkIntro);
            link.Children.Add(label);

            var mailIntro = Line(Words.MembersInviteIntro(), true);
            var email = new TextBox { Header = Words.MembersEmail(), InputScope = Scope(InputScopeNameValue.EmailSmtpAddress) };
            AutomationProperties.SetAutomationId(email, "invite-email");
            var password = new PasswordBox { Header = Words.MembersInvitePasswordLabel(), Description = Hint(Words.MembersInvitePasswordNote()), Visibility = Visibility.Collapsed };
            AutomationProperties.SetAutomationId(password, "invite-password");
            var toggle = new HyperlinkButton { Content = Words.MembersInviteUsePassword(), Padding = new Thickness(0) };
            AutomationProperties.SetAutomationId(toggle, "invite-password-toggle");
            var mail = new StackPanel { Spacing = 12, Visibility = Visibility.Collapsed };
            mail.Children.Add(mailIntro);
            mail.Children.Add(email);
            mail.Children.Add(password);
            mail.Children.Add(toggle);

            var refusal = new TextBlock { TextWrapping = TextWrapping.Wrap, Foreground = Brush("SystemFillColorCriticalBrush"), Visibility = Visibility.Collapsed };
            AutomationProperties.SetAutomationId(refusal, "invite-refusal");
            AutomationProperties.SetLiveSetting(refusal, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
            var working = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Visibility = Visibility.Collapsed };
            working.Children.Add(new ProgressRing { IsActive = true, Width = 20, Height = 20 });
            working.Children.Add(Line(Words.MembersInviteSecuring(), true));
            AutomationProperties.SetAutomationId(working, "invite-working");

            var form = new StackPanel { Spacing = 12, MinWidth = 400, MaxWidth = 480 };
            foreach (var c in new UIElement[] { ways, link, mail, refusal, working }) form.Children.Add(c);
            var dialog = new ContentDialog
            {
                Title = Words.MembersInvite(),
                Content = new ScrollViewer { Content = form, MaxHeight = 560 },
                PrimaryButtonText = Words.MembersLinkCreate(),
                CloseButtonText = Words.EntryCancel(),
                DefaultButton = ContentDialogButton.Primary,
            };
            AutomationProperties.SetAutomationId(dialog, "invite");

            var byMail = false;
            var ownPassword = false;
            void Enable()
            {
                dialog.IsPrimaryButtonEnabled = !byMail
                    || (email.Text.Trim().Length > 0 && (!ownPassword || password.Password.Length >= MinPassword));
            }
            ways.SelectionChanged += (_, _) =>
            {
                byMail = ReferenceEquals(ways.SelectedItem, mailWay);
                link.Visibility = byMail ? Visibility.Collapsed : Visibility.Visible;
                mail.Visibility = byMail ? Visibility.Visible : Visibility.Collapsed;
                dialog.PrimaryButtonText = byMail ? Words.MembersInviteSubmit() : Words.MembersLinkCreate();
                refusal.Visibility = Visibility.Collapsed;
                Enable();
            };
            toggle.Click += (_, _) =>
            {
                ownPassword = !ownPassword;
                password.Password = "";
                password.Visibility = ownPassword ? Visibility.Visible : Visibility.Collapsed;
                toggle.Content = ownPassword ? Words.MembersInviteUseCode() : Words.MembersInviteUsePassword();
                Enable();
            };
            email.TextChanged += (_, _) => Enable();
            password.PasswordChanged += (_, _) => Enable();

            TenantInvitesOutcome? shown = null;
            dialog.PrimaryButtonClick += async (_, args) =>
            {
                // the dialog stays while the core makes the invite: its result replaces the form, a refusal stands under it
                var deferral = args.GetDeferral();
                args.Cancel = true;
                try
                {
                    if (shown is not null) return;
                    dialog.IsPrimaryButtonEnabled = false;
                    form.IsHitTestVisible = false;
                    refusal.Visibility = Visibility.Collapsed;
                    working.Visibility = Visibility.Visible;
                    Call call = byMail
                        ? new Call.SettingsInviteMail { email = email.Text, password = ownPassword ? password.Password : null }
                        : new Call.SettingsInviteLink { label = label.Text.Trim().Length > 0 ? label.Text : null };
                    var (answer, _) = await Core.CallAsync(call);
                    working.Visibility = Visibility.Collapsed;
                    form.IsHitTestVisible = true;
                    if (answer is Answer.Invited { outcome: { } o })
                    {
                        Log.Write($"invite by {(byMail ? "mail" : "link")}: {o.notice.msg}, done {o.done}");
                        if (o.done)
                        {
                            made = true;
                            shown = o;
                            password.Password = "";
                            dialog.Title = o.address is not null ? Words.MembersLinkResultTitle() : Words.MembersInvite();
                            dialog.Content = new ScrollViewer { Content = Result(o, spaceName), MaxHeight = 560 };
                            dialog.PrimaryButtonText = "";
                            dialog.CloseButtonText = Words.ShareDone();
                            dialog.DefaultButton = ContentDialogButton.Close;
                            return;
                        }
                        refusal.Text = Said.Notice(o.notice);
                        refusal.Visibility = Visibility.Visible;
                    }
                    else Log.Write($"invite answered {answer?.GetType().Name}");
                }
                catch (Exception e)
                {
                    working.Visibility = Visibility.Collapsed;
                    form.IsHitTestVisible = true;
                    Log.Write($"the invite failed ({e.GetType().Name})");
                }
                finally
                {
                    if (shown is null) Enable();
                    deferral.Complete();
                }
            };
            Enable();
            label.Loaded += (_, _) => label.Focus(FocusState.Programmatic);
            await Dialogs.Show(window, dialog);
        }
        catch (Exception e)
        {
            Log.Write($"Invite member did not open ({e.GetType().Name})");
        }
        return made;
    }

    /// <summary>A re-issue's (or any invite's) result over <paramref name="window"/> on its own, shown once.</summary>
    public static async Task ShowResult(Window window, TenantInvitesOutcome outcome, string spaceName)
    {
        try
        {
            var dialog = new ContentDialog
            {
                Title = outcome.address is not null ? Words.MembersLinkResultTitle() : Words.MembersInvite(),
                Content = new ScrollViewer { Content = Result(outcome, spaceName), MaxHeight = 560 },
                CloseButtonText = Words.ShareDone(),
                DefaultButton = ContentDialogButton.Close,
            };
            AutomationProperties.SetAutomationId(dialog, "invite-result");
            await Dialogs.Show(window, dialog);
        }
        catch (Exception e)
        {
            Log.Write($"an invite's result did not show ({e.GetType().Name})");
        }
    }

    /// <summary>What an invite made: a link with its warning, the message (editable), Copy and Open in email; or the
    /// mailed invite's intro and its code with Copy.</summary>
    private static UIElement Result(TenantInvitesOutcome o, string spaceName)
    {
        var panel = new StackPanel { Spacing = 12, MinWidth = 400, MaxWidth = 480 };
        AutomationProperties.SetAutomationId(panel, "invite-result-body");
        var said = new TextBlock { Text = Said.Notice(o.notice), TextWrapping = TextWrapping.Wrap, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
        AutomationProperties.SetAutomationId(said, "invite-result-said");
        panel.Children.Add(said);
        if (o.address is { } address)
        {
            var warning = new TextBlock { Text = Words.MembersLinkWarning(), TextWrapping = TextWrapping.Wrap, Foreground = Brush("SystemFillColorCautionBrush") };
            AutomationProperties.SetAutomationId(warning, "invite-result-warning");
            panel.Children.Add(warning);
            // multi-line before its text: a TextBox set while single-line keeps only the first line (the link is the last)
            var message = new TextBox { Header = Words.MembersLinkMessageLabel(), AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, MinHeight = 120 };
            message.Text = o.message is { } m ? Said.Notice(m) : address;
            AutomationProperties.SetAutomationId(message, "invite-result-message");
            panel.Children.Add(message);
            var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            buttons.Children.Add(CopyButton(() => message.Text, Words.MembersLinkCopy(), "invite-result-copy"));
            var mailButton = IconButton("", Words.MembersLinkMail(), "invite-result-mail");
            mailButton.Click += (_, _) => Mail(Words.MembersLinkMailSubject(spaceName), message.Text);
            buttons.Children.Add(mailButton);
            panel.Children.Add(buttons);
        }
        else
        {
            panel.Children.Add(Line(o.ownChoice ? Words.MembersInviteSentPasswordIntro() : Words.MembersInviteSentIntro(), false));
            if (o.code is { } code)
            {
                var box = new TextBox { Header = Words.MembersInviteCodeLabel(), Description = Hint(Words.MembersInviteCodeNote()), Text = code, IsReadOnly = true, FontSize = 20 };
                AutomationProperties.SetAutomationId(box, "invite-result-code");
                panel.Children.Add(box);
                panel.Children.Add(CopyButton(() => code, Words.MembersInviteCodeCopy(), "invite-result-code-copy"));
            }
        }
        return panel;
    }

    /// <summary>A copy of <paramref name="text"/> to the clipboard, its button saying *Copied* for the core's copy note's
    /// time.</summary>
    private static Button CopyButton(Func<string> text, string word, string id)
    {
        var button = IconButton("", word, id);
        var label = (TextBlock)((StackPanel)button.Content).Children[1];
        var round = 0;
        button.Click += async (_, _) =>
        {
            try
            {
                var package = new DataPackage();
                package.SetText(text());
                Clipboard.SetContent(package);
                Log.Write($"{id}: copied");
                var mine = ++round;
                label.Text = Words.MembersLinkCopied();
                await Task.Delay(Lives.Copied ?? TimeSpan.FromSeconds(2));
                if (mine == round) label.Text = word;
            }
            catch (Exception e)
            {
                Log.Write($"{id}: the copy failed ({e.GetType().Name})");
            }
        };
        return button;
    }

    private static Button IconButton(string glyph, string word, string id)
    {
        var content = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        content.Children.Add(new FontIcon { Glyph = glyph, FontSize = 14 });
        content.Children.Add(new TextBlock { Text = word });
        var button = new Button { Content = content };
        AutomationProperties.SetName(button, word);
        AutomationProperties.SetAutomationId(button, id);
        return button;
    }

    /// <summary>A new mail with <paramref name="subject"/> and <paramref name="body"/> in the person's mail app
    /// (<c>mailto:</c>, no recipient: the owner picks one), never logged.</summary>
    private static async void Mail(string subject, string body)
    {
        try
        {
            var uri = new Uri("mailto:?subject=" + Uri.EscapeDataString(subject) + "&body=" + Uri.EscapeDataString(body));
            if (!await Windows.System.Launcher.LaunchUriAsync(uri)) Log.Write("invite: no mail app opened");
            else Log.Write("invite: a mail opened");
        }
        catch (Exception e)
        {
            Log.Write($"invite: the mail did not open ({e.GetType().Name})");
        }
    }

    /// <summary>A field's hint under it, wrapped: a TextBox's own Description is one line, cut at the field's width.</summary>
    internal static TextBlock Hint(string text) => new()
    {
        Text = text,
        TextWrapping = TextWrapping.Wrap,
        Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
        Foreground = Brush("TextFillColorSecondaryBrush"),
    };

    private static TextBlock Line(string text, bool secondary)
    {
        var t = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap };
        if (secondary) t.Foreground = Brush("TextFillColorSecondaryBrush");
        return t;
    }

    private static Brush Brush(string key) => (Brush)Application.Current.Resources[key];

    private static InputScope Scope(InputScopeNameValue value)
    {
        var scope = new InputScope();
        scope.Names.Add(new InputScopeName(value));
        return scope;
    }
}
