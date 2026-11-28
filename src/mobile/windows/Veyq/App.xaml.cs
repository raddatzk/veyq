// The veyq app on Windows (ADR 0161 decision 5; ADR 0123's Consequences, the Windows app): the window, the core started
// behind it, and what its session says. Each screen draws the core's state and calls its actions; nothing else. Its
// entry is Program.cs (one instance of the packaged app); a veyq: link is DeepLinks'.
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Veyq.Bridge;
using Veyq.Core;

namespace Veyq;

public partial class App : Application
{
    private MainWindow? _window;

    public App()
    {
        Log.Start(AppData.Folder);
        Log.Write(AppData.Packaged ? "data in the package's own folder" : $"data in {AppData.Folder} (unpackaged)");
        UnhandledException += (_, e) => Log.Write($"unhandled exception: {e.Exception}");
        System.AppDomain.CurrentDomain.UnhandledException += (_, e) => Log.Write($"unhandled exception: {e.ExceptionObject}");
        TaskScheduler.UnobservedTaskException += (_, e) => Log.Write($"unobserved task exception: {e.Exception}");
        // an update's handler that throws reaches none of the three above (WinUI's SynchronizationContext, inbox #1429)
        Bridge.Core.Faulted = e => Log.Write($"an update's handler threw: {e}");
        InitializeComponent();
        // the whole app's, not only the window's: a brush a screen looks up in code (a caption's secondary text) is the
        // application theme's, and under the window's theme alone it stayed light's on a dark window (measured)
        if (Appearance.Theme != ElementTheme.Default) RequestedTheme = Appearance.Theme == ElementTheme.Dark ? ApplicationTheme.Dark : ApplicationTheme.Light;
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        Words.Lookup = MrtWords.Open(null, Appearance.Language);
        // every application registered into the core UI before anything is drawn (the composition root)
        Applications.Register();
        _window = new MainWindow();
        AskBeforeClosing(_window);
        _window.Closed += (_, _) => Bridge.Core.Shutdown();
        _window.Activate();
        _window.StartCore();
        // the activation that started this process: a veyq: link waits for the core (DeepLinks, ADR 0150 § O3)
        DeepLinks.Launched();
    }

    /// <summary>The person chose "Quit anyway": the next close goes through.</summary>
    private bool _quitting;

    /// <summary>Closing the window while files still upload asks first (ADR 0145 decision 2): "Keep uploading", the
    /// default, keeps it open; "Quit anyway" closes it, which ends the app and its uploads. A minimized window keeps
    /// uploading.</summary>
    private void AskBeforeClosing(Window window)
    {
        window.AppWindow.Closing += async (_, args) =>
        {
            var files = Uploads.Active;
            if (files == 0 || _quitting) return;
            args.Cancel = true;
            Log.Write($"closing asked: {files} uploads under way");
            var dialog = new Microsoft.UI.Xaml.Controls.ContentDialog
            {
                Title = Words.UploadQuitTitle(),
                Content = Words.UploadQuitBody(files),
                PrimaryButtonText = Words.UploadQuitKeep(),
                SecondaryButtonText = Words.UploadQuitAnyway(),
                DefaultButton = Microsoft.UI.Xaml.Controls.ContentDialogButton.Primary,
            };
            var answer = await Dialogs.Show(window, dialog);
            Log.Write($"closing answered: {answer}");
            if (answer == Microsoft.UI.Xaml.Controls.ContentDialogResult.Secondary)
            {
                _quitting = true;
                window.Close();
            }
        };
    }
}
