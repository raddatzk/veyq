// A receipt opened (the web's ReceiptViewer; Linux's receipt_viewer.rs; ADR 0123's Consequences, Expenses whole), Fluent's way: a
// ContentDialog titled with the receipt's name — the editor is a page, so this is the one dialog open —, the receipt in
// its ScrollViewer, "Save a copy" its primary button and Close its close button. The bytes come beside the answer
// (Receipt.Open, ADR 0128) and are shown from memory, never written to disk: a picture decoded by WIC (BitmapImage), a
// PDF by Windows' own renderer (Windows.Data.Pdf, PdfPages.cs) — as many pages as the core says (Receipt.Pages), and what
// it says when that is not all. A file WIC does not decode (HEIC without the codec) or that is no picture and no PDF is
// offered for saving only. Save a copy asks the save picker for a path and the core writes the receipt there
// (Receipt.Save).
using System;
using System.IO;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Storage.Pickers;
using Windows.Storage.Streams;

namespace Veyq;

internal static class ReceiptViewerDialog
{
    /// <summary>The width a receipt is laid out at in the dialog, in effective pixels.</summary>
    private const double Width = 640;

    /// <summary>Opens receipt <paramref name="receipt"/> of Pot <paramref name="pot"/>, listed as <paramref name="name"/>;
    /// <paramref name="say"/> says how a saved copy went.</summary>
    public static async void Open(Window window, long pot, string receipt, string name, Action<bool, string> say)
    {
        try
        {
            var body = new StackPanel { Spacing = 12, Width = Width };
            body.Children.Add(new ProgressRing { IsActive = true, HorizontalAlignment = HorizontalAlignment.Center });
            var dialog = new ContentDialog
            {
                Title = name,
                Content = new ScrollViewer { Content = body, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, VerticalScrollBarVisibility = ScrollBarVisibility.Auto },
                PrimaryButtonText = Words.ReceiptsSave(),
                CloseButtonText = Words.ReceiptsClose(),
                DefaultButton = ContentDialogButton.Close,
                IsPrimaryButtonEnabled = false,
            };
            // a receipt reads at the dialog's own width, wider than a question's
            dialog.Resources["ContentDialogMaxWidth"] = Width + 96;
            AutomationProperties.SetAutomationId(dialog, "receipt-viewer");
            var scale = window.Content.XamlRoot?.RasterizationScale ?? 1.0;
            _ = Load(dialog, body, pot, receipt, (uint)Math.Round(Width * scale));
            if (await Dialogs.Show(window, dialog) == ContentDialogResult.Primary) SaveCopy(window, pot, receipt, name, say);
        }
        catch (Exception e)
        {
            // an exception out of an async void would end the app in combase, with nothing in the log
            Log.Write($"the receipt viewer failed: {e.GetType().Name}: {e.Message}");
        }
    }

    private static async Task Load(ContentDialog dialog, StackPanel body, long pot, string receipt, uint pixels)
    {
        try
        {
            var (answer, bytes) = await Core.CallAsync(new Call.ReceiptOpen { pot = pot, receipt = receipt });
            body.Children.Clear();
            if (answer is not Answer.Bytes { mediaType: var type } || bytes is null)
            {
                Log.Write($"Receipt.Open answered {answer.GetType().Name}");
                body.Children.Add(Note(Words.ReceiptsUnreadable(), "receipt-unreadable"));
                return;
            }
            dialog.IsPrimaryButtonEnabled = true;
            Log.Write($"receipt opened: {bytes.Length} B, {type ?? "no receipt type"}");
            if (type == "application/pdf") await ShowPdf(body, bytes, pixels);
            else if (type?.StartsWith("image/", StringComparison.Ordinal) == true) await ShowPicture(body, bytes);
            else body.Children.Add(OnlySaved());
        }
        catch (Exception e)
        {
            Log.Write($"the receipt did not show: {e.GetType().Name}: {e.Message}");
            body.Children.Clear();
            body.Children.Add(OnlySaved());
        }
    }

    /// <summary>A picture decoded by WIC from the bytes; one it cannot decode is offered for saving.</summary>
    private static async Task ShowPicture(StackPanel body, byte[] bytes)
    {
        var image = new BitmapImage();
        try
        {
            using var stream = new InMemoryRandomAccessStream();
            await stream.WriteAsync(bytes.AsBuffer());
            stream.Seek(0);
            await image.SetSourceAsync(stream);
        }
        catch (Exception e)
        {
            Log.Write($"the receipt's picture did not decode ({e.GetType().Name})");
            body.Children.Add(OnlySaved());
            return;
        }
        Log.Write($"receipt picture decoded: {image.PixelWidth}×{image.PixelHeight}");
        body.Children.Add(Fitted(image, image.PixelWidth, image.PixelHeight, "receipt-picture"));
    }

    /// <summary>A PDF's pages, as many as the core says, each rendered once at the dialog's width in device pixels.</summary>
    private static async Task ShowPdf(StackPanel body, byte[] bytes, uint pixels)
    {
        Windows.Data.Pdf.PdfDocument pdf;
        try
        {
            pdf = await PdfPages.OpenAsync(bytes);
        }
        catch (Exception e)
        {
            Log.Write($"the receipt's PDF did not open ({e.GetType().Name})");
            body.Children.Add(OnlySaved());
            return;
        }
        var total = (int)pdf.PageCount;
        var (answer, _) = await Core.CallAsync(new Call.ReceiptPages { total = total });
        if (answer is not Answer.ReceiptPages pages)
        {
            Log.Write($"Receipt.Pages answered {answer.GetType().Name}");
            return;
        }
        var drawn = 0;
        for (var i = 0; i < pages.shown; i++)
        {
            try
            {
                using var png = await PdfPages.RenderAsync(pdf, (uint)i, pixels);
                var image = new BitmapImage();
                await image.SetSourceAsync(png);
                body.Children.Add(Fitted(image, image.PixelWidth, image.PixelHeight, $"receipt-page:{i}"));
                drawn++;
            }
            catch (Exception e)
            {
                Log.Write($"page {i + 1} of the receipt's PDF did not render ({e.GetType().Name})");
            }
        }
        Log.Write($"receipt PDF: {drawn} of {total} pages drawn");
        if (pages.cut is { } cut) body.Children.Add(Note(Said.Notice(cut), "receipt-pages-cut"));
        if (drawn == 0) body.Children.Add(OnlySaved());
    }

    /// <summary><paramref name="image"/> at the dialog's width at most, its height kept in proportion.</summary>
    private static Image Fitted(BitmapImage image, int width, int height, string id)
    {
        var shown = new Image { Source = image, Stretch = Stretch.Uniform, MaxWidth = Math.Min(Width, Math.Max(1, width)), HorizontalAlignment = HorizontalAlignment.Center };
        AutomationProperties.SetAutomationId(shown, id);
        AutomationProperties.SetName(shown, $"{width}×{height}");
        return shown;
    }

    private static TextBlock Note(string text, string id)
    {
        var note = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"] };
        AutomationProperties.SetAutomationId(note, id);
        return note;
    }

    /// <summary>A file shown only as a copy to save.</summary>
    private static UIElement OnlySaved()
    {
        var panel = new StackPanel { Spacing = 4 };
        panel.Children.Add(new TextBlock { Text = Words.ReceiptsCannotShow(), Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"], TextWrapping = TextWrapping.Wrap });
        panel.Children.Add(Note(Words.ReceiptsSaveToOpen(), "receipt-only-saved"));
        return panel;
    }

    /// <summary>"Save a copy": the save picker names the file, the core writes the receipt there.</summary>
    private static async void SaveCopy(Window window, long pot, string receipt, string name, Action<bool, string> say)
    {
        try
        {
            var picker = new FileSavePicker { SuggestedStartLocation = PickerLocationId.Downloads, SuggestedFileName = Path.GetFileNameWithoutExtension(name) };
            var extension = Path.GetExtension(name);
            picker.FileTypeChoices.Add(Words.ReceiptsTitle(), [extension.Length > 1 ? extension : ".bin"]);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(window));
            var file = await picker.PickSaveFileAsync();
            if (file is null) return;
            var (answer, _) = await Core.CallAsync(new Call.ReceiptSave { pot = pot, receipt = receipt, path = file.Path });
            Log.Write($"receipt saved to {file.Path}: {answer.GetType().Name}");
            if (answer is Answer.Done) say(true, Words.ReceiptsSaved());
            else say(false, Words.ReceiptsNotSaved());
        }
        catch (Exception e)
        {
            Log.Write($"the receipt's copy failed: {e.GetType().Name}: {e.Message}");
            say(false, Words.ReceiptsNotSaved());
        }
    }
}
