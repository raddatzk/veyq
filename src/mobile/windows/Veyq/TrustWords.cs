// What the device trust's screens say (the CR device-trust-on-linux-and-windows, D5, D7), the core's answers drawn: what
// the unlock of a trusted account says (by its record's method and, for a remembered one, by its record's level), what
// the offer to stay signed in lists, how "Manage servers" names a server's trust and which changes it lists. Linux's
// sign_in::unlock_words, offer_choices and manage::changes. No rule here: each answer is the core's; this only picks its
// words and leaves out the change to where a server already is.
using System.Collections.Generic;
using System.Linq;
using Veyq.Bridge.Wire;

namespace Veyq;

internal static class TrustWords
{
    /// <summary>The unlock's line and button: the password's for a password-trusted account; for a remembered one the
    /// presence line where Hello asks, else (LOGIN: nothing is asked, so the unlock shows only after a resume that failed)
    /// the line that says so and "Try again" (owner 2026-11-11). Both buttons send the same unlock. <paramref name="level"/>
    /// is the record's own (TrustedDevice.level).</summary>
    public static (string Hint, string Button) Unlock(Method method, StaySignedIn? level) => (method, level) switch
    {
        (Method.Device, _) => (Words.UnlockPasswordHint(), Words.UnlockSubmit()),
        (Method.DeviceKey, StaySignedIn.Login) => (Words.UnlockLoginHint(), Words.UnlockRetry()),
        _ => (Words.UnlockRememberedHint(), Words.UnlockSubmit()),
    };

    /// <summary>"Manage servers"' line for how this device trusts a server — the password, Windows Hello (PRESENCE),
    /// staying signed in without a check (LOGIN) — null where it trusts it not.</summary>
    public static string? Trusted(Method? method, StaySignedIn? level) => (method, level) switch
    {
        (Method.DeviceKey, StaySignedIn.Presence) => Words.ManageTrustedHello(),
        (Method.DeviceKey, _) => Words.ManageTrustedRemembered(),
        (Method.Device, _) => Words.ManageTrustedPassword(),
        _ => null,
    };

    /// <summary>A level's name on Windows and the line that says what it means: Windows Hello where the person confirms
    /// (PRESENCE), staying signed in where the Windows sign-in opens it (LOGIN).</summary>
    public static (string Title, string Hint) Level(StaySignedIn level) => level switch
    {
        StaySignedIn.Presence => (Words.OfferHello(), Words.OfferHelloHint()),
        _ => (Words.SigninRemember(), Words.OfferLoginHint()),
    };

    /// <summary>The offer's answers, in order: each level the core offers (Gate.Signed.offer, in the core's order), then
    /// "Not now" (null) — each with its title and line.</summary>
    public static IReadOnlyList<(StaySignedIn? Level, string Title, string Hint)> Offer(IEnumerable<StaySignedIn> levels) =>
        levels.Select(l => ((StaySignedIn?)l, Level(l).Title, Level(l).Hint))
            .Append((null, Words.OfferNotNow(), Words.OfferNotNowHint()))
            .ToList();

    /// <summary>The changes "Manage servers" lists for a server trusted by <paramref name="method"/> at
    /// <paramref name="level"/>, the device offering <paramref name="levels"/>: each offered level it is not at, then the
    /// way back to the password (null) from staying signed in. None where the device trusts it not.</summary>
    public static IReadOnlyList<StaySignedIn?> Changes(Method? method, StaySignedIn? level, IEnumerable<StaySignedIn> levels)
    {
        if (method is null) return [];
        var changes = levels.Where(l => !(method == Method.DeviceKey && level == l)).Select(l => (StaySignedIn?)l).ToList();
        if (method == Method.DeviceKey) changes.Add(null);
        return changes;
    }
}
