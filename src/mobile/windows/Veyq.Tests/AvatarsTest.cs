using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
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
        var first = held.Listed("t1", ["b", "a"], 0);
        CollectionAssert.AreEqual(new[] { "a", "b" }, first.Ask.ToArray());
        Assert.IsTrue(held.Arrived(first.Round, "a", "A"));
        Assert.IsTrue(held.Arrived(first.Round, "b", "B"));
        // the same set again, in another order: nothing asked
        Assert.AreEqual(0, held.Listed("t1", ["a", "b"], 0).Ask.Count);
        // a member's picture set: the whole set asked again, the others kept meanwhile
        var second = held.Listed("t1", ["a", "b", "c"], 0);
        CollectionAssert.AreEqual(new[] { "a", "b", "c" }, second.Ask.ToArray());
        Assert.AreEqual(0, second.Gone.Count);
        Assert.AreEqual("A", held.Get("a"));
        // a picture removed: gone at once, its person shows the initials
        var third = held.Listed("t1", ["a", "c"], 0);
        CollectionAssert.AreEqual(new[] { "b" }, third.Gone.ToArray());
        Assert.IsNull(held.Get("b"));
    }

    /// <summary>A picture replaced under the same id (the core read the people anew): the ids stay, the round moves, and
    /// the whole set is asked again while what is held stays shown; the same round again asks nothing. Bitten by a Listed
    /// that ignores the round: red.</summary>
    [TestMethod]
    public void APictureReplacedUnderItsIdIsAskedForAgainWhenTheRoundMoves()
    {
        var held = new Held<string>();
        var first = held.Listed("t1", ["a", "b"], 3);
        Assert.IsTrue(held.Arrived(first.Round, "a", "A"));
        var again = held.Listed("t1", ["a", "b"], 4);
        CollectionAssert.AreEqual(new[] { "a", "b" }, again.Ask.ToArray());
        Assert.AreEqual(0, again.Gone.Count);
        Assert.AreEqual("A", held.Get("a"), "shown until the new one arrives");
        Assert.IsTrue(held.Arrived(again.Round, "a", "A2"));
        Assert.AreEqual("A2", held.Get("a"));
        Assert.AreEqual(0, held.Listed("t1", ["b", "a"], 4).Ask.Count);
    }

    [TestMethod]
    public void AnotherTenantDropsWhatWasHeldAndALateAnswerIsNotKept()
    {
        var held = new Held<string>();
        var first = held.Listed("t1", ["a"], 0);
        Assert.IsTrue(held.Arrived(first.Round, "a", "A"));
        var other = held.Listed("t2", ["a", "x"], 0);
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
        CollectionAssert.AreEqual(new[] { "a" }, held.Listed("t1", ["a"], 0).Ask.ToArray());
    }

    /// <summary>
    /// The web writes a member's picture as WebP. WinUI's BitmapImage decodes through the Windows Imaging Component, which
    /// has WebP only through the Store's WebP Image Extension (in Windows' own image, but removable): without it every
    /// person showed their initials (measured 2026-11-11, this test's first form red with the extension removed). A WebP WIC
    /// refuses now goes to the core, whose PNG WIC always decodes (PictureDecode.cs). Here WIC as on a Windows without the
    /// extension: it refuses every WebP as it then does, the core's answer is the PNG the DLL gives for this picture
    /// (CoreTest holds the DLL to the same bytes), and the picture decodes. Bitten by a Decode without the fallback: red.
    /// </summary>
    [TestMethod]
    public async Task APictureAsTheWebWritesItDecodesWithoutTheWebpExtension()
    {
        var asked = new List<byte[]>();
        var (width, height) = await PictureDecode.Decode(Webp, WicWithoutWebp, webp =>
        {
            asked.Add(webp);
            return Task.FromResult<byte[]?>(OneTransparentPixelPng);
        });
        Assert.AreEqual((1u, 1u), (width, height));
        Assert.HasCount(1, asked);
        CollectionAssert.AreEqual(Webp, asked[0], "the core got the WebP");
    }

    /// <summary>With the extension WIC decodes the WebP itself and the core is not asked; without it the core is. Either
    /// way the picture decodes on this host, whatever it has installed.</summary>
    [TestMethod]
    public async Task APictureAsTheWebWritesItDecodesOnThisHost()
    {
        var (width, height) = await PictureDecode.Decode(Webp, Wic, _ => Task.FromResult<byte[]?>(OneTransparentPixelPng));
        Assert.AreEqual((1u, 1u), (width, height));
    }

    /// <summary>What is no WebP and fails throws as WIC threw, the core never asked; a WebP the core refuses too throws.</summary>
    [TestMethod]
    public async Task WhatNeitherDecodesThrowsAsWicThrew()
    {
        var asked = 0;
        Func<byte[], Task<byte[]?>> core = _ => { asked++; return Task.FromResult<byte[]?>(null); };
        await Assert.ThrowsAsync<Exception>(() => PictureDecode.Decode("GIF89a broken"u8.ToArray(), Wic, core));
        Assert.AreEqual(0, asked, "the core was asked for no WebP");
        await Assert.ThrowsAsync<COMException>(() => PictureDecode.Decode(Webp, WicWithoutWebp, core));
        Assert.AreEqual(1, asked);
    }

    // a 1×1 lossless WebP (Linux's test's and the core's WebpPictureTest's): one transparent black pixel
    private static readonly byte[] Webp =
    [
        0x52, 0x49, 0x46, 0x46, 0x1a, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x4c, 0x0d, 0x00,
        0x00, 0x00, 0x2f, 0x00, 0x00, 0x00, 0x10, 0x07, 0x10, 0x11, 0x11, 0x88, 0x88, 0xfe, 0x07, 0x00,
    ];

    /// <summary>The core's PNG of <see cref="Webp"/> (core's RgbaPngTest and WebpPictureTest, Veyq.Bridge.Tests' CoreTest).</summary>
    internal static readonly byte[] OneTransparentPixelPng = Convert.FromHexString(
        "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c48900000010494441547801010500faff000000000000050001647895380000000049454e44ae426082");

    private static async Task<(uint, uint)> Wic(byte[] bytes)
    {
        using var stream = new InMemoryRandomAccessStream();
        await stream.WriteAsync(bytes.AsBuffer());
        stream.Seek(0);
        var decoder = await BitmapDecoder.CreateAsync(stream);
        // the pixels too, as BitmapImage reads them: a decoder made is not a picture drawn
        var pixels = await decoder.GetPixelDataAsync();
        Assert.HasCount((int)(decoder.PixelWidth * decoder.PixelHeight * 4), pixels.DetachPixelData());
        return (decoder.PixelWidth, decoder.PixelHeight);
    }

    /// <summary>WIC as on a Windows without the WebP Image Extension: a WebP is refused as WIC then refuses it, with
    /// WINCODEC_ERR_COMPONENTNOTFOUND; every other format as WIC decodes it.</summary>
    private static Task<(uint, uint)> WicWithoutWebp(byte[] bytes) =>
        PictureDecode.IsWebp(bytes) ? throw new COMException("no WebP codec", unchecked((int)0x88982F50)) : Wic(bytes);
}
