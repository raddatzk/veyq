// A medium's plaintext as a seekable stream (the Depot CR's D7; ADR 0130 decision 2; Linux's reader_stream.rs): the
// MediaPlayerElement reads it through MediaSource.CreateFromStream over AsRandomAccessStream (measured in Phase 4) in
// ranges from Media Foundation's own threads, and each range comes from the core's reader (Reader.Read), only the frames it
// needs decrypted there. Nothing of the medium is written anywhere (ADR 0047); this side holds one window of at most
// Window bytes it read last.
//
// A read awaits the core's answer, which arrives on a core thread, without the UI thread: nothing here needs the
// dispatcher (ConfigureAwait(false) throughout), so the UI never waits on a range and a range never waits on the UI.
using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace Veyq;

internal sealed class ReaderStream : Stream
{
    /// <summary>The bytes asked of the reader at once at least (one of the core's frames): a player's small reads are
    /// served from them.</summary>
    public const int Window = 256 * 1024;

    private readonly Func<long, int, Task<byte[]>> _read;
    private readonly Action _close;
    private readonly long _size;
    private readonly SemaphoreSlim _one = new(1, 1);
    private long _position;
    private byte[] _window = [];
    private long _windowAt;
    private bool _closed;

    /// <param name="size">The medium's length.</param>
    /// <param name="read">Up to the length asked from a position — fewer only at the end, none from it on.</param>
    /// <param name="close">Lets the reader go once the stream is disposed.</param>
    public ReaderStream(long size, Func<long, int, Task<byte[]>> read, Action close)
    {
        _size = size;
        _read = read;
        _close = close;
    }

    public override bool CanRead => !_closed;
    public override bool CanSeek => !_closed;
    public override bool CanWrite => false;
    public override long Length => _size;

    public override long Position
    {
        get => Interlocked.Read(ref _position);
        set => Seek(value, SeekOrigin.Begin);
    }

    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
    {
        await _one.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ObjectDisposedException.ThrowIf(_closed, this);
            var at = Interlocked.Read(ref _position);
            var n = (int)Math.Min(buffer.Length, Math.Max(0, _size - at));
            if (n == 0) return 0;
            var (window, windowAt) = (_window, _windowAt);
            if (!(at >= windowAt && at + n <= windowAt + window.Length))
            {
                window = await _read(at, Math.Max(n, Window)).ConfigureAwait(false);
                windowAt = at;
                if (window.Length < n) throw new IOException("the reader ended early");
                // disposed meanwhile: what came is let go with the rest
                ObjectDisposedException.ThrowIf(_closed, this);
                (_window, _windowAt) = (window, windowAt);
            }
            window.AsMemory((int)(at - windowAt), n).CopyTo(buffer);
            // a seek while this read awaited the core moved the position: it stays where the seek put it
            Interlocked.CompareExchange(ref _position, at + n, at);
            return n;
        }
        finally
        {
            _one.Release();
        }
    }

    public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
        ReadAsync(buffer.AsMemory(offset, count), cancellationToken).AsTask();

    /// <summary>A read that blocks its caller on the core's answer: only ever off the UI thread.</summary>
    public override int Read(byte[] buffer, int offset, int count) =>
        ReadAsync(buffer.AsMemory(offset, count)).AsTask().GetAwaiter().GetResult();

    public override long Seek(long offset, SeekOrigin origin)
    {
        ObjectDisposedException.ThrowIf(_closed, this);
        var to = origin switch
        {
            SeekOrigin.Current => Interlocked.Read(ref _position) + offset,
            SeekOrigin.End => _size + offset,
            _ => offset,
        };
        if (to < 0) throw new IOException("a seek before the start");
        Interlocked.Exchange(ref _position, to);
        return to;
    }

    public override void Flush() { }
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing && !_closed)
        {
            _closed = true;
            _window = [];
            _close();
        }
        base.Dispose(disposing);
    }
}
