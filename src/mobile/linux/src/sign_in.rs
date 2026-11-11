//! The way in (ADR 0123's Consequences, the Linux app): what the core's `SessionModel.Gate` says — opening, the full
//! sign-in with the Secret Key (every start, until the VM slice brings the Secret Service) — drawn as GNOME draws a form:
//! a clamped boxed list of entry rows and one suggested button. The person's input goes to the core as one call; the
//! core decides what it means and answers with the next gate.
use adw::prelude::*;

use crate::bridge::{self, wire::Call, wire::Gate};
use crate::{said, theme, words};

/// The sign-in page: built once, given each gate the core sends.
pub struct SignIn {
    pub page: adw::ToolbarView,
    server: adw::EntryRow,
    email: adw::EntryRow,
    password: adw::PasswordEntryRow,
    secret_key: adw::PasswordEntryRow,
    submit: gtk::Button,
    spinner: adw::Spinner,
    error: gtk::Label,
}

impl SignIn {
    pub fn new() -> Self {
        let server = adw::EntryRow::new();
        server.set_title(&words::signin_server());
        server.set_input_purpose(gtk::InputPurpose::Url);
        let email = adw::EntryRow::new();
        email.set_title(&words::signin_email());
        email.set_input_purpose(gtk::InputPurpose::Email);
        let password = adw::PasswordEntryRow::new();
        password.set_title(&words::signin_password());
        let secret_key = adw::PasswordEntryRow::new();
        secret_key.set_title(&words::signin_secret_key());

        let fields = adw::PreferencesGroup::new();
        for row in [server.upcast_ref::<gtk::Widget>(), email.upcast_ref(), password.upcast_ref(), secret_key.upcast_ref()] {
            fields.add(row);
        }
        let error = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
        error.add_css_class("error");
        let submit = gtk::Button::builder().label(words::signin_submit()).halign(gtk::Align::Center).build();
        submit.add_css_class("suggested-action");
        submit.add_css_class("pill");
        let spinner = adw::Spinner::builder().visible(false).build();

        let column = gtk::Box::new(gtk::Orientation::Vertical, 18);
        column.set_margin_top(24);
        column.set_margin_bottom(24);
        column.set_margin_start(12);
        column.set_margin_end(12);
        let brand = gtk::Image::from_icon_name(theme::APP_ID);
        brand.set_pixel_size(96);
        let heading = gtk::Label::builder().label(words::signin_title()).build();
        heading.add_css_class("title-1");
        column.append(&brand);
        column.append(&heading);
        column.append(&fields);
        column.append(&error);
        column.append(&submit);
        column.append(&spinner);
        let clamp = adw::Clamp::builder().maximum_size(420).child(&column).build();
        let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).child(&clamp).build();
        let page = adw::ToolbarView::new();
        page.add_top_bar(&adw::HeaderBar::builder().show_title(false).build());
        page.set_content(Some(&scroll));

        let s = Self { page, server, email, password, secret_key, submit, spinner, error };
        let ask = {
            let (server, email, password, secret_key) = (s.server.clone(), s.email.clone(), s.password.clone(), s.secret_key.clone());
            move || {
                bridge::send(Call::SessionSignIn {
                    server: server.text().trim().to_string(),
                    email: email.text().trim().to_string(),
                    password: password.text().to_string(),
                    secret_key: secret_key.text().trim().to_string(),
                    remember: false, // "Stay signed in" is Phase 4's switch
                });
            }
        };
        let on_click = ask.clone();
        s.submit.connect_clicked(move |_| on_click());
        // Enter in the last field signs in, as a form's default button does
        s.secret_key.connect_entry_activated(move |_| ask());
        s
    }

    /// Draws [gate] (a `Gate.SignIn`): the server and the email it knows, working or not, the core's error.
    pub fn show(&self, gate: &Gate) {
        let Gate::SignIn { working, error, server, email, .. } = gate else { return };
        if let Some(server) = server.as_deref().filter(|_| self.server.text().is_empty()) {
            self.server.set_text(server);
        }
        if let Some(email) = email.as_deref().filter(|_| self.email.text().is_empty()) {
            self.email.set_text(email);
        }
        self.submit.set_sensitive(!working);
        self.spinner.set_visible(*working);
        match error {
            Some(key) => {
                self.error.set_label(&said::key(key));
                self.error.set_visible(true);
            }
            None => self.error.set_visible(false),
        }
    }
}

impl Default for SignIn {
    fn default() -> Self {
        Self::new()
    }
}
