//! A Memory's lightbox (core's `Lightbox`; ADR 0123's Consequences, the Linux app), GNOME's way, as Loupe shows a
//! picture: an `AdwNavigationPage` pushed over the gallery, the medium over black, its header bar with the position as its
//! title and the medium's name under it, Previous and Next at its start, Slideshow, Details, Save and Move to trash at its
//! end — each only where the core offers it. A picture zooms in its own scroll (Ctrl+wheel, a pinch, `+`, `-`, `0`); ←
//! and → and a swipe page; Escape goes back. A video shows its poster with Play — it never plays by itself — and plays
//! through the core's reader (`Medium.Reader`, [crate::player]); what the system cannot decode says so, Save stays. The
//! details are an `AdwDialog` of rows (`Lightbox.Details`); Save writes the original where GTK's save dialog says
//! (`Medium.Save`, the file page's progress toast with Cancel). The slideshow's clock is the core's.
//!
//! While the account's player holds a queue its compact controls sit in the header bar after Previous and Next — the
//! track's title, Previous, Play or Pause, Next (`LightboxShown.music`, the `Player.*` calls; ADR 0142 decision 5) — and
//! a video playing here interrupts the music until it stops (`Lightbox.Video`, decision 13).
//!
//! Every state is the core's (`Lightbox.Open`, `Watched.Lightbox`); every action one call. Nothing decrypted is written to
//! show it (ADR 0047): the picture is decoded in memory, a video read in ranges.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::{gdk, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::LightboxShown, wire::SlideshowState, wire::Update, wire::Watched};
use crate::home::glib_escape;
use crate::player::Playing;
use crate::{said, words};

/// The longest side a lightbox's picture is held at (the file page's `FileView.IMAGE_EDGE`).
const PICTURE_EDGE: i32 = 4096;

/// The most a picture is zoomed in, and each step's factor.
const ZOOM_MAX: f64 = 8.0;
const ZOOM_STEP: f64 = 1.25;

/// What a key does to the lightbox.
type Step = Box<dyn Fn(&Rc<Inner>)>;

/// A lightbox on screen: opened in the core when made, closed — and its page gone — when dropped or when its page leaves.
pub struct Lightbox {
    inner: Rc<Inner>,
}

struct Inner {
    id: Cell<Option<i64>>,
    closed: Cell<bool>,
    page: adw::NavigationPage,
    title: adw::WindowTitle,
    previous: gtk::Button,
    next: gtk::Button,
    slideshow: gtk::Button,
    details: gtk::Button,
    save: gtk::Button,
    trash: gtk::Button,
    /// The music's compact controls: shown while the player holds a queue.
    music: gtk::Box,
    music_title: gtk::Label,
    music_play: gtk::Button,
    /// Where the medium shows: replaced when the medium or what it shows changes.
    stage: gtk::Box,
    shown: RefCell<Option<LightboxShown>>,
    /// What the stage shows now: the medium's id, its picture's blob, and whether its player is up.
    drawn: RefCell<Option<(String, Option<String>, bool)>>,
    /// The picture shown and its scroll, while a picture shows; its zoom.
    picture: RefCell<Option<(gtk::ScrolledWindow, gtk::Picture)>>,
    zoom: Cell<f64>,
    playing: RefCell<Option<Playing>>,
    watch: RefCell<Option<Watch>>,
    nav: adw::NavigationView,
    popped: RefCell<Option<glib::SignalHandlerId>>,
    window: gtk::Window,
    toasts: adw::ToastOverlay,
}

impl Lightbox {
    /// The lightbox over gallery [gallery] at row [index], pushed onto [nav] over [window].
    pub fn open(gallery: i64, index: i32, window: &gtk::Window, toasts: &adw::ToastOverlay, nav: &adw::NavigationView) -> Self {
        style_once();
        let title = adw::WindowTitle::new("", "");
        let icon = |name: &str, label: &str| {
            let b = gtk::Button::builder().icon_name(name).tooltip_text(label).build();
            b.update_property(&[gtk::accessible::Property::Label(label)]);
            b
        };
        let previous = icon("go-previous-symbolic", &words::memory_previous());
        let next = icon("go-next-symbolic", &words::memory_next());
        let slideshow = icon("media-playback-start-symbolic", &words::memory_slideshow_start());
        let details = icon("dialog-information-symbolic", &words::memory_details());
        let save = icon("folder-download-symbolic", &words::memory_download());
        let trash = icon("user-trash-symbolic", &words::memory_trash());
        let music_previous = icon("media-skip-backward-symbolic", &words::music_previous());
        let music_play = icon("media-playback-pause-symbolic", &words::music_pause());
        let music_next = icon("media-skip-forward-symbolic", &words::music_next());
        music_previous.connect_clicked(|_| bridge::send(Call::PlayerPrevious));
        music_next.connect_clicked(|_| bridge::send(Call::PlayerNext));
        let music_title = gtk::Label::builder().ellipsize(gtk::pango::EllipsizeMode::End).max_width_chars(24).build();
        music_title.add_css_class("dim-label");
        let music = gtk::Box::builder().spacing(6).visible(false).margin_start(12).build();
        music.append(&gtk::Image::from_icon_name("audio-x-generic-symbolic"));
        music.append(&music_title);
        for b in [&music_previous, &music_play, &music_next] {
            b.add_css_class("flat");
            music.append(b);
        }
        let bar = adw::HeaderBar::new();
        bar.set_title_widget(Some(&title));
        bar.pack_start(&previous);
        bar.pack_start(&next);
        bar.pack_start(&music);
        for b in [&trash, &save, &details, &slideshow] {
            bar.pack_end(b);
        }
        let stage = gtk::Box::builder().orientation(gtk::Orientation::Vertical).hexpand(true).vexpand(true).build();
        stage.add_css_class("veyq-lightbox");
        let view = adw::ToolbarView::new();
        view.add_top_bar(&bar);
        view.set_content(Some(&stage));
        let page = adw::NavigationPage::builder().child(&view).title(words::memory_details()).can_pop(true).build();
        let inner = Rc::new(Inner {
            id: Cell::new(None),
            closed: Cell::new(false),
            page,
            title,
            previous,
            next,
            slideshow,
            details,
            save,
            trash,
            music,
            music_title,
            music_play,
            stage,
            shown: RefCell::new(None),
            drawn: RefCell::new(None),
            picture: RefCell::new(None),
            zoom: Cell::new(1.0),
            playing: RefCell::new(None),
            watch: RefCell::new(None),
            nav: nav.clone(),
            popped: RefCell::new(None),
            window: window.clone(),
            toasts: toasts.clone(),
        });
        Inner::connect(&inner);
        nav.push(&inner.page);
        let weak = Rc::downgrade(&inner);
        glib::spawn_future_local(async move {
            let Ok((Answer::LightboxOpened { lightbox }, _)) = bridge::call(&Call::LightboxOpen { gallery, index }).await else {
                eprintln!("veyq: the lightbox did not open in the core");
                return;
            };
            let Some(inner) = weak.upgrade().filter(|i| !i.closed.get()) else {
                bridge::send(Call::LightboxClose { lightbox });
                return;
            };
            inner.id.set(Some(lightbox));
            let Some((watch, updates)) = bridge::watch(&Watched::Lightbox { lightbox }) else { return };
            *inner.watch.borrow_mut() = Some(watch);
            drop(inner);
            follow(weak, updates).await;
        });
        Self { inner }
    }
}

impl Drop for Lightbox {
    fn drop(&mut self) {
        self.inner.close();
        if let Some(id) = self.inner.popped.borrow_mut().take() {
            self.inner.nav.disconnect(id);
        }
        // the page goes with it (its gallery's page left, or another lightbox opened)
        if self.inner.nav.visible_page().as_ref() == Some(&self.inner.page) {
            self.inner.nav.pop();
        }
    }
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Lightbox { shown, .. }) => {
                if let Some(s) = &shown {
                    eprintln!("veyq: lightbox shown: {} of {}, {}, playing {}, slideshow {:?}, save {}, trash {}", s.index + 1, s.count,
                        s.row.as_ref().map_or("-", |r| r.media_id.as_str()), s.playing, s.slideshow, s.download_name.is_some(), s.can_trash);
                }
                let gone = shown.as_ref().is_none_or(|s| s.row.is_none());
                *inner.shown.borrow_mut() = shown;
                if gone {
                    // closed by the core (its gallery or tenant), or nothing left to show: back to the gallery
                    inner.close();
                    if inner.nav.visible_page().as_ref() == Some(&inner.page) {
                        inner.nav.pop();
                    }
                    return;
                }
                inner.draw();
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a lightbox update did not read: {:?}", e.0),
        }
    }
}

impl Inner {
    fn connect(this: &Rc<Self>) {
        let call = |this: &Rc<Inner>, f: fn(&Rc<Inner>)| {
            let weak = Rc::downgrade(this);
            move |_: &gtk::Button| {
                if let Some(i) = weak.upgrade() {
                    f(&i);
                }
            }
        };
        this.previous.connect_clicked(call(this, |i| i.step(-1)));
        this.next.connect_clicked(call(this, |i| i.step(1)));
        this.slideshow.connect_clicked(call(this, |i| {
            let on = i.shown.borrow().as_ref().is_none_or(|s| s.slideshow != Some(SlideshowState::Playing));
            if let Some(lightbox) = i.id.get() {
                bridge::send(Call::LightboxSlideshow { lightbox, on });
            }
        }));
        this.music_play.connect_clicked(call(this, |i| {
            let playing = i.shown.borrow().as_ref().and_then(|s| s.music.as_ref()).is_some_and(|m| m.playing);
            bridge::send(if playing { Call::PlayerPause } else { Call::PlayerResume });
        }));
        this.details.connect_clicked(call(this, Inner::show_details));
        this.save.connect_clicked(call(this, Inner::save));
        this.trash.connect_clicked(call(this, |i| {
            let Some(lightbox) = i.id.get() else { return };
            let toasts = i.toasts.clone();
            glib::spawn_future_local(async move {
                match bridge::call(&Call::LightboxTrash { lightbox }).await.map(|(a, _)| a) {
                    // the Undo is the gallery's toast, held while its state offers it
                    Ok(Answer::Trashed { undoable, .. }) => eprintln!("veyq: lightbox trash: Trashed, undo {undoable}"),
                    other => crate::header::say(&toasts, other, "Lightbox.Trash"),
                }
            });
        }));
        // the page left (back, Escape, a swipe): the lightbox closes in the core
        let weak = Rc::downgrade(this);
        let id = this.nav.connect_popped(move |_, page| {
            if let Some(i) = weak.upgrade()
                && page == &i.page
            {
                i.close();
            }
        });
        *this.popped.borrow_mut() = Some(id);
        // keys: ← → page, + - 0 zoom, Escape back
        let shortcuts = gtk::ShortcutController::new();
        shortcuts.set_scope(gtk::ShortcutScope::Managed);
        // before the picture's scroll, which would take ← and → for itself (measured: ← did not page in the bubble phase)
        shortcuts.set_propagation_phase(gtk::PropagationPhase::Capture);
        let add = |trigger: &str, f: Step| {
            let weak = Rc::downgrade(this);
            let action = gtk::CallbackAction::new(move |_, _| {
                if let Some(i) = weak.upgrade() {
                    f(&i);
                }
                glib::Propagation::Stop
            });
            shortcuts.add_shortcut(gtk::Shortcut::new(gtk::ShortcutTrigger::parse_string(trigger), Some(action)));
        };
        add("Left", Box::new(|i| i.step(-1)));
        add("Right", Box::new(|i| i.step(1)));
        add("plus|equal|KP_Add", Box::new(|i| i.zoom_by(ZOOM_STEP)));
        add("minus|KP_Subtract", Box::new(|i| i.zoom_by(1.0 / ZOOM_STEP)));
        add("0|KP_0", Box::new(|i| i.set_zoom(1.0)));
        add("Escape", Box::new(|i| {
            i.nav.pop();
        }));
        this.page.add_controller(shortcuts);
    }

    /// Pages by [by] (−1 back, 1 on) where there is a medium there.
    fn step(&self, by: i32) {
        let Some(lightbox) = self.id.get() else { return };
        let Some((index, count)) = self.shown.borrow().as_ref().map(|s| (s.index, s.count)) else { return };
        let to = index + by;
        if (0..count).contains(&to) {
            bridge::send(Call::LightboxGo { lightbox, index: to });
        }
    }

    /// Closes it in the core once: its player stopped, its watch ended.
    fn close(&self) {
        if self.closed.replace(true) {
            return;
        }
        self.stop_player();
        self.watch.borrow_mut().take();
        if let Some(lightbox) = self.id.get() {
            eprintln!("veyq: lightbox closed");
            bridge::send(Call::LightboxClose { lightbox });
        }
    }

    fn draw(self: &Rc<Self>) {
        let shown = self.shown.borrow();
        let Some(s) = shown.as_ref() else { return };
        let Some(row) = s.row.as_ref() else { return };
        let position = s.position.as_ref().map(said::notice).unwrap_or_default();
        let name = row.name.clone().unwrap_or_else(|| words::memory_unnamed(i64::from(s.index) + 1));
        self.title.set_title(&glib_escape(&position));
        self.title.set_subtitle(&glib_escape(&name));
        self.page.set_title(&name);
        self.previous.set_sensitive(s.index > 0);
        self.next.set_sensitive(s.index + 1 < s.count);
        let playing = s.slideshow == Some(SlideshowState::Playing);
        self.slideshow.set_visible(s.count > 1);
        self.slideshow.set_icon_name(if playing { "media-playback-pause-symbolic" } else { "media-playback-start-symbolic" });
        let label = if playing { words::memory_slideshow_pause() } else { words::memory_slideshow_start() };
        self.slideshow.set_tooltip_text(Some(&label));
        self.slideshow.update_property(&[gtk::accessible::Property::Label(&label)]);
        self.save.set_visible(s.download_name.is_some());
        self.trash.set_visible(s.can_trash);
        self.music.set_visible(s.music.is_some());
        if let Some(m) = &s.music {
            self.music_title.set_label(&m.title);
            let (icon, label) =
                if m.playing { ("media-playback-pause-symbolic", words::music_pause()) } else { ("media-playback-start-symbolic", words::music_resume()) };
            self.music_play.set_icon_name(icon);
            self.music_play.set_tooltip_text(Some(&label));
            self.music_play.update_property(&[gtk::accessible::Property::Label(&label)]);
        }
        // the stage: drawn anew when the medium, its picture or its player changed
        let player_up = self.playing.borrow().is_some();
        if player_up && !s.playing {
            // the core let the reader go (a slideshow started, paged away): the player goes with it
            drop(shown);
            self.stop_player();
            *self.drawn.borrow_mut() = None;
            return self.draw();
        }
        let now = (row.media_id.clone(), row.picture.clone(), player_up);
        if self.drawn.borrow().as_ref() == Some(&now) {
            return;
        }
        *self.drawn.borrow_mut() = Some(now);
        if player_up {
            return;
        }
        let (blocked, video, picture, media_id) = (row.blocked, row.video, row.picture.clone(), row.media_id.clone());
        let said = row.said.clone();
        drop(shown);
        self.set_zoom(1.0);
        if blocked {
            return self.replace(&message(&said.as_deref().map(said::key).unwrap_or_else(words::memory_blocked), "action-unavailable-symbolic"));
        }
        let column = gtk::Box::new(gtk::Orientation::Vertical, 12);
        column.set_vexpand(true);
        if picture.is_some() {
            let pic = gtk::Picture::builder().can_shrink(true).content_fit(gtk::ContentFit::Contain).hexpand(true).vexpand(true).build();
            pic.update_property(&[gtk::accessible::Property::Label(&name)]);
            let scroll = gtk::ScrolledWindow::builder().child(&pic).hexpand(true).vexpand(true).build();
            self.zoomable(&scroll);
            column.append(&scroll);
            *self.picture.borrow_mut() = Some((scroll, pic.clone()));
            self.load(&media_id, pic);
        } else {
            *self.picture.borrow_mut() = None;
            let icon = gtk::Image::builder().icon_name(if video { "video-x-generic-symbolic" } else { "image-x-generic-symbolic" }).pixel_size(96).vexpand(true).build();
            icon.add_css_class("dim-label");
            column.append(&icon);
        }
        if video {
            let play = gtk::Button::builder().label(words::memory_play()).halign(gtk::Align::Center).margin_bottom(18).build();
            play.add_css_class("pill");
            play.add_css_class("suggested-action");
            let weak = Rc::downgrade(self);
            play.connect_clicked(move |_| {
                if let Some(i) = weak.upgrade() {
                    i.play();
                }
            });
            column.append(&play);
        }
        self.replace(&column);
    }

    /// The medium's picture (`Lightbox.Picture`: its preview, else its original), decoded in memory into [into].
    fn load(self: &Rc<Self>, media_id: &str, into: gtk::Picture) {
        let Some(lightbox) = self.id.get() else { return };
        let (weak, media_id) = (Rc::downgrade(self), media_id.to_owned());
        glib::spawn_future_local(async move {
            let answered = bridge::call(&Call::LightboxPicture { lightbox, media_id: media_id.clone() }).await;
            let Some(i) = weak.upgrade() else { return };
            // another medium shows meanwhile: this answer is not shown
            if i.drawn.borrow().as_ref().map(|d| &d.0) != Some(&media_id) {
                return;
            }
            match answered {
                Ok((Answer::Bytes { .. }, Some(bytes))) => match gdk::Texture::from_bytes(&glib::Bytes::from_owned(bytes)) {
                    Ok(t) => {
                        let held = crate::file_page::within_edge(&t, PICTURE_EDGE);
                        eprintln!("veyq: lightbox picture {media_id}: {}×{}", held.width(), held.height());
                        into.set_paintable(Some(&held));
                    }
                    Err(e) => eprintln!("veyq: the lightbox's picture did not decode: {e}"),
                },
                other => eprintln!("veyq: Lightbox.Picture answered {:?}", other.map(|(a, _)| a)),
            }
        });
    }

    /// Plays the video through a reader the core opens for this medium (`Medium.Reader`); what the system cannot decode
    /// is said, and Save stays.
    fn play(self: &Rc<Self>) {
        let Some(lightbox) = self.id.get() else { return };
        let weak = Rc::downgrade(self);
        let asked = self.drawn.borrow().as_ref().map(|d| d.0.clone());
        glib::spawn_future_local(async move {
            let answered = bridge::call(&Call::MediumReader { lightbox }).await.map(|(a, _)| a);
            let i = weak.upgrade().filter(|i| i.drawn.borrow().as_ref().map(|d| d.0.clone()) == asked);
            let (reader, size) = match (answered, i.as_ref()) {
                (Ok(Answer::Reader { reader, size, media_type }), Some(_)) => {
                    eprintln!("veyq: lightbox medium: reader {reader}, {size} B, {}", media_type.as_deref().unwrap_or("no type"));
                    (reader, size)
                }
                (Ok(Answer::Reader { reader, .. }), None) => return bridge::send(Call::ReaderClose { reader }),
                (other, _) => return eprintln!("veyq: Medium.Reader answered {other:?}"),
            };
            let Some(i) = i else { return };
            let weak = Rc::downgrade(&i);
            let label = i.title.subtitle().to_string();
            let playing = Playing::new(reader, size, &label, true, move || {
                if let Some(i) = weak.upgrade() {
                    i.stop_player();
                    i.replace(&message(&words::memory_video_failed(), "video-x-generic-symbolic"));
                }
            });
            // the music pauses while the video plays and plays again when it stops (ADR 0142 decision 13)
            playing.on_playing(move |on| {
                eprintln!("veyq: lightbox video {}", if on { "playing" } else { "stopped" });
                bridge::send(Call::LightboxVideo { lightbox, playing: on });
            });
            *i.picture.borrow_mut() = None;
            i.replace(&playing.video.clone());
            *i.playing.borrow_mut() = Some(playing);
            if let Some(d) = i.drawn.borrow_mut().as_mut() {
                d.2 = true;
            }
        });
    }

    /// The player let go (its stream closed, the core's reader with it) and the core told (`Lightbox.Stop`).
    fn stop_player(&self) {
        if let Some(p) = self.playing.borrow_mut().take() {
            p.stop();
            if let Some(lightbox) = self.id.get()
                && !self.closed.get()
            {
                bridge::send(Call::LightboxStop { lightbox });
            }
        }
    }

    fn replace(&self, widget: &impl IsA<gtk::Widget>) {
        while let Some(child) = self.stage.first_child() {
            self.stage.remove(&child);
        }
        self.stage.append(widget);
    }

    /// Ctrl+wheel and a pinch zoom the picture in [scroll]; a swipe pages while it is not zoomed in.
    fn zoomable(self: &Rc<Self>, scroll: &gtk::ScrolledWindow) {
        let wheel = gtk::EventControllerScroll::new(gtk::EventControllerScrollFlags::VERTICAL);
        let weak = Rc::downgrade(self);
        wheel.connect_scroll(move |c, _, dy| {
            if !c.current_event_state().contains(gdk::ModifierType::CONTROL_MASK) {
                return glib::Propagation::Proceed;
            }
            if let Some(i) = weak.upgrade() {
                i.zoom_by(if dy < 0.0 { ZOOM_STEP } else { 1.0 / ZOOM_STEP });
            }
            glib::Propagation::Stop
        });
        scroll.add_controller(wheel);
        let pinch = gtk::GestureZoom::new();
        let start = Rc::new(Cell::new(1.0));
        let (weak, from) = (Rc::downgrade(self), start.clone());
        pinch.connect_begin(move |_, _| {
            if let Some(i) = weak.upgrade() {
                from.set(i.zoom.get());
            }
        });
        let weak = Rc::downgrade(self);
        pinch.connect_scale_changed(move |_, scale| {
            if let Some(i) = weak.upgrade() {
                i.set_zoom(start.get() * scale);
            }
        });
        scroll.add_controller(pinch);
        let swipe = gtk::GestureSwipe::new();
        swipe.set_touch_only(true);
        let weak = Rc::downgrade(self);
        swipe.connect_swipe(move |_, vx, vy| {
            if let Some(i) = weak.upgrade()
                && i.zoom.get() <= 1.0
                && vx.abs() > 400.0
                && vx.abs() > vy.abs()
            {
                i.step(if vx < 0.0 { 1 } else { -1 });
            }
        });
        scroll.add_controller(swipe);
    }

    fn zoom_by(&self, factor: f64) {
        self.set_zoom(self.zoom.get() * factor);
    }

    /// The picture drawn at [zoom] times the view's size (1: fitted to it), its centre kept where it was.
    fn set_zoom(&self, zoom: f64) {
        let zoom = zoom.clamp(1.0, ZOOM_MAX);
        let before = self.zoom.replace(zoom);
        let held = self.picture.borrow();
        let Some((scroll, pic)) = held.as_ref() else { return };
        if (zoom - 1.0).abs() < 1e-3 {
            pic.set_size_request(-1, -1);
            eprintln!("veyq: lightbox zoom 1");
            return;
        }
        let (w, h) = (scroll.width().max(1), scroll.height().max(1));
        let (ha, va) = (scroll.hadjustment(), scroll.vadjustment());
        // the point at the view's centre, as a share of the picture before
        let share = |a: &gtk::Adjustment| if a.upper() > 0.0 { (a.value() + a.page_size() / 2.0) / a.upper() } else { 0.5 };
        let (sx, sy) = (share(&ha), share(&va));
        pic.set_size_request((f64::from(w) * zoom) as i32, (f64::from(h) * zoom) as i32);
        eprintln!("veyq: lightbox zoom {zoom:.2} (from {before:.2})");
        // put the centre back once the scroll took the new size (a value set during the allocation is not taken)
        let (ha, va) = (ha.clone(), va.clone());
        glib::idle_add_local_once(move || {
            ha.set_value(sx * ha.upper() - ha.page_size() / 2.0);
            va.set_value(sy * va.upper() - va.page_size() / 2.0);
        });
    }

    /// The details (`Lightbox.Details`) in a dialog of rows, "Added by" where the core places it; a photo with a position
    /// shows it on a small map (`Lightbox.Map`, ADR 0143 decision 7) and its place's name once the geocoder named it
    /// (`Lightbox.Place`, sealed once ever; the dialog opens at once and the row joins above the coordinate).
    fn show_details(self: &Rc<Self>) {
        let Some(lightbox) = self.id.get() else { return };
        let Some(media_id) = self.shown.borrow().as_ref().and_then(|s| s.row.as_ref().map(|r| r.media_id.clone())) else { return };
        let window = self.window.clone();
        let me = Rc::downgrade(self);
        let me_profile = me.clone();
        glib::spawn_future_local(async move {
            let (rows, uploader, at, has_map, profile) = match bridge::call(&Call::LightboxDetails { lightbox, media_id: media_id.clone() }).await.map(|(a, _)| a) {
                Ok(Answer::MediumDetails { rows, uploader, uploader_at, map, profile, .. }) => (rows, uploader, uploader_at, map, profile),
                other => return eprintln!("veyq: Lightbox.Details answered {other:?}"),
            };
            let group = adw::PreferencesGroup::new();
            let mut lines: Vec<(String, String)> = rows.iter().map(|r| {
                let value = if let Some(w) = &r.words {
                    said::notice(w)
                } else if let Some(size) = r.size {
                    said::size(size)
                } else if let Some(moment) = r.moment {
                    said::moment(moment)
                } else {
                    r.text.clone().unwrap_or_default()
                };
                (said::key(&r.label), value)
            }).collect();
            if let Some(u) = &uploader {
                lines.insert((at.max(0) as usize).min(lines.len()), (words::memory_field_uploaded_by(), said::piece(u)));
            }
            eprintln!("veyq: lightbox details: {} rows{}", lines.len(), if uploader.is_some() { ", the uploader among them" } else { "" });
            if lines.is_empty() {
                group.add(&adw::ActionRow::builder().title(glib_escape(&words::memory_no_detail())).build());
            }
            let place_label = words::memory_field_place();
            let asks_place = has_map && !lines.iter().any(|(l, _)| *l == place_label);
            // the place's row stands where it joins (above the coordinate), hidden until the geocoder named it
            let place_row = adw::ActionRow::builder().title(glib_escape(&place_label)).subtitle_selectable(true).visible(false).build();
            place_row.add_css_class("property");
            let location = words::memory_field_location();
            let (taken, added_by) = (words::memory_field_captured(), words::memory_field_uploaded_by());
            let mut taken_row = None;
            let mut placed = !asks_place;
            for (label, value) in lines {
                if !placed && label == location {
                    group.add(&place_row);
                    placed = true;
                }
                let row = adw::ActionRow::builder().title(glib_escape(&label)).subtitle(glib_escape(&value)).subtitle_selectable(true).build();
                row.add_css_class("property");
                if label == taken && taken_row.is_none() {
                    taken_row = Some(row.clone());
                }
                // a current member's name opens their profile (ADR 0144 decision 11); a former one's, a guest's nothing
                if label == added_by
                    && let Some(user_id) = profile.clone()
                {
                    row.set_subtitle_selectable(false);
                    row.set_activatable(true);
                    row.set_widget_name("details-uploader");
                    row.set_tooltip_text(Some(&words::people_open(&value)));
                    row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
                    let me_profile = me_profile.clone();
                    row.connect_activated(move |r| {
                        eprintln!("veyq: uploader's profile opened");
                        if let Some(d) = r.ancestor(adw::Dialog::static_type()).and_downcast::<adw::Dialog>() {
                            d.close();
                        }
                        // the lightbox leaves for the profile, as its back would
                        if let Some(i) = me_profile.upgrade() {
                            i.nav.pop();
                        }
                        bridge::send(Call::ShellOpenPerson { user_id: user_id.clone() });
                    });
                }
                group.add(&row);
            }
            if !placed {
                group.add(&place_row);
            }
            let page = adw::PreferencesPage::new();
            let map = if has_map {
                match bridge::call(&Call::LightboxMap { lightbox, media_id: media_id.clone() }).await.map(|(a, _)| a) {
                    Ok(Answer::MapOpened { map }) => {
                        let m = crate::raster_map::RasterMap::open(map, 200, true, &window);
                        let map_group = adw::PreferencesGroup::new();
                        map_group.add(m.widget());
                        page.add(&map_group);
                        Some(m)
                    }
                    other => {
                        eprintln!("veyq: Lightbox.Map answered {other:?}");
                        None
                    }
                }
            } else {
                None
            };
            page.add(&group);
            if asks_place {
                let (row, place_media) = (place_row.downgrade(), media_id.clone());
                glib::spawn_future_local(async move {
                    match bridge::call(&Call::LightboxPlace { lightbox, media_id: place_media }).await.map(|(a, _)| a) {
                        Ok(Answer::PlaceResolved { name: Some(name) }) => {
                            eprintln!("veyq: lightbox details: the place's name resolved");
                            if let Some(row) = row.upgrade() {
                                row.set_subtitle(&glib_escape(&name));
                                row.set_visible(true);
                            }
                        }
                        other => eprintln!("veyq: Lightbox.Place answered {other:?}"),
                    }
                });
            }
            let view = adw::ToolbarView::new();
            view.add_top_bar(&adw::HeaderBar::new());
            view.set_content(Some(&page));
            let dialog = adw::Dialog::builder().title(words::memory_details()).child(&view).content_width(420).content_height(560).build();
            // the photo's map held while the dialog shows, closed in the core with it
            let held = RefCell::new(map);
            dialog.connect_closed(move |_| {
                held.borrow_mut().take();
            });
            // the capture time's editor on the *Taken* row, where the core offers it (ADR 0144 decision 3); a save shows
            // the details anew
            if let Some(row) = &taken_row {
                let again = me.clone();
                crate::curation::on_taken_row(lightbox, media_id.clone(), row, &dialog, &window, move || {
                    if let Some(i) = again.upgrade() {
                        i.show_details();
                    }
                });
            }
            dialog.present(Some(&window));
        });
    }

    /// Save: GTK's save dialog proposes the core's name, the core writes the original there (`Medium.Save`), and a toast
    /// says how far it is with Cancel, then how it ended; the save goes on when the lightbox closes.
    fn save(self: &Rc<Self>) {
        let Some(lightbox) = self.id.get() else { return };
        let Some(name) = self.shown.borrow().as_ref().and_then(|s| s.download_name.clone()) else { return };
        let (parent, toasts) = (self.window.clone(), self.toasts.clone());
        glib::spawn_future_local(async move {
            let failed = || adw::Toast::new(&glib_escape(&words::download_failed(&name)));
            let dialog = gtk::FileDialog::builder().title(words::memory_download()).initial_name(name.as_str()).modal(true).build();
            if let Some(pictures) = glib::user_special_dir(glib::UserDirectory::Pictures).filter(|d| d.is_dir()) {
                dialog.set_initial_folder(Some(&gtk::gio::File::for_path(pictures)));
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
                eprintln!("veyq: the save dialog chose no local UTF-8 path");
                return toasts.add_toast(failed());
            };
            let save = match bridge::call(&Call::MediumSave { lightbox, path: path.clone() }).await.map(|(a, _)| a) {
                Ok(Answer::Saving { save }) => save,
                other => {
                    eprintln!("veyq: Medium.Save answered {other:?}");
                    return toasts.add_toast(failed());
                }
            };
            eprintln!("veyq: saving an original to {path} (save {save})");
            crate::file_page::saving(save, &toasts).await;
        });
    }
}

/// What shows in the medium's place: [text] under [icon].
fn message(text: &str, icon: &str) -> gtk::Widget {
    let page = adw::StatusPage::builder().icon_name(icon).title(glib_escape(text)).vexpand(true).build();
    page.add_css_class("compact");
    page.upcast()
}

/// The lightbox's stage over black, as a photo viewer shows a picture (Loupe's), once per display.
fn style_once() {
    thread_local!(static DONE: Cell<bool> = const { Cell::new(false) });
    if DONE.with(|d| d.replace(true)) {
        return;
    }
    let Some(display) = gdk::Display::default() else { return };
    let css = gtk::CssProvider::new();
    css.load_from_string(".veyq-lightbox { background-color: black; color: white; }");
    gtk::style_context_add_provider_for_display(&display, &css, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
}
