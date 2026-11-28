// A cover drawn from an SVG (core's SvgCover, uc.platform.node-cover main flow 3; the web's rasterizeSvg): the core's one
// page — the SVG as an <img> of a data: URL under a policy that loads only data: images — drawn by WebView2 at the size the
// core planned, at one pixel per CSS pixel on a transparent background, through the DevTools protocol's screenshot
// (Page.captureScreenshot beyond the viewport, so the view itself stays one pixel and out of sight). Script is off, every
// request but the page refused, the browser in a user-data folder of its own deleted after (PdfWeb's). WIC decodes the
// PNG; the host hands the core straight RGBA, which the core encodes as the cover's WebP. Direct2D's SvgImageSource would
// need no browser, but it draws no <text>.

namespace Veyq.Core;

using System;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Text.Json;
using System.Threading.Tasks;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Web.WebView2.Core;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

internal static class SvgRaster
{
    /// <summary>The drawing of <paramref name="page"/> at <paramref name="width"/> × <paramref name="height"/>: straight RGBA
    /// for the core and the frame's picture; null where WebView2 is missing or did not draw it. The view lives in
    /// <paramref name="host"/> while it draws.</summary>
    public static async Task<(byte[] Rgba, SoftwareBitmap Shown)?> Draw(Panel host, string page, int width, int height)
    {
        if (!PdfWeb.Available()) return null;
        var folder = PdfWeb.NewFolder();
        CoreWebView2Environment? environment = null;
        var control = new WebView2 { Width = 1, Height = 1, Opacity = 0, IsHitTestVisible = false, IsTabStop = false, DefaultBackgroundColor = Colors.Transparent };
        host.Children.Add(control);
        try
        {
            environment = await PdfWeb.EnvironmentAsync(folder);
            await control.EnsureCoreWebView2Async(environment, PdfWeb.ControllerOptions(environment));
            var core = control.CoreWebView2;
            var s = core.Settings;
            s.IsScriptEnabled = false;
            s.AreHostObjectsAllowed = false;
            s.IsWebMessageEnabled = false;
            s.AreDefaultContextMenusEnabled = false;
            s.IsPasswordAutosaveEnabled = false;
            s.IsGeneralAutofillEnabled = false;
            s.IsReputationCheckingRequired = false;
            core.AddWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All, CoreWebView2WebResourceRequestSourceKinds.All);
            var env = environment;
            core.WebResourceRequested += (_, e) =>
            {
                if (!PdfWeb.Network(e.Request.Uri)) return;
                Log.Write("svg cover: a request refused");
                e.Response = env.CreateWebResourceResponse(null, 403, "Forbidden", "Cache-Control: no-store");
            };
            var navigations = 0;
            core.NavigationStarting += (_, e) =>
            {
                if (++navigations == 1) return;
                Log.Write("svg cover: a navigation refused");
                e.Cancel = true;
            };
            core.FrameNavigationStarting += (_, e) => e.Cancel = true;
            core.NewWindowRequested += (_, e) => e.Handled = true;
            core.DownloadStarting += (_, e) => e.Cancel = true;
            core.LaunchingExternalUriScheme += (_, e) => e.Cancel = true;
            core.PermissionRequested += (_, e) => e.State = CoreWebView2PermissionState.Deny;
            var loaded = new TaskCompletionSource<bool>();
            core.NavigationCompleted += (_, e) => loaded.TrySetResult(e.IsSuccess);
            core.NavigateToString(page);
            if (!await loaded.Task) return null;
            await core.CallDevToolsProtocolMethodAsync("Emulation.setDefaultBackgroundColorOverride", "{\"color\":{\"r\":0,\"g\":0,\"b\":0,\"a\":0}}");
            var shot = await core.CallDevToolsProtocolMethodAsync("Page.captureScreenshot",
                $"{{\"format\":\"png\",\"captureBeyondViewport\":true,\"fromSurface\":true,\"clip\":{{\"x\":0,\"y\":0,\"width\":{width},\"height\":{height},\"scale\":1}}}}");
            var png = Convert.FromBase64String(JsonDocument.Parse(shot).RootElement.GetProperty("data").GetString() ?? "");
            using var stream = new InMemoryRandomAccessStream();
            await stream.WriteAsync(png.AsBuffer());
            stream.Seek(0);
            var decoder = await BitmapDecoder.CreateAsync(stream);
            var transform = new BitmapTransform { ScaledWidth = (uint)width, ScaledHeight = (uint)height, InterpolationMode = BitmapInterpolationMode.Fant };
            var straight = await decoder.GetSoftwareBitmapAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Straight, transform,
                ExifOrientationMode.IgnoreExifOrientation, ColorManagementMode.DoNotColorManage);
            var rgba = new byte[width * height * 4];
            straight.CopyToBuffer(rgba.AsBuffer());
            for (var i = 0; i < rgba.Length; i += 4) (rgba[i], rgba[i + 2]) = (rgba[i + 2], rgba[i]);
            Log.Write($"svg cover: drawn at {width}×{height} from a {png.Length} B capture");
            return (rgba, SoftwareBitmap.Convert(straight, BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied));
        }
        catch (Exception e)
        {
            Log.Write($"svg cover: not drawn ({e.GetType().Name}: {e.Message})");
            return null;
        }
        finally
        {
            host.Children.Remove(control);
            if (environment is null) _ = PdfWeb.DeleteAsync(folder);
            else
            {
                var queue = control.DispatcherQueue;
                environment.BrowserProcessExited += (_, _) => queue.TryEnqueue(() => _ = PdfWeb.DeleteAsync(folder));
                try
                {
                    control.Close();
                }
                catch (Exception e)
                {
                    Log.Write($"svg cover: the view did not close ({e.GetType().Name})");
                }
            }
        }
    }
}
