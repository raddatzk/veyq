// The app's window as UI Automation sees it, and each step of a drive.
using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Automation;
using System.Windows.Media.Imaging;

namespace Veyq.Drive;

internal sealed partial class AppWindow(string process)
{
    /// <summary>Where the next drag's shot goes, taken while it is held over its target (`hovershot <file>`).</summary>
    private string? _hoverShot;

    private AutomationElement Window()
    {
        // A freshly copied build's first start took 23 s to its window on the VM under UAC on (2026-11-13: the core
        // started 20 s after the process), so `run.py run`'s `size` right after the launch waits a minute, not 15 s.
        var deadline = DateTime.UtcNow.AddSeconds(60);
        while (true)
        {
            foreach (var p in Process.GetProcessesByName(process))
            {
                var found = AutomationElement.RootElement.FindFirst(TreeScope.Children,
                    new AndCondition(new PropertyCondition(AutomationElement.ProcessIdProperty, p.Id),
                        new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Window)));
                if (found is not null) return found;
            }
            if (DateTime.UtcNow > deadline) throw new InvalidOperationException($"no window of a process named {process}");
            Thread.Sleep(200);
        }
    }

    /// <summary>The first element with AutomationId <paramref name="id"/> in any of the app's top-level windows: a WinUI
    /// flyout, a menu or a suggestion list is a window of its own beside the main one.</summary>
    private AutomationElement Find(string id, int seconds = 5)
    {
        var deadline = DateTime.UtcNow.AddSeconds(seconds);
        var byId = new PropertyCondition(AutomationElement.AutomationIdProperty, id);
        while (true)
        {
            foreach (var p in Process.GetProcessesByName(process))
            {
                var windows = AutomationElement.RootElement.FindAll(TreeScope.Children,
                    new PropertyCondition(AutomationElement.ProcessIdProperty, p.Id));
                foreach (AutomationElement w in windows)
                {
                    var found = w.FindFirst(TreeScope.Descendants, byId);
                    if (found is not null) return found;
                }
            }
            if (DateTime.UtcNow > deadline) throw new InvalidOperationException($"no element with AutomationId {id}");
            Thread.Sleep(200);
        }
    }

    /// <summary>Every top-level window of the app but the main one (popups: a flyout, a menu, a suggestion list).</summary>
    private string Popups(AutomationElement main)
    {
        var text = new StringBuilder();
        foreach (var p in Process.GetProcessesByName(process))
        {
            foreach (AutomationElement w in AutomationElement.RootElement.FindAll(TreeScope.Children,
                         new PropertyCondition(AutomationElement.ProcessIdProperty, p.Id)))
            {
                if (!Automation.Compare(w, main)) Walk(TreeWalker.ControlViewWalker, w, 0, text);
            }
        }
        return text.ToString();
    }

    public string Step(string line)
    {
        var parts = line.Split(' ', 3, StringSplitOptions.RemoveEmptyEntries);
        // an AutomationId may hold spaces (a name): invoke and select take the rest of the line
        var rest = line[parts[0].Length..].Trim();
        switch (parts[0])
        {
            case "tree":
            {
                var main = Window();
                return Tree(main) + Popups(main);
            }
            case "invoke":
                Invoke(Find(rest));
                Thread.Sleep(300);
                return "";
            case "select":
                ((SelectionItemPattern)Find(rest).GetCurrentPattern(SelectionItemPattern.Pattern)).Select();
                Thread.Sleep(300);
                return "";
            case "set":
                ((ValuePattern)Find(parts[1]).GetCurrentPattern(ValuePattern.Pattern)).SetValue(parts.Length > 2 ? parts[2] : "");
                return "";
            case "key":
                Keys.Type(Handle(Window()), string.Join(' ', parts.Skip(1)));
                return "";
            case "type":
            {
                // a system dialog's entry (the save picker's file name) takes what is typed into it: a Value set through
                // UIA showed there and was not read (the picker saved under the name it had)
                var e = Find(parts[1]);
                // the open picker's file name is an Edit inside a ComboBox of the same AutomationId (1148): the ComboBox,
                // found first, took the focus and nothing typed reached the Edit (2026-11-13, the kit's Windows pass)
                if (e.Current.ControlType == ControlType.ComboBox &&
                    e.FindFirst(TreeScope.Children, new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Edit)) is { } edit)
                {
                    e = edit;
                }
                e.SetFocus();
                Keys.Text(TopLevel(e), parts.Length > 2 ? parts[2] : "");
                return "";
            }
            case "press":
            {
                // a system dialog's button (the save picker's) on a real key: UIA's Invoke reaches it as an
                // input-synchronous call, out of which the dialog cannot call the file broker (RPC_E_CANTCALLOUT_ININPUTSYNCCALL)
                var e = Find(parts[1]);
                e.SetFocus();
                Keys.Type(TopLevel(e), parts.Length > 2 ? parts[2] : "enter");
                Thread.Sleep(300);
                return "";
            }
            case "scroll":
            {
                // a scrolled view (a ScrollViewer: a list, a file's PDF) moved to <percent> of its height, as a wheel would
                var e = Find(parts[1]);
                var to = double.Parse(parts.Length > 2 ? parts[2] : "100", CultureInfo.InvariantCulture);
                var pattern = (ScrollPattern)e.GetCurrentPattern(ScrollPattern.Pattern);
                pattern.SetScrollPercent(ScrollPattern.NoScroll, to);
                return Settled(e, pattern);
            }
            case "wheel":
            {
                // a scrolled view turned by the mouse wheel over its middle, <notches> down (negative: up), as a person
                // scrolls: SendInput's wheel, not UIA's ScrollPattern — a jump seen after one is the app's, not the drive's
                var e = Find(parts[1]);
                // over the middle of what shows of it: a view's UIA rectangle can reach past the window (Settings' page
                // read 3830 high in a 717 high window, 2026-11-15)
                var r = System.Windows.Rect.Intersect(e.Current.BoundingRectangle, Window().Current.BoundingRectangle);
                var notches = int.Parse(parts.Length > 2 ? parts[2] : "5", CultureInfo.InvariantCulture);
                Mouse.Wheel((int)(r.Left + r.Width / 2), (int)(r.Top + r.Height / 2), notches);
                return Settled(e, (ScrollPattern)e.GetCurrentPattern(ScrollPattern.Pattern));
            }
            case "range":
            {
                // a slider (a player's seek bar) set to <percent> of its range through its RangeValue, as a drag would
                var e = Find(parts[1]);
                var range = (RangeValuePattern)e.GetCurrentPattern(RangeValuePattern.Pattern);
                var (min, max) = (range.Current.Minimum, range.Current.Maximum);
                range.SetValue(min + (max - min) * double.Parse(parts[2], CultureInfo.InvariantCulture) / 100);
                Thread.Sleep(300);
                return $"{range.Current.Value} of {min}..{max}";
            }
            case "slide":
            {
                // a pointer's drag across an element (the device link's slide) from <from> to <to>, each a percent of its
                // width at its vertical middle, with SendInput's mouse as a hand drags; `range` is the one move a drag is not
                var e = Find(parts[1]);
                var r = e.Current.BoundingRectangle;
                var span = parts.Length > 2 ? parts[2].Split(' ', StringSplitOptions.RemoveEmptyEntries) : [];
                if (span.Length != 2) throw new ArgumentException("slide <id> <from> <to>");
                int At(string percent) => (int)(r.Left + r.Width * double.Parse(percent, CultureInfo.InvariantCulture) / 100);
                var y = (int)(r.Top + r.Height / 2);
                return Mouse.Drag(At(span[0]), y, At(span[1]), y);
            }
            case "size":
                Size(Window(), int.Parse(parts[1], CultureInfo.InvariantCulture), int.Parse(parts[2], CultureInfo.InvariantCulture));
                return "";
            case "maximize":
                ((WindowPattern)Window().GetCurrentPattern(WindowPattern.Pattern)).SetWindowVisualState(WindowVisualState.Maximized);
                Thread.Sleep(500);
                return "";
            case "wait":
                Find(parts[1], parts.Length > 2 ? int.Parse(parts[2], CultureInfo.InvariantCulture) : 10);
                return "";
            case "sleep":
                Thread.Sleep(int.Parse(parts[1], CultureInfo.InvariantCulture));
                return "";
            case "explorer":
                return Explorer(int.Parse(parts[1], CultureInfo.InvariantCulture), line);
            case "drag":
                return Drag(parts[1], parts.Length > 2 ? parts[2] : throw new ArgumentException("drag <id> <name>|<name>…"));
            case "click":
                Mouse.Click(int.Parse(parts[1], CultureInfo.InvariantCulture), int.Parse(parts[2], CultureInfo.InvariantCulture));
                return "";
            case "screen":
                Capture(0, 0, GetSystemMetrics(0), GetSystemMetrics(1), string.Join(' ', parts.Skip(1)));
                return "";
            case "droptargets":
                return DropTargets(Handle(Window()));
            case "hovershot":
                _hoverShot = string.Join(' ', parts.Skip(1));
                return "";
            case "shot":
                Shot(Window(), string.Join(' ', parts.Skip(1)));
                return "";
            default:
                throw new ArgumentException($"no step named {parts[0]}");
        }
    }

    /// <summary>Where a scrolled view stands right after a scroll and two seconds later (a jump back shows as the two
    /// differing) — its ScrollPattern's percent, which WinUI updates late, and the screen top of its own focused element
    /// (or else its first named element), which is the layout itself —, and which element holds the focus then.</summary>
    private static string Settled(AutomationElement view, ScrollPattern pattern)
    {
        var mark = AutomationElement.FocusedElement is { } f && IsWithin(f, view) ? f
            : view.FindFirst(TreeScope.Descendants, new NotCondition(new PropertyCondition(AutomationElement.AutomationIdProperty, "")));
        string At() => FormattableString.Invariant($"{pattern.Current.VerticalScrollPercent:0.#} %, '{mark?.Current.AutomationId}' at y {mark?.Current.BoundingRectangle.Top:0}");
        Thread.Sleep(300);
        var first = At();
        Thread.Sleep(2000);
        var later = At();
        var focused = AutomationElement.FocusedElement;
        var focus = focused is null ? "none" : $"{focused.Current.ControlType.ProgrammaticName} '{focused.Current.AutomationId}'";
        return $"scrolled to {first}; 2 s later {later}; focus on {focus}\n";
    }

    /// <summary>Whether <paramref name="e"/> lies inside <paramref name="ancestor"/>.</summary>
    private static bool IsWithin(AutomationElement e, AutomationElement ancestor)
    {
        for (var p = TreeWalker.ControlViewWalker.GetParent(e); p is not null; p = TreeWalker.ControlViewWalker.GetParent(p))
        {
            if (Automation.Compare(p, ancestor)) return true;
        }
        return false;
    }

    /// <summary>`explorer <x> <y> <w> <h> <path>`: an Explorer window opened at <path> in the logged-on session, placed at
    /// x, y and sized w × h, so a drag has a source beside the app.</summary>
    private static string Explorer(int x, string line)
    {
        var parts = line.Split(' ', 6, StringSplitOptions.RemoveEmptyEntries);
        int y = int.Parse(parts[2], CultureInfo.InvariantCulture), w = int.Parse(parts[3], CultureInfo.InvariantCulture), h = int.Parse(parts[4], CultureInfo.InvariantCulture);
        var path = parts[5];
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{path}\"") { UseShellExecute = false })?.Dispose();
        var window = ExplorerWindow(Path.GetFileName(path.TrimEnd('\\')), 15);
        SetWindowPos(Handle(window), 0, x, y, w, h, SwpNoZOrder);
        SetForegroundWindow(Handle(window));
        Thread.Sleep(1000);
        return $"explorer at {path}\n";
    }

    /// <summary>The Explorer window whose title is <paramref name="title"/>.</summary>
    private static AutomationElement ExplorerWindow(string title, int seconds = 5)
    {
        var deadline = DateTime.UtcNow.AddSeconds(seconds);
        var cabinet = new AndCondition(new PropertyCondition(AutomationElement.ClassNameProperty, "CabinetWClass"),
            new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Window));
        while (true)
        {
            foreach (AutomationElement w in AutomationElement.RootElement.FindAll(TreeScope.Children, cabinet))
            {
                if (w.Current.Name.StartsWith(title, StringComparison.OrdinalIgnoreCase)) return w;
            }
            if (DateTime.UtcNow > deadline) throw new InvalidOperationException($"no Explorer window titled {title}");
            Thread.Sleep(300);
        }
    }

    /// <summary>`drag <id> <name>|<name>…`: the named items of the frontmost Explorer window selected and dragged with the
    /// mouse onto the app's element <id> (a level, a folder's row), as a person drags them (SendInput: Explorer's OLE drag
    /// on real input; UIA has no drag).</summary>
    private string Drag(string id, string names)
    {
        var cabinet = new AndCondition(new PropertyCondition(AutomationElement.ClassNameProperty, "CabinetWClass"),
            new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Window));
        var first = names.Split('|', StringSplitOptions.RemoveEmptyEntries)[0];
        AutomationElement? explorer = null;
        foreach (AutomationElement w in AutomationElement.RootElement.FindAll(TreeScope.Children, cabinet))
        {
            try
            {
                ExplorerItem(w, first);
                explorer = w;
                break;
            }
            catch (InvalidOperationException)
            {
                // another Explorer window: the drag's source is the one showing its first item
            }
        }
        if (explorer is null) throw new InvalidOperationException($"no Explorer window shows {first}");
        SetForegroundWindow(Handle(explorer));
        Thread.Sleep(500);
        var items = names.Split('|', StringSplitOptions.RemoveEmptyEntries).Select(n => ExplorerItem(explorer, n)).ToList();
        for (var i = 0; i < items.Count; i++)
        {
            var select = (SelectionItemPattern)items[i].GetCurrentPattern(SelectionItemPattern.Pattern);
            if (i == 0) select.Select();
            else select.AddToSelection();
        }
        Thread.Sleep(300);
        // pressed on the first item's name, where a press drags the selection (beside it a press starts a marquee)
        var name = items[0].FindFirst(TreeScope.Descendants, new PropertyCondition(AutomationElement.AutomationIdProperty, "System.ItemNameDisplay"));
        var from = (name ?? items[0]).Current.BoundingRectangle;
        // `@x,y`: a point of the screen instead of an element (a probe of the drag itself)
        var to = id.StartsWith('@')
            ? new System.Windows.Rect(int.Parse(id[1..].Split(',')[0], CultureInfo.InvariantCulture), int.Parse(id[1..].Split(',')[1], CultureInfo.InvariantCulture), 1, 1)
            : Find(id).Current.BoundingRectangle;
        int fx = (int)(from.Left + Math.Min(from.Width / 2, 40)), fy = (int)(from.Top + from.Height / 2);
        // a row: its middle; a level (taller than a few rows): near its bottom, below its rows, where no folder's row takes it
        int tx = (int)(to.Left + to.Width / 2), ty = (int)(to.Height > 120 ? to.Bottom - 40 : to.Top + to.Height / 2);
        var shot = _hoverShot;
        _hoverShot = null;
        var said = Mouse.Drag(fx, fy, tx, ty, shot is null ? null : () => Capture(0, 0, GetSystemMetrics(0), GetSystemMetrics(1), shot));
        return $"dragged {items.Count} from ({fx},{fy}) to {id} ({tx},{ty}): {said}\n";
    }

    /// <summary>The item named <paramref name="name"/> in an Explorer window, its extension shown or hidden.</summary>
    private static AutomationElement ExplorerItem(AutomationElement explorer, string name)
    {
        foreach (AutomationElement item in explorer.FindAll(TreeScope.Descendants, new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.ListItem)))
        {
            var shown = item.Current.Name;
            if (shown == name || shown == Path.GetFileNameWithoutExtension(name)) return item;
        }
        throw new InvalidOperationException($"no item named {name} in Explorer");
    }

    /// <summary>The app's window and each of its child windows with its class and whether OLE holds a drop target for it
    /// (RegisterDragDrop's window property): where a drop from Explorer can land at all.</summary>
    private static string DropTargets(nint main)
    {
        var text = new StringBuilder();
        void Say(nint hwnd, int depth)
        {
            var name = new char[256];
            var length = GetClassNameW(hwnd, name, name.Length);
            var target = GetPropW(hwnd, "OleDropTargetInterface") != 0;
            text.Append(' ', depth * 2).Append(new string(name, 0, length)).Append(target ? " drop target" : "").AppendLine();
        }
        Say(main, 0);
        EnumChildWindows(main, (child, _) => { Say(child, 1); return true; }, 0);
        return text.ToString();
    }

    private delegate bool EnumProc(nint hwnd, nint data);

    [DllImport("user32.dll")]
    private static extern bool EnumChildWindows(nint parent, EnumProc proc, nint data);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetClassNameW(nint hwnd, char[] name, int max);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern nint GetPropW(nint hwnd, string name);

    private static void Invoke(AutomationElement e)
    {
        if (e.TryGetCurrentPattern(InvokePattern.Pattern, out var invoke)) ((InvokePattern)invoke).Invoke();
        else if (e.TryGetCurrentPattern(TogglePattern.Pattern, out var toggle)) ((TogglePattern)toggle).Toggle();
        else if (e.TryGetCurrentPattern(ExpandCollapsePattern.Pattern, out var expand))
        {
            var p = (ExpandCollapsePattern)expand;
            if (p.Current.ExpandCollapseState == ExpandCollapseState.Expanded) p.Collapse();
            else p.Expand();
        }
        else throw new InvalidOperationException($"{e.Current.AutomationId} can be neither invoked, toggled nor expanded");
    }

    private static string Tree(AutomationElement window)
    {
        var text = new StringBuilder();
        Walk(TreeWalker.ControlViewWalker, window, 0, text);
        return text.ToString();
    }

    private static void Walk(TreeWalker walker, AutomationElement e, int depth, StringBuilder text)
    {
        AutomationElement.AutomationElementInformation c;
        try
        {
            c = e.Current;
        }
        catch (ElementNotAvailableException)
        {
            return; // gone while the tree was read
        }
        // a WinUI element may report no control type (or no name) to the managed client
        text.Append(' ', depth * 2).Append(c.ControlType?.ProgrammaticName.Replace("ControlType.", "") ?? "Element");
        if (!string.IsNullOrEmpty(c.Name)) text.Append(" \"").Append(c.Name).Append('"');
        if (!string.IsNullOrEmpty(c.AutomationId)) text.Append(" #").Append(c.AutomationId);
        if (e.TryGetCurrentPattern(ValuePattern.Pattern, out var value)) text.Append(" value=\"").Append(((ValuePattern)value).Current.Value).Append('"');
        if (e.TryGetCurrentPattern(SelectionItemPattern.Pattern, out var item) && ((SelectionItemPattern)item).Current.IsSelected) text.Append(" selected");
        // a check box's or switch's state, so a pass reads it before its shot
        if (e.TryGetCurrentPattern(TogglePattern.Pattern, out var toggle)) text.Append(((TogglePattern)toggle).Current.ToggleState == ToggleState.On ? " on" : " off");
        if (!c.IsEnabled) text.Append(" disabled");
        var b = c.BoundingRectangle;
        if (!b.IsEmpty) text.Append(CultureInfo.InvariantCulture, $" [{b.X:0},{b.Y:0} {b.Width:0}x{b.Height:0}]");
        text.AppendLine();
        for (var child = walker.GetFirstChild(e); child is not null; child = walker.GetNextSibling(child))
        {
            Walk(walker, child, depth + 1, text);
        }
    }

    private static nint Handle(AutomationElement window) => window.Current.NativeWindowHandle;

    /// <summary>The window <paramref name="e"/> lies in: its nearest ancestor (or itself) with a window handle.</summary>
    private static nint TopLevel(AutomationElement e)
    {
        for (var at = e; at is not null; at = TreeWalker.RawViewWalker.GetParent(at))
        {
            if (at.Current.ControlType == ControlType.Window && at.Current.NativeWindowHandle != 0) return at.Current.NativeWindowHandle;
        }
        throw new InvalidOperationException($"{e.Current.AutomationId} lies in no window");
    }

    private static void Size(AutomationElement window, int width, int height)
    {
        ((WindowPattern)window.GetCurrentPattern(WindowPattern.Pattern)).SetWindowVisualState(WindowVisualState.Normal);
        if (!SetWindowPos(Handle(window), 0, 0, 0, width, height, SwpNoZOrder | SwpNoActivate))
        {
            throw new InvalidOperationException($"SetWindowPos failed ({Marshal.GetLastPInvokeError()})");
        }
        Thread.Sleep(500);
    }

    private static void Shot(AutomationElement window, string file)
    {
        var hwnd = Handle(window);
        SetForegroundWindow(hwnd);
        Thread.Sleep(500);
        if (DwmGetWindowAttribute(hwnd, DwmwaExtendedFrameBounds, out var r, Marshal.SizeOf<Rect>()) != 0 && !GetWindowRect(hwnd, out r))
        {
            throw new InvalidOperationException("the window has no bounds");
        }
        Capture(r.Left, r.Top, r.Right - r.Left, r.Bottom - r.Top, file);
    }

    /// <summary>A PNG of the screen's rectangle at x, y of w × h, as it shows.</summary>
    private static void Capture(int x, int y, int w, int h, string file)
    {
        var screen = GetDC(0);
        var memory = CreateCompatibleDC(screen);
        var bitmap = CreateCompatibleBitmap(screen, w, h);
        var old = SelectObject(memory, bitmap);
        try
        {
            if (!BitBlt(memory, 0, 0, w, h, screen, x, y, SrcCopy | CaptureBlt)) throw new InvalidOperationException("BitBlt failed");
            var source = System.Windows.Interop.Imaging.CreateBitmapSourceFromHBitmap(bitmap, 0, System.Windows.Int32Rect.Empty,
                BitmapSizeOptions.FromEmptyOptions());
            var png = new PngBitmapEncoder();
            png.Frames.Add(BitmapFrame.Create(source));
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(file))!);
            using var stream = File.Create(file);
            png.Save(stream);
        }
        finally
        {
            SelectObject(memory, old);
            DeleteObject(bitmap);
            DeleteDC(memory);
            ReleaseDC(0, screen);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Rect
    {
        public int Left, Top, Right, Bottom;
    }

    private const uint SwpNoZOrder = 0x0004, SwpNoActivate = 0x0010;
    private const int DwmwaExtendedFrameBounds = 9;
    private const uint SrcCopy = 0x00CC0020, CaptureBlt = 0x40000000;

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetWindowPos(nint hwnd, nint after, int x, int y, int cx, int cy, uint flags);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetForegroundWindow(nint hwnd);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GetWindowRect(nint hwnd, out Rect rect);

    [LibraryImport("dwmapi.dll")]
    private static partial int DwmGetWindowAttribute(nint hwnd, int attribute, out Rect value, int size);

    [LibraryImport("user32.dll")]
    private static partial nint GetDC(nint hwnd);

    [LibraryImport("user32.dll")]
    private static partial int GetSystemMetrics(int index);

    [LibraryImport("user32.dll")]
    private static partial int ReleaseDC(nint hwnd, nint dc);

    [LibraryImport("gdi32.dll")]
    private static partial nint CreateCompatibleDC(nint dc);

    [LibraryImport("gdi32.dll")]
    private static partial nint CreateCompatibleBitmap(nint dc, int w, int h);

    [LibraryImport("gdi32.dll")]
    private static partial nint SelectObject(nint dc, nint obj);

    [LibraryImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DeleteObject(nint obj);

    [LibraryImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool DeleteDC(nint dc);

    [LibraryImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool BitBlt(nint dest, int x, int y, int w, int h, nint src, int sx, int sy, uint rop);
}
