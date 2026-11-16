// A Memory's or a chapter's page (ADR 0136 decision 2; ADR 0123's Consequences, the Windows app; Linux's
// memory_page.rs, rule for rule), Fluent's way: the node's head (its cover, NodeHeader.Hero), the count, a Memory's chapters
// as a list of rows with their covers, and the media as the web's justified grid (ADR 0136's Consequences) — every tile's frame the core's
// (Gallery.Layout: GalleryModel.layout), only the rows near the view placed on the grid's Canvas (the core's
// Layout.window, ADR 0105), laid out anew when the width or the scroll changes; each tile the grid tier's thumbnail decoded
// by WIC (Gallery.Tile, held small and dropped least recently shown first, Recent), a video's with a play badge. A tile
// opens the lightbox (LightboxView) in the page's place. The page's "Add photos" (the header's own upload) opens the open
// picker for photos and videos, and a drop of files onto the page adds them (Memory.Upload) — a dropped folder is said and
// not taken (the owner, 2026-11-13). A medium trashed (from the lightbox) is out at once with Undo in the shell's
// InfoBar for as long as the core offers it. The page is read anew live while it stands (Watched.GalleryLive, the web's
// poll).
//
// Under its head the facts line (its date and place, each opening its editor where whoever looks may edit its meta:
// MemoryFacts, ADR 0143 decision 5), then a SelectorBar switching between its photos, its Music view where the node has
// music or whoever looks may add some (GalleryShown.musicView; MusicView, ADR 0142 decision 6: Fluent's SelectorBar, Linux's
// AdwToggleGroup) and its Map view, always (MemoryMapView, ADR 0143 decision 8); the header's own music action plays or
// stops the node's music (Gallery.Music).
//
// Every state is the core's GalleryModel (Gallery.Open, Watched.Gallery): the rows, the chapters, what it offers (rights
// only remove functions: share view = member view). Nothing decrypted is written to show it (ADR 0047).
using System;
using System.Collections.Generic;
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
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;
using Windows.Storage.Pickers;
using Windows.Storage.Streams;
using ApplicationId = Veyq.Bridge.Wire.ApplicationId;

namespace Veyq;

internal sealed class MemoryPage : IDisposable
{
    /// <summary>The page's margin around the grid and the chapters, in effective pixels (Fluent's 16).</summary>
    private const double Margin = 16;

    /// <summary>The longest side a tile's decoded thumbnail is held at (Linux's TILE_EDGE).</summary>
    private const int TileEdge = 400;

    /// <summary>How many decoded tiles are held at once (Linux's TILES_HELD).</summary>
    private const int TilesHeld = 400;

    /// <summary>The file types the open picker offers for "Add photos": what the system and the core take as a photo or a
    /// video (the core leaves out, and says, whatever is none).</summary>
    private static readonly string[] MediaTypes =
        [".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif", ".avif", ".tif", ".tiff", ".bmp", ".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi", ".3gp", ".wmv"];

    private readonly Window _window;
    private readonly Action<bool, string> _say;
    private readonly InfoBar _undoBar;
    private readonly Action<long> _saving;
    private readonly Grid _root = new();
    private readonly ScrollViewer _scroll;
    private readonly StackPanel _column = new() { Spacing = 12, Padding = new Thickness(Margin, 4, Margin, Margin * 2) };
    private readonly Border _heroSlot = new();
    private readonly TextBlock _count = new() { Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"] };
    private readonly StackPanel _chapters = new() { Spacing = 4, Visibility = Visibility.Collapsed };
    private readonly ListView _chapterList = new() { SelectionMode = ListViewSelectionMode.None, IsItemClickEnabled = true };
    private readonly ContentControl _status = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch };
    private readonly Canvas _grid = new() { HorizontalAlignment = HorizontalAlignment.Stretch };
    /// <summary>The rows placed: by row index, its Canvas, what it was built from and its top.</summary>
    private readonly Dictionary<int, (Canvas Row, string Key, double Top)> _mounted = [];
    private readonly Recent<ImageSource> _tiles = new(TilesHeld);
    private readonly HashSet<string> _reading = [];
    private readonly Dictionary<string, List<(Image Image, Grid Face)>> _waiting = [];
    /// <summary>The tiles WIC did not decode: their glyph is drawn, never read again.</summary>
    private readonly HashSet<string> _undecodable = [];
    private UIElement? _hero;
    private long? _id;
    private Watch? _watch;
    private Watch? _live;
    private GalleryShown? _shown;
    private double _laidWidth;
    private bool _asking;
    private bool _again;
    private bool _seenUndo;
    private bool _saidShown;
    private bool _purgeAsked;
    private bool _closed;
    private LightboxView? _lightbox;
    /// <summary>Photos | Music, shown where the node offers its Music view.</summary>
    private readonly SelectorBar _views = new() { Visibility = Visibility.Collapsed };
    private readonly SelectorBarItem _photosItem = new() { Text = Words.MusicViewPhotos(), IsSelected = true };
    private readonly SelectorBarItem _musicItem = new() { Text = Words.MusicViewMusic() };
    private readonly Border _musicSlot = new() { Visibility = Visibility.Collapsed };
    private MusicView? _music;
    /// <summary>The Map view's place (ADR 0143 decision 8: offered always, as the web's tab is) and the view while it shows.</summary>
    private readonly SelectorBarItem _mapItem = new() { Text = Words.MemoryViewMap() };
    private readonly Border _mapSlot = new() { Visibility = Visibility.Collapsed };
    private MemoryMapView? _mapView;
    /// <summary>The facts line under the head (its date and place, ADR 0143 decision 5) and what it was drawn from.</summary>
    private readonly Border _factsSlot = new();
    private (MemoryDateShown?, string?, bool)? _factsDrawn;

    public NodeAt Node { get; }

    public UIElement Page => _root;

    /// <summary>The page's id in the core once it opened.</summary>
    public long? Id => _id;

    /// <summary>The state now; null until the core delivered it.</summary>
    public GalleryShown? Shown => _shown;

    /// <summary>Told each state with the page's id (New's chapter, the header's own upload).</summary>
    public Action<GalleryShown, long>? Changed { get; set; }

    /// <summary>Opens Memory or chapter <paramref name="node"/>: what its actions do said through <paramref name="say"/>,
    /// the trash's Undo in <paramref name="undoBar"/>, a save of an original followed by <paramref name="saving"/>.</summary>
    public MemoryPage(Window window, NodeAt node, Action<bool, string> say, InfoBar undoBar, Action<long> saving)
    {
        _window = window;
        Node = node;
        _say = say;
        _undoBar = undoBar;
        _saving = saving;
        _column.Children.Add(_heroSlot);
        _column.Children.Add(_factsSlot);
        AutomationProperties.SetAutomationId(_views, "memory-views");
        AutomationProperties.SetAutomationId(_photosItem, "memory-view-photos");
        AutomationProperties.SetAutomationId(_musicItem, "memory-view-music");
        _views.Items.Add(_photosItem);
        _views.Items.Add(_musicItem);
        AutomationProperties.SetAutomationId(_mapItem, "memory-view-map");
        _views.Items.Add(_mapItem);
        _views.SelectionChanged += (_, _) => ShowView();
        _column.Children.Add(_views);
        _column.Children.Add(_musicSlot);
        _column.Children.Add(_mapSlot);
        AutomationProperties.SetAutomationId(_count, "memory-count");
        _column.Children.Add(_count);
        var heading = new TextBlock { Text = Words.MemoryChapters(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
        AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        _chapters.Children.Add(heading);
        AutomationProperties.SetAutomationId(_chapterList, "memory-chapters");
        _chapterList.ItemClick += (_, e) =>
        {
            var row = e.ClickedItem as ListViewItem ?? (e.ClickedItem as FrameworkElement)?.Parent as ListViewItem;
            if (row?.Tag is NodeAt chapter) OpenNode(chapter);
        };
        _chapters.Children.Add(_chapterList);
        _column.Children.Add(_chapters);
        _column.Children.Add(_status);
        AutomationProperties.SetAutomationId(_grid, "memory-grid");
        AutomationProperties.SetName(_grid, Words.AppMemories());
        _column.Children.Add(_grid);
        _scroll = new ScrollViewer
        {
            Content = _column,
            HorizontalScrollMode = ScrollMode.Disabled,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            // a drop lands anywhere on the page, under its last row too
            Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent),
        };
        AutomationProperties.SetAutomationId(_scroll, "memory-page");
        // the grid laid out anew when its width changes (a window resized, the pane shown) and the rows near the view
        // follow the scroll
        _scroll.SizeChanged += (_, _) => Relayout();
        _scroll.ViewChanged += (_, _) => Relayout();
        Droppable();
        _root.Children.Add(_scroll);
        Draw();
        Open();
    }

    private async void Open()
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.GalleryOpen { node = Node });
            if (answer is not Answer.GalleryOpened { gallery: var gallery })
            {
                Log.Write($"the gallery did not open in the core ({answer.GetType().Name})");
                return;
            }
            if (_closed)
            {
                Act.Send(new Call.GalleryClose { gallery = gallery });
                return;
            }
            _id = gallery;
            // the web's poll, for as long as the page stands: its reads arrive on the page's own watch
            _live = Core.Watch(new Watched.GalleryLive { gallery = gallery }, _ => { }, e => Log.Write($"a gallery's poll: {e.Message}"));
            _watch = Core.Watch(new Watched.Gallery { gallery = gallery }, Updated, e => Log.Write($"a gallery update: {e.Message}"));
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"the gallery did not open ({e.GetType().Name})");
        }
    }

    private void Updated(Update update)
    {
        if (_closed || update is not Update.Gallery g) return;
        if (g.shown is { } s)
        {
            Log.Write($"gallery shown: {s.rows?.Count ?? -1} rows, {s.chapters.Count} chapters, failed {s.failed}, undo {s.undo is not null}, upload {s.canUpload}, save {s.canSave}, creates {string.Join(",", s.creates.Select(c => c.create))}");
        }
        var chaptersChanged = !(_shown?.chapters ?? []).SequenceEqual(g.shown?.chapters ?? []);
        _shown = g.shown;
        if (chaptersChanged) DrawChapters();
        Draw();
        Relayout();
        if (_shown is { } now && _id is { } id) Changed?.Invoke(now, id);
    }

    /// <summary>The node's head (NodeHeader.Hero) placed first in the page's scroll, as the Pot page places it.</summary>
    public void Hero(UIElement? hero)
    {
        if (ReferenceEquals(hero, _hero)) return;
        _heroSlot.Child = null;
        _hero = hero;
        if (hero is FrameworkElement { Parent: Border other }) other.Child = null;
        if (hero is FrameworkElement { Parent: Panel panel }) panel.Children.Remove(hero);
        _heroSlot.Child = hero;
    }

    /// <summary>"Add photos": the open picker for photos and videos (several), their paths to the core (Memory.Upload).</summary>
    public async void Add()
    {
        if (_id is not { } gallery) return;
        try
        {
            var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.PicturesLibrary, ViewMode = PickerViewMode.Thumbnail };
            foreach (var t in MediaTypes) picker.FileTypeFilter.Add(t);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var picked = await picker.PickMultipleFilesAsync();
            if (picked is null || picked.Count == 0) return;
            await Upload(gallery, DropSort.Sort(picked.Select(f => new DroppedItem(f.Name, f.Path, false))));
        }
        catch (Exception e)
        {
            Log.Write($"add photos failed: {e.GetType().Name}: {e.Message}");
        }
    }

    /// <summary>Sends <paramref name="sorted"/>'s files to gallery <paramref name="gallery"/> and says what was not taken.</summary>
    private async Task Upload(long gallery, DropSort.Sorted sorted)
    {
        Log.Write($"memory upload: {sorted.Files.Count} files, {sorted.Folders.Count} folders, {sorted.Refused.Count} refused");
        foreach (var text in DropSort.GallerySaid(sorted)) _say(false, text);
        if (DropSort.GalleryCall(gallery, sorted) is not { } call) return;
        var (answer, _) = await Core.CallAsync(call);
        Uploads.Queued(answer, "Memory.Upload", _say);
    }

    /// <summary>A drop of files onto the page adds them, where the rights add anything.</summary>
    private void Droppable()
    {
        _scroll.AllowDrop = true;
        _scroll.DragEnter += (_, e) => Log.Write($"a drag entered the gallery: files {e.DataView.Contains(StandardDataFormats.StorageItems)}");
        _scroll.DragOver += (_, e) =>
        {
            if (!e.DataView.Contains(StandardDataFormats.StorageItems) || _shown is not { canUpload: true }) return;
            e.AcceptedOperation = DataPackageOperation.Copy;
            e.DragUIOverride.Caption = Words.MemoryAdd();
            e.Handled = true;
        };
        _scroll.Drop += async (_, e) =>
        {
            if (!e.DataView.Contains(StandardDataFormats.StorageItems) || _id is not { } gallery || _shown is not { canUpload: true }) return;
            e.Handled = true;
            try
            {
                DropSort.Sorted sorted;
                var deferral = e.GetDeferral();
                try
                {
                    var items = await e.DataView.GetStorageItemsAsync();
                    sorted = DropSort.Sort(items.Select(i => new DroppedItem(i.Name, i.Path, i.IsOfType(StorageItemTypes.Folder))));
                }
                finally
                {
                    deferral.Complete();
                }
                await Upload(gallery, sorted);
            }
            catch (Exception ex)
            {
                // an exception out of an async handler would end the app in combase, with nothing in the log
                Log.Write($"a drop onto the gallery failed: {ex.GetType().Name}: {ex.Message}");
            }
        };
    }

    /// <summary>The header's music action: the node's music started shuffled, or stopped where it plays (Gallery.Music).</summary>
    public void Music()
    {
        if (_id is { } gallery) Act.Send(new Call.GalleryMusic { gallery = gallery });
    }

    /// <summary>The photos or the Music view, as the SelectorBar stands; the Music view opened when it is first shown and
    /// closed when the node no longer offers it or the page goes.</summary>
    private void ShowView()
    {
        var music = _views.Visibility == Visibility.Visible && ReferenceEquals(_views.SelectedItem, _musicItem);
        var map = _views.Visibility == Visibility.Visible && ReferenceEquals(_views.SelectedItem, _mapItem);
        if (map && _mapView is null && _id is { } at)
        {
            _mapView = new MemoryMapView(_window, at, _say, _saving);
            _mapSlot.Child = _mapView.View;
            Log.Write("memory page: the Map view");
        }
        else if (!map && _mapView is not null)
        {
            _mapSlot.Child = null;
            _mapView.Dispose();
            _mapView = null;
            Log.Write("memory page: the Map view left");
        }
        _mapSlot.Visibility = map ? Visibility.Visible : Visibility.Collapsed;
        if (music && _music is null && _id is { } gallery)
        {
            _music = new MusicView(_window, gallery, _say, _saving);
            _musicSlot.Child = _music.View;
            Log.Write("memory page: the Music view");
        }
        else if (!music && _music is not null)
        {
            _musicSlot.Child = null;
            _music.Dispose();
            _music = null;
            Log.Write("memory page: the photos");
        }
        _musicSlot.Visibility = music ? Visibility.Visible : Visibility.Collapsed;
        var other = music || map;
        var photos = other ? Visibility.Collapsed : Visibility.Visible;
        _count.Visibility = photos;
        _status.Visibility = photos;
        _grid.Visibility = photos;
        _chapters.Visibility = other ? Visibility.Collapsed : _shown?.chapters.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        if (!other) Relayout();
    }

    /// <summary>Refresh (F5, Ctrl+R): the gallery read anew (Gallery.Reload).</summary>
    public void Reload()
    {
        if (_id is { } gallery) Act.Send(new Call.GalleryReload { gallery = gallery });
    }

    /// <summary>New chapter (Gallery.CreateChapter): its name asked in a dialog, asked again with the core's problem; the
    /// chapter made opens.</summary>
    public async void CreateChapter() => await CreateChapter("", null);

    private async Task CreateChapter(string name, string? problem)
    {
        if (_id is not { } gallery) return;
        var typed = await Dialogs.AskName(_window, "new-chapter", Words.MemoryCreateChapterTitle(), Words.NodeName(), name, problem, Words.FolderCreate());
        if (typed is null) return;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.GalleryCreateChapter { gallery = gallery, name = typed });
            switch (answer)
            {
                case Answer.Created { node: { } made }:
                    Log.Write("new chapter: made");
                    OpenNode(made);
                    break;
                case Answer.Created { notice: { } n }:
                    Log.Write($"new chapter: {n.msg}");
                    await CreateChapter(typed, Said.Notice(n));
                    break;
                default:
                    Log.Write($"Gallery.CreateChapter answered {answer.GetType().Name}");
                    break;
            }
        }
        catch (Exception e)
        {
            Log.Write($"Gallery.CreateChapter failed ({e.GetType().Name})");
        }
    }

    /// <summary>Opens <paramref name="node"/> (a Memory or a chapter just made, a chapter's row) where the shell opens an object.</summary>
    public static void OpenNode(NodeAt node) =>
        Act.Send(new Call.ShellOpen { Ref = new ObjectRef { scopeId = node.scopeId, nodeId = node.nodeId, kind = node.kind, application = ApplicationId.Memories, name = node.name } });

    /// <summary>The grid's width: the scroll's view, less the page's margins.</summary>
    private double Width => _scroll.ViewportWidth - 2 * Margin;

    /// <summary>The grid asked for anew (Gallery.Layout) at its width and the scroll's view; one ask at a time, the latest
    /// wanted asked once the one in flight answered.</summary>
    private async void Relayout()
    {
        if (_id is not { } gallery || _closed || _music is not null) return;
        var width = Width;
        if (width <= 0 || _shown?.rows is null) return;
        if (_asking)
        {
            _again = true;
            return;
        }
        _asking = true;
        try
        {
            // the view's top in the grid's own frame: the scroll's offset less where the grid starts in the column
            var start = _grid.TransformToVisual(_column).TransformPoint(new Windows.Foundation.Point(0, 0)).Y;
            var top = Math.Max(0, _scroll.VerticalOffset - start);
            var view = Math.Max(1, _scroll.ViewportHeight);
            var (answer, _) = await Core.CallAsync(new Call.GalleryLayout { gallery = gallery, width = width, top = top, viewHeight = view });
            if (_closed) return;
            if (answer is Answer.GridLayout l)
            {
                if (Math.Abs(_laidWidth - width) > 0.5)
                {
                    _laidWidth = width;
                    Log.Write($"gallery laid out {width:0} wide: {l.count} rows, {l.height:0} high");
                }
                Place(l.rows, l.height);
            }
            else Log.Write($"Gallery.Layout answered {answer.GetType().Name}");
        }
        catch (Exception e)
        {
            Log.Write($"Gallery.Layout failed ({e.GetType().Name})");
        }
        finally
        {
            _asking = false;
        }
        if (_again)
        {
            _again = false;
            Relayout();
        }
    }

    /// <summary>Places <paramref name="rows"/> (the view's window) at their places in a grid <paramref name="height"/>
    /// high; rows out of it go, a row whose frames changed is built anew.</summary>
    private void Place(IReadOnlyList<GridRow> rows, double height)
    {
        _grid.Height = Math.Ceiling(height);
        var byId = new Dictionary<string, (int Index, GalleryRow Row)>();
        if (_shown?.rows is { } all) for (var i = 0; i < all.Count; i++) byId[all[i].mediaId] = (i, all[i]);
        var wanted = rows.Select(r => r.index).ToHashSet();
        foreach (var index in _mounted.Keys.Where(k => !wanted.Contains(k)).ToList())
        {
            _grid.Children.Remove(_mounted[index].Row);
            _mounted.Remove(index);
        }
        foreach (var r in rows)
        {
            // a row is built from its frames and its media's tiles: the same row moves, another is made
            var key = string.Join(" ", r.tiles.Select(t => $"{t.mediaId}:{(byId.TryGetValue(t.mediaId, out var m) ? m.Row.tile ?? "-" : "-")}:{t.left:0.0}:{t.width:0.0}")) + $" h{r.height:0.0}";
            if (_mounted.TryGetValue(r.index, out var placed))
            {
                if (placed.Key == key)
                {
                    if (Math.Abs(placed.Top - r.top) > 0.1)
                    {
                        Canvas.SetTop(placed.Row, r.top);
                        _mounted[r.index] = placed with { Top = r.top };
                    }
                    continue;
                }
                _grid.Children.Remove(placed.Row);
            }
            var row = Row(r, byId);
            Canvas.SetTop(row, r.top);
            _grid.Children.Add(row);
            _mounted[r.index] = (row, key, r.top);
        }
    }

    /// <summary>One row of the grid: its tiles at their frames.</summary>
    private Canvas Row(GridRow r, Dictionary<string, (int Index, GalleryRow Row)> byId)
    {
        var line = new Canvas { Height = r.height };
        foreach (var t in r.tiles)
        {
            if (!byId.TryGetValue(t.mediaId, out var m)) continue;
            // the tile's frame at the core's place in the row: a grid's cell, nothing laid over a view
            var frame = new Border { Width = t.width, Height = r.height, CornerRadius = new CornerRadius(4), Child = Tile(m.Row, m.Index, t.width, r.height) };
            Canvas.SetLeft(frame, t.left);
            line.Children.Add(frame);
        }
        return line;
    }

    /// <summary>One tile: the grid tier's thumbnail filling its frame (cut to it), a video's play badge, the kind's glyph
    /// where it has none; invoking it opens the lightbox at <paramref name="index"/>.</summary>
    private Button Tile(GalleryRow row, int index, double width, double height)
    {
        var name = row.name ?? Words.MemoryUnnamed(index + 1);
        var face = new Grid();
        if (row.tile is { } blob && !_undecodable.Contains(blob))
        {
            var image = new Image { Stretch = Stretch.UniformToFill, Width = width, Height = height };
            face.Children.Add(image);
            ShowTile(image, face, row, blob);
        }
        else face.Children.Add(KindGlyph(row));
        if (row.video && !row.blocked)
        {
            var badge = new Border
            {
                Background = new SolidColorBrush(Windows.UI.Color.FromArgb(160, 0, 0, 0)),
                CornerRadius = new CornerRadius(12),
                Padding = new Thickness(6),
                Margin = new Thickness(6),
                HorizontalAlignment = HorizontalAlignment.Left,
                VerticalAlignment = VerticalAlignment.Bottom,
                Child = new FontIcon { Glyph = "\uE768", FontSize = 12, Foreground = new SolidColorBrush(Microsoft.UI.Colors.White) },
            };
            face.Children.Add(badge);
        }
        var tile = new Button
        {
            Content = face,
            Width = width,
            Height = height,
            Padding = new Thickness(0),
            BorderThickness = new Thickness(0),
            CornerRadius = new CornerRadius(4),
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            VerticalContentAlignment = VerticalAlignment.Stretch,
            Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"],
        };
        AutomationProperties.SetAutomationId(tile, $"tile:{index}");
        AutomationProperties.SetName(tile, name);
        ToolTipService.SetToolTip(tile, name);
        tile.Click += (_, _) => OpenLightbox(index);
        return tile;
    }

    /// <summary>The kind's glyph a tile shows where it has no picture: none listed, or one WIC does not decode (a HEIC
    /// without the HEIF extension's tiers goes up alone, its original listed as its tile).</summary>
    private static FontIcon KindGlyph(GalleryRow row)
    {
        var glyph = HomeView.Glyph(row.blocked ? "\uE733" : row.video ? "\uE714" : "\uE91B");
        glyph.FontSize = 32;
        glyph.Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];
        glyph.HorizontalAlignment = HorizontalAlignment.Center;
        glyph.VerticalAlignment = VerticalAlignment.Center;
        return glyph;
    }

    /// <summary>Shows tile <paramref name="blob"/> of <paramref name="row"/> in <paramref name="image"/>: from the held
    /// tiles, else read (Gallery.Tile) once for every image that waits for it.</summary>
    private async void ShowTile(Image image, Grid face, GalleryRow row, string blob)
    {
        if (_tiles.Get(blob) is { } held)
        {
            image.Source = held;
            return;
        }
        if (!_waiting.TryGetValue(blob, out var waiting)) _waiting[blob] = waiting = [];
        waiting.Add((image, face));
        if (!_reading.Add(blob) || _id is not { } gallery) return;
        var read = false;
        try
        {
            var (answer, bytes) = await Core.CallAsync(new Call.GalleryTile { gallery = gallery, mediaId = row.mediaId });
            if (answer is not Answer.Bytes || bytes is null)
            {
                Log.Write($"Gallery.Tile answered {answer.GetType().Name}");
                return;
            }
            read = true;
            var source = await Decoded(bytes, TileEdge);
            if (_closed) return;
            _tiles.Put(blob, source);
            if (_waiting.TryGetValue(blob, out var those)) foreach (var (i, _) in those) i.Source = source;
        }
        catch (Exception e)
        {
            Log.Write($"a tile {(read ? "did not decode" : "was not read")} ({e.GetType().Name} 0x{e.HResult:X8})");
            // the kind's glyph in its place, as where none is listed (it stayed an empty frame, measured 2026-11-15); the face
            // is the one the image was put in, never its Parent, which reads null while that face still loads
            if (!read) return;
            _undecodable.Add(blob);
            if (!_closed && _waiting.TryGetValue(blob, out var those))
            {
                foreach (var (i, f) in those)
                {
                    f.Children.Remove(i);
                    f.Children.Insert(0, KindGlyph(row));
                }
            }
        }
        finally
        {
            _reading.Remove(blob);
            _waiting.Remove(blob);
        }
    }

    /// <summary>A picture decoded by WIC from <paramref name="bytes"/>, its longest side at <paramref name="edge"/> at most.</summary>
    internal static async Task<ImageSource> Decoded(byte[] bytes, int edge)
    {
        using var stream = new InMemoryRandomAccessStream();
        await stream.WriteAsync(bytes.AsBuffer());
        stream.Seek(0);
        var decoder = await Windows.Graphics.Imaging.BitmapDecoder.CreateAsync(stream);
        var image = new BitmapImage();
        if (Math.Max(decoder.OrientedPixelWidth, decoder.OrientedPixelHeight) > edge)
        {
            if (decoder.OrientedPixelWidth >= decoder.OrientedPixelHeight) image.DecodePixelWidth = edge;
            else image.DecodePixelHeight = edge;
        }
        stream.Seek(0);
        await image.SetSourceAsync(stream);
        return image;
    }

    private void OpenLightbox(int index)
    {
        if (_id is not { } gallery || _closed) return;
        Log.Write($"lightbox asked at {index}");
        CloseLightbox();
        _lightbox = new LightboxView(_window, gallery, index, _say, _saving, CloseLightbox);
        _scroll.Visibility = Visibility.Collapsed;
        _root.Children.Add(_lightbox.View);
        _lightbox.Focus();
    }

    /// <summary>The lightbox let go and the gallery shown again where it stood.</summary>
    private void CloseLightbox()
    {
        if (_lightbox is not { } l) return;
        _lightbox = null;
        l.Dispose();
        _root.Children.Remove(l.View);
        _scroll.Visibility = Visibility.Visible;
    }

    /// <summary>A Memory's chapters: a row each, its cover read where it has one (Gallery.ChapterCover), opening the chapter.</summary>
    private void DrawChapters()
    {
        _chapterList.Items.Clear();
        var chapters = _shown?.chapters ?? [];
        _chapters.Visibility = chapters.Count > 0 && _music is null ? Visibility.Visible : Visibility.Collapsed;
        if (_id is not { } gallery) return;
        foreach (var chapter in chapters)
        {
            var line = new Grid { ColumnSpacing = 12 };
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(64) });
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var glyph = HomeView.Glyph(HomeView.KindGlyph(chapter.kind));
            var cover = new Image { Width = 64, Height = 36, Stretch = Stretch.UniformToFill, Visibility = Visibility.Collapsed };
            var cell = new Grid();
            cell.Children.Add(glyph);
            cell.Children.Add(new Border { CornerRadius = new CornerRadius(4), Child = cover });
            line.Children.Add(cell);
            var title = new TextBlock { Text = chapter.name, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(title, 1);
            line.Children.Add(title);
            var chevron = HomeView.Glyph("\uE76C");
            chevron.FontSize = 12;
            Grid.SetColumn(chevron, 2);
            line.Children.Add(chevron);
            var row = new ListViewItem { Content = line, Tag = chapter, MinHeight = 48 };
            AutomationProperties.SetAutomationId(row, $"chapter:{chapter.name}");
            AutomationProperties.SetName(row, chapter.name);
            _chapterList.Items.Add(row);
            ChapterCover(gallery, chapter.nodeId, cover, glyph);
        }
    }

    private async void ChapterCover(long gallery, string nodeId, Image cover, FontIcon glyph)
    {
        try
        {
            var (answer, bytes) = await Core.CallAsync(new Call.GalleryChapterCover { gallery = gallery, nodeId = nodeId });
            if (answer is not Answer.Bytes || bytes is null || _closed) return;
            cover.Source = await Decoded(bytes, 160);
            cover.Visibility = Visibility.Visible;
            glyph.Visibility = Visibility.Collapsed;
        }
        catch (Exception e)
        {
            Log.Write($"a chapter's cover was not drawn ({e.GetType().Name})");
        }
    }

    /// <summary>What is not the grid: the count, the status in the grid's place, the trash's Undo, what the core says, a
    /// purge asked.</summary>
    private void Draw()
    {
        var s = _shown;
        _count.Text = s is null ? "" : Said.Notice(s.count);
        _status.Content = s switch
        {
            null or { rows: null, failure: null } => new ProgressRing { IsActive = true, Margin = new Thickness(24), HorizontalAlignment = HorizontalAlignment.Center },
            // offline, what the store does not hold says so, else the load failed (the core's words)
            { rows: null, failure: { } failure } => Status(Said.Key(failure), ""),
            { rows.Count: 0 } => Status(Words.MemoryGalleryEmpty(), HomeView.KindGlyph(Node.kind)),
            _ => null,
        };
        if (s?.rows is { Count: 0 }) DropRows();
        if (s is null) return;
        DrawFacts(s);
        // Photos | Music | Map: the Map view always (the web's tab), Music where the node offers it
        var music = s.musicView ? Visibility.Visible : Visibility.Collapsed;
        if (_views.Visibility != Visibility.Visible || _musicItem.Visibility != music)
        {
            _views.Visibility = Visibility.Visible;
            _musicItem.Visibility = music;
            if (!s.musicView && ReferenceEquals(_views.SelectedItem, _musicItem)) _views.SelectedItem = _photosItem;
            ShowView();
        }
        // the offer ended (its window, or the undo): the Undo goes with it — once a state showed the offer, so an update
        // from before the trash that arrives after its answer ends nothing (src/mobile/CLAUDE.md § Traps)
        if (s.undo is { } undo)
        {
            if (!_seenUndo && _id is { } gallery)
            {
                _seenUndo = true;
                var button = new Button { Content = Words.TrashUndo() };
                AutomationProperties.SetAutomationId(button, "gallery-undo");
                button.Click += (_, _) =>
                {
                    Log.Write("gallery undo pressed");
                    Act.Send(new Call.GalleryUndo { gallery = gallery });
                };
                _undoBar.Severity = InfoBarSeverity.Informational;
                _undoBar.Content = null;
                _undoBar.Message = Said.Notice(undo);
                _undoBar.ActionButton = button;
                _undoBar.IsOpen = true;
            }
        }
        else if (_seenUndo)
        {
            _seenUndo = false;
            Log.Write("gallery undo offer ended");
            _undoBar.ActionButton = null;
            _undoBar.IsOpen = false;
        }
        if (s.said is null) _saidShown = false;
        if (s.said is { } said && !_saidShown && _id is { } g)
        {
            _saidShown = true;
            Log.Write($"gallery said: {said.msg}");
            _say(false, Said.Notice(said));
            Act.Send(new Call.GallerySeen { gallery = g });
        }
        if (s.purging is null) _purgeAsked = false;
        if (s.purging is not null && s.purgeQuestion is { } question && !_purgeAsked && _id is { } p)
        {
            _purgeAsked = true;
            AskPurge(p, Said.Notice(question));
        }
    }

    /// <summary>The facts line under the head, drawn anew only when its date, place or rights changed.</summary>
    private void DrawFacts(GalleryShown s)
    {
        var drawn = (s.date, s.place, s.editsFacts);
        if (_factsDrawn == drawn || _id is not { } gallery) return;
        _factsDrawn = drawn;
        Log.Write($"memory facts: date {MemoryFacts.DateText(s.date) ?? "-"}, place {(s.place is null ? "-" : "named")}, edits {s.editsFacts}, tracks {s.tracks}");
        _factsSlot.Child = MemoryFacts.Line(_window, gallery, s);
    }

    private async void AskPurge(long gallery, string question)
    {
        try
        {
            if (await Dialogs.Confirm(_window, "gallery-purge", Words.MemoryDeleteOwnTitle(), question, Words.MemoryDeleteOwnAccept()))
            {
                var (answer, _) = await Core.CallAsync(new Call.GalleryPurge { gallery = gallery });
                if (answer is Answer.Said { notice: { } n }) _say(false, Said.Notice(n));
            }
            else Act.Send(new Call.GalleryCancelAsk { gallery = gallery });
        }
        catch (Exception e)
        {
            Log.Write($"Gallery.Purge failed ({e.GetType().Name})");
        }
    }

    /// <summary>Every placed row let go: the next layout places the window anew.</summary>
    private void DropRows()
    {
        foreach (var (_, placed) in _mounted) _grid.Children.Remove(placed.Row);
        _mounted.Clear();
    }

    /// <summary>What shows in the grid's place: <paramref name="text"/> under <paramref name="glyph"/>.</summary>
    private static UIElement Status(string text, string glyph)
    {
        var column = new StackPanel { Spacing = 12, HorizontalAlignment = HorizontalAlignment.Center, Padding = new Thickness(24) };
        var icon = HomeView.Glyph(glyph);
        icon.FontSize = 48;
        column.Children.Add(icon);
        var words = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center };
        AutomationProperties.SetAutomationId(words, "memory-status");
        column.Children.Add(words);
        return column;
    }

    public void Dispose()
    {
        if (_closed) return;
        _closed = true;
        CloseLightbox();
        _music?.Dispose();
        _music = null;
        _mapView?.Dispose();
        _mapView = null;
        _watch?.Dispose();
        _live?.Dispose();
        if (_seenUndo)
        {
            _undoBar.ActionButton = null;
            _undoBar.IsOpen = false;
        }
        _heroSlot.Child = null;
        if (_id is { } gallery) Act.Send(new Call.GalleryClose { gallery = gallery });
    }
}
