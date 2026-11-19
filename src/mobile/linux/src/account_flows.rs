//! The account's flows on Linux (ADR 0148 decision 10, the Linux row; the web's `changePassword`, `emailChange`,
//! `rotation` and `erasure` panels the reference), GNOME's way: each an `AdwDialog` holding an `AdwNavigationView` of
//! pages, every password and Secret Key typed into an `AdwPasswordEntryRow`, every danger step asked first in an
//! `AdwAlertDialog` with a destructive response, and the Emergency Kit saved through `gtk::FileDialog` (the portal's where
//! there is one) — the core writes it straight into the file chosen (`Kit.Save` by path, ADR 0137 decision 3); it is
//! never shown and never passes through this side.
//!
//! Every state is the core's (`PasswordChange.*`, `AddressChange.*`, `KeyRotation.*`, `Erasure.*`, `Kit.*`, each watched):
//! this side draws it and sends what is typed or pressed. A typed secret is read out of its row once, sent, and the row
//! emptied; nothing of it is kept here. Linux keeps no snapshot of a window (src/mobile/CLAUDE.md § Secrets on screen), so
//! no secure-window flag is set.
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, Watch, wire::AccountErasureState, wire::Answer, wire::Call, wire::KeyRotationState, wire::KitStepState,
    wire::Step, wire::Update, wire::Watched};
use crate::{said, words};

/// What one flow's dialog holds while it is open: its watches, and the kit steps it opened itself (closed with it).
#[derive(Default)]
struct Held {
    watches: RefCell<Vec<Watch>>,
    kits: RefCell<Vec<i64>>,
}

/// A flow's dialog: its navigation, its toasts and what it holds.
#[derive(Clone)]
struct Flow {
    dialog: adw::Dialog,
    nav: adw::NavigationView,
    toasts: adw::ToastOverlay,
    held: Rc<Held>,
}

impl Flow {
    fn new(title: &str) -> Self {
        let nav = adw::NavigationView::new();
        let toasts = { let t = adw::ToastOverlay::new(); t.set_child(Some(&nav)); t };
        let dialog = adw::Dialog::builder().title(title).content_width(560).content_height(680).child(&toasts).build();
        Self { dialog, nav, toasts, held: Rc::new(Held::default()) }
    }

    /// Presents the dialog over [parent]; once closed, [closing] runs, the watches go and the kits it opened close.
    fn present(&self, parent: &gtk::Widget, closing: impl Fn() + 'static) {
        let held = self.held.clone();
        self.dialog.connect_closed(move |_| {
            closing();
            held.watches.borrow_mut().clear();
            for kit in held.kits.borrow_mut().drain(..) {
                bridge::send(Call::KitClose { kit });
            }
        });
        self.dialog.present(Some(parent));
    }

    /// Watches [watched]; each update goes to [draw] until it answers false.
    fn watch(&self, watched: &Watched, draw: impl Fn(Update) -> bool + 'static) {
        let Some((watch, updates)) = bridge::watch(watched) else { return };
        self.held.watches.borrow_mut().push(watch);
        glib::spawn_future_local(async move {
            while let Ok(update) = updates.recv().await {
                match update {
                    Ok(u) => {
                        if !draw(u) {
                            return;
                        }
                    }
                    Err(e) => eprintln!("veyq: a flow's update did not read: {:?}", e.0),
                }
            }
        });
    }

    fn window(&self) -> Option<gtk::Window> {
        self.dialog.root().and_downcast::<gtk::Window>()
    }
}

/// A page of a flow: a header bar over the body, which scrolls.
fn page(title: &str, tag: &str, body: &impl IsA<gtk::Widget>) -> adw::NavigationPage {
    let clamp = adw::Clamp::builder().maximum_size(520).margin_top(12).margin_bottom(24).margin_start(12).margin_end(12).child(body).build();
    let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build();
    let view = adw::ToolbarView::new();
    view.add_top_bar(&adw::HeaderBar::new());
    view.set_content(Some(&scroll));
    adw::NavigationPage::builder().title(title).tag(tag).child(&view).build()
}

fn column() -> gtk::Box {
    gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).build()
}

fn text(words: &str) -> gtk::Label {
    gtk::Label::builder().label(words).wrap(true).xalign(0.0).build()
}

fn dim(words: &str) -> gtk::Label {
    let l = text(words);
    l.add_css_class("dim-label");
    l
}

/// Why the last step did not come through, in the error colour; hidden while there is none.
fn error_label() -> gtk::Label {
    let l = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
    l.add_css_class("error");
    l
}

fn said_refusal(label: &gtk::Label, refusal: Option<&String>) {
    label.set_visible(refusal.is_some());
    label.set_label(&refusal.map(|r| said::key(r)).unwrap_or_default());
}

fn pill(words: &str, css: &str) -> gtk::Button {
    let b = gtk::Button::builder().label(words).halign(gtk::Align::Center).build();
    b.add_css_class("pill");
    if !css.is_empty() {
        b.add_css_class(css);
    }
    b
}

fn rows(of: &[&gtk::Widget]) -> gtk::ListBox {
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("boxed-list");
    for r in of {
        list.append(*r);
    }
    list
}

fn secret(title: &str) -> adw::PasswordEntryRow {
    adw::PasswordEntryRow::builder().title(title).build()
}

/// What a row holds, read once and the row emptied: a typed secret is sent and not kept.
fn take(row: &impl IsA<gtk::Editable>) -> String {
    let typed = row.text().to_string();
    row.set_text("");
    typed
}

/// A plain line of facts, each a row (the web's fact lists).
fn facts(lines: &[String]) -> gtk::ListBox {
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("boxed-list");
    for line in lines {
        let row = adw::ActionRow::builder().title(line.as_str()).use_markup(false).build();
        row.add_prefix(&gtk::Image::from_icon_name("dialog-information-symbolic"));
        list.append(&row);
    }
    list
}

fn spinner_page(title: &str, tag: &str) -> adw::NavigationPage {
    let spinner = adw::Spinner::builder().width_request(48).height_request(48).halign(gtk::Align::Center).valign(gtk::Align::Center).vexpand(true).build();
    page(title, tag, &spinner)
}

/// Asks [heading] with [body] first; [accept] (its destructive words) runs [then].
fn ask(over: &impl IsA<gtk::Widget>, heading: &str, body: &str, accept: &str, then: impl Fn() + 'static) {
    let alert = adw::AlertDialog::builder().heading(heading).body(body).build();
    alert.add_responses(&[("cancel", &words::signin_cancel()), ("accept", accept)]);
    alert.set_response_appearance("accept", adw::ResponseAppearance::Destructive);
    alert.set_default_response(Some("cancel"));
    alert.set_close_response("cancel");
    alert.connect_response(None, move |_, response| {
        if response == "accept" {
            then();
        }
    });
    alert.present(Some(over));
}

/// The person's local date as days since 1970-01-01: the kit's "Created on".
fn local_epoch_day() -> i64 {
    let now = glib::DateTime::now_local().ok();
    now.and_then(|n| glib::DateTime::from_utc(n.year(), n.month(), n.day_of_month(), 0, 0, 0.0).ok())
        .map_or(0, |d| d.to_unix().div_euclid(86_400))
}

/// One flow's id in the core once answered, sent its close when the dialog goes.
type Id = Rc<Cell<Option<i64>>>;

/// Opens a flow with [opening] and, once answered `FlowOpened`, watches it with [watched]; refused, the dialog closes.
fn open_flow(flow: &Flow, id: &Id, opening: Call, watched: impl Fn(i64) -> Watched + 'static, draw: impl Fn(Update) -> bool + 'static) {
    let (f, id) = (flow.clone(), id.clone());
    glib::spawn_future_local(async move {
        match bridge::call(&opening).await.map(|(a, _)| a) {
            Ok(Answer::FlowOpened { flow: n }) => {
                id.set(Some(n));
                f.watch(&watched(n), draw);
            }
            other => {
                eprintln!("veyq: a flow's opening answered {other:?}");
                f.dialog.force_close();
            }
        }
    });
}

/// Sends what [make] builds for the flow's id, its answer to stderr as [what].
fn act(id: &Id, what: &'static str, make: impl FnOnce(i64) -> Call) {
    let Some(n) = id.get() else { return };
    let call = make(n);
    glib::spawn_future_local(async move {
        let answered = bridge::call(&call).await.map(|(a, _)| a);
        eprintln!("veyq: {what}: {}", crate::manage::answer_kind(&answered));
    });
}

// ---- the Emergency Kit's step (D5): saved through the save dialog, never shown ----

/// The kit step [kit] as a page: its words [sub], "Save your Emergency Kit" (the save dialog, the core writing into the
/// file chosen), "I saved it" only once a file was written, and — for the rotation — its danger button [danger],
/// enabled once both are done.
fn kit_page(flow: &Flow, kit: i64, sub: Option<&str>, extra: Option<&gtk::Widget>, danger: Option<&gtk::Button>) -> adw::NavigationPage {
    let col = column();
    if let Some(sub) = sub {
        col.append(&text(sub));
    }
    let save = pill(&words::kit_save_title(), "suggested-action");
    save.set_widget_name("kit-save");
    col.append(&save);
    let error = error_label();
    col.append(&error);
    let check = gtk::CheckButton::builder().label(words::kit_saved_confirm()).sensitive(false).build();
    check.set_widget_name("kit-saved");
    col.append(&check);
    if let Some(e) = extra {
        col.append(e);
    }
    if let Some(d) = danger {
        d.set_sensitive(false);
        col.append(d);
    }
    let drawing = Rc::new(Cell::new(false));
    // the file's first name, the state's (the web's `veyq-emergency-kit-<email>.pdf`)
    let name = Rc::new(RefCell::new(String::new()));
    let d = drawing.clone();
    check.connect_toggled(move |c| {
        if !d.get() {
            bridge::send(Call::KitConfirm { kit, checked: c.is_active() });
        }
    });
    let (f, n) = (flow.clone(), name.clone());
    save.connect_clicked(move |_| {
        let (f, first) = (f.clone(), n.borrow().clone());
        glib::spawn_future_local(async move {
            let dialog = gtk::FileDialog::builder().title(words::kit_save_title()).initial_name(first.as_str()).modal(true).build();
            if let Some(documents) = glib::user_special_dir(glib::UserDirectory::Documents).filter(|d| d.is_dir()) {
                dialog.set_initial_folder(Some(&gio::File::for_path(documents)));
            }
            let chosen = match dialog.save_future(f.window().as_ref()).await {
                Ok(chosen) => chosen,
                Err(e) => return eprintln!("veyq: kit: no file chosen ({e})"),
            };
            // the core opens the path itself; one that is no UTF-8 does not cross the wire
            let Some(path) = chosen.path().and_then(|p| p.to_str().map(str::to_owned)) else {
                return f.toasts.add_toast(adw::Toast::new(&words::kit_save_failed()));
            };
            eprintln!("veyq: kit: saving into {path}");
            let answered = bridge::call(&Call::KitSave { kit, path, language: words::language().to_owned(), created_epoch_day: local_epoch_day() }).await;
            eprintln!("veyq: Kit.Save: {}", crate::manage::answer_kind(&answered.map(|(a, _)| a)));
        });
    });
    let (c, e, dz) = (check.clone(), error.clone(), danger.cloned());
    flow.watch(&Watched::Kit { kit }, move |u| {
        let Update::Kit { shown, .. } = u else { return true };
        let Some(st) = shown else { return false };
        *name.borrow_mut() = st.file_name.clone();
        draw_kit(&st, &c, &e, dz.as_ref(), &drawing);
        true
    });
    page(&words::kit_save_title(), "kit", &col)
}

fn draw_kit(st: &KitStepState, check: &gtk::CheckButton, error: &gtk::Label, danger: Option<&gtk::Button>, drawing: &Cell<bool>) {
    eprintln!("veyq: kit: saved {}, confirmed {}, refusal {:?}", st.saved, st.confirmed, st.refusal);
    check.set_sensitive(st.saved);
    drawing.set(true);
    check.set_active(st.confirmed);
    drawing.set(false);
    said_refusal(error, st.refusal.as_ref());
    if let Some(d) = danger {
        d.set_sensitive(st.saved && st.confirmed);
    }
}

// ---- change the password (D6) ----

/// "Change password" for the account [connection] names, over [parent].
pub fn password(parent: &gtk::Widget, connection: String) {
    let flow = Flow::new(&words::password_change_title());
    let (current, next, confirm, key) = (
        secret(&words::password_change_current()),
        secret(&words::password_change_new()),
        secret(&words::password_change_confirm()),
        secret(&words::password_change_secret_key()),
    );
    let col = column();
    col.append(&rows(&[current.upcast_ref(), next.upcast_ref(), confirm.upcast_ref()]));
    col.append(&rows(&[key.upcast_ref()]));
    col.append(&dim(&words::password_change_secret_key_hint()));
    let error = error_label();
    col.append(&error);
    let submit = pill(&words::password_change_submit(), "suggested-action");
    col.append(&submit);
    flow.nav.add(&page(&words::password_change_title(), "form", &col));
    let id: Id = Rc::new(Cell::new(None));
    let i = id.clone();
    submit.connect_clicked(move |_| {
        let (c, n, k, key) = (take(&current), take(&next), take(&confirm), take(&key));
        act(&i, "PasswordChange.Run", |flow| Call::PasswordChangeRun { flow, current_password: c, new_password: n, confirm_password: k, secret_key: key });
    });
    let (f, done) = (flow.clone(), Rc::new(Cell::new(false)));
    open_flow(&flow, &id, Call::PasswordChangeOpen { connection }, |flow| Watched::PasswordChange { flow }, move |u| {
        let Update::PasswordChange { shown, .. } = u else { return true };
        let Some(s) = shown else {
            if !done.get() {
                f.dialog.force_close();
            }
            return false;
        };
        eprintln!("veyq: password change: working {}, done {}, refusal {:?}", s.working, s.done, s.refusal);
        submit.set_sensitive(!s.working);
        said_refusal(&error, s.refusal.as_ref());
        if s.done && !done.replace(true) {
            let col = column();
            let status = adw::StatusPage::builder().icon_name("object-select-symbolic").title(words::password_change_done()).description(words::password_change_done_note()).build();
            col.append(&status);
            let close = pill(&words::share_done(), "suggested-action");
            let d = f.dialog.clone();
            close.connect_clicked(move |_| {
                d.close();
            });
            col.append(&close);
            f.nav.replace(&[page(&words::password_change_title(), "done", &col)]);
        }
        true
    });
    let i = id.clone();
    flow.present(parent, move || {
        if let Some(flow) = i.take() {
            bridge::send(Call::PasswordChangeClose { flow });
        }
    });
}

// ---- change the sign-in address (D6), and the new kit after it ----

/// "Change sign-in address" for the account [connection] names: the request (the confirm is the web's), and "Print a
/// new Emergency Kit" with the account's address once the password and the Secret Key passed.
pub fn address(parent: &gtk::Widget, connection: String) {
    let flow = Flow::new(&words::email_change_action());
    let email = adw::EntryRow::builder().title(words::email_change_new_email()).input_purpose(gtk::InputPurpose::Email).build();
    let (password, key) = (secret(&words::signin_password()), secret(&words::password_change_secret_key()));
    let col = column();
    col.append(&dim(&words::email_change_form_sub()));
    col.append(&rows(&[email.upcast_ref(), password.upcast_ref(), key.upcast_ref()]));
    let error = error_label();
    col.append(&error);
    let submit = pill(&words::email_change_submit(), "suggested-action");
    col.append(&submit);
    let new_kit = pill(&words::email_change_kit_title(), "flat");
    col.append(&new_kit);
    flow.nav.add(&page(&words::email_change_action(), "form", &col));
    let id: Id = Rc::new(Cell::new(None));
    // which form asked last (the request, or the new kit's check): the refusal is said under that one alone
    let asking_kit = Rc::new(Cell::new(false));
    let (i, ak) = (id.clone(), asking_kit.clone());
    submit.connect_clicked(move |_| {
        ak.set(false);
        let (new_email, password, secret_key) = (email.text().to_string(), take(&password), take(&key));
        act(&i, "AddressChange.Request", |flow| Call::AddressChangeRequest { flow, new_email, password, secret_key });
    });
    // the kit's check: the password and the Secret Key, then the kit step
    let kit_error = error_label();
    let (f, i, ke, ak) = (flow.clone(), id.clone(), kit_error.clone(), asking_kit.clone());
    let open_kit = Rc::new(move || {
        ke.set_visible(false);
        let (password, key) = (secret(&words::signin_password()), secret(&words::password_change_secret_key()));
        let col = column();
        col.append(&text(&words::email_change_kit_sub()));
        col.append(&rows(&[password.upcast_ref(), key.upcast_ref()]));
        col.append(&ke);
        let check = pill(&words::email_change_kit_check(), "suggested-action");
        col.append(&check);
        let (f2, i2, ak2) = (f.clone(), i.clone(), ak.clone());
        check.connect_clicked(move |_| {
            let Some(n) = i2.get() else { return };
            ak2.set(true);
            let (password, secret_key) = (take(&password), take(&key));
            let f3 = f2.clone();
            glib::spawn_future_local(async move {
                match bridge::call(&Call::AddressChangeKit { flow: n, password, secret_key }).await.map(|(a, _)| a) {
                    Ok(Answer::KitOpened { kit }) => {
                        f3.held.kits.borrow_mut().push(kit);
                        let close = pill(&words::share_done(), "suggested-action");
                        let d = f3.dialog.clone();
                        close.connect_clicked(move |_| {
                            d.close();
                        });
                        let p = kit_page(&f3, kit, None, None, Some(&close));
                        f3.nav.push(&p);
                    }
                    other => eprintln!("veyq: AddressChange.Kit: {}", crate::manage::answer_kind(&other)),
                }
            });
        });
        f.nav.push(&page(&words::email_change_kit_title(), "kit-check", &col));
    });
    let o = open_kit.clone();
    new_kit.connect_clicked(move |_| o());
    let (f, sent) = (flow.clone(), Rc::new(RefCell::new(None::<String>)));
    open_flow(&flow, &id, Call::AddressChangeOpen { connection }, |flow| Watched::AddressChange { flow }, move |u| {
        let Update::AddressChange { shown, .. } = u else { return true };
        let Some(s) = shown else {
            f.dialog.force_close();
            return false;
        };
        eprintln!("veyq: address change: working {}, sent {}, refusal {:?}", s.working, s.sent_to.is_some(), s.refusal);
        submit.set_sensitive(!s.working);
        said_refusal(&error, s.refusal.as_ref().filter(|_| !asking_kit.get()));
        said_refusal(&kit_error, s.refusal.as_ref().filter(|_| asking_kit.get()));
        if let Some(to) = s.sent_to.clone().filter(|to| sent.borrow().as_ref() != Some(to)) {
            *sent.borrow_mut() = Some(to.clone());
            let col = column();
            let status = adw::StatusPage::builder().icon_name("mail-send-symbolic").description(words::email_change_sent(&to)).build();
            col.append(&status);
            let kit = pill(&words::email_change_kit_title(), "");
            let o = open_kit.clone();
            kit.connect_clicked(move |_| o());
            col.append(&kit);
            f.nav.push(&page(&words::email_change_action(), "sent", &col));
        }
        true
    });
    let i = id.clone();
    flow.present(parent, move || {
        if let Some(flow) = i.take() {
            bridge::send(Call::AddressChangeClose { flow });
        }
    });
}

// ---- replace the keys (D7) ----

/// "Secure your account" for the account [connection] names: the gate, the new kit saved, the danger confirmation, the
/// publish and each space's new lock. A moderator is sent to the web (D9).
pub fn rotation(parent: &gtk::Widget, connection: String) {
    let flow = Flow::new(&words::rotation_title());
    flow.nav.add(&spinner_page(&words::rotation_title(), "loading"));
    let id: Id = Rc::new(Cell::new(None));
    let gate = Rc::new(Gate::new(&flow, &id));
    let shown = Rc::new(Cell::new(None::<Step>));
    let done = Rc::new(Cell::new(false));
    let f = flow.clone();
    open_flow(&flow, &id, Call::KeyRotationOpen { connection }, |flow| Watched::KeyRotation { flow }, move |u| {
        let Update::KeyRotation { shown: state, .. } = u else { return true };
        // a finished rotation re-opens the tenant, which closes the flow in the core: its last page stays
        let Some(s) = state else {
            if !done.get() {
                f.dialog.force_close();
            }
            return false;
        };
        eprintln!("veyq: rotation: {:?}, new password {}, done {}, refusal {:?}", s.step, s.new_password, s.done, s.refusal);
        done.set(s.done);
        gate.draw(&s);
        if shown.replace(Some(s.step)) == Some(s.step) && s.step != Step::Done {
            return true;
        }
        match s.step {
            Step::Loading => f.nav.replace(&[spinner_page(&words::rotation_title(), "loading")]),
            Step::OnTheWeb => f.nav.replace(&[on_the_web(&words::rotation_title(), &words::rotation_moderator_web(), &s.server)]),
            Step::Gate => f.nav.replace(std::slice::from_ref(&gate.page)),
            // the kit's page is pushed once `Prove` answered its step; a run refused there comes back to it
            Step::Kit => {
                if let Some(kit) = gate.kit.borrow().clone() {
                    f.nav.replace(&[gate.page.clone(), kit]);
                }
            }
            Step::Working => f.nav.replace(&[spinner_page(&words::rotation_title(), "working")]),
            Step::Done => f.nav.replace(&[rotation_done(&f, &s)]),
        }
        true
    });
    let i = id.clone();
    flow.present(parent, move || {
        if let Some(flow) = i.take() {
            bridge::send(Call::KeyRotationClose { flow });
        }
    });
}

/// The rotation's gate (the web's): its facts, the password and the Secret Key, "Also choose a new password", Continue.
struct Gate {
    page: adw::NavigationPage,
    new_password: adw::SwitchRow,
    next: adw::PasswordEntryRow,
    confirm: adw::PasswordEntryRow,
    error: gtk::Label,
    kit_error: gtk::Label,
    go: gtk::Button,
    drawing: Rc<Cell<bool>>,
    /// The kit step's page once `Prove` opened it: shown again where a run was refused.
    kit: Rc<RefCell<Option<adw::NavigationPage>>>,
}

impl Gate {
    fn new(flow: &Flow, id: &Id) -> Self {
        let col = column();
        col.append(&text(&words::rotation_sub()));
        col.append(&facts(&[
            words::rotation_fact_shared_spaces(),
            words::rotation_fact_signs_out(),
            words::rotation_fact_new_kit(),
            words::rotation_fact_password(),
            words::rotation_fact_not_repair(),
            words::rotation_fact_links(),
            words::rotation_store_wiped(),
        ]));
        let (password, key) = (secret(&words::rotation_password()), secret(&words::rotation_secret_key()));
        let new_password = adw::SwitchRow::builder().title(words::rotation_new_password()).build();
        let (next, confirm) = (secret(&words::password_change_new()), secret(&words::password_change_confirm()));
        col.append(&rows(&[password.upcast_ref(), key.upcast_ref()]));
        col.append(&dim(&words::rotation_secret_key_hint()));
        col.append(&rows(&[new_password.upcast_ref(), next.upcast_ref(), confirm.upcast_ref()]));
        let error = error_label();
        col.append(&error);
        let go = pill(&words::rotation_next(), "suggested-action");
        col.append(&go);
        let drawing = Rc::new(Cell::new(false));
        let (i, d) = (id.clone(), drawing.clone());
        new_password.connect_active_notify(move |s| {
            if !d.get() {
                let on = s.is_active();
                act(&i, "KeyRotation.ChooseNewPassword", |flow| Call::KeyRotationChooseNewPassword { flow, on });
            }
        });
        let kit_error = error_label();
        let gate_page = page(&words::rotation_title(), "gate", &col);
        let kit = Rc::new(RefCell::new(None));
        let this = Self { page: gate_page, new_password, next: next.clone(), confirm: confirm.clone(), error, kit_error, go: go.clone(), drawing, kit: kit.clone() };
        let (f, i, ke, held_kit) = (flow.clone(), id.clone(), this.kit_error.clone(), kit);
        go.connect_clicked(move |_| {
            let Some(n) = i.get() else { return };
            let (password, secret_key, new_password, confirm_password) = (take(&password), take(&key), take(&next), take(&confirm));
            let (f, i, ke, held_kit) = (f.clone(), i.clone(), ke.clone(), held_kit.clone());
            glib::spawn_future_local(async move {
                match bridge::call(&Call::KeyRotationProve { flow: n, password, secret_key, new_password, confirm_password }).await.map(|(a, _)| a) {
                    Ok(Answer::KitOpened { kit }) => {
                        let danger = pill(&words::rotation_action(), "destructive-action");
                        danger.set_widget_name("rotation-run");
                        let (f2, i2) = (f.clone(), i.clone());
                        danger.connect_clicked(move |_| {
                            let i3 = i2.clone();
                            ask(&f2.dialog, &words::rotation_confirm_title(), &words::rotation_confirm_body(), &words::rotation_action(), move || {
                                act(&i3, "KeyRotation.Run", |flow| Call::KeyRotationRun { flow });
                            });
                        });
                        let extra = column();
                        extra.append(&dim(&words::rotation_kit_keep_old()));
                        extra.append(&ke);
                        let p = kit_page(&f, kit, Some(&words::rotation_kit_sub()), Some(extra.upcast_ref()), Some(&danger));
                        *held_kit.borrow_mut() = Some(p.clone());
                        f.nav.push(&p);
                    }
                    other => eprintln!("veyq: KeyRotation.Prove: {}", crate::manage::answer_kind(&other)),
                }
            });
        });
        this
    }

    fn draw(&self, s: &KeyRotationState) {
        self.drawing.set(true);
        self.new_password.set_active(s.new_password);
        self.drawing.set(false);
        self.next.set_visible(s.new_password);
        self.confirm.set_visible(s.new_password);
        self.go.set_sensitive(s.step == Step::Gate);
        let at_gate = matches!(s.step, Step::Gate | Step::Loading);
        said_refusal(&self.error, s.refusal.as_ref().filter(|_| at_gate));
        said_refusal(&self.kit_error, s.refusal.as_ref().filter(|_| s.step == Step::Kit));
    }
}

/// The rotation's end: the old kit no longer works, this device's offline content is fetched again, and each space's
/// new lock as the server says it.
fn rotation_done(flow: &Flow, s: &KeyRotationState) -> adw::NavigationPage {
    let col = column();
    if let Some(r) = &s.refusal {
        let e = error_label();
        said_refusal(&e, Some(r));
        col.append(&e);
    }
    if s.done {
        let status = adw::StatusPage::builder().icon_name("security-high-symbolic").title(words::rotation_done_old_kit()).description(words::rotation_store_wiped()).build();
        col.append(&status);
        col.append(&text(&words::rotation_done_spaces()));
        let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
        list.add_css_class("boxed-list");
        list.set_widget_name("rotation-scopes");
        for scope in &s.scopes {
            // core names the line (inbox #979): the space's own name, else the personal space or one not opened here
            let title = match (&scope.name, &scope.name_word) {
                (Some(name), _) => name.clone(),
                (None, Some(word)) => said::key(word),
                (None, None) => words::rotation_scope_name_unavailable(),
            };
            let row = adw::ActionRow::builder().title(title).subtitle(said::key(&scope.status)).use_markup(false).build();
            if let Some(why) = &scope.blocked {
                row.set_subtitle(&format!("{} · {}", said::key(&scope.status), said::key(why)));
            }
            list.append(&row);
        }
        if !s.scopes.is_empty() {
            col.append(&list);
        }
        if !s.unreadable.is_empty() {
            col.append(&text(&words::rotation_blocked_title()));
            let blocked: Vec<String> = s.unreadable.iter().map(|_| words::rotation_blocked_unreadable()).collect();
            col.append(&facts(&blocked));
        }
    }
    let close = pill(&words::share_done(), "suggested-action");
    let d = flow.dialog.clone();
    close.connect_clicked(move |_| {
        d.close();
    });
    col.append(&close);
    page(&words::rotation_title(), "done", &col)
}

/// What the apps cannot do yet, said with the server's address (D9).
fn on_the_web(title: &str, why: &str, server: &str) -> adw::NavigationPage {
    let col = column();
    let status = adw::StatusPage::builder().icon_name("network-server-symbolic").title(why).description(words::account_on_the_web(server)).build();
    col.append(&status);
    page(title, "on-the-web", &col)
}

// ---- erase the account (D8) ----

/// "Erase your account" for the account [connection] names: the preview, the last-owner choices, the password, the danger
/// confirmation; once erased the core signs the account out here.
pub fn erasure(parent: &gtk::Widget, connection: String) {
    let flow = Flow::new(&words::erasure_title());
    flow.nav.add(&spinner_page(&words::erasure_title(), "loading"));
    let id: Id = Rc::new(Cell::new(None));
    let form: Rc<RefCell<Option<ErasureForm>>> = Rc::new(RefCell::new(None));
    let (f, i, erased) = (flow.clone(), id.clone(), Rc::new(Cell::new(false)));
    open_flow(&flow, &id, Call::ErasureOpen { connection }, |flow| Watched::Erasure { flow }, move |u| {
        let Update::Erasure { shown, .. } = u else { return true };
        // erased, the account is signed out here and the flow closed with it: the ending stays said
        let Some(s) = shown else {
            if !erased.get() {
                f.dialog.force_close();
            }
            return false;
        };
        eprintln!("veyq: erasure: loaded {}, working {}, owner {}, web {}, {} tenants, erased {}, refusal {:?}", s.loaded, s.working,
            s.server_owner, s.on_the_web, s.tenants.len(), s.erased, s.refusal);
        if s.erased {
            if !erased.replace(true) {
                let col = column();
                col.append(&adw::StatusPage::builder().icon_name("user-trash-symbolic").description(words::erasure_ended()).build());
                let close = pill(&words::share_done(), "suggested-action");
                let d = f.dialog.clone();
                close.connect_clicked(move |_| {
                    d.close();
                });
                col.append(&close);
                f.nav.replace(&[page(&words::erasure_title(), "erased", &col)]);
            }
            return true;
        }
        if !s.loaded {
            if let Some(r) = &s.refusal {
                let col = column();
                let e = error_label();
                said_refusal(&e, Some(r));
                col.append(&e);
                f.nav.replace(&[page(&words::erasure_title(), "failed", &col)]);
            }
            return true;
        }
        if s.server_owner {
            let col = column();
            col.append(&text(&words::erasure_server_owner()));
            f.nav.replace(&[page(&words::erasure_title(), "owner", &col)]);
            return true;
        }
        if s.on_the_web {
            f.nav.replace(&[on_the_web(&words::erasure_title(), &words::erasure_moderator_web(), &s.server)]);
            return true;
        }
        let mut held = form.borrow_mut();
        if held.is_none() {
            let made = ErasureForm::new(&f, &i, &s);
            f.nav.replace(std::slice::from_ref(&made.page));
            *held = Some(made);
        }
        if let Some(form) = held.as_ref() {
            form.draw(&s);
        }
        true
    });
    let i = id.clone();
    flow.present(parent, move || {
        if let Some(flow) = i.take() {
            bridge::send(Call::ErasureClose { flow });
        }
    });
}

/// The erasure's form, built once at the first preview: the facts, a choice per space this account is the last owner of
/// with other members (delete it along, or one of its members as owner), the spaces deleted along, the password, Erase.
struct ErasureForm {
    page: adw::NavigationPage,
    /// Each choice's check button: (tenant, new owner or None for "delete the space").
    choices: Vec<(String, Option<String>, gtk::CheckButton)>,
    password: adw::PasswordEntryRow,
    error: gtk::Label,
    erase: gtk::Button,
    drawing: Rc<Cell<bool>>,
    made: Rc<Cell<bool>>,
}

impl ErasureForm {
    fn new(flow: &Flow, id: &Id, s: &AccountErasureState) -> Self {
        let col = column();
        col.append(&text(&words::erasure_sub()));
        col.append(&facts(&[
            words::erasure_fact_goes(),
            words::erasure_fact_shared_stays(),
            words::erasure_fact_kept(),
            words::erasure_fact_backups(),
            words::erasure_fact_window(),
        ]));
        let drawing = Rc::new(Cell::new(false));
        let mut choices = Vec::new();
        let shared: Vec<_> = s.tenants.iter().filter(|t| !t.members.is_empty()).collect();
        if !shared.is_empty() {
            let heading = text(&words::erasure_choice_title());
            heading.add_css_class("heading");
            col.append(&heading);
            col.append(&dim(&words::erasure_choice_sub()));
            for t in shared {
                let group = adw::PreferencesGroup::builder().title(t.name.as_str()).build();
                let mut first: Option<gtk::CheckButton> = None;
                let options = std::iter::once((None, words::erasure_delete_along()))
                    .chain(t.members.iter().map(|m| (Some(m.user_id.clone()), words::erasure_make_owner(&m.name))));
                for (owner, title) in options {
                    let check = gtk::CheckButton::new();
                    if let Some(f) = &first {
                        check.set_group(Some(f));
                    } else {
                        first = Some(check.clone());
                    }
                    let row = adw::ActionRow::builder().title(title).use_markup(false).activatable_widget(&check).build();
                    row.add_prefix(&check);
                    group.add(&row);
                    let (i, d, tenant, o) = (id.clone(), drawing.clone(), t.tenant_id.clone(), owner.clone());
                    check.connect_toggled(move |c| {
                        if c.is_active() && !d.get() {
                            let (tenant_id, new_owner) = (tenant.clone(), o.clone());
                            act(&i, "Erasure.Choose", |flow| Call::ErasureChoose { flow, tenant_id, new_owner });
                        }
                    });
                    choices.push((t.tenant_id.clone(), owner, check));
                }
                col.append(&group);
            }
        }
        let sole: Vec<String> = s.tenants.iter().filter(|t| t.members.is_empty()).map(|t| t.name.clone()).collect();
        if !sole.is_empty() {
            let group = adw::PreferencesGroup::builder().title(words::erasure_sole_title()).description(words::erasure_sole_sub()).build();
            for name in sole {
                group.add(&adw::ActionRow::builder().title(name).use_markup(false).build());
            }
            col.append(&group);
        }
        let password = secret(&words::erasure_password());
        col.append(&rows(&[password.upcast_ref()]));
        let error = error_label();
        col.append(&error);
        let erase = pill(&words::erasure_action(), "destructive-action");
        erase.set_widget_name("erasure-run");
        col.append(&erase);
        let made = Rc::new(Cell::new(false));
        let (f, i, p) = (flow.clone(), id.clone(), password.clone());
        erase.connect_clicked(move |_| {
            let (i, p) = (i.clone(), p.clone());
            ask(&f.dialog, &words::erasure_confirm_title(), &words::erasure_confirm_body(), &words::erasure_action(), move || {
                let password = take(&p);
                act(&i, "Erasure.Run", |flow| Call::ErasureRun { flow, password });
            });
        });
        let (e, m) = (erase.clone(), made.clone());
        password.connect_changed(move |p| e.set_sensitive(m.get() && !p.text().is_empty()));
        Self { page: page(&words::erasure_title(), "form", &col), choices, password, error, erase, drawing, made }
    }

    fn draw(&self, s: &AccountErasureState) {
        self.drawing.set(true);
        for (tenant, owner, check) in &self.choices {
            let Some(t) = s.tenants.iter().find(|t| &t.tenant_id == tenant) else { continue };
            if t.chosen && t.new_owner_id == *owner {
                check.set_active(true);
            }
        }
        self.drawing.set(false);
        let choices_made = s.tenants.iter().all(|t| t.members.is_empty() || t.chosen);
        self.made.set(choices_made && !s.working);
        self.erase.set_sensitive(self.made.get() && !self.password.text().is_empty());
        said_refusal(&self.error, s.refusal.as_ref());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_kits_day_is_counted_from_1970() {
        let day = local_epoch_day();
        // 2026-01-01 is day 20454; a run of this test is after it
        assert!(day > 20_454, "{day}");
        let today = glib::DateTime::now_local().expect("a local time");
        let back = glib::DateTime::from_unix_utc(day * 86_400).expect("a day");
        assert_eq!((back.year(), back.month(), back.day_of_month()), (today.year(), today.month(), today.day_of_month()));
    }
}
