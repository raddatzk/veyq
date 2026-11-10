//! The veyq app on Linux (ADR 0127 decision 4, CR core-as-a-native-library D8): every rule is the core's, reached
//! through libveyq.so ([bridge]); this crate draws and holds the device — the words ([words]), the theme ([theme]),
//! the creating action ([create_action]) and, from the slice on, one module per screen.
pub mod bridge;
pub mod create_action;
pub mod theme;
pub mod words;
