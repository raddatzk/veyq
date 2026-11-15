// A secret on screen stays out of what Windows captures of the window (inbox #886; src/mobile/CLAUDE.md § Secrets on
// screen): while an element that can show one is loaded — the sign-in (its Secret Key and password, PasswordBox's peek
// shows either in clear, and the kit scanner's camera shows the kit's code), the unlock's password and the password
// asked by Manage and Settings —, the window is excluded from capture (SetWindowDisplayAffinity, WDA_EXCLUDEFROMCAPTURE):
// Recall's snapshots, which Windows writes to disk on its own, and every screenshot or recording through the
// compositor see the desktop behind it. Electron's setContentProtection and Signal's screen security are the same call.
// The one way in for every screen: ScreenSecrets.Guard(element). A debug build started with VEYQ_DEV_CAPTURABLE=1 keeps
// the window capturable, for a pass's shots of these screens.
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using Microsoft.UI.Xaml;

namespace Veyq;

internal static partial class ScreenSecrets
{
    private const uint WdaNone = 0x0;
    private const uint WdaExcludeFromCapture = 0x11;

    private static nint _window;
    private static readonly HashSet<FrameworkElement> Shown = [];

    /// <summary>The app's one window: what the guards exclude.</summary>
    public static void Attach(Window window) => _window = WinRT.Interop.WindowNative.GetWindowHandle(window);

    /// <summary>The window is kept out of capture while <paramref name="element"/> is loaded; returns it.</summary>
    public static T Guard<T>(T element) where T : FrameworkElement
    {
        element.Loaded += (_, _) => { if (Shown.Add(element)) Apply(); };
        // WinUI may raise a move's Unloaded after its new Loaded (measured 2026-11-15: a ContentDialog's content loaded,
        // then unloaded 13 ms later while it stayed on screen, and the window became capturable with the pairing code
        // in it): an element that is loaded again keeps the window out of capture
        element.Unloaded += (_, _) => { if (!element.IsLoaded && Shown.Remove(element)) Apply(); };
        return element;
    }

    private static bool _excluded;

    private static void Apply()
    {
        var exclude = Shown.Count > 0 && !Capturable;
        if (exclude == _excluded || _window == 0) return;
        if (SetWindowDisplayAffinity(_window, exclude ? WdaExcludeFromCapture : WdaNone))
        {
            _excluded = exclude;
            Log.Write(exclude ? "the window is out of capture: a secret is on screen" : "the window is capturable again");
        }
        else
        {
            Log.Write($"the window's capture could not be changed (error {Marshal.GetLastPInvokeError()})");
        }
    }

#if DEBUG
    private static bool Capturable { get; } = Environment.GetEnvironmentVariable("VEYQ_DEV_CAPTURABLE") == "1";
#else
    private static bool Capturable => false;
#endif

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetWindowDisplayAffinity(nint hwnd, uint affinity);
}
