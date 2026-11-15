//! Debug builds only — the account's Settings drawn without a server (a visual pass's harness, `uc.account.sessions`),
//! as [crate::dev_scan] is the camera's: started with `VEYQ_DEV_SETTINGS=1`, the window opens the Settings dialog over
//! [fake], a state as the core would send it (every row the page has: this device, a named and an unnamed other session,
//! one whose saved key can go, a device with a saved key and no session). No page is open in the core, so each action is
//! refused and said so on stderr. A release build has no such door.
use crate::bridge::wire::{Method, SettingsFamily, SettingsKeptDevice, SettingsShown, StaySignedIn};

/// Whether the harness was asked for; false in a release build.
pub fn requested() -> bool {
    #[cfg(debug_assertions)]
    {
        std::env::var("VEYQ_DEV_SETTINGS").is_ok_and(|v| v == "1")
    }
    #[cfg(not(debug_assertions))]
    false
}

/// The state the harness draws: a staying-signed-in device and three sessions, an unconfirmed address.
pub fn fake() -> SettingsShown {
    let day = 86_400_000;
    let now = 1_794_475_800_000;
    SettingsShown {
        email: "p@veyq.localhost".into(),
        email_confirmed: Some(false),
        device: "fedora".into(),
        trusted: Some(Method::DeviceKey),
        level: Some(StaySignedIn::Login),
        levels: vec![StaySignedIn::Login],
        families: Some(vec![
            SettingsFamily { id: "f-here".into(), created_at: Some(now - 9 * day), last_used_at: Some(now), current: true, label: Some("fedora".into()), removes_key: false },
            SettingsFamily { id: "f-laptop".into(), created_at: Some(now - 40 * day), last_used_at: Some(now - 2 * day), current: false, label: Some("Work laptop".into()), removes_key: true },
            SettingsFamily { id: "f-old".into(), created_at: Some(now - 120 * day), last_used_at: Some(now - 30 * day), current: false, label: None, removes_key: false },
        ]),
        kept_devices: vec![SettingsKeptDevice { device_id: "d-phone".into(), label: "Old phone".into() }],
        other_saved_keys: true,
        has_others: true,
        add_device: true,
        working: false,
        refusal: None,
        register_refusal: None,
        storage: None,
        storage_unavailable: None,
        asking_remove_all: false,
        limit_units: vec![],
    }
}
