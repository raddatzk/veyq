// The app's log (ADR 0123's Consequences, the Windows app): what the core said it could not do, the session's gates by
// kind (never their fields) and every unhandled exception, in log.txt in the app's data folder (AppData: the package's
// LocalState when packaged, %LOCALAPPDATA%\veyq unpackaged), begun anew at each start. windows/run.py fetches it before
// anything on screen is believed.

namespace Veyq.Core;

using System;
using System.Globalization;
using System.IO;

internal static class Log
{
    private static readonly object Lock = new();
    private static string? _file;

    public static void Start(string dir)
    {
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
