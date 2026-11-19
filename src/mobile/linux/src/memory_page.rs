//! A Memory's or a chapter's page (ADR 0136 decision 2; ADR 0123's Consequences, the Linux app), GNOME's way: the
//! node's head (its cover, [crate::header]), a Memory's chapters as a boxed list of rows with their covers, and the media
//! as the web's justified grid (ADR 0136's Consequences) — every tile's frame the core's (`Gallery.Layout`: `GalleryModel.layout`), only the
//! rows near the view mounted (the core's `Layout.window`, ADR 0105), each tile the grid tier's thumbnail decoded by GTK
//! (`Gallery.Tile`, held small and dropped least recently used first), a video's with a play badge. A tile opens the
//! lightbox ([crate::lightbox]); the page's "Add photos" (the header's own `upload`) opens GTK's file dialog for photos and
//! videos, and a drop of files onto the page adds them (`Memory.Upload`) — a dropped folder is said and not taken (the owner,
//! 2026-11-13). A medium trashed (from the lightbox) is out at once with Undo in a toast for as long as the core
//! offers it; what the page says otherwise is a toast too. A medium the search asked for (`GalleryShown.searchOpened`, ADR
//! 0146 decision 8) opens its lightbox once the rows hold it. The page is read anew live while it stands
//! (`Watched.GalleryLive`, the web's poll). Where the node has music or whoever looks may add some (`musicView`), a
//! toggle group under the head switches between its photos and its Music view ([crate::music_view], ADR 0142 decision
//! 6); the header's music action plays or stops the node's music (`Gallery.Music`). The toggle group's Map always stands
//! (ADR 0143 decision 8): the node's Map view ([crate::memory_map_view]) with its photos' dots and GPX tracks. Under the
//! head, the facts line: the node's date and place, each opening its editor where whoever looks may edit them
//! ([crate::memory_facts]).
//!
//! Memories' rest (ADR 0149 decisions 4–6): a tile carries its mark's badge — the heart, filled where the caller marked it,
//! with how many did where the scope counts them; no button (the heart is the lightbox's) —; over the grid the filter
//! ([crate::gallery_filter]), which the grid and the lightbox both follow; and for a member a fourth toggle,
//! Contributions ([crate::contributions]).
//!
//! Every state is the core's `GalleryModel` (`Gallery.Open`, `Watched.Gallery`): the rows, the chapters, what it offers
//! (rights only remove functions: share view = member view). Nothing decrypted is written to show it (ADR 0047).
use std::cell::{Cell, RefCell};
use std::collections::{HashMap, HashSet, VecDeque};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::{gdk, gio, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::ApplicationId, wire::Call, wire::Create, wire::GalleryRow, wire::GalleryShown, wire::UploadTileShown,
    wire::GridRow, wire::NodeAt, wire::ObjectRef, wire::Update, wire::Watched};
use crate::drop::Sorted;
use crate::home::{glib_escape, kind_icon};
use crate::banner::BannerView;
use crate::contributions::ContributionsView;
use crate::gallery_filter::FilterBar;
use crate::curation::PeopleLine;
use crate::lightbox::Lightbox;
use crate::memory_facts::FactsLine;
use crate::memory_map_view::MemoryMapView;
use crate::music_view::MusicView;
use crate::{header, said, words};

/// The page's margin around the grid and the chapters, in logical pixels (GNOME's 12).
const MARGIN: i32 = 12;

/// The longest side a tile's decoded thumbnail is held at (the grid's tallest row at twice its scale, about).
const TILE_EDGE: i32 = 400;

/// How many decoded tiles are held at once; the least recently shown goes first.
const TILES_HELD: usize = 400;

/// What a page tells the shell each time its state changes: the state, and its id in the core.
type OnShown = Box<dyn Fn(&GalleryShown, i64)>;

/// A Memory or a chapter on screen: opened in the core when made, closed when dropped.
pub struct MemoryPage {
    pub node: NodeAt,
    inner: Rc<Inner>,
}

struct Inner {
    node: NodeAt,
    root: gtk::ScrolledWindow,
    column: gtk::Box,
    hero_slot: gtk::Box,
    hero: RefCell<Option<gtk::Widget>>,
    /// The facts line under the head: its date and its place.
    facts: FactsLine,
    /// The banner above the head (ADR 0144 decision 4), and under the facts line the people line with *Add banner*.
    banner: BannerView,
    curation: gtk::Box,
    people: PeopleLine,
    add_banner: gtk::Button,
    add_banner_content: adw::ButtonContent,
    /// A delete of the banner's own picture asked (until the core drops its question).
    banner_asked: Cell<bool>,
    /// Photos | Music | Map (Music where the gallery offers its Music view, and whether it is listed now); the photos'
    /// part (the chapters and the grid), the Music view's place and the Map view's, one of them shown.
    switch: adw::ToggleGroup,
    /// What the switch lists beyond Photos and Map: (Music, Contributions), once a state said so.
    listed: Cell<Option<(bool, bool)>>,
    /// The filter over the photos (ADR 0149 decision 5).
    filter: FilterBar,
    /// The Contributions view's place and the view while it shows (ADR 0149 decision 6).
    contributions_slot: gtk::Box,
    contributions: RefCell<Option<ContributionsView>>,
    photos: gtk::Box,
    music_slot: gtk::Box,
    music: RefCell<Option<MusicView>>,
    map_slot: gtk::Box,
    map: RefCell<Option<MemoryMapView>>,
    chapters: adw::PreferencesGroup,
    chapter_rows: RefCell<Vec<adw::ActionRow>>,
    /// What shows in the grid's place while it holds no row: reading, empty, failed.
    status: gtk::Box,
    grid: gtk::Fixed,
    /// The rows mounted: by row index, its widget and what it was built from.
    mounted: RefCell<HashMap<i32, (gtk::Fixed, String, f64)>>,
    /// The placeholder tiles' progress bars, by their layout id: set from each update, never rebuilt for a step (ADR 0145 decision 3).
    bars: RefCell<HashMap<String, glib::WeakRef<gtk::ProgressBar>>>,
    /// The width the grid was last laid out at, and whether a layout is in flight / wanted again.
    laid_width: Cell<i32>,
    asking: Cell<bool>,
    again: Cell<bool>,
    id: Cell<Option<i64>>,
    shown: RefCell<Option<GalleryShown>>,
    watch: RefCell<Option<Watch>>,
    live: RefCell<Option<Watch>>,
    on_shown: RefCell<Option<OnShown>>,
    tiles: RefCell<Tiles>,
    toasts: adw::ToastOverlay,
    window: gtk::Window,
    nav: adw::NavigationView,
    undo_toast: RefCell<Option<adw::Toast>>,
    seen_undo: Cell<bool>,
    /// What the core said was shown (until its next state without it), and a purge asked (until it is answered).
    said_shown: Cell<bool>,
    /// The medium the search asked for that this page opened last (`GalleryShown.searchOpened`), until the core drops it.
    search_opened: RefCell<Option<String>>,
    purge_asked: Cell<bool>,
    lightbox: RefCell<Option<Lightbox>>,
    ticking: RefCell<Option<gtk::TickCallbackId>>,
}

/// The decoded tiles by their blob id, the least recently shown first out; and what waits for a tile being read.
#[derive(Default)]
struct Tiles {
    held: HashMap<String, gdk::Texture>,
    order: VecDeque<String>,
    reading: HashSet<String>,
    waiting: HashMap<String, Vec<glib::WeakRef<gtk::Picture>>>,
}

impl Tiles {
    fn get(&mut self, blob: &str) -> Option<gdk::Texture> {
        let t = self.held.get(blob)?.clone();
        self.order.retain(|b| b != blob);
        self.order.push_back(blob.to_owned());
        Some(t)
    }

    fn put(&mut self, blob: &str, texture: gdk::Texture) {
        self.held.insert(blob.to_owned(), texture);
        self.order.retain(|b| b != blob);
        self.order.push_back(blob.to_owned());
        while self.order.len() > TILES_HELD {
            if let Some(old) = self.order.pop_front() {
                self.held.remove(&old);
            }
        }
    }
}

impl MemoryPage {
    /// Opens Memory or chapter [node] on a page over [window]: its toasts in [toasts], its lightbox pushed onto [nav].
    pub fn open(node: &NodeAt, window: &gtk::Window, toasts: &adw::ToastOverlay, nav: &adw::NavigationView) -> Self {
        let column = gtk::Box::new(gtk::Orientation::Vertical, 18);
        column.set_margin_top(MARGIN);
        column.set_margin_bottom(MARGIN * 2);
        column.set_margin_start(MARGIN);
        column.set_margin_end(MARGIN);
        let banner = BannerView::new(toasts);
        column.append(banner.widget());
        let hero_slot = gtk::Box::new(gtk::Orientation::Vertical, 0);
        column.append(&hero_slot);
        let facts = FactsLine::new(window);
        column.append(facts.widget());
        let curation = gtk::Box::builder().spacing(6).visible(false).build();
        let people = PeopleLine::new(window);
        curation.append(people.widget());
        let add_content = adw::ButtonContent::builder().icon_name("image-x-generic-symbolic").label(words::memory_banner_add()).build();
        let add_banner = gtk::Button::builder().child(&add_content).visible(false).build();
        add_banner.add_css_class("flat");
        add_banner.set_widget_name("memory-banner-add");
        curation.append(&add_banner);
        column.append(&curation);
        // the view switch (GNOME: a toggle group for a few views of one content, AdwToggleGroup); its toggles once the
        // gallery says whether it offers music
        let switch = adw::ToggleGroup::builder().halign(gtk::Align::Center).visible(false).build();
        switch.set_widget_name("memory-view");
        column.append(&switch);
        let photos = gtk::Box::new(gtk::Orientation::Vertical, 18);
        column.append(&photos);
        let music_slot = gtk::Box::builder().orientation(gtk::Orientation::Vertical).visible(false).build();
        column.append(&music_slot);
        let map_slot = gtk::Box::builder().orientation(gtk::Orientation::Vertical).visible(false).build();
        column.append(&map_slot);
        let contributions_slot = gtk::Box::builder().orientation(gtk::Orientation::Vertical).visible(false).build();
        column.append(&contributions_slot);
        let chapters = adw::PreferencesGroup::builder().title(words::memory_chapters()).visible(false).build();
        photos.append(&chapters);
        let filter = FilterBar::new();
        photos.append(filter.widget());
        let status = gtk::Box::new(gtk::Orientation::Vertical, 0);
        photos.append(&status);
        let grid = gtk::Fixed::builder().hexpand(true).build();
        grid.update_property(&[gtk::accessible::Property::Label(&words::app_memories())]);
        photos.append(&grid);
        // External, not Never: the grid's rows are as wide as the width they were laid out at, and Never would hand that width on as
        // the page's minimum — the window could not shrink, so it never laid out anew (measured in Broadway, 2026-11-14)
        let root = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::External).vexpand(true).child(&column).build();
        let inner = Rc::new(Inner {
            node: node.clone(),
            root,
            column,
            hero_slot,
            hero: RefCell::new(None),
            facts,
            banner,
            curation,
            people,
            add_banner,
            add_banner_content: add_content,
            banner_asked: Cell::new(false),
            switch,
            listed: Cell::new(None),
            filter,
            contributions_slot,
            contributions: RefCell::new(None),
            photos,
            music_slot,
            music: RefCell::new(None),
            map_slot,
            map: RefCell::new(None),
            chapters,
            chapter_rows: RefCell::new(Vec::new()),
            status,
            grid,
            mounted: RefCell::new(HashMap::new()),
            bars: RefCell::new(HashMap::new()),
            laid_width: Cell::new(0),
            asking: Cell::new(false),
            again: Cell::new(false),
            id: Cell::new(None),
            shown: RefCell::new(None),
            watch: RefCell::new(None),
            live: RefCell::new(None),
            on_shown: RefCell::new(None),
            tiles: RefCell::new(Tiles::default()),
            toasts: toasts.clone(),
            window: window.clone(),
            nav: nav.clone(),
            undo_toast: RefCell::new(None),
            seen_undo: Cell::new(false),
            said_shown: Cell::new(false),
            search_opened: RefCell::new(None),
            purge_asked: Cell::new(false),
            lightbox: RefCell::new(None),
            ticking: RefCell::new(None),
        });
        inner.draw();
        Inner::connect(&inner);
        let weak = Rc::downgrade(&inner);
        let open = node.clone();
        glib::spawn_future_local(async move {
            let Ok((Answer::GalleryOpened { gallery }, _)) = bridge::call(&Call::GalleryOpen { node: open }).await else {
                eprintln!("veyq: the gallery did not open in the core");
                return;
            };
            let Some(inner) = weak.upgrade() else {
                bridge::send(Call::GalleryClose { gallery });
                return;
            };
            inner.id.set(Some(gallery));
            // the web's poll, for as long as the page stands: its reads arrive on the page's own watch
            *inner.live.borrow_mut() = bridge::watch(&Watched::GalleryLive { gallery }).map(|(w, _)| w);
            let Some((watch, updates)) = bridge::watch(&Watched::Gallery { gallery }) else { return };
            *inner.watch.borrow_mut() = Some(watch);
            drop(inner);
            follow(weak, updates).await;
        });
        Self { node: node.clone(), inner }
    }

    pub fn widget(&self) -> &gtk::ScrolledWindow {
        &self.inner.root
    }

    /// The node's head ([crate::header::Header::hero]) placed first in the page's scroll, as the Pot page places it.
    pub fn hero(&self, hero: Option<gtk::Widget>) {
        if *self.inner.hero.borrow() == hero {
            return;
        }
        while let Some(child) = self.inner.hero_slot.first_child() {
            self.inner.hero_slot.remove(&child);
        }
        if let Some(h) = &hero {
            if let Some(parent) = h.parent().and_downcast::<gtk::Box>() {
                parent.remove(h);
            }
            self.inner.hero_slot.append(h);
        }
        *self.inner.hero.borrow_mut() = hero;
    }

    /// Told each state and the page's id in the core (the count for the title, New's chapter, the header's own upload).
    pub fn on_shown(&self, f: impl Fn(&GalleryShown, i64) + 'static) {
        *self.inner.on_shown.borrow_mut() = Some(Box::new(f));
        if let (Some(s), Some(id)) = (self.inner.shown.borrow().as_ref(), self.inner.id.get())
            && let Some(f) = self.inner.on_shown.borrow().as_ref()
        {
            f(s, id);
        }
    }

    /// "Add photos": GTK's open dialog for photos and videos (several), their paths to the core (`Memory.Upload`).
    pub fn add(&self) {
        let Some(gallery) = self.inner.id.get() else { return };
        let (parent, toasts) = (self.inner.window.clone(), self.inner.toasts.clone());
        glib::spawn_future_local(async move {
            let filter = gtk::FileFilter::new();
            filter.set_name(Some(&words::memory_add()));
            filter.add_mime_type("image/*");
            filter.add_mime_type("video/*");
            let filters = gio::ListStore::new::<gtk::FileFilter>();
            filters.append(&filter);
            let dialog = gtk::FileDialog::builder().title(words::memory_add()).modal(true).filters(&filters).default_filter(&filter).build();
            if let Some(pictures) = glib::user_special_dir(glib::UserDirectory::Pictures).filter(|d| d.is_dir()) {
                dialog.set_initial_folder(Some(&gio::File::for_path(pictures)));
            }
            let picked = match dialog.open_multiple_future(Some(&parent)).await {
                Ok(picked) => picked,
                Err(e) => {
                    if !e.matches(gtk::DialogError::Dismissed) && !e.matches(gtk::DialogError::Cancelled) {
                        eprintln!("veyq: the open dialog failed: {e}");
                    }
                    return;
                }
            };
            let files: Vec<gio::File> = (0..picked.n_items()).filter_map(|i| picked.item(i).and_downcast::<gio::File>()).collect();
            upload(gallery, crate::drop::sort(&files), &toasts);
        });
    }

    /// The header's music action: the node's music started shuffled, or stopped where it plays (`Gallery.Music`).
    pub fn music(&self) {
        if let Some(gallery) = self.inner.id.get() {
            eprintln!("veyq: gallery music pressed");
            bridge::send(Call::GalleryMusic { gallery });
        }
    }

    /// Refresh (F5, Ctrl+R): the gallery read anew (`Gallery.Reload`).
    pub fn reload(&self) {
        if let Some(gallery) = self.inner.id.get() {
            bridge::send(Call::GalleryReload { gallery });
        }
    }

    /// New chapter (`Gallery.CreateChapter`): its name asked in an alert dialog, asked again with the core's problem; the
    /// chapter made opens.
    pub fn create_chapter(&self) {
        let Some(gallery) = self.inner.id.get() else { return };
        let toasts = self.inner.toasts.clone();
        let parent = self.inner.window.clone().upcast::<gtk::Widget>();
        header::ask_name(&parent, &words::memory_create_chapter_title(), &words::node_name(), "", None, &words::folder_create(), move |name, again| {
            let toasts = toasts.clone();
            glib::spawn_future_local(async move {
                match bridge::call(&Call::GalleryCreateChapter { gallery, name: name.clone() }).await.map(|(a, _)| a) {
                    Ok(Answer::Created { node: Some(node), .. }) => {
                        eprintln!("veyq: new chapter: made");
                        open_node(&node);
                    }
                    Ok(Answer::Created { notice: Some(n), .. }) => {
                        eprintln!("veyq: new chapter: {}", n.msg);
                        again(name, said::notice(&n));
                    }
                    other => header::say(&toasts, other, "Gallery.CreateChapter"),
                }
            });
        });
    }
}

impl Drop for MemoryPage {
    fn drop(&mut self) {
        if let Some(id) = self.inner.ticking.borrow_mut().take() {
            id.remove();
        }
        self.inner.lightbox.borrow_mut().take();
        self.inner.music.borrow_mut().take();
        self.inner.map.borrow_mut().take();
        self.inner.contributions.borrow_mut().take();
        self.inner.watch.borrow_mut().take();
        self.inner.live.borrow_mut().take();
        if let Some(t) = self.inner.undo_toast.borrow_mut().take() {
            t.dismiss();
        }
        if let Some(gallery) = self.inner.id.get() {
            bridge::send(Call::GalleryClose { gallery });
        }
    }
}

/// Opens [node] (a Memory or a chapter just made, a chapter's row) where the shell opens an object.
pub fn open_node(node: &NodeAt) {
    let r = ObjectRef { scope_id: node.scope_id.clone(), node_id: node.node_id.clone(), kind: node.kind.clone(), application: ApplicationId::Memories, name: node.name.clone() };
    bridge::send(Call::ShellOpen { r#ref: r });
}

/// What a drop or a pick onto a gallery sends (the drop's one rule here): the files as one `Memory.Upload`, nothing for a
/// folder (each said by its name, not taken) or for what has no local path (said as Depot's drop says it).
pub fn calls(gallery: i64, sorted: &Sorted) -> Option<Call> {
    (!sorted.files.is_empty()).then(|| Call::MemoryUpload { gallery, paths: sorted.files.clone() })
}

/// What a drop or a pick onto a gallery says at once, before the core answers: each folder not taken, each item that is
/// not on this computer.
pub fn said_at_once(sorted: &Sorted) -> Vec<String> {
    let name = |path: &String| std::path::Path::new(path).file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| path.clone());
    sorted.folders.iter().map(|f| words::memory_drop_folder(&name(f))).chain(sorted.refused.iter().map(|r| words::depot_drop_not_local(r))).collect()
}

/// Sends [sorted]'s files to gallery [gallery] and says what was not taken.
fn upload(gallery: i64, sorted: Sorted, toasts: &adw::ToastOverlay) {
    eprintln!("veyq: memory upload: {} files, {} folders, {} refused", sorted.files.len(), sorted.folders.len(), sorted.refused.len());
    for text in said_at_once(&sorted) {
        toasts.add_toast(adw::Toast::new(&glib_escape(&text)));
    }
    let Some(call) = calls(gallery, &sorted) else { return };
    let answer = bridge::call(&call);
    let toasts = toasts.clone();
    glib::spawn_future_local(async move {
        match answer.await.map(|(a, _)| a) {
            Ok(Answer::Queued { count, notice }) => {
                eprintln!("veyq: Memory.Upload: {count} queued");
                if let Some(n) = notice {
                    toasts.add_toast(adw::Toast::new(&glib_escape(&said::notice(&n))));
                }
            }
            other => header::say(&toasts, other, "Memory.Upload"),
        }
    });
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Gallery { shown, .. }) => {
                if let Some(s) = &shown {
                    eprintln!("veyq: gallery shown: {} rows, {} chapters, failed {}, undo {}, upload {}, save {}, creates {:?}",
                        s.rows.as_ref().map_or(-1, |r| r.len() as i64), s.chapters.len(), s.failed, s.undo.is_some(), s.can_upload, s.can_save,
                        s.creates.iter().map(|c| &c.create).collect::<Vec<_>>());
                }
                let chapters_changed = inner.shown.borrow().as_ref().map(|s| &s.chapters) != shown.as_ref().map(|s| &s.chapters);
                *inner.shown.borrow_mut() = shown;
                if chapters_changed {
                    inner.draw_chapters();
                }
                inner.draw();
                inner.relayout();
                inner.step_uploads();
                inner.open_searched();
                if let (Some(s), Some(id), Some(f)) = (inner.shown.borrow().as_ref(), inner.id.get(), inner.on_shown.borrow().as_ref()) {
                    f(s, id);
                }
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a gallery update did not read: {:?}", e.0),
        }
    }
}

impl Inner {
    fn connect(this: &Rc<Self>) {
        // the grid laid out anew when its width changes (a window resized, the sidebar shown), checked once a frame
        let weak = Rc::downgrade(this);
        let id = this.root.add_tick_callback(move |_, _| {
            if let Some(inner) = weak.upgrade()
                && inner.width() != inner.laid_width.get()
                && inner.width() > 0
            {
                inner.relayout();
            }
            glib::ControlFlow::Continue
        });
        *this.ticking.borrow_mut() = Some(id);
        // the rows near the view follow the scroll
        let adjustment = this.root.vadjustment();
        for signal in ["value-changed", "notify::page-size"] {
            let weak = Rc::downgrade(this);
            adjustment.connect_local(signal, false, move |_| {
                if let Some(inner) = weak.upgrade() {
                    inner.relayout();
                }
                None
            });
        }
        // a drop of files onto the page adds them (where the rights add anything)
        let target = gtk::DropTarget::new(gdk::FileList::static_type(), gdk::DragAction::COPY);
        let weak = Rc::downgrade(this);
        target.connect_drop(move |_, value, _, _| {
            let Some(inner) = weak.upgrade() else { return false };
            let (Some(gallery), true) = (inner.id.get(), inner.shown.borrow().as_ref().is_some_and(|s| s.can_upload)) else { return false };
            let Ok(list) = value.get::<gdk::FileList>() else { return false };
            let sorted = crate::drop::sort(&list.files());
            let took = !sorted.files.is_empty();
            upload(gallery, sorted, &inner.toasts);
            took
        });
        this.root.add_controller(target);
        // *Add banner*: the banner's dialog
        let weak = Rc::downgrade(this);
        this.add_banner.connect_clicked(move |_| {
            if let Some(inner) = weak.upgrade() {
                inner.banner.edit(&inner.window);
            }
        });
        // the view switch: the Music view opened while it shows, closed when the photos show again
        let weak = Rc::downgrade(this);
        this.switch.connect_active_name_notify(move |_| {
            if let Some(inner) = weak.upgrade() {
                inner.switch_view();
            }
        });
    }

    /// Shows the view the switch names: the photos, the node's Music view or its Map view (each opened in the core while
    /// it shows).
    fn switch_view(self: &Rc<Self>) {
        let active = self.switch.active_name();
        let music = active.as_deref() == Some("music") && self.switch.is_visible();
        let map = active.as_deref() == Some("map") && self.switch.is_visible();
        let contributions = active.as_deref() == Some("contributions") && self.switch.is_visible();
        self.photos.set_visible(!music && !map && !contributions);
        self.music_slot.set_visible(music);
        self.map_slot.set_visible(map);
        self.contributions_slot.set_visible(contributions);
        if contributions != self.contributions.borrow().is_some() {
            eprintln!("veyq: memory view: {}", if contributions { "contributions" } else { "not contributions" });
            while let Some(child) = self.contributions_slot.first_child() {
                self.contributions_slot.remove(&child);
            }
            *self.contributions.borrow_mut() = None;
            if let (true, Some(gallery)) = (contributions, self.id.get()) {
                let weak = Rc::downgrade(self);
                let view = ContributionsView::open(gallery, &self.window, &self.toasts, move |media_id| {
                    let Some(inner) = weak.upgrade() else { return };
                    let at = inner.shown.borrow().as_ref().and_then(|s| s.rows.as_ref()?.iter().position(|r| r.media_id == media_id));
                    match at {
                        Some(at) => inner.open_lightbox(at),
                        None => eprintln!("veyq: contributions: the medium is not in the gallery's filtered rows"),
                    }
                });
                self.contributions_slot.append(view.widget());
                *self.contributions.borrow_mut() = Some(view);
            }
        }
        if music != self.music.borrow().is_some() {
            eprintln!("veyq: memory view: {}", if music { "music" } else if map { "map" } else { "photos" });
            while let Some(child) = self.music_slot.first_child() {
                self.music_slot.remove(&child);
            }
            *self.music.borrow_mut() = None;
            if let (true, Some(gallery)) = (music, self.id.get()) {
                let view = MusicView::open(gallery, &self.window, &self.toasts);
                self.music_slot.append(view.widget());
                *self.music.borrow_mut() = Some(view);
            }
        }
        if map != self.map.borrow().is_some() {
            eprintln!("veyq: memory view: {}", if map { "map" } else if music { "music" } else { "photos" });
            while let Some(child) = self.map_slot.first_child() {
                self.map_slot.remove(&child);
            }
            *self.map.borrow_mut() = None;
            if let (true, Some(gallery)) = (map, self.id.get()) {
                let view = MemoryMapView::open(gallery, &self.window, &self.toasts);
                self.map_slot.append(view.widget());
                *self.map.borrow_mut() = Some(view);
            }
        }
    }

    /// The switch's toggles: Photos, Music where the gallery offers it, Map, Contributions where it offers them (a member's);
    /// the view shown kept where it is still listed.
    fn list_views(self: &Rc<Self>, music: bool, contributions: bool) {
        if self.listed.replace(Some((music, contributions))) == Some((music, contributions)) {
            return;
        }
        let active = self.switch.active_name().map(|n| n.to_string())
            .filter(|n| (music || n != "music") && (contributions || n != "contributions")).unwrap_or_else(|| "photos".into());
        self.switch.remove_all();
        let mut views = vec![("photos", words::music_view_photos())];
        if music {
            views.push(("music", words::music_view_music()));
        }
        views.push(("map", words::memory_view_map()));
        if contributions {
            views.push(("contributions", words::memory_buckets_title()));
        }
        for (name, label) in views {
            self.switch.add(adw::Toggle::builder().name(name).label(label).build());
        }
        self.switch.set_active_name(Some(&active));
        self.switch.set_visible(true);
        let this = self.clone();
        glib::idle_add_local_once(move || this.switch_view());
    }

    /// The grid's width: the scroll's, less the page's margins.
    fn width(&self) -> i32 {
        self.root.width() - 2 * MARGIN
    }

    /// The grid asked for anew (`Gallery.Layout`) at its width and the scroll's view; one ask at a time, the latest
    /// wanted asked once the one in flight answered.
    fn relayout(self: &Rc<Self>) {
        let Some(gallery) = self.id.get() else { return };
        let width = self.width();
        if width <= 0 || self.shown.borrow().as_ref().and_then(|s| s.rows.as_ref()).is_none() {
            return;
        }
        if self.asking.replace(true) {
            self.again.set(true);
            return;
        }
        let adjustment = self.root.vadjustment();
        // the view's top in the grid's own frame: the scroll's value less where the grid starts in the column
        let start = self.grid.compute_point(&self.column, &gtk::graphene::Point::new(0.0, 0.0)).map_or(0.0, |p| f64::from(p.y()));
        let top = (adjustment.value() - start).max(0.0);
        let view = adjustment.page_size().max(1.0);
        let weak = Rc::downgrade(self);
        let answer = bridge::call(&Call::GalleryLayout { gallery, width: f64::from(width), top, view_height: view });
        glib::spawn_future_local(async move {
            let answered = answer.await.map(|(a, _)| a);
            let Some(inner) = weak.upgrade() else { return };
            inner.asking.set(false);
            match answered {
                Ok(Answer::GridLayout { rows, height, count }) => {
                    if inner.laid_width.replace(width) != width {
                        eprintln!("veyq: gallery laid out {width} wide: {count} rows, {height:.0} high");
                    }
                    inner.place(&rows, height);
                }
                other => eprintln!("veyq: Gallery.Layout answered {other:?}"),
            }
            if inner.again.replace(false) {
                inner.relayout();
            }
        });
    }

    /// Mounts [rows] (the view's window) at their places in a grid [height] high; rows out of it go, a row whose frames
    /// changed is built anew.
    fn place(self: &Rc<Self>, rows: &[GridRow], height: f64) {
        self.grid.set_size_request(-1, height.ceil() as i32);
        let shown = self.shown.borrow();
        let by_id: HashMap<&str, (usize, &GalleryRow)> = shown.as_ref().and_then(|s| s.rows.as_ref()).map(|r| {
            r.iter().enumerate().map(|(i, row)| (row.media_id.as_str(), (i, row))).collect()
        }).unwrap_or_default();
        // what this device sends here leads the grid as placeholder tiles (ADR 0145 decision 3)
        let uploading: HashMap<&str, &UploadTileShown> = shown.as_ref().map(|s| s.uploading.iter().map(|u| (u.id.as_str(), u)).collect()).unwrap_or_default();
        let mut mounted = self.mounted.borrow_mut();
        let wanted: HashSet<i32> = rows.iter().map(|r| r.index).collect();
        mounted.retain(|index, (widget, _, _)| {
            let keep = wanted.contains(index);
            if !keep {
                self.grid.remove(widget);
            }
            keep
        });
        for r in rows {
            // a row is built from its frames and its media's tiles: the same row moves, another is made
            let key = r.tiles.iter().map(|t| {
                // a placeholder is built anew when it starts sending or fails, never for a step of its progress
                let stage = uploading.get(t.media_id.as_str()).map(|u| if u.failed { "failed" } else if u.preparing { "preparing" } else { "sending" });
                let tile = stage.or_else(|| by_id.get(t.media_id.as_str()).and_then(|(_, row)| row.tile.as_deref())).unwrap_or("-");
                // a mark's badge changed builds the tile anew
                let mark = by_id.get(t.media_id.as_str()).map_or((None, false), |(_, row)| (row.marks, row.mine));
                format!("{}:{tile}:{:.1}:{:.1}:{mark:?}", t.media_id, t.left, t.width)
            }).collect::<Vec<_>>().join(" ") + &format!(" h{:.1}", r.height);
            if let Some((widget, built, top)) = mounted.get_mut(&r.index) {
                if *built == key {
                    if (*top - r.top).abs() > 0.1 {
                        self.grid.move_(widget, 0.0, r.top);
                        *top = r.top;
                    }
                    continue;
                }
                self.grid.remove(widget);
            }
            let widget = self.row(r, &by_id, &uploading);
            self.grid.put(&widget, 0.0, r.top);
            mounted.insert(r.index, (widget, key, r.top));
        }
    }

    /// One row of the grid: its tiles at their frames.
    fn row(self: &Rc<Self>, r: &GridRow, by_id: &HashMap<&str, (usize, &GalleryRow)>, uploading: &HashMap<&str, &UploadTileShown>) -> gtk::Fixed {
        let line = gtk::Fixed::new();
        let height = r.height.round() as i32;
        for t in &r.tiles {
            if let Some(upload) = uploading.get(t.media_id.as_str()) {
                line.put(&self.placeholder(upload, t.width.round() as i32, height), t.left, 0.0);
                continue;
            }
            let Some((index, row)) = by_id.get(t.media_id.as_str()) else { continue };
            let tile = self.tile(row, *index, t.width.round() as i32, height);
            line.put(&tile, t.left, 0.0);
        }
        line
    }

    /// One tile: the grid tier's thumbnail filling its frame (cut to it), a video's play badge, the kind's icon where it
    /// has none; activating it opens the lightbox at [index].
    fn tile(self: &Rc<Self>, row: &GalleryRow, index: usize, width: i32, height: i32) -> gtk::Overlay {
        let name = row.name.clone().unwrap_or_else(|| words::memory_unnamed(index as i64 + 1));
        let tile = gtk::Overlay::builder().accessible_role(gtk::AccessibleRole::Button).focusable(true).width_request(width).height_request(height).tooltip_text(&name).build();
        tile.update_property(&[gtk::accessible::Property::Label(&name)]);
        tile.set_overflow(gtk::Overflow::Hidden);
        tile.add_css_class("card");
        let picture = gtk::Picture::builder().content_fit(gtk::ContentFit::Cover).can_shrink(true).width_request(width).height_request(height).build();
        tile.set_child(Some(&picture));
        match &row.tile {
            Some(blob) => self.show_tile(&picture, row, blob),
            None => {
                let icon = if row.blocked { "action-unavailable-symbolic" } else if row.video { "video-x-generic-symbolic" } else { "image-x-generic-symbolic" };
                let image = gtk::Image::builder().icon_name(icon).pixel_size(32).halign(gtk::Align::Center).valign(gtk::Align::Center).build();
                image.add_css_class("dim-label");
                tile.add_overlay(&image);
            }
        }
        if let Some(badge) = mark_badge(row) {
            tile.add_overlay(&badge);
            tile.update_property(&[gtk::accessible::Property::Description(&mark_said(row).unwrap_or_default())]);
        }
        if row.video && !row.blocked {
            let badge = gtk::Image::builder().icon_name("media-playback-start-symbolic").pixel_size(16).halign(gtk::Align::Start).valign(gtk::Align::End)
                .margin_start(6).margin_bottom(6).build();
            badge.add_css_class("osd");
            tile.add_overlay(&badge);
        }
        let weak = Rc::downgrade(self);
        let click = gtk::GestureClick::new();
        click.connect_released(move |gesture, _, _, _| {
            if let Some(inner) = weak.upgrade() {
                gesture.set_state(gtk::EventSequenceState::Claimed);
                inner.open_lightbox(index);
            }
        });
        tile.add_controller(click);
        let weak = Rc::downgrade(self);
        let keys = gtk::EventControllerKey::new();
        keys.connect_key_pressed(move |_, key, _, _| {
            if matches!(key, gdk::Key::Return | gdk::Key::KP_Enter | gdk::Key::space)
                && let Some(inner) = weak.upgrade()
            {
                inner.open_lightbox(index);
                return glib::Propagation::Stop;
            }
            glib::Propagation::Proceed
        });
        tile.add_controller(keys);
        tile
    }

    /// A photo or video on its way into this page (ADR 0145 decision 3): its kind's icon on a card, a spinner while its tiers
    /// are made on the device, then a bar of how far it is ([step_uploads] moves it), or that it failed — the uploads
    /// popover retries it. A screen reader reads its name and what is happening to it.
    fn placeholder(self: &Rc<Self>, upload: &UploadTileShown, width: i32, height: i32) -> gtk::Overlay {
        let label = said::notice(&upload.label);
        let tile = gtk::Overlay::builder().width_request(width).height_request(height).tooltip_text(&label).build();
        tile.update_property(&[gtk::accessible::Property::Label(&label)]);
        tile.set_overflow(gtk::Overflow::Hidden);
        tile.add_css_class("card");
        let icon = if upload.failed { "dialog-warning-symbolic" } else if upload.video { "video-x-generic-symbolic" } else { "image-x-generic-symbolic" };
        let image = gtk::Image::builder().icon_name(icon).pixel_size(32).halign(gtk::Align::Center).valign(gtk::Align::Center).build();
        image.add_css_class(if upload.failed { "error" } else { "dim-label" });
        tile.set_child(Some(&gtk::Box::new(gtk::Orientation::Vertical, 0)));
        tile.add_overlay(&image);
        if upload.preparing {
            let spinner = adw::Spinner::builder().width_request(16).height_request(16).halign(gtk::Align::End).valign(gtk::Align::End)
                .margin_end(6).margin_bottom(6).build();
            tile.add_overlay(&spinner);
        } else if !upload.failed {
            let bar = gtk::ProgressBar::builder().fraction(upload.progress).valign(gtk::Align::End).margin_start(6).margin_end(6).margin_bottom(6).build();
            bar.add_css_class("osd");
            self.bars.borrow_mut().insert(upload.id.clone(), bar.downgrade());
            tile.add_overlay(&bar);
        }
        tile
    }

    /// Each placeholder's bar moved to its upload's progress as the update says it; a bar no longer shown is forgotten.
    fn step_uploads(&self) {
        let shown = self.shown.borrow();
        let mut bars = self.bars.borrow_mut();
        bars.retain(|_, bar| bar.upgrade().is_some());
        for u in shown.as_ref().map(|s| s.uploading.as_slice()).unwrap_or_default() {
            if let Some(bar) = bars.get(&u.id).and_then(glib::WeakRef::upgrade) {
                bar.set_fraction(u.progress);
            }
        }
    }

    /// Shows tile [blob] of [row] in [picture]: from the held tiles, else read (`Gallery.Tile`) once for every picture
    /// that waits for it.
    fn show_tile(self: &Rc<Self>, picture: &gtk::Picture, row: &GalleryRow, blob: &str) {
        let mut tiles = self.tiles.borrow_mut();
        if let Some(t) = tiles.get(blob) {
            picture.set_paintable(Some(&t));
            return;
        }
        tiles.waiting.entry(blob.to_owned()).or_default().push(picture.downgrade());
        if !tiles.reading.insert(blob.to_owned()) {
            return;
        }
        drop(tiles);
        let Some(gallery) = self.id.get() else { return };
        let (weak, blob, media_id) = (Rc::downgrade(self), blob.to_owned(), row.media_id.clone());
        glib::spawn_future_local(async move {
            let answered = bridge::call(&Call::GalleryTile { gallery, media_id }).await;
            let Some(inner) = weak.upgrade() else { return };
            let mut tiles = inner.tiles.borrow_mut();
            tiles.reading.remove(&blob);
            let waiting = tiles.waiting.remove(&blob).unwrap_or_default();
            let texture = match answered {
                Ok((Answer::Bytes { .. }, Some(bytes))) => match gdk::Texture::from_bytes(&glib::Bytes::from_owned(bytes)) {
                    Ok(t) => crate::file_page::within_edge(&t, TILE_EDGE),
                    Err(e) => return eprintln!("veyq: a tile did not decode: {e}"),
                },
                other => return eprintln!("veyq: Gallery.Tile answered {:?}", other.map(|(a, _)| a)),
            };
            tiles.put(&blob, texture.clone());
            for p in waiting.iter().filter_map(glib::WeakRef::upgrade) {
                p.set_paintable(Some(&texture));
            }
        });
    }

    /// The search asked for a medium of this page (another Memory's photo opened from its results): its lightbox, once.
    fn open_searched(self: &Rc<Self>) {
        let (asked, at) = {
            let shown = self.shown.borrow();
            let asked = shown.as_ref().and_then(|s| s.search_opened.clone());
            let at = asked.as_ref().and_then(|id| shown.as_ref()?.rows.as_ref()?.iter().position(|r| r.media_id == *id));
            (asked, at)
        };
        if asked.is_none() || *self.search_opened.borrow() == asked {
            *self.search_opened.borrow_mut() = asked;
            return;
        }
        let (Some(at), Some(gallery)) = (at, self.id.get()) else { return };
        *self.search_opened.borrow_mut() = asked;
        eprintln!("veyq: the search's medium opens at {at}");
        self.open_lightbox(at);
        bridge::send(Call::GallerySearchOpenedSeen { gallery });
    }

    fn open_lightbox(self: &Rc<Self>, index: usize) {
        let Some(gallery) = self.id.get() else { return };
        eprintln!("veyq: lightbox asked at {index}");
        let lightbox = Lightbox::open(gallery, index as i32, &self.node, &self.window, &self.toasts, &self.nav);
        *self.lightbox.borrow_mut() = Some(lightbox);
    }

    /// A Memory's chapters: a row each, its cover read where it has one (`Gallery.ChapterCover`), opening the chapter.
    fn draw_chapters(self: &Rc<Self>) {
        for row in self.chapter_rows.borrow_mut().drain(..) {
            self.chapters.remove(&row);
        }
        let shown = self.shown.borrow();
        let chapters = shown.as_ref().map(|s| s.chapters.clone()).unwrap_or_default();
        self.chapters.set_visible(!chapters.is_empty());
        let Some(gallery) = self.id.get() else { return };
        for chapter in chapters {
            let row = adw::ActionRow::builder().title(glib_escape(&chapter.name)).activatable(true).build();
            let cover = gtk::Picture::builder().content_fit(gtk::ContentFit::Cover).can_shrink(true).width_request(64).height_request(36).valign(gtk::Align::Center).build();
            cover.set_overflow(gtk::Overflow::Hidden);
            cover.add_css_class("card");
            let icon = gtk::Image::from_icon_name(kind_icon(&chapter.kind));
            let prefix = gtk::Stack::new();
            prefix.add_child(&icon);
            prefix.add_child(&cover);
            row.add_prefix(&prefix);
            row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
            let open = chapter.clone();
            row.connect_activated(move |_| open_node(&open));
            self.chapters.add(&row);
            self.chapter_rows.borrow_mut().push(row);
            let (node_id, cover, prefix) = (chapter.node_id.clone(), cover.downgrade(), prefix.downgrade());
            glib::spawn_future_local(async move {
                let answered = bridge::call(&Call::GalleryChapterCover { gallery, node_id }).await;
                let (Some(cover), Some(prefix)) = (cover.upgrade(), prefix.upgrade()) else { return };
                if let Ok((Answer::Bytes { .. }, Some(bytes))) = answered
                    && let Ok(t) = gdk::Texture::from_bytes(&glib::Bytes::from_owned(bytes))
                {
                    cover.set_paintable(Some(&crate::file_page::within_edge(&t, 160)));
                    prefix.set_visible_child(&cover);
                }
            });
        }
    }

    /// What is not the grid: the status in its place, the trash's Undo, what the core says, a purge asked.
    fn draw(self: &Rc<Self>) {
        let shown = self.shown.borrow();
        while let Some(child) = self.status.first_child() {
            self.status.remove(&child);
        }
        match shown.as_ref().map(|s| (s.rows.as_ref(), s.failure.as_deref())) {
            None | Some((None, None)) => {
                let page = adw::StatusPage::builder().vexpand(true).build();
                page.set_child(Some(&adw::Spinner::builder().width_request(32).height_request(32).halign(gtk::Align::Center).build()));
                page.add_css_class("compact");
                self.status.append(&page);
            }
            // offline, what the store does not hold says so, else the load failed (the core's words)
            Some((None, Some(failure))) => {
                let icon = if shown.as_ref().is_some_and(|s| s.offline.is_some()) { "network-offline-symbolic" } else { "dialog-warning-symbolic" };
                self.status.append(&status(&said::key(failure), icon))
            }
            Some((Some(rows), _)) if rows.is_empty() && shown.as_ref().is_some_and(|s| s.uploading.is_empty()) => {
                self.drop_rows();
                // a filter that keeps nothing says so, never that the Memory is empty (E5)
                match shown.as_ref().and_then(|s| s.filter.as_ref()).and_then(crate::gallery_filter::empty_text) {
                    Some(text) => self.status.append(&status(&text, "edit-find-symbolic")),
                    None => self.status.append(&status(&words::memory_gallery_empty(), kind_icon(&self.node.kind))),
                }
            }
            Some((Some(_), _)) => {}
        }
        let Some(s) = shown.as_ref() else { return };
        if let Some(gallery) = self.id.get() {
            self.filter.draw(s.filter.as_ref(), gallery);
            self.facts.draw(s, gallery);
            self.banner.draw(s.banner.as_ref(), gallery);
            self.people.draw(s.people.as_ref(), gallery);
        }
        // *Add banner* where there is none, *Change banner* where there is one
        self.add_banner.set_visible(s.edits_banner);
        self.add_banner_content.set_label(&if s.banner.is_some() { words::memory_banner_change() } else { words::memory_banner_add() });
        // set from what it shows, never read back (a hidden box's children read as hidden too)
        self.curation.set_visible(s.people.is_some() || s.edits_banner);
        // A2: a delete of the banner's own picture asks first; the banner stays unless *Remove the banner too*
        if s.banner_question.is_none() {
            self.banner_asked.set(false);
        }
        if let (Some(_), Some(gallery), false) = (&s.banner_question, self.id.get(), self.banner_asked.replace(s.banner_question.is_some())) {
            eprintln!("veyq: the banner's picture asked about");
            let ask = adw::AlertDialog::builder().heading(words::memory_banner_picture_title()).body(words::memory_banner_picture_notice()).build();
            ask.add_responses(&[("cancel", &words::entry_cancel()), ("remove", &words::memory_banner_picture_remove()), ("trash", &words::memory_trash())]);
            ask.set_response_appearance("remove", adw::ResponseAppearance::Destructive);
            ask.set_response_appearance("trash", adw::ResponseAppearance::Destructive);
            ask.set_close_response("cancel");
            let toasts = self.toasts.clone();
            ask.connect_response(None, move |_, response| {
                if response == "cancel" {
                    bridge::send(Call::GalleryCancelAsk { gallery });
                    return;
                }
                let remove_banner = response == "remove";
                let toasts = toasts.clone();
                glib::spawn_future_local(async move {
                    let answered = bridge::call(&Call::GalleryTrashBannerPicture { gallery, remove_banner }).await.map(|(a, _)| a);
                    eprintln!("veyq: Gallery.TrashBannerPicture (remove the banner {remove_banner}) answered {answered:?}");
                    if !matches!(answered, Ok(Answer::Trashed { .. }) | Ok(Answer::Done)) {
                        header::say(&toasts, answered, "Gallery.TrashBannerPicture");
                    }
                });
            });
            ask.present(Some(&self.window));
        }
        self.list_views(s.music_view, s.contributions);
        // the offer ended (its window, or the undo): the toast goes with it — once a state showed the offer, so an update
        // from before the trash that arrives after its answer ends nothing (src/mobile/CLAUDE.md § Traps)
        if let Some(undo) = &s.undo {
            if !self.seen_undo.replace(true)
                && let Some(gallery) = self.id.get()
            {
                let toast = adw::Toast::builder().title(glib_escape(&said::notice(undo))).timeout(0).button_label(words::trash_undo()).build();
                toast.connect_button_clicked(move |_| {
                    eprintln!("veyq: gallery undo pressed");
                    bridge::send(Call::GalleryUndo { gallery });
                });
                self.toasts.add_toast(toast.clone());
                *self.undo_toast.borrow_mut() = Some(toast);
            }
        } else if self.seen_undo.replace(false)
            && let Some(t) = self.undo_toast.borrow_mut().take()
        {
            eprintln!("veyq: gallery undo offer ended");
            t.dismiss();
        }
        if s.said.is_none() {
            self.said_shown.set(false);
        }
        if let (Some(said), Some(gallery), false) = (&s.said, self.id.get(), self.said_shown.replace(s.said.is_some())) {
            eprintln!("veyq: gallery said: {}", said.msg);
            self.toasts.add_toast(adw::Toast::new(&glib_escape(&said::notice(said))));
            bridge::send(Call::GallerySeen { gallery });
        }
        if s.purging.is_none() {
            self.purge_asked.set(false);
        }
        if let (Some(_), Some(question), Some(gallery), false) = (&s.purging, &s.purge_question, self.id.get(), self.purge_asked.replace(s.purging.is_some())) {
            let ask = adw::AlertDialog::builder().heading(words::memory_delete_own_title()).body(said::notice(question)).build();
            ask.add_responses(&[("cancel", &words::entry_cancel()), ("purge", &words::memory_delete_own_accept())]);
            ask.set_response_appearance("purge", adw::ResponseAppearance::Destructive);
            ask.set_close_response("cancel");
            let toasts = self.toasts.clone();
            ask.connect_response(None, move |_, response| {
                if response == "purge" {
                    let toasts = toasts.clone();
                    glib::spawn_future_local(async move {
                        header::say(&toasts, bridge::call(&Call::GalleryPurge { gallery }).await.map(|(a, _)| a), "Gallery.Purge");
                    });
                } else {
                    bridge::send(Call::GalleryCancelAsk { gallery });
                }
            });
            ask.present(Some(&self.window));
        }
    }

    /// Every mounted row let go: the next layout mounts the window anew.
    fn drop_rows(&self) {
        for (_, (widget, _, _)) in self.mounted.borrow_mut().drain() {
            self.grid.remove(&widget);
        }
    }
}

/// A medium's mark as its tile shows it (ADR 0149 decision 4): the heart — filled where the caller marked it — with the count
/// where the scope counts marks and someone did; none where nobody marked it (or, in a personal scope, the caller did not).
/// A badge, no button: the mark is toggled in the lightbox.
fn mark_badge(row: &GalleryRow) -> Option<gtk::Box> {
    let (heart, count) = badge_of(row)?;
    let badge = gtk::Box::builder().spacing(3).halign(gtk::Align::End).valign(gtk::Align::Start).margin_end(6).margin_top(6).can_target(false).build();
    badge.add_css_class("osd");
    badge.add_css_class("veyq-mark-badge");
    badge.append(&gtk::Image::from_icon_name(heart));
    if let Some(n) = count {
        badge.append(&gtk::Label::new(Some(&n)));
    }
    Some(badge)
}

/// What a tile's badge draws ([mark_badge]): its heart's icon and the count it shows; none where it shows no badge.
fn badge_of(row: &GalleryRow) -> Option<(&'static str, Option<String>)> {
    let count = row.marks.filter(|n| *n > 0);
    if count.is_none() && !row.mine {
        return None;
    }
    Some((if row.mine { crate::theme::HEART_FILLED } else { crate::theme::HEART }, count.map(|n| n.to_string())))
}

/// What a screen reader says of a tile's badge: how many marked it, and that the caller did.
fn mark_said(row: &GalleryRow) -> Option<String> {
    let count = row.marks.filter(|n| *n > 0).map(|n| words::media_marks_count(i64::from(n)));
    let yours = row.mine.then(words::media_marks_yours);
    let said: Vec<String> = [count, yours].into_iter().flatten().collect();
    (!said.is_empty()).then(|| said.join(", "))
}

/// What shows in the grid's place: [text] under [icon].
fn status(text: &str, icon: &str) -> gtk::Widget {
    let page = adw::StatusPage::builder().icon_name(icon).title(glib_escape(text)).vexpand(true).build();
    page.add_css_class("compact");
    page.upcast()
}

/// Whether a gallery's New offers a chapter (`GalleryShown.creates`, the core's `GalleryModel.creates`).
pub fn offers_chapter(shown: &GalleryShown) -> bool {
    shown.creates.iter().any(|c| c.create == Create::Chapter)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sorted() -> Sorted {
        Sorted {
            files: vec!["/home/p/Bilder/a.jpg".into(), "/home/p/Bilder/clip.webm".into()],
            folders: vec!["/home/p/Bilder/Reise ü".into()],
            refused: vec!["away.jpg".into()],
        }
    }

    /// A drop onto a gallery: its files in one `Memory.Upload` (the core leaves out what is no medium and says so), a
    /// folder said and not taken (owner question 3), what is not on this computer said as Depot's drop says it.
    #[test]
    fn a_drop_onto_a_gallery_sends_its_files_and_says_each_folder_not_taken() {
        let call = serde_json::to_value(calls(7, &sorted())).unwrap();
        let files = serde_json::to_value(Some(Call::MemoryUpload { gallery: 7, paths: vec!["/home/p/Bilder/a.jpg".into(), "/home/p/Bilder/clip.webm".into()] })).unwrap();
        assert_eq!(call, files, "the files, one batch, no folder");
        let said = said_at_once(&sorted());
        assert_eq!(said, vec![words::memory_drop_folder("Reise ü"), words::depot_drop_not_local("away.jpg")]);
        let only_folder = Sorted { folders: vec!["/x/y".into()], ..Sorted::default() };
        assert!(calls(7, &only_folder).is_none(), "a folder alone sends nothing");
        assert_eq!(said_at_once(&only_folder), vec![words::memory_drop_folder("y")]);
    }

    fn row(marks: Option<i32>, mine: bool) -> GalleryRow {
        GalleryRow { media_id: "m".into(), name: None, media_type: None, video: false, blocked: false, tile: None, picture: None, captured_at: None,
            width: None, height: None, said: None, can_delete: false, marks, mine }
    }

    /// A tile's badge (ADR 0149 decision 4): the count where the scope counts and someone marked it, the heart filled where
    /// the caller did; nothing where nobody did; in a personal scope (no count) only the caller's own heart.
    #[test]
    fn a_tiles_badge_shows_the_count_and_the_callers_own_heart() {
        assert_eq!(badge_of(&row(Some(0), false)), None, "nobody marked it");
        assert_eq!(badge_of(&row(None, false)), None, "a personal scope, not marked");
        assert_eq!(mark_said(&row(Some(2), false)), Some(words::media_marks_count(2)));
        assert_eq!(mark_said(&row(Some(1), true)), Some(format!("{}, {}", words::media_marks_count(1), words::media_marks_yours())));
        assert_eq!(mark_said(&row(None, true)), Some(words::media_marks_yours()), "a personal scope says no count");
        assert_eq!(badge_of(&row(Some(3), true)), Some((crate::theme::HEART_FILLED, Some("3".into()))));
        assert_eq!(badge_of(&row(Some(2), false)), Some((crate::theme::HEART, Some("2".into()))), "others' marks: the open heart");
        assert_eq!(badge_of(&row(None, true)), Some((crate::theme::HEART_FILLED, None)), "no count in a personal scope");
    }

    #[test]
    fn the_tiles_held_are_bounded_the_least_recently_shown_first_out() {
        let mut tiles = Tiles::default();
        let texture = || -> gdk::Texture {
            gdk::MemoryTexture::new(1, 1, gdk::MemoryFormat::R8g8b8a8, &glib::Bytes::from_static(&[0, 0, 0, 255]), 4).upcast()
        };
        for i in 0..TILES_HELD {
            tiles.put(&format!("b{i}"), texture());
        }
        assert!(tiles.get("b0").is_some(), "b0 shown again: the most recent now");
        tiles.put("new", texture());
        assert_eq!(tiles.held.len(), TILES_HELD);
        assert!(tiles.get("b0").is_some() && tiles.get("b1").is_none(), "b1 was the least recently shown");
    }
}
