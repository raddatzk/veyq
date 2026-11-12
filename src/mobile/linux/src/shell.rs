//! The shell (ADR 0122; ADR 0123's Consequences, the Linux app), GNOME's way: an `AdwNavigationSplitView` whose sidebar is an
//! `AdwSidebar` — Home, Favorites, Recent, the applications — under a header bar whose title is the one switcher (the
//! space shown, its tenant and server; spaces and tenants in its menu), with New beside it and Search under it (Ctrl+F),
//! and Settings pinned at its foot; the switcher shows the signed-in member's picture beside the tenant. The content
//! side shows what the core's shell says is open: Home, an application's list, a Pot (its menu at the header's end:
//! Download), or an object this app does not draw yet. Below 600sp the split view collapses to a navigation stack.
//!
//! Every state is the core's (`ShellModel.State` and `ShellShown`); every action one call. What the sidebar selects,
//! which back the header shows and what the title says follow the state, as Android's and Apple's shells draw it.
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, wire::Answer, wire::ApplicationId, wire::Call, wire::Create, wire::Facts, wire::ObjectRef, wire::ShellModelState, wire::ShellShown};
use crate::header::{self, Header};
use crate::home::{self, app_icon, app_name, kind_icon};
use crate::pot::PotPage;
use crate::{avatars, create_action, said, trash, words};

/// What a sidebar item opens.
#[derive(Clone)]
enum Target {
    Home,
    Application(ApplicationId),
    Object(ObjectRef),
}

/// The breakpoint (D9): below it the split view is a navigation stack. libadwaita's own examples collapse at 400sp
/// with its default sidebar (180–280sp); this sidebar is 240–360sp wide, so it collapses where a 240sp sidebar would
/// leave the content less than 360sp.
pub const COLLAPSE_BELOW: &str = "max-width: 600sp";

pub struct Shell {
    pub split: adw::NavigationSplitView,
    sidebar: adw::Sidebar,
    targets: RefCell<Vec<Option<Target>>>,
    switcher: gtk::MenuButton,
    switcher_title: adw::WindowTitle,
    me: adw::Avatar,
    switching: adw::Spinner,
    new: gtk::MenuButton,
    space_action: gio::SimpleAction,
    tenant_action: gio::SimpleAction,
    search_bar: gtk::SearchBar,
    search: gtk::SearchEntry,
    hits: RefCell<Option<Vec<ObjectRef>>>,
    content: adw::NavigationPage,
    content_view: adw::ToolbarView,
    content_header: adw::HeaderBar,
    content_title: adw::WindowTitle,
    back: gtk::Button,
    /// The header bar's end, where the node on screen draws its actions (`header`).
    header_bar: gtk::Box,
    /// An open Pot's Refresh (`win.pot-refresh`, F5 / Ctrl+R), beside its actions.
    refresh: gtk::Button,
    /// An open Pot's "Add entry", the content's creating action, shown once the Pot is read and takes writes.
    add_entry: gtk::Button,
    refresh_action: gio::SimpleAction,
    header: Rc<RefCell<Option<Header>>>,
    /// A header whose page left while its trash's Undo stands: kept until another takes its place.
    undoing: RefCell<Option<Header>>,
    toasts: adw::ToastOverlay,
    window: adw::ApplicationWindow,
    pot: RefCell<Option<PotPage>>,
    now: RefCell<Option<(ShellModelState, ShellShown)>>,
    started: Cell<bool>,
}

impl Shell {
    /// The shell inside [window], whose actions (`win.space`, `win.tenant`) and Ctrl+F it adds.
    pub fn new(window: &adw::ApplicationWindow) -> Rc<Self> {
        let switcher_title = adw::WindowTitle::new("", "");
        let switching = adw::Spinner::builder().visible(false).build();
        let me = adw::Avatar::new(24, None, true);
        let switcher_child = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        switcher_child.append(&me);
        switcher_child.append(&switcher_title);
        switcher_child.append(&switching);
        let switcher = gtk::MenuButton::builder().child(&switcher_child).always_show_arrow(true).tooltip_text(words::switcher_open()).build();
        switcher.add_css_class("flat");
        let new = create_action::create_menu(&words::drawer_new(), gio::Menu::new().upcast_ref());
        let search_toggle = gtk::ToggleButton::builder().icon_name("system-search-symbolic").tooltip_text(words::tab_search()).build();

        let sidebar_header = adw::HeaderBar::new();
        sidebar_header.set_title_widget(Some(&switcher));
        sidebar_header.pack_start(&new);
        sidebar_header.pack_end(&search_toggle);
        let search = gtk::SearchEntry::builder().placeholder_text(words::search_hint()).hexpand(true).build();
        let search_bar = gtk::SearchBar::builder().child(&search).build();
        search_bar.connect_entry(&search);
        search_toggle.bind_property("active", &search_bar, "search-mode-enabled").bidirectional().build();

        let sidebar = adw::Sidebar::new();
        let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&sidebar).build();
        // Settings pinned at the foot, below what scrolls (owner 2026-11-10); it does nothing yet, as on the other apps
        let settings = adw::ActionRow::builder().title(words::drawer_settings()).activatable(true).build();
        settings.add_prefix(&gtk::Image::from_icon_name("emblem-system-symbolic"));
        let foot = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
        foot.add_css_class("navigation-sidebar");
        foot.append(&settings);
        let sidebar_view = adw::ToolbarView::new();
        sidebar_view.add_top_bar(&sidebar_header);
        sidebar_view.add_top_bar(&search_bar);
        sidebar_view.set_content(Some(&scroll));
        sidebar_view.add_bottom_bar(&foot);
        let sidebar_page = adw::NavigationPage::new(&sidebar_view, &words::app_name());

        let content_title = adw::WindowTitle::new("", "");
        let back = gtk::Button::builder().icon_name("go-previous-symbolic").tooltip_text(words::back()).visible(false).build();
        let content_header = adw::HeaderBar::new();
        content_header.set_title_widget(Some(&content_title));
        content_header.pack_start(&back);
        let add_entry = create_action::create_action(&words::pot_add(), || {});
        add_entry.set_visible(false);
        content_header.pack_start(&add_entry);
        // the node on screen's actions, GNOME's way: buttons and a menu at the header bar's end (`header`)
        let header_bar = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        content_header.pack_end(&header_bar);
        // an open Pot's Refresh: desktops have no pull gesture (F5 and Ctrl+R, as GNOME's apps reload)
        let refresh = gtk::Button::builder().icon_name("view-refresh-symbolic").tooltip_text(words::pot_refresh()).action_name("win.pot-refresh").visible(false).build();
        refresh.update_property(&[gtk::accessible::Property::Label(&words::pot_refresh())]);
        content_header.pack_end(&refresh);
        let refresh_action = gio::SimpleAction::new("pot-refresh", None);
        refresh_action.set_enabled(false);
        window.add_action(&refresh_action);
        let content_view = adw::ToolbarView::new();
        content_view.add_top_bar(&content_header);
        let toasts = adw::ToastOverlay::new();
        toasts.set_child(Some(&content_view));
        let content = adw::NavigationPage::new(&toasts, &words::app_name());

        let split = adw::NavigationSplitView::builder().sidebar(&sidebar_page).content(&content).min_sidebar_width(240.0).max_sidebar_width(360.0).build();
        let breakpoint = adw::Breakpoint::new(adw::BreakpointCondition::parse(COLLAPSE_BELOW).expect("a valid condition"));
        breakpoint.add_setter(&split, "collapsed", Some(&true.to_value()));
        breakpoint.add_setter(&sidebar, "mode", Some(&adw::SidebarMode::Page.to_value()));
        window.add_breakpoint(breakpoint);

        let space_action = gio::SimpleAction::new_stateful("space", Some(glib::VariantTy::STRING), &"".to_variant());
        let tenant_action = gio::SimpleAction::new_stateful("tenant", Some(glib::VariantTy::STRING), &"".to_variant());
        window.add_action(&space_action);
        window.add_action(&tenant_action);

        let shell = Rc::new(Self {
            split,
            sidebar,
            targets: RefCell::new(Vec::new()),
            switcher,
            switcher_title,
            me,
            switching,
            new,
            space_action,
            tenant_action,
            search_bar,
            search,
            hits: RefCell::new(None),
            content,
            content_view,
            content_header,
            content_title,
            back,
            header_bar,
            refresh,
            add_entry: add_entry.clone(),
            refresh_action,
            header: Rc::new(RefCell::new(None)),
            undoing: RefCell::new(None),
            toasts,
            window: window.clone(),
            pot: RefCell::new(None),
            now: RefCell::new(None),
            started: Cell::new(false),
        });
        shell.connect(window);
        shell
    }

    fn connect(self: &Rc<Self>, window: &adw::ApplicationWindow) {
        let weak = Rc::downgrade(self);
        self.sidebar.connect_activated(move |_, index| {
            let Some(s) = weak.upgrade() else { return };
            let target = s.targets.borrow().get(index as usize).cloned().flatten();
            match target {
                Some(Target::Home) => bridge::send(Call::ShellChoose { application: None }),
                Some(Target::Application(app)) => bridge::send(Call::ShellChoose { application: Some(app) }),
                // the sidebar beside the content opens a top-level destination, no back (`Shell.openAtTop`)
                Some(Target::Object(r)) => bridge::send(Call::ShellOpenAtTop { r#ref: r }),
                None => return,
            }
            s.split.set_show_content(true);
        });
        self.back.connect_clicked(|_| bridge::send(Call::ShellBack));
        let download = gio::SimpleAction::new("pot-download", None);
        let weak = Rc::downgrade(self);
        download.connect_activate(move |_, _| {
            let Some(s) = weak.upgrade() else { return };
            if let Some(pot) = s.pot.borrow().as_ref() {
                pot.download(s.window.upcast_ref(), &s.toasts);
            }
        });
        window.add_action(&download);
        let weak = Rc::downgrade(self);
        self.add_entry.connect_clicked(move |_| {
            let Some(s) = weak.upgrade() else { return };
            if let Some(pot) = s.pot.borrow().as_ref() {
                pot.add_entry();
            }
        });
        let weak = Rc::downgrade(self);
        self.refresh_action.connect_activate(move |_, _| {
            let Some(s) = weak.upgrade() else { return };
            if let Some(pot) = s.pot.borrow().as_ref() {
                pot.refresh();
            }
        });

        // the header's actions, on the node on screen (`header`)
        for id in ["favorite", "share", "rename", "delete", "move", "copy", "download", "report", "cover"] {
            let action = gio::SimpleAction::new(&format!("header-{id}"), None);
            let weak = Rc::downgrade(self);
            action.connect_activate(move |_, _| {
                let Some(s) = weak.upgrade() else { return };
                let held = s.header.borrow();
                let Some(h) = held.as_ref() else { return };
                match id {
                    "favorite" => h.pin(),
                    "share" => h.share(&s.window),
                    "rename" => h.rename(&s.window),
                    "move" => h.move_sheet(&s.window, false),
                    "copy" => h.move_sheet(&s.window, true),
                    "download" => h.download(s.window.upcast_ref()),
                    "report" => h.report(&s.window),
                    "cover" => h.cover(&s.window),
                    _ => h.delete(&s.window),
                }
            });
            window.add_action(&action);
        }
        // New's folder (`Shell.CreateFolder`), named in an alert dialog that asks again with the core's problem
        let create_folder = gio::SimpleAction::new("create-folder", None);
        let weak = Rc::downgrade(self);
        create_folder.connect_activate(move |_, _| {
            let Some(s) = weak.upgrade() else { return };
            let toasts = s.toasts.clone();
            header::ask_name(s.window.upcast_ref(), &words::folder_new(), &words::folder_name(), "", None, &words::folder_create(), move |name, again| {
                let toasts = toasts.clone();
                glib::spawn_future_local(async move {
                    match bridge::call(&Call::ShellCreateFolder { name: name.clone() }).await.map(|(a, _)| a) {
                        Ok(Answer::Said { notice: Some(n) }) => {
                            eprintln!("veyq: new folder: {}", n.msg);
                            again(name, said::notice(&n));
                        }
                        other => header::say(&toasts, other, "Shell.CreateFolder"),
                    }
                });
            });
        });
        window.add_action(&create_folder);
        // the space's trash, from the switcher's menu (`trash`)
        let open_trash = gio::SimpleAction::new("trash", None);
        let weak = Rc::downgrade(self);
        open_trash.connect_activate(move |_, _| {
            let Some(s) = weak.upgrade() else { return };
            let space = s.now.borrow().as_ref().and_then(|(state, _)| state.shell.space.clone());
            if let Some(space) = space {
                trash::open(&s.window, space);
            }
        });
        window.add_action(&open_trash);

        let weak = Rc::downgrade(self);
        self.space_action.connect_activate(move |_, scope| {
            let (Some(s), Some(scope)) = (weak.upgrade(), scope.and_then(|v| v.str().map(str::to_owned))) else { return };
            bridge::send(Call::ShellSwitchSpace { scope_id: scope });
            s.split.set_show_content(true);
        });
        let weak = Rc::downgrade(self);
        self.tenant_action.connect_activate(move |action, target| {
            let Some(target) = target.and_then(|v| v.str().map(str::to_owned)) else { return };
            if action.state().and_then(|v| v.str().map(str::to_owned)).as_deref() == Some(target.as_str()) {
                return;
            }
            let Some((connection, tenant)) = target.split_once('\n') else { return };
            bridge::send(Call::SessionSwitchTo { connection: connection.into(), tenant_id: tenant.into() });
            if let Some(s) = weak.upgrade() {
                s.started.set(false);
            }
        });

        // Search over every object's name (`ShellWords.search`), its hits in the sidebar while a query stands
        let weak = Rc::downgrade(self);
        self.search.connect_search_changed(move |entry| {
            let Some(s) = weak.upgrade() else { return };
            s.query(entry.text().to_string());
        });
        let weak = Rc::downgrade(self);
        self.search_bar.connect_search_mode_enabled_notify(move |bar| {
            let Some(s) = weak.upgrade() else { return };
            if bar.is_search_mode() {
                // collapsed, the sidebar is where Search is
                s.split.set_show_content(false);
                s.search.grab_focus();
            } else {
                s.search.set_text("");
            }
        });
        let bar = self.search_bar.clone();
        let toggle = gtk::CallbackAction::new(move |_, _| {
            bar.set_search_mode(!bar.is_search_mode());
            glib::Propagation::Stop
        });
        let shortcuts = gtk::ShortcutController::new();
        shortcuts.set_scope(gtk::ShortcutScope::Global);
        shortcuts.add_shortcut(gtk::Shortcut::new(gtk::ShortcutTrigger::parse_string("<Control>f"), Some(toggle)));
        // an open Pot's Refresh (the action is enabled only while one shows)
        for trigger in ["F5", "<Control>r"] {
            shortcuts.add_shortcut(gtk::Shortcut::new(gtk::ShortcutTrigger::parse_string(trigger), Some(gtk::NamedAction::new("win.pot-refresh"))));
        }
        window.add_controller(shortcuts);
    }

    fn query(self: &Rc<Self>, query: String) {
        if query.trim().is_empty() {
            *self.hits.borrow_mut() = None;
            self.draw_sidebar();
            return;
        }
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let answer = bridge::call(&Call::SearchQuery { query: query.clone() }).await.map(|(answer, _)| answer);
            let Some(s) = weak.upgrade() else { return };
            // only the answer to the query still standing
            if s.search.text() != query.as_str() {
                return;
            }
            match answer {
                Ok(bridge::wire::Answer::Hits { hits }) => *s.hits.borrow_mut() = Some(hits),
                other => eprintln!("veyq: Search answered {other:?}"),
            }
            s.draw_sidebar();
        });
    }

    /// Says [text] as a toast over the content.
    pub fn say(&self, text: &str) {
        self.toasts.add_toast(adw::Toast::new(text));
    }

    /// A switch to another tenant is under way (`Gate.Signed.switching`).
    pub fn switching(&self, on: bool) {
        self.switching.set_visible(on);
        self.switcher.set_sensitive(!on);
    }

    /// Draws the core's shell: [state] with what the core says it shows beside it.
    pub fn show(self: &Rc<Self>, state: ShellModelState, shown: ShellShown) {
        let first = !self.started.replace(true);
        avatars::listed(&format!("{}\n{}", shown.connection, shown.tenant.tenant_id), &shown.pictures);
        *self.now.borrow_mut() = Some((state, shown));
        self.draw_switcher();
        self.draw_new();
        let standing = self.search.text();
        if self.hits.borrow().is_some() && !standing.is_empty() {
            // the lists changed under a standing query: its hits anew
            self.query(standing.to_string());
        } else {
            self.draw_sidebar();
        }
        self.draw_content();
        if first {
            let opens_drawer = self.now.borrow().as_ref().is_some_and(|(_, s)| s.drawer_opens_at_start);
            self.split.set_show_content(!opens_drawer);
        }
    }

    fn draw_switcher(&self) {
        let now = self.now.borrow();
        let Some((state, shown)) = now.as_ref() else { return };
        self.switcher_title.set_title(&said::piece(&shown.space_name));
        self.switcher_title.set_subtitle(&shown.switcher_subtitle);
        // the signed-in member's picture where the core holds one, else the tenant's initials
        self.me.set_text(Some(&shown.tenant.name));
        avatars::show(&self.me, shown.me.as_deref());
        let menu = gio::Menu::new();
        let spaces = gio::Menu::new();
        for s in &shown.spaces {
            let item = gio::MenuItem::new(Some(&said::piece(&s.name)), None);
            item.set_action_and_target_value(Some("win.space"), Some(&s.space.scope_id.to_variant()));
            spaces.append_item(&item);
        }
        menu.append_section(Some(&shown.tenant.name), &spaces);
        for server in &shown.servers {
            let tenants = gio::Menu::new();
            for t in &server.tenants {
                let item = gio::MenuItem::new(Some(&t.name), None);
                item.set_action_and_target_value(Some("win.tenant"), Some(&format!("{}\n{}", server.connection, t.tenant_id).to_variant()));
                tenants.append_item(&item);
            }
            menu.append_section(Some(&server.label), &tenants);
        }
        // the space's trash (`trash.rs`)
        let space_menu = gio::Menu::new();
        space_menu.append(Some(&words::trash_title()), Some("win.trash"));
        menu.append_section(None, &space_menu);
        // "Manage servers" (the window's `win.manage`, `manage.rs`)
        let manage = gio::Menu::new();
        manage.append(Some(&words::switcher_manage()), Some("win.manage"));
        menu.append_section(None, &manage);
        self.switcher.set_menu_model(Some(&menu));
        self.space_action.set_state(&state.shell.space.clone().unwrap_or_default().to_variant());
        self.tenant_action.set_state(&format!("{}\n{}", shown.connection, shown.tenant.tenant_id).to_variant());
    }

    /// New: what the sidebar's New offers (`AppKinds.drawerCreates`) where the shell may create (`canCreateHere`). A
    /// folder is made here; the others are later CRs' (their entry stays insensitive: `win.create` is no action).
    fn draw_new(&self) {
        let now = self.now.borrow();
        let Some((_, shown)) = now.as_ref() else { return };
        let menu = gio::Menu::new();
        for c in &shown.creates {
            menu.append(Some(&said::key(&c.word)), Some(if c.create == Create::Folder { "win.create-folder" } else { "win.create" }));
        }
        self.new.set_menu_model(Some(&menu));
        self.new.set_visible(shown.can_create_here);
    }

    fn draw_sidebar(&self) {
        let now = self.now.borrow();
        let Some((state, shown)) = now.as_ref() else { return };
        let shell = &state.shell;
        self.sidebar.remove_all();
        let mut targets = Vec::new();
        let mut selected = gtk::INVALID_LIST_POSITION;
        let mut add = |section: &adw::SidebarSection, title: &str, icon: &str, target: Option<Target>, chosen: bool| {
            let item = adw::SidebarItem::new(title);
            item.set_icon_name(Some(icon));
            item.set_enabled(target.is_some());
            if chosen {
                selected = targets.len() as u32;
            }
            targets.push(target);
            section.append(item);
        };
        let opened = |r: &ObjectRef| shell.open_object.as_ref().is_some_and(|o| o.scope_id == r.scope_id && o.node_id == r.node_id);

        if let Some(hits) = self.hits.borrow().as_ref() {
            let section = adw::SidebarSection::new();
            section.set_title(Some(&words::tab_search()));
            if hits.is_empty() {
                add(&section, &words::list_empty(), "system-search-symbolic", None, false);
            }
            for h in hits {
                add(&section, &h.name, kind_icon(&h.kind), Some(Target::Object(h.clone())), opened(h));
            }
            self.sidebar.append(section);
        } else {
            let top = adw::SidebarSection::new();
            add(&top, &words::drawer_home(), "user-home-symbolic", Some(Target::Home), shown.is_home);
            self.sidebar.append(top);
            for (title, refs) in [(words::drawer_favorites(), &state.lists.drawer.favorites), (words::drawer_recent(), &state.lists.drawer.recent)] {
                let section = adw::SidebarSection::new();
                section.set_title(Some(&title));
                if refs.is_empty() {
                    add(&section, &words::drawer_nothing_yet(), "view-list-symbolic", None, false);
                }
                for r in refs {
                    add(&section, &r.name, kind_icon(&r.kind), Some(Target::Object(r.clone())), opened(r));
                }
                self.sidebar.append(section);
            }
            let apps = adw::SidebarSection::new();
            apps.set_title(Some(&words::drawer_applications()));
            for app in &state.lists.applications {
                let chosen = shell.application == Some(*app) && shell.open_object.is_none();
                add(&apps, &app_name(shown, *app), app_icon(*app), Some(Target::Application(*app)), chosen);
            }
            self.sidebar.append(apps);
        }
        *self.targets.borrow_mut() = targets;
        self.sidebar.set_selected(selected);
    }

    /// The header of the node the content heads (`ShellShown.headed`): opened anew when the node (or its name) changed,
    /// none on a space's root. A header whose trash's Undo stands is kept until the offer ends.
    fn draw_header(&self, shown: &ShellShown) {
        let same = self.header.borrow().as_ref().map(|h| &h.node) == shown.headed.as_ref();
        if same {
            return;
        }
        if let Some(old) = self.header.borrow_mut().take()
            && old.undoing()
        {
            old.leave_bar();
            *self.undoing.borrow_mut() = Some(old);
        }
        while let Some(child) = self.header_bar.first_child() {
            self.header_bar.remove(&child);
        }
        if let Some(node) = &shown.headed {
            // a folder's page knows all it needs; a Pot's tells once it is read
            let facts = (node.kind != "EXPENSES_POT").then_some(Facts { can_write: true, has_content: true });
            *self.header.borrow_mut() = Some(Header::open(node, facts, &self.header_bar, &self.toasts));
        }
    }

    fn draw_content(&self) {
        let now = self.now.borrow();
        let Some((state, shown)) = now.as_ref() else { return };
        let shell = &state.shell;
        // the title as both apps head the screen: the open object, the chosen application, the folder, the space
        let title = if let Some(o) = &shell.open_object {
            o.name.clone()
        } else if let Some(app) = shell.application {
            app_name(shown, app)
        } else if let Some(folder) = shell.path.last() {
            folder.name.clone()
        } else {
            said::piece(&shown.space_name)
        };
        self.content.set_title(&title);
        self.content_title.set_title(&title);
        // the shell's own back where it has one; else, collapsed, the split view's back to the sidebar
        self.back.set_visible(shown.can_go_back);
        self.content_header.set_show_back_button(!shown.can_go_back);

        self.draw_header(shown);
        let body: gtk::Widget = match &shell.open_object {
            Some(o) if o.kind == "EXPENSES_POT" => {
                let mut pot = self.pot.borrow_mut();
                if pot.as_ref().is_none_or(|p| p.r#ref != *o) {
                    let page = PotPage::open(o, self.window.upcast_ref(), &self.toasts);
                    // the Pot read: the header learns whether it takes writes (`AppKinds.Facts`)
                    let held = Rc::downgrade(&self.header);
                    let add = self.add_entry.downgrade();
                    page.on_read(move |can_write| {
                        if let Some(add) = add.upgrade() {
                            add.set_visible(can_write);
                        }
                        if let Some(held) = held.upgrade()
                            && let Some(h) = held.borrow().as_ref()
                        {
                            h.facts(Some(Facts { can_write, has_content: true }));
                        }
                    });
                    *pot = Some(page);
                }
                let page = pot.as_ref().expect("set above");
                page.hero(self.header.borrow().as_ref().map(Header::hero));
                page.places(&shown.tabs, shown.selected_tab.as_ref());
                page.widget().clone().upcast()
            }
            Some(o) => {
                self.pot.borrow_mut().take();
                adw::StatusPage::builder().icon_name(kind_icon(&o.kind)).title(o.name.as_str()).description(words::not_built()).build().upcast()
            }
            None => {
                self.pot.borrow_mut().take();
                if shell.application.is_some() { home::list(shown.list.as_ref()) } else { home::home(state, shown) }
            }
        };
        let pot_open = self.pot.borrow().is_some();
        if !pot_open {
            self.add_entry.set_visible(false);
        }
        self.refresh.set_visible(pot_open);
        self.refresh_action.set_enabled(pot_open);
        if self.content_view.content().as_ref() != Some(&body) {
            self.content_view.set_content(Some(&body));
        }
    }
}
