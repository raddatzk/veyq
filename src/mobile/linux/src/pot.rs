//! An open Pot (ADR 0123's Consequences, the Linux app; ADR 0122 decision 4: Expenses' places are the open Pot's
//! Entries, Overview and Settings, as the web's Pot has them). The Pot is a model of its own in the core (`Pot.Open`),
//! watched while it is on screen and closed when it leaves; what each row says — its line, its badge, a balance's words
//! and tone, who pays whom — is the core's (`PotView.Shown`). This lays it out and writes money. Each place's scroll
//! starts with the node's head, its cover ([crate::header::Header::hero]).
//!
//! Its live poll (`Watched.PotLive`) runs while the page is mapped and its window is active, as the phones poll while
//! the Pot is shown (`STARTED`, `scenePhase`): the watch is dropped when either ends, which stops the poll. Refresh
//! (`Pot.Refresh`) is the header bar's button and F5 / Ctrl+R (`win.pot-refresh`, the shell's). A settle-up row is
//! recorded (`Pot.Record`, dated the core's today) after an `AdwAlertDialog` asked, one row or all; an entry opens the
//! entry editor ([crate::entry_editor], which holds its history), a deleted one its history and restore
//! ([crate::entry_history]); "Add entry" is the shell's header bar's creating action ([PotPage::add_entry]). Its download (`Pot.Download`) goes where GTK's save
//! dialog says, under the name the core gives (`Pot.ArchiveName`); the core writes the file (ADR 0128 decision 3).
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Answer, wire::BarTab, wire::Call, wire::ObjectRef, wire::PotModelState,
    wire::PotView, wire::SettleRow, wire::ShownEntry, wire::ShownTab, wire::Tone, wire::Update, wire::Watched};
use crate::home::glib_escape;
use crate::{avatars, said, words};

/// What a Pot page tells of each read: whether it takes writes.
type OnRead = Box<dyn Fn(bool)>;

/// The Pot on screen: opened in the core when made, closed when dropped.
pub struct PotPage {
    pub r#ref: ObjectRef,
    inner: Rc<Inner>,
}

struct Inner {
    root: gtk::Box,
    id: Cell<Option<i64>>,
    state: RefCell<Option<PotModelState>>,
    tabs: RefCell<(Vec<ShownTab>, Option<BarTab>)>,
    watch: RefCell<Option<Watch>>,
    /// The live poll's watch while the page is mapped and its window active; dropped, the poll stops.
    live: RefCell<Option<Watch>>,
    window: gtk::Window,
    /// The window's `is-active` handler, disconnected when the page goes.
    active: RefCell<Option<glib::SignalHandlerId>>,
    toasts: adw::ToastOverlay,
    /// The node's head (its cover), placed first in each place's scroll.
    hero: RefCell<Option<gtk::Widget>>,
    /// The places' scroll, kept across redraws (an update of the live poll keeps where it stood), and its column.
    scroll: gtk::ScrolledWindow,
    clamp: adw::Clamp,
    /// The place drawn last: another starts at its top.
    place: RefCell<Option<String>>,
    /// Told whether the Pot takes writes, each time it is read (its header's facts).
    read: RefCell<Option<OnRead>>,
    /// The Settings place, opened in the core when it is first shown and kept while the page is.
    settings: RefCell<Option<crate::pot_settings::SettingsPlace>>,
}

impl PotPage {
    /// Opens Pot [r] on a page inside [window], saying what its actions did in [toasts].
    pub fn open(r: &ObjectRef, window: &gtk::Window, toasts: &adw::ToastOverlay) -> Self {
        let inner = Rc::new(Inner {
            root: gtk::Box::new(gtk::Orientation::Vertical, 0),
            id: Cell::new(None),
            state: RefCell::new(None),
            tabs: RefCell::new((Vec::new(), None)),
            watch: RefCell::new(None),
            live: RefCell::new(None),
            window: window.clone(),
            active: RefCell::new(None),
            toasts: toasts.clone(),
            hero: RefCell::new(None),
            scroll: gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).visible(false).build(),
            clamp: adw::Clamp::builder().maximum_size(720).build(),
            place: RefCell::new(None),
            read: RefCell::new(None),
            settings: RefCell::new(None),
        });
        inner.scroll.set_child(Some(&inner.clamp));
        inner.root.append(&inner.scroll);
        inner.draw();
        // the live poll's lifetime: the page mapped and its window active
        let w = Rc::downgrade(&inner);
        inner.root.connect_map(move |_| {
            if let Some(i) = w.upgrade() {
                i.map_live();
            }
        });
        let w = Rc::downgrade(&inner);
        inner.root.connect_unmap(move |_| {
            if let Some(i) = w.upgrade() {
                i.map_live();
            }
        });
        let w = Rc::downgrade(&inner);
        *inner.active.borrow_mut() = Some(window.connect_is_active_notify(move |_| {
            if let Some(i) = w.upgrade() {
                i.map_live();
            }
        }));
        let weak = Rc::downgrade(&inner);
        let open = r.clone();
        glib::spawn_future_local(async move {
            let Ok((Answer::PotOpened { pot }, _)) = bridge::call(&Call::PotOpen { r#ref: open }).await else {
                eprintln!("veyq: the Pot did not open in the core");
                return;
            };
            let Some(inner) = weak.upgrade() else {
                bridge::send(Call::PotClose { pot });
                return;
            };
            inner.id.set(Some(pot));
            inner.map_live();
            let Some((watch, updates)) = bridge::watch(&Watched::Pot { pot }) else { return };
            *inner.watch.borrow_mut() = Some(watch);
            drop(inner);
            follow(weak, updates).await;
        });
        Self { r#ref: r.clone(), inner }
    }

    /// Downloads this Pot as the web's ZIP: GTK's save dialog (the file chooser portal where there is one) suggests the
    /// core's name, the core writes the file the dialog chose, and a toast in [toasts] says how it went.
    pub fn download(&self, parent: &gtk::Window, toasts: &adw::ToastOverlay) {
        let Some(pot) = self.inner.id.get() else { return };
        let (name, parent, toasts) = (self.r#ref.name.clone(), parent.clone(), toasts.clone());
        glib::spawn_future_local(async move {
            let failed = || adw::Toast::new(&words::download_failed(&name));
            let suggested = match bridge::call(&Call::PotArchiveName { pot }).await.map(|(answer, _)| answer) {
                Ok(Answer::Name { name }) => name,
                other => {
                    eprintln!("veyq: Pot.ArchiveName answered {other:?}");
                    toasts.add_toast(failed());
                    return;
                }
            };
            let dialog = gtk::FileDialog::builder().title(words::node_download()).initial_name(suggested).modal(true).build();
            if let Some(downloads) = glib::user_special_dir(glib::UserDirectory::Downloads).filter(|d| d.is_dir()) {
                dialog.set_initial_folder(Some(&gtk::gio::File::for_path(downloads)));
            }
            let file = match dialog.save_future(Some(&parent)).await {
                Ok(file) => file,
                Err(e) if e.matches(gtk::DialogError::Dismissed) || e.matches(gtk::DialogError::Cancelled) => return,
                Err(e) => {
                    eprintln!("veyq: the save dialog failed: {e}");
                    toasts.add_toast(failed());
                    return;
                }
            };
            // the core opens the path itself; one that is no UTF-8 does not cross the wire
            let Some(path) = file.path().and_then(|p| p.to_str().map(str::to_owned)) else {
                eprintln!("veyq: the save dialog chose no local UTF-8 path");
                toasts.add_toast(failed());
                return;
            };
            let call = Call::PotDownload { pot, path, deleted_name: words::pot_deleted_person() };
            match bridge::call(&call).await.map(|(answer, _)| answer) {
                Ok(Answer::Done) => toasts.add_toast(adw::Toast::new(&words::download_done(&name))),
                other => {
                    eprintln!("veyq: Pot.Download answered {other:?}");
                    toasts.add_toast(failed());
                }
            }
        });
    }

    /// The entry editor of a new entry (the header bar's "Add entry").
    pub fn add_entry(&self) {
        let Some(pot) = self.inner.id.get() else { return };
        crate::entry_editor::open(&self.inner.window, pot, None, &self.inner.toasts);
    }

    /// Reads the Pot again now (Refresh, F5 / Ctrl+R); what changed arrives on its watch.
    pub fn refresh(&self) {
        let Some(pot) = self.inner.id.get() else { return };
        eprintln!("veyq: pot refresh");
        bridge::send(Call::PotRefresh { pot });
    }

    /// The node's head ([crate::header::Header::hero]), placed first in each place's scroll; another header's replaces it.
    pub fn hero(&self, hero: Option<gtk::Widget>) {
        if *self.inner.hero.borrow() == hero {
            return;
        }
        *self.inner.hero.borrow_mut() = hero;
        self.inner.draw();
    }

    /// [f] is told whether the Pot takes writes, each time its view is read.
    pub fn on_read(&self, f: impl Fn(bool) + 'static) {
        *self.inner.read.borrow_mut() = Some(Box::new(f));
    }

    pub fn widget(&self) -> &gtk::Box {
        &self.inner.root
    }

    /// The places the shell offers for this Pot and the one selected (`Shell.barTabs`, `selectedTab`).
    pub fn places(&self, tabs: &[ShownTab], selected: Option<&BarTab>) {
        let next = (tabs.to_vec(), selected.cloned());
        if *self.inner.tabs.borrow() != next {
            *self.inner.tabs.borrow_mut() = next;
            self.inner.draw();
        }
    }
}

impl Drop for PotPage {
    fn drop(&mut self) {
        self.inner.live.borrow_mut().take();
        if let Some(h) = self.inner.active.borrow_mut().take() {
            self.inner.window.disconnect(h);
        }
        self.inner.watch.borrow_mut().take();
        self.inner.settings.borrow_mut().take();
        if let Some(pot) = self.inner.id.get() {
            bridge::send(Call::PotClose { pot });
        }
    }
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Pot { state, .. }) => {
                if let (Some(view), Some(read)) = (state.as_ref().and_then(|s| s.view.as_ref()), inner.read.borrow().as_ref()) {
                    read(view.can_write);
                }
                *inner.state.borrow_mut() = state;
                inner.draw();
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a Pot update did not read: {:?}", e.0),
        }
    }
}

impl Inner {
    /// The live poll watched while the page is mapped and its window active, dropped (so stopped) when either ends.
    fn map_live(self: &Rc<Self>) {
        let wanted = self.root.is_mapped() && self.window.is_active();
        let Some(pot) = self.id.get() else { return };
        let running = self.live.borrow().is_some();
        if wanted == running {
            return;
        }
        eprintln!("veyq: pot live poll {}", if wanted { "on" } else { "off" });
        *self.live.borrow_mut() = if wanted { bridge::watch(&Watched::PotLive { pot }).map(|(watch, _)| watch) } else { None };
    }

    fn draw(self: &Rc<Self>) {
        if let Some(hero) = self.hero.borrow().as_ref()
            && let Some(parent) = hero.parent().and_downcast::<gtk::Box>()
        {
            parent.remove(hero);
        }
        // the scroll stays across redraws (an update of the live poll), so where it stood stays; the rest is drawn anew
        let mut child = self.root.first_child();
        while let Some(c) = child {
            child = c.next_sibling();
            if c != self.scroll {
                self.root.remove(&c);
            }
        }
        if let Some(place) = self.settings.borrow().as_ref()
            && let Some(parent) = place.widget().parent().and_downcast::<gtk::Box>()
        {
            parent.remove(place.widget());
        }
        let state = self.state.borrow();
        let (tabs, selected) = &*self.tabs.borrow();
        let view = state.as_ref().and_then(|s| s.view.as_ref());
        match (state.as_ref(), view) {
            (Some(s), None) if s.failed => {
                self.scroll.set_visible(false);
                self.root.append(&adw::StatusPage::builder().icon_name("dialog-warning-symbolic").title(words::pot_open_failed()).vexpand(true).build());
                return;
            }
            (_, None) => {
                let spinner = adw::Spinner::builder().width_request(32).height_request(32).build();
                let page = adw::StatusPage::builder().title(words::pot_loading()).child(&spinner).vexpand(true).build();
                self.scroll.set_visible(false);
                self.root.append(&page);
                return;
            }
            _ => {}
        }
        let view = view.expect("matched above");
        if tabs.len() > 1 {
            self.root.prepend(&places(tabs, selected.as_ref()));
        }
        // another place starts at its top
        let place = selected.as_ref().map(|t| t.key.clone());
        if *self.place.borrow() != place {
            *self.place.borrow_mut() = place;
            self.scroll.vadjustment().set_value(0.0);
        }
        let content = column();
        if let Some(hero) = self.hero.borrow().as_ref() {
            content.append(hero);
        }
        if !view.missing.is_empty() {
            content.append(&missing(view));
        }
        match selected.as_ref().map(|t| t.key.as_str()) {
            // the settings choose a base currency the Pot does not have yet
            Some("expenses.settings") => self.settings(&content),
            _ if !view.ready => content.append(&note(&words::settings_missing_read_only())),
            Some("expenses.overview") => self.overview(&content, view),
            _ => self.entries(&content, view),
        }
        self.clamp.set_child(Some(&content));
        self.scroll.set_visible(true);
    }

    fn entries(self: &Rc<Self>, content: &gtk::Box, view: &PotView) {
        if view.entries.is_empty() {
            content.append(&note(&words::pot_no_entries_readonly()));
        } else {
            let group = adw::PreferencesGroup::new();
            for e in &view.entries {
                group.add(&self.entry_row(view, e, false));
            }
            content.append(&group);
        }
        if !view.deleted.is_empty() {
            let deleted = adw::PreferencesGroup::builder().title(words::entries_deleted_title()).build();
            for e in &view.deleted {
                deleted.add(&self.entry_row(view, e, true));
            }
            content.append(&deleted);
        }
    }

    /// An entry's row, opened to the editor (a deleted one's to its history and restore).
    fn entry_row(self: &Rc<Self>, view: &PotView, e: &ShownEntry, deleted: bool) -> adw::ActionRow {
        let title = e.row.title.clone().unwrap_or_else(words::pot_transfer);
        let row = adw::ActionRow::builder().title(glib_escape(&title)).subtitle(glib_escape(&said::line(&e.line))).activatable(true).build();
        if deleted {
            row.add_suffix(&suffix(&words::entries_deleted_badge(), &["caption", "dim-label"]));
        } else if let Some(b) = &e.badge {
            row.add_suffix(&suffix(&said::notice(&b.notice), if b.danger { &["caption", "error"] } else { &["caption", "dim-label"] }));
        }
        if let Some(amount) = e.row.amount {
            row.add_suffix(&suffix(&money(view, amount), &["numeric"]));
        }
        row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
        let (w, id, amount) = (Rc::downgrade(self), e.row.id.clone(), e.row.amount.map(|a| money(view, a)));
        row.connect_activated(move |_| {
            let Some(i) = w.upgrade() else { return };
            let Some(pot) = i.id.get() else { return };
            if deleted {
                crate::entry_history::open(&i.window, pot, &id, &title, amount.as_deref(), &i.toasts);
            } else {
                crate::entry_editor::open(&i.window, pot, Some(&id), &i.toasts);
            }
        });
        row
    }

    fn overview(self: &Rc<Self>, content: &gtk::Box, view: &PotView) {
        let balances = adw::PreferencesGroup::builder().title(words::pot_balances()).build();
        for b in &view.balances {
            let name = said::piece(&b.name);
            let row = adw::ActionRow::builder().title(glib_escape(&name)).subtitle(glib_escape(&said::line(&b.facts))).build();
            row.add_prefix(&avatars::avatar(&name, b.row.user_id.as_deref(), 32));
            let tone = match b.balance.tone {
                Tone::Owes => "error",
                Tone::Owed => "success",
                Tone::Settled => "dim-label",
            };
            row.add_suffix(&suffix(&said::notice(&b.balance.notice), &[tone]));
            balances.add(&row);
        }
        content.append(&balances);
        if !view.settle_up.is_empty() {
            let settle = adw::PreferencesGroup::builder().title(words::pot_settle_up()).build();
            if view.can_record_all {
                let all = gtk::Button::builder().label(words::balances_record_all()).valign(gtk::Align::Center).build();
                all.add_css_class("flat");
                let (w, rows) = (Rc::downgrade(self), view.settle_up.iter().map(|t| t.row.clone()).collect::<Vec<_>>());
                all.connect_clicked(move |_| if let Some(i) = w.upgrade() {
                    let n = rows.len() as i64;
                    i.ask_record(rows.clone(), &words::balances_record_all_question(), &words::balances_record_all_hint(n));
                });
                settle.set_header_suffix(Some(&all));
            }
            for t in &view.settle_up {
                let pays = said::notice(&t.pays);
                let amount = money(view, t.row.amount);
                let row = adw::ActionRow::builder().title(glib_escape(&pays)).build();
                row.add_suffix(&suffix(&amount, &["numeric"]));
                if view.can_write {
                    let record = gtk::Button::builder().label(words::balances_record()).valign(gtk::Align::Center).build();
                    let (w, one) = (Rc::downgrade(self), t.row.clone());
                    let body = [pays.clone(), amount.clone()].join(" · ");
                    record.connect_clicked(move |_| if let Some(i) = w.upgrade() {
                        i.ask_record(vec![one.clone()], &words::balances_record_question(), &[body.clone(), words::balances_record_hint()].join("\n\n"));
                    });
                    row.add_suffix(&record);
                }
                settle.add(&row);
            }
            content.append(&settle);
        }
        if !view.by_category.is_empty() {
            let categories = adw::PreferencesGroup::builder().title(words::pot_by_category()).build();
            for c in &view.by_category {
                let row = adw::ActionRow::builder().title(glib_escape(&said::piece(&c.label))).build();
                row.add_suffix(&suffix(&money(view, c.row.amount), &["numeric"]));
                categories.add(&row);
            }
            let total = adw::ActionRow::builder().title(words::pot_total()).build();
            total.add_suffix(&suffix(&money(view, view.total), &["numeric", "heading"]));
            categories.add(&total);
            content.append(&categories);
        }
    }

    /// The Settings place ([crate::pot_settings]): opened in the core the first time it shows, kept across redraws.
    fn settings(self: &Rc<Self>, content: &gtk::Box) {
        let Some(pot) = self.id.get() else { return };
        let mut held = self.settings.borrow_mut();
        if !held.as_ref().is_some_and(|p| p.of(pot)) {
            *held = Some(crate::pot_settings::SettingsPlace::open(pot, &self.window, &self.toasts));
        }
        if let Some(place) = held.as_ref() {
            content.append(place.widget());
        }
    }

    /// Asks before settle-up [rows] are recorded (one, or all): [heading] and [body] in an `AdwAlertDialog`, then
    /// `Pot.Record`; a refusal is said at once from the answer, the settled rows arrive on the Pot's watch.
    fn ask_record(self: &Rc<Self>, rows: Vec<SettleRow>, heading: &str, body: &str) {
        let Some(pot) = self.id.get() else { return };
        let ask = adw::AlertDialog::builder().heading(heading).body(body).build();
        ask.add_responses(&[("cancel", &words::entry_cancel()), ("record", &words::balances_record())]);
        ask.set_response_appearance("record", adw::ResponseAppearance::Suggested);
        ask.set_default_response(Some("record"));
        ask.set_close_response("cancel");
        let toasts = self.toasts.clone();
        ask.connect_response(None, move |_, response| {
            if response != "record" {
                return;
            }
            let (toasts, count, call) = (toasts.clone(), rows.len(), Call::PotRecord { pot, rows: rows.clone() });
            glib::spawn_future_local(async move {
                let answered = bridge::call(&call).await.map(|(a, _)| a);
                eprintln!("veyq: pot record {count} row(s): {answered:?}");
                crate::header::say(&toasts, answered, "Pot.Record");
            });
        });
        ask.present(Some(&self.window));
    }
}

/// The Pot's places as a toggle group above its body (GNOME's inline view switcher); a choice is the shell's.
fn places(tabs: &[ShownTab], selected: Option<&BarTab>) -> gtk::Widget {
    let group = adw::ToggleGroup::builder().halign(gtk::Align::Center).margin_top(12).margin_bottom(6).build();
    for t in tabs {
        group.add(adw::Toggle::builder().label(said::key(&t.word)).name(t.tab.key.clone()).build());
    }
    if let Some(s) = selected {
        group.set_active_name(Some(&s.key));
    }
    group.connect_active_name_notify(|g| {
        if let Some(key) = g.active_name() {
            bridge::send(Call::ShellSelect { tab: BarTab { key: key.into() } });
        }
    });
    group.upcast()
}

fn column() -> gtk::Box {
    let content = gtk::Box::new(gtk::Orientation::Vertical, 24);
    content.set_margin_top(12);
    content.set_margin_bottom(24);
    content.set_margin_start(12);
    content.set_margin_end(12);
    content
}

fn note(text: &str) -> gtk::Label {
    let label = gtk::Label::builder().label(text).wrap(true).xalign(0.0).build();
    label.add_css_class("dim-label");
    label
}

/// A label at a row's end: an amount, a balance, a badge.
fn suffix(text: &str, classes: &[&str]) -> gtk::Label {
    let label = gtk::Label::builder().label(text).build();
    for c in classes {
        label.add_css_class(c);
    }
    label
}

fn money(view: &PotView, amount: i64) -> String {
    said::money(amount, view.currency.as_deref(), view.minor_units)
}

/// The ledger check's notice (ADR 0108 decision 5): what the read found missing, so the balances may be wrong.
fn missing(view: &PotView) -> adw::PreferencesGroup {
    let group = adw::PreferencesGroup::builder().description(words::pot_ledger_missing()).build();
    group.add_css_class("error");
    for m in &view.missing {
        group.add(&adw::ActionRow::builder().title(glib_escape(&said::piece(m))).build());
    }
    group
}

