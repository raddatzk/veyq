// A node's header (ADR 0129 decision 2): core's NodeHeaderModel, opened per node on screen
// (Header.Open) and watched while it shows, drawn Fluent's way as Explorer draws a selection's commands — the plan's
// buttons as the CommandBar's primary commands (the heart as Favorite), the rest as its secondary ones behind its own
// More, the menu's last group after a separator —, rename and delete asked in a ContentDialog (Dialogs), the trash's
// Undo an InfoBar's action held for exactly as long as the core offers it (it closes when the state's undo does: no
// timer here). The move and the copy open core's sheet (MoveDialog), the share core's share sheet (ShareDialog), the report
// core's report sheet (ReportDialog), the cover its dialog (CoverDialog); a folder's download goes where the save picker says
// (Header.Download, under the core's archive name), a Pot's through its page (Pot.Download), a file's — the file itself,
// with its progress and Cancel — through its page (File.Save, FilePage). What is offered, in which
// order and with which words is the core's (HeaderPlan: what is drawn so far, and the bar's groups).
//
// The header also draws the node's head for its page (Hero): the cover across the page's width, 16:9, cut as stored
// (Header.CoverPicture, decoded by WIC, the part its crop shows by Cover.Cut: the core's math), as Android's and Apple's
// pages place it first in their scroll; invoked, it opens the cover dialog where the plan offers it. A node without a
// cover shows none (its cover is set from the bar).
using System;
using System.Collections.Generic;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

internal sealed class NodeHeader : IDisposable
{
    /// <summary>The most buttons beside New the bar holds before More (Fluent: a few primary commands).</summary>
    public const int MaxButtons = 2;

    private readonly Window _window;
    private readonly Action _changed;
    private readonly Action<bool, string> _say;
    private long? _id;
    private Watch? _watch;
    private Facts? _later;
    private bool _laterSet;
    private bool _closed;
    private bool _seenUndo;

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

    /// <summary>The InfoBar the Undo stands in, its action button.</summary>
    public InfoBar? UndoBar { get; set; }

    /// <summary>Opens the header of <paramref name="node"/>; <paramref name="changed"/> runs on each state (the bar is
    /// drawn anew), <paramref name="say"/> says what an action did.</summary>
    public NodeHeader(Window window, NodeAt node, Facts? facts, Action changed, Action<bool, string> say)
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
            if (Shown is { plan: { } plan } s && _id is { } header && new[] { plan.buttons, plan.menu, plan.last }.Any(g => g.Any(a => a.id == "cover")))
            {
                _heroRead = false; // a new picture may come under the crop it had
                CoverDialog.Open(_window, header, s.node, s.cover);
            }
        };
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
        if (_laterSet) Act.Send(new Call.HeaderFacts { header = header, facts = _later });
        _watch = Core.Watch(new Watched.Header { header = header }, update =>
        {
            if (_closed || update is not Update.Header h) return;
            Shown = h.shown;
            if (h.shown is { } s) Log.Write($"header {s.node.kind}: pinned {s.pinned}, undo {s.undo is not null}, gone {s.gone}, plan {Ids(s.plan)}");
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
                    if (UndoBar is { } bar)
                    {
                        bar.ActionButton = null;
                        bar.IsOpen = false;
                    }
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

    private static string Ids(Plan? p) => p is null ? "" : string.Join(" | ", new[] { p.buttons, p.menu, p.last }.Select(g => string.Join(",", g.Select(a => a.id))));

    /// <summary>What the kind's page knows now (a Pot read: whether it takes writes).</summary>
    public void Facts(Facts? facts)
    {
        if (_id is { } header) Act.Send(new Call.HeaderFacts { header = header, facts = facts });
        else (_later, _laterSet) = (facts, true);
    }

    /// <summary>The header's commands for the bar: its primary ones (the plan's buttons) and its secondary ones (the
    /// menu, a separator, the last group), from the plan's groups (HeaderPlan). <paramref name="pageDownload"/> is the
    /// page's own download where it has one (HeaderPlan.ByPage: a Pot's, a file's Save a copy).</summary>
    public (List<ICommandBarElement> Primary, List<ICommandBarElement> Secondary) Commands(Action pageDownload)
    {
        var primary = new List<ICommandBarElement>();
        var secondary = new List<ICommandBarElement>();
        if (Shown is not { gone: false, plan: { } plan } s) return (primary, secondary);
        var kind = s.node.kind;
        foreach (var a in HeaderPlan.Primary(plan, kind)) primary.Add(Command(a, s, pageDownload));
        foreach (var a in HeaderPlan.Secondary(plan, kind)) secondary.Add(a is null ? new AppBarSeparator() : Command(a, s, pageDownload));
        return (primary, secondary);
    }

    private AppBarButton Command(PlanAction a, HeaderShown s, Action pageDownload)
    {
        var label = a.word is { } w ? Veyq.Said.Key(w) : a.id;
        var icon = a.id switch
        {
            "favorite" => new SymbolIcon(s.pinned == true ? Symbol.UnFavorite : Symbol.Favorite),
            "download" => new SymbolIcon(Symbol.Download),
            "rename" => new SymbolIcon(Symbol.Rename),
            "delete" => new SymbolIcon(Symbol.Delete),
            "move" => new SymbolIcon(Symbol.MoveToFolder),
            "copy" => new SymbolIcon(Symbol.Copy),
            "share" => new SymbolIcon(Symbol.Share),
            "report" => new SymbolIcon(Symbol.Flag),
            "cover" => new SymbolIcon(Symbol.Pictures),
            _ => new SymbolIcon(Symbol.More),
        };
        var button = new AppBarButton { Icon = icon, Label = label };
        AutomationProperties.SetAutomationId(button, $"header:{a.id}");
        button.Click += (_, _) =>
        {
            switch (a.id)
            {
                case "favorite": Pin(); break;
                case "share": ShareDialog.Open(_window, s.node); break;
                case "rename": Rename(s.node.name, null); break;
                case "delete": Delete(); break;
                case "download" when HeaderPlan.ByPage(a.id, s.node.kind): pageDownload(); break;
                case "download": Download(); break;
                case "move": MoveDialog.Open(_window, s.node, copy: false, UndoBar); break;
                case "copy": MoveDialog.Open(_window, s.node, copy: true, UndoBar); break;
                case "report": ReportDialog.Open(_window, s.node); break;
                case "cover" when _id is { } header:
                    _heroRead = false; // a new picture may come under the crop it had
                    CoverDialog.Open(_window, header, s.node, s.cover);
                    break;
            }
        };
        return button;
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
                    _say(written, string.Join(" ", new[] { n, left }.OfType<Notice>().Select(Veyq.Said.Notice)));
                    break;
                case var other:
                    Log.Write($"Header.Download answered {other?.GetType().Name}");
                    _say(false, Words.DownloadFailed(name));
                    break;
            }
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"the download failed: {e.GetType().Name}: {e.Message}");
            _say(false, Words.DownloadFailed(name));
        }
    }

    private async void Pin()
    {
        if (_id is not { } header) return;
        Tell(await Ask(new Call.HeaderPin { header = header }), "Header.Pin");
    }

    private async void Rename(string name, string? problem)
    {
        if (_id is not { } header) return;
        var typed = await Dialogs.AskName(_window, "rename", Words.NodeRename(), Words.NodeName(), name, problem, Words.NodeRename());
        if (typed is null) return;
        switch (await Ask(new Call.HeaderRename { header = header, name = typed }))
        {
            case Answer.Renamed { problem: { } p }:
                Rename(typed, Veyq.Said.Notice(p)); // asked again, with the core's problem (the dialog closed first)
                break;
            case Answer.Renamed { notice: var n }:
                Log.Write("header rename: Renamed");
                if (n is not null) _say(true, Veyq.Said.Notice(n));
                break;
            case var other:
                Log.Write($"Header.Rename answered {other?.GetType().Name}");
                break;
        }
    }

    private async void Delete()
    {
        if (_id is not { } header || Shown is not { } s) return;
        var title = s.plan?.last.FirstOrDefault(a => a.id == "delete")?.word is { } w ? Veyq.Said.Key(w) : Words.EntryDelete();
        if (!await Dialogs.Confirm(_window, "delete", title, Veyq.Said.Notice(s.deleteQuestion), Words.EntryDelete())) return;
        switch (await Ask(new Call.HeaderTrash { header = header }))
        {
            case Answer.Trashed { notice: var n, undoable: var undoable }:
                Log.Write($"header trash: {n.msg}, undo offered: {undoable}");
                Offer(Veyq.Said.Notice(n), undoable, header);
                break;
            case var other:
                Tell(other, "Header.Trash");
                break;
        }
    }

    /// <summary>What the trash said, with Undo where the core offers it now: the InfoBar's action until the offer ends.</summary>
    private void Offer(string text, bool undoable, long header)
    {
        if (UndoBar is not { } bar) return;
        bar.Severity = InfoBarSeverity.Informational;
        bar.Message = text;
        if (undoable)
        {
            var undo = new Button { Content = Words.TrashUndo() };
            AutomationProperties.SetAutomationId(undo, "undo");
            undo.Click += async (_, _) =>
            {
                bar.ActionButton = null;
                bar.IsOpen = false;
                Undoing = false;
                var answered = await Ask(new Call.HeaderUndo { header = header });
                Log.Write($"header undo: {answered?.GetType().Name}");
                Tell(answered, "Header.Undo");
            };
            bar.ActionButton = undo;
            Undoing = true;
        }
        else bar.ActionButton = null;
        bar.IsOpen = true;
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
                _say(false, Veyq.Said.Notice(n));
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
