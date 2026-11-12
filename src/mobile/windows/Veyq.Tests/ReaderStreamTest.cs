using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Tasks;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Windows.Storage.Streams;

namespace Veyq.Tests;

/// <summary>A medium's stream over a played reader (the Depot CR's Phase 4; Linux's reader_stream tests, case for case), as
/// the app hands it to Media Foundation: through AsRandomAccessStream, the WinRT stream MediaSource.CreateFromStream reads —
/// small reads served from one window, a seek from the start, from here and from the end, a range across a frame's edge, a
/// short read at the end, the reader let go when the stream is.</summary>
[TestClass]
public sealed class ReaderStreamTest
{
    /// <summary>Three frames and a bit, each byte telling its frame and offset apart.</summary>
    private static byte[] Medium() => Enumerable.Range(0, 3 * ReaderStream.Window + 777).Select(i => (byte)((i * 7 + i / ReaderStream.Window) % 251)).ToArray();

    private sealed class Played(byte[] bytes)
    {
        public readonly List<(long At, int Length)> Asked = [];
        public bool Closed;

        public ReaderStream Stream() => new(bytes.Length, (at, length) =>
        {
            lock (Asked) Asked.Add((at, length));
            var from = (int)Math.Min(at, bytes.Length);
            return Task.FromResult(bytes[from..Math.Min(bytes.Length, from + length)]);
        }, () => Closed = true);
    }

    private static async Task<byte[]> Read(IRandomAccessStream s, int n)
    {
        var buffer = await s.ReadAsync(new Windows.Storage.Streams.Buffer((uint)n), (uint)n, InputStreamOptions.None);
        return buffer.ToArray();
    }

    [TestMethod]
    public async Task SmallReadsAreServedFromOneWindowAndASeekReadsWhereItLands()
    {
        var bytes = Medium();
        var played = new Played(bytes);
        using var s = played.Stream().AsRandomAccessStream();
        Assert.AreEqual((ulong)bytes.Length, s.Size);
        Assert.IsTrue(s.CanRead && !s.CanWrite);
        CollectionAssert.AreEqual(bytes[..4096], await Read(s, 4096));
        CollectionAssert.AreEqual(bytes[4096..8192], await Read(s, 4096));
        Assert.AreEqual(1, played.Asked.Count, "two small reads asked the reader twice");
        Assert.AreEqual((0L, ReaderStream.Window), played.Asked[0]);
        const int w = ReaderStream.Window;
        s.Seek((ulong)(2 * w + 5));
        Assert.AreEqual((ulong)(2 * w + 5), s.Position);
        CollectionAssert.AreEqual(bytes[(2 * w + 5)..(2 * w + 15)], await Read(s, 10));
        s.Seek((ulong)(2 * w - 5));
        CollectionAssert.AreEqual(bytes[(2 * w - 5)..(2 * w + 5)], await Read(s, 10), "a range across a frame's edge");
        var net = played.Stream();
        Assert.AreEqual(bytes.Length - 100L, net.Seek(-100, SeekOrigin.End));
        Assert.AreEqual(bytes.Length - 120L, net.Seek(-20, SeekOrigin.Current));
        Assert.ThrowsExactly<IOException>(() => net.Seek(-1, SeekOrigin.Begin));
    }

    /// <summary>A player may seek while a read awaits the core (Media Foundation reads and seeks from threads of its own):
    /// the read's end must not move the position the seek set.</summary>
    [TestMethod]
    public async Task ASeekWhileAReadAwaitsTheReaderStaysWhereItPutThePosition()
    {
        var bytes = Medium();
        var held = new TaskCompletionSource<byte[]>();
        using var stream = new ReaderStream(bytes.Length, (_, _) => held.Task, () => { });
        var reading = stream.ReadAsync(new byte[4096]).AsTask();
        Assert.AreEqual(2L * ReaderStream.Window, stream.Seek(2L * ReaderStream.Window, SeekOrigin.Begin));
        held.SetResult(bytes[..ReaderStream.Window]);
        Assert.AreEqual(4096, await reading);
        Assert.AreEqual(2L * ReaderStream.Window, stream.Position, "the read moved the position the seek set");
    }

    [TestMethod]
    public async Task AReadAtTheEndIsShortAndPastItIsEmpty()
    {
        var bytes = Medium();
        using var s = new Played(bytes).Stream().AsRandomAccessStream();
        s.Seek((ulong)(bytes.Length - 50));
        CollectionAssert.AreEqual(bytes[^50..], await Read(s, 4096), "the short read at the end");
        Assert.AreEqual(0, (await Read(s, 4096)).Length);
        s.Seek((ulong)bytes.Length + 10);
        Assert.AreEqual(0, (await Read(s, 4096)).Length, "past the end");
    }

    [TestMethod]
    public async Task TheWholeMediumReadInUnevenPiecesIsItsBytesAndDisposingLetsTheReaderGo()
    {
        var bytes = Medium();
        var played = new Played(bytes);
        var stream = played.Stream();
        var s = stream.AsRandomAccessStream();
        var all = new List<byte>();
        int[] sizes = [1, 4095, 300_000, 7, 65_536, ReaderStream.Window + 3];
        for (var i = 0; ; i++)
        {
            var got = await Read(s, sizes[i % sizes.Length]);
            if (got.Length == 0) break;
            all.AddRange(got);
        }
        CollectionAssert.AreEqual(bytes, all.ToArray());
        Assert.IsTrue(played.Asked.All(a => a.Length >= ReaderStream.Window), "a range asked below the window");
        s.Dispose();
        Assert.IsTrue(played.Closed, "the reader was not let go");
        await Assert.ThrowsExactlyAsync<ObjectDisposedException>(async () => _ = await stream.ReadAsync(new byte[4]));
    }
}
