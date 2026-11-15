// The account's Settings (uc.account.sessions; the web's Settings hub, its SessionsPanel the reference), Fluent's way,
// as Windows' own Settings draws a page: the pane's Settings item opens it in the shell's NavigationView content (WinUI's
// guideline for the settings item), a column of cards under the web's section titles —
//
// - Account: the address this account signs in with, and whether the server holds it confirmed;
// - This device: its name in the account's device register, renamed in a TextBox with its button; how it stays signed
//   in and its change (Manage's: each other level, "Back to your password" asking in place) and "Stop trusting this device";
// - Devices and sessions: every session family, this device's first and marked, named from the register or by its dates,
//   its sign-out (and, where the core offers it, "Sign out and remove saved key") behind the card's More, every step asked
//   first (Dialogs.Confirm); "Sign out everywhere else";
// - Add a device (only while the core offers it, addDevice: core.device-linking): "Show a code" and, where there is a
//   camera, "Scan a code" — each a ceremony of DeviceLinkDialog over the window;
// - Devices with a saved key: each removable, and "Remove every saved key except this device's";
// - Sign out of this account here.
//
// Theme and language follow the system (D3): no card for them. Every state is the core's (Settings.Open,
// Watched.Settings: SettingsShown), every action one call; why one did not come through is the state's refusal, said in an
// InfoBar. Linux's account_settings.rs draws the same.
using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

internal sealed class AccountSettingsPage : IDisposable
{
    private readonly Window _window;
    private readonly Action _closedByCore;
    private long? _id;
    private Watch? _watch;
    private bool _disposed;
    private readonly StackPanel _sections = new() { Spacing = 4, MaxWidth = 1000, Padding = new Thickness(24, 8, 24, 24) };
    private readonly InfoBar _refused = new() { IsOpen = false, IsClosable = true, Severity = InfoBarSeverity.Error };
    /// <summary>This device's name, kept across redraws: what is typed stays while the cards around it are drawn anew.</summary>
    private readonly TextBox _name = new() { MinWidth = 240 };
    private readonly Button _rename = new() { Content = Words.NodeRename() };
    private string? _label;
    private string? _refusal;
    /// <summary>"Back to your password" asks in place, kept across the redraws; and what its last try was told.</summary>
    private bool _asking;
    private string? _told;
    private SettingsShown? _last;
    /// <summary>The account this page is of (none for the debug harness's), for the device link's calls; whether a camera
    /// is there for "Scan a code" (ADR 0141 decision 4: no camera, no entry).</summary>
    private string? _connection;
    private bool _camera;

    public UIElement Page { get; }

    private AccountSettingsPage(Window window, Action closedByCore)
    {
        _window = window;
        _closedByCore = closedByCore;
        AutomationProperties.SetAutomationId(_refused, "settings-refused");
        AutomationProperties.SetName(_name, Words.AccountSettingsRename());
        AutomationProperties.SetAutomationId(_name, "settings-name");
        AutomationProperties.SetAutomationId(_rename, "settings-rename");
        _rename.Click += (_, _) => Rename();
        _name.KeyDown += (_, e) =>
        {
            if (e.Key != Windows.System.VirtualKey.Enter) return;
            e.Handled = true;
            Rename();
        };
        // a capped column inside a stretching Grid, never straight in the ScrollViewer (it sat off centre and clipped)
        var column = new Grid();
        column.Children.Add(_sections);
        var scroll = new ScrollViewer { Content = column, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        AutomationProperties.SetAutomationId(scroll, "settings-page");
        Page = scroll;
    }

    /// <summary>Opens <paramref name="connection"/>'s Settings in the core; <paramref name="closedByCore"/> runs once the
    /// core closed it (its tenant closed, its account signed out here).</summary>
    public static AccountSettingsPage Open(Window window, string connection, Action closedByCore)
    {
        var page = new AccountSettingsPage(window, closedByCore) { _connection = connection };
        page.OfferScan();
        page.OpenIn(connection);
        return page;
    }

    /// <summary>The debug harness's door (DevSettings): <paramref name="shown"/> drawn as the core would send it, with no
    /// page in the core (every action is then refused, and said so in the log).</summary>
    public static AccountSettingsPage Showing(Window window, SettingsShown shown)
    {
        var page = new AccountSettingsPage(window, () => { });
        page.Draw(shown);
        return page;
    }

    private async void OfferScan()
    {
        try
        {
            _camera = await QrCamera.PresentAsync();
            Log.Write(_camera ? "Settings' link scan offered" : "Settings' link scan not offered: no camera");
            if (!_disposed && _last is { } last) Draw(last);
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"the camera's check failed: {e.GetType().Name}");
        }
    }

    /// <summary>Opens the device link <paramref name="make"/> builds for this page's account (ADR 0059).</summary>
    private async void Link(Func<string, Call> make)
    {
        try
        {
            if (_connection is not { } connection)
            {
                Log.Write("settings add a device: no account in the core");
                return;
            }
            await DeviceLinkDialog.Show(_window, make(connection));
        }
        catch (Exception e)
        {
            Log.Write($"veyq-link: the ceremony failed: {e.GetType().Name}");
        }
    }

    private async void OpenIn(string connection)
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.SettingsOpen { connection = connection });
            if (answer is not Answer.SettingsOpened { settings: var id })
            {
                Log.Write($"Settings.Open answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                return;
            }
            if (_disposed)
            {
                Act.Send(new Call.SettingsClose { settings = id });
                return;
            }
            _id = id;
            Log.Write("settings open");
            _watch = Core.Watch(new Watched.Settings { settings = id }, update =>
            {
                if (_disposed || update is not Update.Settings { shown: var s }) return;
                try
                {
                    if (s is null)
                    {
                        _id = null;
                        _closedByCore();
                    }
                    else Draw(s);
                }
                catch (Exception e)
                {
                    // out of a watch's callback it would end the app in combase, with nothing in the log
                    Log.Write($"the settings did not draw: {e.GetType().Name} 0x{e.HResult:X8}: {e.Message}");
                }
            }, e => Log.Write($"a settings update: {e.Message}"));
        }
        catch (Exception e)
        {
            Log.Write($"Settings.Open failed: {e.GetType().Name}");
        }
    }

    public void Dispose()
    {
        _disposed = true;
        _watch?.Dispose();
        _watch = null;
        if (_id is { } id) Act.Send(new Call.SettingsClose { settings = id });
        _id = null;
    }

    /// <summary>Sends what <paramref name="make"/> builds for this page's id, answered once it ended; returns the answer
    /// (null without a page in the core).</summary>
    private async System.Threading.Tasks.Task<Answer?> Send(string what, Func<long, Call> make)
    {
        if (_id is not { } id)
        {
            Log.Write($"settings {what}: no page in the core");
            return null;
        }
        try
        {
            var (answer, _) = await Core.CallAsync(make(id));
            Log.Write($"settings {what}: {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
            return answer;
        }
        catch (Exception e)
        {
            Log.Write($"settings {what} failed: {e.GetType().Name}");
            return null;
        }
    }

    private async void Do(string what, Func<long, Call> make) => await Send(what, make);

    /// <summary>Asks first (a destructive answer), then sends.</summary>
    private async void AskThen(string id, string title, string question, string confirm, string what, Func<long, Call> make)
    {
        try
        {
            if (await Dialogs.Confirm(_window, id, title, question, confirm)) await Send(what, make);
        }
        catch (Exception e)
        {
            Log.Write($"settings {what} failed: {e.GetType().Name}");
        }
    }

    private void Rename()
    {
        var label = _name.Text;
        if (label.Trim().Length == 0) return;
        Do("rename", id => new Call.SettingsRename { settings = id, label = label });
    }

    /// <summary>A change of this device's trust; what it was refused for is said under the device's card, a cancelled
    /// Hello prompt says nothing.</summary>
    private async void Change(string what, Func<long, Call> make)
    {
        var answer = await Send(what, make);
        switch (answer)
        {
            case null:
                return;
            case Answer.Done:
                (_asking, _told) = (false, null);
                break;
            case Answer.Refused { why: Why.TrustCancelled }:
                _told = null;
                break;
            case Answer.Refused { why: Why.WrongPassword }:
                _told = Words.ManageWrongPassword();
                break;
            default:
                (_asking, _told) = (false, Words.TrustFailed());
                break;
        }
        if (_last is { } last) Draw(last);
    }

    private static Brush Brush(string key) => (Brush)Application.Current.Resources[key];

    private static TextBlock Caption(string text) => new()
    {
        Text = text,
        Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
        Foreground = Brush("TextFillColorSecondaryBrush"),
        TextWrapping = TextWrapping.Wrap,
    };

    private static TextBlock Heading(string text)
    {
        var t = new TextBlock { Text = text, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], Margin = new Thickness(0, 20, 0, 4) };
        AutomationProperties.SetHeadingLevel(t, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        return t;
    }

    /// <summary>A card as Windows' Settings draws one: its icon, its title and lines, and its controls at the end.</summary>
    private static Border Card(string glyph, string title, IEnumerable<string> lines, string automationId, params UIElement[] end)
    {
        var grid = new Grid { ColumnSpacing = 16, MinHeight = 44 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var icon = new FontIcon { Glyph = glyph, FontSize = 20, VerticalAlignment = VerticalAlignment.Center };
        grid.Children.Add(icon);
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        text.Children.Add(new TextBlock { Text = title, TextWrapping = TextWrapping.Wrap });
        foreach (var line in lines) text.Children.Add(Caption(line));
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);
        var controls = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
        foreach (var e in end) controls.Children.Add(e);
        Grid.SetColumn(controls, 2);
        grid.Children.Add(controls);
        var card = new Border
        {
            Child = grid,
            Padding = new Thickness(16, 12, 16, 12),
            CornerRadius = new CornerRadius(4),
            Background = Brush("CardBackgroundFillColorDefaultBrush"),
            BorderBrush = Brush("CardStrokeColorDefaultBrush"),
            BorderThickness = new Thickness(1),
        };
        AutomationProperties.SetAutomationId(card, automationId);
        return card;
    }

    private static Button TextButton(string words, string id)
    {
        var b = new Button { Content = words };
        AutomationProperties.SetAutomationId(b, id);
        return b;
    }

    private void Draw(SettingsShown s)
    {
        _last = s;
        // the name's box and button are moved into each redraw: off their old card first
        (_name.Parent as Panel)?.Children.Remove(_name);
        (_rename.Parent as Panel)?.Children.Remove(_rename);
        _sections.Children.Clear();
        if (_refusal != s.refusal)
        {
            _refusal = s.refusal;
            _refused.Message = s.refusal is { } why ? Said.Key(why) : "";
            _refused.IsOpen = s.refusal is not null;
        }
        _sections.Children.Add(_refused);

        // Account
        _sections.Children.Add(Heading(Words.AccountSettingsAccount()));
        var confirmed = s.emailConfirmed switch
        {
            true => Words.AccountSettingsEmailConfirmed(),
            false => Words.AccountSettingsEmailUnconfirmed(),
            null => null,
        };
        _sections.Children.Add(Card("", Words.AccountSettingsEmail(), new[] { s.email, confirmed }.OfType<string>(), "settings-email"));

        // This device
        _sections.Children.Add(Heading(Words.AccountSettingsThisDevice()));
        if (_label != s.device)
        {
            // the name is set from the state when it changed, never over what is being typed
            if (_label is null || _name.FocusState == FocusState.Unfocused) _name.Text = s.device;
            _label = s.device;
        }
        _sections.Children.Add(Card("", Words.AccountSettingsRename(), new[] { Words.AccountSettingsRenameHint() }, "settings-device-name", _name, _rename));
        _sections.Children.Add(Trust(s));

        // Add a device
        if (s.addDevice)
        {
            _sections.Children.Add(Heading(Words.AccountSettingsAddDevice()));
            _sections.Children.Add(Caption(Words.AccountSettingsAddDeviceSub()));
            var show = TextButton(Words.LinkShow(), "settings-link-show");
            show.Click += (_, _) => Link(connection => new Call.LinkShowForNewDevice { connection = connection });
            _sections.Children.Add(Card("\uED14", Words.LinkShow(), new[] { Words.LinkIntro() }, "settings-link-show-card", show));
            if (_camera)
            {
                var scan = TextButton(Words.LinkScan(), "settings-link-scan");
                scan.Click += (_, _) => Link(connection => new Call.LinkScanSignInCode { connection = connection });
                _sections.Children.Add(Card("\uE722", Words.LinkScan(), new[] { Words.LinkScanIntro() }, "settings-link-scan-card", scan));
            }
        }

        // Devices and sessions
        _sections.Children.Add(Heading(Words.AccountSettingsSessions()));
        _sections.Children.Add(Caption(Words.AccountSettingsSessionsSub()));
        if (s.registerRefusal is { } names)
        {
            var bar = new InfoBar { IsOpen = true, IsClosable = false, Severity = InfoBarSeverity.Warning, Message = Said.Key(names) };
            AutomationProperties.SetAutomationId(bar, "settings-names-missing");
            _sections.Children.Add(bar);
        }
        if (s.families is not { } families)
        {
            var loading = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            loading.Children.Add(new ProgressRing { IsActive = true, Width = 16, Height = 16 });
            loading.Children.Add(Caption(Words.AccountSettingsSessionsLoading()));
            _sections.Children.Add(loading);
        }
        else
        {
            foreach (var f in families) _sections.Children.Add(Family(s, f));
            if (s.hasOthers)
            {
                var others = TextButton(Words.AccountSettingsRevokeOthers(), "settings-revoke-others");
                others.Margin = new Thickness(0, 8, 0, 0);
                others.Click += (_, _) => AskThen("settings-revoke-others-ask", Words.AccountSettingsRevokeOthers(), Words.AccountSettingsRevokeOthersConfirm(),
                    Words.AccountSettingsRevokeOthers(), "sign out every other", id => new Call.SettingsSignOutOthers { settings = id });
                _sections.Children.Add(others);
            }
        }

        // Devices with a saved key
        if (s.keptDevices.Count > 0 || s.otherSavedKeys)
        {
            _sections.Children.Add(Heading(Words.AccountSettingsSavedKeys()));
            _sections.Children.Add(Caption(Words.AccountSettingsSavedKeysSub()));
            foreach (var d in s.keptDevices)
            {
                var remove = TextButton(Words.AccountSettingsRemoveKey(), $"settings-remove-key:{d.label}");
                var (device, label) = (d.deviceId, d.label);
                remove.Click += (_, _) => AskThen("settings-remove-key-ask", Words.AccountSettingsRemoveKey(), Words.AccountSettingsRemoveKeyConfirm(label),
                    Words.AccountSettingsRemoveKey(), "remove a saved key", id => new Call.SettingsRemoveSavedKey { settings = id, device = device });
                _sections.Children.Add(Card("", d.label, new[] { Words.AccountSettingsSavedKeyRow() }, $"settings-kept:{d.label}", remove));
            }
            if (s.otherSavedKeys)
            {
                var others = TextButton(Words.AccountSettingsRemoveOtherKeys(), "settings-remove-other-keys");
                others.Margin = new Thickness(0, 8, 0, 0);
                others.Click += (_, _) => AskThen("settings-remove-other-keys-ask", Words.AccountSettingsRemoveOtherKeys(), Words.AccountSettingsRemoveOtherKeysConfirm(),
                    Words.AccountSettingsRemoveOtherKeys(), "remove every other saved key", id => new Call.SettingsRemoveOtherSavedKeys { settings = id });
                _sections.Children.Add(others);
            }
        }

        // Sign out
        _sections.Children.Add(Heading(Words.DrawerSignOut()));
        var signOut = TextButton(Words.DrawerSignOut(), "settings-sign-out");
        signOut.Click += (_, _) => Do("sign out here", id => new Call.SettingsSignOutHere { settings = id });
        _sections.Children.Add(signOut);
    }

    /// <summary>How this device stays signed in, as Manage says it, with its changes and "Stop trusting this device".</summary>
    private UIElement Trust(SettingsShown s)
    {
        var panel = new StackPanel { Spacing = 8 };
        var buttons = new List<UIElement>();
        foreach (var change in TrustWords.Changes(s.trusted, s.level, s.levels))
        {
            var button = TextButton(change is { } level ? TrustWords.Level(level).Title : Words.ManagePasswordTrust(), change switch
            {
                StaySignedIn.Presence => "settings-presence",
                StaySignedIn.Login => "settings-login",
                _ => "settings-password",
            });
            if (change is { } l)
            {
                ToolTipService.SetToolTip(button, TrustWords.Level(l).Hint);
                button.Click += (_, _) => Change($"stay signed in {l}", id => new Call.SettingsStaySignedIn { settings = id, level = l });
            }
            else
            {
                button.Click += (_, _) =>
                {
                    (_asking, _told) = (true, null);
                    if (_last is { } last) Draw(last);
                };
            }
            buttons.Add(button);
        }
        if (s.trusted is not null)
        {
            var forget = TextButton(Words.ManageForgetDevice(), "settings-forget");
            // asked first, as the phones ask
            forget.Click += (_, _) => AskThen("settings-forget-ask", Words.AccountSettingsUntrustConfirmTitle(), Words.AccountSettingsUntrustConfirm(),
                Words.ManageForgetDevice(), "stop trusting", id => new Call.SettingsStopTrusting { settings = id });
            buttons.Add(forget);
        }
        var line = TrustWords.Trusted(s.trusted, s.level) ?? Words.AccountSettingsThisDevice();
        panel.Children.Add(Card("", line, [], "settings-trust"));
        if (buttons.Count > 0)
        {
            var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            foreach (var b in buttons) row.Children.Add(b);
            panel.Children.Add(row);
        }
        if (_asking && s.trusted == Method.DeviceKey) panel.Children.Add(PasswordAsk());
        else if (_told is { } told) panel.Children.Add(Told(told));
        return panel;
    }

    private static TextBlock Told(string text)
    {
        var told = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, Foreground = Brush("SystemFillColorCriticalBrush") };
        AutomationProperties.SetAutomationId(told, "settings-told");
        AutomationProperties.SetLiveSetting(told, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
        return told;
    }

    /// <summary>"Back to your password"'s field, in place, as Manage's: the core checks it first.</summary>
    private UIElement PasswordAsk()
    {
        var panel = new StackPanel { Spacing = 8 };
        panel.Children.Add(Caption(Words.ManagePasswordTrustHint()));
        var password = ScreenSecrets.Guard(new PasswordBox { Header = Words.SigninPassword(), MaxWidth = 360, HorizontalAlignment = HorizontalAlignment.Left });
        AutomationProperties.SetAutomationId(password, "settings-password-field");
        if (DevLogin.TakePasswordTrust() is { } dev) password.Password = dev;
        panel.Children.Add(password);
        if (_told is { } told) panel.Children.Add(Told(told));
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var confirm = new Button { Content = Words.ManagePasswordTrust(), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetAutomationId(confirm, "settings-password-confirm");
        void SendIt()
        {
            if (password.Password.Length == 0) return;
            var typed = password.Password;
            password.Password = "";
            Change("back to the password", id => new Call.SettingsPasswordTrust { settings = id, password = typed });
        }
        confirm.Click += (_, _) => SendIt();
        password.KeyDown += (_, e) =>
        {
            if (e.Key != Windows.System.VirtualKey.Enter) return;
            e.Handled = true;
            SendIt();
        };
        var cancel = new Button { Content = Words.SigninCancel() };
        AutomationProperties.SetAutomationId(cancel, "settings-password-cancel");
        cancel.Click += (_, _) =>
        {
            (_asking, _told) = (false, null);
            if (_last is { } last) Draw(last);
        };
        buttons.Children.Add(confirm);
        buttons.Children.Add(cancel);
        panel.Children.Add(buttons);
        return panel;
    }

    /// <summary>One family's card: its device's name (this device's own, else the register's, else "Signed-in device"),
    /// its dates, "You are here" on this device's, and its actions behind More.</summary>
    private UIElement Family(SettingsShown s, SettingsFamily f)
    {
        var title = f.label ?? (f.current ? s.device : Words.AccountSettingsOtherDevice());
        // opened under its button, its right edge on the button's: a wide entry stays inside the window
        var menu = new MenuFlyout { Placement = Microsoft.UI.Xaml.Controls.Primitives.FlyoutPlacementMode.BottomEdgeAlignedRight };
        var revokeWords = f.current ? Words.AccountSettingsRevokeSelf() : Words.AccountSettingsRevoke();
        var revoke = new MenuFlyoutItem { Text = revokeWords };
        AutomationProperties.SetAutomationId(revoke, $"settings-revoke:{f.id}");
        var family = f.id;
        revoke.Click += (_, _) => AskThen("settings-revoke-ask", revokeWords, f.current ? Words.AccountSettingsRevokeSelfConfirm() : Words.AccountSettingsRevokeConfirm(),
            revokeWords, "sign out", id => new Call.SettingsSignOut { settings = id, family = family });
        menu.Items.Add(revoke);
        if (f.removesKey)
        {
            var escalate = new MenuFlyoutItem { Text = Words.AccountSettingsEscalate() };
            AutomationProperties.SetAutomationId(escalate, $"settings-escalate:{f.id}");
            escalate.Click += (_, _) => AskThen("settings-escalate-ask", Words.AccountSettingsEscalate(), Words.AccountSettingsEscalateConfirm(),
                Words.AccountSettingsEscalateAccept(), "sign out and remove the saved key", id => new Call.SettingsSignOutAndRemoveKey { settings = id, family = family });
            menu.Items.Add(escalate);
        }
        var more = new Button { Content = new FontIcon { Glyph = "", FontSize = 16 }, Flyout = menu };
        ToolTipService.SetToolTip(more, title);
        AutomationProperties.SetName(more, title);
        AutomationProperties.SetAutomationId(more, $"settings-family-more:{f.id}");
        var end = new List<UIElement>();
        if (f.current)
        {
            var here = Caption(Words.AccountSettingsCurrent());
            here.VerticalAlignment = VerticalAlignment.Center;
            end.Add(here);
        }
        end.Add(more);
        return Card(f.current ? "" : "", title, Dates(f), $"settings-family:{f.id}", end.ToArray());
    }

    /// <summary>A family's dates as its card says them: when it signed in and when it was last used, the ones the server sent.</summary>
    public static IEnumerable<string> Dates(SettingsFamily f)
    {
        if (f.createdAt is { } created) yield return Words.AccountSettingsSignedIn(Said.Moment(created));
        if (f.lastUsedAt is { } used) yield return Words.AccountSettingsLastUsed(Said.Moment(used));
    }
}
