//! Debug builds only — the Linux counterpart of the web's `?devlogin`, Android's `devlogin.py` and Apple's
//! `DevLogin.swift`: `run.py run --devlogin` hands the seeded dev principal of `.run/.env` to the app's environment
//! (`VEYQ_DEV_SERVER`, `VEYQ_DEV_EMAIL`, `VEYQ_DEV_PASSWORD`, `VEYQ_DEV_SECRET_KEY`), and the app signs in through its
//! real sign-in once, when the core first asks for one — so a pass never types a password into the window. A release
//! build compiles none of this.
use crate::bridge::wire::Call;

/// The sign-in the environment names, once per process; None in a release build, without the four variables, or
/// after it was taken.
pub fn take() -> Option<Call> {
    #[cfg(debug_assertions)]
    {
        use std::sync::atomic::{AtomicBool, Ordering};
        static TAKEN: AtomicBool = AtomicBool::new(false);
        let var = |name: &str| std::env::var(name).ok().filter(|v| !v.is_empty());
        let call = Call::SessionSignIn {
            server: var("VEYQ_DEV_SERVER")?,
            email: var("VEYQ_DEV_EMAIL")?,
            password: var("VEYQ_DEV_PASSWORD")?,
            secret_key: var("VEYQ_DEV_SECRET_KEY")?,
        };
        if TAKEN.swap(true, Ordering::SeqCst) {
            return None;
        }
        eprintln!("veyq: signing in as the dev principal (debug build)");
        Some(call)
    }
    #[cfg(not(debug_assertions))]
    None
}
