// What is said and how long it lives (ADR 0154 decision 4): a notice's words with the core's kind (Spoken), and the
// core's lifetimes, word kinds and separator (Lives), read once as the core starts. No duration, no kind and no
// separator here is this app's. Free of WinUI, so Veyq.Tests compiles it beside SaidNotice.cs; NoticeBar.cs draws it.

namespace Veyq.Core;

using System;
using System.Collections.Generic;
using System.Linq;
using Veyq.Bridge.Wire;

/// <summary>What is said under the bar: its words and how long it lives.</summary>
public sealed record Spoken(string Text, NoticeKind Kind)
{
    /// <summary>A core notice, living as its kind says.</summary>
    public static Spoken Of(Notice n) => new(Said.Notice(n), n.kind);

    /// <summary>Several of the core's notices said as one (<paramref name="text"/>): held where any is held.</summary>
    public static Spoken Of(IReadOnlyCollection<Notice> notices, string text) =>
        new(text, notices.Any(n => n.kind == NoticeKind.Held) || notices.Count == 0 ? NoticeKind.Held : notices.First().kind);

    /// <summary>A word this app says itself (a failure of its own: a file not local, a save that threw) by its catalog
    /// <paramref name="key"/>, living as the core's table says that word lives (Lives.KindOf).</summary>
    public static Spoken Own(string key, string text) => new(text, Lives.KindOf(key));
}

/// <summary>The core's lifetimes, its word kinds and its line separator (Call.NoticeLifetimes):
/// read once as the core starts. Until read, a said notice stays as a held one does.</summary>
public static partial class Lives
{
    private static Answer.Lifetimes? _lifetimes;
    private static Dictionary<string, NoticeKind> _kinds = [];

    /// <summary>What a line's parts are joined with (the core's Shown.SEPARATOR).</summary>
    public static string Separator { get; private set; } = "\n";

    /// <summary>How long "copied" shows; null until read.</summary>
    public static TimeSpan? Copied => _lifetimes is { } l ? TimeSpan.FromMilliseconds(l.copiedMs) : null;

    /// <summary>How long the player says that nothing plays; null until read.</summary>
    public static TimeSpan? NothingPlays => _lifetimes is { } l ? TimeSpan.FromMilliseconds(l.nothingPlaysMs) : null;

    /// <summary>How long a notice of <paramref name="kind"/> shows before it closes itself; null while it stays.</summary>
    public static TimeSpan? Of(NoticeKind kind) => kind == NoticeKind.Said && _lifetimes is { } l ? TimeSpan.FromMilliseconds(l.saidMs) : null;

    /// <summary>The kind the core gives word <paramref name="key"/> by default: held unless its table says otherwise.</summary>
    public static NoticeKind KindOf(string key) => _kinds.TryGetValue(key, out var kind) ? kind : NoticeKind.Held;

    /// <summary>A line's parts joined as every app and the web join them.</summary>
    public static string Joined(IEnumerable<string?> parts) => string.Join(Separator, parts.Where(p => !string.IsNullOrEmpty(p)));

    /// <summary>Takes the core's answer (Load's; a test's).</summary>
    public static void Take(Answer.Lifetimes l)
    {
        _lifetimes = l;
        _kinds = l.saidWords.Select(k => (k, NoticeKind.Said)).Concat(l.undoWords.Select(k => (k, NoticeKind.Undo)))
            .ToDictionary(p => p.k, p => p.Item2);
        if (l.separator.Length > 0) Separator = l.separator;
    }
}
