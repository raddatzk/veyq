// What a received link's screen offers and says (ADR 0150 decisions 2–4, 6–8; inbox #1018), without a widget: what its
// header offers in which mode, the guest-write record's notice, when its content is drawn anew, a refusal's and a
// problem's words, and when a kept link's page offers its actions — Linux's received.rs and bookmark_page.rs rules, rule
// for rule. ReceivedTextTest holds them.

namespace Veyq.Core;

using System;
using System.Linq;
using System.Text.Json;
using Veyq.Bridge.Wire;

/// <summary>How a received link's screen stands: on its own above everything, or in place on a kept link's page.</summary>
internal enum ReceivedMode
{
    OnItsOwn,
    InPlace,
}

/// <summary>What a guest's header offers: Back (on its own always, at the root it asks first; in place only inside the
/// link), Keep, the report, Not you?.</summary>
internal readonly record struct ReceivedOffers(bool Back, bool Keep, bool Report, bool NotYou);

internal static class ReceivedText
{
    /// <summary>The offers for <paramref name="s"/> in <paramref name="mode"/>: Keep and the report only behind the gates,
    /// Keep only on its own, Not you? only for a named guest.</summary>
    public static ReceivedOffers Offers(ReceivedShown s, ReceivedMode mode)
    {
        var ready = s.gate == ReceivedGate.Ready;
        return new ReceivedOffers(
            Back: mode == ReceivedMode.OnItsOwn || !s.atRoot,
            Keep: ready && s.keepOffered && mode == ReceivedMode.OnItsOwn,
            Report: ready,
            NotYou: ready && s.guestName is not null);
    }

    /// <summary>The guest-write record's notice for <paramref name="days"/> (the web's two sentences, ADR 0093 § 10), or
    /// null for a link that does not write.</summary>
    public static string? RecordNotice(int? days) =>
        days is int d && d > 0 ? $"{Words.ShareGuestRecordKept(d)} {Words.ShareGuestRecordObjection()}" : null;

    /// <summary>What the content shows, as a key: drawn anew only when it changed (a level's rows, another page, another
    /// gate, the password asked again), never for a busy flag or a name.</summary>
    public static string DrawnKey(ReceivedShown s)
    {
        var page = s.page is { } p
            ? $"{p.kind}|{p.file}|{p.gallery}|{p.pot}|{string.Join(",", p.rows.Select(r => $"{r.id}:{string.Join("/", r.menu.Select(a => a.id))}"))}|{string.Join("\n", p.sent)}"
              // the level's move (#1063): its tools and, while it picks, what is selected and the bar's state
              + $"|{string.Join(",", p.tools)}|{(p.selection is { } sel ? $"{string.Join(",", sel.selected)}|{string.Join(",", sel.actions.Select(a => $"{a.id}:{a.enabled}:{a.cannot is not null}"))}" : "-")}"
            : "";
        return $"{s.gate}|{s.refusal}|{s.askedAgain}|{page}";
    }

    /// <summary>Why the app does not open the link's instance, naming its host.</summary>
    public static string Refusal(ReceivedRefusal? refusal, string host) => refusal switch
    {
        ReceivedRefusal.OtherVersion => Words.LinkRefusedOtherVersion(host),
        ReceivedRefusal.NoLinks => Words.LinkRefusedNoLinks(host),
        _ => Words.LinkRefusedUnreachable(host),
    };

    /// <summary>A <see cref="Msg"/> the core answered with, in the person's words: the wire spells it as the catalog's key
    /// in capitals.</summary>
    public static string Said(Msg m)
    {
        var key = JsonSerializer.Serialize(m).Trim('"').ToLowerInvariant();
        return Words.Say(key, Array.Empty<object>()) ?? key;
    }
}
