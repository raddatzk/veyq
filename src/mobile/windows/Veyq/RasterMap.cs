// The core's raster map drawn on Windows (ADR 0143 decisions 1 and 11; Linux's raster_map.rs, Android's MapView): a
// Canvas of tile Images, each placed by the viewport's one affine formula from the normalised Web Mercator space the core
// hands it — px = (p.x − view.x) · 256 · 2^zoom + width/2, the same for y —, the overlays over it (a track's Polylines in
// its colour with its activity's dash, the photos' dots, a photo's point with its direction needle, the Memories map's
// pins with their covers and counts, the place editor's fixed centre pin), the tile provider's attribution always shown
// at its foot, and at its top end "Turn map services off" and the zoom buttons (Windows Maps' own place for them). Every
// rule is the core's: which tiles, when they may be fetched (the consent), clustering, framing; the host draws what it is
// handed and reports gestures — a drag pans, the wheel zooms about the pointer, a double click zooms in, + − and the arrows
// on the keyboard — as `Map.View` with its centre (at most every 100 ms during a gesture and once at its end; a centre
// is sent for a member's gesture only, so on the place editor's map it is the member's pan). A size change sends the size
// alone. The core's view is adopted whenever its `moved` turns (its opening, a cluster framed, a track selected).
//
// Before the consent the consent's offer stands in the map's place (uc.memory.place A6), worded as the web's
// MapConsentPrompt; with no map served, or offline, why there is none. A withdrawal tears the map down at once: every
// tile Image dropped. The pins are no Buttons (src/mobile/CLAUDE.md § Traps, the floating-button guard): a click is
// hit-tested against them and told to the core (`Map.Tap`). Tiles are held decoded in memory only, a bounded few per map
// (ADR 0143 decision 4); nothing is written to disk.
using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices.WindowsRuntime;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Shapes;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Foundation;
using Windows.Storage.Streams;

namespace Veyq;

internal sealed class RasterMap : IDisposable
{
    /// <summary>A tile's edge in layout pixels at its own zoom (Web Mercator's 256).</summary>
    private const double Tile = 256;

    /// <summary>The zoom levels a member may reach (the web's Leaflet maxZoom 19).</summary>
    private const double MinZoom = 1, MaxZoom = 19;

    /// <summary>How many decoded tiles one map holds: a view's worth and the levels just left (Linux's TILES_HELD).</summary>
    private const int TilesHeld = 160;

    /// <summary>How far a pointer may move between press and release and still be a click (layout pixels).</summary>
    private const double Slop = 4;

    /// <summary>How near a click must land to a pin's point to be its (layout pixels).</summary>
    private const double PinReach = 24;

    private readonly Window _window;
    private readonly long _map;
    private readonly bool _closesItself;
    private readonly Grid _root = new() { IsTabStop = true, UseSystemFocusVisuals = true };
    private readonly Canvas _canvas = new();
    private readonly Canvas _tiles = new();
    private readonly Canvas _overlay = new();
    private readonly ContentControl _status = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Center };
    private readonly Border _attribution;
    private readonly TextBlock _attributionText = new() { FontSize = 11, TextWrapping = TextWrapping.NoWrap };
    private readonly StackPanel _chrome = new() { Spacing = 4, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(8) };
    private readonly FontIcon _centrePin = new()
    {
        Glyph = "", FontSize = 32, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
        IsHitTestVisible = false, Visibility = Visibility.Collapsed,
        // its point at the map's centre: the glyph's foot, half its height above the middle
        RenderTransform = new TranslateTransform { Y = -16 },
    };
    private readonly Recent<HeldTile> _held = new(TilesHeld);
    private readonly Dictionary<string, Image> _placed = [];
    private readonly HashSet<string> _reading = [];
    private Watch? _watch;
    private MapShown? _shown;
    private bool _closed;

    // the host's own view: its centre in normalised space, its zoom, its size in layout pixels
    private double _x = 0.5, _y = 0.5, _zoom = 2, _width, _height;
    private bool _hasView;
    private long _moved = -1;
    /// <summary>The zoom the overlays were built at and the centre they were built about: a pan only moves them.</summary>
    private double _builtZoom = double.NaN, _builtX, _builtY;

    // a gesture: where it was pressed, whether it moved, when the core was last told, whether a telling waits
    private Point? _pressed;
    private Point _last;
    private bool _dragged;
    private DateTime _told = DateTime.MinValue;
    private readonly Microsoft.UI.Dispatching.DispatcherQueueTimer _tell;

    private sealed record HeldTile(int Z, double X, double Y, double Size, ImageSource Picture);

    /// <summary>Told each state the core sends (the Memories map's empty note).</summary>
    public Action<MapShown?>? Changed { get; set; }

    /// <summary>The map's place, sized by its owner.</summary>
    public FrameworkElement View => _root;

    /// <summary>Map <paramref name="map"/> (answered by its owner's call: Map.OpenMemories, Lightbox.Map, Place.Open,
    /// Tracks.Open). <paramref name="closesItself"/>: whether this control closes it in the core when it goes (Map.Close);
    /// an editor's or the Map view's map closes with them.</summary>
    public RasterMap(Window window, long map, bool closesItself)
    {
        _window = window;
        _map = map;
        _closesItself = closesItself;
        AutomationProperties.SetAutomationId(_root, $"map:{map}");
        AutomationProperties.SetName(_root, Words.MemoryViewMap());
        // the map's ground while its tiles load: Fluent's subtle fill, never black
        _root.Background = (Brush)Application.Current.Resources["SubtleFillColorSecondaryBrush"];
        _canvas.Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        _canvas.Children.Add(_tiles);
        _canvas.Children.Add(_overlay);
        _root.Children.Add(_canvas);
        _root.Children.Add(_centrePin);
        _attribution = new Border
        {
            Child = _attributionText,
            Padding = new Thickness(6, 2, 6, 2),
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Bottom,
            Background = (Brush)Application.Current.Resources["AcrylicInAppFillColorDefaultBrush"],
            CornerRadius = new CornerRadius(4, 0, 0, 0),
            Visibility = Visibility.Collapsed,
        };
        AutomationProperties.SetAutomationId(_attributionText, $"map-attribution:{map}");
        _root.Children.Add(_attribution);
        // "Turn map services off": Fluent's power glyph, the switch's own sign
        _chrome.Children.Add(ChromeButton(new FontIcon { Glyph = "", FontSize = 16 }, Words.MapOff(), "map-off", () =>
        {
            Log.Write($"map {_map}: turn map services off");
            Act.Send(new Call.MapTurnOff());
        }));
        _chrome.Children.Add(ChromeButton(new SymbolIcon(Symbol.ZoomIn), Words.MapZoomIn(), "map-zoom-in", () => ZoomBy(1, _width / 2, _height / 2)));
        _chrome.Children.Add(ChromeButton(new SymbolIcon(Symbol.ZoomOut), Words.MapZoomOut(), "map-zoom-out", () => ZoomBy(-1, _width / 2, _height / 2)));
        _chrome.Visibility = Visibility.Collapsed;
        _root.Children.Add(_chrome);
        _root.Children.Add(_status);
        _root.SizeChanged += (_, e) =>
        {
            _width = e.NewSize.Width;
            _height = e.NewSize.Height;
            _root.Clip = new RectangleGeometry { Rect = new Rect(0, 0, _width, _height) };
            if (_width > 0 && _height > 0) TellSize();
            Frame();
        };
        _tell = Microsoft.UI.Dispatching.DispatcherQueue.GetForCurrentThread().CreateTimer();
        _tell.IsRepeating = false;
        _tell.Interval = TimeSpan.FromMilliseconds(100);
        _tell.Tick += (_, _) => TellView();
        Gestures();
        _watch = Core.Watch(new Watched.Map { map = map }, Updated, e => Log.Write($"a map update: {e.Message}"));
        Draw();
    }

    /// <summary>A button of the map's own (Windows Maps' at its top end): an icon, its name for a screen reader and its
    /// tooltip.</summary>
    private static Button ChromeButton(IconElement icon, string name, string id, Action act)
    {
        var button = new Button { Content = icon, Padding = new Thickness(6), Width = 36, Height = 36 };
        AutomationProperties.SetAutomationId(button, id);
        AutomationProperties.SetName(button, name);
        ToolTipService.SetToolTip(button, name);
        button.Click += (_, _) => act();
        return button;
    }

    private void Updated(Update update)
    {
        if (_closed || update is not Update.Map { shown: var s }) return;
        var wasShown = _shown?.state is MapStatus.MapStateShown;
        _shown = s;
        if (s is null)
        {
            Log.Write($"map {_map}: closed in the core");
            Drop();
            Draw();
            Changed?.Invoke(null);
            return;
        }
        if (s.view is { } v && (!_hasView || v.moved != _moved) && _pressed is null)
        {
            _hasView = true;
            _moved = v.moved;
            (_x, _y, _zoom) = (v.x, v.y, v.zoom);
        }
        Log.Write($"map {_map} shown: {State(s.state)}, zoom {s.view?.zoom:0.0}, moved {s.view?.moved}, {s.tiles.Count} tiles, {s.tracks.Count} tracks, {s.dots.Count} dots, {s.clusters.Count} clusters, photo {s.photo is not null}, centre pin {s.centrePin}");
        if (s.state is not MapStatus.MapStateShown && wasShown) Drop();
        _builtZoom = double.NaN;
        Draw();
        Changed?.Invoke(s);
    }

    private static string State(MapStatus s) => s switch
    {
        MapStatus.MapStateShown => "shown",
        MapStatus.MapStateConsent c => $"consent ({(c.renewal ? "renewal" : "first")})",
        MapStatus.MapStateOffline => "offline",
        _ => "not offered",
    };

    /// <summary>Every tile let go at once (a withdrawal, the map closed): nothing of it stays drawn or held.</summary>
    private void Drop()
    {
        _tiles.Children.Clear();
        _placed.Clear();
        _held.Clear();
        _overlay.Children.Clear();
        _builtZoom = double.NaN;
    }

    /// <summary>What the map's place shows: the map, or the consent's offer, or why there is none.</summary>
    private void Draw()
    {
        var shown = _shown?.state is MapStatus.MapStateShown;
        _canvas.Visibility = shown ? Visibility.Visible : Visibility.Collapsed;
        _chrome.Visibility = shown ? Visibility.Visible : Visibility.Collapsed;
        _attributionText.Text = _shown?.attribution ?? "";
        _attribution.Visibility = shown && !string.IsNullOrEmpty(_shown?.attribution) ? Visibility.Visible : Visibility.Collapsed;
        _centrePin.Visibility = shown && _shown?.centrePin == true ? Visibility.Visible : Visibility.Collapsed;
        _status.Content = _shown?.state switch
        {
            null or MapStatus.MapStateShown => null,
            MapStatus.MapStateConsent c => ConsentOffer(c),
            MapStatus.MapStateOffline => Why(Words.MapNeedsConnection()),
            _ => Why(Words.MapNotOffered()),
        };
        if (!shown) return;
        foreach (var t in _shown!.tiles)
        {
            if (_held.Get(t.tile) is null && _reading.Add(t.tile)) Read(t);
        }
        Frame();
    }

    private static UIElement Why(string text)
    {
        var line = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(16) };
        line.Children.Add(new SymbolIcon(Symbol.Map));
        line.Children.Add(new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center });
        return line;
    }

    /// <summary>The consent's offer where the map would stand (the web's MapConsentPrompt): what turning on sends where, the
    /// recipients by name, and its one button.</summary>
    public static UIElement ConsentOffer(MapStatus.MapStateConsent c)
    {
        var panel = new StackPanel { Spacing = 8, Padding = new Thickness(16), MaxWidth = 560, HorizontalAlignment = HorizontalAlignment.Left };
        AutomationProperties.SetAutomationId(panel, "map-consent");
        panel.Children.Add(new TextBlock { Text = Words.MapConsentTitle(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"] });
        panel.Children.Add(new TextBlock { Text = Words.MapConsentBody(), TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"] });
        if (Hosts(c) is { } hosts) panel.Children.Add(new TextBlock { Text = hosts, TextWrapping = TextWrapping.Wrap });
        var on = new Button { Content = Words.MapConsentEnable(), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
        AutomationProperties.SetAutomationId(on, "map-consent-enable");
        on.Click += (_, _) =>
        {
            Log.Write("map consent: turn map services on");
            Act.Send(new Call.MapTurnOn());
        };
        panel.Children.Add(on);
        return panel;
    }

    /// <summary>The recipients a consent given now names; a renewal says it is asked again for them.</summary>
    public static string? Hosts(MapStatus.MapStateConsent c)
    {
        if (c.tileHost is not { } tiles) return null;
        if (c.geocoderHost is { } places) return c.renewal ? Words.MapConsentRenewBoth(tiles, places) : Words.MapConsentHosts(tiles, places);
        return c.renewal ? Words.MapConsentRenewTiles(tiles) : Words.MapConsentHostsTiles(tiles);
    }

    /// <summary>Tile <paramref name="t"/>'s bytes from the core (Map.Tile, Answer.Bytes), decoded in memory and held.</summary>
    private async void Read(TileShown t)
    {
        try
        {
            var (answer, bytes) = await Core.CallAsync(new Call.MapTile { map = _map, tile = t.tile });
            _reading.Remove(t.tile);
            if (_closed || bytes is null || answer is not Answer.Bytes)
            {
                if (!_closed) Log.Write($"map {_map}: Map.Tile {t.tile} answered {(answer is Answer.Refused r ? $"Refused {r.why}" : answer.GetType().Name)}");
                return;
            }
            if (_shown?.state is not MapStatus.MapStateShown) return;
            using var stream = new InMemoryRandomAccessStream();
            await stream.WriteAsync(bytes.AsBuffer());
            stream.Seek(0);
            var picture = new BitmapImage();
            await picture.SetSourceAsync(stream);
            if (_closed || _shown?.state is not MapStatus.MapStateShown) return;
            var z = int.TryParse(t.tile.Split('/')[0], out var level) ? level : 0;
            _held.Put(t.tile, new HeldTile(z, t.x, t.y, t.size, picture));
            Frame();
        }
        catch (Exception e)
        {
            _reading.Remove(t.tile);
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"map {_map}: a tile was not drawn ({e.GetType().Name})");
        }
    }

    /// <summary>Layout pixels per normalised unit at the host's zoom.</summary>
    private double Scale => Tile * Math.Pow(2, _zoom);

    private Point Px(double x, double y) => new((x - _x) * Scale + _width / 2, (y - _y) * Scale + _height / 2);

    /// <summary>Everything placed for the host's view now: the tiles held (those of the view's level on top, the levels
    /// left under them until theirs arrive), the overlays moved — or built anew where the zoom changed.</summary>
    private void Frame()
    {
        if (_shown is not { state: MapStatus.MapStateShown } s || _width <= 0) return;
        var level = s.tiles.Count > 0 && int.TryParse(s.tiles[0].tile.Split('/')[0], out var l) ? l : (int)Math.Floor(_zoom);
        var keep = new HashSet<string>();
        foreach (var (key, held) in _held.Entries())
        {
            var at = Px(held.X, held.Y);
            var edge = held.Size * Scale;
            if (at.X > _width || at.Y > _height || at.X + edge < 0 || at.Y + edge < 0) continue;
            keep.Add(key);
            if (!_placed.TryGetValue(key, out var image))
            {
                image = new Image { Source = held.Picture, Stretch = Stretch.Fill, IsHitTestVisible = false };
                _placed[key] = image;
                _tiles.Children.Add(image);
            }
            // half a pixel more each way: no seam between neighbours at a fractional zoom
            image.Width = edge + 0.5;
            image.Height = edge + 0.5;
            Canvas.SetLeft(image, Math.Floor(at.X));
            Canvas.SetTop(image, Math.Floor(at.Y));
            Canvas.SetZIndex(image, held.Z == level ? 100 : 50 - Math.Abs(held.Z - level));
        }
        foreach (var key in _placed.Keys.Where(k => !keep.Contains(k)).ToList())
        {
            _tiles.Children.Remove(_placed[key]);
            _placed.Remove(key);
        }
        if (double.IsNaN(_builtZoom) || Math.Abs(_builtZoom - _zoom) > 1e-9) BuildOverlay(s);
        var shift = new Point((_builtX - _x) * Scale, (_builtY - _y) * Scale);
        _overlay.RenderTransform = new TranslateTransform { X = shift.X, Y = shift.Y };
    }

    /// <summary>The overlays at the host's zoom about its centre now: the tracks, the dots, a photo's point, the pins.</summary>
    private void BuildOverlay(MapShown s)
    {
        _builtZoom = _zoom;
        (_builtX, _builtY) = (_x, _y);
        _overlay.Children.Clear();
        foreach (var track in s.tracks.OrderBy(t => t.selected))
        {
            var thickness = track.selected ? 6.0 : 4.0;
            foreach (var segment in track.segments)
            {
                var line = new Polyline
                {
                    Stroke = new SolidColorBrush(Argb(track.color)),
                    StrokeThickness = thickness,
                    StrokeLineJoin = PenLineJoin.Round,
                    StrokeStartLineCap = PenLineCap.Round,
                    StrokeEndLineCap = PenLineCap.Round,
                    IsHitTestVisible = false,
                };
                // the dash in pixels as the core gives it; XAML's dash counts in stroke widths
                if (track.dash is { Count: > 0 } dash)
                {
                    line.StrokeDashArray = Dash(dash, thickness);
                    line.StrokeDashCap = PenLineCap.Flat;
                }
                foreach (var p in segment) line.Points.Add(Px(p.x, p.y));
                _overlay.Children.Add(line);
            }
        }
        var accent = (Brush)Application.Current.Resources["AccentFillColorDefaultBrush"];
        var white = new SolidColorBrush(Microsoft.UI.Colors.White);
        foreach (var d in s.dots)
        {
            var at = Px(d.x, d.y);
            var dot = new Ellipse { Width = 10, Height = 10, Fill = accent, Stroke = white, StrokeThickness = 2, IsHitTestVisible = false };
            Canvas.SetLeft(dot, at.X - 5);
            Canvas.SetTop(dot, at.Y - 5);
            _overlay.Children.Add(dot);
        }
        if (s.photo is { } photo)
        {
            var at = Px(photo.at.x, photo.at.y);
            if (photo.direction is { } deg)
            {
                // the direction it faced: a needle from the point, true north up
                var r = deg * Math.PI / 180;
                var needle = new Line
                {
                    X1 = at.X, Y1 = at.Y, X2 = at.X + 26 * Math.Sin(r), Y2 = at.Y - 26 * Math.Cos(r),
                    Stroke = accent, StrokeThickness = 3, StrokeEndLineCap = PenLineCap.Triangle, IsHitTestVisible = false,
                };
                _overlay.Children.Add(needle);
            }
            var point = new Ellipse { Width = 16, Height = 16, Fill = accent, Stroke = white, StrokeThickness = 3, IsHitTestVisible = false };
            Canvas.SetLeft(point, at.X - 8);
            Canvas.SetTop(point, at.Y - 8);
            _overlay.Children.Add(point);
        }
        foreach (var c in s.clusters)
        {
            var at = Px(c.at.x, c.at.y);
            var pin = Pin(c);
            Canvas.SetLeft(pin, at.X - 22);
            Canvas.SetTop(pin, at.Y - 22);
            _overlay.Children.Add(pin);
        }
    }

    /// <summary>A pin as drawn (no Button: a click is hit-tested, Map.Tap): the Memory's cover in a ring, the count beside it
    /// where it stands for several.</summary>
    private static FrameworkElement Pin(ClusterShown c)
    {
        var glyph = new FontIcon { Glyph = HomeView.KindGlyph("MEMORY"), FontSize = 16 };
        var cover = RowCovers.Prefix(glyph, c.pin.node.nodeId, 36, 36);
        var ring = new Border
        {
            Width = 44, Height = 44, CornerRadius = new CornerRadius(22), BorderThickness = new Thickness(3),
            BorderBrush = new SolidColorBrush(Microsoft.UI.Colors.White),
            Background = (Brush)Application.Current.Resources["SolidBackgroundFillColorBaseBrush"],
            Child = new Border { CornerRadius = new CornerRadius(18), Child = cover, Width = 36, Height = 36 },
            IsHitTestVisible = false,
        };
        var pin = new Grid { Width = 44, Height = 44, IsHitTestVisible = false };
        pin.Children.Add(ring);
        if (c.count > 1)
        {
            var badge = new Border
            {
                MinWidth = 20, Height = 20, CornerRadius = new CornerRadius(10), Padding = new Thickness(5, 0, 5, 0),
                HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, -6, -10, 0),
                Background = (Brush)Application.Current.Resources["AccentFillColorDefaultBrush"],
                Child = new TextBlock
                {
                    Text = c.count.ToString(System.Globalization.CultureInfo.CurrentCulture), FontSize = 11, VerticalAlignment = VerticalAlignment.Center,
                    HorizontalAlignment = HorizontalAlignment.Center,
                    Foreground = (Brush)Application.Current.Resources["TextOnAccentFillColorPrimaryBrush"],
                },
            };
            pin.Children.Add(badge);
        }
        AutomationProperties.SetName(pin, MapPins.Name(c.count, c.pin.node.name));
        AutomationProperties.SetAutomationId(pin, $"map-pin:{c.pin.node.name}");
        return pin;
    }

    /// <summary>A core dash (on-off lengths in pixels) in XAML's units: multiples of the stroke's width.</summary>
    public static DoubleCollection Dash(IReadOnlyList<int> dash, double thickness)
    {
        var lengths = new DoubleCollection();
        foreach (var d in dash) lengths.Add(d / thickness);
        return lengths;
    }

    /// <summary>A core colour (ARGB in a long) as XAML's.</summary>
    public static Windows.UI.Color Argb(long argb) =>
        Windows.UI.Color.FromArgb((byte)(argb >> 24), (byte)(argb >> 16), (byte)(argb >> 8), (byte)argb);

    private void Gestures()
    {
        _root.PointerPressed += (_, e) =>
        {
            if (_shown?.state is not MapStatus.MapStateShown || !e.GetCurrentPoint(_root).Properties.IsLeftButtonPressed) return;
            if (IsChrome(e.OriginalSource)) return;
            _pressed = _last = e.GetCurrentPoint(_root).Position;
            _dragged = false;
            _root.CapturePointer(e.Pointer);
            _root.Focus(FocusState.Pointer);
            e.Handled = true;
        };
        _root.PointerMoved += (_, e) =>
        {
            if (_pressed is not { } from) return;
            var at = e.GetCurrentPoint(_root).Position;
            if (!_dragged && Math.Abs(at.X - from.X) + Math.Abs(at.Y - from.Y) < Slop) return;
            _dragged = true;
            PanBy(at.X - _last.X, at.Y - _last.Y);
            _last = at;
            e.Handled = true;
        };
        void Release(PointerRoutedEventArgs e, bool lost)
        {
            if (_pressed is not { } from) return;
            _pressed = null;
            _root.ReleasePointerCapture(e.Pointer);
            if (_dragged)
            {
                Log.Write($"map {_map}: panned to {_x:0.000000},{_y:0.000000}");
                TellView();
            }
            else if (!lost) Tapped(from);
            e.Handled = true;
        }
        _root.PointerReleased += (_, e) => Release(e, false);
        _root.PointerCaptureLost += (_, e) => Release(e, true);
        _root.DoubleTapped += (_, e) =>
        {
            if (_shown?.state is not MapStatus.MapStateShown || IsChrome(e.OriginalSource)) return;
            var at = e.GetPosition(_root);
            ZoomBy(1, at.X, at.Y);
            e.Handled = true;
        };
        _root.PointerWheelChanged += (_, e) =>
        {
            if (_shown?.state is not MapStatus.MapStateShown) return;
            var point = e.GetCurrentPoint(_root);
            ZoomBy(point.Properties.MouseWheelDelta / 120.0 * 0.5, point.Position.X, point.Position.Y);
            e.Handled = true;
        };
        _root.KeyDown += (_, e) =>
        {
            if (_shown?.state is not MapStatus.MapStateShown) return;
            const double step = 64;
            switch (e.Key)
            {
                case Windows.System.VirtualKey.Add or (Windows.System.VirtualKey)187: ZoomBy(1, _width / 2, _height / 2); break;
                case Windows.System.VirtualKey.Subtract or (Windows.System.VirtualKey)189: ZoomBy(-1, _width / 2, _height / 2); break;
                case Windows.System.VirtualKey.Left: PanBy(step, 0); TellView(); break;
                case Windows.System.VirtualKey.Right: PanBy(-step, 0); TellView(); break;
                case Windows.System.VirtualKey.Up: PanBy(0, step); TellView(); break;
                case Windows.System.VirtualKey.Down: PanBy(0, -step); TellView(); break;
                default: return;
            }
            e.Handled = true;
        };
    }

    /// <summary>Whether a pointer landed on the map's own buttons or the consent's offer, not on the map.</summary>
    private bool IsChrome(object source)
    {
        for (var e = source as DependencyObject; e is not null && !ReferenceEquals(e, _root); e = VisualTreeHelper.GetParent(e))
        {
            if (ReferenceEquals(e, _chrome) || ReferenceEquals(e, _status) || ReferenceEquals(e, _attribution)) return true;
        }
        return false;
    }

    /// <summary>The map moved by a drag of (<paramref name="dx"/>, <paramref name="dy"/>) layout pixels: drawn at once, the
    /// core told at most every 100 ms.</summary>
    private void PanBy(double dx, double dy)
    {
        _x = Math.Clamp(_x - dx / Scale, 0, 1);
        _y = Math.Clamp(_y - dy / Scale, 0, 1);
        Frame();
        if (DateTime.UtcNow - _told >= TimeSpan.FromMilliseconds(100)) TellView();
        else if (!_tell.IsRunning) _tell.Start();
    }

    /// <summary>The zoom changed by <paramref name="by"/> about (<paramref name="px"/>, <paramref name="py"/>): the point under
    /// it stays where it is.</summary>
    private void ZoomBy(double by, double px, double py)
    {
        if (_width <= 0) return;
        var zoom = Math.Clamp(_zoom + by, MinZoom, MaxZoom);
        if (Math.Abs(zoom - _zoom) < 1e-9) return;
        var wx = _x + (px - _width / 2) / Scale;
        var wy = _y + (py - _height / 2) / Scale;
        _zoom = zoom;
        _x = Math.Clamp(wx - (px - _width / 2) / Scale, 0, 1);
        _y = Math.Clamp(wy - (py - _height / 2) / Scale, 0, 1);
        Log.Write($"map {_map}: zoom {_zoom:0.0}");
        Frame();
        TellView();
    }

    /// <summary>The member's view told to the core (Map.View with its centre): its tiles follow.</summary>
    private void TellView()
    {
        _tell.Stop();
        if (_closed || _width <= 0) return;
        _told = DateTime.UtcNow;
        Act.Send(new Call.MapView { map = _map, x = _x, y = _y, zoom = _zoom, width = _width, height = _height, scale = Rasterization });
    }

    /// <summary>The map's size told alone: the core opens at its view for that size, or keeps its own.</summary>
    private void TellSize()
    {
        if (_closed) return;
        Act.Send(new Call.MapView { map = _map, x = null, y = null, zoom = null, width = _width, height = _height, scale = Rasterization });
    }

    private double Rasterization => _root.XamlRoot?.RasterizationScale ?? 1;

    /// <summary>A click on the map: the pin nearest under it told to the core (Map.Tap) — its Memory opened, or the Memories
    /// sharing its spot listed; else the core framed them.</summary>
    private async void Tapped(Point at)
    {
        try
        {
            if (_shown is not { } s || s.clusters.Count == 0) return;
            var hit = s.clusters
                .Select(c => (c, d: Distance(Px(c.at.x, c.at.y), at)))
                .Where(h => h.d <= PinReach)
                .OrderBy(h => h.d)
                .Select(h => h.c)
                .FirstOrDefault();
            if (hit is null) return;
            Log.Write($"map {_map}: pin tapped ({hit.count})");
            var (answer, _) = await Core.CallAsync(new Call.MapTap { map = _map, nodeId = hit.pin.node.nodeId });
            switch (answer)
            {
                case Answer.MapTapped { open: { page: { } page } }:
                    Log.Write($"map {_map}: opens {page.name}");
                    Act.Send(new Call.ShellOpen { Ref = page });
                    break;
                case Answer.MapTapped { listed.Count: > 0 } t:
                    Log.Write($"map {_map}: {t.listed.Count} listed");
                    await List(t.listed);
                    break;
                case Answer.MapTapped:
                    Log.Write($"map {_map}: framed");
                    break;
                default:
                    Log.Write($"Map.Tap answered {answer.GetType().Name}");
                    break;
            }
        }
        catch (Exception e)
        {
            Log.Write($"map {_map}: a tap failed ({e.GetType().Name})");
        }
    }

    private static double Distance(Point a, Point b) => Math.Sqrt((a.X - b.X) * (a.X - b.X) + (a.Y - b.Y) * (a.Y - b.Y));

    /// <summary>The Memories that share one spot at the highest zoom, each opening its page.</summary>
    private async System.Threading.Tasks.Task List(IReadOnlyList<PinShown> pins)
    {
        var list = new ListView { SelectionMode = ListViewSelectionMode.None, IsItemClickEnabled = true, MinWidth = 320 };
        AutomationProperties.SetAutomationId(list, "map-listed");
        foreach (var p in pins)
        {
            var row = new ListViewItem { Content = new TextBlock { Text = p.node.name }, Tag = p };
            AutomationProperties.SetAutomationId(row, $"map-listed:{p.node.name}");
            list.Items.Add(row);
        }
        var dialog = new ContentDialog
        {
            Title = Words.MapClusterTitle(pins.Count),
            Content = new ScrollViewer { Content = list, MaxHeight = 420 },
            CloseButtonText = Words.MemoryClose(),
            DefaultButton = ContentDialogButton.Close,
        };
        AutomationProperties.SetAutomationId(dialog, "map-cluster-dialog");
        list.ItemClick += (_, e) =>
        {
            var row = e.ClickedItem as ListViewItem ?? (e.ClickedItem as FrameworkElement)?.Parent as ListViewItem;
            var pin = row?.Tag as PinShown;
            if (pin?.page is { } page)
            {
                dialog.Hide();
                Act.Send(new Call.ShellOpen { Ref = page });
            }
        };
        await Dialogs.Show(_window, dialog);
    }

    public void Dispose()
    {
        if (_closed) return;
        _closed = true;
        _tell.Stop();
        _watch?.Dispose();
        Drop();
        if (_closesItself) Act.Send(new Call.MapClose { map = _map });
    }
}
