//! Home and an application's list (ADR 0122): Home's level as the core groups it — the folders, then the contents —
//! under the filters the core offers, and an application's own list in its Favorites, Recent and the rest. Each drawn
//! as GNOME draws a list of places: boxed lists in a clamped, scrolled column, a filter as a toggle group — or, where the
//! account chose tiles (`ShellShown.view`, ADR 0149 decision 8), each group as a grid of tiles ([crate::core_ui::tiles]).
//! Every node's row and tile opens its menu on a right click or a long press: the core's plan for it
//! ([crate::core_ui::header::row_menu], `Row.Plan`, ADR 0154 decision 2).
use std::cell::RefCell;
use std::collections::HashMap;

use adw::prelude::*;
use gtk::{gdk, glib};

use crate::bridge::{self, wire::Answer, wire::ApplicationId, wire::ArrangedList, wire::Call, wire::Crop, wire::MemoryDateShown, wire::NodeRef, wire::ObjectRef, wire::QueuedItem,
    wire::ShellModelState, wire::ShellShown, wire::FolderRef, wire::ViewModeShown};
use crate::core_ui::tiles::{self, Tile};
use crate::core_ui::drop::Drops;
use crate::core_ui::header::row_menu;
use crate::core_ui::{said, words};

/// The icon of a node's kind — Android's `kindIcon`, Apple's `Names.kindSymbol`, in GNOME's icon set.
pub fn kind_icon(kind: &str) -> &'static str {
    match kind {
        "FOLDER" => "folder-symbolic",
        "EXPENSES_POT" => "x-office-spreadsheet-symbolic",
        "MEMORY" => "camera-photo-symbolic",
        "CHAPTER" => "folder-pictures-symbolic",
        "DEPOT_DROPBOX" => "folder-download-symbolic",
        "SHARE_BOOKMARK" => "folder-publicshare-symbolic",
        _ => "text-x-generic-symbolic",
    }
}

/// An application's icon.
pub fn app_icon(app: ApplicationId) -> &'static str {
    match app {
        ApplicationId::Memories => "camera-photo-symbolic",
        ApplicationId::Depot => "folder-documents-symbolic",
        ApplicationId::Expenses => "x-office-spreadsheet-symbolic",
    }
}

/// An application's name, as the core names it ([ShellShown::application_words]).
pub fn app_name(shown: &ShellShown, app: ApplicationId) -> String {
    shown.application_words.iter().find(|w| w.application == app).map(|w| said::key(&w.word)).unwrap_or_default()
}

/// A row that opens [r] in its application when activated; its kind's icon, replaced by its cover where it has one
/// ([cover_prefix]), as every row on the phones.
pub fn object_row(r: &ObjectRef) -> adw::ActionRow {
    let row = adw::ActionRow::builder().title(glib_escape(&r.name)).activatable(true).build();
    row.add_prefix(&cover_prefix(gtk::Image::from_icon_name(kind_icon(&r.kind)), &r.node_id));
    if r.kind == "MEMORY" {
        dated(&row, &r.node_id);
    }
    let open = r.clone();
    row.connect_activated(move |_| bridge::send(Call::ShellOpen { r#ref: open.clone() }));
    row
}

thread_local! {
    /// Every Memory's and chapter's date the core unseals from the meta it holds (`Memories.Dates`), by node id; the
    /// objects they were asked for last, and whether an ask is in flight.
    static ROW_DATES: RefCell<HashMap<String, MemoryDateShown>> = RefCell::new(HashMap::new());
    static DATES_ASKED: RefCell<(String, bool)> = const { RefCell::new((String::new(), false)) };
    /// The rows and tiles drawn that show a date (a row's subtitle, a tile's second line), each told it again when the
    /// dates arrive.
    static DATED_ROWS: RefCell<Vec<(String, glib::WeakRef<gtk::Widget>)>> = const { RefCell::new(Vec::new()) };
}

/// A Memory's row's second line, its date (ADR 0143 decision 5; the facts line's way of saying it), set now where it is
/// held and again whenever the dates arrive.
fn dated(row: &adw::ActionRow, node_id: &str) {
    date_on(row.upcast_ref(), node_id);
}

/// A Memory's tile's second line, its date, as [dated] tells a row's.
pub fn dated_label(line: &gtk::Label, node_id: &str) {
    date_on(line.upcast_ref(), node_id);
}

fn date_on(widget: &gtk::Widget, node_id: &str) {
    if let Some(text) = ROW_DATES.with(|d| d.borrow().get(node_id).and_then(crate::core_ui::said::date_text)) {
        set_date(widget, &text);
    }
    DATED_ROWS.with(|r| {
        let mut r = r.borrow_mut();
        r.retain(|(_, w)| w.upgrade().is_some());
        r.push((node_id.to_owned(), widget.downgrade()));
    });
}

/// [text] as [widget]'s date: a row's subtitle (markup), a tile's line (plain).
fn set_date(widget: &gtk::Widget, text: &str) {
    if let Some(row) = widget.downcast_ref::<adw::ActionRow>() {
        row.set_subtitle(&glib_escape(text));
    } else if let Some(line) = widget.downcast_ref::<gtk::Label>() {
        line.set_label(text);
    }
}

/// The rows' dates asked for (`Memories.Dates`) unless they were for the objects [key] names (or [again]: a page closed
/// back onto the list, its date maybe edited); each row drawn is told its date once they arrive.
pub fn ask_dates(key: &str, again: bool) {
    let ask = DATES_ASKED.with(|a| {
        let mut a = a.borrow_mut();
        if a.1 || (a.0 == key && !again) {
            return false;
        }
        *a = (key.to_owned(), true);
        true
    });
    if !ask {
        return;
    }
    glib::spawn_future_local(async {
        let answered = bridge::call(&Call::MemoriesDates).await.map(|(a, _)| a);
        DATES_ASKED.with(|a| a.borrow_mut().1 = false);
        let dates = match answered {
            Ok(Answer::MemoryDates { dates }) => dates,
            other => return eprintln!("veyq: Memories.Dates answered {other:?}"),
        };
        let next: HashMap<String, MemoryDateShown> = dates.into_iter().map(|d| (d.node_id, d.date)).collect();
        eprintln!("veyq: memory dates: {} of {} dated", next.values().filter(|d| crate::core_ui::said::date_text(d).is_some()).count(), next.len());
        ROW_DATES.with(|d| *d.borrow_mut() = next);
        DATED_ROWS.with(|r| {
            for (node, row) in r.borrow().iter() {
                if let Some(row) = row.upgrade() {
                    let text = ROW_DATES.with(|d| d.borrow().get(node).and_then(crate::core_ui::said::date_text)).unwrap_or_default();
                    set_date(&row, &text);
                }
            }
        });
    });
}

/// The longest side a list row's cover is held at: it is drawn 64 × 36 in a row, and as a tile's square cover up to about
/// 250 wide (the core's `TileGrid` at a wide window), which a smaller one would blur.
const ROW_COVER_EDGE: i32 = 320;

/// How many rows' covers are held at once.
const ROW_COVERS_HELD: usize = 256;

thread_local! {
    /// The list rows' covers by node id: the frame each was cut for (the core's `RowCover.frame`) and the cut picture —
    /// a list is drawn anew on every state, and a held frame is answered without its picture.
    static ROW_COVERS: RefCell<HashMap<String, (String, gdk::Texture)>> = RefCell::new(HashMap::new());
}

/// [icon] in a row's prefix, replaced by node [node_id]'s cover once the core names one (`Shell.RowCover`): the
/// framed part of the stored picture, cut where the core says (`Cover.Cut`), as the chapters' rows and the phones'.
pub fn cover_prefix(icon: gtk::Image, node_id: &str) -> gtk::Stack {
    let cover = gtk::Picture::builder().content_fit(gtk::ContentFit::Cover).can_shrink(true).width_request(64).height_request(36).valign(gtk::Align::Center).build();
    cover.set_overflow(gtk::Overflow::Hidden);
    cover.add_css_class("card");
    // sized by what it shows: a row whose cover went is an icon's width again, not the picture's
    let prefix = gtk::Stack::builder().hhomogeneous(false).vhomogeneous(false).build();
    with_cover(&prefix, icon, cover, node_id);
    prefix
}

/// [icon] in a tile's square, replaced by node [node_id]'s cover in [picture] as [cover_prefix] replaces a row's.
pub fn cover_stack(icon: gtk::Image, picture: gtk::Picture, node_id: &str) -> gtk::Stack {
    let stack = gtk::Stack::new();
    with_cover(&stack, icon, picture, node_id);
    stack
}

/// [prefix] showing [icon], then [cover] once the core names node [node_id]'s cover (`Shell.RowCover`).
fn with_cover(prefix: &gtk::Stack, icon: gtk::Image, cover: gtk::Picture, node_id: &str) {
    prefix.add_child(&icon);
    prefix.add_child(&cover);
    if let Some((_, t)) = ROW_COVERS.with(|c| c.borrow().get(node_id).cloned()) {
        cover.set_paintable(Some(&t));
        prefix.set_visible_child(&cover);
    }
    let (node_id, cover, icon, weak) = (node_id.to_owned(), cover.downgrade(), icon.downgrade(), prefix.downgrade());
    glib::spawn_future_local(async move {
        let Some(shown) = asked_row_cover(&node_id).await else { return };
        let (Some(cover), Some(icon), Some(prefix)) = (cover.upgrade(), icon.upgrade(), weak.upgrade()) else { return };
        match shown {
            Some(t) => {
                cover.set_paintable(Some(&t));
                prefix.set_visible_child(&cover);
            }
            None => {
                cover.set_paintable(None::<&gdk::Paintable>);
                prefix.set_visible_child(&icon);
            }
        }
    });
}

/// Node [node_id]'s list-row cover (a Memories map's pin shows it too, ADR 0143 decision 6): the one held, else asked for
/// (`Shell.RowCover`); none where it has none.
pub async fn row_cover_texture(node_id: &str) -> Option<gdk::Texture> {
    let held = ROW_COVERS.with(|c| c.borrow().get(node_id).map(|(_, t)| t.clone()));
    match asked_row_cover(node_id).await {
        Some(asked) => asked,
        None => held,
    }
}

/// Node [node_id]'s cover asked for anew (`Shell.RowCover`, naming the frame held): Some(the cover, or none where it has
/// none now), or None where the held one stands (or the ask failed).
async fn asked_row_cover(node_id: &str) -> Option<Option<gdk::Texture>> {
    let held_frame = ROW_COVERS.with(|c| c.borrow().get(node_id).map(|(f, _)| f.clone()));
    let answered = bridge::call(&Call::ShellRowCover { node_id: node_id.to_owned(), held: held_frame }).await;
    match answered {
        Ok((Answer::RowCover { sent: false, .. }, _)) => None,
        Ok((Answer::RowCover { frame, crop, sent: true, .. }, Some(bytes))) => Some(match row_cover(bytes, crop).await {
            Some(t) => {
                ROW_COVERS.with(|c| {
                    let mut c = c.borrow_mut();
                    if c.len() >= ROW_COVERS_HELD && !c.contains_key(node_id)
                        && let Some(k) = c.keys().next().cloned()
                    {
                        c.remove(&k);
                    }
                    c.insert(node_id.to_owned(), (frame, t.clone()));
                });
                Some(t)
            }
            None => None,
        }),
        Ok((Answer::Refused { .. }, _)) => {
            ROW_COVERS.with(|c| c.borrow_mut().remove(node_id));
            Some(None)
        }
        other => {
            eprintln!("veyq: Shell.RowCover answered {:?}", other.map(|(a, _)| a));
            None
        }
    }
}

/// A row's cover from the stored picture [bytes]: decoded by GTK, the part [crop] frames cut out where the core says, held
/// small.
async fn row_cover(bytes: Vec<u8>, crop: Crop) -> Option<gdk::Texture> {
    let texture = gdk::Texture::from_bytes(&glib::Bytes::from_owned(bytes)).inspect_err(|e| eprintln!("veyq: a row's cover did not decode: {e}")).ok()?;
    let (width, height) = (texture.width(), texture.height());
    let Ok((Answer::CoverCut { x, y, width: cw, height: ch }, _)) = bridge::call(&Call::CoverCut { crop, width, height }).await else {
        eprintln!("veyq: a row's cover was not cut");
        return None;
    };
    Some(crate::core_ui::picture::within_edge(&cut(&texture, x, y, cw, ch)?, ROW_COVER_EDGE))
}

/// The part ([x], [y], [width] × [height] pixels) of [texture], as a texture of its own; None where it is not inside it.
pub fn cut(texture: &gdk::Texture, x: i32, y: i32, width: i32, height: i32) -> Option<gdk::Texture> {
    let (tw, th) = (texture.width(), texture.height());
    if width <= 0 || height <= 0 || x < 0 || y < 0 || x + width > tw || y + height > th {
        return None;
    }
    let mut downloader = gdk::TextureDownloader::new(texture);
    downloader.set_format(gdk::MemoryFormat::B8g8r8a8Premultiplied);
    let (whole, stride) = downloader.download_bytes();
    let row = width as usize * 4;
    let mut part = Vec::with_capacity(row * height as usize);
    for r in y..y + height {
        let at = r as usize * stride + x as usize * 4;
        part.extend_from_slice(&whole[at..at + row]);
    }
    Some(gdk::MemoryTexture::new(width, height, gdk::MemoryFormat::B8g8r8a8Premultiplied, &glib::Bytes::from_owned(part), row).upcast())
}

/// A row's title is markup in libadwaita: a name is shown as it is written.
pub fn glib_escape(text: &str) -> String {
    gtk::glib::markup_escape_text(text).into()
}

/// A level's scrolled column: clamped as GNOME clamps a list, wider for tiles (Files' grid fills its window).
fn column(tiles: bool) -> (gtk::ScrolledWindow, gtk::Box) {
    let content = gtk::Box::new(gtk::Orientation::Vertical, 24);
    content.set_margin_top(18);
    content.set_margin_bottom(24);
    content.set_margin_start(12);
    content.set_margin_end(12);
    let clamp = adw::Clamp::builder().maximum_size(if tiles { 1400 } else { 720 }).child(&content).build();
    let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build();
    (scroll, content)
}

fn empty() -> adw::StatusPage {
    adw::StatusPage::builder().icon_name("folder-open-symbolic").title(words::list_empty()).vexpand(true).build()
}

fn filter_name(f: Option<ApplicationId>) -> String {
    match f {
        Some(app) => serde_json::to_value(app).ok().and_then(|v| v.as_str().map(str::to_owned)).unwrap_or_default(),
        None => "all".to_string(),
    }
}

/// Home: the filters (where the core offers more than "all"), a folder's head ([hero], its cover: the folder's header's,
/// as the Pot's and the Memory's pages place it), then the level's groups (`Home.groups`): a folder enters, an object
/// opens in its application. With [drops], files and folders dropped onto the level go to the core's one drop where it
/// offers the level, and onto a folder's row where it offers that row (ADR 0154 decision 1).
pub fn home(state: &ShellModelState, shown: &ShellShown, drops: Option<&Drops>, hero: Option<gtk::Widget>, toasts: &adw::ToastOverlay) -> gtk::Widget {
    let as_tiles = shown.view == ViewModeShown::Tiles;
    let (scroll, content) = column(as_tiles);
    if let Some(t) = drops.and_then(Drops::onto_level) {
        scroll.add_controller(t);
    }
    if state.lists.filters.len() > 1 {
        content.append(&filter_group(state, shown));
    }
    if let Some(h) = hero {
        // the head is the header's one widget: taken from the level drawn before; under the filters, as the phones place it
        if let Some(parent) = h.parent().and_downcast::<gtk::Box>() {
            parent.remove(&h);
        }
        content.append(&h);
    }
    if shown.groups.is_empty() && shown.queued_here.is_empty() {
        content.append(&empty());
    }
    for g in &shown.groups {
        let list = adw::PreferencesGroup::new();
        if let Some(heading) = &g.heading {
            list.set_title(&said::key(heading));
        }
        if as_tiles {
            list.add(&level_tiles(state, &g.nodes, g.folders, drops, toasts));
            content.append(&list);
            continue;
        }
        for n in &g.nodes {
            if g.folders {
                let row = folder_row(n);
                if let Some(t) = drops.and_then(|d| d.onto_row(n)) {
                    row.add_controller(t);
                }
                row_menu(&row, &node_at(n), toasts);
                list.add(&row);
            } else if let Some(o) = state.lists.objects.iter().find(|o| o.scope_id == n.scope_id && o.node_id == n.node_id) {
                let row = object_row(o);
                row_menu(&row, &object_at(o), toasts);
                list.add(&row);
            } else if n.kind == crate::core_ui::bookmark_page::KIND {
                let row = bookmark_row(n);
                row_menu(&row, &node_at(n), toasts);
                list.add(&row);
            }
        }
        content.append(&list);
    }
    // what was made here offline and is not sent yet, in place, marked as the core says it (ADR 0153 decision 5)
    if !shown.queued_here.is_empty() {
        let list = adw::PreferencesGroup::new();
        for q in &shown.queued_here {
            list.add(&queued_row(q));
        }
        content.append(&list);
    }
    scroll.upcast()
}

/// A write made offline as its level lists it: what it is, and *Waiting to send* (or why it is held) under it.
fn queued_row(q: &QueuedItem) -> adw::ActionRow {
    let row = adw::ActionRow::builder().title(glib_escape(&said::notice(&q.title))).subtitle(glib_escape(&said::notice(&q.said))).build();
    row.add_prefix(&gtk::Image::from_icon_name("document-send-symbolic"));
    row.add_css_class("dim-label");
    row
}

/// A group of Home's level as tiles: a folder's enters it (and takes a drop as its row does), an object's opens it.
fn level_tiles(state: &ShellModelState, nodes: &[NodeRef], folders: bool, drops: Option<&Drops>, toasts: &adw::ToastOverlay) -> gtk::FlowBox {
    let mut tiles = Vec::new();
    let mut dropping = Vec::new();
    let mut menus = Vec::new();
    for n in nodes {
        let before = tiles.len();
        if folders {
            let folder = FolderRef { node_id: n.node_id.clone(), name: n.name.clone() };
            tiles.push(Tile { name: n.name.clone(), subtitle: None, dated: false, icon: kind_icon(&n.kind), node_id: n.node_id.clone(),
                open: Box::new(move || bridge::send(Call::ShellEnter { folder: folder.clone() })) });
            dropping.push(drops.and_then(|d| d.onto_row(n)));
        } else if let Some(o) = state.lists.objects.iter().find(|o| o.scope_id == n.scope_id && o.node_id == n.node_id) {
            tiles.push(object_tile(o));
            dropping.push(None);
        } else if n.kind == crate::core_ui::bookmark_page::KIND {
            let node = node_at(n);
            tiles.push(Tile { name: n.name.clone(), subtitle: None, dated: false, icon: kind_icon(&n.kind), node_id: n.node_id.clone(),
                open: Box::new(move || crate::core_ui::bookmark_page::open_kept(&node)) });
            dropping.push(None);
        }
        if tiles.len() > before {
            menus.push(node_at(n));
        }
    }
    let grid = tiles::grid(tiles);
    for (i, target) in dropping.into_iter().enumerate() {
        if let (Some(t), Some(child)) = (target, grid.child_at_index(i as i32)) {
            child.add_controller(t);
        }
    }
    menus_on(&grid, &menus, toasts);
    grid
}

/// Each tile of [grid] opens the menu of its node in [nodes] (the same order).
fn menus_on(grid: &gtk::FlowBox, nodes: &[crate::bridge::wire::NodeAt], toasts: &adw::ToastOverlay) {
    for (i, node) in nodes.iter().enumerate() {
        if let Some(child) = grid.child_at_index(i as i32) {
            row_menu(&child, node, toasts);
        }
    }
}

/// An object's node, for its menu.
fn object_at(r: &ObjectRef) -> crate::bridge::wire::NodeAt {
    crate::bridge::wire::NodeAt { scope_id: r.scope_id.clone(), node_id: r.node_id.clone(), kind: r.kind.clone(), name: r.name.clone() }
}

/// An object as a tile: opens in its application; a Memory's second line its date, as its row's.
fn object_tile(r: &ObjectRef) -> Tile {
    let open = r.clone();
    Tile { name: r.name.clone(), subtitle: None, dated: r.kind == "MEMORY", icon: kind_icon(&r.kind), node_id: r.node_id.clone(),
        open: Box::new(move || bridge::send(Call::ShellOpen { r#ref: open.clone() })) }
}

/// A kept link's row (ADR 0150 decision 6): no application's object — the core's shell opens its page
/// ([crate::core_ui::bookmark_page::open_kept], `Shell.OpenBookmark`, #1032).
fn bookmark_row(n: &NodeRef) -> adw::ActionRow {
    let row = adw::ActionRow::builder().title(glib_escape(&n.name)).activatable(true).build();
    row.add_prefix(&gtk::Image::from_icon_name(kind_icon(&n.kind)));
    let node = node_at(n);
    row.connect_activated(move |_| crate::core_ui::bookmark_page::open_kept(&node));
    row
}

fn node_at(n: &NodeRef) -> crate::bridge::wire::NodeAt {
    crate::bridge::wire::NodeAt { scope_id: n.scope_id.clone(), node_id: n.node_id.clone(), kind: n.kind.clone(), name: n.name.clone() }
}

fn folder_row(n: &NodeRef) -> adw::ActionRow {
    let row = adw::ActionRow::builder().title(glib_escape(&n.name)).activatable(true).build();
    row.add_prefix(&cover_prefix(gtk::Image::from_icon_name(kind_icon(&n.kind)), &n.node_id));
    row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
    let folder = FolderRef { node_id: n.node_id.clone(), name: n.name.clone() };
    row.connect_activated(move |_| bridge::send(Call::ShellEnter { folder: folder.clone() }));
    row
}

fn filter_group(state: &ShellModelState, shown: &ShellShown) -> adw::ToggleGroup {
    let filters = &state.lists.filters;
    let group = adw::ToggleGroup::builder().halign(gtk::Align::Center).build();
    for f in filters {
        let label = match f {
            Some(app) => app_name(shown, *app),
            None => words::filter_all(),
        };
        group.add(adw::Toggle::builder().label(label).name(filter_name(*f)).build());
    }
    group.set_active_name(Some(&filter_name(state.shell.filter)));
    let offered = filters.clone();
    group.connect_active_name_notify(move |g| {
        let name = g.active_name().map(String::from).unwrap_or_default();
        if let Some(f) = offered.iter().find(|f| filter_name(**f) == name) {
            bridge::send(Call::ShellFilter { application: *f });
        }
    });
    group
}

/// An application's own list: Favorites, Recent, then the rest, each where it holds something.
pub fn list(list: Option<&ArrangedList>, view: ViewModeShown, toasts: &adw::ToastOverlay) -> gtk::Widget {
    let as_tiles = view == ViewModeShown::Tiles;
    let (scroll, content) = column(as_tiles);
    let Some(l) = list.filter(|l| !(l.favorites.is_empty() && l.recent.is_empty() && l.all.is_empty())) else {
        content.append(&empty());
        return scroll.upcast();
    };
    for (title, refs) in [(Some(words::drawer_favorites()), &l.favorites), (Some(words::drawer_recent()), &l.recent), (None, &l.all)] {
        if refs.is_empty() {
            continue;
        }
        let group = adw::PreferencesGroup::new();
        if let Some(t) = title {
            group.set_title(&t);
        }
        if as_tiles {
            let grid = tiles::grid(refs.iter().map(object_tile).collect());
            menus_on(&grid, &refs.iter().map(object_at).collect::<Vec<_>>(), toasts);
            group.add(&grid);
            content.append(&group);
            continue;
        }
        for r in refs {
            let row = object_row(r);
            row_menu(&row, &object_at(r), toasts);
            group.add(&row);
        }
        content.append(&group);
    }
    scroll.upcast()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A row's cover is the part of the stored picture the core's cut names, nothing outside it.
    #[test]
    fn a_rows_cover_is_the_part_the_cut_names() {
        // 3 × 2, each pixel's blue byte its index (B, G, R, A premultiplied, opaque)
        let pixels: Vec<u8> = (0..6u8).flat_map(|i| [i, 0, 0, 255]).collect();
        let texture: gdk::Texture = gdk::MemoryTexture::new(3, 2, gdk::MemoryFormat::B8g8r8a8Premultiplied, &glib::Bytes::from_owned(pixels), 12).upcast();
        let part = cut(&texture, 1, 0, 2, 2).expect("inside the picture");
        assert_eq!((part.width(), part.height()), (2, 2));
        let mut downloader = gdk::TextureDownloader::new(&part);
        downloader.set_format(gdk::MemoryFormat::B8g8r8a8Premultiplied);
        let (bytes, stride) = downloader.download_bytes();
        let blue: Vec<u8> = (0..2).flat_map(|r| (0..2).map(move |c| (r, c))).map(|(r, c)| bytes[r * stride + c * 4]).collect();
        assert_eq!(blue, vec![1, 2, 4, 5], "the columns 1 and 2 of both rows");
        assert!(cut(&texture, 2, 0, 2, 2).is_none(), "a cut past the picture's edge");
        assert!(cut(&texture, 0, 0, 0, 2).is_none(), "an empty cut");
    }
}
