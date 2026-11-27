//! The account's Settings (`uc.account.sessions`; the web's Settings hub, its `SessionsPanel` the reference), GNOME's
//! way: an `AdwPreferencesDialog` opened from the sidebar's Settings, as "Manage servers" is ([crate::core_ui::manage]). One page
//! of groups — the core's sections (`SettingsShown.sections`), each drawn, in its order: the web's (ADR 0154 decision 2) —,
//! each titled as the web's sections are:
//!
//! - **Account**: the address this account signs in with, and whether the server holds it confirmed;
//! - **This device**: its name in the account's device register, renamed in an `AdwEntryRow` with apply; how it stays
//!   signed in and its change (the levels and the way back to the password, Manage's own rows), "Stop trusting this
//!   device";
//! - **Devices and sessions**: every session family, this device's first and marked, each named from the register or
//!   by its dates, its sign-out (and, where the core offers it, "Sign out and remove saved key") in the row's menu, every
//!   step asked first in an `AdwAlertDialog`; "Sign out everywhere else";
//! - **Add a device** (only while the core offers it, `addDevice`: `core.device-linking`): "Show a code" and, where the
//!   Camera portal names a camera, "Scan a code" — each a ceremony of [crate::core_ui::device_link] over this dialog;
//! - **Devices with a saved key**: each removable, and "Remove every saved key except this device's";
//! - **Storage on this device** (ADR 0140 decision 10): the limit of what is kept of content viewed — a free size in an
//!   `AdwEntryRow` with apply, its unit or Off in a drop-down beside it —, the sizes the automatic tier and the items
//!   available offline take and the volume's free space, "Clear cache", "Remove all offline content" (asked first, the
//!   core's `askingRemoveAll`), and a group of the items available offline, each with its status and size and removable;
//!   followed live while the dialog shows (`Watched.SettingsStorageLive`). Where this device keeps no store of the account,
//!   the group says why (`storageUnavailable`);
//! - **Sign out** of this account here.
//!
//! Opened from a search's settings result (ADR 0146 decision 9), the page scrolls to that section: each group is named by
//! its `SettingsSection.id` (`settings-<id>`).
//!
//! Theme and language follow the system (D3): no row for them. Every state is the core's (`Settings.Open`, `Watched.Settings`: `SettingsShown`), every action one call;
//! why one did not come through is the state's `refusal`, said as a toast.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::LimitField, wire::LimitUnit, wire::OfflineItem, wire::QueuedAsk, wire::QueuedLoss, wire::SettingsSection,
    wire::KitStatusStanding, wire::SettingsFamily, wire::SettingsShown, wire::Storage, wire::Update, wire::Watched};
use crate::core_ui::manage::{self, Change};
use crate::core_ui::map_services::MapServices;
use crate::core_ui::{said, sign_in, words};

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
    /// What the core asks before it while writes made offline wait (`Queued.Ask`, ADR 0153 decision 2): its question.
    remove_all_queued: RefCell<Option<QueuedAsk>>,
    /// Map services (ADR 0143 decision 2): this device's consent, followed while the dialog shows; built once.
    maps: MapServices,
    /// The section a search's result asked for (`SettingsSection.id`), scrolled to once the page first drew.
    at: RefCell<Option<String>>,
    /// The page's own `GtkScrolledWindow`, held once found (inbox #957: the page's first child is not it, so a redraw
    /// read no position and the page jumped to its top at every storage update).
    scroll: RefCell<Option<gtk::ScrolledWindow>>,
}

/// Opens [connection]'s Settings over [window]; the core reads and delivers what it shows. [at]: the section it scrolls to
/// (a search's settings result, `SettingsSection.id`).
pub fn open(window: &adw::ApplicationWindow, connection: String, at: Option<String>) {
    let page = Page::new();
    page.at.replace(at);
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

/// The debug harness's door ([crate::core_ui::dev_settings]): [shown] drawn as the core would send it, with no page in the core
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
            remove_all_queued: RefCell::new(None),
            maps: MapServices::new(),
            at: RefCell::new(None),
            scroll: RefCell::new(None),
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
            let present = crate::core_ui::qr_camera::camera_present().await;
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
        crate::core_ui::device_link::open(&self.dialog, make(connection));
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

    /// What the core asks before [loss] while writes made offline wait (`Queued.Ask`); None: none waits, nothing to ask.
    async fn queued_ask(loss: QueuedLoss) -> Option<QueuedAsk> {
        match bridge::call(&Call::QueuedAsk { loss }).await.map(|(a, _)| a) {
            Ok(Answer::QueuedAsked { ask }) => ask,
            other => {
                eprintln!("veyq: Queued.Ask answered {other:?}");
                None
            }
        }
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
        let scroll = self.scrolled().map(|w| w.vadjustment());
        let at = scroll.as_ref().map(|a| a.value());
        self.draw_groups(s);
        if let Some(section) = self.at.borrow_mut().take() {
            self.scroll_to(section);
        }
        if let (Some(a), Some(at)) = (scroll, at.filter(|v| *v > 0.0)) {
            a.set_value(at);
            // the groups' new heights arrive with the next allocation, and a closing dialog hands the focus back to a row
            // the redraw replaced (the page then scrolls to whatever takes it): set it again after both
            let again = a.clone();
            glib::idle_add_local_once(move || again.set_value(at));
            glib::timeout_add_local_once(std::time::Duration::from_millis(250), move || a.set_value(at));
        }
    }

    /// The `GtkScrolledWindow` inside the page's template, found once among its descendants and held: libadwaita does
    /// not hand it out, and which child of the page it is changed between its versions (a banner's box came first).
    fn scrolled(&self) -> Option<gtk::ScrolledWindow> {
        if let Some(held) = self.scroll.borrow().as_ref() {
            return Some(held.clone());
        }
        let found = find_scrolled(self.page.upcast_ref());
        self.scroll.replace(found.clone());
        found
    }

    /// Scrolls the page to [section]'s group once it is laid out; a section this page does not show leaves it at its top.
    /// The group is looked up at each try, not now: every draw builds the groups anew (the storage section's updates follow
    /// the page's at once), and a group just built has no place until the next allocation — so it is tried every 100 ms
    /// until the group's top is reached — a page still growing (the sessions arrive after it opened) clamps it short, and
    /// the next try goes on — for at most two seconds.
    fn scroll_to(self: &Rc<Self>, section: String) {
        let weak = Rc::downgrade(self);
        let tries = Cell::new(0);
        glib::timeout_add_local(std::time::Duration::from_millis(100), move || {
            let Some(page) = weak.upgrade() else { return glib::ControlFlow::Break };
            tries.set(tries.get() + 1);
            let name = format!("settings-{section}");
            let Some(group) = page.groups.borrow().iter().find(|g| g.widget_name() == name.as_str()).cloned() else {
                eprintln!("veyq: settings shows no section {section}");
                return glib::ControlFlow::Break;
            };
            let scroll = page.scrolled();
            let a = scroll.as_ref().map(gtk::ScrolledWindow::vadjustment);
            // the group's top in the view, plus where the view stands: its place in what scrolls
            let at = scroll.as_ref().and_then(|s| s.child()).and_then(|view| group.compute_point(&view, &gtk::graphene::Point::new(0.0, 0.0)));
            match (a, at) {
                (Some(a), Some(at)) if group.height() > 0 && a.upper() > a.page_size() => {
                    let wanted = a.value() + f64::from(at.y());
                    let to = wanted.min(a.upper() - a.page_size()).max(0.0);
                    a.set_value(to);
                    if to < wanted && tries.get() < 20 {
                        return glib::ControlFlow::Continue;
                    }
                    eprintln!("veyq: settings scrolled to {section} at {to:.0} of {:.0} (its top at {wanted:.0})", a.upper() - a.page_size());
                    glib::ControlFlow::Break
                }
                _ if tries.get() >= 20 => {
                    eprintln!("veyq: settings' {section} was not laid out in time");
                    glib::ControlFlow::Break
                }
                _ => glib::ControlFlow::Continue,
            }
        });
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
        // the core's sections (`SettingsShown.sections`, ADR 0154 decision 2: the web's order), each drawn as its groups;
        // a section's first group named by its `SettingsSection.id`, where a search's result scrolls to it
        let mut groups = Vec::new();
        for section in &s.sections {
            let (id, drawn) = match section {
                // a desktop's theme and language follow the system (D3): the core offers no appearance here
                SettingsSection::Appearance => ("appearance", vec![]),
                SettingsSection::Maps => ("maps", vec![Some(self.maps.group().clone())]),
                SettingsSection::Storage => ("device-storage", vec![self.storage(s), self.offline_items(s)]),
                SettingsSection::Sessions => ("sessions", vec![self.sessions(s)]),
                SettingsSection::Device => ("device", vec![self.device(s)]),
                SettingsSection::SavedKeys => ("saved-keys", vec![self.saved_keys(s)]),
                SettingsSection::Account => ("account", vec![self.account(s)]),
                SettingsSection::Email => ("email", vec![self.email()]),
                SettingsSection::Password => ("password", vec![self.password()]),
                SettingsSection::AddDevice => ("add-device", vec![Some(self.add_device.clone())]),
                SettingsSection::Security => ("security", vec![self.security(), self.kit(s)]),
                SettingsSection::Profile => ("profile", vec![self.profile()]),
                SettingsSection::Erase => ("erase", vec![self.erase()]),
                SettingsSection::Members => ("tenant-members", vec![self.members()]),
                SettingsSection::WhosWho => ("tenant-people", vec![self.whos_who()]),
                SettingsSection::Terms => ("terms", vec![self.terms()]),
                SettingsSection::Groups => ("groups", vec![self.groups()]),
                SettingsSection::GroupInvitations => ("group-invitations", vec![self.group_invitations()]),
                SettingsSection::SignOut => ("sign-out", vec![self.sign_out()]),
            };
            for (i, g) in drawn.into_iter().flatten().enumerate() {
                if i == 0 {
                    g.set_widget_name(&format!("settings-{id}"));
                }
                self.page.add(&g);
                groups.push(g);
            }
        }
        eprintln!("veyq: settings sections: {}", s.sections.iter().map(|x| format!("{x:?}")).collect::<Vec<_>>().join(","));
        *self.groups.borrow_mut() = groups;
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
                self.dialog.add_toast(said::word_toast(why, &said::key(why)));
            }
            *self.refusal.borrow_mut() = s.refusal.clone();
        }
    }

    fn account(self: &Rc<Self>, s: &SettingsShown) -> Option<adw::PreferencesGroup> {
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

    /// *Email address*: the address changed (ADR 0148 decision 6: the request here, the confirm on the web).
    fn email(self: &Rc<Self>) -> Option<adw::PreferencesGroup> {
        let group = adw::PreferencesGroup::builder().title(words::account_settings_email()).build();
        group.add(&self.opens("settings-email-change", &words::email_change_action(), crate::core_ui::account_flows::address));
        Some(group)
    }

    /// A row that opens one of the account's flows ([open]) for this page's account, over the window: the flow outlives
    /// this dialog (a finished rotation re-opens the tenant, which closes Settings).
    fn opens(self: &Rc<Self>, name: &str, title: &str, open: fn(&gtk::Widget, String)) -> adw::ActionRow {
        let row = adw::ActionRow::builder().title(title).activatable(true).build();
        row.set_widget_name(name);
        row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
        let (weak, what) = (Rc::downgrade(self), name.to_owned());
        row.connect_activated(move |_| {
            let Some(p) = weak.upgrade() else { return };
            let Some(connection) = p.connection.borrow().clone() else {
                return eprintln!("veyq: settings {what}: no account in the core");
            };
            open(&p.window_or_dialog(), connection);
        });
        row
    }

    fn window_or_dialog(&self) -> gtk::Widget {
        self.dialog.root().map(|r| r.upcast::<gtk::Widget>()).unwrap_or_else(|| self.dialog.clone().upcast())
    }

    /// The open space's members (the web's tenant section `tenant-members`): a subpage; what an act says is the dialog's toast.
    fn members(self: &Rc<Self>) -> Option<adw::PreferencesGroup> {
        let group = adw::PreferencesGroup::builder().title(words::members_title()).description(words::members_sub()).build();
        let row = adw::ActionRow::builder().title(words::members_title()).activatable(true).build();
        row.set_widget_name("settings-members-open");
        row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
        let weak = Rc::downgrade(self);
        row.connect_activated(move |_| {
            if let Some(p) = weak.upgrade() {
                let dialog = p.dialog.clone();
                p.dialog.push_subpage(&crate::core_ui::members::page(Rc::new(move |t| dialog.add_toast(t))));
            }
        });
        group.add(&row);
        Some(group)
    }

    /// The open space's terms, an owner's to edit (uc.admin.tenant-terms): a subpage of the dialog, as GNOME Settings' are.
    fn terms(self: &Rc<Self>) -> Option<adw::PreferencesGroup> {
        let group = adw::PreferencesGroup::builder().title(words::terms_settings_title()).description(words::terms_editor_sub()).build();
        let row = adw::ActionRow::builder().title(words::terms_settings_title()).activatable(true).build();
        row.set_widget_name("settings-terms-open");
        row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
        let weak = Rc::downgrade(self);
        row.connect_activated(move |_| {
            if let Some(p) = weak.upgrade() {
                let dialog = p.dialog.clone();
                p.dialog.push_subpage(&crate::core_ui::terms::page(std::rc::Rc::new(move |t| dialog.add_toast(t))));
            }
        });
        group.add(&row);
        Some(group)
    }

    /// The caller's groups (the web's `groups` category): a subpage listing them, each group's page pushed over it.
    fn groups(self: &Rc<Self>) -> Option<adw::PreferencesGroup> {
        self.subpage("settings-groups-open", &words::groups_title(), crate::core_ui::groups::page)
    }

    /// The caller's group invitations (the web's `/invitations`): a subpage with *Accept* and *Decline*.
    fn group_invitations(self: &Rc<Self>) -> Option<adw::PreferencesGroup> {
        self.subpage("settings-group-invitations-open", &words::invitations_title(), crate::core_ui::groups::invitations)
    }

    /// A section that is one row opening a subpage of the dialog ([make] over the dialog's toasts and subpages).
    fn subpage(self: &Rc<Self>, name: &str, title: &str, make: fn(crate::core_ui::groups::Host) -> adw::NavigationPage) -> Option<adw::PreferencesGroup> {
        let group = adw::PreferencesGroup::builder().title(title).build();
        let row = adw::ActionRow::builder().title(title).activatable(true).build();
        row.set_widget_name(name);
        row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
        let weak = Rc::downgrade(self);
        row.connect_activated(move |_| {
            if let Some(p) = weak.upgrade() {
                p.dialog.push_subpage(&make(crate::core_ui::groups::Host::of(&p.dialog)));
            }
        });
        group.add(&row);
        Some(group)
    }

    /// Who's who in the open space (the web's tenant section `tenant-people`, inbox #967): a subpage; a person chosen
    /// there closes the dialog for their profile in the shell.
    fn whos_who(self: &Rc<Self>) -> Option<adw::PreferencesGroup> {
        let group = adw::PreferencesGroup::builder().title(words::whos_who_title()).description(words::whos_who_sub()).build();
        let row = adw::ActionRow::builder().title(words::whos_who_title()).activatable(true).build();
        row.set_widget_name("settings-whos-who-open");
        row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
        let weak = Rc::downgrade(self);
        row.connect_activated(move |_| {
            if let Some(p) = weak.upgrade() {
                let dialog = p.dialog.clone();
                p.dialog.push_subpage(&crate::core_ui::whos_who::page(Rc::new(move || {
                    dialog.close();
                })));
            }
        });
        group.add(&row);
        Some(group)
    }

    /// *Your profile* (the web's rubric): one's own profile in the space open now, the same model as one's own page's.
    fn profile(self: &Rc<Self>) -> Option<adw::PreferencesGroup> {
        let group = adw::PreferencesGroup::builder().title(words::profile_title()).description(words::profile_sub()).build();
        let row = adw::ActionRow::builder().title(words::profile_edit()).activatable(true).build();
        row.set_widget_name("settings-profile-edit");
        row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
        let weak = Rc::downgrade(self);
        row.connect_activated(move |_| {
            if let Some(p) = weak.upgrade() {
                crate::core_ui::profile_edit::open(&p.window_or_dialog());
            }
        });
        group.add(&row);
        Some(group)
    }

    fn password(self: &Rc<Self>) -> Option<adw::PreferencesGroup> {
        let group = adw::PreferencesGroup::new();
        group.add(&self.opens("settings-password-change", &words::password_change_title(), crate::core_ui::account_flows::password));
        Some(group)
    }

    /// *Secure your account*: the keys replaced with a new Emergency Kit.
    fn security(self: &Rc<Self>) -> Option<adw::PreferencesGroup> {
        let group = adw::PreferencesGroup::builder().title(words::rotation_title()).description(words::rotation_sub()).build();
        group.add(&self.opens("settings-rotation", &words::rotation_action(), crate::core_ui::account_flows::rotation));
        Some(group)
    }

    /// Which Emergency Kit the account has (ADR 0157 decision 5): current, or a clearer one exists — what it does better,
    /// that the kit the person has keeps working, *Make a new kit* — or that it could not be read; nothing until read.
    fn kit(self: &Rc<Self>, s: &SettingsShown) -> Option<adw::PreferencesGroup> {
        let kit = s.kit?;
        let description = match kit {
            KitStatusStanding::Current => words::kit_status_current(),
            KitStatusStanding::Unreadable => words::kit_status_unreadable(),
            KitStatusStanding::Older => format!(
                "{} {} {}", words::kit_status_older(), s.kit_news.as_ref().map(said::notice).unwrap_or_default(), words::kit_status_keeps_working(),
            ),
        };
        let group = adw::PreferencesGroup::builder().title(words::kit_status_title()).description(description).build();
        group.set_widget_name("settings-kit");
        if kit == KitStatusStanding::Older {
            group.add(&self.opens("settings-kit-make", &words::kit_status_make(), crate::core_ui::account_flows::kit_renewal));
        }
        Some(group)
    }

    /// *Erase your account*.
    fn erase(self: &Rc<Self>) -> Option<adw::PreferencesGroup> {
        let group = adw::PreferencesGroup::builder().title(words::erasure_title()).build();
        let row = self.opens("settings-erasure", &words::erasure_action(), crate::core_ui::account_flows::erasure);
        row.add_css_class("error");
        group.add(&row);
        Some(group)
    }

    /// Renames [family]'s device (the web's `renameDeviceOfFamily`: every session of that device shows the name), asked in
    /// an `AdwAlertDialog` with the name as it stands.
    fn ask_rename(self: &Rc<Self>, family: String, label: &str) {
        let entry = adw::EntryRow::builder().title(words::account_settings_rename()).build();
        entry.set_text(label);
        let rows = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
        rows.add_css_class("boxed-list");
        rows.append(&entry);
        let ask = adw::AlertDialog::builder().heading(words::account_settings_rename()).body(words::account_settings_rename_hint()).extra_child(&rows).build();
        ask.add_responses(&[("cancel", &words::signin_cancel()), ("rename", &words::node_rename())]);
        ask.set_response_appearance("rename", adw::ResponseAppearance::Suggested);
        ask.set_default_response(Some("rename"));
        ask.set_close_response("cancel");
        entry.set_activates_default(true);
        let weak = Rc::downgrade(self);
        ask.connect_response(None, move |_, response| {
            if response != "rename" {
                return;
            }
            let label = entry.text().trim().to_string();
            if let (Some(p), false) = (weak.upgrade(), label.is_empty()) {
                let family = family.clone();
                p.act("rename a device", move |settings| Call::SettingsRenameFamily { settings, family, label });
            }
        });
        ask.present(Some(&self.dialog));
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
                    // asked first, as the phones ask; while writes made offline wait, the core's question names them
                    glib::spawn_future_local(async move {
                        let (body, accept) = match Self::queued_ask(QueuedLoss::StopTrusting).await {
                            Some(q) => (said::notice(&q.question), said::key(&q.confirm)),
                            None => (words::account_settings_untrust_confirm(), words::manage_forget_device()),
                        };
                        p.ask_then(words::account_settings_untrust_confirm_title(), body, accept, "stop trusting", |settings| Call::SettingsStopTrusting { settings });
                    });
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
            if !current {
                let heading = words::account_settings_revoke();
                p.ask_then(heading.clone(), words::account_settings_revoke_confirm(), heading, "sign out", move |settings| Call::SettingsSignOut { settings, family });
                return;
            }
            // this device's own family is a sign-out here: while writes made offline wait, the core's question in place
            // of the plain one (ADR 0153 decision 2)
            glib::spawn_future_local(async move {
                let heading = words::account_settings_revoke_self();
                let (body, accept) = match Self::queued_ask(QueuedLoss::SignOut).await {
                    Some(q) => (said::notice(&q.question), said::key(&q.confirm)),
                    None => (words::account_settings_revoke_self_confirm(), heading.clone()),
                };
                p.ask_then(heading, body, accept, "sign out", move |settings| Call::SettingsSignOut { settings, family });
            });
        });
        actions.add_action(&revoke);
        // any device the register names is renamed here (ADR 0148 decision 1); this device's own name is the row above
        if let (false, Some(label)) = (f.current, f.label.clone()) {
            let rename = gio::SimpleAction::new("rename", None);
            let (weak, id) = (Rc::downgrade(self), f.id.clone());
            rename.connect_activate(move |_, _| {
                if let Some(p) = weak.upgrade() {
                    p.ask_rename(id.clone(), &label);
                }
            });
            actions.add_action(&rename);
            menu.append(Some(&words::account_settings_rename()), Some("family.rename"));
        }
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
            (words::account_settings_storage_automatic(), Some(&st.automatic_text)),
            (words::account_settings_storage_pinned(), Some(&st.pinned_text)),
            (words::account_settings_storage_free(), st.free_text.as_ref()),
        ];
        for (title, text) in sizes {
            let Some(text) = text else { continue };
            let row = adw::ActionRow::builder().title(title).build();
            let size = gtk::Label::builder().label(text.as_str()).valign(gtk::Align::Center).build();
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
                // the core asks first (`askingRemoveAll`), drawn by `draw_remove_all` — in its own words while writes made
                // offline wait (`Queued.Ask`)
                glib::spawn_future_local(async move {
                    *p.remove_all_queued.borrow_mut() = Self::queued_ask(QueuedLoss::RemoveAll).await;
                    p.act("ask remove all", |settings| Call::SettingsAskRemoveAll { settings });
                });
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
        row.add_prefix(&gtk::Image::from_icon_name(crate::core_ui::home::kind_icon(&item.kind)));
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
        let queued = self.remove_all_queued.borrow_mut().take();
        let (body, accept) = match &queued {
            Some(q) => (said::notice(&q.question), said::key(&q.confirm)),
            None => (words::account_settings_storage_remove_all_question(), words::account_settings_storage_remove_all_confirm()),
        };
        let ask = adw::AlertDialog::builder().heading(words::account_settings_storage_remove_all()).body(body).build();
        ask.add_responses(&[("cancel", &words::signin_cancel()), ("accept", &accept)]);
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
                // while writes made offline wait, the core asks first (ADR 0153 decision 2): *Sign out anyway* deletes them
                glib::spawn_future_local(async move {
                    match Self::queued_ask(QueuedLoss::SignOut).await {
                        Some(q) => p.ask_then(words::drawer_sign_out(), said::notice(&q.question), said::key(&q.confirm), "sign out here", |settings| Call::SettingsSignOutHere { settings }),
                        None => p.act("sign out here", |settings| Call::SettingsSignOutHere { settings }),
                    }
                });
            }
        });
        group.add(&row);
        Some(group)
    }
}

/// The first `GtkScrolledWindow` under [widget], depth first.
fn find_scrolled(widget: &gtk::Widget) -> Option<gtk::ScrolledWindow> {
    let mut child = widget.first_child();
    while let Some(c) = child {
        if let Ok(s) = c.clone().downcast::<gtk::ScrolledWindow>() {
            return Some(s);
        }
        if let Some(s) = find_scrolled(&c) {
            return Some(s);
        }
        child = c.next_sibling();
    }
    None
}

/// A family's dates as its row says them: when it signed in and when it was last used, the ones the server sent.
pub fn family_dates(f: &SettingsFamily) -> String {
    let lines: Vec<String> = [f.created_text.as_deref().map(words::account_settings_signed_in), f.last_used_text.as_deref().map(words::account_settings_last_used)]
        .into_iter()
        .flatten()
        .collect();
    lines.join("\n")
}

/// An item's line under its name: its status as the core says it, and the size it keeps on this device.
pub fn item_line(item: &OfflineItem) -> String {
    match &item.said {
        Some(n) => said::joined(&[said::notice(n), item.bytes_text.clone()]),
        None => item.bytes_text.clone(),
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
            title: Notice { msg: "offline_item_named".into(), args: vec![], kind: crate::bridge::wire::NoticeKind::Held }, status: OfflineStatus::Kept, said, bytes: 7_000_000,
            bytes_text: "7 MB".into(),
        };
        let kept = item_line(&item(Some(Notice { msg: "offline_status_kept".into(), args: vec![], kind: crate::bridge::wire::NoticeKind::Held })));
        assert!(kept.starts_with(&words::offline_status_kept()), "{kept}");
        assert!(kept.ends_with("7 MB"), "the core's size, as it came: {kept}");
        assert_eq!(item_line(&item(None)), "7 MB");
    }

    fn family(created_at: Option<i64>, last_used_at: Option<i64>) -> SettingsFamily {
        let text = |t: Option<i64>| t.map(|t| format!("at {t}"));
        SettingsFamily { id: "f".into(), created_at, last_used_at, current: false, label: None, removes_key: false, created_text: text(created_at), last_used_text: text(last_used_at) }
    }

    #[test]
    fn a_familys_row_says_the_dates_the_server_sent() {
        let both = family_dates(&family(Some(1_793_527_200_000), Some(1_794_475_800_000)));
        assert_eq!(both.lines().count(), 2, "{both}");
        assert!(both.starts_with(&words::account_settings_signed_in("").trim_end().to_string()), "{both}");
        assert!(both.contains("at 1793527200000") && both.contains("at 1794475800000"), "the core's moments, as they came: {both}");
        assert_eq!(family_dates(&family(None, Some(1_794_475_800_000))).lines().count(), 1);
        assert_eq!(family_dates(&family(None, None)), "");
    }
}
