//! A node's share sheet (ADR 0129 decision 2; core's `ShareSheet`, the web's `FolderShareDialog`), GNOME's way: an
//! `AdwDialog` whose page is the list of links — each an `AdwActionRow` with its copy button and its other actions in a
//! "More" menu —, the new-link or edit form (an `AdwComboRow` for the role, an `AdwEntryRow` for the label, an
//! `AdwSwitchRow` with an `AdwPasswordEntryRow` for the password, an `AdwSwitchRow` with a calendar for the end; Cancel and
//! Create/Save in the header bar), or the address just minted, shown once to copy. A renew and a delete are asked in an
//! `AdwAlertDialog`. GNOME has no share sheet: "Copy link" (`gdk::Clipboard`) is the share here.
//!
//! Every state and every rule is the core's (`Share.*`, watched by id); the password typed goes in and never comes back.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::NodeAt, wire::ShareShown, wire::ShownLink, wire::Update, wire::Watched};
use crate::home::glib_escape;
use crate::{said, words};

/// A local day (days since 1970-01-01, the core's count) as its year, month and day.
pub fn ymd(day: i64) -> (i32, i32, i32) {
    let t = glib::DateTime::from_unix_utc(day * 86_400).expect("a day in range");
    (t.year(), t.month(), t.day_of_month())
}

/// The core's day of a calendar's year, month and day.
pub fn day_of(y: i32, m: i32, d: i32) -> Option<i64> {
    glib::DateTime::from_utc(y, m, d, 0, 0, 0.0).ok().map(|t| t.to_unix().div_euclid(86_400))
}

/// A day the person reads: the locale's date.
fn day_text(day: i64) -> String {
    let (y, m, d) = ymd(day);
    said::day(&format!("{y:04}-{m:02}-{d:02}"))
}

/// A link's line under its title: when it was made and ends, then its badges.
pub fn subtitle(link: &ShownLink) -> String {
    let mut dates = vec![words::share_created(&said::instant_day(&link.created_at))];
    if let Some(e) = &link.expires_at {
        dates.push(words::share_expires(&said::instant_day(e)));
    }
    let badges: Vec<String> = link.badges.iter().map(said::notice).collect();
    let mut line = dates.join(" · ");
    if !badges.is_empty() {
        line.push('\n');
        line.push_str(&badges.join(" · "));
    }
    line
}

struct Form {
    group: adw::PreferencesGroup,
    role: adw::ComboRow,
    roles: RefCell<Vec<String>>,
    hint: gtk::Label,
    label: adw::EntryRow,
    lock: adw::SwitchRow,
    password: adw::PasswordEntryRow,
    short: gtk::Label,
    end: adw::SwitchRow,
    end_day: adw::ActionRow,
    calendar: gtk::Calendar,
    problem: gtk::Label,
}

struct Inner {
    id: Cell<Option<i64>>,
    watch: RefCell<Option<Watch>>,
    dialog: adw::Dialog,
    toasts: adw::ToastOverlay,
    title: adw::WindowTitle,
    stack: gtk::Stack,
    intro: gtk::Label,
    add: gtk::Button,
    notice: gtk::Label,
    status: gtk::Label,
    list: gtk::ListBox,
    form: Form,
    cancel: gtk::Button,
    save: gtk::Button,
    fresh: gtk::Entry,
    shown: RefCell<Option<ShareShown>>,
    /// A draw sets the form's widgets: their signals are not the person's.
    drawing: Cell<bool>,
    /// Which form the widgets hold (the link edited, or "" for a new one): a new one fills them anew.
    form_of: RefCell<Option<String>>,
    /// The renew or delete asked on screen: a later state with the same question shows no second alert.
    asked: RefCell<Option<String>>,
}

/// Opens the share sheet of [node] over [parent].
pub fn open(parent: &impl IsA<gtk::Widget>, node: &NodeAt) {
    let toasts = adw::ToastOverlay::new();
    let title = adw::WindowTitle::new("", "");
    let header = adw::HeaderBar::builder().title_widget(&title).build();
    let cancel = gtk::Button::with_label(&words::entry_cancel());
    let save = gtk::Button::new();
    save.add_css_class("suggested-action");
    header.pack_start(&cancel);
    header.pack_end(&save);

    // the list
    let intro = gtk::Label::builder().wrap(true).xalign(0.0).build();
    intro.add_css_class("dim-label");
    let add = gtk::Button::builder().label(words::share_new()).halign(gtk::Align::Start).build();
    add.add_css_class("pill");
    add.add_css_class("suggested-action");
    let notice = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
    let status = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("boxed-list");
    let body = gtk::Box::new(gtk::Orientation::Vertical, 18);
    for w in [intro.upcast_ref::<gtk::Widget>(), add.upcast_ref(), notice.upcast_ref(), status.upcast_ref(), list.upcast_ref()] {
        body.append(w);
    }
    let list_page = page(&body);

    // the form
    let role = adw::ComboRow::builder().title(words::share_role()).build();
    let hint = gtk::Label::builder().wrap(true).xalign(0.0).build();
    hint.add_css_class("dim-label");
    let label = adw::EntryRow::new();
    let lock = adw::SwitchRow::builder().title(words::share_password_section()).build();
    let password = adw::PasswordEntryRow::new();
    let short = gtk::Label::builder().label(words::share_password_short(10)).wrap(true).xalign(0.0).build();
    let end = adw::SwitchRow::builder().title(words::share_expiry_section()).build();
    let calendar = gtk::Calendar::new();
    let pick = gtk::MenuButton::builder()
        .icon_name("x-office-calendar-symbolic")
        .valign(gtk::Align::Center)
        .tooltip_text(words::share_expires_on())
        .popover(&gtk::Popover::builder().child(&calendar).build())
        .build();
    pick.add_css_class("flat");
    let end_day = adw::ActionRow::builder().title(words::share_expires_on()).build();
    end_day.add_suffix(&pick);
    end_day.set_activatable_widget(Some(&pick));
    let problem = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
    problem.add_css_class("error");
    let group = adw::PreferencesGroup::new();
    let rows = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    rows.add_css_class("boxed-list");
    for r in [role.upcast_ref::<gtk::Widget>(), label.upcast_ref()] {
        rows.append(r);
    }
    let lock_rows = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    lock_rows.add_css_class("boxed-list");
    lock_rows.append(&lock);
    lock_rows.append(&password);
    let end_rows = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    end_rows.add_css_class("boxed-list");
    end_rows.append(&end);
    end_rows.append(&end_day);
    let form_body = gtk::Box::new(gtk::Orientation::Vertical, 12);
    for w in [rows.upcast_ref::<gtk::Widget>(), hint.upcast_ref(), lock_rows.upcast_ref(), short.upcast_ref(), end_rows.upcast_ref(), problem.upcast_ref()] {
        form_body.append(w);
    }
    group.add(&form_body);
    let form_page = page(&group);

    // the address shown once
    let ready = gtk::Label::builder().label(words::share_ready()).wrap(true).xalign(0.0).build();
    let fresh = gtk::Entry::builder().editable(false).hexpand(true).build();
    let copy = gtk::Button::builder().label(words::share_copy()).build();
    copy.add_css_class("suggested-action");
    copy.add_css_class("pill");
    let done = gtk::Button::builder().label(words::share_done()).build();
    done.add_css_class("pill");
    let buttons = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    buttons.set_halign(gtk::Align::End);
    buttons.append(&done);
    buttons.append(&copy);
    let fresh_body = gtk::Box::new(gtk::Orientation::Vertical, 12);
    for w in [ready.upcast_ref::<gtk::Widget>(), fresh.upcast_ref(), buttons.upcast_ref()] {
        fresh_body.append(w);
    }
    let fresh_page = page(&fresh_body);

    let stack = gtk::Stack::builder().transition_type(gtk::StackTransitionType::Crossfade).build();
    stack.add_named(&list_page, Some("list"));
    stack.add_named(&form_page, Some("form"));
    stack.add_named(&fresh_page, Some("fresh"));
    let view = adw::ToolbarView::new();
    view.add_top_bar(&header);
    view.set_content(Some(&stack));
    toasts.set_child(Some(&view));
    let dialog = adw::Dialog::builder().content_width(520).content_height(640).child(&toasts).build();

    let inner = Rc::new(Inner {
        id: Cell::new(None),
        watch: RefCell::new(None),
        dialog: dialog.clone(),
        toasts,
        title,
        stack,
        intro,
        add: add.clone(),
        notice,
        status,
        list,
        form: Form { group, role, roles: RefCell::new(Vec::new()), hint, label, lock, password, short, end, end_day, calendar, problem },
        cancel: cancel.clone(),
        save: save.clone(),
        fresh: fresh.clone(),
        shown: RefCell::new(None),
        drawing: Cell::new(false),
        form_of: RefCell::new(None),
        asked: RefCell::new(None),
    });
    inner.wire(&add, &cancel, &save, &copy, &done);
    let held = inner.clone();
    dialog.connect_closed(move |_| {
        held.watch.borrow_mut().take();
        if let Some(share) = held.id.take() {
            bridge::send(Call::ShareClose { share });
        }
    });
    dialog.present(Some(parent));
    let weak = Rc::downgrade(&inner);
    let call = Call::ShareOpen { node: node.clone() };
    glib::spawn_future_local(async move {
        let Ok((Answer::ShareOpened { share }, _)) = bridge::call(&call).await else {
            eprintln!("veyq: the share sheet did not open in the core");
            return;
        };
        let Some(inner) = weak.upgrade() else {
            bridge::send(Call::ShareClose { share });
            return;
        };
        inner.id.set(Some(share));
        let Some((watch, updates)) = bridge::watch(&Watched::Share { share }) else { return };
        *inner.watch.borrow_mut() = Some(watch);
        drop(inner);
        follow(weak, updates).await;
    });
}

/// A page of the dialog: its content clamped and scrolled, with GNOME's margins.
fn page(content: &impl IsA<gtk::Widget>) -> gtk::ScrolledWindow {
    let clamp = adw::Clamp::builder().maximum_size(560).margin_top(12).margin_bottom(18).margin_start(12).margin_end(12).child(content).build();
    gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build()
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Share { shown, .. }) => {
                if let Some(s) = &shown {
                    eprintln!(
                        "veyq: share sheet: {} links{}, busy {:?}, notice {:?}, form {}, editing {:?}, saving {}, problem {:?}, fresh {}, renewing {:?}, deleting {:?}",
                        s.links.as_ref().map_or(0, Vec::len),
                        s.links.as_ref().map(|l| format!(" [{}]", l.iter().map(|k| format!("{}{}", &k.share_id[..6.min(k.share_id.len())], if k.can_copy { "" } else { " off" })).collect::<Vec<_>>().join(", "))).unwrap_or_default(),
                        s.busy, s.notice.as_ref().map(|n| &n.msg), s.form.is_some(), s.editing, s.saving, s.problem.as_ref().map(|p| &p.msg),
                        s.fresh_address.is_some(), s.renewing, s.deleting
                    );
                }
                *inner.shown.borrow_mut() = shown;
                inner.draw();
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a share sheet update did not read: {:?}", e.0),
        }
    }
}

impl Inner {
    /// Sends [call] on the sheet open in the core (each change of the form, an action).
    fn send(&self, call: impl FnOnce(i64) -> Call) {
        if let Some(share) = self.id.get() {
            bridge::send(call(share));
        }
    }

    /// Sends [call] and says what it answered in the dialog's toasts.
    fn ask(self: &Rc<Self>, call: impl FnOnce(i64) -> Call, what: &'static str) {
        let Some(share) = self.id.get() else { return };
        let call = call(share);
        let toasts = self.toasts.clone();
        glib::spawn_future_local(async move {
            crate::header::say(&toasts, bridge::call(&call).await.map(|(a, _)| a), what);
        });
    }

    fn wire(self: &Rc<Self>, add: &gtk::Button, cancel: &gtk::Button, save: &gtk::Button, copy: &gtk::Button, done: &gtk::Button) {
        let w = Rc::downgrade(self);
        add.connect_clicked(move |_| { if let Some(i) = w.upgrade() { i.send(|share| Call::ShareAdd { share }) } });
        let w = Rc::downgrade(self);
        cancel.connect_clicked(move |_| { if let Some(i) = w.upgrade() { i.send(|share| Call::ShareCancelForm { share }) } });
        let w = Rc::downgrade(self);
        // the problem shows under the form from the state, not as a toast
        save.connect_clicked(move |_| { if let Some(i) = w.upgrade() { i.send(|share| Call::ShareSave { share }) } });
        let w = Rc::downgrade(self);
        copy.connect_clicked(move |b| {
            if let Some(i) = w.upgrade() {
                i.copy(b, &i.fresh.text());
            }
        });
        let w = Rc::downgrade(self);
        done.connect_clicked(move |_| { if let Some(i) = w.upgrade() { i.send(|share| Call::ShareFreshSeen { share }) } });

        let f = &self.form;
        let w = Rc::downgrade(self);
        f.role.connect_selected_notify(move |row| {
            let Some(i) = w.upgrade() else { return };
            if i.drawing.get() {
                return;
            }
            let role = i.form.roles.borrow().get(row.selected() as usize).cloned();
            if let Some(role) = role {
                i.send(|share| Call::ShareRole { share, role });
            }
        });
        let w = Rc::downgrade(self);
        f.label.connect_changed(move |e| {
            let Some(i) = w.upgrade() else { return };
            if !i.drawing.get() {
                let label = e.text().to_string();
                i.send(|share| Call::ShareLabel { share, label });
            }
        });
        let w = Rc::downgrade(self);
        f.lock.connect_active_notify(move |s| {
            let Some(i) = w.upgrade() else { return };
            if !i.drawing.get() {
                let on = s.is_active();
                i.send(|share| Call::ShareLockOn { share, on });
            }
        });
        let w = Rc::downgrade(self);
        f.password.connect_changed(move |e| {
            let Some(i) = w.upgrade() else { return };
            if !i.drawing.get() {
                let password = e.text().to_string();
                i.send(|share| Call::SharePassword { share, password });
            }
        });
        let w = Rc::downgrade(self);
        f.end.connect_active_notify(move |s| {
            let Some(i) = w.upgrade() else { return };
            if !i.drawing.get() {
                let on = s.is_active();
                i.send(|share| Call::ShareEndOn { share, on });
            }
        });
        let w = Rc::downgrade(self);
        f.calendar.connect_day_selected(move |c| {
            let Some(i) = w.upgrade() else { return };
            if i.drawing.get() {
                return;
            }
            let t = c.date();
            if let Some(day) = day_of(t.year(), t.month(), t.day_of_month()) {
                i.send(|share| Call::ShareEndDay { share, day });
            }
        });
    }

    /// [address] to the clipboard (GNOME's share), said in a toast.
    fn copy(&self, from: &impl IsA<gtk::Widget>, address: &str) {
        from.clipboard().set_text(address);
        eprintln!("veyq: share: a link's address copied");
        self.toasts.add_toast(adw::Toast::new(&words::share_copied()));
    }

    fn draw(self: &Rc<Self>) {
        let shown = self.shown.borrow().clone();
        let Some(s) = shown else { return };
        self.title.set_title(&said::key(&s.title));
        let page = if s.fresh_address.is_some() { "fresh" } else if s.form.is_some() { "form" } else { "list" };
        self.stack.set_visible_child_name(page);
        self.cancel.set_visible(page == "form");
        self.save.set_visible(page == "form");
        self.dialog.set_can_close(!s.saving);
        match page {
            "fresh" => self.fresh.set_text(s.fresh_address.as_deref().unwrap_or_default()),
            "form" => self.draw_form(&s),
            _ => {
                *self.form_of.borrow_mut() = None;
                self.draw_list(&s);
            }
        }
        self.ask_question(&s);
    }

    fn draw_list(self: &Rc<Self>, s: &ShareShown) {
        self.intro.set_label(&said::key(&s.intro));
        self.add.set_visible(s.can_mint);
        self.add.set_sensitive(s.busy.is_none());
        self.notice.set_visible(s.notice.is_some());
        if let Some(n) = &s.notice {
            self.notice.set_label(&said::notice(n));
        }
        while let Some(row) = self.list.first_child() {
            self.list.remove(&row);
        }
        let Some(links) = &s.links else {
            self.status.set_visible(!s.failed);
            self.list.set_visible(false);
            if !s.failed {
                self.status.set_label("…");
            }
            return;
        };
        if s.failed {
            self.status.set_label(&words::share_load_failed());
            self.status.add_css_class("error");
        }
        self.status.set_visible(s.failed);
        self.list.set_visible(!links.is_empty());
        for link in links {
            self.list.append(&self.row(link, s.busy.as_deref() == Some(link.share_id.as_str())));
        }
    }

    /// One link: its title and line, its copy button, a spinner while a write runs on it, the rest in its menu.
    fn row(self: &Rc<Self>, link: &ShownLink, busy: bool) -> adw::ActionRow {
        let row = adw::ActionRow::builder().title(glib_escape(&said::piece(&link.title))).subtitle(glib_escape(&subtitle(link))).build();
        if let (Some(address), true) = (link.address.clone(), link.can_copy) {
            let copy = gtk::Button::builder().icon_name("edit-copy-symbolic").tooltip_text(words::share_copy()).valign(gtk::Align::Center).build();
            copy.add_css_class("flat");
            copy.update_property(&[gtk::accessible::Property::Label(&words::share_copy())]);
            let w = Rc::downgrade(self);
            copy.connect_clicked(move |b| {
                if let Some(i) = w.upgrade() {
                    i.copy(b, &address);
                }
            });
            row.add_suffix(&copy);
        }
        if busy {
            row.add_suffix(&adw::Spinner::new());
            return row;
        }
        let actions = gio::SimpleActionGroup::new();
        let menu = gio::Menu::new();
        let main = gio::Menu::new();
        let closing = gio::Menu::new();
        let id = link.share_id.clone();
        let entries: [(&str, bool, String, &gio::Menu); 5] = [
            ("edit", link.can_edit, words::share_edit(), &main),
            ("reactivate", link.can_reactivate, words::share_reactivate(), &main),
            ("renew", link.can_renew, words::share_renew(), &main),
            ("switch-off", link.can_switch_off, words::share_switch_off(), &closing),
            ("delete", link.can_delete, words::share_delete(), &closing),
        ];
        for (name, offered, word, group) in entries {
            if !offered {
                continue;
            }
            let action = gio::SimpleAction::new(name, None);
            let (w, id) = (Rc::downgrade(self), id.clone());
            action.connect_activate(move |_, _| {
                let Some(i) = w.upgrade() else { return };
                let link = id.clone();
                match name {
                    "edit" => i.send(|share| Call::ShareEdit { share, link }),
                    "reactivate" => i.ask(|share| Call::ShareReactivate { share, link }, "Share.Reactivate"),
                    "renew" => i.send(|share| Call::ShareAskRenew { share, link }),
                    "switch-off" => i.ask(|share| Call::ShareSwitchOff { share, link }, "Share.SwitchOff"),
                    _ => i.send(|share| Call::ShareAskDelete { share, link }),
                }
            });
            actions.add_action(&action);
            group.append(Some(&word), Some(&format!("link.{name}")));
        }
        menu.append_section(None, &main);
        menu.append_section(None, &closing);
        if main.n_items() + closing.n_items() > 0 {
            row.insert_action_group("link", Some(&actions));
            let more = gtk::MenuButton::builder().icon_name("view-more-symbolic").tooltip_text(words::node_more()).menu_model(&menu).valign(gtk::Align::Center).build();
            more.add_css_class("flat");
            row.add_suffix(&more);
        }
        row
    }

    fn draw_form(self: &Rc<Self>, s: &ShareShown) {
        let Some(form) = &s.form else { return };
        let f = &self.form;
        self.drawing.set(true);
        let which = s.editing.clone().unwrap_or_default();
        let fresh_form = self.form_of.borrow().as_ref() != Some(&which);
        if fresh_form {
            // a form just opened: its widgets filled from the core's form once, then only what the person types
            *self.form_of.borrow_mut() = Some(which);
            f.label.set_text(&form.label);
            f.password.set_text("");
        }
        let roles: Vec<String> = form.roles.iter().map(|r| r.role.clone()).collect();
        if *f.roles.borrow() != roles {
            let words: Vec<String> = form.roles.iter().map(|r| said::key(&r.word)).collect();
            f.role.set_model(Some(&gtk::StringList::new(&words.iter().map(String::as_str).collect::<Vec<_>>())));
            *f.roles.borrow_mut() = roles.clone();
        }
        f.role.set_visible(!roles.is_empty());
        if let Some(i) = roles.iter().position(|r| *r == form.role) {
            f.role.set_selected(i as u32);
        }
        f.hint.set_label(&said::key(&form.hint));
        f.label.set_visible(form.names_it);
        f.label.set_title(&said::key(&form.name_label));
        f.lock.set_active(form.lock.on);
        f.lock.set_sensitive(form.lock.optional && !s.saving);
        f.lock.set_subtitle(&said::notice(&form.lock.hint));
        f.password.set_visible(form.lock.on);
        f.password.set_title(&if form.lock.kept { words::share_password_keep() } else { words::share_password_section() });
        f.short.set_visible(form.lock.on);
        if form.lock.too_short { f.short.add_css_class("error") } else { f.short.remove_css_class("error") }
        f.end.set_active(form.end.on);
        f.end.set_sensitive(form.end.optional && !s.saving);
        f.end.set_subtitle(&said::notice(&form.end.hint));
        f.end_day.set_visible(form.end.on);
        f.end_day.set_subtitle(&day_text(form.end.day));
        let (y, m, d) = ymd(form.end.day);
        if let Ok(t) = glib::DateTime::from_local(y, m, d, 12, 0, 0.0) {
            f.calendar.set_date(&t);
        }
        f.problem.set_visible(s.problem.is_some());
        if let Some(p) = &s.problem {
            f.problem.set_label(&said::notice(p));
        }
        f.group.set_sensitive(!s.saving);
        self.save.set_label(&said::key(&s.submit));
        self.save.set_sensitive(form.can_save && !s.saving);
        self.cancel.set_sensitive(!s.saving);
        self.drawing.set(false);
    }

    /// A renew or a delete the core asks: an alert dialog, its answer sent.
    fn ask_question(self: &Rc<Self>, s: &ShareShown) {
        let (asked, renew) = match (&s.renewing, &s.deleting) {
            (Some(id), _) => (id.clone(), true),
            (_, Some(id)) => (id.clone(), false),
            _ => return,
        };
        // asked once per question: a later state with the same question shows no second dialog
        let key = format!("{}{asked}", if renew { "renew:" } else { "delete:" });
        if self.asked.borrow().as_deref() == Some(key.as_str()) {
            return;
        }
        *self.asked.borrow_mut() = Some(key);
        let heading = if renew { words::share_renew() } else { words::share_delete() };
        let body = said::key(if renew { &s.renew_question } else { &s.delete_question });
        let ask = adw::AlertDialog::builder().heading(&heading).body(&body).build();
        ask.add_responses(&[("cancel", &words::entry_cancel()), ("confirm", &heading)]);
        ask.set_response_appearance("confirm", if renew { adw::ResponseAppearance::Suggested } else { adw::ResponseAppearance::Destructive });
        ask.set_default_response(Some("cancel"));
        ask.set_close_response("cancel");
        let w = Rc::downgrade(self);
        ask.connect_response(None, move |_, response| {
            let Some(i) = w.upgrade() else { return };
            i.asked.borrow_mut().take();
            match (response, renew) {
                ("confirm", true) => i.ask(|share| Call::ShareRenew { share }, "Share.Renew"),
                ("confirm", false) => i.ask(|share| Call::ShareDelete { share }, "Share.Delete"),
                _ => i.send(|share| Call::ShareCancelAsk { share }),
            }
        });
        ask.present(Some(&self.dialog));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bridge::wire::{Notice, Piece};

    #[test]
    fn a_day_goes_to_the_calendar_and_back() {
        assert_eq!(ymd(0), (1970, 1, 1));
        assert_eq!(ymd(20_768), (2026, 11, 11));
        assert_eq!(day_of(2026, 11, 11), Some(20_768));
        assert_eq!(day_of(2000, 2, 29), Some(11_016));
    }

    #[test]
    fn a_links_line_says_its_days_then_its_badges() {
        let link = ShownLink {
            share_id: "s".into(), title: Piece::Plain { text: "Family".into() },
            badges: vec![Notice { msg: "share_switched_off".into(), args: vec![] }],
            created_at: "2026-11-01T10:00:00Z".into(), expires_at: None, address: None, can_copy: false, can_edit: false,
            can_renew: true, can_switch_off: false, can_reactivate: true, can_delete: true,
        };
        let line = subtitle(&link);
        assert_eq!(line.lines().count(), 2, "{line}");
        assert!(line.lines().next().unwrap().contains(&said::instant_day("2026-11-01T10:00:00Z")));
    }
}
