// Finishing a password recovery from its mailed link (core's RecoveryFinish, the web's RecoverView;
// uc.account.recover-access): the link pasted into *Open a link* answers RecoveryOpened (Received.Open), and its finish
// stands above everything as a join does (MainWindow), drawing Watched.Recovery — the link checked or why not (LOADING,
// SPENT), the Secret Key (KEY: the flows' FlowKeyControl — typed, a pasted kit token, a kit file, a scan), a new password
// with its breached-password check (PASSWORD: NewPasswordField over Recovery.Check / Edited / UseAnyway), and DONE with
// *Sign in* (Recovery.SignIn: the finish closes and the sign-in opens filled with the account; the session's gate shows it
// behind this screen once it is let go). The form is built once and each update sets what shows. ✕ closes the finish
// (Recovery.Close), which zeroes what it held. Android's RecoveryScreen.kt, Apple's RecoveryView.swift, Linux's
// account_flows::recovery.
//
// Secrets on screen (src/mobile/CLAUDE.md): the Secret Key and the new password are typed here (PasswordBox's peek shows
// them), so the screen is guarded as the sign-in is (ScreenSecrets.Guard); every secret box is emptied once the core took
// it, and none is logged.

namespace Veyq.Core;

using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal sealed class RecoveryView : IDisposable
{
    /// <summary>How a finish becomes the window's face: set once by the window (MainWindow).</summary>
    public static Action<long>? Presenter { get; set; }

    /// <summary>Shows finish <paramref name="flow"/> as its own screen (<see cref="Presenter"/>).</summary>
    public static void Present(long flow)
    {
        if (Presenter is { } present) present(flow);
        else
        {
            Log.Write("a recovery opened with no screen to show it");
            Act.Send(new Call.RecoveryClose { flow = flow });
        }
    }

    private readonly long _flow;
    private readonly Action _left;
    private readonly Watch? _watch;
    private RecoveryFinishState? _state;
    private bool _closed;

    private readonly TextBlock _title = new() { Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"], VerticalAlignment = VerticalAlignment.Center };
    private readonly StackPanel _loadingRow = new() { Orientation = Orientation.Horizontal, Spacing = 12 };
    private readonly TextBlock _forAccount = Text("recovery-for-account");
    private readonly TextBlock _keyIntro = Text("recovery-key-intro", secondary: true);
    private readonly FlowKeyControl _key;
    private readonly TextBlock _keyHint = Text("recovery-key-hint", secondary: true);
    private readonly NewPasswordField _next;
    private readonly PasswordBox _confirm = FlowPage.Secret(Words.RecoveryConfirmPassword(), "recovery-confirm");
    private readonly TextBlock _deviceNote = Text("recovery-device-note", secondary: true);
    private readonly TextBlock _done = Text("recovery-done");
    private readonly TextBlock _error = Text("recovery-error");
    private readonly Button _continue = Accent(Words.JoinContinue(), "recovery-continue");
    private readonly Button _submit = Accent(Words.RecoverySubmit(), "recovery-submit");
    private readonly Button _signIn = Accent(Words.SigninSubmit(), "recovery-sign-in");
    private readonly ProgressBar _busy = new() { IsIndeterminate = true, Visibility = Visibility.Collapsed };
    private readonly Button _close = new() { Content = new FontIcon { Glyph = "", FontSize = 14 } };

    /// <summary>The window's face while the finish stands.</summary>
    public UIElement Page { get; }

    /// <summary>Finish <paramref name="flow"/> over <paramref name="window"/>; <paramref name="left"/> once it is let go
    /// (its ✕, its *Sign in*, or the core closed it).</summary>
    public RecoveryView(Window window, long flow, Action left)
    {
        _flow = flow;
        _left = left;
        _key = new FlowKeyControl(window, () => _closed ? null : _flow, Words.SigninSecretKey(), "recovery-secret-key", "recovery") { Changed = Enable };
        _next = new NewPasswordField(() => _closed ? null : _flow, "recovery-new",
            (f, typed) => new Call.RecoveryCheck { flow = f, newPassword = typed }, f => new Call.RecoveryEdited { flow = f },
            (f, on) => new Call.RecoveryUseAnyway { flow = f, on = on });
        foreach (var box in new[] { _next.Box, _confirm })
        {
            box.MaxWidth = double.PositiveInfinity;
            box.HorizontalAlignment = HorizontalAlignment.Stretch;
            box.PasswordChanged += (_, _) => Enable();
        }
        _keyIntro.Text = Words.RecoveryStepKeyIntro();
        _keyHint.Text = Words.RecoverySecretKeyHint();
        _deviceNote.Text = Words.RecoveryDeviceNote();
        _done.Text = Words.RecoveryDone();
        _done.Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"];
        _error.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        AutomationProperties.SetLiveSetting(_error, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
        AutomationProperties.SetAutomationId(_busy, "recovery-busy");
        AutomationProperties.SetName(_close, Words.EntryCancel());
        AutomationProperties.SetAutomationId(_close, "recovery-close");
        ToolTipService.SetToolTip(_close, Words.EntryCancel());
        AutomationProperties.SetHeadingLevel(_title, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level1);
        AutomationProperties.SetAutomationId(_title, "recovery-title");
        var loading = new TextBlock { Text = Words.RecoveryChecking(), VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetAutomationId(loading, "recovery-loading");
        _loadingRow.Children.Add(new ProgressRing { IsActive = true, Width = 24, Height = 24 });
        _loadingRow.Children.Add(loading);

        _continue.Click += (_, _) =>
        {
            if (_closed) return;
            var key = _key.Take();
            Act.Send(new Call.RecoveryUnlock { flow = _flow, secretKey = key });
        };
        // the boxes stay: a breached new password stops here until *Use it anyway*, the form as typed
        _submit.Click += (_, _) =>
        {
            if (!_closed) Act.Send(new Call.RecoveryFinish { flow = _flow, newPassword = _next.Box.Password, confirmPassword = _confirm.Password });
        };
        _signIn.Click += async (_, _) =>
        {
            if (_closed) return;
            var (answer, _) = await Core.CallAsync(new Call.RecoverySignIn { flow = _flow });
            Log.Write($"Recovery.SignIn: {answer.GetType().Name}");
            Leave(sendClose: false);
        };
        _close.Click += (_, _) => Leave(sendClose: true);

        var head = new Grid { ColumnSpacing = 12 };
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.Children.Add(_title);
        Grid.SetColumn(_close, 1);
        head.Children.Add(_close);
        var column = new StackPanel { Spacing = 12, MaxWidth = 560, Padding = new Thickness(24), HorizontalAlignment = HorizontalAlignment.Stretch };
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var b in new UIElement[] { _continue, _submit, _signIn }) buttons.Children.Add(b);
        var noEscrow = Text("recovery-no-escrow", secondary: true);
        noEscrow.Text = Words.RecoveryNoEscrow();
        noEscrow.Visibility = Visibility.Visible;
        foreach (var c in new UIElement[]
                 {
                     head, _loadingRow, _forAccount, _keyIntro, _key.View, _keyHint, _next.View, _confirm, _deviceNote, _done, _error, buttons, _busy, noEscrow,
                 })
        {
            column.Children.Add(c);
        }
        var stretch = new Grid();
        stretch.Children.Add(column);
        var scroll = new ScrollViewer { Content = stretch, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
        AutomationProperties.SetAutomationId(scroll, "recovery");
        // the Secret Key and the new password are typed here: out of capture while it shows
        Page = ScreenSecrets.Guard(scroll);
        Draw(null, null, null);
        _watch = Core.Watch(new Watched.Recovery { flow = flow }, update =>
        {
            if (_closed || update is not Update.Recovery { shown: var s } u) return;
            try
            {
                if (s is null)
                {
                    Log.Write("recovery: closed by the core");
                    Leave(sendClose: false);
                    return;
                }
                Draw(s, u.kit, u.breach);
            }
            catch (Exception e)
            {
                Log.Write($"the recovery did not draw: {e.GetType().Name} 0x{e.HResult:X8}");
            }
        }, e => Log.Write($"a recovery update: {e.Message}"));
        if (_watch is null) Log.Write("recovery: the core refused to show it");
    }

    private void Enable()
    {
        var working = _state is { working: true };
        _continue.IsEnabled = !working && _key.Ready;
        _submit.IsEnabled = !working && _next.Box.Password.Length > 0 && _confirm.Password.Length > 0;
    }

    private void Draw(RecoveryFinishState? s, IntakeState? kit, BreachState? breach)
    {
        var before = _state?.phase;
        _state = s;
        var phase = s?.phase ?? RecoveryPhase.Loading;
        if (s is not null && before != phase) Log.Write($"recovery: {phase}, working {s.working}, refusal {s.refusal ?? "none"}");
        _title.Text = s is null ? Words.RecoveryTitle() : Said.Key(s.title);
        Show(_loadingRow, phase == RecoveryPhase.Loading);
        var asks = phase is RecoveryPhase.Key or RecoveryPhase.Password;
        _forAccount.Text = s?.forAccount is { } line ? Said.Notice(line) : "";
        Show(_forAccount, asks && s?.forAccount is not null);
        foreach (var e in new UIElement[] { _keyIntro, _key.View, _keyHint, _continue }) Show(e, phase == RecoveryPhase.Key);
        foreach (var e in new UIElement[] { _next.View, _confirm, _deviceNote, _submit }) Show(e, phase == RecoveryPhase.Password);
        Show(_done, phase == RecoveryPhase.Done);
        Show(_signIn, phase == RecoveryPhase.Done);
        Show(_busy, s is { working: true });
        _error.Text = s?.refusal is { } r ? Said.Key(r) : "";
        Show(_error, s?.refusal is not null);
        _key.Draw(kit);
        _next.Draw(breach);
        if (phase == RecoveryPhase.Done)
        {
            // the core took them: the boxes emptied
            _next.Forget();
            _confirm.Password = "";
        }
        Enable();
    }

    private void Leave(bool sendClose)
    {
        if (_closed) return;
        _closed = true;
        _watch?.Dispose();
        _next.Forget();
        _confirm.Password = "";
        _key.Take();
        if (sendClose) Act.Send(new Call.RecoveryClose { flow = _flow });
        Log.Write("recovery: closed");
        _left();
    }

    public void Dispose()
    {
        if (_closed) return;
        _closed = true;
        _watch?.Dispose();
        _next.Forget();
        _confirm.Password = "";
        _key.Take();
        Act.Send(new Call.RecoveryClose { flow = _flow });
    }

    private static void Show(UIElement e, bool on) => e.Visibility = on ? Visibility.Visible : Visibility.Collapsed;

    private static TextBlock Text(string id, bool secondary = false)
    {
        var t = new TextBlock { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
        if (secondary) t.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        AutomationProperties.SetAutomationId(t, id);
        return t;
    }

    private static Button Accent(string words, string id)
    {
        var b = new Button { Content = words, Style = (Style)Application.Current.Resources["AccentButtonStyle"], Visibility = Visibility.Collapsed };
        AutomationProperties.SetAutomationId(b, id);
        return b;
    }
}
