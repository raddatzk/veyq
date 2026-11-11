// Veyq.Drive (ADR 0123's Consequences, the Windows app). Two modes, each what one scheduled task runs in the logged-on
// session (an ssh command runs in session 0, which has no desktop):
//   --launch <file>   reads the app's start from <file> — {"exe": …, "env": {…}} — deletes it at once, and starts the
//                     app with that environment (a secret in it never reaches a command line). Writes <file>.started.
//   --drive <file>    runs the steps in <file>, one a line, against the app's window, writes what each did to
//                     <file>.out (UTF-8) and then <file>.done. A step that fails ends the run, said in the output.
// Steps:
//   tree                      the window's UIA tree as text: control type, name, AutomationId, value, enabled, bounds;
//                             then each other top-level window of the app (a flyout, a menu, a suggestion list)
//   invoke <id>               the element's Invoke (a button), or its Toggle or ExpandCollapse where it has no Invoke;
//                             the id is the rest of the line (it may hold a name's spaces)
//   select <id>               the element's SelectionItem.Select (a list entry, a tab, a pane item); the id as invoke's
//   set <id> <text>           the element's Value.SetValue (an entry)
//   key <keys>                keys typed into the window, e.g. `ctrl+e`, `enter`, `tab`, `escape`
//   type <id> <text>          the element focused and the text typed into it character by character (a system
//                             dialog's entry, the save picker's file name, which a Value set is not read from)
//   press <id> [keys]         the element focused and keys typed into the window it lies in (`enter` by default):
//                             a system dialog's button (the save picker's Save), which UIA's Invoke cannot press
//   size <w> <h>              the window restored and resized to w × h (in its own pixels)
//   maximize                  the window maximized
//   wait <id> [seconds]       until an element with that AutomationId is there (10 s by default)
//   sleep <ms>                a pause
//   shot <file>               a PNG of the window as it shows on the screen (the proof, not a probe)
// An <id> is an AutomationId, found in any top-level window of the app; a step's words are never typed from anything
// but the steps file.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Threading;

namespace Veyq.Drive;

internal static class Program
{
    private const string AppProcess = "Veyq.App";

    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length == 2 && args[0] == "--launch") return Launch(args[1]);
        if (args.Length == 2 && args[0] == "--drive") return DriveFile(args[1]);
        return 2;
    }

    private static int Launch(string file)
    {
        string text;
        try
        {
            text = File.ReadAllText(file, Encoding.UTF8);
        }
        finally
        {
            File.Delete(file);
        }
        using var start = JsonDocument.Parse(text);
        var info = new ProcessStartInfo(start.RootElement.GetProperty("exe").GetString()!) { UseShellExecute = false };
        info.WorkingDirectory = Path.GetDirectoryName(info.FileName)!;
        if (start.RootElement.TryGetProperty("env", out var env))
        {
            foreach (var v in env.EnumerateObject()) info.Environment[v.Name] = v.Value.GetString();
        }
        using var app = Process.Start(info)!;
        File.WriteAllText(file + ".started", app.Id.ToString(CultureInfo.InvariantCulture));
        return 0;
    }

    private static int DriveFile(string file)
    {
        var output = new StringBuilder();
        var code = 0;
        try
        {
            var window = new AppWindow(AppProcess);
            foreach (var raw in File.ReadAllLines(file, Encoding.UTF8))
            {
                var line = raw.Trim();
                if (line.Length == 0 || line.StartsWith('#')) continue;
                output.Append("> ").AppendLine(line);
                try
                {
                    output.Append(window.Step(line));
                }
                catch (Exception e)
                {
                    output.AppendLine($"FAILED: {e.GetType().Name}: {e.Message}");
                    code = 1;
                    break;
                }
            }
        }
        catch (Exception e)
        {
            output.AppendLine($"FAILED: {e.GetType().Name}: {e.Message}");
            code = 1;
        }
        File.WriteAllText(file + ".out", output.ToString(), new UTF8Encoding(false));
        File.WriteAllText(file + ".done", code.ToString(CultureInfo.InvariantCulture));
        return code;
    }
}
