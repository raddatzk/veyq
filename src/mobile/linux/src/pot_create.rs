//! "New pot" from New (the web's `PotCreateDialog.vue`; D5 of the Expenses CR), GNOME's way: an `AdwDialog` with Cancel
//! and Create (the suggested action) in its header bar, the name an `AdwEntryRow`, the space and the base currency
//! `AdwComboRow`s (the currency with search; the space only where there is more than one), the people a boxed list with
//! a remove button each, a name typed added through an `AdwEntryRow` with apply, the space's members by their row's add
//! button. Every rule is the core's `PotCreateSheet` (`PotCreate.Open`, watched as `Watched.PotCreate`): its defaults, its
//! members read again for another space, whether Create is offered for the name typed (`PotCreate.Name`, sent as typed,
//! never echoed), where the Pot lands. A create answers what to do at once (`Answer::Stored`): stored closes the dialog
//! — the shell opens the new Pot —, else the core's refusal is a toast in the dialog.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::PotCreateShown, wire::Update, wire::Watched};
use crate::home::glib_escape;
use crate::{avatars, said, words};

/// Opens New pot over [parent]; [said] takes what a create that landed still says once the dialog closed.
pub fn open(parent: &gtk::Window, said: &adw::ToastOverlay) {
    let (parent, said) = (parent.clone(), said.clone());
    // the language the words speak, as gettext reads it: the core starts the Pot in its currency
    let language = glib::language_names().first().map(|l| l.to_string()).unwrap_or_default();
    glib::spawn_future_local(async move {
        let create = match bridge::call(&Call::PotCreateOpen { language }).await.map(|(a, _)| a) {
            Ok(Answer::PotCreateOpened { create }) => create,
            other => {
                eprintln!("veyq: PotCreate.Open answered {other:?}");
                said.add_toast(adw::Toast::new(&words::create_pot_failed()));
                return;
            }
        };
        eprintln!("veyq: new pot open");
        Inner::show(&parent, create, &said);
    });
}

struct Inner {
    create: i64,
    dialog: adw::Dialog,
    toasts: adw::ToastOverlay,
    said: adw::ToastOverlay,
    create_button: gtk::Button,
    form: adw::PreferencesGroup,
    space: RefCell<Option<(adw::ComboRow, Vec<String>)>>,
    currency: RefCell<Option<(adw::ComboRow, Vec<String>)>>,
    people_rows: gtk::ListBox,
    add_person: adw::EntryRow,
    members: adw::PreferencesGroup,
    member_rows: gtk::ListBox,
    drawing: Cell<bool>,
    watch: RefCell<Option<Watch>>,
}

fn boxed() -> gtk::ListBox {
    let rows = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    rows.add_css_class("boxed-list");
    rows
}

impl Inner {
    fn show(parent: &gtk::Window, create: i64, said: &adw::ToastOverlay) {
        let header = adw::HeaderBar::new();
        header.set_show_start_title_buttons(false);
        header.set_show_end_title_buttons(false);
        let cancel = gtk::Button::builder().label(words::entry_cancel()).build();
        let create_button = gtk::Button::builder().label(words::create_pot_create()).sensitive(false).build();
        create_button.add_css_class("suggested-action");
        header.pack_start(&cancel);
        header.pack_end(&create_button);
        let column = gtk::Box::new(gtk::Orientation::Vertical, 24);
        column.set_margin_top(12);
        column.set_margin_bottom(24);
        column.set_margin_start(12);
        column.set_margin_end(12);
        let form = adw::PreferencesGroup::new();
        let name = adw::EntryRow::builder().title(words::create_pot_name()).build();
        form.add(&name);
        column.append(&form);
        let people = adw::PreferencesGroup::builder().title(words::pot_people()).build();
        let people_rows = boxed();
        let add_person = adw::EntryRow::builder().title(words::people_add()).show_apply_button(true).build();
        people_rows.append(&add_person);
        people.add(&people_rows);
        column.append(&people);
        let members = adw::PreferencesGroup::builder().title(words::people_add_member()).description(words::people_choose_member()).visible(false).build();
        let member_rows = boxed();
        members.add(&member_rows);
        column.append(&members);
        let holder = adw::Clamp::builder().maximum_size(560).child(&column).build();
        let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&holder).build();
        let view = adw::ToolbarView::new();
        view.add_top_bar(&header);
        view.set_content(Some(&scroll));
        let toasts = adw::ToastOverlay::new();
        toasts.set_child(Some(&view));
        let dialog = adw::Dialog::builder().child(&toasts).content_width(520).content_height(640).title(words::create_pot()).build();
        let inner = Rc::new(Inner {
            create,
            dialog: dialog.clone(),
            toasts,
            said: said.clone(),
            create_button: create_button.clone(),
            form,
            space: RefCell::new(None),
            currency: RefCell::new(None),
            people_rows,
            add_person: add_person.clone(),
            members,
            member_rows,
            drawing: Cell::new(false),
            watch: RefCell::new(None),
        });
        let d = dialog.clone();
        cancel.connect_clicked(move |_| {
            d.close();
        });
        // the name as typed: the core judges Create on it, nothing comes back into the field
        name.connect_changed(move |e| bridge::send(Call::PotCreateName { create, name: e.text().to_string() }));
        let w = Rc::downgrade(&inner);
        name.connect_entry_activated(move |_| {
            if let Some(i) = w.upgrade()
                && i.create_button.is_sensitive()
            {
                i.submit();
            }
        });
        let w = Rc::downgrade(&inner);
        add_person.connect_apply(move |row| {
            let Some(i) = w.upgrade() else { return };
            let (row, answer) = (row.clone(), bridge::call(&Call::PotCreateAddTyped { create: i.create, name: row.text().to_string() }));
            glib::spawn_future_local(async move {
                if let Ok((Answer::Stored { stored: true, .. }, _)) = answer.await {
                    row.set_text("");
                }
            });
        });
        let w = Rc::downgrade(&inner);
        create_button.connect_clicked(move |_| {
            if let Some(i) = w.upgrade() {
                i.submit();
            }
        });
        let held = RefCell::new(Some(inner.clone()));
        dialog.connect_closed(move |_| {
            if let Some(i) = held.borrow_mut().take() {
                i.watch.borrow_mut().take();
                bridge::send(Call::PotCreateClose { create: i.create });
                eprintln!("veyq: new pot closed");
            }
        });
        dialog.present(Some(parent));
        name.grab_focus();
        let Some((watch, updates)) = bridge::watch(&Watched::PotCreate { create }) else { return };
        *inner.watch.borrow_mut() = Some(watch);
        glib::spawn_future_local(follow(Rc::downgrade(&inner), updates));
    }

    /// Create: the name sent last, a name still typed in the people's field taken first.
    fn submit(self: &Rc<Self>) {
        let call = Call::PotCreateCreate { create: self.create, typed: self.add_person.text().to_string() };
        self.create_button.set_sensitive(false);
        let answer = bridge::call(&call);
        let w = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let answered = answer.await.map(|(a, _)| a);
            let Some(i) = w.upgrade() else { return };
            match answered {
                Ok(Answer::Stored { stored: true, notice }) => {
                    eprintln!("veyq: new pot created{}", notice.as_ref().map(|n| format!(", {}", n.msg)).unwrap_or_default());
                    if let Some(n) = notice {
                        i.said.add_toast(adw::Toast::new(&said::notice(&n)));
                    }
                    i.dialog.close();
                }
                Ok(Answer::Stored { stored: false, notice }) => {
                    eprintln!("veyq: new pot refused: {}", notice.as_ref().map(|n| n.msg.to_string()).unwrap_or_default());
                    if let Some(n) = notice {
                        i.toasts.add_toast(adw::Toast::new(&said::notice(&n)));
                    }
                }
                other => {
                    eprintln!("veyq: PotCreate.Create answered {other:?}");
                    i.toasts.add_toast(adw::Toast::new(&words::create_pot_failed()));
                }
            }
        });
    }

    fn draw(self: &Rc<Self>, s: &PotCreateShown) {
        self.drawing.set(true);
        // the space (only where there is a choice) and the currency: built once, chosen from the state after
        if self.space.borrow().is_none() && s.spaces.len() > 1 {
            let labels: Vec<String> = s.spaces.iter().map(|sp| said::piece(&sp.name)).collect();
            let ids: Vec<String> = s.spaces.iter().map(|sp| sp.space.scope_id.clone()).collect();
            let row = self.combo(&words::create_pot_space(), &labels, &ids, s.space.as_deref(), false, |create, scope_id| Call::PotCreateSpace { create, scope_id });
            self.form.add(&row);
            *self.space.borrow_mut() = Some((row, ids));
        }
        if self.currency.borrow().is_none() {
            let row = self.combo(&words::pot_base_currency(), &s.currencies, &s.currencies, Some(&s.currency), true, |create, code| Call::PotCreateCurrency { create, code });
            row.set_subtitle(&glib_escape(&words::settings_currency_hint()));
            self.form.add(&row);
            *self.currency.borrow_mut() = Some((row, s.currencies.clone()));
        }
        for (held, now) in [(&self.space, s.space.as_deref()), (&self.currency, Some(s.currency.as_str()))] {
            if let Some((row, ids)) = held.borrow().as_ref()
                && let Some(at) = now.and_then(|n| ids.iter().position(|i| i == n))
                && row.selected() != at as u32
            {
                row.set_selected(at as u32);
            }
        }

        // the people it starts with, each taken out by its button
        let mut child = self.people_rows.first_child();
        while let Some(c) = child {
            child = c.next_sibling();
            if &c != self.add_person.upcast_ref::<gtk::Widget>() {
                self.people_rows.remove(&c);
            }
        }
        for (n, p) in s.people.iter().enumerate() {
            let row = adw::ActionRow::builder().title(glib_escape(&p.name)).build();
            row.add_prefix(&avatars::avatar(&p.name, None, 32));
            let remove = gtk::Button::from_icon_name("list-remove-symbolic");
            let label = words::people_remove(&p.name);
            remove.set_tooltip_text(Some(&label));
            remove.update_property(&[gtk::accessible::Property::Label(&label)]);
            remove.set_valign(gtk::Align::Center);
            remove.add_css_class("flat");
            let create = self.create;
            remove.connect_clicked(move |_| bridge::send(Call::PotCreateRemove { create, index: n as i32 }));
            row.add_suffix(&remove);
            self.people_rows.insert(&row, n as i32);
        }

        // the space's members still to add
        while let Some(c) = self.member_rows.first_child() {
            self.member_rows.remove(&c);
        }
        for m in &s.offered {
            let row = adw::ActionRow::builder().title(glib_escape(&m.name)).build();
            row.add_prefix(&avatars::avatar(&m.name, Some(&m.user_id), 32));
            let add = gtk::Button::from_icon_name("contact-new-symbolic");
            let label = words::people_add_member();
            add.set_tooltip_text(Some(&label));
            add.update_property(&[gtk::accessible::Property::Label(&label)]);
            add.set_valign(gtk::Align::Center);
            add.add_css_class("flat");
            let (create, user_id) = (self.create, m.user_id.clone());
            add.connect_clicked(move |_| bridge::send(Call::PotCreateAddMember { create, user_id: user_id.clone() }));
            row.add_suffix(&add);
            self.member_rows.append(&row);
        }
        self.members.set_visible(!s.offered.is_empty());
        self.create_button.set_sensitive(s.can_create && !s.done);
        self.drawing.set(false);
    }

    /// A combo row of [labels] (by [ids]), [at] selected, built in one builder (a later model or search replaces the
    /// selection, which says it changed); a choice sends [call].
    fn combo(self: &Rc<Self>, title: &str, labels: &[String], ids: &[String], at: Option<&str>, search: bool, call: impl Fn(i64, String) -> Call + 'static) -> adw::ComboRow {
        let selected = at.and_then(|a| ids.iter().position(|i| i == a)).unwrap_or(0) as u32;
        let strs: Vec<&str> = labels.iter().map(String::as_str).collect();
        let row = adw::ComboRow::builder()
            .title(title)
            .enable_search(search)
            .expression(gtk::PropertyExpression::new(gtk::StringObject::static_type(), None::<gtk::Expression>, "string"))
            .model(&gtk::StringList::new(&strs))
            .selected(selected)
            .build();
        let (w, ids) = (Rc::downgrade(self), ids.to_vec());
        row.connect_selected_notify(move |r| {
            let Some(i) = w.upgrade() else { return };
            if i.drawing.get() {
                return;
            }
            if let Some(id) = ids.get(r.selected() as usize) {
                bridge::send(call(i.create, id.clone()));
            }
        });
        row
    }
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::PotCreate { shown: Some(s), .. }) => inner.draw(&s),
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a New pot update did not read: {:?}", e.0),
        }
    }
}
