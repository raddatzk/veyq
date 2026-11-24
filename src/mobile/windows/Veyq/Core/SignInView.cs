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
//
// The ways in from another device (ADR 0059 §1): "Sign in with a QR code" and, only where there is a camera, "Scan a
// code from another device" — each a ceremony of DeviceLinkDialog over the server the form names (nothing a scan reads
// changes it). *Open a link* (ADR 0150 decision 4): a received link opened as its guest, no sign-in needed (ReceivedView).
//
// Which of these entries the form offers and in which order is the core's (SignIn.Plan, ADR 0154 decision 2, owner
// 2026-11-20: the web's — Sign in first, Cancel while adding an account, *I don't have my Secret Key with me*, the device
// links, the kit, *Forgot your password?*, Open a link last), asked again when adding or the camera changes; every entry
// is drawn, in its order, under the fields. The locked sign-in and the password reset are pages of their own
// (SignInEntriesViews.cs, ADR 0154 P9).

namespace Veyq.Core;

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
    /// <summary>The two device-linking entries; the scan only where there is a camera.</summary>
    private readonly Button _linkScan;
    private readonly Button _linkShow;
    /// <summary>*Open a link*: a link someone sent, opened as its guest without signing in (ReceivedView).</summary>
    private readonly Button _openLink;
    /// <summary>*I don't have my Secret Key with me* (uc.account.sessions E3) and *Forgot your password?* (uc.account.recover-access).</summary>
    private readonly Button _locked;
    private readonly Button _forgot;
    /// <summary>What the way here left to say that is no error, and the locked sign-in's way back keeping nothing.</summary>
    private readonly TextBlock _notice;
    private readonly TextBlock _keepsNothing;
    /// <summary>The kit's server replaced a different one the form held: said, never refused.</summary>
    private readonly TextBlock _otherServer;
    /// <summary>The Secret Key's typo, said as it is typed (<see cref="SecretKeyTypo"/>).</summary>
    private readonly TextBlock _typo;
    /// <summary>The kit the form last showed, so a new one fills the email and the server once.</summary>
    private KitShown? _shownKit;
    /// <summary>The entries under the fields, the core's plan drawn whole (Entry).</summary>
    private readonly StackPanel _entries = new() { Spacing = 16 };
    private bool _adding;
    private bool _camera;
    private (bool Adding, bool Camera)? _planned;

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
        _openKit = Field(new Button { Content = Words.SigninKitOpen(), HorizontalAlignment = HorizontalAlignment.Stretch }, "signin-kit-open");
        _openKit.Click += (_, _) => PickKit();
        _scanKit = Field(new Button { Content = Words.SigninKitScan(), HorizontalAlignment = HorizontalAlignment.Stretch }, "signin-kit-scan");
        _scanKit.Click += (_, _) => ScanKit();
        _linkScan = Field(new Button { Content = Words.SigninLinkScan(), HorizontalAlignment = HorizontalAlignment.Stretch }, "signin-link-scan");
        _linkScan.Click += (_, _) => Link(server => new Call.LinkScanFromTrusted { server = server });
        _linkShow = Field(new Button { Content = Words.SigninLinkShow(), HorizontalAlignment = HorizontalAlignment.Stretch }, "signin-link-show");
        _linkShow.Click += (_, _) => Link(server => new Call.LinkShowForSignIn { server = server });
        // *Open a link* under the sign-in (ADR 0150 decision 4): a received link needs no session
        var openLink = Field(new Button { Content = Words.LinkOpen(), HorizontalAlignment = HorizontalAlignment.Stretch }, "open-link");
        openLink.Click += (_, _) => ReceivedView.OpenLink(_window);
        _openLink = openLink;
        // the locked sign-in: the login alone, with the email and the password typed (the core refuses blanks)
        _locked = Field(new Button { Content = Words.SigninLocked(), HorizontalAlignment = HorizontalAlignment.Stretch }, "signin-locked");
        _locked.Click += (_, _) => Act.Send(new Call.SessionSignInLocked { server = _server.Text.Trim(), email = _email.Text.Trim(), password = _password.Password });
        _forgot = Field(new Button { Content = Words.SigninForgot(), HorizontalAlignment = HorizontalAlignment.Stretch }, "signin-forgot");
        _forgot.Click += (_, _) => Act.Send(new Call.SessionForgotPassword { server = _server.Text.Trim(), email = _email.Text.Trim() });
        _notice = Field(new TextBlock { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed }, "signin-notice");
        _notice.Foreground = (Brush)Application.Current.Resources["SystemFillColorSuccessBrush"];
        _keepsNothing = Field(new TextBlock { Text = Words.LockedBackHint(), TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed }, "signin-keeps-nothing");
        _keepsNothing.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        _otherServer = Field(new TextBlock { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed }, "signin-kit-other-server");
        _otherServer.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        _typo = SecretKeyTypo.Watch(_secretKey, "signin-secret-key-typo");
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
        _cancel = new Button { Content = Words.SigninCancel(), HorizontalAlignment = HorizontalAlignment.Stretch };
        AutomationProperties.SetAutomationId(_cancel, "signin-cancel");
        _cancel.Click += (_, _) => Act.Send(new Call.SessionCancelAdd());
        _working = new ProgressRing { IsActive = false, Visibility = Visibility.Collapsed, HorizontalAlignment = HorizontalAlignment.Center };
        AutomationProperties.SetAutomationId(_working, "signin-working");

        _heading = new TextBlock { Text = Words.SigninTitle(), Style = (Style)Application.Current.Resources["TitleTextBlockStyle"] };
        AutomationProperties.SetAutomationId(_heading, "signin-title");
        AutomationProperties.SetHeadingLevel(_heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level1);
        var column = new StackPanel { Spacing = 16, MaxWidth = 420, Padding = new Thickness(24), VerticalAlignment = VerticalAlignment.Center };
        foreach (var child in new UIElement[] { _heading, _server, _email, _password, _secretKey, _typo, _kitHeld, _otherServer, _keepsNothing, _error, _notice, _entries, _working }) column.Children.Add(child);
        // a width-capped column straight in a ScrollViewer sat off centre once the window was resized (measured): the
        // ScrollViewer's child stretches, the column centres in it
        var stretch = new Grid();
        stretch.Children.Add(column);
        // the Secret Key and the password (PasswordBox's peek shows them) and the kit scanner's camera: out of capture
        // the tray over the app's queue alone at the top right: a link guest's uploads left running signed out (inbox #1250)
        Page = Uploads.WithTray(window, ScreenSecrets.Guard(new ScrollViewer { Content = stretch, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled }));
        Plan();
        OfferScan();
    }

    /// <summary>The entries under the fields as the core plans them for adding or not and a camera or not (SignIn.Plan):
    /// asked when either changed, each entry drawn in its order; the latest ask's answer is drawn.</summary>
    private async void Plan()
    {
        (bool Adding, bool Camera) asked = (_adding, _camera);
        if (_planned == asked) return;
        _planned = asked;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.SignInPlan { adding = asked.Adding, camera = asked.Camera });
            if (_planned != asked) return;
            if (answer is not Answer.SignInPlan plan)
            {
                Log.Write($"SignIn.Plan answered {Describe(answer)}");
                return;
            }
            _entries.Children.Clear();
            foreach (var e in plan.entries) _entries.Children.Add(Entry(e));
            Log.Write($"sign-in entries: {string.Join(",", plan.entries)}");
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"SignIn.Plan failed: {e.GetType().Name}");
        }
    }

    /// <summary>A plan entry's control: Sign in the accent button, every other a button of its own.</summary>
    private Button Entry(SignInEntry e) => e switch
    {
        SignInEntry.Submit => _submit,
        SignInEntry.Cancel => _cancel,
        SignInEntry.LinkShow => _linkShow,
        SignInEntry.LinkScan => _linkScan,
        SignInEntry.KitOpen => _openKit,
        SignInEntry.KitScan => _scanKit,
        SignInEntry.Locked => _locked,
        SignInEntry.Forgot => _forgot,
        SignInEntry.OpenLink => _openLink,
    };

    /// <summary>Whether this device has a camera, for the plan's scans (the kit CR's D7: no camera, no entry).</summary>
    private async void OfferScan()
    {
        try
        {
            var present = await QrCamera.PresentAsync();
            Log.Write(present ? "the sign-in's kit and link scans offered" : "the sign-in's kit and link scans not offered: no camera");
            _camera = present;
            Plan();
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"the camera's check failed: {e.GetType().Name}");
        }
    }

    /// <summary>A device link from the sign-in over the server the form names (Link.ScanFromTrusted, Link.ShowForSignIn).</summary>
    private async void Link(Func<string, Call> make)
    {
        try
        {
            await DeviceLinkDialog.Show(_window, make(_server.Text.Trim()));
        }
        catch (Exception e)
        {
            Log.Write($"veyq-link: the ceremony failed: {e.GetType().Name}");
        }
    }

    /// <summary>What the open picker offers for a kit: a PDF or a picture (the account flows' kit control too).</summary>
    internal static readonly string[] KitFileTypes = [".pdf", ".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif", ".avif", ".bmp", ".tif", ".tiff"];

    /// <summary>"Open Emergency Kit": the open picker for a PDF or a picture; the core reads the file at the path picked.</summary>
    private async void PickKit()
    {
        try
        {
            var picker = new Windows.Storage.Pickers.FileOpenPicker { SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.DocumentsLibrary };
            foreach (var ext in KitFileTypes) picker.FileTypeFilter.Add(ext);
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
    internal static UIElement? ScanSaid(ScanShown shown) => shown.state is ScanState.NoCamera or ScanState.Denied
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
        _linkScan.IsEnabled = !gate.working;
        _linkShow.IsEnabled = !gate.working;
        _locked.IsEnabled = !gate.working;
        _forgot.IsEnabled = !gate.working;
        _keepsNothing.Visibility = gate.keepsNothing ? Visibility.Visible : Visibility.Collapsed;
        if (gate.notice is { } notice)
        {
            _notice.Text = Said.Key(notice);
            _notice.Visibility = Visibility.Visible;
        }
        else
        {
            _notice.Visibility = Visibility.Collapsed;
        }
        _heading.Text = gate.adding ? Words.SigninAddTitle() : Words.SigninTitle();
        _adding = gate.adding;
        Plan();
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

    /// <summary>The password and the Secret Key typed, forgotten once the core has left the sign-in (signed in, unlocking):
    /// the view is built once per window, so without this a sign-out or "Use another account" showed both filled again, a
    /// press of Sign in away from the account (measured on the VM, 2026-11-13; Linux's <c>SignIn::forget</c>).</summary>
    public void Forget()
    {
        _password.Password = "";
        _secretKey.Password = "";
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

/// <summary>
/// A typo said under a Secret Key box as it is typed (SecretKey.Check, the core's <c>KeyIntake.problem</c>; inbox #1058,
/// the web's SecretKeyField): shown for a <c>TYPO</c>, collapsed for a key, an empty box or a <c>MALFORMED</c> one, which
/// says nothing until the action refuses it. Each edit asks anew; an answer to an older edit is dropped. Nothing of the
/// key is logged.
/// </summary>
internal static class SecretKeyTypo
{
    /// <summary>The line under <paramref name="box"/>, watching it from here on.</summary>
    public static TextBlock Watch(PasswordBox box, string id)
    {
        var line = new TextBlock { Text = Words.SecretKeyTypo(), TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
        line.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        AutomationProperties.SetAutomationId(line, id);
        long asked = 0;
        box.PasswordChanged += async (_, _) =>
        {
            var n = ++asked;
            try
            {
                var (answer, _) = await Core.CallAsync(new Call.SecretKeyCheck { secretKey = box.Password });
                if (n != asked) return;
                var typo = answer is Answer.SecretKeyChecked { problem: SecretKeyProblem.Typo };
                line.Visibility = typo ? Visibility.Visible : Visibility.Collapsed;
            }
            catch (Exception e)
            {
                // an exception out of an async void would end the app in combase, with nothing in the log
                Log.Write($"SecretKey.Check failed: {e.GetType().Name}");
            }
        };
        return line;
    }
}
