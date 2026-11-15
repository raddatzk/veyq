using System;
using System.IO;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;
using Windows.Graphics.Imaging;
using Windows.Media.Editing;
using Windows.Media.MediaProperties;
using Windows.Media.Transcoding;
using Windows.Storage;
using Windows.Storage.Streams;

namespace Veyq.Tests;

/// <summary>
/// A picked medium's tiers made by Windows for the core (MediaTiers; ADR 0123's Consequences, the Windows app; Linux's
/// media_tiers tests, case for case): a photo turned by its EXIF and fitted to the core's edges, never enlarged; a video's
/// poster at the core's moment and its length; a file that does not decode answered with no tiers. Every file is made here
/// in the temporary folder and removed after.
/// </summary>
[TestClass]
public sealed class MediaTiersTest
{
    private static readonly MediaTiers.Spec Spec = new(2048, 0.85, 512, 0.8, 100);

    private static string Temp(string name) => Path.Combine(Path.GetTempPath(), $"veyq-tiers-{Environment.ProcessId}-{name}");

    /// <summary>A JPEG of <paramref name="w"/> × <paramref name="h"/>, its left half red and its right half blue, stored
    /// with EXIF orientation <paramref name="orientation"/> where one is given.</summary>
    private static async Task<string> Halves(string name, int w, int h, ushort? orientation)
    {
        var bgra = new byte[w * h * 4];
        for (var y = 0; y < h; y++)
        for (var x = 0; x < w; x++)
        {
            var at = (y * w + x) * 4;
            var left = x < w / 2;
            (bgra[at], bgra[at + 1], bgra[at + 2], bgra[at + 3]) = left ? ((byte)0, (byte)0, (byte)255, (byte)255) : ((byte)255, (byte)0, (byte)0, (byte)255);
        }
        using var stream = new InMemoryRandomAccessStream();
        var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.JpegEncoderId, stream);
        encoder.SetPixelData(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Ignore, (uint)w, (uint)h, 96, 96, bgra);
        if (orientation is { } o)
        {
            await encoder.BitmapProperties.SetPropertiesAsync([new("System.Photo.Orientation", new BitmapTypedValue(o, Windows.Foundation.PropertyType.UInt16))]);
        }
        await encoder.FlushAsync();
        var bytes = new byte[stream.Size];
        stream.Seek(0);
        await stream.ReadAsync(bytes.AsBuffer(), (uint)bytes.Length, InputStreamOptions.None);
        var path = Temp(name);
        await File.WriteAllBytesAsync(path, bytes);
        return path;
    }

    private static async Task<(int W, int H, byte[] Bgra)> Decoded(byte[] jpeg)
    {
        using var stream = new InMemoryRandomAccessStream();
        await stream.WriteAsync(jpeg.AsBuffer());
        stream.Seek(0);
        var decoder = await BitmapDecoder.CreateAsync(stream);
        var data = await decoder.GetPixelDataAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Ignore, new BitmapTransform(),
            ExifOrientationMode.IgnoreExifOrientation, ColorManagementMode.DoNotColorManage);
        return ((int)decoder.PixelWidth, (int)decoder.PixelHeight, data.DetachPixelData());
    }

    private static (int R, int G, int B) At((int W, int H, byte[] Bgra) p, int x, int y)
    {
        var at = (y * p.W + x) * 4;
        return (p.Bgra[at + 2], p.Bgra[at + 1], p.Bgra[at]);
    }

    private static bool Reddish((int R, int G, int B) c) => c.R > 180 && c.B < 90;
    private static bool Bluish((int R, int G, int B) c) => c.B > 180 && c.R < 90;

    [TestMethod]
    public void ASizeFitsItsLongEdgeAndIsNeverEnlarged()
    {
        Assert.AreEqual((2048, 683), MediaTiers.Fit(3000, 1000, 2048));
        Assert.AreEqual((683, 2048), MediaTiers.Fit(1000, 3000, 2048));
        Assert.AreEqual((800, 600), MediaTiers.Fit(800, 600, 2048), "never enlarged");
        Assert.AreEqual((512, 384), MediaTiers.Fit(800, 600, 512));
    }

    [TestMethod]
    public async Task APhotoIsTurnedByItsExifAndFittedToTheCoresEdges()
    {
        // stored 3000 × 1000, left red: orientation 6 turns it a quarter clockwise — upright 1000 × 3000, red on top
        var turned = await Halves("turned.jpg", 3000, 1000, 6);
        var plain = await Halves("plain.jpg", 800, 600, null);
        try
        {
            var t = await MediaTiers.TiersAsync(turned, "image/jpeg", Spec);
            Assert.AreEqual((1000, 3000), (t.Width, t.Height), "the upright size");
            var preview = await Decoded(t.Preview);
            Assert.AreEqual((683, 2048), (preview.W, preview.H), "the preview at the core's long edge");
            Assert.IsTrue(Reddish(At(preview, 340, 300)) && Bluish(At(preview, 340, 1750)), $"turned: top {At(preview, 340, 300)}, bottom {At(preview, 340, 1750)}");
            var thumbnail = await Decoded(t.Thumbnail);
            Assert.AreEqual((171, 512), (thumbnail.W, thumbnail.H), "the thumbnail fitted from the preview");

            var p = await MediaTiers.TiersAsync(plain, "image/jpeg", Spec);
            Assert.AreEqual((800, 600), (p.Width, p.Height));
            var unturned = await Decoded(p.Preview);
            Assert.AreEqual((800, 600), (unturned.W, unturned.H), "a small photo is not enlarged");
            Assert.IsTrue(Reddish(At(unturned, 100, 300)) && Bluish(At(unturned, 700, 300)), "unturned without EXIF");
        }
        finally
        {
            File.Delete(turned);
            File.Delete(plain);
        }
    }

    /// <summary>A video's poster is its frame at the core's moment: a clip whose first second is red and second blue, asked
    /// at 1.5 s, is blue; its length is said in milliseconds. The clip is made by Windows.Media.Editing itself.</summary>
    [TestMethod]
    public async Task AVideosPosterIsItsFrameAtTheCoresMoment()
    {
        var path = Temp("clip.mp4");
        var composition = new MediaComposition();
        composition.Clips.Add(MediaClip.CreateFromColor(Windows.UI.Color.FromArgb(255, 255, 0, 0), TimeSpan.FromSeconds(1)));
        composition.Clips.Add(MediaClip.CreateFromColor(Windows.UI.Color.FromArgb(255, 0, 0, 255), TimeSpan.FromSeconds(1)));
        var folder = await StorageFolder.GetFolderFromPathAsync(Path.GetTempPath());
        var file = await folder.CreateFileAsync(Path.GetFileName(path), CreationCollisionOption.ReplaceExisting);
        try
        {
            var rendered = await composition.RenderToFileAsync(file, MediaTrimmingPreference.Precise, MediaEncodingProfile.CreateMp4(VideoEncodingQuality.Qvga));
            Assert.AreEqual(TranscodeFailureReason.None, rendered, "the clip was made");
            var ms = await MediaTiers.DurationMsAsync(path);
            Assert.IsNotNull(ms, "its length");
            Assert.IsTrue(ms is >= 1900 and <= 2100, $"{ms} ms");
            var blue = await MediaTiers.TiersAsync(path, "video/mp4", Spec with { PosterAtMs = 1500 });
            Assert.AreEqual((320, 240), (blue.Width, blue.Height));
            var poster = await Decoded(blue.Preview);
            Assert.IsTrue(Bluish(At(poster, 160, 120)), $"the frame at 1.5 s: {At(poster, 160, 120)}");
            var red = await MediaTiers.TiersAsync(path, "video/mp4", Spec);
            Assert.IsTrue(Reddish(At(await Decoded(red.Thumbnail), 160, 120)), "the frame at 0.1 s");
        }
        finally
        {
            File.Delete(path);
        }
    }

    /// <summary>What the platform does not decode has no tiers, so the original goes up alone; the reply packs the preview,
    /// then the thumbnail.</summary>
    [TestMethod]
    public async Task AFileThatDoesNotDecodeHasNoTiersAndADecodedOnePacksThePreviewFirst()
    {
        var junk = Temp("junk.jpg");
        await File.WriteAllBytesAsync(junk, "not a picture at all"u8.ToArray());
        var photo = await Halves("packed.jpg", 1200, 900, null);
        try
        {
            var (none, nothing) = await MediaTiers.Answer(new HostRequest.MediaTiers { path = junk, mediaType = "image/jpeg", previewEdge = 2048, previewQuality = 0.85, thumbnailEdge = 512, thumbnailQuality = 0.8, posterAtMs = 0 })!;
            Assert.AreEqual(0, ((HostReply.Tiers)none).previewSize);
            Assert.IsNull(((HostReply.Tiers)none).width, "no size for what does not decode");
            Assert.IsNull(nothing);
            var (videoNone, _) = await MediaTiers.Answer(new HostRequest.MediaTiers { path = junk, mediaType = "video/mp4", previewEdge = 2048, previewQuality = 0.85, thumbnailEdge = 512, thumbnailQuality = 0.8, posterAtMs = 0 })!;
            Assert.AreEqual(0, ((HostReply.Tiers)videoNone).previewSize);
            Assert.IsNull(await MediaTiers.DurationMsAsync(junk));

            var (reply, payload) = await MediaTiers.Answer(new HostRequest.MediaTiers { path = photo, mediaType = "image/jpeg", previewEdge = 2048, previewQuality = 0.85, thumbnailEdge = 512, thumbnailQuality = 0.8, posterAtMs = 0 })!;
            var tiers = (HostReply.Tiers)reply;
            Assert.AreEqual((1200, 900), (tiers.width, tiers.height));
            var preview = await Decoded(payload![..tiers.previewSize]);
            var thumbnail = await Decoded(payload[tiers.previewSize..]);
            Assert.AreEqual((1200, 900), (preview.W, preview.H), "the preview first");
            Assert.AreEqual((512, 384), (thumbnail.W, thumbnail.H), "then the thumbnail");
            Assert.IsNull(MediaTiers.Answer(new HostRequest.StoreRead { name = "a" }), "the store's request is no medium's");
        }
        finally
        {
            File.Delete(junk);
            File.Delete(photo);
        }
    }

    [TestMethod]
    public void EachExifOrientationIsAFlipThenAClockwiseTurn()
    {
        Assert.AreEqual((BitmapFlip.None, BitmapRotation.None, false), MediaTiers.Transform(1));
        Assert.AreEqual((BitmapFlip.None, BitmapRotation.Clockwise90Degrees, true), MediaTiers.Transform(6));
        Assert.AreEqual((BitmapFlip.None, BitmapRotation.Clockwise270Degrees, true), MediaTiers.Transform(8));
        Assert.AreEqual((BitmapFlip.None, BitmapRotation.Clockwise180Degrees, false), MediaTiers.Transform(3));
        Assert.AreEqual((BitmapFlip.Vertical, BitmapRotation.Clockwise90Degrees, true), MediaTiers.Transform(5), "transpose");
        Assert.AreEqual((BitmapFlip.Horizontal, BitmapRotation.Clockwise90Degrees, true), MediaTiers.Transform(7), "transverse");
    }
}
