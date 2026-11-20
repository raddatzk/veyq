using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;
using Veyq.Core;

namespace Veyq.Tests;

/// <summary>
/// An Emergency Kit's pages drawn by Windows' own renderer for the core (KitDrawing; the kit CR's D6), in an unpackaged
/// process: the printed fixture opened from its path, its first page drawn 2400 px wide as luminance, a path that does not
/// open and a request that is no drawing. This project does not link the core (Veyq.Bridge.Tests does, without the
/// Windows API), so the hand-off of these pixels to the core is the pass's (a picked kit read through to the form).
/// </summary>
[TestClass]
public sealed class KitDrawingTest
{
    private static string Fixture(string name) =>
        Path.Combine(Environment.GetEnvironmentVariable("VEYQ_KIT_FIXTURES") ?? throw new AssertFailedException("run.py sets VEYQ_KIT_FIXTURES"), name);

    [TestMethod]
    public async Task ThePrintedKitsFirstPageIsDrawnAsLuminanceAtTheWidthAsked()
    {
        var path = Fixture("kit-printed.pdf");
        Assert.IsGreaterThanOrEqualTo(1u, await KitDrawing.PagesAsync(path));
        var page = await KitDrawing.PageAsync(path, 1, 2400);
        Assert.AreEqual(2400, page.Width);
        Assert.IsGreaterThan(3000, page.Height, "an A4 page 2400 px wide is about 3394 px high");
        Assert.AreEqual(page.Width * page.Height, page.Bytes.Length);
        Assert.IsTrue(page.Bytes.Any(b => b < 64) && page.Bytes.Any(b => b > 192), "the page drew nothing");

        var (reply, payload) = await KitDrawing.Answer(new HostRequest.PdfPage { path = path, page = 1, width = 2000 })!;
        Assert.AreEqual(new HostReply.Pixels { width = 2000, height = ((HostReply.Pixels)reply).height }, reply);
        Assert.AreEqual(2000 * ((HostReply.Pixels)reply).height, payload!.Length);
    }

    [TestMethod]
    public async Task APathThatDoesNotOpenOrAPageItLacksIsFailedAndTheStoreIsNoDrawing()
    {
        var (missing, none) = await KitDrawing.Answer(new HostRequest.PdfPages { path = Fixture("no-such-kit.pdf") })!;
        Assert.IsInstanceOfType<HostReply.Failed>(missing);
        Assert.IsNull(none);
        var (beyond, _) = await KitDrawing.Answer(new HostRequest.PdfPage { path = Fixture("kit-printed.pdf"), page = 9, width = 2400 })!;
        Assert.IsInstanceOfType<HostReply.Failed>(beyond);
        Assert.IsNull(KitDrawing.Answer(new HostRequest.StoreRead { name = "a" }), "the store's request is no drawing");
    }

    [TestMethod]
    public void LuminanceIsTakenOverWhite()
    {
        // premultiplied BGRA: black, white, transparent (white shows through), pure red, pure blue
        var l = KitDrawing.Luminance([0, 0, 0, 255, 255, 255, 255, 255, 0, 0, 0, 0, 0, 0, 255, 255, 255, 0, 0, 255], 5, 1);
        CollectionAssert.AreEqual(new byte[] { 0, 255, 255, 76, 29 }, l.Bytes);
    }
}
