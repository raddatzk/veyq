// Settings → Map services on Windows (ADR 0143 decision 2; the web's MapsPanel, Apple's MapServicesSection, Linux's
// account_settings map section), as Windows' own Settings draws a switch: the section's title and what it is about, the
// services this server uses (the map's host and the place names' host, "none configured" where one is not), what each
// receives, and the switch — this device's consent, given for the hosts served now (Map.TurnOn) or withdrawn at once
// (Map.TurnOff), which stops every map and every lookup. Where the consent was given for other hosts it says so, and that
// it no longer holds; with no map served there is nothing to consent to. Every state is the core's (Watched.MapServices).

namespace Veyq.Core;

using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal sealed class MapServicesSection : IDisposable
{
    // Built once and only filled in from each state: the switch is never taken out of its row (a ToggleSwitch re-parented
    // while its own toggle's transition ran ended the app in combase, 0xc000027b, measured 2026-11-16 on the VM).
    private readonly StackPanel _panel = new() { Spacing = 4 };
    private readonly ToggleSwitch _switch = new() { OnContent = null, OffContent = null, VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock _notOffered = new() { TextWrapping = TextWrapping.Wrap };
    private readonly Border _notOfferedCard;
    private readonly TextBlock _tiles = new() { IsTextSelectionEnabled = true };
    private readonly TextBlock _places = new() { IsTextSelectionEnabled = true };
    private readonly Border _servicesCard;
    private readonly TextBlock _givenFor = Caption("");
    private readonly Border _toggleCard;
    private Watch? _watch;
    private MapServicesShown? _shown;
    private bool _drawing;

    /// <summary>The section, re-added by its page at each redraw.</summary>
    public UIElement Panel => _panel;

    public MapServicesSection()
    {
        AutomationProperties.SetAutomationId(_panel, "settings-maps");
        AutomationProperties.SetAutomationId(_switch, "settings-maps-switch");
        AutomationProperties.SetName(_switch, Words.SettingsMapsSwitch());
        AutomationProperties.SetAutomationId(_notOffered, "settings-maps-not-offered");
        AutomationProperties.SetAutomationId(_tiles, "settings-maps-tiles");
        AutomationProperties.SetAutomationId(_places, "settings-maps-places");
        AutomationProperties.SetAutomationId(_givenFor, "settings-maps-given-for");
        var heading = new TextBlock { Text = Words.SettingsMaps(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], Margin = new Thickness(0, 20, 0, 4) };
        AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        _panel.Children.Add(heading);
        _panel.Children.Add(Caption(Words.SettingsMapsSub()));
        _notOfferedCard = Card(_notOffered);
        _panel.Children.Add(_notOfferedCard);
        var services = new StackPanel { Spacing = 8 };
        services.Children.Add(Row(Words.SettingsMapsTiles(), _tiles));
        services.Children.Add(Row(Words.SettingsMapsPlaces(), _places));
        services.Children.Add(Caption(Words.SettingsMapsReceive()));
        _servicesCard = Card(services);
        _panel.Children.Add(_servicesCard);
        var line = new Grid { ColumnSpacing = 16 };
        line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        text.Children.Add(new TextBlock { Text = Words.SettingsMapsSwitch(), TextWrapping = TextWrapping.Wrap });
        text.Children.Add(Caption(Words.SettingsMapsSwitchHint()));
        line.Children.Add(text);
        Grid.SetColumn(_switch, 1);
        line.Children.Add(_switch);
        var toggle = new StackPanel { Spacing = 4 };
        toggle.Children.Add(line);
        toggle.Children.Add(_givenFor);
        _toggleCard = Card(toggle);
        _panel.Children.Add(_toggleCard);
        _switch.Toggled += (_, _) =>
        {
            if (_drawing) return;
            Log.Write($"settings map services: {(_switch.IsOn ? "on" : "off")}");
            Act.Send(_switch.IsOn ? new Call.MapTurnOn() : new Call.MapTurnOff());
        };
        _watch = Core.Watch(new Watched.MapServices(), update =>
        {
            if (update is not Update.MapServices { shown: var s }) return;
            _shown = s;
            Log.Write($"map services shown: {(s is null ? "none" : $"{s.state.GetType().Name}, consent for {(s.consentTileHost is null ? "none" : "hosts")}")}");
            Draw();
        }, e => Log.Write($"a map services update: {e.Message}"));
        Draw();
    }

    private static TextBlock Caption(string text) => new()
    {
        Text = text,
        TextWrapping = TextWrapping.Wrap,
        Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
        Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
    };

    private static Grid Row(string label, TextBlock value)
    {
        var row = new Grid { ColumnSpacing = 16 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.Children.Add(new TextBlock { Text = label });
        value.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        Grid.SetColumn(value, 1);
        row.Children.Add(value);
        return row;
    }

    private static Border Card(UIElement child) => new()
    {
        Child = child,
        Padding = new Thickness(16, 12, 16, 12),
        CornerRadius = new CornerRadius(4),
        BorderThickness = new Thickness(1),
        Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"],
        BorderBrush = (Brush)Application.Current.Resources["CardStrokeColorDefaultBrush"],
    };

    private void Draw()
    {
        if (_shown is not { } s)
        {
            _panel.Visibility = Visibility.Collapsed;
            return;
        }
        _panel.Visibility = Visibility.Visible;
        var none = s.state is MapStatus.MapStateNotOffered or MapStatus.MapStateOffline;
        _notOffered.Text = s.state is MapStatus.MapStateOffline ? Words.MapNeedsConnection() : Words.SettingsMapsNotOffered();
        _notOfferedCard.Visibility = none ? Visibility.Visible : Visibility.Collapsed;
        _servicesCard.Visibility = none ? Visibility.Collapsed : Visibility.Visible;
        _toggleCard.Visibility = none ? Visibility.Collapsed : Visibility.Visible;
        if (none) return;
        // the hosts served now: the consent's ask names them; once it holds they are those it was given for
        var (tiles, places) = s.state is MapStatus.MapStateConsent c ? (c.tileHost, c.geocoderHost) : (s.consentTileHost, s.consentGeocoderHost);
        _tiles.Text = tiles ?? Words.SettingsMapsNone();
        _places.Text = places ?? Words.SettingsMapsNone();
        var on = s.state is MapStatus.MapStateShown;
        if (_switch.IsOn != on)
        {
            _drawing = true;
            _switch.IsOn = on;
            _drawing = false;
        }
        var renewal = s.state is MapStatus.MapStateConsent { renewal: true };
        _givenFor.Text = !renewal ? ""
            : s.consentTileHost is null && s.consentGeocoderHost is null ? Words.SettingsMapsGivenForUnrecorded()
            : Words.SettingsMapsGivenFor(s.consentTileHost ?? Words.SettingsMapsNone(), s.consentGeocoderHost ?? Words.SettingsMapsNone());
        _givenFor.Visibility = renewal ? Visibility.Visible : Visibility.Collapsed;
    }

    public void Dispose()
    {
        _watch?.Dispose();
        _watch = null;
    }
}
