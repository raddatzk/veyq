// This install's appearance and language (ADR 0162; the web's Persönlich → Darstellung und Sprache), read once at the
// start: Windows has no per-app language an unpackaged app can rely on, so the app keeps its own (AppearanceKept), and
// both apply at the next start — the application theme can be set only before the first window (App's constructor), and
// a brush a screen looks up in code is that theme's (App.xaml.cs), so a theme changed on the window alone would leave
// them light on dark; the words' catalog is opened once (MrtWords) and the bridge's words start in its language. A debug
// build's VEYQ_THEME and VEYQ_LANGUAGE (DebugEnv) still win, for a pass.

namespace Veyq.Core;

using Microsoft.UI.Xaml;

internal static class Appearance
{
    /// <summary>What this start was made with.</summary>
    public static AppearanceKept Started { get; } = AppearanceKept.Read(AppData.Folder);

    /// <summary>The theme of this start's every window and dialog.</summary>
    public static ElementTheme Theme => DebugEnv.Theme != ElementTheme.Default
        ? DebugEnv.Theme
        : Started.Theme switch
        {
            "light" => ElementTheme.Light,
            "dark" => ElementTheme.Dark,
            _ => ElementTheme.Default,
        };

    /// <summary>The words' culture of this start; null: the person's languages, as Windows orders them.</summary>
    public static string? Language => DebugEnv.Language ?? AppearanceKept.CultureOf(Started.Language);

    /// <summary>What is kept now (Settings shows it; the next start is made with it).</summary>
    public static AppearanceKept Kept => AppearanceKept.Read(AppData.Folder);

    public static void Keep(AppearanceKept next)
    {
        try
        {
            next.Write(AppData.Folder);
            Log.Write($"appearance kept: theme {next.Theme}, language {next.Language}");
        }
        catch (System.Exception e) when (e is System.IO.IOException or System.UnauthorizedAccessException)
        {
            Log.Write($"the appearance was not kept ({e.GetType().Name})");
        }
    }
}
