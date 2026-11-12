// Keys typed into the app's window through SendInput: `ctrl+e`, `enter`, `tab`, `escape`, a letter or a digit, each
// chord of a step separated by a space; or a text, character by character.
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading;

namespace Veyq.Drive;

internal static partial class Keys
{
    private static readonly Dictionary<string, ushort> Named = new(StringComparer.OrdinalIgnoreCase)
    {
        ["ctrl"] = 0x11, ["alt"] = 0x12, ["shift"] = 0x10, ["win"] = 0x5B, ["enter"] = 0x0D, ["tab"] = 0x09,
        ["escape"] = 0x1B, ["space"] = 0x20, ["back"] = 0x08, ["delete"] = 0x2E, ["left"] = 0x25, ["up"] = 0x26,
        ["right"] = 0x27, ["down"] = 0x28, ["home"] = 0x24, ["end"] = 0x23, ["f5"] = 0x74, ["f6"] = 0x75,
    };

    public static void Type(nint window, string chords)
    {
        SetForegroundWindow(window);
        Thread.Sleep(200);
        foreach (var chord in chords.Split(' ', StringSplitOptions.RemoveEmptyEntries))
        {
            var keys = new List<ushort>();
            foreach (var name in chord.Split('+')) keys.Add(Key(name));
            var inputs = new List<Input>();
            foreach (var k in keys) inputs.Add(Input.Of(k, false));
            for (var i = keys.Count - 1; i >= 0; i--) inputs.Add(Input.Of(keys[i], true));
            var array = inputs.ToArray();
            if (SendInput((uint)array.Length, array, Marshal.SizeOf<Input>()) != array.Length)
            {
                throw new InvalidOperationException($"SendInput sent not all of {chord} ({Marshal.GetLastPInvokeError()})");
            }
            Thread.Sleep(100);
        }
    }

    /// <summary><paramref name="text"/> typed into <paramref name="window"/> as characters (KEYEVENTF_UNICODE), as a
    /// person types it: a system dialog's entry reads typed text, never one set through UI Automation's Value.</summary>
    public static void Text(nint window, string text)
    {
        SetForegroundWindow(window);
        Thread.Sleep(200);
        foreach (var c in text)
        {
            Input[] pair = [Input.Unicode(c, false), Input.Unicode(c, true)];
            if (SendInput(2, pair, Marshal.SizeOf<Input>()) != 2)
            {
                throw new InvalidOperationException($"SendInput sent not all of {c} ({Marshal.GetLastPInvokeError()})");
            }
        }
        Thread.Sleep(100);
    }

    private static ushort Key(string name)
    {
        if (Named.TryGetValue(name, out var vk)) return vk;
        if (name.Length == 1 && char.IsAsciiLetterOrDigit(name[0])) return char.ToUpperInvariant(name[0]);
        throw new ArgumentException($"no key named {name}");
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Input
    {
        public uint Type;
        public KeyboardInput Ki;
        private readonly long _pad; // the union's size is a MOUSEINPUT's

        public static Input Of(ushort vk, bool up) => new() { Type = 1, Ki = new KeyboardInput { Vk = vk, Flags = up ? 2u : 0u } };

        public static Input Unicode(char c, bool up) => new() { Type = 1, Ki = new KeyboardInput { Scan = c, Flags = 4u | (up ? 2u : 0u) } };
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KeyboardInput
    {
        public ushort Vk;
        public ushort Scan;
        public uint Flags;
        public uint Time;
        public nint ExtraInfo;
    }

    [LibraryImport("user32.dll", SetLastError = true)]
    private static partial uint SendInput(uint count, [In] Input[] inputs, int size);

    [LibraryImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetForegroundWindow(nint hwnd);
}
