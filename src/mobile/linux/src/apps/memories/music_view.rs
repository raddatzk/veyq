//! A Memory's or a chapter's Music view (ADR 0142 decisions 6–11), GNOME's way: the node's tracks as a boxed list of rows
//! — the title (an untitled track by its number), its length, the one playing marked, one that did not play on this device
//! marked so — each row playing its track first, then the rest shuffled (`Music.Play`), with a menu: Rename (an alert
//! dialog whose name may be left empty: the file's name again), Download (GTK's save dialog, the file page's progress
//! toast), Remove (the gallery's trash, its Undo the page's toast) and Report (the report sheet, an item's: no "only its
//! name"). "Add music" opens GTK's open dialog for audio files (`Music.Add`); what the core refuses is said in toasts.
//! Offline the view is read-only. Every state is the core's `MusicModel` (`Music.Open`, `Watched.Music`); only a change
//! of the tracks draws them anew (the core sends the view each time the player moves: a row rebuilt then would close its
//! open menu).
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::MusicShown, wire::MusicTrackShown, wire::Update, wire::Watched};
use crate::core_ui::home::glib_escape;
use crate::core_ui::{said, words};

/// A gallery's Music view on screen: opened in the core when made, closed when dropped.
pub struct MusicView {
    /// The view, clamped to a list's readable width (GNOME: a boxed list in an AdwClamp).
    clamp: adw::Clamp,
    inner: Rc<Inner>,
}

struct Inner {
    id: Cell<Option<i64>>,
    root: gtk::Box,
    add: gtk::Button,
    checking: adw::Spinner,
    list: gtk::ListBox,
    empty: adw::StatusPage,
    shown: RefCell<Option<MusicShown>>,
    /// What the rows were built from ([Shape]), and each row with its mark: a change of anything else (a title, the one
    /// playing, a mark) is drawn into them in place, so an open menu stays open while the music moves on.
    drawn: RefCell<Option<Shape>>,
    rows: RefCell<Vec<(adw::ActionRow, gtk::Image)>>,
    said_shown: Cell<bool>,
    watch: RefCell<Option<Watch>>,
    window: gtk::Window,
    toasts: adw::ToastOverlay,
}

impl MusicView {
    /// Gallery [gallery]'s Music view over [window], what it says in [toasts].
    pub fn open(gallery: i64, window: &gtk::Window, toasts: &adw::ToastOverlay) -> Self {
        let add = gtk::Button::builder().label(words::music_add()).halign(gtk::Align::Start).visible(false).build();
        add.add_css_class("pill");
        add.set_widget_name("music-add");
        let checking = adw::Spinner::builder().width_request(24).height_request(24).visible(false).build();
        let actions = gtk::Box::new(gtk::Orientation::Horizontal, 12);
        actions.append(&add);
        actions.append(&checking);
        let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).visible(false).build();
        list.add_css_class("boxed-list");
        list.update_property(&[gtk::accessible::Property::Label(&words::music_view_music())]);
        let empty = adw::StatusPage::builder().icon_name("audio-x-generic-symbolic").description(words::music_empty_hint()).visible(false).build();
        empty.add_css_class("compact");
        let root = gtk::Box::new(gtk::Orientation::Vertical, 12);
        root.append(&actions);
        root.append(&list);
        root.append(&empty);
        let inner = Rc::new(Inner {
            id: Cell::new(None),
            root,
            add,
            checking,
            list,
            empty,
            shown: RefCell::new(None),
            drawn: RefCell::new(None),
            rows: RefCell::new(Vec::new()),
            said_shown: Cell::new(false),
            watch: RefCell::new(None),
            window: window.clone(),
            toasts: toasts.clone(),
        });
        let weak = Rc::downgrade(&inner);
        inner.add.connect_clicked(move |_| {
            if let Some(i) = weak.upgrade() {
                i.pick();
            }
        });
        let weak = Rc::downgrade(&inner);
        glib::spawn_future_local(async move {
            let Ok((Answer::MusicOpened { music }, _)) = bridge::call(&Call::MusicOpen { gallery }).await else {
                return eprintln!("veyq: the Music view did not open in the core");
            };
            let Some(inner) = weak.upgrade() else { return bridge::send(Call::MusicClose { music }) };
            inner.id.set(Some(music));
            let Some((watch, updates)) = bridge::watch(&Watched::Music { music }) else { return };
            *inner.watch.borrow_mut() = Some(watch);
            drop(inner);
            follow(weak, updates).await;
        });
        let clamp = adw::Clamp::builder().maximum_size(720).child(&inner.root).build();
        Self { clamp, inner }
    }

    pub fn widget(&self) -> &adw::Clamp {
        &self.clamp
    }
}

impl Drop for MusicView {
    fn drop(&mut self) {
        self.inner.watch.borrow_mut().take();
        if let Some(music) = self.inner.id.take() {
            bridge::send(Call::MusicClose { music });
        }
    }
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Music { shown, .. }) => {
                *inner.shown.borrow_mut() = shown;
                inner.draw();
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a Music view update did not read: {:?}", e.0),
        }
    }
}

/// What a list's rows are built from: the tracks in order, what the view offers, and each track's own menu (blocked,
/// removable).
type Shape = (Vec<String>, [bool; 4], Vec<(bool, bool)>);

/// The rows' shape of [tracks] under [offers]: equal shapes keep their rows, only their text and marks drawn anew.
pub fn shape(tracks: &[MusicTrackShown], offers: [bool; 4]) -> Shape {
    (tracks.iter().map(|t| t.media_id.clone()).collect(), offers, tracks.iter().map(|t| (t.blocked, t.can_remove)).collect())
}

/// A track's title as the view says it: its own, else its number's (`music_untitled`).
pub fn title_of(t: &MusicTrackShown) -> String {
    t.title.clone().or_else(|| t.untitled.as_ref().map(said::notice)).unwrap_or_default()
}

/// A track's line under its title: its length, and what keeps it from playing here (blocked, or not played on this device).
pub fn subtitle_of(t: &MusicTrackShown) -> String {
    let mark = if t.blocked {
        Some(words::memory_blocked())
    } else if t.unplayable {
        Some(words::music_track_unplayable())
    } else {
        None
    };
    [t.length.clone(), mark].into_iter().flatten().collect::<Vec<_>>().join(" · ")
}

impl Inner {
    fn draw(self: &Rc<Self>) {
        let shown = self.shown.borrow();
        let Some(s) = shown.as_ref() else {
            self.list.set_visible(false);
            self.empty.set_visible(false);
            self.add.set_visible(false);
            return;
        };
        self.add.set_visible(s.can_add && !s.offline);
        self.checking.set_visible(s.checking);
        if s.said.is_none() {
            self.said_shown.set(false);
        }
        if let (Some(said), Some(music), false) = (&s.said, self.id.get(), self.said_shown.replace(s.said.is_some())) {
            eprintln!("veyq: music view said: {}", said.msg);
            self.toasts.add_toast(adw::Toast::new(&glib_escape(&said::notice(said))));
            bridge::send(Call::MusicSeen { music });
        }
        let tracks = s.tracks.clone();
        self.empty.set_visible(tracks.as_ref().is_some_and(Vec::is_empty));
        self.list.set_visible(tracks.as_ref().is_some_and(|t| !t.is_empty()));
        let offers = [s.can_rename && !s.offline, s.can_download, !s.offline, s.can_report && !s.offline];
        let now = tracks.unwrap_or_default();
        let shaped = shape(&now, offers);
        let marks = |t: &MusicTrackShown| (title_of(t), subtitle_of(t), t.playing);
        let drawn_marks: Vec<_> = self.rows.borrow().iter().map(|(r, i)| (r.title().to_string(), r.subtitle().unwrap_or_default().to_string(), i.has_css_class("accent"))).collect();
        let wanted_marks: Vec<_> = now.iter().map(|t| { let (a, b, c) = marks(t); (glib_escape(&a), glib_escape(&b), c) }).collect();
        if self.drawn.borrow().as_ref() == Some(&shaped) && drawn_marks == wanted_marks {
            return;
        }
        eprintln!("veyq: music view: {} tracks, add {}, offline {}, playing {:?}, unplayable {:?}", now.len(), s.can_add, s.offline,
            now.iter().find(|t| t.playing).map(|t| &t.media_id), now.iter().filter(|t| t.unplayable).map(|t| &t.media_id).collect::<Vec<_>>());
        drop(shown);
        if self.drawn.borrow().as_ref() == Some(&shaped) {
            for ((row, icon), t) in self.rows.borrow().iter().zip(&now) {
                Self::mark(row, icon, t);
            }
            return;
        }
        while let Some(child) = self.list.first_child() {
            self.list.remove(&child);
        }
        let rows: Vec<_> = now.iter().map(|t| self.row(t, offers)).collect();
        for (row, _) in &rows {
            self.list.append(row);
        }
        *self.rows.borrow_mut() = rows;
        *self.drawn.borrow_mut() = Some(shaped);
    }

    /// A row's text and marks: its title, its line, whether it plays.
    fn mark(row: &adw::ActionRow, icon: &gtk::Image, t: &MusicTrackShown) {
        let title = title_of(t);
        row.set_title(&glib_escape(&title));
        row.set_subtitle(&glib_escape(&subtitle_of(t)));
        row.upcast_ref::<gtk::Widget>().update_property(&[gtk::accessible::Property::Description(&words::music_play_from(&title))]);
        icon.set_icon_name(Some(if t.playing { "media-playback-start-symbolic" } else { "audio-x-generic-symbolic" }));
        if t.playing {
            icon.add_css_class("accent");
        } else {
            icon.remove_css_class("accent");
        }
    }

    /// The track [media_id] as the view shows it now.
    fn track(&self, media_id: &str) -> Option<MusicTrackShown> {
        self.shown.borrow().as_ref()?.tracks.as_ref()?.iter().find(|t| t.media_id == media_id).cloned()
    }

    /// One track's row: pressing it plays it first; its menu offers what [offers] says (rename, download, remove, report),
    /// a remove only where the track itself may be.
    fn row(self: &Rc<Self>, t: &MusicTrackShown, offers: [bool; 4]) -> (adw::ActionRow, gtk::Image) {
        let title = title_of(t);
        let row = adw::ActionRow::builder().activatable(!t.blocked).build();
        row.set_widget_name(&format!("music-track:{}", t.media_id));
        let icon = gtk::Image::new();
        row.add_prefix(&icon);
        Self::mark(&row, &icon, t);
        let (weak, id) = (Rc::downgrade(self), t.media_id.clone());
        row.connect_activated(move |_| {
            if let Some(music) = weak.upgrade().and_then(|i| i.id.get()) {
                eprintln!("veyq: music view: play {id}");
                bridge::send(Call::MusicPlay { music, media_id: id.clone() });
            }
        });
        let [rename, download, remove, report] = offers;
        let menu = gio::Menu::new();
        let group = gio::SimpleActionGroup::new();
        let entry = |name: &str, label: String, offered: bool, act: fn(&Rc<Inner>, &MusicTrackShown)| {
            if !offered {
                return;
            }
            menu.append(Some(&label), Some(&format!("track.{name}")));
            let action = gio::SimpleAction::new(name, None);
            // the track as shown when the entry is chosen (its own name may have changed since the row was built)
            let (weak, id) = (Rc::downgrade(self), t.media_id.clone());
            action.connect_activate(move |_, _| {
                if let Some(i) = weak.upgrade()
                    && let Some(track) = i.track(&id)
                {
                    act(&i, &track);
                }
            });
            group.add_action(&action);
        };
        entry("rename", words::music_rename(), rename && !t.blocked, Inner::rename);
        entry("download", words::music_download(), download && !t.blocked, Inner::download);
        entry("remove", words::music_remove(), remove && t.can_remove, Inner::remove);
        entry("report", words::report_action(), report, Inner::report);
        if menu.n_items() > 0 {
            let button = gtk::MenuButton::builder().icon_name("view-more-symbolic").menu_model(&menu).valign(gtk::Align::Center).tooltip_text(words::music_actions(&title)).build();
            button.add_css_class("flat");
            button.update_property(&[gtk::accessible::Property::Label(&words::music_actions(&title))]);
            button.set_widget_name(&format!("music-actions:{}", t.media_id));
            row.add_suffix(&button);
            row.insert_action_group("track", Some(&group));
        }
        (row, icon)
    }

    /// "Add music": GTK's open dialog for audio files (several), their paths to the core, what it refused said.
    fn pick(self: &Rc<Self>) {
        let Some(music) = self.id.get() else { return };
        let (parent, toasts) = (self.window.clone(), self.toasts.clone());
        glib::spawn_future_local(async move {
            let filter = gtk::FileFilter::new();
            filter.set_name(Some(&words::music_add()));
            filter.add_mime_type("audio/*");
            let filters = gio::ListStore::new::<gtk::FileFilter>();
            filters.append(&filter);
            let dialog = gtk::FileDialog::builder().title(words::music_add()).modal(true).filters(&filters).default_filter(&filter).build();
            if let Some(dir) = glib::user_special_dir(glib::UserDirectory::Music).filter(|d| d.is_dir()) {
                dialog.set_initial_folder(Some(&gio::File::for_path(dir)));
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
            let paths: Vec<String> = (0..picked.n_items())
                .filter_map(|i| picked.item(i).and_downcast::<gio::File>())
                .filter_map(|f| f.path().and_then(|p| p.to_str().map(str::to_owned)))
                .collect();
            eprintln!("veyq: music add: {} files", paths.len());
            match bridge::call(&Call::MusicAdd { music, paths }).await.map(|(a, _)| a) {
                Ok(Answer::MusicAdded { said }) => {
                    eprintln!("veyq: Music.Add: {} said", said.len());
                    // the last refusal is the view's own said as well (`MusicShown.said`), which says it once: the rest here
                    for n in said.iter().take(said.len().saturating_sub(1)) {
                        toasts.add_toast(adw::Toast::new(&glib_escape(&said::notice(n))));
                    }
                }
                other => crate::core_ui::header::say(&toasts, other, "Music.Add"),
            }
        });
    }

    /// Rename: an alert dialog with the track's own name, which may be left empty (its file's name again).
    fn rename(self: &Rc<Self>, t: &MusicTrackShown) {
        let Some(music) = self.id.get() else { return };
        let entry = adw::EntryRow::builder().title(words::music_name_label()).text(t.own_name.clone().unwrap_or_default()).build();
        entry.set_widget_name("music-name");
        let rows = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
        rows.add_css_class("boxed-list");
        rows.append(&entry);
        let ask = adw::AlertDialog::builder().heading(words::music_rename_title()).body(words::music_name_hint()).extra_child(&rows).build();
        ask.add_responses(&[("cancel", &words::entry_cancel()), ("rename", &words::music_rename())]);
        ask.set_response_appearance("rename", adw::ResponseAppearance::Suggested);
        ask.set_default_response(Some("rename"));
        ask.set_close_response("cancel");
        entry.set_activates_default(true);
        let focus = entry.clone();
        let (media_id, toasts) = (t.media_id.clone(), self.toasts.clone());
        ask.connect_response(None, move |_, response| {
            if response != "rename" {
                return;
            }
            let name = entry.text().trim().to_owned();
            let (media_id, toasts) = (media_id.clone(), toasts.clone());
            eprintln!("veyq: music view: rename {media_id}");
            glib::spawn_future_local(async move {
                let answered = bridge::call(&Call::MusicRename { music, media_id, name: (!name.is_empty()).then_some(name) }).await;
                crate::core_ui::header::say(&toasts, answered.map(|(a, _)| a), "Music.Rename");
            });
        });
        ask.present(Some(&self.window));
        focus.grab_focus();
    }

    /// Download: GTK's save dialog proposes the file's name, the core writes it there, the file page's toast says how far.
    fn download(self: &Rc<Self>, t: &MusicTrackShown) {
        let Some(music) = self.id.get() else { return };
        let name = t.file_name.clone().unwrap_or_else(|| title_of(t));
        let (parent, toasts, media_id) = (self.window.clone(), self.toasts.clone(), t.media_id.clone());
        glib::spawn_future_local(async move {
            let failed = || adw::Toast::new(&glib_escape(&words::music_download_failed()));
            let dialog = gtk::FileDialog::builder().title(words::music_download()).initial_name(name.as_str()).modal(true).build();
            if let Some(dir) = glib::user_special_dir(glib::UserDirectory::Music).filter(|d| d.is_dir()) {
                dialog.set_initial_folder(Some(&gio::File::for_path(dir)));
            }
            let chosen = match dialog.save_future(Some(&parent)).await {
                Ok(chosen) => chosen,
                Err(e) if e.matches(gtk::DialogError::Dismissed) || e.matches(gtk::DialogError::Cancelled) => return,
                Err(e) => {
                    eprintln!("veyq: the save dialog failed: {e}");
                    return toasts.add_toast(failed());
                }
            };
            let Some(path) = chosen.path().and_then(|p| p.to_str().map(str::to_owned)) else {
                return toasts.add_toast(failed());
            };
            let save = match bridge::call(&Call::MusicDownload { music, media_id, path: path.clone() }).await.map(|(a, _)| a) {
                Ok(Answer::Saving { save }) => save,
                other => {
                    eprintln!("veyq: Music.Download answered {other:?}");
                    return toasts.add_toast(failed());
                }
            };
            eprintln!("veyq: saving a track to {path} (save {save})");
            crate::core_ui::saving::saving(save, &toasts).await;
        });
    }

    /// Remove: the gallery's trash; its Undo is the page's toast (`GalleryShown.undo`).
    fn remove(self: &Rc<Self>, t: &MusicTrackShown) {
        let Some(music) = self.id.get() else { return };
        let (toasts, media_id) = (self.toasts.clone(), t.media_id.clone());
        eprintln!("veyq: music view: remove {media_id}");
        glib::spawn_future_local(async move {
            crate::core_ui::header::say(&toasts, bridge::call(&Call::MusicRemove { music, media_id }).await.map(|(a, _)| a), "Music.Remove");
        });
    }

    /// Report: the report sheet the core opens for the track, an item (ADR 0142 decision 11).
    fn report(self: &Rc<Self>, t: &MusicTrackShown) {
        let (Some(music), Some(node)) = (self.id.get(), self.shown.borrow().as_ref().map(|s| s.node.clone())) else { return };
        crate::core_ui::report::open_by(&self.window, &node, Call::MusicReport { music, media_id: t.media_id.clone() });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bridge::wire::{Notice, NoticeArg};

    fn track() -> MusicTrackShown {
        MusicTrackShown {
            media_id: "m1".into(),
            title: None,
            untitled: Some(Notice { msg: "music_untitled".into(), args: vec![NoticeArg::Int { value: 2 }] }),
            own_name: None,
            file_name: Some("a.ogg".into()),
            length: Some("3:05".into()),
            playing: false,
            unplayable: false,
            blocked: false,
            can_remove: true,
        }
    }

    /// A row's line: the length, and the device's mark where the track did not play here (ADR 0142 decision 9); a title
    /// of its own before the untitled number.
    #[test]
    fn a_tracks_row_says_its_length_and_why_it_does_not_play() {
        let t = track();
        assert_eq!(subtitle_of(&t), "3:05");
        assert_eq!(subtitle_of(&MusicTrackShown { unplayable: true, ..t.clone() }), format!("3:05 · {}", words::music_track_unplayable()));
        assert_eq!(subtitle_of(&MusicTrackShown { blocked: true, length: None, ..t.clone() }), words::memory_blocked());
        assert_eq!(title_of(&MusicTrackShown { title: Some("Song".into()), ..t.clone() }), "Song");
        assert_eq!(title_of(&t), words::music_untitled(2), "an untitled track is said by its number");
    }
}
