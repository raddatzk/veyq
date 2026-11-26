//! The veyq app on Linux (ADR 0161 decision 4; ADR 0123's Consequences, the Linux app): every rule is the core's, reached
//! through libveyq.so ([bridge]); this crate draws and holds the device. Its UI is split as the web's (ADR 0154 decision 3,
//! ADR 0079): the core UI ([core_ui]) — the shell, the frame, the header, the level, the shared sheets and dialogs, the
//! notices, New, the uploads and the drop, the received link's screen — and one module per application under [apps], each
//! registering its kinds' page bodies and its own sheets into the core UI's registry ([core_ui::registry]) from its
//! `register`, called by the one composition root, [applications]. An application uses the core UI, never another
//! application; the core UI reaches an application only through the registry (`scripts/validate_mobile_ui_direction.py`).
pub mod applications;
pub mod bridge;
pub mod core_ui;

/// One module per application, named by the core's application ids (`ApplicationId`): what draws only that application's
/// kinds and screens.
pub mod apps {
    pub mod depot;
    pub mod expenses;
    pub mod memories;
}

/// The process's locale is one for every test thread: a test that sets it holds this while it does.
#[cfg(test)]
static LOCALE: std::sync::Mutex<()> = std::sync::Mutex::new(());
