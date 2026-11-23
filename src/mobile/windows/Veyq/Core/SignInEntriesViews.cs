// The sign-in's two entries the web has (ADR 0154 § As built: the plans aligned, P9), each a page of its own drawn as the
// sign-in is — a centred column, one accent or critical button: the locked sign-in (LockedView, Gate.Locked,
// uc.account.sessions E3) — signed in without unlocking, one action, every device signed out, asked first with the web's
// question (Cancel the default), and the way back — and *Reset your password* (RecoverView, Gate.Recover,
// uc.account.recover-access steps 1–2) — the link asked for; the mailed link opens the web's page, which finishes the
// reset. The entries under each are the core's (SignIn.LockedPlan, SignIn.RecoverPlan), drawn whole and in their order. Linux's
// sign_in_entries.rs, in Fluent.

namespace Veyq.Core;

using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal static class WayInPage
{
    public static TextBlock Text(string text, string? id = null, bool secondary = false)
    {
        var t = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap };
        if (secondary) t.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        if (id is not null) AutomationProperties.SetAutomationId(t, id);
        return t;
    }

    public static TextBlock Heading(string text, string id)
    {
        var h = new TextBlock { Text = text, Style = (Style)Application.Current.Resources["TitleTextBlockStyle"], TextWrapping = TextWrapping.Wrap };
        AutomationProperties.SetAutomationId(h, id);
        AutomationProperties.SetHeadingLevel(h, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level1);
        return h;
    }

    public static TextBlock Error(string id)
    {
        var e = new TextBlock { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
        e.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        AutomationProperties.SetAutomationId(e, id);
        AutomationProperties.SetLiveSetting(e, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
        return e;
    }

    public static void ShowError(TextBlock label, string? key)
    {
        label.Text = key is null ? "" : Said.Key(key);
        label.Visibility = key is null ? Visibility.Collapsed : Visibility.Visible;
    }

    public static Button Button(string text, string id, bool accent = false)
    {
        var b = new Button { Content = text, HorizontalAlignment = HorizontalAlignment.Stretch };
        if (accent) b.Style = (Style)Application.Current.Resources["AccentButtonStyle"];
        AutomationProperties.SetAutomationId(b, id);
        return b;
    }

    /// <summary>The column every way-in page shares, centred in a scroller.</summary>
    public static UIElement Column(params UIElement[] children)
    {
        var column = new StackPanel { Spacing = 16, MaxWidth = 420, Padding = new Thickness(24), VerticalAlignment = VerticalAlignment.Center };
        foreach (var child in children) column.Children.Add(child);
        var stretch = new Grid();
        stretch.Children.Add(column);
        return new ScrollViewer { Content = stretch, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
    }
}

internal sealed class LockedView
{
    private readonly Window _window;
    private readonly TextBlock _email;
    private readonly Button _signOut;
    private readonly Button _back;
    private readonly TextBlock _backHint;
    private readonly StackPanel _entries = new() { Spacing = 8 };
    private readonly ProgressRing _working = new() { IsActive = false, Visibility = Visibility.Collapsed, HorizontalAlignment = HorizontalAlignment.Center };
    private readonly TextBlock _error = WayInPage.Error("locked-error");
    private bool _planned;

    public UIElement Page { get; }

    public LockedView(Window window)
    {
        _window = window;
        _email = WayInPage.Text("", "locked-email");
        _email.Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"];
        // destructive: Fluent's critical fill, white text
        _signOut = WayInPage.Button(Words.LockedAction(), "locked-sign-out-everywhere");
        _signOut.Background = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        _signOut.Foreground = new SolidColorBrush(Microsoft.UI.Colors.White);
        _signOut.Click += async (_, _) =>
        {
            try
            {
                if (await Dialogs.Confirm(_window, "locked-confirm", Words.LockedConfirmTitle(), Words.LockedConfirm(), Words.LockedAction()))
                {
                    Act.Send(new Call.SessionSignOutEverywhere());
                }
            }
            catch (Exception e)
            {
                // an exception out of an async void would end the app in combase, with nothing in the log
                Log.Write($"the locked sign-out's question failed: {e.GetType().Name}");
            }
        };
        _back = WayInPage.Button(Words.LockedBack(), "locked-back");
        _back.Click += (_, _) => Act.Send(new Call.SessionLockedBack());
        _backHint = WayInPage.Text(Words.LockedBackHint(), "locked-back-hint", secondary: true);
        AutomationProperties.SetAutomationId(_working, "locked-working");
        Page = WayInPage.Column(
            WayInPage.Heading(Words.LockedTitle(), "locked-title"), _email,
            WayInPage.Text(Words.LockedNote()), WayInPage.Text(Words.LockedScopeNote(), secondary: true),
            _error, _entries, _working);
    }

    /// <summary>The core's entries (SignIn.LockedPlan), asked once: the plan has no input.</summary>
    private async void Plan()
    {
        if (_planned) return;
        _planned = true;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.SignInLockedPlan());
            if (answer is not Answer.LockedPlan plan)
            {
                Log.Write($"SignIn.LockedPlan answered {answer.GetType().Name}");
                return;
            }
            _entries.Children.Clear();
            foreach (var e in plan.entries) Entry(e);
        }
        catch (Exception e)
        {
            Log.Write($"SignIn.LockedPlan failed: {e.GetType().Name}");
        }
    }

    private void Entry(LockedEntry e)
    {
        UIElement[] drawn = e switch
        {
            LockedEntry.SignOutEverywhere => [_signOut],
            LockedEntry.Back => [_back, _backHint],
        };
        foreach (var d in drawn) _entries.Children.Add(d);
    }

    /// <summary>Draws <paramref name="gate"/>: the account, working or not, the core's error.</summary>
    public void Show(Gate.Locked gate)
    {
        Plan();
        _email.Text = gate.email;
        _signOut.IsEnabled = !gate.working;
        _back.IsEnabled = !gate.working;
        _working.IsActive = gate.working;
        _working.Visibility = gate.working ? Visibility.Visible : Visibility.Collapsed;
        WayInPage.ShowError(_error, gate.error);
    }
}

internal sealed class RecoverView
{
    private readonly TextBox _server;
    private readonly TextBox _email;
    private readonly Button _send;
    private readonly Button _back;
    private readonly StackPanel _sent = new() { Spacing = 8, Visibility = Visibility.Collapsed };
    private readonly StackPanel _entries = new() { Spacing = 8 };
    private readonly ProgressRing _working = new() { IsActive = false, Visibility = Visibility.Collapsed, HorizontalAlignment = HorizontalAlignment.Center };
    private readonly TextBlock _error = WayInPage.Error("recover-error");
    private bool? _planned;
    private (string?, string?)? _shown;

    public UIElement Page { get; }

    public RecoverView()
    {
        _server = new TextBox { Header = Words.SigninServer() };
        AutomationProperties.SetAutomationId(_server, "recover-server");
        _email = new TextBox { Header = Words.RecoveryEmail() };
        AutomationProperties.SetAutomationId(_email, "recover-email");
        _send = WayInPage.Button(Words.RecoverySend(), "recover-send", accent: true);
        _send.Click += (_, _) => Act.Send(new Call.SessionRequestRecovery { server = _server.Text.Trim(), email = _email.Text.Trim() });
        _back = WayInPage.Button(Words.Back(), "recover-back");
        _back.Click += (_, _) => Act.Send(new Call.SessionBackToSignIn());
        var sentLine = WayInPage.Text(Words.RecoverySent(), "recover-sent");
        sentLine.Foreground = (Brush)Application.Current.Resources["SystemFillColorSuccessBrush"];
        _sent.Children.Add(sentLine);
        _sent.Children.Add(WayInPage.Text(Words.RecoveryOnWeb()));
        AutomationProperties.SetAutomationId(_working, "recover-working");
        Page = WayInPage.Column(
            WayInPage.Heading(Words.RecoveryTitle(), "recover-title"), WayInPage.Text(Words.RecoveryIntro()),
            _server, _email, _sent, _error, _entries, _working, WayInPage.Text(Words.RecoveryNoEscrow(), secondary: true));
    }

    /// <summary>The core's entries for <paramref name="sent"/> (SignIn.RecoverPlan), drawn once answered; the latest ask wins.</summary>
    private async void Plan(bool sent)
    {
        if (_planned == sent) return;
        _planned = sent;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.SignInRecoverPlan { sent = sent });
            if (_planned != sent) return;
            if (answer is not Answer.RecoverPlan plan)
            {
                Log.Write($"SignIn.RecoverPlan answered {answer.GetType().Name}");
                return;
            }
            _entries.Children.Clear();
            foreach (var e in plan.entries) _entries.Children.Add(Entry(e));
        }
        catch (Exception e)
        {
            Log.Write($"SignIn.RecoverPlan failed: {e.GetType().Name}");
        }
    }

    private Button Entry(RecoverEntry e) => e switch
    {
        RecoverEntry.Send => _send,
        RecoverEntry.Back => _back,
    };

    /// <summary>Draws <paramref name="gate"/>: the server and the address it came with (once per arrival), sent or not,
    /// working or not, the core's error.</summary>
    public void Show(Gate.Recover gate)
    {
        var arrived = (gate.server, gate.email);
        if (!gate.working && !gate.sent && gate.error is null && _shown != arrived)
        {
            _server.Text = gate.server ?? "";
            _email.Text = gate.email ?? "";
        }
        _shown = arrived;
        Plan(gate.sent);
        _server.IsEnabled = !gate.sent && !gate.working;
        _email.IsEnabled = !gate.sent && !gate.working;
        _send.IsEnabled = !gate.working;
        _back.IsEnabled = !gate.working;
        _sent.Visibility = gate.sent ? Visibility.Visible : Visibility.Collapsed;
        _working.IsActive = gate.working;
        _working.Visibility = gate.working ? Visibility.Visible : Visibility.Collapsed;
        WayInPage.ShowError(_error, gate.error);
    }
}
