//! Debug builds only — the Linux counterpart of the web's `?devlogin`, Android's `devlogin.py` and Apple's
//! `DevLogin.swift`, so a pass never types a password into the window. A release build compiles none of this.
//!
//! - `run.py run --devlogin` hands the seeded dev principal of `.run/.env` to the app's environment (`VEYQ_DEV_SERVER`,
//!   `VEYQ_DEV_EMAIL`, `VEYQ_DEV_PASSWORD`, `VEYQ_DEV_SECRET_KEY`). The app fills its real sign-in with them once, when
//!   the core first asks for one, and signs in through it — with "Stay signed in" as the switch stands, off unless
//!   `VEYQ_DEV_FILL` is set: then it only fills the form, and the pass turns the switch and presses Sign in itself.
//! - `run.py run --devlogin --unlock` hands the password alone (`VEYQ_DEV_UNLOCK_PASSWORD`, Android's `--unlock`,
//!   Apple's variable): the unlock of a password-trusted account takes it once, when the core shows that unlock.
use std::sync::atomic::{AtomicBool, Ordering};

/// The seeded principal the environment names, for the sign-in's fields.
pub struct DevSignIn {
    pub server: String,
    pub email: String,
    pub password: String,
    pub secret_key: String,
    /// Fill only: the pass submits the form itself.
    pub fill_only: bool,
}

/// The sign-in the environment names, once per process; None in a release build, without the four variables, or after
/// it was taken.
pub fn take() -> Option<DevSignIn> {
    #[cfg(debug_assertions)]
    {
        static TAKEN: AtomicBool = AtomicBool::new(false);
        let dev = DevSignIn {
            server: var("VEYQ_DEV_SERVER")?,
            email: var("VEYQ_DEV_EMAIL")?,
            password: var("VEYQ_DEV_PASSWORD")?,
            secret_key: var("VEYQ_DEV_SECRET_KEY")?,
            fill_only: var("VEYQ_DEV_FILL").is_some(),
        };
        if TAKEN.swap(true, Ordering::SeqCst) {
            return None;
        }
        eprintln!("veyq: {} the sign-in as the dev principal (debug build)", if dev.fill_only { "filling" } else { "signing in through" });
        Some(dev)
    }
    #[cfg(not(debug_assertions))]
    None
}

/// The password for a password-trusted account's unlock, once per process; None in a release build, without
/// `VEYQ_DEV_UNLOCK_PASSWORD`, or after it was taken.
pub fn take_unlock() -> Option<String> {
    #[cfg(debug_assertions)]
    {
        static TAKEN: AtomicBool = AtomicBool::new(false);
        let password = var("VEYQ_DEV_UNLOCK_PASSWORD")?;
        if TAKEN.swap(true, Ordering::SeqCst) {
            return None;
        }
        eprintln!("veyq: unlocking with the dev principal's password (debug build)");
        Some(password)
    }
    #[cfg(not(debug_assertions))]
    None
}

#[cfg(debug_assertions)]
fn var(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|v| !v.is_empty())
}
