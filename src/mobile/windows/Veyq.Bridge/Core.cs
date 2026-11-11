// The core, reached through veyq.dll's six functions (ADR 0127 decision 5; ADR 0123's Consequences, the bridge): the
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
// was started on. The host function answers at once, from the core's thread, without waiting for the core (bridge.h).
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
    /// member's picture, a blob's range), copied out of the callback; null for every other answer. Before the core started
    /// it is never answered.
    /// </summary>
    public static Task<(Answer Answer, byte[]? Payload)> CallAsync(Call call)
    {
        var answer = new TaskCompletionSource<(Answer, byte[]?)>(TaskCreationOptions.RunContinuationsAsynchronously);
        var handle = GCHandle.Alloc(answer);
        fixed (byte* json = Utf8(JsonSerializer.Serialize(call, WireContext.Default.Call)))
        {
            Native.veyq_call(json, null, 0, (void*)GCHandle.ToIntPtr(handle), &Answered);
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

    /// <summary>The host function: the core's request, answered at once from this (core) thread.</summary>
    [UnmanagedCallersOnly(CallConvs = [typeof(CallConvCdecl)])]
    private static void Host(void* ctx, ulong token, byte* json, byte* bytes, nuint len)
    {
        HostReply reply;
        try
        {
            var store = (DeviceStore)GCHandle.FromIntPtr((nint)ctx).Target!;
            var request = JsonSerializer.Deserialize(Copy(json), WireContext.Default.HostRequest) ?? throw new JsonException("null");
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
        fixed (byte* p = Utf8(JsonSerializer.Serialize(reply, WireContext.Default.HostReply)))
        {
            Native.veyq_reply(token, p, null, 0);
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
