// A tenant's terms (uc.account.accept-terms; the web's TermsAcceptView and TenantTermsPanel): the member's gate
// (TermsGateView, the core's Gate.Signed.terms), a face of the way in in place of the shell while it stands — the space's
// terms, the operator section after them, "I agree" and "Sign out", each one call, the core answering with the next gate —,
// and Settings' Terms (TermsDialog, SettingsSection.TERMS), the terms read-only in a ContentDialog, as Windows' Settings
// shows a long read-only text over its page. The privacy notice's passage on the guest-write record opens in the browser.
// Linux's core_ui::terms.

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

internal sealed class TermsGateView
{
    private readonly StackPanel _column = new() { Spacing = 16, MaxWidth = 640, Padding = new Thickness(24) };

    public UIElement Page { get; }

    public TermsGateView()
    {
        var stretch = new Grid();
        stretch.Children.Add(_column);
        Page = new ScrollViewer { Content = stretch, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
        AutomationProperties.SetAutomationId(Page, "terms-gate");
    }

    /// <summary>Draws <paramref name="gate"/> over the connection <paramref name="connection"/> (Gate.Signed.connectionKey),
    /// whose sign-out is its other way out.</summary>
    public void Show(TermsGate gate, string connection)
    {
        _column.Children.Clear();
        var heading = new TextBlock { Text = Words.TermsGateTitle(), Style = (Style)Application.Current.Resources["TitleTextBlockStyle"], TextWrapping = TextWrapping.Wrap };
        AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level1);
        _column.Children.Add(heading);
        _column.Children.Add(TermsText.Line(Words.TermsGateIntro(), false));
        _column.Children.Add(TermsText.Of(gate.shown));
        if (gate.error is { } key)
        {
            var error = TermsText.Line(Said.Key(key), false);
            error.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
            AutomationProperties.SetAutomationId(error, "terms-error");
            AutomationProperties.SetLiveSetting(error, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
            _column.Children.Add(error);
        }
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var accept = new Button { Content = Words.TermsAccept(), Style = (Style)Application.Current.Resources["AccentButtonStyle"], IsEnabled = !gate.working };
        AutomationProperties.SetAutomationId(accept, "terms-accept");
        accept.Click += (_, _) => Act.Send(new Call.SessionAcceptTerms());
        var signOut = new Button { Content = Words.DrawerSignOut(), IsEnabled = !gate.working };
        AutomationProperties.SetAutomationId(signOut, "terms-sign-out");
        signOut.Click += (_, _) => Act.Send(new Call.SessionSignOut { connection = connection });
        buttons.Children.Add(accept);
        buttons.Children.Add(signOut);
        if (gate.working) buttons.Children.Add(new ProgressRing { IsActive = true, Width = 20, Height = 20 });
        _column.Children.Add(buttons);
    }
}

/// <summary>The terms as the core hands them, the gate's and Settings' alike.</summary>
internal static class TermsText
{
    /// <summary>The space's text (or that it has none), the operator section under its label and note, and the record's
    /// pointer with its link to the server's privacy notice.</summary>
    public static StackPanel Of(TermsShown shown)
    {
        var panel = new StackPanel { Spacing = 12 };
        if (shown.hasTerms && shown.terms.Trim().Length > 0) panel.Children.Add(Markdown(shown.terms));
        else panel.Children.Add(Line(Words.TermsNoneSet(), true));
        panel.Children.Add(new Border { Height = 1, Background = (Brush)Application.Current.Resources["DividerStrokeColorDefaultBrush"] });
        var label = new TextBlock { Text = Words.TermsOperatorSectionLabel(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
        AutomationProperties.SetHeadingLevel(label, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        panel.Children.Add(label);
        var note = Line(Words.TermsOperatorSectionNote(), true);
        note.Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"];
        panel.Children.Add(note);
        if (shown.operatorSection.Trim().Length > 0) panel.Children.Add(Markdown(shown.operatorSection));
        if (shown.record.Trim().Length > 0)
        {
            panel.Children.Add(Markdown(shown.record));
            if (Uri.TryCreate(shown.privacyUrl, UriKind.Absolute, out var uri))
            {
                var link = new HyperlinkButton { Content = Words.TermsRecordLink(), NavigateUri = uri };
                AutomationProperties.SetAutomationId(link, "terms-privacy");
                panel.Children.Add(link);
            }
        }
        return panel;
    }

    /// <summary>A text written in Markdown, drawn as its paragraphs (a heading's line strong, a list's items with a bullet):
    /// selectable, never followed.</summary>
    public static StackPanel Markdown(string text)
    {
        var panel = new StackPanel { Spacing = 6 };
        foreach (var (block, heading) in Blocks(text))
        {
            var line = Line(block, false);
            if (heading) line.Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"];
            panel.Children.Add(line);
        }
        return panel;
    }

    /// <summary>A Markdown text's paragraphs, each with whether it is a heading.</summary>
    public static List<(string Text, bool Heading)> Blocks(string text) =>
        text.Trim().Replace("\r\n", "\n").Split("\n\n").Select(b => b.Trim()).Where(b => b.Length > 0)
            // a heading is its own line: the lines after it in the same block are the paragraph under it
            .SelectMany(b => b.StartsWith('#') && b.IndexOf('\n') is var nl and > 0 && b[(nl + 1)..].Trim().Length > 0
                ? new[] { b[..nl].Trim(), b[(nl + 1)..].Trim() } : new[] { b })
            .Select(b =>
            b.StartsWith('#')
                ? (b.TrimStart('#').Trim(), true)
                : (string.Join("\n", b.Split('\n').Select(l => l.Trim()).Select(l => l.StartsWith("- ") || l.StartsWith("* ") ? "• " + l[2..] : l)).Replace("**", ""), false)
        ).ToList();

    public static TextBlock Line(string text, bool secondary)
    {
        var t = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true };
        if (secondary) t.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        return t;
    }
}

/// <summary>Settings' Terms: the open space's terms read when it opens, read-only with the note that the owner edits
/// them in the browser.</summary>
internal static class TermsDialog
{
    public static async void Open(Window window)
    {
        try
        {
            var body = new StackPanel { Spacing = 12 };
            body.Children.Add(new ProgressRing { IsActive = true });
            body.Children.Add(TermsText.Line(Words.TermsLoading(), true));
            var dialog = new ContentDialog
            {
                Title = Words.TermsSettingsTitle(),
                Content = new ScrollViewer { Content = body, MaxHeight = 520 },
                CloseButtonText = Words.ShareDone(),
                DefaultButton = ContentDialogButton.Close,
            };
            AutomationProperties.SetAutomationId(dialog, "terms-page");
            var shown = Dialogs.Show(window, dialog);
            var (answer, _) = await Core.CallAsync(new Call.SettingsTerms());
            body.Children.Clear();
            if (answer is Answer.Terms { shown: { } terms })
            {
                body.Children.Add(TermsText.Line(Words.TermsReadonlyNote(), true));
                body.Children.Add(TermsText.Of(terms));
            }
            else
            {
                Log.Write($"Settings.Terms answered {answer?.GetType().Name}");
                var failed = TermsText.Line(Words.TermsLoadFailed(), false);
                AutomationProperties.SetAutomationId(failed, "terms-load-failed");
                body.Children.Add(failed);
            }
            await shown;
        }
        catch (Exception e)
        {
            Log.Write($"the terms did not open ({e.GetType().Name})");
        }
    }
}
