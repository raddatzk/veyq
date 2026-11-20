//! The composition root (ADR 0154 decision 3; the web's `src/web/src/applications.ts`, Android's `Applications.kt`): the
//! one place the core UI's registry is filled — each application's `register`, then the seal. It runs first in `main`,
//! before any screen asks the registry; the core UI names no application but through it
//! (`scripts/validate_mobile_ui_direction.py`, which also holds that this file registers every application).
use crate::core_ui::registry::Registry;

/// Registers every application into the core UI's registry and seals it.
pub fn register() {
    let mut registry = Registry::default();
    crate::apps::memories::register(&mut registry);
    crate::apps::depot::register(&mut registry);
    crate::apps::expenses::register(&mut registry);
    registry.seal();
}
