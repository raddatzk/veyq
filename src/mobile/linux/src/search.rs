//! The search over every space of the open tenant (ADR 0146 decisions 7, 8, 9, 12 and 13; the web's `SearchField.vue`), GNOME's
//! way: the shell's `GtkSearchBar` under the sidebar's title holds the `GtkSearchEntry` and, under it, the chips set — an
//! `AdwWrapBox` of buttons, each its icon, its value and a close icon, pressed to remove it. While something is asked the
//! sidebar's place is the search's list ([Search::list], a `GtkListBox` styled `.navigation-sidebar`, as GNOME Settings
//! lists a sidebar search's results): what the field says (a space still loading, spaces not searched, the empty state), a
//! *Filter by* heading with the chips offered, a separator, a *Results* heading with the rows — the name in markup, the
//! matched part bold, the second line dim — and *Show more*. A calm focus builds the index and shows no list.
//!
//! The keys: Ctrl+F opens and closes the bar (the shell's), Esc closes it (`GtkSearchBar`), Enter takes the first entry —
//! the first chip offered, else the first result —, Backspace in an empty entry removes the last chip, Down moves into
//! the list. A result opens where it lives (the core's `SearchModel.open`: the shell's state moves, a photo of another
//! Memory opens its lightbox through the gallery's request); a settings section is handed to [Search::on_settings]. What a
//! screen reader hears is the core's announcement, through GTK's `gtk_accessible_announce`.
//!
//! Every state is the core's (`Search.Open`, `Watched.Search`: `SearchShown`), every action one call, the text applied in
//! the order it was typed; nothing is decided here.
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::PartShown, wire::SearchShown, wire::Update, wire::Watched};
use crate::home::glib_escape;
use crate::{said, words};

/// What a list row does when activated.
#[derive(Clone, Copy, Debug, PartialEq)]
enum Entry {
    /// Suggestion [0] of `SearchShown.suggestions`: its chip set.
    Choose(i32),
    /// Result [0] of `SearchShown.results`: opened where it lives.
    Open(i32),
    /// *Show more*.
    More,
}

/// The shell's search: its bar (with the entry and the chips), its list, and the model in the core once opened.
pub struct Search {
    pub bar: gtk::SearchBar,
    pub entry: gtk::SearchEntry,
    /// The sidebar's place while something is asked ([Search::on_asked] says when).
    pub list: gtk::ScrolledWindow,
    inner: Rc<Inner>,
}

struct Inner {
    bar: gtk::SearchBar,
    entry: gtk::SearchEntry,
    chips: adw::WrapBox,
    rows: gtk::ListBox,
    /// The search's id in the core, once `Search.Open` answered; none before and after its tenant closed.
    id: Cell<Option<i64>>,
    opening: Cell<bool>,
    watch: RefCell<Option<Watch>>,
    /// What each row of [rows] does, by its index; None for a heading, a separator or a line of status.
    entries: RefCell<Vec<Option<Entry>>>,
    /// The text last told the core: the entry's change that repeats it (one the state set) is not told again.
    sent: RefCell<String>,
    /// A chip was chosen while this many were set: the next state with more takes the text the core kept.
    adopt: Cell<Option<usize>>,
    /// The announcement said last, so a state that repeats it says nothing.
    announced: RefCell<String>,
    /// How many settings results the last state counted (`SearchShown.openSettingsCount`): each new one opens the page,
    /// the same section chosen twice included; a state that repeats the count opens nothing.
    settings_opened: Cell<i32>,
    asked: Cell<bool>,
    on_asked: Listener<bool>,
    on_settings: Listener<String>,
    on_opened: Listener<()>,
}

/// One of the shell's listeners ([Search::on_asked], …), none until the shell sets it.
type Listener<T> = RefCell<Option<Box<dyn Fn(T)>>>;

impl Search {
    pub fn new() -> Self {
        let entry = gtk::SearchEntry::builder().placeholder_text(words::search_hint()).hexpand(true).build();
        entry.set_widget_name("search-entry");
        let chips = adw::WrapBox::builder().child_spacing(6).line_spacing(6).visible(false).build();
        chips.set_widget_name("search-chips");
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).hexpand(true).build();
        column.append(&entry);
        column.append(&chips);
        let bar = gtk::SearchBar::builder().child(&column).build();
        bar.connect_entry(&entry);
        let rows = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
        rows.add_css_class("navigation-sidebar");
        rows.set_widget_name("search-list");
        rows.update_property(&[gtk::accessible::Property::Label(&words::search_results())]);
        let list = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&rows).build();
        let inner = Rc::new(Inner {
            bar: bar.clone(),
            entry: entry.clone(),
            chips,
            rows,
            id: Cell::new(None),
            opening: Cell::new(false),
            watch: RefCell::new(None),
            entries: RefCell::new(Vec::new()),
            sent: RefCell::new(String::new()),
            adopt: Cell::new(None),
            announced: RefCell::new(String::new()),
            settings_opened: Cell::new(0),
            asked: Cell::new(false),
            on_asked: RefCell::new(None),
            on_settings: RefCell::new(None),
            on_opened: RefCell::new(None),
        });
        Inner::connect(&inner);
        Self { bar, entry, list, inner }
    }

    /// Called with whether something is asked (the list's place in the sidebar) whenever that changes.
    pub fn on_asked(&self, f: impl Fn(bool) + 'static) {
        *self.inner.on_asked.borrow_mut() = Some(Box::new(f));
    }

    /// Called with a settings section's id (`SettingsSection.id`) a result chose: the settings page opens there.
    pub fn on_settings(&self, f: impl Fn(String) + 'static) {
        *self.inner.on_settings.borrow_mut() = Some(Box::new(f));
    }

    /// Called when a result was opened (collapsed, the content then shows).
    pub fn on_opened(&self, f: impl Fn() + 'static) {
        *self.inner.on_opened.borrow_mut() = Some(Box::new(move |()| f()));
    }
}

impl Default for Search {
    fn default() -> Self {
        Self::new()
    }
}

impl Inner {
    fn connect(this: &Rc<Self>) {
        // the bar opened: the field's focus (the first builds the index); closed: the text and the chips go
        let weak = Rc::downgrade(this);
        this.bar.connect_search_mode_enabled_notify(move |bar| {
            let Some(i) = weak.upgrade() else { return };
            if bar.is_search_mode() {
                i.focus();
            } else {
                i.close();
            }
        });
        let weak = Rc::downgrade(this);
        this.entry.connect_search_changed(move |entry| {
            let Some(i) = weak.upgrade() else { return };
            let text = entry.text().to_string();
            if *i.sent.borrow() == text {
                return;
            }
            *i.sent.borrow_mut() = text.clone();
            if let Some(search) = i.id.get() {
                bridge::send(Call::SearchText { search, text });
            }
        });
        // Enter: the first entry (`activate`)
        let weak = Rc::downgrade(this);
        this.entry.connect_activate(move |_| {
            let Some(i) = weak.upgrade() else { return };
            let first = i.entries.borrow().iter().flatten().copied().find(|e| *e != Entry::More);
            if let Some(e) = first {
                eprintln!("veyq: search Enter takes {e:?}");
                i.act(e);
            }
        });
        // Backspace in an empty entry: the last chip; Down: into the list
        let keys = gtk::EventControllerKey::new();
        keys.set_propagation_phase(gtk::PropagationPhase::Capture);
        let weak = Rc::downgrade(this);
        keys.connect_key_pressed(move |_, key, _, modifiers| {
            let Some(i) = weak.upgrade() else { return glib::Propagation::Proceed };
            if !modifiers.is_empty() {
                return glib::Propagation::Proceed;
            }
            match key {
                gtk::gdk::Key::BackSpace if i.entry.text().is_empty() && i.chips.first_child().is_some() => {
                    if let Some(search) = i.id.get() {
                        eprintln!("veyq: search Backspace removes the last chip");
                        bridge::send(Call::SearchRemoveLastChip { search });
                    }
                    glib::Propagation::Stop
                }
                gtk::gdk::Key::Down => {
                    let first = (0..).map_while(|n| i.rows.row_at_index(n)).find(|r| r.is_activatable());
                    match first {
                        Some(row) => {
                            row.grab_focus();
                            glib::Propagation::Stop
                        }
                        None => glib::Propagation::Proceed,
                    }
                }
                _ => glib::Propagation::Proceed,
            }
        });
        this.entry.add_controller(keys);
        let weak = Rc::downgrade(this);
        this.rows.connect_row_activated(move |_, row| {
            let Some(i) = weak.upgrade() else { return };
            let entry = i.entries.borrow().get(row.index() as usize).copied().flatten();
            if let Some(e) = entry {
                i.act(e);
            }
        });
    }

    /// The field's focus: the search opened in the core first (once per tenant), then told.
    fn focus(self: &Rc<Self>) {
        if let Some(search) = self.id.get() {
            bridge::send(Call::SearchFocus { search });
            return;
        }
        if self.opening.replace(true) {
            return;
        }
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let answered = bridge::call(&Call::SearchOpen).await.map(|(a, _)| a);
            let Some(i) = weak.upgrade() else { return };
            i.opening.set(false);
            let search = match answered {
                Ok(Answer::SearchOpened { search }) => search,
                other => return eprintln!("veyq: Search.Open answered {other:?}"),
            };
            let Some((watch, updates)) = bridge::watch(&Watched::Search { search }) else { return };
            i.id.set(Some(search));
            *i.watch.borrow_mut() = Some(watch);
            eprintln!("veyq: search open");
            bridge::send(Call::SearchFocus { search });
            // what was typed while it opened
            let typed = i.entry.text().to_string();
            *i.sent.borrow_mut() = typed.clone();
            if !typed.is_empty() {
                bridge::send(Call::SearchText { search, text: typed });
            }
            let weak = Rc::downgrade(&i);
            drop(i);
            while let Ok(update) = updates.recv().await {
                let Some(i) = weak.upgrade() else { return };
                match update {
                    Ok(Update::Search { search: s, shown: Some(shown) }) if Some(s) == i.id.get() => i.draw(&shown),
                    // its tenant closed (a switch, a lock, a sign-out): the field closes; the next focus opens one anew
                    Ok(Update::Search { search: s, shown: None }) if Some(s) == i.id.get() => {
                        eprintln!("veyq: search closed with its tenant");
                        i.id.set(None);
                        i.watch.borrow_mut().take();
                        // the next search is a new model, counting from nothing
                        i.settings_opened.set(0);
                        i.bar.set_search_mode(false);
                        i.clear();
                        return;
                    }
                    Ok(_) => {}
                    Err(e) => eprintln!("veyq: a search update did not read: {:?}", e.0),
                }
            }
        });
    }

    /// The bar closed: the core forgets the text and the chips, the field and the list are emptied.
    fn close(&self) {
        if let Some(search) = self.id.get() {
            bridge::send(Call::SearchClose { search });
        }
        self.clear();
    }

    fn clear(&self) {
        *self.sent.borrow_mut() = String::new();
        self.entry.set_text("");
        self.adopt.set(None);
        self.announced.borrow_mut().clear();
        while let Some(c) = self.chips.first_child() {
            self.chips.remove(&c);
        }
        self.chips.set_visible(false);
        self.rows.remove_all();
        self.entries.borrow_mut().clear();
        self.set_asked(false);
    }

    fn set_asked(&self, asked: bool) {
        if self.asked.replace(asked) != asked
            && let Some(f) = self.on_asked.borrow().as_ref()
        {
            f(asked);
        }
    }

    fn act(&self, entry: Entry) {
        let Some(search) = self.id.get() else { return };
        match entry {
            Entry::Choose(index) => {
                self.adopt.set(Some(self.chips_set()));
                bridge::send(Call::SearchChoose { search, index });
            }
            Entry::Open(index) => {
                bridge::send(Call::SearchOpenResult { search, index });
                if let Some(f) = self.on_opened.borrow().as_ref() {
                    f(());
                }
            }
            Entry::More => bridge::send(Call::SearchMore { search }),
        }
    }

    fn chips_set(&self) -> usize {
        let mut n = 0;
        let mut c = self.chips.first_child();
        while let Some(w) = c {
            n += 1;
            c = w.next_sibling();
        }
        n
    }

    fn draw(self: &Rc<Self>, s: &SearchShown) {
        eprintln!("veyq: search shown: asked {}, {} chips, {} offered, {} results of {}, {} more, status {:?}",
            s.asked, s.chips.len(), s.suggestions.len(), s.results.len(), s.total, s.more, s.status.iter().map(|n| n.msg.as_str()).collect::<Vec<_>>());
        // a chip chosen: the text is what the core kept of it (the chip's words gone)
        if let Some(before) = self.adopt.get()
            && s.chips.len() > before
        {
            self.adopt.set(None);
            if self.entry.text() != s.text.as_str() {
                *self.sent.borrow_mut() = s.text.clone();
                self.entry.set_text(&s.text);
                self.entry.set_position(-1);
            }
        }
        self.draw_chips(s);
        self.draw_rows(s);
        self.set_asked(s.asked);
        self.announce(s);
        // a settings result: the page opens at its section, once per result chosen (the count moves with each)
        if self.settings_opened.replace(s.open_settings_count) != s.open_settings_count
            && let (Some(section), Some(f)) = (s.open_settings.clone(), self.on_settings.borrow().as_ref())
        {
            eprintln!("veyq: search opens settings at {section} ({})", s.open_settings_count);
            f(section);
        }
    }

    fn draw_chips(&self, s: &SearchShown) {
        while let Some(c) = self.chips.first_child() {
            self.chips.remove(&c);
        }
        for (index, chip) in s.chips.iter().enumerate() {
            let label = said::piece(&chip.label);
            let named = format!("{}: {label}", said::key(&chip.word));
            // the button removes it: its name says so, its tooltip names the chip (the web's ui.removeTag)
            let removes = words::search_remove_chip(&named);
            let content = gtk::Box::new(gtk::Orientation::Horizontal, 6);
            content.append(&gtk::Image::from_icon_name(icon(&chip.icon)));
            content.append(&gtk::Label::new(Some(&label)));
            content.append(&gtk::Image::from_icon_name("window-close-symbolic"));
            let button = gtk::Button::builder().child(&content).tooltip_text(named.as_str()).build();
            button.add_css_class("small");
            button.set_widget_name("search-chip");
            button.update_property(&[gtk::accessible::Property::Label(&removes)]);
            let (search, index) = (self.id.get(), index as i32);
            button.connect_clicked(move |_| {
                if let Some(search) = search {
                    bridge::send(Call::SearchRemoveChip { search, index });
                }
            });
            self.chips.append(&button);
        }
        self.chips.set_visible(!s.chips.is_empty());
    }

    fn draw_rows(&self, s: &SearchShown) {
        // the row the keyboard stands on stays where it was by its index, so arrowing through the list survives a redraw
        let focused = self.rows.focus_child().and_downcast::<gtk::ListBoxRow>().map(|r| r.index());
        self.rows.remove_all();
        let mut entries = Vec::new();
        if !s.asked {
            *self.entries.borrow_mut() = entries;
            return;
        }
        for n in &s.status {
            let line = gtk::Label::builder().label(said::notice(n)).wrap(true).xalign(0.0).margin_start(6).margin_end(6).build();
            line.add_css_class("dim-label");
            self.rows.append(&still(&line));
            entries.push(None);
        }
        if !s.suggestions.is_empty() {
            self.rows.append(&still(&heading(&words::search_filter_by())));
            entries.push(None);
            for (i, o) in s.suggestions.iter().enumerate() {
                let word = said::key(&o.word);
                let label = said::piece(&o.label);
                let row = row(icon(&o.icon), &glib_escape(&label), &glib_escape(&said::line(&o.r#where)), Some(&word));
                row.update_property(&[gtk::accessible::Property::Label(&format!("{word}: {label}"))]);
                self.rows.append(&row);
                entries.push(Some(Entry::Choose(i as i32)));
            }
        }
        if !s.results.is_empty() {
            let results = still(&heading(&words::search_results()));
            // the separator between the chips offered and the results: the heading row's header, as a list box separates
            // (a row of its own drew as a filled block in `.navigation-sidebar`)
            if !s.suggestions.is_empty() {
                results.set_header(Some(&gtk::Separator::builder().margin_top(6).margin_start(6).margin_end(6).build()));
            }
            self.rows.append(&results);
            entries.push(None);
            for (i, r) in s.results.iter().enumerate() {
                let line = said::line(&r.line);
                let mut subtitle = glib_escape(&line);
                if let Some(reason) = &r.reason {
                    if !subtitle.is_empty() {
                        subtitle.push_str(" · ");
                    }
                    subtitle.push_str(&markup(reason));
                }
                let row = row(icon(&r.icon), &markup(&r.name), &subtitle, None);
                let plain: String = r.name.iter().map(|p| p.text.as_str()).collect();
                let reason: String = r.reason.iter().flatten().map(|p| p.text.as_str()).collect();
                let said = [plain, line, reason].into_iter().filter(|t| !t.is_empty()).collect::<Vec<_>>().join(", ");
                row.update_property(&[gtk::accessible::Property::Label(&said)]);
                self.rows.append(&row);
                entries.push(Some(Entry::Open(i as i32)));
            }
        }
        if s.more > 0 {
            let more = row("view-more-symbolic", &glib_escape(&words::search_show_more(i64::from(s.more))), "", None);
            more.set_widget_name("search-more");
            self.rows.append(&more);
            entries.push(Some(Entry::More));
        }
        *self.entries.borrow_mut() = entries;
        if let Some(row) = focused.and_then(|at| self.rows.row_at_index(at)) {
            row.grab_focus();
        }
    }

    /// The core's announcement for a screen reader, said once it changed (`gtk_accessible_announce`, polite).
    fn announce(&self, s: &SearchShown) {
        let text = s.announcement.iter().map(said::notice).collect::<Vec<_>>().join(" ");
        if text.is_empty() || *self.announced.borrow() == text {
            return;
        }
        *self.announced.borrow_mut() = text.clone();
        let context = self.entry.at_context().type_().name();
        eprintln!("veyq: search announces {:?} through {context}", s.announcement.iter().map(|n| n.msg.as_str()).collect::<Vec<_>>());
        if speaks(context) {
            self.entry.announce(&text, gtk::AccessibleAnnouncementPriority::Medium);
        }
    }
}

/// Whether an accessibility context of type [name] reaches a screen reader. Only those are asked to announce: GTK's test
/// context, which a display without an accessibility bus gets (Broadway's), implements no announcement, and
/// `gtk_accessible_announce` called through it once realized jumped to NULL (measured 2026-11-18, GTK 4.22 under Broadway:
/// SIGSEGV after a list row had held the focus).
fn speaks(name: &str) -> bool {
    matches!(name, "GtkAtSpiContext" | "GtkAccessKitContext")
}

/// A name in its parts as markup: each escaped, the matched ones bold.
fn markup(parts: &[PartShown]) -> String {
    parts.iter().map(|p| if p.strong { format!("<b>{}</b>", glib_escape(&p.text)) } else { glib_escape(&p.text) }).collect()
}

/// A list row: an icon, a markup [title], a dim markup [subtitle] under it where it has one, and a dim [suffix] at its end.
fn row(icon: &str, title: &str, subtitle: &str, suffix: Option<&str>) -> gtk::ListBoxRow {
    let content = gtk::Box::builder().spacing(12).margin_top(3).margin_bottom(3).build();
    content.append(&gtk::Image::from_icon_name(icon));
    let lines = gtk::Box::builder().orientation(gtk::Orientation::Vertical).hexpand(true).valign(gtk::Align::Center).build();
    let name = gtk::Label::builder().label(title).use_markup(true).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).build();
    lines.append(&name);
    if !subtitle.is_empty() {
        let second = gtk::Label::builder().label(subtitle).use_markup(true).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).build();
        second.add_css_class("dim-label");
        second.add_css_class("caption");
        lines.append(&second);
    }
    content.append(&lines);
    if let Some(suffix) = suffix {
        let kind = gtk::Label::new(Some(suffix));
        kind.add_css_class("dim-label");
        kind.add_css_class("caption");
        content.append(&kind);
    }
    gtk::ListBoxRow::builder().child(&content).build()
}

/// A heading over a part of the list, as `AdwSidebar` titles its sections.
fn heading(text: &str) -> gtk::Label {
    let label = gtk::Label::builder().label(text).xalign(0.0).margin_start(6).margin_top(6).build();
    label.add_css_class("heading");
    label.add_css_class("dim-label");
    label
}

/// A row that is no entry: a heading, a line the field says.
fn still(child: &impl IsA<gtk::Widget>) -> gtk::ListBoxRow {
    gtk::ListBoxRow::builder().child(child).activatable(false).selectable(false).can_focus(false).build()
}

/// The icon of the core's icon kind (`ChipShown.icon`, `ResultShown.icon`: the web's icon names), as the shell draws the
/// same kinds ([crate::home::kind_icon]).
pub fn icon(kind: &str) -> &'static str {
    match kind {
        "folder" => "folder-symbolic",
        "folder-open" => "folder-open-symbolic",
        "bookmark" => "folder-publicshare-symbolic",
        "inbox" => "folder-download-symbolic",
        "wallet" => "x-office-spreadsheet-symbolic",
        "images" => "camera-photo-symbolic",
        "image" => "image-x-generic-symbolic",
        "play" => "video-x-generic-symbolic",
        "user" => "avatar-default-symbolic",
        "users" => "system-users-symbolic",
        "settings" => "emblem-system-symbolic",
        "calendar" => "x-office-calendar-symbolic",
        "map-marker" => "mark-location-symbolic",
        "tag" => "view-grid-symbolic",
        _ => "text-x-generic-symbolic",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_name_is_escaped_and_its_matched_part_bold() {
        let parts = [
            PartShown { text: "Ost".into(), strong: true },
            PartShown { text: "see <2026> & mehr".into(), strong: false },
        ];
        assert_eq!(markup(&parts), "<b>Ost</b>see &lt;2026&gt; &amp; mehr");
        assert_eq!(markup(&[PartShown { text: "<b>".into(), strong: true }]), "<b>&lt;b&gt;</b>", "a name's own markup is text");
    }

    #[test]
    fn only_a_context_that_reaches_a_screen_reader_is_asked_to_announce() {
        assert!(speaks("GtkAtSpiContext"));
        assert!(speaks("GtkAccessKitContext"));
        assert!(!speaks("GtkTestATContext"), "Broadway's: no announcement there, and asking it crashed");
    }

    #[test]
    fn every_icon_kind_the_core_sends_has_its_own_icon() {
        for kind in ["folder", "folder-open", "bookmark", "inbox", "wallet", "images", "image", "play", "user", "users", "settings", "calendar", "map-marker", "tag"] {
            assert_ne!(icon(kind), icon("file"), "{kind}");
        }
        assert_eq!(icon("file"), "text-x-generic-symbolic");
    }
}
