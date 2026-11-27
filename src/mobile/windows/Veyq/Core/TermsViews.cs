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
    public static StackPanel Of(TermsShown shown, bool withTerms = true)
    {
        var panel = new StackPanel { Spacing = 12 };
        if (withTerms && shown.hasTerms && shown.terms.Trim().Length > 0) panel.Children.Add(Markdown(shown.terms));
        else if (withTerms) panel.Children.Add(Line(Words.TermsNoneSet(), true));
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

/// <summary>Settings' Terms (the web's TenantTermsPanel): for an owner the editor over the core's draft (Terms.Editing,
/// Terms.Edit, Terms.Save) — one text per language that has terms, "Add language", each language's "Insert template" and
/// remove (asked in place first), the blank language's warning, "Save terms" and the preview —, for a member the terms
/// read-only; the operator section below both. Every rule is the core's; the dialog keeps the draft it was answered.</summary>
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
                Content = new ScrollViewer { Content = body, MaxHeight = 560 },
                CloseButtonText = Words.ShareDone(),
                DefaultButton = ContentDialogButton.Close,
            };
            AutomationProperties.SetAutomationId(dialog, "terms-page");
            var shown = Dialogs.Show(window, dialog);
            var (answer, _) = await Core.CallAsync(new Call.SettingsTermsEditing());
            body.Children.Clear();
            if (answer is Answer.TermsEditing { editing: { owner: true } editing })
            {
                new Editor(body, editing.shown, editing.draft).Edit(editing.draft, TermsChange.Text, "", null);
            }
            else if (answer is Answer.TermsEditing { editing: { } read })
            {
                body.Children.Add(TermsText.Line(Words.TermsReadonlyNote(), true));
                body.Children.Add(TermsText.Of(read.shown));
            }
            else
            {
                Log.Write($"Terms.Editing answered {answer?.GetType().Name}");
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

    /// <summary>A word of one text argument the core named.</summary>
    private static string With(Msg m, string arg)
    {
        var key = System.Text.Json.JsonSerializer.Serialize(m).Trim('"').ToLowerInvariant();
        return Words.Say(key, [arg]) ?? key;
    }

    /// <summary>The owner's editor: the draft the core last answered, drawn anew after each change of its languages; a
    /// typed text asks the core for the new draft and preview without redrawing the box being typed in.</summary>
    private sealed class Editor(StackPanel body, TermsShown shown, TermsDraft draft)
    {
        private TermsDraft _draft = draft;
        private string _preview = "";
        private readonly StackPanel _tail = new() { Spacing = 12 };
        private readonly InfoBar _said = new() { IsOpen = false, IsClosable = true };

        public async void Edit(TermsDraft from, TermsChange change, string lang, string? typed)
        {
            var (answer, _) = await Core.CallAsync(new Call.SettingsTermsEdit { draft = from, change = change, lang = lang, text = typed ?? "" });
            if (answer is not Answer.TermsDrafted { draft: { } next } drafted)
            {
                Log.Write($"Terms.Edit answered {answer?.GetType().Name}");
                return;
            }
            _draft = next;
            _preview = drafted.preview;
            if (typed is null) Draw(); else Tail();
        }

        private void Draw()
        {
            body.Children.Clear();
            AutomationProperties.SetAutomationId(_said, "terms-notice");
            body.Children.Add(_said);
            body.Children.Add(TermsText.Line(Words.TermsEditorSub(), true));
            if (_draft.showsRevision)
            {
                var revision = new TextBlock { Text = Words.TermsRevision(_draft.revision), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
                AutomationProperties.SetAutomationId(revision, "terms-revision");
                body.Children.Add(revision);
            }
            foreach (var t in _draft.texts) body.Children.Add(Language(t));
            if (_draft.addable.Count > 0)
            {
                var menu = new MenuFlyout();
                foreach (var l in _draft.addable)
                {
                    var item = new MenuFlyoutItem { Text = l.name };
                    AutomationProperties.SetAutomationId(item, $"terms-add-{l.lang}");
                    var code = l.lang;
                    item.Click += (_, _) => Edit(_draft, TermsChange.Add, code, null);
                    menu.Items.Add(item);
                }
                var add = new DropDownButton { Content = Words.TermsAddLanguage(), Flyout = menu };
                AutomationProperties.SetAutomationId(add, "terms-add");
                body.Children.Add(add);
            }
            body.Children.Add(_tail);
            Tail();
        }

        private StackPanel Language(LangText t)
        {
            var panel = new StackPanel { Spacing = 6 };
            AutomationProperties.SetAutomationId(panel, $"terms-lang-{t.lang}");
            var box = new TextBox
            {
                Header = t.name, Text = t.text, PlaceholderText = Words.TermsPlaceholder(), AcceptsReturn = true,
                TextWrapping = TextWrapping.Wrap, MinHeight = 160, FontFamily = new FontFamily("Cascadia Mono, Consolas"),
            };
            AutomationProperties.SetAutomationId(box, $"terms-text-{t.lang}");
            var code = t.lang;
            box.TextChanged += (_, _) => Edit(_draft, TermsChange.Text, code, box.Text);
            panel.Children.Add(box);
            var acts = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            if (t.templated)
            {
                var template = new HyperlinkButton { Content = Words.TermsInsertTemplate() };
                AutomationProperties.SetAutomationId(template, $"terms-template-{t.lang}");
                template.Click += (_, _) => Ask(panel, Words.TermsInsertTemplate(), With(t.templateAsk, t.name), () => Edit(_draft, TermsChange.Template, code, null));
                acts.Children.Add(template);
            }
            var remove = new HyperlinkButton { Content = Words.TermsRemoveLang(t.name) };
            AutomationProperties.SetAutomationId(remove, $"terms-remove-{t.lang}");
            remove.Click += (_, _) => Ask(panel, Words.TermsRemoveLang(t.name), Words.TermsRemoveLangAsk(t.name), () => Edit(_draft, TermsChange.Remove, code, null));
            acts.Children.Add(remove);
            panel.Children.Add(acts);
            return panel;
        }

        /// <summary>A question asked in place under a language's row (one ContentDialog at a time): its words, the act and Cancel.</summary>
        private static void Ask(StackPanel under, string act, string question, Action then)
        {
            var ask = new StackPanel { Spacing = 8, Padding = new Thickness(12), CornerRadius = new CornerRadius(4), Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"] };
            AutomationProperties.SetAutomationId(ask, "terms-ask");
            ask.Children.Add(TermsText.Line(question, false));
            var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            var yes = new Button { Content = act, Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
            AutomationProperties.SetAutomationId(yes, "terms-ask-confirm");
            var no = new Button { Content = Words.SigninCancel() };
            yes.Click += (_, _) => { under.Children.Remove(ask); then(); };
            no.Click += (_, _) => under.Children.Remove(ask);
            buttons.Children.Add(yes);
            buttons.Children.Add(no);
            ask.Children.Add(buttons);
            under.Children.Add(ask);
        }

        /// <summary>The warning, the hint, "Save terms", the preview and the operator section: what a typed text moves.</summary>
        private void Tail()
        {
            _tail.Children.Clear();
            if (_draft.blankWarning is { } warning)
            {
                var bar = new InfoBar { IsOpen = true, IsClosable = false, Severity = InfoBarSeverity.Warning, Message = With(warning, _draft.blankNames ?? "") };
                AutomationProperties.SetAutomationId(bar, "terms-blank");
                _tail.Children.Add(bar);
            }
            var hint = TermsText.Line(Words.TermsMarkdownHint("{contact}"), true);
            hint.Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"];
            _tail.Children.Add(hint);
            var save = new Button { Content = Words.TermsSave(), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
            AutomationProperties.SetAutomationId(save, "terms-save");
            save.Click += (_, _) => Save(save);
            _tail.Children.Add(save);
            if (_preview.Length > 0)
            {
                var label = new TextBlock { Text = Words.TermsPreview(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
                AutomationProperties.SetHeadingLevel(label, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
                _tail.Children.Add(label);
                _tail.Children.Add(TermsText.Markdown(_preview));
            }
            _tail.Children.Add(TermsText.Of(shown, withTerms: false));
        }

        private async void Save(Button save)
        {
            save.IsEnabled = false;
            var (answer, _) = await Core.CallAsync(new Call.SettingsTermsSave { draft = _draft });
            save.IsEnabled = true;
            if (answer is not Answer.TermsSaved { notice: { } n } saved)
            {
                Log.Write($"Terms.Save answered {answer?.GetType().Name}");
                return;
            }
            _said.Message = Said.Notice(n);
            _said.Severity = n.kind == NoticeKind.Said ? InfoBarSeverity.Success : InfoBarSeverity.Error;
            _said.IsOpen = true;
            if (saved.draft is { } next) Edit(next, TermsChange.Text, "", null);
        }
    }
}
