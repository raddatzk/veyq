// A receipt's PDF drawn by Windows' own renderer (ADR 0123's Consequences, Expenses whole): Windows.Data.Pdf, part of the Windows SDK the app
// builds against, reached by an unpackaged app (PdfPagesTest, an unpackaged process, renders one). The document opens
// from the decrypted bytes in memory, never from a file; each page renders to a PNG in memory, which the viewer decodes
// as a BitmapImage. How many pages are rendered is the core's (Receipt.Pages: Receipts.pagesShown).

namespace Veyq.Core;

using System;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Windows.Data.Pdf;
using Windows.Storage.Streams;

internal static class PdfPages
{
    /// <summary>The PDF in <paramref name="bytes"/>, opened from memory with <paramref name="password"/> where one was typed
    /// (only Windows' renderer sees it; it is neither kept nor sent); throws where Windows reads no PDF there, and
    /// <see cref="NeedsPassword"/> tells a password-protected one (or a wrong password) apart.</summary>
    public static async Task<PdfDocument> OpenAsync(byte[] bytes, string? password = null)
    {
        var stream = new InMemoryRandomAccessStream();
        await stream.WriteAsync(bytes.AsBuffer());
        stream.Seek(0);
        return password is null ? await PdfDocument.LoadFromStreamAsync(stream) : await PdfDocument.LoadFromStreamAsync(stream, password);
    }

    /// <summary>Whether <paramref name="e"/> is Windows refusing a PDF for its password: ERROR_WRONG_PASSWORD, which it
    /// answers for a protected PDF opened without one and for a wrong one alike.</summary>
    public static bool NeedsPassword(Exception e) => e.HResult == unchecked((int)0x8007052B);

    /// <summary>Page <paramref name="index"/> of <paramref name="pdf"/> as a PNG in memory, <paramref name="width"/>
    /// pixels wide, on white (a receipt's page has no background of its own).</summary>
    public static async Task<InMemoryRandomAccessStream> RenderAsync(PdfDocument pdf, uint index, uint width)
    {
        using var page = pdf.GetPage(index);
        var png = new InMemoryRandomAccessStream();
        var options = new PdfPageRenderOptions
        {
            DestinationWidth = width,
            DestinationHeight = (uint)Math.Max(1, Math.Round(width * page.Size.Height / Math.Max(1, page.Size.Width))),
            BackgroundColor = new Windows.UI.Color { A = 255, R = 255, G = 255, B = 255 },
        };
        await page.RenderToStreamAsync(png, options);
        png.Seek(0);
        return png;
    }
}
