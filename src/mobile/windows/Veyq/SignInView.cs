// The way in (ADR 0123's Consequences, the Windows app): what the core's SessionModel.Gate says at SignIn — the
// full sign-in with the Secret Key — drawn as Windows draws a form: a centred column of headed fields and one accent
// button. The person's input goes to the core as one call; the core decides what it means and answers with the next
// gate. Linux's sign_in.rs, in Fluent.
//
// No "Stay signed in" here any more (ADR 0123 decision 8): every sign-in holds the password
// trust, and the core offers staying signed in after it (Gate.Signed.offer, OfferView).
//
// The sign-in also takes the Emergency Kit (en.account.key-model § The Emergency Kit): "Open Emergency Kit" under the
// Secret Key field picks the file (the open picker, PDFs and pictures) and hands the core its path (Session.SignInKit),
// which reads it and asks this side to draw its pages (KitDrawing); "Scan Emergency Kit" beside it, only where there is a
// camera, scans its code (QrCamera, kind KIT). Either way the gate's kit fills the email and the server and the Secret Key
// field says "From your Emergency Kit" — the key itself the core holds, never this side.
using System;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
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
    private readonly TextBlock _heading;
    private readonly Button _cancel;
    private readonly Window _window;
    /// <summary>The Secret Key while a kit's key is held: "From your Emergency Kit", and the way back to typing it.</summary>
    private readonly StackPanel _kitHeld;
    private readonly Button _openKit;
    private readonly Button _scanKit;
    /// <summary>The kit's server replaced a different one the form held: said, never refused.</summary>
    private readonly TextBlock _otherServer;
    /// <summary>The kit the form last showed, so a new one fills the email and the server once.</summary>
    private KitShown? _shownKit;

    public UIElement Page { get; }

    public SignInView(Window window)
    {
        _window = window;
        _server = Field(new TextBox { Header = Words.SigninServer(), InputScope = Scope(InputScopeNameValue.Url) }, "signin-server");
        _email = Field(new TextBox { Header = Words.SigninEmail(), InputScope = Scope(InputScopeNameValue.EmailSmtpAddress) }, "signin-email");
        _password = Field(new PasswordBox { Header = Words.SigninPassword() }, "signin-password");
        _secretKey = Field(new PasswordBox { Header = Words.SigninSecretKey() }, "signin-secret-key");
        var clear = Field(new HyperlinkButton { Content = Words.SigninKitClear(), Padding = new Thickness(0) }, "signin-kit-clear");
        clear.Click += (_, _) => Act.Send(new Call.SessionSignInKitClear());
        _kitHeld = Field(new StackPanel { Spacing = 4, Visibility = Visibility.Collapsed }, "signin-kit-held");
        _kitHeld.Children.Add(new TextBlock { Text = Words.SigninSecretKey() });
        _kitHeld.Children.Add(Field(new TextBlock { Text = Words.SigninKitHeld(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] }, "signin-kit-held-text"));
        _kitHeld.Children.Add(clear);
        // the file first on a desktop (the kit CR's owner question 1); the scan only where there is a camera
        _openKit = Field(new Button { Content = Words.SigninKitOpen(), HorizontalAlignment = HorizontalAlignment.Stretch }, "signin-kit-open");
        _openKit.Click += (_, _) => PickKit();
        _scanKit = Field(new Button { Content = Words.SigninKitScan(), HorizontalAlignment = HorizontalAlignment.Stretch, Visibility = Visibility.Collapsed }, "signin-kit-scan");
        _scanKit.Click += (_, _) => ScanKit();
        _otherServer = Field(new TextBlock { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed }, "signin-kit-other-server");
        _otherServer.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
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
        _cancel = new Button { Content = Words.SigninCancel(), HorizontalAlignment = HorizontalAlignment.Stretch, Visibility = Visibility.Collapsed };
        AutomationProperties.SetAutomationId(_cancel, "signin-cancel");
        _cancel.Click += (_, _) => Act.Send(new Call.SessionCancelAdd());
        _working = new ProgressRing { IsActive = false, Visibility = Visibility.Collapsed, HorizontalAlignment = HorizontalAlignment.Center };
        AutomationProperties.SetAutomationId(_working, "signin-working");

        _heading = new TextBlock { Text = Words.SigninTitle(), Style = (Style)Application.Current.Resources["TitleTextBlockStyle"] };
        AutomationProperties.SetAutomationId(_heading, "signin-title");
        AutomationProperties.SetHeadingLevel(_heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level1);
        var column = new StackPanel { Spacing = 16, MaxWidth = 420, Padding = new Thickness(24), VerticalAlignment = VerticalAlignment.Center };
        foreach (var child in new UIElement[] { _heading, _server, _email, _password, _secretKey, _kitHeld, _openKit, _scanKit, _otherServer, _error, _submit, _cancel, _working }) column.Children.Add(child);
        // a width-capped column straight in a ScrollViewer sat off centre once the window was resized (measured): the
        // ScrollViewer's child stretches, the column centres in it
        var stretch = new Grid();
        stretch.Children.Add(column);
        // the Secret Key and the password (PasswordBox's peek shows them) and the kit scanner's camera: out of capture
        Page = ScreenSecrets.Guard(new ScrollViewer { Content = stretch, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled });
        OfferScan();
    }

    /// <summary>The scan's entry only where there is a camera (the kit CR's D7: no camera, no entry).</summary>
    private async void OfferScan()
    {
        try
        {
            var present = await QrCamera.PresentAsync();
            Log.Write(present ? "the sign-in's kit scan offered" : "the sign-in's kit scan not offered: no camera");
            _scanKit.Visibility = present ? Visibility.Visible : Visibility.Collapsed;
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"the camera's check failed: {e.GetType().Name}");
        }
    }

    /// <summary>"Open Emergency Kit": the open picker for a PDF or a picture; the core reads the file at the path picked.</summary>
    private async void PickKit()
    {
        try
        {
            var picker = new Windows.Storage.Pickers.FileOpenPicker { SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.DocumentsLibrary };
            foreach (var ext in new[] { ".pdf", ".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif", ".avif", ".bmp", ".tif", ".tiff" }) picker.FileTypeFilter.Add(ext);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var file = await picker.PickSingleFileAsync();
            if (file is null || string.IsNullOrEmpty(file.Path)) return;
            // the core opens the path itself
            var (answer, _) = await Core.CallAsync(new Call.SessionSignInKit { path = file.Path, server = _server.Text.Trim() });
            // the outcome alone: never the path or anything of the kit
            Log.Write($"kit picked: {Describe(answer)}");
        }
        catch (Exception e)
        {
            Log.Write($"the kit's pick failed: {e.GetType().Name}: {e.Message}");
        }
    }

    /// <summary>"Scan Emergency Kit": the camera in a dialog over a scan of kind KIT; the kit it finds goes to the sign-in in
    /// the core, and the dialog closes on it.</summary>
    private async void ScanKit()
    {
        try
        {
            var hint = new TextBlock { Text = Words.SigninKitScanHint(), TextWrapping = TextWrapping.Wrap, HorizontalAlignment = HorizontalAlignment.Center };
            var dialog = new ContentDialog { XamlRoot = Page.XamlRoot, Title = Words.SigninKitScan(), CloseButtonText = Words.SigninCancel() };
            var found = false;
            var camera = QrCamera.Open(ScanKind.Kit, _server.Text.Trim(), ScanSaid, shown =>
            {
                Log.Write($"kit scan {shown.state}");
                if (shown.state == ScanState.Found && !found)
                {
                    found = true;
                    dialog.Hide();
                }
            });
            camera.View.Width = 480;
            camera.View.Height = 360;
            var body = new StackPanel { Spacing = 12 };
            body.Children.Add(camera.View);
            body.Children.Add(hint);
            dialog.Content = body;
            try
            {
                await dialog.ShowAsync();
            }
            finally
            {
                // the scan and its camera end with the dialog, however it closes
                camera.Dispose();
            }
        }
        catch (Exception e)
        {
            Log.Write($"the kit's scan failed: {e.GetType().Name}: {e.Message}");
        }
    }

    /// <summary>What the kit's scan shows but its camera: no camera or a refused one says to open the file or type the key
    /// instead (the web's scanError); a found kit closes the dialog.</summary>
    private static UIElement? ScanSaid(ScanShown shown) => shown.state is ScanState.NoCamera or ScanState.Denied
        ? new TextBlock { Text = Words.SigninKitScanDenied(), TextWrapping = TextWrapping.Wrap, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center }
        : null;

    private static string Describe(Answer a) => a is Answer.Refused r ? $"Refused {r.why}" : a.GetType().Name;

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

    /// <summary>The sign-in the form holds, sent; while a kit's key is held the Secret Key is sent empty, so the core signs
    /// in with it.</summary>
    private void Ask() => Act.Send(new Call.SessionSignIn
    {
        server = _server.Text.Trim(),
        email = _email.Text.Trim(),
        password = _password.Password,
        secretKey = _shownKit is null ? _secretKey.Password.Trim() : "",
    });

    /// <summary>Draws <paramref name="gate"/>: the server and the email it knows, a kit's own replacing them once, the Secret
    /// Key as typed or as the kit's, adding a server or not, working or not, the core's error.</summary>
    public void Show(Gate.SignIn gate)
    {
        var newKit = gate.kit is not null && !Equals(_shownKit, gate.kit);
        _shownKit = gate.kit;
        if (gate.server is { } server && (newKit || _server.Text.Length == 0)) _server.Text = server;
        if (gate.email is { } email && (newKit || _email.Text.Length == 0)) _email.Text = email;
        _secretKey.Visibility = gate.kit is null ? Visibility.Visible : Visibility.Collapsed;
        _kitHeld.Visibility = gate.kit is null ? Visibility.Collapsed : Visibility.Visible;
        if (newKit) _secretKey.Password = "";
        if (gate.kit is { server: { } madeAt, typedServer: { } before })
        {
            _otherServer.Text = Words.SigninKitOtherServer(madeAt, before);
            _otherServer.Visibility = Visibility.Visible;
        }
        else
        {
            _otherServer.Visibility = Visibility.Collapsed;
        }
        _openKit.IsEnabled = !gate.working;
        _heading.Text = gate.adding ? Words.SigninAddTitle() : Words.SigninTitle();
        _cancel.Visibility = gate.adding ? Visibility.Visible : Visibility.Collapsed;
        _cancel.IsEnabled = !gate.working;
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

    /// <summary>The debug build's dev principal: the form filled, and signed in through it unless it only fills.</summary>
    public void Dev(DevSignIn dev)
    {
        _server.Text = dev.Server;
        _email.Text = dev.Email;
        _password.Password = dev.Password;
        _secretKey.Password = dev.SecretKey;
        if (!dev.FillOnly) Ask();
    }
}
