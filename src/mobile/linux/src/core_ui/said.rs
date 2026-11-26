//! What the core says, written the way this desktop writes it (ADR 0123's Consequences, the Linux app): a `Notice` by its key
//! and arguments through [words::say], a `Piece` piece by piece, a line's pieces joined by the core's separator. Every
//! size, day, moment, amount and length arrives as the core's text (ADR 0154 decision 5) and a notice shown on its own
//! lives as its kind says, by the core's lifetimes (decision 4: [toast]); nothing here formats a value or holds a
//! duration. Nothing here decides what is said; that is the core's (ADR 0161 decision 1). The same as Android's
//! `NoticeText.kt` and Apple's `say(notice)`.
use std::ffi::{CStr, c_char};
use std::sync::OnceLock;

use gtk::glib;

use crate::bridge::wire::{Answer, Call, Notice, NoticeArg, NoticeKind, Piece};
use crate::core_ui::words::{self, Arg};

/// What the core answers once for every notice and line ([start]): its lifetimes, the words that live as `SAID` or
/// `UNDO` when this app raises them itself (every other is `HELD`, core's `NoticeKind.of`), and the separator.
#[derive(Debug, Clone, PartialEq)]
pub struct Lifetimes {
    pub said_ms: i64,
    pub undo_ms: i64,
    pub copied_ms: i64,
    pub nothing_plays_ms: i64,
    pub said_words: Vec<String>,
    pub undo_words: Vec<String>,
    pub separator: String,
}

static LIFETIMES: OnceLock<Lifetimes> = OnceLock::new();

/// Asks the core for its [Lifetimes] once, right after its start and before the first window: answered at once, without
/// the core's start (`Notice.Lifetimes`), so this one wait on GTK's thread is a constant's.
pub fn start() {
    match crate::bridge::call_blocking(&Call::NoticeLifetimes).map(|(a, _)| a) {
        Ok(Answer::Lifetimes { said_ms, undo_ms, copied_ms, nothing_plays_ms, said_words, undo_words, separator }) => {
            eprintln!("veyq: notices live {said_ms} ms said, {copied_ms} ms copied; {} words said, {} undo", said_words.len(), undo_words.len());
            let _ = LIFETIMES.set(Lifetimes { said_ms, undo_ms, copied_ms, nothing_plays_ms, said_words, undo_words, separator });
        }
        other => eprintln!("veyq: Notice.Lifetimes answered {other:?}"),
    }
}

/// The core's [Lifetimes]; before [start] answered (never, past the core's start) none: every notice held, a line's
/// parts set apart by a comma. A test reads the core's answer as `ShownWireTest` holds it.
fn lifetimes() -> &'static Lifetimes {
    #[cfg(test)]
    let none = tests::core_lifetimes;
    #[cfg(not(test))]
    let none = || Lifetimes {
        said_ms: 0, undo_ms: 0, copied_ms: 0, nothing_plays_ms: 0, said_words: Vec::new(), undo_words: Vec::new(), separator: ", ".into(),
    };
    LIFETIMES.get_or_init(none)
}

/// The kind a word this app raises itself lives as: the core's table ([Lifetimes]).
pub fn kind_of(key: &str) -> NoticeKind {
    let l = lifetimes();
    if l.said_words.iter().any(|w| w == key) {
        NoticeKind::Said
    } else if l.undo_words.iter().any(|w| w == key) {
        NoticeKind::Undo
    } else {
        NoticeKind::Held
    }
}

/// Whole seconds for libadwaita's toast, never shorter than the core's milliseconds.
fn seconds(ms: i64) -> u32 {
    u32::try_from((ms.max(0) + 999) / 1000).unwrap_or(u32::MAX)
}

/// A toast saying [text] (plain, escaped here) that lives as [kind] says (ADR 0154 decision 4): `SAID` closes itself after
/// the core's `SAID_MS`; `HELD` stays until the person closes it (libadwaita's close button); `UNDO` stays until its state
/// drops the offer, when its caller dismisses it.
pub fn toast(text: &str, kind: NoticeKind) -> adw::Toast {
    let t = adw::Toast::new(&glib::markup_escape_text(text));
    t.set_timeout(match kind {
        NoticeKind::Said => seconds(lifetimes().said_ms),
        NoticeKind::Held | NoticeKind::Undo => 0,
    });
    t
}

/// A core notice as its toast ([toast] by its own kind).
pub fn notice_toast(n: &Notice) -> adw::Toast {
    toast(&notice(n), n.kind)
}

/// A word this app raises itself ([key], its catalog key, said as [text]) as its toast, living as the core's table says.
pub fn word_toast(key: &str, text: &str) -> adw::Toast {
    toast(text, kind_of(key))
}

/// "Copied" as a toast: it shows for the core's `COPIED_MS`.
pub fn copied_toast(text: &str) -> adw::Toast {
    let t = toast(text, NoticeKind::Held);
    t.set_timeout(seconds(lifetimes().copied_ms));
    t
}

/// The player's "nothing plays" as a toast: it shows for the core's `NOTHING_PLAYS_MS`.
pub fn nothing_plays_toast(n: &Notice) -> adw::Toast {
    let t = toast(&notice(n), NoticeKind::Held);
    t.set_timeout(seconds(lifetimes().nothing_plays_ms));
    t
}

/// How long "copied" shows beside what was copied (`COPIED_MS`).
pub fn copied_for() -> std::time::Duration {
    std::time::Duration::from_millis(u64::try_from(lifetimes().copied_ms).unwrap_or(0))
}

/// A line's [parts] joined as every app and the web join them (`Shown.joined`).
pub fn joined<S: AsRef<str>>(parts: &[S]) -> String {
    parts.iter().map(AsRef::as_ref).collect::<Vec<_>>().join(&lifetimes().separator)
}

/// A notice in the person's language; its key itself (and a line on stderr) where the catalog has no such word, which
/// only a core built from another `words.json` sends.
pub fn notice(n: &Notice) -> String {
    let parts: Vec<Part> = n.args.iter().map(part).collect();
    let typed: Vec<Arg> = parts.iter().map(Part::arg).collect();
    // a `{n}` placeholder takes a number as its text: the catalog's functions are typed, the core's arguments are values
    let texts: Vec<Arg> = parts.iter().map(|p| Arg::Text(p.text())).collect();
    words::say(&n.msg, &typed).or_else(|| words::say(&n.msg, &texts)).unwrap_or_else(|| {
        eprintln!("veyq: no word for {} with {} arguments", n.msg, n.args.len());
        n.msg.clone()
    })
}

/// A word by its catalog key alone (a `Msg` the core sent: a group's heading, an application's name).
pub fn key(key: &str) -> String {
    notice(&Notice { msg: key.to_string(), args: Vec::new(), kind: crate::bridge::wire::NoticeKind::Held })
}

/// One piece of a line.
pub fn piece(p: &Piece) -> String {
    match p {
        Piece::Said { notice: n } => notice(n),
        Piece::Plain { text } => text.clone(),
    }
}

/// A line's pieces joined as the other apps join them.
pub fn line(pieces: &[Piece]) -> String {
    joined(&pieces.iter().map(piece).collect::<Vec<_>>())
}

/// An argument as a notice's words take it: a number stays one (and has its text for a `{n}` placeholder), everything
/// else is its text.
struct Part {
    text: String,
    int: Option<i64>,
}

impl Part {
    fn arg(&self) -> Arg<'_> {
        self.int.map_or(Arg::Text(&self.text), Arg::Int)
    }

    fn text(&self) -> &str {
        &self.text
    }
}

fn part(a: &NoticeArg) -> Part {
    let int = match a {
        NoticeArg::Int { value } => Some(i64::from(*value)),
        NoticeArg::Long { value } => Some(*value),
        _ => None,
    };
    Part { text: arg_text(a), int }
}

fn arg_text(a: &NoticeArg) -> String {
    match a {
        NoticeArg::Int { value } => value.to_string(),
        NoticeArg::Long { value } => value.to_string(),
        NoticeArg::Joined { parts, separator } => parts.iter().map(arg_text).collect::<Vec<_>>().join(separator),
        NoticeArg::Notice { notice: n } => notice(n),
        NoticeArg::Text { text } => text.clone(),
    }
}

// glibc's numeric conventions of the locale (`localeconv`, the C standard's `struct lconv` in glibc's field order), read
// up to its first field, the one used here: the separator a person types, never a value shown.
#[repr(C)]
struct Lconv {
    decimal_point: *const c_char,
}

unsafe extern "C" {
    fn localeconv() -> *const Lconv;
}

fn text(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    // SAFETY: localeconv's strings are NUL-terminated and live until the next localeconv or setlocale, on this thread.
    unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned()
}

/// The person's decimal separator, as they type an amount (the locale's numeric data; the core reads both `.` and `,`):
/// what the entry editor hands the core at its open, so the amounts and the rate it fills read the person's way.
pub fn decimal_separator() -> String {
    // SAFETY: called on GTK's main thread only, which is the only one that changes the locale (at GTK's start).
    let l = unsafe { &*localeconv() };
    let d = text(l.decimal_point);
    if d.chars().count() == 1 { d } else { ".".into() }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The core's answer as `Notice.Lifetimes` gives it (`ShownWireTest` holds the core's side).
    pub fn core_lifetimes() -> Lifetimes {
        Lifetimes {
            said_ms: 4_000, undo_ms: 6_000, copied_ms: 2_000, nothing_plays_ms: 5_000,
            said_words: vec!["download_done".into(), "profile_saved".into()], undo_words: vec!["trash_moved".into()],
            separator: format!(" {} ", '\u{b7}'),
        }
    }

    /// A word this app raises itself lives as the core's table says; one it does not name is held; a line is joined by
    /// the core's separator. Bitten by a `kind_of` that answers `Said` for every word: red.
    #[test]
    fn a_words_kind_and_a_lines_separator_are_the_cores() {
        assert_eq!(kind_of("profile_saved"), NoticeKind::Said);
        assert_eq!(kind_of("trash_moved"), NoticeKind::Undo);
        assert_eq!(kind_of("download_failed"), NoticeKind::Held);
        assert_eq!(joined(&["a", "b"]), format!("a{}b", core_lifetimes().separator));
        assert_eq!(seconds(4_000), 4);
        assert_eq!(seconds(1_500), 2);
    }
}
