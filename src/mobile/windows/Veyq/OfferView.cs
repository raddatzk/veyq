// The offer to stay signed in (ADR 0123 decision 8), shown while the core's Gate.Signed carries
// one: a page of the way in, drawn as Windows' own sign-in setup draws its offer after a sign-in ("Use Windows Hello"):
// the sign-in's centred column, a title, each answer a full-width button with the line that says what it means — the
// strongest the core lists first and accented —, and where to change it later. A ContentDialog's three buttons carry no
// line each, and the choice continues the sign-in rather than interrupting the shell. Each button is one call; the core
// answers with the next gate (a cancelled Hello prompt keeps the offer, and this page with it). Linux's sign_in::Offer.
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

internal sealed class OfferView
{
    private readonly StackPanel _choices = new() { Spacing = 8 };
    private readonly ProgressRing _working;
    private readonly Action<string> _said;
    private List<StaySignedIn> _shown = [];
    private readonly List<Button> _buttons = [];

    public UIElement Page { get; }

    /// <param name="said">Says a level this device could not keep, where the person then is (the shell: the offer has
    /// ended, the password trust stays).</param>
    public OfferView(Action<string> said)
    {
        _said = said;
        var heading = new TextBlock { Text = Words.OfferTitle(), Style = (Style)Application.Current.Resources["TitleTextBlockStyle"], TextWrapping = TextWrapping.Wrap };
        AutomationProperties.SetAutomationId(heading, "offer-title");
        AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level1);
        var footer = new TextBlock
        {
            Text = Words.OfferFooter(Words.SwitcherManage()),
            Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
            Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
            TextWrapping = TextWrapping.Wrap,
        };
        AutomationProperties.SetAutomationId(footer, "offer-footer");
        _working = new ProgressRing { IsActive = false, Visibility = Visibility.Collapsed, HorizontalAlignment = HorizontalAlignment.Center };
        AutomationProperties.SetAutomationId(_working, "offer-working");
        var column = new StackPanel { Spacing = 16, MaxWidth = 420, Padding = new Thickness(24), VerticalAlignment = VerticalAlignment.Center };
        foreach (var child in new UIElement[] { heading, _choices, footer, _working }) column.Children.Add(child);
        // the sign-in's layout: the capped column centres in a stretching Grid (a trap of src/mobile/CLAUDE.md)
        var stretch = new Grid();
        stretch.Children.Add(column);
        Page = new ScrollViewer { Content = stretch, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
    }

    /// <summary>Draws <paramref name="offer"/>'s levels; the buttons are built anew only when they change.</summary>
    public void Show(Offer offer)
    {
        if (_shown.SequenceEqual(offer.levels)) return;
        _shown = [.. offer.levels];
        _choices.Children.Clear();
        _buttons.Clear();
        var first = true;
        foreach (var (level, title, hint) in TrustWords.Offer(offer.levels))
        {
            var content = new StackPanel { Spacing = 2 };
            content.Children.Add(new TextBlock { Text = title, Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], TextWrapping = TextWrapping.Wrap });
            var line = new TextBlock { Text = hint, Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"], TextWrapping = TextWrapping.Wrap };
            // an accent button's own foreground carries its line; another's line is secondary, as a setting's description is
            if (!first) line.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
            content.Children.Add(line);
            var button = new Button
            {
                Content = content,
                HorizontalAlignment = HorizontalAlignment.Stretch,
                HorizontalContentAlignment = HorizontalAlignment.Left,
                Padding = new Thickness(16, 12, 16, 12),
            };
            if (first && level is not null) button.Style = (Style)Application.Current.Resources["AccentButtonStyle"];
            first = false;
            AutomationProperties.SetName(button, title);
            AutomationProperties.SetHelpText(button, hint);
            AutomationProperties.SetAutomationId(button, level switch
            {
                StaySignedIn.Presence => "offer-presence",
                StaySignedIn.Login => "offer-login",
                _ => "offer-not-now",
            });
            button.Click += (_, _) => Choose(level);
            _buttons.Add(button);
            _choices.Children.Add(button);
        }
    }

    /// <summary>Sends the answer; the buttons wait meanwhile (Hello's prompt may be up). What the core answers moves the
    /// gate on; a level it could not keep is said through the shell.</summary>
    private async void Choose(StaySignedIn? level)
    {
        Call call = level is { } l ? new Call.SessionStaySignedIn { level = l } : new Call.SessionNotNow();
        var choice = level?.ToString() ?? "NotNow";
        Waiting(true);
        try
        {
            var (answer, _) = await Core.CallAsync(call);
            switch (answer)
            {
                case Answer.Done:
                    Log.Write($"offer answered: {choice}");
                    break;
                case Answer.Refused { why: Why.TrustCancelled }:
                    // the prompt cancelled: the offer stands, as the core keeps it
                    Log.Write($"offer {choice}: cancelled");
                    break;
                case Answer.Refused { why: Why.TrustFailed }:
                    Log.Write($"offer {choice}: not kept");
                    _said(Words.TrustFailed());
                    break;
                default:
                    Log.Write($"offer {choice} answered {answer.GetType().Name}");
                    break;
            }
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"offer {choice} failed: {e.GetType().Name}");
        }
        Waiting(false);
    }

    private void Waiting(bool on)
    {
        foreach (var b in _buttons) b.IsEnabled = !on;
        _working.IsActive = on;
        _working.Visibility = on ? Visibility.Visible : Visibility.Collapsed;
    }
}
