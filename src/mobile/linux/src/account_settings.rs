//! The account's Settings (`uc.account.sessions`; the web's Settings hub, its `SessionsPanel` the reference), GNOME's
//! way: an `AdwPreferencesDialog` opened from the sidebar's Settings, as "Manage servers" is ([crate::manage]). One page
//! of groups, each titled as the web's sections are:
//!
//! - **Account**: the address this account signs in with, and whether the server holds it confirmed;
//! - **This device**: its name in the account's device register, renamed in an `AdwEntryRow` with apply; how it stays
//!   signed in and its change (the levels and the way back to the password, Manage's own rows), "Stop trusting this
//!   device";
//! - **Devices and sessions**: every session family, this device's first and marked, each named from the register or
//!   by its dates, its sign-out (and, where the core offers it, "Sign out and remove saved key") in the row's menu, every
//!   step asked first in an `AdwAlertDialog`; "Sign out everywhere else";
//! - **Devices with a saved key**: each removable, and "Remove every saved key except this device's";
//! - **Sign out** of this account here.
//!
//! Theme and language follow the system (D3): no row for them. "Add a device" is the core's (`addDevice`) but no screen
//! of this app yet, so it is left out until `cr-app-device-linking` builds one, never drawn as a control that answers
//! "not yet". Every state is the core's (`Settings.Open`, `Watched.Settings`: `SettingsShown`), every action one call;
//! why one did not come through is the state's `refusal`, said as a toast.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::SettingsFamily, wire::SettingsShown, wire::Update, wire::Watched};
use crate::manage::{self, Change};
use crate::{said, sign_in, words};

/// One Settings dialog: its widgets, the page's id in the core once opened (none for the debug harness's), and what was
/// drawn last that a redraw must not repeat (the name typed, the refusal said).
struct Page {
    dialog: adw::PreferencesDialog,
    page: adw::PreferencesPage,
    groups: RefCell<Vec<adw::PreferencesGroup>>,
    /// This device's name, kept across redraws so what is typed stays while the rows around it are drawn anew.
    rename: adw::EntryRow,
    /// The group [rename] sits in now.
    rename_group: RefCell<Option<adw::PreferencesGroup>>,
    id: Cell<Option<i64>>,
    watch: RefCell<Option<Watch>>,
    label: RefCell<Option<String>>,
    refusal: RefCell<Option<String>>,
}

/// Opens [connection]'s Settings over [window]; the core reads and delivers what it shows.
pub fn open(window: &adw::ApplicationWindow, connection: String) {
    let page = Page::new();
    let weak = Rc::downgrade(&page);
    glib::spawn_future_local(async move {
        let settings = match bridge::call(&Call::SettingsOpen { connection }).await.map(|(a, _)| a) {
            Ok(Answer::SettingsOpened { settings }) => settings,
            other => {
                eprintln!("veyq: Settings.Open answered {other:?}");
                if let Some(p) = weak.upgrade() {
                    p.dialog.force_close();
                }
                return;
            }
        };
        let Some(page) = weak.upgrade() else {
            bridge::send(Call::SettingsClose { settings });
            return;
        };
        page.id.set(Some(settings));
        eprintln!("veyq: settings open");
        let Some((watch, updates)) = bridge::watch(&Watched::Settings { settings }) else { return };
        *page.watch.borrow_mut() = Some(watch);
        drop(page);
        follow(weak, updates).await;
    });
    present(page, window);
}

/// The debug harness's door ([crate::dev_settings]): [shown] drawn as the core would send it, with no page in the core
/// (every action is then refused, and said so on stderr).
pub fn show(window: &adw::ApplicationWindow, shown: &SettingsShown) {
    let page = Page::new();
    page.draw(shown);
    present(page, window);
}

/// Presents [page]'s dialog and holds the page until it closes: a dialog's state only weak handlers reach would be
/// dropped at once (the traps, `cover.rs`).
fn present(page: Rc<Page>, window: &adw::ApplicationWindow) {
    let held = RefCell::new(Some(page.clone()));
    page.dialog.connect_closed(move |_| {
        if let Some(p) = held.borrow_mut().take() {
            p.watch.borrow_mut().take();
            if let Some(settings) = p.id.take() {
                bridge::send(Call::SettingsClose { settings });
            }
        }
    });
    page.dialog.present(Some(window));
}

async fn follow(weak: Weak<Page>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(page) = weak.upgrade() else { return };
        match update {
            Ok(Update::Settings { shown: Some(s), .. }) => page.draw(&s),
            // closed in the core (its tenant closed, its account signed out here): the dialog goes with it
            Ok(Update::Settings { shown: None, .. }) => {
                page.id.set(None);
                page.dialog.force_close();
                return;
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a settings update did not read: {:?}", e.0),
        }
    }
}

impl Page {
    fn new() -> Rc<Self> {
        let page = adw::PreferencesPage::new();
        let dialog = adw::PreferencesDialog::builder()
            .title(words::drawer_settings())
            .search_enabled(false)
            .content_width(600)
            .content_height(720)
            .build();
        dialog.add(&page);
        let rename = adw::EntryRow::builder().title(words::account_settings_rename()).show_apply_button(true).build();
        let p = Rc::new(Self {
            dialog,
            page,
            groups: RefCell::new(Vec::new()),
            rename: rename.clone(),
            rename_group: RefCell::new(None),
            id: Cell::new(None),
            watch: RefCell::new(None),
            label: RefCell::new(None),
            refusal: RefCell::new(None),
        });
        let weak = Rc::downgrade(&p);
        rename.connect_apply(move |row| {
            let Some(p) = weak.upgrade() else { return };
            let label = row.text().to_string();
            p.act("rename", move |settings| Call::SettingsRename { settings, label });
        });
        p
    }

    /// Sends the call [make] builds for this page's id; the answer goes to stderr, a change of trust's to a toast.
    fn act(self: &Rc<Self>, what: &'static str, make: impl FnOnce(i64) -> Call) {
        let Some(settings) = self.id.get() else {
            eprintln!("veyq: settings {what}: no page in the core");
            return;
        };
        let call = make(settings);
        let trust = matches!(call, Call::SettingsStaySignedIn { .. });
        let dialog = self.dialog.clone();
        glib::spawn_future_local(async move {
            let answered = bridge::call(&call).await.map(|(a, _)| a);
            eprintln!("veyq: settings {what}: {}", manage::answer_kind(&answered));
            if trust {
                manage::said_change(&dialog, answered);
            }
        });
    }

    /// Asks [body] under [heading] first, then sends what [make] builds (a destructive answer, [accept] its words).
    fn ask_then(self: &Rc<Self>, heading: String, body: String, accept: String, what: &'static str, make: impl FnOnce(i64) -> Call + 'static) {
        let ask = adw::AlertDialog::builder().heading(heading).body(body).build();
        ask.add_responses(&[("cancel", &words::signin_cancel()), ("accept", &accept)]);
        ask.set_response_appearance("accept", adw::ResponseAppearance::Destructive);
        ask.set_default_response(Some("cancel"));
        ask.set_close_response("cancel");
        let weak = Rc::downgrade(self);
        let make = RefCell::new(Some(make));
        ask.connect_response(None, move |_, response| {
            if response != "accept" {
                return;
            }
            if let (Some(p), Some(make)) = (weak.upgrade(), make.borrow_mut().take()) {
                p.act(what, make);
            }
        });
        ask.present(Some(&self.dialog));
    }

    /// Draws [s]: every group anew, the name's row kept.
    fn draw(self: &Rc<Self>, s: &SettingsShown) {
        if let Some(g) = self.rename_group.borrow_mut().take() {
            g.remove(&self.rename);
        }
        for g in self.groups.borrow_mut().drain(..) {
            self.page.remove(&g);
        }
        let groups = vec![self.account(s), self.device(s), self.sessions(s), self.saved_keys(s), self.sign_out()];
        for g in groups.iter().flatten() {
            self.page.add(g);
        }
        *self.groups.borrow_mut() = groups.into_iter().flatten().collect();
        // the name is set from the state when it changed, never over what is being typed
        if self.label.borrow().as_deref() != Some(s.device.as_str()) {
            if !self.rename.state_flags().contains(gtk::StateFlags::FOCUS_WITHIN) || self.label.borrow().is_none() {
                self.rename.set_text(&s.device);
            }
            *self.label.borrow_mut() = Some(s.device.clone());
        }
        // why the last action did not come through, said once
        if *self.refusal.borrow() != s.refusal {
            if let Some(why) = &s.refusal {
                self.dialog.add_toast(adw::Toast::new(&said::key(why)));
            }
            *self.refusal.borrow_mut() = s.refusal.clone();
        }
    }

    fn account(&self, s: &SettingsShown) -> Option<adw::PreferencesGroup> {
        let group = adw::PreferencesGroup::builder().title(words::account_settings_account()).build();
        let email = adw::ActionRow::builder().title(words::account_settings_email()).subtitle(s.email.as_str()).use_markup(false).subtitle_selectable(true).build();
        email.add_css_class("property");
        group.add(&email);
        match s.email_confirmed {
            Some(true) => group.set_description(Some(&words::account_settings_email_confirmed())),
            Some(false) => group.set_description(Some(&words::account_settings_email_unconfirmed())),
            None => {}
        }
        Some(group)
    }

    fn device(self: &Rc<Self>, s: &SettingsShown) -> Option<adw::PreferencesGroup> {
        let group = adw::PreferencesGroup::builder().title(words::account_settings_this_device()).build();
        // how this device trusts the account, as "Manage servers" says it
        if let Some(line) = manage::trust_line(s.trusted) {
            group.set_description(Some(&line));
        }
        self.rename.set_tooltip_text(Some(&words::account_settings_rename_hint()));
        group.add(&self.rename);
        *self.rename_group.borrow_mut() = Some(group.clone());
        for change in manage::changes(s.trusted, s.level, &s.levels) {
            let row = match change {
                Change::Stay(level) => {
                    let (title, hint) = sign_in::level_words(level).unwrap_or_default();
                    let row = adw::ButtonRow::builder().title(title).build();
                    row.set_tooltip_text(Some(&hint));
                    let weak = Rc::downgrade(self);
                    row.connect_activated(move |_| {
                        if let Some(p) = weak.upgrade() {
                            p.act("stay signed in", move |settings| Call::SettingsStaySignedIn { settings, level });
                        }
                    });
                    row
                }
                Change::Password => {
                    let row = adw::ButtonRow::builder().title(words::manage_password_trust()).build();
                    let weak = Rc::downgrade(self);
                    row.connect_activated(move |_| {
                        let Some(p) = weak.upgrade() else { return };
                        let Some(settings) = p.id.get() else { return };
                        manage::ask_password_then(&p.dialog, "settings back to the password", move |password| Call::SettingsPasswordTrust { settings, password });
                    });
                    row
                }
            };
            group.add(&row);
        }
        if s.trusted.is_some() {
            let forget = adw::ButtonRow::builder().title(words::manage_forget_device()).build();
            forget.add_css_class("destructive-action");
            let weak = Rc::downgrade(self);
            forget.connect_activated(move |_| {
                if let Some(p) = weak.upgrade() {
                    // asked first, as the phones ask
                    p.ask_then(
                        words::account_settings_untrust_confirm_title(),
                        words::account_settings_untrust_confirm(),
                        words::manage_forget_device(),
                        "stop trusting",
                        |settings| Call::SettingsStopTrusting { settings },
                    );
                }
            });
            group.add(&forget);
        }
        Some(group)
    }

    fn sessions(self: &Rc<Self>, s: &SettingsShown) -> Option<adw::PreferencesGroup> {
        let mut hint = words::account_settings_sessions_sub();
        // the names are missing, the rows still act (ADR 0028 decision 7)
        if let Some(why) = &s.register_refusal {
            hint = format!("{hint}\n\n{}", said::key(why));
        }
        let group = adw::PreferencesGroup::builder().title(words::account_settings_sessions()).description(hint).build();
        let Some(families) = &s.families else {
            let loading = adw::ActionRow::builder().title(words::account_settings_sessions_loading()).build();
            loading.add_prefix(&adw::Spinner::new());
            group.add(&loading);
            return Some(group);
        };
        for f in families {
            group.add(&self.family(s, f));
        }
        if s.has_others {
            let others = adw::ButtonRow::builder().title(words::account_settings_revoke_others()).build();
            others.add_css_class("destructive-action");
            let weak = Rc::downgrade(self);
            others.connect_activated(move |_| {
                if let Some(p) = weak.upgrade() {
                    p.ask_then(
                        words::account_settings_revoke_others(),
                        words::account_settings_revoke_others_confirm(),
                        words::account_settings_revoke_others(),
                        "sign out every other",
                        |settings| Call::SettingsSignOutOthers { settings },
                    );
                }
            });
            group.add(&others);
        }
        Some(group)
    }

    /// One family's row: its device's name (this device's own, else the register's, else "Signed-in device"), its
    /// dates, "You are here" on this device's, and its actions in the row's menu.
    fn family(self: &Rc<Self>, s: &SettingsShown, f: &SettingsFamily) -> adw::ActionRow {
        let title = match (&f.label, f.current) {
            (Some(label), _) => label.clone(),
            (None, true) => s.device.clone(),
            (None, false) => words::account_settings_other_device(),
        };
        let row = adw::ActionRow::builder().title(title).subtitle(family_dates(f)).use_markup(false).subtitle_lines(2).build();
        row.add_prefix(&gtk::Image::from_icon_name(if f.current { "computer-symbolic" } else { "network-workgroup-symbolic" }));
        if f.current {
            let here = gtk::Label::builder().label(words::account_settings_current()).valign(gtk::Align::Center).build();
            here.add_css_class("dim-label");
            row.add_suffix(&here);
        }
        let actions = gio::SimpleActionGroup::new();
        let menu = gio::Menu::new();
        let id = f.id.clone();
        let revoke = gio::SimpleAction::new("revoke", None);
        let weak = Rc::downgrade(self);
        let current = f.current;
        revoke.connect_activate(move |_, _| {
            let Some(p) = weak.upgrade() else { return };
            let family = id.clone();
            let (heading, body) = if current {
                (words::account_settings_revoke_self(), words::account_settings_revoke_self_confirm())
            } else {
                (words::account_settings_revoke(), words::account_settings_revoke_confirm())
            };
            p.ask_then(heading.clone(), body, heading, "sign out", move |settings| Call::SettingsSignOut { settings, family });
        });
        actions.add_action(&revoke);
        menu.append(Some(&if f.current { words::account_settings_revoke_self() } else { words::account_settings_revoke() }), Some("family.revoke"));
        if f.removes_key {
            let escalate = gio::SimpleAction::new("escalate", None);
            let (weak, id) = (Rc::downgrade(self), f.id.clone());
            escalate.connect_activate(move |_, _| {
                let Some(p) = weak.upgrade() else { return };
                let family = id.clone();
                p.ask_then(
                    words::account_settings_escalate(),
                    words::account_settings_escalate_confirm(),
                    // the web's short words: the title's would be cut on the dialog's button
                    words::account_settings_escalate_accept(),
                    "sign out and remove the saved key",
                    move |settings| Call::SettingsSignOutAndRemoveKey { settings, family },
                );
            });
            actions.add_action(&escalate);
            menu.append(Some(&words::account_settings_escalate()), Some("family.escalate"));
        }
        row.insert_action_group("family", Some(&actions));
        let more = gtk::MenuButton::builder().icon_name("view-more-symbolic").menu_model(&menu).valign(gtk::Align::Center).tooltip_text(row.title()).build();
        more.add_css_class("flat");
        row.add_suffix(&more);
        row
    }

    fn saved_keys(self: &Rc<Self>, s: &SettingsShown) -> Option<adw::PreferencesGroup> {
        if s.kept_devices.is_empty() && !s.other_saved_keys {
            return None;
        }
        let group = adw::PreferencesGroup::builder().title(words::account_settings_saved_keys()).description(words::account_settings_saved_keys_sub()).build();
        for d in &s.kept_devices {
            let row = adw::ActionRow::builder().title(d.label.as_str()).subtitle(words::account_settings_saved_key_row()).use_markup(false).build();
            let remove = gtk::Button::builder().icon_name("user-trash-symbolic").valign(gtk::Align::Center).tooltip_text(words::account_settings_remove_key()).build();
            remove.update_property(&[gtk::accessible::Property::Label(&words::account_settings_remove_key())]);
            remove.add_css_class("flat");
            let (weak, device, label) = (Rc::downgrade(self), d.device_id.clone(), d.label.clone());
            remove.connect_clicked(move |_| {
                let Some(p) = weak.upgrade() else { return };
                let device = device.clone();
                p.ask_then(
                    words::account_settings_remove_key(),
                    words::account_settings_remove_key_confirm(&label),
                    words::account_settings_remove_key(),
                    "remove a saved key",
                    move |settings| Call::SettingsRemoveSavedKey { settings, device },
                );
            });
            row.add_suffix(&remove);
            group.add(&row);
        }
        if s.other_saved_keys {
            let others = adw::ButtonRow::builder().title(words::account_settings_remove_other_keys()).build();
            others.add_css_class("destructive-action");
            let weak = Rc::downgrade(self);
            others.connect_activated(move |_| {
                if let Some(p) = weak.upgrade() {
                    p.ask_then(
                        words::account_settings_remove_other_keys(),
                        words::account_settings_remove_other_keys_confirm(),
                        words::account_settings_remove_other_keys(),
                        "remove every other saved key",
                        |settings| Call::SettingsRemoveOtherSavedKeys { settings },
                    );
                }
            });
            group.add(&others);
        }
        Some(group)
    }

    fn sign_out(self: &Rc<Self>) -> Option<adw::PreferencesGroup> {
        let group = adw::PreferencesGroup::new();
        let row = adw::ButtonRow::builder().title(words::drawer_sign_out()).start_icon_name("system-log-out-symbolic").build();
        row.add_css_class("destructive-action");
        let weak = Rc::downgrade(self);
        row.connect_activated(move |_| {
            if let Some(p) = weak.upgrade() {
                p.act("sign out here", |settings| Call::SettingsSignOutHere { settings });
            }
        });
        group.add(&row);
        Some(group)
    }
}

/// A family's dates as its row says them: when it signed in and when it was last used, the ones the server sent.
pub fn family_dates(f: &SettingsFamily) -> String {
    let lines: Vec<String> = [f.created_at.map(|t| words::account_settings_signed_in(&said::moment(t))), f.last_used_at.map(|t| words::account_settings_last_used(&said::moment(t)))]
        .into_iter()
        .flatten()
        .collect();
    lines.join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn family(created_at: Option<i64>, last_used_at: Option<i64>) -> SettingsFamily {
        SettingsFamily { id: "f".into(), created_at, last_used_at, current: false, label: None, removes_key: false }
    }

    #[test]
    fn a_familys_row_says_the_dates_the_server_sent() {
        let both = family_dates(&family(Some(1_793_527_200_000), Some(1_794_475_800_000)));
        assert_eq!(both.lines().count(), 2, "{both}");
        assert!(both.starts_with(&words::account_settings_signed_in("").trim_end().to_string()), "{both}");
        assert_eq!(family_dates(&family(None, Some(1_794_475_800_000))).lines().count(), 1);
        assert_eq!(family_dates(&family(None, None)), "");
    }
}
