//! A Pot's Settings place (the web's `SettingsPanel.vue` and `PeoplePanel.vue`; ADR 0123's Consequences, Expenses whole), GNOME's way:
//! editable in place, as `AdwPreferencesGroup`s of boxed lists. Every rule is the core's `PotSettings` (`PotSettings.Open`,
//! watched as `Watched.PotSettings`): what may be changed and done to whom arrives in `PotSettingsShown`, each change is a
//! call answered once its write ended (`Answer::Stored`: stored — the field is emptied —, or why not, as a toast).
//!
//! - the base currency an `AdwComboRow` with search while it may be chosen, else its code and, once money fixes it, why;
//! - the own categories, each renamed through [crate::header::ask_name], one added through an `AdwEntryRow` with apply;
//! - the people with their facts, each renamed, (de)activated by a flat button and erased by a destructive one, the erase
//!   asked by an `AdwAlertDialog` (the core's `asking`); a person added through an `AdwEntryRow`, a member of the space by
//!   their row's add button.
//!
//! The place is kept across the Pot page's redraws: the two entry rows keep what is typed while the rows around them are
//! drawn anew.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::PotSettingsShown, wire::Update, wire::Watched};
use crate::home::glib_escape;
use crate::{avatars, header, said, words};

/// The Settings place of Pot [pot]: its widgets, kept while the Pot page shows.
pub struct SettingsPlace {
    root: gtk::Box,
    inner: Rc<Inner>,
}

struct Inner {
    pot: i64,
    id: Cell<Option<i64>>,
    window: gtk::Window,
    toasts: adw::ToastOverlay,
    watch: RefCell<Option<Watch>>,
    note: gtk::Label,
    currency: adw::PreferencesGroup,
    currency_rows: gtk::ListBox,
    categories: adw::PreferencesGroup,
    category_rows: gtk::ListBox,
    add_category: adw::EntryRow,
    people: adw::PreferencesGroup,
    people_rows: gtk::ListBox,
    add_person: adw::EntryRow,
    members: adw::PreferencesGroup,
    member_rows: gtk::ListBox,
    /// Set while the rows are drawn from a state: a combo row's selection then is no choice.
    drawing: Cell<bool>,
    /// The erase asked, while its dialog is shown.
    asked: RefCell<Option<adw::AlertDialog>>,
}

fn boxed() -> gtk::ListBox {
    let rows = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    rows.add_css_class("boxed-list");
    rows
}

impl SettingsPlace {
    /// Opens Pot [pot]'s settings in the core and draws them as they arrive.
    pub fn open(pot: i64, window: &gtk::Window, toasts: &adw::ToastOverlay) -> Self {
        let root = gtk::Box::new(gtk::Orientation::Vertical, 24);
        let note = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
        note.add_css_class("dim-label");
        let group = |title: String, rows: &gtk::ListBox| {
            let g = adw::PreferencesGroup::builder().title(title).visible(false).build();
            g.add(rows);
            g
        };
        let (currency_rows, category_rows, people_rows, member_rows) = (boxed(), boxed(), boxed(), boxed());
        let add_category = adw::EntryRow::builder().title(words::settings_add_category()).show_apply_button(true).build();
        let add_person = adw::EntryRow::builder().title(words::people_add()).show_apply_button(true).build();
        category_rows.append(&add_category);
        people_rows.append(&add_person);
        let inner = Rc::new(Inner {
            pot,
            id: Cell::new(None),
            window: window.clone(),
            toasts: toasts.clone(),
            watch: RefCell::new(None),
            note: note.clone(),
            currency: group(words::pot_base_currency(), &currency_rows),
            currency_rows,
            categories: group(words::pot_own_categories(), &category_rows),
            category_rows,
            add_category: add_category.clone(),
            people: group(words::pot_people(), &people_rows),
            people_rows,
            add_person: add_person.clone(),
            members: group(words::people_add_member(), &member_rows),
            member_rows,
            drawing: Cell::new(false),
            asked: RefCell::new(None),
        });
        root.append(&note);
        root.append(&inner.currency);
        root.append(&inner.categories);
        root.append(&inner.people);
        root.append(&inner.members);
        let w = Rc::downgrade(&inner);
        add_category.connect_apply(move |row| {
            if let Some(i) = w.upgrade() {
                let name = row.text().to_string();
                i.change(Some(row.clone()), move |settings| Call::PotSettingsAddCategory { settings, name });
            }
        });
        let w = Rc::downgrade(&inner);
        add_person.connect_apply(move |row| {
            if let Some(i) = w.upgrade() {
                let name = row.text().to_string();
                i.change(Some(row.clone()), move |settings| Call::PotSettingsAddPerson { settings, name });
            }
        });
        let weak = Rc::downgrade(&inner);
        glib::spawn_future_local(async move {
            let settings = match bridge::call(&Call::PotSettingsOpen { pot }).await.map(|(a, _)| a) {
                Ok(Answer::PotSettingsOpened { settings }) => settings,
                other => {
                    eprintln!("veyq: PotSettings.Open answered {other:?}");
                    return;
                }
            };
            let Some(inner) = weak.upgrade() else {
                bridge::send(Call::PotSettingsClose { settings });
                return;
            };
            inner.id.set(Some(settings));
            eprintln!("veyq: pot settings open");
            let Some((watch, updates)) = bridge::watch(&Watched::PotSettings { settings }) else { return };
            *inner.watch.borrow_mut() = Some(watch);
            drop(inner);
            follow(weak, updates).await;
        });
        Self { root, inner }
    }

    /// The place's widget, moved into each redraw of the Pot page.
    pub fn widget(&self) -> &gtk::Box {
        &self.root
    }

    /// Whether these are Pot [pot]'s settings.
    pub fn of(&self, pot: i64) -> bool {
        self.inner.pot == pot
    }
}

impl Drop for SettingsPlace {
    fn drop(&mut self) {
        self.inner.watch.borrow_mut().take();
        if let Some(settings) = self.inner.id.get() {
            bridge::send(Call::PotSettingsClose { settings });
        }
    }
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::PotSettings { shown: Some(s), .. }) => inner.draw(&s),
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a settings update did not read: {:?}", e.0),
        }
    }
}

/// A flat button at a row's end: an icon with its words as tooltip and accessible name, or words alone.
fn row_button(icon: Option<&str>, text: &str, classes: &[&str]) -> gtk::Button {
    let b = match icon {
        Some(i) => {
            let b = gtk::Button::from_icon_name(i);
            b.set_tooltip_text(Some(text));
            b.update_property(&[gtk::accessible::Property::Label(text)]);
            b
        }
        None => gtk::Button::with_label(text),
    };
    b.set_valign(gtk::Align::Center);
    b.add_css_class("flat");
    for c in classes {
        b.add_css_class(c);
    }
    b
}

/// Every row of [rows] but [keep] removed.
fn clear(rows: &gtk::ListBox, keep: Option<&gtk::Widget>) {
    let mut child = rows.first_child();
    while let Some(c) = child {
        child = c.next_sibling();
        if Some(&c) != keep {
            rows.remove(&c);
        }
    }
}

impl Inner {
    fn draw(self: &Rc<Self>, s: &PotSettingsShown) {
        self.drawing.set(true);
        let root_sensitive = !s.saving;
        for g in [&self.currency, &self.categories, &self.people, &self.members] {
            g.set_sensitive(root_sensitive);
        }
        let note = if s.ready { None } else if s.can_change_settings { Some(words::settings_missing()) } else { Some(words::settings_missing_read_only()) };
        self.note.set_visible(note.is_some());
        self.note.set_label(&note.unwrap_or_default());

        // the base currency: chosen while no money fixes it
        clear(&self.currency_rows, None);
        if s.currency_editable {
            let at = s.currency.as_ref().and_then(|c| s.currencies.iter().position(|k| k == c)).unwrap_or(0) as u32;
            let labels: Vec<&str> = s.currencies.iter().map(String::as_str).collect();
            let row = adw::ComboRow::builder()
                .title(words::pot_base_currency())
                .enable_search(true)
                .expression(gtk::PropertyExpression::new(gtk::StringObject::static_type(), None::<gtk::Expression>, "string"))
                .model(&gtk::StringList::new(&labels))
                .selected(at)
                .build();
            let (w, codes) = (Rc::downgrade(self), s.currencies.clone());
            row.connect_selected_notify(move |r| {
                let Some(i) = w.upgrade() else { return };
                if i.drawing.get() {
                    return;
                }
                if let Some(code) = codes.get(r.selected() as usize).cloned() {
                    i.change(None, move |settings| Call::PotSettingsCurrency { settings, code });
                }
            });
            self.currency_rows.append(&row);
        } else {
            let row = adw::ActionRow::builder().title(glib_escape(s.currency.as_deref().unwrap_or("—"))).build();
            if s.currency_fixed {
                row.set_subtitle(&glib_escape(&words::settings_currency_fixed()));
            }
            self.currency_rows.append(&row);
        }
        self.currency.set_description(Some(&words::settings_currency_hint()));
        self.currency.set_visible(true);

        // the own categories
        clear(&self.category_rows, Some(self.add_category.upcast_ref()));
        for (n, c) in s.categories.iter().enumerate() {
            let label = said::piece(&c.label);
            let row = adw::ActionRow::builder().title(glib_escape(&label)).build();
            if s.can_change_settings {
                let rename = row_button(Some("document-edit-symbolic"), &words::settings_rename_category(&label), &[]);
                let (w, id) = (Rc::downgrade(self), c.id.clone());
                rename.connect_clicked(move |_| {
                    if let Some(i) = w.upgrade() {
                        i.rename_category(&id, &label);
                    }
                });
                row.add_suffix(&rename);
            }
            self.category_rows.insert(&row, n as i32);
        }
        self.add_category.set_visible(s.can_add_category);
        self.categories.set_description(s.can_change_settings.then(words::settings_categories_hint).as_deref());
        self.categories.set_visible(s.categories_shown);

        // the people
        clear(&self.people_rows, Some(self.add_person.upcast_ref()));
        for (n, p) in s.people.iter().enumerate() {
            let e = &p.entry;
            let row = adw::ActionRow::builder().title(glib_escape(&e.name)).subtitle(glib_escape(&said::line(&p.facts))).build();
            row.add_prefix(&avatars::avatar(&e.name, e.user_id.as_deref(), 32));
            if e.renamable {
                let rename = row_button(Some("document-edit-symbolic"), &words::people_rename(&e.name), &[]);
                let (w, id, name) = (Rc::downgrade(self), e.id.clone(), e.name.clone());
                rename.connect_clicked(move |_| {
                    if let Some(i) = w.upgrade() {
                        i.rename_person(&id, &name);
                    }
                });
                row.add_suffix(&rename);
            }
            if e.can_toggle {
                let toggle = row_button(None, &if e.active { words::people_deactivate() } else { words::people_activate() }, &[]);
                let (w, person) = (Rc::downgrade(self), e.id.clone());
                toggle.connect_clicked(move |_| {
                    if let Some(i) = w.upgrade() {
                        let person = person.clone();
                        i.change(None, move |settings| Call::PotSettingsToggle { settings, person });
                    }
                });
                row.add_suffix(&toggle);
            }
            if e.can_erase {
                let words = if e.named { words::people_delete(&e.name) } else { words::people_remove(&e.name) };
                let erase = row_button(Some("user-trash-symbolic"), &words, &["error"]);
                let (w, person) = (Rc::downgrade(self), e.id.clone());
                erase.connect_clicked(move |_| {
                    if let Some(settings) = w.upgrade().and_then(|i| i.id.get()) {
                        eprintln!("veyq: pot settings erase asked");
                        bridge::send(Call::PotSettingsAskErase { settings, person: person.clone() });
                    }
                });
                row.add_suffix(&erase);
            }
            self.people_rows.insert(&row, n as i32);
        }
        self.add_person.set_visible(s.can_write);
        let mut facts = Vec::new();
        if s.deleted_people > 0 {
            facts.push(words::people_deleted_summary(i64::from(s.deleted_people)));
        }
        if s.settle_first {
            facts.push(words::people_settle_first());
        }
        self.people.set_description((!facts.is_empty()).then(|| facts.join(" ")).as_deref());
        self.people.set_visible(true);

        // the space's members still to add
        clear(&self.member_rows, None);
        for m in &s.members {
            let row = adw::ActionRow::builder().title(glib_escape(&m.name)).build();
            row.add_prefix(&avatars::avatar(&m.name, Some(&m.user_id), 32));
            let add = row_button(Some("contact-new-symbolic"), &words::people_add_member(), &[]);
            let (w, user_id) = (Rc::downgrade(self), m.user_id.clone());
            add.connect_clicked(move |_| {
                if let Some(i) = w.upgrade() {
                    let user_id = user_id.clone();
                    i.change(None, move |settings| Call::PotSettingsAddMember { settings, user_id });
                }
            });
            row.add_suffix(&add);
            self.member_rows.append(&row);
        }
        self.members.set_description(Some(&words::people_choose_member()));
        self.members.set_visible(s.can_write && !s.members.is_empty());
        self.drawing.set(false);

        self.ask_erase(s);
    }

    /// The erase the core asks about, in an `AdwAlertDialog`: its answer erases, anything else cancels.
    fn ask_erase(self: &Rc<Self>, s: &PotSettingsShown) {
        let Some(asked) = &s.asking else {
            if let Some(d) = self.asked.borrow_mut().take() {
                d.force_close();
            }
            return;
        };
        if self.asked.borrow().is_some() {
            return;
        }
        let (heading, body, accept) = if asked.named {
            (words::people_delete(&asked.name), words::people_delete_confirm(), words::people_delete_accept())
        } else {
            (words::people_remove(&asked.name), words::people_remove_confirm(), words::people_remove_accept())
        };
        let ask = adw::AlertDialog::builder().heading(heading).body(body).build();
        ask.add_responses(&[("cancel", &words::entry_cancel()), ("erase", &accept)]);
        ask.set_response_appearance("erase", adw::ResponseAppearance::Destructive);
        ask.set_default_response(Some("cancel"));
        ask.set_close_response("cancel");
        let w = Rc::downgrade(self);
        ask.connect_response(None, move |_, response| {
            let Some(i) = w.upgrade() else { return };
            i.asked.borrow_mut().take();
            let Some(settings) = i.id.get() else { return };
            if response == "erase" {
                i.change(None, |settings| Call::PotSettingsErase { settings });
            } else {
                bridge::send(Call::PotSettingsCancelAsk { settings });
            }
        });
        *self.asked.borrow_mut() = Some(ask.clone());
        ask.present(Some(&self.window));
    }

    /// One change sent, answered once its write ended: stored empties [field]; a refusal is a toast.
    fn change(self: &Rc<Self>, field: Option<adw::EntryRow>, call: impl FnOnce(i64) -> Call) {
        let Some(settings) = self.id.get() else { return };
        let call = call(settings);
        let kind = serde_json::to_value(&call).ok().and_then(|v| v["type"].as_str().map(str::to_owned)).unwrap_or_default();
        let answer = bridge::call(&call);
        let toasts = self.toasts.clone();
        glib::spawn_future_local(async move {
            match answer.await.map(|(a, _)| a) {
                Ok(Answer::Stored { stored, notice }) => {
                    eprintln!("veyq: {kind}: stored {stored}{}", notice.as_ref().map(|n| format!(", {}", n.msg)).unwrap_or_default());
                    if stored && let Some(f) = field {
                        f.set_text("");
                    }
                    if let Some(n) = notice {
                        toasts.add_toast(adw::Toast::new(&said::notice(&n)));
                    }
                }
                other => eprintln!("veyq: {kind} answered {other:?}"),
            }
        });
    }

    /// A name asked for again where the core refused it; [call] makes the change of the name typed.
    fn rename(self: &Rc<Self>, heading: &str, field: &str, name: &str, call: impl Fn(i64, String) -> Call + Clone + 'static) {
        let (w, toasts) = (Rc::downgrade(self), self.toasts.clone());
        header::ask_name(self.window.upcast_ref(), heading, field, name, None, &words::entry_save(), move |typed, again| {
            let (call, toasts) = (call.clone(), toasts.clone());
            let Some(settings) = w.upgrade().and_then(|i| i.id.get()) else { return };
            let answer = bridge::call(&call(settings, typed.clone()));
            glib::spawn_future_local(async move {
                match answer.await.map(|(a, _)| a) {
                    Ok(Answer::Stored { stored: false, notice: Some(n) }) => {
                        eprintln!("veyq: rename refused: {}", n.msg);
                        again(typed, said::notice(&n));
                    }
                    Ok(Answer::Stored { stored, .. }) => eprintln!("veyq: rename stored {stored}"),
                    other => {
                        eprintln!("veyq: a rename answered {other:?}");
                        toasts.add_toast(adw::Toast::new(&words::people_failed()));
                    }
                }
            });
        });
    }

    fn rename_category(self: &Rc<Self>, id: &str, name: &str) {
        let category = id.to_owned();
        self.rename(&words::settings_rename_category(name), &words::settings_category_name(), name, move |settings, name| {
            Call::PotSettingsRenameCategory { settings, category: category.clone(), name }
        });
    }

    fn rename_person(self: &Rc<Self>, id: &str, name: &str) {
        let person = id.to_owned();
        self.rename(&words::people_rename(name), &words::people_name(), name, move |settings, name| {
            Call::PotSettingsRename { settings, person: person.clone(), name }
        });
    }
}
