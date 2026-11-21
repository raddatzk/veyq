// How a notice lives on Windows (ADR 0154 decision 4; the web's useToast): the core's kind mapped onto the InfoBar
// under the bar — a SAID one closes itself after the core's saidMs, a HELD one stays until its close button, an UNDO one
// stays while the state offers the undo (its owner ends it when the core's offer ends). Every duration and every kind is
// the core's (Notice.Lifetimes, read once as the core starts: Lives); none is this app's. Android's NoticeHost, Apple's
// notice overlay and Linux's toast overlay map the same kinds.

namespace Veyq.Core;

using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
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

/// <summary>The core's lifetimes, its word kinds and its line separator (Call.NoticeLifetimes, Call.ShownSeparator):
/// read once as the core starts. Until read, a said notice stays as a held one does.</summary>
public static class Lives
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

    /// <summary>Asks the core once (StartCore, before anything is said).</summary>
    public static async Task Load()
    {
        try
        {
            if ((await Bridge.Core.CallAsync(new Call.NoticeLifetimes())).Answer is Answer.Lifetimes l)
            {
                _lifetimes = l;
                _kinds = new Dictionary<string, NoticeKind>(l.kinds);
            }
            if ((await Bridge.Core.CallAsync(new Call.ShownSeparator())).Answer is Answer.Name { name: { } separator }) Separator = separator;
            Log.Write($"lives read: said {_lifetimes?.saidMs} ms, {_kinds.Count} words not held");
        }
        catch (Exception e)
        {
            Log.Write($"lives not read ({e.GetType().Name})");
        }
    }
}

/// <summary>The InfoBar under the bar as a notice's place: it shows one notice at a time, as its kind lives.</summary>
public sealed class NoticeBar
{
    private DispatcherQueueTimer? _timer;
    private int _shown;

    public NoticeBar(InfoBar bar) => Bar = bar;

    public InfoBar Bar { get; }

    /// <summary>Whether an undo's offer stands in it.</summary>
    public bool Offering { get; private set; }

    /// <summary><paramref name="s"/>, alone in the bar, for as long as its kind lives.</summary>
    public void Say(Spoken s) => Show(s.Text, s.Kind, null, Lives.Of(s.Kind));

    /// <summary><paramref name="text"/> for <paramref name="life"/> (the player's "nothing plays"; null: until closed).</summary>
    public void SayFor(string text, TimeSpan? life) => Show(text, NoticeKind.Said, null, life);

    /// <summary>An undo's offer: <paramref name="s"/> with <paramref name="undo"/> as the bar's action, standing until
    /// <see cref="EndOffer"/> (the core's offer ended) or the undo is pressed; without one it lives as its kind says.</summary>
    public void Offer(Spoken s, Button? undo)
    {
        if (undo is null)
        {
            Say(s);
            return;
        }
        Show(s.Text, NoticeKind.Undo, undo, null);
        Offering = true;
    }

    /// <summary>The offer ended: the bar closes where it still shows it.</summary>
    public void EndOffer()
    {
        if (!Offering) return;
        Offering = false;
        Bar.ActionButton = null;
        Bar.IsOpen = false;
    }

    private void Show(string text, NoticeKind kind, Button? action, TimeSpan? life)
    {
        var shown = ++_shown;
        Offering = false;
        _timer?.Stop();
        Bar.Content = null;
        Bar.ActionButton = action;
        Bar.IsClosable = true;
        Bar.Severity = kind switch
        {
            NoticeKind.Said => InfoBarSeverity.Success,
            NoticeKind.Undo => InfoBarSeverity.Informational,
            _ => InfoBarSeverity.Error,
        };
        Bar.Message = text;
        Bar.IsOpen = true;
        if (life is not { } span) return;
        var timer = _timer = Bar.DispatcherQueue.CreateTimer();
        timer.Interval = span;
        timer.IsRepeating = false;
        timer.Tick += (_, _) =>
        {
            if (shown == _shown) Bar.IsOpen = false;
        };
        timer.Start();
    }
}
