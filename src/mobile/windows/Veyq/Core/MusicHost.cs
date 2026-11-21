// The music's host side on Windows (ADR 0142 decisions 2, 3 and 9; Linux's music_host.rs): the core's player decides which
// track plays, when and how loud; this plays bytes and lends the system's controls.
//
// - Two decks (Audio.*), each a Windows.Media.Playback.MediaPlayer over MediaSource.CreateFromStream of a ReaderStream whose
//   reads are the core's Reader.Read ranges (as MediumPlayer plays a video; nothing decrypted is written, ADR 0047), made at
//   a deck's load and let go at its clear. A load is answered with the file's length once Media Foundation opened it, or
//   failed where it cannot (no Web Media extension: an Ogg file, measured 2026-11-16) — the core then skips the track and
//   marks it. The volume ramp is this side's: steps of MusicRamp.StepMs on a DispatcherQueueTimer. An end or a stream error
//   after the load goes back as Audio.Ended / Audio.Failed with the load's number.
// - The system's controls (Controls.*): one SystemMediaTransportControls for the window (GetForWindow), the decks' own
//   CommandManager off, so the flyout and the media keys drive the core's player (Player.*), never a deck.
//
// Every request is handed to the UI thread's DispatcherQueue in the order the core sent it (Core.Player is called on the
// core's thread); the players live there.

namespace Veyq.Core;

using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using Microsoft.UI.Dispatching;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.Media;
using Windows.Media.Playback;

internal static class MusicHost
{
    private static DispatcherQueue? _queue;
    private static nint _window;
    private static readonly Dictionary<long, Deck> Decks = [];
    private static SystemMediaTransportControls? _controls;
    private static bool _bound;

    /// <summary>Plays on <paramref name="queue"/>'s thread; the system's controls are <paramref name="window"/>'s.</summary>
    public static void Start(DispatcherQueue queue, nint window)
    {
        _queue = queue;
        _window = window;
        Core.Player = Request;
    }

    /// <summary>The reply to Words.Say (an untitled track's title, ADR 0142 decision 4); null for every other request.</summary>
    public static Task<(HostReply Reply, byte[]? Payload)>? Words(HostRequest request) => request is HostRequest.WordsSay w
        ? Task.FromResult<(HostReply, byte[]?)>((new HostReply.Value { value = Said.Notice(w.words) }, null))
        : null;

    private static Task<HostReply> Request(HostRequest request)
    {
        var done = new TaskCompletionSource<HostReply>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (_queue is not { } queue || !queue.TryEnqueue(() =>
            {
                try
                {
                    Handle(request, done);
                }
                catch (Exception e)
                {
                    Log.Write($"music: {request.GetType().Name} threw {e.GetType().Name} {e.HResult:X8}");
                    done.TrySetResult(new HostReply.Failed { error = e.GetType().Name });
                }
            }))
        {
            done.TrySetResult(new HostReply.Failed { error = "no thread to play on" });
        }
        return done.Task;
    }

    private static readonly HostReply Done = new HostReply.Done();

    private static void Handle(HostRequest request, TaskCompletionSource<HostReply> done)
    {
        switch (request)
        {
            case HostRequest.AudioLoad r:
                Deck(r.deck).Load(r.load, r.reader, r.size, r.mediaType, done);
                break;
            case HostRequest.AudioPlay r:
                done.TrySetResult(Decks.TryGetValue(r.deck, out var p) && p.Play() ? Done : new HostReply.Failed { error = "nothing loaded" });
                break;
            case HostRequest.AudioPause r:
                if (Decks.TryGetValue(r.deck, out var pause)) pause.Pause();
                done.TrySetResult(Done);
                break;
            case HostRequest.AudioSeek r:
                if (Decks.TryGetValue(r.deck, out var seek)) seek.Seek(r.positionMs);
                done.TrySetResult(Done);
                break;
            case HostRequest.AudioPosition r:
                done.TrySetResult(new HostReply.Value { value = Decks.TryGetValue(r.deck, out var at) ? at.PositionMs().ToString(System.Globalization.CultureInfo.InvariantCulture) : "0" });
                break;
            case HostRequest.AudioRamp r:
                Deck(r.deck).Ramp(r.to, r.overMs);
                done.TrySetResult(Done);
                break;
            case HostRequest.AudioClear r:
                // the deck stays, its volume as last ramped: the core readies the next load at it
                if (Decks.TryGetValue(r.deck, out var cleared)) cleared.Dispose();
                done.TrySetResult(Done);
                break;
            case HostRequest.ControlsBind:
                Bind();
                done.TrySetResult(Done);
                break;
            case HostRequest.ControlsShow r:
                Show(r.now);
                done.TrySetResult(Done);
                break;
            case HostRequest.ControlsClear:
                Clear();
                done.TrySetResult(Done);
                break;
            default:
                done.TrySetResult(new HostReply.Failed { error = "not the music's" });
                break;
        }
    }

    private static Deck Deck(long id)
    {
        if (!Decks.TryGetValue(id, out var deck)) Decks[id] = deck = new Deck(id, _queue!);
        return deck;
    }

    // --- the system's controls ----------------------------------------------------------------------------------------

    private static SystemMediaTransportControls? Controls()
    {
        if (_controls is not null) return _controls;
        try
        {
            _controls = SystemMediaTransportControlsInterop.GetForWindow(_window);
            _controls.ButtonPressed += (_, e) =>
            {
                Log.Write($"system controls: {e.Button}");
                Call? call = e.Button switch
                {
                    SystemMediaTransportControlsButton.Play => new Call.PlayerResume(),
                    SystemMediaTransportControlsButton.Pause => new Call.PlayerPause(),
                    SystemMediaTransportControlsButton.Next => new Call.PlayerNext(),
                    SystemMediaTransportControlsButton.Previous => new Call.PlayerPrevious(),
                    SystemMediaTransportControlsButton.Stop => new Call.PlayerStop(),
                    _ => null,
                };
                if (call is not null) Act.Send(call);
            };
            _controls.PlaybackPositionChangeRequested += (_, e) =>
            {
                Log.Write($"system controls: seek to {(long)e.RequestedPlaybackPosition.TotalMilliseconds} ms");
                Act.Send(new Call.PlayerSeek { positionMs = (long)e.RequestedPlaybackPosition.TotalMilliseconds });
            };
        }
        catch (Exception e)
        {
            Log.Write($"system controls: none for this window ({e.GetType().Name} {e.HResult:X8})");
        }
        return _controls;
    }

    private static void Bind()
    {
        if (Controls() is not { } c) return;
        c.IsEnabled = true;
        c.IsPlayEnabled = c.IsPauseEnabled = c.IsNextEnabled = c.IsPreviousEnabled = c.IsStopEnabled = true;
        _bound = true;
        Log.Write("system controls: bound");
    }

    private static void Show(NowPlayingShown now)
    {
        if (!_bound || Controls() is not { } c) return;
        c.PlaybackStatus = now.playing ? MediaPlaybackStatus.Playing : MediaPlaybackStatus.Paused;
        var display = c.DisplayUpdater;
        if (display.Type != MediaPlaybackType.Music || display.MusicProperties.Title != now.title || display.MusicProperties.AlbumTitle != (now.album ?? ""))
        {
            display.Type = MediaPlaybackType.Music;
            display.MusicProperties.Title = now.title;
            display.MusicProperties.AlbumTitle = now.album ?? "";
            display.Update();
            Log.Write($"system controls: shows a track, {(now.playing ? "playing" : "paused")}");
        }
        c.UpdateTimelineProperties(new SystemMediaTransportControlsTimelineProperties
        {
            StartTime = TimeSpan.Zero,
            MinSeekTime = TimeSpan.Zero,
            Position = TimeSpan.FromMilliseconds(now.positionMs),
            MaxSeekTime = TimeSpan.FromMilliseconds(now.durationMs),
            EndTime = TimeSpan.FromMilliseconds(now.durationMs),
        });
    }

    private static void Clear()
    {
        _bound = false;
        if (_controls is not { } c) return;
        c.DisplayUpdater.ClearAll();
        c.DisplayUpdater.Update();
        c.PlaybackStatus = MediaPlaybackStatus.Closed;
        c.IsPlayEnabled = c.IsPauseEnabled = c.IsNextEnabled = c.IsPreviousEnabled = c.IsStopEnabled = false;
        c.IsEnabled = false;
        Log.Write("system controls: cleared");
    }
}

/// <summary>One of the core's two decks: a MediaPlayer for one load at a time, on the UI thread.</summary>
internal sealed class Deck(long id, DispatcherQueue queue) : IDisposable
{
    private MediaPlayer? _mp;
    private Windows.Media.Core.MediaSource? _source;
    private ReaderStream? _stream;
    private long _load;
    private bool _opened;
    private double _volume = 1;
    private DispatcherQueueTimer? _ramp;

    public void Load(long load, long reader, long size, string type, TaskCompletionSource<HostReply> done)
    {
        LetGo();
        _load = load;
        _opened = false;
        _stream = new ReaderStream(size, (at, length) => Range(reader, at, length), () => { });
        _source = Windows.Media.Core.MediaSource.CreateFromStream(_stream.AsRandomAccessStream(), type);
        var mp = new MediaPlayer { AutoPlay = false, Volume = _volume, AudioCategory = MediaPlayerAudioCategory.Media };
        // the window's one SMTC drives the core's player: a deck's own would answer the media keys for itself
        mp.CommandManager.IsEnabled = false;
        mp.MediaOpened += (p, _) =>
        {
            var ms = (long)p.PlaybackSession.NaturalDuration.TotalMilliseconds;
            Log.Write($"deck {id} load {load}: opened, {ms} ms");
            queue.TryEnqueue(() =>
            {
                if (_load == load) _opened = true;
            });
            done.TrySetResult(new HostReply.Value { value = ms > 0 ? ms.ToString(System.Globalization.CultureInfo.InvariantCulture) : null });
        };
        mp.MediaFailed += (_, e) =>
        {
            Log.Write($"deck {id} load {load}: does not play here ({e.Error} {e.ExtendedErrorCode?.HResult:X8})");
            // before it opened the load is refused (the core skips the track); after, the stream failed
            if (done.TrySetResult(new HostReply.Failed { error = $"{e.Error}" })) return;
            Act.Send(new Call.AudioFailed { deck = id, load = load });
        };
        mp.MediaEnded += (_, _) =>
        {
            Log.Write($"deck {id} load {load}: ended");
            Act.Send(new Call.AudioEnded { deck = id, load = load });
        };
        mp.Source = _source;
        _mp = mp;
    }

    /// <summary>Range <paramref name="at"/>+<paramref name="length"/> of reader <paramref name="reader"/> (Reader.Read), off the UI thread.</summary>
    private static async Task<byte[]> Range(long reader, long at, int length)
    {
        var (answer, bytes) = await Core.CallAsync(new Call.ReaderRead { reader = reader, position = at, length = length }).ConfigureAwait(false);
        return answer is Answer.Bytes && bytes is not null ? bytes : throw new IOException($"Reader.Read answered {answer.GetType().Name}");
    }

    public bool Play()
    {
        if (_mp is not { } mp || !_opened) return false;
        mp.Play();
        return true;
    }

    public void Pause() => _mp?.Pause();

    public void Seek(long ms)
    {
        if (_mp is { } mp) mp.PlaybackSession.Position = TimeSpan.FromMilliseconds(Math.Max(0, ms));
    }

    public long PositionMs() => _mp is { } mp ? (long)mp.PlaybackSession.Position.TotalMilliseconds : 0;

    /// <summary>The volume moved to <paramref name="to"/> over <paramref name="overMs"/> in MusicRamp's steps; a ramp
    /// under way is replaced from where it stands.</summary>
    public void Ramp(double to, long overMs)
    {
        _ramp?.Stop();
        _ramp = null;
        var steps = MusicRamp.Steps(_volume, to, overMs);
        if (steps.Count == 1)
        {
            Set(steps[0]);
            return;
        }
        var next = 0;
        var timer = queue.CreateTimer();
        timer.Interval = MusicRamp.Interval(overMs);
        timer.IsRepeating = true;
        timer.Tick += (t, _) =>
        {
            if (!ReferenceEquals(t, _ramp)) return;
            Set(steps[next++]);
            if (next < steps.Count) return;
            t.Stop();
            _ramp = null;
        };
        _ramp = timer;
        timer.Start();
    }

    private void Set(double volume)
    {
        _volume = volume;
        if (_mp is { } mp) mp.Volume = volume;
    }

    /// <summary>The player let go as MediumPlayer lets one go: paused, its source, the player, the stream (the core drops
    /// the reader itself at the clear).</summary>
    private void LetGo()
    {
        _ramp?.Stop();
        _ramp = null;
        var (mp, source, stream) = (_mp, _source, _stream);
        (_mp, _source, _stream) = (null, null, null);
        if (mp is not null)
        {
            MediumPlayer.Quietly("pause", () => mp.Pause());
            MediumPlayer.Quietly("source", () => mp.Source = null);
            MediumPlayer.Quietly("player", () => mp.Dispose());
        }
        if (source is not null) MediumPlayer.Quietly("media source", () => source.Dispose());
        if (stream is not null) MediumPlayer.Quietly("stream", () => stream.Dispose());
    }

    /// <summary>The deck's clear: its player let go, its volume kept for its next load ("readied … its volume as last ramped").</summary>
    public void Dispose() => LetGo();
}
