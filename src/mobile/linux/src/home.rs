//! Home and an application's list (ADR 0122): Home's level as the core groups it — the folders, then the contents —
//! under the filters the core offers, and an application's own list in its Favorites, Recent and the rest. Each drawn
//! as GNOME draws a list of places: boxed lists in a clamped, scrolled column, a filter as a toggle group.
use std::cell::RefCell;
use std::collections::HashMap;

use adw::prelude::*;
use gtk::{gdk, glib};

use crate::bridge::{self, wire::Answer, wire::ApplicationId, wire::ArrangedList, wire::Call, wire::Crop, wire::MemoryDateShown, wire::NodeRef, wire::ObjectRef,
    wire::ShellModelState, wire::ShellShown, wire::FolderRef};
use crate::drop::Drops;
use crate::{said, words};

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

/// A row that opens [r] in its application when activated; a Memory's shows its cover where it has one ([cover_prefix]).
pub fn object_row(r: &ObjectRef) -> adw::ActionRow {
    let row = adw::ActionRow::builder().title(glib_escape(&r.name)).activatable(true).build();
    let icon = gtk::Image::from_icon_name(kind_icon(&r.kind));
    if r.kind == "MEMORY" {
        row.add_prefix(&cover_prefix(icon, &r.node_id));
        dated(&row, &r.node_id);
    } else {
        row.add_prefix(&icon);
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
    /// The rows drawn that show a date, each told it again when the dates arrive.
    static DATED_ROWS: RefCell<Vec<(String, glib::WeakRef<adw::ActionRow>)>> = const { RefCell::new(Vec::new()) };
}

/// A Memory's row's second line, its date (ADR 0143 decision 5; the facts line's way of saying it), set now where it is
/// held and again whenever the dates arrive.
fn dated(row: &adw::ActionRow, node_id: &str) {
    if let Some(text) = ROW_DATES.with(|d| d.borrow().get(node_id).and_then(crate::memory_facts::date_text)) {
        row.set_subtitle(&glib_escape(&text));
    }
    DATED_ROWS.with(|r| {
        let mut r = r.borrow_mut();
        r.retain(|(_, w)| w.upgrade().is_some());
        r.push((node_id.to_owned(), row.downgrade()));
    });
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
        eprintln!("veyq: memory dates: {} of {} dated", next.values().filter(|d| crate::memory_facts::date_text(d).is_some()).count(), next.len());
        ROW_DATES.with(|d| *d.borrow_mut() = next);
        DATED_ROWS.with(|r| {
            for (node, row) in r.borrow().iter() {
                if let Some(row) = row.upgrade() {
                    let text = ROW_DATES.with(|d| d.borrow().get(node).and_then(crate::memory_facts::date_text)).unwrap_or_default();
                    row.set_subtitle(&glib_escape(&text));
                }
            }
        });
    });
}

/// The longest side a list row's cover is held at (it is drawn 64 × 36).
const ROW_COVER_EDGE: i32 = 128;

/// How many rows' covers are held at once.
const ROW_COVERS_HELD: usize = 256;

thread_local! {
    /// The list rows' covers by node id: the frame each was cut for (the core's `RowCover.frame`) and the cut picture —
    /// a list is drawn anew on every state, and a held frame is answered without its picture.
    static ROW_COVERS: RefCell<HashMap<String, (String, gdk::Texture)>> = RefCell::new(HashMap::new());
}

/// [icon] in a row's prefix, replaced by node [node_id]'s cover once the core names one (`Shell.RowCover`): the
/// framed part of the stored picture, cut where the core says (`Cover.Cut`), as the chapters' rows and the phones'.
fn cover_prefix(icon: gtk::Image, node_id: &str) -> gtk::Stack {
    let cover = gtk::Picture::builder().content_fit(gtk::ContentFit::Cover).can_shrink(true).width_request(64).height_request(36).valign(gtk::Align::Center).build();
    cover.set_overflow(gtk::Overflow::Hidden);
    cover.add_css_class("card");
    let prefix = gtk::Stack::new();
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
            None => prefix.set_visible_child(&icon),
        }
    });
    prefix
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
    Some(crate::file_page::within_edge(&cut(&texture, x, y, cw, ch)?, ROW_COVER_EDGE))
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

fn column() -> (gtk::ScrolledWindow, gtk::Box) {
    let content = gtk::Box::new(gtk::Orientation::Vertical, 24);
    content.set_margin_top(18);
    content.set_margin_bottom(24);
    content.set_margin_start(12);
    content.set_margin_end(12);
    let clamp = adw::Clamp::builder().maximum_size(720).child(&content).build();
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

/// Home: the filters (where the core offers more than "all"), then the level's groups (`Home.groups`): a folder
/// enters, an object opens in its application. With [drops], files and folders dropped onto the level upload there, and
/// onto a folder's row into that folder (ADR 0123's Consequences, the Linux app's Depot whole).
pub fn home(state: &ShellModelState, shown: &ShellShown, drops: Option<&Drops>) -> gtk::Widget {
    let (scroll, content) = column();
    if let Some(d) = drops {
        scroll.add_controller(d.onto_level());
    }
    if state.lists.filters.len() > 1 {
        content.append(&filter_group(state, shown));
    }
    if shown.groups.is_empty() {
        content.append(&empty());
    }
    for g in &shown.groups {
        let list = adw::PreferencesGroup::new();
        if let Some(heading) = &g.heading {
            list.set_title(&said::key(heading));
        }
        for n in &g.nodes {
            if g.folders {
                let row = folder_row(n);
                if let Some(d) = drops.filter(|_| n.kind == "FOLDER") {
                    row.add_controller(d.onto_folder(&n.node_id, &n.name));
                }
                list.add(&row);
            } else if let Some(o) = state.lists.objects.iter().find(|o| o.scope_id == n.scope_id && o.node_id == n.node_id) {
                list.add(&object_row(o));
            }
        }
        content.append(&list);
    }
    scroll.upcast()
}

fn folder_row(n: &NodeRef) -> adw::ActionRow {
    let row = adw::ActionRow::builder().title(glib_escape(&n.name)).activatable(true).build();
    row.add_prefix(&gtk::Image::from_icon_name(kind_icon(&n.kind)));
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
pub fn list(list: Option<&ArrangedList>) -> gtk::Widget {
    let (scroll, content) = column();
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
        for r in refs {
            group.add(&object_row(r));
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
