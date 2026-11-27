//! Joining a space from an invite (uc.spaces.add-member's invitee; the web's `TenantJoinView` over core's `TenantJoin`,
//! ADR 0111, ADR 0129), GNOME's way: an `AdwDialog` over the window, as the account's flows are, that draws the join's
//! phases (`TenantJoin.State`, watched as `Watched.Join`) one page at a time — the invite read, or why it is none; the
//! code (or the owner's password) typed; a new account's handle, name, address (a link invite's) and password with the
//! breached-password check, then its Emergency Kit saved as every kit is ([account_flows::kit_page]: the save dialog, the
//! core writing into the file chosen, *I saved it*) before *Accept & join*; an existing account's sign-in with its
//! password and Secret Key (both masked, the key's typo said as the sign-in says it); and the outcome with *Open the
//! space*, which signs the app in as any sign-in does (`Join.Open`; the shell follows the session's gate). The join is
//! opened by *Open a link* (`Received.Open` answering `JoinOpened`, [crate::core_ui::received::open_link]), never by a
//! `veyq:` link. Its error stands under the form and a busy join disables it; closing the dialog lets the join go
//! (`Join.Close`, which zeroes every secret it held). Every word and rule is the core's; a typed secret is read out of
//! its row when sent, nothing of it kept here. Linux keeps no snapshot of a window (src/mobile/CLAUDE.md § Secrets on
//! screen).
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, wire::Answer, wire::BreachKind, wire::Call, wire::Mode, wire::Phase, wire::SecretKeyProblem, wire::TenantJoinState, wire::Update, wire::Watched};
use crate::core_ui::account_flows::{self, Flow};
use crate::core_ui::{said, words};

/// The least a new account's password may be, in characters (`PasswordChange.MIN_PASSWORD_LENGTH`).
const MIN_PASSWORD: usize = 10;

/// The widget name of the join's dialog.
pub const JOIN_DIALOG: &str = "join";

/// Join [join] (a `JoinOpened` id) drawn in a dialog over [parent]; its close lets the join go.
pub fn present(join: i64, parent: &gtk::Widget) {
    let flow = Flow::new(&words::join_title());
    flow.dialog.set_widget_name(JOIN_DIALOG);
    let j = Rc::new(Join { id: join, flow: flow.clone(), phase: Cell::new(None), kit: Cell::new(None), kit_drawn: Cell::new(false), texts: RefCell::default(), parts: RefCell::default(), state: RefCell::new(None) });
    flow.present(parent, move || bridge::send(Call::JoinClose { join }));
    j.show_phase(Phase::Loading);
    let weak = Rc::downgrade(&j);
    flow.watch(&Watched::Join { join }, move |u| {
        let Update::Join { shown, .. } = u else { return true };
        let Some(j) = weak.upgrade() else { return false };
        match shown {
            Some(s) => {
                j.draw(s);
                true
            }
            None => {
                eprintln!("veyq: join: closed");
                false
            }
        }
    });
    // the dialog holds the join until it closes (a dialog's state only weak handlers reach is dropped at once, § Traps)
    let held = RefCell::new(Some(j));
    flow.dialog.connect_closed(move |_| {
        held.borrow_mut().take();
    });
}

/// What the person typed that is no secret, kept across the join's pages (a link invite's switch between a new account and
/// one they have keeps them, as the web's form does).
#[derive(Default)]
struct Texts {
    username: String,
    first: String,
    last: String,
    email: String,
}

/// The page shown: what each update of the same phase changes.
#[derive(Default)]
struct Parts {
    error: Option<gtk::Label>,
    spinner: Option<adw::Spinner>,
    breach: Option<Breach>,
    open: Option<gtk::Button>,
}

/// A new account's password with the breached-password check (ADR 0151 decision 5): checked once the row is left, its
/// verdict shown only while the row still holds the password checked.
#[derive(Clone)]
struct Breach {
    row: adw::PasswordEntryRow,
    notice: gtk::Label,
    anyway: gtk::CheckButton,
    checked: Rc<RefCell<String>>,
    quiet: Rc<Cell<bool>>,
}

struct Join {
    id: i64,
    flow: Flow,
    phase: Cell<Option<Phase>>,
    /// The new account's kit step (`Join.Register`'s `KitOpened`): the join's, closed with it, never by `Kit.Close`.
    kit: Cell<Option<i64>>,
    kit_drawn: Cell<bool>,
    texts: RefCell<Texts>,
    parts: RefCell<Parts>,
    state: RefCell<Option<TenantJoinState>>,
}

impl Join {
    fn draw(self: &Rc<Self>, s: TenantJoinState) {
        let phase = s.phase;
        *self.state.borrow_mut() = Some(s.clone());
        eprintln!("veyq: join: {phase:?}, busy {}, error {:?}, can open {}", s.busy, s.error, s.can_open);
        if self.phase.get() != Some(phase) || (phase == Phase::Kit && !self.kit_drawn.get()) {
            self.show_phase(phase);
        }
        let parts = self.parts.borrow();
        if let Some(e) = &parts.error {
            e.set_visible(s.error.is_some());
            e.set_label(&s.error.as_deref().map(said::key).unwrap_or_default());
        }
        if let Some(sp) = &parts.spinner {
            sp.set_visible(s.busy);
        }
        if let Some(b) = &parts.breach {
            b.draw(&s);
        }
        if let Some(o) = &parts.open {
            o.set_visible(s.can_open);
        }
        self.flow.nav.set_sensitive(!s.busy);
    }

    /// The page of [phase], drawn anew.
    fn show_phase(self: &Rc<Self>, phase: Phase) {
        let s = self.state.borrow().clone();
        let mut parts = Parts::default();
        let page = match (phase, s) {
            (Phase::Kit, Some(s)) => {
                let Some(kit) = self.kit.get() else { return };
                self.kit_drawn.set(true);
                let error = error_label();
                let finish = gtk::Button::builder().label(said::key(&s.join_word)).halign(gtk::Align::Center).build();
                finish.add_css_class("pill");
                finish.add_css_class("suggested-action");
                finish.set_widget_name("join-finish");
                let id = self.id;
                finish.connect_clicked(move |_| act("Join.Finish", Call::JoinFinish { join: id }));
                let extra = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).build();
                let spinner = adw::Spinner::builder().visible(false).build();
                extra.append(&spinner);
                extra.append(&error);
                parts.error = Some(error);
                parts.spinner = Some(spinner);
                let p = account_flows::kit_page(&self.flow, kit, Some(&words::join_save_kit_sub()), Some(extra.upcast_ref()), Some(&finish));
                p.set_title(&words::join_save_kit_title());
                p
            }
            (Phase::Loading, _) | (_, None) => {
                let col = column();
                let spinner = adw::Spinner::builder().width_request(32).height_request(32).halign(gtk::Align::Center).build();
                col.append(&spinner);
                col.append(&dim(&words::join_loading()));
                account_flows::page(&words::join_title(), "join-loading", &col)
            }
            (Phase::Invalid, _) => said_page("join-invalid", &words::join_invalid(), true),
            (Phase::Expired, _) => said_page("join-expired", &words::join_expired(), false),
            (Phase::Redeemed, _) => said_page("join-redeemed", &words::join_redeemed(), false),
            (Phase::Code, Some(s)) => self.code_page(&s, &mut parts),
            (Phase::Register, Some(s)) => self.register_page(&s, &mut parts),
            (Phase::Signin, Some(s)) => self.sign_in_page(&s, &mut parts),
            (Phase::Done, Some(s)) => self.done_page(&s, &mut parts),
        };
        self.phase.set(Some(phase));
        *self.parts.borrow_mut() = parts;
        self.flow.nav.replace(&[page]);
    }

    fn code_page(self: &Rc<Self>, s: &TenantJoinState, parts: &mut Parts) -> adw::NavigationPage {
        let col = column();
        col.append(&text(&said::notice(&s.intro)));
        col.append(&dim(&said::key(&s.code_sub)));
        let label = said::key(&s.code_label);
        let row: gtk::Widget = if s.code_mode == Mode::Code {
            adw::EntryRow::builder().title(label.as_str()).build().upcast()
        } else {
            adw::PasswordEntryRow::builder().title(label.as_str()).build().upcast()
        };
        row.set_widget_name("join-code");
        col.append(&rows(&[&row]));
        let error = error_label();
        col.append(&error);
        let spinner = adw::Spinner::builder().visible(false).build();
        col.append(&spinner);
        let go = pill(&words::join_continue(), "suggested-action");
        go.set_widget_name("join-code-continue");
        go.set_sensitive(false);
        col.append(&go);
        let editable = row.clone().downcast::<gtk::Editable>().ok();
        if let Some(e) = &editable {
            let g = go.clone();
            e.connect_changed(move |e| g.set_sensitive(!e.text().trim().is_empty()));
        }
        let id = self.id;
        let send = Rc::new(move || {
            let Some(e) = &editable else { return };
            let code = e.text().to_string();
            if !code.trim().is_empty() {
                act("Join.Code", Call::JoinCode { join: id, code });
            }
        });
        let s2 = send.clone();
        go.connect_clicked(move |_| s2());
        if let Ok(r) = row.downcast::<adw::EntryRow>() {
            r.connect_entry_activated(move |_| send());
        }
        parts.error = Some(error);
        parts.spinner = Some(spinner);
        account_flows::page(&words::join_title(), "join-code", &col)
    }

    fn register_page(self: &Rc<Self>, s: &TenantJoinState, parts: &mut Parts) -> adw::NavigationPage {
        let col = column();
        col.append(&text(&said::notice(&s.intro)));
        col.append(&dim(&said::notice(&s.register_sub)));
        let details = self.details(s, &col);
        let password = adw::PasswordEntryRow::builder().title(words::signin_password()).build();
        password.set_widget_name("join-password");
        col.append(&rows(&[password.upcast_ref()]));
        let breach = Breach::new(self.id, &password);
        col.append(&breach.notice);
        col.append(&breach.anyway);
        let error = error_label();
        col.append(&error);
        let spinner = adw::Spinner::builder().visible(false).build();
        col.append(&spinner);
        let go = pill(&words::join_continue(), "suggested-action");
        go.set_widget_name("join-register");
        col.append(&go);
        let asks_email = s.asks_email;
        let check = {
            let (g, d, p) = (go.clone(), details.clone(), password.clone());
            Rc::new(move || g.set_sensitive(d.username_given() && p.text().chars().count() >= MIN_PASSWORD && (!asks_email || d.email_given())))
        };
        check();
        details.on_change(check.clone());
        let c = check.clone();
        password.connect_changed(move |_| c());
        let (id, d, p, me, checked) = (self.id, details.clone(), password.clone(), Rc::downgrade(self), breach.checked.clone());
        go.connect_clicked(move |_| {
            let t = d.texts();
            // the core checks a password not checked yet before it builds anything; its verdict is this one's
            *checked.borrow_mut() = p.text().to_string();
            let call = Call::JoinRegister { join: id, username: t.username, first_name: t.first, last_name: t.last, email: t.email, password: p.text().to_string() };
            let me = me.clone();
            glib::spawn_future_local(async move {
                match bridge::call(&call).await.map(|(a, _)| a) {
                    Ok(Answer::KitOpened { kit }) => {
                        eprintln!("veyq: join: Join.Register: KitOpened");
                        if let Some(j) = me.upgrade() {
                            j.kit.set(Some(kit));
                            if j.state.borrow().as_ref().is_some_and(|s| s.phase == Phase::Kit) && !j.kit_drawn.get() {
                                j.show_phase(Phase::Kit);
                            }
                        }
                    }
                    other => eprintln!("veyq: join: Join.Register: {}", crate::core_ui::manage::answer_kind(&other)),
                }
            });
        });
        if s.offers_switch {
            let switch = gtk::Button::builder().label(words::join_have_account()).halign(gtk::Align::Center).build();
            switch.add_css_class("flat");
            switch.set_widget_name("join-have-account");
            let id = self.id;
            switch.connect_clicked(move |_| act("Join.Choose", Call::JoinChoose { join: id, register: false }));
            col.append(&switch);
        }
        parts.error = Some(error);
        parts.spinner = Some(spinner);
        parts.breach = Some(breach);
        account_flows::page(&words::join_title(), "join-register", &col)
    }

    fn sign_in_page(self: &Rc<Self>, s: &TenantJoinState, parts: &mut Parts) -> adw::NavigationPage {
        let col = column();
        col.append(&text(&said::notice(&s.intro)));
        col.append(&dim(&said::notice(&s.signin_sub)));
        let details = self.details(s, &col);
        let password = adw::PasswordEntryRow::builder().title(words::signin_password()).build();
        password.set_widget_name("join-password");
        let key = adw::PasswordEntryRow::builder().title(words::signin_secret_key()).build();
        key.set_widget_name("join-secret-key");
        col.append(&rows(&[password.upcast_ref(), key.upcast_ref()]));
        let typo = error_label();
        typo.set_label(&words::secret_key_typo());
        col.append(&typo);
        let error = error_label();
        col.append(&error);
        let spinner = adw::Spinner::builder().visible(false).build();
        col.append(&spinner);
        let go = pill(&said::key(&s.join_word), "suggested-action");
        go.set_widget_name("join-sign-in");
        col.append(&go);
        let (asks_username, asks_email) = (s.asks_username, s.asks_email);
        let check = {
            let (g, d, p, k) = (go.clone(), details.clone(), password.clone(), key.clone());
            Rc::new(move || {
                g.set_sensitive((!asks_username || d.username_given()) && !p.text().is_empty() && !k.text().is_empty() && (!asks_email || d.email_given()))
            })
        };
        check();
        details.on_change(check.clone());
        let c = check.clone();
        password.connect_changed(move |_| c());
        let t = typo.clone();
        key.connect_changed(move |k| {
            check();
            let (t, typed) = (t.clone(), k.text().to_string());
            if typed.is_empty() {
                return t.set_visible(false);
            }
            // the sign-in's typo line (`SecretKey.Check`): the core judges the text as typed
            glib::spawn_future_local(async move {
                if let Ok((Answer::SecretKeyChecked { problem }, _)) = bridge::call(&Call::SecretKeyCheck { secret_key: typed }).await {
                    t.set_visible(problem == Some(SecretKeyProblem::Typo));
                }
            });
        });
        let (id, d, p, k) = (self.id, details.clone(), password.clone(), key.clone());
        go.connect_clicked(move |_| {
            let t = d.texts();
            act("Join.SignIn", Call::JoinSignIn { join: id, username: t.username, first_name: t.first, last_name: t.last, email: t.email, password: take(&p), secret_key: take(&k) });
        });
        if s.offers_switch {
            let switch = gtk::Button::builder().label(words::join_new_account()).halign(gtk::Align::Center).build();
            switch.add_css_class("flat");
            switch.set_widget_name("join-new-account");
            let id = self.id;
            switch.connect_clicked(move |_| act("Join.Choose", Call::JoinChoose { join: id, register: true }));
            col.append(&switch);
        }
        parts.error = Some(error);
        parts.spinner = Some(spinner);
        account_flows::page(&words::join_title(), "join-signin", &col)
    }

    fn done_page(self: &Rc<Self>, s: &TenantJoinState, parts: &mut Parts) -> adw::NavigationPage {
        let col = column();
        let status = gtk::Box::builder().spacing(12).build();
        status.append(&gtk::Image::from_icon_name("object-select-symbolic"));
        let done = text(&said::notice(&s.done));
        done.set_widget_name("join-done");
        status.append(&done);
        col.append(&status);
        if let Some(to) = &s.confirmation_sent_to {
            col.append(&text(&words::join_confirmation_sent(to)));
        }
        col.append(&dim(&said::key(&s.done_note)));
        let error = error_label();
        col.append(&error);
        let spinner = adw::Spinner::builder().visible(false).build();
        col.append(&spinner);
        let open = pill(&words::join_open_space(), "suggested-action");
        open.set_widget_name("join-open-space");
        open.set_visible(s.can_open);
        let (id, dialog) = (self.id, self.flow.dialog.clone());
        open.connect_clicked(move |_| {
            let dialog = dialog.clone();
            glib::spawn_future_local(async move {
                // the app's own session signs in with what the join holds; the shell follows the session's gate
                match bridge::call(&Call::JoinOpen { join: id }).await.map(|(a, _)| a) {
                    Ok(Answer::Done) => {
                        eprintln!("veyq: join: Join.Open: Done");
                        dialog.close();
                    }
                    other => eprintln!("veyq: join: Join.Open: {}", crate::core_ui::manage::answer_kind(&other)),
                }
            });
        });
        col.append(&open);
        parts.error = Some(error);
        parts.spinner = Some(spinner);
        parts.open = Some(open);
        account_flows::page(&words::join_title(), "join-done", &col)
    }

    /// The handle in this space, the real name and — for a link invite — the address, as the step asks for them, each
    /// filled from what was typed before.
    fn details(self: &Rc<Self>, s: &TenantJoinState, col: &gtk::Box) -> Details {
        let t = self.texts.borrow();
        let entry = |title: String, name: &str, value: &str| {
            let r = adw::EntryRow::builder().title(title).text(value).build();
            r.set_widget_name(name);
            r
        };
        let d = Details {
            username: s.asks_username.then(|| entry(words::join_username(), "join-username", &t.username)),
            first: s.asks_username.then(|| entry(words::profile_first_name(), "join-first-name", &t.first)),
            last: s.asks_username.then(|| entry(words::profile_last_name(), "join-last-name", &t.last)),
            email: s.asks_email.then(|| {
                let r = entry(words::signin_email(), "join-email", &t.email);
                r.set_input_purpose(gtk::InputPurpose::Email);
                r
            }),
        };
        drop(t);
        if let (Some(u), Some(f), Some(l)) = (&d.username, &d.first, &d.last) {
            let handle = adw::PreferencesGroup::builder().description(words::join_username_hint()).build();
            handle.add(u);
            col.append(&handle);
            let name = adw::PreferencesGroup::builder().description(words::join_name_hint()).build();
            name.add(f);
            name.add(l);
            col.append(&name);
        }
        if let Some(e) = &d.email {
            col.append(&rows(&[e.upcast_ref()]));
        }
        let me = Rc::downgrade(self);
        let d2 = d.clone();
        d.on_change(Rc::new(move || {
            if let Some(j) = me.upgrade() {
                *j.texts.borrow_mut() = d2.texts();
            }
        }));
        d
    }
}

/// The rows [Join::details] drew.
#[derive(Clone)]
struct Details {
    username: Option<adw::EntryRow>,
    first: Option<adw::EntryRow>,
    last: Option<adw::EntryRow>,
    email: Option<adw::EntryRow>,
}

impl Details {
    fn texts(&self) -> Texts {
        let of = |r: &Option<adw::EntryRow>| r.as_ref().map(|r| r.text().to_string()).unwrap_or_default();
        Texts { username: of(&self.username), first: of(&self.first), last: of(&self.last), email: of(&self.email) }
    }

    fn username_given(&self) -> bool {
        self.username.as_ref().is_some_and(|r| !r.text().trim().is_empty())
    }

    fn email_given(&self) -> bool {
        self.email.as_ref().is_some_and(|r| !r.text().trim().is_empty())
    }

    fn on_change(&self, f: Rc<dyn Fn()>) {
        for r in [&self.username, &self.first, &self.last, &self.email].into_iter().flatten() {
            let f = f.clone();
            r.connect_changed(move |_| f());
        }
    }
}

impl Breach {
    fn new(join: i64, row: &adw::PasswordEntryRow) -> Self {
        let notice = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
        let anyway = gtk::CheckButton::builder().label(words::password_pwned_anyway()).visible(false).build();
        anyway.set_widget_name("join-use-anyway");
        let b = Breach { row: row.clone(), notice, anyway, checked: Rc::default(), quiet: Rc::new(Cell::new(false)) };
        let (r, checked) = (row.clone(), b.checked.clone());
        let leaving = gtk::EventControllerFocus::new();
        leaving.connect_leave(move |_| {
            let typed = r.text().to_string();
            // checked once the field is left, never while typing (ADR 0151 decision 5)
            if !typed.is_empty() && *checked.borrow() != typed {
                *checked.borrow_mut() = typed.clone();
                act("Join.CheckPassword", Call::JoinCheckPassword { join, password: typed });
            }
        });
        row.add_controller(leaving);
        let (n, a, r, checked) = (b.notice.clone(), b.anyway.clone(), row.clone(), b.checked.clone());
        row.connect_changed(move |_| {
            // the verdict stands only beside the password it was given on
            let same = *checked.borrow() == r.text().as_str();
            n.set_visible(same && !n.label().is_empty());
            a.set_visible(same && a.is_sensitive());
        });
        let quiet = b.quiet.clone();
        b.anyway.connect_toggled(move |c| {
            if !quiet.get() {
                act("Join.UseAnyway", Call::JoinUseAnyway { join, on: c.is_active() });
            }
        });
        b
    }

    /// The check's verdict in [s], drawn while the row still holds the password checked.
    fn draw(&self, s: &TenantJoinState) {
        let kind = s.breach.kind;
        let said = match kind {
            BreachKind::Hit | BreachKind::Accepted => Some(words::password_pwned_warning(i64::from(s.breach.count))),
            BreachKind::Unavailable => Some(words::password_pwned_unavailable()),
            _ => None,
        };
        let same = *self.checked.borrow() == self.row.text().as_str();
        self.notice.set_label(said.as_deref().unwrap_or_default());
        self.notice.set_visible(same && said.is_some());
        if kind == BreachKind::Unavailable { self.notice.add_css_class("dim-label") } else { self.notice.remove_css_class("dim-label") }
        if kind == BreachKind::Hit { self.notice.add_css_class("warning") } else { self.notice.remove_css_class("warning") }
        let offered = matches!(kind, BreachKind::Hit | BreachKind::Accepted);
        // sensitivity marks the offer, so an edit hides it and a return to the checked text shows it again
        self.anyway.set_sensitive(offered);
        self.anyway.set_visible(same && offered);
        self.quiet.set(true);
        self.anyway.set_active(kind == BreachKind::Accepted);
        self.quiet.set(false);
    }
}

/// Sends [call], its answer's kind to stderr as [what].
fn act(what: &'static str, call: Call) {
    glib::spawn_future_local(async move {
        let answered = bridge::call(&call).await.map(|(a, _)| a);
        eprintln!("veyq: join: {what}: {}", crate::core_ui::manage::answer_kind(&answered));
    });
}

/// What a secret row holds, read once and the row emptied.
fn take(row: &impl IsA<gtk::Editable>) -> String {
    let typed = row.text().to_string();
    row.set_text("");
    typed
}

fn said_page(tag: &str, words: &str, error: bool) -> adw::NavigationPage {
    let col = column();
    let l = text(words);
    l.set_widget_name(tag);
    if error {
        l.add_css_class("error");
    }
    col.append(&l);
    account_flows::page(&crate::core_ui::words::join_title(), tag, &col)
}

fn column() -> gtk::Box {
    gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).build()
}

fn rows(of: &[&gtk::Widget]) -> gtk::ListBox {
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("boxed-list");
    for r in of {
        list.append(*r);
    }
    list
}

fn text(words: &str) -> gtk::Label {
    gtk::Label::builder().label(words).wrap(true).xalign(0.0).build()
}

fn dim(words: &str) -> gtk::Label {
    let l = text(words);
    l.add_css_class("dim-label");
    l
}

fn error_label() -> gtk::Label {
    let l = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
    l.add_css_class("error");
    l
}

fn pill(words: &str, css: &str) -> gtk::Button {
    let b = gtk::Button::builder().label(words).halign(gtk::Align::Center).build();
    b.add_css_class("pill");
    if !css.is_empty() {
        b.add_css_class(css);
    }
    b
}
