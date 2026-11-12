// What the core says, in words (ADR 0123's Consequences, the Windows app): a Notice by its key and arguments through
// the generated Words.Say, a Piece piece by piece, a line's pieces joined — Linux's said.rs, Android's NoticeText.kt,
// Apple's say(notice). Nothing here decides what is said; that is the core's (ADR 0129 decision 1). Said.cs writes the
// money, days and sizes; this half needs the wire's types, which the words' tests do not compile.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using Veyq.Bridge.Wire;

namespace Veyq;

public static partial class Said
{
    /// <summary>A notice in the person's language; its key itself (and a line in the log) where the catalog has no such
    /// word, which only a core built from another words.json sends.</summary>
    public static string Notice(Notice n)
    {
        // a {n:int} placeholder takes a number, a {n} one its text: the core's arguments are values, the catalog's
        // methods typed, so the typed reading first, then every argument as its text
        var typed = n.args.Select(a => a is NoticeArg.Int i ? (object)(long)i.value : a is NoticeArg.Long l ? l.value : ArgText(a)).ToList();
        var texts = n.args.Select(a => (object)ArgText(a)).ToList();
        var said = Words.Say(n.msg, typed) ?? Words.Say(n.msg, texts);
        if (said is null) Log.Write($"no word for {n.msg} with {n.args.Count} arguments");
        return said ?? n.msg;
    }

    /// <summary>A word by its catalog key alone (a key the core sent: a group's heading, an application's name).</summary>
    public static string Key(string key) => Notice(new Notice { msg = key, args = [] });

    /// <summary>One piece of a line.</summary>
    public static string Piece(Piece p) => p switch
    {
        Piece.Said s => Notice(s.notice),
        Piece.Plain t => t.text,
        Piece.Day d => Day(d.iso),
        Piece.Size s => Size(s.bytes),
        Piece.Money m => Money(m.amount, m.currency, m.minorUnits),
        _ => "",
    };

    /// <summary>A line's pieces joined as the other apps join them.</summary>
    public static string Line(IEnumerable<Piece> pieces) => string.Join(" · ", pieces.Select(Piece));

    /// <summary>A moment in the local zone, as the culture writes a short date and a short time.</summary>
    public static string Moment(long epochMs) =>
        DateTimeOffset.FromUnixTimeMilliseconds(epochMs).ToLocalTime().ToString("g", CultureInfo.CurrentCulture);

    private static string ArgText(NoticeArg a) => a switch
    {
        NoticeArg.Amount m => Money(m.amount, m.currency, m.minorUnits),
        NoticeArg.Bytes b => Size(b.count),
        NoticeArg.Int i => i.value.ToString(CultureInfo.CurrentCulture),
        NoticeArg.Long l => l.value.ToString(CultureInfo.CurrentCulture),
        NoticeArg.Joined j => string.Join(j.separator, j.parts.Select(ArgText)),
        NoticeArg.Moment m => Moment(m.epochMs),
        NoticeArg.Notice n => Notice(n.notice),
        NoticeArg.Text t => t.text,
        _ => "",
    };
}
