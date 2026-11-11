// The window and its faces (ADR 0123's Consequences, the Windows app): what shows while the core opens or
// refuses, the sign-in, or the shell — the screen the core's session says. Each screen draws the core's state and calls
// its actions; nothing else. Linux's main.rs, in WinUI.
using System;
using System.IO;
using Microsoft.UI.Xaml;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

public sealed partial class MainWindow : Window
{
    private Watch? _session;
    private Watch? _shellWatch;
    private SignInView? _signIn;
    private ShellView? _shell;

    public MainWindow()
    {
        InitializeComponent();
        Title = Words.AppName();
        Root.RequestedTheme = DebugEnv.Theme;
        Heading.Text = Words.AppName();
        Status.Text = Words.Starting();
    }

    /// <summary>Starts the core with this user's device store and shows its session from then on.</summary>
    public async void StartCore()
    {
        DeviceStore store;
        try
        {
            store = DeviceStore.OpenDefault();
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.Text.Json.JsonException)
        {
            Log.Write($"the device store did not open ({e.GetType().Name}); this start keeps nothing");
            store = DeviceStore.InMemory();
        }
        Start started;
        try
        {
            started = Core.Start(new Config { device = Environment.MachineName }, store);
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException or BadImageFormatException)
        {
            // an exception out of this async void would end the app in combase, with nothing in the log
            Log.Write($"the core's veyq.dll did not load ({e.GetType().Name}: {e.Message})");
            return;
        }
        switch (started)
        {
            case Start.Started or Start.AlreadyStarted:
                break;
            case Start.NoAes:
                Log.Write("the core refused its start: this CPU has no AES-GCM");
                Status.Text = Words.StartNoAes();
                return;
            default:
                Log.Write("the core refused its start");
                return;
        }
        Log.Write("the core started");
        _session = Core.Watch(new Watched.Session(), ShowGate, e => Log.Write(e.Message));
        _shellWatch = Core.Watch(new Watched.Shell(), ShellUpdate, e => Log.Write(e.Message));
        if (_session is null || _shellWatch is null)
        {
            Log.Write("the core refused to show the session or the shell");
            return;
        }
        // the way in, once per process: the trusted accounts resume, or the sign-in shows
        try
        {
            var (answer, _) = await Core.CallAsync(new Call.SessionStart());
            if (answer is not Answer.Done) Log.Write($"the start answered {answer.GetType().Name}");
        }
        catch (UnreadableException e)
        {
            Log.Write($"the start: {e.Message}");
        }
    }

    private void Face(UIElement? face)
    {
        Opening.Visibility = face is null ? Visibility.Visible : Visibility.Collapsed;
        Faces.Visibility = face is null ? Visibility.Collapsed : Visibility.Visible;
        if (!ReferenceEquals(Faces.Content, face)) Faces.Content = face;
    }

    private void ShowGate(Update update)
    {
        if (update is not Update.Session { gate: var gate }) return;
        Log.Write($"session {gate.GetType().Name}");
        switch (gate)
        {
            case Gate.Starting:
                Status.Text = Words.Starting();
                Face(null);
                break;
            case Gate.SignIn signIn:
                if (!signIn.working && signIn.error is null && DevLogin.Take() is { } call) Act.Send(call);
                _signIn ??= new SignInView();
                _signIn.Show(signIn);
                Face(_signIn.Page);
                break;
            case Gate.Signed signed:
                _shell ??= new ShellView(this);
                _shell.Switching(signed.switching);
                Face(_shell.Page);
                break;
            default:
                // neither reaches this slice: no share extension (NeedsApp), no trusted device yet (Unlock); said in the
                // log, the window stays where it is
                Log.Write($"the session is at a gate this slice does not draw: {gate.GetType().Name}");
                break;
        }
    }

    private void ShellUpdate(Update update)
    {
        if (update is not Update.Shell { state: { } state, shown: { } shown }) return;
        _shell ??= new ShellView(this);
        _shell.Show(state, shown);
    }
}
