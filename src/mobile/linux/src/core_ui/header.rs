//! A node's header (ADR 0136 decision 2): core's `NodeHeaderModel`, opened per node on screen
//! (`Header.Open`) and watched while it shows, drawn GNOME's way — the plan's buttons in the content's header bar (the
//! heart as a toggle, a Pot's Download), the rest in its "More" menu with the menu's last group behind a separator —, its
//! rename and its delete asked in an `AdwAlertDialog`, the trash's Undo an `AdwToast` held for exactly as long as the core
//! offers it (no timer here: the toast goes when the state's `undo` does). What is offered, in which order and with which
//! words is the core's; this lays it out.
//!
//! The move and the copy open core's sheet ([crate::core_ui::move_sheet]); a folder's download goes where GTK's save dialog says
//! (`Header.Download`, under the core's archive name), a Pot's through its page (`Pot.Download`), a file's — the file
//! itself, with its progress and Cancel — through its page (`File.Save`, `apps::depot::file_page`); the share opens core's
//! share sheet ([crate::core_ui::share_sheet]); the report opens core's report sheet ([crate::core_ui::report]), the cover its dialog
//! ([crate::core_ui::cover]), whose words follow the core's state (Set cover, or Change cover where the node has one).
//!
//! Every entry of the plan is drawn, in its order (ADR 0154 decision 2): core's own and the page's — a gallery's *Add
//! photos*, its music and its banner, a file's Replace, a kept link's *Open on its own page* and *Report shared content*
//! (the page tells its id, [Header::facts]) — each one [HeaderAction] whose doing is one exhaustive match ([Inner::act]);
//! how many stand as buttons is this platform's room ([MAX_BUTTONS], ADR 0110). Each drawn entry is an action of the bar's
//! own group (`header.<n>`, the entry's place in the plan), so no list of ids stands here. A level row's menu
//! ([row_menu]) draws its plan (`Row.Plan`) the same way, its entries done through the row's node's own header model.
//!
//! "Available offline" (ADR 0140 decision 10) is a check item of the "More" menu (a stateful action of the group
//! whose state is the core's `offline.on`), the pin's status line under it as an insensitive item ("Downloading 3 MB of
//! 12 MB", "On this device"); turning it on asks first, the size it keeps in an `AdwAlertDialog` (the core's `asking` and
//! `question`), as GNOME asks before a step that takes space; turning it off does not.
//!
//! The header also draws the node's head for its page ([Header::hero]): the cover across the page's width, 16:9, cut as
//! stored (`Header.CoverPicture`, the part its crop shows by `Cover.Cut`: the core's math), as Android's and Apple's
//! pages place it first in their scroll; a click opens the cover dialog where the plan offers it. A node without a cover
//! shows none (its cover is set from the menu).
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::{cairo, gdk, gio, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::Crop, wire::Facts, wire::HeaderAction, wire::HeaderShown, wire::NodeAt, wire::OfflineToggle, wire::Update, wire::Watched};
use crate::core_ui::{said, words};

/// The most buttons the content's header bar holds beside its title (GNOME: a few, the rest in the menu).
pub const MAX_BUTTONS: i32 = 2;

/// Who does an entry: the header itself (its model's call or sheet), or the open page's own window action
/// (`win.<name>`, registered by the kind's application in `KindPage.actions`).
#[derive(Debug, PartialEq, Eq)]
pub enum Doer {
    Header,
    Page(&'static str),
    /// Never offered on this platform (a phone's share sheet: the bridge builds a file's page with `shares = false`).
    Nobody,
}

/// Who does [a] on a node of [kind]; [row]: from a level row's menu, where no page is open — its download is the node's
/// archive, as a folder's.
pub fn doer(a: HeaderAction, kind: &str, row: bool) -> Doer {
    match a {
        HeaderAction::Favorite | HeaderAction::Share | HeaderAction::Rename | HeaderAction::Move | HeaderAction::Copy | HeaderAction::Cover
        | HeaderAction::Offline | HeaderAction::Report | HeaderAction::Delete | HeaderAction::OpenOwnPage | HeaderAction::ReportShared => Doer::Header,
        // a Pot's download is its page's (`Pot.Download`), a file's its page's Save a copy (the file itself); else the
        // node's archive (`Header.Download`)
        HeaderAction::Download => match (kind, row) {
            ("EXPENSES_POT", false) => Doer::Page("pot-download"),
            ("FILE", false) => Doer::Page("file-save"),
            (_, _) => Doer::Header,
        },
        HeaderAction::Upload => Doer::Page("memory-upload"),
        HeaderAction::Music => Doer::Page("memory-music"),
        HeaderAction::Banner => Doer::Page("memory-banner"),
        HeaderAction::Replace => Doer::Page("file-replace"),
        HeaderAction::Send => Doer::Nobody,
    }
}

/// What a page told before the core answered the open: its facts, a file page's id, a gallery's id, a kept link's page's id.
type Later = (Option<Facts>, Option<i64>, Option<i64>, Option<i64>);

/// The header of one node, opened in the core when made and closed when dropped.
pub struct Header {
    pub node: NodeAt,
    inner: Rc<Inner>,
}

struct Inner {
    node: NodeAt,
    /// Opened for a level row's menu ([row_menu]): it draws nothing, and does one entry once the core answered the open.
    row: Option<RefCell<Option<HeaderAction>>>,
    /// The drawn entries' actions (`header.<n>`), the bar's group.
    actions: gio::SimpleActionGroup,
    /// A kept link's page's id (`HeaderFacts.bookmark`): its own entries are done through it.
    bookmark: Cell<Option<i64>>,
    id: Cell<Option<i64>>,
    shown: RefCell<Option<HeaderShown>>,
    watch: RefCell<Option<Watch>>,
    /// Where it draws: the header bar's end, and what to redraw there when the state changes.
    bar: gtk::Box,
    toasts: adw::ToastOverlay,
    undo_toast: RefCell<Option<adw::Toast>>,
    /// Whether it still draws into the bar: a header kept for its Undo after its page left does not.
    on_bar: Cell<bool>,
    /// Facts the page learnt before the core answered the open (and a file page's id): sent once it did.
    later: RefCell<Option<Later>>,
    /// A state showed the trash's undo offered: its end is the next state without it.
    seen_undo: Cell<bool>,
    /// The node's head: its cover, cut as stored, drawn 16:9 across the width it is given; hidden while it has none.
    hero: gtk::DrawingArea,
    picture: Rc<RefCell<Option<cairo::ImageSurface>>>,
    /// The crop the head shows (`HeaderShown.cover`), and the number of the latest read of its picture.
    hero_crop: RefCell<Option<Crop>>,
    hero_asked: Cell<u64>,
    /// The pin's question while it shows (the core's `offline.asking`): presented once, closed when the state drops it.
    offline_ask: RefCell<Option<adw::AlertDialog>>,
}

impl Header {
    /// Opens the header of [node] (its page knowing [facts]), drawing into [bar] and saying what it did in [toasts].
    pub fn open(node: &NodeAt, facts: Option<Facts>, bar: &gtk::Box, toasts: &adw::ToastOverlay) -> Self {
        Self::opened(node, facts, bar, toasts, None)
    }

    fn opened(node: &NodeAt, facts: Option<Facts>, bar: &gtk::Box, toasts: &adw::ToastOverlay, row: Option<HeaderAction>) -> Self {
        let inner = Rc::new(Inner {
            node: node.clone(),
            row: row.map(|a| RefCell::new(Some(a))),
            actions: gio::SimpleActionGroup::new(),
            bookmark: Cell::new(None),
            id: Cell::new(None),
            shown: RefCell::new(None),
            watch: RefCell::new(None),
            bar: bar.clone(),
            toasts: toasts.clone(),
            undo_toast: RefCell::new(None),
            on_bar: Cell::new(row.is_none()),
            later: RefCell::new(None),
            seen_undo: Cell::new(false),
            hero: gtk::DrawingArea::builder().hexpand(true).content_height(180).visible(false).build(),
            picture: Rc::new(RefCell::new(None)),
            hero_crop: RefCell::new(None),
            hero_asked: Cell::new(0),
            offline_ask: RefCell::new(None),
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
        click.connect_released(move |_, _, _, _| {
            let Some(i) = w.upgrade() else { return };
            let offered = i.shown.borrow().as_ref().and_then(|s| s.plan.as_ref()).is_some_and(|p| {
                [&p.buttons, &p.menu, &p.last].iter().any(|g| g.iter().any(|a| a.id == HeaderAction::Cover))
            });
            if offered {
                i.act(HeaderAction::Cover);
            }
        });
        inner.hero.add_controller(click);
        inner.hero.set_tooltip_text(Some(&words::cover_change()));
        inner.hero.update_property(&[gtk::accessible::Property::Label(&words::cover_change())]);
        if inner.row.is_none() {
            clear(bar);
            bar.insert_action_group("header", Some(&inner.actions));
        }
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
            if let Some((facts, file, gallery, bookmark)) = inner.later.borrow_mut().take() {
                bridge::send(Call::HeaderFacts { header, facts, file, gallery, bookmark });
            }
            let Some((watch, updates)) = bridge::watch(&Watched::Header { header }) else { return };
            *inner.watch.borrow_mut() = Some(watch);
            drop(inner);
            follow(weak, updates).await;
        });
        Self { node: node.clone(), inner }
    }

    /// What the kind's page knows now (a Pot read: whether it takes writes) and, for a file's page, a Memory's or a kept
    /// link's, its id: the page's own actions (a file's Replace, a gallery's "Add photos", a kept link's own page) join the plan.
    pub fn facts(&self, facts: Option<Facts>, file: Option<i64>, gallery: Option<i64>, bookmark: Option<i64>) {
        self.inner.bookmark.set(bookmark);
        match self.inner.id.get() {
            Some(header) => bridge::send(Call::HeaderFacts { header, facts, file, gallery, bookmark }),
            None => *self.inner.later.borrow_mut() = Some((facts, file, gallery, bookmark)),
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

    /// Rename with [name] in the field (a held rename's *Open*, ADR 0153 decision 4: the name the person gave offline).
    pub fn rename_as(&self, parent: &impl IsA<gtk::Widget>, name: &str) {
        self.inner.rename_as(parent, name);
    }

    /// The node's head for its page: the cover, cut as stored; hidden while there is none.
    pub fn hero(&self) -> gtk::Widget {
        self.inner.hero.clone().upcast()
    }
}

impl Inner {
    /// Pins or unpins: the heart turns at once (the core's state), a failure is said.
    fn pin(self: &Rc<Self>) {
        let Some(header) = self.id.get() else { return };
        let toasts = self.toasts.clone();
        glib::spawn_future_local(async move {
            say(&toasts, bridge::call(&Call::HeaderPin { header }).await.map(|(a, _)| a), "Header.Pin");
        });
    }

    /// "Available offline" toggled: off at once, on asked first (the state's `asking`, drawn by [Inner::draw_offline]).
    fn offline(self: &Rc<Self>) {
        let Some(header) = self.id.get() else { return };
        let toasts = self.toasts.clone();
        glib::spawn_future_local(async move {
            say(&toasts, bridge::call(&Call::HeaderOffline { header }).await.map(|(a, _)| a), "Header.Offline");
        });
    }

    /// Rename: the name asked for in an alert dialog over [parent], asked again with the core's problem.
    fn rename(self: &Rc<Self>, parent: &impl IsA<gtk::Widget>) {
        if self.id.get().is_none() {
            return;
        }
        self.rename_as(parent, &self.node.name);
    }

    /// Rename with [name] in the field (a held rename's *Open*, ADR 0153 decision 4: the name the person gave offline); the
    /// header is the core's by the time the dialog is confirmed.
    fn rename_as(self: &Rc<Self>, parent: &impl IsA<gtk::Widget>, name: &str) {
        let toasts = self.toasts.clone();
        let parent = parent.clone().upcast::<gtk::Widget>();
        let weak = Rc::downgrade(self);
        ask_name(&parent, &words::node_rename(), &words::node_name(), name, None, &words::node_rename(), move |name, again| {
            let toasts = toasts.clone();
            let Some(header) = weak.upgrade().and_then(|i| i.id.get()) else { return };
            glib::spawn_future_local(async move {
                match bridge::call(&Call::HeaderRename { header, name: name.clone() }).await.map(|(a, _)| a) {
                    Ok(Answer::Named { problem: Some(p), .. }) => again(name, said::notice(&p)),
                    Ok(Answer::Named { problem: None, notice, .. }) => {
                        eprintln!("veyq: header rename: Named");
                        if let Some(n) = notice {
                            toasts.add_toast(said::notice_toast(&n));
                        }
                    }
                    other => say(&toasts, other, "Header.Rename"),
                }
            });
        });
    }

    /// The move ([copy] false) or copy sheet over [parent] (core's `MoveSheet`, [crate::core_ui::move_sheet]).
    fn move_sheet(self: &Rc<Self>, parent: &impl IsA<gtk::Widget>, copy: bool) {
        crate::core_ui::move_sheet::open(parent, &self.node, copy, &self.toasts);
    }

    /// The share sheet over [parent] (core's `ShareSheet`, [crate::core_ui::share_sheet]).
    fn share(self: &Rc<Self>, parent: &impl IsA<gtk::Widget>) {
        crate::core_ui::share_sheet::open(parent, &self.node);
    }

    /// The report sheet over [parent] (core's `ReportSheet`, [crate::core_ui::report]).
    fn report(self: &Rc<Self>, parent: &impl IsA<gtk::Widget>) {
        crate::core_ui::report::open(parent, &self.node);
    }

    /// The cover dialog over [parent] ([crate::core_ui::cover]): the stored cover framed as the core holds it, or a picture to pick.
    fn cover(self: &Rc<Self>, parent: &impl IsA<gtk::Widget>) {
        let Some(header) = self.id.get() else { return };
        let stored = self.shown.borrow().as_ref().and_then(|s| s.cover.clone());
        // a new picture may come under the crop it had: the head reads its picture again at the next state
        *self.hero_crop.borrow_mut() = Some(Crop { x: -1.0, y: -1.0, width: 0.0, height: 0.0 });
        crate::core_ui::cover::open(parent, header, &self.node, stored, &self.toasts);
    }

    /// Downloads the node as the web's ZIP: GTK's save dialog (the file chooser portal where there is one) suggests the
    /// core's name, the core writes the file the dialog chose and removes it when it did not finish, and a toast says what
    /// the core said (with what it left out).
    fn download(self: &Rc<Self>, parent: &gtk::Window) {
        let Some(header) = self.id.get() else { return };
        let Some(suggested) = self.shown.borrow().as_ref().map(|s| s.archive_name.clone()) else { return };
        let (name, parent, toasts) = (self.node.name.clone(), parent.clone(), self.toasts.clone());
        glib::spawn_future_local(async move {
            let failed = || said::word_toast("download_failed", &words::download_failed(&name));
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
                    toasts.add_toast(said::toast(&said, notice.kind));
                }
                other => {
                    eprintln!("veyq: Header.Download answered {other:?}");
                    toasts.add_toast(failed());
                }
            }
        });
    }

    /// The delete: asked first, with the kind's title and the core's question; then to the trash, its Undo offered.
    fn delete(self: &Rc<Self>, parent: &impl IsA<gtk::Widget>) {
        let Some(header) = self.id.get() else { return };
        let shown = self.shown.borrow();
        let Some(shown) = shown.as_ref() else { return };
        let title = shown.plan.as_ref().and_then(|p| p.last.iter().find(|a| a.id == HeaderAction::Delete)).and_then(|a| a.word.as_deref()).map(said::key).unwrap_or_default();
        let ask = adw::AlertDialog::builder().heading(&title).body(said::notice(&shown.delete_question)).build();
        ask.add_responses(&[("cancel", &words::entry_cancel()), ("delete", &words::entry_delete())]);
        ask.set_response_appearance("delete", adw::ResponseAppearance::Destructive);
        ask.set_default_response(Some("cancel"));
        ask.set_close_response("cancel");
        let weak = Rc::downgrade(self);
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
                        inner.offer(&notice, undoable, header);
                    }
                    other => say(&inner.toasts, other, "Header.Trash"),
                }
            });
        });
        ask.present(Some(parent));
    }
}

impl Inner {
    /// The window the header's dialogs open over.
    fn window(&self) -> Option<gtk::Window> {
        self.bar.root().and_downcast::<gtk::Window>().or_else(|| self.toasts.root().and_downcast::<gtk::Window>())
    }

    /// Does plan entry [a] on this header's node: every entry one arm ([doer] says whose it is).
    fn act(self: &Rc<Self>, a: HeaderAction) {
        let Some(window) = self.window() else { return eprintln!("veyq: header {a:?}: no window") };
        match doer(a, &self.node.kind, self.row.is_some()) {
            Doer::Page(name) => {
                let _ = WidgetExt::activate_action(&window, &format!("win.{name}"), None);
                return;
            }
            Doer::Nobody => return eprintln!("veyq: header {a:?}: not offered on this platform"),
            Doer::Header => {}
        }
        match a {
            HeaderAction::Favorite => self.pin(),
            HeaderAction::Share => self.share(&window),
            HeaderAction::Rename => self.rename(&window),
            HeaderAction::Move => self.move_sheet(&window, false),
            HeaderAction::Copy => self.move_sheet(&window, true),
            HeaderAction::Cover => self.cover(&window),
            HeaderAction::Download => self.download(&window),
            HeaderAction::Offline => self.offline(),
            HeaderAction::Report => self.report(&window),
            HeaderAction::Delete => self.delete(&window),
            HeaderAction::OpenOwnPage => self.kept_link(false),
            HeaderAction::ReportShared => self.kept_link(true),
            // the page's own, done above
            HeaderAction::Upload | HeaderAction::Music | HeaderAction::Banner | HeaderAction::Replace | HeaderAction::Send => {}
        }
    }

    /// A kept link's *Open on its own page* or ([report]) *Report shared content*: the link's screen above the shell,
    /// opened by the core from the page's model (`Bookmark.OwnPage`, `Bookmark.ReportShared`) — or, from the kept link's row
    /// (no page open, core's row plan offers its share report where the link reads), from the node (`Row.ReportShared`).
    fn kept_link(&self, report: bool) {
        let call = match (self.bookmark.get(), report) {
            (Some(bookmark), true) => Call::BookmarkReportShared { bookmark },
            (Some(bookmark), false) => Call::BookmarkOwnPage { bookmark },
            (None, true) => Call::RowReportShared { node: self.node.clone() },
            (None, false) => return eprintln!("veyq: header kept link: the page told no id"),
        };
        glib::spawn_future_local(async move {
            match bridge::call(&call).await.map(|(a, _)| a) {
                Ok(Answer::ReceivedOpened { received }) => crate::core_ui::received::present(received, None),
                Ok(_) => eprintln!("veyq: the bookmark's link did not open"),
                Err(e) => eprintln!("veyq: the bookmark's link's answer did not read: {:?}", e.0),
            }
        });
    }

    /// What the trash said, with Undo where the core offers it now: a toast that stays until the offer ends.
    fn offer(self: &Rc<Self>, notice: &crate::bridge::wire::Notice, undo: bool, header: i64) {
        let toast = said::notice_toast(notice);
        if undo {
            toast.set_button_label(Some(&words::trash_undo()));
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

    /// The pin's question, while the state asks it: the size it keeps, Cancel or "Make available"; gone with the asking.
    fn draw_offline(self: &Rc<Self>, toggle: Option<&OfflineToggle>) {
        let question = toggle.and_then(|t| t.asking.and(t.question.as_ref()));
        let Some(question) = question else {
            if let Some(ask) = self.offline_ask.borrow_mut().take() {
                ask.force_close();
            }
            return;
        };
        let (Some(header), true) = (self.id.get(), self.offline_ask.borrow().is_none()) else { return };
        let Some(parent) = self.toasts.root() else { return };
        let ask = adw::AlertDialog::builder().heading(words::offline_pin_ask_title()).body(said::notice(question)).build();
        ask.add_responses(&[("cancel", &words::entry_cancel()), ("confirm", &words::offline_pin_confirm())]);
        ask.set_response_appearance("confirm", adw::ResponseAppearance::Suggested);
        ask.set_default_response(Some("confirm"));
        ask.set_close_response("cancel");
        let toasts = self.toasts.clone();
        ask.connect_response(None, move |_, response| {
            let confirmed = response == "confirm";
            let toasts = toasts.clone();
            glib::spawn_future_local(async move {
                if confirmed {
                    say(&toasts, bridge::call(&Call::HeaderOfflineConfirm { header }).await.map(|(a, _)| a), "Header.OfflineConfirm");
                } else {
                    say(&toasts, bridge::call(&Call::HeaderOfflineCancel { header }).await.map(|(a, _)| a), "Header.OfflineCancel");
                }
            });
        });
        eprintln!("veyq: header offline: asked {}", question.msg);
        ask.present(Some(&parent));
        *self.offline_ask.borrow_mut() = Some(ask);
    }

    fn draw(self: &Rc<Self>) {
        // a row's header draws no bar (it is never on one): it does its one entry once the core answered the open with a
        // plan, then follows what that entry asks (the pin's question, the trash's Undo) as a page's header does
        if let Some(pending) = &self.row {
            let ready = self.id.get().is_some() && self.shown.borrow().as_ref().is_some_and(|s| s.plan.is_some());
            let entry = if ready { pending.borrow_mut().take() } else { None };
            if let Some(a) = entry {
                eprintln!("veyq: row menu {a:?}");
                self.act(a);
            }
        }
        let cover = self.shown.borrow().as_ref().map(|s| s.cover.clone());
        if let (Some(cover), None) = (cover, &self.row) {
            self.draw_hero(cover);
        }
        let offline = self.shown.borrow().as_ref().and_then(|s| s.offline.clone());
        if self.on_bar.get() || self.row.is_some() {
            self.draw_offline(offline.as_ref());
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
        for name in self.actions.list_actions() {
            self.actions.remove_action(&name);
        }
        let Some(plan) = s.plan.as_ref() else { return };
        let menu = gio::Menu::new();
        let first = gio::Menu::new();
        let last = gio::Menu::new();
        // every entry, in the plan's order: its action is its place (`header.<n>`), what it does the entry's own arm
        let mut n = 0;
        let mut action = |a: HeaderAction| {
            let name = format!("e{n}");
            n += 1;
            let made = match a {
                // "Available offline" is a check item: its state is the core's `offline.on`, never the click's
                HeaderAction::Offline => gio::SimpleAction::new_stateful(&name, None, &s.offline.as_ref().is_some_and(|o| o.on).to_variant()),
                HeaderAction::Favorite | HeaderAction::Share | HeaderAction::Rename | HeaderAction::Move | HeaderAction::Copy | HeaderAction::Cover
                | HeaderAction::Download | HeaderAction::Report | HeaderAction::Delete | HeaderAction::Upload | HeaderAction::Music
                | HeaderAction::Banner | HeaderAction::Replace | HeaderAction::Send | HeaderAction::OpenOwnPage | HeaderAction::ReportShared => gio::SimpleAction::new(&name, None),
            };
            let weak = Rc::downgrade(self);
            made.connect_activate(move |_, _| {
                if let Some(i) = weak.upgrade() {
                    i.act(a);
                }
            });
            self.actions.add_action(&made);
            format!("header.{name}")
        };
        // GNOME's header bar packs from the end: the menu outermost, the buttons before it
        for a in &plan.buttons {
            let label = a.word.as_deref().map(said::key).unwrap_or_default();
            // the heart filled where the node is a favourite
            let icon = if a.id == HeaderAction::Favorite && s.pinned == Some(true) { "starred-symbolic" } else { icon_of(a.id) };
            let button = gtk::Button::builder().icon_name(icon).tooltip_text(&label).action_name(action(a.id)).build();
            button.update_property(&[gtk::accessible::Property::Label(&label)]);
            self.bar.append(&button);
        }
        for (group, entries) in [(&first, &plan.menu), (&last, &plan.last)] {
            for a in entries {
                group.append(a.word.as_deref().map(said::key).as_deref(), Some(&action(a.id)));
                // the pin's status under its check item, drawn insensitive
                if a.id == HeaderAction::Offline
                    && let Some(line) = s.offline.as_ref().and_then(|o| o.said.as_ref())
                {
                    // an action no one registers draws the item insensitive: a status, not a command
                    group.append(Some(&said::notice(line)), Some("header.offline-status"));
                }
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
                    eprintln!("veyq: header {}: pinned {:?}, offline {:?}, undo {}, gone {}, plan {}", s.node.kind, s.pinned, s.offline.as_ref().map(|o| (o.status, o.asking.is_some())), s.undo.is_some(), s.gone,
                        s.plan.as_ref().map(|p| [&p.buttons, &p.menu, &p.last].iter().map(|g| g.iter().map(|a| format!("{:?}", a.id)).collect::<Vec<_>>().join(",")).collect::<Vec<_>>().join(" | ")).unwrap_or_default());
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

/// A plan entry's icon where it stands as a button, in GNOME's icon set.
pub fn icon_of(a: HeaderAction) -> &'static str {
    match a {
        HeaderAction::Favorite => "non-starred-symbolic",
        HeaderAction::Download => "folder-download-symbolic",
        HeaderAction::Share => "send-to-symbolic",
        HeaderAction::Move => "folder-symbolic",
        HeaderAction::Copy => "edit-copy-symbolic",
        HeaderAction::Rename => "document-edit-symbolic",
        HeaderAction::Delete => "user-trash-symbolic",
        HeaderAction::Report | HeaderAction::ReportShared => "dialog-warning-symbolic",
        HeaderAction::Cover | HeaderAction::Banner => "image-x-generic-symbolic",
        HeaderAction::Replace => "document-revert-symbolic",
        HeaderAction::Upload => "insert-image-symbolic",
        HeaderAction::Offline => "folder-download-symbolic",
        HeaderAction::Music => "audio-x-generic-symbolic",
        HeaderAction::Send => "document-send-symbolic",
        HeaderAction::OpenOwnPage => "folder-publicshare-symbolic",
    }
}

thread_local! {
    /// The header a row's menu entry is being done through: kept until the next row's entry (a dialog, a sheet or the
    /// trash's Undo it opened stays its own).
    static ROW_HEADER: RefCell<Option<Header>> = const { RefCell::new(None) };
}

/// A level row's menu on [widget] (ADR 0154 decision 2; the web's `levelRow.ts`), opened GNOME's way — a right click, or a
/// long press on a touch screen —: the core's plan for [node] (`Row.Plan`) drawn whole, its [menu] then after a separator
/// its last group, each entry done through [node]'s own header model, as its page's header would (said in [toasts]).
pub fn row_menu(widget: &impl IsA<gtk::Widget>, node: &NodeAt, toasts: &adw::ToastOverlay) {
    let open = {
        let (widget, node, toasts) = (widget.clone().upcast::<gtk::Widget>(), node.clone(), toasts.clone());
        move |x: f64, y: f64| {
            let (widget, node, toasts) = (widget.clone(), node.clone(), toasts.clone());
            glib::spawn_future_local(async move {
                let (menu, last) = match bridge::call(&Call::RowPlan { node: node.clone() }).await.map(|(a, _)| a) {
                    Ok(Answer::RowPlan { menu, last }) => (menu, last),
                    other => return eprintln!("veyq: Row.Plan answered {other:?}"),
                };
                eprintln!("veyq: row menu {}: {} | {}", node.kind,
                    menu.iter().map(|a| format!("{:?}", a.id)).collect::<Vec<_>>().join(","), last.iter().map(|a| format!("{:?}", a.id)).collect::<Vec<_>>().join(","));
                if menu.is_empty() && last.is_empty() {
                    return;
                }
                let actions = gio::SimpleActionGroup::new();
                let model = gio::Menu::new();
                let mut n = 0;
                for entries in [&menu, &last] {
                    let section = gio::Menu::new();
                    for a in entries {
                        let name = format!("e{n}");
                        n += 1;
                        let action = gio::SimpleAction::new(&name, None);
                        let (id, node, toasts) = (a.id, node.clone(), toasts.clone());
                        action.connect_activate(move |_, _| {
                            let header = Header::opened(&node, Some(Facts { can_write: true, has_content: true }), &gtk::Box::new(gtk::Orientation::Horizontal, 0), &toasts, Some(id));
                            ROW_HEADER.with(|h| *h.borrow_mut() = Some(header));
                        });
                        actions.add_action(&action);
                        section.append(a.word.as_deref().map(said::key).as_deref(), Some(&format!("row.{name}")));
                    }
                    model.append_section(None, &section);
                }
                widget.insert_action_group("row", Some(&actions));
                let popover = gtk::PopoverMenu::from_model(Some(&model));
                popover.set_parent(&widget);
                popover.set_has_arrow(false);
                popover.set_pointing_to(Some(&gdk::Rectangle::new(x as i32, y as i32, 1, 1)));
                popover.connect_closed(|p| {
                    let p = p.clone();
                    // unparented after its item's action ran (GTK activates it as the popover closes)
                    glib::idle_add_local_once(move || p.unparent());
                });
                popover.popup();
            });
        }
    };
    let right = gtk::GestureClick::builder().button(gdk::BUTTON_SECONDARY).build();
    let on_right = open.clone();
    right.connect_pressed(move |g, _, x, y| {
        g.set_state(gtk::EventSequenceState::Claimed);
        on_right(x, y);
    });
    widget.add_controller(right);
    let long = gtk::GestureLongPress::new();
    long.set_touch_only(true);
    long.connect_pressed(move |_, x, y| open(x, y));
    widget.add_controller(long);
}

/// Says what an action answered, where it says something: its notice as a toast, an unexpected answer on stderr.
pub fn say(toasts: &adw::ToastOverlay, answered: Result<Answer, bridge::Unreadable>, what: &str) {
    match answered {
        Ok(Answer::Said { notice: None }) | Ok(Answer::Done) => eprintln!("veyq: {what}: Done"),
        Ok(Answer::Said { notice: Some(n) }) => {
            eprintln!("veyq: {what}: {}", n.msg);
            toasts.add_toast(said::notice_toast(&n));
        }
        // a refusal or an answer that did not read is said, never only logged (ADR 0154 decision 1)
        Ok(Answer::Refused { why }) => {
            eprintln!("veyq: {what}: Refused {why:?}");
            toasts.add_toast(said::word_toast("node_save_failed", &words::node_save_failed()));
        }
        Err(e) => {
            eprintln!("veyq: {what}: did not read: {:?}", e.0);
            toasts.add_toast(said::word_toast("node_save_failed", &words::node_save_failed()));
        }
        Ok(other) => eprintln!("veyq: {what} answered {other:?}"),
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
    ask_name_with(parent, heading, field, name, problem, confirm, Vec::new(), send);
}

/// [ask_name] with [extra] (rows of the same boxed list, e.g. New Memory's *I was there* and its place) under the name's
/// field, kept with what they hold when the name is asked again.
#[allow(clippy::too_many_arguments)]
pub fn ask_name_with(
    parent: &gtk::Widget,
    heading: &str,
    field: &str,
    name: &str,
    problem: Option<&str>,
    confirm: &str,
    extra: Vec<gtk::ListBoxRow>,
    send: impl Fn(String, Box<dyn Fn(String, String)>) + Clone + 'static,
) {
    let entry = adw::EntryRow::builder().title(field).text(name).build();
    let rows = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    rows.add_css_class("boxed-list");
    rows.append(&entry);
    for row in &extra {
        if let Some(old) = row.parent().and_downcast::<gtk::ListBox>() {
            old.remove(row);
        }
        rows.append(row);
    }
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
        let (at, heading, field, confirm, again_send, extra) = (at.clone(), heading.clone(), field.clone(), confirm.clone(), send.clone(), extra.clone());
        let again: Box<dyn Fn(String, String)> = Box::new(move |name, problem| {
            ask_name_with(&at, &heading, &field, &name, Some(&problem), &confirm, extra.clone(), again_send.clone());
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
    fn each_entry_is_done_by_the_header_or_by_its_pages_own_action() {
        use HeaderAction as A;
        for a in [A::Favorite, A::Share, A::Rename, A::Move, A::Copy, A::Cover, A::Offline, A::Report, A::Delete, A::OpenOwnPage, A::ReportShared] {
            assert_eq!(doer(a, "FOLDER", false), Doer::Header, "{a:?}");
        }
        assert_eq!(doer(A::Download, "FOLDER", false), Doer::Header, "a folder's download is its archive");
        assert_eq!(doer(A::Download, "EXPENSES_POT", false), Doer::Page("pot-download"), "a Pot's download is its page's");
        assert_eq!(doer(A::Download, "FILE", false), Doer::Page("file-save"), "a file's download is its page's Save a copy");
        assert_eq!(doer(A::Download, "FILE", true), Doer::Header, "a row's download has no page: the node's archive");
        assert_eq!(doer(A::Replace, "FILE", false), Doer::Page("file-replace"));
        assert_eq!(doer(A::Upload, "CHAPTER", false), Doer::Page("memory-upload"));
        assert_eq!(doer(A::Music, "MEMORY", false), Doer::Page("memory-music"), "ADR 0142 decision 6");
        assert_eq!(doer(A::Banner, "MEMORY", false), Doer::Page("memory-banner"), "ADR 0144 decision 5");
        assert_eq!(doer(A::Send, "FILE", false), Doer::Nobody, "a phone's share sheet: never offered on a desktop");
    }
}
