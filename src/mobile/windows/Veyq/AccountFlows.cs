// The account's flows on Windows (ADR 0148 decision 10; uc.account.change-password, .change-email, .rotate-keys,
// .erase-account, uc.spaces.edit-profile, .set-profile-picture), Fluent's way as Windows' own Settings opens a setting of
// more than one step: a page in the settings frame (AccountSettingsPage), its breadcrumb Settings › the flow's title, Back
// returning to the sections. Each page draws the state of the core's model over the bridge (PasswordChange.*,
// AddressChange.*, KeyRotation.*, Erasure.*, Profile.*; each watched) and calls it; the core decides everything.
//
// Every step where a password or the Secret Key is typed is a PasswordBox kept by ScreenSecrets.Guard (its peek shows the
// secret in clear: the window is out of capture while the page shows one, § Secrets on screen). The typed secrets cross
// once, inward, and each box is emptied once sent. A kit is never on screen: it is saved through FileSavePicker, the core
// writing the PDF straight into the file chosen (Kit.Save by path, KitSection). The danger step is its own ContentDialog
// whose primary is accent-free and whose default is Cancel (Dialogs.Confirm), as Windows asks before an action that
// cannot be undone. Linux draws the same over the same bridge.
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Storage.Pickers;

namespace Veyq;

/// <summary>The account's flows the Settings page opens, each a section of the page (SettingsSection's id).</summary>
internal enum AccountFlow { Profile, Password, Email, Security, Erase }

internal static class AccountFlows
{
    /// <summary>The section's id on the Settings page (SettingsSection.id), where a search's result scrolls to it.</summary>
    public static string Id(AccountFlow f) => f switch
    {
        AccountFlow.Profile => "profile",
        AccountFlow.Password => "password",
        AccountFlow.Email => "email",
        AccountFlow.Security => "security",
        _ => "erase",
    };

    /// <summary>The section's rubric (the web's).</summary>
    public static string Title(AccountFlow f) => f switch
    {
        AccountFlow.Profile => Words.ProfileTitle(),
        AccountFlow.Password => Words.PasswordChangeTitle(),
        AccountFlow.Email => Words.AccountSettingsEmail(),
        AccountFlow.Security => Words.RotationTitle(),
        _ => Words.ErasureTitle(),
    };

    /// <summary>What the flow is for.</summary>
    public static string Sub(AccountFlow f) => f switch
    {
        AccountFlow.Profile => Words.ProfileSub(),
        AccountFlow.Password => Words.PasswordChangeSub(),
        AccountFlow.Email => Words.EmailChangeFormSub(),
        AccountFlow.Security => Words.RotationSub(),
        _ => Words.ErasureSub(),
    };

    /// <summary>The button that opens it, and the page's title.</summary>
    public static string Action(AccountFlow f) => f switch
    {
        AccountFlow.Profile => Words.ProfileEdit(),
        AccountFlow.Password => Words.PasswordChangeTitle(),
        AccountFlow.Email => Words.EmailChangeAction(),
        AccountFlow.Security => Words.RotationAction(),
        _ => Words.ErasureAction(),
    };

    /// <summary>Segoe Fluent Icons: Contact, Permissions, Mail, Shield, Delete.</summary>
    public static string Glyph(AccountFlow f) => f switch
    {
        AccountFlow.Profile => "",
        AccountFlow.Password => "",
        AccountFlow.Email => "",
        AccountFlow.Security => "",
        _ => "",
    };

    /// <summary>The page of <paramref name="flow"/> over the account <paramref name="connection"/> names; <paramref name="back"/>
    /// returns to the Settings page.</summary>
    public static FlowPage Open(AccountFlow flow, Window window, string connection, Action back) => flow switch
    {
        AccountFlow.Profile => new ProfilePage(window, connection, back),
        AccountFlow.Password => new PasswordChangePage(window, connection, back),
        AccountFlow.Email => new AddressChangePage(window, connection, back),
        AccountFlow.Security => new KeyRotationPage(window, connection, back),
        _ => new ErasurePage(window, connection, back),
    };
}

/// <summary>The person's language and local date for a kit (ADR 0148 decision 5): the app's language as its catalog was
/// chosen, today's date where the person is, in days since 1970-01-01.</summary>
internal static class KitDay
{
    public static string Language => Words.Language();

    public static long EpochDay => (long)(DateTime.Today - DateTime.UnixEpoch.Date).TotalDays;
}

/// <summary>One flow's page in the settings frame: the breadcrumb, Back, and the body each flow draws into.</summary>
internal abstract class FlowPage : IDisposable
{
    protected readonly Window Window;
    protected readonly string Connection;
    private readonly Action _back;
    protected readonly StackPanel Body = new() { Spacing = 12 };
    protected bool Closed { get; private set; }

    public UIElement View { get; }

    protected FlowPage(Window window, string connection, Action back, AccountFlow flow)
    {
        Window = window;
        Connection = connection;
        _back = back;
        var column = new StackPanel { Spacing = 16, MaxWidth = 1000, Padding = new Thickness(24, 8, 24, 24) };
        var top = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var backButton = new Button { Content = new FontIcon { Glyph = "", FontSize = 14 }, VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetName(backButton, Words.DrawerSettings());
        AutomationProperties.SetAutomationId(backButton, "flow-back");
        ToolTipService.SetToolTip(backButton, Words.DrawerSettings());
        backButton.Click += (_, _) => _back();
        top.Children.Add(backButton);
        // Windows' own Settings names a sub-page by its path: Settings › the setting
        var crumbs = new BreadcrumbBar { ItemsSource = new[] { Words.DrawerSettings(), AccountFlows.Action(flow) }, VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetAutomationId(crumbs, "flow-crumbs");
        crumbs.ItemClicked += (_, e) =>
        {
            if (e.Index == 0) _back();
        };
        top.Children.Add(crumbs);
        column.Children.Add(top);
        var title = new TextBlock { Text = AccountFlows.Action(flow), Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"] };
        AutomationProperties.SetHeadingLevel(title, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level1);
        AutomationProperties.SetAutomationId(title, $"flow-title:{AccountFlows.Id(flow)}");
        column.Children.Add(title);
        column.Children.Add(Body);
        var grid = new Grid();
        grid.Children.Add(column);
        var scroll = new ScrollViewer { Content = grid, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        AutomationProperties.SetAutomationId(scroll, $"flow:{AccountFlows.Id(flow)}");
        View = scroll;
    }

    /// <summary>Back to the Settings page.</summary>
    protected void Back() => _back();

    public void Dispose()
    {
        if (Closed) return;
        Closed = true;
        Close();
    }

    /// <summary>The flow's model closed in the core and its watches ended.</summary>
    protected abstract void Close();

    protected static Brush Brush(string key) => (Brush)Application.Current.Resources[key];

    protected static TextBlock Text(string text, string? id = null)
    {
        var t = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap };
        if (id is not null) AutomationProperties.SetAutomationId(t, id);
        return t;
    }

    protected static TextBlock Caption(string text, string? id = null)
    {
        var t = Text(text, id);
        t.Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"];
        t.Foreground = Brush("TextFillColorSecondaryBrush");
        return t;
    }

    protected static TextBlock Heading(string text)
    {
        var t = new TextBlock { Text = text, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], Margin = new Thickness(0, 8, 0, 0), TextWrapping = TextWrapping.Wrap };
        AutomationProperties.SetHeadingLevel(t, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        return t;
    }

    /// <summary>A refusal of the flow, in the critical colour, read out as it appears; null for none.</summary>
    protected static UIElement? Refusal(string? msg, string id = "flow-refusal")
    {
        if (msg is null) return null;
        var t = Text(Said.Key(msg), id);
        t.Foreground = Brush("SystemFillColorCriticalBrush");
        AutomationProperties.SetLiveSetting(t, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
        return t;
    }

    /// <summary>A secret's box: masked, kept out of capture while it is loaded (its peek shows it in clear).</summary>
    protected static PasswordBox Secret(string header, string id)
    {
        var box = ScreenSecrets.Guard(new PasswordBox { Header = header, MaxWidth = 420, HorizontalAlignment = HorizontalAlignment.Left });
        AutomationProperties.SetAutomationId(box, id);
        return box;
    }

    protected static Button Accent(string words, string id)
    {
        var b = new Button { Content = words, Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetAutomationId(b, id);
        return b;
    }

    protected static Button Plain(string words, string id)
    {
        var b = new Button { Content = words };
        AutomationProperties.SetAutomationId(b, id);
        return b;
    }

    protected static ProgressRing Ring() => new() { IsActive = true, Width = 24, Height = 24, HorizontalAlignment = HorizontalAlignment.Left };

    /// <summary>A card of lines, as the Settings page draws its cards.</summary>
    protected static Border Card(UIElement content, string id)
    {
        var card = new Border
        {
            Child = content,
            Padding = new Thickness(16, 12, 16, 12),
            CornerRadius = new CornerRadius(4),
            Background = Brush("CardBackgroundFillColorDefaultBrush"),
            BorderBrush = Brush("CardStrokeColorDefaultBrush"),
            BorderThickness = new Thickness(1),
        };
        AutomationProperties.SetAutomationId(card, id);
        return card;
    }

    /// <summary>A list of facts, each its own line with a bullet.</summary>
    protected static UIElement Facts(IEnumerable<string> facts, string id)
    {
        var list = new StackPanel { Spacing = 6 };
        foreach (var f in facts) list.Children.Add(Text($"•  {f}"));
        return Card(list, id);
    }

    /// <summary>Sends <paramref name="call"/>, its answer returned (null where it failed, said in the log).</summary>
    protected static async Task<Answer?> Ask(string what, Call call)
    {
        try
        {
            var (answer, _) = await Core.CallAsync(call);
            Log.Write($"{what}: {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
            return answer;
        }
        catch (Exception e)
        {
            Log.Write($"{what} failed: {e.GetType().Name}");
            return null;
        }
    }

    /// <summary>Opens the flow's model with <paramref name="open"/>: its id, or null where the core refused it.</summary>
    protected static async Task<long?> OpenFlow(string what, Call open)
    {
        var answer = await Ask(what, open);
        return answer switch
        {
            Answer.FlowOpened { flow: var id } => id,
            Answer.ProfileOpened { profile: var id } => id,
            _ => null,
        };
    }

    /// <summary>An exception out of an async void or a watch's callback ends the app in combase: each is said in the log.</summary>
    protected static void Guarded(string what, Action draw)
    {
        try
        {
            draw();
        }
        catch (Exception e)
        {
            Log.Write($"{what} did not draw: {e.GetType().Name} 0x{e.HResult:X8}: {e.Message}");
        }
    }

    /// <summary>A message over the window once the flow ended (the account's keys replaced, the account erased): the
    /// page itself closes then, with the tenant or the account it was of.</summary>
    protected static async void Told(Window window, string id, string title, IEnumerable<string> lines)
    {
        try
        {
            var body = new StackPanel { Spacing = 8 };
            foreach (var l in lines) body.Children.Add(new TextBlock { Text = l, TextWrapping = TextWrapping.Wrap });
            var dialog = new ContentDialog { Title = title, Content = body, CloseButtonText = Words.Ok(), DefaultButton = ContentDialogButton.Close };
            AutomationProperties.SetAutomationId(dialog, id);
            await Dialogs.Show(window, dialog);
        }
        catch (Exception e)
        {
            Log.Write($"{id} was not shown: {e.GetType().Name}");
        }
    }
}

/// <summary>The kit's step (the web's EmergencyKitSave): "Save your Emergency Kit" → FileSavePicker → the core writes
/// the PDF straight into the file chosen (Kit.Save with its path) → "I saved it", which the core takes only once a file
/// was written. The Secret Key is never on screen. A cancelled picker leaves nothing; a failed write leaves no file the
/// core made (it removes what it began) and the step not saved.</summary>
internal sealed class KitSection : IDisposable
{
    private readonly Window _window;
    private readonly long _kit;
    private readonly bool _ownsClose;
    private readonly StackPanel _panel = new() { Spacing = 8 };
    private readonly Button _save = new() { Content = Words.KitSaveTitle() };
    private readonly CheckBox _confirm = new() { Content = Words.KitSavedConfirm(), IsEnabled = false };
    private readonly ContentControl _refusal = new();
    private readonly Watch? _watch;
    private KitStepState? _state;
    private bool _drawing;
    private bool _closed;

    /// <summary>Whether the flow may go on past the kit: a file written and "I saved it" checked.</summary>
    public bool Ready => _state is { saved: true, confirmed: true };

    /// <summary>Heard when <see cref="Ready"/> may have changed.</summary>
    public Action? Changed { get; set; }

    public UIElement View => _panel;

    /// <summary>The kit step <paramref name="kit"/> (Kit.*); <paramref name="ownsClose"/>: this section closes it (an address
    /// change's), else its flow does (a rotation's closes with the rotation).</summary>
    public KitSection(Window window, long kit, bool ownsClose)
    {
        _window = window;
        _kit = kit;
        _ownsClose = ownsClose;
        var icon = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        icon.Children.Add(new FontIcon { Glyph = "", FontSize = 16 });
        icon.Children.Add(new TextBlock { Text = Words.KitSaveTitle() });
        _save.Content = icon;
        AutomationProperties.SetName(_save, Words.KitSaveTitle());
        AutomationProperties.SetAutomationId(_save, "kit-save");
        _save.Click += (_, _) => Save();
        AutomationProperties.SetAutomationId(_confirm, "kit-saved");
        _confirm.Checked += (_, _) => Confirm(true);
        _confirm.Unchecked += (_, _) => Confirm(false);
        _panel.Children.Add(_save);
        _panel.Children.Add(_confirm);
        _panel.Children.Add(_refusal);
        _watch = Core.Watch(new Watched.Kit { kit = kit }, update =>
        {
            if (_closed || update is not Update.Kit { shown: var s }) return;
            try
            {
                Draw(s);
            }
            catch (Exception e)
            {
                Log.Write($"the kit step did not draw: {e.GetType().Name}");
            }
        }, e => Log.Write($"a kit step update: {e.Message}"));
    }

    private void Draw(KitStepState? s)
    {
        _state = s;
        if (s is null)
        {
            _save.IsEnabled = false;
            _confirm.IsEnabled = false;
            Changed?.Invoke();
            return;
        }
        Log.Write($"kit step: saved {s.saved}, confirmed {s.confirmed}, refusal {s.refusal ?? "none"}");
        _drawing = true;
        _confirm.IsEnabled = s.saved;
        _confirm.IsChecked = s.confirmed;
        _drawing = false;
        _refusal.Content = s.refusal is { } r ? FlowText.Critical(Said.Key(r), "kit-refusal") : null;
        Changed?.Invoke();
    }

    private void Confirm(bool on)
    {
        if (_drawing || _closed) return;
        Act.Send(new Call.KitConfirm { kit = _kit, Checked = on });
    }

    /// <summary>The save picker (owned by the app's window) proposing the core's name in Documents; the core writes the
    /// kit at the path chosen.</summary>
    private async void Save()
    {
        if (_state is not { } s) return;
        try
        {
            var picker = new FileSavePicker { SuggestedStartLocation = PickerLocationId.DocumentsLibrary, SuggestedFileName = Path.GetFileNameWithoutExtension(s.fileName) };
            picker.FileTypeChoices.Add(Words.TabDepotFile(), [".pdf"]);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var chosen = await picker.PickSaveFileAsync();
            if (chosen is null || _closed) return;
            _save.IsEnabled = false;
            var (answer, _) = await Core.CallAsync(new Call.KitSave { kit = _kit, path = chosen.Path, language = KitDay.Language, createdEpochDay = KitDay.EpochDay });
            // never the path: the kit's file name holds the address
            Log.Write($"Kit.Save answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
        }
        catch (Exception e)
        {
            Log.Write($"the kit was not saved: {e.GetType().Name}");
        }
        finally
        {
            _save.IsEnabled = !_closed;
        }
    }

    public void Dispose()
    {
        if (_closed) return;
        _closed = true;
        _watch?.Dispose();
        if (_ownsClose) Act.Send(new Call.KitClose { kit = _kit });
    }
}

/// <summary>Small text helpers shared by the flows and the kit's section.</summary>
internal static class FlowText
{
    public static TextBlock Critical(string text, string id)
    {
        var t = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"] };
        AutomationProperties.SetAutomationId(t, id);
        AutomationProperties.SetLiveSetting(t, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
        return t;
    }
}

// ---- Change password (D6) ----

internal sealed class PasswordChangePage : FlowPage
{
    private readonly PasswordBox _current = Secret(Words.PasswordChangeCurrent(), "password-current");
    private readonly PasswordBox _next = Secret(Words.PasswordChangeNew(), "password-new");
    private readonly PasswordBox _confirm = Secret(Words.PasswordChangeConfirm(), "password-confirm");
    private readonly PasswordBox _key = Secret(Words.PasswordChangeSecretKey(), "password-secret-key");
    private readonly Button _run = Accent(Words.PasswordChangeSubmit(), "password-submit");
    private long? _id;
    private Watch? _watch;
    private PasswordChangeState? _state;

    public PasswordChangePage(Window window, string connection, Action back) : base(window, connection, back, AccountFlow.Password)
    {
        foreach (var box in new[] { _current, _next, _confirm, _key }) box.PasswordChanged += (_, _) => Enable();
        _run.Click += (_, _) => Run();
        Draw(null);
        Start();
    }

    private async void Start()
    {
        var id = await OpenFlow("PasswordChange.Open", new Call.PasswordChangeOpen { connection = Connection });
        if (id is not { } flow) return;
        if (Closed)
        {
            Act.Send(new Call.PasswordChangeClose { flow = flow });
            return;
        }
        _id = flow;
        _watch = Core.Watch(new Watched.PasswordChange { flow = flow }, update =>
        {
            if (Closed || update is not Update.PasswordChange { shown: var s }) return;
            Guarded("the password change", () => Draw(s));
        }, e => Log.Write($"a password change update: {e.Message}"));
    }

    private void Enable() => _run.IsEnabled = _state is not { working: true } && _current.Password.Length > 0 && _next.Password.Length > 0
        && _confirm.Password.Length > 0 && _key.Password.Length > 0;

    private void Draw(PasswordChangeState? s)
    {
        _state = s;
        Body.Children.Clear();
        if (s is { done: true })
        {
            foreach (var box in new[] { _current, _next, _confirm, _key }) box.Password = "";
            var done = new StackPanel { Spacing = 6 };
            done.Children.Add(Text(Words.PasswordChangeDone(), "password-done"));
            done.Children.Add(Caption(Words.PasswordChangeDoneNote()));
            Body.Children.Add(Card(done, "password-done-card"));
            var close = Accent(Words.ShareDone(), "password-close");
            close.Click += (_, _) => Back();
            Body.Children.Add(close);
            return;
        }
        Body.Children.Add(Caption(Words.PasswordChangeSub()));
        Body.Children.Add(_current);
        Body.Children.Add(_next);
        Body.Children.Add(_confirm);
        Body.Children.Add(_key);
        Body.Children.Add(Caption(Words.PasswordChangeSecretKeyHint()));
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
        row.Children.Add(_run);
        if (s is { working: true }) row.Children.Add(Ring());
        Body.Children.Add(row);
        if (Refusal(s?.refusal) is { } refused) Body.Children.Add(refused);
        Enable();
    }

    private async void Run()
    {
        if (_id is not { } flow) return;
        var call = new Call.PasswordChangeRun
        {
            flow = flow, currentPassword = _current.Password, newPassword = _next.Password, confirmPassword = _confirm.Password, secretKey = _key.Password,
        };
        // the Secret Key crosses once; typed again after a refusal
        _key.Password = "";
        _run.IsEnabled = false;
        await Ask("PasswordChange.Run", call);
    }

    protected override void Close()
    {
        _watch?.Dispose();
        if (_id is { } flow) Act.Send(new Call.PasswordChangeClose { flow = flow });
    }
}

// ---- Change the sign-in address (D6) ----

/// <summary>The request (the new address, the password, the Secret Key) → "a link was sent"; then, as on the web, a new
/// kit with the account's address (the password and the Secret Key checked first). The confirm is the web's alone (owner
/// 2026-11-18): the app takes the new address at its next account read.</summary>
internal sealed class AddressChangePage : FlowPage
{
    private readonly TextBox _email = new() { Header = Words.EmailChangeNewEmail(), MaxWidth = 420, HorizontalAlignment = HorizontalAlignment.Left, InputScope = new Microsoft.UI.Xaml.Input.InputScope { Names = { new Microsoft.UI.Xaml.Input.InputScopeName(Microsoft.UI.Xaml.Input.InputScopeNameValue.EmailSmtpAddress) } } };
    private readonly PasswordBox _password = Secret(Words.EmailChangePassword(), "email-password");
    private readonly PasswordBox _key = Secret(Words.EmailChangeSecretKey(), "email-secret-key");
    private readonly Button _request = Accent(Words.EmailChangeSubmit(), "email-submit");
    private readonly PasswordBox _kitPassword = Secret(Words.EmailChangePassword(), "email-kit-password");
    private readonly PasswordBox _kitKey = Secret(Words.EmailChangeSecretKey(), "email-kit-secret-key");
    private readonly Button _check = Plain(Words.EmailChangeKitCheck(), "email-kit-check");
    private long? _id;
    private Watch? _watch;
    private AddressChangeState? _state;
    private KitSection? _kit;

    public AddressChangePage(Window window, string connection, Action back) : base(window, connection, back, AccountFlow.Email)
    {
        AutomationProperties.SetAutomationId(_email, "email-new");
        _email.TextChanged += (_, _) => Enable();
        foreach (var box in new[] { _password, _key, _kitPassword, _kitKey }) box.PasswordChanged += (_, _) => Enable();
        _request.Click += (_, _) => Request();
        _check.Click += (_, _) => MakeKit();
        Draw(null);
        Start();
    }

    private async void Start()
    {
        var id = await OpenFlow("AddressChange.Open", new Call.AddressChangeOpen { connection = Connection });
        if (id is not { } flow) return;
        if (Closed)
        {
            Act.Send(new Call.AddressChangeClose { flow = flow });
            return;
        }
        _id = flow;
        _watch = Core.Watch(new Watched.AddressChange { flow = flow }, update =>
        {
            if (Closed || update is not Update.AddressChange { shown: var s }) return;
            Guarded("the address change", () => Draw(s));
        }, e => Log.Write($"an address change update: {e.Message}"));
    }

    private void Enable()
    {
        var working = _state is { working: true };
        _request.IsEnabled = !working && _email.Text.Trim().Length > 0 && _password.Password.Length > 0 && _key.Password.Length > 0;
        _check.IsEnabled = !working && _kitPassword.Password.Length > 0 && _kitKey.Password.Length > 0;
    }

    private void Draw(AddressChangeState? s)
    {
        _state = s;
        Body.Children.Clear();
        if (s?.sentTo is { } sent)
        {
            _password.Password = "";
            Body.Children.Add(Card(Text(Words.EmailChangeSent(sent), "email-sent"), "email-sent-card"));
            Body.Children.Add(Heading(Words.EmailChangeKitTitle()));
            if (_kit is { } kit)
            {
                Body.Children.Add(kit.View);
            }
            else
            {
                Body.Children.Add(Caption(Words.EmailChangeKitSub()));
                Body.Children.Add(_kitPassword);
                Body.Children.Add(_kitKey);
                var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
                row.Children.Add(_check);
                if (s.working) row.Children.Add(Ring());
                Body.Children.Add(row);
                if (Refusal(s.refusal) is { } refused) Body.Children.Add(refused);
            }
            var close = Plain(Words.ShareDone(), "email-close");
            close.Click += (_, _) => Back();
            Body.Children.Add(close);
            Enable();
            return;
        }
        Body.Children.Add(Caption(Words.EmailChangeFormSub()));
        Body.Children.Add(_email);
        Body.Children.Add(_password);
        Body.Children.Add(_key);
        Body.Children.Add(Caption(Words.RotationSecretKeyHint()));
        var send = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
        send.Children.Add(_request);
        if (s is { working: true }) send.Children.Add(Ring());
        Body.Children.Add(send);
        if (Refusal(s?.refusal) is { } r) Body.Children.Add(r);
        Enable();
    }

    private async void Request()
    {
        if (_id is not { } flow) return;
        var call = new Call.AddressChangeRequest { flow = flow, newEmail = _email.Text.Trim(), password = _password.Password, secretKey = _key.Password };
        _key.Password = "";
        _request.IsEnabled = false;
        await Ask("AddressChange.Request", call);
    }

    /// <summary>"Check and make the kit": the password and the Secret Key checked by the core, then the kit's step.</summary>
    private async void MakeKit()
    {
        if (_id is not { } flow) return;
        var call = new Call.AddressChangeKit { flow = flow, password = _kitPassword.Password, secretKey = _kitKey.Password };
        _kitKey.Password = "";
        _kitPassword.Password = "";
        _check.IsEnabled = false;
        if (await Ask("AddressChange.Kit", call) is Answer.KitOpened { kit: var kit })
        {
            if (Closed)
            {
                Act.Send(new Call.KitClose { kit = kit });
                return;
            }
            _kit = new KitSection(Window, kit, ownsClose: true);
            Draw(_state);
        }
    }

    protected override void Close()
    {
        _kit?.Dispose();
        _kit = null;
        _watch?.Dispose();
        if (_id is { } flow) Act.Send(new Call.AddressChangeClose { flow = flow });
    }
}

// ---- Replace the keys (D7) ----

/// <summary>The web's order: the facts → the gate (the password, the Secret Key, "also choose a new password") → the new
/// kit saved (never shown) and "I saved it" → the danger confirmation → the publish. A moderator is sent to the web (D9).
/// Once the keys are replaced the bridge opens the tenant anew (its keys were the old identity's), which closes this
/// page with the Settings page: what it came to is told over the window.</summary>
internal sealed class KeyRotationPage : FlowPage
{
    private readonly PasswordBox _password = Secret(Words.RotationPassword(), "rotation-password");
    private readonly PasswordBox _key = Secret(Words.RotationSecretKey(), "rotation-secret-key");
    private readonly ToggleSwitch _choose = new() { Header = Words.RotationNewPassword() };
    private readonly PasswordBox _next = Secret(Words.PasswordChangeNew(), "rotation-new-password");
    private readonly PasswordBox _confirm = Secret(Words.PasswordChangeConfirm(), "rotation-confirm-password");
    private readonly Button _continue = Accent(Words.RotationNext(), "rotation-next");
    private readonly Button _publish = Plain(Words.RotationAction(), "rotation-publish");
    private long? _id;
    private Watch? _watch;
    private KeyRotationState? _state;
    private KitSection? _kit;
    private bool _drawing;
    private bool _told;

    public KeyRotationPage(Window window, string connection, Action back) : base(window, connection, back, AccountFlow.Security)
    {
        AutomationProperties.SetAutomationId(_choose, "rotation-choose-password");
        _choose.Toggled += (_, _) =>
        {
            if (_drawing || _id is not { } flow) return;
            Act.Send(new Call.KeyRotationChooseNewPassword { flow = flow, on = _choose.IsOn });
        };
        foreach (var box in new[] { _password, _key, _next, _confirm }) box.PasswordChanged += (_, _) => Enable();
        _continue.Click += (_, _) => Prove();
        _publish.Click += (_, _) => Publish();
        Draw(null);
        Start();
    }

    private async void Start()
    {
        var id = await OpenFlow("KeyRotation.Open", new Call.KeyRotationOpen { connection = Connection });
        if (id is not { } flow) return;
        if (Closed)
        {
            Act.Send(new Call.KeyRotationClose { flow = flow });
            return;
        }
        _id = flow;
        _watch = Core.Watch(new Watched.KeyRotation { flow = flow }, update =>
        {
            if (update is not Update.KeyRotation { shown: var s }) return;
            if (s is null)
            {
                // closed with the tenant the bridge opened anew after the publish: what it came to is told once
                if (_state is { done: true } last) TellDone(last);
                return;
            }
            if (Closed) return;
            Guarded("the rotation", () => Draw(s));
        }, e => Log.Write($"a rotation update: {e.Message}"));
    }

    private void Enable()
    {
        var newPassword = _state?.newPassword ?? true;
        _continue.IsEnabled = _state is { step: Step.Gate } && _password.Password.Length > 0 && _key.Password.Length > 0
            && (!newPassword || (_next.Password.Length > 0 && _confirm.Password.Length > 0));
        _publish.IsEnabled = _state is { step: Step.Kit } && _kit is { Ready: true };
    }

    private void Draw(KeyRotationState? s)
    {
        _state = s;
        Log.Write($"rotation: step {s?.step}, done {s?.done}, resumed {s?.resumed}, {s?.scopes.Count ?? 0} scopes, refusal {s?.refusal ?? "none"}");
        Body.Children.Clear();
        switch (s?.step)
        {
            case null or Step.Loading or Step.Working:
                Body.Children.Add(Ring());
                break;
            case Step.OnTheWeb:
                var web = new StackPanel { Spacing = 6 };
                web.Children.Add(Text(Words.RotationModeratorWeb(), "rotation-on-the-web"));
                web.Children.Add(Caption(Words.AccountOnTheWeb(s.server)));
                Body.Children.Add(Card(web, "rotation-on-the-web-card"));
                break;
            case Step.Gate:
                Gate(s);
                break;
            case Step.Kit:
                KitStep(s);
                break;
            case Step.Done:
                Done(s);
                break;
        }
        Enable();
    }

    private void Gate(KeyRotationState s)
    {
        Body.Children.Add(Caption(Words.RotationSub()));
        Body.Children.Add(Heading(Words.RotationAfterTitle()));
        Body.Children.Add(Facts([Words.RotationFactSharedSpaces(), Words.RotationFactSignsOut(), Words.RotationFactNewKit(), Words.RotationFactPassword(),
            Words.RotationFactNotRepair(), Words.RotationStoreWiped()], "rotation-facts"));
        Body.Children.Add(_password);
        Body.Children.Add(_key);
        Body.Children.Add(Caption(Words.RotationSecretKeyHint()));
        _drawing = true;
        _choose.IsOn = s.newPassword;
        _drawing = false;
        Body.Children.Add(_choose);
        if (s.newPassword)
        {
            Body.Children.Add(_next);
            Body.Children.Add(_confirm);
        }
        Body.Children.Add(_continue);
        if (Refusal(s.refusal) is { } r) Body.Children.Add(r);
    }

    private void KitStep(KeyRotationState s)
    {
        Body.Children.Add(Heading(Words.RotationKitTitle()));
        Body.Children.Add(Text(Words.RotationKitSub()));
        Body.Children.Add(Caption(Words.RotationKitKeepOld()));
        if (_kit is { } kit) Body.Children.Add(kit.View);
        // the danger step: asked first in its own dialog, never the accent's colour
        Body.Children.Add(_publish);
        if (Refusal(s.refusal) is { } r) Body.Children.Add(r);
    }

    private void Done(KeyRotationState s)
    {
        if (s.refusal is { } refusal)
        {
            Body.Children.Add(Refusal(refusal)!);
            return;
        }
        var done = new StackPanel { Spacing = 6 };
        done.Children.Add(Text(Words.RotationDoneOldKit(), "rotation-done"));
        if (s.scopes.Count > 0) done.Children.Add(Caption(Words.RotationDoneSpaces()));
        Body.Children.Add(Card(done, "rotation-done-card"));
        foreach (var scope in s.scopes) Body.Children.Add(Caption(ScopeLine(scope), $"rotation-scope:{scope.scopeId}"));
    }

    /// <summary>A space's line: its name (the core's, else its word: one's personal space, or one this device has not
    /// opened), its new lock's status, and why it is blocked where it is.</summary>
    private static string ScopeLine(ScopeStatus scope)
    {
        var name = scope.name ?? (scope.nameWord is { } word ? Said.Key(word) : Words.RotationScopeNameUnavailable());
        return scope.blocked is { } why ? $"{name}: {Said.Key(scope.status)} · {Said.Key(why)}" : $"{name}: {Said.Key(scope.status)}";
    }

    private async void Prove()
    {
        if (_id is not { } flow) return;
        var call = new Call.KeyRotationProve
        {
            flow = flow, password = _password.Password, secretKey = _key.Password,
            newPassword = _state?.newPassword == true ? _next.Password : "", confirmPassword = _state?.newPassword == true ? _confirm.Password : "",
        };
        _key.Password = "";
        _continue.IsEnabled = false;
        var answer = await Ask("KeyRotation.Prove", call);
        if (answer is Answer.KitOpened { kit: var kit } && !Closed)
        {
            _password.Password = "";
            _next.Password = "";
            _confirm.Password = "";
            _kit = new KitSection(Window, kit, ownsClose: false) { Changed = Enable };
            if (_state is { } s) Draw(s);
        }
    }

    private async void Publish()
    {
        if (_id is not { } flow || _kit is not { Ready: true }) return;
        if (!await Dialogs.Confirm(Window, "rotation-confirm", Words.RotationConfirmTitle(), Words.RotationConfirmBody(), Words.RotationAction())) return;
        if (Closed) return;
        _publish.IsEnabled = false;
        var answer = await Ask("KeyRotation.Run", new Call.KeyRotationRun { flow = flow });
        if (answer is not null && _state is { done: true } last) TellDone(last);
    }

    /// <summary>The keys replaced: told once over the window, the spaces' new locks with it where the core named them.</summary>
    private void TellDone(KeyRotationState s)
    {
        if (_told) return;
        _told = true;
        var lines = new List<string> { s.refusal is { } r ? Said.Key(r) : Words.RotationDoneOldKit() };
        if (s.refusal is null && s.scopes.Count > 0)
        {
            lines.Add(Words.RotationDoneSpaces());
            lines.AddRange(s.scopes.Select(ScopeLine));
        }
        Told(Window, "rotation-done-told", Words.RotationTitle(), lines);
    }

    protected override void Close()
    {
        _kit?.Dispose();
        _kit = null;
        _watch?.Dispose();
        if (_id is { } flow) Act.Send(new Call.KeyRotationClose { flow = flow });
    }
}

// ---- Erase the account (D8) ----

internal sealed class ErasurePage : FlowPage
{
    private readonly PasswordBox _password = Secret(Words.ErasurePassword(), "erasure-password");
    private readonly Button _erase = Plain(Words.ErasureAction(), "erasure-run");
    private long? _id;
    private Watch? _watch;
    private AccountErasureState? _state;
    private bool _told;

    public ErasurePage(Window window, string connection, Action back) : base(window, connection, back, AccountFlow.Erase)
    {
        _password.PasswordChanged += (_, _) => Enable();
        _erase.Click += (_, _) => Erase();
        Draw(null);
        Start();
    }

    private async void Start()
    {
        var id = await OpenFlow("Erasure.Open", new Call.ErasureOpen { connection = Connection });
        if (id is not { } flow) return;
        if (Closed)
        {
            Act.Send(new Call.ErasureClose { flow = flow });
            return;
        }
        _id = flow;
        _watch = Core.Watch(new Watched.Erasure { flow = flow }, update =>
        {
            if (update is not Update.Erasure { shown: var s }) return;
            if (s is null)
            {
                if (_state is { erased: true }) TellErased();
                return;
            }
            if (Closed) return;
            Guarded("the erasure", () => Draw(s));
        }, e => Log.Write($"an erasure update: {e.Message}"));
    }

    /// <summary>Every tenant with other members has its choice (the core's choicesMade; the core refuses the erasure
    /// without, ERASURE_ERROR_CHOICE_REQUIRED, so this only keeps the button from asking in vain).</summary>
    private static bool ChoicesMade(AccountErasureState s) => s.tenants.All(t => t.members.Count == 0 || t.chosen);

    private void Enable() => _erase.IsEnabled = _state is { loaded: true, working: false, serverOwner: false, onTheWeb: false } s
        && _password.Password.Length > 0 && ChoicesMade(s);

    private void Draw(AccountErasureState? s)
    {
        _state = s;
        Log.Write($"erasure: loaded {s?.loaded}, working {s?.working}, owner {s?.serverOwner}, web {s?.onTheWeb}, {s?.tenants.Count ?? 0} tenants, erased {s?.erased}, refusal {s?.refusal ?? "none"}");
        if (s is { erased: true })
        {
            TellErased();
            return;
        }
        Body.Children.Clear();
        if (s is not { loaded: true })
        {
            Body.Children.Add(Ring());
            if (Refusal(s?.refusal) is { } failed) Body.Children.Add(failed);
            return;
        }
        if (s.serverOwner)
        {
            Body.Children.Add(Card(Text(Words.ErasureServerOwner(), "erasure-server-owner"), "erasure-server-owner-card"));
            return;
        }
        if (s.onTheWeb)
        {
            var web = new StackPanel { Spacing = 6 };
            var strong = Text(Words.ErasureModeratorWeb(), "erasure-on-the-web");
            strong.Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"];
            web.Children.Add(strong);
            web.Children.Add(Caption(Words.AccountOnTheWeb(s.server)));
            Body.Children.Add(Card(web, "erasure-on-the-web-card"));
            return;
        }
        Body.Children.Add(Caption(Words.ErasureSub()));
        Body.Children.Add(Facts([Words.ErasureFactGoes(), Words.ErasureFactSharedStays(), Words.ErasureFactKept(), Words.ErasureFactBackups(), Words.ErasureFactWindow()], "erasure-facts"));
        var shared = s.tenants.Where(t => t.members.Count > 0).ToList();
        if (shared.Count > 0)
        {
            Body.Children.Add(Heading(Words.ErasureChoiceTitle()));
            Body.Children.Add(Caption(Words.ErasureChoiceSub()));
            foreach (var t in shared) Body.Children.Add(Choice(t));
        }
        var sole = s.tenants.Where(t => t.members.Count == 0).ToList();
        if (sole.Count > 0)
        {
            Body.Children.Add(Heading(Words.ErasureSoleTitle()));
            Body.Children.Add(Caption(Words.ErasureSoleSub()));
            foreach (var t in sole) Body.Children.Add(Text(t.name, $"erasure-sole:{t.tenantId}"));
        }
        Body.Children.Add(_password);
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
        row.Children.Add(_erase);
        if (s.working) row.Children.Add(Ring());
        Body.Children.Add(row);
        if (Refusal(s.refusal) is { } r) Body.Children.Add(r);
        Enable();
    }

    /// <summary>A last-owner tenant's choice: delete it along, or one of its members as the new owner.</summary>
    private UIElement Choice(AccountErasureTenant t)
    {
        var box = new ComboBox { Header = t.name, PlaceholderText = Words.ErasureChoicePlaceholder(), MinWidth = 320 };
        AutomationProperties.SetAutomationId(box, $"erasure-choice:{t.tenantId}");
        var options = new List<string?> { null };
        box.Items.Add(Words.ErasureDeleteAlong());
        foreach (var m in t.members)
        {
            box.Items.Add(Words.ErasureMakeOwner(m.name));
            options.Add(m.userId);
        }
        if (t.chosen) box.SelectedIndex = options.IndexOf(t.newOwnerId);
        var tenant = t.tenantId;
        box.SelectionChanged += (_, _) =>
        {
            if (_id is not { } flow || box.SelectedIndex < 0) return;
            Act.Send(new Call.ErasureChoose { flow = flow, tenantId = tenant, newOwner = options[box.SelectedIndex] });
        };
        return box;
    }

    private async void Erase()
    {
        if (_id is not { } flow) return;
        if (!await Dialogs.Confirm(Window, "erasure-confirm", Words.ErasureConfirmTitle(), Words.ErasureConfirmBody(), Words.ErasureAction())) return;
        if (Closed) return;
        var call = new Call.ErasureRun { flow = flow, password = _password.Password };
        _password.Password = "";
        _erase.IsEnabled = false;
        await Ask("Erasure.Run", call);
    }

    /// <summary>The account is erased and this device forgot it; the bridge signed it out here: told once over the window.</summary>
    private void TellErased()
    {
        if (_told) return;
        _told = true;
        Told(Window, "erasure-ended", Words.ErasureTitle(), [Words.ErasureEnded()]);
    }

    protected override void Close()
    {
        _watch?.Dispose();
        if (_id is { } flow) Act.Send(new Call.ErasureClose { flow = flow });
    }
}
