//! A Memory's Contributions view (ADR 0149 decision 6, `uc.memory.buckets`; the web's `MemoryBuckets.vue`), GNOME's way:
//! the uploader picker as a boxed list — each contributor's primary bucket with the sub-buckets curated under it, indented by
//! depth, its count at the end, the chosen one checked — and under it the chosen bucket's photos and videos as a grid of
//! square thumbnails (a press opens the lightbox where the gallery shows the medium; a secondary press offers *Move item*
//! into another bucket of one's own). Where the chosen bucket is the caller's to curate, *New sub-bucket*, and on a
//! sub-bucket *Rename* and *Delete* (asked, its name typed to confirm, as the web asks). Shown only to a member, the fourth
//! toggle beside Photos | Music | Map.
//!
//! Every state is the core's (`Buckets.Open`, `Watched.Buckets`); every action one call; a curation not done is said as a
//! toast and told seen. A curation done moves the gallery's rows only once its re-read lands, after the view read its
//! buckets (inbox #990): the view is read anew at the gallery's next state, the chosen bucket kept.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::{gdk, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::BucketRowShown, wire::BucketsOutcome, wire::BucketsShown, wire::Call, wire::Update, wire::Watched};
use crate::home::glib_escape;
use crate::{header, said, words};

/// A thumbnail's side in the chosen bucket's grid.
const THUMB: i32 = 120;

/// What opens a medium of the chosen bucket in the gallery's lightbox (by its id; nothing where the gallery's filter hides it).
type OpenMedium = Box<dyn Fn(&str)>;

/// The Contributions view of one gallery: opened in the core when made, closed when dropped.
pub struct ContributionsView {
    inner: Rc<Inner>,
}

struct Inner {
    gallery: i64,
    root: gtk::Box,
    rows: adw::PreferencesGroup,
    row_widgets: RefCell<Vec<adw::ActionRow>>,
    create: gtk::Button,
    rename: gtk::Button,
    delete: gtk::Button,
    status: gtk::Label,
    spinner: adw::Spinner,
    media: gtk::FlowBox,
    id: Cell<Option<i64>>,
    shown: RefCell<Option<BucketsShown>>,
    watch: RefCell<Option<Watch>>,
    window: gtk::Window,
    toasts: adw::ToastOverlay,
    open: OpenMedium,
    /// A curation landed: the next gallery state reads the buckets anew (#990), choosing [keep] again once they are read.
    stale: Cell<bool>,
    keep: RefCell<Option<String>>,
}

impl ContributionsView {
    /// The view over gallery [gallery]; [open] opens one of its media in the lightbox.
    pub fn open(gallery: i64, window: &gtk::Window, toasts: &adw::ToastOverlay, open: impl Fn(&str) + 'static) -> Self {
        let root = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).build();
        root.set_widget_name("contributions");
        let action = |label: String, icon: &str| {
            let b = gtk::Button::builder().child(&adw::ButtonContent::builder().label(label).icon_name(icon).build()).visible(false).build();
            b.add_css_class("flat");
            b
        };
        // GNOME's creating action (`create_action`): the "+" with its label as tooltip
        let create = crate::create_action::create_action(&words::memory_buckets_create_bucket(), || {});
        create.add_css_class("flat");
        create.set_visible(false);
        let rename = action(words::memory_buckets_rename(), "document-edit-symbolic");
        let delete = action(words::memory_buckets_delete(), "user-trash-symbolic");
        let actions = gtk::Box::builder().spacing(6).build();
        for b in [&create, &rename, &delete] {
            actions.append(b);
        }
        let rows = adw::PreferencesGroup::builder().title(words::memory_buckets_title()).header_suffix(&actions).build();
        let spinner = adw::Spinner::builder().width_request(32).height_request(32).halign(gtk::Align::Center).build();
        let status = gtk::Label::builder().wrap(true).visible(false).build();
        status.add_css_class("dim-label");
        let media = gtk::FlowBox::builder().selection_mode(gtk::SelectionMode::None).homogeneous(true).column_spacing(6).row_spacing(6)
            .max_children_per_line(40).valign(gtk::Align::Start).build();
        media.set_widget_name("contributions-media");
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).build();
        column.append(&rows);
        column.append(&spinner);
        column.append(&status);
        column.append(&media);
        root.append(&adw::Clamp::builder().maximum_size(960).child(&column).build());
        let inner = Rc::new(Inner {
            gallery,
            root,
            rows,
            row_widgets: RefCell::new(Vec::new()),
            create,
            rename,
            delete,
            status,
            spinner,
            media,
            id: Cell::new(None),
            shown: RefCell::new(None),
            watch: RefCell::new(None),
            window: window.clone(),
            toasts: toasts.clone(),
            open: Box::new(open),
            stale: Cell::new(false),
            keep: RefCell::new(None),
        });
        Inner::connect(&inner);
        Inner::attach(&inner);
        Self { inner }
    }

    pub fn widget(&self) -> &gtk::Box {
        &self.inner.root
    }

    /// The gallery's state moved: where a curation landed since, the buckets are read anew (#990).
    pub fn gallery_changed(&self) {
        if self.inner.stale.replace(false) {
            eprintln!("veyq: contributions read anew after a curation");
            *self.inner.keep.borrow_mut() = self.inner.shown.borrow().as_ref().and_then(|s| s.selected.clone());
            self.inner.watch.borrow_mut().take();
            if let Some(buckets) = self.inner.id.take() {
                bridge::send(Call::BucketsClose { buckets });
            }
            Inner::attach(&self.inner);
        }
    }
}

impl Inner {
    /// The view opened in the core (`Buckets.Open`) and followed.
    fn attach(this: &Rc<Self>) {
        let gallery = this.gallery;
        let weak = Rc::downgrade(this);
        glib::spawn_future_local(async move {
            let buckets = match bridge::call(&Call::BucketsOpen { gallery }).await.map(|(a, _)| a) {
                Ok(Answer::BucketsOpened { buckets }) => buckets,
                other => return eprintln!("veyq: Buckets.Open answered {other:?}"),
            };
            let Some(inner) = weak.upgrade() else { return bridge::send(Call::BucketsClose { buckets }) };
            inner.id.set(Some(buckets));
            eprintln!("veyq: contributions open");
            let Some((watch, updates)) = bridge::watch(&Watched::Buckets { buckets }) else { return };
            *inner.watch.borrow_mut() = Some(watch);
            drop(inner);
            follow(weak, updates).await;
        });
    }
}

impl Drop for ContributionsView {
    fn drop(&mut self) {
        self.inner.watch.borrow_mut().take();
        if let Some(buckets) = self.inner.id.take() {
            bridge::send(Call::BucketsClose { buckets });
        }
    }
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Buckets { shown: Some(s), .. }) => inner.draw(s),
            Ok(Update::Buckets { shown: None, .. }) => {
                inner.id.set(None);
                return;
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a contributions update did not read: {:?}", e.0),
        }
    }
}

/// The curation word that says [outcome] did not land: a move's, or any other's.
pub fn not_done(outcome: BucketsOutcome, moving: bool) -> Option<String> {
    (outcome != BucketsOutcome::Done).then(|| if moving { words::memory_buckets_move_failed() } else { words::memory_buckets_save_failed() })
}

/// The rows a medium of the chosen bucket may move into: the caller's own buckets but the chosen one.
pub fn targets(s: &BucketsShown) -> Vec<&BucketRowShown> {
    s.rows.iter().filter(|r| r.can_manage && Some(&r.bucket_id) != s.selected.as_ref()).collect()
}

impl Inner {
    fn connect(this: &Rc<Self>) {
        let weak = Rc::downgrade(this);
        this.create.connect_clicked(move |_| {
            if let Some(i) = weak.upgrade() {
                i.ask_create();
            }
        });
        let weak = Rc::downgrade(this);
        this.rename.connect_clicked(move |_| {
            if let Some(i) = weak.upgrade() {
                i.ask_rename();
            }
        });
        let weak = Rc::downgrade(this);
        this.delete.connect_clicked(move |_| {
            if let Some(i) = weak.upgrade() {
                i.ask_delete();
            }
        });
    }

    fn selected_row(&self) -> Option<BucketRowShown> {
        let s = self.shown.borrow();
        let s = s.as_ref()?;
        s.rows.iter().find(|r| Some(&r.bucket_id) == s.selected.as_ref()).cloned()
    }

    fn draw(self: &Rc<Self>, s: BucketsShown) {
        eprintln!("veyq: contributions shown: {} buckets, selected {}, {} media, curates {}, loading {}, failed {}, refused {:?}",
            s.rows.len(), s.selected.is_some(), s.media.len(), s.curates, s.loading, s.failed, s.refused);
        let rows_changed = self.shown.borrow().as_ref().map(|o| (&o.rows, &o.selected)) != Some((&s.rows, &s.selected));
        let media_changed = self.shown.borrow().as_ref().map(|o| &o.media) != Some(&s.media);
        if let (Some(refused), Some(buckets)) = (s.refused, self.id.get()) {
            // a move's own answer says it; this is what any curation left behind
            if let Some(text) = not_done(refused, false) {
                self.toasts.add_toast(adw::Toast::new(&glib_escape(&text)));
            }
            bridge::send(Call::BucketsSeen { buckets });
        }
        *self.shown.borrow_mut() = Some(s.clone());
        // read anew after a curation: the bucket chosen before is chosen again
        if !s.loading
            && let (Some(keep), Some(buckets)) = (self.keep.borrow_mut().take(), self.id.get())
            && s.selected.as_ref() != Some(&keep)
            && s.rows.iter().any(|r| r.bucket_id == keep)
        {
            bridge::send(Call::BucketsPick { buckets, bucket_id: keep });
        }
        self.spinner.set_visible(s.loading && s.rows.is_empty());
        let status = if s.failed {
            Some(words::media_unavailable())
        } else if !s.loading && s.selected.is_some() && s.media.is_empty() {
            Some(words::memory_buckets_empty_bucket())
        } else {
            None
        };
        self.status.set_visible(status.is_some());
        self.status.set_label(&status.unwrap_or_default());
        let sub = self.selected_row().is_some_and(|r| !r.is_primary && r.can_manage);
        self.create.set_visible(s.curates);
        self.rename.set_visible(s.curates && sub);
        self.delete.set_visible(s.curates && sub);
        if rows_changed {
            self.draw_rows(&s);
        }
        if media_changed {
            self.draw_media(&s);
        }
    }

    /// The picker: a row per bucket at its depth, its count, the chosen one checked; a row chosen narrows the grid.
    fn draw_rows(self: &Rc<Self>, s: &BucketsShown) {
        for row in self.row_widgets.borrow_mut().drain(..) {
            self.rows.remove(&row);
        }
        for r in &s.rows {
            let row = adw::ActionRow::builder().title(glib_escape(&said::piece(&r.label))).activatable(true).build();
            if r.depth > 0 {
                row.add_prefix(&gtk::Box::builder().width_request(24 * r.depth).build());
                row.add_prefix(&gtk::Image::from_icon_name("folder-symbolic"));
            } else {
                row.add_prefix(&gtk::Image::from_icon_name("avatar-default-symbolic"));
            }
            let count = gtk::Label::new(Some(&r.media_count.to_string()));
            count.add_css_class("dim-label");
            row.add_suffix(&count);
            let check = gtk::Image::from_icon_name("object-select-symbolic");
            check.set_visible(Some(&r.bucket_id) == s.selected.as_ref());
            row.add_suffix(&check);
            let weak = Rc::downgrade(self);
            let id = r.bucket_id.clone();
            row.connect_activated(move |_| {
                if let Some(i) = weak.upgrade()
                    && let Some(buckets) = i.id.get()
                {
                    eprintln!("veyq: contributions: bucket chosen");
                    bridge::send(Call::BucketsPick { buckets, bucket_id: id.clone() });
                }
            });
            self.rows.add(&row);
            self.row_widgets.borrow_mut().push(row);
        }
    }

    /// The chosen bucket's media as square thumbnails (`Gallery.Tile`), each opening its lightbox; a secondary press
    /// offers *Move item* where the bucket is the caller's to curate.
    fn draw_media(self: &Rc<Self>, s: &BucketsShown) {
        self.media.remove_all();
        for media_id in &s.media {
            let picture = gtk::Picture::builder().content_fit(gtk::ContentFit::Cover).can_shrink(true).width_request(THUMB).height_request(THUMB).build();
            let frame = gtk::Overlay::builder().child(&picture).width_request(THUMB).height_request(THUMB).build();
            frame.set_overflow(gtk::Overflow::Hidden);
            frame.add_css_class("card");
            let child = gtk::FlowBoxChild::builder().child(&frame).focusable(true).build();
            self.thumbnail(&picture, media_id);
            let click = gtk::GestureClick::builder().button(0).build();
            let (weak, id, anchor) = (Rc::downgrade(self), media_id.clone(), frame.downgrade());
            click.connect_released(move |g, _, x, y| {
                let Some(i) = weak.upgrade() else { return };
                g.set_state(gtk::EventSequenceState::Claimed);
                if g.current_button() == gdk::BUTTON_SECONDARY {
                    if let Some(a) = anchor.upgrade() {
                        i.move_menu(&a, &id, x, y);
                    }
                } else {
                    (i.open)(&id);
                }
            });
            frame.add_controller(click);
            let (weak, id) = (Rc::downgrade(self), media_id.clone());
            child.connect_activate(move |_| {
                if let Some(i) = weak.upgrade() {
                    (i.open)(&id);
                }
            });
            self.media.append(&child);
        }
    }

    /// Medium [media_id]'s grid tile, read once (`Gallery.Tile`) and decoded in memory.
    fn thumbnail(&self, picture: &gtk::Picture, media_id: &str) {
        let (gallery, picture, media_id) = (self.gallery, picture.downgrade(), media_id.to_owned());
        glib::spawn_future_local(async move {
            let answered = bridge::call(&Call::GalleryTile { gallery, media_id }).await;
            let Some(picture) = picture.upgrade() else { return };
            if let Ok((Answer::Bytes { .. }, Some(bytes))) = answered
                && let Ok(t) = gdk::Texture::from_bytes(&glib::Bytes::from_owned(bytes))
            {
                picture.set_paintable(Some(&crate::file_page::within_edge(&t, THUMB * 2)));
            }
        });
    }

    /// *Move item*: a popover over the thumbnail listing the caller's other buckets; one chosen moves the medium there.
    fn move_menu(self: &Rc<Self>, anchor: &gtk::Overlay, media_id: &str, x: f64, y: f64) {
        let Some(s) = self.shown.borrow().clone() else { return };
        if !s.curates {
            return;
        }
        let list = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(2).margin_top(6).margin_bottom(6).margin_start(6).margin_end(6).build();
        let heading = gtk::Label::builder().label(words::memory_buckets_move_target()).xalign(0.0).build();
        heading.add_css_class("heading");
        list.append(&heading);
        let popover = gtk::Popover::builder().child(&list).build();
        let targets = targets(&s);
        if targets.is_empty() {
            let none = gtk::Label::builder().label(words::memory_buckets_move_empty()).wrap(true).max_width_chars(30).xalign(0.0).build();
            none.add_css_class("dim-label");
            list.append(&none);
        }
        for t in targets {
            let b = gtk::Button::builder().label(said::piece(&t.label)).build();
            b.add_css_class("flat");
            let (weak, media_id, target, pop) = (Rc::downgrade(self), media_id.to_owned(), t.bucket_id.clone(), popover.downgrade());
            b.connect_clicked(move |_| {
                if let Some(p) = pop.upgrade() {
                    p.popdown();
                }
                if let Some(i) = weak.upgrade() {
                    i.curate(Call::BucketsMove { buckets: i.id.get().unwrap_or_default(), media_ids: vec![media_id.clone()], target: target.clone() }, true);
                }
            });
            list.append(&b);
        }
        popover.set_parent(anchor);
        popover.set_pointing_to(Some(&gdk::Rectangle::new(x as i32, y as i32, 1, 1)));
        popover.connect_closed(|p| {
            let p = p.clone();
            glib::idle_add_local_once(move || p.unparent());
        });
        eprintln!("veyq: contributions: move offered");
        popover.popup();
    }

    /// A curation sent; what did not land said ([moving]: a move's own word).
    fn curate(self: &Rc<Self>, call: Call, moving: bool) {
        if self.id.get().is_none() {
            return;
        }
        let toasts = self.toasts.clone();
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            match bridge::call(&call).await.map(|(a, _)| a) {
                Ok(Answer::BucketsDone { outcome }) => {
                    eprintln!("veyq: contributions curation: {outcome:?}");
                    if outcome == BucketsOutcome::Done
                        && let Some(i) = weak.upgrade()
                    {
                        i.stale.set(true);
                    }
                    if let Some(text) = not_done(outcome, moving) {
                        toasts.add_toast(adw::Toast::new(&glib_escape(&text)));
                    }
                }
                other => header::say(&toasts, other, "Buckets"),
            }
        });
    }

    /// *New sub-bucket*: its name asked; made under the chosen bucket.
    fn ask_create(self: &Rc<Self>) {
        let Some(buckets) = self.id.get() else { return };
        let weak = Rc::downgrade(self);
        let parent = self.window.clone().upcast::<gtk::Widget>();
        let title = words::memory_buckets_create_bucket();
        header::ask_name(&parent, &title, &words::memory_buckets_name(), "", None, &title, move |name, _again| {
            if let Some(i) = weak.upgrade() {
                i.curate(Call::BucketsCreate { buckets, name }, false);
            }
        });
    }

    /// *Rename*: the chosen sub-bucket's new name asked.
    fn ask_rename(self: &Rc<Self>) {
        let (Some(buckets), Some(row)) = (self.id.get(), self.selected_row()) else { return };
        let weak = Rc::downgrade(self);
        let parent = self.window.clone().upcast::<gtk::Widget>();
        header::ask_name(&parent, &words::memory_buckets_bucket_dialog_rename(), &words::memory_buckets_name(), &said::piece(&row.label), None,
            &words::memory_buckets_rename(), move |name, _again| {
                if let Some(i) = weak.upgrade() {
                    i.curate(Call::BucketsRename { buckets, name }, false);
                }
            });
    }

    /// *Delete*: asked, the bucket's name typed to confirm (the web's dialog); its media go back to the caller's primary.
    fn ask_delete(self: &Rc<Self>) {
        let (Some(buckets), Some(row)) = (self.id.get(), self.selected_row()) else { return };
        let name = said::piece(&row.label);
        let ask = adw::AlertDialog::builder().heading(words::memory_buckets_delete_bucket_title()).body(words::memory_buckets_delete_bucket_copy(&name)).build();
        let entry = adw::EntryRow::builder().title(words::memory_buckets_delete_bucket_name_label()).build();
        let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
        list.add_css_class("boxed-list");
        list.append(&entry);
        ask.set_extra_child(Some(&list));
        ask.add_responses(&[("cancel", &words::entry_cancel()), ("delete", &words::memory_buckets_delete_bucket_confirm())]);
        ask.set_response_appearance("delete", adw::ResponseAppearance::Destructive);
        ask.set_response_enabled("delete", false);
        ask.set_close_response("cancel");
        let (dialog, typed_name) = (ask.downgrade(), name.clone());
        entry.connect_changed(move |e| {
            if let Some(d) = dialog.upgrade() {
                d.set_response_enabled("delete", e.text().trim() == typed_name.trim());
            }
        });
        let weak = Rc::downgrade(self);
        ask.connect_response(None, move |_, response| {
            if response == "delete"
                && let Some(i) = weak.upgrade()
            {
                i.curate(Call::BucketsDelete { buckets }, false);
            }
        });
        ask.present(Some(&self.window));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bridge::wire::Piece;

    fn row(id: &str, primary: bool, manage: bool) -> BucketRowShown {
        BucketRowShown { bucket_id: id.into(), label: Piece::Plain { text: id.into() }, depth: if primary { 0 } else { 1 }, media_count: 0, is_primary: primary, can_manage: manage }
    }

    /// A medium moves only into another bucket of the caller's own, never the one it is in or someone else's.
    #[test]
    fn a_medium_moves_into_another_bucket_of_ones_own() {
        let s = BucketsShown {
            rows: vec![row("self", true, true), row("trip", false, true), row("other", true, false)],
            selected: Some("trip".into()),
            media: vec![],
            curates: true,
            loading: false,
            failed: false,
            refused: None,
        };
        assert_eq!(targets(&s).iter().map(|r| r.bucket_id.as_str()).collect::<Vec<_>>(), vec!["self"]);
        assert_eq!(not_done(BucketsOutcome::Done, true), None);
        assert_eq!(not_done(BucketsOutcome::Refused, true), Some(words::memory_buckets_move_failed()));
        assert_eq!(not_done(BucketsOutcome::Failed, false), Some(words::memory_buckets_save_failed()));
    }
}
