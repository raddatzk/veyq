using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Microsoft.Web.WebView2.Core;
using Veyq.Core;

namespace Veyq.Tests;

/// <summary>
/// WebView2's PDF viewer as the app sets it up (PdfWeb, inbox #955), the measurement it was chosen on kept as its
/// regression: a PDF whose text and title are canaries is served from memory, its text selected and copied (Ctrl+A,
/// Ctrl+C sent to the viewer), the viewer closed and its browser started once more on the same folder; then no file
/// in the viewer's user-data folder, nor one written meanwhile under %TEMP%, %LOCALAPPDATA% or %APPDATA%, holds a
/// canary (ASCII, UTF-16) or the PDF's bytes, and the view made no request but the document's.
/// Each browser writes Chromium's net log, which must show no DNS query, no connection tried, no datagram and no request
/// started: <c>VEYQ_PDF_NETLOG=&lt;path&gt;</c> keeps it beside that path in full (<c>.1.json</c>, <c>.2.json</c>).
/// <c>VEYQ_PDF_BARE=1</c> starts the browser without PdfWeb's arguments and not InPrivate: the measurement's baseline and
/// this test's bite, red (the PDF's title in <c>History</c>; Edge's configuration fetched).
/// </summary>
[TestClass]
public sealed class PdfWebTest
{
    [TestMethod]
    [Timeout(180_000)]
    public void TheViewerCopiesTheTextAndKeepsNoneOfItOnDisk()
    {
        var text = Canary();
        var title = Canary();
        var marker = Canary();
        var (pdf, stream) = Pdf(text, title, marker);
        var since = DateTime.UtcNow.AddSeconds(-2);
        var folder = PdfWeb.NewFolder();
        var seen = new ConcurrentQueue<string>();
        var bare = Environment.GetEnvironmentVariable("VEYQ_PDF_BARE") == "1";
        // Chromium's net log of each browser (a second one on the same path writes over the first's): the witness of what
        // reached the network, read below; VEYQ_PDF_NETLOG keeps it there in full, else it goes to %TEMP% and is deleted
        var kept = Environment.GetEnvironmentVariable("VEYQ_PDF_NETLOG");
        var netLog = string.IsNullOrEmpty(kept) ? Path.Combine(Path.GetTempPath(), $"veyq-pdf-netlog-{Guid.NewGuid():N}") : kept;
        string NetLog(int run) => $"{netLog}.{run}.json";
        string Extra(int run) => $"--log-net-log=\"{NetLog(run)}\"" + (string.IsNullOrEmpty(kept) ? "" : " --net-log-capture-mode=Everything");
        var extra = Extra(1);
        string? copied = null;
        try
        {
            copied = Sta.Run(async () =>
            {
                using var window = new Window();
                Assert.AreNotEqual(IntPtr.Zero, window.Handle, $"no window: {window.Error}");
                var environment = bare
                    ? await CoreWebView2Environment.CreateWithOptionsAsync("", folder, new CoreWebView2EnvironmentOptions { AdditionalBrowserArguments = extra })
                    : await PdfWeb.EnvironmentAsync(folder, extra);
                var controllerOptions = PdfWeb.ControllerOptions(environment);
                if (bare) controllerOptions.IsInPrivateModeEnabled = false;
                var controller = await environment.CreateCoreWebView2ControllerAsync(
                    CoreWebView2ControllerWindowReference.CreateFromWindowHandle((ulong)window.Handle), controllerOptions);
                controller.Bounds = new Windows.Foundation.Rect(0, 0, 900, 700);
                controller.IsVisible = true;
                var core = controller.CoreWebView2;
                var loaded = new TaskCompletionSource<bool>();
                core.NavigationCompleted += (_, e) => loaded.TrySetResult(e.IsSuccess);
                PdfWeb.Show(environment, core, pdf, seen.Enqueue);
                Assert.IsTrue(await Within(loaded.Task, 30), "the PDF did not load");
                Assert.AreEqual(PdfWeb.Document, core.Source);
                await Task.Delay(3000);
                Clipboard.Clear(window.Handle);
                controller.MoveFocus(CoreWebView2MoveFocusReason.Programmatic);
                await Click(core, 450, 300);
                await Keys(core, 'A', "selectAll");
                await Keys(core, 'C', "copy");
                string? got = null;
                for (var i = 0; i < 50 && got is null; i++)
                {
                    await Task.Delay(100);
                    got = Clipboard.Text(window.Handle);
                }
                // the host's clipboard left empty, not holding the canary
                Clipboard.Clear(window.Handle);
                await Close(environment, controller);
                // the browser once more on the same folder: what it writes only at its next start
                var again = await PdfWeb.EnvironmentAsync(folder, Extra(2));
                var second = await again.CreateCoreWebView2ControllerAsync(
                    CoreWebView2ControllerWindowReference.CreateFromWindowHandle((ulong)window.Handle), PdfWeb.ControllerOptions(again));
                await Task.Delay(1500);
                await Close(again, second);
                return got;
            });
            Say($"requests: {string.Join(" ", seen)}");
            Say($"copied: {(copied is null ? "nothing" : copied.Contains(text) ? "the text" : $"other text ({copied.Length} chars)")}");
            var needles = new Dictionary<string, byte[]>();
            foreach (var (name, value) in new[] { ("text", text), ("title", title), ("marker", marker) })
            {
                needles[$"{name} (ASCII)"] = Encoding.ASCII.GetBytes(value);
                needles[$"{name} (UTF-16LE)"] = Encoding.Unicode.GetBytes(value);
                needles[$"{name} (UTF-16BE)"] = Encoding.BigEndianUnicode.GetBytes(value);
            }
            needles["the PDF's content stream"] = stream[..Math.Min(48, stream.Length)];
            needles["the PDF (base64)"] = Encoding.ASCII.GetBytes(Convert.ToBase64String(pdf)[..64]);
            var files = Files(folder, DateTime.MinValue).Concat(new[]
            {
                Path.GetTempPath(),
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            }.SelectMany(root => Files(root, since))).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
            var inFolder = Files(folder, DateTime.MinValue).Select(f => Path.GetRelativePath(folder, f)).ToList();
            Say($"the viewer's folder held {inFolder.Count} files: {string.Join(", ", inFolder)}");
            Say($"{files.Count} files written since the start searched");
            var found = new List<string>();
            foreach (var file in files)
            {
                var bytes = Read(file);
                if (bytes is null) continue;
                foreach (var (what, needle) in needles)
                {
                    if (bytes.AsSpan().IndexOf(needle) >= 0) found.Add($"{what} in {file}");
                }
            }
            foreach (var f in found) Say($"FOUND {f}");
            var wire = new[] { 1, 2 }.SelectMany(run => Wire(NetLog(run))).ToList();
            foreach (var w in wire) Say($"NETWORK {w}");
            Assert.AreEqual(0, found.Count, $"plaintext on disk: {string.Join("; ", found)}");
            // the viewer asks its page's favicon too, which is refused; nothing names another host
            var elsewhere = seen.Where(u => PdfWeb.Network(u) && !u.StartsWith($"https://{PdfWeb.Host}/", StringComparison.Ordinal)).ToList();
            Assert.AreEqual(0, elsewhere.Count, $"the view asked another host: {string.Join(" ", elsewhere)}");
            Assert.AreEqual(0, wire.Count, $"the browser reached the network: {string.Join("; ", wire.Distinct())}");
            Assert.IsNotNull(copied, "nothing was copied");
            StringAssert.Contains(copied, text, "the copied text is not the PDF's");
        }
        finally
        {
            if (string.IsNullOrEmpty(kept))
            {
                foreach (var run in new[] { 1, 2 }) File.Delete(NetLog(run));
            }
            Assert.IsTrue(Sta.Run(async () => { await PdfWeb.DeleteAsync(folder); return !Directory.Exists(folder); }), "the viewer's folder stayed");
        }
    }

    /// <summary>What the test saw, printed and, with <c>VEYQ_PDF_REPORT=&lt;file&gt;</c>, written there (a passing
    /// test's output is not shown).</summary>
    private static void Say(string line)
    {
        Console.WriteLine(line);
        if (Environment.GetEnvironmentVariable("VEYQ_PDF_REPORT") is { Length: > 0 } report) File.AppendAllText(report, line + Environment.NewLine);
    }

    /// <summary>The events of the net log at <paramref name="file"/> that put something on the wire or asked a resolver:
    /// a DNS query, a TCP connection tried, a UDP datagram or a request started (Chromium's IPv6 probe connects a UDP
    /// socket without sending, which is none of them).</summary>
    private static IEnumerable<string> Wire(string file)
    {
        var text = File.ReadAllText(file).TrimEnd();
        // a browser that ended without closing its log leaves the event array open
        if (!text.EndsWith('}')) text = text.TrimEnd(',') + "]}";
        using var log = System.Text.Json.JsonDocument.Parse(text);
        var names = log.RootElement.GetProperty("constants").GetProperty("logEventTypes").EnumerateObject()
            .ToDictionary(p => p.Value.GetInt32(), p => p.Name);
        string[] wire = ["HOST_RESOLVER_DNS_TASK", "TCP_CONNECT_ATTEMPT", "UDP_BYTES_SENT", "URL_REQUEST_START_JOB", "SSL_CONNECT"];
        var found = new List<string>();
        foreach (var e in log.RootElement.GetProperty("events").EnumerateArray())
        {
            var name = names.GetValueOrDefault(e.GetProperty("type").GetInt32(), "?");
            if (wire.Contains(name)) found.Add($"{name} {(e.TryGetProperty("params", out var p) ? p.ToString() : "")}");
        }
        return found;
    }

    /// <summary>A random word of 24 lowercase letters: found nowhere it was not put.</summary>
    private static string Canary() => new(RandomNumberGenerator.GetItems<char>("abcdefghijklmnopqrstuvwxyz", 24));

    /// <summary>A one-page PDF whose page says <paramref name="text"/> in Helvetica through a Flate-compressed content
    /// stream (so the text is not in the file's bytes), whose title is <paramref name="title"/> as UTF-16BE hex (nor is
    /// it), and whose comment holds <paramref name="marker"/> (the file's own bytes, if they are copied); and the
    /// compressed stream.</summary>
    internal static (byte[] Pdf, byte[] Stream) Pdf(string text, string title, string marker)
    {
        var content = Encoding.ASCII.GetBytes($"BT /F1 24 Tf 40 300 Td ({text}) Tj ET");
        using var packed = new MemoryStream();
        using (var z = new ZLibStream(packed, CompressionLevel.Optimal, leaveOpen: true)) z.Write(content);
        var stream = packed.ToArray();
        var hexTitle = "FEFF" + Convert.ToHexString(Encoding.BigEndianUnicode.GetBytes(title));
        var pdf = new MemoryStream();
        void Put(string s) => pdf.Write(Encoding.ASCII.GetBytes(s));
        Put($"%PDF-1.4\n%{marker}\n");
        var offsets = new List<long>();
        void Obj(string body)
        {
            offsets.Add(pdf.Position);
            Put($"{offsets.Count} 0 obj\n{body}\nendobj\n");
        }
        Obj("<< /Type /Catalog /Pages 2 0 R >>");
        Obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        Obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 400] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>");
        offsets.Add(pdf.Position);
        Put($"4 0 obj\n<< /Length {stream.Length} /Filter /FlateDecode >>\nstream\n");
        pdf.Write(stream);
        Put("\nendstream\nendobj\n");
        Obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        Obj($"<< /Title <{hexTitle}> >>");
        var xref = pdf.Position;
        Put($"xref\n0 {offsets.Count + 1}\n0000000000 65535 f \n");
        foreach (var o in offsets) Put($"{o:D10} 00000 n \n");
        Put($"trailer\n<< /Size {offsets.Count + 1} /Root 1 0 R /Info 6 0 R >>\nstartxref\n{xref}\n%%EOF\n");
        return (pdf.ToArray(), stream);
    }

    private static async Task<bool> Within(Task<bool> task, int seconds) =>
        await Task.WhenAny(task, Task.Delay(TimeSpan.FromSeconds(seconds))) == task && task.Result;

    /// <summary>A click at (x, y) in the view, as a person's mouse would make it (DevTools' Input domain).</summary>
    private static async Task Click(CoreWebView2 core, int x, int y)
    {
        foreach (var type in new[] { "mousePressed", "mouseReleased" })
        {
            await core.CallDevToolsProtocolMethodAsync("Input.dispatchMouseEvent",
                $"{{\"type\":\"{type}\",\"x\":{x},\"y\":{y},\"button\":\"left\",\"clickCount\":1}}");
        }
    }

    /// <summary>Ctrl+<paramref name="key"/> pressed in the view, carrying the editing <paramref name="command"/> it is.</summary>
    private static async Task Keys(CoreWebView2 core, char key, string command)
    {
        var lower = char.ToLowerInvariant(key);
        await core.CallDevToolsProtocolMethodAsync("Input.dispatchKeyEvent",
            $"{{\"type\":\"rawKeyDown\",\"modifiers\":2,\"windowsVirtualKeyCode\":{(int)key},\"key\":\"{lower}\",\"code\":\"Key{key}\",\"commands\":[\"{command}\"]}}");
        await core.CallDevToolsProtocolMethodAsync("Input.dispatchKeyEvent",
            $"{{\"type\":\"keyUp\",\"modifiers\":2,\"windowsVirtualKeyCode\":{(int)key},\"key\":\"{lower}\",\"code\":\"Key{key}\"}}");
        await Task.Delay(300);
    }

    /// <summary>The controller closed and its browser's processes gone.</summary>
    private static async Task Close(CoreWebView2Environment environment, CoreWebView2Controller controller)
    {
        var exited = new TaskCompletionSource<bool>();
        environment.BrowserProcessExited += (_, _) => exited.TrySetResult(true);
        controller.Close();
        Assert.IsTrue(await Within(exited.Task, 30), "the browser did not exit");
    }

    private static IEnumerable<string> Files(string root, DateTime since)
    {
        var options = new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true, AttributesToSkip = FileAttributes.ReparsePoint };
        if (!Directory.Exists(root)) return Array.Empty<string>();
        return new DirectoryInfo(root).EnumerateFiles("*", options)
            .Where(f => f.LastWriteTimeUtc >= since || f.CreationTimeUtc >= since)
            .Where(f => f.Length < 512L * 1024 * 1024)
            .Select(f => f.FullName);
    }

    private static byte[]? Read(string file)
    {
        try
        {
            using var s = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            var bytes = new byte[s.Length];
            s.ReadExactly(bytes);
            return bytes;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>A plain top-level window of the predefined STATIC class: the controller's parent.</summary>
    internal sealed class Window : IDisposable
    {
        public Window()
        {
            Handle = CreateWindowExW(0, "STATIC", "veyq pdf test", 0x10CF0000 /* WS_OVERLAPPEDWINDOW | WS_VISIBLE */,
                0, 0, 920, 760, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
            Error = Marshal.GetLastWin32Error();
        }

        public IntPtr Handle { get; }

        public int Error { get; }

        public void Dispose() => DestroyWindow(Handle);

        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateWindowExW(int exStyle, string className, string name, uint style, int x, int y, int w, int h,
            IntPtr parent, IntPtr menu, IntPtr instance, IntPtr param);

        [DllImport("user32.dll")]
        private static extern bool DestroyWindow(IntPtr hwnd);
    }

    /// <summary>The clipboard read and emptied through Win32 (CF_UNICODETEXT).</summary>
    private static class Clipboard
    {
        public static string? Text(IntPtr owner)
        {
            if (!OpenClipboard(owner)) return null;
            try
            {
                var handle = GetClipboardData(13);
                if (handle == IntPtr.Zero) return null;
                var p = GlobalLock(handle);
                try
                {
                    return Marshal.PtrToStringUni(p);
                }
                finally
                {
                    GlobalUnlock(handle);
                }
            }
            finally
            {
                CloseClipboard();
            }
        }

        public static void Clear(IntPtr owner)
        {
            if (!OpenClipboard(owner)) return;
            EmptyClipboard();
            CloseClipboard();
        }

        [DllImport("user32.dll", SetLastError = true)] private static extern bool OpenClipboard(IntPtr owner);
        [DllImport("user32.dll")] private static extern bool CloseClipboard();
        [DllImport("user32.dll")] private static extern bool EmptyClipboard();
        [DllImport("user32.dll")] private static extern IntPtr GetClipboardData(uint format);
        [DllImport("kernel32.dll")] private static extern IntPtr GlobalLock(IntPtr h);
        [DllImport("kernel32.dll")] private static extern bool GlobalUnlock(IntPtr h);
    }

    /// <summary>An STA thread with a Win32 message loop and a synchronization context posting into it: what WebView2's
    /// controller and its events need, without a UI framework.</summary>
    internal sealed class Sta : SynchronizationContext
    {
        private const uint WmApp = 0x8000;
        private readonly ConcurrentQueue<(SendOrPostCallback, object?)> _queue = new();
        private readonly uint _thread;

        private Sta(uint thread) => _thread = thread;

        public override void Post(SendOrPostCallback d, object? state)
        {
            _queue.Enqueue((d, state));
            PostThreadMessageW(_thread, WmApp, IntPtr.Zero, IntPtr.Zero);
        }

        public override void Send(SendOrPostCallback d, object? state) => throw new NotSupportedException();

        public static T Run<T>(Func<Task<T>> body)
        {
            T result = default!;
            Exception? error = null;
            var thread = new Thread(() =>
            {
                var sta = new Sta(GetCurrentThreadId());
                SetSynchronizationContext(sta);
                var task = body();
                while (!task.IsCompleted)
                {
                    while (sta._queue.TryDequeue(out var item)) item.Item1(item.Item2);
                    if (task.IsCompleted) break;
                    MsgWaitForMultipleObjectsEx(0, IntPtr.Zero, 50, 0x04FF /* QS_ALLINPUT */, 0x0004 /* MWMO_INPUTAVAILABLE */);
                    while (PeekMessageW(out var msg, IntPtr.Zero, 0, 0, 1))
                    {
                        TranslateMessage(ref msg);
                        DispatchMessageW(ref msg);
                    }
                }
                if (task.IsFaulted) error = task.Exception!.InnerException;
                else result = task.Result;
            });
            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
            thread.Join();
            if (error is not null) System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(error).Throw();
            return result;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct Msg
        {
            public IntPtr Hwnd;
            public uint Message;
            public IntPtr WParam;
            public IntPtr LParam;
            public uint Time;
            public int X;
            public int Y;
        }

        [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
        [DllImport("user32.dll")] private static extern bool PostThreadMessageW(uint thread, uint msg, IntPtr w, IntPtr l);
        [DllImport("user32.dll")] private static extern bool PeekMessageW(out Msg msg, IntPtr hwnd, uint min, uint max, uint remove);
        [DllImport("user32.dll")] private static extern bool TranslateMessage(ref Msg msg);
        [DllImport("user32.dll")] private static extern IntPtr DispatchMessageW(ref Msg msg);
        [DllImport("user32.dll")] private static extern uint MsgWaitForMultipleObjectsEx(uint count, IntPtr handles, uint ms, uint wake, uint flags);
    }
}
