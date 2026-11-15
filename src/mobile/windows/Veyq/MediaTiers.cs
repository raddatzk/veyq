// A picked photo's or video's tiers, made for the core (ADR 0137's Consequences, the tiers; ADR 0136 decision 1: decoding and
// encoding are the platform's, the spec — edges, qualities, the poster's moment — the core's, in the request; Linux's
// media_tiers.rs, rule for rule). The core asks on its upload queue's own thread (Media.Duration, then Media.Tiers), which
// Core.Draw hands to the thread pool, never the UI thread; this answers there:
// - a photo through WIC's BitmapDecoder, turned upright by its EXIF orientation as this side reads it (System.Photo.Orientation
//   into the transform's flip and rotation; the decoder's own RespectExifOrientation is not relied on for a scaled read);
// - a video's poster through Windows.Media.Editing: the clip's frame at the core's moment (MediaComposition.GetThumbnailAsync),
//   its length from the clip (MediaClip.OriginalDuration);
// - the preview scaled to fit the core's long edge (never enlarged) over white, the thumbnail fitted from the preview's size,
//   both JPEGs encoded by WIC in memory and handed back as the reply's payload, preview ‖ thumbnail (ADR 0137 decision 3,
//   inward). Nothing is written to disk (ADR 0047): the file is read in place at its path.
//
// What the platform cannot decode (a HEIC without the HEIF extension, a codec Media Foundation lacks, no file) is answered
// with no tiers, and the original goes up alone with its icon — the phones' rule.
using System;
using System.IO;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Veyq.Bridge.Wire;
using Windows.Graphics.Imaging;
using Windows.Media.Editing;
using Windows.Storage;
using Windows.Storage.Streams;

namespace Veyq;

internal static class MediaTiers
{
    /// <summary>The core's spec for one medium's tiers (MediaUpload's, in Media.Tiers).</summary>
    public sealed record Spec(int PreviewEdge, double PreviewQuality, int ThumbnailEdge, double ThumbnailQuality, long PosterAtMs);

    /// <summary>A medium's tiers: its upright size, and the two JPEGs.</summary>
    public sealed record Tiers(int Width, int Height, byte[] Preview, byte[] Thumbnail);

    /// <summary>The reply to the core's Media.Duration and Media.Tiers with the tiers as its payload; null for every other
    /// request.</summary>
    public static Task<(HostReply Reply, byte[]? Payload)>? Answer(HostRequest request) => request switch
    {
        HostRequest.MediaDuration r => Duration(r.path),
        HostRequest.MediaTiers r => Answered(r),
        _ => null,
    };

    private static async Task<(HostReply, byte[]?)> Duration(string path)
    {
        var ms = await DurationMsAsync(path);
        Log.Write($"media duration: {(ms is { } m ? $"{m} ms" : "unknown")}");
        return (new HostReply.Value { value = ms?.ToString(System.Globalization.CultureInfo.InvariantCulture) }, null);
    }

    private static async Task<(HostReply, byte[]?)> Answered(HostRequest.MediaTiers r)
    {
        try
        {
            var t = await TiersAsync(r.path, r.mediaType, new Spec(r.previewEdge, r.previewQuality, r.thumbnailEdge, r.thumbnailQuality, r.posterAtMs));
            Log.Write($"media tiers: {t.Width}×{t.Height}, preview {t.Preview.Length} B, thumbnail {t.Thumbnail.Length} B");
            var payload = new byte[t.Preview.Length + t.Thumbnail.Length];
            t.Preview.CopyTo(payload, 0);
            t.Thumbnail.CopyTo(payload, t.Preview.Length);
            return (new HostReply.Tiers { width = t.Width, height = t.Height, previewSize = t.Preview.Length }, payload);
        }
        catch (Exception e)
        {
            // never the path or anything of the file: the kind of failure alone
            Log.Write($"media tiers: none ({e.GetType().Name} {e.HResult:X8})");
            return (new HostReply.Tiers { width = null, height = null, previewSize = 0 }, null);
        }
    }

    /// <summary>The length of the video at <paramref name="path"/> in milliseconds; null where it does not open or says none.</summary>
    public static async Task<long?> DurationMsAsync(string path)
    {
        try
        {
            var clip = await MediaClip.CreateFromFileAsync(await StorageFile.GetFileFromPathAsync(Path.GetFullPath(path)));
            var ms = (long)clip.OriginalDuration.TotalMilliseconds;
            return ms > 0 ? ms : null;
        }
        catch (Exception e)
        {
            Log.Write($"media duration: none ({e.GetType().Name} {e.HResult:X8})");
            return null;
        }
    }

    /// <summary>The tiers of the file at <paramref name="path"/>, a photo or a video by the core's <paramref name="mediaType"/>.</summary>
    public static async Task<Tiers> TiersAsync(string path, string mediaType, Spec spec)
    {
        if (mediaType.StartsWith("video/", StringComparison.Ordinal))
        {
            using var frame = await PosterAsync(path, spec.PosterAtMs);
            return await FromAsync(frame, 1, spec);
        }
        using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        using var stream = file.AsRandomAccessStream();
        return await FromAsync(stream, null, spec);
    }

    /// <summary>The frame of the video at <paramref name="path"/> at <paramref name="atMs"/>, at its own size, as the image
    /// stream Windows.Media.Editing hands back.</summary>
    private static async Task<IRandomAccessStream> PosterAsync(string path, long atMs)
    {
        var clip = await MediaClip.CreateFromFileAsync(await StorageFile.GetFileFromPathAsync(Path.GetFullPath(path)));
        var composition = new MediaComposition();
        composition.Clips.Add(clip);
        var at = TimeSpan.FromMilliseconds(Math.Clamp(atMs, 0, Math.Max(0, (long)clip.OriginalDuration.TotalMilliseconds - 1)));
        return await composition.GetThumbnailAsync(at, 0, 0, VideoFramePrecision.NearestFrame);
    }

    /// <summary>The tiers of the picture <paramref name="stream"/> holds, turned by <paramref name="orientation"/> (null: as
    /// its EXIF says).</summary>
    private static async Task<Tiers> FromAsync(IRandomAccessStream stream, ushort? orientation, Spec spec)
    {
        var decoder = await BitmapDecoder.CreateAsync(stream);
        var turn = orientation ?? await OrientationAsync(decoder);
        var (flip, rotation, swaps) = Transform(turn);
        var (storedW, storedH) = ((int)decoder.PixelWidth, (int)decoder.PixelHeight);
        if (storedW <= 0 || storedH <= 0) throw new InvalidDataException("an empty picture");
        var (width, height) = swaps ? (storedH, storedW) : (storedW, storedH);
        var preview = Fit(width, height, spec.PreviewEdge);
        var thumbnail = Fit(preview.Width, preview.Height, spec.ThumbnailEdge);
        return new Tiers(width, height,
            await JpegAsync(decoder, preview, flip, rotation, swaps, spec.PreviewQuality),
            await JpegAsync(decoder, thumbnail, flip, rotation, swaps, spec.ThumbnailQuality));
    }

    /// <summary>The EXIF orientation the decoder reads (1–8), 1 where it names none.</summary>
    private static async Task<ushort> OrientationAsync(BitmapDecoder decoder)
    {
        try
        {
            var props = await decoder.BitmapProperties.GetPropertiesAsync(["System.Photo.Orientation"]);
            if (props.TryGetValue("System.Photo.Orientation", out var v) && v.Value is ushort o && o is >= 1 and <= 8) return o;
        }
        catch (Exception e)
        {
            // a format without the property (a PNG) throws: as stored
            Log.Write($"media tiers: no orientation read ({e.GetType().Name})");
        }
        return 1;
    }

    /// <summary>EXIF orientation <paramref name="o"/> as WIC's transform: its flip and its clockwise rotation (WIC flips
    /// first, then rotates), and whether the upright picture's sides are the stored one's swapped.</summary>
    public static (BitmapFlip Flip, BitmapRotation Rotation, bool Swaps) Transform(ushort o) => o switch
    {
        2 => (BitmapFlip.Horizontal, BitmapRotation.None, false),
        3 => (BitmapFlip.None, BitmapRotation.Clockwise180Degrees, false),
        4 => (BitmapFlip.Vertical, BitmapRotation.None, false),
        5 => (BitmapFlip.Vertical, BitmapRotation.Clockwise90Degrees, true),   // transpose
        6 => (BitmapFlip.None, BitmapRotation.Clockwise90Degrees, true),
        7 => (BitmapFlip.Horizontal, BitmapRotation.Clockwise90Degrees, true), // transverse
        8 => (BitmapFlip.None, BitmapRotation.Clockwise270Degrees, true),
        _ => (BitmapFlip.None, BitmapRotation.None, false),
    };

    /// <summary>The size <paramref name="width"/> × <paramref name="height"/> fits at with its long edge at most
    /// <paramref name="edge"/>, never enlarged (the web's mediaDeriver.ts; Linux's media_tiers::fit).</summary>
    public static (int Width, int Height) Fit(int width, int height, int edge)
    {
        var longest = Math.Max(width, height);
        if (longest <= edge || longest <= 0 || edge <= 0) return (Math.Max(1, width), Math.Max(1, height));
        var scale = (double)edge / longest;
        return (Math.Max(1, (int)Math.Round(width * scale)), Math.Max(1, (int)Math.Round(height * scale)));
    }

    /// <summary>The decoder's picture at <paramref name="size"/> (upright), over white, as a JPEG at <paramref name="quality"/>.</summary>
    private static async Task<byte[]> JpegAsync(BitmapDecoder decoder, (int Width, int Height) size, BitmapFlip flip, BitmapRotation rotation, bool swaps, double quality)
    {
        // WIC scales the stored picture before it flips and turns it: the scaled size is the stored orientation's
        var (sw, sh) = swaps ? (size.Height, size.Width) : (size.Width, size.Height);
        var transform = new BitmapTransform { ScaledWidth = (uint)sw, ScaledHeight = (uint)sh, Flip = flip, Rotation = rotation, InterpolationMode = BitmapInterpolationMode.Fant };
        var data = await decoder.GetPixelDataAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied, transform,
            ExifOrientationMode.IgnoreExifOrientation, ColorManagementMode.ColorManageToSRgb);
        var bgra = data.DetachPixelData();
        try
        {
            OverWhite(bgra);
            using var stream = new InMemoryRandomAccessStream();
            var options = new BitmapPropertySet { { "ImageQuality", new BitmapTypedValue((float)quality, Windows.Foundation.PropertyType.Single) } };
            var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.JpegEncoderId, stream, options);
            encoder.SetPixelData(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Ignore, (uint)size.Width, (uint)size.Height, 96, 96, bgra);
            await encoder.FlushAsync();
            var bytes = new byte[stream.Size];
            stream.Seek(0);
            await stream.ReadAsync(bytes.AsBuffer(), (uint)bytes.Length, InputStreamOptions.None);
            return bytes;
        }
        finally
        {
            Array.Clear(bgra);
        }
    }

    /// <summary>Premultiplied BGRA pixels drawn over white, in place (a JPEG has no alpha).</summary>
    public static void OverWhite(byte[] bgra)
    {
        for (var at = 0; at + 3 < bgra.Length; at += 4)
        {
            var under = 255 - bgra[at + 3];
            bgra[at] = (byte)Math.Min(255, bgra[at] + under);
            bgra[at + 1] = (byte)Math.Min(255, bgra[at + 1] + under);
            bgra[at + 2] = (byte)Math.Min(255, bgra[at + 2] + under);
            bgra[at + 3] = 255;
        }
    }
}
