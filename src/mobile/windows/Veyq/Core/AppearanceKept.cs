// What this install keeps of its appearance and language (ADR 0162): the core's ids (`ThemeChoice`, `LanguageChoice`:
// the web's stored values), in `appearance.json` in the app's data folder beside the device store. Nothing else is in it,
// and nothing of it leaves the device. No WinUI type here, so Veyq.Tests holds it.

namespace Veyq.Core;

using System;
using System.IO;
using System.Text.Json;

public sealed record AppearanceKept(string Theme, string Language)
{
    public const string File = "appearance.json";

    /// <summary>Nothing kept: the system's appearance and language.</summary>
    public static readonly AppearanceKept Systems = new("system", "system");

    /// <summary>What <paramref name="folder"/> keeps; the system's for a file that is not there or not read.</summary>
    public static AppearanceKept Read(string folder)
    {
        try
        {
            using var doc = JsonDocument.Parse(System.IO.File.ReadAllText(Path.Combine(folder, File)));
            string Get(string name) =>
                doc.RootElement.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString()! : "system";
            return new AppearanceKept(Get("theme"), Get("language"));
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException)
        {
            return Systems;
        }
    }

    /// <summary>Keeps this choice in <paramref name="folder"/>.</summary>
    public void Write(string folder)
    {
        Directory.CreateDirectory(folder);
        System.IO.File.WriteAllText(Path.Combine(folder, File), JsonSerializer.Serialize(new { theme = Theme, language = Language }));
    }

    /// <summary>The words' culture for a kept language id (the .resw catalogs' folders): null for the system's and for an
    /// id the core does not offer.</summary>
    public static string? CultureOf(string language) => language switch
    {
        "en" => "en-US",
        "de" => "de-DE",
        _ => null,
    };
}
