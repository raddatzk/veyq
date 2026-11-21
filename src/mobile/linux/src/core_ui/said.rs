//! What the core says, written the way this desktop writes it (ADR 0123's Consequences, the Linux app): a `Notice` by its key
//! and arguments through [words::say], a `Piece` piece by piece — the core writes a notice's and a piece's sizes, moments
//! and amounts before the wire (ADR 0154 decision 5); the values the wire still carries raw are written here in the
//! person's locale, as glibc and GLib hold it, until this app's phase of cr-apps-notices-and-formats-from-the-core. Nothing
//! here decides what is said; that is the core's (ADR 0136 decision 1). The same as Android's `NoticeText.kt` and Apple's
//! `say(notice)`.
use std::ffi::{CStr, c_char};

use gtk::glib;

use crate::bridge::wire::{DateMode, MemoryDateShown, Notice, NoticeArg, Piece};
use crate::core_ui::words::{self, Arg};

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
    pieces.iter().map(piece).collect::<Vec<_>>().join(" · ")
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

/// An ISO day (`YYYY-MM-DD`) as the locale writes a date; the text itself where it is no day.
pub fn day(iso: &str) -> String {
    let mut parts = iso.splitn(3, '-').map(str::parse::<i32>);
    let (Some(Ok(y)), Some(Ok(m)), Some(Ok(d))) = (parts.next(), parts.next(), parts.next()) else { return iso.to_string() };
    glib::DateTime::from_local(y, m, d, 0, 0, 0.0)
        .ok()
        .and_then(|t| t.format("%x").ok())
        .map(String::from)
        .unwrap_or_else(|| iso.to_string())
}

/// The day of a server's instant (ISO 8601, `2026-11-11T16:46:58.016Z`) in the local zone, as the locale writes a date
/// (Android's and Apple's trash rows do the same); the instant's own day where it does not read.
pub fn instant_day(instant: &str) -> String {
    glib::DateTime::from_iso8601(instant, None)
        .and_then(|t| t.to_local())
        .ok()
        .and_then(|t| t.format("%x").ok())
        .map(String::from)
        .unwrap_or_else(|| instant.chars().take(10).collect())
}

/// A server's instant (ISO 8601) in the local zone, as the locale writes a date and a time (a report's receipt); the
/// instant itself where it does not read.
pub fn instant_moment(instant: &str) -> String {
    glib::DateTime::from_iso8601(instant, None)
        .and_then(|t| t.to_local())
        .ok()
        .and_then(|t| t.format("%x %R").ok())
        .map(String::from)
        .unwrap_or_else(|| instant.to_string())
}

/// A size as GNOME writes one (`g_format_size`).
pub fn size(bytes: i64) -> String {
    glib::format_size(bytes.max(0) as u64).into()
}

/// A moment in the local zone, as the locale writes a date and a time.
pub fn moment(epoch_ms: i64) -> String {
    glib::DateTime::from_unix_local(epoch_ms.div_euclid(1000))
        .ok()
        .and_then(|t| t.format("%x %R").ok())
        .map(String::from)
        .unwrap_or_else(|| epoch_ms.to_string())
}

// glibc's own monetary conventions of the locale (`localeconv`, the C standard's `struct lconv` in glibc's field order),
// read up to the last field used here.
#[repr(C)]
struct Lconv {
    decimal_point: *const c_char,
    thousands_sep: *const c_char,
    grouping: *const c_char,
    int_curr_symbol: *const c_char,
    currency_symbol: *const c_char,
    mon_decimal_point: *const c_char,
    mon_thousands_sep: *const c_char,
    mon_grouping: *const c_char,
    positive_sign: *const c_char,
    negative_sign: *const c_char,
    int_frac_digits: c_char,
    frac_digits: c_char,
    p_cs_precedes: c_char,
    p_sep_by_space: c_char,
    n_cs_precedes: c_char,
    n_sep_by_space: c_char,
}

unsafe extern "C" {
    fn localeconv() -> *const Lconv;
}

/// What the locale says about writing money (its `LC_MONETARY`).
struct Monetary {
    decimal: String,
    thousands: String,
    group: usize,
    own_code: String,
    own_symbol: String,
    precedes: bool,
    space: bool,
    minus: String,
}

fn text(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    // SAFETY: localeconv's strings are NUL-terminated and live until the next localeconv or setlocale, on this thread.
    unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned()
}

fn monetary() -> Monetary {
    // SAFETY: called on GTK's main thread only, which is the only one that changes the locale (at GTK's start).
    let l = unsafe { &*localeconv() };
    // SAFETY: as above; grouping's first byte is the size of the first group (0 or CHAR_MAX: none).
    let group = if l.mon_grouping.is_null() { 0 } else { i32::from(unsafe { *l.mon_grouping }) };
    let decimal = text(l.mon_decimal_point);
    Monetary {
        decimal: if decimal.is_empty() { ".".into() } else { decimal },
        thousands: text(l.mon_thousands_sep),
        group: usize::try_from(group).ok().filter(|g| (1..127).contains(g)).unwrap_or(0),
        own_code: text(l.int_curr_symbol).trim().to_string(),
        own_symbol: text(l.currency_symbol),
        precedes: l.p_cs_precedes == 1,
        space: l.p_sep_by_space == 1,
        minus: {
            let m = text(l.negative_sign);
            if m.is_empty() { "-".into() } else { m }
        },
    }
}

/// The person's decimal separator, as they type an amount (the locale's numeric data; the core reads both `.` and `,`):
/// what the entry editor hands the core at its open, so the amounts and the rate it fills read the person's way.
pub fn decimal_separator() -> String {
    // SAFETY: called on GTK's main thread only, which is the only one that changes the locale (at GTK's start).
    let l = unsafe { &*localeconv() };
    let d = text(l.decimal_point);
    if d.chars().count() == 1 { d } else { ".".into() }
}

include!(concat!(env!("OUT_DIR"), "/icu.rs"));

unsafe extern "C" {
    fn setlocale(category: std::ffi::c_int, locale: *const c_char) -> *mut c_char;
}
const LC_MONETARY: std::ffi::c_int = 4; // glibc's

/// [code]'s symbol as ICU writes it for the locale money is written in (CLDR's: `€` for EUR under en_US, `CA$` for CAD
/// under de_DE, as Android, Apple and the web's `Intl` write it); None where ICU knows none but the code itself, or the
/// locale is C or POSIX (no language to ask for).
fn icu_symbol(code: &str) -> Option<String> {
    // SAFETY: a query (NULL), on GTK's main thread, which is the only one that changes the locale.
    let name = text(unsafe { setlocale(LC_MONETARY, std::ptr::null()) });
    let locale = name.split(['.', '@']).next().unwrap_or("");
    if locale.is_empty() || locale == "C" || locale == "POSIX" || code.len() != 3 {
        return None;
    }
    let locale = std::ffi::CString::new(locale).ok()?;
    let mut currency: Vec<u16> = code.encode_utf16().collect();
    currency.push(0);
    let (mut choice, mut len): (i8, i32) = (0, 0);
    let mut status: std::ffi::c_int = 0;
    // SAFETY: the code is a NUL-terminated UTF-16 string and the locale a C string, both alive across the call; ICU
    // returns a pointer into its own data (never freed by the caller) of `len` UTF-16 units.
    let name = unsafe { ucurr_get_name(currency.as_ptr(), locale.as_ptr(), 0 /* UCURR_SYMBOL_NAME */, &mut choice, &mut len, &mut status) };
    if status > 0 || name.is_null() || len <= 0 {
        return None;
    }
    // SAFETY: as above, `len` units at `name`.
    let symbol = String::from_utf16(unsafe { std::slice::from_raw_parts(name, len as usize) }).ok()?;
    (symbol != code && !symbol.is_empty()).then_some(symbol)
}

/// [minor] units of [currency] (with [minor_units] digits after the point) as the locale writes money: its separators
/// and grouping, its own currency by its symbol where it is the locale's, any other by the symbol ICU knows for it in
/// the locale (CLDR's, as the phones and the web write it), else by its code. No currency: the number alone.
pub fn money(minor: i64, currency: Option<&str>, minor_units: i32) -> String {
    let m = monetary();
    let digits = usize::try_from(minor_units).unwrap_or(0);
    let abs = minor.unsigned_abs().to_string();
    let abs = format!("{abs:0>width$}", width = digits + 1);
    let (whole, frac) = abs.split_at(abs.len() - digits);
    let mut grouped = String::new();
    for (i, c) in whole.chars().enumerate() {
        if m.group > 0 && i > 0 && (whole.len() - i) % m.group == 0 {
            grouped.push_str(&m.thousands);
        }
        grouped.push(c);
    }
    let number = if digits > 0 { format!("{grouped}{}{frac}", m.decimal) } else { grouped };
    let sign = if minor < 0 { m.minus.as_str() } else { "" };
    let Some(code) = currency else { return format!("{sign}{number}") };
    let symbol = if code == m.own_code && !m.own_symbol.is_empty() { Some(m.own_symbol.clone()) } else { icu_symbol(code) };
    if let Some(symbol) = symbol {
        // placed as the locale places its own
        let gap = if m.space { " " } else { "" };
        return if m.precedes { format!("{sign}{symbol}{gap}{number}") } else { format!("{sign}{number}{gap}{symbol}") };
    }
    if m.precedes { format!("{sign}{code} {number}") } else { format!("{sign}{number} {code}") }
}

/// A Memory's date as its facts line, its row and a person's row say it: one day, a range, "Since …" for an ongoing one; none without a day.
pub fn date_text(d: &MemoryDateShown) -> Option<String> {
    let from = d.from.as_deref().filter(|f| !f.is_empty())?;
    if d.mode == DateMode::Ongoing {
        return Some(words::memory_date_since(&day(from)));
    }
    match d.to.as_deref().filter(|t| !t.is_empty() && *t > from) {
        Some(to) => Some(format!("{} – {}", day(from), day(to))),
        None => Some(day(from)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CString;

    fn with_monetary(locale: &str, f: impl FnOnce()) {
        let _locale = crate::LOCALE.lock().unwrap_or_else(|p| p.into_inner());
        let l = CString::new(locale).unwrap();
        // SAFETY: the only test that changes LC_MONETARY; the string outlives the call.
        assert!(!unsafe { setlocale(LC_MONETARY, l.as_ptr()) }.is_null(), "no {locale} locale (the image's langpacks)");
        f();
    }

    /// Money as each locale of the image writes it — the locale's own currency by its symbol (glibc's locale data),
    /// another by the symbol ICU knows for it there (CLDR's, as Android and Apple write `€1,234.56` under en_US), one ICU
    /// knows only by its code by that code —, never from a table here; and nothing lost in the digits. Bitten by a
    /// `money` that writes every foreign currency by its code: red.
    #[test]
    fn money_is_written_in_the_locales_own_way() {
        with_monetary("de_DE.UTF-8", || {
            assert_eq!(money(123456, Some("EUR"), 2), "1.234,56 €");
            assert_eq!(money(-5, Some("EUR"), 2), "-0,05 €");
            assert_eq!(money(1234567, Some("JPY"), 0), "1.234.567 ¥");
            assert_eq!(money(123456, Some("CAD"), 2), "1.234,56 CA$");
            assert_eq!(money(4500, Some("CHF"), 2), "45,00 CHF");
        });
        with_monetary("en_US.UTF-8", || {
            assert_eq!(money(123456, Some("USD"), 2), "$1,234.56");
            assert_eq!(money(123456, Some("EUR"), 2), "€1,234.56");
            assert_eq!(money(-123456, Some("GBP"), 2), "-£1,234.56");
            assert_eq!(money(4500, Some("CHF"), 2), "CHF 45.00");
            assert_eq!(money(7, None, 3), "0.007");
        });
    }

    #[test]
    fn a_day_that_is_no_day_stays_as_it_came() {
        assert_eq!(day("soon"), "soon");
        assert_eq!(day("2026-13-40"), "2026-13-40");
    }

    #[test]
    fn a_servers_instant_is_said_as_its_day_and_one_that_does_not_read_as_its_first_ten_characters() {
        let said = instant_day("2026-11-11T12:00:00.016Z");
        assert!(!said.contains('T') && !said.contains(':') && said.contains("11"), "{said}");
        assert_eq!(instant_day("2026-11-11 or so"), "2026-11-11");
    }

    fn d(mode: DateMode, from: Option<&str>, to: Option<&str>) -> MemoryDateShown {
        MemoryDateShown { mode, from: from.map(str::to_owned), to: to.map(str::to_owned) }
    }

    /// A date is said as one day, a range, or "Since …"; without a day it is not said; a range ending on its start day is
    /// that day.
    #[test]
    fn a_memorys_date_is_said_as_a_day_a_range_or_since() {
        assert_eq!(date_text(&d(DateMode::Auto, None, None)), None);
        assert_eq!(date_text(&d(DateMode::Single, Some("2026-05-01"), None)), Some(day("2026-05-01")));
        assert_eq!(date_text(&d(DateMode::Range, Some("2026-05-01"), Some("2026-05-03"))), Some(format!("{} – {}", day("2026-05-01"), day("2026-05-03"))));
        assert_eq!(date_text(&d(DateMode::Auto, Some("2026-05-01"), Some("2026-05-01"))), Some(day("2026-05-01")));
        assert_eq!(date_text(&d(DateMode::Ongoing, Some("2026-05-01"), None)), Some(words::memory_date_since(&day("2026-05-01"))));
    }
}
