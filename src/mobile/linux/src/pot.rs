//! An open Pot, read only (ADR 0123's Consequences, the Linux app; ADR 0122 decision 4: Expenses' places are the open
//! Pot's Entries, Overview and Settings, as the web's Pot has them). The Pot is a model of its own in the core
//! (`Pot.Open`), watched while it is on screen and closed when it leaves; what each row says — its line, its badge, a
//! balance's words and tone, who pays whom — is the core's (`PotView.Shown`). This lays it out and writes money. Its
//! download (`Pot.Download`) goes where GTK's save dialog says, under the name the core gives (`Pot.ArchiveName`); the
//! core writes the file (ADR 0128 decision 3).
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;

use crate::bridge::{self, Watch, wire::Answer, wire::BarTab, wire::Call, wire::ObjectRef, wire::PotModelState,
    wire::PotView, wire::ShownTab, wire::Tone, wire::Update, wire::Watched};
use crate::home::glib_escape;
use crate::{avatars, said, words};

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
}

impl PotPage {
    pub fn open(r: &ObjectRef) -> Self {
        let inner = Rc::new(Inner {
            root: gtk::Box::new(gtk::Orientation::Vertical, 0),
            id: Cell::new(None),
            state: RefCell::new(None),
            tabs: RefCell::new((Vec::new(), None)),
            watch: RefCell::new(None),
        });
        inner.draw();
        let weak = Rc::downgrade(&inner);
        let open = r.clone();
        gtk::glib::spawn_future_local(async move {
            let Ok((Answer::PotOpened { pot }, _)) = bridge::call(&Call::PotOpen { r#ref: open }).await else {
                eprintln!("veyq: the Pot did not open in the core");
                return;
            };
            let Some(inner) = weak.upgrade() else {
                bridge::send(Call::PotClose { pot });
                return;
            };
            inner.id.set(Some(pot));
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
        gtk::glib::spawn_future_local(async move {
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
            if let Some(downloads) = gtk::glib::user_special_dir(gtk::glib::UserDirectory::Downloads).filter(|d| d.is_dir()) {
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
        self.inner.watch.borrow_mut().take();
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
                *inner.state.borrow_mut() = state;
                inner.draw();
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a Pot update did not read: {:?}", e.0),
        }
    }
}

impl Inner {
    fn draw(&self) {
        while let Some(child) = self.root.first_child() {
            self.root.remove(&child);
        }
        let state = self.state.borrow();
        let (tabs, selected) = &*self.tabs.borrow();
        let view = state.as_ref().and_then(|s| s.view.as_ref());
        match (state.as_ref(), view) {
            (Some(s), None) if s.failed => {
                self.root.append(&adw::StatusPage::builder().icon_name("dialog-warning-symbolic").title(words::pot_open_failed()).vexpand(true).build());
                return;
            }
            (_, None) => {
                let spinner = adw::Spinner::builder().width_request(32).height_request(32).build();
                let page = adw::StatusPage::builder().title(words::pot_loading()).child(&spinner).vexpand(true).build();
                self.root.append(&page);
                return;
            }
            _ => {}
        }
        let view = view.expect("matched above");
        if tabs.len() > 1 {
            self.root.append(&places(tabs, selected.as_ref()));
        }
        let (scroll, content) = column();
        if !view.missing.is_empty() {
            content.append(&missing(view));
        }
        if !view.ready {
            content.append(&note(&words::settings_missing_read_only()));
        } else {
            match selected.as_ref().map(|t| t.key.as_str()) {
                Some("expenses.overview") => overview(&content, view),
                Some("expenses.settings") => settings(&content, view),
                _ => entries(&content, view),
            }
        }
        self.root.append(&scroll);
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

fn column() -> (gtk::ScrolledWindow, gtk::Box) {
    let content = gtk::Box::new(gtk::Orientation::Vertical, 24);
    content.set_margin_top(12);
    content.set_margin_bottom(24);
    content.set_margin_start(12);
    content.set_margin_end(12);
    let clamp = adw::Clamp::builder().maximum_size(720).child(&content).build();
    let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build();
    (scroll, content)
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

fn entries(content: &gtk::Box, view: &PotView) {
    if view.entries.is_empty() {
        content.append(&note(&words::pot_no_entries_readonly()));
        return;
    }
    let group = adw::PreferencesGroup::new();
    for e in &view.entries {
        let title = e.row.title.clone().unwrap_or_else(words::pot_transfer);
        let row = adw::ActionRow::builder().title(glib_escape(&title)).subtitle(glib_escape(&said::line(&e.line))).build();
        if let Some(b) = &e.badge {
            row.add_suffix(&suffix(&said::notice(&b.notice), if b.danger { &["caption", "error"] } else { &["caption", "dim-label"] }));
        }
        if let Some(amount) = e.row.amount {
            row.add_suffix(&suffix(&money(view, amount), &["numeric"]));
        }
        group.add(&row);
    }
    content.append(&group);
}

fn overview(content: &gtk::Box, view: &PotView) {
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
        for t in &view.settle_up {
            let row = adw::ActionRow::builder().title(glib_escape(&said::notice(&t.pays))).build();
            row.add_suffix(&suffix(&money(view, t.row.amount), &["numeric"]));
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

fn settings(content: &gtk::Box, view: &PotView) {
    let currency = adw::PreferencesGroup::builder().title(words::pot_base_currency()).build();
    currency.add(&adw::ActionRow::builder().title(glib_escape(view.currency.as_deref().unwrap_or("—"))).build());
    content.append(&currency);
    let people = adw::PreferencesGroup::builder().title(words::pot_people()).build();
    for p in &view.people {
        let row = adw::ActionRow::builder().title(glib_escape(&p.entry.name)).subtitle(glib_escape(&said::line(&p.facts))).build();
        row.add_prefix(&avatars::avatar(&p.entry.name, p.entry.user_id.as_deref(), 32));
        people.add(&row);
    }
    if view.deleted_people > 0 {
        people.set_description(Some(&words::people_deleted_summary(i64::from(view.deleted_people))));
    }
    content.append(&people);
}
