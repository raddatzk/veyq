//! The veyq app on Linux (ADR 0127 decision 4; ADR 0123's Consequences, the Linux app): every rule is the core's, reached
//! through libveyq.so ([bridge]); this crate draws and holds the device — the words ([words], [said]), the theme
//! ([theme]), the creating action ([create_action]), the members' pictures ([avatars]) and one module per screen:
//! [sign_in] with the unlock, [shell] with [home], [pot] with its [pot_settings], [entry_editor] (a text field's rule, [typed]), a node's [header] with its [move_sheet], [share_sheet], [report] and [cover] and a space's [trash], New pot ([pot_create]) (Search is the shell's sidebar's), [manage] ("Manage
//! servers", from the switcher), and, in a debug build, [dev_login].
pub mod avatars;
pub mod cover;
pub mod bridge;
pub mod create_action;
pub mod dev_login;
pub mod entry_editor;
pub mod entry_history;
pub mod header;
pub mod home;
pub mod manage;
pub mod move_sheet;
pub mod pot;
pub mod pot_create;
pub mod pot_settings;
pub mod receipt_viewer;
pub mod report;
pub mod said;
pub mod share_sheet;
pub mod shell;
pub mod sign_in;
pub mod theme;
pub mod trash;
pub mod typed;
pub mod words;

/// The process's locale is one for every test thread: a test that sets it holds this while it does.
#[cfg(test)]
static LOCALE: std::sync::Mutex<()> = std::sync::Mutex::new(());
