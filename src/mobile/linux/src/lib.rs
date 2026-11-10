//! The veyq app on Linux (ADR 0127 decision 4; ADR 0123's Consequences, the Linux app): every rule is the core's, reached
//! through libveyq.so ([bridge]); this crate draws and holds the device — the words ([words], [said]), the theme
//! ([theme]), the creating action ([create_action]) and one module per screen: [sign_in], [shell] with [home] and
//! [pot] (Search is the shell's sidebar's), and, in a debug build, [dev_login].
pub mod bridge;
pub mod create_action;
pub mod dev_login;
pub mod home;
pub mod pot;
pub mod said;
pub mod shell;
pub mod sign_in;
pub mod theme;
pub mod words;

/// The process's locale is one for every test thread: a test that sets it holds this while it does.
#[cfg(test)]
static LOCALE: std::sync::Mutex<()> = std::sync::Mutex::new(());
