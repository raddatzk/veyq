//! A Memory's date and place on Linux (ADR 0143 decision 5; Windows' `MemoryFacts`, Apple's `MemoryFactsLine`): the facts
//! line under its head — its date and its place, each a flat button to its editor where whoever looks may edit its meta
//! (`GalleryShown.editsFacts`), text otherwise — and the two editors as `AdwDialog`s, GNOME's way: a header bar with
//! Cancel and Save, a boxed list of rows. The date editor (`Date.Open`, `Watched.Date`) offers the four modes in a combo
//! row and each day through a `GtkCalendar` in a popover; `auto` previews the range the photos give. The place editor
//! (`Place.Open`, `Watched.Place`) holds the place's map ([crate::raster_map]) panned under its fixed centre pin — no drag
//! of a pin, no search —, *Follow the photos*, the level picker from the reverse lookup, the typed name and *Remove
//! location*; without a map (none served, no consent, offline) the name field alone, the consent's offer where the map
//! would stand (the map's own place says it). Every rule is the core's; a field set from the state is no edit of the
//! member's ([crate::typed]).
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::DateMode, wire::DateShown, wire::FactsSave, wire::GalleryShown,
    wire::MemoryDateShown, wire::PlaceMode, wire::PlaceNaming, wire::PlaceShown, wire::Update, wire::Watched};
use crate::home::glib_escape;
use crate::raster_map::RasterMap;
use crate::typed::Typed;
use crate::{said, words};

/// A Memory's date as the facts line and its row say it: one day, a range, "Since …" for an ongoing one; none without a day.
pub fn date_text(d: &MemoryDateShown) -> Option<String> {
    let from = d.from.as_deref().filter(|f| !f.is_empty())?;
    if d.mode == DateMode::Ongoing {
        return Some(words::memory_date_since(&said::day(from)));
    }
    match d.to.as_deref().filter(|t| !t.is_empty() && *t > from) {
        Some(to) => Some(format!("{} – {}", said::day(from), said::day(to))),
        None => Some(said::day(from)),
    }
}

/// The facts line under a Memory's or a chapter's head: built once, drawn from each gallery state.
pub struct FactsLine {
    root: gtk::Box,
    date: gtk::Button,
    date_content: adw::ButtonContent,
    place: gtk::Button,
    place_content: adw::ButtonContent,
    gallery: Rc<Cell<Option<i64>>>,
}

impl FactsLine {
    pub fn new(window: &gtk::Window) -> Self {
        let root = gtk::Box::builder().spacing(6).visible(false).build();
        root.set_widget_name("memory-facts");
        let fact = |icon: &str, id: &str| {
            let content = adw::ButtonContent::builder().icon_name(icon).can_shrink(true).build();
            let button = gtk::Button::builder().child(&content).build();
            button.add_css_class("flat");
            button.set_widget_name(id);
            (button, content)
        };
        let (date, date_content) = fact("x-office-calendar-symbolic", "memory-date");
        let (place, place_content) = fact("mark-location-symbolic", "memory-place");
        root.append(&date);
        root.append(&place);
        let gallery = Rc::new(Cell::new(None));
        let (g, w) = (gallery.clone(), window.clone());
        date.connect_clicked(move |_| {
            if let Some(gallery) = g.get() {
                DateEditor::open(gallery, &w);
            }
        });
        let (g, w) = (gallery.clone(), window.clone());
        place.connect_clicked(move |_| {
            if let Some(gallery) = g.get() {
                PlaceEditor::open(gallery, &w);
            }
        });
        Self { root, date, date_content, place, place_content, gallery }
    }

    pub fn widget(&self) -> &gtk::Box {
        &self.root
    }

    /// The line as gallery [gallery]'s state [s] says it: a fact shown where it has one or where it may be set; a button
    /// only where it opens its editor.
    pub fn draw(&self, s: &GalleryShown, gallery: i64) {
        self.gallery.set(Some(gallery));
        let date = s.date.as_ref().and_then(date_text);
        let edits = s.edits_facts;
        let (shows_date, shows_place) = (date.is_some() || edits, s.place.is_some() || edits);
        self.date.set_visible(shows_date);
        self.date_content.set_label(&date.clone().unwrap_or_else(words::memory_date_none));
        self.place.set_visible(shows_place);
        self.place_content.set_label(&s.place.clone().unwrap_or_else(words::memory_place_set));
        for (b, tip, has) in [(&self.date, words::memory_date_edit(), date.is_some()), (&self.place, words::memory_place_edit(), s.place.is_some())] {
            b.set_can_target(edits);
            b.set_focusable(edits);
            b.set_tooltip_text(edits.then_some(tip.as_str()));
            if has { b.remove_css_class("dim-label") } else { b.add_css_class("dim-label") }
        }
        // set from what it shows, never read back: a hidden line's children read as hidden too
        self.root.set_visible(shows_date || shows_place);
        eprintln!("veyq: facts line: date {}, place {}, edits {edits}", date.is_some(), s.place.is_some());
    }
}

/// A dialog's header bar: Cancel at its start, Save (suggested) at its end; the dialog's own title between.
fn header(dialog: &adw::Dialog) -> (adw::HeaderBar, gtk::Button) {
    let bar = adw::HeaderBar::builder().show_end_title_buttons(false).show_start_title_buttons(false).build();
    let cancel = gtk::Button::builder().label(words::entry_cancel()).build();
    let d = dialog.downgrade();
    cancel.connect_clicked(move |_| {
        if let Some(d) = d.upgrade() {
            d.close();
        }
    });
    bar.pack_start(&cancel);
    let save = gtk::Button::builder().label(words::entry_save()).sensitive(false).build();
    save.add_css_class("suggested-action");
    save.set_widget_name("facts-save");
    bar.pack_end(&save);
    (bar, save)
}

/// A line under a group saying why the last save did not land.
fn error_label() -> gtk::Label {
    let l = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
    l.add_css_class("error");
    l
}

// --- the date editor -------------------------------------------------------------------------------------------------

struct DateEditor {
    id: Cell<Option<i64>>,
    dialog: adw::Dialog,
    save: gtk::Button,
    mode: adw::ComboRow,
    from: adw::ActionRow,
    from_calendar: gtk::Calendar,
    to: adw::ActionRow,
    to_calendar: gtk::Calendar,
    auto: adw::ActionRow,
    invalid: gtk::Label,
    failed: gtk::Label,
    shown: RefCell<Option<DateShown>>,
    drawing: Cell<bool>,
    watch: RefCell<Option<Watch>>,
}

const MODES: [DateMode; 4] = [DateMode::Auto, DateMode::Single, DateMode::Range, DateMode::Ongoing];

/// An ISO day (`yyyy-MM-dd`) of [calendar]'s chosen date.
fn calendar_day(calendar: &gtk::Calendar) -> String {
    let d = calendar.date();
    format!("{:04}-{:02}-{:02}", d.year(), d.month(), d.day_of_month())
}

/// A day's row: its title, the day it holds said as the locale writes one, and a calendar in a popover to choose it.
fn day_row(id: &str) -> (adw::ActionRow, gtk::Calendar) {
    let calendar = gtk::Calendar::new();
    let popover = gtk::Popover::builder().child(&calendar).build();
    let pick = gtk::MenuButton::builder().icon_name("x-office-calendar-symbolic").popover(&popover).valign(gtk::Align::Center).build();
    pick.add_css_class("flat");
    pick.set_widget_name(id);
    let row = adw::ActionRow::builder().activatable_widget(&pick).build();
    row.add_suffix(&pick);
    (row, calendar)
}

impl DateEditor {
    fn open(gallery: i64, window: &gtk::Window) {
        let dialog = adw::Dialog::builder().title(words::memory_date_title()).content_width(420).build();
        let (bar, save) = header(&dialog);
        let mode = adw::ComboRow::builder().title(words::memory_date_title())
            .model(&gtk::StringList::new(&[words::memory_date_mode_auto().as_str(), &words::memory_date_mode_single(), &words::memory_date_mode_range(), &words::memory_date_mode_ongoing()]))
            .build();
        mode.set_widget_name("date-mode");
        let (from, from_calendar) = day_row("date-from");
        let (to, to_calendar) = day_row("date-to");
        to.set_title(&words::memory_date_to());
        let auto = adw::ActionRow::builder().title(words::memory_date_mode_auto()).build();
        auto.add_css_class("property");
        let group = adw::PreferencesGroup::new();
        for r in [mode.upcast_ref::<gtk::Widget>(), from.upcast_ref(), to.upcast_ref(), auto.upcast_ref()] {
            group.add(r);
        }
        let (invalid, failed) = (error_label(), error_label());
        invalid.set_label(&words::memory_date_invalid_range());
        failed.set_label(&words::node_save_failed());
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).margin_top(12).margin_bottom(18).margin_start(12).margin_end(12).build();
        column.append(&group);
        column.append(&invalid);
        column.append(&failed);
        let view = adw::ToolbarView::new();
        view.add_top_bar(&bar);
        view.set_content(Some(&column));
        dialog.set_child(Some(&view));
        let this = Rc::new(Self {
            id: Cell::new(None),
            dialog,
            save,
            mode,
            from,
            from_calendar,
            to,
            to_calendar,
            auto,
            invalid,
            failed,
            shown: RefCell::new(None),
            drawing: Cell::new(false),
            watch: RefCell::new(None),
        });
        this.connect();
        let held = RefCell::new(Some(this.clone()));
        this.dialog.connect_closed(move |_| {
            if let Some(e) = held.borrow_mut().take() {
                e.watch.borrow_mut().take();
                if let Some(date) = e.id.take() {
                    bridge::send(Call::DateClose { date });
                }
            }
        });
        let weak = Rc::downgrade(&this);
        glib::spawn_future_local(async move {
            let date = match bridge::call(&Call::DateOpen { gallery }).await.map(|(a, _)| a) {
                Ok(Answer::DateOpened { date }) => date,
                other => {
                    eprintln!("veyq: Date.Open answered {other:?}");
                    if let Some(e) = weak.upgrade() {
                        e.dialog.force_close();
                    }
                    return;
                }
            };
            let Some(e) = weak.upgrade() else { return bridge::send(Call::DateClose { date }) };
            e.id.set(Some(date));
            eprintln!("veyq: date editor open");
            let Some((watch, updates)) = bridge::watch(&Watched::Date { date }) else { return };
            *e.watch.borrow_mut() = Some(watch);
            drop(e);
            while let Ok(update) = updates.recv().await {
                let Some(e) = weak.upgrade() else { return };
                match update {
                    Ok(Update::Date { shown: Some(s), .. }) => e.draw(s),
                    Ok(Update::Date { shown: None, .. }) => {
                        e.id.set(None);
                        e.dialog.force_close();
                        return;
                    }
                    Ok(_) => {}
                    Err(err) => eprintln!("veyq: a date editor update did not read: {:?}", err.0),
                }
            }
        });
        this.dialog.present(Some(window));
    }

    fn connect(self: &Rc<Self>) {
        let weak = Rc::downgrade(self);
        self.mode.connect_selected_notify(move |row| {
            let Some(e) = weak.upgrade() else { return };
            if e.drawing.get() {
                return;
            }
            if let (Some(date), Some(mode)) = (e.id.get(), MODES.get(row.selected() as usize)) {
                eprintln!("veyq: date editor: mode {mode:?}");
                bridge::send(Call::DateMode { date, mode: *mode });
            }
        });
        for (calendar, to) in [(&self.from_calendar, false), (&self.to_calendar, true)] {
            let weak = Rc::downgrade(self);
            calendar.connect_day_selected(move |c| {
                let Some(e) = weak.upgrade() else { return };
                if e.drawing.get() {
                    return;
                }
                let Some(date) = e.id.get() else { return };
                let day = calendar_day(c);
                eprintln!("veyq: date editor: {} {day}", if to { "to" } else { "from" });
                bridge::send(if to { Call::DateTo { date, day } } else { Call::DateFrom { date, day } });
            });
        }
        let weak = Rc::downgrade(self);
        self.save.connect_clicked(move |_| {
            let Some(e) = weak.upgrade() else { return };
            let Some(date) = e.id.get() else { return };
            e.save.set_sensitive(false);
            e.failed.set_visible(false);
            let weak = Rc::downgrade(&e);
            glib::spawn_future_local(async move {
                let answered = bridge::call(&Call::DateSave { date }).await.map(|(a, _)| a);
                let Some(e) = weak.upgrade() else { return };
                eprintln!("veyq: Date.Save answered {answered:?}");
                match answered {
                    Ok(Answer::FactsSaved { outcome: FactsSave::Saved }) => {
                        e.dialog.close();
                    }
                    // re-opened on a peer's date written meanwhile: the editor shows it now
                    Ok(Answer::FactsSaved { outcome: FactsSave::Reopened }) => e.redraw(),
                    _ => {
                        e.failed.set_visible(true);
                        e.redraw();
                    }
                }
            });
        });
    }

    fn redraw(&self) {
        if let Some(s) = self.shown.borrow().clone() {
            self.draw(s);
        }
    }

    fn draw(&self, s: DateShown) {
        eprintln!("veyq: date editor shown: {:?} {}..{}, invalid {}, save {}", s.mode, s.from, s.to, s.range_invalid, s.can_save);
        self.drawing.set(true);
        let at = MODES.iter().position(|m| *m == s.mode).unwrap_or(0) as u32;
        if self.mode.selected() != at {
            self.mode.set_selected(at);
        }
        let day = |iso: &str| if iso.is_empty() { String::new() } else { said::day(iso) };
        self.from.set_title(&match s.mode {
            DateMode::Single => words::memory_date_day(),
            DateMode::Range => words::memory_date_from(),
            _ => words::memory_date_start(),
        });
        self.from.set_subtitle(&glib_escape(&day(&s.from)));
        self.to.set_subtitle(&glib_escape(&day(&s.to)));
        for (calendar, iso) in [(&self.from_calendar, &s.from), (&self.to_calendar, &s.to)] {
            let mut parts = iso.splitn(3, '-').map(str::parse::<i32>);
            if let (Some(Ok(y)), Some(Ok(m)), Some(Ok(d))) = (parts.next(), parts.next(), parts.next())
                && let Ok(date) = glib::DateTime::from_local(y, m, d, 0, 0, 0.0)
                && calendar_day(calendar) != *iso
            {
                calendar.set_date(&date);
            }
        }
        self.from.set_visible(s.mode != DateMode::Auto);
        self.to.set_visible(s.mode == DateMode::Range);
        self.auto.set_visible(s.mode == DateMode::Auto);
        match date_text(&s.auto) {
            Some(range) => {
                self.auto.set_subtitle(&glib_escape(&format!("{range}\n{}", words::memory_date_auto_hint())));
            }
            None => self.auto.set_subtitle(&glib_escape(&words::memory_date_auto_empty())),
        }
        self.invalid.set_visible(s.range_invalid);
        self.save.set_sensitive(s.can_save);
        *self.shown.borrow_mut() = Some(s);
        self.drawing.set(false);
    }
}

// --- the place editor ------------------------------------------------------------------------------------------------

struct PlaceEditor {
    id: Cell<Option<i64>>,
    dialog: adw::Dialog,
    save: gtk::Button,
    map_slot: gtk::Box,
    map: RefCell<Option<RasterMap>>,
    mode: gtk::Label,
    follow: gtk::Button,
    hint: gtk::Label,
    name: adw::EntryRow,
    typed: RefCell<Typed>,
    level: adw::ComboRow,
    levels: RefCell<Vec<String>>,
    toggle: gtk::Button,
    remove: gtk::Button,
    failed: gtk::Label,
    shown: RefCell<Option<PlaceShown>>,
    drawing: Cell<bool>,
    watch: RefCell<Option<Watch>>,
    window: gtk::Window,
}

impl PlaceEditor {
    fn open(gallery: i64, window: &gtk::Window) {
        let dialog = adw::Dialog::builder().title(words::memory_place_title()).content_width(560).content_height(720).build();
        let (bar, save) = header(&dialog);
        let map_slot = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
        let mode = gtk::Label::builder().xalign(0.0).hexpand(true).wrap(true).build();
        mode.add_css_class("dim-label");
        let follow = gtk::Button::builder().label(words::memory_place_follow_again()).visible(false).build();
        follow.add_css_class("flat");
        follow.set_widget_name("place-follow");
        let mode_line = gtk::Box::builder().spacing(6).build();
        mode_line.append(&mode);
        mode_line.append(&follow);
        let hint = gtk::Label::builder().xalign(0.0).wrap(true).build();
        hint.add_css_class("caption");
        hint.add_css_class("dim-label");
        let name = adw::EntryRow::builder().title(words::memory_place_name()).build();
        name.set_widget_name("place-name");
        let level = adw::ComboRow::builder().title(words::memory_place_title()).visible(false).build();
        level.set_widget_name("place-level");
        let group = adw::PreferencesGroup::new();
        group.add(&name);
        group.add(&level);
        let toggle = gtk::Button::builder().halign(gtk::Align::Start).visible(false).build();
        toggle.add_css_class("flat");
        toggle.set_widget_name("place-toggle-name");
        let remove = gtk::Button::builder().label(words::memory_place_remove()).halign(gtk::Align::Start).visible(false).build();
        remove.add_css_class("destructive-action");
        remove.set_widget_name("place-remove");
        let failed = error_label();
        failed.set_label(&words::node_save_failed());
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).margin_top(12).margin_bottom(18).margin_start(12).margin_end(12).build();
        for w in [map_slot.upcast_ref::<gtk::Widget>(), mode_line.upcast_ref(), hint.upcast_ref(), group.upcast_ref(), toggle.upcast_ref(), failed.upcast_ref(), remove.upcast_ref()] {
            column.append(w);
        }
        let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).propagate_natural_height(true).child(&column).build();
        let view = adw::ToolbarView::new();
        view.add_top_bar(&bar);
        view.set_content(Some(&scroll));
        dialog.set_child(Some(&view));
        let this = Rc::new(Self {
            id: Cell::new(None),
            dialog,
            save,
            map_slot,
            map: RefCell::new(None),
            mode,
            follow,
            hint,
            name,
            typed: RefCell::new(Typed::default()),
            level,
            levels: RefCell::new(Vec::new()),
            toggle,
            remove,
            failed,
            shown: RefCell::new(None),
            drawing: Cell::new(false),
            watch: RefCell::new(None),
            window: window.clone(),
        });
        this.connect();
        let held = RefCell::new(Some(this.clone()));
        this.dialog.connect_closed(move |_| {
            if let Some(e) = held.borrow_mut().take() {
                e.watch.borrow_mut().take();
                e.map.borrow_mut().take();
                if let Some(place) = e.id.take() {
                    // its map closes with it
                    bridge::send(Call::PlaceClose { place });
                }
            }
        });
        let weak = Rc::downgrade(&this);
        glib::spawn_future_local(async move {
            let (place, map) = match bridge::call(&Call::PlaceOpen { gallery }).await.map(|(a, _)| a) {
                Ok(Answer::PlaceOpened { place, map }) => (place, map),
                other => {
                    eprintln!("veyq: Place.Open answered {other:?}");
                    if let Some(e) = weak.upgrade() {
                        e.dialog.force_close();
                    }
                    return;
                }
            };
            let Some(e) = weak.upgrade() else { return bridge::send(Call::PlaceClose { place }) };
            e.id.set(Some(place));
            eprintln!("veyq: place editor open, map {map:?}");
            if let Some(map) = map {
                let m = RasterMap::open(map, 320, false, &e.window);
                e.map_slot.append(m.widget());
                *e.map.borrow_mut() = Some(m);
            }
            let Some((watch, updates)) = bridge::watch(&Watched::Place { place }) else { return };
            *e.watch.borrow_mut() = Some(watch);
            drop(e);
            follow_place(weak, updates).await;
        });
        this.dialog.present(Some(window));
    }

    fn connect(self: &Rc<Self>) {
        let send = |weak: &Weak<Self>, make: fn(i64) -> Call, what: &'static str| {
            let weak = weak.clone();
            move |_: &gtk::Button| {
                if let Some(place) = weak.upgrade().and_then(|e| e.id.get()) {
                    eprintln!("veyq: place editor: {what}");
                    bridge::send(make(place));
                }
            }
        };
        let weak = Rc::downgrade(self);
        self.follow.connect_clicked(send(&weak, |place| Call::PlaceFollow { place }, "follow the photos"));
        self.toggle.connect_clicked(send(&weak, |place| Call::PlaceToggleName { place }, "toggle the name"));
        let weak = Rc::downgrade(self);
        self.name.connect_changed(move |entry| {
            let Some(e) = weak.upgrade() else { return };
            if e.drawing.get() {
                return;
            }
            let Some(place) = e.id.get() else { return };
            let name = entry.text().to_string();
            e.typed.borrow_mut().sent(&name);
            bridge::send(Call::PlaceName { place, name });
        });
        let weak = Rc::downgrade(self);
        self.level.connect_selected_notify(move |row| {
            let Some(e) = weak.upgrade() else { return };
            if e.drawing.get() {
                return;
            }
            let level = e.levels.borrow().get(row.selected() as usize).cloned();
            if let (Some(place), Some(level)) = (e.id.get(), level) {
                eprintln!("veyq: place editor: level {level}");
                bridge::send(Call::PlaceLevel { place, level });
            }
        });
        for (button, remove) in [(&self.save, false), (&self.remove, true)] {
            let weak = Rc::downgrade(self);
            button.connect_clicked(move |_| {
                let Some(e) = weak.upgrade() else { return };
                let Some(place) = e.id.get() else { return };
                e.failed.set_visible(false);
                let weak = Rc::downgrade(&e);
                glib::spawn_future_local(async move {
                    let call = if remove { Call::PlaceRemove { place } } else { Call::PlaceSave { place } };
                    let answered = bridge::call(&call).await.map(|(a, _)| a);
                    eprintln!("veyq: Place.{} answered {answered:?}", if remove { "Remove" } else { "Save" });
                    let Some(e) = weak.upgrade() else { return };
                    match answered {
                        Ok(Answer::FactsSaved { outcome: FactsSave::Saved }) => {
                        e.dialog.close();
                    }
                        Ok(Answer::FactsSaved { outcome: FactsSave::Reopened }) => {}
                        _ => e.failed.set_visible(true),
                    }
                });
            });
        }
    }

    fn draw(&self, s: PlaceShown) {
        eprintln!("veyq: place editor shown: map {}, {:?}, {:?} name ({} chars), {} levels, chosen {:?}, picker {}, save {}, follow {}, remove {}",
            s.with_map, s.mode, s.naming, s.name.chars().count(), s.levels.len(), s.chosen_level, s.level_picker, s.can_save, s.can_follow, s.can_remove);
        self.drawing.set(true);
        self.mode.set_label(&if !s.with_map {
            words::memory_place_name_only()
        } else if s.mode == PlaceMode::Auto {
            words::memory_place_follows_photos()
        } else {
            words::memory_place_custom()
        });
        self.follow.set_visible(s.with_map && s.mode == PlaceMode::Fixed && s.can_follow);
        self.hint.set_label(&words::memory_place_move_to_set());
        self.hint.set_visible(s.with_map);
        let focused = self.name.state_flags().contains(gtk::StateFlags::FOCUS_WITHIN);
        if let Some(text) = self.typed.borrow_mut().take(focused, &self.name.text(), &s.name) {
            self.name.set_text(&text);
        }
        let tokens: Vec<String> = s.levels.iter().map(|l| l.level.clone()).collect();
        if *self.levels.borrow() != tokens {
            let names: Vec<&str> = s.levels.iter().map(|l| l.name.as_str()).collect();
            self.level.set_model(Some(&gtk::StringList::new(&names)));
            *self.levels.borrow_mut() = tokens;
        }
        if let Some(at) = s.chosen_level.as_ref().and_then(|c| self.levels.borrow().iter().position(|l| l == c))
            && self.level.selected() != at as u32
        {
            self.level.set_selected(at as u32);
        }
        self.level.set_visible(s.level_picker && !s.levels.is_empty());
        let geocoded = s.naming == PlaceNaming::Geocoded;
        self.toggle.set_label(&if geocoded { words::memory_place_name_own() } else { words::memory_place_name_from_map() });
        self.toggle.set_visible(s.with_map && (geocoded || !s.levels.is_empty()));
        self.remove.set_visible(s.can_remove);
        self.save.set_sensitive(s.can_save);
        *self.shown.borrow_mut() = Some(s);
        self.drawing.set(false);
    }
}

async fn follow_place(weak: Weak<PlaceEditor>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(e) = weak.upgrade() else { return };
        match update {
            Ok(Update::Place { shown: Some(s), .. }) => e.draw(s),
            Ok(Update::Place { shown: None, .. }) => {
                e.id.set(None);
                e.dialog.force_close();
                return;
            }
            Ok(_) => {}
            Err(err) => eprintln!("veyq: a place editor update did not read: {:?}", err.0),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(mode: DateMode, from: Option<&str>, to: Option<&str>) -> MemoryDateShown {
        MemoryDateShown { mode, from: from.map(str::to_owned), to: to.map(str::to_owned) }
    }

    /// A date is said as one day, a range, or "Since …"; without a day it is not said; a range ending on its start day is
    /// that day.
    #[test]
    fn a_memorys_date_is_said_as_a_day_a_range_or_since() {
        assert_eq!(date_text(&d(DateMode::Auto, None, None)), None);
        assert_eq!(date_text(&d(DateMode::Single, Some("2026-05-01"), None)), Some(said::day("2026-05-01")));
        assert_eq!(date_text(&d(DateMode::Range, Some("2026-05-01"), Some("2026-05-03"))), Some(format!("{} – {}", said::day("2026-05-01"), said::day("2026-05-03"))));
        assert_eq!(date_text(&d(DateMode::Auto, Some("2026-05-01"), Some("2026-05-01"))), Some(said::day("2026-05-01")));
        assert_eq!(date_text(&d(DateMode::Ongoing, Some("2026-05-01"), None)), Some(words::memory_date_since(&said::day("2026-05-01"))));
    }
}
