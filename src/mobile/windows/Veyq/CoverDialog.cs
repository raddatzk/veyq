// A node's cover (the header CR's Phase 5; the web's CoverDialog around its 16:9 cropper), Fluent's way: one ContentDialog
// holding the 16:9 frame, a zoom Slider under it, "Choose a picture" (the file open picker for the app's window) and,
// where the node has a cover, "Remove cover"; Save the primary button, Cancel the close one. A drag pans the picture.
// The device's part is the platform's (ADR 0127 decision 1): WIC decodes the picked picture (BitmapDecoder, upright by its
// EXIF orientation) scaled to the size the core names (Cover.Fit, the web's COVER_SPEC) and encodes the JPEG at the core's
// quality (BitmapEncoder) into a file the core reads (Header.CoverSave by path, ADR 0128 decision 3), removed after.
// Where the picture sits is the core's math (Cover.Pose, CoverFrame), asked per change: the answer of the latest ask is
// drawn, an older one that arrives after it is dropped.
using System;
using System.IO;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace Veyq;

internal sealed class CoverDialog
{
    private readonly Window _window;
    private readonly long _header;
    private readonly Crop? _stored;
    private readonly Canvas _canvas = new() { Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent) };
    private readonly Image _image = new() { Stretch = Stretch.Fill };
    private readonly Slider _zoom = new() { Minimum = 1, Maximum = 3, StepFrequency = 0.01, IsEnabled = false };
    private readonly TextBlock _problem = new() { TextWrapping = TextWrapping.Wrap, Visibility = Visibility.Collapsed };
    private readonly ProgressRing _ring = new() { IsActive = true, Width = 32, Height = 32 };
    private readonly Button _choose = new();
    private readonly Button _remove = new();
    private ContentDialog? _dialog;
    private SoftwareBitmap? _bitmap;
    private bool _picked;
    private float _quality;
    private Pose? _pose;
    private Crop? _crop;
    private Pose? _dragFrom;
    private long _asked;
    private bool _drawing;
    private bool _saving;

    private CoverDialog(Window window, long header, Crop? stored) => (_window, _header, _stored) = (window, header, stored);

    /// <summary>Opens the cover dialog of <paramref name="node"/> over <paramref name="window"/> for header
    /// <paramref name="header"/>, whose cover is framed at <paramref name="stored"/> (null: none).</summary>
    public static void Open(Window window, long header, NodeAt node, Crop? stored) => new CoverDialog(window, header, stored).Show(node);

    private async void Show(NodeAt node)
    {
        if (Dialogs.Open) return;
        var body = new StackPanel { Spacing = 12, MinWidth = 480 };
        body.Children.Add(new TextBlock { Text = Words.CoverHint(), TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"] });
        var frame = new Grid
        {
            CornerRadius = new CornerRadius(8),
            Background = (Brush)Application.Current.Resources["CardBackgroundFillColorDefaultBrush"],
            BorderBrush = (Brush)Application.Current.Resources["CardStrokeColorDefaultBrush"],
            BorderThickness = new Thickness(1),
        };
        _canvas.Children.Add(_image);
        frame.Children.Add(_canvas);
        _ring.Visibility = _stored is null ? Visibility.Collapsed : Visibility.Visible;
        _ring.HorizontalAlignment = HorizontalAlignment.Center;
        _ring.VerticalAlignment = VerticalAlignment.Center;
        frame.Children.Add(_ring);
        AutomationProperties.SetName(frame, Words.CoverTitle());
        AutomationProperties.SetAutomationId(frame, "cover-frame");
        // 16:9, as wide as the dialog lets it be
        frame.SizeChanged += (_, e) =>
        {
            if (Math.Abs(frame.Height - e.NewSize.Width * 9 / 16) > 0.5) frame.Height = e.NewSize.Width * 9 / 16;
            _canvas.Clip = new RectangleGeometry { Rect = new Windows.Foundation.Rect(0, 0, e.NewSize.Width, e.NewSize.Width * 9 / 16) };
            // the frame's size changed: the same crop, posed anew for it
            Pose(null, _crop ?? (_picked ? null : _stored), 0, 0, 1);
        };
        _canvas.ManipulationMode = ManipulationModes.TranslateX | ManipulationModes.TranslateY;
        _canvas.ManipulationStarted += (_, _) => _dragFrom = _pose;
        _canvas.ManipulationDelta += (_, e) =>
        {
            if (_dragFrom is { } from) Pose(from, null, e.Cumulative.Translation.X, e.Cumulative.Translation.Y, 1);
        };
        body.Children.Add(frame);
        AutomationProperties.SetName(_zoom, Words.CoverZoom());
        AutomationProperties.SetAutomationId(_zoom, "cover-zoom");
        _zoom.Header = Words.CoverZoom();
        _zoom.ValueChanged += (_, e) =>
        {
            if (!_drawing && _pose is { } at) Pose(at, null, 0, 0, e.NewValue / at.zoom);
        };
        body.Children.Add(_zoom);
        _problem.Foreground = (Brush)Application.Current.Resources["SystemFillColorCriticalBrush"];
        AutomationProperties.SetAutomationId(_problem, "cover-problem");
        body.Children.Add(_problem);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        _choose.Content = Words.CoverChoose();
        AutomationProperties.SetAutomationId(_choose, "cover-choose");
        _choose.Click += (_, _) => Choose();
        buttons.Children.Add(_choose);
        if (_stored is not null)
        {
            _remove.Content = Words.CoverRemove();
            AutomationProperties.SetAutomationId(_remove, "cover-remove");
            _remove.Click += (_, _) => Remove();
            buttons.Children.Add(_remove);
        }
        body.Children.Add(buttons);

        var dialog = new ContentDialog
        {
            Title = $"{Words.CoverTitle()} · {node.name}",
            Content = new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Padding = new Thickness(0, 0, 16, 0) },
            PrimaryButtonText = Words.CoverSave(),
            CloseButtonText = Words.EntryCancel(),
            DefaultButton = ContentDialogButton.Primary,
            IsPrimaryButtonEnabled = false,
        };
        AutomationProperties.SetAutomationId(dialog, "cover");
        dialog.PrimaryButtonClick += (_, args) =>
        {
            // the dialog stays until the core said the cover landed
            args.Cancel = true;
            Save();
        };
        dialog.Closing += (_, args) =>
        {
            if (_saving) args.Cancel = true;
        };
        _dialog = dialog;
        if (_stored is not null) LoadStored();
        await Dialogs.Show(_window, dialog);
    }

    /// <summary>Asks the core where the picture sits: from <paramref name="at"/> (or the <paramref name="crop"/> shown, or
    /// centred), panned and zoomed by <paramref name="factor"/>. Only the latest ask's answer is drawn.</summary>
    private async void Pose(Pose? at, Crop? crop, double panX, double panY, double factor)
    {
        if (_bitmap is not { } b || _canvas.ActualWidth <= 0) return;
        var n = ++_asked;
        var call = new Call.CoverPose
        {
            imageWidth = b.PixelWidth, imageHeight = b.PixelHeight, frameWidth = _canvas.ActualWidth, frameHeight = _canvas.ActualWidth * 9 / 16,
            at = at, crop = crop, panX = panX, panY = panY, zoom = factor,
        };
        Answer answer;
        try
        {
            (answer, _) = await Core.CallAsync(call);
        }
        catch (Exception e)
        {
            Log.Write($"Cover.Pose failed: {e.GetType().Name}");
            return;
        }
        if (n != _asked) return;
        if (answer is not Answer.CoverPosed p)
        {
            Log.Write($"Cover.Pose answered {answer.GetType().Name}");
            return;
        }
        _pose = p.pose;
        _crop = p.crop;
        _image.Width = p.width;
        _image.Height = p.height;
        Canvas.SetLeft(_image, p.pose.x);
        Canvas.SetTop(_image, p.pose.y);
        _drawing = true;
        _zoom.Value = p.pose.zoom;
        _drawing = false;
        _zoom.IsEnabled = !_saving;
        if (_dialog is { } d) d.IsPrimaryButtonEnabled = !_saving;
    }

    private async void LoadStored()
    {
        try
        {
            var (answer, bytes) = await Core.CallAsync(new Call.HeaderCoverPicture { header = _header });
            _ring.Visibility = Visibility.Collapsed;
            if (answer is not Answer.Bytes || bytes is null)
            {
                Log.Write($"Header.CoverPicture answered {answer.GetType().Name}");
                return;
            }
            using var stream = new InMemoryRandomAccessStream();
            await stream.WriteAsync(bytes.AsBuffer());
            stream.Seek(0);
            var decoder = await BitmapDecoder.CreateAsync(stream);
            var bitmap = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied, new BitmapTransform(),
                ExifOrientationMode.RespectExifOrientation, ColorManagementMode.ColorManageToSRgb);
            Log.Write($"cover: the stored picture decoded, {bitmap.PixelWidth}×{bitmap.PixelHeight}");
            await Hold(bitmap, picked: false, 0);
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"cover: the stored picture did not decode ({e.GetType().Name}: {e.Message})");
            _ring.Visibility = Visibility.Collapsed;
            Say(Words.CoverUnreadable());
        }
    }

    /// <summary>Holds <paramref name="bitmap"/> for the frame and frames it: as stored, or a picked one centred.</summary>
    private async Task Hold(SoftwareBitmap bitmap, bool picked, float quality)
    {
        _bitmap = bitmap;
        _picked = picked;
        _quality = quality;
        _pose = null;
        _crop = null;
        var source = new SoftwareBitmapSource();
        await source.SetBitmapAsync(bitmap);
        _image.Source = source;
        Pose(null, picked ? null : _stored, 0, 0, 1);
    }

    private void Say(string text)
    {
        _problem.Text = text;
        _problem.Visibility = Visibility.Visible;
    }

    /// <summary>"Choose a picture": the open picker for one image, decoded by WIC at the size the core names.</summary>
    private async void Choose()
    {
        try
        {
            var picker = new Windows.Storage.Pickers.FileOpenPicker { SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.PicturesLibrary, ViewMode = Windows.Storage.Pickers.PickerViewMode.Thumbnail };
            foreach (var ext in new[] { ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".tif", ".tiff", ".webp", ".heic", ".heif" }) picker.FileTypeFilter.Add(ext);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(_window));
            var file = await picker.PickSingleFileAsync();
            if (file is null) return;
            _problem.Visibility = Visibility.Collapsed;
            using var stream = await file.OpenReadAsync();
            var decoder = await BitmapDecoder.CreateAsync(stream);
            var (ow, oh) = (decoder.OrientedPixelWidth, decoder.OrientedPixelHeight);
            var (fit, _) = await Core.CallAsync(new Call.CoverFit { width = (int)ow, height = (int)oh });
            if (fit is not Answer.CoverFit f)
            {
                Log.Write($"Cover.Fit answered {fit.GetType().Name}");
                return;
            }
            // the transform scales the stored frame, before its orientation turns it upright
            var turned = ow != decoder.PixelWidth;
            var transform = new BitmapTransform
            {
                ScaledWidth = (uint)(turned ? f.height : f.width),
                ScaledHeight = (uint)(turned ? f.width : f.height),
                InterpolationMode = BitmapInterpolationMode.Fant,
            };
            var bitmap = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied, transform,
                ExifOrientationMode.RespectExifOrientation, ColorManagementMode.ColorManageToSRgb);
            Log.Write($"cover: picked {ow}×{oh}, framed and encoded at {bitmap.PixelWidth}×{bitmap.PixelHeight}, quality {f.quality}");
            await Hold(bitmap, picked: true, (float)f.quality);
        }
        catch (Exception e)
        {
            Log.Write($"cover: the picture did not decode ({e.GetType().Name}: {e.Message})");
            Say(Words.CoverUnreadable());
        }
    }

    private void Busy(bool on)
    {
        _saving = on;
        if (_dialog is { } d) d.IsPrimaryButtonEnabled = !on && _pose is not null;
        _zoom.IsEnabled = !on && _pose is not null;
        _choose.IsEnabled = !on;
        _remove.IsEnabled = !on;
    }

    /// <summary>Save: a picked picture encoded to the core's spec into a file the core reads, framed by the crop shown;
    /// the stored one only framed anew. The dialog closes once the core said it landed, else says why.</summary>
    private async void Save()
    {
        if (_crop is not { } crop || _saving) return;
        Busy(true);
        string? path = null;
        try
        {
            if (_picked && _bitmap is { } bitmap)
            {
                path = Path.Combine(Path.GetTempPath(), $"veyq-cover-{Guid.NewGuid():N}.jpg");
                await File.WriteAllBytesAsync(path, await Jpeg(bitmap, _quality));
                Log.Write($"cover: encoded {bitmap.PixelWidth}×{bitmap.PixelHeight} to {Path.GetFileName(path)}");
            }
            var (answer, _) = await Core.CallAsync(new Call.HeaderCoverSave { header = _header, path = path, crop = crop });
            Log.Write($"Header.CoverSave ({(path is null ? "framed anew" : "picked")}, crop {crop.x:0.###},{crop.y:0.###},{crop.width:0.###},{crop.height:0.###}) answered " +
                (answer is Answer.Said { notice: { } n } ? n.msg : answer.GetType().Name));
            Busy(false);
            switch (answer)
            {
                case Answer.Said { notice: null }: _dialog?.Hide(); break;
                case Answer.Said { notice: { } said }: Say(Said.Notice(said)); break;
                default: Say(Words.CoverFailed()); break;
            }
        }
        catch (Exception e)
        {
            Log.Write($"cover: not saved ({e.GetType().Name}: {e.Message})");
            Busy(false);
            Say(Words.CoverFailed());
        }
        finally
        {
            if (path is not null) try { File.Delete(path); } catch (IOException) { }
        }
    }

    private async void Remove()
    {
        Busy(true);
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.HeaderCoverRemove { header = _header });
            Log.Write($"Header.CoverRemove answered {(answer is Answer.Said { notice: { } n } ? n.msg : answer.GetType().Name)}");
            Busy(false);
            switch (answer)
            {
                case Answer.Said { notice: null }: _dialog?.Hide(); break;
                case Answer.Said { notice: { } said }: Say(Said.Notice(said)); break;
                default: Say(Words.CoverFailed()); break;
            }
        }
        catch (Exception e)
        {
            Log.Write($"cover: not removed ({e.GetType().Name})");
            Busy(false);
            Say(Words.CoverFailed());
        }
    }

    /// <summary><paramref name="bitmap"/> as a JPEG at <paramref name="quality"/> (0–1), WIC's encoder.</summary>
    private static async Task<byte[]> Jpeg(SoftwareBitmap bitmap, float quality)
    {
        using var stream = new InMemoryRandomAccessStream();
        var options = new BitmapPropertySet { { "ImageQuality", new BitmapTypedValue(quality, Windows.Foundation.PropertyType.Single) } };
        var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.JpegEncoderId, stream, options);
        using var opaque = SoftwareBitmap.Convert(bitmap, BitmapPixelFormat.Bgra8, BitmapAlphaMode.Ignore);
        encoder.SetSoftwareBitmap(opaque);
        await encoder.FlushAsync();
        var bytes = new byte[stream.Size];
        stream.Seek(0);
        await stream.ReadAsync(bytes.AsBuffer(), (uint)bytes.Length, InputStreamOptions.None);
        return bytes;
    }
}
