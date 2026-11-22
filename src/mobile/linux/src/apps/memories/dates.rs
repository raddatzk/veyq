//! A Memory's date as Memories says it (ADR 0143 decision 5, the web's `memoryDateRange`): its facts line, a person's row
//! and — asked of the core here (`Memories.Dates`) and handed to the core UI's rows as their second line — the rows of
//! Home and Memories' list (inbox #1066: the core UI holds the lines, never a Memory's date).
use std::collections::HashMap;

use gtk::glib;

use crate::bridge::{self, wire::Answer, wire::Call, wire::DateMode, wire::MemoryDateShown};
use crate::core_ui::{home, words};

/// A Memory's date as its facts line, its row and a person's row say it: the core's one day or range, "Since …" for an
/// ongoing one; none without a day.
pub fn date_text(d: &MemoryDateShown) -> Option<String> {
    if d.text.is_empty() {
        return None;
    }
    Some(if d.mode == DateMode::Ongoing { words::memory_date_since(&d.text) } else { d.text.clone() })
}

/// The rows' dates asked for (`Memories.Dates`) unless they were for the objects [key] names (or [again]: a page closed
/// back onto the list, its date maybe edited); the core UI's rows are handed each one's line once they arrive.
pub fn ask(key: &str, again: bool) {
    if !home::lines_asked(key, again) {
        return;
    }
    glib::spawn_future_local(async {
        let answered = bridge::call(&Call::MemoriesDates).await.map(|(a, _)| a);
        let dates = match answered {
            Ok(Answer::MemoryDates { dates }) => dates,
            other => {
                home::lines_arrived(None);
                return eprintln!("veyq: Memories.Dates answered {other:?}");
            }
        };
        let lines: HashMap<String, String> = dates.into_iter().filter_map(|d| date_text(&d.date).map(|t| (d.node_id, t))).collect();
        eprintln!("veyq: memory dates: {} dated", lines.len());
        home::lines_arrived(Some(lines));
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(mode: DateMode, text: &str) -> MemoryDateShown {
        MemoryDateShown { mode, from: Some("2026-05-01".into()), to: None, text: text.into() }
    }

    /// A date is said as the core's text, "Since …" for an ongoing one; without a day it is not said.
    #[test]
    fn a_memorys_date_is_the_cores_text_or_since() {
        assert_eq!(date_text(&d(DateMode::Auto, "")), None);
        assert_eq!(date_text(&d(DateMode::Range, "05/01/2026 – 05/03/2026")).as_deref(), Some("05/01/2026 – 05/03/2026"));
        assert_eq!(date_text(&d(DateMode::Ongoing, "05/01/2026")), Some(words::memory_date_since("05/01/2026")));
    }
}
