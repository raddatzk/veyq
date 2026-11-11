// An account this device trusts (ADR 0123 decision 8; SessionModel.Gate.Unlock; ADR 0123's
// Consequences, the device trust), drawn as Windows' own sign-in screen draws a known account: its picture and name, the password where it opens
// with one (a password-trusted account), one accent button, and the way to another account as a link under it. For a
// remembered account the button asks the core again, which raises Windows Hello's prompt itself ("" crosses, nothing
// typed); without Hello (LOGIN) it says the resume failed and tries again (TrustWords.Unlock). Linux's sign_in::Unlock.
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge.Wire;
using Windows.System;

namespace Veyq;

internal sealed class UnlockView
{
    private readonly PersonPicture _picture;
    private readonly TextBlock _email;
    private readonly TextBlock _server;
    private readonly TextBlock _hint;
    private readonly PasswordBox _password;
    private readonly Button _submit;
    private readonly HyperlinkButton _other;
    private readonly ProgressRing _working;
    private readonly TextBlock _error;

    public UIElement Page { get; }

    public UnlockView()
    {
        var heading = new TextBlock { Text = Words.UnlockTitle(), Style = (Style)Application.Current.Resources["TitleTextBlockStyle"], HorizontalAlignment = HorizontalAlignment.Center };
        AutomationProperties.SetAutomationId(heading, "unlock-title");
        AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level1);
        _picture = new PersonPicture { Width = 96, Height = 96, HorizontalAlignment = HorizontalAlignment.Center };
        _email = new TextBlock { Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"], HorizontalAlignment = HorizontalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis };
        AutomationProperties.SetAutomationId(_email, "unlock-account");
        _server = new TextBlock
        {
            Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
            Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
            HorizontalAlignment = HorizontalAlignment.Center,
            TextTrimming = TextTrimming.CharacterEllipsis,
        };
        AutomationProperties.SetAutomationId(_server, "unlock-server");
        _hint = new TextBlock { TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
        AutomationProperties.SetAutomationId(_hint, "unlock-hint");
        _password = new PasswordBox { Header = Words.SigninPassword() };
        AutomationProperties.SetAutomationId(_password, "unlock-password");
        _password.KeyDown += (_, e) =>
        {
            if (e.Key != VirtualKey.Enter) return;
            e.Handled = true;
            Unlock();
        };
        _error = new TextBlock { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
        _error.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        AutomationProperties.SetAutomationId(_error, "unlock-error");
        AutomationProperties.SetLiveSetting(_error, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
        _submit = new Button { HorizontalAlignment = HorizontalAlignment.Stretch, Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetAutomationId(_submit, "unlock-submit");
        _submit.Click += (_, _) => Unlock();
        _other = new HyperlinkButton { Content = Words.UnlockOther(), HorizontalAlignment = HorizontalAlignment.Center };
        AutomationProperties.SetAutomationId(_other, "unlock-other");
        _other.Click += (_, _) => Act.Send(new Call.SessionOtherAccount());
        _working = new ProgressRing { IsActive = false, Visibility = Visibility.Collapsed, HorizontalAlignment = HorizontalAlignment.Center };
        AutomationProperties.SetAutomationId(_working, "unlock-working");

        var column = new StackPanel { Spacing = 16, MaxWidth = 420, Padding = new Thickness(24), VerticalAlignment = VerticalAlignment.Center };
        var account = new StackPanel { Spacing = 4 };
        account.Children.Add(_picture);
        account.Children.Add(_email);
        account.Children.Add(_server);
        foreach (var child in new UIElement[] { heading, account, _hint, _password, _error, _submit, _other, _working }) column.Children.Add(child);
        // the sign-in's layout: the capped column centres in a stretching Grid (a trap of src/mobile/CLAUDE.md)
        var stretch = new Grid();
        stretch.Children.Add(column);
        Page = new ScrollViewer { Content = stretch, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
    }

    private void Unlock()
    {
        // "" for a remembered account, whose field is hidden: the core asks the store itself
        var typed = _password.Visibility == Visibility.Visible ? _password.Password : "";
        _password.Password = "";
        Act.Send(new Call.SessionUnlock { password = typed });
    }

    /// <summary>Draws <paramref name="gate"/>: whose account, the password where it opens with one, what opens it
    /// (the record's level), working or not, the core's error.</summary>
    public void Show(Gate.Unlock gate)
    {
        var record = gate.record;
        _email.Text = record.email;
        _server.Text = record.server;
        _picture.DisplayName = record.email;
        var (hint, button) = TrustWords.Unlock(record.method, record.level);
        _hint.Text = hint;
        _submit.Content = button;
        _password.Visibility = record.method == Method.Device ? Visibility.Visible : Visibility.Collapsed;
        _submit.IsEnabled = !gate.working;
        _other.IsEnabled = !gate.working;
        _password.IsEnabled = !gate.working;
        _working.IsActive = gate.working;
        _working.Visibility = gate.working ? Visibility.Visible : Visibility.Collapsed;
        if (gate.error is { } key)
        {
            _error.Text = Said.Key(key);
            _error.Visibility = Visibility.Visible;
        }
        else
        {
            _error.Visibility = Visibility.Collapsed;
        }
    }
}
