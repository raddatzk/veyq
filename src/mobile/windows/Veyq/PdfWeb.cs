// A PDF shown by WebView2's own PDF viewer (inbox #955): the one way the Windows app selects and copies a PDF's text, which
// Windows.Data.Pdf (PdfPages.cs) cannot. The opened bytes never touch a file: the viewer navigates to a URL of a host
// that does not exist, and WebResourceRequested answers it from memory; every other request is refused, so the viewer
// fetches nothing off the device. The browser runs InPrivate in a user-data folder of its own under %TEMP%, made for one
// PDF and deleted when its browser has exited; what it may write there was measured to hold none of the PDF's bytes and
// none of its text (PdfWebTest, the regression; native-apps.md § A PDF's text on Windows). Its toolbar keeps no item
// that writes the plaintext (Save, Save as, Print, the More menu), a download or a Save as dialog is cancelled, and the
// context menu holds Copy alone. No XAML here: the app hosts it in a WebView2 control (FilePage), the test on a window
// of its own.
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.Web.WebView2.Core;

namespace Veyq;

internal static class PdfWeb
{
    /// <summary>The host the document is served under: `.invalid` resolves nowhere (RFC 6761), so a request that escaped
    /// WebResourceRequested would fail on the device, never reach another host.</summary>
    public const string Host = "pdf.veyq.invalid";

    /// <summary>The document's URL: a fixed name, so neither the file's name nor anything of it is in a URL.</summary>
    public const string Document = "https://" + Host + "/document.pdf";

    /// <summary>The parent of every viewer's user-data folder, under %TEMP% (a packaged app's own temp folder).</summary>
    public static string Root => Path.Combine(Path.GetTempPath(), "veyq-pdf");

    /// <summary>What the browser is started with: no host name is resolved (every one maps to "not found", so not even
    /// the document's `.invalid` host is asked of the network's resolver), no proxy is looked for (WPAD asked the LAN's
    /// router for wpad.dat), and nothing of the browser's own reaches the network — Edge's configuration service
    /// (config.edge.skype.com, asked at each start), component updates, pings, the domain-reliability reports. Measured
    /// without them on the Windows VM (PdfWebTest's net log): each of these went out.</summary>
    public const string Arguments = "--host-resolver-rules=\"MAP * ~NOTFOUND\" --no-proxy-server "
        + "--disable-background-networking --disable-component-update --no-pings --disable-domain-reliability --disable-sync";

    /// <summary>The toolbar's items that would write the plaintext where the person did not choose to save it (Save a copy
    /// is the header's, ADR 0047), or hand it on (Print spools it to disk; the More menu holds Print and Read aloud).</summary>
    public const CoreWebView2PdfToolbarItems Hidden = CoreWebView2PdfToolbarItems.Save | CoreWebView2PdfToolbarItems.SaveAs
        | CoreWebView2PdfToolbarItems.Print | CoreWebView2PdfToolbarItems.MoreSettings | CoreWebView2PdfToolbarItems.FullScreen;

    /// <summary>The context menu's items kept: Copy, Select all and nothing else (no Print, Save as, Read aloud, Search).</summary>
    private static readonly HashSet<string> MenuKept = new(StringComparer.OrdinalIgnoreCase) { "copy", "selectAll" };

    /// <summary>Whether this device has WebView2's runtime (Windows 11 ships it); without it the app draws the pages with
    /// Windows.Data.Pdf, as before.</summary>
    public static bool Available()
    {
        try
        {
            return !string.IsNullOrEmpty(CoreWebView2Environment.GetAvailableBrowserVersionString());
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>A new, empty user-data folder for one viewer. Folders a viewer left behind (the app ended before its
    /// browser did) are deleted first, where no browser holds them still.</summary>
    public static string NewFolder()
    {
        if (Directory.Exists(Root))
        {
            foreach (var left in Directory.GetDirectories(Root)) TryDelete(left);
        }
        var folder = Path.Combine(Root, Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        return folder;
    }

    /// <summary>The browser for one viewer in <paramref name="folder"/>, with <paramref name="extra"/> arguments beside
    /// <see cref="Arguments"/> (the measurement's net log).</summary>
    public static async Task<CoreWebView2Environment> EnvironmentAsync(string folder, string? extra = null)
    {
        var options = new CoreWebView2EnvironmentOptions
        {
            AdditionalBrowserArguments = extra is null ? Arguments : Arguments + " " + extra,
            // a crash's report stays in the folder, which is deleted, instead of going to Microsoft with the memory it held
            IsCustomCrashReportingEnabled = true,
            ExclusiveUserDataFolderAccess = true,
            AreBrowserExtensionsEnabled = false,
        };
        return await CoreWebView2Environment.CreateWithOptionsAsync("", folder, options);
    }

    /// <summary>The controller's options: InPrivate, so the profile keeps its history, cache and storage in memory.</summary>
    public static CoreWebView2ControllerOptions ControllerOptions(CoreWebView2Environment environment)
    {
        var options = environment.CreateCoreWebView2ControllerOptions();
        options.IsInPrivateModeEnabled = true;
        return options;
    }

    /// <summary>Sets <paramref name="core"/> up to show <paramref name="bytes"/> and nothing else, and navigates to it.
    /// <paramref name="seen"/> is told every request the view made (the regression's witness).</summary>
    public static void Show(CoreWebView2Environment environment, CoreWebView2 core, byte[] bytes, Action<string>? seen = null)
    {
        var s = core.Settings;
        s.AreDevToolsEnabled = false;
        s.AreHostObjectsAllowed = false;
        s.IsWebMessageEnabled = false;
        s.IsStatusBarEnabled = false;
        s.AreDefaultScriptDialogsEnabled = false;
        s.IsPasswordAutosaveEnabled = false;
        s.IsGeneralAutofillEnabled = false;
        s.IsReputationCheckingRequired = false;
        s.IsSwipeNavigationEnabled = false;
        s.IsBuiltInErrorPageEnabled = false;
        // Ctrl+P, Ctrl+S and the browser's other keys; Ctrl+C, Ctrl+A and the viewer's own keys stay
        s.AreBrowserAcceleratorKeysEnabled = false;
        s.HiddenPdfToolbarItems = Hidden;
        core.AddWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All, CoreWebView2WebResourceRequestSourceKinds.All);
        core.WebResourceRequested += (_, e) =>
        {
            var uri = e.Request.Uri;
            seen?.Invoke(uri);
            if (uri == Document && e.Request.Method == "GET")
            {
                // a stream over the bytes in memory, no copy of them
                e.Response = environment.CreateWebResourceResponse(new MemoryStream(bytes, false).AsRandomAccessStream(), 200, "OK",
                    $"Content-Type: application/pdf\r\nContent-Length: {bytes.Length}\r\nCache-Control: no-store");
                return;
            }
            // the viewer's own pages (edge:, chrome-extension:) are the browser's, never the network's
            if (!Network(uri)) return;
            Log.Write("pdf view: a request refused");
            e.Response = environment.CreateWebResourceResponse(null, 403, "Forbidden", "Cache-Control: no-store");
        };
        core.NavigationStarting += (_, e) =>
        {
            if (e.Uri == Document) return;
            Log.Write("pdf view: a navigation refused");
            e.Cancel = true;
        };
        core.NewWindowRequested += (_, e) => e.Handled = true;
        core.DownloadStarting += (_, e) =>
        {
            Log.Write("pdf view: a download refused");
            e.Cancel = true;
        };
        core.SaveAsUIShowing += (_, e) =>
        {
            Log.Write("pdf view: Save as refused");
            e.Cancel = true;
        };
        core.LaunchingExternalUriScheme += (_, e) => e.Cancel = true;
        core.PermissionRequested += (_, e) => e.State = CoreWebView2PermissionState.Deny;
        core.ScreenCaptureStarting += (_, e) => e.Cancel = true;
        core.BasicAuthenticationRequested += (_, e) => e.Cancel = true;
        core.ContextMenuRequested += (_, e) =>
        {
            var items = e.MenuItems;
            var names = items.Select(i => i.Name).ToList();
            for (var i = items.Count - 1; i >= 0; i--)
            {
                if (!MenuKept.Contains(items[i].Name)) items.RemoveAt(i);
            }
            Log.Write($"pdf view: context menu {string.Join(",", names)} -> {string.Join(",", items.Select(i => i.Name))}");
        };
        core.Navigate(Document);
    }

    /// <summary>Whether <paramref name="uri"/> would leave the browser for the network.</summary>
    public static bool Network(string uri) =>
        !(uri.StartsWith("edge:", StringComparison.OrdinalIgnoreCase) || uri.StartsWith("chrome-extension:", StringComparison.OrdinalIgnoreCase)
          || uri.StartsWith("chrome:", StringComparison.OrdinalIgnoreCase) || uri.StartsWith("blob:", StringComparison.OrdinalIgnoreCase)
          || uri.StartsWith("data:", StringComparison.OrdinalIgnoreCase) || uri.StartsWith("about:", StringComparison.OrdinalIgnoreCase));

    /// <summary>Deletes <paramref name="folder"/>; false where a file in it is still held (a browser still running).</summary>
    public static bool TryDelete(string folder)
    {
        try
        {
            if (Directory.Exists(folder)) Directory.Delete(folder, true);
            return true;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    /// <summary>Deletes <paramref name="folder"/> once its browser has exited, trying again while its files are released.</summary>
    public static async Task DeleteAsync(string folder)
    {
        for (var i = 0; i < 50; i++)
        {
            if (TryDelete(folder)) return;
            await Task.Delay(100);
        }
        Log.Write("pdf view: its folder is still held; the next viewer deletes it");
    }
}
