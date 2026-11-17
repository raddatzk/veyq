//! A Memory's participants and a medium's capture time on Linux (ADR 0144 decisions 1–3; Windows' `Participants.cs` and
//! `CaptureTimeEdit.cs`), GNOME's way. The people line under the head, beside the facts line: one person as their avatar
//! and name, several as up to three overlapping avatars with "+N" in the third slot, a `GtkMenuButton` whose popover lists
//! everyone (a former member greyed; a current member's row opens their profile, `Shell.OpenPerson`), *Edit participants*
//! beside it where whoever looks may edit, the *Add participants* button while nobody is listed. The picker
//! (`Participants.Open`, `Watched.Participants`) is an `AdwDialog`: Cancel and Save in its header bar, a search entry, a
//! boxed list of the scope's members with a check each, the ids no member holds any more greyed above them. The capture
//! time's editor (`CaptureTime.Open`, `Watched.CaptureTime`) stands on the lightbox details' *Taken* row: *Edit capture
//! time* opens a dialog with a `GtkCalendar` and two spin buttons for the hour and the minute in the device's zone, which
//! `glib::DateTime` turns into epoch ms (the core does no zone arithmetic); *Reset to original* where a correction is set.
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::CaptureTimeShown, wire::CurationSaid, wire::FactsSave,
    wire::MemberShown, wire::ParticipantShown, wire::ParticipantsShown, wire::PeopleShown, wire::Update, wire::Watched};
use crate::home::glib_escape;
use crate::{avatars, words};

/// The avatar's size in the people line and the step between two overlapping ones.
const FACE: i32 = 24;
const STEP: i32 = 16;

/// A participant's name as shown: their display name, a former member's words, nothing while names read.
pub fn name_of(p: &ParticipantShown) -> String {
    match &p.name {
        Some(n) => n.clone(),
        None if p.former => words::memory_participants_former(),
        None => String::new(),
    }
}

/// What the people line draws in its slots: the avatars by user id, then "+N" where the core says more.
pub fn slots(people: &PeopleShown) -> (Vec<String>, Option<i64>) {
    (people.avatars.clone(), (people.more > 0).then_some(i64::from(people.more)))
}

/// The people line: built once, drawn from each gallery state.
pub struct PeopleLine {
    root: gtk::Box,
    button: gtk::MenuButton,
    faces: gtk::Fixed,
    name: gtk::Label,
    list: gtk::ListBox,
    popover: gtk::Popover,
    edit: gtk::Button,
    add: gtk::Button,
    gallery: Rc<Cell<Option<i64>>>,
    drawn: RefCell<Option<String>>,
}

impl PeopleLine {
    pub fn new(window: &gtk::Window) -> Self {
        let root = gtk::Box::builder().spacing(6).visible(false).build();
        root.set_widget_name("memory-people");
        let faces = gtk::Fixed::new();
        let name = gtk::Label::builder().ellipsize(gtk::pango::EllipsizeMode::End).build();
        let content = gtk::Box::builder().spacing(8).build();
        content.append(&faces);
        content.append(&name);
        let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).width_request(260).build();
        list.add_css_class("boxed-list");
        let heading = gtk::Label::builder().label(words::memory_participants_title()).xalign(0.0).build();
        heading.add_css_class("heading");
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).margin_top(6).margin_bottom(6).margin_start(6).margin_end(6).build();
        column.append(&heading);
        column.append(&gtk::ScrolledWindow::builder().child(&list).propagate_natural_height(true).max_content_height(360).hscrollbar_policy(gtk::PolicyType::Never).build());
        let popover = gtk::Popover::builder().child(&column).build();
        let button = gtk::MenuButton::builder().child(&content).popover(&popover).build();
        button.add_css_class("flat");
        button.set_widget_name("memory-people-list");
        let edit = gtk::Button::builder().icon_name("document-edit-symbolic").tooltip_text(words::memory_participants_edit()).visible(false).build();
        edit.add_css_class("flat");
        edit.set_widget_name("memory-people-edit");
        let add_content = adw::ButtonContent::builder().icon_name("contact-new-symbolic").label(words::memory_participants_add()).build();
        let add = gtk::Button::builder().child(&add_content).visible(false).build();
        add.add_css_class("flat");
        add.set_widget_name("memory-people-add");
        root.append(&button);
        root.append(&edit);
        root.append(&add);
        let gallery = Rc::new(Cell::new(None));
        for b in [&edit, &add] {
            let (g, w) = (gallery.clone(), window.clone());
            b.connect_clicked(move |_| {
                if let Some(gallery) = g.get() {
                    Picker::open(gallery, &w);
                }
            });
        }
        Self { root, button, faces, name, list, popover, edit, add, gallery, drawn: RefCell::new(None) }
    }

    pub fn widget(&self) -> &gtk::Box {
        &self.root
    }

    /// The line as gallery [gallery]'s state says it: hidden where the core shows none.
    pub fn draw(&self, people: Option<&PeopleShown>, gallery: i64) {
        self.gallery.set(Some(gallery));
        let Some(p) = people else {
            self.root.set_visible(false);
            return;
        };
        self.root.set_visible(true);
        self.button.set_visible(!p.people.is_empty());
        self.edit.set_visible(p.edits && !p.people.is_empty());
        self.add.set_visible(p.add);
        // drawn anew only when what it shows changed
        let key = format!("{:?}|{:?}|{}|{}", p.people.iter().map(|q| (&q.user_id, &q.name, q.former, q.opens_profile)).collect::<Vec<_>>(), p.avatars, p.more, p.named);
        if self.drawn.replace(Some(key.clone())).as_deref() == Some(key.as_str()) {
            return;
        }
        eprintln!("veyq: people line: {} listed, {} faces, more {}, edits {}, add {}", p.people.len(), p.avatars.len(), p.more, p.edits, p.add);
        while let Some(c) = self.faces.first_child() {
            self.faces.remove(&c);
        }
        let by_id: HashMap<&str, &ParticipantShown> = p.people.iter().map(|q| (q.user_id.as_str(), q)).collect();
        let (faces, more) = slots(p);
        let mut x = 0.0;
        for id in &faces {
            let name = by_id.get(id.as_str()).map(|q| name_of(q)).unwrap_or_default();
            let a = avatars::avatar(&name, Some(id), FACE);
            self.faces.put(&a, x, 0.0);
            x += f64::from(STEP);
        }
        if let Some(n) = more {
            let label = gtk::Label::builder().label(format!("+{n}")).width_request(FACE).height_request(FACE).build();
            label.add_css_class("caption-heading");
            label.add_css_class("card");
            self.faces.put(&label, x, 0.0);
        }
        let names: Vec<String> = p.people.iter().map(name_of).filter(|n| !n.is_empty()).collect();
        self.name.set_label(&if p.people.len() == 1 { names.first().cloned().unwrap_or_default() } else { String::new() });
        self.name.set_visible(p.people.len() == 1);
        let label = format!("{}: {}", words::memory_participants_title(), names.join(", "));
        self.button.set_tooltip_text(Some(&label));
        self.button.update_property(&[gtk::accessible::Property::Label(&label)]);
        // everyone, in the core's order
        while let Some(c) = self.list.first_child() {
            self.list.remove(&c);
        }
        for q in &p.people {
            let name = name_of(q);
            let row = adw::ActionRow::builder().title(glib_escape(&name)).activatable(q.opens_profile).build();
            row.add_prefix(&avatars::avatar(&name, Some(&q.user_id), 32));
            if q.former {
                row.add_css_class("dim-label");
            }
            if q.opens_profile {
                row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
                row.set_tooltip_text(Some(&words::people_open(&name)));
                let (id, popover) = (q.user_id.clone(), self.popover.downgrade());
                row.connect_activated(move |_| {
                    if let Some(p) = popover.upgrade() {
                        p.popdown();
                    }
                    eprintln!("veyq: participant's profile opened");
                    bridge::send(Call::ShellOpenPerson { user_id: id.clone() });
                });
            }
            self.list.append(&row);
        }
    }
}

// --- the participants picker -------------------------------------------------------------------------------------------

/// What the picker says for the core's [said] after a save that did not land.
fn picker_said(said: CurationSaid) -> String {
    match said {
        CurationSaid::Unreadable => words::memory_meta_unreadable(),
        CurationSaid::Conflict | CurationSaid::Failed => words::memory_participants_save_failed(),
    }
}

struct Picker {
    id: Cell<Option<i64>>,
    dialog: adw::Dialog,
    save: gtk::Button,
    search: gtk::SearchEntry,
    status: gtk::Label,
    retry: gtk::Button,
    spinner: adw::Spinner,
    list: gtk::ListBox,
    said: gtk::Label,
    /// The rows drawn (by id: its check), and their ids in order.
    checks: RefCell<HashMap<String, gtk::CheckButton>>,
    drawn: RefCell<Vec<String>>,
    drawing: Cell<bool>,
    watch: RefCell<Option<Watch>>,
}

impl Picker {
    fn open(gallery: i64, window: &gtk::Window) {
        let dialog = adw::Dialog::builder().title(words::memory_participants_dialog_title()).content_width(420).content_height(560).build();
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
        save.set_widget_name("participants-save");
        bar.pack_end(&save);
        let hint = gtk::Label::builder().label(words::memory_participants_dialog_hint()).wrap(true).xalign(0.0).build();
        hint.add_css_class("dim-label");
        let search = gtk::SearchEntry::builder().placeholder_text(words::memory_participants_search()).build();
        search.set_widget_name("participants-search");
        let status = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
        status.set_widget_name("participants-status");
        let retry = gtk::Button::builder().label(words::memory_people_retry()).halign(gtk::Align::Start).visible(false).build();
        let spinner = adw::Spinner::builder().width_request(24).height_request(24).halign(gtk::Align::Center).visible(false).build();
        let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).valign(gtk::Align::Start).build();
        list.add_css_class("boxed-list");
        list.set_widget_name("participants-list");
        let said = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
        said.add_css_class("error");
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).margin_top(12).margin_bottom(18).margin_start(12).margin_end(12).build();
        for w in [hint.upcast_ref::<gtk::Widget>(), search.upcast_ref(), spinner.upcast_ref(), status.upcast_ref(), retry.upcast_ref(), list.upcast_ref(), said.upcast_ref()] {
            column.append(w);
        }
        let view = adw::ToolbarView::new();
        view.add_top_bar(&bar);
        view.set_content(Some(&gtk::ScrolledWindow::builder().child(&column).vexpand(true).hscrollbar_policy(gtk::PolicyType::Never).build()));
        dialog.set_child(Some(&view));
        let this = Rc::new(Self {
            id: Cell::new(None),
            dialog,
            save,
            search,
            status,
            retry,
            spinner,
            list,
            said,
            checks: RefCell::new(HashMap::new()),
            drawn: RefCell::new(Vec::new()),
            drawing: Cell::new(false),
            watch: RefCell::new(None),
        });
        this.connect();
        let held = RefCell::new(Some(this.clone()));
        this.dialog.connect_closed(move |_| {
            if let Some(e) = held.borrow_mut().take() {
                e.watch.borrow_mut().take();
                if let Some(participants) = e.id.take() {
                    bridge::send(Call::ParticipantsClose { participants });
                }
            }
        });
        let weak = Rc::downgrade(&this);
        glib::spawn_future_local(async move {
            let participants = match bridge::call(&Call::ParticipantsOpen { gallery }).await.map(|(a, _)| a) {
                Ok(Answer::ParticipantsOpened { participants }) => participants,
                other => {
                    eprintln!("veyq: Participants.Open answered {other:?}");
                    if let Some(e) = weak.upgrade() {
                        e.dialog.force_close();
                    }
                    return;
                }
            };
            let Some(e) = weak.upgrade() else { return bridge::send(Call::ParticipantsClose { participants }) };
            e.id.set(Some(participants));
            eprintln!("veyq: participants picker open");
            let Some((watch, updates)) = bridge::watch(&Watched::Participants { participants }) else { return };
            *e.watch.borrow_mut() = Some(watch);
            drop(e);
            while let Ok(update) = updates.recv().await {
                let Some(e) = weak.upgrade() else { return };
                match update {
                    Ok(Update::Participants { shown: Some(s), .. }) => e.draw(&s),
                    Ok(Update::Participants { shown: None, .. }) => {
                        e.id.set(None);
                        e.dialog.force_close();
                        return;
                    }
                    Ok(_) => {}
                    Err(err) => eprintln!("veyq: a participants update did not read: {:?}", err.0),
                }
            }
        });
        this.dialog.present(Some(window));
        this.search.grab_focus();
    }

    fn connect(self: &Rc<Self>) {
        let weak = Rc::downgrade(self);
        self.search.connect_search_changed(move |entry| {
            if let Some(e) = weak.upgrade()
                && let Some(participants) = e.id.get()
            {
                bridge::send(Call::ParticipantsSearch { participants, query: entry.text().to_string() });
            }
        });
        let weak = Rc::downgrade(self);
        self.retry.connect_clicked(move |_| {
            if let Some(e) = weak.upgrade()
                && let Some(participants) = e.id.get()
            {
                bridge::send(Call::ParticipantsLoad { participants });
            }
        });
        let weak = Rc::downgrade(self);
        self.save.connect_clicked(move |_| {
            let Some(e) = weak.upgrade() else { return };
            let Some(participants) = e.id.get() else { return };
            e.save.set_sensitive(false);
            let weak = Rc::downgrade(&e);
            glib::spawn_future_local(async move {
                let answered = bridge::call(&Call::ParticipantsSave { participants }).await.map(|(a, _)| a);
                eprintln!("veyq: Participants.Save answered {answered:?}");
                if let (Ok(Answer::FactsSaved { outcome: FactsSave::Saved }), Some(e)) = (answered, weak.upgrade()) {
                    e.dialog.close();
                }
            });
        });
    }

    /// A row: the person's avatar and name, a check that ticks them (`Participants.Toggle`).
    fn row(self: &Rc<Self>, m: &MemberShown) -> adw::ActionRow {
        let name = m.name.clone().unwrap_or_else(words::memory_participants_former);
        let row = adw::ActionRow::builder().title(glib_escape(&name)).build();
        let check = gtk::CheckButton::builder().active(m.ticked).sensitive(m.togglable).valign(gtk::Align::Center).build();
        check.update_property(&[gtk::accessible::Property::Label(&name)]);
        row.add_prefix(&check);
        row.add_prefix(&avatars::avatar(&name, Some(&m.user_id), 32));
        row.set_activatable_widget(Some(&check));
        if m.name.is_none() {
            row.add_css_class("dim-label");
        }
        let (weak, id) = (Rc::downgrade(self), m.user_id.clone());
        check.connect_toggled(move |_| {
            let Some(e) = weak.upgrade() else { return };
            if e.drawing.get() {
                return;
            }
            let Some(participants) = e.id.get() else { return };
            eprintln!("veyq: participant toggled");
            let (weak, user_id) = (Rc::downgrade(&e), id.clone());
            glib::spawn_future_local(async move {
                let answered = bridge::call(&Call::ParticipantsToggle { participants, user_id }).await.map(|(a, _)| a);
                if !matches!(answered, Ok(Answer::Done)) {
                    eprintln!("veyq: Participants.Toggle answered {answered:?}");
                    // refused: the check goes back to what the core holds
                    if let Some(e) = weak.upgrade() {
                        e.drawn.borrow_mut().clear();
                    }
                }
            });
        });
        self.checks.borrow_mut().insert(m.user_id.clone(), check);
        row
    }

    fn draw(self: &Rc<Self>, s: &ParticipantsShown) {
        eprintln!("veyq: participants shown: {} members, {} former, failed {}, full {}, no matches {}, nobody {}, save {}, saving {}, said {:?}, saved {}",
            s.members.as_ref().map_or(-1, |m| m.len() as i64), s.former.len(), s.failed, s.full, s.no_matches, s.nobody, s.can_save, s.saving, s.said, s.saved);
        if s.saved {
            self.dialog.close();
            return;
        }
        self.drawing.set(true);
        self.save.set_sensitive(s.can_save && !s.saving);
        self.spinner.set_visible(s.members.is_none() && !s.failed);
        self.retry.set_visible(s.failed);
        let status = if s.failed {
            Some(words::memory_participants_load_error())
        } else if s.full {
            Some(words::memory_participants_full(100))
        } else if s.nobody {
            Some(words::memory_participants_no_members())
        } else if s.no_matches {
            Some(words::memory_participants_no_matches())
        } else {
            None
        };
        self.status.set_visible(status.is_some());
        self.status.set_label(&status.unwrap_or_default());
        self.said.set_visible(s.said.is_some());
        if let Some(said) = s.said {
            self.said.set_label(&picker_said(said));
        }
        let rows: Vec<&MemberShown> = s.former.iter().chain(s.members.iter().flatten()).collect();
        let key: Vec<String> = rows.iter().map(|m| m.user_id.clone()).collect();
        let same_rows = *self.drawn.borrow() == key;
        if same_rows {
            // the same people: only the ticks move, so the focus stays where it is
            let checks = self.checks.borrow();
            for m in &rows {
                if let Some(c) = checks.get(&m.user_id) {
                    c.set_active(m.ticked);
                    c.set_sensitive(m.togglable);
                }
            }
        } else {
            while let Some(c) = self.list.first_child() {
                self.list.remove(&c);
            }
            self.checks.borrow_mut().clear();
            for m in &rows {
                let row = self.row(m);
                self.list.append(&row);
            }
        }
        self.list.set_visible(!rows.is_empty());
        *self.drawn.borrow_mut() = key;
        self.drawing.set(false);
    }
}

// --- the capture time ------------------------------------------------------------------------------------------------

/// Epoch ms of the local [year]-[month]-[day] [hour]:[minute] in the device's zone (`glib::DateTime`), the minute whole.
pub fn local_epoch_ms(year: i32, month: i32, day: i32, hour: i32, minute: i32) -> Option<i64> {
    glib::DateTime::from_local(year, month, day, hour, minute, 0.0).ok().map(|d| d.to_unix() * 1000)
}

/// The local date and time of [epoch_ms] in the device's zone: (year, month, day, hour, minute).
pub fn local_of(epoch_ms: i64) -> Option<(i32, i32, i32, i32, i32)> {
    let d = glib::DateTime::from_unix_local(epoch_ms.div_euclid(1000)).ok()?;
    Some((d.year(), d.month(), d.day_of_month(), d.hour(), d.minute()))
}

/// What the editor says for the core's [said].
pub fn capture_said(said: CurationSaid) -> String {
    match said {
        CurationSaid::Conflict => words::media_captured_at_conflict(),
        CurationSaid::Unreadable => words::media_meta_unreadable(),
        CurationSaid::Failed => words::media_captured_at_save_failed(),
    }
}

/// Medium [media_id]'s capture-time editor for the details of lightbox [lightbox], where the core offers it: its buttons
/// on [row] (the *Taken* row), held until [dialog] (the details) closes. [saved] runs after a save or a reset landed
/// (the details show the time anew).
pub fn on_taken_row(lightbox: i64, media_id: String, row: &adw::ActionRow, dialog: &adw::Dialog, window: &gtk::Window, saved: impl Fn() + Clone + 'static) {
    let edit = gtk::Button::builder().icon_name("document-edit-symbolic").tooltip_text(words::media_edit_captured_at()).valign(gtk::Align::Center).visible(false).build();
    edit.add_css_class("flat");
    edit.set_widget_name("capture-edit");
    let reset = gtk::Button::builder().icon_name("edit-undo-symbolic").tooltip_text(words::media_reset_captured_at()).valign(gtk::Align::Center).visible(false).build();
    reset.add_css_class("flat");
    reset.set_widget_name("capture-reset");
    row.add_suffix(&reset);
    row.add_suffix(&edit);
    let state: Rc<RefCell<Option<CaptureTimeShown>>> = Rc::new(RefCell::new(None));
    let id: Rc<Cell<Option<i64>>> = Rc::new(Cell::new(None));
    let watch: Rc<RefCell<Option<Watch>>> = Rc::new(RefCell::new(None));
    {
        let (id, watch) = (id.clone(), watch.clone());
        dialog.connect_closed(move |_| {
            watch.borrow_mut().take();
            if let Some(capture_time) = id.take() {
                bridge::send(Call::CaptureTimeClose { capture_time });
            }
        });
    }
    {
        let (id, state, window, saved, dialog) = (id.clone(), state.clone(), window.clone(), saved.clone(), dialog.downgrade());
        edit.connect_clicked(move |_| {
            let Some(capture_time) = id.get() else { return };
            if state.borrow().is_none() {
                return;
            }
            let (saved, dialog) = (saved.clone(), dialog.clone());
            TimeDialog::open(capture_time, state.clone(), &window, move || {
                if let Some(d) = dialog.upgrade() {
                    d.close();
                }
                saved();
            });
        });
    }
    {
        let (id, saved, dialog) = (id.clone(), saved.clone(), dialog.downgrade());
        reset.connect_clicked(move |button| {
            let Some(capture_time) = id.get() else { return };
            button.set_sensitive(false);
            eprintln!("veyq: capture time reset pressed");
            let (saved, dialog) = (saved.clone(), dialog.clone());
            glib::spawn_future_local(async move {
                let answered = bridge::call(&Call::CaptureTimeReset { capture_time }).await.map(|(a, _)| a);
                eprintln!("veyq: CaptureTime.Reset answered {answered:?}");
                if matches!(answered, Ok(Answer::FactsSaved { outcome: FactsSave::Saved })) {
                    if let Some(d) = dialog.upgrade() {
                        d.close();
                    }
                    saved();
                }
            });
        });
    }
    let (edit, reset) = (edit.downgrade(), reset.downgrade());
    glib::spawn_future_local(async move {
        let capture_time = match bridge::call(&Call::CaptureTimeOpen { lightbox, media_id }).await.map(|(a, _)| a) {
            Ok(Answer::CaptureTimeOpened { capture_time }) => capture_time,
            other => return eprintln!("veyq: CaptureTime.Open answered {other:?}"),
        };
        if edit.upgrade().is_none() {
            return bridge::send(Call::CaptureTimeClose { capture_time });
        }
        id.set(Some(capture_time));
        let Some((w, updates)) = bridge::watch(&Watched::CaptureTime { capture_time }) else { return };
        *watch.borrow_mut() = Some(w);
        while let Ok(update) = updates.recv().await {
            match update {
                Ok(Update::CaptureTime { shown: Some(s), .. }) => {
                    eprintln!("veyq: capture time shown: at {:?}, corrected {}, original {:?}, saving {}, said {:?}", s.at_ms, s.corrected, s.original_ms, s.saving, s.said);
                    if let Some(e) = edit.upgrade() {
                        e.set_visible(true);
                    }
                    if let Some(r) = reset.upgrade() {
                        r.set_visible(s.corrected);
                        r.set_sensitive(!s.saving);
                    }
                    *state.borrow_mut() = Some(s);
                }
                Ok(Update::CaptureTime { shown: None, .. }) => {
                    id.set(None);
                    return;
                }
                Ok(_) => {}
                Err(err) => eprintln!("veyq: a capture time update did not read: {:?}", err.0),
            }
        }
    });
}

/// [at] (epoch ms) shown in the pickers as the device's local day, hour and minute.
fn show_time(calendar: &gtk::Calendar, hour: &gtk::SpinButton, minute: &gtk::SpinButton, at: Option<i64>) {
    if let Some((y, m, day, h, min)) = at.and_then(local_of) {
        if let Ok(d) = glib::DateTime::from_local(y, m, day, 0, 0, 0.0) {
            calendar.set_date(&d);
        }
        hour.set_value(f64::from(h));
        minute.set_value(f64::from(min));
    }
}

/// The editor's own dialog: a calendar for the day, the hour and the minute; Save sends the local time as epoch ms.
struct TimeDialog;

impl TimeDialog {
    fn open(capture_time: i64, state: Rc<RefCell<Option<CaptureTimeShown>>>, window: &gtk::Window, saved: impl Fn() + 'static) {
        let dialog = adw::Dialog::builder().title(words::media_edit_captured_at()).content_width(380).build();
        let bar = adw::HeaderBar::builder().show_end_title_buttons(false).show_start_title_buttons(false).build();
        let cancel = gtk::Button::builder().label(words::entry_cancel()).build();
        let d = dialog.downgrade();
        cancel.connect_clicked(move |_| {
            if let Some(d) = d.upgrade() {
                d.close();
            }
        });
        bar.pack_start(&cancel);
        let save = gtk::Button::builder().label(words::entry_save()).build();
        save.add_css_class("suggested-action");
        save.set_widget_name("capture-save");
        bar.pack_end(&save);
        let calendar = gtk::Calendar::builder().halign(gtk::Align::Center).build();
        calendar.set_widget_name("capture-day");
        let hour = gtk::SpinButton::with_range(0.0, 23.0, 1.0);
        let minute = gtk::SpinButton::with_range(0.0, 59.0, 1.0);
        for (spin, id) in [(&hour, "capture-hour"), (&minute, "capture-minute")] {
            spin.set_wrap(true);
            spin.set_valign(gtk::Align::Center);
            spin.set_widget_name(id);
        }
        hour.update_property(&[gtk::accessible::Property::Label(&words::memory_field_captured())]);
        let time = gtk::Box::builder().spacing(6).halign(gtk::Align::Center).build();
        time.append(&hour);
        time.append(&gtk::Label::new(Some(":")));
        time.append(&minute);
        show_time(&calendar, &hour, &minute, state.borrow().as_ref().and_then(|s| s.at_ms));
        let said = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
        said.add_css_class("error");
        said.set_widget_name("capture-said");
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).margin_top(12).margin_bottom(18).margin_start(12).margin_end(12).build();
        column.append(&calendar);
        column.append(&time);
        column.append(&said);
        let view = adw::ToolbarView::new();
        view.add_top_bar(&bar);
        view.set_content(Some(&column));
        dialog.set_child(Some(&view));
        let saved = Rc::new(saved);
        let (d, c, h, m, l) = (dialog.downgrade(), calendar.downgrade(), hour.downgrade(), minute.downgrade(), said.downgrade());
        save.connect_clicked(move |button| {
            let (Some(c), Some(h), Some(m)) = (c.upgrade(), h.upgrade(), m.upgrade()) else { return };
            let day = c.date();
            let Some(epoch_ms) = local_epoch_ms(day.year(), day.month(), day.day_of_month(), h.value_as_int(), m.value_as_int()) else { return };
            button.set_sensitive(false);
            eprintln!("veyq: capture time set pressed");
            let (d, l, h2, m2, c2, button, saved, state) = (d.clone(), l.clone(), h.downgrade(), m.downgrade(), c.downgrade(), button.downgrade(), saved.clone(), state.clone());
            glib::spawn_future_local(async move {
                let answered = bridge::call(&Call::CaptureTimeSet { capture_time, epoch_ms }).await.map(|(a, _)| a);
                eprintln!("veyq: CaptureTime.Set answered {answered:?}");
                if let Some(b) = button.upgrade() {
                    b.set_sensitive(true);
                }
                match answered {
                    Ok(Answer::FactsSaved { outcome: FactsSave::Saved }) => {
                        if let Some(d) = d.upgrade() {
                            d.close();
                        }
                        saved();
                    }
                    other => {
                        // a peer's correction meanwhile: shown now, with the web's words; else the failure's
                        let reopened = matches!(other, Ok(Answer::FactsSaved { outcome: FactsSave::Reopened }));
                        if let Some(l) = l.upgrade() {
                            l.set_label(&if reopened { capture_said(CurationSaid::Conflict) } else { capture_said(CurationSaid::Failed) });
                            l.set_visible(true);
                        }
                        if reopened
                            && let (Some(h), Some(m), Some(c)) = (h2.upgrade(), m2.upgrade(), c2.upgrade())
                        {
                            show_time(&c, &h, &m, state.borrow().as_ref().and_then(|s| s.at_ms));
                        }
                    }
                }
            });
        });
        dialog.present(Some(window));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn person(id: &str, name: Option<&str>, former: bool) -> ParticipantShown {
        ParticipantShown { user_id: id.into(), name: name.map(Into::into), former, opens_profile: !former }
    }

    /// A former member is said with the web's words, a name still reading with nothing; "+N" only where the core says more.
    #[test]
    fn the_people_line_names_a_former_member_and_puts_more_in_the_third_slot() {
        assert_eq!(name_of(&person("a", Some("Ada"), false)), "Ada");
        assert_eq!(name_of(&person("b", None, true)), words::memory_participants_former());
        assert_eq!(name_of(&person("c", None, false)), "");
        let people = PeopleShown { people: vec![person("a", Some("Ada"), false), person("b", Some("Bo"), false), person("c", Some("Cy"), false),
            person("d", Some("Di"), false)], avatars: vec!["a".into(), "b".into()], more: 2, named: true, edits: true, add: false };
        assert_eq!(slots(&people), (vec!["a".to_string(), "b".to_string()], Some(2)));
        let two = PeopleShown { more: 0, avatars: vec!["a".into(), "b".into()], ..people };
        assert_eq!(slots(&two).1, None);
    }

    /// The picked local day and time go to the core as epoch ms of the device's zone, and back to the same fields.
    #[test]
    fn a_local_time_round_trips_through_epoch_ms_in_the_devices_zone() {
        let _guard = crate::LOCALE.lock();
        let ms = local_epoch_ms(2022, 5, 3, 14, 7).expect("a local time");
        assert_eq!(ms % 60_000, 0, "the minute whole");
        assert_eq!(local_of(ms), Some((2022, 5, 3, 14, 7)));
        assert_eq!(local_of(ms + 59_999), Some((2022, 5, 3, 14, 7)));
    }
}
