//! "Manage servers" (owner 2026-11-08; the CR device-trust-on-linux-and-windows, D5), GNOME's way: an
//! `AdwPreferencesDialog` opened from the switcher. One group per server signed in to — each tenant an expander row
//! whose switch shows or hides it in the switcher, its spaces switch rows inside; how this device trusts the server as
//! the group's line, and its change (the CR's D7): each other level the device offers, "Back to your password" (which
//! asks for it in an `AdwAlertDialog` over this one), "Stop trusting this device"; sign out — and "Add server" last. What
//! it shows is the core's (`Watched.Manage`, `SessionModel.managed`); each switch and button is one call.
use std::cell::{Cell, RefCell};
use std::collections::HashSet;
use std::rc::Rc;

use adw::prelude::*;

use gtk::glib;

use crate::bridge::{self, wire::Answer, wire::Call, wire::ManagedShown, wire::Method, wire::StaySignedIn, wire::Why};
use crate::{dev_login, said, sign_in, words};

/// "Manage servers"' line for how this device trusts a server: the password, staying signed in (the level without a check
/// is the only one a Linux store keeps), or None where it trusts it not.
pub fn trust_line(trusted: Option<Method>) -> Option<String> {
    match trusted {
        Some(Method::DeviceKey) => Some(words::manage_trusted_remembered()),
        Some(Method::Device) => Some(words::manage_trusted_password()),
        None => None,
    }
}

/// A change of a server's trust "Manage servers" offers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Change {
    /// Stay signed in at this level.
    Stay(StaySignedIn),
    /// Back to the password trust.
    Password,
}

/// The changes for a server trusted by [trusted] at [level], the device offering [levels]: each offered level it is not
/// at (and that has words here), then the way back to the password from staying signed in. Nothing where the device does
/// not trust it: the next sign-in does.
pub fn changes(trusted: Option<Method>, level: Option<StaySignedIn>, levels: &[StaySignedIn]) -> Vec<Change> {
    let Some(method) = trusted else { return Vec::new() };
    let mut out: Vec<Change> = levels
        .iter()
        .filter(|&&l| !(method == Method::DeviceKey && level == Some(l)) && sign_in::level_words(l).is_some())
        .map(|&l| Change::Stay(l))
        .collect();
    if method == Method::DeviceKey {
        out.push(Change::Password);
    }
    out
}

pub struct Manage {
    dialog: adw::PreferencesDialog,
    page: adw::PreferencesPage,
    groups: RefCell<Vec<adw::PreferencesGroup>>,
    /// The tenants whose spaces the person opened, kept across the redraw each switch brings.
    expanded: Rc<RefCell<HashSet<String>>>,
    open: Cell<bool>,
    window: adw::ApplicationWindow,
}

impl Manage {
    pub fn new(window: &adw::ApplicationWindow) -> Rc<Self> {
        let page = adw::PreferencesPage::new();
        let dialog = adw::PreferencesDialog::builder()
            .title(words::manage_title())
            .search_enabled(false)
            // the size GNOME's own preferences dialogs open at; its content arrives after it opened
            .content_width(560)
            .content_height(640)
            .build();
        dialog.add(&page);
        let manage = Rc::new(Self {
            dialog,
            page,
            groups: RefCell::new(Vec::new()),
            expanded: Rc::new(RefCell::new(HashSet::new())),
            open: Cell::new(false),
            window: window.clone(),
        });
        let weak = Rc::downgrade(&manage);
        manage.dialog.connect_closed(move |_| {
            if let Some(m) = weak.upgrade() {
                m.open.set(false);
            }
            bridge::send(Call::ManageClose);
        });
        manage
    }

    /// Opens the dialog over the window; the core then delivers what it shows.
    pub fn open(&self) {
        if self.open.replace(true) {
            return;
        }
        bridge::send(Call::ManageOpen);
        self.dialog.present(Some(&self.window));
    }

    /// Closes it, where it is open (the way in left the shell).
    pub fn close(&self) {
        if self.open.get() {
            self.dialog.force_close();
        }
    }

    /// Draws the core's [servers]; None while it is closed.
    pub fn show(&self, servers: Option<Vec<ManagedShown>>) {
        let Some(servers) = servers else { return };
        for g in self.groups.borrow_mut().drain(..) {
            self.page.remove(&g);
        }
        let mut groups = Vec::new();
        for server in &servers {
            groups.push(self.server(server));
        }
        let add = adw::PreferencesGroup::builder().description(words::manage_hint()).build();
        let add_row = adw::ButtonRow::builder().title(words::switcher_add_server()).build();
        let dialog = self.dialog.clone();
        add_row.connect_activated(move |_| {
            bridge::send(Call::SessionAddServer);
            dialog.force_close();
        });
        add.add(&add_row);
        groups.push(add);
        for g in &groups {
            self.page.add(g);
        }
        *self.groups.borrow_mut() = groups;
    }

    fn server(&self, server: &ManagedShown) -> adw::PreferencesGroup {
        let group = adw::PreferencesGroup::builder().title(server.label.as_str()).build();
        // how this device trusts the account; ending it means the next sign-in asks for the Secret Key
        if let Some(line) = trust_line(server.trusted) {
            group.set_description(Some(&line));
        }
        for t in &server.tenants {
            let key = format!("{}\n{}", server.connection, t.tenant.tenant_id);
            let row = adw::ExpanderRow::builder()
                .title(t.tenant.name.as_str())
                .subtitle(said::piece(&t.subtitle))
                .expanded(self.expanded.borrow().contains(&key))
                .build();
            let shown = gtk::Switch::builder().active(t.shown).sensitive(t.switchable).valign(gtk::Align::Center).build();
            shown.update_property(&[gtk::accessible::Property::Label(&t.tenant.name)]);
            row.add_suffix(&shown);
            let (connection, tenant_id) = (server.connection.clone(), t.tenant.tenant_id.clone());
            shown.connect_active_notify(move |s| {
                bridge::send(Call::ManageHideTenant { connection: connection.clone(), tenant_id: tenant_id.clone(), hidden: !s.is_active() });
            });
            let expanded = self.expanded.clone();
            row.connect_expanded_notify(move |r| {
                let mut set = expanded.borrow_mut();
                if r.is_expanded() { set.insert(key.clone()) } else { set.remove(&key) };
            });
            match &t.spaces {
                None => {
                    let reading = adw::ActionRow::new();
                    reading.add_prefix(&adw::Spinner::new());
                    row.add_row(&reading);
                }
                Some(spaces) => {
                    for s in spaces {
                        let space = adw::SwitchRow::builder().title(said::piece(&s.name)).active(!s.hidden).sensitive(s.can_hide).build();
                        let scope_id = s.space.scope_id.clone();
                        space.connect_active_notify(move |r| bridge::send(Call::ManageHideSpace { scope_id: scope_id.clone(), hidden: !r.is_active() }));
                        row.add_row(&space);
                    }
                }
            }
            group.add(&row);
        }
        let sign_out = adw::ButtonRow::builder().title(words::manage_sign_out()).start_icon_name("system-log-out-symbolic").build();
        sign_out.add_css_class("destructive-action");
        let connection = server.connection.clone();
        sign_out.connect_activated(move |_| bridge::send(Call::SessionSignOut { connection: connection.clone() }));
        group.add(&sign_out);
        for change in changes(server.trusted, server.level, &server.levels) {
            let connection = server.connection.clone();
            let row = match change {
                Change::Stay(level) => {
                    let (title, hint) = sign_in::level_words(level).unwrap_or_default();
                    let row = adw::ButtonRow::builder().title(title).build();
                    row.set_tooltip_text(Some(&hint));
                    let dialog = self.dialog.clone();
                    row.connect_activated(move |_| change_level(&dialog, connection.clone(), level));
                    row
                }
                Change::Password => {
                    let row = adw::ButtonRow::builder().title(words::manage_password_trust()).build();
                    let dialog = self.dialog.clone();
                    row.connect_activated(move |_| ask_password(&dialog, connection.clone()));
                    row
                }
            };
            group.add(&row);
        }
        if server.trusted.is_some() {
            let forget = adw::ButtonRow::builder().title(words::manage_forget_device()).build();
            forget.add_css_class("destructive-action");
            let connection = server.connection.clone();
            forget.connect_activated(move |_| bridge::send(Call::SessionForgetDevice { connection: connection.clone() }));
            group.add(&forget);
        }
        group
    }
}

/// Says what a change of trust was answered, where it did not happen, as a toast over [dialog].
fn said_change(dialog: &adw::PreferencesDialog, answered: Result<Answer, bridge::Unreadable>) {
    let text = match answered {
        Ok(Answer::Done) => return,
        // a prompt cancelled: nothing changed, and nothing to say
        Ok(Answer::Refused { why: Why::TrustCancelled }) => return,
        Ok(Answer::Refused { why: Why::WrongPassword }) => words::manage_wrong_password(),
        Ok(Answer::Refused { why: Why::TrustFailed }) => words::trust_failed(),
        other => {
            eprintln!("veyq: a change of trust answered {other:?}");
            words::trust_failed()
        }
    };
    dialog.add_toast(adw::Toast::new(&text));
}

/// Stays signed in to [connection] at [level] instead; Manage follows (the core refreshes it).
fn change_level(dialog: &adw::PreferencesDialog, connection: String, level: StaySignedIn) {
    let dialog = dialog.clone();
    glib::spawn_future_local(async move {
        let answered = bridge::call(&Call::ManageStaySignedIn { connection, level }).await.map(|(a, _)| a);
        eprintln!("veyq: manage stay signed in {level:?}: {}", answer_kind(&answered));
        said_change(&dialog, answered);
    });
}

/// "Back to your password": the password asked for over [dialog] (ADR 0005 §9 keeps one convenience wrap per device, so
/// the password trust is re-created, which takes it), then sent to the core, which checks it first.
fn ask_password(dialog: &adw::PreferencesDialog, connection: String) {
    let password = adw::PasswordEntryRow::builder().title(words::signin_password()).build();
    let rows = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    rows.add_css_class("boxed-list");
    rows.append(&password);
    let ask = adw::AlertDialog::builder()
        .heading(words::manage_password_trust())
        .body(words::manage_password_trust_hint())
        .extra_child(&rows)
        .build();
    ask.add_responses(&[("cancel", &words::signin_cancel()), ("confirm", &words::manage_password_trust())]);
    ask.set_response_appearance("confirm", adw::ResponseAppearance::Suggested);
    ask.set_default_response(Some("confirm"));
    ask.set_close_response("cancel");
    ask.set_response_enabled("confirm", false);
    let on_text = ask.clone();
    password.connect_changed(move |p| on_text.set_response_enabled("confirm", !p.text().is_empty()));
    // Enter takes the default response, once a password is typed
    password.set_activates_default(true);
    if let Some(dev) = dev_login::take_password_trust() {
        password.set_text(&dev);
    }
    let parent = dialog.clone();
    ask.connect_response(None, move |_, response| {
        if response != "confirm" {
            return;
        }
        let typed = password.text().to_string();
        password.set_text("");
        let (parent, connection) = (parent.clone(), connection.clone());
        glib::spawn_future_local(async move {
            let answered = bridge::call(&Call::ManagePasswordTrust { connection, password: typed }).await.map(|(a, _)| a);
            eprintln!("veyq: manage back to the password: {}", answer_kind(&answered));
            said_change(&parent, answered);
        });
    });
    ask.present(Some(dialog));
}

/// An answer's kind alone, for stderr.
fn answer_kind(answered: &Result<Answer, bridge::Unreadable>) -> String {
    match answered {
        Ok(Answer::Refused { why }) => format!("Refused {why:?}"),
        Ok(a) => serde_json::to_value(a).ok().and_then(|v| v["type"].as_str().map(str::to_owned)).unwrap_or_default(),
        Err(_) => "unreadable".to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn manage_offers_each_other_level_and_the_way_back() {
        use StaySignedIn::Login;
        // staying signed in at LOGIN, the only level here: back to the password alone
        assert_eq!(changes(Some(Method::DeviceKey), Some(Login), &[Login]), vec![Change::Password]);
        // password-trusted: the level the device offers
        assert_eq!(changes(Some(Method::Device), None, &[Login]), vec![Change::Stay(Login)]);
        // PRESENCE has no words here and is never offered as LOGIN's
        assert_eq!(changes(Some(Method::Device), None, &[StaySignedIn::Presence, Login]), vec![Change::Stay(Login)]);
        // not trusted, or nothing offered
        assert!(changes(None, None, &[Login]).is_empty());
        assert!(changes(Some(Method::Device), None, &[]).is_empty());
        assert_eq!(trust_line(Some(Method::DeviceKey)), Some(words::manage_trusted_remembered()));
        assert_eq!(trust_line(Some(Method::Device)), Some(words::manage_trusted_password()));
        assert_eq!(trust_line(None), None);
    }
}
