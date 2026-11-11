// "Manage servers" (owner 2026-11-08; the CR device-trust-on-linux-and-windows, D5), Fluent's way: a ContentDialog opened
// from the switcher, laid out as Windows' Settings lays out a group of settings. One section per server signed in to —
// its name, how this device trusts it as the line under it, each tenant an Expander whose header carries its name and the
// ToggleSwitch that shows or hides it in the switcher, its spaces' ToggleSwitches inside; then "Sign out of this server",
// the changes of its trust (the CR's D7: each other level the device offers, "Back to your password", which asks for
// the password in a field shown in place — a ContentDialog opens no second one over it) and "Stop trusting this device"
// — and "Add server" last. What it shows is the core's (Watched.Manage,
// SessionModel.managed); each switch and button is one call. Linux's manage.rs (an AdwPreferencesDialog), Apple's
// ManageView.
using System;
using System.Collections.Generic;
using Microsoft.UI.Xaml;
using Veyq.Bridge;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge.Wire;

namespace Veyq;

internal sealed class ManageDialog
{
    private readonly Window _window;
    /// <summary>What the open dialog shows, new at each open: an element keeps its parent, the closed dialog's
    /// ScrollViewer, and a second open that reused it crashed the app (measured 2026-11-11).</summary>
    private StackPanel _servers = new();
    /// <summary>The tenants whose spaces the person opened, kept across the redraw each switch brings.</summary>
    private readonly HashSet<string> _expanded = [];
    private ContentDialog? _dialog;
    /// <summary>The server whose way back to the password asks for it now, kept across the redraws; and what its last
    /// try was told (a wrong password, a change not kept).</summary>
    private string? _asking;
    private string? _told;

    public ManageDialog(Window window) => _window = window;

    /// <summary>Opens the dialog over the window; the core then delivers what it shows.</summary>
    public async void Open()
    {
        if (_dialog is not null) return;
        _servers = new StackPanel { Spacing = 24 };
        (_asking, _told) = (null, null);
        var dialog = new ContentDialog
        {
            Title = Words.ManageTitle(),
            CloseButtonText = Words.ShareDone(),
            DefaultButton = ContentDialogButton.Close,
            Content = new ScrollViewer { Content = _servers, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(0, 0, 16, 0) },
            XamlRoot = _window.Content.XamlRoot,
            RequestedTheme = DebugEnv.Theme,
        };
        AutomationProperties.SetAutomationId(dialog, "manage");
        _dialog = dialog;
        dialog.Closed += (_, _) =>
        {
            _dialog = null;
            Act.Send(new Call.ManageClose());
        };
        Act.Send(new Call.ManageOpen());
        try
        {
            await dialog.ShowAsync();
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"Manage servers did not open ({e.GetType().Name})");
            _dialog = null;
        }
    }

    /// <summary>Closes it, where it is open (the way in left the shell).</summary>
    public void Close() => _dialog?.Hide();

    /// <summary>Draws the core's <paramref name="servers"/>; null while it is closed.</summary>
    public void Show(IReadOnlyList<ManagedShown>? servers)
    {
        if (servers is null || _dialog is null) return;
        _last = servers;
        _servers.Children.Clear();
        foreach (var server in servers) _servers.Children.Add(ServerSection(server));
        var add = new StackPanel { Spacing = 8 };
        add.Children.Add(Caption(Words.ManageHint()));
        var addButton = new Button { Content = Words.SwitcherAddServer() };
        AutomationProperties.SetAutomationId(addButton, "manage-add-server");
        addButton.Click += (_, _) =>
        {
            Act.Send(new Call.SessionAddServer());
            _dialog?.Hide();
        };
        add.Children.Add(addButton);
        _servers.Children.Add(add);
    }

    private static TextBlock Caption(string text) => new()
    {
        Text = text,
        Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
        Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
        TextWrapping = TextWrapping.Wrap,
    };

    /// <summary>A switch of a settings row: its label is the row's, and it says no On/Off of its own beside it.</summary>
    private static ToggleSwitch Switch(string name, string id, bool on, bool enabled)
    {
        var s = new ToggleSwitch { IsOn = on, IsEnabled = enabled, OnContent = "", OffContent = "", MinWidth = 0, VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetName(s, name);
        AutomationProperties.SetAutomationId(s, id);
        return s;
    }

    private UIElement ServerSection(ManagedShown server)
    {
        var section = new StackPanel { Spacing = 4 };
        var title = new TextBlock { Text = server.label, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], TextTrimming = TextTrimming.CharacterEllipsis };
        AutomationProperties.SetHeadingLevel(title, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        section.Children.Add(title);
        // how this device trusts the account; ending it means the next sign-in asks for the Secret Key
        if (TrustWords.Trusted(server.trusted, server.level) is { } trusted)
        {
            var line = Caption(trusted);
            AutomationProperties.SetAutomationId(line, $"manage-trusted:{server.label}");
            section.Children.Add(line);
        }
        foreach (var t in server.tenants) section.Children.Add(TenantRow(server, t));
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Margin = new Thickness(0, 8, 0, 0) };
        var signOut = new Button { Content = Words.ManageSignOut() };
        AutomationProperties.SetAutomationId(signOut, $"manage-sign-out:{server.label}");
        var connection = server.connection;
        signOut.Click += (_, _) => Act.Send(new Call.SessionSignOut { connection = connection });
        buttons.Children.Add(signOut);
        if (server.trusted is not null)
        {
            var forget = new Button { Content = Words.ManageForgetDevice() };
            AutomationProperties.SetAutomationId(forget, $"manage-forget:{server.label}");
            forget.Click += (_, _) => Act.Send(new Call.SessionForgetDevice { connection = connection });
            buttons.Children.Add(forget);
        }
        section.Children.Add(buttons);
        var changes = TrustWords.Changes(server.trusted, server.level, server.levels);
        if (changes.Count > 0)
        {
            var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            foreach (var change in changes)
            {
                var label = change is { } level ? TrustWords.Level(level).Title : Words.ManagePasswordTrust();
                var button = new Button { Content = label };
                AutomationProperties.SetAutomationId(button, change switch
                {
                    StaySignedIn.Presence => $"manage-presence:{server.label}",
                    StaySignedIn.Login => $"manage-login:{server.label}",
                    _ => $"manage-password:{server.label}",
                });
                if (change is { } l)
                {
                    ToolTipService.SetToolTip(button, TrustWords.Level(l).Hint);
                    button.Click += (_, _) => Change(new Call.ManageStaySignedIn { connection = connection, level = l }, $"stay signed in {l}", connection);
                }
                else
                {
                    button.Click += (_, _) =>
                    {
                        (_asking, _told) = (connection, null);
                        Redraw();
                    };
                }
                row.Children.Add(button);
            }
            section.Children.Add(row);
        }
        if (_asking == server.connection && server.trusted == Method.DeviceKey) section.Children.Add(PasswordAsk(connection));
        else if (_told is { } told && _asking is null && server.connection == _toldFor) section.Children.Add(Told(told));
        return section;
    }

    private string? _toldFor;
    private IReadOnlyList<ManagedShown>? _last;

    /// <summary>Draws what the core last delivered again, with what this dialog asks or was told meanwhile.</summary>
    private void Redraw() => Show(_last);

    private TextBlock Told(string text)
    {
        var told = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"] };
        AutomationProperties.SetAutomationId(told, "manage-told");
        AutomationProperties.SetLiveSetting(told, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
        return told;
    }

    /// <summary>"Back to your password"'s field, in place: ADR 0005 §9 keeps one convenience wrap per device, so the
    /// password trust is re-created, which takes the password; the core checks it first.</summary>
    private UIElement PasswordAsk(string connection)
    {
        var panel = new StackPanel { Spacing = 8, Margin = new Thickness(0, 8, 0, 0) };
        panel.Children.Add(Caption(Words.ManagePasswordTrustHint()));
        var password = new PasswordBox { Header = Words.SigninPassword() };
        AutomationProperties.SetAutomationId(password, "manage-password-field");
        if (DevLogin.TakePasswordTrust() is { } dev) password.Password = dev;
        panel.Children.Add(password);
        if (_told is { } told) panel.Children.Add(Told(told));
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var confirm = new Button { Content = Words.ManagePasswordTrust(), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetAutomationId(confirm, "manage-password-confirm");
        void Send()
        {
            if (password.Password.Length == 0) return;
            var typed = password.Password;
            password.Password = "";
            Change(new Call.ManagePasswordTrust { connection = connection, password = typed }, "back to the password", connection);
        }
        confirm.Click += (_, _) => Send();
        password.KeyDown += (_, e) =>
        {
            if (e.Key != Windows.System.VirtualKey.Enter) return;
            e.Handled = true;
            Send();
        };
        var cancel = new Button { Content = Words.SigninCancel() };
        AutomationProperties.SetAutomationId(cancel, "manage-password-cancel");
        cancel.Click += (_, _) =>
        {
            (_asking, _told) = (null, null);
            Redraw();
        };
        buttons.Children.Add(confirm);
        buttons.Children.Add(cancel);
        panel.Children.Add(buttons);
        return panel;
    }

    /// <summary>Sends a change of <paramref name="connection"/>'s trust; Manage follows (the core refreshes it). What it
    /// was refused for is said in that server's section; a cancelled Hello prompt says nothing.</summary>
    private async void Change(Call call, string what, string connection)
    {
        try
        {
            var (answer, _) = await Core.CallAsync(call);
            Log.Write($"manage {what}: {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
            _toldFor = connection;
            switch (answer)
            {
                case Answer.Done:
                    (_asking, _told) = (null, null);
                    break;
                case Answer.Refused { why: Why.TrustCancelled }:
                    _told = null;
                    break;
                case Answer.Refused { why: Why.WrongPassword }:
                    _told = Words.ManageWrongPassword();
                    break;
                default:
                    (_asking, _told) = (null, Words.TrustFailed());
                    break;
            }
            Redraw();
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"manage {what} failed: {e.GetType().Name}");
        }
    }

    private UIElement TenantRow(ManagedShown server, Tenant t)
    {
        var key = $"{server.connection}\n{t.tenant.tenantId}";
        var name = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        name.Children.Add(new TextBlock { Text = t.tenant.name, TextTrimming = TextTrimming.CharacterEllipsis });
        name.Children.Add(Caption(Said.Piece(t.subtitle)));
        var shown = Switch(t.tenant.name, $"manage-tenant:{t.tenant.name}", t.shown, t.switchable);
        var (connection, tenantId) = (server.connection, t.tenant.tenantId);
        shown.Toggled += (_, _) => Act.Send(new Call.ManageHideTenant { connection = connection, tenantId = tenantId, hidden = !shown.IsOn });
        var header = new Grid { ColumnSpacing = 12 };
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        header.Children.Add(name);
        Grid.SetColumn(shown, 1);
        header.Children.Add(shown);

        var spaces = new StackPanel { Spacing = 4 };
        if (t.spaces is null)
        {
            spaces.Children.Add(new ProgressRing { IsActive = true, Width = 16, Height = 16, HorizontalAlignment = HorizontalAlignment.Left });
        }
        else
        {
            foreach (var s in t.spaces)
            {
                var spaceName = Said.Piece(s.name);
                var row = new Grid { ColumnSpacing = 12 };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                row.Children.Add(new TextBlock { Text = spaceName, VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis });
                var on = Switch(spaceName, $"manage-space:{spaceName}", !s.hidden, s.canHide);
                var scopeId = s.space.scopeId;
                on.Toggled += (_, _) => Act.Send(new Call.ManageHideSpace { scopeId = scopeId, hidden = !on.IsOn });
                Grid.SetColumn(on, 1);
                row.Children.Add(on);
                spaces.Children.Add(row);
            }
        }
        var expander = new Expander
        {
            Header = header,
            Content = spaces,
            IsExpanded = _expanded.Contains(key),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
        };
        AutomationProperties.SetAutomationId(expander, $"manage-expand:{t.tenant.name}");
        AutomationProperties.SetName(expander, t.tenant.name);
        expander.Expanding += (_, _) => _expanded.Add(key);
        expander.Collapsed += (_, _) => _expanded.Remove(key);
        return expander;
    }
}
