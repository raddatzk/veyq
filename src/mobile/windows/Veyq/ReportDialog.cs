// A node's report to its server's moderators (the header CR's Phase 5; core's ReportSheet, the web's ReportDialog,
// ADR 0093), Fluent's way: one ContentDialog — what is reported (the node whole or only its name) and the category as
// RadioButtons, the reason in a multi-line TextBox, the good-faith confirmation as a CheckBox, the optional contact in a
// TextBox and the privacy notice's passage on it as a HyperlinkButton (opened in the browser, Launcher.LaunchUriAsync, the
// address the core's); Send the primary button, Cancel the close one. Sent, the dialog shows the receipt and Close.
// Every rule is the core's (Report.*, watched by id): what may be sent, the note under a category, the room of each field
// (the boxes' MaxLength, in UTF-16 units as the core counts), one send at a time. The typed reason and contact go in and
// are not echoed back.
using System;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

internal sealed class ReportDialog
{
    private readonly Window _window;
    private readonly StackPanel _body = new() { Spacing = 12, MinWidth = 460 };
    private ContentDialog? _dialog;
    private long? _id;
    private Watch? _watch;
    private ReportShown? _shown;
    private bool _closed;
    /// <summary>A draw sets the controls: their events are not the person's.</summary>
    private bool _drawing;
    private Form? _form;

    private sealed class Form
    {
        public required RadioButtons Scope { get; init; }
        public required TextBlock ScopeHint { get; init; }
        public required RadioButtons Category { get; init; }
        public required TextBlock Note { get; init; }
        public required TextBox Reason { get; init; }
        public required CheckBox GoodFaith { get; init; }
        public required TextBox Contact { get; init; }
        public required TextBlock Problem { get; init; }
        public required StackPanel Panel { get; init; }
    }

    private ReportDialog(Window window) => _window = window;

    /// <summary>Opens the report sheet of <paramref name="node"/> over <paramref name="window"/>.</summary>
    public static void Open(Window window, NodeAt node) => new ReportDialog(window).Show(node);

    private async void Show(NodeAt node)
    {
        if (Dialogs.Open) return;
        var dialog = new ContentDialog
        {
            Title = Words.ReportTitle(),
            Content = new ScrollViewer { Content = _body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(0, 0, 16, 0) },
            PrimaryButtonText = Words.ReportSend(),
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Primary,
            IsPrimaryButtonEnabled = false,
        };
        AutomationProperties.SetAutomationId(dialog, "report");
        dialog.PrimaryButtonClick += (_, args) =>
        {
            // sent or refused, the dialog stays: the state says what shows next (the receipt, or why not)
            args.Cancel = true;
            if (_id is { } report) Send(report);
        };
        dialog.Closing += (_, args) =>
        {
            if (_shown?.sending == true) args.Cancel = true;
        };
        _dialog = dialog;
        Draw();
        Watch(node);
        await Dialogs.Show(_window, dialog);
        _closed = true;
        _watch?.Dispose();
        _watch = null;
        if (_id is { } id) Act.Send(new Call.ReportClose { report = id });
        _id = null;
    }

    private static async void Send(long report)
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.ReportSend { report = report });
            Log.Write(answer is Answer.Said { notice: { } n } ? $"Report.Send: {n.msg}" : $"Report.Send: {answer.GetType().Name}");
        }
        catch (Exception e)
        {
            Log.Write($"Report.Send failed: {e.GetType().Name}");
        }
    }

    private async void Watch(NodeAt node)
    {
        Answer answer;
        try
        {
            (answer, _) = await Core.CallAsync(new Call.ReportOpen { node = node });
        }
        catch (Exception e)
        {
            Log.Write($"the report sheet did not open in the core ({e.GetType().Name})");
            return;
        }
        if (answer is not Answer.ReportOpened { report: var report })
        {
            Log.Write($"the report sheet did not open in the core ({answer.GetType().Name})");
            return;
        }
        if (_closed)
        {
            Act.Send(new Call.ReportClose { report = report });
            return;
        }
        _id = report;
        _watch = Core.Watch(new Watched.Report { report = report }, update =>
        {
            if (update is not Update.Report u || _closed) return;
            _shown = u.shown;
            if (u.shown is { } s)
            {
                Log.Write($"report sheet: whole {s.whole}, category {s.category}, good faith {s.goodFaith}, can send {s.canSend}, sending {s.sending}, " +
                    $"problem {s.problem?.msg}, sent {s.sent}, reference {s.reference}");
            }
            Draw();
        }, e => Log.Write($"a report sheet update: {e.Message}"));
    }

    private void Draw()
    {
        if (_dialog is not { } dialog) return;
        if (_shown is not { } s || _id is not { } report)
        {
            _body.Children.Clear();
            _body.Children.Add(new ProgressRing { IsActive = true });
            return;
        }
        if (s.sent)
        {
            _form = null;
            _body.Children.Clear();
            _body.Children.Add(new TextBlock { Text = Words.ReportSent(), Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"], TextWrapping = TextWrapping.Wrap });
            var receipt = Text(Words.ReportReceipt(s.reference ?? "", s.receivedAt is { } at ? Said.InstantMoment(at) : ""), "report-receipt", "TextFillColorSecondaryBrush");
            receipt.IsTextSelectionEnabled = true;
            _body.Children.Add(receipt);
            dialog.PrimaryButtonText = "";
            dialog.CloseButtonText = Words.ReportClose();
            return;
        }
        var f = _form ??= Build(s, report);
        _drawing = true;
        f.Scope.SelectedIndex = s.whole ? 0 : 1;
        f.ScopeHint.Text = Said.Key(s.scopeHint);
        f.Category.SelectedIndex = s.category is { } c ? s.categories.Select(x => x.id).ToList().IndexOf(c) : -1;
        f.Note.Text = s.note is { } n ? Said.Key(n) : "";
        f.Note.Visibility = s.note is null ? Visibility.Collapsed : Visibility.Visible;
        f.GoodFaith.IsChecked = s.goodFaith;
        f.Problem.Text = s.problem is { } p ? Said.Notice(p) : "";
        f.Problem.Visibility = s.problem is null ? Visibility.Collapsed : Visibility.Visible;
        f.Panel.IsHitTestVisible = !s.sending;
        f.Panel.Opacity = s.sending ? 0.6 : 1;
        _drawing = false;
        dialog.IsPrimaryButtonEnabled = s.canSend;
    }

    /// <summary>The form's controls, built once per open (a control reused under a new parent crashed a second open).</summary>
    private Form Build(ReportShown s, long report)
    {
        _body.Children.Clear();
        var panel = new StackPanel { Spacing = 12 };
        panel.Children.Add(Text(Words.ReportIntro(), "report-intro", "TextFillColorSecondaryBrush"));

        var scope = new RadioButtons { Header = Words.ReportScope() };
        foreach (var (id, word) in new[] { ("whole", Words.ReportScopeWhole()), ("name", Words.ReportScopeName()) })
        {
            var item = new RadioButton { Content = word };
            AutomationProperties.SetAutomationId(item, $"report-scope:{id}");
            scope.Items.Add(item);
        }
        AutomationProperties.SetAutomationId(scope, "report-scope");
        scope.SelectionChanged += (_, _) =>
        {
            if (!_drawing && scope.SelectedIndex >= 0) Act.Send(new Call.ReportWhole { report = report, whole = scope.SelectedIndex == 0 });
        };
        panel.Children.Add(scope);
        var scopeHint = Text("", "report-scope-hint", "TextFillColorSecondaryBrush");
        scopeHint.Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"];
        panel.Children.Add(scopeHint);

        var category = new RadioButtons { Header = Words.ReportCategory() };
        foreach (var c in s.categories)
        {
            var item = new RadioButton { Content = Said.Key(c.word) };
            AutomationProperties.SetAutomationId(item, $"report-category:{c.id}");
            category.Items.Add(item);
        }
        var ids = s.categories.Select(c => c.id).ToList();
        category.SelectionChanged += (_, _) =>
        {
            if (!_drawing && category.SelectedIndex >= 0) Act.Send(new Call.ReportCategory { report = report, category = ids[category.SelectedIndex] });
        };
        panel.Children.Add(category);
        var note = Text("", "report-note", "AccentTextFillColorPrimaryBrush");
        panel.Children.Add(note);

        var reason = new TextBox
        {
            Header = Words.ReportReason(),
            PlaceholderText = Words.ReportReasonHint(),
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            MinHeight = 110,
            MaxLength = s.reasonMax,
        };
        AutomationProperties.SetAutomationId(reason, "report-reason");
        reason.TextChanged += (_, _) =>
        {
            if (!_drawing) Act.Send(new Call.ReportReason { report = report, reason = reason.Text });
        };
        panel.Children.Add(reason);
        panel.Children.Add(Caption(Words.ReportReasonHint()));

        var faith = new CheckBox { Content = new TextBlock { Text = Words.ReportGoodFaith(), TextWrapping = TextWrapping.Wrap } };
        AutomationProperties.SetName(faith, Words.ReportGoodFaith());
        AutomationProperties.SetAutomationId(faith, "report-good-faith");
        faith.Checked += (_, _) => { if (!_drawing) Act.Send(new Call.ReportGoodFaith { report = report, on = true }); };
        faith.Unchecked += (_, _) => { if (!_drawing) Act.Send(new Call.ReportGoodFaith { report = report, on = false }); };
        panel.Children.Add(faith);

        var contact = new TextBox { Header = Words.ReportContact(), MaxLength = s.contactMax };
        contact.InputScope = new InputScope { Names = { new InputScopeName(InputScopeNameValue.EmailSmtpAddress) } };
        AutomationProperties.SetAutomationId(contact, "report-contact");
        contact.TextChanged += (_, _) =>
        {
            if (!_drawing) Act.Send(new Call.ReportContact { report = report, contact = contact.Text });
        };
        panel.Children.Add(contact);
        panel.Children.Add(Caption(Words.ReportContactHint()));
        // GDPR Art. 13 applies where a contact is taken: the notice's passage on it, in the browser
        var privacy = new HyperlinkButton { Content = Words.ReportPrivacy(), Padding = new Thickness(0) };
        AutomationProperties.SetAutomationId(privacy, "report-privacy");
        privacy.Click += async (_, _) =>
        {
            if (_shown?.privacyUrl is not { } url) return;
            try
            {
                var opened = await Windows.System.Launcher.LaunchUriAsync(new Uri(url));
                Log.Write($"privacy: {(opened ? "opened" : "did not open")} {url}");
            }
            catch (Exception e)
            {
                Log.Write($"privacy: {url} did not open ({e.GetType().Name})");
            }
        };
        panel.Children.Add(privacy);
        var problem = Text("", "report-problem", "SystemFillColorCriticalBrush");
        panel.Children.Add(problem);
        _body.Children.Add(panel);
        return new Form { Scope = scope, ScopeHint = scopeHint, Category = category, Note = note, Reason = reason, GoodFaith = faith, Contact = contact, Problem = problem, Panel = panel };
    }

    private static TextBlock Caption(string text) => new()
    {
        Text = text,
        TextWrapping = TextWrapping.Wrap,
        Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
        Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
    };

    private static TextBlock Text(string text, string id, string brush)
    {
        var t = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources[brush] };
        AutomationProperties.SetAutomationId(t, id);
        return t;
    }
}
