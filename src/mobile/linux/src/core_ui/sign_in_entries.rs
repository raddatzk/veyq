//! The sign-in's two entries the web has (ADR 0154 § As built: the plans aligned, P9), each its own way-in page drawn
//! as the sign-in is: the locked sign-in ([Locked], `Gate.Locked`, uc.account.sessions E3) — signed in without unlocking,
//! one action, every device signed out, asked first in an `AdwAlertDialog`, and the way back — and *Reset your password*
//! ([Recover], `Gate.Recover`, uc.account.recover-access steps 1–2) — the link asked for; the mailed link opens the web's
//! page, which finishes the reset. The entries under each are the core's (`Locked.Plan`, `Recover.Plan`), drawn whole and
//! in their order. The core decides what each press means and answers with the next gate.
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, wire::Answer, wire::Call, wire::Gate, wire::LockedEntry, wire::RecoverEntry};
use crate::core_ui::sign_in::{error_label, page, show_error};
use crate::core_ui::words;

fn note(text: &str, dim: bool) -> gtk::Label {
    let label = gtk::Label::builder().label(text).wrap(true).xalign(0.0).build();
    if dim {
        label.add_css_class("dim-label");
    }
    label
}

/// Signed in, still locked: what this state is, that this device keeps nothing, then the core's entries.
pub struct Locked {
    pub page: adw::ToolbarView,
    email: gtk::Label,
    sign_out: gtk::Button,
    back: gtk::Button,
    back_hint: gtk::Label,
    entries: gtk::Box,
    spinner: adw::Spinner,
    error: gtk::Label,
    drawn: Cell<bool>,
}

impl Locked {
    pub fn new() -> Rc<Self> {
        let email = note("", false);
        email.add_css_class("heading");
        let sign_out = gtk::Button::builder().label(words::locked_action()).halign(gtk::Align::Center).build();
        sign_out.add_css_class("destructive-action");
        sign_out.add_css_class("pill");
        sign_out.set_widget_name("locked-sign-out-everywhere");
        let back = gtk::Button::builder().label(words::locked_back()).halign(gtk::Align::Center).build();
        back.add_css_class("flat");
        back.set_widget_name("locked-back");
        let back_hint = note(&words::locked_back_hint(), true);
        back_hint.set_justify(gtk::Justification::Center);
        back_hint.set_xalign(0.5);
        let entries = gtk::Box::new(gtk::Orientation::Vertical, 12);
        entries.set_widget_name("locked-entries");
        let spinner = adw::Spinner::builder().visible(false).build();
        let error = error_label();
        error.set_widget_name("locked-error");
        let heading = gtk::Label::new(Some(&words::locked_title()));
        let page = page(
            &heading,
            &[
                email.upcast_ref(),
                note(&words::locked_note(), false).upcast_ref(),
                note(&words::locked_scope_note(), true).upcast_ref(),
                error.upcast_ref(),
                spinner.upcast_ref(),
                entries.upcast_ref(),
            ],
        );
        let s = Rc::new(Self { page, email, sign_out, back, back_hint, entries, spinner, error, drawn: Cell::new(false) });
        let w = Rc::downgrade(&s);
        s.sign_out.connect_clicked(move |_| if let Some(s) = w.upgrade() {
            s.ask();
        });
        s.back.connect_clicked(|_| bridge::send(Call::SessionLockedBack));
        s
    }

    /// The web's question first (`sessions.lockedConfirm`), the destructive answer not the default; then the core signs out.
    fn ask(&self) {
        let ask = adw::AlertDialog::builder().heading(words::locked_confirm_title()).body(words::locked_confirm()).build();
        ask.add_responses(&[("cancel", &words::signin_cancel()), ("accept", &words::locked_action())]);
        ask.set_response_appearance("accept", adw::ResponseAppearance::Destructive);
        ask.set_default_response(Some("cancel"));
        ask.set_close_response("cancel");
        ask.connect_response(None, |_, response| {
            if response == "accept" {
                bridge::send(Call::SessionSignOutEverywhere);
            }
        });
        ask.present(self.page.root().as_ref());
    }

    /// The core's entries (`Locked.Plan`), asked once: the plan has no input.
    fn ask_plan(self: &Rc<Self>) {
        if self.drawn.replace(true) {
            return;
        }
        let w = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            match bridge::call(&Call::LockedPlan).await.map(|(a, _)| a) {
                Ok(Answer::LockedPlan { entries }) => {
                    let Some(s) = w.upgrade() else { return };
                    for entry in entries {
                        match entry {
                            LockedEntry::SignOutEverywhere => s.entries.append(&s.sign_out),
                            LockedEntry::Back => {
                                s.entries.append(&s.back);
                                s.entries.append(&s.back_hint);
                            }
                        }
                    }
                }
                other => eprintln!("veyq: Locked.Plan answered {other:?}"),
            }
        });
    }

    /// Draws [gate] (a `Gate.Locked`): the account, working or not, the core's error.
    pub fn show(self: &Rc<Self>, gate: &Gate) {
        let Gate::Locked { email, working, error, .. } = gate else { return };
        self.ask_plan();
        self.email.set_label(email);
        self.sign_out.set_sensitive(!working);
        self.back.set_sensitive(!working);
        self.spinner.set_visible(*working);
        show_error(&self.error, error);
    }
}

/// *Reset your password*: the intro, the server and the address, the answer and what follows it, the core's entries, and
/// that nothing helps without the kit.
pub struct Recover {
    pub page: adw::ToolbarView,
    server: adw::EntryRow,
    email: adw::EntryRow,
    fields: adw::PreferencesGroup,
    send: gtk::Button,
    back: gtk::Button,
    sent: gtk::Box,
    entries: gtk::Box,
    spinner: adw::Spinner,
    error: gtk::Label,
    /// The `sent` the plan was last asked with, and the gate last shown (so its server and address fill the form once).
    asked: Cell<Option<bool>>,
    shown: RefCell<Option<(Option<String>, Option<String>)>>,
}

impl Recover {
    pub fn new() -> Rc<Self> {
        let server = adw::EntryRow::new();
        server.set_title(&words::signin_server());
        server.set_input_purpose(gtk::InputPurpose::Url);
        let email = adw::EntryRow::new();
        email.set_title(&words::recovery_email());
        email.set_input_purpose(gtk::InputPurpose::Email);
        email.set_widget_name("recover-email");
        let fields = adw::PreferencesGroup::new();
        fields.add(&server);
        fields.add(&email);
        let send = gtk::Button::builder().label(words::recovery_send()).halign(gtk::Align::Center).build();
        send.add_css_class("suggested-action");
        send.add_css_class("pill");
        send.set_widget_name("recover-send");
        let back = gtk::Button::builder().label(words::back()).halign(gtk::Align::Center).build();
        back.add_css_class("flat");
        back.set_widget_name("recover-back");
        let sent = gtk::Box::new(gtk::Orientation::Vertical, 6);
        let sent_label = note(&words::recovery_sent(), false);
        sent_label.add_css_class("success");
        sent_label.set_widget_name("recover-sent");
        sent.append(&sent_label);
        sent.append(&note(&words::recovery_on_web(), false));
        sent.set_visible(false);
        let entries = gtk::Box::new(gtk::Orientation::Vertical, 12);
        entries.set_widget_name("recover-entries");
        let spinner = adw::Spinner::builder().visible(false).build();
        let error = error_label();
        error.set_widget_name("recover-error");
        let heading = gtk::Label::new(Some(&words::recovery_title()));
        let page = page(
            &heading,
            &[
                note(&words::recovery_intro(), false).upcast_ref(),
                fields.upcast_ref(),
                sent.upcast_ref(),
                error.upcast_ref(),
                spinner.upcast_ref(),
                entries.upcast_ref(),
                note(&words::recovery_no_escrow(), true).upcast_ref(),
            ],
        );
        let s = Rc::new(Self {
            page,
            server,
            email,
            fields,
            send,
            back,
            sent,
            entries,
            spinner,
            error,
            asked: Cell::new(None),
            shown: RefCell::new(None),
        });
        let (server, email) = (s.server.clone(), s.email.clone());
        let ask = move || {
            bridge::send(Call::SessionRequestRecovery { server: server.text().trim().to_string(), email: email.text().trim().to_string() })
        };
        let on_click = ask.clone();
        s.send.connect_clicked(move |_| on_click());
        s.email.connect_entry_activated(move |_| ask());
        s.back.connect_clicked(|_| bridge::send(Call::SessionBackToSignIn));
        s
    }

    /// The core's entries for [sent] (`Recover.Plan`), drawn once answered.
    fn ask_plan(self: &Rc<Self>, sent: bool) {
        if self.asked.replace(Some(sent)) == Some(sent) {
            return;
        }
        let w = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            match bridge::call(&Call::RecoverPlan { sent }).await.map(|(a, _)| a) {
                Ok(Answer::RecoverPlan { entries }) => {
                    let Some(s) = w.upgrade() else { return };
                    while let Some(child) = s.entries.first_child() {
                        s.entries.remove(&child);
                    }
                    for entry in entries {
                        match entry {
                            RecoverEntry::Send => s.entries.append(&s.send),
                            RecoverEntry::Back => s.entries.append(&s.back),
                        }
                    }
                }
                other => eprintln!("veyq: Recover.Plan answered {other:?}"),
            }
        });
    }

    /// Draws [gate] (a `Gate.Recover`): the server and the address it came with (once per arrival), sent or not, working
    /// or not, the core's error.
    pub fn show(self: &Rc<Self>, gate: &Gate) {
        let Gate::Recover { server, email, working, sent, error, .. } = gate else { return };
        let arrived = (server.clone(), email.clone());
        if !*working && !*sent && error.is_none() && self.shown.borrow().as_ref() != Some(&arrived) {
            self.server.set_text(server.as_deref().unwrap_or(""));
            self.email.set_text(email.as_deref().unwrap_or(""));
        }
        *self.shown.borrow_mut() = Some(arrived);
        self.ask_plan(*sent);
        self.fields.set_sensitive(!*sent && !*working);
        self.send.set_sensitive(!working);
        self.back.set_sensitive(!working);
        self.sent.set_visible(*sent);
        self.spinner.set_visible(*working);
        show_error(&self.error, error);
    }
}
