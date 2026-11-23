// An SVG shown by WebView2 in its image mode (inbox #1059; ADR 0155 § The file facts' slice D15): the core's page
// (Answer.SvgPage, core's SvgView) holds the SVG only as its <img>, and a browser's image mode runs no script and loads
// nothing an SVG names. Around it the browser is closed as PdfWeb's is — its arguments (no resolver, no proxy, nothing of
// the browser's own on the network), InPrivate in a user-data folder of its own deleted after it — and more: the page's
// script is off (the zoom's script is the app's, which WebView2 runs beside it), the page and the picture are answered
// from memory under a host that resolves nowhere and every other request is refused, no navigation but the page's own,
// no window, no download, no context menu, no browser zoom (it would fit the picture again). A foreignObject never gets
// here (SvgView.refused, in the core's open). The zoom lays the picture out again at each step, so Chromium paints its
// vectors anew — only the tiles in view: sharp, and no bitmap past the view. No XAML here: FilePage hosts it in a WebView2
// control, SvgWebTest on a window of its own.

namespace Veyq.Core;

using System;
using System.IO;
using System.Text;
using Microsoft.Web.WebView2.Core;

/// <summary>The core's page for an SVG (Answer.SvgPage), as the host needs it.</summary>
internal sealed record SvgPage(string Document, string DocumentUrl, string PictureUrl, string Policy, double[] Steps, string[] Scripts)
{
    /// <summary>The step at <paramref name="index"/>'s script; the steps clamp.</summary>
    public string Script(int index) => Scripts[Math.Clamp(index, 0, Scripts.Length - 1)];
}

internal static class SvgWeb
{
    /// <summary>Sets <paramref name="core"/> up to show <paramref name="svg"/> in <paramref name="page"/> and nothing else,
    /// and navigates to it. <paramref name="seen"/> is told every request the view made (the regression's witness).</summary>
    public static void Show(CoreWebView2Environment environment, CoreWebView2 core, SvgPage page, byte[] svg, Action<string>? seen = null)
    {
        var s = core.Settings;
        s.IsScriptEnabled = false;
        s.AreDevToolsEnabled = false;
        s.AreHostObjectsAllowed = false;
        s.IsWebMessageEnabled = false;
        s.IsStatusBarEnabled = false;
        s.AreDefaultScriptDialogsEnabled = false;
        s.AreDefaultContextMenusEnabled = false;
        s.IsPasswordAutosaveEnabled = false;
        s.IsGeneralAutofillEnabled = false;
        s.IsReputationCheckingRequired = false;
        s.IsSwipeNavigationEnabled = false;
        s.IsBuiltInErrorPageEnabled = false;
        s.AreBrowserAcceleratorKeysEnabled = false;
        // Ctrl+wheel and Ctrl+plus would zoom the browser, which fits the picture again; the page's own steps zoom it
        s.IsZoomControlEnabled = false;
        s.IsPinchZoomEnabled = true;
        var document = Encoding.UTF8.GetBytes(page.Document);
        var headers = $"Cache-Control: no-store\r\nContent-Security-Policy: {page.Policy}";
        core.AddWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All, CoreWebView2WebResourceRequestSourceKinds.All);
        core.WebResourceRequested += (_, e) =>
        {
            var uri = e.Request.Uri;
            seen?.Invoke(uri);
            if (e.Request.Method == "GET" && (uri == page.DocumentUrl || uri == page.PictureUrl))
            {
                var (bytes, type) = uri == page.DocumentUrl ? (document, "text/html; charset=utf-8") : (svg, "image/svg+xml");
                e.Response = environment.CreateWebResourceResponse(new MemoryStream(bytes, false).AsRandomAccessStream(), 200, "OK",
                    $"Content-Type: {type}\r\nContent-Length: {bytes.Length}\r\n{headers}");
                return;
            }
            if (!PdfWeb.Network(uri)) return;
            Log.Write("svg view: a request refused");
            e.Response = environment.CreateWebResourceResponse(null, 403, "Forbidden", "Cache-Control: no-store");
        };
        core.NavigationStarting += (_, e) =>
        {
            if (e.Uri == page.DocumentUrl) return;
            Log.Write("svg view: a navigation refused");
            e.Cancel = true;
        };
        core.FrameNavigationStarting += (_, e) => e.Cancel = true;
        core.NewWindowRequested += (_, e) => e.Handled = true;
        core.DownloadStarting += (_, e) => e.Cancel = true;
        core.SaveAsUIShowing += (_, e) => e.Cancel = true;
        core.LaunchingExternalUriScheme += (_, e) => e.Cancel = true;
        core.PermissionRequested += (_, e) => e.State = CoreWebView2PermissionState.Deny;
        core.ScreenCaptureStarting += (_, e) => e.Cancel = true;
        core.BasicAuthenticationRequested += (_, e) => e.Cancel = true;
        core.ContextMenuRequested += (_, e) => e.Handled = true;
        core.Navigate(page.DocumentUrl);
    }

    /// <summary>The page from the core's answer.</summary>
    public static SvgPage From(Veyq.Bridge.Wire.Answer.SvgPage answer) =>
        new(answer.document, answer.documentUrl, answer.pictureUrl, answer.policy, [.. answer.steps], [.. answer.scripts]);
}
