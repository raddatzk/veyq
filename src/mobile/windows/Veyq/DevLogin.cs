// Debug builds only — the Windows counterpart of Linux's dev_login.rs, Apple's DevLogin.swift and the web's ?devlogin,
// so a pass never types a password into the window. A release build compiles none of this.
// - `run.py run --devlogin` hands the seeded dev principal of .run/.env to the app's environment through the launch file
//   Veyq.Drive deletes on reading (VEYQ_DEV_SERVER, VEYQ_DEV_EMAIL, VEYQ_DEV_PASSWORD, VEYQ_DEV_SECRET_KEY). The app fills
//   its real sign-in with them once, when the core first asks for one, and signs in through it, "Stay signed in" as it
//   stands (off) — unless VEYQ_DEV_FILL is set (`--fill`): then it only fills the form, and the pass turns "Stay signed
//   in" and presses Sign in itself.
// - `run.py run --devlogin --unlock` hands the password alone (VEYQ_DEV_UNLOCK_PASSWORD, Android's `--unlock`, Apple's
//   variable): the unlock of a password-trusted account takes it once, when the core shows that unlock; and "Manage
//   servers"' way back to the password trust fills its password field with it once (fill only: the pass presses the
//   button), so the agent never types it.
using System;

namespace Veyq;

/// <summary>The seeded principal for the sign-in's fields; <see cref="FillOnly"/>: the pass submits the form itself.</summary>
internal sealed record DevSignIn(string Server, string Email, string Password, string SecretKey, bool FillOnly);

internal static class DevLogin
{
#if DEBUG
    private static bool _taken;
    private static bool _unlockTaken;
    private static bool _passwordTrustTaken;
    private static string? _password;

    private static readonly string[] Names = ["VEYQ_DEV_SERVER", "VEYQ_DEV_EMAIL", "VEYQ_DEV_PASSWORD", "VEYQ_DEV_SECRET_KEY"];
#endif

    /// <summary>The sign-in the environment names, once per process; null in a release build, without the four
    /// variables, or after it was taken. Taken, they leave the process's environment.</summary>
    public static DevSignIn? Take()
    {
#if DEBUG
        if (_taken) return null;
        var values = new string[Names.Length];
        for (var i = 0; i < Names.Length; i++)
        {
            if (Environment.GetEnvironmentVariable(Names[i]) is not { Length: > 0 } v) return null;
            values[i] = v;
        }
        _taken = true;
        var fill = Environment.GetEnvironmentVariable("VEYQ_DEV_FILL") is { Length: > 0 };
        foreach (var name in Names) Environment.SetEnvironmentVariable(name, null);
        Log.Write(fill ? "filling the sign-in as the dev principal (debug build)" : "signing in as the dev principal (debug build)");
        return new DevSignIn(values[0], values[1], values[2], values[3], fill);
#else
        return null;
#endif
    }

    /// <summary>The password for a password-trusted account's unlock, once per process; null in a release build, without
    /// VEYQ_DEV_UNLOCK_PASSWORD, or after it was taken.</summary>
    public static string? TakeUnlock()
    {
#if DEBUG
        if (_unlockTaken || Password() is not { } password) return null;
        _unlockTaken = true;
        Log.Write("unlocking with the dev principal's password (debug build)");
        return password;
#else
        return null;
#endif
    }

    /// <summary>The password for "Manage servers"' way back to the password trust, once per process, apart from
    /// <see cref="TakeUnlock"/>'s; null in a release build, without VEYQ_DEV_UNLOCK_PASSWORD, or after it was taken.</summary>
    public static string? TakePasswordTrust()
    {
#if DEBUG
        if (_passwordTrustTaken || Password() is not { } password) return null;
        _passwordTrustTaken = true;
        Log.Write("filling the password field with the dev principal's password (debug build)");
        return password;
#else
        return null;
#endif
    }

#if DEBUG
    /// <summary>VEYQ_DEV_UNLOCK_PASSWORD, read once: it leaves the process's environment then.</summary>
    private static string? Password()
    {
        if (_password is null && Environment.GetEnvironmentVariable("VEYQ_DEV_UNLOCK_PASSWORD") is { Length: > 0 } v)
        {
            _password = v;
            Environment.SetEnvironmentVariable("VEYQ_DEV_UNLOCK_PASSWORD", null);
        }
        return _password;
    }
#endif
}
