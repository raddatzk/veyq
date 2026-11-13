// Debug builds only — the account's Settings drawn without a server (cr-app-account-settings phase 4's visual pass), as
// DevScan is the camera's and as Linux's dev_settings.rs: started with VEYQ_DEV_SETTINGS=1, the window shows an
// AccountSettingsPage alone over Fake, a state as the core would send it (every card the page has: this device, a named
// and an unnamed other session, one whose saved key can go, a device with a saved key and no session). No page is open in
// the core, so each action is refused and said so in the log. A release build has no such door.
using System;
using Veyq.Bridge.Wire;

namespace Veyq;

internal static class DevSettings
{
    /// <summary>Whether the harness was asked for; false in a release build.</summary>
    public static bool Requested
    {
        get
        {
#if DEBUG
            return Environment.GetEnvironmentVariable("VEYQ_DEV_SETTINGS") == "1";
#else
            return false;
#endif
        }
    }

    /// <summary>The state the harness draws: a staying-signed-in device and three sessions, an unconfirmed address.</summary>
    public static SettingsShown Fake()
    {
        const long day = 86_400_000;
        const long now = 1_794_475_800_000;
        return new SettingsShown
        {
            email = "p@veyq.localhost",
            emailConfirmed = false,
            device = "WIN-VM",
            trusted = Method.DeviceKey,
            level = StaySignedIn.Presence,
            levels = [StaySignedIn.Presence, StaySignedIn.Login],
            families =
            [
                new SettingsFamily { id = "f-here", createdAt = now - 9 * day, lastUsedAt = now, current = true, label = "WIN-VM", removesKey = false },
                new SettingsFamily { id = "f-laptop", createdAt = now - 40 * day, lastUsedAt = now - 2 * day, current = false, label = "Work laptop", removesKey = true },
                new SettingsFamily { id = "f-old", createdAt = now - 120 * day, lastUsedAt = now - 30 * day, current = false, label = null, removesKey = false },
            ],
            keptDevices = [new SettingsKeptDevice { deviceId = "d-phone", label = "Old phone" }],
            otherSavedKeys = true,
            hasOthers = true,
            addDevice = true,
            working = false,
            refusal = null,
            registerRefusal = null,
        };
    }
}
