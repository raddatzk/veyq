//! This install's appearance and language (ADR 0162; the web's Persönlich → Darstellung und Sprache): Settings' first
//! section, every choice the core offers (`SettingsShown.themes`, `languages`) as an `AdwComboRow`, the chosen ids kept
//! in `$XDG_DATA_HOME/veyq/appearance.ini` beside the device store (in the Flatpak its own `~/.var/app/<id>/data`; the
//! development run's home volume keeps it across starts, as it keeps the store), a key file with the
//! group `appearance` and the keys `theme` and `language`, the core's ids (the web's stored values). Nothing else is in
//! it, and nothing of it leaves the device.
//!
//! The appearance applies at once: libadwaita's `AdwStyleManager` color scheme, which every window reads. The language
//! applies at the next start — GNOME has no per-app language, so the app makes its own: before anything reads a word,
//! [start] puts the kept language first in `LANGUAGE`, which gettext's catalogs and the bridge's words (`Config.language`,
//! from `words::language`) then follow; the row says so (`appearance_next_start`). gettext ignores `LANGUAGE` under a C
//! locale (src/mobile/CLAUDE.md § Traps): a session under `C.UTF-8` keeps English whatever is kept here.
use std::path::{Path, PathBuf};

use gtk::glib;

const GROUP: &str = "appearance";
const THEME: &str = "theme";
const LANGUAGE: &str = "language";

/// What this install keeps: the core's ids, `system` where nothing is kept.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Kept {
    pub theme: String,
    pub language: String,
}

impl Default for Kept {
    fn default() -> Self {
        Kept { theme: "system".into(), language: "system".into() }
    }
}

/// The key file of this install's choice.
pub fn path() -> PathBuf {
    glib::user_data_dir().join("veyq").join("appearance.ini")
}

/// The choice kept at [at]; the system's for a file that is not there or not read.
pub fn read_at(at: &Path) -> Kept {
    let file = glib::KeyFile::new();
    if file.load_from_file(at, glib::KeyFileFlags::NONE).is_err() {
        return Kept::default();
    }
    let get = |key: &str| file.string(GROUP, key).map(|s| s.to_string()).unwrap_or_else(|_| "system".into());
    Kept { theme: get(THEME), language: get(LANGUAGE) }
}

/// Keeps [kept] at [at] (its directory made as needed).
pub fn write_at(at: &Path, kept: &Kept) -> Result<(), String> {
    if let Some(dir) = at.parent() {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    let file = glib::KeyFile::new();
    file.set_string(GROUP, THEME, &kept.theme);
    file.set_string(GROUP, LANGUAGE, &kept.language);
    file.save_to_file(at).map_err(|e| e.to_string())
}

pub fn read() -> Kept {
    read_at(&path())
}

/// Keeps [kept], saying on stderr what did not go.
pub fn write(kept: &Kept) {
    match write_at(&path(), kept) {
        Ok(()) => eprintln!("veyq: appearance kept: theme {}, language {}", kept.theme, kept.language),
        Err(e) => eprintln!("veyq: the appearance was not kept ({e})"),
    }
}

/// libadwaita's color scheme for a kept theme id: the system's for `system` and for any id the core does not offer.
pub fn scheme(theme: &str) -> adw::ColorScheme {
    match theme {
        "light" => adw::ColorScheme::ForceLight,
        "dark" => adw::ColorScheme::ForceDark,
        _ => adw::ColorScheme::Default,
    }
}

/// `LANGUAGE` for a kept language id over the environment's [now]: the kept language first, then what was there (so a
/// word it lacks falls back as before); None — leave it — for `system` and for any id the core does not offer.
pub fn language_env(language: &str, now: Option<&str>) -> Option<String> {
    if !matches!(language, "en" | "de") {
        return None;
    }
    Some(match now.filter(|n| !n.is_empty()) {
        Some(n) => format!("{language}:{n}"),
        None => language.to_string(),
    })
}

/// The kept language put first, before any word is read (`main`'s first step: one thread, nothing reads the environment).
pub fn start() -> Kept {
    let kept = read();
    if let Some(value) = language_env(&kept.language, std::env::var("LANGUAGE").ok().as_deref()) {
        // SAFETY: the process's first step, before GTK, the bridge or any other thread exists
        unsafe { std::env::set_var("LANGUAGE", &value) };
        eprintln!("veyq: language {} (LANGUAGE={value})", kept.language);
    }
    kept
}

/// The kept appearance on every window (after the application's startup, which makes the style manager).
pub fn apply_theme(theme: &str) {
    adw::StyleManager::default().set_color_scheme(scheme(theme));
    eprintln!("veyq: appearance {theme}");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_kept_choice_reads_back_and_nothing_kept_is_the_systems() {
        let dir = std::env::temp_dir().join(format!("veyq-appearance-{}", std::process::id()));
        let at = dir.join("veyq").join("appearance.ini");
        assert_eq!(read_at(&at), Kept::default(), "no file: the system's");
        let kept = Kept { theme: "dark".into(), language: "de".into() };
        write_at(&at, &kept).unwrap();
        assert_eq!(read_at(&at), kept);
        std::fs::write(&at, "not a key file [").unwrap();
        assert_eq!(read_at(&at), Kept::default(), "an unreadable file: the system's");
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn a_theme_is_libadwaitas_scheme() {
        assert_eq!(scheme("light"), adw::ColorScheme::ForceLight);
        assert_eq!(scheme("dark"), adw::ColorScheme::ForceDark);
        assert_eq!(scheme("system"), adw::ColorScheme::Default);
        assert_eq!(scheme("sepia"), adw::ColorScheme::Default);
    }

    #[test]
    fn a_language_goes_first_in_language_and_the_systems_leaves_it() {
        assert_eq!(language_env("de", None).as_deref(), Some("de"));
        assert_eq!(language_env("de", Some("en_US:en")).as_deref(), Some("de:en_US:en"));
        assert_eq!(language_env("en", Some("")).as_deref(), Some("en"));
        assert_eq!(language_env("system", Some("de")), None);
        assert_eq!(language_env("fr", None), None);
        // the words' language the bridge is started in follows (words::language over glib's names)
        assert_eq!(crate::core_ui::words::language_of(&["de".into(), "en_US".into()]), "de");
    }
}
