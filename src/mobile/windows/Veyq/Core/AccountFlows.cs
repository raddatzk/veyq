// The account's flows on Windows (ADR 0148 decision 10; uc.account.change-password, .change-email, .rotate-keys,
// .erase-account, uc.spaces.edit-profile, .set-profile-picture), Fluent's way as Windows' own Settings opens a setting of
// more than one step: a page in the settings frame (AccountSettingsPage), its breadcrumb Settings › the flow's title, Back
// returning to the sections. Each page draws the state of the core's model over the bridge (PasswordChange.*,
// AddressChange.*, KeyRotation.*, Erasure.*, Profile.*; each watched) and calls it; the core decides everything.
//
// Every step where a password or the Secret Key is typed is a PasswordBox kept by ScreenSecrets.Guard (its peek shows the
// secret in clear: the window is out of capture while the page shows one, § Secrets on screen). The typed secrets cross
// inward only, and each box is emptied once the core took it — the password change's and the rotation gate's boxes stay
// until then (a breached new password stops the submit until *Use it anyway*, and the web keeps the form as typed), the
// address change's are emptied once sent. Each Secret Key is the sign-in's control (FlowKeyControl, ADR 0151 decision 4:
// typed or pasted, a kit file through FileOpenPicker, a scan where there is a camera) and each new password is checked
// against known breaches (NewPasswordField, decision 5). A kit is never on screen: it is saved through FileSavePicker, the core
// writing the PDF straight into the file chosen (Kit.Save by path, KitSection). The danger step is its own ContentDialog
// whose primary is accent-free and whose default is Cancel (Dialogs.Confirm), as Windows asks before an action that
// cannot be undone. Linux draws the same over the same bridge.

namespace Veyq.Core;

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

    /// <summary>The panels of the last draw that hold a kept control (a page's own button or picture, made once).</summary>
    private readonly List<Panel> _held = [];

    /// <summary>A panel of this draw that a kept control goes into. Body's Clear leaves the control a child of the old
    /// panel, and adding it to the next draw's threw 0x800F1000 — every draw after the first: the profile stopped drawing
    /// once a picture was chosen (measured 2026-11-19, Windows pass). ClearBody empties them.</summary>
    protected T Held<T>(T panel) where T : Panel
    {
        _held.Add(panel);
        return panel;
    }

    /// <summary>Body emptied for a new draw, every kept control taken out of the panel it was last put in.</summary>
    protected void ClearBody()
    {
        Body.Children.Clear();
        foreach (var p in _held) p.Children.Clear();
        _held.Clear();
    }

    public void Dispose()
    {
        if (Closed) return;
        Closed = true;
        Close();
    }

    /// <summary>The flow's model closed in the core and its watches ended.</summary>
    protected abstract void Close();

    internal static Brush Brush(string key) => (Brush)Application.Current.Resources[key];

    internal static TextBlock Text(string text, string? id = null)
    {
        var t = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap };
        if (id is not null) AutomationProperties.SetAutomationId(t, id);
        return t;
    }

    internal static TextBlock Caption(string text, string? id = null)
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
    internal static PasswordBox Secret(string header, string id)
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

    /// <summary>A notice under a field, hidden until said: the caption's size, read out as it changes.</summary>
    public static TextBlock Notice(string id)
    {
        var t = FlowPage.Caption("", id);
        t.Visibility = Visibility.Collapsed;
        AutomationProperties.SetLiveSetting(t, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Polite);
        return t;
    }

    /// <summary>Shows <paramref name="t"/> with <paramref name="text"/> in <paramref name="brush"/>, or hides it for none.</summary>
    public static void Say(TextBlock t, string? text, string brush = "TextFillColorSecondaryBrush")
    {
        t.Text = text ?? "";
        t.Foreground = (Brush)Application.Current.Resources[brush];
        t.Visibility = text is null ? Visibility.Collapsed : Visibility.Visible;
    }
}

// ---- The Secret Key's control (ADR 0151 decision 4): the sign-in's, in each flow that asks for the key ----

/// <summary>A flow's Secret Key as the sign-in takes it: typed or pasted into its box (a <c>veyqkit:</c> token too: the
/// core reads it first), or an Emergency Kit read from a file FileOpenPicker picked (Kit.Read, the core reading the path
/// and asking KitDrawing for its pages) or scanned where there is a camera (Scan.Open of kind FLOW_KIT, the flow its link).
/// A kit's key stays in the flow's intake in the core: this side draws only what the flow's update says it holds — "From
/// your Emergency Kit" with *Enter it instead* (Kit.Clear), a kit of another account's warning (it never blocks: only the
/// wrap can judge a key), a kit of another server's note — and sends the field empty while a kit is held. Linux's
/// KeyControl draws the same.</summary>
internal sealed class FlowKeyControl
{
    private readonly Window _window;
    private readonly Func<long?> _flow;
    private readonly string _prefix;
    private readonly StackPanel _panel = new() { Spacing = 8 };
    private readonly StackPanel _held = new() { Spacing = 4, Visibility = Visibility.Collapsed };
    private readonly Button _open;
    private readonly Button _scan;
    private readonly ContentControl _problem = new();
    private readonly TextBlock _otherAccount;
    private readonly TextBlock _otherServer;
    private HeldKit? _shown;

    /// <summary>The typed field (shown while no kit is held).</summary>
    public PasswordBox Box { get; }

    /// <summary>Heard when <see cref="Ready"/> may have changed.</summary>
    public Action? Changed { get; set; }

    public UIElement View => _panel;

    /// <summary>Whether the flow has a Secret Key to send: a kit's held, or one typed.</summary>
    public bool Ready => _shown is not null || Box.Password.Length > 0;

    /// <param name="flow">the flow's id once it opened</param>
    /// <param name="header">the field's words (the flow's)</param>
    /// <param name="id">the field's automation id</param>
    /// <param name="prefix">the controls' automation ids' (<c>password</c> → <c>password-kit-open</c>)</param>
    public FlowKeyControl(Window window, Func<long?> flow, string header, string id, string prefix)
    {
        _window = window;
        _flow = flow;
        _prefix = prefix;
        Box = FlowPage.Secret(header, id);
        Box.PasswordChanged += (_, _) => Changed?.Invoke();
        var clear = new HyperlinkButton { Content = Words.SigninKitClear(), Padding = new Thickness(0) };
        AutomationProperties.SetAutomationId(clear, $"{prefix}-kit-clear");
        clear.Click += (_, _) =>
        {
            if (_flow() is { } f) Act.Send(new Call.KitClear { flow = f });
        };
        AutomationProperties.SetAutomationId(_held, $"{prefix}-kit-held");
        _held.Children.Add(new TextBlock { Text = header });
        var heldText = FlowPage.Text(Words.SigninKitHeld(), $"{prefix}-kit-held-text");
        heldText.Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"];
        _held.Children.Add(heldText);
        _held.Children.Add(clear);
        // the file first on a desktop, as the sign-in; the scan only where there is a camera
        _open = new Button { Content = Words.SigninKitOpen() };
        AutomationProperties.SetAutomationId(_open, $"{prefix}-kit-open");
        _open.Click += (_, _) => Pick();
        _scan = new Button { Content = Words.SigninKitScan(), Visibility = Visibility.Collapsed };
        AutomationProperties.SetAutomationId(_scan, $"{prefix}-kit-scan");
        _scan.Click += (_, _) => Scan();
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        buttons.Children.Add(_open);
        buttons.Children.Add(_scan);
        AutomationProperties.SetAutomationId(_problem, $"{prefix}-kit-problem");
        _otherAccount = FlowText.Notice($"{prefix}-kit-other-account");
        _otherServer = FlowText.Notice($"{prefix}-kit-other-server");
        foreach (var c in new UIElement[] { Box, _held, buttons, _problem, _otherAccount, _otherServer }) _panel.Children.Add(c);
        OfferScan();
    }

    /// <summary>The Secret Key as the field holds it, the box kept (the flow may stop for a breached password and go on):
    /// empty while a kit's key is held, so the core takes the intake's.</summary>
    public string Typed() => _shown is null ? Box.Password : "";

    /// <summary>As <see cref="Typed"/>, the box emptied: a typed secret is sent and not kept.</summary>
    public string Take()
    {
        var typed = Typed();
        Box.Password = "";
        return typed;
    }

    /// <summary>Draws the intake's <paramref name="state"/> (none: nothing held).</summary>
    public void Draw(IntakeState? state)
    {
        var kit = state?.kit;
        var newKit = kit is not null && !Equals(_shown, kit);
        _shown = kit;
        if (newKit) Box.Password = "";
        Box.Visibility = kit is null ? Visibility.Visible : Visibility.Collapsed;
        _held.Visibility = kit is null ? Visibility.Collapsed : Visibility.Visible;
        var reading = state?.reading == true;
        Box.IsEnabled = !reading;
        _open.IsEnabled = !reading;
        _scan.IsEnabled = !reading;
        _problem.Content = state?.problem is { } p ? FlowText.Critical(Said.Key(p), $"{_prefix}-kit-problem-text") : null;
        FlowText.Say(_otherAccount, kit is { otherAccount: { } expected } ? Words.KitEmailMismatch(kit.email, expected) : null, "SystemFillColorCautionBrush");
        FlowText.Say(_otherServer, kit is { otherServer: { } before } ? Words.SigninKitOtherServer(kit.server ?? "", before) : null);
        Changed?.Invoke();
    }

    /// <summary>The scan's entry only where there is a camera (no camera, no entry).</summary>
    private async void OfferScan()
    {
        try
        {
            var present = await QrCamera.PresentAsync();
            Log.Write(present ? "a flow's kit scan offered" : "a flow's kit scan not offered: no camera");
            _scan.Visibility = present ? Visibility.Visible : Visibility.Collapsed;
        }
        catch (Exception e)
        {
            Log.Write($"the camera's check failed: {e.GetType().Name}");
        }
    }

    /// <summary>"Open Emergency Kit": the open picker for a PDF or a picture; the core reads the file at the path picked
    /// into the flow's intake.</summary>
    private async void Pick()
    {
        try
        {
            if (_flow() is not { } flow) return;
            var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.DocumentsLibrary };
            foreach (var ext in SignInView.KitFileTypes) picker.FileTypeFilter.Add(ext);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var file = await picker.PickSingleFileAsync();
            if (file is null || string.IsNullOrEmpty(file.Path)) return;
            var (answer, _) = await Core.CallAsync(new Call.KitRead { flow = flow, path = file.Path });
            // the outcome alone: never the path or anything of the kit
            Log.Write($"a flow's kit picked: {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
        }
        catch (Exception e)
        {
            Log.Write($"a flow's kit pick failed: {e.GetType().Name}");
        }
    }

    /// <summary>"Scan Emergency Kit": the camera in a dialog over a scan of kind FLOW_KIT; the kit it finds goes to the
    /// flow's intake in the core, and the dialog closes on it.</summary>
    private async void Scan()
    {
        try
        {
            if (_flow() is not { } flow) return;
            var dialog = new ContentDialog { Title = Words.SigninKitScan(), CloseButtonText = Words.SigninCancel() };
            AutomationProperties.SetAutomationId(dialog, "flow-kit-scan");
            var found = false;
            var camera = QrCamera.OpenFlowKit(flow, SignInView.ScanSaid, shown =>
            {
                Log.Write($"a flow's kit scan {shown.state}");
                if (shown.state == ScanState.Found && !found)
                {
                    found = true;
                    dialog.Hide();
                }
            });
            camera.View.Width = 480;
            camera.View.Height = 360;
            // the camera's picture is the kit: out of capture while it shows, as the sign-in's
            var body = ScreenSecrets.Guard(new StackPanel { Spacing = 12 });
            body.Children.Add(camera.View);
            body.Children.Add(new TextBlock { Text = Words.SigninKitScanHint(), TextWrapping = TextWrapping.Wrap, HorizontalAlignment = HorizontalAlignment.Center });
            dialog.Content = body;
            try
            {
                await Dialogs.Show(_window, dialog);
            }
            finally
            {
                // the scan and its camera end with the dialog, however it closes
                camera.Dispose();
            }
        }
        catch (Exception e)
        {
            Log.Write($"a flow's kit scan failed: {e.GetType().Name}");
        }
    }
}

// ---- The new password's breached-password check (ADR 0151 decision 5): the web's ----

/// <summary>The new password's box with the web's breached-password check: checked when the box is left (*.Check),
/// never while typing; each edit drops the verdict (*.Edited, no text); a hit says so under the box with its count and
/// *Use it anyway* (*.UseAnyway); a check that could not run says that and stops nothing. No range service configured:
/// the core sends no state, and nothing is drawn. Linux's NewPassword draws the same.</summary>
internal sealed class NewPasswordField
{
    private readonly Func<long?> _flow;
    private readonly StackPanel _panel = new() { Spacing = 4 };
    private readonly TextBlock _notice;
    private readonly CheckBox _anyway = new() { Content = Words.PasswordPwnedAnyway(), Visibility = Visibility.Collapsed };
    private bool _drawing;
    /// <summary>Set while this side empties the box: the edit that makes is no person's, and sends no Edited.</summary>
    private bool _forgetting;

    public PasswordBox Box { get; }

    public UIElement View => _panel;

    public NewPasswordField(Func<long?> flow, string id, Func<long, string, Call> check, Func<long, Call> edited, Func<long, bool, Call> anyway)
    {
        _flow = flow;
        Box = FlowPage.Secret(Words.PasswordChangeNew(), id);
        _notice = FlowText.Notice($"{id}-breach");
        AutomationProperties.SetAutomationId(_anyway, $"{id}-anyway");
        Box.PasswordChanged += (_, _) =>
        {
            if (_forgetting)
            {
                if (Box.Password.Length == 0) return;
                _forgetting = false;
            }
            if (_flow() is { } f) Act.Send(edited(f));
        };
        // the check when the box is left, never while typing (the web's blur)
        Box.LostFocus += (_, _) =>
        {
            var typed = Box.Password;
            if (typed.Length > 0 && _flow() is { } f) Act.Send(check(f, typed));
        };
        _anyway.Checked += (_, _) => UseAnyway(anyway, true);
        _anyway.Unchecked += (_, _) => UseAnyway(anyway, false);
        _panel.Children.Add(Box);
        _panel.Children.Add(_notice);
        _panel.Children.Add(_anyway);
    }

    private void UseAnyway(Func<long, bool, Call> anyway, bool on)
    {
        if (_drawing || _flow() is not { } f) return;
        Act.Send(anyway(f, on));
    }

    /// <summary>Draws the check's <paramref name="state"/> (none: no check).</summary>
    public void Draw(BreachState? state)
    {
        var kind = state?.kind ?? BreachKind.Idle;
        var count = state?.count ?? 0;
        switch (kind)
        {
            case BreachKind.Hit:
                FlowText.Say(_notice, Words.PasswordPwnedWarning(count), "SystemFillColorCautionBrush");
                break;
            case BreachKind.Accepted:
                FlowText.Say(_notice, Words.PasswordPwnedWarning(count), "TextFillColorPrimaryBrush");
                break;
            case BreachKind.Unavailable:
                FlowText.Say(_notice, Words.PasswordPwnedUnavailable());
                break;
            default:
                FlowText.Say(_notice, null);
                break;
        }
        _anyway.Visibility = kind is BreachKind.Hit or BreachKind.Accepted ? Visibility.Visible : Visibility.Collapsed;
        _drawing = true;
        _anyway.IsChecked = kind == BreachKind.Accepted;
        _drawing = false;
    }

    /// <summary>The box emptied once the core took the password, sending nothing.</summary>
    public void Forget()
    {
        if (Box.Password.Length == 0) return;
        _forgetting = true;
        Box.Password = "";
    }
}

// ---- Change password (D6) ----

internal sealed class PasswordChangePage : FlowPage
{
    private readonly PasswordBox _current = Secret(Words.PasswordChangeCurrent(), "password-current");
    private readonly NewPasswordField _next;
    private readonly PasswordBox _confirm = Secret(Words.PasswordChangeConfirm(), "password-confirm");
    private readonly FlowKeyControl _key;
    private readonly Button _run = Accent(Words.PasswordChangeSubmit(), "password-submit");
    /// <summary>The form, built once and drawn in place: an update (a check's verdict, a kit read) never takes the focus
    /// out of the box being typed in.</summary>
    private readonly StackPanel _form = new() { Spacing = 12 };
    private readonly ProgressRing _ring = Ring();
    private readonly ContentControl _refusal = new();
    private long? _id;
    private Watch? _watch;
    private PasswordChangeState? _state;

    public PasswordChangePage(Window window, string connection, Action back) : base(window, connection, back, AccountFlow.Password)
    {
        _next = new NewPasswordField(() => _id, "password-new", (flow, typed) => new Call.PasswordChangeCheck { flow = flow, newPassword = typed },
            flow => new Call.PasswordChangeEdited { flow = flow }, (flow, on) => new Call.PasswordChangeUseAnyway { flow = flow, on = on });
        _key = new FlowKeyControl(window, () => _id, Words.PasswordChangeSecretKey(), "password-secret-key", "password") { Changed = Enable };
        foreach (var box in new[] { _current, _next.Box, _confirm }) box.PasswordChanged += (_, _) => Enable();
        _run.Click += (_, _) => Run();
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
        row.Children.Add(_run);
        row.Children.Add(_ring);
        foreach (var c in new UIElement[] { Caption(Words.PasswordChangeSub()), _current, _next.View, _confirm, _key.View, Caption(Words.PasswordChangeSecretKeyHint()), row, _refusal })
            _form.Children.Add(c);
        Draw(null, null, null);
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
            if (Closed || update is not Update.PasswordChange { shown: var s } u) return;
            Guarded("the password change", () => Draw(s, u.kit, u.breach));
        }, e => Log.Write($"a password change update: {e.Message}"));
    }

    private void Enable() => _run.IsEnabled = _state is not { working: true } && _current.Password.Length > 0 && _next.Box.Password.Length > 0
        && _confirm.Password.Length > 0 && _key.Ready;

    private void Draw(PasswordChangeState? s, IntakeState? kit, BreachState? breach)
    {
        _state = s;
        Log.Write($"password change: working {s?.working}, done {s?.done}, refusal {s?.refusal ?? "none"}, kit held {kit?.kit is not null}, breach {breach?.kind}");
        if (s is { done: true })
        {
            ClearBody();
            // the core took them: the boxes emptied
            _current.Password = "";
            _confirm.Password = "";
            _next.Forget();
            _key.Take();
            var done = new StackPanel { Spacing = 6 };
            done.Children.Add(Text(Words.PasswordChangeDone(), "password-done"));
            done.Children.Add(Caption(Words.PasswordChangeDoneNote()));
            Body.Children.Add(Card(done, "password-done-card"));
            var close = Accent(Words.ShareDone(), "password-close");
            close.Click += (_, _) => Back();
            Body.Children.Add(close);
            return;
        }
        if (!Body.Children.Contains(_form))
        {
            ClearBody();
            Body.Children.Add(_form);
        }
        _key.Draw(kit);
        _next.Draw(breach);
        _ring.Visibility = s is { working: true } ? Visibility.Visible : Visibility.Collapsed;
        _refusal.Content = Refusal(s?.refusal);
        Enable();
    }

    /// <summary>The form as typed: the boxes stay until the core took them (a breached new password stops here until
    /// *Use it anyway*, as the web keeps its form).</summary>
    private async void Run()
    {
        if (_id is not { } flow) return;
        var call = new Call.PasswordChangeRun
        {
            flow = flow, currentPassword = _current.Password, newPassword = _next.Box.Password, confirmPassword = _confirm.Password, secretKey = _key.Typed(),
        };
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
    /// <summary>One intake for both halves (the request, the new kit's check): each control draws the flow's kit.</summary>
    private readonly FlowKeyControl _key;
    private readonly Button _request = Accent(Words.EmailChangeSubmit(), "email-submit");
    private readonly PasswordBox _kitPassword = Secret(Words.EmailChangePassword(), "email-kit-password");
    private readonly FlowKeyControl _kitKey;
    private readonly Button _check = Plain(Words.EmailChangeKitCheck(), "email-kit-check");
    private long? _id;
    private Watch? _watch;
    private AddressChangeState? _state;
    private KitSection? _kit;
    /// <summary>Which half asked last (the request, or the kit's check): the one refusal is said under that one.</summary>
    private bool _kitAsked;

    public AddressChangePage(Window window, string connection, Action back) : base(window, connection, back, AccountFlow.Email)
    {
        AutomationProperties.SetAutomationId(_email, "email-new");
        _email.TextChanged += (_, _) => Enable();
        _key = new FlowKeyControl(window, () => _id, Words.EmailChangeSecretKey(), "email-secret-key", "email") { Changed = Enable };
        _kitKey = new FlowKeyControl(window, () => _id, Words.EmailChangeSecretKey(), "email-kit-secret-key", "email-kit") { Changed = Enable };
        foreach (var box in new[] { _password, _kitPassword }) box.PasswordChanged += (_, _) => Enable();
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
            if (Closed || update is not Update.AddressChange { shown: var s } u) return;
            Guarded("the address change", () =>
            {
                _key.Draw(u.kit);
                _kitKey.Draw(u.kit);
                Draw(s);
            });
        }, e => Log.Write($"an address change update: {e.Message}"));
    }

    private void Enable()
    {
        var working = _state is { working: true };
        _request.IsEnabled = !working && _email.Text.Trim().Length > 0 && _password.Password.Length > 0 && _key.Ready;
        _check.IsEnabled = !working && _kitPassword.Password.Length > 0 && _kitKey.Ready;
    }

    private void Draw(AddressChangeState? s)
    {
        _state = s;
        ClearBody();
        if (s?.sentTo is { } sent)
        {
            _password.Password = "";
            Body.Children.Add(Card(Text(Words.EmailChangeSent(sent), "email-sent"), "email-sent-card"));
            DrawKit(s);
            var close = Plain(Words.ShareDone(), "email-close");
            close.Click += (_, _) => Back();
            Body.Children.Add(close);
            Enable();
            return;
        }
        Body.Children.Add(Caption(Words.EmailChangeFormSub()));
        Body.Children.Add(_email);
        Body.Children.Add(_password);
        Body.Children.Add(_key.View);
        Body.Children.Add(Caption(Words.RotationSecretKeyHint()));
        var send = Held(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 });
        send.Children.Add(_request);
        if (s is { working: true }) send.Children.Add(Ring());
        Body.Children.Add(send);
        if (!_kitAsked && Refusal(s?.refusal) is { } r) Body.Children.Add(r);
        DrawKit(s);
        Enable();
    }

    /// <summary>"Print a new Emergency Kit" — only where the core offers it: once this sign-in took the new address, never
    /// right after the request (the confirm is still to come, inbox #1010).</summary>
    private void DrawKit(AddressChangeState? s)
    {
        if (s is not { kitOffered: true }) return;
        Body.Children.Add(Heading(Words.EmailChangeKitTitle()));
        if (_kit is { } kit)
        {
            Body.Children.Add(kit.View);
            return;
        }
        Body.Children.Add(Caption(Words.EmailChangeKitSub()));
        Body.Children.Add(_kitPassword);
        Body.Children.Add(_kitKey.View);
        var row = Held(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 });
        row.Children.Add(_check);
        if (s.working) row.Children.Add(Ring());
        Body.Children.Add(row);
        if (_kitAsked && Refusal(s.refusal) is { } refused) Body.Children.Add(refused);
    }

    private async void Request()
    {
        if (_id is not { } flow) return;
        _kitAsked = false;
        var call = new Call.AddressChangeRequest { flow = flow, newEmail = _email.Text.Trim(), password = _password.Password, secretKey = _key.Take() };
        _request.IsEnabled = false;
        await Ask("AddressChange.Request", call);
    }

    /// <summary>"Check and make the kit": the password and the Secret Key checked by the core, then the kit's step.</summary>
    private async void MakeKit()
    {
        if (_id is not { } flow) return;
        _kitAsked = true;
        var call = new Call.AddressChangeKit { flow = flow, password = _kitPassword.Password, secretKey = _kitKey.Take() };
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
/// Once the keys are replaced the bridge opens the tenant anew (its keys were the old identity's); the done rotation
/// outlives that (inbox #984), so its last step — each space's new lock, the links to renew — is drawn in place, and the
/// Settings page under it reads anew on Back. A sign-out ends it: what it came to is told over the window then.</summary>
internal sealed class KeyRotationPage : FlowPage
{
    private readonly PasswordBox _password = Secret(Words.RotationPassword(), "rotation-password");
    private readonly FlowKeyControl _key;
    private readonly ToggleSwitch _choose = new() { Header = Words.RotationNewPassword() };
    private readonly NewPasswordField _next;
    private readonly PasswordBox _confirm = Secret(Words.PasswordChangeConfirm(), "rotation-confirm-password");
    private readonly Button _continue = Accent(Words.RotationNext(), "rotation-next");
    /// <summary>The gate, built once and drawn in place: an update (a check's verdict, a kit read) never takes the focus
    /// out of the box being typed in.</summary>
    private readonly StackPanel _gate = new() { Spacing = 12 };
    private readonly ContentControl _gateRefusal = new();
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
        _key = new FlowKeyControl(window, () => _id, Words.RotationSecretKey(), "rotation-secret-key", "rotation") { Changed = Enable };
        _next = new NewPasswordField(() => _id, "rotation-new-password", (flow, typed) => new Call.KeyRotationCheck { flow = flow, newPassword = typed },
            flow => new Call.KeyRotationEdited { flow = flow }, (flow, on) => new Call.KeyRotationUseAnyway { flow = flow, on = on });
        foreach (var box in new[] { _password, _next.Box, _confirm }) box.PasswordChanged += (_, _) => Enable();
        _continue.Click += (_, _) => Prove();
        foreach (var c in new UIElement[]
                 {
                     Caption(Words.RotationSub()), Heading(Words.RotationAfterTitle()),
                     Facts([Words.RotationFactSharedSpaces(), Words.RotationFactSignsOut(), Words.RotationFactNewKit(), Words.RotationFactPassword(),
                         Words.RotationFactNotRepair(), Words.RotationStoreWiped()], "rotation-facts"),
                     _password, _key.View, Caption(Words.RotationSecretKeyHint()), _choose, _next.View, _confirm, _continue, _gateRefusal,
                 })
            _gate.Children.Add(c);
        _publish.Click += (_, _) => Publish();
        Draw(null, null, null);
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
            if (update is not Update.KeyRotation { shown: var s } u) return;
            if (s is null)
            {
                // closed with the tenant the bridge opened anew after the publish: what it came to is told once
                if (_state is { done: true } last) TellDone(last);
                return;
            }
            if (Closed) return;
            Guarded("the rotation", () => Draw(s, u.kit, u.breach));
        }, e => Log.Write($"a rotation update: {e.Message}"));
    }

    private void Enable()
    {
        var newPassword = _state?.newPassword ?? true;
        _continue.IsEnabled = _state is { step: Step.Gate } && _password.Password.Length > 0 && _key.Ready
            && (!newPassword || (_next.Box.Password.Length > 0 && _confirm.Password.Length > 0));
        _publish.IsEnabled = _state is { step: Step.Kit } && _kit is { Ready: true };
    }

    private void Draw(KeyRotationState? s, IntakeState? kit, BreachState? breach)
    {
        _state = s;
        Log.Write($"rotation: step {s?.step}, done {s?.done}, resumed {s?.resumed}, {s?.scopes.Count ?? 0} scopes, refusal {s?.refusal ?? "none"}, kit held {kit?.kit is not null}, breach {breach?.kind}");
        _key.Draw(kit);
        _next.Draw(s is { newPassword: true } ? breach : null);
        if (s is { step: Step.Gate })
        {
            if (!Body.Children.Contains(_gate))
            {
                ClearBody();
                Body.Children.Add(_gate);
            }
            Gate(s);
            Enable();
            return;
        }
        ClearBody();
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
        _drawing = true;
        _choose.IsOn = s.newPassword;
        _drawing = false;
        _next.View.Visibility = s.newPassword ? Visibility.Visible : Visibility.Collapsed;
        _confirm.Visibility = s.newPassword ? Visibility.Visible : Visibility.Collapsed;
        _gateRefusal.Content = Refusal(s.refusal);
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
        // the links made before, which stay open until renewed (the web's decision 13): the person's own (inbox #977)
        if (s.links.Count == 0) return;
        Body.Children.Add(Heading(Words.RotationLinksTitle()));
        foreach (var link in s.links)
        {
            var row = new StackPanel { Spacing = 4 };
            row.Children.Add(Text(link.node?.name ?? Words.RotationLinkNameUnavailable(), $"rotation-link:{link.shareId}"));
            var space = link.scopeName ?? (link.scopeWord is { } word ? Said.Key(word) : Words.RotationScopeNameUnavailable());
            row.Children.Add(Caption(space));
            if (link.node is { } node)
            {
                var renew = Plain(link.upload ? Words.RotationLinkOpen() : Words.RotationRenew(), $"rotation-renew:{link.shareId}");
                // read anew once its dialog closed: a renewed link drops out
                renew.Click += (_, _) => ShareDialog.Open(Window, node, () =>
                {
                    if (!Closed && _id is { } flow) Act.Send(new Call.KeyRotationRefreshLinks { flow = flow });
                });
                row.Children.Add(renew);
                if (link.upload) row.Children.Add(Caption(Words.RotationLinkUpload()));
            }
            else if (link.tenant is { } tenant) row.Children.Add(Caption(Words.RotationLinkElsewhere(tenant)));
            Body.Children.Add(row);
        }
    }

    /// <summary>Whether this page is a done rotation, which stays on screen across the tenant the bridge opened anew.</summary>
    public bool KeptAcrossReopen => _state is { done: true };

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
        // the gate as typed: its boxes stay until the kit step opened (a breached new password stops here until *Use it anyway*)
        var call = new Call.KeyRotationProve
        {
            flow = flow, password = _password.Password, secretKey = _key.Typed(),
            newPassword = _state?.newPassword == true ? _next.Box.Password : "", confirmPassword = _state?.newPassword == true ? _confirm.Password : "",
        };
        _continue.IsEnabled = false;
        var answer = await Ask("KeyRotation.Prove", call);
        if (answer is Answer.KitOpened { kit: var kit } && !Closed)
        {
            // the core took them: the gate's boxes emptied
            _password.Password = "";
            _key.Take();
            _next.Forget();
            _confirm.Password = "";
            _kit = new KitSection(Window, kit, ownsClose: false) { Changed = Enable };
            if (_state is { } s) Draw(s, null, null);
        }
    }

    private async void Publish()
    {
        if (_id is not { } flow || _kit is not { Ready: true }) return;
        if (!await Dialogs.Confirm(Window, "rotation-confirm", Words.RotationConfirmTitle(), Words.RotationConfirmBody(), Words.RotationAction())) return;
        if (Closed) return;
        _publish.IsEnabled = false;
        // the done step is drawn in place (the flow outlives the tenant's reopen, inbox #984): nothing told over the window
        await Ask("KeyRotation.Run", new Call.KeyRotationRun { flow = flow });
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
        ClearBody();
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
        var row = Held(new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 });
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
