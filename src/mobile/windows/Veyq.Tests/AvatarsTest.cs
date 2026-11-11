using System;
using System.Linq;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace Veyq.Tests;

/// <summary>The members' pictures as the app holds them (AvatarsHeld.cs: Linux's avatars::Held, rule for rule) and as
/// Windows decodes the web's.</summary>
[TestClass]
public sealed class AvatarsTest
{
    [TestMethod]
    public void ASetIsAskedForOnceAndAgainOnlyWhenItChanges()
    {
        var held = new Held<string>();
        var first = held.Listed("t1", ["b", "a"]);
        CollectionAssert.AreEqual(new[] { "a", "b" }, first.Ask.ToArray());
        Assert.IsTrue(held.Arrived(first.Round, "a", "A"));
        Assert.IsTrue(held.Arrived(first.Round, "b", "B"));
        // the same set again, in another order: nothing asked
        Assert.AreEqual(0, held.Listed("t1", ["a", "b"]).Ask.Count);
        // a member's picture set: the whole set asked again, the others kept meanwhile
        var second = held.Listed("t1", ["a", "b", "c"]);
        CollectionAssert.AreEqual(new[] { "a", "b", "c" }, second.Ask.ToArray());
        Assert.AreEqual(0, second.Gone.Count);
        Assert.AreEqual("A", held.Get("a"));
        // a picture removed: gone at once, its person shows the initials
        var third = held.Listed("t1", ["a", "c"]);
        CollectionAssert.AreEqual(new[] { "b" }, third.Gone.ToArray());
        Assert.IsNull(held.Get("b"));
    }

    [TestMethod]
    public void AnotherTenantDropsWhatWasHeldAndALateAnswerIsNotKept()
    {
        var held = new Held<string>();
        var first = held.Listed("t1", ["a"]);
        Assert.IsTrue(held.Arrived(first.Round, "a", "A"));
        var other = held.Listed("t2", ["a", "x"]);
        CollectionAssert.AreEqual(new[] { "a", "x" }, other.Ask.ToArray());
        CollectionAssert.AreEqual(new[] { "a" }, other.Gone.ToArray());
        Assert.IsNull(held.Get("a"), "t1's picture of a is not t2's");
        // an answer to t1's round arriving now is t1's: dropped
        Assert.IsFalse(held.Arrived(first.Round, "a", "A of t1"));
        Assert.IsNull(held.Get("a"));
        // nor is an answer for an id no longer listed, nor one that did not decode
        Assert.IsFalse(held.Arrived(other.Round, "z", "Z"));
        Assert.IsFalse(held.Arrived(other.Round, "x", null));
        Assert.IsTrue(held.Arrived(other.Round, "x", "X"));
        // and back to t1: asked anew, though its set is the one it had
        CollectionAssert.AreEqual(new[] { "a" }, held.Listed("t1", ["a"]).Ask.ToArray());
    }

    /// <summary>
    /// The web writes a member's picture as WebP. WinUI's BitmapImage decodes through the Windows Imaging Component, which
    /// has WebP only through its WebP codec (the Webp Image Extensions package, in Windows' own image): without it every
    /// person shows their initials, and only the app's log says why. This asks WIC for the same decode.
    /// </summary>
    [TestMethod]
    public async Task APictureAsTheWebWritesItDecodes()
    {
        // a 1×1 lossless WebP (Linux's test's)
        byte[] webp =
        [
            0x52, 0x49, 0x46, 0x46, 0x1a, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x4c, 0x0d, 0x00,
            0x00, 0x00, 0x2f, 0x00, 0x00, 0x00, 0x10, 0x07, 0x10, 0x11, 0x11, 0x88, 0x88, 0xfe, 0x07, 0x00,
        ];
        using var stream = new InMemoryRandomAccessStream();
        await stream.WriteAsync(webp.AsBuffer());
        stream.Seek(0);
        var decoder = await BitmapDecoder.CreateAsync(stream);
        Assert.AreEqual(BitmapDecoder.WebpDecoderId, decoder.DecoderInformation.CodecId);
        Assert.AreEqual((1u, 1u), (decoder.PixelWidth, decoder.PixelHeight));
    }
}
