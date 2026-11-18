using System;
using System.Collections.Concurrent;
using System.Threading.Tasks;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;

namespace Veyq.Bridge.Tests;

/// <summary>
/// The wrapper over the real veyq.dll (Windows only: the DLL is a mingw x64 one), as `bridge/smoke/smoke.c` runs the
/// library from C: the core starts once, a watch gets the way in's gate, a call is answered, a WebP is decoded, the host's store is asked,
/// a call before a tenant is open is refused, an ended watch gets no more updates, and the core shuts down. It runs once
/// per process — veyq_init starts the core once.
/// </summary>
[TestClass]
public sealed class CoreTest
{
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(30);

    [TestMethod]
    [OSCondition(OperatingSystems.Windows)]
    public async Task TheCoreAnswersThroughTheWrapper()
    {
        var store = DeviceStore.InMemory();
        Assert.AreEqual(Start.Started, Core.Start(new Config { device = "Veyq.Bridge.Tests", window = 0, prompt = "", offlineRoot = null, language = "en" }, store));
        Assert.AreEqual(Start.AlreadyStarted, Core.Start(new Config { device = "Veyq.Bridge.Tests", window = 0, prompt = "", offlineRoot = null, language = "en" }, DeviceStore.InMemory()));
        try
        {
            var gates = new BlockingCollection<Update>();
            var watch = Core.Watch(new Watched.Session(), gates.Add, e => Assert.Fail(e.ToString()));
            Assert.IsNotNull(watch, "the Session watch was refused");
            Assert.IsInstanceOfType<Gate.Starting>(GateOf(Next(gates)));

            var started = await Core.CallAsync(new Call.SessionStart()).WaitAsync(Wait);
            Assert.AreEqual(new Answer.Done(), started.Answer);
            Assert.IsNull(started.Payload, "a payload beside an answer that is no Bytes");
            while (GateOf(Next(gates)) is not Gate.SignIn) { }
            Assert.IsGreaterThan(0, store.Answered, "the core asked the host's store");

            // a WebP the platform cannot decode, decoded by the DLL's libwebp: the PNG Veyq.Tests' AvatarsTest feeds WIC
            var (decoded, png) = await Core.CallAsync(new Call.PictureDecode(), Webp).WaitAsync(Wait);
            Assert.AreEqual(new Answer.Bytes { mediaType = "image/png" }, decoded);
            Assert.AreEqual(OneTransparentPixelPng, Convert.ToHexStringLower(png!));
            var (notOne, none) = await Core.CallAsync(new Call.PictureDecode(), "GIF89a"u8.ToArray()).WaitAsync(Wait);
            Assert.AreEqual(new Answer.Refused { why = Why.NotAPicture }, notOne);
            Assert.IsNull(none);

            var (refused, _) = await Core.CallAsync(new Call.SearchQuery { query = "x" }).WaitAsync(Wait);
            Assert.AreEqual(new Answer.Refused { why = Why.NoTenantOpen }, refused);

            watch.Dispose();
            var before = gates.Count;
            // a sign-in nobody answers moves the gate, which no ended watch may report
            await Core.CallAsync(new Call.SessionSignIn { server = "http://127.0.0.1:9", email = "a@b.c", password = "x", secretKey = "x" }).WaitAsync(Wait);
            await Task.Delay(300);
            Assert.AreEqual(before, gates.Count, "an ended watch got an update");
        }
        finally
        {
            Core.Shutdown();
        }
    }

    // a 1×1 lossless WebP (the core's WebpPictureTest's) and the PNG the core makes of it (its RgbaPngTest's)
    private static readonly byte[] Webp =
    [
        0x52, 0x49, 0x46, 0x46, 0x1a, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x4c, 0x0d, 0x00,
        0x00, 0x00, 0x2f, 0x00, 0x00, 0x00, 0x10, 0x07, 0x10, 0x11, 0x11, 0x88, 0x88, 0xfe, 0x07, 0x00,
    ];

    private const string OneTransparentPixelPng =
        "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c48900000010494441547801010500faff000000000000050001647895380000000049454e44ae426082";

    private static Update Next(BlockingCollection<Update> updates) =>
        updates.TryTake(out var u, Wait) ? u : throw new AssertFailedException("no update arrived");

    private static Gate GateOf(Update u) => ((Update.Session)u).gate;
}
