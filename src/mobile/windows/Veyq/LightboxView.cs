// A Memory's lightbox (core's Lightbox; ADR 0123's Consequences, the Windows app; Linux's lightbox.rs, rule for rule),
// Fluent's way, as Windows' Photos shows a picture: in the gallery's place over black, a CommandBar on top with Close,
// Previous and Next, the position and the medium's name, then Slideshow, Details, Save and Move to trash — each only where
// the core offers it. A picture zooms in its ScrollViewer (Ctrl+wheel, a pinch, +, -, 0); ← and → page; Escape closes. A
// video shows its poster with Play — it never plays by itself — and plays through the core's reader (Medium.Reader,
// MediumPlayer); what the system cannot decode says so, Save stays. The details are a ContentDialog of rows
// (Lightbox.Details; one dialog at a time, Dialogs.Show); Save writes the original where the save picker says (Medium.Save,
// the shell's InfoBar with its progress and Cancel). The slideshow's clock is the core's.
//
// Every state is the core's (Lightbox.Open, Watched.Lightbox); every action one call. Nothing decrypted is written to show
// it (ADR 0047): the picture is decoded in memory, a video read in ranges.
using System;
using System.IO;
using System.Linq;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Storage.Pickers;
using Windows.System;

namespace Veyq;

internal sealed class LightboxView : IDisposable
{
    /// <summary>The longest side a lightbox's picture is held at (the file page's FileView.IMAGE_EDGE).</summary>
    private const int PictureEdge = 4096;

    /// <summary>The most a picture is zoomed in, and each step's factor (Linux's).</summary>
    private const float ZoomMax = 8f;
    private const float ZoomStep = 1.25f;

    private readonly Window _window;
    private readonly long _gallery;
    private readonly Action<bool, string> _say;
    private readonly Action<long> _saving;
    private readonly Action _close;
    private readonly Grid _view = new() { Background = new SolidColorBrush(Microsoft.UI.Colors.Black), RequestedTheme = ElementTheme.Dark };
    private readonly CommandBar _bar = new() { DefaultLabelPosition = CommandBarDefaultLabelPosition.Right, IsDynamicOverflowEnabled = true };
    private readonly TextBlock _title = new() { Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly TextBlock _subtitle = new() { Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"], VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly AppBarButton _previous;
    private readonly AppBarButton _next;
    private readonly AppBarButton _slideshow;
    private readonly AppBarButton _details;
    private readonly AppBarButton _save;
    private readonly AppBarButton _trash;
    /// <summary>Where the medium shows: replaced when the medium or what it shows changes.</summary>
    private readonly ContentControl _stage = new() { HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Stretch };
    private long? _id;
    private Watch? _watch;
    private LightboxShown? _shown;
    /// <summary>What the stage shows now: the medium's id, its picture's blob, whether its player is up.</summary>
    private (string MediaId, string? Picture, bool Playing)? _drawn;
    private ScrollViewer? _zoom;
    /// <summary>Where the view's centre was before a zoom by keys, in the unzoomed picture's frame.</summary>
    private (double X, double Y)? _centre;
    /// <summary>Where the lightbox's keys are held while it stands (the window's content), and the keys.</summary>
    private readonly UIElement _keyHost;
    private readonly System.Collections.Generic.List<KeyboardAccelerator> _keys = [];
    /// <summary>← and → on the window's content, tunnelling: before a focused control's own arrow keys.</summary>
    private readonly KeyEventHandler _arrows;
    private MediumPlayer? _player;
    private bool _closed;

    public UIElement View => _view;

    /// <summary>The lightbox over gallery <paramref name="gallery"/> at row <paramref name="index"/>; <paramref name="close"/>
    /// takes it away (Close, Escape, the core closing it).</summary>
    public LightboxView(Window window, long gallery, int index, Action<bool, string> say, Action<long> saving, Action close)
    {
        _window = window;
        _gallery = gallery;
        _say = say;
        _saving = saving;
        _close = close;
        AppBarButton Button(Symbol symbol, string label, string id, Action act)
        {
            var b = new AppBarButton { Icon = new SymbolIcon(symbol), Label = label };
            AutomationProperties.SetAutomationId(b, id);
            ToolTipService.SetToolTip(b, label);
            b.Click += (_, _) => act();
            return b;
        }
        var back = Button(Symbol.Back, Words.MemoryClose(), "lightbox-close", () => _close());
        _previous = Button(Symbol.Previous, Words.MemoryPrevious(), "lightbox-previous", () => Step(-1));
        _next = Button(Symbol.Next, Words.MemoryNext(), "lightbox-next", () => Step(1));
        _slideshow = Button(Symbol.Play, Words.MemorySlideshowStart(), "lightbox-slideshow", Slideshow);
        _details = Button(Symbol.Help, Words.MemoryDetails(), "lightbox-details", ShowDetails);
        _details.Icon = new FontIcon { Glyph = "\uE946" };
        _save = Button(Symbol.Download, Words.MemoryDownload(), "lightbox-save", Save);
        _trash = Button(Symbol.Delete, Words.MemoryTrash(), "lightbox-trash", Trash);
        foreach (var b in new[] { back, _previous, _next }) _bar.PrimaryCommands.Add(b);
        _bar.PrimaryCommands.Add(new AppBarSeparator());
        foreach (var b in new[] { _slideshow, _details, _save, _trash }) _bar.PrimaryCommands.Add(b);
        var heading = new StackPanel { Margin = new Thickness(12, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetAutomationId(_title, "lightbox-position");
        AutomationProperties.SetAutomationId(_subtitle, "lightbox-name");
        heading.Children.Add(_title);
        heading.Children.Add(_subtitle);
        _bar.Content = heading;
        _bar.Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        _view.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _view.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        _view.Children.Add(_bar);
        Grid.SetRow(_stage, 1);
        _view.Children.Add(_stage);
        AutomationProperties.SetAutomationId(_view, "lightbox");
        _keyHost = (UIElement)window.Content;
        _keyHost.KeyboardAcceleratorPlacementMode = KeyboardAcceleratorPlacementMode.Hidden;
        _arrows = (_, e) =>
        {
            if (e.Key is not (VirtualKey.Left or VirtualKey.Right) || e.OriginalSource is TextBox or Slider || Held(VirtualKey.Menu)
                || Held(VirtualKey.Control) || Held(VirtualKey.Shift)) return;
            e.Handled = true;
            Step(e.Key == VirtualKey.Left ? -1 : 1);
        };
        _keyHost.AddHandler(UIElement.PreviewKeyDownEvent, _arrows, true);
        Keys();
        Open(index);
    }

    /// <summary>The keyboard's focus in the lightbox, so its keys reach it.</summary>
    public void Focus()
    {
        _view.IsTabStop = true;
        _view.Loaded += (_, _) => _view.Focus(FocusState.Programmatic);
    }

    private async void Open(int index)
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.LightboxOpen { gallery = _gallery, index = index });
            if (answer is not Answer.LightboxOpened { lightbox: var lightbox })
            {
                Log.Write($"the lightbox did not open in the core ({answer.GetType().Name})");
                return;
            }
            if (_closed)
            {
                Act.Send(new Call.LightboxClose { lightbox = lightbox });
                return;
            }
            _id = lightbox;
            _watch = Core.Watch(new Watched.Lightbox { lightbox = lightbox }, Updated, e => Log.Write($"a lightbox update: {e.Message}"));
        }
        catch (Exception e)
        {
            Log.Write($"the lightbox did not open ({e.GetType().Name})");
        }
    }

    private void Updated(Update update)
    {
        if (_closed || update is not Update.Lightbox l) return;
        if (l.shown is { } s)
        {
            Log.Write($"lightbox shown: {s.index + 1} of {s.count}, {s.row?.mediaId ?? "-"}, playing {s.playing}, slideshow {s.slideshow?.ToString() ?? "-"}, save {s.downloadName is not null}, trash {s.canTrash}");
        }
        _shown = l.shown;
        if (l.shown?.row is null)
        {
            // closed by the core (its gallery or tenant), or nothing left to show: back to the gallery
            _close();
            return;
        }
        Draw();
    }

    /// <summary>+ - 0 zoom and Escape back as accelerators of the window's content while the lightbox stands, so they reach
    /// it wherever the focus went; ← → page on that content's PreviewKeyDown. As accelerators they were taken first by the
    /// focused control's own arrows: after the save picker the focus is back on the bar's Save, whose CommandBar moves it
    /// with ← → (measured 2026-11-15: + zoomed there, → paged nothing). A text box and the player's seek bar keep theirs.</summary>
    private void Keys()
    {
        void Add(VirtualKey key, Action act, VirtualKeyModifiers modifiers = VirtualKeyModifiers.None)
        {
            var a = new KeyboardAccelerator { Key = key, Modifiers = modifiers };
            a.Invoked += (_, e) =>
            {
                e.Handled = true;
                act();
            };
            _keys.Add(a);
            _keyHost.KeyboardAccelerators.Add(a);
        }
        Add((VirtualKey)187, () => ZoomBy(ZoomStep));               // = and + (VK_OEM_PLUS)
        Add(VirtualKey.Add, () => ZoomBy(ZoomStep));
        Add((VirtualKey)189, () => ZoomBy(1 / ZoomStep));           // - (VK_OEM_MINUS)
        Add(VirtualKey.Subtract, () => ZoomBy(1 / ZoomStep));
        Add(VirtualKey.Number0, () => SetZoom(1));
        Add(VirtualKey.NumberPad0, () => SetZoom(1));
        Add(VirtualKey.Escape, () => _close());
    }

    private static bool Held(VirtualKey key) =>
        Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(key).HasFlag(Windows.UI.Core.CoreVirtualKeyStates.Down);

    /// <summary>Pages by <paramref name="by"/> (−1 back, 1 on) where there is a medium there.</summary>
    private void Step(int by)
    {
        if (_id is not { } lightbox || _shown is not { } s) return;
        var to = s.index + by;
        if (to >= 0 && to < s.count) Act.Send(new Call.LightboxGo { lightbox = lightbox, index = to });
    }

    private void Slideshow()
    {
        if (_id is not { } lightbox) return;
        var on = _shown?.slideshow != SlideshowState.Playing;
        Act.Send(new Call.LightboxSlideshow { lightbox = lightbox, on = on });
    }

    private async void Trash()
    {
        if (_id is not { } lightbox) return;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.LightboxTrash { lightbox = lightbox });
            // the Undo is the gallery's, held while its state offers it
            if (answer is Answer.Trashed { undoable: var undoable }) Log.Write($"lightbox trash: Trashed, undo {undoable}");
            else if (answer is Answer.Said { notice: { } n }) _say(false, Said.Notice(n));
            else Log.Write($"Lightbox.Trash answered {answer.GetType().Name}");
        }
        catch (Exception e)
        {
            Log.Write($"Lightbox.Trash failed ({e.GetType().Name})");
        }
    }

    private void Draw()
    {
        if (_shown is not { row: { } row } s) return;
        var name = row.name ?? Words.MemoryUnnamed(s.index + 1);
        _title.Text = s.position is { } p ? Said.Notice(p) : "";
        _subtitle.Text = name;
        _previous.IsEnabled = s.index > 0;
        _next.IsEnabled = s.index + 1 < s.count;
        var playing = s.slideshow == SlideshowState.Playing;
        _slideshow.Visibility = s.count > 1 ? Visibility.Visible : Visibility.Collapsed;
        _slideshow.Icon = new SymbolIcon(playing ? Symbol.Pause : Symbol.Play);
        _slideshow.Label = playing ? Words.MemorySlideshowPause() : Words.MemorySlideshowStart();
        ToolTipService.SetToolTip(_slideshow, _slideshow.Label);
        _save.Visibility = s.downloadName is not null ? Visibility.Visible : Visibility.Collapsed;
        _trash.Visibility = s.canTrash ? Visibility.Visible : Visibility.Collapsed;
        // the stage: drawn anew when the medium, its picture or its player changed
        if (_player is not null && !s.playing)
        {
            // the core let the reader go (a slideshow started, paged away): the player goes with it
            StopPlayer(tellCore: false);
            _drawn = null;
        }
        var now = (row.mediaId, row.picture, _player is not null);
        if (_drawn == now) return;
        _drawn = now;
        if (_player is not null) return;
        _zoom = null;
        if (row.blocked)
        {
            _stage.Content = Message(row.said is { } w ? Said.Key(w) : Words.MemoryBlocked(), "\uE733");
            return;
        }
        var column = new Grid();
        column.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        column.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        if (row.picture is not null)
        {
            var image = new Image { Stretch = Stretch.Uniform };
            AutomationProperties.SetAutomationId(image, "lightbox-picture");
            AutomationProperties.SetName(image, name);
            var zoom = new ScrollViewer
            {
                Content = image,
                ZoomMode = ZoomMode.Enabled,
                MinZoomFactor = 1,
                MaxZoomFactor = ZoomMax,
                HorizontalScrollMode = ScrollMode.Auto,
                VerticalScrollMode = ScrollMode.Auto,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                HorizontalContentAlignment = HorizontalAlignment.Center,
                VerticalContentAlignment = VerticalAlignment.Center,
            };
            AutomationProperties.SetAutomationId(zoom, "lightbox-zoom");
            // the picture fitted to the view at zoom 1; zoomed, the scroll holds it larger
            zoom.SizeChanged += (_, e) =>
            {
                image.Width = e.NewSize.Width;
                image.Height = e.NewSize.Height;
            };
            zoom.ViewChanged += (sv, e) =>
            {
                if (e.IsIntermediate) return;
                var z = (ScrollViewer)sv!;
                // the centre kept: put back once the scroll took the new zoom (an offset asked with the zoom was held to the
                // extent before it, measured 2026-11-15: a zoom by keys stayed at the top left)
                if (_centre is { } c)
                {
                    _centre = null;
                    z.ChangeView(Math.Max(0, c.X * z.ZoomFactor - z.ViewportWidth / 2), Math.Max(0, c.Y * z.ZoomFactor - z.ViewportHeight / 2), null, disableAnimation: true);
                    return;
                }
                Log.Write($"lightbox zoom {z.ZoomFactor:0.00} at {z.HorizontalOffset:0},{z.VerticalOffset:0} of {z.ExtentWidth:0}×{z.ExtentHeight:0}");
            };
            column.Children.Add(zoom);
            _zoom = zoom;
            Load(row.mediaId, image);
        }
        else
        {
            var glyph = HomeView.Glyph(row.video ? "\uE714" : "\uE91B");
            glyph.FontSize = 96;
            glyph.HorizontalAlignment = HorizontalAlignment.Center;
            glyph.VerticalAlignment = VerticalAlignment.Center;
            column.Children.Add(glyph);
        }
        if (row.video)
        {
            var play = new Button { Content = Words.MemoryPlay(), HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 12, 0, 24), Style = (Style)Application.Current.Resources["AccentButtonStyle"] };
            AutomationProperties.SetAutomationId(play, "lightbox-play");
            play.Click += (_, _) => Play();
            Grid.SetRow(play, 1);
            column.Children.Add(play);
        }
        _stage.Content = column;
    }

    /// <summary>The medium's picture (Lightbox.Picture: its preview, else its original), decoded in memory into
    /// <paramref name="into"/>.</summary>
    private async void Load(string mediaId, Image into)
    {
        if (_id is not { } lightbox) return;
        try
        {
            var (answer, bytes) = await Core.CallAsync(new Call.LightboxPicture { lightbox = lightbox, mediaId = mediaId });
            // another medium shows meanwhile: this answer is not shown
            if (_closed || _drawn?.MediaId != mediaId) return;
            if (answer is not Answer.Bytes || bytes is null)
            {
                Log.Write($"Lightbox.Picture answered {answer.GetType().Name}");
                return;
            }
            var source = await MemoryPage.Decoded(bytes, PictureEdge);
            if (_closed || _drawn?.MediaId != mediaId) return;
            into.Source = source;
            if (source is Microsoft.UI.Xaml.Media.Imaging.BitmapImage b) Log.Write($"lightbox picture {mediaId}: {b.PixelWidth}×{b.PixelHeight}");
        }
        catch (Exception e)
        {
            Log.Write($"the lightbox's picture did not decode ({e.GetType().Name})");
        }
    }

    private void ZoomBy(float factor)
    {
        if (_zoom is { } z) SetZoom(z.ZoomFactor * factor);
    }

    /// <summary>The picture drawn at <paramref name="factor"/> times the view's size (1: fitted to it), its centre kept.</summary>
    private void SetZoom(float factor)
    {
        if (_zoom is not { } z) return;
        var to = Math.Clamp(factor, 1f, ZoomMax);
        // the point at the view's centre, in the picture's own frame: put back there once the zoom is taken
        _centre = ((z.HorizontalOffset + z.ViewportWidth / 2) / z.ZoomFactor, (z.VerticalOffset + z.ViewportHeight / 2) / z.ZoomFactor);
        z.ChangeView(null, null, to, disableAnimation: true);
    }

    /// <summary>Plays the video through a reader the core opens for this medium (Medium.Reader); what the system cannot
    /// decode is said, and Save stays.</summary>
    private async void Play()
    {
        if (_id is not { } lightbox) return;
        var asked = _drawn?.MediaId;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.MediumReader { lightbox = lightbox });
            if (answer is not Answer.Reader { reader: var reader, size: var size, mediaType: var type })
            {
                Log.Write($"Medium.Reader answered {answer.GetType().Name}");
                return;
            }
            if (_closed || _drawn?.MediaId != asked)
            {
                Act.Send(new Call.ReaderClose { reader = reader });
                return;
            }
            Log.Write($"lightbox medium: reader {reader}, {size} B, {type ?? "no type"}");
            var player = new MediumPlayer(reader, size, type, "lightbox-medium", _subtitle.Text, true, _view.DispatcherQueue, () =>
            {
                if (_closed) return;
                StopPlayer(tellCore: true);
                _stage.Content = Message(Words.MemoryVideoFailed(), "\uE714");
            });
            _player = player;
            _zoom = null;
            _stage.Content = player.Element;
            if (_drawn is { } d) _drawn = d with { Playing = true };
        }
        catch (Exception e)
        {
            Log.Write($"the lightbox's medium did not open ({e.GetType().Name}: {e.Message})");
            if (!_closed) _stage.Content = Message(Words.MemoryVideoFailed(), "\uE714");
        }
    }

    /// <summary>The player let go (its stream closed, the core's reader with it) and the core told (Lightbox.Stop) where it
    /// did not stop it itself.</summary>
    private void StopPlayer(bool tellCore)
    {
        if (_player is not { } p) return;
        _player = null;
        _stage.Content = null;
        p.Dispose();
        if (tellCore && _id is { } lightbox && !_closed) Act.Send(new Call.LightboxStop { lightbox = lightbox });
    }

    /// <summary>The details (Lightbox.Details) in a dialog of rows, "Added by" where the core places it.</summary>
    private async void ShowDetails()
    {
        if (_id is not { } lightbox || _shown?.row?.mediaId is not { } mediaId) return;
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.LightboxDetails { lightbox = lightbox, mediaId = mediaId });
            if (answer is not Answer.MediumDetails d)
            {
                Log.Write($"Lightbox.Details answered {answer.GetType().Name}");
                return;
            }
            var lines = d.rows.Select(r => (Label: Said.Key(r.label), Value:
                r.words is { } w ? Said.Notice(w)
                : r.size is { } size ? Said.Size(size)
                : r.moment is { } moment ? Said.Moment(moment)
                : r.text ?? "")).ToList();
            if (d.uploader is { } u) lines.Insert(Math.Clamp(d.uploaderAt, 0, lines.Count), (Words.MemoryFieldUploadedBy(), Said.Piece(u)));
            Log.Write($"lightbox details: {lines.Count} rows{(d.uploader is null ? "" : ", the uploader among them")}");
            var list = new StackPanel { Spacing = 10 };
            if (lines.Count == 0) list.Children.Add(new TextBlock { Text = Words.MemoryNoDetail(), TextWrapping = TextWrapping.Wrap });
            foreach (var (label, value) in lines)
            {
                var row = new StackPanel { Spacing = 0 };
                row.Children.Add(new TextBlock { Text = label, Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"], Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"] });
                var text = new TextBlock { Text = value, TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true };
                AutomationProperties.SetName(text, $"{label}: {value}");
                row.Children.Add(text);
                list.Children.Add(row);
            }
            var dialog = new ContentDialog
            {
                Title = Words.MemoryDetails(),
                Content = new ScrollViewer { Content = list, MaxHeight = 480 },
                CloseButtonText = Words.MemoryClose(),
                DefaultButton = ContentDialogButton.Close,
            };
            AutomationProperties.SetAutomationId(dialog, "lightbox-details-dialog");
            await Dialogs.Show(_window, dialog);
        }
        catch (Exception e)
        {
            Log.Write($"the details were not shown ({e.GetType().Name})");
        }
    }

    /// <summary>Save: the save picker suggests the core's name in Pictures, the core writes the original there (Medium.Save),
    /// and the shell's InfoBar follows it with Cancel; the save goes on when the lightbox closes.</summary>
    private async void Save()
    {
        if (_id is not { } lightbox || _shown?.downloadName is not { } name) return;
        try
        {
            var picker = new FileSavePicker { SuggestedStartLocation = PickerLocationId.PicturesLibrary, SuggestedFileName = Path.GetFileNameWithoutExtension(name) };
            var extension = Path.GetExtension(name);
            picker.FileTypeChoices.Add(Words.MemoryDownload(), [extension.Length > 1 ? extension : ".bin"]);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var chosen = await picker.PickSaveFileAsync();
            if (chosen is null) return;
            var (answer, _) = await Core.CallAsync(new Call.MediumSave { lightbox = lightbox, path = chosen.Path });
            if (answer is Answer.Saving { save: var save })
            {
                Log.Write($"saving an original (save {save})");
                _saving(save);
                return;
            }
            Log.Write($"Medium.Save answered {answer.GetType().Name}");
            _say(false, Words.DownloadFailed(name));
        }
        catch (Exception e)
        {
            Log.Write($"the original was not saved: {e.GetType().Name}");
            _say(false, Words.DownloadFailed(name));
        }
    }

    /// <summary>What shows in the medium's place: <paramref name="text"/> under <paramref name="glyph"/>.</summary>
    private static UIElement Message(string text, string glyph)
    {
        var column = new StackPanel { Spacing = 12, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Padding = new Thickness(24) };
        var icon = HomeView.Glyph(glyph);
        icon.FontSize = 48;
        column.Children.Add(icon);
        var words = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center, MaxWidth = 480 };
        AutomationProperties.SetAutomationId(words, "lightbox-message");
        column.Children.Add(words);
        return column;
    }

    /// <summary>Closed in the core once: its player stopped, its watch ended.</summary>
    public void Dispose()
    {
        if (_closed) return;
        _closed = true;
        if (_player is { } p)
        {
            _player = null;
            p.Dispose();
        }
        _stage.Content = null;
        foreach (var k in _keys) _keyHost.KeyboardAccelerators.Remove(k);
        _keyHost.RemoveHandler(UIElement.PreviewKeyDownEvent, _arrows);
        _keys.Clear();
        _watch?.Dispose();
        if (_id is { } lightbox)
        {
            Log.Write("lightbox closed");
            Act.Send(new Call.LightboxClose { lightbox = lightbox });
        }
    }
}
