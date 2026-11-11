//! The way in (ADR 0123's Consequences, the Linux app): what the core's `SessionModel.Gate` says — the full sign-in with
//! the Secret Key ([SignIn]) and the unlock of an account this device trusts ([Unlock]) — drawn as GNOME draws a form: a
//! clamped boxed list of rows and one suggested button. The person's input goes to the core as one call; the core
//! decides what it means and answers with the next gate.
use adw::prelude::*;

use crate::bridge::{self, wire::Call, wire::Gate, wire::Method, wire::StaySignedIn};
use crate::dev_login::DevSignIn;
use crate::{said, theme, words};

/// What "Stay signed in" says on this device, the core's answer drawn (the CR device-trust-on-linux-and-windows, D5): its
/// label and the line under it, or None where nothing can be kept and the switch is hidden. `LOGIN` is the desktop
/// sign-in opening it with no further question, `PRESENCE` a store that asks the person (Linux never answers it).
pub fn remember_words(stay: StaySignedIn) -> Option<(String, String)> {
    match stay {
        StaySignedIn::None => None,
        StaySignedIn::Login => Some((words::signin_remember(), words::signin_remember_hint_login())),
        StaySignedIn::Presence => Some((words::signin_remember(), words::signin_remember_hint())),
    }
}

/// What the unlock of [method]'s record says, its line and its button, by what staying signed in uses here ([stay],
/// the core's answer beside a remembered account's unlock): the password's line for a password-trusted account; for a
/// remembered one the presence line where the store asks the person, else (`LOGIN`: nothing is asked, so the unlock
/// shows only after a resume that failed) the line that says so and "Try again" (owner 2026-11-11).
pub fn unlock_words(method: Method, stay: Option<StaySignedIn>) -> (String, String) {
    match (method, stay) {
        (Method::Device, _) => (words::unlock_password_hint(), words::unlock_submit()),
        (Method::DeviceKey, Some(StaySignedIn::Login)) => (words::unlock_login_hint(), words::unlock_retry()),
        (Method::DeviceKey, _) => (words::unlock_remembered_hint(), words::unlock_submit()),
    }
}

/// The column every way-in page shares: the brand, a heading, then [rows], clamped and scrolling, under a bare header bar.
fn page(heading: &gtk::Label, rows: &[&gtk::Widget]) -> adw::ToolbarView {
    let column = gtk::Box::new(gtk::Orientation::Vertical, 18);
    column.set_margin_top(24);
    column.set_margin_bottom(24);
    column.set_margin_start(12);
    column.set_margin_end(12);
    let brand = gtk::Image::from_icon_name(theme::APP_ID);
    brand.set_pixel_size(96);
    heading.add_css_class("title-1");
    heading.set_wrap(true);
    heading.set_justify(gtk::Justification::Center);
    column.append(&brand);
    column.append(heading);
    for row in rows {
        column.append(*row);
    }
    let clamp = adw::Clamp::builder().maximum_size(420).child(&column).build();
    let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).child(&clamp).build();
    let page = adw::ToolbarView::new();
    page.add_top_bar(&adw::HeaderBar::builder().show_title(false).build());
    page.set_content(Some(&scroll));
    page
}

fn error_label() -> gtk::Label {
    let error = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
    error.add_css_class("error");
    error
}

fn show_error(label: &gtk::Label, error: &Option<String>) {
    match error {
        Some(key) => {
            label.set_label(&said::key(key));
            label.set_visible(true);
        }
        None => label.set_visible(false),
    }
}

/// The sign-in page: built once, given each gate the core sends.
pub struct SignIn {
    pub page: adw::ToolbarView,
    heading: gtk::Label,
    server: adw::EntryRow,
    email: adw::EntryRow,
    password: adw::PasswordEntryRow,
    secret_key: adw::PasswordEntryRow,
    remember_group: adw::PreferencesGroup,
    remember: adw::SwitchRow,
    submit: gtk::Button,
    cancel: gtk::Button,
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
        // Staying signed in is an explicit choice, never the default (ADR 0005 § 13): off, and its line says what it costs
        let remember = adw::SwitchRow::new();
        let remember_group = adw::PreferencesGroup::builder().visible(false).build();
        remember_group.add(&remember);
        let error = error_label();
        let submit = gtk::Button::builder().label(words::signin_submit()).halign(gtk::Align::Center).build();
        submit.add_css_class("suggested-action");
        submit.add_css_class("pill");
        let cancel = gtk::Button::builder().label(words::signin_cancel()).halign(gtk::Align::Center).visible(false).build();
        cancel.add_css_class("flat");
        let spinner = adw::Spinner::builder().visible(false).build();
        let heading = gtk::Label::new(Some(&words::signin_title()));
        let page = page(&heading, &[fields.upcast_ref(), remember_group.upcast_ref(), error.upcast_ref(), submit.upcast_ref(), cancel.upcast_ref(), spinner.upcast_ref()]);

        let s = Self { page, heading, server, email, password, secret_key, remember_group, remember, submit, cancel, spinner, error };
        let ask = s.asker();
        let on_click = ask.clone();
        s.submit.connect_clicked(move |_| on_click());
        // Enter in the last field signs in, as a form's default button does
        s.secret_key.connect_entry_activated(move |_| ask());
        s.cancel.connect_clicked(|_| bridge::send(Call::SessionCancelAdd));
        s
    }

    /// The sign-in the form holds, sent: "Stay signed in" only where it shows.
    fn asker(&self) -> impl Fn() + Clone + 'static {
        let (server, email, password, secret_key) = (self.server.clone(), self.email.clone(), self.password.clone(), self.secret_key.clone());
        let (remember, group) = (self.remember.clone(), self.remember_group.clone());
        move || {
            bridge::send(Call::SessionSignIn {
                server: server.text().trim().to_string(),
                email: email.text().trim().to_string(),
                password: password.text().to_string(),
                secret_key: secret_key.text().trim().to_string(),
                remember: group.is_visible() && remember.is_active(),
            });
        }
    }

    /// Draws [gate] (a `Gate.SignIn`): the server and the email it knows, working or not, the core's error, and "Stay
    /// signed in" as [stay] says — hidden where the device keeps nothing.
    pub fn show(&self, gate: &Gate, stay: Option<StaySignedIn>) {
        let Gate::SignIn { working, error, server, email, adding } = gate else { return };
        if let Some(server) = server.as_deref().filter(|_| self.server.text().is_empty()) {
            self.server.set_text(server);
        }
        if let Some(email) = email.as_deref().filter(|_| self.email.text().is_empty()) {
            self.email.set_text(email);
        }
        self.heading.set_label(&if *adding { words::signin_add_title() } else { words::signin_title() });
        self.cancel.set_visible(*adding);
        self.cancel.set_sensitive(!working);
        match stay.and_then(remember_words) {
            Some((label, hint)) => {
                self.remember.set_title(&label);
                self.remember_group.set_description(Some(&hint));
                self.remember_group.set_visible(true);
            }
            None => self.remember_group.set_visible(false),
        }
        self.remember.set_sensitive(!working);
        self.submit.set_sensitive(!working);
        self.spinner.set_visible(*working);
        show_error(&self.error, error);
    }

    /// The debug build's dev principal: the form filled, and signed in through it unless [DevSignIn::fill_only].
    pub fn dev(&self, dev: DevSignIn) {
        self.server.set_text(&dev.server);
        self.email.set_text(&dev.email);
        self.password.set_text(&dev.password);
        self.secret_key.set_text(&dev.secret_key);
        if !dev.fill_only {
            self.asker()();
        }
    }
}

impl Default for SignIn {
    fn default() -> Self {
        Self::new()
    }
}

/// An account this device trusts (ADR 0123 decision 8; `Gate.Unlock`): the password alone for a password-trusted one, a
/// button that asks the core again for a remembered one, and the way to another account.
pub struct Unlock {
    pub page: adw::ToolbarView,
    account: adw::ActionRow,
    group: adw::PreferencesGroup,
    password: adw::PasswordEntryRow,
    submit: gtk::Button,
    other: gtk::Button,
    spinner: adw::Spinner,
    error: gtk::Label,
}

impl Unlock {
    pub fn new() -> Self {
        let account = adw::ActionRow::new();
        account.add_prefix(&gtk::Image::from_icon_name("avatar-default-symbolic"));
        let password = adw::PasswordEntryRow::new();
        password.set_title(&words::signin_password());
        let group = adw::PreferencesGroup::new();
        group.add(&account);
        group.add(&password);
        let error = error_label();
        let submit = gtk::Button::builder().label(words::unlock_submit()).halign(gtk::Align::Center).build();
        submit.add_css_class("suggested-action");
        submit.add_css_class("pill");
        let other = gtk::Button::builder().label(words::unlock_other()).halign(gtk::Align::Center).build();
        other.add_css_class("flat");
        let spinner = adw::Spinner::builder().visible(false).build();
        let heading = gtk::Label::new(Some(&words::unlock_title()));
        let page = page(&heading, &[group.upcast_ref(), error.upcast_ref(), submit.upcast_ref(), other.upcast_ref(), spinner.upcast_ref()]);

        let s = Self { page, account, group, password, submit, other, spinner, error };
        let unlock = {
            let password = s.password.clone();
            move || {
                // "" for a remembered account, whose row is hidden: the core asks the store itself
                let typed = if password.is_visible() { password.text().to_string() } else { String::new() };
                password.set_text("");
                bridge::send(Call::SessionUnlock { password: typed });
            }
        };
        let on_click = unlock.clone();
        s.submit.connect_clicked(move |_| on_click());
        s.password.connect_entry_activated(move |_| unlock());
        s.other.connect_clicked(|_| bridge::send(Call::SessionOtherAccount));
        s
    }

    /// Draws [gate] (a `Gate.Unlock`): whose account, the password where it opens with one, what opens it ([stay]),
    /// working or not, the error.
    pub fn show(&self, gate: &Gate, stay: Option<StaySignedIn>) {
        let Gate::Unlock { record, working, error } = gate else { return };
        self.account.set_title(&record.email);
        self.account.set_subtitle(&record.server);
        let remembered = record.method == Method::DeviceKey;
        self.password.set_visible(!remembered);
        let (hint, button) = unlock_words(record.method, stay);
        self.group.set_description(Some(&hint));
        self.submit.set_label(&button);
        self.submit.set_sensitive(!working);
        self.other.set_sensitive(!working);
        self.spinner.set_visible(*working);
        show_error(&self.error, error);
    }
}

impl Default for Unlock {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_switch_is_hidden_where_nothing_is_kept_and_says_what_it_uses_elsewhere() {
        assert_eq!(remember_words(StaySignedIn::None), None);
        let (login, login_hint) = remember_words(StaySignedIn::Login).expect("shown");
        let (presence, presence_hint) = remember_words(StaySignedIn::Presence).expect("shown");
        assert_eq!(login, words::signin_remember());
        assert_eq!(presence, words::signin_remember());
        assert_eq!(login_hint, words::signin_remember_hint_login());
        assert_ne!(login_hint, presence_hint, "the login's switch says the person is asked");
    }

    #[test]
    fn a_remembered_unlock_says_what_opens_it() {
        assert_eq!(unlock_words(Method::DeviceKey, Some(StaySignedIn::Login)), (words::unlock_login_hint(), words::unlock_retry()));
        assert_eq!(unlock_words(Method::DeviceKey, Some(StaySignedIn::Presence)), (words::unlock_remembered_hint(), words::unlock_submit()));
        assert_eq!(unlock_words(Method::DeviceKey, None), (words::unlock_remembered_hint(), words::unlock_submit()));
        assert_eq!(unlock_words(Method::Device, Some(StaySignedIn::Login)), (words::unlock_password_hint(), words::unlock_submit()));
        assert_ne!(words::unlock_login_hint(), words::unlock_remembered_hint());
    }
}
