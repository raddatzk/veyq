// A node's header (ADR 0161 decision 2): core's NodeHeaderModel, opened per node on screen
// (Header.Open) and watched while it shows, drawn Fluent's way as Explorer draws a selection's commands — the plan's
// buttons as the CommandBar's primary commands (the heart as Favorite), the rest as its secondary ones behind its own
// More, the menu's last group after a separator —, rename and delete asked in a ContentDialog (Dialogs), the trash's
// Undo an InfoBar's action held for exactly as long as the core offers it (it closes when the state's undo does: no
// timer here). The move and the copy open core's sheet (MoveDialog), the share core's share sheet (ShareDialog), the report
// core's report sheet (ReportDialog), the cover its dialog (CoverDialog); a folder's download goes where the save picker says
// (Header.Download, under the core's archive name), a Pot's through its page (Pot.Download), a file's — the file itself,
// with its progress and Cancel — through its page (File.Save, FilePage). What is offered, in which
// order and with which words is the core's: every entry of the plan is drawn, in its order (HeaderPlan: the bar's groups;
// ADR 0154 decision 2) — a page's own entries (a Memory's banner and Add photos, a file's Replace, a kept link's Open on its
// own page and Report shared content) done by that page (PageHooks). A level row's menu runs its entries through a header
// of that row's node (Run, RowMenu).
//
// "Available offline" (ADR 0140 decision 10) is an AppBarToggleButton among the secondary commands, checked as the core's
// offline.on says, the pin's status line under it ("Downloading 3 MB of 12 MB", "On this device"); turning it on asks first,
// the size it keeps in a ContentDialog (the core's asking and question), turning it off does not.
//
// The header also draws the node's head for its page (Hero): the cover across the page's width, 16:9, cut as stored
// (Header.CoverPicture, decoded by WIC, the part its crop shows by Cover.Cut: the core's math), as Android's and Apple's
// pages place it first in their scroll; invoked, it opens the cover dialog where the plan offers it. A node without a
// cover shows none (its cover is set from the bar).

namespace Veyq.Core;

using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

internal sealed class NodeHeader : IDisposable
{
    /// <summary>The most buttons beside New the bar holds before More (Fluent: a few primary commands).</summary>
    public const int MaxButtons = 2;

    private readonly Window _window;
    private readonly Action _changed;
    private readonly Action<Spoken> _say;
    private long? _id;
    private Watch? _watch;
    private Facts? _facts;
    private long? _file;
    private long? _gallery;
    private long? _bookmark;
    private bool _factsPending;
    private bool _closed;
    private bool _seenUndo;
    private bool _offlineAsked;

    public NodeAt Node { get; }

    private readonly Button _hero;
    private readonly Image _heroImage = new() { Stretch = Microsoft.UI.Xaml.Media.Stretch.UniformToFill };
    private Crop? _heroCrop;
    private bool _heroRead;
    private int _heroAsked;

    /// <summary>The node's head for its page: the cover, cut as stored; collapsed while there is none.</summary>
    public UIElement Hero => _hero;

    /// <summary>The header's state now; null until the core delivered it.</summary>
    public HeaderShown? Shown { get; private set; }

    /// <summary>Whether the trash's Undo stands: the header is kept until it is over.</summary>
    public bool Undoing { get; private set; }

    /// <summary>The notice bar the Undo stands in, its action button.</summary>
    public NoticeBar? UndoBar { get; set; }

    /// <summary>Opens the header of <paramref name="node"/>; <paramref name="changed"/> runs on each state (the bar is
    /// drawn anew), <paramref name="say"/> says what an action did.</summary>
    public NodeHeader(Window window, NodeAt node, Facts? facts, Action changed, Action<Spoken> say)
    {
        _window = window;
        Node = node;
        _changed = changed;
        _say = say;
        _hero = new Button
        {
            Content = _heroImage,
            Padding = new Thickness(0),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            VerticalContentAlignment = VerticalAlignment.Stretch,
            CornerRadius = new CornerRadius(8),
            Margin = new Thickness(12, 4, 12, 8),
            Visibility = Visibility.Collapsed,
        };
        AutomationProperties.SetAutomationId(_hero, "hero");
        AutomationProperties.SetName(_hero, Words.CoverChange());
        ToolTipService.SetToolTip(_hero, Words.CoverChange());
        // 16:9 at whatever width the page gives it
        _hero.SizeChanged += (_, e) => { if (e.NewSize.Width > 0) _hero.Height = e.NewSize.Width * 9 / 16; };
        _hero.Click += (_, _) =>
        {
            if (Shown is { plan: { } plan } s && new[] { plan.buttons, plan.menu, plan.last }.Any(g => g.Any(a => a.id == HeaderAction.Cover))) OpenCover(s);
        };
        _facts = facts;
        Open(facts);
    }

    private async void Open(Facts? facts)
    {
        Answer answer;
        try
        {
            (answer, _) = await Core.CallAsync(new Call.HeaderOpen { node = Node, facts = facts, maxButtons = MaxButtons });
        }
        catch (Exception e)
        {
            Log.Write($"the header did not open in the core ({e.GetType().Name})");
            return;
        }
        if (answer is not Answer.HeaderOpened { header: var header })
        {
            Log.Write($"the header did not open in the core ({answer.GetType().Name})");
            return;
        }
        if (_closed)
        {
            Act.Send(new Call.HeaderClose { header = header });
            return;
        }
        _id = header;
        if (_factsPending) SendFacts();
        _watch = Core.Watch(new Watched.Header { header = header }, update =>
        {
            if (_closed || update is not Update.Header h) return;
            Shown = h.shown;
            if (h.shown is { } s) Log.Write($"header {s.node.kind}: pinned {s.pinned}, offline {s.offline?.status}{(s.offline?.asking is not null ? " asking" : "")}, undo {s.undo is not null}, gone {s.gone}, plan {Ids(s.plan)}");
            AskOffline(h.shown?.offline);
            // the offer ended (its window, or the undo): the Undo goes with it — once a state showed the offer, so an
            // update from before the trash that arrives after its answer ends nothing
            DrawHero(h.shown?.cover);
            if (h.shown?.undo is not null) _seenUndo = true;
            else if (_seenUndo)
            {
                _seenUndo = false;
                if (Undoing)
                {
                    Undoing = false;
                    Log.Write("header undo offer ended");
                    UndoBar?.EndOffer();
                }
            }
            _changed();
        }, e => Log.Write($"a header update: {e.Message}"));
    }

    /// <summary>The head follows the state's cover: read again (its picture, then the part its crop shows) when the crop
    /// changed or the cover dialog was opened; the latest read's answer is drawn.</summary>
    private async void DrawHero(Crop? cover)
    {
        if (_heroRead && Equals(cover, _heroCrop)) return;
        _heroRead = true;
        _heroCrop = cover;
        var n = ++_heroAsked;
        if (cover is null || _id is not { } header)
        {
            _hero.Visibility = Visibility.Collapsed;
            _heroImage.Source = null;
            return;
        }
        try
        {
            var (answer, bytes) = await Core.CallAsync(new Call.HeaderCoverPicture { header = header });
            if (answer is not Answer.Bytes || bytes is null)
            {
                Log.Write($"cover hero: Header.CoverPicture answered {answer.GetType().Name}");
                return;
            }
            using var stream = new Windows.Storage.Streams.InMemoryRandomAccessStream();
            await stream.WriteAsync(System.Runtime.InteropServices.WindowsRuntime.WindowsRuntimeBufferExtensions.AsBuffer(bytes));
            stream.Seek(0);
            var decoder = await Windows.Graphics.Imaging.BitmapDecoder.CreateAsync(stream);
            var (width, height) = ((int)decoder.OrientedPixelWidth, (int)decoder.OrientedPixelHeight);
            var (cut, _) = await Core.CallAsync(new Call.CoverCut { crop = cover, width = width, height = height });
            if (cut is not Answer.CoverCut c)
            {
                Log.Write($"cover hero: Cover.Cut answered {cut.GetType().Name}");
                return;
            }
            var transform = new Windows.Graphics.Imaging.BitmapTransform
            {
                Bounds = new Windows.Graphics.Imaging.BitmapBounds { X = (uint)c.x, Y = (uint)c.y, Width = (uint)c.width, Height = (uint)c.height },
            };
            var bitmap = await decoder.GetSoftwareBitmapAsync(Windows.Graphics.Imaging.BitmapPixelFormat.Bgra8, Windows.Graphics.Imaging.BitmapAlphaMode.Premultiplied, transform,
                Windows.Graphics.Imaging.ExifOrientationMode.RespectExifOrientation, Windows.Graphics.Imaging.ColorManagementMode.ColorManageToSRgb);
            if (n != _heroAsked || _closed) return;
            var source = new Microsoft.UI.Xaml.Media.Imaging.SoftwareBitmapSource();
            await source.SetBitmapAsync(bitmap);
            if (n != _heroAsked || _closed) return;
            _heroImage.Source = source;
            _hero.Visibility = Visibility.Visible;
            Log.Write($"cover hero: {width}×{height}, the crop's {c.width}×{c.height} at {c.x},{c.y}");
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"cover hero: not drawn ({e.GetType().Name}: {e.Message})");
        }
    }

    private static string Ids(Plan? p) => p is null ? "" : string.Join(" | ", new[] { p.buttons, p.menu, p.last }.Select(g => string.Join(",", g.Select(a => HeaderPlan.Wire(a.id)))));

    /// <summary>What the kind's page knows now (a Pot read: whether it takes writes) and, for a file's page, its id
    /// <paramref name="file"/>, for a Memory's or a chapter's its gallery's <paramref name="gallery"/>: the page's own
    /// entries (Replace, Add photos, the banner) join the plan.</summary>
    public void Facts(Facts? facts, long? file = null, long? gallery = null)
    {
        (_facts, _file, _gallery) = (facts, file, gallery);
        SendFacts();
    }

    /// <summary>A kept link's page, opened as <paramref name="bookmark"/> (Bookmark.Open): its own entries (Open on its own
    /// page, Report shared content) join the plan once its meta reads.</summary>
    public void Bookmark(long bookmark)
    {
        _bookmark = bookmark;
        SendFacts();
    }

    private void SendFacts()
    {
        if (_id is { } header) Act.Send(new Call.HeaderFacts { header = header, facts = _facts, file = _file, gallery = _gallery, bookmark = _bookmark });
        else _factsPending = true;
    }

    /// <summary>The header's commands for the bar: its primary ones (the plan's buttons) and its secondary ones (the
    /// menu, a separator, the last group) — every entry of the plan, in its order (HeaderPlan); a page's own entries done
    /// by <paramref name="page"/>.</summary>
    public (List<ICommandBarElement> Primary, List<ICommandBarElement> Secondary) Commands(PageHooks page)
    {
        var primary = new List<ICommandBarElement>();
        var secondary = new List<ICommandBarElement>();
        if (Shown is not { gone: false, plan: { } plan } s) return (primary, secondary);
        foreach (var a in HeaderPlan.Primary(plan)) primary.AddRange(Draw(a, s, page));
        foreach (var a in HeaderPlan.Secondary(plan))
        {
            if (a is null) secondary.Add(new AppBarSeparator());
            else secondary.AddRange(Draw(a, s, page));
        }
        return (primary, secondary);
    }

    /// <summary>An entry's controls: "Available offline" a toggle with its status line, every other a command.</summary>
    private IEnumerable<ICommandBarElement> Draw(PlanAction a, HeaderShown s, PageHooks page) => a.id switch
    {
        HeaderAction.Offline => Offline(a, s),
        HeaderAction.Favorite or HeaderAction.Share or HeaderAction.Rename or HeaderAction.Move or HeaderAction.Copy
            or HeaderAction.Cover or HeaderAction.Download or HeaderAction.Report or HeaderAction.Delete or HeaderAction.Upload
            or HeaderAction.Music or HeaderAction.Banner or HeaderAction.Replace or HeaderAction.Send or HeaderAction.OpenOwnPage
            or HeaderAction.ReportShared => [Command(a, s, page)],
    };

    /// <summary>"Available offline": a toggle checked as the core holds it — a click asks the core, whose state redraws it
    /// — and the pin's status line under it where it has one.</summary>
    private IEnumerable<ICommandBarElement> Offline(PlanAction a, HeaderShown s)
    {
        var on = s.offline?.on == true;
        var toggle = new AppBarToggleButton { Icon = new SymbolIcon(Symbol.Download), Label = a.word is { } w ? Veyq.Core.Said.Key(w) : HeaderPlan.Wire(a.id), IsChecked = on };
        AutomationProperties.SetAutomationId(toggle, "header:offline");
        // Checked/Unchecked, not Click: UI Automation's Toggle (a screen reader's) raises no Click. The core's state, not
        // the toggle, checks it: put back at once, the next state redraws it
        var putBack = false;
        void Toggled(object sender, RoutedEventArgs e)
        {
            if (putBack) return;
            putBack = true;
            toggle.IsChecked = on;
            putBack = false;
            ToggleOffline();
        }
        toggle.Checked += Toggled;
        toggle.Unchecked += Toggled;
        yield return toggle;
        if (s.offline?.said is { } line)
        {
            var text = new TextBlock
            {
                Text = Veyq.Core.Said.Notice(line),
                Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
                Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
                Margin = new Thickness(44, 0, 12, 6),
                TextWrapping = TextWrapping.Wrap,
            };
            AutomationProperties.SetAutomationId(text, "header:offline-status");
            yield return new AppBarElementContainer { Content = text, IsTabStop = false };
        }
    }

    private async void ToggleOffline()
    {
        if (_id is not { } header) return;
        Tell(await Ask(new Call.HeaderOffline { header = header }), "Header.Offline");
    }

    /// <summary>The pin's question while the state asks it (the size it keeps): "Make available" confirms, Cancel leaves it;
    /// asked once per asking.</summary>
    private async void AskOffline(OfflineToggle? toggle)
    {
        if (toggle is not { asking: not null, question: { } question })
        {
            _offlineAsked = false;
            return;
        }
        if (_offlineAsked || _id is not { } header) return;
        _offlineAsked = true;
        Log.Write($"header offline: asked {question.msg}");
        var confirmed = await Dialogs.Ask(_window, "offline-ask", Words.OfflinePinAskTitle(), Veyq.Core.Said.Notice(question), Words.OfflinePinConfirm());
        if (_closed) return;
        Tell(await Ask(confirmed ? new Call.HeaderOfflineConfirm { header = header } : new Call.HeaderOfflineCancel { header = header }),
            confirmed ? "Header.OfflineConfirm" : "Header.OfflineCancel");
    }

    private AppBarButton Command(PlanAction a, HeaderShown s, PageHooks page)
    {
        var id = HeaderPlan.Wire(a.id);
        var button = new AppBarButton { Icon = Icon(a, s), Label = a.word is { } w ? Veyq.Core.Said.Key(w) : id };
        AutomationProperties.SetAutomationId(button, $"header:{id}");
        button.Click += (_, _) => Run(a.id, page);
        return button;
    }

    /// <summary>An entry's icon, Fluent's symbol for what it does.</summary>
    public static IconElement Icon(PlanAction a, HeaderShown? s) => a.id switch
    {
        HeaderAction.Favorite => new SymbolIcon(s?.pinned == true ? Symbol.UnFavorite : Symbol.Favorite),
        HeaderAction.Share => new SymbolIcon(Symbol.Share),
        HeaderAction.Rename => new SymbolIcon(Symbol.Rename),
        HeaderAction.Move => new SymbolIcon(Symbol.MoveToFolder),
        HeaderAction.Copy => new SymbolIcon(Symbol.Copy),
        HeaderAction.Cover => new SymbolIcon(Symbol.Pictures),
        HeaderAction.Download or HeaderAction.Offline => new SymbolIcon(Symbol.Download),
        HeaderAction.Report or HeaderAction.ReportShared => new SymbolIcon(Symbol.Flag),
        HeaderAction.Delete => new SymbolIcon(Symbol.Delete),
        HeaderAction.Upload or HeaderAction.Replace => new SymbolIcon(Symbol.Upload),
        // the music's play, or its stop while this node's music is the player's queue (the core's word says which)
        HeaderAction.Music => new SymbolIcon(a.word == "music_stop" ? Symbol.Stop : Symbol.Audio),
        // a Memory's banner (ADR 0144 decision 5): Segoe Fluent Icons' picture
        HeaderAction.Banner => new FontIcon { Glyph = "\uE91B" },
        HeaderAction.Send => new SymbolIcon(Symbol.Send),
        HeaderAction.OpenOwnPage => new SymbolIcon(Symbol.NewWindow),
    };

    /// <summary>Does entry <paramref name="id"/> for this header's node: core's through its calls and sheets, a page's own
    /// through <paramref name="page"/> (a row's menu has none: PageHooks.None, the header's own download serving it).</summary>
    public void Run(HeaderAction id, PageHooks page)
    {
        if (Shown is not { } s) return;
        Action act = id switch
        {
            HeaderAction.Favorite => Pin,
            HeaderAction.Share => () => ShareDialog.Open(_window, s.node),
            HeaderAction.Rename => () => Rename(s.node.name),
            HeaderAction.Move => () => MoveDialog.Open(_window, s.node, copy: false, UndoBar),
            HeaderAction.Copy => () => MoveDialog.Open(_window, s.node, copy: true, UndoBar),
            HeaderAction.Cover => () => OpenCover(s),
            HeaderAction.Download => page.Download is { } byPage && HeaderPlan.DownloadsByPage(s.node.kind) ? byPage : Download,
            HeaderAction.Offline => ToggleOffline,
            HeaderAction.Report => () => ReportDialog.Open(_window, s.node),
            HeaderAction.Delete => Delete,
            HeaderAction.Upload => ByPage(page.Upload, id),
            HeaderAction.Music => ByPage(page.Music, id),
            HeaderAction.Banner => ByPage(page.Banner, id),
            HeaderAction.Replace => ByPage(page.Replace, id),
            // "Send a copy" is a phone's share sheet: the core plans none where the platform hands nothing on (FileModel.shares)
            HeaderAction.Send => ByPage(null, id),
            HeaderAction.OpenOwnPage => ByPage(page.OwnPage, id),
            HeaderAction.ReportShared => ByPage(page.ReportShared, id),
        };
        act();
    }

    /// <summary>A page's own entry: what the page does, or — where no page does it here — a line in the log.</summary>
    private static Action ByPage(Action? doing, HeaderAction id) => doing ?? (() => Log.Write($"header {HeaderPlan.Wire(id)}: no page does it here"));

    private void OpenCover(HeaderShown s)
    {
        if (_id is not { } header) return;
        _heroRead = false; // a new picture may come under the crop it had
        CoverDialog.Open(_window, header, s.node, s.cover);
    }

    /// <summary>The node as the web's ZIP: the save picker suggests the core's name, the core writes the file it chose and
    /// removes it when it did not finish; what the core said (with what it left out) under the bar.</summary>
    private async void Download()
    {
        if (_id is not { } header || Shown is not { } s) return;
        var name = s.node.name;
        try
        {
            var picker = new Windows.Storage.Pickers.FileSavePicker { SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.Downloads, SuggestedFileName = s.archiveName };
            picker.FileTypeChoices.Add(Words.DownloadTypeZip(), [System.IO.Path.GetExtension(s.archiveName)]);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var file = await picker.PickSaveFileAsync();
            if (file is null) return;
            switch (await Ask(new Call.HeaderDownload { header = header, path = file.Path, deletedName = Words.PotDeletedPerson() }))
            {
                case Answer.Downloaded { written: var written, notice: var n, leftOut: var left }:
                    Log.Write($"header download: {n.msg}, left out: {left is not null}");
                    var notices = new[] { n, left }.OfType<Notice>().ToList();
                    _say(Spoken.Of(notices, string.Join(" ", notices.Select(Veyq.Core.Said.Notice))));
                    break;
                case var other:
                    Log.Write($"Header.Download answered {other?.GetType().Name}");
                    _say(Spoken.Own("download_failed", Words.DownloadFailed(name)));
                    break;
            }
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"the download failed: {e.GetType().Name}: {e.Message}");
            _say(Spoken.Own("download_failed", Words.DownloadFailed(name)));
        }
    }

    private async void Pin()
    {
        if (_id is not { } header) return;
        Tell(await Ask(new Call.HeaderPin { header = header }), "Header.Pin");
    }

    /// <summary>Rename (Header.Rename) in a dialog the core's answer keeps open with its problem (ADR 0154 decision 1);
    /// its notice said as it closes.</summary>
    private async void Rename(string name)
    {
        if (_id is not { } header) return;
        try
        {
            var named = await Dialogs.AskNamed(_window, "rename", Words.NodeRename(), Words.NodeName(), name, Words.NodeRename(),
                typed => Dialogs.Named(new Call.HeaderRename { header = header, name = typed }, "Header.Rename"));
            if (named?.notice is { } n) _say(Spoken.Of(n));
        }
        catch (Exception e)
        {
            Log.Write($"header rename failed ({e.GetType().Name})");
        }
    }

    private async void Delete()
    {
        if (_id is not { } header || Shown is not { } s) return;
        var title = s.plan?.last.FirstOrDefault(a => a.id == HeaderAction.Delete)?.word is { } w ? Veyq.Core.Said.Key(w) : Words.EntryDelete();
        if (!await Dialogs.Confirm(_window, "delete", title, Veyq.Core.Said.Notice(s.deleteQuestion), Words.EntryDelete())) return;
        switch (await Ask(new Call.HeaderTrash { header = header }))
        {
            case Answer.Trashed { notice: var n, undoable: var undoable }:
                Log.Write($"header trash: {n.msg}, undo offered: {undoable}");
                Offer(n, undoable, header);
                break;
            case var other:
                Tell(other, "Header.Trash");
                break;
        }
    }

    /// <summary>What the trash said, with Undo where the core offers it now: the bar's action until the offer ends.
    /// Without the offer the notice lives as its kind says (a trash without Undo: said briefly).</summary>
    private void Offer(Notice said, bool undoable, long header)
    {
        if (UndoBar is not { } bar) return;
        if (!undoable)
        {
            bar.Say(Spoken.Of(said));
            return;
        }
        var undo = new Button { Content = Words.TrashUndo() };
        AutomationProperties.SetAutomationId(undo, "undo");
        undo.Click += async (_, _) =>
        {
            bar.EndOffer();
            Undoing = false;
            var answered = await Ask(new Call.HeaderUndo { header = header });
            Log.Write($"header undo: {answered?.GetType().Name}");
            Tell(answered, "Header.Undo");
        };
        bar.Offer(Spoken.Of(said), undo);
        Undoing = true;
    }

    private void Tell(Answer? answered, string what)
    {
        switch (answered)
        {
            case Answer.Said { notice: null } or Answer.Done:
                Log.Write($"{what}: Done");
                break;
            case Answer.Said { notice: { } n }:
                Log.Write($"{what}: {n.msg}");
                _say(Spoken.Of(n));
                break;
            default:
                Log.Write($"{what} answered {answered?.GetType().Name}");
                break;
        }
    }

    private static async System.Threading.Tasks.Task<Answer?> Ask(Call call)
    {
        try
        {
            return (await Core.CallAsync(call)).Answer;
        }
        catch (Exception e)
        {
            Log.Write($"{call.GetType().Name} failed: {e.GetType().Name}");
            return null;
        }
    }

    public void Dispose()
    {
        _closed = true;
        _watch?.Dispose();
        _watch = null;
        if (_id is { } header) Act.Send(new Call.HeaderClose { header = header });
        _id = null;
    }
}

/// <summary>What a page does for its own entries of a header's plan (ADR 0154 decision 2): a Pot's and a file's download, a
/// file's Replace, a Memory's Add photos, its music and its banner, a kept link's Open on its own page and Report shared
/// content. Null: no page does it here (a row's menu: None).</summary>
internal sealed record PageHooks
{
    public static readonly PageHooks None = new();

    public Action? Download { get; init; }
    public Action? Replace { get; init; }
    public Action? Upload { get; init; }
    public Action? Music { get; init; }
    public Action? Banner { get; init; }
    public Action? OwnPage { get; init; }
    public Action? ReportShared { get; init; }
}
