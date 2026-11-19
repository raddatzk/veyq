// The process's entry in place of WinUI's generated Main (DISABLE_XAML_GENERATED_MAIN, Veyq.csproj), for one instance of
// the packaged app (ADR 0150 § O3, cr-open-in-the-app phase 6): a `veyq:` link opened while the app runs starts a second
// process, which hands its activation to the running one (AppInstance's key "main") and exits; the running one shows
// what the link asks (DeepLinks). Only with package identity: the MSIX is what claims `veyq:` (Package.appxmanifest.in),
// and the unpackaged development loop keeps starting as many windows as it is asked for, each pass beside another its own.
// The activation's URI is handed on as the system gave it, never read here, logged or kept.
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;

namespace Veyq;

public static partial class Program
{
    [STAThread]
    private static int Main()
    {
        WinRT.ComWrappersSupport.InitializeComWrappers();
        AppInstance? main = null;
        if (AppData.Packaged)
        {
            main = AppInstance.FindOrRegisterForKey("main");
            if (!main.IsCurrent)
            {
                HandOn(main);
                return 0;
            }
        }
        Application.Start(start =>
        {
            var ui = DispatcherQueue.GetForCurrentThread();
            SynchronizationContext.SetSynchronizationContext(new DispatcherQueueSynchronizationContext(ui));
            // a second activation (a veyq: link opened while this one runs) arrives on another thread: on to the UI's
            if (main is not null) main.Activated += (_, args) => ui.TryEnqueue(() => DeepLinks.Activated(args));
            _ = new App();
        });
        return 0;
    }

    /// <summary>This process's activation handed to the running instance <paramref name="main"/>, which may then take the
    /// foreground (this one was started by the shell, which may give it); waited for without blocking COM on this STA
    /// thread (the Windows App SDK's own redirection sample's way).</summary>
    private static void HandOn(AppInstance main)
    {
        var args = AppInstance.GetCurrent().GetActivatedEventArgs();
        AllowSetForegroundWindow((uint)main.ProcessId);
        var done = CreateEventW(IntPtr.Zero, true, false, IntPtr.Zero);
        Task.Run(() =>
        {
            try
            {
                main.RedirectActivationToAsync(args).AsTask().Wait();
            }
            finally
            {
                SetEvent(done);
            }
        });
        _ = CoWaitForMultipleObjects(0, uint.MaxValue, 1, [done], out _);
        CloseHandle(done);
        // the running window in front, from the process the shell started (a no-op where it was given no foreground)
        try
        {
            using var running = Process.GetProcessById((int)main.ProcessId);
            if (running.MainWindowHandle != IntPtr.Zero) SetForegroundWindow(running.MainWindowHandle);
        }
        catch (ArgumentException)
        {
            // it ended meanwhile
        }
    }

    [LibraryImport("kernel32.dll", SetLastError = true)]
    private static partial IntPtr CreateEventW(IntPtr attributes, [MarshalAs(UnmanagedType.Bool)] bool manualReset,
        [MarshalAs(UnmanagedType.Bool)] bool initialState, IntPtr name);

    [LibraryImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetEvent(IntPtr handle);

    [LibraryImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool CloseHandle(IntPtr handle);

    [LibraryImport("ole32.dll")]
    private static partial int CoWaitForMultipleObjects(uint flags, uint timeout, uint count, IntPtr[] handles, out uint index);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool AllowSetForegroundWindow(uint processId);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetForegroundWindow(IntPtr window);
}
