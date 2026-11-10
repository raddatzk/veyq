// The way in (ADR 0123's Consequences, the Windows app): what the core's SessionModel.Gate says at SignIn — the
// full sign-in with the Secret Key, every start until device trust comes — drawn as Windows draws a form: a centred
// column of headed fields and one accent button. The person's input goes to the core as one call; the core decides
// what it means and answers with the next gate. Linux's sign_in.rs, in Fluent.
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge.Wire;
using Windows.System;

namespace Veyq;

internal sealed class SignInView
{
    private readonly TextBox _server;
    private readonly TextBox _email;
    private readonly PasswordBox _password;
    private readonly PasswordBox _secretKey;
    private readonly Button _submit;
    private readonly ProgressRing _working;
    private readonly TextBlock _error;

    public UIElement Page { get; }

    public SignInView()
    {
        _server = Field(new TextBox { Header = Words.SigninServer(), InputScope = Scope(InputScopeNameValue.Url) }, "signin-server");
        _email = Field(new TextBox { Header = Words.SigninEmail(), InputScope = Scope(InputScopeNameValue.EmailSmtpAddress) }, "signin-email");
        _password = Field(new PasswordBox { Header = Words.SigninPassword() }, "signin-password");
        _secretKey = Field(new PasswordBox { Header = Words.SigninSecretKey() }, "signin-secret-key");
        _error = new TextBlock { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
        _error.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        AutomationProperties.SetAutomationId(_error, "signin-error");
        AutomationProperties.SetLiveSetting(_error, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
        _submit = new Button
        {
            Content = Words.SigninSubmit(),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Style = (Style)Application.Current.Resources["AccentButtonStyle"],
        };
        AutomationProperties.SetAutomationId(_submit, "signin-submit");
        _submit.Click += (_, _) => Ask();
        // Enter in the last field signs in, as a form's default button does
        _secretKey.KeyDown += (_, e) =>
        {
            if (e.Key != VirtualKey.Enter) return;
            e.Handled = true;
            Ask();
        };
        _working = new ProgressRing { IsActive = false, Visibility = Visibility.Collapsed, HorizontalAlignment = HorizontalAlignment.Center };
        AutomationProperties.SetAutomationId(_working, "signin-working");

        var heading = new TextBlock { Text = Words.SigninTitle(), Style = (Style)Application.Current.Resources["TitleTextBlockStyle"] };
        AutomationProperties.SetAutomationId(heading, "signin-title");
        AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level1);
        var column = new StackPanel { Spacing = 16, MaxWidth = 420, Padding = new Thickness(24), VerticalAlignment = VerticalAlignment.Center };
        foreach (var child in new UIElement[] { heading, _server, _email, _password, _secretKey, _error, _submit, _working }) column.Children.Add(child);
        // a width-capped column straight in a ScrollViewer sat off centre once the window was resized (measured): the
        // ScrollViewer's child stretches, the column centres in it
        var stretch = new Grid();
        stretch.Children.Add(column);
        Page = new ScrollViewer { Content = stretch, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
    }

    private static T Field<T>(T field, string id) where T : FrameworkElement
    {
        AutomationProperties.SetAutomationId(field, id);
        return field;
    }

    private static InputScope Scope(InputScopeNameValue name)
    {
        var scope = new InputScope();
        scope.Names.Add(new InputScopeName(name));
        return scope;
    }

    private void Ask() => Act.Send(new Call.SessionSignIn
    {
        server = _server.Text.Trim(),
        email = _email.Text.Trim(),
        password = _password.Password,
        secretKey = _secretKey.Password.Trim(),
    });

    /// <summary>Draws <paramref name="gate"/>: the server and the email it knows, working or not, the core's error.</summary>
    public void Show(Gate.SignIn gate)
    {
        if (gate.server is { } server && _server.Text.Length == 0) _server.Text = server;
        if (gate.email is { } email && _email.Text.Length == 0) _email.Text = email;
        _submit.IsEnabled = !gate.working;
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
