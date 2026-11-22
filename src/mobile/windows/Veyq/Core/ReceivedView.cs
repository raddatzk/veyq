// A received link opened in the app as its guest (ADR 0150 decisions 2–4, 7 and 8; cr-app-received-links phase 10),
// Fluent's way — Linux's received.rs, in WinUI. *Open a link* (OpenLink) is a ContentDialog with the link's box, a Paste
// button that reads the clipboard on the tap only, and the core's reason where the text is no link the app opens — with
// *Open in the browser* for a moderator's review (Received.Open answers ReceivedProblem). It stands on the sign-in page
// under the sign-in and the unlock (owner 2026-11-19: not in the account menu).
//
// An opened link is a ReceivedView: a screen above everything (the window's face while it stands, MainWindow) with its
// own head — Back (inside the link a step, at its root a question first: the key lives only in memory), the root's or
// the page's name with the link's host, and a CommandBar whose first command is the page's own write (a Memory's Add
// photos, a Pot's Add entry) and whose More holds Keep (signed in: a space picked, the personal space first), Report
// shared content (to the link's own operator) and Not you? —, no pane, no bar. Behind the gates (the password, the
// terms, the note — each the core's ShareGates state) it shows the member's own page of the kind with a guest's rights:
// a level's rows, the page its kind's application registered for a guest (Registry's KindPage.Guest: a file's, a
// Memory's, a Pot's) over the models the core registered for the link (ReceivedPage), or a WRITE_ONLY guest's page (its
// files picked, what it sent in this visit). A
// kept link's page shows the same view in place (BookmarkPage).
//
// Before any guest write the web's notice of the guest-write record stands under the head while the link writes
// (ReceivedShown.recordDays, ADR 0093 § 10, inbox #1018) as an InfoBar, its action opening the link's instance's
// privacy notice; a write that needs the guest's name asks it first (share_guest_name_*), the notice in the question too.
//
// What this side keeps of the link: nothing but the text the person pasted, held by the view only to offer *Open in the
// browser* where the core refuses the link's instance; no update carries the link or its key (ADR 0150 decision 7), and
// nothing here writes either anywhere or logs it.

namespace Veyq.Core;

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using ApplicationId = Veyq.Bridge.Wire.ApplicationId;
using Windows.Storage.Pickers;
using Windows.System;

internal sealed class ReceivedView : IDisposable
{
    /// <summary>How an opened link becomes the window's face: set once by the window (MainWindow), handed the link's id and,
    /// for a pasted one, the text to offer the browser where the app refuses its instance.</summary>
    public static Action<long, string?>? Presenter { get; set; }

    /// <summary>Shows received link <paramref name="received"/> as its own screen (<see cref="Presenter"/>).</summary>
    public static void Present(long received, string? browser)
    {
        if (Presenter is { } present) present(received, browser);
        else
        {
            Log.Write("a received link opened with no screen to show it");
            Act.Send(new Call.ReceivedClose { received = received });
        }
    }

    /// <summary>Whether *Open a link* shows now (a veyq: link opens no second one, DeepLinks).</summary>
    public static bool LinkAsked { get; private set; }

    /// <summary>*Open a link* over <paramref name="window"/>: the box, Paste, the core's reason, Open in the browser for what
    /// only a browser opens; the link opened shows as its own screen.</summary>
    public static async void OpenLink(Window window)
    {
        if (LinkAsked) return;
        LinkAsked = true;
        try
        {
            // the box's label above the row, so Paste sits beside the box itself
            var label = new TextBlock { Text = Words.LinkPasteHint(), TextWrapping = TextWrapping.Wrap };
            var box = new TextBox { MinWidth = 380, InputScope = UrlScope() };
            AutomationProperties.SetAutomationId(box, "open-link-text");
            AutomationProperties.SetName(box, Words.LinkPasteHint());
            var paste = new Button { Content = Words.LinkPaste() };
            AutomationProperties.SetAutomationId(paste, "open-link-paste");
            var field = new Grid { ColumnSpacing = 8 };
            field.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            field.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            field.Children.Add(box);
            Grid.SetColumn(paste, 1);
            field.Children.Add(paste);
            var problem = Critical("open-link-problem");
            var browser = new HyperlinkButton { Content = Words.LinkOpenInBrowser(), Visibility = Visibility.Collapsed, Padding = new Thickness(0) };
            AutomationProperties.SetAutomationId(browser, "open-link-browser");
            var body = new StackPanel { Spacing = 12 };
            body.Children.Add(label);
            body.Children.Add(field);
            body.Children.Add(problem);
            body.Children.Add(browser);
            var dialog = new ContentDialog
            {
                Title = Words.LinkOpen(),
                Content = body,
                PrimaryButtonText = Words.LinkOpen(),
                CloseButtonText = Words.EntryCancel(),
                DefaultButton = ContentDialogButton.Primary,
                IsPrimaryButtonEnabled = false,
            };
            AutomationProperties.SetAutomationId(dialog, "open-link");
            // the clipboard read on the tap only, never on its own (ADR 0150 decision 4)
            paste.Click += async (_, _) =>
            {
                try
                {
                    var content = Windows.ApplicationModel.DataTransfer.Clipboard.GetContent();
                    if (content.Contains(Windows.ApplicationModel.DataTransfer.StandardDataFormats.Text)) box.Text = await content.GetTextAsync();
                }
                catch (Exception e)
                {
                    Log.Write($"Open a link: the clipboard did not read ({e.GetType().Name})");
                }
            };
            box.TextChanged += (_, _) =>
            {
                dialog.IsPrimaryButtonEnabled = box.Text.Trim().Length > 0;
                problem.Visibility = Visibility.Collapsed;
                browser.Visibility = Visibility.Collapsed;
            };
            box.Loaded += (_, _) => box.Focus(FocusState.Programmatic);
            browser.Click += (_, _) => Launch(box.Text.Trim());
            long? opened = null;
            dialog.PrimaryButtonClick += async (_, args) =>
            {
                // the dialog stays while the core reads the link: it closes on an opened one, else says why
                var deferral = args.GetDeferral();
                try
                {
                    var text = box.Text;
                    if (text.Trim().Length == 0)
                    {
                        args.Cancel = true;
                        return;
                    }
                    var (answer, _) = await Core.CallAsync(new Call.ReceivedOpen { text = text });
                    switch (answer)
                    {
                        case Answer.ReceivedOpened o:
                            opened = o.received;
                            break;
                        case Answer.ReceivedProblem p:
                            args.Cancel = true;
                            problem.Text = ReceivedText.Said(p.problem);
                            problem.Visibility = Visibility.Visible;
                            browser.Visibility = p.browser ? Visibility.Visible : Visibility.Collapsed;
                            break;
                        default:
                            args.Cancel = true;
                            Log.Write($"Open a link answered {answer.GetType().Name}");
                            break;
                    }
                }
                catch (Exception e)
                {
                    args.Cancel = true;
                    Log.Write($"Open a link failed ({e.GetType().Name})");
                }
                finally
                {
                    deferral.Complete();
                }
            };
            await Dialogs.Show(window, dialog);
            LinkAsked = false;
            if (opened is { } received) Present(received, box.Text.Trim());
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"Open a link failed ({e.GetType().Name})");
        }
        finally
        {
            LinkAsked = false;
        }
    }

    private static InputScope UrlScope()
    {
        var scope = new InputScope();
        scope.Names.Add(new InputScopeName(InputScopeNameValue.Url));
        return scope;
    }

    /// <summary>Hands <paramref name="uri"/> to the system browser (what a click on the link in a chat would have done),
    /// never logged.</summary>
    internal static async void Launch(string uri)
    {
        try
        {
            if (!Uri.TryCreate(uri, UriKind.Absolute, out var u)) return;
            if (!await Launcher.LaunchUriAsync(u)) Log.Write("the browser did not open");
        }
        catch (Exception e)
        {
            Log.Write($"the browser did not open ({e.GetType().Name})");
        }
    }

    private static TextBlock Critical(string automationId)
    {
        var text = new TextBlock { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
        text.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        AutomationProperties.SetAutomationId(text, automationId);
        AutomationProperties.SetLiveSetting(text, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Assertive);
        return text;
    }

    public long Received { get; }

    public UIElement Page => _root;

    private readonly Window _window;
    private readonly ReceivedMode _mode;
    private readonly string? _browser;
    private Action? _onLeft;
    private readonly Grid _root = new();
    private readonly Button _back;
    private readonly TextBlock _title;
    private readonly TextBlock _subtitle;
    private readonly CommandBar _commands;
    private readonly InfoBar _notice;
    private readonly InfoBar _said;
    private readonly NoticeBar _notices;
    private readonly StackPanel _guestLine;
    private readonly TextBlock _guestText;
    private readonly ContentControl _content = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch };
    private Watch? _watch;
    private ReceivedShown? _shown;
    private string _drawn = "\0";
    /// <summary>The page of the link's kind, as its application registered it for a guest (Registry's KindPage.Guest).</summary>
    private KindBody? _page;
    private PasswordBox? _password;
    private Button? _unlock;
    private bool _leaveAsked;
    /// <summary>The core's naming dialog stands (ReceivedShown.naming, ADR 0154 decision 1).</summary>
    private bool _namingAsked;
    private bool _reportOpened;
    private bool _left;
    private bool _disposed;

    /// <summary>The screen of received link <paramref name="received"/> in <paramref name="mode"/> over
    /// <paramref name="window"/>; <paramref name="onLeft"/> runs once the link was let go.</summary>
    public ReceivedView(Window window, long received, ReceivedMode mode, string? browser, Action? onLeft)
    {
        _window = window;
        Received = received;
        _mode = mode;
        _browser = browser;
        _onLeft = onLeft;
        _back = new Button { Content = new FontIcon { Glyph = "", FontSize = 16 }, VerticalAlignment = VerticalAlignment.Center, Padding = new Thickness(10, 8, 10, 8) };
        AutomationProperties.SetAutomationId(_back, "received-back");
        AutomationProperties.SetName(_back, Words.Back());
        ToolTipService.SetToolTip(_back, Words.Back());
        _back.Click += (_, _) => Act.Send(new Call.ReceivedBack { received = Received });
        _title = new TextBlock { Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"], TextTrimming = TextTrimming.CharacterEllipsis };
        AutomationProperties.SetAutomationId(_title, "received-title");
        AutomationProperties.SetHeadingLevel(_title, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level1);
        _subtitle = new TextBlock
        {
            Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
            Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
            TextTrimming = TextTrimming.CharacterEllipsis,
        };
        AutomationProperties.SetAutomationId(_subtitle, "received-host");
        var titles = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        titles.Children.Add(_title);
        titles.Children.Add(_subtitle);
        _commands = new CommandBar { DefaultLabelPosition = CommandBarDefaultLabelPosition.Right, IsDynamicOverflowEnabled = true, VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetAutomationId(_commands, "received-commands");
        var head = new Grid { Padding = new Thickness(mode == ReceivedMode.OnItsOwn ? 12 : 4, 8, 8, 4), ColumnSpacing = 8 };
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.Children.Add(_back);
        Grid.SetColumn(titles, 1);
        head.Children.Add(titles);
        Grid.SetColumn(_commands, 2);
        head.Children.Add(_commands);
        // the guest-write record's notice (#1018): open while the link writes, never closable while it stands
        _notice = new InfoBar { IsOpen = false, IsClosable = false, Severity = InfoBarSeverity.Informational, Margin = new Thickness(12, 0, 12, 4) };
        AutomationProperties.SetAutomationId(_notice, "guest-write-record-notice");
        var more = new HyperlinkButton { Content = Words.ShareGuestRecordMore() };
        AutomationProperties.SetAutomationId(more, "guest-write-record-more");
        more.Click += (_, _) =>
        {
            if (_shown?.privacy is { } uri) Launch(uri);
        };
        _notice.ActionButton = more;
        _said = new InfoBar { IsOpen = false, IsClosable = true, Margin = new Thickness(12, 0, 12, 4) };
        AutomationProperties.SetAutomationId(_said, "received-said");
        _notices = new NoticeBar(_said);
        _guestText = new TextBlock { TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetAutomationId(_guestText, "received-guest");
        var notYou = new HyperlinkButton { Content = Words.ShareGuestNotYou(), VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetAutomationId(notYou, "received-not-you");
        notYou.Click += (_, _) => AskNotYou();
        _guestLine = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Margin = new Thickness(16, 0, 16, 4), Visibility = Visibility.Collapsed };
        _guestLine.Children.Add(_guestText);
        _guestLine.Children.Add(notYou);
        foreach (var _ in Enumerable.Range(0, 4)) _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        var row = 0;
        foreach (var e in new FrameworkElement[] { head, _notice, _guestLine, _said, _content })
        {
            Grid.SetRow(e, row++);
            _root.Children.Add(e);
        }
        AutomationProperties.SetAutomationId(_root, mode == ReceivedMode.OnItsOwn ? "received" : "received-in-place");
        // the window's own background on its own (the face it covers draws none of its own)
        if (mode == ReceivedMode.OnItsOwn) _root.Background = (Brush)Application.Current.Resources["SolidBackgroundFillColorBaseBrush"];
        Draw();
        _watch = Core.Watch(new Watched.Received { received = received }, Updated, e => Log.Write($"a received link's update: {e.Message}"));
    }

    private void Updated(Update update)
    {
        if (_disposed || update is not Update.Received u) return;
        if (u.shown is { } s)
        {
            // the gate and the page's kind alone: never a name, the host or the link
            Log.Write($"received: {s.gate}, page {s.page?.kind.ToString() ?? "-"}, at root {s.atRoot}, leaving {s.leaving}, record {(s.recordDays is null ? "-" : "set")}");
        }
        if (u.shown is { left: false } shown)
        {
            _shown = shown;
            Draw();
        }
        else Leave();
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _watch?.Dispose();
        _watch = null;
        DropPages();
        // on its own the screen's going lets go of the link (the window closed, another link over it); in place the kept
        // link's page does
        if (_mode == ReceivedMode.OnItsOwn && !_left) Act.Send(new Call.ReceivedClose { received = Received });
    }

    private void DropPages()
    {
        _page?.Dispose();
        _page = null;
    }

    /// <summary>The link let go: the screen returns where the person was (once).</summary>
    private void Leave()
    {
        if (_left) return;
        _left = true;
        DropPages();
        var f = _onLeft;
        _onLeft = null;
        f?.Invoke();
    }

    private void Draw()
    {
        if (_shown is not { } s)
        {
            _title.Text = "";
            _subtitle.Text = "";
            _back.Visibility = _mode == ReceivedMode.OnItsOwn ? Visibility.Visible : Visibility.Collapsed;
            _content.Content = Spinner();
            DrawCommands(null);
            return;
        }
        var ready = s.gate == ReceivedGate.Ready;
        var name = s.page?.name is { Length: > 0 } pageName ? pageName : s.rootName;
        _title.Text = ready && name.Length > 0 ? name : s.host;
        _subtitle.Text = ready ? s.host : "";
        _subtitle.Visibility = ready ? Visibility.Visible : Visibility.Collapsed;
        var offers = ReceivedText.Offers(s, _mode);
        _back.Visibility = offers.Back ? Visibility.Visible : Visibility.Collapsed;
        if (ReceivedText.RecordNotice(s.recordDays) is { } notice)
        {
            _notice.Message = notice;
            if (_notice.ActionButton is { } more) more.Visibility = s.privacy is null ? Visibility.Collapsed : Visibility.Visible;
            _notice.IsOpen = true;
        }
        else _notice.IsOpen = false;
        if (ready && s.guestName is { } guest)
        {
            _guestText.Text = Words.ShareGuestAs(guest);
            _guestLine.Visibility = Visibility.Visible;
        }
        else _guestLine.Visibility = Visibility.Collapsed;
        var key = ReceivedText.DrawnKey(s);
        if (key != _drawn)
        {
            _drawn = key;
            DrawContent(s);
        }
        if (_password is not null) _password.IsEnabled = !s.busy;
        if (_unlock is not null) _unlock.IsEnabled = !s.busy;
        DrawCommands(s);
        // a write waits for the guest's name (Replace, Add entry): the core asks it at the write, the app draws its dialog
        if (s.naming && !_namingAsked)
        {
            _namingAsked = true;
            AskNaming();
        }
        else if (!s.naming) _namingAsked = false;
        if (s.leaving && !_leaveAsked)
        {
            _leaveAsked = true;
            AskLeave();
        }
        else if (!s.leaving) _leaveAsked = false;
        if (s.reportAsked && ready && !_reportOpened)
        {
            _reportOpened = true;
            Act.Send(new Call.ReceivedReportShown { received = Received });
            Report();
        }
    }

    /// <summary>The CommandBar: the page's own write first (a Memory's Add photos, a Pot's Add entry, where it takes one),
    /// then Keep, the report and Not you? behind its More.</summary>
    private void DrawCommands(ReceivedShown? s)
    {
        _commands.PrimaryCommands.Clear();
        _commands.SecondaryCommands.Clear();
        if (s is null) return;
        // the page's own guest write (a Memory's Add photos, a Pot's Add entry), where it offers one
        if (_page?.GuestCreate() is { } write)
        {
            _commands.PrimaryCommands.Add(write);
        }
        else if (s.gate == ReceivedGate.Ready && s.page is { kind: ReceivedPageKind.Level } level && level.creates.Count > 0)
        {
            // a READ_WRITE guest's folder (#1034): core's plan (GuestLevel.creates) drawn whole — New folder, Upload files,
            // Upload folder (owner 2026-11-22); an unnamed guest is asked its name by the core at the write
            // (GuestLevel.createFolder, uploadAsking, writer: ReceivedShown.naming, AskNaming)
            _commands.PrimaryCommands.Add(CreateAction.Menu(Words.DrawerNew(), "received-new",
                level.creates.Select(c => (Said.Key(c.word), GuestCreateId(c.create), GuestCreating(c.create)))));
        }
        if (_page?.GuestReplaces is { } file && s.gate == ReceivedGate.Ready)
        {
            // a guest's Replace (#1035) where the core's FileRights offer it: on the link's own queue, signed in or out; an
            // unnamed guest is asked its name by the core at the write (Received.Replace waits on AskNaming's answer)
            _commands.PrimaryCommands.Add(Command(Words.DepotReplace(), "received-replace", Symbol.Upload, () => _page?.GuestReplace(file)));
        }
        var offers = ReceivedText.Offers(s, _mode);
        if (offers.Keep) _commands.SecondaryCommands.Add(Command(Words.LinkKeep(), "received-keep", Symbol.SaveLocal, Keep));
        if (offers.Report) _commands.SecondaryCommands.Add(Command(Words.BookmarkReportShare(), "received-report", Symbol.Flag, Report));
        if (offers.NotYou) _commands.SecondaryCommands.Add(Command(Words.ShareGuestNotYou(), "received-not-you-command", Symbol.Contact, AskNotYou));
        _commands.Visibility = _commands.PrimaryCommands.Count + _commands.SecondaryCommands.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private static AppBarButton Command(string label, string automationId, Symbol symbol, Action act)
    {
        var button = new AppBarButton { Label = label, Icon = new SymbolIcon(symbol) };
        AutomationProperties.SetAutomationId(button, automationId);
        button.Click += (_, _) => act();
        return button;
    }

    private void DrawContent(ReceivedShown s)
    {
        DropPages();
        _password = null;
        _unlock = null;
        switch (s.gate)
        {
            case ReceivedGate.Loading:
                _content.Content = Spinner();
                break;
            case ReceivedGate.Refused:
            {
                var column = Column();
                column.Children.Add(Body(ReceivedText.Refusal(s.refusal, s.host), "received-refused"));
                if (_browser is { } text)
                {
                    var browser = new Button { Content = Words.LinkOpenInBrowser() };
                    AutomationProperties.SetAutomationId(browser, "received-browser");
                    browser.Click += (_, _) => Launch(text);
                    column.Children.Add(browser);
                }
                _content.Content = Scrolled(column);
                break;
            }
            case ReceivedGate.Locked:
                DrawPassword(s);
                break;
            case ReceivedGate.Terms:
                _content.Content = Gate(Words.ShareTermsTitle(), Words.ShareTermsIntro(), [s.terms, s.operatorSection, s.record], "received-accept-terms",
                    () => Act.Send(new Call.ReceivedAcceptTerms { received = Received }));
                break;
            case ReceivedGate.Note:
                _content.Content = Gate(Words.ShareNoteTitle(), Words.ShareNoteIntro(), [s.note], "received-accept-note",
                    () => Act.Send(new Call.ReceivedAcceptNote { received = Received }));
                break;
            case ReceivedGate.Unavailable:
            {
                var column = Column();
                column.Children.Add(Heading(Words.ShareUnavailable()));
                column.Children.Add(Body(Words.ShareUnavailableHint(), "received-unavailable"));
                _content.Content = Scrolled(column);
                break;
            }
            case ReceivedGate.Ready:
                if (s.page is { } p) DrawPage(p);
                else _content.Content = Spinner();
                break;
        }
    }

    private void DrawPassword(ReceivedShown s)
    {
        var column = Column();
        column.Children.Add(Heading(Words.SharePasswordTitle()));
        column.Children.Add(Body(Words.SharePasswordIntro(), "received-password-intro"));
        var box = new PasswordBox { Header = Words.SharePasswordLabel() };
        AutomationProperties.SetAutomationId(box, "received-password");
        column.Children.Add(box);
        var wrong = Critical("received-password-wrong");
        wrong.Text = Words.SharePasswordWrong();
        wrong.Visibility = s.askedAgain ? Visibility.Visible : Visibility.Collapsed;
        column.Children.Add(wrong);
        var unlock = new Button { Content = Words.SharePasswordUnlock(), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetAutomationId(unlock, "received-unlock");
        void Send()
        {
            var password = box.Password;
            if (password.Length == 0) return;
            box.Password = "";
            Act.Send(new Call.ReceivedPassword { received = Received, password = password });
        }
        unlock.Click += (_, _) => Send();
        box.KeyDown += (_, e) =>
        {
            if (e.Key != VirtualKey.Enter) return;
            e.Handled = true;
            Send();
        };
        column.Children.Add(unlock);
        _content.Content = Scrolled(column);
        box.Loaded += (_, _) => box.Focus(FocusState.Programmatic);
        _password = box;
        _unlock = unlock;
    }

    private void DrawPage(ReceivedPage p)
    {
        var node = new NodeAt { scopeId = "", nodeId = "", kind = p.nodeKind, name = p.name };
        switch (p.kind)
        {
            case ReceivedPageKind.Level:
                _content.Content = p.rows.Count == 0 ? Scrolled(Empty(p.name, null)) : Rows(p.rows);
                break;
            case ReceivedPageKind.DropBox or ReceivedPageKind.Uploader:
                DrawUploadPage(p);
                break;
            case ReceivedPageKind.NotShown:
                _content.Content = Scrolled(Empty(p.name, Words.ShareNotShown()));
                break;
            default:
                // the page its kind's application registered for a guest, over the model the core registered for the link
                _page = Registry.Shared.Page(p.nodeKind)?.Guest?.Invoke(p, new GuestHost
                {
                    Window = _window,
                    Say = Say,
                    Said = _notices,
                    Node = node,
                    Received = Received,
                    OpenNode = id => Act.Send(new Call.ReceivedOpenNode { received = Received, node = id }),
                    Changed = () => DrawCommands(_shown),
                });
                _content.Content = _page is { } page ? page.Page : Spinner();
                break;
        }
    }

    /// <summary>A guest's level: its rows, each opening its kind's guest page.</summary>
    private UIElement Rows(IReadOnlyList<ReceivedRow> rows)
    {
        var list = new ListView { SelectionMode = ListViewSelectionMode.None, IsItemClickEnabled = true, Padding = new Thickness(12, 0, 12, 24) };
        AutomationProperties.SetAutomationId(list, "received-level");
        foreach (var r in rows)
        {
            var line = new Grid { ColumnSpacing = 12 };
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            line.Children.Add(HomeView.Glyph(HomeView.KindGlyph(r.kind)));
            var title = new TextBlock { Text = r.name, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(title, 1);
            line.Children.Add(title);
            var chevron = HomeView.Glyph("");
            chevron.FontSize = 12;
            Grid.SetColumn(chevron, 2);
            line.Children.Add(chevron);
            var item = new ListViewItem { Content = line, Tag = r.id, MinHeight = 44 };
            // a viewing link's row reported to the link's own operator (#1033: whole, or by its name below the root)
            if (_shown is { } s && ReceivedText.Offers(s, _mode).Report)
            {
                var report = new MenuFlyoutItem { Text = Words.ReportAction(), Icon = new SymbolIcon(Symbol.Flag) };
                AutomationProperties.SetAutomationId(report, $"received-row-report:{r.name}");
                var node = r.id;
                report.Click += (_, _) => ReportDialog.Open(_window, new Call.ReceivedReportNode { received = Received, node = node });
                var menu = new MenuFlyout();
                menu.Items.Add(report);
                item.ContextFlyout = menu;
            }
            AutomationProperties.SetAutomationId(item, $"received-row:{r.name}");
            AutomationProperties.SetName(item, r.name);
            list.Items.Add(item);
        }
        list.ItemClick += (_, e) =>
        {
            var row = e.ClickedItem as ListViewItem ?? (e.ClickedItem as FrameworkElement)?.Parent as ListViewItem;
            if (row?.Tag is string id) Act.Send(new Call.ReceivedOpenNode { received = Received, node = id });
        };
        return list;
    }

    /// <summary>A WRITE_ONLY guest's page (a drop box's, a Memory's uploader): its files picked, what it sent in this visit.</summary>
    private void DrawUploadPage(ReceivedPage p)
    {
        var column = Column();
        var dropBox = p.kind == ReceivedPageKind.DropBox;
        // the web's words of each WRITE_ONLY page: a drop box's, a Memory's uploader's
        column.Children.Add(Heading(p.name));
        column.Children.Add(Body(dropBox ? Words.ShareDropboxHint(p.name) : Words.ShareUploaderIntro(), "received-upload-intro"));
        var add = new Button { Content = dropBox ? Words.ShareDropboxPick() : Words.ShareAddPhotos(), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetAutomationId(add, "guest-upload");
        // the core asks the guest's name at the upload where it has none (uploadAsking, AskNaming; #1042)
        add.Click += (_, _) => PickAndUpload();
        column.Children.Add(add);
        if (p.sent.Count > 0)
        {
            if (dropBox)
            {
                var heading = new TextBlock { Text = Words.ShareDropboxSession(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
                AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
                column.Children.Add(heading);
            }
            var sent = new StackPanel { Spacing = 4 };
            AutomationProperties.SetAutomationId(sent, "guest-sent");
            foreach (var name in p.sent)
            {
                var line = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
                line.Children.Add(HomeView.Glyph(""));
                line.Children.Add(new TextBlock { Text = name, TextTrimming = TextTrimming.CharacterEllipsis });
                AutomationProperties.SetName(line, name);
                sent.Children.Add(line);
            }
            column.Children.Add(sent);
        }
        _content.Content = Scrolled(column);
    }

    /// <summary>What a guest's New entry <paramref name="c"/> begins; null for what a guest never makes (core's plan holds
    /// none of them).</summary>
    private Action? GuestCreating(Create c) => c switch
    {
        Create.Folder => NewFolder,
        Create.UploadFiles => PickAndUpload,
        Create.UploadFolder => PickFolderAndUpload,
        Create.Pot => null,
        Create.Memory => null,
        Create.Chapter => null,
        Create.Bookmark => null,
    };

    /// <summary>The automation id of a guest's New entry (the ids the passes read).</summary>
    private static string GuestCreateId(Create c) => c switch
    {
        Create.Folder => "received-new-folder",
        Create.UploadFiles => "received-upload",
        Create.UploadFolder => "received-upload-folder",
        Create.Pot => "received-new-pot",
        Create.Memory => "received-new-memory",
        Create.Chapter => "received-new-chapter",
        Create.Bookmark => "received-new-bookmark",
    };

    /// <summary>The folder picker, the directory's path to the core (Received.UploadFolder): walked there, its folders made
    /// and its files queued as the guest (owner 2026-11-22); every line it says, said.</summary>
    private async void PickFolderAndUpload()
    {
        try
        {
            var picker = new FolderPicker { SuggestedStartLocation = PickerLocationId.DocumentsLibrary };
            picker.FileTypeFilter.Add("*");
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var folder = await picker.PickSingleFolderAsync();
            if (folder is null) return;
            if (string.IsNullOrEmpty(folder.Path))
            {
                Say(Spoken.Own("depot_drop_not_local", Words.DepotDropNotLocal(folder.Name)));
                return;
            }
            Log.Write("guest upload folder: picked");
            var (answer, _) = await Core.CallAsync(new Call.ReceivedUploadFolder { received = Received, path = folder.Path });
            Uploads.Queued(answer, "Received.UploadFolder", Say);
        }
        catch (Exception e)
        {
            Log.Write($"a guest's folder upload failed ({e.GetType().Name})");
        }
    }

    /// <summary>The open picker (several files), their paths to the core (Received.Upload).</summary>
    private async void PickAndUpload()
    {
        try
        {
            var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.PicturesLibrary };
            picker.FileTypeFilter.Add("*");
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var picked = await picker.PickMultipleFilesAsync();
            if (picked is null || picked.Count == 0) return;
            // Received.Upload takes paths alone: a picked item with none (a phone's, a cloud's) is said by its name
            foreach (var f in picked.Where(f => string.IsNullOrEmpty(f.Path))) Say(Spoken.Own("depot_drop_not_local", Words.DepotDropNotLocal(f.Name)));
            var paths = picked.Select(f => f.Path).Where(p => !string.IsNullOrEmpty(p)).ToList();
            if (paths.Count == 0) return;
            Log.Write($"guest upload: {paths.Count} picked");
            var (answer, _) = await Core.CallAsync(new Call.ReceivedUpload { received = Received, paths = paths });
            if (answer is not Answer.Done)
            {
                Log.Write($"a guest's upload answered {answer.GetType().Name}");
                Say(Spoken.Own("share_failed", Words.ShareFailed()));
            }
        }
        catch (Exception e)
        {
            Log.Write($"a guest's upload failed ({e.GetType().Name})");
        }
    }

    /// <summary>A READ_WRITE guest's new folder in the folder on screen (#1034), named in a dialog the core's answer keeps
    /// open with its problem (ADR 0154 decision 1); its notice said as it closes.</summary>
    private async void NewFolder()
    {
        try
        {
            var named = await Dialogs.AskNamed(_window, "received-new-folder-dialog", Words.FolderNew(), Words.FolderName(), "", Words.FolderCreate(),
                typed => Dialogs.Named(new Call.ReceivedNewFolder { received = Received, name = typed.Trim() }, "Received.NewFolder"));
            if (named?.notice is { } n) Say(Spoken.Of(n));
        }
        catch (Exception e)
        {
            Log.Write($"a guest's new folder failed ({e.GetType().Name})");
        }
    }

    /// <summary>The core's naming at a write (GuestAccess.naming, ADR 0154 decision 1): the guest's name asked while a
    /// write waits on it — the web's hint and the guest-write record's notice (#1018) above the field —, the dialog kept
    /// open with the core's problem; Cancel declines, and the waiting write is not made.</summary>
    private async void AskNaming()
    {
        try
        {
            var body = new StackPanel { Spacing = 12 };
            body.Children.Add(new TextBlock { Text = Words.ShareGuestNameHint(), TextWrapping = TextWrapping.Wrap });
            if (ReceivedText.RecordNotice(_shown?.recordDays) is { } notice)
            {
                var said = new TextBlock { Text = notice, TextWrapping = TextWrapping.Wrap, Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"] };
                AutomationProperties.SetAutomationId(said, "guest-name-record-notice");
                body.Children.Add(said);
            }
            var named = await Dialogs.AskNamed(_window, "guest-name-dialog", Words.ShareGuestNameTitle(), Words.ShareYourName(), "", Words.ShareSave(),
                typed => Dialogs.Named(new Call.ReceivedNamingSave { received = Received, name = typed }, "Received.NamingSave"),
                body, Words.ShareYourNamePlaceholder());
            if (named is null)
            {
                Act.Send(new Call.ReceivedNamingDecline { received = Received });
                return;
            }
            if (named.notice is { } n) Say(Spoken.Of(n));
        }
        catch (Exception e)
        {
            Log.Write($"the guest's naming failed ({e.GetType().Name})");
            Act.Send(new Call.ReceivedNamingDecline { received = Received });
        }
    }

    private async void AskNotYou()
    {
        try
        {
            if (await Dialogs.Confirm(_window, "guest-not-you", Words.ShareGuestNotYouTitle(), Words.ShareGuestNotYouConfirm(_shown?.guestName ?? ""), Words.ShareGuestNotYouAccept()))
            {
                Act.Send(new Call.ReceivedForgetGuest { received = Received });
            }
        }
        catch (Exception e)
        {
            Log.Write($"Not you? failed ({e.GetType().Name})");
        }
    }

    /// <summary>Back at the root: the question, Leave or stay (Cancel the default — the key goes with Leave).</summary>
    private async void AskLeave()
    {
        try
        {
            var leave = await Dialogs.Confirm(_window, "received-leave", Words.LinkLeaveTitle(), Words.LinkLeaveBody(), Words.LinkLeaveAccept());
            Act.Send(leave ? new Call.ReceivedLeave { received = Received } : new Call.ReceivedStay { received = Received });
        }
        catch (Exception e)
        {
            Log.Write($"the leave question failed ({e.GetType().Name})");
        }
    }

    /// <summary>Keep: the places the core offers (the personal space first), one picked, the link kept at its top.</summary>
    private async void Keep()
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.ReceivedKeepPlaces { received = Received });
            if (answer is not Answer.KeepPlaces { places: { Count: > 0 } places })
            {
                Log.Write($"Keep's places answered {answer.GetType().Name}");
                return;
            }
            var choices = new RadioButtons { Header = Words.LinkKeepWhere() };
            AutomationProperties.SetAutomationId(choices, "received-keep-places");
            foreach (var p in places)
            {
                var name = Said.Piece(p.name);
                var item = new RadioButton { Content = name, Tag = p };
                AutomationProperties.SetAutomationId(item, $"keep-place:{name}");
                choices.Items.Add(item);
            }
            choices.SelectedIndex = 0;
            var dialog = new ContentDialog
            {
                Title = Words.LinkKeep(),
                Content = choices,
                PrimaryButtonText = Words.LinkKeep(),
                CloseButtonText = Words.EntryCancel(),
                DefaultButton = ContentDialogButton.Primary,
            };
            AutomationProperties.SetAutomationId(dialog, "received-keep-dialog");
            if (await Dialogs.Show(_window, dialog) != ContentDialogResult.Primary) return;
            if ((choices.SelectedItem as RadioButton)?.Tag is not ShownSpace place) return;
            var (kept, _) = await Core.CallAsync(new Call.ReceivedKeep { received = Received, scope = place.space.scopeId });
            if (kept is Answer.Done)
            {
                Say(Spoken.Own("link_kept", Words.LinkKept(Said.Piece(place.name))));
                // the shell's lists read anew, so the kept link's row is there when the person returns
                Act.Send(new Call.ShellRefresh());
            }
            else
            {
                Log.Write($"Keep answered {kept.GetType().Name}");
                Say(Spoken.Own("bookmark_create_failed", Words.BookmarkCreateFailed()));
            }
        }
        catch (Exception e)
        {
            Log.Write($"Keep failed ({e.GetType().Name})");
        }
    }

    /// <summary>Report shared content: the core's report sheet of the link, to its own operator (ReportDialog).</summary>
    private void Report() => ReportDialog.Open(_window, new Call.ReceivedReport { received = Received });

    /// <summary>What an action did, in the screen's own InfoBar, living as its kind says.</summary>
    private void Say(Spoken said) => _notices.Say(said);

    private static UIElement Spinner()
    {
        var ring = new ProgressRing { IsActive = true, Width = 32, Height = 32, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetAutomationId(ring, "received-loading");
        AutomationProperties.SetName(ring, Words.ShareLoading());
        return ring;
    }

    private static StackPanel Column() => new() { Spacing = 16, MaxWidth = 640, Padding = new Thickness(24), HorizontalAlignment = HorizontalAlignment.Stretch };

    /// <summary>A column centred in a scroller (a width-capped column straight in a ScrollViewer sat off centre).</summary>
    private static ScrollViewer Scrolled(StackPanel column)
    {
        var stretch = new Grid();
        stretch.Children.Add(column);
        return new ScrollViewer { Content = stretch, HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
    }

    private static TextBlock Heading(string text)
    {
        var heading = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"] };
        AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        return heading;
    }

    private static TextBlock Body(string text, string automationId)
    {
        var body = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true };
        AutomationProperties.SetAutomationId(body, automationId);
        return body;
    }

    private static StackPanel Empty(string name, string? said)
    {
        var column = Column();
        column.Children.Add(Heading(name));
        if (said is not null) column.Children.Add(Body(said, "received-not-shown"));
        return column;
    }

    /// <summary>A gate of texts to accept (the terms with their operator's section and record, or the note), then accept.</summary>
    private static ScrollViewer Gate(string title, string intro, string[] texts, string automationId, Action accept)
    {
        var column = Column();
        column.Children.Add(Heading(title));
        column.Children.Add(Body(intro, $"{automationId}-intro"));
        foreach (var t in texts.Where(t => t.Trim().Length > 0))
        {
            var border = new Border
            {
                Child = Body(t, $"{automationId}-text"),
                Padding = new Thickness(12),
                CornerRadius = new CornerRadius(4),
                BorderThickness = new Thickness(1),
                BorderBrush = (Brush)Application.Current.Resources["CardStrokeColorDefaultBrush"],
                Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"],
            };
            column.Children.Add(border);
        }
        var button = new Button { Content = Words.ShareAcceptContinue(), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetAutomationId(button, automationId);
        button.Click += (_, _) => accept();
        column.Children.Add(button);
        return Scrolled(column);
    }
}
