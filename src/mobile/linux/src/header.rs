//! A node's header (ADR 0127 decision 2): core's `NodeHeaderModel`, opened per node on screen
//! (`Header.Open`) and watched while it shows, drawn GNOME's way — the plan's buttons in the content's header bar (the
//! heart as a toggle, a Pot's Download), the rest in its "More" menu with the menu's last group behind a separator —, its
//! rename and its delete asked in an `AdwAlertDialog`, the trash's Undo an `AdwToast` held for exactly as long as the core
//! offers it (no timer here: the toast goes when the state's `undo` does). What is offered, in which order and with which
//! words is the core's; this lays it out.
//!
//! The move and the copy open core's sheet ([crate::move_sheet]); a folder's download goes where GTK's save dialog says
//! (`Header.Download`, under the core's archive name), a Pot's through its page (`Pot.Download`), a file's — the file
//! itself, with its progress and Cancel — through its page (`File.Save`, [crate::file_page]); the share opens core's
//! share sheet ([crate::share_sheet]); the report opens core's report sheet ([crate::report]), the cover its dialog
//! ([crate::cover]), whose words follow the core's state (Set cover, or Change cover where the node has one).
//!
//! The header also draws the node's head for its page ([Header::hero]): the cover across the page's width, 16:9, cut as
//! stored (`Header.CoverPicture`, the part its crop shows by `Cover.Cut`: the core's math), as Android's and Apple's
//! pages place it first in their scroll; a click opens the cover dialog where the plan offers it. A node without a cover
//! shows none (its cover is set from the menu).
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::{cairo, gdk, gio, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::Crop, wire::Facts, wire::HeaderShown, wire::NodeAt, wire::Update, wire::Watched};
use crate::{said, words};

/// The most buttons the content's header bar holds beside its title (GNOME: a few, the rest in the menu).
pub const MAX_BUTTONS: i32 = 2;

/// The header's ids drawn so far; every other id of the plan is left out until its phase.
pub const DRAWN: &[&str] = &["favorite", "share", "rename", "delete", "download", "move", "copy", "report", "cover"];

/// Whether [id] is drawn for a node of [kind].
pub fn drawn(id: &str, _kind: &str) -> bool {
    DRAWN.contains(&id)
}

/// The window's action an id fires (`win.header-<id>`; a Pot's download is the Pot page's `win.pot-download`, a file's its
/// page's Save a copy, `win.file-save`).
pub fn action_of(id: &str, kind: &str) -> String {
    match (id, kind) {
        ("download", "EXPENSES_POT") => "win.pot-download".into(),
        ("download", "FILE") => "win.file-save".into(),
        _ => format!("win.header-{id}"),
    }
}

/// The header of one node, opened in the core when made and closed when dropped.
pub struct Header {
    pub node: NodeAt,
    inner: Rc<Inner>,
}

struct Inner {
    id: Cell<Option<i64>>,
    shown: RefCell<Option<HeaderShown>>,
    watch: RefCell<Option<Watch>>,
    /// Where it draws: the header bar's end, and what to redraw there when the state changes.
    bar: gtk::Box,
    toasts: adw::ToastOverlay,
    undo_toast: RefCell<Option<adw::Toast>>,
    /// Whether it still draws into the bar: a header kept for its Undo after its page left does not.
    on_bar: Cell<bool>,
    /// Facts the page learnt before the core answered the open: sent once it did.
    later: RefCell<Option<Option<Facts>>>,
    /// A state showed the trash's undo offered: its end is the next state without it.
    seen_undo: Cell<bool>,
    /// The node's head: its cover, cut as stored, drawn 16:9 across the width it is given; hidden while it has none.
    hero: gtk::DrawingArea,
    picture: Rc<RefCell<Option<cairo::ImageSurface>>>,
    /// The crop the head shows (`HeaderShown.cover`), and the number of the latest read of its picture.
    hero_crop: RefCell<Option<Crop>>,
    hero_asked: Cell<u64>,
}

impl Header {
    /// Opens the header of [node] (its page knowing [facts]), drawing into [bar] and saying what it did in [toasts].
    pub fn open(node: &NodeAt, facts: Option<Facts>, bar: &gtk::Box, toasts: &adw::ToastOverlay) -> Self {
        let inner = Rc::new(Inner {
            id: Cell::new(None),
            shown: RefCell::new(None),
            watch: RefCell::new(None),
            bar: bar.clone(),
            toasts: toasts.clone(),
            undo_toast: RefCell::new(None),
            on_bar: Cell::new(true),
            later: RefCell::new(None),
            seen_undo: Cell::new(false),
            hero: gtk::DrawingArea::builder().hexpand(true).content_height(180).visible(false).build(),
            picture: Rc::new(RefCell::new(None)),
            hero_crop: RefCell::new(None),
            hero_asked: Cell::new(0),
        });
        inner.hero.set_overflow(gtk::Overflow::Hidden);
        inner.hero.add_css_class("card");
        inner.hero.set_margin_bottom(6);
        // 16:9 at whatever width the page gives it (GTK has no height-for-width for a picture that may grow)
        inner.hero.connect_resize(|area, width, _| {
            let height = width * 9 / 16;
            if area.content_height() != height {
                // a size asked for during an allocation is not taken: the next turn of the loop asks for it
                let area = area.clone();
                glib::idle_add_local_once(move || area.set_content_height(height));
            }
        });
        let held = inner.picture.clone();
        inner.hero.set_draw_func(move |_, cr, width, height| {
            let Some(surface) = held.borrow().clone() else { return };
            // the cut fills the frame, as the web's cover box does (its aspect is the frame's)
            let (sw, sh) = (f64::from(surface.width()), f64::from(surface.height()));
            let scale = (f64::from(width) / sw).max(f64::from(height) / sh);
            cr.translate((f64::from(width) - sw * scale) / 2.0, (f64::from(height) - sh * scale) / 2.0);
            cr.scale(scale, scale);
            if cr.set_source_surface(&surface, 0.0, 0.0).is_ok() {
                let _ = cr.paint();
            }
        });
        let w = Rc::downgrade(&inner);
        let click = gtk::GestureClick::new();
        click.connect_released(move |gesture, _, _, _| {
            let Some(i) = w.upgrade() else { return };
            let offered = i.shown.borrow().as_ref().and_then(|s| s.plan.as_ref()).is_some_and(|p| {
                [&p.buttons, &p.menu, &p.last].iter().any(|g| g.iter().any(|a| a.id == "cover"))
            });
            if offered {
                let _ = gesture.widget().and_then(|w| w.activate_action("win.header-cover", None).ok());
            }
        });
        inner.hero.add_controller(click);
        inner.hero.set_tooltip_text(Some(&words::cover_change()));
        inner.hero.update_property(&[gtk::accessible::Property::Label(&words::cover_change())]);
        clear(bar);
        let weak = Rc::downgrade(&inner);
        let call = Call::HeaderOpen { node: node.clone(), facts, max_buttons: MAX_BUTTONS };
        glib::spawn_future_local(async move {
            let Ok((Answer::HeaderOpened { header }, _)) = bridge::call(&call).await else {
                eprintln!("veyq: the header did not open in the core");
                return;
            };
            let Some(inner) = weak.upgrade() else {
                bridge::send(Call::HeaderClose { header });
                return;
            };
            inner.id.set(Some(header));
            if let Some(facts) = inner.later.borrow_mut().take() {
                bridge::send(Call::HeaderFacts { header, facts });
            }
            let Some((watch, updates)) = bridge::watch(&Watched::Header { header }) else { return };
            *inner.watch.borrow_mut() = Some(watch);
            drop(inner);
            follow(weak, updates).await;
        });
        Self { node: node.clone(), inner }
    }

    /// What the kind's page knows now (a Pot read: whether it takes writes).
    pub fn facts(&self, facts: Option<Facts>) {
        match self.inner.id.get() {
            Some(header) => bridge::send(Call::HeaderFacts { header, facts }),
            None => *self.inner.later.borrow_mut() = Some(facts),
        }
    }

    /// Whether the trash's Undo still stands: the header is kept until it is over.
    pub fn undoing(&self) -> bool {
        self.inner.undo_toast.borrow().is_some()
    }

    /// Stops drawing into the bar (another node's header took it); a standing Undo stays.
    pub fn leave_bar(&self) {
        self.inner.on_bar.set(false);
    }

    /// Pins or unpins: the heart turns at once (the core's state), a failure is said.
    pub fn pin(&self) {
        let Some(header) = self.inner.id.get() else { return };
        let toasts = self.inner.toasts.clone();
        glib::spawn_future_local(async move {
            say(&toasts, bridge::call(&Call::HeaderPin { header }).await.map(|(a, _)| a), "Header.Pin");
        });
    }

    /// Rename: the name asked for in an alert dialog over [parent], asked again with the core's problem.
    pub fn rename(&self, parent: &impl IsA<gtk::Widget>) {
        let Some(header) = self.inner.id.get() else { return };
        let toasts = self.inner.toasts.clone();
        let parent = parent.clone().upcast::<gtk::Widget>();
        ask_name(&parent, &words::node_rename(), &words::node_name(), &self.node.name, None, &words::node_rename(), move |name, again| {
            let toasts = toasts.clone();
            glib::spawn_future_local(async move {
                match bridge::call(&Call::HeaderRename { header, name: name.clone() }).await.map(|(a, _)| a) {
                    Ok(Answer::Renamed { problem: Some(p), .. }) => again(name, said::notice(&p)),
                    Ok(Answer::Renamed { problem: None, notice }) => {
                        eprintln!("veyq: header rename: Renamed");
                        if let Some(n) = notice {
                            toasts.add_toast(adw::Toast::new(&said::notice(&n)));
                        }
                    }
                    other => say(&toasts, other, "Header.Rename"),
                }
            });
        });
    }

    /// The move ([copy] false) or copy sheet over [parent] (core's `MoveSheet`, [crate::move_sheet]).
    pub fn move_sheet(&self, parent: &impl IsA<gtk::Widget>, copy: bool) {
        crate::move_sheet::open(parent, &self.node, copy, &self.inner.toasts);
    }

    /// The share sheet over [parent] (core's `ShareSheet`, [crate::share_sheet]).
    pub fn share(&self, parent: &impl IsA<gtk::Widget>) {
        crate::share_sheet::open(parent, &self.node);
    }

    /// The report sheet over [parent] (core's `ReportSheet`, [crate::report]).
    pub fn report(&self, parent: &impl IsA<gtk::Widget>) {
        crate::report::open(parent, &self.node);
    }

    /// The node's head for its page: the cover, cut as stored; hidden while there is none.
    pub fn hero(&self) -> gtk::Widget {
        self.inner.hero.clone().upcast()
    }

    /// The cover dialog over [parent] ([crate::cover]): the stored cover framed as the core holds it, or a picture to pick.
    pub fn cover(&self, parent: &impl IsA<gtk::Widget>) {
        let Some(header) = self.inner.id.get() else { return };
        let stored = self.inner.shown.borrow().as_ref().and_then(|s| s.cover.clone());
        // a new picture may come under the crop it had: the head reads its picture again at the next state
        *self.inner.hero_crop.borrow_mut() = Some(Crop { x: -1.0, y: -1.0, width: 0.0, height: 0.0 });
        crate::cover::open(parent, header, &self.node, stored, &self.inner.toasts);
    }

    /// Downloads the node as the web's ZIP: GTK's save dialog (the file chooser portal where there is one) suggests the
    /// core's name, the core writes the file the dialog chose and removes it when it did not finish, and a toast says what
    /// the core said (with what it left out).
    pub fn download(&self, parent: &gtk::Window) {
        let Some(header) = self.inner.id.get() else { return };
        let Some(suggested) = self.inner.shown.borrow().as_ref().map(|s| s.archive_name.clone()) else { return };
        let (name, parent, toasts) = (self.node.name.clone(), parent.clone(), self.inner.toasts.clone());
        glib::spawn_future_local(async move {
            let failed = || adw::Toast::new(&words::download_failed(&name));
            let dialog = gtk::FileDialog::builder().title(words::node_download()).initial_name(suggested).modal(true).build();
            if let Some(downloads) = glib::user_special_dir(glib::UserDirectory::Downloads).filter(|d| d.is_dir()) {
                dialog.set_initial_folder(Some(&gio::File::for_path(downloads)));
            }
            let file = match dialog.save_future(Some(&parent)).await {
                Ok(file) => file,
                Err(e) if e.matches(gtk::DialogError::Dismissed) || e.matches(gtk::DialogError::Cancelled) => return,
                Err(e) => {
                    eprintln!("veyq: the save dialog failed: {e}");
                    toasts.add_toast(failed());
                    return;
                }
            };
            // the core opens the path itself; one that is no UTF-8 does not cross the wire
            let Some(path) = file.path().and_then(|p| p.to_str().map(str::to_owned)) else {
                eprintln!("veyq: the save dialog chose no local UTF-8 path");
                toasts.add_toast(failed());
                return;
            };
            let call = Call::HeaderDownload { header, path, deleted_name: words::pot_deleted_person() };
            match bridge::call(&call).await.map(|(answer, _)| answer) {
                Ok(Answer::Downloaded { notice, left_out, .. }) => {
                    eprintln!("veyq: header download: {}, left out: {}", notice.msg, left_out.is_some());
                    let said = [Some(&notice), left_out.as_ref()].into_iter().flatten().map(said::notice).collect::<Vec<_>>().join(" ");
                    toasts.add_toast(adw::Toast::new(&said));
                }
                other => {
                    eprintln!("veyq: Header.Download answered {other:?}");
                    toasts.add_toast(failed());
                }
            }
        });
    }

    /// The delete: asked first, with the kind's title and the core's question; then to the trash, its Undo offered.
    pub fn delete(&self, parent: &impl IsA<gtk::Widget>) {
        let Some(header) = self.inner.id.get() else { return };
        let shown = self.inner.shown.borrow();
        let Some(shown) = shown.as_ref() else { return };
        let title = shown.plan.as_ref().and_then(|p| p.last.iter().find(|a| a.id == "delete")).and_then(|a| a.word.as_deref()).map(said::key).unwrap_or_default();
        let ask = adw::AlertDialog::builder().heading(&title).body(said::notice(&shown.delete_question)).build();
        ask.add_responses(&[("cancel", &words::entry_cancel()), ("delete", &words::entry_delete())]);
        ask.set_response_appearance("delete", adw::ResponseAppearance::Destructive);
        ask.set_default_response(Some("cancel"));
        ask.set_close_response("cancel");
        let weak = Rc::downgrade(&self.inner);
        ask.connect_response(None, move |_, response| {
            if response != "delete" {
                return;
            }
            let weak = weak.clone();
            glib::spawn_future_local(async move {
                let answered = bridge::call(&Call::HeaderTrash { header }).await.map(|(a, _)| a);
                let Some(inner) = weak.upgrade() else { return };
                match answered {
                    Ok(Answer::Trashed { notice, undoable }) => {
                        eprintln!("veyq: header trash: {}, undo offered: {undoable}", notice.msg);
                        inner.offer(&said::notice(&notice), undoable, header);
                    }
                    other => say(&inner.toasts, other, "Header.Trash"),
                }
            });
        });
        ask.present(Some(parent));
    }
}

impl Inner {
    /// What the trash said, with Undo where the core offers it now: a toast that stays until the offer ends.
    fn offer(self: &Rc<Self>, text: &str, undo: bool, header: i64) {
        let toast = adw::Toast::new(text);
        if undo {
            toast.set_button_label(Some(&words::trash_undo()));
            toast.set_timeout(0);
            let toasts = self.toasts.clone();
            toast.connect_button_clicked(move |_| {
                let toasts = toasts.clone();
                glib::spawn_future_local(async move {
                    let answered = bridge::call(&Call::HeaderUndo { header }).await.map(|(a, _)| a);
                    eprintln!("veyq: header undo: {answered:?}");
                    say(&toasts, answered, "Header.Undo");
                });
            });
            *self.undo_toast.borrow_mut() = Some(toast.clone());
        }
        self.toasts.add_toast(toast);
    }

    /// The head follows the state's cover: read again (its picture, then the part its crop shows) when the crop changed.
    fn draw_hero(self: &Rc<Self>, cover: Option<Crop>) {
        if *self.hero_crop.borrow() == cover {
            return;
        }
        *self.hero_crop.borrow_mut() = cover.clone();
        let n = self.hero_asked.get() + 1;
        self.hero_asked.set(n);
        let (Some(crop), Some(header)) = (cover, self.id.get()) else {
            self.hero.set_visible(false);
            self.picture.borrow_mut().take();
            return;
        };
        let w = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let answered = bridge::call(&Call::HeaderCoverPicture { header }).await;
            let texture = match answered {
                Ok((Answer::Bytes { .. }, Some(bytes))) => match gdk::Texture::from_bytes(&glib::Bytes::from_owned(bytes)) {
                    Ok(t) => t,
                    Err(e) => return eprintln!("veyq: cover hero: the picture did not decode: {e}"),
                },
                other => return eprintln!("veyq: cover hero: Header.CoverPicture answered {:?}", other.map(|(a, _)| a)),
            };
            let (width, height) = (texture.width(), texture.height());
            let cut = bridge::call(&Call::CoverCut { crop, width, height }).await.map(|(a, _)| a);
            let Some(i) = w.upgrade() else { return };
            if i.hero_asked.get() != n {
                return;
            }
            let Ok(Answer::CoverCut { x, y, width: cw, height: ch }) = cut else {
                return eprintln!("veyq: cover hero: Cover.Cut answered {cut:?}");
            };
            eprintln!("veyq: cover hero: {width}×{height}, the crop's {cw}×{ch} at {x},{y}");
            let Some(surface) = cut_surface(&texture, x, y, cw, ch) else {
                return eprintln!("veyq: cover hero: the cut was not drawn");
            };
            *i.picture.borrow_mut() = Some(surface);
            i.hero.set_visible(true);
            i.hero.queue_draw();
        });
    }

    fn draw(self: &Rc<Self>) {
        let cover = self.shown.borrow().as_ref().map(|s| s.cover.clone());
        if let Some(cover) = cover {
            self.draw_hero(cover);
        }
        let shown = self.shown.borrow();
        // the offer ended (its window, or the undo): the toast goes with it — once a state showed the offer, so an
        // update from before the trash that arrives after its answer ends nothing
        let offered = shown.as_ref().is_some_and(|s| s.undo.is_some());
        if offered {
            self.seen_undo.set(true);
        } else if self.seen_undo.replace(false)
            && let Some(t) = self.undo_toast.borrow_mut().take()
        {
            eprintln!("veyq: header undo offer ended");
            t.dismiss();
        }
        let Some(s) = shown.as_ref() else { return };
        if s.gone || !self.on_bar.get() {
            return;
        }
        clear(&self.bar);
        let Some(plan) = s.plan.as_ref() else { return };
        let kind = s.node.kind.as_str();
        let menu = gio::Menu::new();
        let first = gio::Menu::new();
        let last = gio::Menu::new();
        // GNOME's header bar packs from the end: the menu outermost, the buttons before it
        for a in plan.buttons.iter().filter(|a| drawn(&a.id, kind)) {
            let label = a.word.as_deref().map(said::key).unwrap_or_default();
            let button = if a.id == "favorite" {
                let icon = if s.pinned == Some(true) { "starred-symbolic" } else { "non-starred-symbolic" };
                gtk::Button::builder().icon_name(icon).tooltip_text(&label).action_name(action_of(&a.id, kind)).build()
            } else {
                gtk::Button::builder().icon_name(icon_of(&a.id)).tooltip_text(&label).action_name(action_of(&a.id, kind)).build()
            };
            button.update_property(&[gtk::accessible::Property::Label(&label)]);
            self.bar.append(&button);
        }
        for (group, ids) in [(&first, &plan.menu), (&last, &plan.last)] {
            for a in ids.iter().filter(|a| drawn(&a.id, kind)) {
                group.append(a.word.as_deref().map(said::key).as_deref(), Some(&action_of(&a.id, kind)));
            }
        }
        menu.append_section(None, &first);
        menu.append_section(None, &last);
        if first.n_items() + last.n_items() > 0 {
            let more = gtk::MenuButton::builder().icon_name("view-more-symbolic").tooltip_text(words::node_more()).menu_model(&menu).build();
            self.bar.append(&more);
        }
    }
}

impl Drop for Header {
    fn drop(&mut self) {
        self.inner.watch.borrow_mut().take();
        if let Some(t) = self.inner.undo_toast.borrow_mut().take() {
            t.dismiss();
        }
        if let Some(header) = self.inner.id.get() {
            bridge::send(Call::HeaderClose { header });
        }
    }
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Header { shown, .. }) => {
                if let Some(s) = &shown {
                    eprintln!("veyq: header {}: pinned {:?}, undo {}, gone {}, plan {}", s.node.kind, s.pinned, s.undo.is_some(), s.gone,
                        s.plan.as_ref().map(|p| [&p.buttons, &p.menu, &p.last].iter().map(|g| g.iter().map(|a| a.id.as_str()).collect::<Vec<_>>().join(",")).collect::<Vec<_>>().join(" | ")).unwrap_or_default());
                }
                *inner.shown.borrow_mut() = shown;
                inner.draw();
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a header update did not read: {:?}", e.0),
        }
    }
}

/// The part ([x], [y], [width] × [height] pixels) of [texture] a cover's crop shows, as cairo's surface to draw.
fn cut_surface(texture: &gdk::Texture, x: i32, y: i32, width: i32, height: i32) -> Option<cairo::ImageSurface> {
    let (tw, th) = (texture.width(), texture.height());
    if width <= 0 || height <= 0 || x < 0 || y < 0 || x + width > tw || y + height > th {
        return None;
    }
    // cairo's ARGB32 is B, G, R, A premultiplied on the little-endian machines the app runs on (as the cover dialog reads it)
    let mut downloader = gdk::TextureDownloader::new(texture);
    downloader.set_format(gdk::MemoryFormat::B8g8r8a8Premultiplied);
    let (whole, stride) = downloader.download_bytes();
    let row = width as usize * 4;
    let mut part = Vec::with_capacity(row * height as usize);
    for r in y..y + height {
        let at = r as usize * stride + x as usize * 4;
        part.extend_from_slice(&whole[at..at + row]);
    }
    cairo::ImageSurface::create_for_data(part, cairo::Format::ARgb32, width, height, row as i32).ok()
}

fn clear(bar: &gtk::Box) {
    while let Some(child) = bar.first_child() {
        bar.remove(&child);
    }
}

/// A plan's button's icon, in GNOME's icon set.
pub fn icon_of(id: &str) -> &'static str {
    match id {
        "download" => "folder-download-symbolic",
        "share" => "send-to-symbolic",
        "move" => "folder-symbolic",
        "copy" => "edit-copy-symbolic",
        "rename" => "document-edit-symbolic",
        "delete" => "user-trash-symbolic",
        "report" => "dialog-warning-symbolic",
        "cover" => "image-x-generic-symbolic",
        _ => "view-more-symbolic",
    }
}

/// Says what an action answered, where it says something: its notice as a toast, an unexpected answer on stderr.
pub fn say(toasts: &adw::ToastOverlay, answered: Result<Answer, bridge::Unreadable>, what: &str) {
    match answered {
        Ok(Answer::Said { notice: None }) | Ok(Answer::Done) => eprintln!("veyq: {what}: Done"),
        Ok(Answer::Said { notice: Some(n) }) => {
            eprintln!("veyq: {what}: {}", n.msg);
            toasts.add_toast(adw::Toast::new(&said::notice(&n)));
        }
        other => eprintln!("veyq: {what} answered {other:?}"),
    }
}

/// A name asked for in an `AdwAlertDialog` over [parent] (a rename, a new folder): [heading], an entry titled [field]
/// holding [name], [problem] under it where the core refused the last one, and [confirm]. [send] gets the name typed and
/// a way to ask again with the core's problem.
pub fn ask_name(
    parent: &gtk::Widget,
    heading: &str,
    field: &str,
    name: &str,
    problem: Option<&str>,
    confirm: &str,
    send: impl Fn(String, Box<dyn Fn(String, String)>) + Clone + 'static,
) {
    let entry = adw::EntryRow::builder().title(field).text(name).build();
    let rows = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    rows.add_css_class("boxed-list");
    rows.append(&entry);
    let ask = adw::AlertDialog::builder().heading(heading).extra_child(&rows).build();
    if let Some(p) = problem {
        ask.set_body(p);
    }
    ask.add_responses(&[("cancel", &words::entry_cancel()), ("confirm", confirm)]);
    ask.set_response_appearance("confirm", adw::ResponseAppearance::Suggested);
    ask.set_default_response(Some("confirm"));
    ask.set_close_response("cancel");
    ask.set_response_enabled("confirm", !name.trim().is_empty());
    let on_text = ask.clone();
    entry.connect_changed(move |e| on_text.set_response_enabled("confirm", !e.text().trim().is_empty()));
    entry.set_activates_default(true);
    let focus = entry.clone();
    let (heading, field, confirm) = (heading.to_owned(), field.to_owned(), confirm.to_owned());
    let at = parent.clone();
    ask.connect_response(None, move |_, response| {
        if response != "confirm" {
            return;
        }
        let (at, heading, field, confirm, again_send) = (at.clone(), heading.clone(), field.clone(), confirm.clone(), send.clone());
        let again: Box<dyn Fn(String, String)> = Box::new(move |name, problem| {
            ask_name(&at, &heading, &field, &name, Some(&problem), &confirm, again_send.clone());
        });
        send(entry.text().to_string(), again);
    });
    ask.present(Some(parent));
    focus.grab_focus();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_cores_actions_are_drawn_and_a_download_where_it_is_the_nodes_archive() {
        for id in ["favorite", "share", "rename", "delete", "move", "copy", "download", "report", "cover"] {
            assert!(drawn(id, "FOLDER") && drawn(id, "EXPENSES_POT") && drawn(id, "FILE"), "{id}");
        }
        assert_eq!(action_of("download", "FILE"), "win.file-save", "a file's download is its page's Save a copy");
        assert!(!drawn("an-applications-own", "FOLDER"), "an application's own action is its page's");
        assert_eq!(action_of("report", "FOLDER"), "win.header-report");
        assert_eq!(action_of("cover", "EXPENSES_POT"), "win.header-cover");
        assert_eq!(action_of("download", "EXPENSES_POT"), "win.pot-download");
        assert_eq!(action_of("download", "FOLDER"), "win.header-download");
        assert_eq!(action_of("move", "EXPENSES_POT"), "win.header-move");
        assert_eq!(action_of("rename", "FOLDER"), "win.header-rename");
    }
}
