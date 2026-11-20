//! A text field the person types into while the core's state for it arrives (ADR 0123's Consequences, Expenses whole): the core keeps every
//! typed text as typed, and a state for a field arrives after each edit sent — possibly one or two keystrokes behind what
//! the field shows. A field set from such a state would lose what was typed since and move the cursor. So a field is set
//! from state only when it is not being typed into, or when the state's text is none it sent: another writer's version
//! after a stale save, a rate the core filled. No widget here: the rule alone, tested.

/// What one field sent to the core and has not seen come back yet.
#[derive(Debug, Default)]
pub struct Typed {
    sent: Vec<String>,
}

/// How many sent texts a field remembers: more than any burst of keystrokes between two states.
const KEPT: usize = 64;

impl Typed {
    /// [text] was sent to the core as this field's edit.
    pub fn sent(&mut self, text: &str) {
        if self.sent.len() == KEPT {
            self.sent.remove(0);
        }
        self.sent.push(text.to_owned());
    }

    /// The text to set the field to now that the core's state says [state] while it shows [shown] — None keeps the field
    /// as it is. A state that echoes a text this field sent is passed over while it is [focused] (it is one the person
    /// typed, at most a little behind), and every text sent before it is forgotten: states arrive in the order the edits
    /// were applied, so none of those comes again. Any other state is set: the field is not typed into, or the core
    /// changed the text itself.
    pub fn take(&mut self, focused: bool, shown: &str, state: &str) -> Option<String> {
        if let Some(at) = self.sent.iter().position(|s| s == state) {
            self.sent.drain(..=at);
            if focused {
                return None;
            }
        } else if focused && state != shown {
            self.sent.clear();
        }
        (state != shown).then(|| state.to_owned())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Typing "1", "12", "123" fast: the states for "1" and "12" arrive while the field shows "123" and are passed over,
    /// so the field keeps what was typed (and its cursor).
    #[test]
    fn a_focused_field_keeps_what_was_typed_while_its_echoes_arrive() {
        let mut t = Typed::default();
        for s in ["1", "12", "123"] {
            t.sent(s);
        }
        assert_eq!(t.take(true, "123", "1"), None);
        assert_eq!(t.take(true, "123", "12"), None);
        assert_eq!(t.take(true, "123", "123"), None);
    }

    /// A stale save reads the other writer's version into the form: a text the field never sent is set, focused or not.
    #[test]
    fn a_text_the_core_changed_is_set_even_into_a_focused_field() {
        let mut t = Typed::default();
        t.sent("Museum");
        assert_eq!(t.take(true, "Museum", "Museum and lunch").as_deref(), Some("Museum and lunch"));
    }

    /// Once an echo of "12" came, an earlier text ("1") that comes again is the core's own change, not an echo.
    #[test]
    fn an_earlier_text_after_a_later_echo_is_the_cores() {
        let mut t = Typed::default();
        t.sent("1");
        t.sent("12");
        assert_eq!(t.take(true, "12", "12"), None);
        assert_eq!(t.take(true, "12", "1").as_deref(), Some("1"));
    }

    /// A field not typed into shows the state, and nothing is set where it already does.
    #[test]
    fn a_field_not_typed_into_follows_the_state() {
        let mut t = Typed::default();
        assert_eq!(t.take(false, "", "1,082954").as_deref(), Some("1,082954"));
        assert_eq!(t.take(false, "1,082954", "1,082954"), None);
        t.sent("2");
        assert_eq!(t.take(false, "2", "1").as_deref(), Some("1"));
    }

    /// The rule against the mistake it prevents: a field set from every state that differs from what it shows would be
    /// rewritten to a keystroke behind ("12" while "123" shows).
    #[test]
    fn a_lagging_state_never_rewrites_a_focused_field() {
        let mut t = Typed::default();
        for s in ["a", "ab", "abc", "abcd"] {
            t.sent(s);
        }
        for lag in ["a", "ab", "abc"] {
            assert_eq!(t.take(true, "abcd", lag), None, "rewritten to {lag}");
        }
    }
}
