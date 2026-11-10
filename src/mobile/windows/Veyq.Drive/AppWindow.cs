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
    private AutomationElement Window()
    {
        var deadline = DateTime.UtcNow.AddSeconds(15);
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

    private AutomationElement Find(string id, int seconds = 5)
    {
        var deadline = DateTime.UtcNow.AddSeconds(seconds);
        while (true)
        {
            var found = Window().FindFirst(TreeScope.Descendants, new PropertyCondition(AutomationElement.AutomationIdProperty, id));
            if (found is not null) return found;
            if (DateTime.UtcNow > deadline) throw new InvalidOperationException($"no element with AutomationId {id}");
            Thread.Sleep(200);
        }
    }

    public string Step(string line)
    {
        var parts = line.Split(' ', 3, StringSplitOptions.RemoveEmptyEntries);
        switch (parts[0])
        {
            case "tree":
                return Tree(Window());
            case "invoke":
                Invoke(Find(parts[1]));
                return "";
            case "select":
                ((SelectionItemPattern)Find(parts[1]).GetCurrentPattern(SelectionItemPattern.Pattern)).Select();
                return "";
            case "set":
                ((ValuePattern)Find(parts[1]).GetCurrentPattern(ValuePattern.Pattern)).SetValue(parts.Length > 2 ? parts[2] : "");
                return "";
            case "key":
                Keys.Type(Handle(Window()), string.Join(' ', parts.Skip(1)));
                return "";
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
            case "shot":
                Shot(Window(), string.Join(' ', parts.Skip(1)));
                return "";
            default:
                throw new ArgumentException($"no step named {parts[0]}");
        }
    }

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
        var c = e.Current;
        text.Append(' ', depth * 2).Append(c.ControlType.ProgrammaticName.Replace("ControlType.", ""));
        if (c.Name.Length > 0) text.Append(" \"").Append(c.Name).Append('"');
        if (c.AutomationId.Length > 0) text.Append(" #").Append(c.AutomationId);
        if (e.TryGetCurrentPattern(ValuePattern.Pattern, out var value)) text.Append(" value=\"").Append(((ValuePattern)value).Current.Value).Append('"');
        if (e.TryGetCurrentPattern(SelectionItemPattern.Pattern, out var item) && ((SelectionItemPattern)item).Current.IsSelected) text.Append(" selected");
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
        int w = r.Right - r.Left, h = r.Bottom - r.Top;
        var screen = GetDC(0);
        var memory = CreateCompatibleDC(screen);
        var bitmap = CreateCompatibleBitmap(screen, w, h);
        var old = SelectObject(memory, bitmap);
        try
        {
            if (!BitBlt(memory, 0, 0, w, h, screen, r.Left, r.Top, SrcCopy | CaptureBlt)) throw new InvalidOperationException("BitBlt failed");
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
