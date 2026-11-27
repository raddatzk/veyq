// The window and its faces (ADR 0123's Consequences, the Windows app): what shows while the core opens or
// refuses, the sign-in, the unlock, or the shell (with "Manage servers" over it) — the screen the core's session says. Each screen draws the core's state and calls
// its actions; nothing else. Linux's main.rs, in WinUI. A received link opened by *Open a link* (ReceivedView, ADR 0150
// decision 4) stands above whichever face shows until it is let go; the face the session's gate gives meanwhile comes back
// then. A received invite's join (JoinView) stands there the same way.
using System;
using System.IO;
using System.Linq;
using Microsoft.UI.Xaml;
using Veyq.Bridge;
using Veyq.Bridge.Wire;
using Veyq.Core;

namespace Veyq;

public sealed partial class MainWindow : Window
{
    private Watch? _session;
    private Watch? _shellWatch;
    private Watch? _manageWatch;
    private SignInView? _signIn;
    private UnlockView? _unlock;
    private LockedView? _locked;
    private RecoverView? _recover;
    private OfferView? _offer;
    private TermsGateView? _terms;
    private ManageDialog? _manage;
    private ShellView? _shell;

    /// <summary>The shell's Settings at <paramref name="section"/> (null: its top), for a link that named it (inbox #1415).</summary>
    internal void OpenSettingsAt(string? section) => _shell?.OpenSettingsFromLink(section);
    /// <summary>A received link's screen while one stands above the faces, and the face it covers (what the session's gate
    /// shows meanwhile), put back once the link is let go.</summary>
    private ReceivedView? _received;
    /// <summary>A received invite's join while it stands above the faces (JoinView), as a received link does.</summary>
    private JoinView? _join;
    private UIElement? _behind;

    public MainWindow()
    {
        InitializeComponent();
        Title = Words.AppName();
        // the title bar's and the taskbar's icon: the exe's own (ApplicationIcon) is not the window's in WinUI 3. The ARM64
        // test VM's taskbar shows the system's blank window for every emulated x86/x64 program, Windows' own charmap
        // too, this one with its icon in both the exe and the window (inbox #1413) - not a defect here
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "veyq.ico"));
        Root.RequestedTheme = DebugEnv.Theme;
        // a screen that can show a secret keeps this window out of capture while it shows (ScreenSecrets)
        ScreenSecrets.Attach(this);
        Heading.Text = Words.AppName();
        Status.Text = Words.Starting();
        // an opened link stands above whatever shows (*Open a link*: the sign-in, the unlock, a kept link's page)
        ReceivedView.Presenter = PresentReceived;
        // a received invite's join stands above whatever shows, as a link does
        JoinView.Presenter = PresentJoin;
        // the window closed lets go of a link or a join still open
        Closed += (_, _) =>
        {
            _received?.Dispose();
            _join?.Dispose();
        };
    }

    /// <summary>Received link <paramref name="received"/> as the window's face until it is let go; another link opened over
    /// it replaces it (the old one let go).</summary>
    private void PresentReceived(long received, string? browser)
    {
        var joined = _join;
        if (joined is not null)
        {
            _join = null;
            joined.Dispose();
        }
        if (_received is { } old) old.Dispose();
        else if (joined is null) _behind = Faces.Visibility == Visibility.Visible ? Faces.Content as UIElement : null;
        ReceivedView? view = null;
        view = new ReceivedView(this, received, ReceivedMode.OnItsOwn, browser, () =>
        {
            // on a later turn: not inside the view's own update
            DispatcherQueue.TryEnqueue(() =>
            {
                if (!ReferenceEquals(_received, view)) return;
                _received = null;
                view?.Dispose();
                Log.Write("received link left");
                Show(_behind);
                _behind = null;
            });
        });
        _received = view;
        Log.Write("received link shown");
        Show(view.Page);
    }

    /// <summary>Join <paramref name="join"/> as the window's face until it is let go; one over a link or another join
    /// replaces it (the old one let go).</summary>
    private void PresentJoin(long join)
    {
        var covering = _received is not null || _join is not null;
        if (_received is { } received)
        {
            _received = null;
            received.Dispose();
        }
        if (_join is { } old)
        {
            _join = null;
            old.Dispose();
        }
        if (!covering) _behind = Faces.Visibility == Visibility.Visible ? Faces.Content as UIElement : null;
        JoinView? view = null;
        view = new JoinView(this, join, () =>
        {
            // on a later turn: not inside the view's own update
            DispatcherQueue.TryEnqueue(() =>
            {
                if (!ReferenceEquals(_join, view)) return;
                _join = null;
                Log.Write("join left");
                Show(_behind);
                _behind = null;
            });
        });
        _join = view;
        Log.Write("join shown");
        Show(view.Page);
    }

    /// <summary>Starts the core with this user's device store and shows its session from then on.</summary>
    public async void StartCore()
    {
        DeviceStore store;
        try
        {
            store = DeviceStore.OpenIn(AppData.Folder);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.Text.Json.JsonException)
        {
            Log.Write($"the device store did not open ({e.GetType().Name}); this start keeps nothing");
            store = DeviceStore.InMemory();
        }
        Start started;
        // an Emergency Kit's pages and pictures are drawn by Windows' own renderer when the core asks (KitDrawing), a picked
        // medium's tiers by WIC and Windows.Media.Editing (MediaTiers), one's own profile picture framed square (ProfilePicture)
        Bridge.Core.Drawer = request => KitDrawing.Answer(request) ?? ProfilePicture.Answer(request) ?? MediaTiers.Answer(request) ?? MusicHost.Words(request);
        // the music's two decks and the system's controls play on this window's thread (MusicHost, ADR 0142 decisions 2 and 3)
        MusicHost.Start(DispatcherQueue, WinRT.Interop.WindowNative.GetWindowHandle(this));
        try
        {
            // the window owns Windows Hello's prompt, in the app's words (ADR 0161's Consequences)
            var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this).ToInt64();
            // the accounts' offline stores in the data folder this process has (ADR 0140 decision 3: the package's under
            // the MSIX, %LOCALAPPDATA%\veyq or a pass's VEYQ_DATA_DIR unpackaged); none where it cannot be made — then nothing is kept offline
            string? offline = null;
            try
            {
                offline = DataFolder.Offline(AppData.Folder);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                Log.Write($"the data folder did not open ({e.GetType().Name}); nothing is kept offline");
            }
            // a debug build logs every request to an outside host into log.txt (inbox #947, Android's `veyq-net`); a release
            // build asks the core for none
            var netLog = false;
#if DEBUG
            netLog = true;
            Bridge.Core.NetLogged = line => Log.Write($"veyq-net {line}");
#endif
            started = Bridge.Core.Start(new Config { device = Environment.MachineName, window = hwnd, prompt = Words.DeviceUnlockPrompt(), offlineRoot = offline, language = Words.Language(), netLog = netLog }, store);
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
        // what closing the window asks about: every upload under way, a tenant open or not (ADR 0145 decision 2, #1016)
        Uploads.WatchUnderway();
        // a debug build's QR harness: the camera alone, no session (the QR CR, phase 5)
        if (DevScan.Requested)
        {
            var scan = DevScan.Open();
            Face(scan.View);
            // closing the window ends the scan and its camera
            Closed += (_, _) => scan.Dispose();
            return;
        }
        // a debug build's Settings harness: the page over a state of its own, no session (uc.account.sessions)
        if (DevSettings.Requested)
        {
            Log.Write("the settings harness (debug build)");
            Face(AccountSettingsPage.Showing(this, DevSettings.Fake()).Page);
            return;
        }
        // how long the core's notices live, its words' kinds and its separator: read before anything is said
        await Lives.Load();
        _session =Bridge.Core.Watch(new Watched.Session(), ShowGate, e => Log.Write(e.Message));
        _shellWatch = Bridge.Core.Watch(new Watched.Shell(), ShellUpdate, e => Log.Write(e.Message));
        _manageWatch = Bridge.Core.Watch(new Watched.Manage(), ManageUpdate, e => Log.Write(e.Message));
        if (_session is null || _shellWatch is null || _manageWatch is null)
        {
            Log.Write("the core refused to show the session, the shell or Manage servers");
            return;
        }
        // the way in, once per process: the trusted accounts resume, or the sign-in shows
        try
        {
            var (answer, _) = await Bridge.Core.CallAsync(new Call.SessionStart());
            if (answer is not Answer.Done) Log.Write($"the start answered {answer.GetType().Name}");
        }
        catch (UnreadableException e)
        {
            Log.Write($"the start: {e.Message}");
        }
        // a veyq: link that started the app (or came while it started) is answered over the face the start showed
        DeepLinks.Ready(this);
    }

    private void Face(UIElement? face)
    {
        // a received link or a join stands above: the face waits behind it
        if (_received is not null || _join is not null)
        {
            _behind = face;
            return;
        }
        Show(face);
    }

    private void Show(UIElement? face)
    {
        Opening.Visibility = face is null ? Visibility.Visible : Visibility.Collapsed;
        Faces.Visibility = face is null ? Visibility.Collapsed : Visibility.Visible;
        if (!ReferenceEquals(Faces.Content, face)) Faces.Content = face;
    }

    /// <summary>What the log says of the trust beside a gate: the offer a sign-in left open, a remembered account's level
    /// at its unlock.</summary>
    private static string TrustNote(Gate gate) => gate switch
    {
        Gate.Signed { terms: { } terms } => $", terms: r{terms.shown.revision}",
        Gate.Signed { offer: { } offer } => $", offer: {string.Join(" ", offer.levels)}",
        Gate.Unlock { record.level: { } level } => $", level: {level}",
        _ => "",
    };

    private void ShowGate(Update update)
    {
        if (update is not Update.Session { gate: var gate }) return;
        Log.Write($"session {gate.GetType().Name}{TrustNote(gate)}");
        if (gate is not Gate.Signed) _manage?.Close();
        if (gate is not Gate.SignIn) _signIn?.Forget();
        switch (gate)
        {
            case Gate.Starting:
                Status.Text = Words.Starting();
                Face(null);
                break;
            case Gate.SignIn signIn:
                _signIn ??= new SignInView(this);
                _signIn.Show(signIn);
                Face(_signIn.Page);
                if (!signIn.working && signIn.error is null && DevLogin.Take() is { } dev) _signIn.Dev(dev);
                break;
            case Gate.Locked locked:
                _locked ??= new LockedView(this);
                _locked.Show(locked);
                Face(_locked.Page);
                break;
            case Gate.Recover recover:
                _recover ??= new RecoverView();
                _recover.Show(recover);
                Face(_recover.Page);
                break;
            case Gate.Unlock unlock:
                _unlock ??= new UnlockView(this);
                _unlock.Show(unlock);
                Face(_unlock.Page);
                if (unlock.record.method == Method.Device && !unlock.working && unlock.error is null && DevLogin.TakeUnlock() is { } password)
                {
                    Act.Send(new Call.SessionUnlock { password = password });
                }
                break;
            // the tenant's terms gate stands in place of the shell, the offer behind it (uc.account.accept-terms)
            case Gate.Signed { terms: { } terms } signedWithTerms:
                _terms ??= new TermsGateView();
                _terms.Show(terms, signedWithTerms.connectionKey);
                Face(_terms.Page);
                break;
            // the offer to stay signed in stands over the shell until it is answered (ADR 0123 decision 8)
            case Gate.Signed { offer: { } offer }:
                _offer ??= new OfferView(said => (_shell ??= NewShell()).Say(said));
                _offer.Show(offer);
                Face(_offer.Page);
                break;
            case Gate.Signed signed:
                _shell ??= NewShell();
                _shell.Switching(signed.switching);
                Face(_shell.Page);
                break;
            default:
                // only the share extension reaches NeedsApp (ADR 0138), which this app has none of, and only a TV's session
                // the Picker (ADR 0159): said in the log, the window stays where it is
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
