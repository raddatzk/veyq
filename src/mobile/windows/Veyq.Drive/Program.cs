// Veyq.Drive (ADR 0123's Consequences, the Windows app). Two modes, each what one scheduled task runs in the logged-on
// session (an ssh command runs in session 0, which has no desktop):
//   --launch <file>   reads the app's start from <file> — {"exe": …, "args": […], "env": {…}}, args optional — deletes
//                     it at once, and starts the app with that environment (a secret in it never reaches a command
//                     line). Writes <file>.started. `run.py hello-probe` starts the core's test binary so, with args.
//                     {"aumid": …} instead activates an installed package's app by its AUMID (`<family>!App`, run.py
//                     package --smoke), as Start does: a packaged app takes no environment, and its process id is the
//                     activation's.
//   --drive <file>    runs the steps in <file>, one a line, against the app's window, writes what each did to
//                     <file>.out (UTF-8) and then <file>.done. A step that fails ends the run, said in the output.
// Steps:
//   tree                      the window's UIA tree as text: control type, name, AutomationId, value, on/off, enabled, bounds;
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
//   scroll <id> [percent]     a scrolled view (its ScrollPattern: a ScrollViewer, a list) moved to percent of its height
//                             (100 by default); prints where it stands then and 2 s later, and what holds the focus
//   wheel <id> [notches]      the mouse wheel turned over the middle of what shows of a scrolled view (SendInput),
//                             notches as WHEEL_DELTA sent (5 by default; on the test VM a negative count scrolls down):
//                             a person's scroll, not UIA's; prints as scroll does
//   range <id> <percent>      a slider (a player's seek bar) set to percent of its range (its RangeValue)
//   slide <id> <from> <to>    a mouse drag across the element (SendInput) from <from> to <to>, each a percent of its
//                             width at its middle: the device link's slide, which a RangeValue set does not confirm
//   listen                    UI Automation's notification events in the app's window heard from now on (Notifications)
//   heard                     what was heard since: each event's kind, processing, activity id and text
//   size <w> <h>              the window restored and resized to w × h (in its own pixels)
//   maximize                  the window maximized
//   wait <id> [seconds]       until an element with that AutomationId is there (10 s by default)
//   sleep <ms>                a pause
//   explorer <x> <y> <w> <h> <path>
//                             an Explorer window opened at <path>, placed and sized (a drag's source beside the app)
//   drag <id> <name>|<name>…  the named items of that Explorer window selected and dragged with the mouse (SendInput)
//                             onto the app's element <id>: a level (`level`), a folder's row (`folder:<name>`)
//   click <x> <y>             a left click at a point of the screen (SendInput), where no element is reachable by UIA
//   sweep <x1> <y1> <x2> <y2> the mouse pressed at one point of the screen, moved to the other and released (SendInput):
//                             a text selected by hand (WebView2's PDF viewer, whose content UIA does not reach)
//   screen <file>             a PNG of the whole screen (a drag's source and target together)
//   hovershot <file>          the next drag or sweep takes a PNG of the whole screen while it is held over its target
//   shot <file>               a PNG of the window as it shows on the screen (the proof, not a probe)
// An <id> is an AutomationId, found in any top-level window of the app; a step's words are never typed from anything
// but the steps file.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
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
        if (start.RootElement.TryGetProperty("aumid", out var aumid))
        {
            var manager = (IApplicationActivationManager)new ApplicationActivationManager();
            manager.ActivateApplication(aumid.GetString()!, null, 0, out var pid);
            File.WriteAllText(file + ".started", pid.ToString(CultureInfo.InvariantCulture));
            return 0;
        }
        var info = new ProcessStartInfo(start.RootElement.GetProperty("exe").GetString()!) { UseShellExecute = false };
        info.WorkingDirectory = Path.GetDirectoryName(info.FileName)!;
        if (start.RootElement.TryGetProperty("args", out var arguments))
        {
            foreach (var a in arguments.EnumerateArray()) info.ArgumentList.Add(a.GetString()!);
        }
        if (start.RootElement.TryGetProperty("env", out var env))
        {
            foreach (var v in env.EnumerateObject()) info.Environment[v.Name] = v.Value.GetString();
        }
        using var app = Process.Start(info)!;
        File.WriteAllText(file + ".started", app.Id.ToString(CultureInfo.InvariantCulture));
        return 0;
    }

    // Start's own activation of a packaged app (shobjidl_core.h); only its first method is called, so only it is declared.
    [ComImport, Guid("2e941141-7f97-4756-ba1d-9decde894a3d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IApplicationActivationManager
    {
        void ActivateApplication([MarshalAs(UnmanagedType.LPWStr)] string appUserModelId,
            [MarshalAs(UnmanagedType.LPWStr)] string? arguments, int options, out uint processId);
    }

    [ComImport, Guid("45BA127D-10A8-46EA-8AB7-56EA9078943C")]
    private class ApplicationActivationManager
    {
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
