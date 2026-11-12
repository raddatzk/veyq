// Where each list was scrolled to, by its place (Linux's shell::ListScrolls, rule for rule): the shell draws a list anew on
// every state and whenever an object opened from it closes (the object takes its place, ADR 0122), and each new one is put
// back where its place's list stood (the owner's report, 2026-11-12: a long list jumped to its top). This holds the rule;
// ShellView follows each list's ScrollViewer.
using System.Collections.Generic;
using Veyq.Bridge.Wire;
using ApplicationId = Veyq.Bridge.Wire.ApplicationId;

namespace Veyq;

internal sealed class ListScrolls
{
    private readonly Dictionary<string, double> _at = new();

    /// <summary>Where the list of <paramref name="place"/> stood last; null where it was never moved from its top.</summary>
    public double? At(string place) => _at.TryGetValue(place, out var at) && at > 0 ? at : null;

    /// <summary>The list of <paramref name="place"/> now stands <paramref name="offset"/> down.</summary>
    public void Moved(string place, double offset) => _at[place] = offset;

    /// <summary>A list's place: an application's list in a space, or Home's level (its space, its folder and its filter).</summary>
    public static string Place(ApplicationId? application, string? space, string? folder, ApplicationId? filter) => application is { } app
        ? $"app:{app}:{space}"
        : $"home:{space}:{folder}:{(filter is { } f ? f.ToString() : "all")}";

    /// <summary>Where a list <paramref name="target"/> down is put back, its content <paramref name="extent"/> tall in a
    /// view <paramref name="viewport"/> tall: there once it reaches that far, else nothing yet — or, <paramref name="settled"/>
    /// (laid out, grown shorter meanwhile), its end.</summary>
    public static double? PutBack(double target, double extent, double viewport, bool settled)
    {
        var end = System.Math.Max(0, extent - viewport);
        if (end >= target) return target;
        if (settled && end > 0) return end;
        return null;
    }
}

