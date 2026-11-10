// The window and its faces. Today one: what shows while the core opens, refuses, or says where its session is
// (Phase 3 of cr-the-windows-app-over-the-native-core; the sign-in and the shell are Phase 4's).
using System;
using System.IO;
using Microsoft.UI.Xaml;
using Veyq.Bridge;
using Veyq.Bridge.Wire;

namespace Veyq;

public sealed partial class MainWindow : Window
{
    private Watch? _session;

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
        switch (Core.Start(new Config { device = Environment.MachineName }, store))
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
        _session = Core.Watch(new Watched.Session(), Show, e => Log.Write(e.Message));
        if (_session is null)
        {
            Log.Write("the core refused to show the session");
            return;
        }
        var answer = await Core.CallAsync(new Call.SessionStart());
        if (answer is not Answer.Done) Log.Write($"the start answered {answer.GetType().Name}");
    }

    private void Show(Update update)
    {
        if (update is not Update.Session { gate: var gate }) return;
        Log.Write($"session {gate.GetType().Name}");
        Status.Text = gate switch
        {
            Gate.Starting => Words.Starting(),
            Gate.SignIn => Words.SigninTitle(),
            _ => Status.Text,
        };
    }
}
