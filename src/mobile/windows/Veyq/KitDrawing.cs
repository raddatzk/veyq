// An Emergency Kit file drawn for the core's read (ADR 0136 decision 1: the platform draws, the core decides; ADR 0137
// decision 3, a drawn page's pixels riding in beside the host's reply): the core reads the file the open picker gave by
// its path, decides what needs drawing (KitImport: a kit found in the file's bytes needs none) and asks for it —
// Pdf.Pages, Pdf.Page, Picture.Luma — through Core.Drawer. A PDF is drawn by Windows.Data.Pdf (as a receipt's,
// PdfPages), a picture decoded by BitmapDecoder, each from a stream over the file at that path into memory only
// (InMemoryRandomAccessStream, never a file); each drawing goes back as its luminance, packed row after row, and the
// pixels this side read are zeroed once converted (WinRT's own buffers are let go, not zeroed).
using System;
using System.IO;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Veyq.Bridge.Wire;
using Windows.Data.Pdf;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace Veyq;

internal static class KitDrawing
{
    /// <summary>A drawing's reply and its luminance, or null for a request that is no drawing.</summary>
    public static Task<(HostReply Reply, byte[]? Payload)>? Answer(HostRequest request) => request switch
    {
        HostRequest.PdfPages r => Guard(async () => (new HostReply.Value { value = (await PagesAsync(r.path)).ToString(System.Globalization.CultureInfo.InvariantCulture) }, null)),
        HostRequest.PdfPage r => Guard(async () => Pixels(await PageAsync(r.path, r.page, r.width))),
        HostRequest.PictureLuma r => Guard(async () => Pixels(await PictureAsync(r.path, r.longSide))),
        _ => null,
    };

    /// <summary>A drawing's luminance: 8 bits a pixel, 0 black, 255 white, packed.</summary>
    public sealed record Luma(int Width, int Height, byte[] Bytes);

    private static (HostReply, byte[]?) Pixels(Luma l) => (new HostReply.Pixels { width = l.Width, height = l.Height }, l.Bytes);

    private static async Task<(HostReply, byte[]?)> Guard(Func<Task<(HostReply, byte[]?)>> draw)
    {
        try
        {
            return await draw();
        }
        catch (Exception e)
        {
            // never the path or anything of the file: the kind of failure alone
            Log.Write($"a kit file was not drawn: {e.GetType().Name}");
            return (new HostReply.Failed { error = e.GetType().Name }, null);
        }
    }

    /// <summary>The file at <paramref name="path"/> as a random-access stream over it: read in place, nothing copied to disk.</summary>
    private static IRandomAccessStream Stream(string path) =>
        new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read).AsRandomAccessStream();

    /// <summary>How many pages Windows opens the PDF at <paramref name="path"/> with.</summary>
    public static async Task<uint> PagesAsync(string path)
    {
        using var stream = Stream(path);
        return (await PdfDocument.LoadFromStreamAsync(stream)).PageCount;
    }

    /// <summary>Page <paramref name="page"/> (1-based) of the PDF at <paramref name="path"/>, <paramref name="width"/> px wide on white.</summary>
    public static async Task<Luma> PageAsync(string path, int page, int width)
    {
        if (width <= 0) throw new ArgumentOutOfRangeException(nameof(width));
        using var stream = Stream(path);
        var pdf = await PdfDocument.LoadFromStreamAsync(stream);
        if (page < 1 || page > pdf.PageCount) throw new ArgumentOutOfRangeException(nameof(page));
        using var png = await PdfPages.RenderAsync(pdf, (uint)(page - 1), (uint)width);
        var decoder = await BitmapDecoder.CreateAsync(png);
        return await LuminanceAsync(decoder, decoder.PixelWidth, decoder.PixelHeight, new BitmapTransform());
    }

    /// <summary>The picture at <paramref name="path"/>, scaled so its longer side is <paramref name="longSide"/> px, over white.</summary>
    public static async Task<Luma> PictureAsync(string path, int longSide)
    {
        if (longSide <= 0) throw new ArgumentOutOfRangeException(nameof(longSide));
        using var stream = Stream(path);
        var decoder = await BitmapDecoder.CreateAsync(stream);
        // as stored, its EXIF orientation not applied: a code reads turned as well, and the scale is the stored size's
        var (w, h) = (decoder.PixelWidth, decoder.PixelHeight);
        if (w == 0 || h == 0) throw new InvalidDataException("an empty picture");
        var scale = (double)longSide / Math.Max(w, h);
        var (sw, sh) = ((uint)Math.Max(1, Math.Round(w * scale)), (uint)Math.Max(1, Math.Round(h * scale)));
        return await LuminanceAsync(decoder, sw, sh, new BitmapTransform { ScaledWidth = sw, ScaledHeight = sh, InterpolationMode = BitmapInterpolationMode.Fant });
    }

    private static async Task<Luma> LuminanceAsync(BitmapDecoder decoder, uint width, uint height, BitmapTransform transform)
    {
        var data = await decoder.GetPixelDataAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied, transform,
            ExifOrientationMode.IgnoreExifOrientation, ColorManagementMode.DoNotColorManage);
        var bgra = data.DetachPixelData();
        try
        {
            return Luminance(bgra, (int)width, (int)height);
        }
        finally
        {
            Array.Clear(bgra);
        }
    }

    /// <summary>Premultiplied BGRA rows, packed, over white, as luminance (BT.601, as the phones and Linux take it).</summary>
    public static Luma Luminance(byte[] bgra, int width, int height)
    {
        var luma = new byte[width * height];
        for (var i = 0; i < luma.Length; i++)
        {
            var at = i * 4;
            var under = 255 - bgra[at + 3]; // what white shows through a transparent pixel
            int b = bgra[at] + under, g = bgra[at + 1] + under, r = bgra[at + 2] + under;
            luma[i] = (byte)((r * 299 + g * 587 + b * 114 + 500) / 1000);
        }
        return new Luma(width, height, luma);
    }
}
