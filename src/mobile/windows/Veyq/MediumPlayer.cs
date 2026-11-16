// A medium played by the system's own player over the core's reader (a file's page and a Memory's lightbox; Linux's
// player.rs): a MediaPlayerElement with its transport controls over a MediaSource of ReaderStream, whose reads are
// Reader.Read ranges — Media Foundation and the Store's extensions decode it, nothing decrypted is written (ADR 0047). The
// player is this class's: handed to the element and taken back from it before it is let go (a player the element made
// itself and that was disposed under it threw E_ABORT when the element left the tree, src/mobile/CLAUDE.md § Traps).
using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

internal sealed class MediumPlayer : IDisposable
{
    private readonly Windows.Media.Playback.MediaPlayer _mp;
    private readonly Windows.Media.Core.MediaSource _source;
    private readonly ReaderStream _stream;
    private bool _disposed;

    public MediaPlayerElement Element { get; }

    /// <summary>Reader <paramref name="reader"/> of <paramref name="size"/> bytes of <paramref name="type"/> in a player named
    /// <paramref name="name"/>; <paramref name="failed"/> is told on <paramref name="queue"/> when the system cannot play it.
    /// <paramref name="autoPlay"/>: it starts once opened. <paramref name="playing"/>, where given, is told on
    /// <paramref name="queue"/> each time it starts playing (true) or stops — paused, ended, failed (false): a lightbox's
    /// video interrupts the music while it plays (ADR 0142 decision 13).</summary>
    public MediumPlayer(long reader, long size, string? type, string automationId, string name, bool autoPlay, DispatcherQueue queue, Action failed, Action<bool>? playing = null)
    {
        _stream = new ReaderStream(size, (at, length) => Range(reader, at, length), () => _ = Core.CallAsync(new Call.ReaderClose { reader = reader }));
        _source = Windows.Media.Core.MediaSource.CreateFromStream(_stream.AsRandomAccessStream(), type ?? "");
        _mp = new Windows.Media.Playback.MediaPlayer { AutoPlay = autoPlay, Source = _source };
        Element = new MediaPlayerElement { AreTransportControlsEnabled = true, Stretch = Stretch.Uniform };
        Element.SetMediaPlayer(_mp);
        AutomationProperties.SetAutomationId(Element, automationId);
        AutomationProperties.SetName(Element, name);
        _mp.MediaOpened += (p, _) => Log.Write($"medium opened: {p.PlaybackSession.NaturalDuration.TotalSeconds:0} s, {p.PlaybackSession.NaturalVideoWidth}×{p.PlaybackSession.NaturalVideoHeight}, seekable {p.PlaybackSession.CanSeek}");
        _mp.MediaFailed += (_, e) =>
        {
            Log.Write($"the medium does not play here: {e.Error} {e.ExtendedErrorCode?.HResult:X8} {e.ErrorMessage}");
            queue.TryEnqueue(() =>
            {
                if (!_disposed) failed();
            });
        };
        if (playing is not null)
        {
            var told = (bool?)null;
            void Tell(bool now) => queue.TryEnqueue(() =>
            {
                if (_disposed || told == now) return;
                told = now;
                playing(now);
            });
            // buffering and opening say nothing: a video that stalls for its bytes still plays
            _mp.PlaybackSession.PlaybackStateChanged += (session, _) =>
            {
                switch (session.PlaybackState)
                {
                    case Windows.Media.Playback.MediaPlaybackState.Playing: Tell(true); break;
                    case Windows.Media.Playback.MediaPlaybackState.Paused or Windows.Media.Playback.MediaPlaybackState.None: Tell(false); break;
                }
            };
            _mp.MediaEnded += (_, _) => Tell(false);
            _mp.MediaFailed += (_, _) => Tell(false);
        }
        // the position in the log each fifth second, for a pass to read the play and a seek from
        var logged = -1L;
        _mp.PlaybackSession.PositionChanged += (session, _) =>
        {
            var at = (long)session.Position.TotalSeconds / 5;
            if (Interlocked.Exchange(ref logged, at) != at)
                Log.Write($"medium at {(long)session.Position.TotalSeconds} s of {(long)session.NaturalDuration.TotalSeconds} s");
        };
    }

    /// <summary>Range <paramref name="at"/>+<paramref name="length"/> of reader <paramref name="reader"/> (Reader.Read),
    /// awaited off the UI thread.</summary>
    private static async Task<byte[]> Range(long reader, long at, int length)
    {
        var (answer, bytes) = await Core.CallAsync(new Call.ReaderRead { reader = reader, position = at, length = length }).ConfigureAwait(false);
        return answer is Answer.Bytes && bytes is not null ? bytes : throw new IOException($"Reader.Read answered {answer.GetType().Name}");
    }

    /// <summary>The player let go first, then its source and the stream, which lets the core's reader go.</summary>
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Quietly("pause", () => _mp.Pause());
        Quietly("element", () => Element.SetMediaPlayer(null));
        Quietly("source", () => _mp.Source = null);
        Quietly("player", () => _mp.Dispose());
        Quietly("media source", () => _source.Dispose());
        Quietly("stream", () => _stream.Dispose());
    }

    /// <summary>A step of letting the player go, which may throw while Media Foundation still works (an exception out of a
    /// dispatcher's callback ends the app in combase): said in the log, the next step taken.</summary>
    public static void Quietly(string what, Action step)
    {
        try
        {
            step();
        }
        catch (Exception e)
        {
            Log.Write($"letting the medium go: {what} threw {e.GetType().Name} {e.HResult:X8}");
        }
    }
}
