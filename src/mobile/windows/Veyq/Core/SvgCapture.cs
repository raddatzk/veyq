// The capture of the cover's SVG page (uc.platform.node-cover main flow 3) once WebView2 loaded it: no XAML here, so
// SvgRaster's hidden view and SvgCaptureTest's window take the same steps. The view is 1 × 1 and never seen; the page is
// laid out and painted at the drawing's size by the DevTools' device metrics, then captured from the surface with its
// transparency (inbox #1428: without the metrics every capture came back transparent, measured on the VM 2026-11-28).

namespace Veyq.Core;

using System;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Text.Json;
using System.Threading.Tasks;
using Microsoft.Web.WebView2.Core;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

internal static class SvgCapture
{
    /// <summary>The test's bite (<c>VEYQ_SVG_CAPTURE_BARE=1</c>): the page captured at the view's own size, as before.</summary>
    private static bool Bare => Environment.GetEnvironmentVariable("VEYQ_SVG_CAPTURE_BARE") == "1";

    /// <summary><paramref name="core"/>'s page as <paramref name="width"/> × <paramref name="height"/> straight BGRA, its
    /// bitmap, and the capture's PNG length. The picture is decoded after the navigation completed, so it is captured again
    /// until something is drawn, a while at most (an SVG that draws nothing stays transparent and is taken so).</summary>
    public static async Task<(byte[] Bgra, SoftwareBitmap Straight, int Png)> Capture(CoreWebView2 core, int width, int height)
    {
        await core.CallDevToolsProtocolMethodAsync("Emulation.setDefaultBackgroundColorOverride", "{\"color\":{\"r\":0,\"g\":0,\"b\":0,\"a\":0}}");
        if (!Bare)
        {
            await core.CallDevToolsProtocolMethodAsync("Emulation.setDeviceMetricsOverride",
                $"{{\"width\":{width},\"height\":{height},\"deviceScaleFactor\":1,\"mobile\":false}}");
        }
        var bgra = new byte[width * height * 4];
        SoftwareBitmap? straight = null;
        var png = 0;
        for (var attempt = 0; attempt < 20; attempt++)
        {
            if (attempt > 0) await Task.Delay(150);
            var shot = await core.CallDevToolsProtocolMethodAsync("Page.captureScreenshot",
                $"{{\"format\":\"png\",\"captureBeyondViewport\":true,\"fromSurface\":true,\"clip\":{{\"x\":0,\"y\":0,\"width\":{width},\"height\":{height},\"scale\":1}}}}");
            var bytes = Convert.FromBase64String(JsonDocument.Parse(shot).RootElement.GetProperty("data").GetString() ?? "");
            png = bytes.Length;
            using var stream = new InMemoryRandomAccessStream();
            await stream.WriteAsync(bytes.AsBuffer());
            stream.Seek(0);
            var decoder = await BitmapDecoder.CreateAsync(stream);
            var transform = new BitmapTransform { ScaledWidth = (uint)width, ScaledHeight = (uint)height, InterpolationMode = BitmapInterpolationMode.Fant };
            straight = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Straight, transform,
                ExifOrientationMode.IgnoreExifOrientation, ColorManagementMode.DoNotColorManage);
            straight.CopyToBuffer(bgra.AsBuffer());
            if (Drawn(bgra)) break;
        }
        return (bgra, straight!, png);
    }

    /// <summary>Whether any pixel of straight BGRA <paramref name="bgra"/> is not fully transparent.</summary>
    public static bool Drawn(byte[] bgra)
    {
        for (var i = 3; i < bgra.Length; i += 4) if (bgra[i] != 0) return true;
        return false;
    }
}
