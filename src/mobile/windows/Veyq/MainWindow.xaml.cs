// The window and its faces (ADR 0123's Consequences, the Windows app): what shows while the core opens or
// refuses, the sign-in, the unlock, or the shell (with "Manage servers" over it) — the screen the core's session says. Each screen draws the core's state and calls
// its actions; nothing else. Linux's main.rs, in WinUI.
using System;
using System.IO;
using System.Linq;
using Microsoft.UI.Xaml;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

public sealed partial class MainWindow : Window
{
    private Watch? _session;
    private Watch? _shellWatch;
    private Watch? _manageWatch;
    private SignInView? _signIn;
    private UnlockView? _unlock;
    private ManageDialog? _manage;
    private ShellView? _shell;

    public MainWindow()
    {
        InitializeComponent();
        Title = Words.AppName();
        // the title bar's and the taskbar's icon: the exe's own (ApplicationIcon) is not the window's in WinUI 3
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "veyq.ico"));
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
            // the window owns Windows Hello's prompt, in the app's words (the CR device-trust-on-linux-and-windows, D1)
            var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this).ToInt64();
            started = Core.Start(new Config { device = Environment.MachineName, window = hwnd, prompt = Words.DeviceUnlockPrompt() }, store);
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
        _manageWatch = Core.Watch(new Watched.Manage(), ManageUpdate, e => Log.Write(e.Message));
        if (_session is null || _shellWatch is null || _manageWatch is null)
        {
            Log.Write("the core refused to show the session, the shell or Manage servers");
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
        if (update is not Update.Session { gate: var gate, staySignedIn: var stay }) return;
        Log.Write($"session {gate.GetType().Name}{(stay is { } s ? $", stay signed in: {s}" : "")}");
        if (gate is not Gate.Signed) _manage?.Close();
        switch (gate)
        {
            case Gate.Starting:
                Status.Text = Words.Starting();
                Face(null);
                break;
            case Gate.SignIn signIn:
                _signIn ??= new SignInView();
                _signIn.Show(signIn, stay);
                Face(_signIn.Page);
                if (!signIn.working && signIn.error is null && DevLogin.Take() is { } dev) _signIn.Dev(dev);
                break;
            case Gate.Unlock unlock:
                _unlock ??= new UnlockView();
                _unlock.Show(unlock, stay);
                Face(_unlock.Page);
                if (unlock.record.method == Method.Device && !unlock.working && unlock.error is null && DevLogin.TakeUnlock() is { } password)
                {
                    Act.Send(new Call.SessionUnlock { password = password });
                }
                break;
            case Gate.Signed signed:
                _shell ??= NewShell();
                _shell.Switching(signed.switching);
                Face(_shell.Page);
                break;
            default:
                // only the share extension reaches it (NeedsApp, ADR 0129), which this app has none of: said in the log,
                // the window stays where it is
                Log.Write($"the session is at a gate this slice does not draw: {gate.GetType().Name}");
                break;
        }
    }

    private void ShellUpdate(Update update)
    {
        if (update is not Update.Shell { state: { } state, shown: { } shown }) return;
        _shell ??= NewShell();
        _shell.Show(state, shown);
    }

    private ShellView NewShell()
    {
        _manage ??= new ManageDialog(this);
        var manage = _manage;
        return new ShellView(this, manage.Open);
    }

    private void ManageUpdate(Update update)
    {
        if (update is not Update.Manage { servers: var servers }) return;
        // what this device trusts, for the pass's log: each server's method, no name of a person
        if (servers is not null) Log.Write($"manage servers: {string.Join(", ", servers.Select(s => s.trusted?.ToString() ?? "None"))}");
        _manage?.Show(servers);
    }
}
