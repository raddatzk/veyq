//! A Memory's or a chapter's Map view (ADR 0143 decision 8; the web's `MemoryTracks` tab, Windows' `MemoryMapView`), GNOME's
//! way: behind the page's toggle group beside Photos and Music, the map ([crate::core_ui::raster_map]: the photos' dots and the
//! node's own GPX tracks, each in its colour, dashed by its activity, the selected one heavier), then "Add GPX track"
//! where whoever looks may add (GTK's open dialog filtered to `.gpx`, several; each accepted file's name and activity
//! asked in a dialog before anything is uploaded, Cancel uploading nothing), then the tracks as a boxed list — each row
//! its show switch, its colour swatch with the activity's dash, the activity above the name, distance, duration, climb
//! and descent; activating a row selects it (its map frames it), again unselects; each row's menu: Edit (name and
//! activity), Download GPX (GTK's save dialog, the file page's progress toast), Remove (the gallery's trash, its Undo the
//! page's toast), Report (an item). Without map services the list alone; offline read-only, no map (decision 9). Every
//! state is the core's `TrackList` (`Tracks.Open`, `Watched.Tracks`); only a change of the tracks' shape builds the rows
//! anew, so an open menu stays open while a track reads.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::TrackFactShown, wire::TrackLabelShown, wire::TrackShown, wire::TrackStatus,
    wire::TracksShown, wire::Update, wire::Watched};
use crate::core_ui::home::glib_escape;
use crate::core_ui::raster_map::{RasterMap, argb};
use crate::core_ui::{said, words};

/// A gallery's Map view on screen: opened in the core when made, closed (its map with it) when dropped.
pub struct MemoryMapView {
    clamp: adw::Clamp,
    inner: Rc<Inner>,
}

struct Inner {
    id: Cell<Option<i64>>,
    root: gtk::Box,
    map_slot: gtk::Box,
    map: RefCell<Option<RasterMap>>,
    add: gtk::Button,
    checking: adw::Spinner,
    list: gtk::ListBox,
    empty: gtk::Label,
    shown: RefCell<Option<TracksShown>>,
    drawn: RefCell<Option<Vec<String>>>,
    said_shown: Cell<bool>,
    asking: Cell<bool>,
    watch: RefCell<Option<Watch>>,
    window: gtk::Window,
    toasts: adw::ToastOverlay,
}

impl MemoryMapView {
    /// Gallery [gallery]'s Map view over [window], what it says in [toasts].
    pub fn open(gallery: i64, window: &gtk::Window, toasts: &adw::ToastOverlay) -> Self {
        let map_slot = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
        let add = gtk::Button::builder().label(words::track_add()).halign(gtk::Align::Start).visible(false).build();
        add.add_css_class("pill");
        add.set_widget_name("track-add");
        let checking = adw::Spinner::builder().width_request(24).height_request(24).visible(false).build();
        let actions = gtk::Box::new(gtk::Orientation::Horizontal, 12);
        actions.append(&add);
        actions.append(&checking);
        let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).visible(false).build();
        list.add_css_class("boxed-list");
        list.update_property(&[gtk::accessible::Property::Label(&words::track_title())]);
        let empty = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
        empty.add_css_class("dim-label");
        let root = gtk::Box::new(gtk::Orientation::Vertical, 12);
        root.append(&map_slot);
        root.append(&actions);
        root.append(&list);
        root.append(&empty);
        let inner = Rc::new(Inner {
            id: Cell::new(None),
            root,
            map_slot,
            map: RefCell::new(None),
            add,
            checking,
            list,
            empty,
            shown: RefCell::new(None),
            drawn: RefCell::new(None),
            said_shown: Cell::new(false),
            asking: Cell::new(false),
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
        inner.list.connect_row_activated(move |_, row| {
            let Some(i) = weak.upgrade() else { return };
            let Some(tracks) = i.id.get() else { return };
            let id = row.widget_name().strip_prefix("track:").map(str::to_owned);
            let selected = id.as_ref().and_then(|m| i.track(m)).is_some_and(|t| t.selected);
            eprintln!("veyq: map view: select {:?}", if selected { None } else { id.clone() });
            bridge::send(Call::TracksSelect { tracks, media_id: if selected { None } else { id } });
        });
        let weak = Rc::downgrade(&inner);
        glib::spawn_future_local(async move {
            let (tracks, map) = match bridge::call(&Call::TracksOpen { gallery }).await.map(|(a, _)| a) {
                Ok(Answer::TracksOpened { tracks, map }) => (tracks, map),
                other => return eprintln!("veyq: Tracks.Open answered {other:?}"),
            };
            let Some(inner) = weak.upgrade() else { return bridge::send(Call::TracksClose { tracks }) };
            inner.id.set(Some(tracks));
            eprintln!("veyq: map view open, map {map:?}");
            if let Some(map) = map {
                let m = RasterMap::open(map, 360, false, &inner.window);
                inner.map_slot.append(m.widget());
                *inner.map.borrow_mut() = Some(m);
            }
            let Some((watch, updates)) = bridge::watch(&Watched::Tracks { tracks }) else { return };
            *inner.watch.borrow_mut() = Some(watch);
            drop(inner);
            follow(weak, updates).await;
        });
        let clamp = adw::Clamp::builder().maximum_size(960).child(&inner.root).build();
        Self { clamp, inner }
    }

    pub fn widget(&self) -> &adw::Clamp {
        &self.clamp
    }
}

impl Drop for MemoryMapView {
    fn drop(&mut self) {
        self.inner.watch.borrow_mut().take();
        self.inner.map.borrow_mut().take();
        if let Some(tracks) = self.inner.id.take() {
            bridge::send(Call::TracksClose { tracks });
        }
    }
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Tracks { shown, .. }) => {
                *inner.shown.borrow_mut() = shown;
                inner.draw();
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a Map view update did not read: {:?}", e.0),
        }
    }
}

/// An activity's word (`track_activity_<token>`); none for no activity.
pub fn activity_word(token: Option<&str>) -> Option<String> {
    token.map(|t| said::key(&format!("track_activity_{}", t.replace('-', "_"))))
}

/// A track's name as the list says it: its own (or its file's), else its number's.
pub fn name_of(t: &TrackShown) -> String {
    t.name.clone().unwrap_or_else(|| words::track_unnamed(i64::from(t.number)))
}

/// A number with [decimals] digits after the person's decimal sign.
/// One of a track's facts: its distance as the core writes it (`Shown.distance`), else the core's phrase.
pub fn fact_text(f: &TrackFactShown) -> Option<String> {
    match (&f.distance_text, &f.words) {
        (Some(d), _) => Some(d.clone()),
        (None, Some(n)) => Some(said::notice(n)),
        _ => None,
    }
}

/// A track's line under its name: its facts, or how its read stands, or that the operator blocked it.
pub fn subtitle_of(t: &TrackShown) -> String {
    if t.blocked {
        return words::memory_blocked();
    }
    match t.status {
        TrackStatus::Loading => words::track_loading(),
        TrackStatus::Failed => words::track_unreadable(),
        TrackStatus::Ready => said::joined(&t.facts.iter().filter_map(fact_text).collect::<Vec<_>>()),
    }
}

/// What a row is built from: its track, its read, its look, its label, its menu — anything else is drawn in place.
fn shape(t: &TrackShown, s: &TracksShown) -> String {
    format!("{}|{:?}|{}|{:?}|{:?}|{:?}|{}|{}|{}|{}|{}|{}|{}|{}", t.media_id, t.status, t.color, t.dash, t.name, t.activity, t.shown, t.selected,
        t.blocked, t.can_remove, s.can_edit && !s.offline, s.can_download, s.can_report && !s.offline, subtitle_of(t))
}

/// A track's swatch: a short stroke in its colour with its activity's dash, drawn as the map draws its line.
fn swatch(color: i64, dash: Option<&Vec<i32>>) -> gtk::Widget {
    let area = gtk::DrawingArea::builder().content_width(36).content_height(14).valign(gtk::Align::Center).build();
    let (rgba, dash) = (argb(color), dash.cloned());
    area.set_draw_func(move |_, cr, w, h| {
        cr.set_source_rgba(f64::from(rgba.red()), f64::from(rgba.green()), f64::from(rgba.blue()), f64::from(rgba.alpha()));
        cr.set_line_width(4.0);
        if let Some(d) = dash.as_ref().filter(|d| !d.is_empty()) {
            cr.set_dash(&d.iter().map(|v| f64::from(*v)).collect::<Vec<_>>(), 0.0);
        }
        cr.move_to(2.0, f64::from(h) / 2.0);
        cr.line_to(f64::from(w) - 2.0, f64::from(h) / 2.0);
        let _ = cr.stroke();
    });
    area.upcast()
}

impl Inner {
    fn track(&self, media_id: &str) -> Option<TrackShown> {
        self.shown.borrow().as_ref()?.tracks.as_ref()?.iter().find(|t| t.media_id == media_id).cloned()
    }

    fn draw(self: &Rc<Self>) {
        let shown = self.shown.borrow();
        let Some(s) = shown.as_ref() else {
            self.list.set_visible(false);
            self.add.set_visible(false);
            return;
        };
        self.add.set_visible(s.can_add && !s.offline);
        self.checking.set_visible(s.checking);
        if s.said.is_none() {
            self.said_shown.set(false);
        }
        if let (Some(said), Some(tracks), false) = (&s.said, self.id.get(), self.said_shown.replace(s.said.is_some())) {
            eprintln!("veyq: map view said: {}", said.msg);
            self.toasts.add_toast(said::notice_toast(said));
            bridge::send(Call::TracksSeen { tracks });
        }
        let tracks = s.tracks.clone().unwrap_or_default();
        let has_map = self.map.borrow().is_some();
        self.empty.set_visible(s.tracks.as_ref().is_some_and(Vec::is_empty));
        self.empty.set_label(&if s.can_add && !s.offline { words::track_empty_hint() } else { words::track_empty() });
        self.list.set_visible(!tracks.is_empty());
        let shapes: Vec<String> = tracks.iter().map(|t| shape(t, s)).collect();
        let changed = self.drawn.borrow().as_ref() != Some(&shapes);
        eprintln!("veyq: tracks shown: {} tracks ({}), map {has_map}, add {}, edit {}, offline {}, checking {}, asking {}",
            tracks.len(), tracks.iter().map(|t| format!("{} {:?}{}{} {}", t.media_id, t.status, if t.shown { "" } else { " hidden" },
                if t.selected { " selected" } else { "" }, t.activity.as_deref().unwrap_or("-"))).collect::<Vec<_>>().join(", "),
            s.can_add, s.can_edit, s.offline, s.checking, s.asking.len());
        let ask = !s.asking.is_empty() && !self.asking.get();
        let snapshot = s.clone();
        drop(shown);
        if changed {
            while let Some(child) = self.list.first_child() {
                self.list.remove(&child);
            }
            for t in &tracks {
                self.list.append(&self.row(t, &snapshot));
            }
            *self.drawn.borrow_mut() = Some(shapes);
        }
        if ask {
            self.ask_labels(&snapshot);
        }
    }

    /// One track's row: its show switch, swatch, activity over its name, facts; its menu what the view offers.
    fn row(self: &Rc<Self>, t: &TrackShown, s: &TracksShown) -> adw::ActionRow {
        let name = name_of(t);
        let title = match activity_word(t.activity.as_deref()) {
            Some(a) => format!("<small>{}</small>\n{}", glib_escape(&a), glib_escape(&name)),
            None => glib_escape(&name),
        };
        let row = adw::ActionRow::builder().title(title).subtitle(glib_escape(&subtitle_of(t))).activatable(!t.blocked).build();
        row.set_widget_name(&format!("track:{}", t.media_id));
        if t.selected {
            row.add_css_class("accent");
        }
        let show = gtk::Switch::builder().active(t.shown).valign(gtk::Align::Center).sensitive(!t.blocked && t.status == TrackStatus::Ready).build();
        show.update_property(&[gtk::accessible::Property::Label(&words::track_show(&name))]);
        show.set_tooltip_text(Some(&words::track_show(&name)));
        show.set_widget_name(&format!("track-show:{}", t.media_id));
        let (weak, id) = (Rc::downgrade(self), t.media_id.clone());
        show.connect_active_notify(move |sw| {
            if let Some(tracks) = weak.upgrade().and_then(|i| i.id.get()) {
                eprintln!("veyq: map view: show {id} {}", sw.is_active());
                bridge::send(Call::TracksShow { tracks, media_id: id.clone(), shown: sw.is_active() });
            }
        });
        row.add_prefix(&show);
        row.add_prefix(&swatch(t.color, t.dash.as_ref()));
        let menu = gio::Menu::new();
        let group = gio::SimpleActionGroup::new();
        let entry = |action: &str, label: String, offered: bool, act: fn(&Rc<Inner>, &TrackShown)| {
            if !offered {
                return;
            }
            menu.append(Some(&label), Some(&format!("track.{action}")));
            let a = gio::SimpleAction::new(action, None);
            let (weak, id) = (Rc::downgrade(self), t.media_id.clone());
            a.connect_activate(move |_, _| {
                if let Some(i) = weak.upgrade()
                    && let Some(track) = i.track(&id)
                {
                    act(&i, &track);
                }
            });
            group.add_action(&a);
        };
        entry("edit", words::track_edit(), s.can_edit && !s.offline && !t.blocked, Inner::edit);
        entry("download", words::track_download(), s.can_download && !t.blocked, Inner::download);
        entry("remove", words::track_remove(), !s.offline && t.can_remove, Inner::remove);
        entry("report", words::report_action(), s.can_report && !s.offline, Inner::report);
        if menu.n_items() > 0 {
            let button = gtk::MenuButton::builder().icon_name("view-more-symbolic").menu_model(&menu).valign(gtk::Align::Center).tooltip_text(words::track_actions(&name)).build();
            button.add_css_class("flat");
            button.update_property(&[gtk::accessible::Property::Label(&words::track_actions(&name))]);
            button.set_widget_name(&format!("track-actions:{}", t.media_id));
            row.add_suffix(&button);
            row.insert_action_group("track", Some(&group));
        }
        row
    }

    /// "Add GPX track": GTK's open dialog for GPX files (several), their paths to the core; what it refused said.
    fn pick(self: &Rc<Self>) {
        let Some(tracks) = self.id.get() else { return };
        let (parent, toasts) = (self.window.clone(), self.toasts.clone());
        glib::spawn_future_local(async move {
            let filter = gtk::FileFilter::new();
            filter.set_name(Some(&words::track_add()));
            filter.add_suffix("gpx");
            filter.add_mime_type("application/gpx+xml");
            let filters = gio::ListStore::new::<gtk::FileFilter>();
            filters.append(&filter);
            let dialog = gtk::FileDialog::builder().title(words::track_add()).modal(true).filters(&filters).default_filter(&filter).build();
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
            eprintln!("veyq: track add: {} files", paths.len());
            match bridge::call(&Call::TracksAdd { tracks, paths }).await.map(|(a, _)| a) {
                Ok(Answer::TracksAdded { said }) => {
                    eprintln!("veyq: Tracks.Add: {} said", said.len());
                    for n in &said {
                        toasts.add_toast(said::notice_toast(n));
                    }
                }
                other => crate::core_ui::header::say(&toasts, other, "Tracks.Add"),
            }
        });
    }

    /// The activities a track may be given, as a drop-down's words (no activity first) and their tokens.
    fn activities(&self) -> (Vec<String>, Vec<Option<String>>) {
        let tokens: Vec<Option<String>> = std::iter::once(None)
            .chain(self.shown.borrow().as_ref().map(|s| s.activities.clone()).unwrap_or_default().into_iter().map(Some))
            .collect();
        let words = tokens.iter().map(|t| activity_word(t.as_deref()).unwrap_or_else(words::track_activity_none)).collect();
        (words, tokens)
    }

    /// A name field and an activity row for one track; the name's hint the name it falls back to.
    fn label_rows(&self, name: Option<&str>, fallback: Option<&str>, activity: Option<&str>) -> (adw::EntryRow, adw::ComboRow, Vec<Option<String>>) {
        let entry = adw::EntryRow::builder().title(words::track_name_label()).text(name.unwrap_or_default()).build();
        if let Some(f) = fallback {
            entry.set_tooltip_text(Some(f));
        }
        let (names, tokens) = self.activities();
        let refs: Vec<&str> = names.iter().map(String::as_str).collect();
        let combo = adw::ComboRow::builder().title(words::track_activity_label()).model(&gtk::StringList::new(&refs)).build();
        combo.set_selected(tokens.iter().position(|t| t.as_deref() == activity).unwrap_or(0) as u32);
        (entry, combo, tokens)
    }

    /// The asked files' labels (`TracksShown.asking`): a name and an activity each, then `Tracks.Confirm` — or
    /// `Tracks.Cancel`, which uploads nothing.
    fn ask_labels(self: &Rc<Self>, s: &TracksShown) {
        let Some(tracks) = self.id.get() else { return };
        self.asking.set(true);
        let many = s.asking.len() > 1;
        let column = gtk::Box::new(gtk::Orientation::Vertical, 12);
        let mut fields = Vec::new();
        for asked in &s.asking {
            let (entry, combo, tokens) = self.label_rows(None, asked.file_track_name.as_deref(), None);
            let group = adw::PreferencesGroup::builder().title(glib_escape(&asked.file_name)).build();
            group.add(&entry);
            group.add(&combo);
            column.append(&group);
            fields.push((entry, combo, tokens));
        }
        let ask = adw::AlertDialog::builder().heading(if many { words::track_add_title_many() } else { words::track_add_title() })
            .body(words::track_add_hint()).extra_child(&column).build();
        let confirm = if many { words::track_add_confirm_many() } else { words::track_add_confirm() };
        ask.add_responses(&[("cancel", &words::entry_cancel()), ("add", &confirm)]);
        ask.set_response_appearance("add", adw::ResponseAppearance::Suggested);
        ask.set_default_response(Some("add"));
        ask.set_close_response("cancel");
        let (weak, toasts) = (Rc::downgrade(self), self.toasts.clone());
        ask.connect_response(None, move |_, response| {
            if let Some(i) = weak.upgrade() {
                i.asking.set(false);
            }
            if response != "add" {
                eprintln!("veyq: map view: the labels cancelled");
                return bridge::send(Call::TracksCancel { tracks });
            }
            let labels: Vec<TrackLabelShown> = fields.iter().map(|(entry, combo, tokens)| {
                let name = entry.text().trim().to_owned();
                TrackLabelShown { name: (!name.is_empty()).then_some(name), activity: tokens.get(combo.selected() as usize).cloned().flatten() }
            }).collect();
            eprintln!("veyq: map view: {} labelled", labels.len());
            let toasts = toasts.clone();
            glib::spawn_future_local(async move {
                match bridge::call(&Call::TracksConfirm { tracks, labels }).await.map(|(a, _)| a) {
                    Ok(Answer::Queued { count, notice }) => {
                        eprintln!("veyq: Tracks.Confirm: {count} queued");
                        if let Some(n) = notice {
                            toasts.add_toast(said::notice_toast(&n));
                        }
                    }
                    other => crate::core_ui::header::say(&toasts, other, "Tracks.Confirm"),
                }
            });
        });
        ask.present(Some(&self.window));
    }

    /// Edit: the track's own name (empty: its file's again) and its activity, in an alert dialog.
    fn edit(self: &Rc<Self>, t: &TrackShown) {
        let Some(tracks) = self.id.get() else { return };
        let (entry, combo, tokens) = self.label_rows(t.own_name.as_deref(), t.file_track_name.as_deref(), t.activity.as_deref());
        entry.set_widget_name("track-name");
        combo.set_widget_name("track-activity");
        let rows = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
        rows.add_css_class("boxed-list");
        rows.append(&entry);
        rows.append(&combo);
        let ask = adw::AlertDialog::builder().heading(words::track_edit_title()).body(words::track_name_hint()).extra_child(&rows).build();
        ask.add_responses(&[("cancel", &words::entry_cancel()), ("save", &words::entry_save())]);
        ask.set_response_appearance("save", adw::ResponseAppearance::Suggested);
        ask.set_default_response(Some("save"));
        ask.set_close_response("cancel");
        entry.set_activates_default(true);
        let (media_id, toasts) = (t.media_id.clone(), self.toasts.clone());
        ask.connect_response(None, move |_, response| {
            if response != "save" {
                return;
            }
            let name = entry.text().trim().to_owned();
            let label = TrackLabelShown { name: (!name.is_empty()).then_some(name), activity: tokens.get(combo.selected() as usize).cloned().flatten() };
            let (media_id, toasts) = (media_id.clone(), toasts.clone());
            eprintln!("veyq: map view: edit {media_id}");
            glib::spawn_future_local(async move {
                let answered = bridge::call(&Call::TracksEdit { tracks, media_id, label }).await;
                crate::core_ui::header::say(&toasts, answered.map(|(a, _)| a), "Tracks.Edit");
            });
        });
        ask.present(Some(&self.window));
    }

    /// Download GPX: GTK's save dialog proposes the file's name, the core writes it there, the file page's toast says how far.
    fn download(self: &Rc<Self>, t: &TrackShown) {
        let Some(tracks) = self.id.get() else { return };
        let name = t.file_name.clone().unwrap_or_else(|| name_of(t));
        let (parent, toasts, media_id) = (self.window.clone(), self.toasts.clone(), t.media_id.clone());
        glib::spawn_future_local(async move {
            let failed = || said::word_toast("track_download_failed", &words::track_download_failed());
            let dialog = gtk::FileDialog::builder().title(words::track_download()).initial_name(name.as_str()).modal(true).build();
            let chosen = match dialog.save_future(Some(&parent)).await {
                Ok(chosen) => chosen,
                Err(e) if e.matches(gtk::DialogError::Dismissed) || e.matches(gtk::DialogError::Cancelled) => return,
                Err(e) => {
                    eprintln!("veyq: the save dialog failed: {e}");
                    return toasts.add_toast(failed());
                }
            };
            let Some(path) = chosen.path().and_then(|p| p.to_str().map(str::to_owned)) else { return toasts.add_toast(failed()) };
            let save = match bridge::call(&Call::TracksDownload { tracks, media_id, path: path.clone() }).await.map(|(a, _)| a) {
                Ok(Answer::Saving { save }) => save,
                other => {
                    eprintln!("veyq: Tracks.Download answered {other:?}");
                    return toasts.add_toast(failed());
                }
            };
            eprintln!("veyq: saving a track to {path} (save {save})");
            crate::core_ui::saving::saving(save, &toasts).await;
        });
    }

    /// Remove: the gallery's trash; its Undo is the page's toast (`GalleryShown.undo`).
    fn remove(self: &Rc<Self>, t: &TrackShown) {
        let Some(tracks) = self.id.get() else { return };
        let (toasts, media_id) = (self.toasts.clone(), t.media_id.clone());
        eprintln!("veyq: map view: remove {media_id}");
        glib::spawn_future_local(async move {
            crate::core_ui::header::say(&toasts, bridge::call(&Call::TracksRemove { tracks, media_id }).await.map(|(a, _)| a), "Tracks.Remove");
        });
    }

    /// Report: the report sheet the core opens for the track, an item.
    fn report(self: &Rc<Self>, t: &TrackShown) {
        let (Some(tracks), Some(node)) = (self.id.get(), self.shown.borrow().as_ref().map(|s| s.node.clone())) else { return };
        crate::core_ui::report::open_by(&self.window, &node, Call::TracksReport { tracks, media_id: t.media_id.clone() });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bridge::wire::{Notice, NoticeArg};

    fn track() -> TrackShown {
        TrackShown {
            media_id: "t1".into(),
            name: None,
            number: 2,
            file_track_name: Some("Spree".into()),
            own_name: None,
            activity: Some("mountain-biking".into()),
            dash: Some(vec![10, 5, 2, 5]),
            file_name: Some("spree.gpx".into()),
            color: 0xFF_E5_39_35,
            status: TrackStatus::Ready,
            facts: vec![
                TrackFactShown { distance: Some(3.24), kilometres: true, words: None, distance_text: Some("3.2 km".into()) },
                TrackFactShown { distance: None, kilometres: false, distance_text: None, words: Some(Notice { msg: "track_duration_minutes".into(), args: vec![NoticeArg::Int { value: 38 }], kind: crate::bridge::wire::NoticeKind::Held }) },
            ],
            shown: true,
            selected: false,
            blocked: false,
            can_remove: true,
        }
    }

    /// A track's line: its distance as the core writes it, then the core's phrases; its read's state
    /// instead while it reads or did not; the operator's block over all. An untitled track is said by its number, and its
    /// activity by its word (a token's dash read as the key's underscore).
    #[test]
    fn a_tracks_row_says_its_facts_its_read_and_its_activity() {
        let t = track();
        assert_eq!(subtitle_of(&t), said::joined(&["3.2 km".to_string(), words::track_duration_minutes(38)]));
        assert_eq!(subtitle_of(&TrackShown { status: TrackStatus::Loading, ..t.clone() }), words::track_loading());
        assert_eq!(subtitle_of(&TrackShown { status: TrackStatus::Failed, ..t.clone() }), words::track_unreadable());
        assert_eq!(subtitle_of(&TrackShown { blocked: true, ..t.clone() }), words::memory_blocked());
        assert_eq!(fact_text(&TrackFactShown { distance: Some(812.4), kilometres: false, words: None, distance_text: Some("812 m".into()) }), Some("812 m".into()), "the core's distance, as it came");
        assert_eq!(name_of(&t), words::track_unnamed(2));
        assert_eq!(activity_word(t.activity.as_deref()), Some(words::track_activity_mountain_biking()));
        assert_eq!(activity_word(None), None);
    }
}
