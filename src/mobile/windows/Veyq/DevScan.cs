// Debug builds only — the QR camera's test harness (the QR CR, phase 5), reached without a sign-in, as Android's, Apple's
// and Linux's DevScan: started with VEYQ_DEV_SCAN=1, the window shows a QrCamera alone over a scan of kind SCHEME (any veyq
// code, found as its scheme alone); with VEYQ_DEV_CAMERA=<image> as well, that image is the camera. Its states go to the
// log (`veyq-scan: state …`); the scheme is all that is shown, never the code's text. A release build has no such door.
using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Veyq.Bridge.Wire;

namespace Veyq;

internal static class DevScan
{
    /// <summary>Whether the harness was asked for; false in a release build.</summary>
    public static bool Requested
    {
        get
        {
#if DEBUG
            return Environment.GetEnvironmentVariable("VEYQ_DEV_SCAN") == "1";
#else
            return false;
#endif
        }
    }

    /// <summary>The harness's camera: what the scan came to as an icon, a found code's scheme beside it.</summary>
    public static QrCamera Open()
    {
        Log.Write("veyq-scan: the scan harness (debug build)");
        return QrCamera.Open(ScanKind.Scheme, Draw, shown =>
            Log.Write(shown is { state: ScanState.Found, found: { } scheme } ? $"veyq-scan: state Found({scheme})" : $"veyq-scan: state {shown.state}"));
    }

    private static UIElement? Draw(ScanShown shown)
    {
        // Segoe Fluent Icons: CheckMark, Camera, Blocked (a debug harness: the log says the state)
        var glyph = shown.state switch
        {
            ScanState.Found => "\uE73E",
            ScanState.NoCamera => "\uE722",
            ScanState.Denied => "\uE733",
            _ => null,
        };
        if (glyph is null) return null;
        var panel = new StackPanel { Spacing = 12, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        panel.Children.Add(new FontIcon { Glyph = glyph, FontSize = 48 });
        // the scheme as the core named it: no word of ours
        if (shown.found is { } scheme) panel.Children.Add(new TextBlock { Text = scheme, HorizontalAlignment = HorizontalAlignment.Center });
        return panel;
    }
}
