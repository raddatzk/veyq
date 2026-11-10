// The app's log (cr-the-windows-app-over-the-native-core D9): what the core said it could not do, the session's gates by
// kind (never their fields) and every unhandled exception, in %LOCALAPPDATA%\veyq\log.txt, begun anew at each start.
// windows/run.py fetches it before anything on screen is believed.
using System;
using System.Globalization;
using System.IO;

namespace Veyq;

internal static class Log
{
    private static readonly object Lock = new();
    private static string? _file;

    public static void Start()
    {
        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "veyq");
        Directory.CreateDirectory(dir);
        _file = Path.Combine(dir, "log.txt");
        File.WriteAllText(_file, "");
        Write("started");
    }

    public static void Write(string line)
    {
        lock (Lock)
        {
            if (_file is null) return;
            try
            {
                File.AppendAllText(_file, $"{DateTime.Now.ToString("O", CultureInfo.InvariantCulture)} {line}{Environment.NewLine}");
            }
            catch (IOException)
            {
                // the log is a witness, never a reason to stop
            }
        }
    }
}
