// What the device trust's screens say (the CR device-trust-on-linux-and-windows, D5), the core's answers drawn: which
// "Stay signed in" this device offers (StaySignedIn, the store's own answer beside the sign-in), what the unlock of a
// trusted account says (by its record's method and, for a remembered one, by StaySignedIn beside that unlock), and how
// "Manage servers" names a server's trust. Linux's sign_in::remember_words and unlock_words, with Windows' own label
// where Windows Hello is what opens it. No rule here: each answer is the core's; this only picks its words.
using Veyq.Bridge.Wire;

namespace Veyq;

internal static class TrustWords
{
    /// <summary>"Stay signed in"'s label and the line under it, or null where nothing can be kept and it is hidden.
    /// PRESENCE is Windows Hello's key on Windows, LOGIN the Windows sign-in (DPAPI) without it.</summary>
    public static (string Label, string Hint)? Remember(StaySignedIn? stay) => stay switch
    {
        StaySignedIn.Presence => (Words.SigninRememberHello(), Words.SigninRememberHintHello()),
        StaySignedIn.Login => (Words.SigninRemember(), Words.SigninRememberHintLogin()),
        _ => null,
    };

    /// <summary>The unlock's line and button: the password's for a password-trusted account; for a remembered one the
    /// presence line where Hello asks, else (LOGIN: nothing is asked, so the unlock shows only after a resume that failed)
    /// the line that says so and "Try again" (owner 2026-11-11). Both buttons send the same unlock.</summary>
    public static (string Hint, string Button) Unlock(Method method, StaySignedIn? stay) => (method, stay) switch
    {
        (Method.Device, _) => (Words.UnlockPasswordHint(), Words.UnlockSubmit()),
        (Method.DeviceKey, StaySignedIn.Login) => (Words.UnlockLoginHint(), Words.UnlockRetry()),
        _ => (Words.UnlockRememberedHint(), Words.UnlockSubmit()),
    };

    /// <summary>"Manage servers"' line for how this device trusts a server, null where it trusts it not.</summary>
    public static string? Trusted(Method? method) => method switch
    {
        Method.DeviceKey => Words.ManageTrustedRemembered(),
        Method.Device => Words.ManageTrustedPassword(),
        _ => null,
    };
}
