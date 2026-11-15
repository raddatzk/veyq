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
//! - **Add a device** (only while the core offers it, `addDevice`: `core.device-linking`): "Show a code" and, where the
//!   Camera portal names a camera, "Scan a code" — each a ceremony of [crate::device_link] over this dialog;
//! - **Devices with a saved key**: each removable, and "Remove every saved key except this device's";
//! - **Storage on this device** (ADR 0140 decision 10): the limit of what is kept of content viewed — a free size in an
//!   `AdwEntryRow` with apply, its unit or Off in a drop-down beside it —, the sizes the automatic tier and the items
//!   available offline take and the volume's free space, "Clear cache", "Remove all offline content" (asked first, the
//!   core's `askingRemoveAll`), and a group of the items available offline, each with its status and size and removable;
//!   followed live while the dialog shows (`Watched.SettingsStorageLive`). Where this device keeps no store of the account,
//!   the group says why (`storageUnavailable`);
//! - **Sign out** of this account here.
//!
//! Theme and language follow the system (D3): no row for them. Every state is the core's (`Settings.Open`, `Watched.Settings`: `SettingsShown`), every action one call;
//! why one did not come through is the state's `refusal`, said as a toast.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::LimitField, wire::LimitUnit, wire::OfflineItem, wire::SettingsFamily, wire::SettingsShown, wire::Storage, wire::Update, wire::Watched};
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
    /// The account this page is of (none for the debug harness's), for the device link's calls.
    connection: RefCell<Option<String>>,
    /// "Add a device", built once: its scan row shows once the Camera portal named a camera.
    add_device: adw::PreferencesGroup,
    /// The storage limit's free size field and its unit (or Off), kept across redraws as [rename] is.
    limit: adw::EntryRow,
    unit: gtk::DropDown,
    /// The units the drop-down lists before its last entry, Off; and the group [limit] sits in now.
    units: RefCell<Vec<LimitUnit>>,
    limit_group: RefCell<Option<adw::PreferencesGroup>>,
    /// The limit's field as drawn last: set again only when the core's changed, never over what is being typed.
    limit_drawn: RefCell<Option<LimitField>>,
    /// Set while a redraw sets the drop-down, so its change sends nothing.
    drawing: Cell<bool>,
    /// The storage section followed live while the dialog shows.
    storage_watch: RefCell<Option<Watch>>,
    /// "Remove all offline content"'s question while the core asks it.
    remove_all_ask: RefCell<Option<adw::AlertDialog>>,
}

/// Opens [connection]'s Settings over [window]; the core reads and delivers what it shows.
pub fn open(window: &adw::ApplicationWindow, connection: String) {
    let page = Page::new();
    page.connection.replace(Some(connection.clone()));
    let weak = Rc::downgrade(&page);
    glib::spawn_future_local(async move {
        let settings = match bridge::call(&Call::SettingsOpen { connection, separator: said::decimal_separator() }).await.map(|(a, _)| a) {
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
        // the storage section follows the pins and their fetches while the dialog shows; its updates come on the page's
        if let Some((watch, _)) = bridge::watch(&Watched::SettingsStorageLive { settings }) {
            *page.storage_watch.borrow_mut() = Some(watch);
        }
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
            p.storage_watch.borrow_mut().take();
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
        let add_device = adw::PreferencesGroup::builder()
            .title(words::account_settings_add_device())
            .description(words::account_settings_add_device_sub())
            .build();
        let show = adw::ActionRow::builder().title(words::link_show()).subtitle(words::link_intro()).activatable(true).build();
        show.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
        let scan = adw::ActionRow::builder().title(words::link_scan()).subtitle(words::link_scan_intro()).activatable(true).visible(false).build();
        scan.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
        add_device.add(&show);
        add_device.add(&scan);
        let limit = adw::EntryRow::builder().title(words::account_settings_storage_limit()).show_apply_button(true).build();
        limit.set_input_purpose(gtk::InputPurpose::Number);
        let unit = gtk::DropDown::builder().valign(gtk::Align::Center).build();
        unit.update_property(&[gtk::accessible::Property::Label(&words::account_settings_storage_limit())]);
        limit.add_suffix(&unit);
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
            connection: RefCell::new(None),
            add_device,
            limit: limit.clone(),
            unit: unit.clone(),
            units: RefCell::new(Vec::new()),
            limit_group: RefCell::new(None),
            limit_drawn: RefCell::new(None),
            drawing: Cell::new(false),
            storage_watch: RefCell::new(None),
            remove_all_ask: RefCell::new(None),
        });
        // the limit typed, set in the unit chosen (the core parses it and says why where it is no size)
        let weak = Rc::downgrade(&p);
        limit.connect_apply(move |row| {
            let Some(p) = weak.upgrade() else { return };
            let amount = row.text().to_string();
            let unit = p.chosen_unit().unwrap_or(LimitUnit::Gb);
            p.act("storage limit", move |settings| Call::SettingsOfflineLimit { settings, amount, unit });
        });
        // a unit chosen sets what the field holds in it; Off turns the automatic tier off
        let weak = Rc::downgrade(&p);
        unit.connect_selected_notify(move |_| {
            let Some(p) = weak.upgrade() else { return };
            if p.drawing.get() {
                return;
            }
            match p.chosen_unit() {
                None => p.act("storage limit off", |settings| Call::SettingsOfflineLimitOff { settings }),
                Some(unit) => {
                    let amount = p.limit.text().to_string();
                    if !amount.trim().is_empty() {
                        p.act("storage limit", move |settings| Call::SettingsOfflineLimit { settings, amount, unit });
                    }
                }
            }
        });
        let weak = Rc::downgrade(&p);
        show.connect_activated(move |_| {
            if let Some(p) = weak.upgrade() {
                p.link(|connection| Call::LinkShowForNewDevice { connection });
            }
        });
        let weak = Rc::downgrade(&p);
        scan.connect_activated(move |_| {
            if let Some(p) = weak.upgrade() {
                p.link(|connection| Call::LinkScanSignInCode { connection });
            }
        });
        // the scan's row only where the Camera portal names a camera (ADR 0141 decision 4: no camera, no entry)
        let row = scan.downgrade();
        glib::spawn_future_local(async move {
            let present = crate::qr_camera::camera_present().await;
            eprintln!("veyq: Settings' link scan {}", if present { "offered" } else { "not offered: no camera" });
            if let Some(row) = row.upgrade() {
                row.set_visible(present);
            }
        });
        let weak = Rc::downgrade(&p);
        rename.connect_apply(move |row| {
            let Some(p) = weak.upgrade() else { return };
            let label = row.text().to_string();
            p.act("rename", move |settings| Call::SettingsRename { settings, label });
        });
        p
    }

    /// Opens the device link [make] builds for this page's account over the dialog (ADR 0059).
    fn link(&self, make: impl FnOnce(String) -> Call) {
        let Some(connection) = self.connection.borrow().clone() else {
            eprintln!("veyq: settings add a device: no account in the core");
            return;
        };
        crate::device_link::open(&self.dialog, make(connection));
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

    /// The unit the drop-down shows; None where it shows Off.
    fn chosen_unit(&self) -> Option<LimitUnit> {
        self.units.borrow().get(self.unit.selected() as usize).copied()
    }

    /// Draws [s]: every group anew, the name's row kept, the page left where it was scrolled to (the storage section
    /// redraws it at each change of a pin's fetch).
    fn draw(self: &Rc<Self>, s: &SettingsShown) {
        let scroll = self.page.first_child().and_downcast::<gtk::ScrolledWindow>().map(|w| w.vadjustment());
        let at = scroll.as_ref().map(|a| a.value());
        self.draw_groups(s);
        if let (Some(a), Some(at)) = (scroll, at.filter(|v| *v > 0.0)) {
            a.set_value(at);
            // the groups' new heights arrive with the next allocation, and a closing dialog hands the focus back to a row
            // the redraw replaced (the page then scrolls to whatever takes it): set it again after both
            let again = a.clone();
            glib::idle_add_local_once(move || again.set_value(at));
            glib::timeout_add_local_once(std::time::Duration::from_millis(250), move || a.set_value(at));
        }
    }

    fn draw_groups(self: &Rc<Self>, s: &SettingsShown) {
        if let Some(g) = self.rename_group.borrow_mut().take() {
            g.remove(&self.rename);
        }
        if let Some(g) = self.limit_group.borrow_mut().take() {
            g.remove(&self.limit);
        }
        for g in self.groups.borrow_mut().drain(..) {
            self.page.remove(&g);
        }
        let add_device = s.add_device.then(|| self.add_device.clone());
        let groups = vec![self.account(s), self.device(s), add_device, self.sessions(s), self.saved_keys(s), self.storage(s), self.offline_items(s), self.sign_out()];
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
        self.draw_remove_all(s.asking_remove_all);
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

    /// "Storage on this device": the limit, the sizes, "Clear cache" and "Remove all offline content"; where this device
    /// keeps no store of the account, why — or nothing where there is nothing to say.
    fn storage(self: &Rc<Self>, s: &SettingsShown) -> Option<adw::PreferencesGroup> {
        let group = adw::PreferencesGroup::builder().title(words::account_settings_storage()).build();
        let Some(st) = &s.storage else {
            let why = s.storage_unavailable.as_ref()?;
            group.set_description(Some(&said::key(why)));
            return Some(group);
        };
        group.set_description(Some(&words::account_settings_storage_limit_hint()));
        self.draw_limit(s, st);
        group.add(&self.limit);
        *self.limit_group.borrow_mut() = Some(group.clone());
        let sizes = [
            (words::account_settings_storage_automatic(), Some(st.automatic)),
            (words::account_settings_storage_pinned(), Some(st.pinned)),
            (words::account_settings_storage_free(), st.free),
        ];
        for (title, bytes) in sizes {
            let Some(bytes) = bytes else { continue };
            let row = adw::ActionRow::builder().title(title).build();
            let size = gtk::Label::builder().label(said::size(bytes)).valign(gtk::Align::Center).build();
            size.add_css_class("dim-label");
            size.add_css_class("numeric");
            row.add_suffix(&size);
            group.add(&row);
        }
        let clear = adw::ButtonRow::builder().title(words::account_settings_storage_clear_cache()).build();
        let weak = Rc::downgrade(self);
        clear.connect_activated(move |_| {
            if let Some(p) = weak.upgrade() {
                p.act("clear cache", |settings| Call::SettingsClearCache { settings });
            }
        });
        group.add(&clear);
        let remove_all = adw::ButtonRow::builder().title(words::account_settings_storage_remove_all()).build();
        remove_all.add_css_class("destructive-action");
        let weak = Rc::downgrade(self);
        remove_all.connect_activated(move |_| {
            if let Some(p) = weak.upgrade() {
                // the core asks first (`askingRemoveAll`), drawn by `draw_remove_all`
                p.act("ask remove all", |settings| Call::SettingsAskRemoveAll { settings });
            }
        });
        group.add(&remove_all);
        Some(group)
    }

    /// The limit's field and its drop-down from the core's [st], set only where the core's changed and not while typed.
    fn draw_limit(&self, s: &SettingsShown, st: &Storage) {
        let units: Vec<LimitUnit> = s.limit_units.iter().map(|u| u.unit).collect();
        if *self.units.borrow() != units || self.unit.model().is_none() {
            let mut labels: Vec<String> = s.limit_units.iter().map(|u| said::key(&u.word)).collect();
            labels.push(words::account_settings_storage_limit_off());
            self.drawing.set(true);
            self.unit.set_model(Some(&gtk::StringList::new(&labels.iter().map(String::as_str).collect::<Vec<_>>())));
            self.drawing.set(false);
            *self.units.borrow_mut() = units;
            self.limit_drawn.borrow_mut().take();
        }
        if self.limit_drawn.borrow().as_ref() == Some(&st.field) {
            return;
        }
        let typing = self.limit.state_flags().contains(gtk::StateFlags::FOCUS_WITHIN) && self.limit_drawn.borrow().is_some();
        if !typing {
            self.limit.set_text(&st.field.amount);
        }
        let at = if st.field.off { self.units.borrow().len() } else { self.units.borrow().iter().position(|u| *u == st.field.unit).unwrap_or(0) };
        self.drawing.set(true);
        self.unit.set_selected(at as u32);
        self.drawing.set(false);
        *self.limit_drawn.borrow_mut() = Some(st.field.clone());
    }

    /// The items available offline, each with its status and size, removable; said empty where there is none.
    fn offline_items(self: &Rc<Self>, s: &SettingsShown) -> Option<adw::PreferencesGroup> {
        let st = s.storage.as_ref()?;
        let group = adw::PreferencesGroup::builder().title(words::account_settings_storage_pinned()).build();
        if st.items.is_empty() {
            let empty = adw::ActionRow::builder().title(words::account_settings_storage_items_empty()).build();
            empty.add_css_class("dim-label");
            group.add(&empty);
        }
        for item in &st.items {
            group.add(&self.offline_item(item));
        }
        Some(group)
    }

    fn offline_item(self: &Rc<Self>, item: &OfflineItem) -> adw::ActionRow {
        let subtitle = item_line(item);
        let row = adw::ActionRow::builder().title(said::notice(&item.title)).subtitle(subtitle).use_markup(false).build();
        row.add_prefix(&gtk::Image::from_icon_name(crate::home::kind_icon(&item.kind)));
        let remove = gtk::Button::builder().icon_name("user-trash-symbolic").valign(gtk::Align::Center).tooltip_text(words::account_settings_storage_remove_item()).build();
        remove.update_property(&[gtk::accessible::Property::Label(&words::account_settings_storage_remove_item())]);
        remove.add_css_class("flat");
        let (weak, node) = (Rc::downgrade(self), item.node.clone());
        remove.connect_clicked(move |_| {
            if let Some(p) = weak.upgrade() {
                let node = node.clone();
                p.act("remove an item", move |settings| Call::SettingsOfflineRemove { settings, node });
            }
        });
        row.add_suffix(&remove);
        row
    }

    /// "Remove all offline content"'s question while the core asks it: Remove all, or Cancel; gone with the asking.
    fn draw_remove_all(self: &Rc<Self>, asking: bool) {
        if !asking {
            if let Some(ask) = self.remove_all_ask.borrow_mut().take() {
                ask.force_close();
            }
            return;
        }
        if self.remove_all_ask.borrow().is_some() {
            return;
        }
        let ask = adw::AlertDialog::builder()
            .heading(words::account_settings_storage_remove_all())
            .body(words::account_settings_storage_remove_all_question())
            .build();
        ask.add_responses(&[("cancel", &words::signin_cancel()), ("accept", &words::account_settings_storage_remove_all_confirm())]);
        ask.set_response_appearance("accept", adw::ResponseAppearance::Destructive);
        ask.set_default_response(Some("cancel"));
        ask.set_close_response("cancel");
        let weak = Rc::downgrade(self);
        ask.connect_response(None, move |_, response| {
            let Some(p) = weak.upgrade() else { return };
            p.remove_all_ask.borrow_mut().take();
            if response == "accept" {
                p.act("remove all", |settings| Call::SettingsRemoveAll { settings });
            } else {
                p.act("cancel remove all", |settings| Call::SettingsCancelRemoveAll { settings });
            }
        });
        ask.present(Some(&self.dialog));
        *self.remove_all_ask.borrow_mut() = Some(ask);
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

/// An item's line under its name: its status as the core says it, and the size it keeps on this device.
pub fn item_line(item: &OfflineItem) -> String {
    let size = said::size(item.bytes);
    match &item.said {
        Some(n) => format!("{} · {size}", said::notice(n)),
        None => size,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bridge::wire::{Notice, OfflineStatus};

    #[test]
    fn an_offline_items_line_says_its_status_and_its_size() {
        let item = |said: Option<Notice>| OfflineItem {
            tenant: "t".into(), scope: "s".into(), node: "n".into(), kind: "MEMORY".into(),
            title: Notice { msg: "offline_item_named".into(), args: vec![] }, status: OfflineStatus::Kept, said, bytes: 7_000_000,
        };
        let kept = item_line(&item(Some(Notice { msg: "offline_status_kept".into(), args: vec![] })));
        assert!(kept.starts_with(&words::offline_status_kept()), "{kept}");
        assert!(kept.ends_with(&said::size(7_000_000)), "{kept}");
        assert_eq!(item_line(&item(None)), said::size(7_000_000));
    }

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
