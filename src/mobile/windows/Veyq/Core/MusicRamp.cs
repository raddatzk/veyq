// The pure halves of the music's host side (ADR 0142 decisions 2 and 5), kept apart from WinUI so Veyq.Tests holds them:
// a deck's volume ramp as the steps the host runs (the core asks a ramp, never a stream of volumes: "steps of at most
// 50 ms, run by the host"), and a span's time as the bar says it (the web's AudioBar `m:ss`).

namespace Veyq.Core;

using System;
using System.Collections.Generic;

internal static class MusicRamp
{
    /// <summary>The longest step a ramp takes, in milliseconds: under the core's 50, so a crossfade of 5 s is 125 steps.</summary>
    public const int StepMs = 40;

    /// <summary>The volumes a ramp from <paramref name="from"/> to <paramref name="to"/> over <paramref name="overMs"/> sets,
    /// one per step of <see cref="Interval"/>, the last exactly <paramref name="to"/>; one step (set at once) for a ramp of
    /// no length. Volumes are held to 0–1.</summary>
    public static IReadOnlyList<double> Steps(double from, double to, long overMs)
    {
        from = Math.Clamp(from, 0, 1);
        to = Math.Clamp(to, 0, 1);
        var n = Count(overMs);
        var steps = new double[n];
        for (var k = 1; k <= n; k++) steps[k - 1] = k == n ? to : from + (to - from) * k / n;
        return steps;
    }

    /// <summary>How many steps a ramp over <paramref name="overMs"/> takes (at least one).</summary>
    public static int Count(long overMs) => overMs <= 0 ? 1 : (int)Math.Ceiling(overMs / (double)StepMs);

    /// <summary>The time between two steps of a ramp over <paramref name="overMs"/>: never more than <see cref="StepMs"/>.</summary>
    public static TimeSpan Interval(long overMs) => TimeSpan.FromMilliseconds(overMs <= 0 ? 0 : overMs / (double)Count(overMs));
}

internal static class MusicTime
{
    /// <summary><paramref name="ms"/> as the bar shows it, <c>m:ss</c> (the web's AudioBar; hours run on as minutes).</summary>
    public static string Said(long ms)
    {
        var s = Math.Max(0, ms) / 1000;
        return $"{s / 60}:{s % 60:00}";
    }
}
