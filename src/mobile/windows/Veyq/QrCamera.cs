// A QR scan's camera (the QR CR's D4 and D5): the core's scan (Scan.Open) fed with the camera's luminance plane, a frame at
// a time as Scan.Frame's inbound payload, its state watched (Watched.Scan). The code is read by the core and its text never
// crosses: this side sees only what the scan's kind made of it (ScanShown.found). Linux's qr_camera.rs, in WinUI.
//
// The camera is MediaCapture over the first video capture device, read by a MediaFrameReader in NV12 (its Y plane is the
// luminance; another format is converted to Gray8 first), on the CPU, the preview a MediaPlayerElement over the same frame
// source. No video capture device, or one that does not start, is NONE; the privacy setting refusing the app
// (UnauthorizedAccessException) is DENIED. A debug build reads VEYQ_DEV_CAMERA=<image> as its camera instead: the image
// decoded, converted to Gray8 and offered ten times a second, shown as it is, so the way from a frame to FOUND is measured where no
// camera is. The answer to each frame's call is the backpressure: a frame that arrives while one is under way is dropped.
using System;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Devices.Enumeration;
using Windows.Graphics.Imaging;
using Windows.Media.Capture;
using Windows.Media.Capture.Frames;
using Windows.Media.Core;
using Windows.Media.MediaProperties;
using Windows.Media.Playback;
using Windows.Storage;
using WinRT;

namespace Veyq;

/// <summary>A luminance plane as the core takes it: its size, the bytes a row and the bytes from its first row on.</summary>
internal sealed record Plane(int Width, int Height, int Stride, byte[] Bytes);

/// <summary>A scan's view: the preview while the scan starts or scans, the caller's element for every other state.</summary>
internal sealed class QrCamera : IDisposable
{
    [ComImport, Guid("5B0D3235-4DBA-4D44-865E-8F1D0E4FD04D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private unsafe interface IMemoryBufferByteAccess
    {
        void GetBuffer(out byte* buffer, out uint capacity);
    }

    public Grid View { get; } = new();

    /// <summary>The scan's id in the core once it opened (a SAFETY scan's compare names it: Trust.CompareScanned).</summary>
    public long? Scan => _scan;
    private readonly MediaPlayerElement _preview = new() { Stretch = Microsoft.UI.Xaml.Media.Stretch.Uniform };
    private readonly Image _still = new() { Stretch = Microsoft.UI.Xaml.Media.Stretch.Uniform, Visibility = Visibility.Collapsed };
    private readonly ContentControl _other = new() { HorizontalContentAlignment = HorizontalAlignment.Center, VerticalContentAlignment = VerticalAlignment.Center, Visibility = Visibility.Collapsed };
    private readonly Func<ScanShown, UIElement?> _draw;
    private readonly Action<ScanShown> _changed;
    private long? _scan;
    private Watch? _watch;
    private MediaCapture? _capture;
    private MediaFrameReader? _reader;
    private MediaPlayer? _player;
    private Timer? _testCamera;
    private int _inFlight;
    private int _running;
    private long _taken;
    private volatile bool _closed;

    private QrCamera(Func<ScanShown, UIElement?> draw, Action<ScanShown> changed)
    {
        _draw = draw;
        _changed = changed;
        _other.HorizontalAlignment = HorizontalAlignment.Stretch;
        _other.VerticalAlignment = VerticalAlignment.Stretch;
        View.Children.Add(_preview);
        View.Children.Add(_still);
        View.Children.Add(_other);
    }

    /// <summary>Opens a scan of <paramref name="kind"/> and its camera. <paramref name="draw"/> draws every state but the
    /// preview's (the caller's words); <paramref name="changed"/> hears each state. Called on the UI thread.</summary>
    public static QrCamera Open(ScanKind kind, Func<ScanShown, UIElement?> draw, Action<ScanShown> changed) => Open(kind, null, draw, changed);

    /// <summary><see cref="Open(ScanKind, Func{ScanShown, UIElement?}, Action{ScanShown})"/> with the sign-in form's
    /// server as typed, for a KIT scan (the other-server notice's).</summary>
    public static QrCamera Open(ScanKind kind, string? server, Func<ScanShown, UIElement?> draw, Action<ScanShown> changed)
    {
        var camera = new QrCamera(draw, changed);
        _ = camera.StartAsync(new Call.ScanOpen { kind = kind, server = server, link = null });
        return camera;
    }

    /// <summary>Opens device link <paramref name="link"/>'s own scan (kind LINK, ADR 0141) and its camera:
    /// what it reads goes straight into the link in the core, never back here; the link's state says what came of it.</summary>
    public static QrCamera OpenLink(long link, Func<ScanShown, UIElement?> draw, Action<ScanShown> changed)
    {
        var camera = new QrCamera(draw, changed);
        _ = camera.StartAsync(new Call.ScanOpen { kind = ScanKind.Link, server = null, link = link });
        return camera;
    }

    /// <summary>Whether a scan here has a camera (a video capture device, or a debug build's test camera): asked before a
    /// scan's entry shows (no camera, no entry); the camera itself starts only with a scan.</summary>
    public static async Task<bool> PresentAsync()
    {
        if (DevCamera() is not null) return true;
        try
        {
            return (await DeviceInformation.FindAllAsync(DeviceClass.VideoCapture)).Count > 0;
        }
        catch (Exception e)
        {
            Log.Write($"veyq-scan: the cameras were not listed ({e.GetType().Name})");
            return false;
        }
    }

    private async Task StartAsync(Call.ScanOpen opening)
    {
        try
        {
            var (answer, _) = await Core.CallAsync(opening);
            if (answer is not Answer.ScanOpened opened)
            {
                Log.Write($"Scan.Open answered {answer.GetType().Name}");
                return;
            }
            var scan = opened.scan;
            if (_closed)
            {
                _ = Core.CallAsync(new Call.ScanClose { scan = scan });
                return;
            }
            _scan = scan;
            _watch = Core.Watch(new Watched.Scan { scan = scan }, update =>
            {
                if (update is Update.Scan { shown: { } shown }) Show(shown);
            }, e => Log.Write(e.Message));
            var refused = await StartCameraAsync(scan);
            if (refused is { } camera) await Core.CallAsync(new Call.ScanCamera { scan = scan, camera = camera });
        }
        catch (Exception e)
        {
            // an exception out of this task would be lost: said, the scan stays where it is
            Log.Write($"the scan's camera failed: {e.GetType().Name}: {e.Message}");
        }
    }

    private void Show(ScanShown shown)
    {
        if (shown.state is ScanState.Starting or ScanState.Scanning)
        {
            _other.Visibility = Visibility.Collapsed;
        }
        else
        {
            // nothing more to read: the camera stops at once (its light goes off)
            StopCamera();
            _preview.Visibility = Visibility.Collapsed;
            _still.Visibility = Visibility.Collapsed;
            _other.Content = _draw(shown);
            _other.Visibility = Visibility.Visible;
        }
        _changed(shown);
    }

    /// <summary>Starts the camera for <paramref name="scan"/>: null once it runs (RUNNING follows with its first frame),
    /// else what the scan says instead.</summary>
    private async Task<Camera?> StartCameraAsync(long scan)
    {
        if (DevCamera() is { } path) return await StartTestCameraAsync(scan, path);
        var devices = await DeviceInformation.FindAllAsync(DeviceClass.VideoCapture);
        if (devices.Count == 0)
        {
            Log.Write("veyq-scan: no video capture device");
            return Camera.None;
        }
        var capture = new MediaCapture();
        try
        {
            await capture.InitializeAsync(new MediaCaptureInitializationSettings
            {
                VideoDeviceId = devices[0].Id,
                StreamingCaptureMode = StreamingCaptureMode.Video,
                MemoryPreference = MediaCaptureMemoryPreference.Cpu,
                SharingMode = MediaCaptureSharingMode.ExclusiveControl,
            });
        }
        catch (UnauthorizedAccessException)
        {
            Log.Write("veyq-scan: the camera is refused to the app (Settings, Privacy & security, Camera)");
            capture.Dispose();
            return Camera.Denied;
        }
        catch (Exception e)
        {
            Log.Write($"veyq-scan: the camera did not start ({e.GetType().Name}: {e.Message})");
            capture.Dispose();
            return Camera.None;
        }
        _capture = capture;
        var source = capture.FrameSources.Values.FirstOrDefault(s => s.Info.SourceKind == MediaFrameSourceKind.Color && s.Info.MediaStreamType == Windows.Media.Capture.MediaStreamType.VideoPreview)
            ?? capture.FrameSources.Values.FirstOrDefault(s => s.Info.SourceKind == MediaFrameSourceKind.Color);
        if (source is null)
        {
            Log.Write("veyq-scan: the camera has no colour source");
            return Camera.None;
        }
        var reader = await capture.CreateFrameReaderAsync(source, MediaEncodingSubtypes.Nv12);
        reader.AcquisitionMode = MediaFrameReaderAcquisitionMode.Realtime;
        reader.FrameArrived += (r, _) => Arrived(scan, r);
        if (await reader.StartAsync() != MediaFrameReaderStartStatus.Success)
        {
            Log.Write("veyq-scan: the camera's frame reader did not start");
            reader.Dispose();
            return Camera.None;
        }
        _reader = reader;
        if (_closed)
        {
            // closed while the camera started: it stops at once
            StopCamera();
            return null;
        }
        var player = new MediaPlayer { RealTimePlayback = true, AutoPlay = true, Source = MediaSource.CreateFromMediaFrameSource(source) };
        // a camera's preview is no media session: the music's SMTC stays the app's only one (ADR 0142 decision 3)
        player.CommandManager.IsEnabled = false;
        _player = player;
        _preview.SetMediaPlayer(player);
        return null;
    }

    private void Arrived(long scan, MediaFrameReader reader)
    {
        if (_closed || Interlocked.CompareExchange(ref _inFlight, 1, 0) != 0) return;
        Plane? plane;
        using (var frame = reader.TryAcquireLatestFrame())
        {
            plane = frame?.VideoMediaFrame?.SoftwareBitmap is { } bitmap ? Luminance(bitmap) : null;
        }
        if (plane is null)
        {
            Interlocked.Exchange(ref _inFlight, 0);
            return;
        }
        _ = SendAsync(scan, plane);
    }

    /// <summary>Hands <paramref name="plane"/> to the scan: the camera said running first, once.</summary>
    private async Task SendAsync(long scan, Plane plane)
    {
        try
        {
            if (Interlocked.Exchange(ref _running, 1) == 0)
            {
                await Core.CallAsync(new Call.ScanCamera { scan = scan, camera = Camera.Running });
                Log.Write($"veyq-scan: camera running, {plane.Width}x{plane.Height} at a stride of {plane.Stride}");
            }
            var (answer, _) = await Core.CallAsync(new Call.ScanFrame { scan = scan, width = plane.Width, height = plane.Height, stride = plane.Stride }, plane.Bytes);
            if (answer is Answer.FrameTaken { taken: true })
            {
                if (Interlocked.Increment(ref _taken) % 50 == 1) Log.Write($"veyq-scan: {_taken} frames taken");
            }
            else if (answer is not Answer.FrameTaken) Log.Write($"veyq-scan: Scan.Frame answered {answer.GetType().Name}");
        }
        catch (Exception e)
        {
            Log.Write($"veyq-scan: a frame failed: {e.GetType().Name}");
        }
        finally
        {
            Interlocked.Exchange(ref _inFlight, 0);
        }
    }

    /// <summary>The luminance of <paramref name="bitmap"/>: an NV12 frame's Y plane as it lies, any other converted to Gray8
    /// first; copied from its first row, the last row only to its width.</summary>
    public static unsafe Plane? Luminance(SoftwareBitmap bitmap)
    {
        SoftwareBitmap? converted = null;
        if (bitmap.BitmapPixelFormat is not (BitmapPixelFormat.Nv12 or BitmapPixelFormat.Gray8))
        {
            converted = SoftwareBitmap.Convert(bitmap, BitmapPixelFormat.Gray8);
            bitmap = converted;
        }
        try
        {
            using var buffer = bitmap.LockBuffer(BitmapBufferAccessMode.Read);
            var y = buffer.GetPlaneDescription(0);
            using var reference = buffer.CreateReference();
            reference.As<IMemoryBufferByteAccess>().GetBuffer(out var data, out var capacity);
            var length = (long)y.Stride * (y.Height - 1) + y.Width;
            if (y.StartIndex + length > capacity) return null;
            var bytes = new byte[length];
            Marshal.Copy((IntPtr)(data + y.StartIndex), bytes, 0, (int)length);
            return new Plane(y.Width, y.Height, y.Stride, bytes);
        }
        finally
        {
            converted?.Dispose();
        }
    }

    /// <summary>A debug build's test camera: the image at <paramref name="path"/> offered ten times a second.</summary>
    private async Task<Camera?> StartTestCameraAsync(long scan, string path)
    {
        Log.Write($"veyq-scan: the test camera reads {path} (debug build)");
        SoftwareBitmap shown;
        try
        {
            var file = await StorageFile.GetFileFromPathAsync(path);
            using var stream = await file.OpenReadAsync();
            var decoder = await BitmapDecoder.CreateAsync(stream);
            // the decoder hands no Gray8 (ArgumentException, measured on the VM): the luminance is Convert's, as a camera's
            shown = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException or COMException)
        {
            Log.Write($"veyq-scan: the test camera's image did not read ({e.GetType().Name}: {e.Message})");
            return Camera.None;
        }
        var plane = Luminance(shown);
        if (plane is null) return Camera.None;
        var source = new SoftwareBitmapSource();
        await source.SetBitmapAsync(shown);
        _still.Source = source;
        _preview.Visibility = Visibility.Collapsed;
        _still.Visibility = Visibility.Visible;
        _testCamera = new Timer(_ =>
        {
            if (_closed || Interlocked.CompareExchange(ref _inFlight, 1, 0) != 0) return;
            _ = SendAsync(scan, plane);
        }, null, 0, 100);
        return null;
    }

    private static string? DevCamera()
    {
#if DEBUG
        return Environment.GetEnvironmentVariable("VEYQ_DEV_CAMERA") is { Length: > 0 } path ? path : null;
#else
        return null;
#endif
    }

    private void StopCamera()
    {
        _testCamera?.Dispose();
        _testCamera = null;
        if (_player is { } player)
        {
            // the element lets go of the player before it is disposed (src/mobile/CLAUDE.md, MediaPlayerElement)
            _preview.SetMediaPlayer(null);
            player.Dispose();
            _player = null;
        }
        var capture = _capture;
        _capture = null;
        if (_reader is { } reader)
        {
            _reader = null;
            // the reader stops before its capture goes
            _ = reader.StopAsync().AsTask().ContinueWith(_ => { reader.Dispose(); capture?.Dispose(); }, TaskScheduler.Default);
        }
        else
        {
            capture?.Dispose();
        }
    }

    /// <summary>Ends the scan and its camera.</summary>
    public void Dispose()
    {
        if (_closed) return;
        _closed = true;
        StopCamera();
        _watch?.Dispose();
        _watch = null;
        if (_scan is { } scan) _ = Core.CallAsync(new Call.ScanClose { scan = scan });
    }
}
