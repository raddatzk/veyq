//! The shell (ADR 0122; ADR 0123's Consequences, the Linux app), GNOME's way: an `AdwNavigationSplitView` whose sidebar is an
//! `AdwSidebar` — Home, Favorites, Recent, the applications — under a header bar whose title is the one switcher (the
//! space shown, its tenant and server; spaces and tenants in its menu), with New beside it and Search under it (Ctrl+F,
//! [crate::search]: while something is asked, its list is the sidebar's place), and Settings pinned at its foot; the switcher shows the signed-in member's picture beside the tenant. The content
//! side shows what the core's shell says is open: Home, an application's list, a Pot (its menu at the header's end:
//! Download), a file ([crate::file_page]), a drop box ([crate::drop_box]), a Memory or a chapter ([crate::memory_page], its
//! lightbox pushed over the content, [crate::lightbox]), or an object this app does not draw yet. Refresh
//! (F5, Ctrl+R) reads an open Pot anew, else the shell's lists (a guest's new folder, another device's upload); a space's top
//! level offers its share, "Add files only" ([crate::share_sheet::open_top_level]), beside it. Below 600sp the split view collapses
//! to a navigation stack. A list keeps where it was scrolled to when the shell draws it anew — after an object opened from
//! it closed, after an update — by its place ([ListScrolls]).
//!
//! Offline (ADR 0140 decision 10) an `AdwBanner` under the content's header bar says so, as of when (`ShellShown.offlineBanner`):
//! GNOME's banner for a state that lasts; New is not offered then and the header's writes are gone (the core's). An item
//! no longer available offline (`ShellModelState.gone`) is said once as a toast, then told seen (`Shell.OfflineGoneSeen`).
//!
//! Every state is the core's (`ShellModel.State` and `ShellShown`); every action one call. What the sidebar selects,
//! which back the header shows and what the title says follow the state, as Android's and Apple's shells draw it.
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, wire::Answer, wire::ApplicationId, wire::Call, wire::Create, wire::Facts, wire::NodeAt, wire::ObjectRef, wire::ShellModelState, wire::ShellShown, wire::ViewModeShown};
use crate::bookmark_page::BookmarkPage;
use crate::drop_box::DropBoxPage;
use crate::file_page::FilePage;
use crate::memories_map::MemoriesMap;
use crate::person_page::PersonPage;
use crate::memory_page::{self, MemoryPage};
use crate::music_bar::MusicBar;
use crate::header::{self, Header};
use crate::home::{self, app_icon, app_name, kind_icon};
use crate::pot::PotPage;
use crate::search::Search;
use crate::drop::Drops;
use crate::uploads::{self, Uploads};
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
    new: gtk::MenuButton,
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
    /// list, the list from tiles (`View.Set`, the account's choice on this device). Shown on a level that offers both.
    view_toggle: gtk::Button,
    /// An open Pot's "Add entry", the content's creating action, shown once the Pot is read and takes writes.
    add_entry: gtk::Button,
    /// A Memory's "Add chapter", its page's own creating action, shown where its gallery offers one.
    add_chapter: gtk::Button,
    refresh_action: gio::SimpleAction,
    header: Rc<RefCell<Option<Header>>>,
    /// A header whose page left while its trash's Undo stands: kept until another takes its place.
    undoing: RefCell<Option<Header>>,
    toasts: adw::ToastOverlay,
    window: adw::ApplicationWindow,
    pot: RefCell<Option<PotPage>>,
    file: RefCell<Option<FilePage>>,
    drop_box: RefCell<Option<DropBoxPage>>,
    memory: RefCell<Option<MemoryPage>>,
    /// A kept link's page while the shell's state names one (`Shell.bookmark`, #1032): its body; core's header heads it.
    bookmark: RefCell<Option<BookmarkPage>>,
    /// The Memories bar's Map tab while it is chosen ([MemoriesMap]); its map closes when it goes.
    memories_map: RefCell<Option<MemoriesMap>>,
    /// A person's profile while the shell's state names one (`Shell.person`, ADR 0144 decision 11).
    person: RefCell<Option<PersonPage>>,
    /// The content's navigation, where a Memory's lightbox is pushed over the page ([crate::lightbox]).
    nav: adw::NavigationView,
    /// "Offline — content as of …", revealed while the core shows the lists from the offline store.
    banner: adw::Banner,
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
        let add_entry = create_action::create_action(&words::pot_add(), || {});
        add_entry.set_visible(false);
        content_header.pack_start(&add_entry);
        let add_chapter = create_action::create_action(&words::memory_add_chapter(), || {});
        add_chapter.set_visible(false);
        content_header.pack_start(&add_chapter);
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
        let view_toggle = gtk::Button::builder().icon_name("view-grid-symbolic").visible(false).build();
        view_toggle.set_widget_name("view-toggle");
        content_header.pack_end(&view_toggle);
        let refresh_action = gio::SimpleAction::new("pot-refresh", None);
        window.add_action(&refresh_action);
        let content_view = adw::ToolbarView::new();
        content_view.add_top_bar(&content_header);
        let banner = adw::Banner::new("");
        content_view.add_top_bar(&banner);
        let toasts = adw::ToastOverlay::new();
        // the upload queue, GNOME Files' way: a header bar button while anything is listed, its popover the list
        let uploads = Uploads::new(&toasts);
        content_header.pack_end(uploads.button());
        // the content is the root of a navigation of its own: a Memory's lightbox is pushed over it, back pops it
        let nav = adw::NavigationView::new();
        nav.add(&adw::NavigationPage::new(&content_view, &words::app_name()));
        // a running save's banner along the pane's foot, outside the navigation, so a lightbox over the content keeps it in
        // view; the toasts stay for what is said (`file_page::saving`)
        nav.set_vexpand(true);
        let saves = gtk::Box::new(gtk::Orientation::Vertical, 0);
        crate::file_page::show_saves_in(&saves);
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
            add_entry: add_entry.clone(),
            add_chapter: add_chapter.clone(),
            refresh_action,
            header: Rc::new(RefCell::new(None)),
            undoing: RefCell::new(None),
            toasts,
            window: window.clone(),
            pot: RefCell::new(None),
            file: RefCell::new(None),
            drop_box: RefCell::new(None),
            memory: RefCell::new(None),
            bookmark: RefCell::new(None),
            memories_map: RefCell::new(None),
            person: RefCell::new(None),
            nav,
            banner,
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
        let download = gio::SimpleAction::new("pot-download", None);
        let weak = Rc::downgrade(self);
        download.connect_activate(move |_, _| {
            let Some(s) = weak.upgrade() else { return };
            if let Some(pot) = s.pot.borrow().as_ref() {
                pot.download(s.window.upcast_ref(), &s.toasts);
            }
        });
        window.add_action(&download);
        // a file's Save a copy, the header's download on its page
        let save = gio::SimpleAction::new("file-save", None);
        let weak = Rc::downgrade(self);
        save.connect_activate(move |_, _| {
            let Some(s) = weak.upgrade() else { return };
            if let Some(file) = s.file.borrow().as_ref() {
                file.save(s.window.upcast_ref(), &s.toasts);
            }
        });
        window.add_action(&save);
        // a file's Replace, the header's own action on its page: chosen first, then its open dialog (`uploads`)
        let replace = gio::SimpleAction::new("file-replace", None);
        let weak = Rc::downgrade(self);
        replace.connect_activate(move |_, _| {
            let Some(s) = weak.upgrade() else { return };
            if let Some(id) = s.file.borrow().as_ref().and_then(FilePage::id) {
                uploads::replace(s.window.upcast_ref(), id, &s.toasts);
            }
        });
        window.add_action(&replace);
        // New's "Upload files" and "Upload folder…" into the level shown (`uploads`)
        for (name, folder) in [("upload-files", false), ("upload-folder", true)] {
            let action = gio::SimpleAction::new(name, None);
            let weak = Rc::downgrade(self);
            action.connect_activate(move |_, _| {
                let Some(s) = weak.upgrade() else { return };
                let Some(level) = s.level() else { return };
                if folder {
                    uploads::pick_folder(s.window.upcast_ref(), level, &s.toasts);
                } else {
                    uploads::pick_files(s.window.upcast_ref(), level, &s.toasts);
                }
            });
            window.add_action(&action);
        }
        // a Memory's "Add photos" (the header's own `upload`) and its "Add chapter"
        let add_media = gio::SimpleAction::new("memory-upload", None);
        let weak = Rc::downgrade(self);
        add_media.connect_activate(move |_, _| {
            let Some(s) = weak.upgrade() else { return };
            if let Some(page) = s.memory.borrow().as_ref() {
                page.add();
            }
        });
        window.add_action(&add_media);
        // a Memory's or a chapter's music action (the header's own `music`): its page plays or stops the node's music
        let music = gio::SimpleAction::new("memory-music", None);
        let weak = Rc::downgrade(self);
        music.connect_activate(move |_, _| {
            let Some(s) = weak.upgrade() else { return };
            if let Some(page) = s.memory.borrow().as_ref() {
                page.music();
            }
        });
        window.add_action(&music);
        let weak = Rc::downgrade(self);
        self.add_chapter.connect_clicked(move |_| {
            let Some(s) = weak.upgrade() else { return };
            if let Some(page) = s.memory.borrow().as_ref() {
                page.create_chapter();
            }
        });
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
            } else if let Some(page) = s.memory.borrow().as_ref() {
                page.reload();
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
                crate::share_sheet::open_top_level(&s.window, space);
            }
        });

        // "Available offline", a check item whose state the header sets from the core's (`header`): activated, the core
        // toggles it (on asks first), the state follows the core's answer, never the click
        let offline = gio::SimpleAction::new_stateful("header-offline", None, &false.to_variant());
        let weak = Rc::downgrade(self);
        offline.connect_activate(move |_, _| {
            let Some(s) = weak.upgrade() else { return };
            if let Some(h) = s.header.borrow().as_ref() {
                h.offline();
            }
        });
        window.add_action(&offline);
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
        // New's Pot (`PotCreate.Open`): its dialog, over the window; the shell opens the Pot it made
        let create_pot = gio::SimpleAction::new("create-pot", None);
        let weak = Rc::downgrade(self);
        create_pot.connect_activate(move |_, _| {
            if let Some(s) = weak.upgrade() {
                crate::pot_create::open(s.window.upcast_ref(), &s.toasts);
            }
        });
        window.add_action(&create_pot);
        // New Memory (`Shell.CreateMemory`) in the level shown, named in an alert dialog that asks again with the core's
        // problem; the Memory made opens (the web's `MemoryNodeDialog`)
        let create_memory = gio::SimpleAction::new("create-memory", None);
        let weak = Rc::downgrade(self);
        create_memory.connect_activate(move |_, _| {
            let Some(s) = weak.upgrade() else { return };
            let Some(level) = s.level() else { return };
            let (toasts, window) = (s.toasts.clone(), s.window.clone());
            glib::spawn_future_local(async move {
                // *I was there* (ADR 0144 decision 2): offered ticked where the core says so, not remembered; *Determine location
                // automatically* (uc.memory.place A1): offered unticked where the map services are shown
                let (offered, offers_place) = match bridge::call(&Call::ShellOffersWasThere { scope_id: level.scope_id.clone() }).await.map(|(a, _)| a) {
                    Ok(Answer::WasThere { offered, auto_place }) => (offered, auto_place),
                    _ => (false, false),
                };
                let check_row = |active: bool, name: &str, title: String, subtitle: String| {
                    let check = gtk::CheckButton::builder().active(active).valign(gtk::Align::Center).build();
                    check.set_widget_name(name);
                    let row = adw::ActionRow::builder().title(title).subtitle(subtitle).build();
                    row.add_prefix(&check);
                    row.set_activatable_widget(Some(&check));
                    (check, row.upcast::<gtk::ListBoxRow>())
                };
                let (place, place_row) = check_row(false, "auto-place", words::memory_place_auto(), words::memory_place_auto_hint());
                let (check, was_there_row) = check_row(true, "was-there", words::memory_participants_was_there(), words::memory_participants_was_there_hint());
                let rows = [offers_place.then_some(place_row), offered.then_some(was_there_row)].into_iter().flatten().collect();
                eprintln!("veyq: new memory: I was there offered {offered}, the place {offers_place}");
                header::ask_name_with(window.upcast_ref(), &words::memory_create_memory_title(), &words::node_name(), "", None, &words::folder_create(), rows, move |name, again| {
                let (toasts, scope_id, parent_id) = (toasts.clone(), level.scope_id.clone(), level.parent_id.clone());
                let was_there = offered && check.is_active();
                let auto_place = offers_place && place.is_active();
                glib::spawn_future_local(async move {
                    match bridge::call(&Call::ShellCreateMemory { scope_id, parent_id, name: name.clone(), was_there, auto_place }).await.map(|(a, _)| a) {
                        Ok(Answer::Created { node: Some(node), .. }) => {
                            eprintln!("veyq: new memory: made");
                            memory_page::open_node(&node);
                        }
                        Ok(Answer::Created { notice: Some(n), .. }) => {
                            eprintln!("veyq: new memory: {}", n.msg);
                            again(name, said::notice(&n));
                        }
                        other => header::say(&toasts, other, "Shell.CreateMemory"),
                    }
                });
                });
            });
        });
        window.add_action(&create_memory);
        // New → Bookmark (ADR 0150 decision 6): its dialog keeps the link where New is
        let create_bookmark = gio::SimpleAction::new("create-bookmark", None);
        let weak = Rc::downgrade(self);
        create_bookmark.connect_activate(move |_, _| {
            let Some(s) = weak.upgrade() else { return };
            let Some(level) = s.level() else { return };
            // the bookmark made shows in the level read anew (the core forgot its tree)
            crate::bookmark_create::open(&s.window, level.scope_id, level.parent_id, |_| bridge::send(Call::ShellRefresh));
        });
        window.add_action(&create_bookmark);
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
                crate::account_settings::open(&window, c, Some(section));
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
    fn level(&self) -> Option<uploads::Level> {
        let now = self.now.borrow();
        let (state, shown) = now.as_ref()?;
        let scope_id = state.shell.space.clone()?;
        let folder = state.shell.path.last();
        let label = folder.map(|f| f.name.clone()).unwrap_or_else(|| said::piece(&shown.space_name));
        Some(uploads::Level { scope_id, parent_id: folder.map(|f| f.node_id.clone()), label })
    }

    /// Where a drop onto the level shown goes: only where its New offers Upload files (the core's `creates`).
    fn drops(&self) -> Option<Drops> {
        let takes = self.now.borrow().as_ref().is_some_and(|(_, shown)| shown.can_create_here && shown.creates.iter().any(|c| c.create == Create::UploadFiles));
        if !takes {
            return None;
        }
        Some(Drops { level: self.level()?, toasts: self.toasts.clone() })
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
        if *self.gone_said.borrow() == state.gone {
            return;
        }
        *self.gone_said.borrow_mut() = state.gone.clone();
        if state.gone.is_empty() {
            return;
        }
        for n in &state.gone {
            eprintln!("veyq: offline gone: {}", n.msg);
            self.toasts.add_toast(adw::Toast::new(&home::glib_escape(&said::notice(n))));
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
        // "Manage servers" (the window's `win.manage`, `manage.rs`) and *Open a link* (`win.open-link`, `received.rs`), the
        // account's menu (cr-app-received-links owner question 2)
        let manage = gio::Menu::new();
        manage.append(Some(&words::link_open()), Some("win.open-link"));
        manage.append(Some(&words::switcher_manage()), Some("win.manage"));
        menu.append_section(None, &manage);
        self.switcher.set_menu_model(Some(&menu));
        self.space_action.set_state(&state.shell.space.clone().unwrap_or_default().to_variant());
        self.tenant_action.set_state(&format!("{}\n{}", shown.connection, shown.tenant.tenant_id).to_variant());
    }

    /// New: what the core offers where the shell is (`AppKinds.createsWhere`: on Home a folder, a Pot, files and a folder
    /// uploaded; elsewhere a folder and a Pot) where it may create (`canCreateHere`).
    fn draw_new(&self) {
        let now = self.now.borrow();
        let Some((state, shown)) = now.as_ref() else { return };
        let menu = gio::Menu::new();
        for c in &shown.creates {
            let action = match c.create {
                Create::Folder => "win.create-folder",
                Create::Pot => "win.create-pot",
                Create::UploadFiles => "win.upload-files",
                Create::UploadFolder => "win.upload-folder",
                Create::Memory => "win.create-memory",
                // a chapter is a Memory's page's own (its "Add chapter"), never a level's
                Create::Chapter => continue,
                Create::Bookmark => "win.create-bookmark",
            };
            menu.append(Some(&said::key(&c.word)), Some(action));
        }
        self.new.set_menu_model(Some(&menu));
        // a person's profile creates nothing, nor does a kept link's page
        self.new.set_visible(shown.can_create_here && state.shell.person.is_none() && state.shell.bookmark.is_none());
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
            // a folder's page knows all it needs; a Pot's and a file's tell once they are read
            let facts = (node.kind != "EXPENSES_POT" && node.kind != "FILE").then_some(Facts { can_write: true, has_content: true });
            *self.header.borrow_mut() = Some(Header::open(node, facts, &self.header_bar, &self.toasts));
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
            folder.name.clone()
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
                edit.connect_clicked(move |_| crate::profile_edit::open(window.upcast_ref()));
                self.header_bar.append(&edit);
            }
        }
        // the Memories bar's Map tab stands while it is chosen and nothing is open; a page closing back onto the list asks
        // the rows' dates again (its own may have been edited)
        let memories = shell.application == Some(ApplicationId::Memories) && shell.open_object.is_none();
        let map_tab = memories && shown.selected_tab.as_ref().is_some_and(|t| t.key == "memories.map");
        if !map_tab {
            self.memories_map.borrow_mut().take();
        }
        let back_from_page = self.memory.borrow().is_some();
        if shell.person.is_none() {
            self.person.borrow_mut().take();
        }
        if shell.bookmark.is_none() {
            self.bookmark.borrow_mut().take();
        }
        let body: gtk::Widget = if let Some(node) = &shell.bookmark {
            // a kept link's page (ADR 0150 decision 6, #1032): its body here, core's header above it, the shell's back out
            self.pot.borrow_mut().take();
            self.file.borrow_mut().take();
            self.drop_box.borrow_mut().take();
            self.memory.borrow_mut().take();
            self.memories_map.borrow_mut().take();
            self.person.borrow_mut().take();
            let mut page = self.bookmark.borrow_mut();
            if page.as_ref().is_none_or(|p| !crate::bookmark_page::same_node(&p.node, node)) {
                *page = None;
                *page = Some(BookmarkPage::open(node, self.window.upcast_ref()));
            }
            page.as_ref().expect("set above").widget().clone().upcast()
        } else if let Some(user) = &shell.person {
            self.pot.borrow_mut().take();
            self.file.borrow_mut().take();
            self.drop_box.borrow_mut().take();
            self.memory.borrow_mut().take();
            self.memories_map.borrow_mut().take();
            let mut page = self.person.borrow_mut();
            if page.as_ref().is_none_or(|p| p.user_id != *user) {
                *page = None;
                *page = Some(PersonPage::open(user, shown.me.as_ref() == Some(user), self.window.upcast_ref()));
            }
            page.as_ref().expect("set above").widget().clone().upcast()
        } else { match &shell.open_object {
            Some(o) if o.kind == "EXPENSES_POT" => {
                self.file.borrow_mut().take();
                self.drop_box.borrow_mut().take();
                self.memory.borrow_mut().take();
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
                            h.facts(Some(Facts { can_write, has_content: true }), None, None);
                        }
                    });
                    *pot = Some(page);
                }
                let page = pot.as_ref().expect("set above");
                page.hero(self.header.borrow().as_ref().map(Header::hero));
                page.places(&shown.tabs, shown.selected_tab.as_ref());
                page.widget().clone().upcast()
            }
            Some(o) if o.kind == "FILE" => {
                self.pot.borrow_mut().take();
                self.drop_box.borrow_mut().take();
                self.memory.borrow_mut().take();
                let mut file = self.file.borrow_mut();
                let node = NodeAt { scope_id: o.scope_id.clone(), node_id: o.node_id.clone(), kind: o.kind.clone(), name: o.name.clone() };
                if file.as_ref().is_none_or(|f| f.node != node) {
                    let page = FilePage::open(&node);
                    // the file read: its header learns what it may do (`FileModel.Header.facts`)
                    let held = Rc::downgrade(&self.header);
                    page.on_facts(move |facts, id| {
                        if let Some(held) = held.upgrade()
                            && let Some(h) = held.borrow().as_ref()
                        {
                            h.facts(Some(facts), Some(id), None);
                        }
                    });
                    *file = Some(page);
                }
                file.as_ref().expect("set above").widget().clone().upcast()
            }
            Some(o) if o.kind == "DEPOT_DROPBOX" => {
                self.pot.borrow_mut().take();
                self.file.borrow_mut().take();
                self.memory.borrow_mut().take();
                let mut page = self.drop_box.borrow_mut();
                let node = NodeAt { scope_id: o.scope_id.clone(), node_id: o.node_id.clone(), kind: o.kind.clone(), name: o.name.clone() };
                if page.as_ref().is_none_or(|p| p.node != node) {
                    *page = Some(DropBoxPage::open(&node, &self.toasts));
                }
                let page = page.as_ref().expect("set above");
                page.hero(self.header.borrow().as_ref().map(Header::hero));
                page.widget().clone().upcast()
            }
            Some(o) if o.kind == "MEMORY" || o.kind == "CHAPTER" => {
                self.pot.borrow_mut().take();
                self.file.borrow_mut().take();
                self.drop_box.borrow_mut().take();
                let mut page = self.memory.borrow_mut();
                let node = NodeAt { scope_id: o.scope_id.clone(), node_id: o.node_id.clone(), kind: o.kind.clone(), name: o.name.clone() };
                if page.as_ref().is_none_or(|p| p.node != node) {
                    *page = None;
                    let opened = MemoryPage::open(&node, self.window.upcast_ref(), &self.toasts, &self.nav);
                    // each state: the count under the title, its "Add chapter", and once its id, the header's own upload
                    let held = Rc::downgrade(&self.header);
                    let (title, add) = (self.content_title.downgrade(), self.add_chapter.downgrade());
                    let told = Cell::new(None);
                    opened.on_shown(move |shown, gallery| {
                        if let Some(title) = title.upgrade() {
                            title.set_subtitle(&home::glib_escape(&said::notice(&shown.count)));
                        }
                        if let Some(add) = add.upgrade() {
                            add.set_visible(memory_page::offers_chapter(shown));
                        }
                        if told.get() != Some(gallery)
                            && let Some(held) = held.upgrade()
                            && let Some(h) = held.borrow().as_ref()
                        {
                            told.set(Some(gallery));
                            h.facts(Some(Facts { can_write: true, has_content: true }), None, Some(gallery));
                        }
                    });
                    *page = Some(opened);
                }
                let page = page.as_ref().expect("set above");
                page.hero(self.header.borrow().as_ref().map(Header::hero));
                page.widget().clone().upcast()
            }
            Some(o) => {
                self.pot.borrow_mut().take();
                self.file.borrow_mut().take();
                self.drop_box.borrow_mut().take();
                self.memory.borrow_mut().take();
                adw::StatusPage::builder().icon_name(kind_icon(&o.kind)).title(o.name.as_str()).description(words::not_built()).build().upcast()
            }
            None => {
                self.pot.borrow_mut().take();
                self.file.borrow_mut().take();
                self.drop_box.borrow_mut().take();
                self.memory.borrow_mut().take();
                if map_tab {
                    let mut page = self.memories_map.borrow_mut();
                    let page = page.get_or_insert_with(|| MemoriesMap::open(self.window.upcast_ref()));
                    page.places(&shown.tabs, shown.selected_tab.as_ref());
                    page.widget().clone().upcast()
                } else {
                    let list = if shell.application.is_some() {
                        home::list(shown.list.as_ref(), shown.view)
                    } else {
                        // a folder's level starts with its head, as the Pot's and the Memory's pages (none on a space's root)
                        home::home(state, shown, self.drops().as_ref(), self.header.borrow().as_ref().map(Header::hero))
                    };
                    if let Some(scroll) = list.downcast_ref::<gtk::ScrolledWindow>() {
                        self.scrolls.keep(scroll, ListScrolls::place(state));
                    }
                    if memories {
                        let key = shown.list.as_ref().map(|l| l.all.iter().chain(&l.favorites).chain(&l.recent).map(|o| o.node_id.as_str()).collect::<Vec<_>>().join(","));
                        home::ask_dates(&key.unwrap_or_default(), back_from_page);
                    }
                    if memories && shown.tabs.len() > 1 {
                        // the bar's places above the list, as the Map tab and the Pot draw them
                        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
                        column.append(&crate::pot::places(&shown.tabs, shown.selected_tab.as_ref()));
                        column.append(&list);
                        column.upcast()
                    } else {
                        list
                    }
                }
            }
        } };
        let pot_open = self.pot.borrow().is_some();
        if !pot_open {
            self.add_entry.set_visible(false);
        }
        let memory_open = self.memory.borrow().is_some();
        if !memory_open {
            self.add_chapter.set_visible(false);
            self.content_title.set_subtitle("");
        }
        self.refresh.set_visible(pot_open || memory_open || self.drop_box.borrow().is_some());
        self.top_share.set_visible(shown.top_level_share);
        // a level's view: where a level shows (no object, no person, not the bar's Map tab) and it offers another
        let level = shell.person.is_none() && shell.bookmark.is_none() && shell.open_object.is_none() && !map_tab;
        match other_view(shown).filter(|_| level) {
            Some(to) => {
                let (icon, label) = if to == ViewModeShown::Tiles { ("view-grid-symbolic", words::node_view_grid_label()) } else { ("view-list-symbolic", words::node_view_list_label()) };
                self.view_toggle.set_icon_name(icon);
                self.view_toggle.set_tooltip_text(Some(&label));
                self.view_toggle.update_property(&[gtk::accessible::Property::Label(&label)]);
                self.view_toggle.set_visible(true);
            }
            None => self.view_toggle.set_visible(false),
        }
        if self.content_view.content().as_ref() != Some(&body) {
            self.content_view.set_content(Some(&body));
        }
    }
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
