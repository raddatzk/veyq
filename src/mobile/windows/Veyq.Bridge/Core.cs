// The core, reached through veyq.dll's six functions (ADR 0161 decision 5; ADR 0123's Consequences, the bridge): the
// wire's types (Veyq.Bridge.Wire, generated from the core's serial descriptors and bridge.h, never written here), a safe
// wrapper over the functions, and the host function — the one way the core asks the device for something (its store).
//
// Memory: what this side hands in is read before the function returns; what the core hands to a callback is valid only
// during that call, so it is copied at once. A context handed to the core is a GCHandle: a call's is freed once its
// answer arrived, a watch's is kept until Shutdown (bridge.h: an update may still arrive after veyq_unwatch), the
// store's for the process. No exception leaves a callback: the core's thread would end the process.
//
// Threads: every answer, update and host request arrives on a core thread. An answer completes a Task whose
// continuations never run there (RunContinuationsAsynchronously): awaited on the UI thread, it resumes on the UI thread's
// SynchronizationContext (WinUI's is its DispatcherQueue's). An update is posted to the SynchronizationContext its watch
// was started on. The host function answers the store at once, from the core's thread, without waiting for the core
// (bridge.h); an Emergency Kit's drawing (Pdf.*, Picture.Luma) is the app's (Drawer: Windows' own renderer, which this
// net10.0 library cannot reach), answered from the thread pool when it is drawn, its pixels beside the reply.
using System;
using System.Collections.Generic;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Veyq.Bridge.Wire;

namespace Veyq.Bridge;

/// <summary>What <c>veyq_init</c> answered.</summary>
public enum Start
{
    Started,
    AlreadyStarted,

    /// <summary>The config or the host function was refused — a defect of this side.</summary>
    Refused,

    /// <summary>This CPU has no AES-GCM (AES-NI, PCLMUL and AVX) the suite needs: the app says so and does not start.</summary>
    NoAes,
}

/// <summary>An answer or an update that did not read as its type: the two sides were built from different models.</summary>
public sealed class UnreadableException(string what, Exception inner) : Exception($"{what} did not read", inner);

/// <summary>The core: started once per process, then called and watched.</summary>
public static unsafe class Core
{
    private static readonly object Lock = new();

    /// <summary>The watches' contexts, freed at Shutdown (an update may arrive after its veyq_unwatch until then).</summary>
    private static readonly List<GCHandle> Kept = [];

    /// <summary>
    /// What draws an Emergency Kit file the core asks for (<c>Pdf.Pages</c>, <c>Pdf.Page</c>, <c>Picture.Luma</c>) and a
    /// picked medium's tiers (<c>Media.Duration</c>, <c>Media.Tiers</c>; ADR 0161 decision 1: the platform draws, the core
    /// decides): the app's, set before the core starts; null for a request it does not draw. Its task's payload (a
    /// drawing's luminance, a medium's two JPEGs) goes beside the reply and is zeroed once the core copied it. Without one,
    /// every such request is answered failed.
    /// </summary>
    public static Func<HostRequest, Task<(HostReply Reply, byte[]? Payload)>?>? Drawer { get; set; }

    /// <summary>
    /// What plays the music the core asks for (ADR 0142 decisions 2 and 3: the player's two decks, <c>Audio.*</c>, and the
    /// system's controls, <c>Controls.*</c>): the app's, set before the core starts. Unlike <see cref="Drawer"/> it is called
    /// on the core's thread itself, in the order the core asks, so it can hand each request on to the one thread its players
    /// live on in that order (a ramp told before a clear is run before it); its task's reply is sent when it completes.
    /// Without one, every such request is answered failed.
    /// </summary>
    public static Func<HostRequest, Task<HostReply>>? Player { get; set; }

    /// <summary>
    /// Where a debug build's request log goes (<c>Net.Log</c>, inbox #947): the core sends a line only where the host's
    /// <see cref="Config"/> asked for it, and none reaches a release build.
    /// </summary>
    public static Action<string>? NetLogged { get; set; }

    /// <summary>
    /// Starts the core once per process with <paramref name="store"/> as the device's store, which the core reads from its own
    /// threads until the process ends.
    /// </summary>
    public static Start Start(Config config, DeviceStore store)
    {
        var handle = GCHandle.Alloc(store);
        int code;
        fixed (byte* json = Utf8(JsonSerializer.Serialize(config, WireContext.Default.Config)))
        {
            code = Native.veyq_init(json, &Host, (void*)GCHandle.ToIntPtr(handle));
        }
        if (code != 0) handle.Free(); // the core did not take it
        return code switch
        {
            0 => global::Veyq.Bridge.Start.Started,
            1 => global::Veyq.Bridge.Start.AlreadyStarted,
            3 => global::Veyq.Bridge.Start.NoAes,
            _ => global::Veyq.Bridge.Start.Refused,
        };
    }

    /// <summary>Ends the core: every watch and every call under way; the application calls it when it quits.</summary>
    public static void Shutdown()
    {
        Native.veyq_shutdown();
        lock (Lock)
        {
            foreach (var h in Kept) h.Free();
            Kept.Clear();
        }
    }

    /// <summary>
    /// One action of the core, answered once, with the payload beside the answer: the bytes of an <c>Answer.Bytes</c> (a
    /// member's picture, a blob's range) or of an <c>Answer.RowCover</c> that sent its picture, copied out of the callback;
    /// null for every other answer. Before the core started
    /// it is never answered.
    /// </summary>
    public static Task<(Answer Answer, byte[]? Payload)> CallAsync(Call call) => CallAsync(call, null);

    /// <summary>
    /// <see cref="CallAsync(Call)"/> with <paramref name="inbound"/> beside the call's JSON (ADR 0137 decision 1's inward
    /// half: a scan's camera frame, <c>Scan.Frame</c>), which the core copies before <c>veyq_call</c> returns. Null is no
    /// payload; an empty array an empty one.
    /// </summary>
    public static Task<(Answer Answer, byte[]? Payload)> CallAsync(Call call, byte[]? inbound)
    {
        var answer = new TaskCompletionSource<(Answer, byte[]?)>(TaskCreationOptions.RunContinuationsAsynchronously);
        var handle = GCHandle.Alloc(answer);
        // an empty array's pointer is NULL under `fixed`: one byte stands for it, never read, so empty stays apart from none
        var held = inbound is { Length: 0 } ? new byte[1] : inbound;
        fixed (byte* json = Utf8(JsonSerializer.Serialize(call, WireContext.Default.Call)))
        fixed (byte* bytes = held)
        {
            Native.veyq_call(json, inbound is null ? null : bytes, (nuint)(inbound?.Length ?? 0), (void*)GCHandle.ToIntPtr(handle), &Answered);
        }
        return answer.Task;
    }

    /// <summary>
    /// Observes <paramref name="what"/>: its state now, then each change, arrive through <paramref name="updated"/> on the
    /// SynchronizationContext this is called on (on the core's thread where there is none); one that does not read arrives
    /// through <paramref name="unreadable"/>. Null when the core refused the watch. Disposing it ends the watch.
    /// </summary>
    public static Watch? Watch(Watched what, Action<Update> updated, Action<UnreadableException> unreadable)
    {
        var sink = new Sink(SynchronizationContext.Current, updated, unreadable);
        var handle = GCHandle.Alloc(sink);
        ulong id;
        fixed (byte* json = Utf8(JsonSerializer.Serialize(what, WireContext.Default.Watched)))
        {
            id = Native.veyq_watch(json, (void*)GCHandle.ToIntPtr(handle), &Updated);
        }
        if (id == 0)
        {
            handle.Free(); // refused: the core holds no context
            return null;
        }
        lock (Lock) Kept.Add(handle);
        return new Watch(id);
    }

    [UnmanagedCallersOnly(CallConvs = [typeof(CallConvCdecl)])]
    private static void Answered(void* ctx, byte* json, byte* bytes, nuint len)
    {
        try
        {
            var handle = GCHandle.FromIntPtr((nint)ctx);
            var answer = (TaskCompletionSource<(Answer, byte[]?)>)handle.Target!;
            handle.Free(); // the core answers once
            var text = Copy(json);
            var payload = Native.Payload(bytes, len); // copied here: the core's bytes are valid only during this call
            try
            {
                answer.TrySetResult((JsonSerializer.Deserialize(text, WireContext.Default.Answer) ?? throw new JsonException("null"), payload));
            }
            catch (JsonException e)
            {
                answer.TrySetException(new UnreadableException("an answer", e));
            }
        }
        catch (Exception e)
        {
            Fault(e);
        }
    }

    [UnmanagedCallersOnly(CallConvs = [typeof(CallConvCdecl)])]
    private static void Updated(void* ctx, byte* json, byte* bytes, nuint len)
    {
        try
        {
            var sink = (Sink)GCHandle.FromIntPtr((nint)ctx).Target!;
            var text = Copy(json);
            Update? update = null;
            UnreadableException? error = null;
            try
            {
                update = JsonSerializer.Deserialize(text, WireContext.Default.Update) ?? throw new JsonException("null");
            }
            catch (JsonException e)
            {
                error = new UnreadableException("an update", e);
            }
            sink.Deliver(update, error);
        }
        catch (Exception e)
        {
            Fault(e);
        }
    }

    /// <summary>
    /// The host function: the core's request — the store's answered at once from this (core) thread, a drawing handed to
    /// <see cref="Drawer"/> and answered when drawn (bridge.h: a reply may come from any thread).
    /// </summary>
    [UnmanagedCallersOnly(CallConvs = [typeof(CallConvCdecl)])]
    private static void Host(void* ctx, ulong token, byte* json, byte* bytes, nuint len)
    {
        HostReply reply;
        try
        {
            var store = (DeviceStore)GCHandle.FromIntPtr((nint)ctx).Target!;
            var request = JsonSerializer.Deserialize(Copy(json), WireContext.Default.HostRequest) ?? throw new JsonException("null");
            if (request is HostRequest.NetLog logged)
            {
                NetLogged?.Invoke(logged.line);
                Reply(token, new HostReply.Done(), null);
                return;
            }
            if (request is HostRequest.PdfPages or HostRequest.PdfPage or HostRequest.PictureLuma or HostRequest.PictureSquare or HostRequest.MediaDuration or HostRequest.MediaSlice or HostRequest.MediaTiers
                or HostRequest.WordsSay)
            {
                Draw(token, request);
                return;
            }
            if (request is HostRequest.AudioLoad or HostRequest.AudioPlay or HostRequest.AudioPause or HostRequest.AudioSeek
                or HostRequest.AudioPosition or HostRequest.AudioRamp or HostRequest.AudioClear
                or HostRequest.ControlsBind or HostRequest.ControlsShow or HostRequest.ControlsClear)
            {
                Play(token, request);
                return;
            }
            reply = store.Answer(request);
        }
        catch (JsonException)
        {
            reply = new HostReply.Failed { error = "the host could not read the request" };
        }
        catch (Exception e)
        {
            reply = new HostReply.Failed { error = $"the host failed: {e.GetType().Name}" };
        }
        Reply(token, reply, null);
    }

    /// <summary>A drawing asked of <see cref="Drawer"/> on the thread pool, off the core's thread; whatever happens, one reply.</summary>
    private static void Draw(ulong token, HostRequest request)
    {
        // (this class is unsafe, where no await may stand: the drawing's task is continued instead)
        Task.Run(() => Drawer?.Invoke(request) ?? Task.FromResult<(HostReply, byte[]?)>((new HostReply.Failed { error = "this host draws nothing" }, null)))
            .ContinueWith(t =>
            {
                var drawn = t.IsCompletedSuccessfully
                    ? t.Result
                    : (new HostReply.Failed { error = $"the drawing failed: {t.Exception?.InnerException?.GetType().Name}" }, null);
                try
                {
                    Reply(token, drawn.Item1, drawn.Item2);
                }
                catch (Exception e)
                {
                    Fault(e);
                }
                finally
                {
                    if (drawn.Item2 is { } p) Array.Clear(p);
                }
            }, TaskScheduler.Default);
    }

    /// <summary>A request of the music handed to <see cref="Player"/> on this (core) thread, in order; one reply when its task ends.</summary>
    private static void Play(ulong token, HostRequest request)
    {
        Task<HostReply> played;
        try
        {
            played = Player?.Invoke(request) ?? Task.FromResult<HostReply>(new HostReply.Failed { error = "this host plays nothing" });
        }
        catch (Exception e)
        {
            played = Task.FromResult<HostReply>(new HostReply.Failed { error = $"the host failed: {e.GetType().Name}" });
        }
        played.ContinueWith(t =>
        {
            try
            {
                Reply(token, t.IsCompletedSuccessfully ? t.Result : new HostReply.Failed { error = $"the player failed: {t.Exception?.InnerException?.GetType().Name}" }, null);
            }
            catch (Exception e)
            {
                Fault(e);
            }
        }, TaskScheduler.Default);
    }

    /// <summary>The reply to <paramref name="token"/>, <paramref name="payload"/> beside it (read before this returns).</summary>
    private static void Reply(ulong token, HostReply reply, byte[]? payload)
    {
        // an empty array's pointer is NULL under `fixed`: one byte stands for it, never read
        var held = payload is { Length: 0 } ? new byte[1] : payload;
        fixed (byte* p = Utf8(JsonSerializer.Serialize(reply, WireContext.Default.HostReply)))
        fixed (byte* b = held)
        {
            Native.veyq_reply(token, p, payload is null ? null : b, (nuint)(payload?.Length ?? 0));
        }
    }

    /// <summary>A defect of this side inside a callback: said, never thrown into the core's thread.</summary>
    private static void Fault(Exception e) => Console.Error.WriteLine($"veyq: a callback failed: {e}");

    /// <summary>A NUL-terminated UTF-8 copy of <paramref name="s"/> (JSON holds no raw NUL: it escapes one).</summary>
    private static byte[] Utf8(string s)
    {
        var bytes = new byte[Encoding.UTF8.GetByteCount(s) + 1];
        Encoding.UTF8.GetBytes(s, bytes);
        return bytes;
    }

    /// <summary>The NUL-terminated UTF-8 string the core handed in, copied: it is valid only during the call.</summary>
    private static string Copy(byte* json) => json == null ? "" : Marshal.PtrToStringUTF8((nint)json) ?? "";

    private sealed class Sink(SynchronizationContext? context, Action<Update> updated, Action<UnreadableException> unreadable)
    {
        public void Deliver(Update? update, UnreadableException? error)
        {
            void Go()
            {
                if (update != null) updated(update);
                else unreadable(error!);
            }

            if (context == null) Go();
            else context.Post(_ => Go(), null);
        }
    }
}

/// <summary>An observed model; disposing it ends the watch.</summary>
public sealed class Watch : IDisposable
{
    private ulong _id;

    internal Watch(ulong id) => _id = id;

    public void Dispose()
    {
        var id = Interlocked.Exchange(ref _id, 0);
        if (id != 0) Native.veyq_unwatch(id);
    }
}
