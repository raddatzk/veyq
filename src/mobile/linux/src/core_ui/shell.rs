//! The shell (ADR 0122; ADR 0123's Consequences, the Linux app), GNOME's way: an `AdwNavigationSplitView` whose sidebar is an
//! `AdwSidebar` — Home, Favorites, Recent, the applications, each only when it lists something — under a header bar whose title is the one switcher (the
//! space shown, its tenant and server; spaces and tenants in its menu), with the drawer's New beside it (`DrawerSection.NEW`,
//! made at the space's top) and Search under it (Ctrl+F,
//! [crate::core_ui::search]: while something is asked, its list is the sidebar's place), and Settings pinned at its foot; the switcher shows the signed-in member's picture beside the tenant. The content
//! side shows what the core's shell says is open: Home, an application's list (with what the application registered
//! around it, [ApplicationList]), an object's page — the body its kind's application registered ([registry]: a Pot's,
//! a file's, a drop box's, a Memory's or a chapter's, pushing what it opens over the content's navigation) inside the
//! frame, its header above it and the page's own creating action at the header bar's start ([Frame]) —, or an object
//! this app does not draw yet. Refresh (F5, Ctrl+R) reads an open page anew where it reads itself (a Pot, a Memory),
//! else the shell's lists (a guest's new folder, another device's upload); a space's top
//! level offers its share, "Add files only" ([crate::core_ui::share_sheet::open_top_level]), beside it. A level draws its
//! plan (`ShellShown.level`, ADR 0154 decision 2): its New at the content header bar's start, its tools — the space's
//! trash, the view switch — in their order at its end. Below 600sp the split view collapses
//! to a navigation stack. A list keeps where it was scrolled to when the shell draws it anew — after an object opened from
//! it closed, after an update — by its place ([ListScrolls]).
//!
//! Offline (ADR 0140 decision 10) an `AdwBanner` under the content's header bar says so, as of when (`ShellShown.offlineBanner`):
//! GNOME's banner for a state that lasts; New is not offered then and the header's writes are gone (the core's). An item
//! no longer available offline (`ShellModelState.gone`) is said once as a toast, then told seen (`Shell.OfflineGoneSeen`).
//! Writes made offline (ADR 0153): a second banner counts those waiting (`queuedBanner`), the level lists what it holds
//! not sent yet in place (`queuedHere`), the reconnect's notices are said once (`queuedSaid`), New offers only what queues
//! (`creates`), and a held write's *Open* from the uploads' tray goes where the core says ([Shell::open_queued]).
//!
//! Every state is the core's (`ShellModel.State` and `ShellShown`); every action one call. What the sidebar selects,
//! which back the header shows and what the title says follow the state, as Android's and Apple's shells draw it.
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, wire::Answer, wire::ApplicationId, wire::Call, wire::Create, wire::DrawerSection, wire::Facts, wire::LevelTool, wire::ObjectRef, wire::QueuedOpen, wire::ShellModelState, wire::ShellShown, wire::ShownCreate, wire::ViewModeShown};
use crate::core_ui::bookmark_page::BookmarkPage;
use crate::core_ui::person_page::PersonPage;
use crate::core_ui::music_bar::MusicBar;
use crate::core_ui::header::{self, Header};
use crate::core_ui::home::{self, app_icon, app_name, kind_icon};
use crate::core_ui::registry::{registry, ApplicationList, Frame, Page};
use crate::core_ui::search::Search;
use crate::core_ui::drop::Drops;
use crate::core_ui::uploads::{self, Uploads};
use crate::core_ui::{avatars, create_action, said, trash, words};

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
    /// What the window shows while signed in: the split view, and under it the account's music bar ([MusicBar]).
    pub root: adw::ToolbarView,
    pub split: adw::NavigationSplitView,
    music_bar: Rc<MusicBar>,
    sidebar: adw::Sidebar,
    targets: RefCell<Vec<Option<Target>>>,
    switcher: gtk::MenuButton,
    switcher_title: adw::WindowTitle,
    me: adw::Avatar,
    switching: adw::Spinner,
    /// The drawer's New (`DrawerSection.NEW`, `ShellShown.drawerCreates`): beside the switcher, made at the space's top.
    new: gtk::MenuButton,
    /// The level's New (`ShellShown.level.creates`): at the content header bar's start, made where the level is.
    level_new: gtk::MenuButton,
    /// The level's tools (`ShellShown.level.tools`), in their order at the content header bar's end.
    level_tools: gtk::Box,
    /// The space's trash among them (`LevelTool.TRASH`).
    trash_button: gtk::Button,
    space_action: gio::SimpleAction,
    tenant_action: gio::SimpleAction,
    /// The search over every space ([Search]): its bar under the sidebar's title, its list in [sidebar_place].
    search: Search,
    /// The sidebar's place: the sidebar, or the search's list while something is asked.
    sidebar_place: gtk::Stack,
    content: adw::NavigationPage,
    content_view: adw::ToolbarView,
    content_header: adw::HeaderBar,
    content_title: adw::WindowTitle,
    back: gtk::Button,
    /// The header bar's end, where the node on screen draws its actions (`header`).
    header_bar: gtk::Box,
    /// Refresh (`win.pot-refresh`, F5 / Ctrl+R): an open Pot's, else the shell's lists; shown on a Pot and a drop box.
    refresh: gtk::Button,
    /// The space's top level's share, "Add files only", shown where the core offers it (`ShellShown.topLevelShare`).
    top_share: gtk::Button,
    /// A level's view (ADR 0149 decision 8), Files' way: one header bar button showing the view it turns to — tiles from the
    /// list, the list from tiles (`View.Set`, the account's choice on this device). A level tool (`LevelTool.VIEW`).
    view_toggle: gtk::Button,
    /// The open page's own creating action's place, after the back (a Pot's "Add entry", a Memory's "Add chapter"):
    /// the page fills it ([Frame::start]), the shell empties it when the page goes.
    page_start: gtk::Box,
    refresh_action: gio::SimpleAction,
    header: Rc<RefCell<Option<Header>>>,
    /// A header whose page left while its trash's Undo stands: kept until another takes its place.
    undoing: RefCell<Option<Header>>,
    toasts: adw::ToastOverlay,
    window: adw::ApplicationWindow,
    /// The open object's page, the body its kind's application registered, while the shell's state names the object.
    page: RefCell<Option<(ObjectRef, Box<dyn Page>)>>,
    /// A kept link's page while the shell's state names one (`Shell.bookmark`, #1032): its body; core's header heads it.
    bookmark: RefCell<Option<BookmarkPage>>,
    /// An application's own list while its list shows ([ApplicationList], the Memories bar's places and Map tab).
    app_list: RefCell<Option<(ApplicationId, Box<dyn ApplicationList>)>>,
    /// A person's profile while the shell's state names one (`Shell.person`, ADR 0144 decision 11).
    person: RefCell<Option<PersonPage>>,
    /// The content's navigation, where a page pushes what it opens over itself (a Memory's lightbox, [Frame::nav]).
    nav: adw::NavigationView,
    /// "Offline — content as of …", revealed while the core shows the lists from the offline store.
    banner: adw::Banner,
    /// Under it, while writes made offline wait: their count (`ShellShown.queuedBanner`, ADR 0153 decision 5).
    queued_banner: adw::Banner,
    /// The reconnect's notices said last (`ShellShown.queuedSaid`), so an update that repeats them says nothing again.
    queued_said: RefCell<Vec<bridge::wire::Notice>>,
    /// A held rename's *Open* (`QueuedOpen.Rename`): the node whose header asks the person's name once it shows.
    pending_rename: RefCell<Option<(String, String)>>,
    /// The `gone` notices said last, so an update that repeats them before the core drops them says nothing again.
    gone_said: RefCell<Vec<bridge::wire::Notice>>,
    /// The upload queue's button in the content's header bar, shown while it lists anything.
    _uploads: Rc<Uploads>,
    /// Where each list was scrolled to, by its place.
    scrolls: ListScrolls,
    now: RefCell<Option<(ShellModelState, ShellShown)>>,
    /// The connection the shell shows, whose Settings the sidebar's foot opens.
    connection: Rc<RefCell<Option<String>>>,
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
        let search = Search::new();
        search_toggle.bind_property("active", &search.bar, "search-mode-enabled").bidirectional().build();

        let sidebar = adw::Sidebar::new();
        let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&sidebar).build();
        // Settings pinned at the foot, below what scrolls (owner 2026-11-10): the account's page (`win.account-settings`)
        let settings = adw::ActionRow::builder().title(words::drawer_settings()).activatable(true).build();
        settings.add_prefix(&gtk::Image::from_icon_name("emblem-system-symbolic"));
        let foot = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
        foot.add_css_class("navigation-sidebar");
        foot.append(&settings);
        let opens = window.clone();
        let shown_now: Rc<RefCell<Option<String>>> = Rc::new(RefCell::new(None));
        let connection = shown_now.clone();
        foot.connect_row_activated(move |_, _| {
            if let Some(c) = connection.borrow().clone() {
                let _ = WidgetExt::activate_action(&opens, "win.account-settings", Some(&c.to_variant()));
            }
        });
        let sidebar_place = gtk::Stack::new();
        sidebar_place.add_named(&scroll, Some("sidebar"));
        sidebar_place.add_named(&search.list, Some("search"));
        let sidebar_view = adw::ToolbarView::new();
        sidebar_view.add_top_bar(&sidebar_header);
        sidebar_view.add_top_bar(&search.bar);
        sidebar_view.set_content(Some(&sidebar_place));
        sidebar_view.add_bottom_bar(&foot);
        let sidebar_page = adw::NavigationPage::new(&sidebar_view, &words::app_name());

        let content_title = adw::WindowTitle::new("", "");
        let back = gtk::Button::builder().icon_name("go-previous-symbolic").tooltip_text(words::back()).visible(false).build();
        let content_header = adw::HeaderBar::new();
        content_header.set_title_widget(Some(&content_title));
        content_header.pack_start(&back);
        let page_start = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        content_header.pack_start(&page_start);
        // the node on screen's actions, GNOME's way: buttons and a menu at the header bar's end (`header`)
        let header_bar = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        content_header.pack_end(&header_bar);
        // an open Pot's Refresh: desktops have no pull gesture (F5 and Ctrl+R, as GNOME's apps reload)
        let refresh = gtk::Button::builder().icon_name("view-refresh-symbolic").tooltip_text(words::pot_refresh()).action_name("win.pot-refresh").visible(false).build();
        refresh.update_property(&[gtk::accessible::Property::Label(&words::pot_refresh())]);
        content_header.pack_end(&refresh);
        // a space's top level is no link's root: its share offers "Add files only" alone (the web's level share)
        let top_share = gtk::Button::builder().icon_name("send-to-symbolic").tooltip_text(words::share_role_uploader()).visible(false).build();
        top_share.update_property(&[gtk::accessible::Property::Label(&words::share_role_uploader())]);
        content_header.pack_end(&top_share);
        // the level's tools, in the plan's order (`level_tools`); its New at the start, after the page's own place
        let level_tools = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        content_header.pack_end(&level_tools);
        let view_toggle = gtk::Button::builder().icon_name("view-grid-symbolic").build();
        view_toggle.set_widget_name("view-toggle");
        let trash_button = gtk::Button::builder().icon_name("user-trash-symbolic").tooltip_text(words::trash_title()).action_name("win.trash").build();
        trash_button.update_property(&[gtk::accessible::Property::Label(&words::trash_title())]);
        trash_button.set_widget_name("level-trash");
        let level_new = create_action::create_menu(&words::drawer_new(), gio::Menu::new().upcast_ref());
        level_new.set_widget_name("level-new");
        level_new.set_visible(false);
        content_header.pack_start(&level_new);
        let refresh_action = gio::SimpleAction::new("pot-refresh", None);
        window.add_action(&refresh_action);
        let content_view = adw::ToolbarView::new();
        content_view.add_top_bar(&content_header);
        let banner = adw::Banner::new("");
        content_view.add_top_bar(&banner);
        let queued_banner = adw::Banner::new("");
        content_view.add_top_bar(&queued_banner);
        let toasts = adw::ToastOverlay::new();
        // the upload queue, GNOME Files' way: a header bar button while anything is listed, its popover the list
        let uploads = Uploads::new(&toasts);
        content_header.pack_end(uploads.button());
        // the content is the root of a navigation of its own: a Memory's lightbox is pushed over it, back pops it
        let nav = adw::NavigationView::new();
        nav.add(&adw::NavigationPage::new(&content_view, &words::app_name()));
        // a running save's banner along the pane's foot, outside the navigation, so a lightbox over the content keeps it in
        // view; the toasts stay for what is said (`saving::saving`)
        nav.set_vexpand(true);
        let saves = gtk::Box::new(gtk::Orientation::Vertical, 0);
        crate::core_ui::saving::show_saves_in(&saves);
        let pane = gtk::Box::new(gtk::Orientation::Vertical, 0);
        pane.append(&nav);
        pane.append(&saves);
        toasts.set_child(Some(&pane));
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

        // the music bar along the window's bottom edge, under the sidebar and the content (GNOME Music's player bar)
        let music_bar = MusicBar::new(&toasts);
        let root = adw::ToolbarView::new();
        root.set_content(Some(&split));
        root.add_bottom_bar(&music_bar.bar);
        let shell = Rc::new(Self {
            root,
            split,
            music_bar,
            sidebar,
            targets: RefCell::new(Vec::new()),
            switcher,
            switcher_title,
            me,
            switching,
            new,
            level_new,
            level_tools,
            trash_button,
            space_action,
            tenant_action,
            search,
            sidebar_place,
            content,
            content_view,
            content_header,
            content_title,
            back,
            header_bar,
            refresh,
            top_share,
            view_toggle,
            page_start,
            refresh_action,
            header: Rc::new(RefCell::new(None)),
            undoing: RefCell::new(None),
            toasts,
            window: window.clone(),
            page: RefCell::new(None),
            bookmark: RefCell::new(None),
            app_list: RefCell::new(None),
            person: RefCell::new(None),
            nav,
            banner,
            queued_banner,
            queued_said: RefCell::new(Vec::new()),
            pending_rename: RefCell::new(None),
            gone_said: RefCell::new(Vec::new()),
            _uploads: uploads,
            scrolls: ListScrolls::default(),
            now: RefCell::new(None),
            connection: shown_now,
            started: Cell::new(false),
        });
        shell.connect(window);
        shell
    }

    fn connect(self: &Rc<Self>, window: &adw::ApplicationWindow) {
        // a held write's *Open* from the uploads' tray goes where the core says (`QueuedOpen`)
        let weak = Rc::downgrade(self);
        self._uploads.on_open(move |open| {
            if let Some(s) = weak.upgrade() {
                s.open_queued(open);
            }
        });
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
        // the level's view turned: the other of the two it offers
        let weak = Rc::downgrade(self);
        self.view_toggle.connect_clicked(move |_| {
            let Some(s) = weak.upgrade() else { return };
            let Some(mode) = s.now.borrow().as_ref().and_then(|(_, shown)| other_view(shown)) else { return };
            eprintln!("veyq: view set {mode:?}");
            bridge::send(Call::ViewSet { mode });
        });
        // the open page's own actions, the header's own (`header::doer`: a Pot's download, a file's Save a copy and
        // Replace, a gallery's Add photos and music), each as its kind registered it
        for name in registry().actions() {
            let action = gio::SimpleAction::new(name, None);
            let weak = Rc::downgrade(self);
            action.connect_activate(move |_, _| {
                let Some(s) = weak.upgrade() else { return };
                if let Some((_, page)) = s.page.borrow().as_ref() {
                    page.act(name, s.window.upcast_ref(), &s.toasts);
                }
            });
            window.add_action(&action);
        }
        // New's "Upload files" and "Upload folder…" into the level shown (`uploads`)
        for (name, folder) in [("upload-files", false), ("upload-folder", true)] {
            let action = gio::SimpleAction::new(name, Some(glib::VariantTy::BOOLEAN));
            let weak = Rc::downgrade(self);
            action.connect_activate(move |_, top| {
                let Some(s) = weak.upgrade() else { return };
                let Some(level) = s.creates_in(top) else { return };
                if folder {
                    uploads::pick_folder(s.window.upcast_ref(), level, &s.toasts);
                } else {
                    uploads::pick_files(s.window.upcast_ref(), level, &s.toasts);
                }
            });
            window.add_action(&action);
        }
        let weak = Rc::downgrade(self);
        self.refresh_action.connect_activate(move |_, _| {
            let Some(s) = weak.upgrade() else { return };
            if s.page.borrow().as_ref().is_some_and(|(_, page)| page.refresh()) {
                // the page read itself anew (a Pot, a Memory)
            } else {
                // the shell's lists read anew from the server: a drop box's page, Home's level and every list follow
                let toasts = s.toasts.clone();
                glib::spawn_future_local(async move {
                    header::say(&toasts, bridge::call(&Call::ShellRefresh).await.map(|(a, _)| a), "Shell.Refresh");
                });
            }
        });
        let weak = Rc::downgrade(self);
        self.top_share.connect_clicked(move |_| {
            let Some(s) = weak.upgrade() else { return };
            let space = s.now.borrow().as_ref().and_then(|(state, _)| state.shell.space.clone());
            if let Some(space) = space {
                crate::core_ui::share_sheet::open_top_level(&s.window, space);
            }
        });

        // the header's entries are its own group's (`header`); New's folder (`Shell.CreateFolder`, at the space's top from
        // the drawer's New), named in an alert dialog that asks again with the core's problem
        let create_folder = gio::SimpleAction::new("create-folder", Some(glib::VariantTy::BOOLEAN));
        let weak = Rc::downgrade(self);
        create_folder.connect_activate(move |_, top| {
            let Some(s) = weak.upgrade() else { return };
            let top = top.and_then(|v| v.get::<bool>()).unwrap_or(false);
            let toasts = s.toasts.clone();
            header::ask_name(s.window.upcast_ref(), &words::folder_new(), &words::folder_name(), "", None, &words::folder_create(), move |name, again| {
                let toasts = toasts.clone();
                glib::spawn_future_local(async move {
                    match bridge::call(&Call::ShellCreateFolder { name: name.clone(), top }).await.map(|(a, _)| a) {
                        Ok(Answer::Named { problem: Some(p), .. }) => {
                            eprintln!("veyq: new folder: {}", p.msg);
                            again(name, said::notice(&p));
                        }
                        Ok(Answer::Named { notice: Some(n), .. }) => toasts.add_toast(said::notice_toast(&n)),
                        Ok(Answer::Named { .. }) => {}
                        other => header::say(&toasts, other, "Shell.CreateFolder"),
                    }
                });
            });
        });
        window.add_action(&create_folder);
        // New's creates an application draws its own dialog for (`win.create-pot`, `win.create-memory`), over the window and
        // the level shown
        for c in registry().applications().iter().flat_map(|a| a.creates.iter()) {
            let action = gio::SimpleAction::new(c.action, Some(glib::VariantTy::BOOLEAN));
            let (weak, open) = (Rc::downgrade(self), c.open);
            action.connect_activate(move |_, top| {
                if let Some(s) = weak.upgrade() {
                    open(s.window.upcast_ref(), &s.toasts, s.creates_in(top));
                }
            });
            window.add_action(&action);
        }
        // New → Bookmark (ADR 0150 decision 6): its dialog keeps the link where New is
        let create_bookmark = gio::SimpleAction::new("create-bookmark", Some(glib::VariantTy::BOOLEAN));
        let weak = Rc::downgrade(self);
        create_bookmark.connect_activate(move |_, top| {
            let Some(s) = weak.upgrade() else { return };
            let Some(level) = s.creates_in(top) else { return };
            // the bookmark made shows in the level read anew (the core forgot its tree)
            crate::core_ui::bookmark_create::open(&s.window, level.scope_id, level.parent_id, |_| bridge::send(Call::ShellRefresh));
        });
        window.add_action(&create_bookmark);
        // the space's trash, a level's tool (`LevelTool.TRASH`, `trash`)
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

        // Search (`search`): opened, the sidebar shows (collapsed, it is where Search is); something asked, its list is the
        // sidebar's place; a result opened shows the content; a settings result opens the account's page at its section
        let weak = Rc::downgrade(self);
        self.search.bar.connect_search_mode_enabled_notify(move |bar| {
            let Some(s) = weak.upgrade() else { return };
            if bar.is_search_mode() {
                s.split.set_show_content(false);
                s.search.entry.grab_focus();
            }
        });
        let place = self.sidebar_place.clone();
        self.search.on_asked(move |asked| place.set_visible_child_name(if asked { "search" } else { "sidebar" }));
        let split = self.split.clone();
        self.search.on_opened(move || split.set_show_content(true));
        let (opens, connection) = (self.window.downgrade(), self.connection.clone());
        self.search.on_settings(move |section| {
            if let (Some(c), Some(window)) = (connection.borrow().clone(), opens.upgrade()) {
                crate::core_ui::account_settings::open(&window, c, Some(section));
            }
        });
        let bar = self.search.bar.clone();
        let toggle = gtk::CallbackAction::new(move |_, _| {
            bar.set_search_mode(!bar.is_search_mode());
            glib::Propagation::Stop
        });
        let shortcuts = gtk::ShortcutController::new();
        shortcuts.set_scope(gtk::ShortcutScope::Global);
        shortcuts.add_shortcut(gtk::Shortcut::new(gtk::ShortcutTrigger::parse_string("<Control>f"), Some(toggle)));
        // Refresh: an open Pot's, else the shell's lists
        for trigger in ["F5", "<Control>r"] {
            shortcuts.add_shortcut(gtk::Shortcut::new(gtk::ShortcutTrigger::parse_string(trigger), Some(gtk::NamedAction::new("win.pot-refresh"))));
        }
        window.add_controller(shortcuts);
    }

    /// The level shown, where New's uploads go: its space, the folder there (none: its top), the name the queue labels it by.
    /// Where a New's entry makes what it makes: the level shown, or — the drawer's New ([top] true) — the space's top.
    fn creates_in(&self, top: Option<&glib::Variant>) -> Option<uploads::Level> {
        let level = self.level()?;
        if !top.and_then(|v| v.get::<bool>()).unwrap_or(false) {
            return Some(level);
        }
        let space = self.now.borrow().as_ref().map(|(_, shown)| said::piece(&shown.space_name)).unwrap_or_default();
        Some(uploads::Level { scope_id: level.scope_id, parent_id: None, label: space })
    }

    fn level(&self) -> Option<uploads::Level> {
        let now = self.now.borrow();
        let (state, shown) = now.as_ref()?;
        let scope_id = state.shell.space.clone()?;
        let folder = state.shell.path.last();
        let label = folder.map(|f| f.name.clone()).unwrap_or_else(|| said::piece(&shown.space_name));
        Some(uploads::Level { scope_id, parent_id: folder.map(|f| f.node_id.clone()), label })
    }

    /// Where a drop onto the level shown goes, as the core says: the level where it takes one (`dropsHere`), the folders'
    /// rows it names (`dropRows`).
    fn drops(&self) -> Drops {
        let (here, rows) = self.now.borrow().as_ref().map(|(_, shown)| (shown.drops_here, shown.drop_rows.clone())).unwrap_or_default();
        Drops { level: here.then(|| self.level().map(|l| l.label)).flatten(), rows, toasts: self.toasts.clone() }
    }

    /// Says [text] — what did not happen (a level this device could not keep) — as a toast over the content, held until
    /// closed (ADR 0154 decision 4).
    pub fn say(&self, text: &str) {
        self.toasts.add_toast(said::toast(text, crate::bridge::wire::NoticeKind::Held));
    }

    /// A switch to another tenant is under way (`Gate.Signed.switching`).
    pub fn switching(&self, on: bool) {
        self.switching.set_visible(on);
        self.switcher.set_sensitive(!on);
    }

    /// Draws the core's shell: [state] with what the core says it shows beside it.
    pub fn show(self: &Rc<Self>, state: ShellModelState, shown: ShellShown) {
        let first = !self.started.replace(true);
        self._uploads.start();
        self.music_bar.start();
        avatars::listed(&format!("{}\n{}", shown.connection, shown.tenant.tenant_id), &shown.pictures, shown.pictures_round);
        *self.connection.borrow_mut() = Some(shown.connection.clone());
        self.draw_offline(&state, &shown);
        *self.now.borrow_mut() = Some((state, shown));
        self.draw_switcher();
        self.draw_new();
        self.draw_sidebar();
        self.draw_content();
        if first {
            let opens_drawer = self.now.borrow().as_ref().is_some_and(|(_, s)| s.drawer_opens_at_start);
            self.split.set_show_content(!opens_drawer);
        }
    }

    /// The offline banner, and each item no longer available offline said once, then told seen.
    fn draw_offline(&self, state: &ShellModelState, shown: &ShellShown) {
        match &shown.offline_banner {
            Some(b) => {
                self.banner.set_title(&home::glib_escape(&said::notice(b)));
                self.banner.set_revealed(true);
            }
            None => self.banner.set_revealed(false),
        }
        match &shown.queued_banner {
            Some(q) => {
                self.queued_banner.set_title(&home::glib_escape(&said::notice(q)));
                self.queued_banner.set_revealed(true);
            }
            None => self.queued_banner.set_revealed(false),
        }
        // after a reconnect: how many writes made offline were sent, how many need the person — said once, then told seen
        if *self.queued_said.borrow() != shown.queued_said {
            *self.queued_said.borrow_mut() = shown.queued_said.clone();
            if !shown.queued_said.is_empty() {
                for n in &shown.queued_said {
                    eprintln!("veyq: queued said: {}", n.msg);
                    self.toasts.add_toast(said::notice_toast(n));
                }
                bridge::send(Call::ShellQueuedSaidSeen);
            }
        }
        if *self.gone_said.borrow() == state.gone {
            return;
        }
        *self.gone_said.borrow_mut() = state.gone.clone();
        if state.gone.is_empty() {
            return;
        }
        for n in &state.gone {
            eprintln!("veyq: offline gone: {}", n.msg);
            self.toasts.add_toast(said::notice_toast(n));
        }
        bridge::send(Call::ShellOfflineGoneSeen);
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
        // every signed-in account's tenants, each row saying where it is (core's `ShellWords.switcher`: its domain, and the
        // account's address where two accounts list the same tenant; owner 2026-11-19)
        let tenants = gio::Menu::new();
        for e in &shown.switcher {
            let item = gio::MenuItem::new(Some(&e.line), None);
            item.set_action_and_target_value(Some("win.tenant"), Some(&format!("{}\n{}", e.connection, e.tenant.tenant_id).to_variant()));
            tenants.append_item(&item);
        }
        menu.append_section(None, &tenants);
        // "Manage servers" (the window's `win.manage`, `manage.rs`); *Open a link* is the sign-in's alone (owner 2026-11-19)
        let manage = gio::Menu::new();
        manage.append(Some(&words::switcher_manage()), Some("win.manage"));
        menu.append_section(None, &manage);
        self.switcher.set_menu_model(Some(&menu));
        self.space_action.set_state(&state.shell.space.clone().unwrap_or_default().to_variant());
        self.tenant_action.set_state(&format!("{}\n{}", shown.connection, shown.tenant.tenant_id).to_variant());
    }

    /// The drawer's New (`DrawerSection.NEW`): hidden until the sidebar's plan names it ([Shell::draw_sidebar]).
    fn draw_new(&self) {
        self.new.set_visible(false);
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

        let top = adw::SidebarSection::new();
        add(&top, &words::drawer_home(), "user-home-symbolic", Some(Target::Home), shown.is_home);
        self.sidebar.append(top);
        // only the sections with something in them (core's `Lists.drawerSections`; owner 2026-11-19)
        for kind in &shown.drawer_sections {
            let section = adw::SidebarSection::new();
            match kind {
                // the drawer's New, below the switcher: the "+" at the sidebar header's start (GNOME's creating action),
                // what it makes landing at the space's top
                DrawerSection::New => {
                    self.new.set_menu_model(Some(&creates_menu(&shown.drawer_creates, true)));
                    self.new.set_visible(true);
                    continue;
                }
                DrawerSection::Favorites => {
                    section.set_title(Some(&words::drawer_favorites()));
                    for r in &state.lists.drawer.favorites {
                        add(&section, &r.name, kind_icon(&r.kind), Some(Target::Object(r.clone())), opened(r));
                    }
                }
                DrawerSection::Recent => {
                    section.set_title(Some(&words::drawer_recent()));
                    for r in &state.lists.drawer.recent {
                        add(&section, &r.name, kind_icon(&r.kind), Some(Target::Object(r.clone())), opened(r));
                    }
                }
                DrawerSection::Applications => {
                    section.set_title(Some(&words::drawer_applications()));
                    for app in &state.lists.applications {
                        let chosen = shell.application == Some(*app) && shell.open_object.is_none();
                        add(&section, &app_name(shown, *app), app_icon(*app), Some(Target::Application(*app)), chosen);
                    }
                }
            }
            self.sidebar.append(section);
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
            // a folder's page knows all it needs; a page whose kind says so (a Pot's, a file's) tells once it is read
            let later = registry().page(&node.kind).is_some_and(|p| p.facts_later);
            let facts = (!later).then_some(Facts { can_write: true, has_content: true });
            let header = Header::open(node, facts, &self.header_bar, &self.toasts);
            // a kept link's page already open (its header opened anew: renamed): its model's id joins the new header
            let kept = self.bookmark.borrow().as_ref().filter(|p| crate::core_ui::bookmark_page::same_node(&p.node, node)).and_then(BookmarkPage::id);
            if let Some(bookmark) = kept {
                header.facts(Some(Facts { can_write: true, has_content: true }), None, None, Some(bookmark));
            }
            // a held rename opened: its node's rename asks again with the name the person gave offline
            let pending = self.pending_rename.borrow().as_ref().filter(|(id, _)| *id == node.node_id).map(|(_, name)| name.clone());
            if let Some(name) = pending {
                self.pending_rename.borrow_mut().take();
                header.rename_as(&self.window, &name);
            }
            *self.header.borrow_mut() = Some(header);
        }
    }

    /// Where a held write's *Open* goes, as the core types it (`Queued.Open`, ADR 0153 decision 4): a Pot entry's held
    /// editor over the newer version, the node's rename with the person's name in it, or the page it was made on.
    fn open_queued(self: &Rc<Self>, open: QueuedOpen) {
        eprintln!("veyq: queued open: {}", match &open {
            QueuedOpen::QueuedOpenEntry { .. } => "entry",
            QueuedOpen::QueuedOpenRename { .. } => "rename",
            QueuedOpen::QueuedOpenPlace { .. } => "place",
        });
        match open {
            QueuedOpen::QueuedOpenRename { scope, node, name, .. } => {
                *self.pending_rename.borrow_mut() = Some((node.clone(), name));
                bridge::send(Call::ShellOpenAt { scope_id: scope, node_id: Some(node) });
                self.split.set_show_content(true);
            }
            QueuedOpen::QueuedOpenPlace { scope, node, .. } => {
                bridge::send(Call::ShellOpenAt { scope_id: scope, node_id: node });
                self.split.set_show_content(true);
            }
            // an application's own write (a Pot entry's held editor over the newer version), where it registered it
            other => {
                let opened = registry().applications().iter().filter_map(|a| a.queued_open).any(|open| open(&other, self.window.upcast_ref(), &self.toasts));
                if !opened {
                    eprintln!("veyq: queued open: no application opens it");
                }
            }
        }
    }

    fn draw_content(&self) {
        let now = self.now.borrow();
        let Some((state, shown)) = now.as_ref() else { return };
        let shell = &state.shell;
        // the title as both apps head the screen: the open object, the chosen application, the folder, the space
        let title = if shell.person.is_some() {
            words::people_profile()
        } else if let Some(b) = &shell.bookmark {
            b.name.clone()
        } else if let Some(o) = &shell.open_object {
            o.name.clone()
        } else if let Some(app) = shell.application {
            app_name(shown, app)
        } else if let Some(folder) = shell.path.last() {
            folder.label.clone().unwrap_or_else(|| folder.name.clone())
        } else {
            said::piece(&shown.space_name)
        };
        self.content.set_title(&title);
        self.content_title.set_title(&title);
        // the shell's own back where it has one; else, collapsed, the split view's back to the sidebar
        self.back.set_visible(shown.can_go_back);
        self.content_header.set_show_back_button(!shown.can_go_back);

        if shell.person.is_none() {
            self.draw_header(shown);
        } else {
            // the profile is no node: no node's actions on it; one's own has *Edit profile* (ADR 0148 decision 10)
            self.header.borrow_mut().take();
            while let Some(child) = self.header_bar.first_child() {
                self.header_bar.remove(&child);
            }
            if shell.person.is_some() && shell.person == shown.me {
                let edit = gtk::Button::builder().icon_name("document-edit-symbolic").tooltip_text(words::profile_edit()).build();
                edit.update_property(&[gtk::accessible::Property::Label(&words::profile_edit())]);
                edit.set_widget_name("edit-profile");
                let window = self.window.clone();
                edit.connect_clicked(move |_| crate::core_ui::profile_edit::open(window.upcast_ref()));
                self.header_bar.append(&edit);
            }
        }
        // an application's list stands where nothing is open: what it registered around it is held while it shows (the
        // Memories bar's places and its Map tab, whose map closes when it goes)
        let listing = if shell.person.is_none() && shell.bookmark.is_none() && shell.open_object.is_none() { shell.application } else { None };
        {
            let mut held = self.app_list.borrow_mut();
            if held.as_ref().map(|(app, _)| *app) != listing {
                *held = None;
                *held = listing.and_then(|app| registry().ui(app)?.list.map(|make| (app, make())));
            }
        }
        // a page of the listed application closing back onto its list (it asks the rows' dates again: its own may have
        // been edited)
        let back_from_page = self.page.borrow().as_ref().is_some_and(|(r, _)| Some(r.application) == listing);
        if shell.person.is_none() {
            self.person.borrow_mut().take();
        }
        if shell.bookmark.is_none() {
            self.bookmark.borrow_mut().take();
        }
        let body: gtk::Widget = if let Some(node) = &shell.bookmark {
            // a kept link's page (ADR 0150 decision 6, #1032): its body here, core's header above it, the shell's back out
            self.drop_page();
            self.person.borrow_mut().take();
            let mut page = self.bookmark.borrow_mut();
            if page.as_ref().is_none_or(|p| !crate::core_ui::bookmark_page::same_node(&p.node, node)) {
                *page = None;
                // the page's model's id joins its header: the kept link's own entries are done through it
                let held = Rc::downgrade(&self.header);
                *page = Some(BookmarkPage::open(node, self.window.upcast_ref(), move |bookmark| {
                    if let Some(held) = held.upgrade()
                        && let Some(h) = held.borrow().as_ref()
                    {
                        h.facts(Some(Facts { can_write: true, has_content: true }), None, None, Some(bookmark));
                    }
                }));
            }
            page.as_ref().expect("set above").widget().clone().upcast()
        } else if let Some(user) = &shell.person {
            self.drop_page();
            let mut page = self.person.borrow_mut();
            if page.as_ref().is_none_or(|p| p.user_id != *user) {
                *page = None;
                *page = Some(PersonPage::open(user, shown.me.as_ref() == Some(user), self.window.upcast_ref()));
            }
            page.as_ref().expect("set above").widget().clone().upcast()
        } else { match (&shell.open_object, shell.open_object.as_ref().and_then(|o| registry().page(&o.kind))) {
            // an object's page: the body its kind's application registered, inside the frame
            (Some(o), Some(kind)) => {
                let mut held = self.page.borrow_mut();
                if held.as_ref().is_none_or(|(r, _)| r != o) {
                    *held = None;
                    self.clear_page_start();
                    *held = Some((o.clone(), (kind.body)(o, &self.frame())));
                }
                let (_, page) = held.as_ref().expect("set above");
                page.hero(self.header.borrow().as_ref().map(Header::hero));
                page.places(&shown.tabs, shown.selected_tab.as_ref());
                page.widget()
            }
            (Some(o), None) => {
                self.drop_page();
                adw::StatusPage::builder().icon_name(kind_icon(&o.kind)).title(o.name.as_str()).description(words::not_built()).build().upcast()
            }
            (None, _) => {
                self.drop_page();
                let held = self.app_list.borrow();
                match held.as_ref().and_then(|(_, l)| l.instead(shown, self.window.upcast_ref())) {
                    Some(own) => own,
                    None => {
                        let list = if shell.application.is_some() {
                            home::list(shown.list.as_ref(), shown.view, &self.toasts)
                        } else {
                            // a folder's level starts with its head, as an object's page (none on a space's root)
                            home::home(state, shown, Some(&self.drops()), self.header.borrow().as_ref().map(Header::hero), &self.toasts)
                        };
                        if let Some(scroll) = list.downcast_ref::<gtk::ScrolledWindow>() {
                            self.scrolls.keep(scroll, ListScrolls::place(state));
                        }
                        match held.as_ref() {
                            Some((_, l)) => l.around(list, shown, back_from_page),
                            None => list,
                        }
                    }
                }
            }
        } };
        let refreshes = self.page.borrow().as_ref().and_then(|(r, _)| registry().page(&r.kind)).is_some_and(|p| p.refreshes);
        self.refresh.set_visible(refreshes);
        self.top_share.set_visible(shown.top_level_share);
        self.draw_level(shown);
        if self.content_view.content().as_ref() != Some(&body) {
            self.content_view.set_content(Some(&body));
        }
    }
}

impl Shell {
    /// The level's plan (`ShellShown.level`; none where no level shows — an object, a person, a kept link): its New at the
    /// content header bar's start, its tools at its end, each entry in its order.
    fn draw_level(&self, shown: &ShellShown) {
        while let Some(child) = self.level_tools.first_child() {
            self.level_tools.remove(&child);
        }
        let Some(level) = shown.level.as_ref() else {
            self.level_new.set_visible(false);
            return;
        };
        self.level_new.set_menu_model(Some(&creates_menu(&level.creates, false)));
        self.level_new.set_visible(!level.creates.is_empty());
        for tool in &level.tools {
            match tool {
                // Select (core's `LevelSelection`, inbox #1049): the level's pick mode on and off, pressed while on (Files'
                // selection mode)
                LevelTool::Select => self.level_tools.append(&crate::core_ui::selection::tool(shown.selection.is_some())),
                LevelTool::Trash => self.level_tools.append(&self.trash_button),
                LevelTool::View => {
                    // the button shows the view it turns to (Files' way); a level offering one view has nothing to turn to
                    let to = other_view(shown);
                    let (icon, label) = if to == Some(ViewModeShown::Tiles) || (to.is_none() && shown.view == ViewModeShown::Tiles) {
                        ("view-grid-symbolic", words::node_view_grid_label())
                    } else {
                        ("view-list-symbolic", words::node_view_list_label())
                    };
                    self.view_toggle.set_icon_name(icon);
                    self.view_toggle.set_tooltip_text(Some(&label));
                    self.view_toggle.update_property(&[gtk::accessible::Property::Label(&label)]);
                    self.view_toggle.set_sensitive(to.is_some());
                    self.level_tools.append(&self.view_toggle);
                }
            }
        }
    }

    /// The frame an object's page opens in: the window, the toasts, the content's navigation, the header bar's start, the
    /// header told what the page read, the line under the title.
    fn frame(&self) -> Frame {
        let held = Rc::downgrade(&self.header);
        let title = self.content_title.downgrade();
        Frame {
            window: self.window.clone().upcast(),
            toasts: self.toasts.clone(),
            nav: self.nav.clone(),
            start: self.page_start.clone(),
            facts: Rc::new(move |facts, file, gallery| {
                if let Some(held) = held.upgrade()
                    && let Some(h) = held.borrow().as_ref()
                {
                    h.facts(facts, file, gallery, None);
                    return true;
                }
                false
            }),
            subtitle: Rc::new(move |text| {
                if let Some(title) = title.upgrade() {
                    title.set_subtitle(text);
                }
            }),
        }
    }

    /// The open object's page let go: its model closes, its creating action and the line under the title go with it.
    fn drop_page(&self) {
        if self.page.borrow_mut().take().is_some() {
            self.clear_page_start();
        }
    }

    fn clear_page_start(&self) {
        while let Some(child) = self.page_start.first_child() {
            self.page_start.remove(&child);
        }
        self.content_title.set_subtitle("");
    }
}

/// New's menu of [creates], each entry its window action — made where the level is, or ([top]: the drawer's New) at the
/// space's top.
fn creates_menu(creates: &[ShownCreate], top: bool) -> gio::Menu {
    let menu = gio::Menu::new();
    for c in creates {
        let action = match c.create {
            Create::Folder => "win.create-folder".to_owned(),
            Create::UploadFiles => "win.upload-files".to_owned(),
            Create::UploadFolder => "win.upload-folder".to_owned(),
            Create::Bookmark => "win.create-bookmark".to_owned(),
            // an application's own dialog (a Pot, a Memory; a chapter is a Memory's page's own and never a level's): the
            // action its application registered for it
            Create::Pot | Create::Memory | Create::Chapter => match registry().create(c.create) {
                Some(dialog) => format!("win.{}", dialog.action),
                None => {
                    eprintln!("veyq: New's {:?}: no application registered its dialog", c.create);
                    continue;
                }
            },
        };
        let item = gio::MenuItem::new(Some(&said::key(&c.word)), None);
        item.set_action_and_target_value(Some(&action), Some(&top.to_variant()));
        menu.append_item(&item);
    }
    menu
}

/// The view a level's toggle turns to: the other of the two it offers (`ShellShown.views`), none where it offers one.
fn other_view(shown: &ShellShown) -> Option<ViewModeShown> {
    (shown.views.len() > 1).then(|| shown.views.iter().copied().find(|v| *v != shown.view)).flatten()
}

/// Where each list was scrolled to, by its place ([ListScrolls::place]): the shell draws a list anew on every state and
/// whenever an object opened from it closes (the object takes its place, ADR 0122), and each new one is put back where its
/// place's list stood (the owner's report, 2026-11-12: a long list jumped to its top).
#[derive(Default, Clone)]
pub struct ListScrolls(Rc<RefCell<HashMap<String, f64>>>);

impl ListScrolls {
    /// A list's place: an application's list in a space, or Home's level (its space, its folder and its filter).
    pub fn place(state: &ShellModelState) -> String {
        let shell = &state.shell;
        place_of(shell.application, shell.space.as_deref(), shell.path.last().map(|f| f.node_id.as_str()), shell.filter)
    }

    /// [scroll], the list of [place], put back where that place's list was and followed from now on.
    pub fn keep(&self, scroll: &gtk::ScrolledWindow, place: String) {
        let adjustment = scroll.vadjustment();
        let held = self.0.clone();
        let (key, shown) = (place.clone(), scroll.clone());
        adjustment.connect_value_changed(move |a| {
            // a list leaving the window is clamped to its top as it goes: only a list on screen says where it stands
            if shown.is_mapped() {
                held.borrow_mut().insert(key.clone(), a.value());
            }
        });
        let Some(target) = self.0.borrow().get(&place).copied().filter(|t| *t > 0.0) else { return };
        // put back once the list is laid out tall enough to stand there (its height arrives with its first allocation) —
        // on the next turn of the loop: a value set inside the allocation moved the scroll bar but not the list (measured
        // 2026-11-12, the list drawn at its top under a thumb at its bottom)
        let pending = Rc::new(Cell::new(true));
        let waiting = pending.clone();
        adjustment.connect_changed(move |a| {
            if waiting.get()
                && let Some(at) = put_back(target, a.upper(), a.page_size(), false)
            {
                waiting.set(false);
                let (a, place) = (a.clone(), place.clone());
                glib::idle_add_local_once(move || {
                    a.set_value(at);
                    eprintln!("veyq: list {place} put back at {at:.0}");
                });
            }
        });
        // a list grown shorter meanwhile stands at its end, once it is laid out
        let a = adjustment.clone();
        glib::timeout_add_local_once(std::time::Duration::from_millis(400), move || {
            if pending.get() {
                pending.set(false);
                if let Some(at) = put_back(target, a.upper(), a.page_size(), true) {
                    a.set_value(at);
                }
            }
        });
    }
}

/// The key of a list's place ([ListScrolls::place]).
fn place_of(application: Option<ApplicationId>, space: Option<&str>, folder: Option<&str>, filter: Option<ApplicationId>) -> String {
    let space = space.unwrap_or_default();
    match application {
        Some(app) => format!("app:{}:{space}", serde_json::to_string(&app).unwrap_or_default()),
        None => format!("home:{space}:{}:{}", folder.unwrap_or_default(), serde_json::to_string(&filter).unwrap_or_default()),
    }
}

/// Where a list [target] down is put back, its content [upper] tall in a view [page] tall: there once it reaches that far,
/// else nothing yet — or, [settled] (laid out, grown shorter meanwhile), its end.
fn put_back(target: f64, upper: f64, page: f64, settled: bool) -> Option<f64> {
    let end = (upper - page).max(0.0);
    if end >= target {
        Some(target)
    } else if settled && end > 0.0 {
        Some(end)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_lists_place_names_its_application_or_its_space_folder_and_filter() {
        let expenses = place_of(Some(ApplicationId::Expenses), Some("s1"), None, None);
        assert_eq!(expenses, place_of(Some(ApplicationId::Expenses), Some("s1"), Some("f"), None), "an application's list is one place in its space");
        assert_ne!(expenses, place_of(Some(ApplicationId::Depot), Some("s1"), None, None));
        assert_ne!(expenses, place_of(Some(ApplicationId::Expenses), Some("s2"), None, None));
        let root = place_of(None, Some("s1"), None, None);
        assert_ne!(root, place_of(None, Some("s1"), Some("f1"), None), "each folder is a place of its own");
        assert_ne!(root, place_of(None, Some("s1"), None, Some(ApplicationId::Depot)), "each filter is a place of its own");
        assert_eq!(place_of(None, Some("s1"), Some("f1"), None), place_of(None, Some("s1"), Some("f1"), None));
    }

    #[test]
    fn a_list_is_put_back_once_it_is_tall_enough_else_at_its_end_once_laid_out() {
        assert_eq!(put_back(1200.0, 800.0, 600.0, false), None, "not laid out tall enough yet");
        assert_eq!(put_back(1200.0, 2000.0, 600.0, false), Some(1200.0));
        assert_eq!(put_back(1200.0, 1000.0, 600.0, true), Some(400.0), "grown shorter: its end");
        assert_eq!(put_back(1200.0, 500.0, 600.0, true), None, "no scroll at all: left at its top");
    }
}
