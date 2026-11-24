// A PDF in WebView2's own viewer (PdfWeb.cs) as a XAML element, for every application that shows a PDF: a Depot file's
// page (FilePage) and an Expenses receipt (ReceiptViewerDialog, inbox #964). A browser of its own, InPrivate, in a
// user-data folder made for this PDF and deleted once the browser has exited, the bytes served from memory. Its text is
// selected and copied as in any viewer and read by Narrator through Chromium's own accessibility tree, as Edge's viewer
// is; nothing reaches the clipboard but on the person's Ctrl+C or Copy. A password-protected PDF is asked for its password
// by the viewer itself, once (PdfWeb.Route): the password goes to the viewer's process and nowhere else (PdfWebTest).

namespace Veyq.Core;

using System;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.Web.WebView2.Core;

internal sealed class PdfWebView
{
    private readonly byte[] _bytes;
    private readonly WebView2 _control = new() { HorizontalAlignment = HorizontalAlignment.Stretch, VerticalAlignment = VerticalAlignment.Stretch };
    private string? _folder;
    private CoreWebView2Environment? _environment;
    private bool _closed;

    /// <summary>The viewer of <paramref name="bytes"/>, its control named <paramref name="automationId"/> for UI Automation
    /// and <paramref name="name"/> for a screen reader.</summary>
    public PdfWebView(byte[] bytes, string automationId, string name)
    {
        _bytes = bytes;
        AutomationProperties.SetAutomationId(_control, automationId);
        AutomationProperties.SetName(_control, name);
    }

    public FrameworkElement Element => _control;

    /// <summary>The viewer started on the PDF; false where WebView2 did not start (the caller draws the pages instead).</summary>
    public async Task<bool> Start()
    {
        try
        {
            _folder = PdfWeb.NewFolder();
            _environment = await PdfWeb.EnvironmentAsync(_folder);
            if (_closed)
            {
                Close();
                return true;
            }
            await _control.EnsureCoreWebView2Async(_environment, PdfWeb.ControllerOptions(_environment));
            if (_closed)
            {
                Close();
                return true;
            }
            PdfWeb.Show(_environment, _control.CoreWebView2, _bytes);
            return true;
        }
        catch (Exception e)
        {
            Log.Write($"WebView2's PDF viewer did not start ({e.GetType().Name}: {e.Message})");
            Close();
            return false;
        }
    }

    /// <summary>The viewer closed; its folder deleted once its browser has exited.</summary>
    public void Close()
    {
        _closed = true;
        var folder = _folder;
        var environment = _environment;
        _folder = null;
        _environment = null;
        if (folder is null) return;
        if (environment is null)
        {
            _ = PdfWeb.DeleteAsync(folder);
            return;
        }
        var queue = _control.DispatcherQueue;
        environment.BrowserProcessExited += (_, _) => queue.TryEnqueue(() => _ = PdfWeb.DeleteAsync(folder));
        try
        {
            _control.Close();
        }
        catch (Exception e)
        {
            Log.Write($"WebView2's PDF viewer did not close ({e.GetType().Name})");
        }
    }
}
