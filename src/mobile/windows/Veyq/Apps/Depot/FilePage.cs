// A file's page (Depot's file viewer; ADR 0123's Consequences, the Windows app's Depot whole; Linux's file_page.rs), Fluent's way: the file's
// content in place — a picture (Image, decoded by WIC at the core's edge at most), a text (a read-only monospace TextBox,
// the cut said in an InfoBar above it), a PDF (WebView2's own PDF viewer fed from memory, PdfWeb.cs and PdfWebView.cs,
// where its text is selected, copied and read by Narrator, a password-protected one's password asked by the viewer; on a
// device without WebView2's runtime through Windows.Data.Pdf, PdfPages.cs, after the page's own password prompt: its
// pages rendered as they come into view and let go when far from it), an audio or video played by the system's own player (MediaPlayerElement with its
// transport controls, over a MediaSource of ReaderStream: the core's reader read in ranges; Media Foundation and the
// Store's extensions decode it, and what they cannot is said as not shown here), or what the core says where it shows
// none — and its facts beneath. Everything is the core's FileModel (File.Open, Watched.File): what shows is decided by the
// bytes, and an image's and a PDF's bytes come beside the answer (File.Bytes, ADR 0137 decision 1), asked again when the
// content's revision turns; a medium is read through File.Reader (decision 2). Nothing decrypted is written to show it
// (ADR 0047).
//
// "Save a copy" is the header's download of a file: the save picker names the file, the core writes it there frame by
// frame (File.Save), and the shell's InfoBar says how far it is, with Cancel (ShellView.Saving: Watched.Save,
// Save.Cancel); the save goes on when the page leaves.

namespace Veyq.Apps.Depot;

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.Web.WebView2.Core;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Veyq.Core;
using Windows.Storage.Pickers;
using Windows.Storage.Streams;

internal sealed class FilePage : IDisposable
{
    /// <summary>The width a PDF's page is laid out at, in effective pixels.</summary>
    private const double PdfWidth = 720;

    private readonly Grid _root = new();
    private readonly ContentControl _content = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch };
    private readonly TextBlock _facts = new() { TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _uploader = new() { TextWrapping = TextWrapping.Wrap };
    private readonly StackPanel _factsPanel = new() { Spacing = 2, Padding = new Thickness(16, 8, 16, 12), Visibility = Visibility.Collapsed };
    private long? _id;
    private Watch? _watch;
    private FileShown? _shown;
    /// <summary>What the content shows now: its kind and the content's revision; drawn anew only when either changed.</summary>
    private (FileViewKind, int)? _drawn;
    private bool _closed;
    private PdfView? _pdf;
    /// <summary>The PDF in WebView2's viewer, closed (its browser ended, its folder deleted) when the content changes.</summary>
    private PdfWebView? _web;
    /// <summary>The medium playing over the core's reader.</summary>
    private MediumPlayer? _media;

    public NodeAt Node { get; }

    /// <summary>The model a received link's guest page handed in, opened by the core (null: File.Open opens it).</summary>
    private readonly long? _opened;

    public UIElement Page => _root;

    /// <summary>Told the header's facts each time the file is read (FileShown.headerFacts), with the page's id.</summary>
    public Action<Facts, long>? Read { get; set; }

    /// <summary>The page's id in the core, once it opened (File.Open).</summary>
    public long? Id => _id;

    /// <summary>File <paramref name="node"/>'s page; with <paramref name="opened"/>, over the model the core already opened
    /// (a received link's guest page, ReceivedPage.file), watched and closed with the page as a member's is.</summary>
    public FilePage(NodeAt node, long? opened = null)
    {
        Node = node;
        _opened = opened;
        _root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        Grid.SetRow(_content, 0);
        _root.Children.Add(_content);
        _uploader.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        AutomationProperties.SetAutomationId(_facts, "file-facts");
        AutomationProperties.SetAutomationId(_uploader, "file-uploader");
        _factsPanel.Children.Add(_facts);
        _factsPanel.Children.Add(_uploader);
        Grid.SetRow(_factsPanel, 1);
        _root.Children.Add(_factsPanel);
        _content.Content = Loading(Words.FileOpening());
        Open();
    }

    private async void Open()
    {
        Answer answer;
        try
        {
            (answer, _) = _opened is { } opened ? ((Answer)new Answer.FileOpened { file = opened }, (byte[]?)null) : await Core.CallAsync(new Call.FileOpen { node = Node });
        }
        catch (Exception e)
        {
            Log.Write($"the file did not open in the core ({e.GetType().Name})");
            return;
        }
        if (answer is not Answer.FileOpened { file: var file })
        {
            Log.Write($"the file did not open in the core ({answer.GetType().Name})");
            return;
        }
        if (_closed)
        {
            Act.Send(new Call.FileClose { file = file });
            return;
        }
        _id = file;
        _watch = Core.Watch(new Watched.File { file = file }, update =>
        {
            if (_closed || update is not Update.File { shown: { } s }) return;
            Log.Write($"file shown: {s.view}, revision {s.revision}, {s.mediaType ?? "-"}");
            _shown = s;
            if (s.headerFacts is { } facts && _id is { } id) Read?.Invoke(facts, id);
            Draw();
        }, e => Log.Write($"a file update: {e.Message}"));
    }

    public void Dispose()
    {
        _closed = true;
        _watch?.Dispose();
        _watch = null;
        _pdf = null;
        StopWeb();
        StopMedia();
        if (_id is { } file) Act.Send(new Call.FileClose { file = file });
        _id = null;
    }

    private void Draw()
    {
        if (_shown is not { } s) return;
        // the facts beneath: size, type and modified on one line, the uploader under it
        _facts.Text = Said.Line(s.facts);
        _uploader.Text = s.uploader is { } u ? Said.Notice(u) : "";
        _uploader.Visibility = s.uploader is null ? Visibility.Collapsed : Visibility.Visible;
        _factsPanel.Visibility = s.facts.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
        var now = (s.view, s.revision);
        if (_drawn == now) return;
        _drawn = now;
        _pdf = null;
        StopWeb();
        StopMedia();
        switch (s.view)
        {
            case FileViewKind.Loading:
                _content.Content = Loading(s.words is { } w ? Said.Key(w) : Words.FileOpening());
                break;
            case FileViewKind.Text:
                _content.Content = Text(s.text ?? "", s.truncated is { } cut ? Said.Notice(cut) : null);
                break;
            case FileViewKind.Image when s.mediaType == "image/svg+xml":
                _content.Content = Loading(Words.FileOpening());
                Load(now, (bytes, _) => ShowSvg(now, bytes));
                break;
            case FileViewKind.Image:
                _content.Content = Loading(Words.FileOpening());
                Load(now, async (bytes, _) => _content.Content = await Picture(bytes, s.edge ?? 4096));
                break;
            case FileViewKind.Pdf:
                _content.Content = Loading(Words.FileOpening());
                Load(now, (bytes, scale) => ShowPdf(now, bytes, scale, null));
                break;
            case FileViewKind.Media:
                _content.Content = Loading(Words.FileOpening());
                Play(now, s.words);
                break;
            default:
                // nothing shows here: what the core says, and Save a copy among the header's commands
                _content.Content = Message(s.words is { } m ? Said.Key(m) : "");
                break;
        }
    }

    /// <summary>The shown content's bytes (File.Bytes), handed to <paramref name="show"/> with the window's scale, unless
    /// another content was asked for meanwhile; what does not arrive is said as the core's failure.</summary>
    private async void Load((FileViewKind, int) asked, Func<byte[], double, Task> show)
    {
        if (_id is not { } file) return;
        try
        {
            var (answer, bytes) = await Core.CallAsync(new Call.FileBytes { file = file });
            if (_closed || _drawn != asked) return;
            if (answer is not Answer.Bytes { mediaType: var type } || bytes is null)
            {
                Log.Write($"File.Bytes answered {answer.GetType().Name}");
                _content.Content = Message(Words.FileOpenFailed());
                return;
            }
            Log.Write($"file bytes: {bytes.Length} B, {type ?? "no type"}");
            await show(bytes, _root.XamlRoot?.RasterizationScale ?? 1.0);
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"the file did not show: {e.GetType().Name}: {e.Message}");
            _content.Content = Message(Words.FileOpenFailed());
        }
    }

    /// <summary>The medium played through a reader the core opened for this page (File.Reader): Media Foundation reads it in
    /// ranges from its own threads; one the system cannot decode says the core's <paramref name="unplayable"/> words
    /// instead (Save a copy stays among the header's commands).</summary>
    private async void Play((FileViewKind, int) asked, string? unplayable)
    {
        if (_id is not { } file) return;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.FileReader { file = file });
            if (answer is not Answer.Reader { reader: var reader, size: var size, mediaType: var type })
            {
                Log.Write($"File.Reader answered {answer.GetType().Name}");
                if (!_closed && _drawn == asked) _content.Content = Message(Words.FileOpenFailed());
                return;
            }
            if (_closed || _drawn != asked)
            {
                // the page went or shows another content meanwhile: the reader is let go
                Act.Send(new Call.ReaderClose { reader = reader });
                return;
            }
            Log.Write($"file medium: reader {reader}, {size} B, {type ?? "no type"}");
            var player = new MediumPlayer(reader, size, type, "file-medium", Words.TabDepotFile(), false, _root.DispatcherQueue, () =>
            {
                if (_closed || _drawn != asked) return;
                StopMedia();
                MediumPlayer.Quietly("the words", () => _content.Content = Message(unplayable is { } w ? Said.Key(w) : Words.FileUnsupported()));
            });
            _media = player;
            _content.Content = player.Element;
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"the medium did not open: {e.GetType().Name}: {e.Message}");
            if (!_closed && _drawn == asked) _content.Content = Message(unplayable is { } w ? Said.Key(w) : Words.FileUnsupported());
        }
    }

    /// <summary>
    /// The PDF in <paramref name="bytes"/> shown, opened with <paramref name="password"/> where one was typed here
    /// (PdfWeb.Route, inbox #964). In WebView2's viewer where the device has it, a password-protected PDF too: the viewer
    /// asks for its password itself, once, as Edge does. Without it the pages are drawn by Windows.Data.Pdf, and a
    /// protected PDF asks for its password in the page first, as the web's does (FileViewer.vue): a PasswordBox whose
    /// value goes to Windows' renderer and nowhere else, emptied at each try; a wrong one said; Cancel says the PDF is
    /// protected and leaves Save a copy.
    /// </summary>
    private async Task ShowPdf((FileViewKind, int) asked, byte[] bytes, double scale, string? password)
    {
        var (pdf, locked) = await PdfView.OpenAsync(bytes, scale, password);
        if (_closed || _drawn != asked) return;
        var opening = pdf is not null ? PdfWeb.Opening.Opened : locked ? PdfWeb.Opening.Locked : PdfWeb.Opening.Failed;
        // a password typed here was asked because the viewer is not there: the pages, or the prompt again
        var shown = password is null ? PdfWeb.Route(opening, PdfWeb.Available(), asksPassword: true)
            : pdf is not null ? PdfWeb.Shown.Pages : locked ? PdfWeb.Shown.AskHere : PdfWeb.Shown.NotShown;
        if (shown == PdfWeb.Shown.Viewer)
        {
            var web = new PdfWebView(bytes, "file-pdf-web", Node.name);
            _web = web;
            _content.Content = web.Element;
            if (await web.Start())
            {
                Log.Write(locked ? "file PDF: in WebView2's viewer, which asks its password" : "file PDF: in WebView2's viewer");
                return;
            }
            if (_closed || _drawn != asked) return;
            StopWeb();
            shown = pdf is not null ? PdfWeb.Shown.Pages : PdfWeb.Shown.AskHere;
        }
        if (shown == PdfWeb.Shown.AskHere)
        {
            _content.Content = PdfPassword(Node.name, wrong: password is not null, typed => _ = ShowPdf(asked, bytes, scale, typed), () => _content.Content = Message(Words.FilePdfPasswordDeclined()));
            return;
        }
        if (shown != PdfWeb.Shown.Pages || pdf is null)
        {
            _content.Content = Message(Words.FileUnsupported());
            return;
        }
        _pdf = pdf;
        _content.Content = pdf.Scroll;
        await pdf.Start();
    }

    /// <summary>The prompt of a password-protected PDF: its name, the PasswordBox, Open and Cancel, the hint or a wrong one said.</summary>
    private static UIElement PdfPassword(string name, bool wrong, Action<string> open, Action cancel)
    {
        var column = new StackPanel { Spacing = 12, MaxWidth = 420, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Padding = new Thickness(24) };
        column.Children.Add(new TextBlock { Text = Words.FilePdfPasswordTitle(name), Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"], TextWrapping = TextWrapping.Wrap });
        var box = new PasswordBox { Header = Words.FilePdfPasswordLabel() };
        AutomationProperties.SetAutomationId(box, "file-pdf-password");
        column.Children.Add(box);
        var note = new TextBlock { Text = wrong ? Words.FilePdfPasswordWrong() : Words.FilePdfPasswordHint(), TextWrapping = TextWrapping.Wrap };
        if (wrong) note.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        AutomationProperties.SetAutomationId(note, "file-pdf-password-note");
        column.Children.Add(note);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var go = new Button { Content = Words.FilePdfPasswordOpen(), Style = (Style)Application.Current.Resources["AccentButtonStyle"], IsEnabled = false };
        AutomationProperties.SetAutomationId(go, "file-pdf-password-open");
        var back = new Button { Content = Words.FilePdfPasswordCancel() };
        AutomationProperties.SetAutomationId(back, "file-pdf-password-cancel");
        void Submit()
        {
            if (box.Password.Length == 0) return;
            var typed = box.Password;
            box.Password = "";
            open(typed);
        }
        box.PasswordChanged += (_, _) => go.IsEnabled = box.Password.Length > 0;
        box.KeyDown += (_, e) =>
        {
            if (e.Key != Windows.System.VirtualKey.Enter) return;
            e.Handled = true;
            Submit();
        };
        go.Click += (_, _) => Submit();
        back.Click += (_, _) =>
        {
            box.Password = "";
            cancel();
        };
        buttons.Children.Add(go);
        buttons.Children.Add(back);
        column.Children.Add(buttons);
        column.Loaded += (_, _) => box.Focus(FocusState.Programmatic);
        return column;
    }

    /// <summary>Stops the medium playing, if one is (MediumPlayer: the player let go first, then the core's reader).</summary>
    private void StopMedia()
    {
        var m = _media;
        _media = null;
        m?.Dispose();
    }

    private static UIElement Loading(string words)
    {
        var column = new StackPanel { Spacing = 12, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        column.Children.Add(new ProgressRing { IsActive = true });
        column.Children.Add(new TextBlock { Text = words, TextAlignment = TextAlignment.Center });
        AutomationProperties.SetAutomationId(column, "file-loading");
        return column;
    }

    /// <summary>What the page says in the content's place (too large, empty, not shown here, not uploaded yet, failed).</summary>
    private static UIElement Message(string words)
    {
        var column = new StackPanel { Spacing = 12, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Padding = new Thickness(24) };
        var glyph = HomeView.Glyph("");
        glyph.FontSize = 48;
        column.Children.Add(glyph);
        var text = new TextBlock { Text = words, TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center, MaxWidth = 480 };
        AutomationProperties.SetAutomationId(text, "file-message");
        column.Children.Add(text);
        return column;
    }

    /// <summary>A text, monospace, a line a row of a virtualized list (one TextBox of a megabyte drew only its first line,
    /// measured 2026-11-12), each line selectable; a cut one says so above it. The web's two conveniences (FileViewer.vue):
    /// Copy puts exactly what is shown on the clipboard (a cut text its shown part, which the button and its note say), and
    /// Wrap lines wraps long lines instead of scrolling sideways (on at first, as on the web).</summary>
    private static UIElement Text(string text, string? cut)
    {
        var grid = new Grid();
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        if (cut is not null)
        {
            var bar = new InfoBar { Message = cut, IsOpen = true, IsClosable = false, Severity = InfoBarSeverity.Informational, Margin = new Thickness(12, 8, 12, 0) };
            AutomationProperties.SetAutomationId(bar, "file-text-cut");
            grid.Children.Add(bar);
        }
        var lines = text.Split('\n').Select(l => l.TrimEnd('\r')).ToList();
        static DataTemplate Template(bool wrap) => (DataTemplate)Microsoft.UI.Xaml.Markup.XamlReader.Load(
            "<DataTemplate xmlns=\"http://schemas.microsoft.com/winfx/2006/xaml/presentation\">" +
            $"<TextBlock Text=\"{{Binding}}\" TextWrapping=\"{(wrap ? "Wrap" : "NoWrap")}\" IsTextSelectionEnabled=\"True\" Margin=\"12,0,12,0\" FontFamily=\"Cascadia Mono, Consolas\" /></DataTemplate>");
        var rows = new ListView
        {
            ItemsSource = lines,
            SelectionMode = ListViewSelectionMode.None,
            IsItemClickEnabled = false,
            Margin = new Thickness(0, 8, 0, 8),
            ItemTemplate = Template(true),
        };
        var row = new Style(typeof(ListViewItem));
        row.Setters.Add(new Setter(FrameworkElement.MinHeightProperty, 0d));
        rows.ItemContainerStyle = row;
        AutomationProperties.SetAutomationId(rows, "file-text");
        AutomationProperties.SetName(rows, Words.TabDepotFile());

        var bar2 = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, Margin = new Thickness(12, 8, 12, 0) };
        var copy = new Button { Content = cut is null ? Words.FileTextCopy() : Words.FileTextCopyShown() };
        AutomationProperties.SetAutomationId(copy, "file-text-copy");
        var copied = new TextBlock { VerticalAlignment = VerticalAlignment.Center, Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"] };
        AutomationProperties.SetAutomationId(copied, "file-text-copied");
        var wrap = new ToggleSwitch { Header = null, OnContent = Words.FileTextWrap(), OffContent = Words.FileTextWrap(), IsOn = true };
        AutomationProperties.SetName(wrap, Words.FileTextWrap());
        AutomationProperties.SetAutomationId(wrap, "file-text-wrap");
        DispatcherQueueTimer? clear = null;
        copy.Click += (_, _) =>
        {
            var package = new Windows.ApplicationModel.DataTransfer.DataPackage();
            package.SetText(text);
            Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(package);
            copied.Text = cut is null ? Words.FileTextCopied() : Words.FileTextCopiedShown();
            Log.Write($"file text copied: {text.Length} characters");
            clear?.Stop();
            // "copied" shows for the core's while (Notice.COPIED_MS, the web's)
            if (Lives.Copied is not { } life) return;
            clear = copied.DispatcherQueue.CreateTimer();
            clear.Interval = life;
            clear.IsRepeating = false;
            clear.Tick += (_, _) => copied.Text = "";
            clear.Start();
        };
        wrap.Toggled += (_, _) =>
        {
            rows.ItemTemplate = Template(wrap.IsOn);
            ScrollViewer.SetHorizontalScrollMode(rows, wrap.IsOn ? ScrollMode.Disabled : ScrollMode.Enabled);
            ScrollViewer.SetHorizontalScrollBarVisibility(rows, wrap.IsOn ? ScrollBarVisibility.Disabled : ScrollBarVisibility.Auto);
        };
        bar2.Children.Add(copy);
        bar2.Children.Add(wrap);
        bar2.Children.Add(copied);
        Grid.SetRow(bar2, 1);
        grid.Children.Add(bar2);
        Grid.SetRow(rows, 2);
        grid.Children.Add(rows);
        Log.Write($"file text shown: {text.Length} characters, {lines.Count} lines");
        return grid;
    }

    /// <summary>A picture decoded by WIC from the bytes, at <paramref name="edge"/> pixels on its longest side at most (the
    /// core's FileView.IMAGE_EDGE), shown at most its own size; one WIC cannot decode is said as not shown here.</summary>
    private static async Task<UIElement> Picture(byte[] bytes, int edge)
    {
        var image = new BitmapImage();
        try
        {
            using var stream = new InMemoryRandomAccessStream();
            await stream.WriteAsync(bytes.AsBuffer());
            // its size read first (the header only), so a large one is decoded to the edge, never whole
            stream.Seek(0);
            var decoder = await Windows.Graphics.Imaging.BitmapDecoder.CreateAsync(stream);
            var (w, h) = (decoder.OrientedPixelWidth, decoder.OrientedPixelHeight);
            if (Math.Max(w, h) > edge)
            {
                if (w >= h) image.DecodePixelWidth = edge;
                else image.DecodePixelHeight = edge;
            }
            stream.Seek(0);
            await image.SetSourceAsync(stream);
            Log.Write($"file picture decoded: {w}×{h}, held at {image.PixelWidth}×{image.PixelHeight}");
            var shown = new Image { Source = image, Stretch = Stretch.Uniform, MaxWidth = w, MaxHeight = h, Margin = new Thickness(12) };
            AutomationProperties.SetAutomationId(shown, "file-image");
            AutomationProperties.SetName(shown, $"{w}×{h}");
            return shown;
        }
        catch (Exception e)
        {
            Log.Write($"the file's picture did not decode ({e.GetType().Name})");
            return Message(Words.FileUnsupported());
        }
    }

    /// <summary>"Save a copy": the save picker (owned by <paramref name="window"/>) suggests the core's name in Downloads,
    /// the core writes the file it chose (File.Save), and <paramref name="saving"/> follows the save by its id.</summary>
    public async void Save(nint window, Action<Spoken> say, Action<long> saving)
    {
        if (_id is not { } file) return;
        var name = _shown?.downloadName ?? Node.name;
        try
        {
            var picker = new FileSavePicker { SuggestedStartLocation = PickerLocationId.Downloads, SuggestedFileName = Path.GetFileNameWithoutExtension(name) };
            var extension = Path.GetExtension(name);
            picker.FileTypeChoices.Add(Words.TabDepotFile(), [extension.Length > 1 ? extension : ".bin"]);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, window);
            var chosen = await picker.PickSaveFileAsync();
            if (chosen is null) return;
            var (answer, _) = await Core.CallAsync(new Call.FileSave { file = file, path = chosen.Path });
            if (answer is Answer.Saving { save: var save })
            {
                Log.Write($"saving a copy to {chosen.Path} (save {save})");
                saving(save);
                return;
            }
            Log.Write($"File.Save answered {answer.GetType().Name}");
            say(Spoken.Own("download_failed", Words.DownloadFailed(name)));
        }
        catch (Exception e)
        {
            Log.Write($"the copy failed: {e.GetType().Name}: {e.Message}");
            say(Spoken.Own("download_failed", Words.DownloadFailed(name)));
        }
    }

    private void StopWeb()
    {
        _web?.Close();
        _web = null;
        _svg?.Close();
        _svg = null;
    }

    /// <summary>The SVG in WebView2's image mode (SvgWeb), with the zoom's buttons; closed when the content changes.</summary>
    private SvgWebView? _svg;

    /// <summary>An SVG shown in the core's page (File.SvgPage) by WebView2's image mode; where WebView2 is missing or does
    /// not start, it is said as not shown here (Save a copy stays among the header's commands).</summary>
    private async Task ShowSvg((FileViewKind, int) asked, byte[] bytes)
    {
        var (answer, _) = await Core.CallAsync(new Call.FileSvgPage());
        if (_closed || _drawn != asked) return;
        if (answer is not Answer.SvgPage page || !PdfWeb.Available())
        {
            _content.Content = Message(Words.FileUnsupported());
            return;
        }
        var web = new SvgWebView(SvgWeb.From(page), bytes);
        _svg = web;
        _content.Content = web.Element;
        if (await web.Start())
        {
            Log.Write($"file SVG: in WebView2's image mode, {bytes.Length} B");
            return;
        }
        if (_closed || _drawn != asked) return;
        StopWeb();
        _content.Content = Message(Words.FileUnsupported());
    }

    /// <summary>An SVG in WebView2's image mode (SvgWeb) under the zoom's buttons: a browser of its own, InPrivate, in a
    /// user-data folder made for it and deleted once the browser has exited, the bytes served from memory. A zoom step runs
    /// the core's script for it (the app's own; the page's script is off), which lays the picture out again at that scale.</summary>
    private sealed class SvgWebView
    {
        private readonly SvgPage _page;
        private readonly byte[] _bytes;
        private readonly WebView2 _control = new() { HorizontalAlignment = HorizontalAlignment.Stretch, VerticalAlignment = VerticalAlignment.Stretch };
        private readonly Grid _root = new();
        private readonly Button _out = new() { Content = new SymbolIcon(Symbol.ZoomOut) };
        private readonly Button _in = new() { Content = new SymbolIcon(Symbol.ZoomIn) };
        private readonly Button _fit = new() { Content = new FontIcon { Glyph = "" } };
        private readonly TextBlock _level = new() { VerticalAlignment = VerticalAlignment.Center, MinWidth = 48, TextAlignment = TextAlignment.Center };
        private string? _folder;
        private CoreWebView2Environment? _environment;
        private int _step;
        private bool _closed;

        public SvgWebView(SvgPage page, byte[] bytes)
        {
            _page = page;
            _bytes = bytes;
            AutomationProperties.SetAutomationId(_control, "file-svg-web");
            foreach (var (button, name, id) in new[] { (_out, Words.FileZoomOut(), "file-svg-zoom-out"), (_in, Words.FileZoomIn(), "file-svg-zoom-in"), (_fit, Words.FileZoomFit(), "file-svg-zoom-fit") })
            {
                AutomationProperties.SetName(button, name);
                AutomationProperties.SetAutomationId(button, id);
                ToolTipService.SetToolTip(button, name);
            }
            _out.Click += (_, _) => Zoom(_step - 1);
            _in.Click += (_, _) => Zoom(_step + 1);
            _fit.Click += (_, _) => Zoom(0);
            var bar = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 0, 0, 8) };
            bar.Children.Add(_out);
            bar.Children.Add(_level);
            bar.Children.Add(_in);
            bar.Children.Add(_fit);
            _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            _root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            Grid.SetRow(_control, 1);
            _root.Children.Add(bar);
            _root.Children.Add(_control);
            Steps();
        }

        public UIElement Element => _root;

        private void Steps()
        {
            _level.Text = $"{Math.Round(_page.Steps[_step] * 100)} %";
            _out.IsEnabled = _step > 0;
            _in.IsEnabled = _step < _page.Steps.Length - 1;
            _fit.IsEnabled = _step > 0;
        }

        private async void Zoom(int step)
        {
            _step = Math.Clamp(step, 0, _page.Steps.Length - 1);
            Steps();
            try
            {
                if (_control.CoreWebView2 is { } core) await core.ExecuteScriptAsync(_page.Script(_step));
            }
            catch (Exception e)
            {
                Log.Write($"svg view: the zoom did not apply ({e.GetType().Name})");
            }
        }

        /// <summary>The view started on the SVG; false where WebView2 did not start.</summary>
        public async Task<bool> Start()
        {
            try
            {
                _folder = PdfWeb.NewFolder();
                _environment = await PdfWeb.EnvironmentAsync(_folder);
                if (_closed)
                {
                    Close();
                    return true;
                }
                await _control.EnsureCoreWebView2Async(_environment, PdfWeb.ControllerOptions(_environment));
                if (_closed)
                {
                    Close();
                    return true;
                }
                SvgWeb.Show(_environment, _control.CoreWebView2, _page, _bytes);
                return true;
            }
            catch (Exception e)
            {
                Log.Write($"WebView2's SVG view did not start ({e.GetType().Name}: {e.Message})");
                Close();
                return false;
            }
        }

        /// <summary>The view closed; its folder deleted once its browser has exited.</summary>
        public void Close()
        {
            _closed = true;
            var folder = _folder;
            var environment = _environment;
            _folder = null;
            _environment = null;
            if (folder is null) return;
            if (environment is null)
            {
                _ = PdfWeb.DeleteAsync(folder);
                return;
            }
            var queue = _control.DispatcherQueue;
            environment.BrowserProcessExited += (_, _) => queue.TryEnqueue(() => _ = PdfWeb.DeleteAsync(folder));
            try
            {
                _control.Close();
            }
            catch (Exception e)
            {
                Log.Write($"WebView2's SVG view did not close ({e.GetType().Name})");
            }
        }
    }

    /// <summary>A PDF's pages in a scroll, each a frame of its page's size at first; a page is rendered (Windows.Data.Pdf,
    /// from memory) when it comes near the view and let go again when it is far from it, so a long PDF holds a few pages'
    /// pixels, not all.</summary>
    private sealed class PdfView
    {
        private readonly Windows.Data.Pdf.PdfDocument _pdf;
        private readonly StackPanel _column = new() { Spacing = 12, Padding = new Thickness(12), MaxWidth = PdfWidth + 24 };
        private readonly List<Image> _pages = [];
        private readonly HashSet<int> _held = [];
        private readonly HashSet<int> _rendering = [];
        private readonly uint _pixels;

        public ScrollViewer Scroll { get; } = new() { HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };

        private PdfView(Windows.Data.Pdf.PdfDocument pdf, double scale)
        {
            _pdf = pdf;
            _pixels = (uint)Math.Round(PdfWidth * scale);
            var stretch = new Grid();
            stretch.Children.Add(_column);
            Scroll.Content = stretch;
            AutomationProperties.SetAutomationId(Scroll, "file-pdf");
        }

        /// <summary>The PDF opened (with <paramref name="password"/> where one was typed), or none and whether it is its
        /// password that kept it shut.</summary>
        public static async Task<(PdfView? View, bool Locked)> OpenAsync(byte[] bytes, double scale, string? password)
        {
            try
            {
                return (new PdfView(await PdfPages.OpenAsync(bytes, password), scale), false);
            }
            catch (Exception e)
            {
                var locked = PdfPages.NeedsPassword(e);
                Log.Write($"the file's PDF did not open ({e.GetType().Name}{(locked ? ", its password" : "")})");
                return (null, locked);
            }
        }

        /// <summary>The pages the core lets show (File.Pages) laid out as frames, then drawn as they come into view.</summary>
        public async Task Start()
        {
            var total = (int)_pdf.PageCount;
            var (answer, _) = await Core.CallAsync(new Call.FilePages { total = total });
            if (answer is not Answer.ReceiptPages pages)
            {
                Log.Write($"File.Pages answered {answer.GetType().Name}");
                return;
            }
            for (var i = 0; i < pages.shown; i++)
            {
                var size = _pdf.GetPage((uint)i).Size;
                var frame = new Image
                {
                    Width = PdfWidth,
                    Height = size.Width > 0 ? Math.Round(PdfWidth * size.Height / size.Width) : PdfWidth,
                    Stretch = Stretch.Uniform,
                    HorizontalAlignment = HorizontalAlignment.Center,
                };
                AutomationProperties.SetAutomationId(frame, $"file-pdf-page:{i}");
                AutomationProperties.SetName(frame, $"{i + 1} / {total}");
                _column.Children.Add(frame);
                _pages.Add(frame);
            }
            if (pages.cut is { } cut)
            {
                var note = new TextBlock { Text = Said.Notice(cut), TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"] };
                AutomationProperties.SetAutomationId(note, "file-pages-cut");
                _column.Children.Add(note);
            }
            Log.Write($"file PDF: {_pages.Count} of {total} pages laid out");
            Scroll.ViewChanged += (_, _) => Near();
            Scroll.SizeChanged += (_, _) => Near();
            _column.LayoutUpdated += First;
        }

        private void First(object? sender, object e)
        {
            _column.LayoutUpdated -= First;
            Near();
        }

        /// <summary>Renders the pages within a screen of the view and lets go of those more than three screens away.</summary>
        private async void Near()
        {
            try
            {
                var (top, page) = (Scroll.VerticalOffset, Math.Max(1, Scroll.ViewportHeight));
                var changed = false;
                for (var i = 0; i < _pages.Count; i++)
                {
                    var frame = _pages[i];
                    var y0 = frame.TransformToVisual(_column).TransformPoint(new Windows.Foundation.Point(0, 0)).Y;
                    var y1 = y0 + frame.ActualHeight;
                    var near = y1 >= top - page && y0 <= top + 2 * page;
                    var far = y1 < top - 3 * page || y0 > top + 4 * page;
                    if (near && !_held.Contains(i) && _rendering.Add(i))
                    {
                        using var png = await PdfPages.RenderAsync(_pdf, (uint)i, _pixels);
                        var image = new BitmapImage();
                        await image.SetSourceAsync(png);
                        frame.Source = image;
                        _held.Add(i);
                        _rendering.Remove(i);
                        changed = true;
                    }
                    else if (far && _held.Remove(i))
                    {
                        frame.Source = null;
                        changed = true;
                    }
                }
                if (changed) Log.Write($"file PDF pages held: {string.Join(",", _held.Order().Select(i => i + 1))}");
            }
            catch (Exception e)
            {
                Log.Write($"a page of the file's PDF did not render ({e.GetType().Name}: {e.Message})");
            }
        }
    }
}
