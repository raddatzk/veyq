using System;
using System.Collections.Concurrent;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Microsoft.Web.WebView2.Core;
using Veyq.Core;

namespace Veyq.Tests;

/// <summary>
/// Inbox #1059's threat model on Windows: an SVG shown in the core's page by WebView2 as the app sets it up (SvgWeb) runs
/// nothing in it and fetches nothing it names. Each hostile file names a loopback listener (script, onload, image,
/// xlink:href, use, feImage, CSS @import and url(), a javascript: link, an external entity, XInclude); the listener sees no
/// connection, the view asks nothing but its page and its picture, the page's title stays its own, and the benign file
/// draws at its size and lays out again at ×8 when the zoom's script runs. The page is the core's own
/// (<c>VEYQ_SVG_PAGE</c>: the svg-page.json WireTest writes). <c>VEYQ_SVG_BARE=1</c> turns the page's script on and puts
/// each SVG inline into the page instead of its &lt;img&gt;: the test's bite, red (the listener hit).
/// </summary>
[TestClass]
public sealed class SvgWebTest
{
    private const string Open = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"200\" height=\"100\"";

    private static string[] Hostile(string at) =>
    [
        $"{Open}><script>fetch('{at}/script');document.title='PWNED'</script><rect width=\"9\" height=\"9\"/></svg>",
        $"{Open} onload=\"fetch('{at}/onload')\"><rect width=\"9\" height=\"9\"/></svg>",
        $"{Open}><image href=\"{at}/image.png\" width=\"9\" height=\"9\"/><image xlink:href=\"{at}/xlink.png\" width=\"9\" height=\"9\"/></svg>",
        $"{Open}><use href=\"{at}/use.svg#x\"/></svg>",
        $"{Open}><filter id=\"f\"><feImage href=\"{at}/feimage.png\"/></filter><rect width=\"9\" height=\"9\" filter=\"url(#f)\"/></svg>",
        $"{Open}><style>@import url({at}/import.css); rect {{ fill: url({at}/fill.svg#p) }}</style><rect width=\"9\" height=\"9\"/></svg>",
        $"{Open}><a href=\"javascript:fetch('{at}/js')\"><rect width=\"9\" height=\"9\"/></a></svg>",
        $"<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY x SYSTEM \"{at}/entity\">]>{Open}><text y=\"15\">&x;</text></svg>",
        $"{Open} xmlns:xi=\"http://www.w3.org/2001/XInclude\"><xi:include href=\"{at}/include.svg\"/></svg>",
    ];

    [TestMethod]
    [Timeout(300_000)]
    public void AnSvgDrawsWithNothingInItRunOrFetched()
    {
        var page = Page();
        var bare = Environment.GetEnvironmentVariable("VEYQ_SVG_BARE") == "1";
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var at = $"http://127.0.0.1:{((IPEndPoint)listener.LocalEndpoint).Port}";
        var hits = new ConcurrentQueue<string>();
        using var stop = new CancellationTokenSource();
        var accepting = Task.Run(async () =>
        {
            while (!stop.IsCancellationRequested)
            {
                try
                {
                    using var client = await listener.AcceptTcpClientAsync(stop.Token);
                    var buffer = new byte[256];
                    var n = await client.GetStream().ReadAsync(buffer, stop.Token);
                    hits.Enqueue(Encoding.ASCII.GetString(buffer, 0, n).Split('\n')[0]);
                }
                catch (OperationCanceledException)
                {
                    return;
                }
            }
        });
        var benign = Encoding.UTF8.GetBytes($"{Open}><rect width=\"100\" height=\"100\" fill=\"#000\"/></svg>");
        var seen = new ConcurrentQueue<string>();
        var folder = PdfWeb.NewFolder();
        try
        {
            var report = PdfWebTest.Sta.Run(async () =>
            {
                using var window = new PdfWebTest.Window();
                Assert.AreNotEqual(IntPtr.Zero, window.Handle, $"no window: {window.Error}");
                var environment = await PdfWeb.EnvironmentAsync(folder);
                var lines = new StringBuilder();
                foreach (var svg in Hostile(at).Select(Encoding.UTF8.GetBytes).Prepend(benign))
                {
                    var controller = await environment.CreateCoreWebView2ControllerAsync(
                        CoreWebView2ControllerWindowReference.CreateFromWindowHandle((ulong)window.Handle), PdfWeb.ControllerOptions(environment));
                    controller.Bounds = new Windows.Foundation.Rect(0, 0, 800, 600);
                    controller.IsVisible = true;
                    var core = controller.CoreWebView2;
                    var shown = bare ? page with { Document = "<!doctype html><html><body>" + Encoding.UTF8.GetString(svg) + "</body></html>", Policy = "default-src * 'unsafe-inline'" } : page;
                    var loaded = new TaskCompletionSource<bool>();
                    core.NavigationCompleted += (_, e) => loaded.TrySetResult(e.IsSuccess);
                    SvgWeb.Show(environment, core, shown, svg, seen.Enqueue);
                    if (bare)
                    {
                        core.Settings.IsScriptEnabled = true;
                        core.Navigate(page.DocumentUrl);
                    }
                    Assert.IsTrue(await Within(loaded.Task, 30), "the page did not load");
                    await Task.Delay(2000);
                    var title = await core.ExecuteScriptAsync("document.title");
                    if (title.Contains("PWNED")) lines.AppendLine("PWNED title");
                    if (svg == benign && !bare)
                    {
                        var size = await core.ExecuteScriptAsync("[document.images[0].naturalWidth, document.images[0].getBoundingClientRect().width]");
                        await core.ExecuteScriptAsync(page.Script(Array.IndexOf(page.Steps, 8.0)));
                        await Task.Delay(300);
                        var zoomed = await core.ExecuteScriptAsync("document.images[0].getBoundingClientRect().width");
                        lines.AppendLine($"benign {size} zoomed x8 {zoomed}");
                    }
                    controller.Close();
                }
                await Close(environment);
                return lines.ToString();
            });
            stop.Cancel();
            Console.WriteLine(report);
            Console.WriteLine($"requests: {string.Join(" ", seen.Distinct())}");
            foreach (var h in hits) Console.WriteLine($"HIT {h}");
            Assert.AreEqual(0, hits.Count, $"the listener was reached: {string.Join("; ", hits)}");
            Assert.IsFalse(report.Contains("PWNED"), "an SVG's script ran");
            var elsewhere = seen.Where(u => PdfWeb.Network(u) && u != page.DocumentUrl && u != page.PictureUrl).ToList();
            Assert.AreEqual(0, elsewhere.Count, $"the view asked for more than its page and picture: {string.Join(" ", elsewhere)}");
            StringAssert.Contains(report, "benign [200,200] zoomed x8 1600", "the benign SVG did not draw at its size, or the zoom did not lay it out again");
        }
        finally
        {
            stop.Cancel();
            listener.Stop();
            Assert.IsTrue(PdfWebTest.Sta.Run(async () => { await PdfWeb.DeleteAsync(folder); return !Directory.Exists(folder); }), "the view's folder stayed");
        }
    }

    /// <summary>The core's page (VEYQ_SVG_PAGE, WireTest's svg-page.json).</summary>
    private static SvgPage Page()
    {
        var path = Environment.GetEnvironmentVariable("VEYQ_SVG_PAGE");
        Assert.IsFalse(string.IsNullOrEmpty(path), "VEYQ_SVG_PAGE names no file: run.py build sets it to WireTest's svg-page.json");
        using var json = JsonDocument.Parse(File.ReadAllText(path));
        var r = json.RootElement;
        return new SvgPage(
            r.GetProperty("document").GetString()!, r.GetProperty("documentUrl").GetString()!, r.GetProperty("pictureUrl").GetString()!,
            r.GetProperty("policy").GetString()!, [.. r.GetProperty("steps").EnumerateArray().Select(e => e.GetDouble())],
            [.. r.GetProperty("scripts").EnumerateArray().Select(e => e.GetString()!)]);
    }

    private static async Task<bool> Within(Task<bool> task, int seconds) =>
        await Task.WhenAny(task, Task.Delay(TimeSpan.FromSeconds(seconds))) == task && task.Result;

    private static async Task Close(CoreWebView2Environment environment)
    {
        var exited = new TaskCompletionSource<bool>();
        environment.BrowserProcessExited += (_, _) => exited.TrySetResult(true);
        await Within(exited.Task, 30);
    }
}
