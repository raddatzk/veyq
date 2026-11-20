using System;
using System.IO;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;
using Veyq.Core;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace Veyq.Tests;

/// <summary>
/// One's own profile picture as this side renders it for the core (ProfilePicture, ADR 0148 decision 2): the square a
/// pose frames, 256 × 256 RGBA over white, from a picture at a path — the core's CoverFrame scale (its base scale in a
/// frame of that edge, times the zoom) and the pose's offset, so the framed part is the one the cropper showed.
/// </summary>
[TestClass]
public sealed class ProfilePictureTest
{
    /// <summary>A 400 × 200 PNG, its left half red and its right half blue, in a temporary file the test removes.</summary>
    private static async Task<string> RedBlue()
    {
        const int w = 400, h = 200;
        var bgra = new byte[w * h * 4];
        for (var y = 0; y < h; y++)
        for (var x = 0; x < w; x++)
        {
            var at = (y * w + x) * 4;
            if (x < w / 2) bgra[at + 2] = 255; else bgra[at] = 255;
            bgra[at + 3] = 255;
        }
        using var stream = new InMemoryRandomAccessStream();
        var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId, stream);
        encoder.SetPixelData(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied, w, h, 96, 96, bgra);
        await encoder.FlushAsync();
        var png = new byte[stream.Size];
        stream.Seek(0);
        await stream.ReadAsync(png.AsBuffer(), (uint)png.Length, InputStreamOptions.None);
        var path = Path.Combine(Path.GetTempPath(), $"veyq-profile-test-{Guid.NewGuid():N}.png");
        await File.WriteAllBytesAsync(path, png);
        return path;
    }

    private static (byte R, byte G, byte B, byte A) At(byte[] rgba, int x, int y, int edge = 256)
    {
        var i = (y * edge + x) * 4;
        return (rgba[i], rgba[i + 1], rgba[i + 2], rgba[i + 3]);
    }

    [TestMethod]
    public async Task TheCentredSquareShowsTheMiddleOfAWidePictureRedLeftBlueRight()
    {
        var path = await RedBlue();
        try
        {
            // a 400 × 200 picture in a 256 frame at zoom 1 is drawn 512 × 256, centred: its left edge 128 px out
            var rgba = await ProfilePicture.Square(path, new Pose { zoom = 1, x = -128, y = 0 }, 256);
            Assert.AreEqual(256 * 256 * 4, rgba.Length);
            Assert.AreEqual(((byte)255, (byte)0, (byte)0, (byte)255), At(rgba, 10, 128), "the left of the square is the picture's red half");
            Assert.AreEqual(((byte)0, (byte)0, (byte)255, (byte)255), At(rgba, 245, 128), "the right of the square is the picture's blue half");

            // panned to its left edge, the square is red alone
            var left = await ProfilePicture.Square(path, new Pose { zoom = 1, x = 0, y = 0 }, 256);
            Assert.AreEqual(((byte)255, (byte)0, (byte)0, (byte)255), At(left, 250, 250));
        }
        finally
        {
            File.Delete(path);
        }
    }

    [TestMethod]
    public async Task TheHostRequestIsAnsweredWithItsPixelsAndAFileThatIsNoPictureFails()
    {
        var path = await RedBlue();
        try
        {
            var (reply, payload) = await ProfilePicture.Answer(new HostRequest.PictureSquare { path = path, pose = new Pose { zoom = 2, x = -300, y = -100 }, edge = 256 })!;
            Assert.AreEqual(new HostReply.Pixels { width = 256, height = 256 }, reply);
            Assert.AreEqual(256 * 256 * 4, payload!.Length);
        }
        finally
        {
            File.Delete(path);
        }
        var notAPicture = Path.Combine(Path.GetTempPath(), $"veyq-profile-test-{Guid.NewGuid():N}.png");
        await File.WriteAllTextAsync(notAPicture, "no picture");
        try
        {
            var (failed, none) = await ProfilePicture.Answer(new HostRequest.PictureSquare { path = notAPicture, pose = new Pose { zoom = 1, x = 0, y = 0 }, edge = 256 })!;
            Assert.IsInstanceOfType<HostReply.Failed>(failed);
            Assert.IsNull(none);
        }
        finally
        {
            File.Delete(notAPicture);
        }
        Assert.IsNull(ProfilePicture.Answer(new HostRequest.StoreRead { name = "a" }), "the store's request is no picture");
    }

    [TestMethod]
    public void TheWindowIsTakenOverWhiteAndWhatLiesOutsideThePictureIsWhite()
    {
        // premultiplied BGRA, 2 × 1: half-transparent black, opaque green
        var rgba = ProfilePicture.Window([0, 0, 0, 128, 0, 255, 0, 255], 2, 1, 0, 0, 3);
        Assert.AreEqual(((byte)127, (byte)127, (byte)127, (byte)255), At(rgba, 0, 0, 3), "white shows through half");
        Assert.AreEqual(((byte)0, (byte)255, (byte)0, (byte)255), At(rgba, 1, 0, 3));
        Assert.AreEqual(((byte)255, (byte)255, (byte)255, (byte)255), At(rgba, 2, 0, 3), "past the picture's right edge");
        Assert.AreEqual(((byte)255, (byte)255, (byte)255, (byte)255), At(rgba, 0, 2, 3), "below it");
    }
}
