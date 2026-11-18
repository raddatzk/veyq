// UI Automation's notification events (UIA_NotificationEventId, what Narrator reads a screen's announcement from) raised
// in the app's window, heard as a screen reader hears them: `listen` registers, `heard` prints what came since. The
// managed System.Windows.Automation client has no notification event, so this goes through UIAutomationCore's COM
// client: CUIAutomation8's IUIAutomation5 by its vtable, the handler a COM object of our own (four slots, no runtime
// wrapper). The vtable slots are UIAutomationClient.h's order: IUnknown's 3, then IUIAutomation's ElementFromHandle at 3
// and IUIAutomation5's AddNotificationEventHandler at 65 (IUIAutomation 55, IUIAutomation2 6, IUIAutomation3 2,
// IUIAutomation4 2 methods before it).
using System;
using System.Collections.Concurrent;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Threading;

namespace Veyq.Drive;

internal static unsafe class Notifications
{
    private static readonly Guid ClsidCuiAutomation8 = new("e22ad333-b25f-460c-83d0-0581107395c9");
    private static readonly Guid IidIuiAutomation5 = new("25f700c8-d816-4057-a9dc-3cbdee77e256");
    private static readonly Guid IidIUnknown = new("00000000-0000-0000-c000-000000000046");
    private static readonly Guid IidHandler = new("c7cb2637-e6c2-4d0c-85de-4948c02175c7");

    private static readonly ConcurrentQueue<string> Heard = new();
    private static nint _handler;
    private static nint _automation;

    /// <summary>Registers for every notification event in <paramref name="window"/>'s subtree (once per drive).</summary>
    public static string Listen(nint window)
    {
        if (_handler != 0) return "already listening\n";
        string? said = null;
        // on an MTA thread of its own: UIA calls the handler on its own threads, which an STA without a pump would hold up
        var thread = new Thread(() => said = Register(window));
        thread.SetApartmentState(ApartmentState.MTA);
        thread.Start();
        thread.Join();
        return said ?? "";
    }

    private static string Register(nint window)
    {
        var clsid = ClsidCuiAutomation8;
        var iid = IidIuiAutomation5;
        Check(CoCreateInstance(&clsid, 0, 1 /* CLSCTX_INPROC_SERVER */, &iid, out _automation), "CoCreateInstance(CUIAutomation8)");
        var vtable = *(nint**)_automation;
        nint element;
        Check(((delegate* unmanaged[Stdcall]<nint, nint, nint*, int>)vtable[3 + 3])(_automation, window, &element), "ElementFromHandle");
        _handler = MakeHandler();
        // TreeScope_Subtree: the element, its children and its descendants
        Check(((delegate* unmanaged[Stdcall]<nint, nint, int, nint, nint, int>)vtable[3 + 65])(_automation, element, 7, 0, _handler),
            "AddNotificationEventHandler");
        return "listening for notification events\n";
    }

    /// <summary>What was heard since the last `heard`, a line each: kind, processing, the text and the activity id.</summary>
    public static string Take()
    {
        var text = new System.Text.StringBuilder();
        while (Heard.TryDequeue(out var line)) text.Append("notification: ").AppendLine(line);
        return text.Length > 0 ? text.ToString() : "no notification heard\n";
    }

    private static void Check(int hr, string what)
    {
        if (hr < 0) throw new InvalidOperationException($"{what}: 0x{hr:X8}");
    }

    /// <summary>A COM object of four slots — QueryInterface, AddRef, Release, HandleNotificationEvent —, never freed: it
    /// lives as long as the drive.</summary>
    private static nint MakeHandler()
    {
        var vtable = (nint*)NativeMemory.Alloc((nuint)(4 * sizeof(nint)));
        vtable[0] = (nint)(delegate* unmanaged[Stdcall]<nint, Guid*, nint*, int>)&QueryInterface;
        vtable[1] = (nint)(delegate* unmanaged[Stdcall]<nint, uint>)&AddRef;
        vtable[2] = (nint)(delegate* unmanaged[Stdcall]<nint, uint>)&Release;
        vtable[3] = (nint)(delegate* unmanaged[Stdcall]<nint, nint, int, int, nint, nint, int>)&HandleNotificationEvent;
        var self = (nint*)NativeMemory.Alloc((nuint)sizeof(nint));
        self[0] = (nint)vtable;
        return (nint)self;
    }

    [UnmanagedCallersOnly(CallConvs = [typeof(CallConvStdcall)])]
    private static int QueryInterface(nint self, Guid* iid, nint* result)
    {
        if (*iid == IidIUnknown || *iid == IidHandler)
        {
            *result = self;
            return 0;
        }
        *result = 0;
        return unchecked((int)0x80004002); // E_NOINTERFACE
    }

    [UnmanagedCallersOnly(CallConvs = [typeof(CallConvStdcall)])]
    private static uint AddRef(nint self) => 2;

    [UnmanagedCallersOnly(CallConvs = [typeof(CallConvStdcall)])]
    private static uint Release(nint self) => 1;

    [UnmanagedCallersOnly(CallConvs = [typeof(CallConvStdcall)])]
    private static int HandleNotificationEvent(nint self, nint sender, int kind, int processing, nint displayString, nint activityId)
    {
        var text = displayString == 0 ? "" : Marshal.PtrToStringBSTR(displayString);
        var activity = activityId == 0 ? "" : Marshal.PtrToStringBSTR(activityId);
        Heard.Enqueue($"kind {kind}, processing {processing}, activity '{activity}': {text}");
        return 0;
    }

    [DllImport("ole32.dll")]
    private static extern int CoCreateInstance(Guid* clsid, nint outer, uint context, Guid* iid, out nint result);
}
