// A Memory's or a chapter's page (ADR 0136 decision 2; ADR 0123's Consequences, the Windows app; Linux's
// memory_page.rs, rule for rule), Fluent's way: the node's head (its cover, NodeHeader.Hero), the count, a Memory's chapters
// as a list of rows with their covers, and the media as the web's justified grid (ADR 0136's Consequences) — every tile's frame the core's
// (Gallery.Layout: GalleryModel.layout), only the rows near the view placed on the grid's Canvas (the core's
// Layout.window, ADR 0105), laid out anew when the width or the scroll changes; each tile the grid tier's thumbnail decoded
// by WIC (Gallery.Tile, held small and dropped least recently shown first, Recent), a video's with a play badge. A tile
// opens the lightbox (LightboxView) in the page's place. The page's "Add photos" (the header's own upload) opens the open
// picker for photos and videos, and a drop of files onto the page adds them (Memory.Upload) — a dropped folder is said and
// not taken (the owner, 2026-11-13). A medium trashed (from the lightbox) is out at once with Undo in the shell's
// InfoBar for as long as the core offers it. A medium the search asked for (GalleryShown.searchOpened, ADR 0146 decision
// 8) opens its lightbox once the rows hold it. The page is read anew live while it stands (Watched.GalleryLive, the web's
// poll).
//
// Under its head the facts line (its date and place, each opening its editor where whoever looks may edit its meta:
// MemoryFacts, ADR 0143 decision 5), then a SelectorBar switching between its photos, its Music view where the node has
// music or whoever looks may add some (GalleryShown.musicView; MusicView, ADR 0142 decision 6: Fluent's SelectorBar, Linux's
// AdwToggleGroup) and its Map view, always (MemoryMapView, ADR 0143 decision 8); the header's own music action plays or
// stops the node's music (Gallery.Music); and, where GalleryShown.contributions holds (a member's), its Contributions view
// (ContributionsView, ADR 0149 decision 6), the fourth item.
//
// Above the photos the filter (GalleryFilterBar, ADR 0149 decision 5) where GalleryShown.filter is offered: the grid and the
// lightbox page the rows it shows. A tile carries its marks' badge (ADR 0149 decision 4: the heart, filled where the caller
// marked it, with the count where anyone did) — a badge, not a button: the mark is toggled in the lightbox and, where the
// marks' mode is offered (a member's, in a shared space, online), from the tile's right-click menu (Marks.Toggle).
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
    /// <summary>The placeholder tiles' progress bars, by layout id: moved from each update, never rebuilt for a step (ADR 0145 decision 3).</summary>
    private readonly Dictionary<string, WeakReference<ProgressBar>> _bars = [];
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
    /// <summary>The medium the search asked for that this page opened last (GalleryShown.searchOpened), until the core drops it.</summary>
    private string? _searchOpened;
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
    /// <summary>The Contributions view's place (where GalleryShown.contributions holds) and the view while it shows.</summary>
    private readonly SelectorBarItem _bucketsItem = new() { Text = Words.MemoryBucketsTitle(), Visibility = Visibility.Collapsed };
    private readonly Border _bucketsSlot = new() { Visibility = Visibility.Collapsed };
    private ContributionsView? _buckets;
    /// <summary>The filter above the photos, made once the page's id is known, while GalleryShown.filter is offered.</summary>
    private readonly Border _filterSlot = new() { Visibility = Visibility.Collapsed };
    private GalleryFilterBar? _filter;
    /// <summary>The facts line under the head (its date and place, ADR 0143 decision 5) and what it was drawn from.</summary>
    private readonly Border _factsSlot = new();
    private (MemoryDateShown?, string?, bool)? _factsDrawn;
    /// <summary>The people line beside the facts line (ADR 0144 decision 1) and the key it was drawn from.</summary>
    private readonly Border _peopleSlot = new();
    private string? _peopleDrawn;
    /// <summary>The banner above the head (ADR 0144 decision 4), and A2's question asked for the medium it names.</summary>
    private readonly BannerStage _banner;
    private string? _bannerAsked;

    public NodeAt Node { get; }

    public UIElement Page => _root;

    /// <summary>The page's id in the core once it opened.</summary>
    public long? Id => _id;

    /// <summary>The state now; null until the core delivered it.</summary>
    public GalleryShown? Shown => _shown;

    /// <summary>Told each state with the page's id (New's chapter, the header's own upload).</summary>
    public Action<GalleryShown, long>? Changed { get; set; }

    /// <summary>Where a chapter's row opens it when not in the shell (a received link's guest opens it inside the link).</summary>
    public Action<NodeAt>? ChapterOpens { get; set; }

    /// <summary>The gallery a received link's guest page handed in, opened by the core (null: Gallery.Open opens it).</summary>
    private readonly long? _opened;

    /// <summary>Opens Memory or chapter <paramref name="node"/>: what its actions do said through <paramref name="say"/>,
    /// the trash's Undo in <paramref name="undoBar"/>, a save of an original followed by <paramref name="saving"/>.</summary>
    /// <remarks>With <paramref name="opened"/>, over the gallery the core already opened (a received link's guest page,
    /// ReceivedPage.gallery): the member's page, the core's rights a guest's.</remarks>
    public MemoryPage(Window window, NodeAt node, Action<bool, string> say, InfoBar undoBar, Action<long> saving, long? opened = null)
    {
        _window = window;
        Node = node;
        _opened = opened;
        _say = say;
        _undoBar = undoBar;
        _saving = saving;
        _banner = new BannerStage(window, say);
        _column.Children.Add(_banner.View);
        _column.Children.Add(_heroSlot);
        // the facts line and the people line in one row
        var factsRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 16 };
        factsRow.Children.Add(_factsSlot);
        factsRow.Children.Add(_peopleSlot);
        _column.Children.Add(factsRow);
        AutomationProperties.SetAutomationId(_views, "memory-views");
        AutomationProperties.SetAutomationId(_photosItem, "memory-view-photos");
        AutomationProperties.SetAutomationId(_musicItem, "memory-view-music");
        _views.Items.Add(_photosItem);
        _views.Items.Add(_musicItem);
        AutomationProperties.SetAutomationId(_mapItem, "memory-view-map");
        _views.Items.Add(_mapItem);
        AutomationProperties.SetAutomationId(_bucketsItem, "memory-view-contributions");
        _views.Items.Add(_bucketsItem);
        _views.SelectionChanged += (_, _) => ShowView();
        _column.Children.Add(_views);
        _column.Children.Add(_musicSlot);
        _column.Children.Add(_mapSlot);
        _column.Children.Add(_bucketsSlot);
        _column.Children.Add(_filterSlot);
        AutomationProperties.SetAutomationId(_count, "memory-count");
        _column.Children.Add(_count);
        var heading = new TextBlock { Text = Words.MemoryChapters(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] };
        AutomationProperties.SetHeadingLevel(heading, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        _chapters.Children.Add(heading);
        AutomationProperties.SetAutomationId(_chapterList, "memory-chapters");
        _chapterList.ItemClick += (_, e) =>
        {
            var row = e.ClickedItem as ListViewItem ?? (e.ClickedItem as FrameworkElement)?.Parent as ListViewItem;
            if (row?.Tag is not NodeAt chapter) return;
            if (ChapterOpens is { } opens) opens(chapter);
            else OpenNode(chapter);
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
        _scroll.SizeChanged += (_, _) =>
        {
            Relayout();
            _banner.Layout(Width, _scroll.ViewportHeight);
        };
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
            var (answer, _) = _opened is { } opened ? ((Answer)new Answer.GalleryOpened { gallery = opened }, (byte[]?)null) : await Core.CallAsync(new Call.GalleryOpen { node = Node });
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
            Log.Write($"gallery shown: {s.rows?.Count ?? -1} rows, {s.chapters.Count} chapters, failed {s.failed}, filter {(s.filter is { } fs ? $"{fs.shown}/{fs.total} {fs.mode} chips {fs.chips.Count} suggestions {fs.suggestions.Count}" : "-")}, contributions {s.contributions}, marks {s.rows?.Count(r => r.mine || r.marks > 0) ?? 0}, undo {s.undo is not null}, upload {s.canUpload}, save {s.canSave}, creates {string.Join(",", s.creates.Select(c => c.create))}");
        }
        var chaptersChanged = !(_shown?.chapters ?? []).SequenceEqual(g.shown?.chapters ?? []);
        _shown = g.shown;
        if (chaptersChanged) DrawChapters();
        Draw();
        Relayout();
        StepUploads();
        OpenSearched();
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

    /// <summary>"Add photos": the open picker for photos and videos (several), handed to the core's one drop onto this
    /// gallery (Shell.Drop, ADR 0154 decision 1), which keeps its photos and videos and says the rest.</summary>
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
            await Drops.Send(_window, new DropOn.DropOnGallery { gallery = gallery }, Drops.Items(picked), _say);
        }
        catch (Exception e)
        {
            Log.Write($"add photos failed: {e.GetType().Name}: {e.Message}");
        }
    }

    /// <summary>A drop of files onto the page goes to the core's one drop onto this gallery, where its rights upload
    /// (GalleryShown.canUpload; the core judges it again).</summary>
    private void Droppable()
    {
        Drops.Attach(_scroll, () => _id is { } gallery && _shown is { canUpload: true } ? new DropOn.DropOnGallery { gallery = gallery } : null,
            Words.MemoryAdd(), null, _window, _say);
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
        var buckets = _views.Visibility == Visibility.Visible && ReferenceEquals(_views.SelectedItem, _bucketsItem);
        if (buckets && _buckets is null && _id is { } of)
        {
            _buckets = new ContributionsView(_window, of, _say, OpenLightboxBy, () => _shown?.offline is not null);
            _bucketsSlot.Child = _buckets.View;
            Log.Write("memory page: the Contributions view");
        }
        else if (!buckets && _buckets is not null)
        {
            _bucketsSlot.Child = null;
            _buckets.Dispose();
            _buckets = null;
            Log.Write("memory page: the Contributions view left");
        }
        _bucketsSlot.Visibility = buckets ? Visibility.Visible : Visibility.Collapsed;
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
        var other = music || map || buckets;
        var photos = other ? Visibility.Collapsed : Visibility.Visible;
        _count.Visibility = photos;
        _filterSlot.Visibility = !other && _filter is not null ? Visibility.Visible : Visibility.Collapsed;
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

    /// <summary>Whether a tile's menu marks (Marks.Toggle): where the core says a mark may be flipped
    /// (GalleryShown.marking, #1001: a member's, the own set read — a personal space's too), online or where the core
    /// queues the page's writes offline (GalleryShown.queues, ADR 0153: a mark kept to be sent).</summary>
    private bool Marking => _shown is { marking: true } s && (s.offline is null || s.queues);

    /// <summary>The grid's width: the scroll's view, less the page's margins.</summary>
    private double Width => _scroll.ViewportWidth - 2 * Margin;

    /// <summary>The grid asked for anew (Gallery.Layout) at its width and the scroll's view; one ask at a time, the latest
    /// wanted asked once the one in flight answered.</summary>
    private async void Relayout()
    {
        if (_id is not { } gallery || _closed || _music is not null || _buckets is not null) return;
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
        // what this device sends here leads the grid as placeholder tiles (ADR 0145 decision 3)
        var uploading = (_shown?.uploading ?? []).ToDictionary(u => u.id);
        var wanted = rows.Select(r => r.index).ToHashSet();
        foreach (var index in _mounted.Keys.Where(k => !wanted.Contains(k)).ToList())
        {
            _grid.Children.Remove(_mounted[index].Row);
            _mounted.Remove(index);
        }
        foreach (var r in rows)
        {
            // a row is built from its frames and its media's tiles: the same row moves, another is made
            // a placeholder is built anew when it starts sending or fails, never for a step of its progress
            var key = string.Join(" ", r.tiles.Select(t => $"{t.mediaId}:{(uploading.TryGetValue(t.mediaId, out var u) ? Stage(u) : byId.TryGetValue(t.mediaId, out var m) ? m.Row.tile ?? "-" : "-")}:{(byId.TryGetValue(t.mediaId, out var b) ? $"{b.Row.marks}/{b.Row.mine}" : "")}:{t.left:0.0}:{t.width:0.0}")) + $" h{r.height:0.0} m{Marking}";
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
            var row = Row(r, byId, uploading);
            Canvas.SetTop(row, r.top);
            _grid.Children.Add(row);
            _mounted[r.index] = (row, key, r.top);
        }
    }

    /// <summary>One row of the grid: its tiles at their frames.</summary>
    private Canvas Row(GridRow r, Dictionary<string, (int Index, GalleryRow Row)> byId, Dictionary<string, UploadTileShown> uploading)
    {
        var line = new Canvas { Height = r.height };
        foreach (var t in r.tiles)
        {
            if (uploading.TryGetValue(t.mediaId, out var upload))
            {
                var held = new Border { Width = t.width, Height = r.height, CornerRadius = new CornerRadius(4), Child = Placeholder(upload, t.width, r.height) };
                Canvas.SetLeft(held, t.left);
                line.Children.Add(held);
                continue;
            }
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
        // the marks' badge: the heart, filled where the caller marked it, the count where anyone did
        var marked = GalleryText.MarkWords(row);
        if (marked is not null)
        {
            var badge = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 4 };
            badge.Children.Add(new FontIcon { Glyph = row.mine ? "\uEB52" : "\uEB51", FontSize = 12, Foreground = new SolidColorBrush(Microsoft.UI.Colors.White) });
            if (row.marks is int n && n > 0) badge.Children.Add(new TextBlock { Text = n.ToString(System.Globalization.CultureInfo.CurrentCulture), FontSize = 12, Foreground = new SolidColorBrush(Microsoft.UI.Colors.White) });
            face.Children.Add(new Border
            {
                Background = new SolidColorBrush(Windows.UI.Color.FromArgb(160, 0, 0, 0)),
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(6, 3, 6, 3),
                Margin = new Thickness(6),
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Top,
                Child = badge,
            });
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
        AutomationProperties.SetName(tile, marked is null ? name : $"{name}, {marked}");
        ToolTipService.SetToolTip(tile, name);
        tile.Click += (_, _) => OpenLightbox(index);
        if (Marking)
        {
            // the right-click menu marks, as Explorer's menu acts on the item under the pointer
            var item = new MenuFlyoutItem { Text = row.mine ? Words.MediaMarksUnmark() : Words.MediaMarksMark(), Icon = new FontIcon { Glyph = row.mine ? "\uEB51" : "\uEB52" } };
            AutomationProperties.SetAutomationId(item, "tile-mark");
            var id = row.mediaId;
            item.Click += (_, _) => ToggleMark(id);
            var menu = new MenuFlyout();
            menu.Items.Add(item);
            tile.ContextFlyout = menu;
        }
        return tile;
    }

    /// <summary>Medium <paramref name="mediaId"/>'s mark flipped from its tile's menu (Marks.Toggle); what did not land is said.</summary>
    private async void ToggleMark(string mediaId)
    {
        if (_id is not { } gallery) return;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.MarksToggle { gallery = gallery, mediaId = mediaId });
            Log.Write($"tile mark: {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
            if (answer is Answer.Said { notice: { } n }) _say(false, Said.Notice(n));
        }
        catch (Exception e)
        {
            Log.Write($"Marks.Toggle failed ({e.GetType().Name})");
        }
    }

    /// <summary>A placeholder's stage, what its tile is built from: waiting to be sent (added offline), preparing, sending, failed.</summary>
    private static string Stage(UploadTileShown u) => u.waiting ? "waiting" : u.failed ? "failed" : u.preparing ? "preparing" : "sending";

    /// <summary>A photo or video on its way into this page (ADR 0145 decision 3): its kind's glyph on the card, a running
    /// bar while its tiers are made on the device, then how far it is (<see cref="StepUploads"/> moves it), or that it
    /// failed — the uploads flyout retries it. Narrator reads its name and what is happening to it.</summary>
    private Grid Placeholder(UploadTileShown upload, double width, double height)
    {
        var label = Said.Notice(upload.label);
        var face = new Grid { Width = width, Height = height, Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"] };
        var glyph = HomeView.Glyph(upload.failed ? "\uE7BA" : upload.video ? "\uE714" : "\uE91B");
        glyph.FontSize = 32;
        glyph.Foreground = (Brush)Application.Current.Resources[upload.failed ? "SystemFillColorCriticalBrush" : "TextFillColorSecondaryBrush"];
        glyph.HorizontalAlignment = HorizontalAlignment.Center;
        glyph.VerticalAlignment = VerticalAlignment.Center;
        face.Children.Add(glyph);
        if (upload.waiting)
        {
            // added offline, sealed on this device: Waiting to send (or why it is held), in place of a bar (ADR 0153 decision 5)
            var waits = new TextBlock
            {
                Text = upload.said is { } w ? Said.Notice(w) : Words.OfflineWaiting(),
                Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
                TextWrapping = TextWrapping.Wrap,
                TextAlignment = TextAlignment.Center,
                Margin = new Thickness(8),
                VerticalAlignment = VerticalAlignment.Bottom,
            };
            face.Children.Add(waits);
            label = $"{label}, {waits.Text}";
        }
        else if (!upload.failed)
        {
            var bar = new ProgressBar { IsIndeterminate = upload.preparing, Maximum = 1, Value = upload.progress, Margin = new Thickness(8), VerticalAlignment = VerticalAlignment.Bottom };
            if (!upload.preparing) _bars[upload.id] = new WeakReference<ProgressBar>(bar);
            face.Children.Add(bar);
        }
        AutomationProperties.SetName(face, label);
        ToolTipService.SetToolTip(face, label);
        return face;
    }

    /// <summary>Each placeholder's bar moved to its upload's progress as the update says it; a bar no longer shown is forgotten.</summary>
    private void StepUploads()
    {
        foreach (var id in _bars.Where(b => !b.Value.TryGetTarget(out _)).Select(b => b.Key).ToList()) _bars.Remove(id);
        foreach (var u in _shown?.uploading ?? [])
        {
            if (_bars.TryGetValue(u.id, out var weak) && weak.TryGetTarget(out var bar)) bar.Value = u.progress;
        }
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

    /// <summary>The search asked for a medium of this page (another Memory's photo opened from its results): its lightbox,
    /// once, then told seen (Gallery.SearchOpenedSeen).</summary>
    private void OpenSearched()
    {
        var asked = _shown?.searchOpened;
        if (asked is null || asked == _searchOpened)
        {
            _searchOpened = asked;
            return;
        }
        var index = -1;
        if (_shown?.rows is { } rows) for (var i = 0; i < rows.Count && index < 0; i++) if (rows[i].mediaId == asked) index = i;
        if (index < 0 || _id is not { } gallery) return;
        _searchOpened = asked;
        Log.Write($"the search's medium opens at {index}");
        OpenLightbox(index);
        Act.Send(new Call.GallerySearchOpenedSeen { gallery = gallery });
    }

    private void OpenLightbox(int index)
    {
        if (_id is not { } gallery) return;
        Log.Write($"lightbox asked at {index}");
        OpenLightboxBy(new Call.LightboxOpen { gallery = gallery, index = index });
    }

    /// <summary>The lightbox the core opens by <paramref name="open"/>: Lightbox.Open at a row, or Buckets.Lightbox over a
    /// bucket's media (#996).</summary>
    private void OpenLightboxBy(Call open)
    {
        if (_id is not { } gallery || _closed) return;
        CloseLightbox();
        _lightbox = new LightboxView(_window, gallery, open, _say, _saving, CloseLightbox);
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
        _chapters.Visibility = chapters.Count > 0 && _music is null && _mapView is null && _buckets is null ? Visibility.Visible : Visibility.Collapsed;
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
            // the filter leaves nothing of what there is: its own words (the web's media.filter.empty)
            { rows.Count: 0, filter: { total: > 0 } f } when GalleryText.Narrows(f) => Status(GalleryText.Empty(f), "\uE71C"),
            { rows.Count: 0, uploading.Count: 0 } => Status(Words.MemoryGalleryEmpty(), HomeView.KindGlyph(Node.kind)),
            _ => null,
        };
        if (s is { rows.Count: 0, uploading.Count: 0 }) DropRows();
        if (s is null) return;
        DrawFacts(s);
        DrawPeople(s);
        DrawFilter(s);
        if (_id is { } shownAt)
        {
            _banner.Show(shownAt, s.banner);
            _banner.Layout(Width, _scroll.ViewportHeight);
        }
        // A2: the banner's own picture asked to be trashed — asked once per medium the state names
        if (s.bannerQuestion is null) _bannerAsked = null;
        else if (s.bannerQuestion != _bannerAsked && _id is { } asking)
        {
            _bannerAsked = s.bannerQuestion;
            Log.Write("gallery asks before trashing the banner's picture");
            BannerStage.Ask(_window, asking, _say);
        }
        // Photos | Music | Map | Contributions: the Map view always (the web's tab), Music where the node offers it, the
        // Contributions where GalleryShown.contributions holds (a member's)
        var music = s.musicView ? Visibility.Visible : Visibility.Collapsed;
        var buckets = s.contributions ? Visibility.Visible : Visibility.Collapsed;
        if (_views.Visibility != Visibility.Visible || _musicItem.Visibility != music || _bucketsItem.Visibility != buckets)
        {
            _views.Visibility = Visibility.Visible;
            _musicItem.Visibility = music;
            _bucketsItem.Visibility = buckets;
            if (!s.musicView && ReferenceEquals(_views.SelectedItem, _musicItem)) _views.SelectedItem = _photosItem;
            if (!s.contributions && ReferenceEquals(_views.SelectedItem, _bucketsItem)) _views.SelectedItem = _photosItem;
            ShowView();
        }
        // the Contributions' bucket follows the page's rows, which an upload or another device's move may have changed
        _buckets?.RowsChanged();
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

    /// <summary>The filter above the photos where the page offers it (GalleryShown.filter): made once, then shown each state.</summary>
    private void DrawFilter(GalleryShown s)
    {
        if (s.filter is not { offered: true } f || _id is not { } gallery || s.rows is null)
        {
            _filterSlot.Visibility = Visibility.Collapsed;
            return;
        }
        if (_filter is null)
        {
            _filter = new GalleryFilterBar(gallery);
            _filterSlot.Child = _filter.View;
            Log.Write($"memory filter offered: marks mode {f.modeOffered}");
        }
        _filter.Show(f);
        var photos = _music is null && _mapView is null && _buckets is null;
        _filterSlot.Visibility = photos ? Visibility.Visible : Visibility.Collapsed;
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

    /// <summary>The people line, drawn anew only when whom it lists or what it offers changed.</summary>
    private void DrawPeople(GalleryShown s)
    {
        var key = s.people is not { } p ? "" : $"{string.Join(",", p.people.Select(x => $"{x.userId}={x.name}/{x.former}/{x.opensProfile}"))}|{string.Join(",", p.avatars)}|{p.more}|{p.edits}|{p.add}";
        if (_peopleDrawn == key || _id is not { } gallery) return;
        _peopleDrawn = key;
        Log.Write($"people line: {s.people?.people.Count.ToString() ?? "none"}{(s.people is { } q ? $", avatars {q.avatars.Count} +{q.more}, edits {q.edits}, add {q.add}" : "")}");
        _peopleSlot.Child = s.people is { } shown ? PeopleLine.Line(_window, gallery, shown) : null;
    }

    /// <summary>"Add banner" / "Change banner" (Banner.Open), where the gallery offers it (GalleryShown.editsBanner).</summary>
    public void EditBanner()
    {
        if (_id is { } gallery && _shown is { editsBanner: true } s) BannerDialog.Open(_window, gallery, s.banner is not null);
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
        _banner.Dispose();
        _music?.Dispose();
        _music = null;
        _mapView?.Dispose();
        _mapView = null;
        _buckets?.Dispose();
        _buckets = null;
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
