// Debug builds only — the account's Settings drawn without a server (a visual pass's harness, uc.account.sessions), as
// DevScan is the camera's and as Linux's dev_settings.rs: started with VEYQ_DEV_SETTINGS=1, the window shows an
// AccountSettingsPage alone over Fake, a state as the core would send it (every card the page has: this device, a named
// and an unnamed other session, one whose saved key can go, a device with a saved key and no session). No page is open in
// the core, so each action is refused and said so in the log. A release build has no such door.

namespace Veyq.Core;

using System;
using Veyq.Bridge.Wire;

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
                new SettingsFamily { id = "f-here", createdAt = now - 9 * day, lastUsedAt = now, current = true, label = "WIN-VM", removesKey = false, createdShown = "Nov 12, 2026, 9:41 AM", lastUsedShown = "Nov 21, 2026, 9:41 AM" },
                new SettingsFamily { id = "f-laptop", createdAt = now - 40 * day, lastUsedAt = now - 2 * day, current = false, label = "Work laptop", removesKey = true, createdShown = "Oct 12, 2026, 9:41 AM", lastUsedShown = "Nov 19, 2026, 9:41 AM" },
                new SettingsFamily { id = "f-old", createdAt = now - 120 * day, lastUsedAt = now - 30 * day, current = false, label = null, removesKey = false, createdShown = "Jul 24, 2026, 9:41 AM", lastUsedShown = "Oct 22, 2026, 9:41 AM" },
            ],
            keptDevices = [new SettingsKeptDevice { deviceId = "d-phone", label = "Old phone" }],
            otherSavedKeys = true,
            hasOthers = true,
            addDevice = true,
            working = false,
            refusal = null,
            registerRefusal = null,
            storage = null,
            storageUnavailable = null,
            askingRemoveAll = false,
            limitUnits = [],
            sections = [],
        };
    }
}
