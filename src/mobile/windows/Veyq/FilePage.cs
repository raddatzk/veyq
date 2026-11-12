// A file's page (Depot's file viewer; the Depot CR's D1, D4 and D10; Linux's file_page.rs), Fluent's way: the file's
// content in place — a picture (Image, decoded by WIC at the core's edge at most), a text (a read-only monospace TextBox,
// the cut said in an InfoBar above it), a PDF (Windows.Data.Pdf, PdfPages.cs: its pages rendered as they come into view
// and let go when far from it), or what the core says where it shows none — and its facts beneath. Everything is the core's
// FileModel (File.Open, Watched.File): what shows is decided by the bytes, and an image's and a PDF's bytes come beside the
// answer (File.Bytes, ADR 0130 decision 1), asked again when the content's revision turns. Nothing decrypted is written
// to show it (ADR 0047).
//
// "Save a copy" is the header's download of a file: the save picker names the file, the core writes it there frame by
// frame (File.Save), and the shell's InfoBar says how far it is, with Cancel (ShellView.Saving: Watched.Save,
// Save.Cancel); the save goes on when the page leaves.
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Storage.Pickers;
using Windows.Storage.Streams;

namespace Veyq;

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

    public NodeAt Node { get; }

    public UIElement Page => _root;

    /// <summary>Told the header's facts each time the file is read (FileShown.headerFacts).</summary>
    public Action<Facts>? Read { get; set; }

    public FilePage(NodeAt node)
    {
        Node = node;
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
            (answer, _) = await Core.CallAsync(new Call.FileOpen { node = Node });
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
            if (s.headerFacts is { } facts) Read?.Invoke(facts);
            Draw();
        }, e => Log.Write($"a file update: {e.Message}"));
    }

    public void Dispose()
    {
        _closed = true;
        _watch?.Dispose();
        _watch = null;
        _pdf = null;
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
        switch (s.view)
        {
            case FileViewKind.Loading:
                _content.Content = Loading(s.words is { } w ? Said.Key(w) : Words.FileOpening());
                break;
            case FileViewKind.Text:
                _content.Content = Text(s.text ?? "", s.truncated is { } cut ? Said.Notice(cut) : null);
                break;
            case FileViewKind.Image:
                _content.Content = Loading(Words.FileOpening());
                Load(now, async (bytes, _) => _content.Content = await Picture(bytes, s.edge ?? 4096));
                break;
            case FileViewKind.Pdf:
                _content.Content = Loading(Words.FileOpening());
                Load(now, async (bytes, scale) =>
                {
                    var pdf = await PdfView.OpenAsync(bytes, scale);
                    if (pdf is null)
                    {
                        _content.Content = Message(Words.FileUnsupported());
                        return;
                    }
                    _pdf = pdf;
                    _content.Content = pdf.Scroll;
                    await pdf.Start();
                });
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
    /// measured 2026-11-12); a cut one says so above it.</summary>
    private static UIElement Text(string text, string? cut)
    {
        var grid = new Grid();
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        if (cut is not null)
        {
            var bar = new InfoBar { Message = cut, IsOpen = true, IsClosable = false, Severity = InfoBarSeverity.Informational, Margin = new Thickness(12, 8, 12, 0) };
            AutomationProperties.SetAutomationId(bar, "file-text-cut");
            grid.Children.Add(bar);
        }
        var lines = text.Split('\n').Select(l => l.TrimEnd('\r')).ToList();
        var rows = new ListView
        {
            ItemsSource = lines,
            SelectionMode = ListViewSelectionMode.None,
            IsItemClickEnabled = false,
            Margin = new Thickness(0, 8, 0, 8),
            ItemTemplate = (DataTemplate)Microsoft.UI.Xaml.Markup.XamlReader.Load(
                "<DataTemplate xmlns=\"http://schemas.microsoft.com/winfx/2006/xaml/presentation\">" +
                "<TextBlock Text=\"{Binding}\" TextWrapping=\"Wrap\" Margin=\"12,0,12,0\" FontFamily=\"Cascadia Mono, Consolas\" /></DataTemplate>"),
        };
        var row = new Style(typeof(ListViewItem));
        row.Setters.Add(new Setter(FrameworkElement.MinHeightProperty, 0d));
        rows.ItemContainerStyle = row;
        AutomationProperties.SetAutomationId(rows, "file-text");
        AutomationProperties.SetName(rows, Words.TabDepotFile());
        Grid.SetRow(rows, 1);
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
    public async void Save(nint window, Action<bool, string> say, Action<long> saving)
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
            say(false, Words.DownloadFailed(name));
        }
        catch (Exception e)
        {
            Log.Write($"the copy failed: {e.GetType().Name}: {e.Message}");
            say(false, Words.DownloadFailed(name));
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

        public static async Task<PdfView?> OpenAsync(byte[] bytes, double scale)
        {
            try
            {
                return new PdfView(await PdfPages.OpenAsync(bytes), scale);
            }
            catch (Exception e)
            {
                Log.Write($"the file's PDF did not open ({e.GetType().Name})");
                return null;
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
