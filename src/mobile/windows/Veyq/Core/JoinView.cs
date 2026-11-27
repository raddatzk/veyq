// Joining a space from a received invite (core's TenantJoin, the web's TenantJoinView; ADR 0111, ADR 0129): an invite
// pasted into *Open a link* answers JoinOpened (Received.Open), and its join stands above everything as a received link's
// screen does (MainWindow), drawing the join's phases from Watched.Join — the invite read or why not (LOADING, INVALID,
// EXPIRED, REDEEMED), its code or password (CODE), a new account (REGISTER) with its Emergency Kit saved as every kit is
// (KIT: KitSection over Kit.Save / Kit.Confirm, then Join.Finish), or one the person has (SIGNIN: the password and the
// sign-in's Secret Key box with its typo line) — both, but at a handover, with the optional picture below the name
// (asksPicture, the web's JoinPicturePicker: the app's one square crop, SquareCropDialog.Pick, its preview the core's
// encoded bytes, Join.PictureBytes) —, and DONE with *Open the space* (Join.Open: the app signs in as any sign-in
// does, and the session's gate shows the shell behind this screen once it is let go). The form is built once and each
// update sets what shows and what is enabled, so an update never takes the focus or a typed field; what the core
// refuses stands under the form; busy disables every field and button. ✕ closes the join (Join.Close), which zeroes what
// it held. Android's JoinScreen.kt, Apple's JoinView.swift.
//
// Secrets on screen (src/mobile/CLAUDE.md): the password, the Secret Key and a mailed invite's password are typed here
// (PasswordBox's peek shows them), so the screen is guarded as the sign-in is (ScreenSecrets.Guard); every secret box is
// emptied once the core took it, and none is logged.

namespace Veyq.Core;

using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal sealed class JoinView : IDisposable
{
    /// <summary>How a join becomes the window's face: set once by the window (MainWindow).</summary>
    public static Action<long>? Presenter { get; set; }

    /// <summary>Shows join <paramref name="join"/> as its own screen (<see cref="Presenter"/>).</summary>
    public static void Present(long join)
    {
        if (Presenter is { } present) present(join);
        else
        {
            Log.Write("a join opened with no screen to show it");
            Act.Send(new Call.JoinClose { join = join });
        }
    }

    private const int MinPassword = 10;

    private readonly Window _window;
    private readonly long _join;
    private readonly Action _left;
    private readonly Watch? _watch;
    private TenantJoinState? _state;
    private KitSection? _kit;
    private bool _closed;
    private bool _opening;

    private readonly TextBlock _loading = new() { Text = Words.JoinLoading(), VerticalAlignment = VerticalAlignment.Center };
    private readonly StackPanel _loadingRow = new() { Orientation = Orientation.Horizontal, Spacing = 12 };
    private readonly TextBlock _said = Text("join-said");
    private readonly TextBlock _intro = Text("join-intro");
    private readonly TextBlock _sub = Text("join-sub", secondary: true);
    private readonly TextBox _code = new() { IsSpellCheckEnabled = false };
    private readonly PasswordBox _codeSecret;
    private readonly TextBox _username = new() { Header = Words.JoinUsername(), Description = InviteDialog.Hint(Words.JoinUsernameHint()), IsSpellCheckEnabled = false };
    private readonly TextBox _firstName = new() { Header = Words.ProfileFirstName() };
    private readonly TextBox _lastName = new() { Header = Words.ProfileLastName(), Description = InviteDialog.Hint(Words.JoinNameHint()) };
    private readonly TextBox _email = new() { Header = Words.SigninEmail() };
    private readonly PersonPicture _picture = new() { Width = 72, Height = 72 };
    private readonly Button _pictureChoose = new();
    private readonly Button _pictureRemove = new() { Content = Words.ProfilePictureRemove() };
    private readonly StackPanel _pictureRow = new() { Orientation = Orientation.Horizontal, Spacing = 16 };
    private readonly TextBlock _pictureHint = Text("join-picture-hint", secondary: true);
    private readonly TextBlock _pictureNotImage = Text("join-picture-not-image");
    private bool? _pictureShown;
    private bool _picking;
    private readonly NewPasswordField _newPassword;
    private readonly PasswordBox _password;
    private readonly PasswordBox _secretKey;
    private readonly TextBlock _typo;
    private readonly TextBlock _kitTitle = Text("join-kit-title");
    private readonly TextBlock _kitSub = Text("join-kit-sub", secondary: true);
    private readonly ContentControl _kitSave = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch };
    private readonly TextBlock _done = Text("join-done");
    private readonly TextBlock _confirmation = Text("join-confirmation");
    private readonly TextBlock _doneNote = Text("join-done-note", secondary: true);
    private readonly TextBlock _error = Text("join-error");
    private readonly Button _continue = Accent(Words.JoinContinue(), "join-continue");
    private readonly Button _accept = Accent("", "join-accept");
    private readonly Button _finish = Accent("", "join-finish");
    private readonly Button _open = Accent(Words.JoinOpenSpace(), "join-open-space");
    private readonly HyperlinkButton _switch = new() { Padding = new Thickness(0) };
    private readonly ProgressBar _busy = new() { IsIndeterminate = true, Visibility = Visibility.Collapsed };
    private readonly Button _close = new() { Content = new FontIcon { Glyph = "", FontSize = 14 } };

    /// <summary>The window's face while the join stands.</summary>
    public UIElement Page { get; }

    /// <summary>Join <paramref name="join"/> over <paramref name="window"/>; <paramref name="left"/> once it is let go
    /// (its ✕, the space opened, or the core closed it).</summary>
    public JoinView(Window window, long join, Action left)
    {
        _window = window;
        _join = join;
        _left = left;
        _codeSecret = new PasswordBox();
        AutomationProperties.SetAutomationId(_code, "join-code");
        AutomationProperties.SetAutomationId(_codeSecret, "join-code");
        AutomationProperties.SetAutomationId(_username, "join-username");
        AutomationProperties.SetAutomationId(_firstName, "join-first-name");
        AutomationProperties.SetAutomationId(_lastName, "join-last-name");
        AutomationProperties.SetAutomationId(_email, "join-email");
        AutomationProperties.SetAutomationId(_picture, "join-picture");
        AutomationProperties.SetAutomationId(_pictureChoose, "join-picture-choose");
        AutomationProperties.SetAutomationId(_pictureRemove, "join-picture-remove");
        _pictureHint.Text = Words.ProfilePictureJoinHint();
        _pictureNotImage.Text = Words.ProfilePictureNotImage();
        _pictureNotImage.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        var pictureButtons = new StackPanel { Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
        pictureButtons.Children.Add(_pictureChoose);
        pictureButtons.Children.Add(_pictureRemove);
        _pictureRow.Children.Add(_picture);
        _pictureRow.Children.Add(pictureButtons);
        _pictureChoose.Click += (_, _) => ChoosePicture();
        _pictureRemove.Click += (_, _) =>
        {
            _pictureNotImage.Visibility = Visibility.Collapsed;
            Act.Send(new Call.JoinRemovePicture { join = _join });
        };
        _firstName.TextChanged += (_, _) => Named();
        _lastName.TextChanged += (_, _) => Named();
        var mail = new InputScope();
        mail.Names.Add(new InputScopeName(InputScopeNameValue.EmailSmtpAddress));
        _email.InputScope = mail;
        _newPassword = new NewPasswordField(() => _closed ? null : _join, "join-password",
            (j, typed) => new Call.JoinCheckPassword { join = j, password = typed }, null,
            (j, on) => new Call.JoinUseAnyway { join = j, on = on }, Words.SigninPassword());
        _password = FlowPage.Secret(Words.SigninPassword(), "join-signin-password");
        _secretKey = FlowPage.Secret(Words.SigninSecretKey(), "join-secret-key");
        _typo = SecretKeyTypo.Watch(_secretKey, "join-secret-key-typo");
        // the flows' secret boxes sit capped at the left; here every field fills the form's column, as the sign-in's do
        foreach (var box in new[] { _newPassword.Box, _password, _secretKey })
        {
            box.MaxWidth = double.PositiveInfinity;
            box.HorizontalAlignment = HorizontalAlignment.Stretch;
        }
        _error.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        AutomationProperties.SetLiveSetting(_error, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
        _kitTitle.Text = Words.JoinSaveKitTitle();
        _kitTitle.Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"];
        _kitSub.Text = Words.JoinSaveKitSub();
        _done.Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"];
        AutomationProperties.SetAutomationId(_switch, "join-switch");
        AutomationProperties.SetAutomationId(_busy, "join-busy");
        AutomationProperties.SetName(_close, Words.EntryCancel());
        AutomationProperties.SetAutomationId(_close, "join-close");
        ToolTipService.SetToolTip(_close, Words.EntryCancel());
        _loadingRow.Children.Add(new ProgressRing { IsActive = true, Width = 24, Height = 24 });
        _loadingRow.Children.Add(_loading);
        AutomationProperties.SetAutomationId(_loading, "join-loading");

        _code.TextChanged += (_, _) => Enable();
        _codeSecret.PasswordChanged += (_, _) => Enable();
        _username.TextChanged += (_, _) =>
        {
            Named();
            Enable();
        };
        _email.TextChanged += (_, _) => Enable();
        _newPassword.Box.PasswordChanged += (_, _) => Enable();
        _password.PasswordChanged += (_, _) => Enable();
        _secretKey.PasswordChanged += (_, _) => Enable();
        _continue.Click += (_, _) => Continue();
        _accept.Click += (_, _) => SignIn();
        _finish.Click += (_, _) => Act.Send(new Call.JoinFinish { join = _join });
        _open.Click += (_, _) => OpenSpace();
        _switch.Click += (_, _) =>
        {
            if (_state is { } s) Act.Send(new Call.JoinChoose { join = _join, register = s.phase == Phase.Signin });
        };
        _close.Click += (_, _) => Leave();

        var title = new TextBlock { Text = Words.JoinTitle(), Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"], VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetHeadingLevel(title, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level1);
        AutomationProperties.SetAutomationId(title, "join-title");
        var head = new Grid { ColumnSpacing = 12 };
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.Children.Add(title);
        Grid.SetColumn(_close, 1);
        head.Children.Add(_close);
        var column = new StackPanel { Spacing = 12, MaxWidth = 560, Padding = new Thickness(24), HorizontalAlignment = HorizontalAlignment.Stretch };
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var b in new UIElement[] { _continue, _accept, _finish, _open }) buttons.Children.Add(b);
        foreach (var c in new UIElement[]
                 {
                     head, _loadingRow, _said, _intro, _sub, _code, _codeSecret, _kitTitle, _kitSub, _kitSave, _username, _firstName,
                     _lastName, _pictureRow, _pictureNotImage, _pictureHint, _email, _newPassword.View, _password, _secretKey, _typo, _done, _confirmation, _doneNote, _error, buttons,
                     _switch, _busy,
                 })
        {
            column.Children.Add(c);
        }
        // a capped column inside a stretching grid sits centred (a capped column straight in a ScrollViewer clips)
        var stretch = new Grid();
        stretch.Children.Add(column);
        var scroll = new ScrollViewer { Content = stretch, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
        AutomationProperties.SetAutomationId(scroll, "join");
        // the password, the Secret Key and an invite's password are typed here: out of capture while it shows
        Page = ScreenSecrets.Guard(scroll);
        Draw(null, first: true);
        _watch = Core.Watch(new Watched.Join { join = join }, update =>
        {
            if (_closed || update is not Update.Join { shown: var s }) return;
            try
            {
                if (s is null)
                {
                    Log.Write("join: closed by the core");
                    Leave();
                    return;
                }
                Draw(s, first: false);
            }
            catch (Exception e)
            {
                Log.Write($"the join did not draw: {e.GetType().Name} 0x{e.HResult:X8}");
            }
        }, e => Log.Write($"a join update: {e.Message}"));
        if (_watch is null)
        {
            Log.Write("join: the core refused to show it");
        }
    }

    private void Draw(TenantJoinState? s, bool first)
    {
        var before = _state?.phase;
        _state = s;
        var phase = s?.phase ?? Phase.Loading;
        if (s is not null && before != phase) Log.Write($"join: {phase}{(s.linkInvite ? ", a link invite" : "")}, busy {s.busy}");
        Show(_loadingRow, phase == Phase.Loading);
        var said = phase switch
        {
            Phase.Invalid => Words.JoinInvalid(),
            Phase.Expired => Words.JoinExpired(),
            Phase.Redeemed => Words.JoinRedeemed(),
            _ => null,
        };
        _said.Text = said ?? "";
        _said.Foreground = (Brush)Application.Current.Resources[phase == Phase.Invalid ? "SystemFillColorCriticalBrush" : "TextFillColorPrimaryBrush"];
        Show(_said, said is not null);
        var asks = phase is Phase.Code or Phase.Register or Phase.Signin;
        Show(_intro, asks);
        Show(_sub, asks);
        if (s is not null && asks)
        {
            _intro.Text = Said.Notice(s.intro);
            _sub.Text = phase switch
            {
                Phase.Code => Said.Key(s.codeSub),
                Phase.Register => Said.Notice(s.registerSub),
                _ => Said.Notice(s.signinSub),
            };
        }
        var byCode = s?.codeMode != Mode.Password;
        Show(_code, phase == Phase.Code && byCode);
        Show(_codeSecret, phase == Phase.Code && !byCode);
        if (s is not null)
        {
            _code.Header = Said.Key(s.codeLabel);
            _codeSecret.Header = Said.Key(s.codeLabel);
        }
        if (phase != Phase.Code && before == Phase.Code)
        {
            // the code went inward once: nothing of it stays on screen
            _code.Text = "";
            _codeSecret.Password = "";
        }
        var details = phase is Phase.Register or Phase.Signin;
        Show(_username, details && s!.asksUsername);
        Show(_firstName, details && s!.asksUsername);
        Show(_lastName, details && s!.asksUsername);
        var asksPicture = details && s!.asksPicture;
        Show(_pictureRow, asksPicture);
        Show(_pictureHint, asksPicture);
        if (!asksPicture) _pictureNotImage.Visibility = Visibility.Collapsed;
        if (s is not null && asksPicture)
        {
            _pictureChoose.Content = Said.Key(s.pictureWord);
            Show(_pictureRemove, s.picture);
            if (_pictureShown != s.picture)
            {
                _pictureShown = s.picture;
                Preview(s.picture);
            }
        }
        Show(_email, details && s!.asksEmail);
        Show(_newPassword.View, phase == Phase.Register);
        if (s is not null) _newPassword.Draw(s.breach);
        Show(_password, phase == Phase.Signin);
        Show(_secretKey, phase == Phase.Signin);
        if (phase != Phase.Signin) _typo.Visibility = Visibility.Collapsed;
        if (before is Phase.Register or Phase.Signin && phase is Phase.Kit or Phase.Done)
        {
            // the core took them: none stays in a box
            _newPassword.Forget();
            _password.Password = "";
            _secretKey.Password = "";
        }
        Show(_kitTitle, phase == Phase.Kit);
        Show(_kitSub, phase == Phase.Kit);
        Show(_kitSave, phase == Phase.Kit && _kit is not null);
        Show(_done, phase == Phase.Done);
        Show(_doneNote, phase == Phase.Done);
        Show(_confirmation, phase == Phase.Done && s?.confirmationSentTo is not null);
        if (s is not null)
        {
            _done.Text = Said.Notice(s.done);
            _doneNote.Text = Said.Key(s.doneNote);
            _confirmation.Text = s.confirmationSentTo is { } to ? Words.JoinConfirmationSent(to) : "";
            _accept.Content = Said.Key(s.joinWord);
            _finish.Content = Said.Key(s.joinWord);
        }
        _error.Text = s?.error is { } e ? Said.Key(e) : "";
        Show(_error, s?.error is not null);
        Show(_continue, phase is Phase.Code or Phase.Register);
        Show(_accept, phase == Phase.Signin);
        Show(_finish, phase == Phase.Kit && _kit is not null);
        Show(_open, phase == Phase.Done && s!.canOpen);
        Show(_switch, s is { offersSwitch: true } && phase is Phase.Register or Phase.Signin);
        _switch.Content = phase == Phase.Signin ? Words.JoinNewAccount() : Words.JoinHaveAccount();
        Show(_busy, s?.busy == true || _opening);
        Enable();
        if (!first && before != phase) Focus(phase);
    }

    /// <summary>Busy disables everything; each button as its phase's fields allow (Android's rules).</summary>
    private void Enable()
    {
        var s = _state;
        var on = s is { busy: false } && !_opening && !_closed;
        foreach (var c in new Control[] { _code, _codeSecret, _username, _firstName, _lastName, _pictureChoose, _pictureRemove, _email, _newPassword.Box, _password, _secretKey, _switch })
        {
            c.IsEnabled = on;
        }
        if (_picking) _pictureChoose.IsEnabled = _pictureRemove.IsEnabled = false;
        if (s is null) return;
        var named = !s.asksUsername || _username.Text.Trim().Length > 0;
        var addressed = !s.asksEmail || _email.Text.Trim().Length > 0;
        var code = s.codeMode == Mode.Password ? _codeSecret.Password : _code.Text;
        _continue.IsEnabled = on && s.phase switch
        {
            Phase.Code => code.Trim().Length > 0,
            Phase.Register => named && addressed && _newPassword.Box.Password.Length >= MinPassword,
            _ => false,
        };
        _accept.IsEnabled = on && named && addressed && _password.Password.Length > 0 && _secretKey.Password.Trim().Length > 0;
        _finish.IsEnabled = on && _kit is { Ready: true };
        _open.IsEnabled = on && s.canOpen;
    }

    /// <summary>The phase's first field takes the focus as the phase opens.</summary>
    private void Focus(Phase phase)
    {
        Control? first = phase switch
        {
            Phase.Code => _state?.codeMode == Mode.Password ? _codeSecret : _code,
            Phase.Register or Phase.Signin => _state?.asksUsername == true ? _username : _state?.asksEmail == true ? _email : phase == Phase.Register ? _newPassword.Box : _password,
            Phase.Done => _open,
            _ => null,
        };
        first?.Focus(FocusState.Programmatic);
    }

    private async void Continue()
    {
        if (_state is not { } s || _closed) return;
        try
        {
            switch (s.phase)
            {
                case Phase.Code:
                    var code = s.codeMode == Mode.Password ? _codeSecret.Password : _code.Text;
                    Act.Send(new Call.JoinCode { join = _join, code = code });
                    break;
                case Phase.Register:
                    _continue.IsEnabled = false;
                    var (answer, _) = await Core.CallAsync(new Call.JoinRegister
                    {
                        join = _join,
                        username = _username.Text,
                        firstName = _firstName.Text,
                        lastName = _lastName.Text,
                        email = _email.Text,
                        password = _newPassword.Box.Password,
                    });
                    Log.Write($"Join.Register answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                    if (answer is Answer.KitOpened { kit: var kit } && !_closed)
                    {
                        // the kit step is the join's: it closes with the join, never on its own
                        _kit?.Dispose();
                        _kit = new KitSection(_window, kit, ownsClose: false) { Changed = Enable };
                        _kitSave.Content = _kit.View;
                        Draw(_state, first: false);
                    }
                    break;
            }
        }
        catch (Exception e)
        {
            Log.Write($"join: the step failed ({e.GetType().Name})");
        }
        finally
        {
            Enable();
        }
    }

    /// <summary>The preview's initials: the name typed, else the handle.</summary>
    private void Named()
    {
        var name = $"{_firstName.Text} {_lastName.Text}".Trim();
        _picture.DisplayName = name.Length > 0 ? name : _username.Text.Trim();
    }

    /// <summary>The join's picture drawn on the preview: the core's encoded bytes decoded in memory where it holds one,
    /// the initials otherwise.</summary>
    private async void Preview(bool held)
    {
        if (!held)
        {
            _picture.ProfilePicture = null;
            return;
        }
        try
        {
            var (answer, bytes) = await Core.CallAsync(new Call.JoinPictureBytes { join = _join });
            if (_closed) return;
            if (answer is not Answer.Bytes || bytes is null)
            {
                Log.Write($"Join.PictureBytes answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                return;
            }
            _picture.ProfilePicture = await Avatars.Decode(bytes);
            Array.Clear(bytes);
            Log.Write("join: the picture previewed");
        }
        catch (Exception e)
        {
            Log.Write($"join: the picture did not decode ({e.GetType().Name})");
        }
    }

    /// <summary>*Choose picture* / *Change picture*: the app's one square pick, the path and the pose to the core
    /// (Join.Picture), which encodes and keeps it in the join until it seals it beside the name.</summary>
    private async void ChoosePicture()
    {
        if (_closed || _picking) return;
        _picking = true;
        Enable();
        try
        {
            var picked = await SquareCropDialog.Pick(_window);
            if (_closed) return;
            var refused = picked is SquareCropDialog.NotAPicture;
            if (picked is SquareCropDialog.Picked chosen)
            {
                var (answer, _) = await Core.CallAsync(new Call.JoinPicture { join = _join, path = chosen.Path, pose = chosen.Pose });
                Log.Write($"Join.Picture answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                refused = answer is not Answer.Done;
                // a picture over a picture leaves the state's flag as it was: the preview is asked anew here
                if (!refused && !_closed) Preview(true);
            }
            _pictureNotImage.Visibility = refused ? Visibility.Visible : Visibility.Collapsed;
        }
        catch (Exception e)
        {
            Log.Write($"join: a picture was not chosen ({e.GetType().Name})");
        }
        finally
        {
            _picking = false;
            if (!_closed) Enable();
        }
    }

    private void SignIn()
    {
        if (_closed) return;
        Act.Send(new Call.JoinSignIn
        {
            join = _join,
            username = _username.Text,
            firstName = _firstName.Text,
            lastName = _lastName.Text,
            email = _email.Text,
            password = _password.Password,
            secretKey = _secretKey.Password.Trim(),
        });
    }

    /// <summary>*Open the space*: the app signs in with what the join holds; the join is let go once it opened, the
    /// session's gate showing the space behind it.</summary>
    private async void OpenSpace()
    {
        if (_closed || _opening) return;
        _opening = true;
        Draw(_state, first: true);
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.JoinOpen { join = _join });
            Log.Write($"Join.Open answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
            if (answer is not Answer.Refused) Leave();
        }
        catch (Exception e)
        {
            Log.Write($"join: the space did not open ({e.GetType().Name})");
        }
        finally
        {
            _opening = false;
            if (!_closed) Draw(_state, first: true);
        }
    }

    /// <summary>The join let go: its model closed in the core (every secret zeroed), the screen handed back.</summary>
    private void Leave()
    {
        if (_closed) return;
        Dispose();
        _left();
    }

    public void Dispose()
    {
        if (_closed) return;
        _closed = true;
        _watch?.Dispose();
        _kit?.Dispose();
        _newPassword.Forget();
        _password.Password = "";
        _secretKey.Password = "";
        _codeSecret.Password = "";
        _code.Text = "";
        Act.Send(new Call.JoinClose { join = _join });
        Log.Write("join: closed");
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
