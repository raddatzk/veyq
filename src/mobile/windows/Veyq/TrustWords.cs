// What the device trust's screens say (the CR device-trust-on-linux-and-windows, D5, D7), the core's answers drawn: what
// the unlock of a trusted account says (by its record's method and, for a remembered one, by its record's level), and
// how "Manage servers" names a server's trust. Linux's sign_in::unlock_words. No rule here: each answer is the core's;
// this only picks its words. The offer to stay signed in is drawn in Phase 7.
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

    /// <summary>"Manage servers"' line for how this device trusts a server, null where it trusts it not.</summary>
    public static string? Trusted(Method? method) => method switch
    {
        Method.DeviceKey => Words.ManageTrustedRemembered(),
        Method.Device => Words.ManageTrustedPassword(),
        _ => null,
    };
}
