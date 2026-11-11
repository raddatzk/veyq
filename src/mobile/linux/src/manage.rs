//! "Manage servers" (owner 2026-11-08; the CR device-trust-on-linux-and-windows, D5), GNOME's way: an
//! `AdwPreferencesDialog` opened from the switcher. One group per server signed in to — each tenant an expander row
//! whose switch shows or hides it in the switcher, its spaces switch rows inside; how this device trusts the server as
//! the group's line; sign out and "Stop trusting this device" as button rows — and "Add server" last. What it shows is
//! the core's (`Watched.Manage`, `SessionModel.managed`); each switch and button is one call.
use std::cell::{Cell, RefCell};
use std::collections::HashSet;
use std::rc::Rc;

use adw::prelude::*;

use crate::bridge::{self, wire::Call, wire::ManagedShown, wire::Method};
use crate::{said, words};

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
        match server.trusted {
            Some(Method::DeviceKey) => group.set_description(Some(&words::manage_trusted_remembered())),
            Some(Method::Device) => group.set_description(Some(&words::manage_trusted_password())),
            None => {}
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
