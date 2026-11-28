using System;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Microsoft.Web.WebView2.Core;
using Veyq.Core;

namespace Veyq.Tests;

/// <summary>
/// An SVG cover drawn by WebView2 as the cover dialog draws it (SvgCapture over SvgRaster's 1 × 1 view, inbox #1428): a
/// logo with transparency comes back drawn at the size asked — its red disc red and opaque, its empty corner transparent.
/// <c>VEYQ_SVG_CAPTURE_BARE=1</c> captures at the view's own size, as before the fix: the test's bite, red (every pixel
/// transparent, measured on the VM 2026-11-28).
/// </summary>
[TestClass]
public sealed class SvgCaptureTest
{
    [TestMethod]
    [Timeout(300_000)]
    public void ALogoIsDrawnAtTheSizeAskedWithItsTransparency()
    {
        const int width = 400, height = 200;
        var svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"60\" viewBox=\"0 0 120 60\"><circle cx=\"30\" cy=\"30\" r=\"25\" fill=\"#ff0000\"/></svg>";
        var page = "<!doctype html><html><head><meta charset=\"utf-8\"><style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}" +
            $"img{{display:block;width:{width}px;height:{height}px}}</style></head><body><img alt=\"\" src=\"data:image/svg+xml;base64," +
            Convert.ToBase64String(Encoding.UTF8.GetBytes(svg)) + "\"></body></html>";
        var folder = PdfWeb.NewFolder();
        try
        {
            var bgra = PdfWebTest.Sta.Run(async () =>
            {
                using var window = new PdfWebTest.Window();
                Assert.AreNotEqual(IntPtr.Zero, window.Handle, $"no window: {window.Error}");
                var environment = await PdfWeb.EnvironmentAsync(folder);
                var controller = await environment.CreateCoreWebView2ControllerAsync(
                    CoreWebView2ControllerWindowReference.CreateFromWindowHandle((ulong)window.Handle), PdfWeb.ControllerOptions(environment));
                // the cover dialog's view: 1 × 1, never seen
                controller.Bounds = new Windows.Foundation.Rect(0, 0, 1, 1);
                controller.IsVisible = true;
                controller.DefaultBackgroundColor = Windows.UI.Color.FromArgb(0, 0, 0, 0);
                var core = controller.CoreWebView2;
                core.Settings.IsScriptEnabled = false;
                var loaded = new TaskCompletionSource<bool>();
                core.NavigationCompleted += (_, e) => loaded.TrySetResult(e.IsSuccess);
                core.NavigateToString(page);
                Assert.IsTrue(await loaded.Task, "the page did not load");
                var (pixels, _, _) = await SvgCapture.Capture(core, width, height);
                controller.Close();
                var exited = new TaskCompletionSource<bool>();
                environment.BrowserProcessExited += (_, _) => exited.TrySetResult(true);
                await Task.WhenAny(exited.Task, Task.Delay(TimeSpan.FromSeconds(30)));
                return pixels;
            });
            Assert.IsTrue(SvgCapture.Drawn(bgra), "the capture is transparent throughout: nothing was drawn");
            // the disc's centre (30, 30 of 120 × 60, drawn at 400 × 200: 100, 100) is opaque red; the far corner transparent
            var centre = (100 * width + 100) * 4;
            Console.WriteLine($"centre BGRA {bgra[centre]},{bgra[centre + 1]},{bgra[centre + 2]},{bgra[centre + 3]}");
            Assert.IsTrue(bgra[centre + 2] > 200 && bgra[centre + 1] < 60 && bgra[centre] < 60 && bgra[centre + 3] > 240, "the disc's centre is not opaque red");
            var corner = ((height - 2) * width + (width - 2)) * 4;
            Assert.AreEqual(0, bgra[corner + 3], "the empty corner is not transparent");
        }
        finally
        {
            Assert.IsTrue(PdfWebTest.Sta.Run(async () => { await PdfWeb.DeleteAsync(folder); return !Directory.Exists(folder); }), "the view's folder stayed");
        }
    }
}
