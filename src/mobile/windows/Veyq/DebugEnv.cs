// What a pass sets on a debug build alone (cr-the-windows-app-over-the-native-core D7): VEYQ_THEME (light, dark) and
// VEYQ_LANGUAGE (a BCP-47 tag: the words' language). A release build reads neither and follows the system.
using System;
using Microsoft.UI.Xaml;

namespace Veyq;

internal static class DebugEnv
{
#if DEBUG
    public static string? Language => Environment.GetEnvironmentVariable("VEYQ_LANGUAGE") is { Length: > 0 } l ? l : null;

    public static ElementTheme Theme => Environment.GetEnvironmentVariable("VEYQ_THEME") switch
    {
        "light" => ElementTheme.Light,
        "dark" => ElementTheme.Dark,
        _ => ElementTheme.Default,
    };
#else
    public static string? Language => null;

    public static ElementTheme Theme => ElementTheme.Default;
#endif
}
