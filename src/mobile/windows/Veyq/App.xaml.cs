// The veyq app on Windows (ADR 0127 decision 5; cr-the-windows-app-over-the-native-core): the window, the core started
// behind it, and what its session says. Each screen draws the core's state and calls its actions; nothing else.
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Veyq.Bridge;

namespace Veyq;

public partial class App : Application
{
    private MainWindow? _window;

    public App()
    {
        Log.Start();
        UnhandledException += (_, e) => Log.Write($"unhandled exception: {e.Exception}");
        System.AppDomain.CurrentDomain.UnhandledException += (_, e) => Log.Write($"unhandled exception: {e.ExceptionObject}");
        TaskScheduler.UnobservedTaskException += (_, e) => Log.Write($"unobserved task exception: {e.Exception}");
        InitializeComponent();
        // the whole app's, not only the window's: a brush a screen looks up in code (a caption's secondary text) is the
        // application theme's, and under the window's theme alone it stayed light's on a dark window (measured)
        if (DebugEnv.Theme != ElementTheme.Default) RequestedTheme = DebugEnv.Theme == ElementTheme.Dark ? ApplicationTheme.Dark : ApplicationTheme.Light;
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        Words.Lookup = MrtWords.Open(null, DebugEnv.Language);
        _window = new MainWindow();
        _window.Closed += (_, _) => Core.Shutdown();
        _window.Activate();
        _window.StartCore();
    }
}
