// A `veyq:` link the system handed the app (ADR 0150 § O) — Linux's main.rs `open`, in
// WinUI. The MSIX claims the scheme (Package.appxmanifest.in); the first activation arrives at the start (App.OnLaunched),
// every later one at the running instance (Program.cs's AppInstance redirect). The core reads the URI's host alone
// (Call.DeepLink): `veyq://link` shows *Open a link* over whatever stands — the sign-in, the unlock, the open tenant, a
// received link's screen — unless it already shows (what was typed stays); `veyq://open` with a place the web named moves
// the open shell there in the core (or once a shell opens), a Settings section opening its page here (inbox #1415); anything
// else brings the window to the front and nothing more. The URI is never logged, kept or shown: it waits in memory only
// until the core has started, and the log names the screen alone.

namespace Veyq.Core;

using System;
using Microsoft.UI.Windowing;
using Microsoft.Windows.AppLifecycle;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Windows.ApplicationModel.Activation;

internal static class DeepLinks
{
    /// <summary>The window, once its core has started; a link before that waits.</summary>
    private static MainWindow? _window;

    /// <summary>A link that arrived before the core started, in memory until then (the latest one).</summary>
    private static string? _waiting;

    /// <summary>The process's own activation (the first instance's): a link that started the app.</summary>
    public static void Launched()
    {
        if (AppData.Packaged) Arrived(AppInstance.GetCurrent().GetActivatedEventArgs(), first: true);
    }

    /// <summary>An activation another process handed on (Program.cs), on the UI thread: a link, or the app started again.</summary>
    public static void Activated(AppActivationArguments args) => Arrived(args, first: false);

    /// <summary>The window's core has started: a link that waited for it is answered now.</summary>
    public static void Ready(MainWindow window)
    {
        _window = window;
        if (_waiting is { } uri)
        {
            _waiting = null;
            Answer(uri);
        }
    }

    private static void Arrived(AppActivationArguments args, bool first)
    {
        if (args.Kind != ExtendedActivationKind.Protocol || args.Data is not IProtocolActivatedEventArgs protocol)
        {
            // the app started again from Start or the taskbar: the running window in front
            if (!first && _window is { } window)
            {
                Log.Write("the app was started again: its window in front");
                Front(window);
            }
            return;
        }
        var uri = protocol.Uri.OriginalString;
        if (_window is null) _waiting = uri;
        else Answer(uri);
    }

    private static async void Answer(string uri)
    {
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.DeepLink { uri = uri });
            if (answer is not Answer.DeepLinked { screen: var screen } linked)
            {
                Log.Write($"a veyq: link answered {answer.GetType().Name}");
                return;
            }
            var window = _window!;
            Front(window);
            if (screen == DeepScreen.Settings)
            {
                Log.Write("veyq: link opened Settings");
                window.OpenSettingsAt(linked.section);
                return;
            }
            if (screen == DeepScreen.Place)
            {
                // the shell moved there: Settings, where it showed over the shell, gives way (inbox #1424)
                Log.Write("veyq: link opened its place");
                window.ShowPlace();
                return;
            }
            if (screen != DeepScreen.Link)
            {
                Log.Write("veyq: link opened the app where it is");
                return;
            }
            if (ReceivedView.LinkAsked)
            {
                Log.Write("veyq: link opened Open a link, which already shows");
                return;
            }
            if (Dialogs.Open)
            {
                // WinUI shows one ContentDialog at a time: the open one is the person's to finish first
                Log.Write("veyq: link asked for Open a link while a dialog is open: not shown");
                return;
            }
            Log.Write("veyq: link opened Open a link");
            ReceivedView.OpenLink(window);
        }
        catch (Exception e)
        {
            // the type alone: nothing of the link reaches the log
            Log.Write($"a veyq: link was not answered ({e.GetType().Name})");
        }
    }

    /// <summary>The window restored where it was minimized and activated; the foreground itself is the system's to grant
    /// (the process the link started allowed it, Program.cs).</summary>
    private static void Front(MainWindow window)
    {
        if (window.AppWindow.Presenter is OverlappedPresenter { State: OverlappedPresenterState.Minimized } presenter) presenter.Restore();
        window.Activate();
    }
}
