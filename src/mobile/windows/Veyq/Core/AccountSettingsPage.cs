// The account's Settings (uc.account.sessions; the web's Settings hub, its SessionsPanel the reference), Fluent's way,
// as Windows' own Settings draws a page: the pane's Settings item opens it in the shell's NavigationView content (WinUI's
// guideline for the settings item), a column of cards under the web's section titles —
//
// - Account: the address this account signs in with, and whether the server holds it confirmed;
// - Your profile, Change password, Email address (ADR 0148 decision 10, the web's order): each a card whose button opens
//   the flow as a page of its own in this frame (AccountFlows: Settings › the flow, Back returning here);
// - This device: its name in the account's device register, renamed in a TextBox with its button; how it stays signed
//   in and its change (Manage's: each other level, "Back to your password" asking in place) and "Stop trusting this device";
// - Devices and sessions: every session family, this device's first and marked, named from the register or by its dates,
//   its Rename where the register names its device (the web's renameDeviceOfFamily, ADR 0148 decision 1: this device's
//   through the name above, any other's through Settings.RenameFamily), its sign-out (and, where the core offers it,
//   "Sign out and remove saved key") behind the card's More, every step asked first (Dialogs.Confirm); "Sign out
//   everywhere else";
// - Add a device (only while the core offers it, addDevice: core.device-linking): "Show a code" and, where there is a
//   camera, "Scan a code" — each a ceremony of DeviceLinkDialog over the window;
// - Devices with a saved key: each removable, and "Remove every saved key except this device's";
// - Storage on this device (ADR 0140 decision 10), as Windows' own Settings > Storage draws sizes: the limit of what is
//   kept of content viewed — a free size in a TextBox, its unit or Off in a ComboBox, "Set limit" —, the sizes the
//   automatic tier and the items available offline take and the volume's free space, "Clear cache", "Remove all offline
//   content" (asked first, the core's askingRemoveAll), and a card per item available offline with its status, its size
//   and its Remove; followed live while the page shows (Watched.SettingsStorageLive). Where this device keeps no store of
//   the account, the section says why (storageUnavailable);
// - Secure your account (replace the keys) and Erase account, each opening its flow as the profile's does;
// - Sign out of this account here.
//
// Opened from a search's settings result (ADR 0146 decision 9), the page scrolls to that section: each section's
// heading is kept by its SettingsSection.id (`account profile password email device add-device sessions saved-keys
// storage maps security erase`).
//
// Theme and language follow the system (D3): no card for them. Every state is the core's (Settings.Open,
// Watched.Settings: SettingsShown), every action one call; why one did not come through is the state's refusal, said in an
// InfoBar. Linux's account_settings.rs draws the same.

namespace Veyq.Core;

using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

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
    /// <summary>The panel the name's box and button were last put in: they are taken out of it by name, never through their
    /// Parent, which reads null while that card still loads — two draws in a row (a sign-out's) then added them to the next
    /// card while still the old one's children, and WinUI threw 0x800F1000 (measured 2026-11-15).</summary>
    private Panel? _nameHolder;
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
    /// <summary>The storage limit's free size field and its unit (or Off, the last entry), kept across redraws as the
    /// name's box is; the panel they were last put in; the field as drawn last; set while a redraw selects the unit.</summary>
    private readonly TextBox _limit = new() { MinWidth = 96, InputScope = new Microsoft.UI.Xaml.Input.InputScope { Names = { new Microsoft.UI.Xaml.Input.InputScopeName(Microsoft.UI.Xaml.Input.InputScopeNameValue.Number) } } };
    private readonly ComboBox _unit = new() { MinWidth = 88 };
    private readonly Button _setLimit = new() { Content = Words.AccountSettingsStorageLimitSet() };
    private Panel? _limitHolder;
    private LimitField? _limitDrawn;
    private IReadOnlyList<LimitUnit> _units = [];
    private bool _drawing;
    /// <summary>The storage section followed live while the page shows; "Remove all offline content"'s question asked.</summary>
    private Watch? _storageWatch;
    private bool _removeAllAsked;

    /// <summary>The page: its sections, or the account's flow open over them (AccountFlows).</summary>
    public UIElement Page => _host;

    private readonly Grid _host = new();
    private readonly ScrollViewer _scroll;
    /// <summary>The account's flow shown in place of the sections, while one is open.</summary>
    private FlowPage? _flow;
    /// <summary>Each section's heading by its SettingsSection.id, as the last draw built them.</summary>
    private readonly Dictionary<string, FrameworkElement> _anchors = [];
    /// <summary>The section a search's result asked for, scrolled to once the page drew; and the tries that wait for it.</summary>
    private string? _at;
    private Microsoft.UI.Dispatching.DispatcherQueueTimer? _scrolling;

    /// <summary>Settings → Map services, followed while the page shows.</summary>
    private readonly MapServicesSection _maps = new();

    private AccountSettingsPage(Window window, Action closedByCore)
    {
        _window = window;
        _closedByCore = closedByCore;
        AutomationProperties.SetAutomationId(_refused, "settings-refused");
        AutomationProperties.SetName(_name, Words.AccountSettingsRename());
        AutomationProperties.SetAutomationId(_name, "settings-name");
        AutomationProperties.SetAutomationId(_rename, "settings-rename");
        _rename.Click += (_, _) => Rename();
        AutomationProperties.SetName(_limit, Words.AccountSettingsStorageLimit());
        AutomationProperties.SetAutomationId(_limit, "settings-storage-limit");
        AutomationProperties.SetName(_unit, Words.AccountSettingsStorageLimit());
        AutomationProperties.SetAutomationId(_unit, "settings-storage-unit");
        AutomationProperties.SetAutomationId(_setLimit, "settings-storage-limit-set");
        _setLimit.Click += (_, _) => SetLimit();
        _limit.KeyDown += (_, e) =>
        {
            if (e.Key != Windows.System.VirtualKey.Enter) return;
            e.Handled = true;
            SetLimit();
        };
        // Off chosen turns the automatic tier off at once; a unit waits for "Set limit"
        _unit.SelectionChanged += (_, _) =>
        {
            if (_drawing || _unit.SelectedIndex != _units.Count) return;
            Do("storage limit off", id => new Call.SettingsOfflineLimitOff { settings = id });
        };
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
        _scroll = scroll;
        _host.Children.Add(scroll);
    }

    /// <summary>Opens <paramref name="flow"/> as a page in this frame over the sections (one at a time); Back returns.</summary>
    public void OpenFlow(AccountFlow flow)
    {
        if (_disposed) return;
        if (_connection is not { } connection)
        {
            Log.Write($"settings {AccountFlows.Id(flow)}: no account in the core");
            return;
        }
        CloseFlow();
        Log.Write($"settings: the {AccountFlows.Id(flow)} flow opened");
        _flow = AccountFlows.Open(flow, _window, connection, () =>
        {
            CloseFlow();
            ScrollTo(AccountFlows.Id(flow));
        });
        _host.Children.Clear();
        _host.Children.Add(_flow.View);
    }

    /// <summary>The flow open closed, the sections shown again — read anew where the core closed them under a done
    /// rotation (<see cref="_reopen"/>).</summary>
    private void CloseFlow()
    {
        if (_flow is null) return;
        _flow.Dispose();
        _flow = null;
        _host.Children.Clear();
        _host.Children.Add(_scroll);
        Log.Write("settings: back from a flow");
        if (!_reopen || _connection is not { } connection) return;
        _reopen = false;
        _watch?.Dispose();
        _storageWatch?.Dispose();
        OpenIn(connection);
    }

    /// <summary>The core closed this page under a done rotation (its tenant opened anew): it is opened again on Back.</summary>
    private bool _reopen;

    /// <summary>Opens <paramref name="connection"/>'s Settings in the core; <paramref name="closedByCore"/> runs once the
    /// core closed it (its tenant closed, its account signed out here). <paramref name="at"/>: the section it scrolls to
    /// once drawn (a search's settings result, SettingsSection.id).</summary>
    public static AccountSettingsPage Open(Window window, string connection, Action closedByCore, string? at = null)
    {
        var page = new AccountSettingsPage(window, closedByCore) { _connection = connection, _at = at };
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
            var (answer, _) = await Core.CallAsync(new Call.SettingsOpen { connection = connection, separator = System.Globalization.CultureInfo.CurrentCulture.NumberFormat.NumberDecimalSeparator is { Length: 1 } sep ? sep : "." });
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
            // the storage section follows the pins and their fetches while the page shows; its updates come on the page's
            _storageWatch = Core.Watch(new Watched.SettingsStorageLive { settings = id }, _ => { }, e => Log.Write($"the storage section's follow: {e.Message}"));
            _watch = Core.Watch(new Watched.Settings { settings = id }, update =>
            {
                if (_disposed || update is not Update.Settings { shown: var s }) return;
                try
                {
                    if (s is null)
                    {
                        _id = null;
                        // a done rotation stays on screen across the tenant the bridge opened anew on the new keys (inbox
                        // #984): this page reads anew on its Back; anything else closes with the core's page
                        if (_flow is KeyRotationPage { KeptAcrossReopen: true })
                        {
                            _reopen = true;
                            return;
                        }
                        _closedByCore();
                    }
                    else Draw(s);
                }
                catch (Exception e)
                {
                    // out of a watch's callback it would end the app in combase, with nothing in the log
                    Log.Write($"the settings did not draw: {e.GetType().Name} 0x{e.HResult:X8}: {e.Message} | {string.Join(" | ", (e.StackTrace ?? "").Split('\n').Select(l => l.Trim()).Take(8))}");
                }
            }, e => Log.Write($"a settings update: {e.Message}"));
        }
        catch (Exception e)
        {
            Log.Write($"Settings.Open failed: {e.GetType().Name}");
        }
    }

    /// <summary>Scrolls to <paramref name="section"/>'s heading (SettingsSection.id) once it is laid out; a section this page
    /// does not show leaves it where it stands. The heading is looked up at each try, not now: every draw builds the
    /// sections anew, and one just built has no place until the next layout — so it is tried every 100 ms until the
    /// heading's top is reached (a page still growing, the sessions arriving after it opened, clamps it short and the next
    /// try goes on), for at most two seconds.</summary>
    public void ScrollTo(string section)
    {
        _scrolling?.Stop();
        var tries = 0;
        var timer = _scrolling = _window.DispatcherQueue.CreateTimer();
        timer.Interval = TimeSpan.FromMilliseconds(100);
        timer.IsRepeating = true;
        timer.Tick += (_, _) =>
        {
            if (_disposed)
            {
                timer.Stop();
                return;
            }
            tries++;
            if (_last is null && tries < 20) return;
            if (!_anchors.TryGetValue(section, out var heading))
            {
                Log.Write($"settings shows no section {section}");
                timer.Stop();
                return;
            }
            if (!heading.IsLoaded || heading.ActualHeight <= 0 || _scroll.ScrollableHeight <= 0)
            {
                if (tries >= 20)
                {
                    Log.Write($"settings' {section} was not laid out in time");
                    timer.Stop();
                }
                return;
            }
            // the heading's top in what scrolls: where it shows now plus where the view stands
            var wanted = heading.TransformToVisual(_scroll).TransformPoint(new Windows.Foundation.Point(0, 0)).Y + _scroll.VerticalOffset;
            var to = Math.Max(0, Math.Min(wanted, _scroll.ScrollableHeight));
            _scroll.ChangeView(null, to, null, disableAnimation: true);
            if (to < wanted - 1 && tries < 20) return;
            Log.Write($"settings scrolled to {section} at {to:0} of {_scroll.ScrollableHeight:0} (its top at {wanted:0})");
            timer.Stop();
        };
        timer.Start();
    }

    public void Dispose()
    {
        _disposed = true;
        _flow?.Dispose();
        _flow = null;
        _scrolling?.Stop();
        _maps.Dispose();
        _storageWatch?.Dispose();
        _storageWatch = null;
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

    /// <summary>The limit typed, set in the unit chosen (the core parses it and says why where it is no size).</summary>
    private void SetLimit()
    {
        var amount = _limit.Text;
        var unit = _unit.SelectedIndex >= 0 && _unit.SelectedIndex < _units.Count ? _units[_unit.SelectedIndex] : _limitDrawn?.unit ?? LimitUnit.Gb;
        Do("storage limit", id => new Call.SettingsOfflineLimit { settings = id, amount = amount, unit = unit });
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
    private static Border Card(string glyph, string title, IEnumerable<string> lines, string automationId, params UIElement[] end) =>
        Card(glyph, title, lines, automationId, out _, end);

    /// <summary>A card, and the panel its controls were put in.</summary>
    private static Border Card(string glyph, string title, IEnumerable<string> lines, string automationId, out Panel holder, params UIElement[] end)
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
        holder = controls;
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
        _nameHolder?.Children.Remove(_name);
        _nameHolder?.Children.Remove(_rename);
        _nameHolder = null;
        _limitHolder?.Children.Remove(_limit);
        _limitHolder?.Children.Remove(_unit);
        _limitHolder?.Children.Remove(_setLimit);
        _limitHolder = null;
        _sections.Children.Clear();
        _anchors.Clear();
        if (_refusal != s.refusal)
        {
            _refusal = s.refusal;
            _refused.Message = s.refusal is { } why ? Said.Key(why) : "";
            _refused.IsOpen = s.refusal is not null;
        }
        _sections.Children.Add(_refused);

        // Account
        _sections.Children.Add(Anchor("account", Heading(Words.AccountSettingsAccount())));
        var confirmed = s.emailConfirmed switch
        {
            true => Words.AccountSettingsEmailConfirmed(),
            false => Words.AccountSettingsEmailUnconfirmed(),
            null => null,
        };
        _sections.Children.Add(Card("", Words.AccountSettingsEmail(), new[] { s.email, confirmed }.OfType<string>(), "settings-email"));

        // the account's own flows, in the web's order (ADR 0148 decision 10)
        foreach (var f in new[] { AccountFlow.Profile, AccountFlow.Password, AccountFlow.Email }) FlowSection(f);

        // This device
        _sections.Children.Add(Anchor("device", Heading(Words.AccountSettingsThisDevice())));
        if (_label != s.device)
        {
            // the name is set from the state when it changed, never over what is being typed
            if (_label is null || _name.FocusState == FocusState.Unfocused) _name.Text = s.device;
            _label = s.device;
        }
        _sections.Children.Add(Card("", Words.AccountSettingsRename(), new[] { Words.AccountSettingsRenameHint() }, "settings-device-name", out _nameHolder, _name, _rename));
        _sections.Children.Add(Trust(s));

        // Add a device
        if (s.addDevice)
        {
            _sections.Children.Add(Anchor("add-device", Heading(Words.AccountSettingsAddDevice())));
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
        _sections.Children.Add(Anchor("sessions", Heading(Words.AccountSettingsSessions())));
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
            _sections.Children.Add(Anchor("saved-keys", Heading(Words.AccountSettingsSavedKeys())));
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

        DrawStorage(s);

        // Map services (ADR 0143 decision 2): this device's consent, its own watch
        if (_maps.Panel is FrameworkElement { Parent: Panel holder }) holder.Children.Remove(_maps.Panel);
        _sections.Children.Add(Anchor("maps", (FrameworkElement)_maps.Panel));

        foreach (var f in new[] { AccountFlow.Security, AccountFlow.Erase }) FlowSection(f);

        // Sign out
        _sections.Children.Add(Heading(Words.DrawerSignOut()));
        var signOut = TextButton(Words.DrawerSignOut(), "settings-sign-out");
        // writes made offline waiting: asked first (the core's question), deleted with the sign-out
        signOut.Click += async (_, _) =>
        {
            try
            {
                if (await Dialogs.AskLoss(_window, QueuedLoss.SignOut, Words.DrawerSignOut()) != false) Do("sign out here", id => new Call.SettingsSignOutHere { settings = id });
            }
            catch (Exception e)
            {
                Log.Write($"settings sign out failed: {e.GetType().Name}");
            }
        };
        _sections.Children.Add(signOut);

        // a search's settings result asked for a section: scrolled to once this first draw is laid out
        if (_at is { } at)
        {
            _at = null;
            ScrollTo(at);
        }
    }

    /// <summary>A section that opens one of the account's flows: its rubric, what it is for, the button that opens it.</summary>
    private void FlowSection(AccountFlow f)
    {
        var id = AccountFlows.Id(f);
        _sections.Children.Add(Anchor(id, Heading(AccountFlows.Title(f))));
        var open = TextButton(AccountFlows.Action(f), $"settings-open:{id}");
        open.Click += (_, _) => OpenFlow(f);
        _sections.Children.Add(Card(AccountFlows.Glyph(f), AccountFlows.Action(f), [AccountFlows.Sub(f)], $"settings-flow:{id}", open));
    }

    /// <summary>Any other device the register names renamed, every session of it (Settings.RenameFamily, ADR 0148 decision
    /// 1); this device's own through the name above.</summary>
    private async void RenameFamily(string family, string label)
    {
        try
        {
            var hint = new TextBlock { Text = Words.AccountSettingsRenameHint(), TextWrapping = TextWrapping.Wrap, Foreground = Brush("TextFillColorSecondaryBrush") };
            var name = await Dialogs.AskName(_window, "settings-rename-family-ask", $"{Words.NodeRename()} · {label}", Words.AccountSettingsRenamePlaceholder(), label, null, Words.NodeRename(), hint);
            if (name is null || name.Trim().Length == 0 || _disposed) return;
            await Send("rename a device", id => new Call.SettingsRenameFamily { settings = id, family = family, label = name });
        }
        catch (Exception e)
        {
            Log.Write($"settings rename a device failed: {e.GetType().Name}");
        }
    }

    /// <summary>A section's heading kept by its SettingsSection.id, where a search's result scrolls to it.</summary>
    private FrameworkElement Anchor(string section, FrameworkElement heading)
    {
        _anchors[section] = heading;
        return heading;
    }

    /// <summary>"Storage on this device": the limit, the sizes, "Clear cache" and "Remove all offline content", then the
    /// items available offline; where this device keeps no store of the account, why — or nothing where there is nothing
    /// to say.</summary>
    private void DrawStorage(SettingsShown s)
    {
        if (s.storage is not { } st)
        {
            if (s.storageUnavailable is not { } why) return;
            _sections.Children.Add(Anchor("storage", Heading(Words.AccountSettingsStorage())));
            _sections.Children.Add(Caption(Said.Key(why)));
            return;
        }
        _sections.Children.Add(Anchor("storage", Heading(Words.AccountSettingsStorage())));
        DrawLimit(s, st);
        _sections.Children.Add(Card("\uE713", Words.AccountSettingsStorageLimit(), [Words.AccountSettingsStorageLimitHint()], "settings-storage-limit-card", out _limitHolder, _limit, _unit, _setLimit));
        foreach (var (glyph, title, bytes, id) in new[]
        {
            ("\uE81C", Words.AccountSettingsStorageAutomatic(), (long?)st.automatic, "settings-storage-automatic"),
            ("\uE896", Words.AccountSettingsStoragePinned(), st.pinned, "settings-storage-pinned"),
            ("\uEDA2", Words.AccountSettingsStorageFree(), st.free, "settings-storage-free"),
        })
        {
            if (bytes is not { } b) continue;
            var size = new TextBlock { Text = Said.Size(b), VerticalAlignment = VerticalAlignment.Center, Foreground = Brush("TextFillColorSecondaryBrush") };
            AutomationProperties.SetAutomationId(size, $"{id}-size");
            _sections.Children.Add(Card(glyph, title, [], id, size));
        }
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Margin = new Thickness(0, 8, 0, 0) };
        var clear = TextButton(Words.AccountSettingsStorageClearCache(), "settings-storage-clear");
        clear.Click += (_, _) => Do("clear cache", id => new Call.SettingsClearCache { settings = id });
        buttons.Children.Add(clear);
        // the core asks first (askingRemoveAll), asked below
        var removeAll = TextButton(Words.AccountSettingsStorageRemoveAll(), "settings-storage-remove-all");
        removeAll.Click += (_, _) => Do("ask remove all", id => new Call.SettingsAskRemoveAll { settings = id });
        buttons.Children.Add(removeAll);
        _sections.Children.Add(buttons);
        AskRemoveAll(s.askingRemoveAll);

        _sections.Children.Add(Heading(Words.AccountSettingsStoragePinned()));
        if (st.items.Count == 0) _sections.Children.Add(Caption(Words.AccountSettingsStorageItemsEmpty()));
        foreach (var item in st.items)
        {
            var remove = TextButton(Words.AccountSettingsStorageRemoveItem(), $"settings-storage-remove:{item.node}");
            var node = item.node;
            remove.Click += (_, _) => Do("remove an item", id => new Call.SettingsOfflineRemove { settings = id, node = node });
            _sections.Children.Add(Card("\uE896", Said.Notice(item.title), [ItemLine(item)], $"settings-storage-item:{item.node}", remove));
        }
    }

    /// <summary>An item's line under its name: its status as the core says it, and the size it keeps on this device.</summary>
    public static string ItemLine(OfflineItem item) =>
        item.said is { } n ? $"{Said.Notice(n)} · {Said.Size(item.bytes)}" : Said.Size(item.bytes);

    /// <summary>The limit's field and its unit from the core's, set only where the core's changed and not while typed.</summary>
    private void DrawLimit(SettingsShown s, Storage st)
    {
        var units = s.limitUnits.Select(u => u.unit).ToList();
        _drawing = true;
        try
        {
            if (!units.SequenceEqual(_units))
            {
                _unit.Items.Clear();
                foreach (var u in s.limitUnits) _unit.Items.Add(Said.Key(u.word));
                _unit.Items.Add(Words.AccountSettingsStorageLimitOff());
                _units = units;
                _limitDrawn = null;
            }
            if (Equals(_limitDrawn, st.field)) return;
            if (_limitDrawn is null || _limit.FocusState == FocusState.Unfocused) _limit.Text = st.field.amount;
            _unit.SelectedIndex = st.field.off ? _units.Count : Math.Max(0, _units.ToList().IndexOf(st.field.unit));
            _limitDrawn = st.field;
        }
        finally
        {
            _drawing = false;
        }
    }

    /// <summary>"Remove all offline content"'s question while the core asks it: Remove all, or Cancel; asked once per asking.</summary>
    private async void AskRemoveAll(bool asking)
    {
        if (!asking)
        {
            _removeAllAsked = false;
            return;
        }
        if (_removeAllAsked) return;
        _removeAllAsked = true;
        try
        {
            // writes made offline waiting: the core's question in place of the plain one (Remove all deletes them too)
            var confirmed = await Dialogs.AskLoss(_window, QueuedLoss.RemoveAll, Words.AccountSettingsStorageRemoveAll())
                ?? await Dialogs.Confirm(_window, "settings-storage-remove-all-ask", Words.AccountSettingsStorageRemoveAll(),
                    Words.AccountSettingsStorageRemoveAllQuestion(), Words.AccountSettingsStorageRemoveAllConfirm());
            if (_disposed) return;
            if (confirmed) await Send("remove all", id => new Call.SettingsRemoveAll { settings = id });
            else await Send("cancel remove all", id => new Call.SettingsCancelRemoveAll { settings = id });
        }
        catch (Exception e)
        {
            Log.Write($"settings remove all failed: {e.GetType().Name}");
        }
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
            forget.Click += async (_, _) =>
            {
                try
                {
                    // writes made offline waiting: the core's question in place of the plain one (they go with the trust)
                    switch (await Dialogs.AskLoss(_window, QueuedLoss.StopTrusting, Words.AccountSettingsUntrustConfirmTitle()))
                    {
                        case null:
                            AskThen("settings-forget-ask", Words.AccountSettingsUntrustConfirmTitle(), Words.AccountSettingsUntrustConfirm(),
                                Words.ManageForgetDevice(), "stop trusting", id => new Call.SettingsStopTrusting { settings = id });
                            break;
                        case true:
                            Do("stop trusting", id => new Call.SettingsStopTrusting { settings = id });
                            break;
                    }
                }
                catch (Exception e)
                {
                    Log.Write($"settings stop trusting failed: {e.GetType().Name}");
                }
            };
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
        // the web's renameDeviceOfFamily: only a device the register names (the core writes nothing for another)
        if (f.label is { } named)
        {
            var rename = new MenuFlyoutItem { Text = Words.NodeRename(), Icon = new FontIcon { Glyph = "\uE8AC" } };
            AutomationProperties.SetAutomationId(rename, $"settings-rename-family:{f.id}");
            var renamed = f.id;
            rename.Click += (_, _) =>
            {
                if (f.current) _name.Focus(FocusState.Programmatic);
                else RenameFamily(renamed, named);
            };
            menu.Items.Add(rename);
        }
        var revokeWords = f.current ? Words.AccountSettingsRevokeSelf() : Words.AccountSettingsRevoke();
        var revoke = new MenuFlyoutItem { Text = revokeWords };
        AutomationProperties.SetAutomationId(revoke, $"settings-revoke:{f.id}");
        var family = f.id;
        var current = f.current;
        revoke.Click += async (_, _) =>
        {
            try
            {
                // this device's own family is a sign-out here: the core's question first while writes made offline wait
                // (ADR 0153 decision 2), in place of the plain one (they go with the session)
                switch (current ? await Dialogs.AskLoss(_window, QueuedLoss.SignOut, revokeWords) : null)
                {
                    case null:
                        AskThen("settings-revoke-ask", revokeWords, current ? Words.AccountSettingsRevokeSelfConfirm() : Words.AccountSettingsRevokeConfirm(),
                            revokeWords, "sign out", id => new Call.SettingsSignOut { settings = id, family = family });
                        break;
                    case true:
                        Do("sign out", id => new Call.SettingsSignOut { settings = id, family = family });
                        break;
                }
            }
            catch (Exception e)
            {
                Log.Write($"settings sign out failed: {e.GetType().Name}");
            }
        };
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
