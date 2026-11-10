// Debug builds only — the Windows counterpart of Linux's dev_login.rs, Apple's DevLogin.swift and the web's ?devlogin:
// `run.py run --devlogin` hands the seeded dev principal of .run/.env to the app's environment through the launch file
// Veyq.Drive deletes on reading (VEYQ_DEV_SERVER, VEYQ_DEV_EMAIL, VEYQ_DEV_PASSWORD, VEYQ_DEV_SECRET_KEY), and the app
// signs in through its real sign-in once, when the core first asks for one — so a pass never types a password into the
// window. A release build compiles none of this.
using System;
using Veyq.Bridge.Wire;

namespace Veyq;

internal static class DevLogin
{
#if DEBUG
    private static bool _taken;

    private static readonly string[] Names = ["VEYQ_DEV_SERVER", "VEYQ_DEV_EMAIL", "VEYQ_DEV_PASSWORD", "VEYQ_DEV_SECRET_KEY"];
#endif

    /// <summary>The sign-in the environment names, once per process; null in a release build, without the four
    /// variables, or after it was taken. Taken, they leave the process's environment.</summary>
    public static Call.SessionSignIn? Take()
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
        foreach (var name in Names) Environment.SetEnvironmentVariable(name, null);
        Log.Write("signing in as the dev principal (debug build)");
        return new Call.SessionSignIn { server = values[0], email = values[1], password = values[2], secretKey = values[3] };
#else
        return null;
#endif
    }
}
