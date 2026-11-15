// A drag with the mouse through SendInput, as a person drags: pressed on one point, moved there in steps past the system's
// drag threshold, held over the target while it answers, released. Explorer's OLE drag runs on these inputs as on a
// person's; UI Automation has no drag.
using System;
using System.Runtime.InteropServices;
using System.Threading;

namespace Veyq.Drive;

internal static partial class Mouse
{
    public static string Drag(int fromX, int fromY, int toX, int toY, Action? whileHeld = null)
    {
        Move(fromX, fromY);
        Thread.Sleep(300);
        GetCursorPos(out var at);
        var said = $"pointer at ({at.X},{at.Y}) before the press";
        Send(MouseLeftDown, fromX, fromY);
        Thread.Sleep(300);
        // first a few small steps, as a hand leaves the press: past the drag threshold (SM_CXDRAG) while DragDetect watches
        foreach (var d in new[] { 2, 4, 8, 12, 16, 24 })
        {
            Move(fromX - d, fromY + d / 2);
            Thread.Sleep(60);
        }
        const int steps = 40;
        for (var i = 1; i <= steps; i++)
        {
            Move(fromX + (toX - fromX) * i / steps, fromY + (toY - fromY) * i / steps);
            Thread.Sleep(30);
        }
        // held over the target: its DragOver answers (a copy) before the release
        for (var i = 0; i < 10; i++)
        {
            Move(toX + (i % 2) * 2, toY);
            Thread.Sleep(100);
        }
        whileHeld?.Invoke();
        GetCursorPos(out at);
        said += $", ({at.X},{at.Y}) at the release";
        Send(MouseLeftUp, toX, toY);
        Thread.Sleep(500);
        return said;
    }

    /// <summary>A left click at x, y on the screen.</summary>
    public static void Click(int x, int y)
    {
        Move(x, y);
        Thread.Sleep(150);
        Send(MouseLeftDown, x, y);
        Thread.Sleep(80);
        Send(MouseLeftUp, x, y);
        Thread.Sleep(300);
    }

    /// <summary>The wheel turned <paramref name="notches"/> notches with the pointer at x, y on the screen, one at a time as a
    /// hand turns it: a positive count is WHEEL_DELTA as sent (Win32's forward turn, away from the person), a negative one
    /// the other way. Which way a view then moves is the host's: on the test VM a negative count moved Settings down
    /// (measured 2026-11-15), so a pass reads where the view stands (`Settled`), never assumes it.</summary>
    public static void Wheel(int x, int y, int notches)
    {
        Move(x, y);
        Thread.Sleep(150);
        for (var i = 0; i < Math.Abs(notches); i++)
        {
            var input = new Input { Type = 0, Mi = new MouseInput { Data = unchecked((uint)(Math.Sign(notches) * WheelDelta)), Flags = MouseWheel } };
            if (SendInput(1, [input], Marshal.SizeOf<Input>()) != 1)
            {
                throw new InvalidOperationException($"SendInput sent no wheel input ({Marshal.GetLastPInvokeError()})");
            }
            Thread.Sleep(60);
        }
    }

    private static void Move(int x, int y) => Send(MouseMove, x, y);

    private static void Send(uint flags, int x, int y)
    {
        int w = GetSystemMetrics(0), h = GetSystemMetrics(1);
        var input = new Input
        {
            Type = 0,
            Mi = new MouseInput { Dx = x * 65535 / Math.Max(1, w - 1), Dy = y * 65535 / Math.Max(1, h - 1), Flags = flags | MouseAbsolute | MouseMove },
        };
        if (SendInput(1, [input], Marshal.SizeOf<Input>()) != 1)
        {
            throw new InvalidOperationException($"SendInput sent no mouse input ({Marshal.GetLastPInvokeError()})");
        }
    }

    private const uint MouseMove = 0x0001, MouseLeftDown = 0x0002, MouseLeftUp = 0x0004, MouseWheel = 0x0800, MouseAbsolute = 0x8000;
    private const int WheelDelta = 120;

    [StructLayout(LayoutKind.Sequential)]
    private struct Input
    {
        public uint Type;
        public MouseInput Mi;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MouseInput
    {
        public int Dx;
        public int Dy;
        public uint Data;
        public uint Flags;
        public uint Time;
        public nint ExtraInfo;
    }

    [LibraryImport("user32.dll", SetLastError = true)]
    private static partial uint SendInput(uint count, [In] Input[] inputs, int size);

    [LibraryImport("user32.dll")]
    private static partial int GetSystemMetrics(int index);

    [StructLayout(LayoutKind.Sequential)]
    private struct Point
    {
        public int X, Y;
    }

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool GetCursorPos(out Point point);
}
