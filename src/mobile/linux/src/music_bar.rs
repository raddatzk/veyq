//! The music bar (ADR 0142 decision 5), GNOME's way — the player bar of GNOME Music along the window's bottom edge, under
//! the sidebar and the content alike, revealed while the account's player holds a queue (`Watched.Player`:
//! `PlayerShown`): Previous, Play or Pause and Next at its start, the track's title over the node's name (a flat button
//! that opens the node, `Player.OpenNode`) and the position over the track's span (a scale that seeks when let go,
//! `Player.Seek`) with the time played and the span's length, and Close (`Player.Stop`) at its end. A queue none of whose
//! tracks played says so in a toast for 5 s (`PlayerShown.notice`). Every state is the core's `MusicPlayer`'s; it stays
//! from page to page and stops with the tenant.
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Call, wire::NowPlayingShown, wire::PlayerShown, wire::Update, wire::Watched};
use crate::home::glib_escape;
use crate::{said, words};

/// The account's music bar: [bar] is what the shell places at the window's bottom; it reveals itself.
pub struct MusicBar {
    pub bar: gtk::ActionBar,
    previous: gtk::Button,
    play: gtk::Button,
    next: gtk::Button,
    title: gtk::Label,
    album: gtk::Button,
    scale: gtk::Scale,
    elapsed: gtk::Label,
    total: gtk::Label,
    close: gtk::Button,
    toasts: adw::ToastOverlay,
    shown: RefCell<Option<PlayerShown>>,
    /// The scale held by the person: the core's positions do not move it meanwhile.
    dragging: Cell<bool>,
    /// The notice said, until a state without it.
    said: Cell<bool>,
    watch: RefCell<Option<Watch>>,
}

/// [ms] as a player says a time: `m:ss`, `h:mm:ss` from an hour on.
pub fn clock(ms: i64) -> String {
    let s = ms.max(0) / 1000;
    let (h, m, s) = (s / 3600, s / 60 % 60, s % 60);
    if h > 0 { format!("{h}:{m:02}:{s:02}") } else { format!("{m}:{s:02}") }
}

impl MusicBar {
    pub fn new(toasts: &adw::ToastOverlay) -> Rc<Self> {
        let button = |icon: &str, label: &str, id: &str| {
            let b = gtk::Button::builder().icon_name(icon).tooltip_text(label).valign(gtk::Align::Center).build();
            b.update_property(&[gtk::accessible::Property::Label(label)]);
            b.set_widget_name(id);
            b
        };
        let previous = button("media-skip-backward-symbolic", &words::music_previous(), "music-previous");
        let play = button("media-playback-start-symbolic", &words::music_resume(), "music-play");
        play.add_css_class("circular");
        let next = button("media-skip-forward-symbolic", &words::music_next(), "music-next");
        let close = button("window-close-symbolic", &words::music_close(), "music-close");
        let title = gtk::Label::builder().xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).build();
        title.add_css_class("heading");
        let album_label = gtk::Label::builder().xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).build();
        let album = gtk::Button::builder().child(&album_label).halign(gtk::Align::Start).build();
        album.add_css_class("flat");
        album.add_css_class("caption");
        album.set_widget_name("music-album");
        let heading = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        heading.append(&title);
        heading.append(&album);
        let scale = gtk::Scale::with_range(gtk::Orientation::Horizontal, 0.0, 1.0, 1000.0);
        scale.set_hexpand(true);
        scale.set_draw_value(false);
        scale.set_widget_name("music-progress");
        let elapsed = gtk::Label::new(None);
        let total = gtk::Label::new(None);
        for l in [&elapsed, &total] {
            l.add_css_class("numeric");
            l.add_css_class("caption");
            l.add_css_class("dim-label");
        }
        let progress = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        progress.append(&elapsed);
        progress.append(&scale);
        progress.append(&total);
        let middle = gtk::Box::builder().orientation(gtk::Orientation::Vertical).hexpand(true).valign(gtk::Align::Center).build();
        middle.append(&heading);
        middle.append(&progress);
        let controls = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        controls.append(&previous);
        controls.append(&play);
        controls.append(&next);
        let bar = gtk::ActionBar::builder().revealed(false).build();
        bar.pack_start(&controls);
        bar.set_center_widget(Some(&middle));
        bar.pack_end(&close);
        // the middle takes the width the bar leaves it (an action bar's center is as wide as its child asks)
        middle.set_size_request(240, -1);
        let this = Rc::new(Self {
            bar,
            previous,
            play,
            next,
            title,
            album,
            scale,
            elapsed,
            total,
            close,
            toasts: toasts.clone(),
            shown: RefCell::new(None),
            dragging: Cell::new(false),
            said: Cell::new(false),
            watch: RefCell::new(None),
        });
        this.connect();
        this
    }

    fn connect(self: &Rc<Self>) {
        self.previous.connect_clicked(|_| bridge::send(Call::PlayerPrevious));
        self.next.connect_clicked(|_| bridge::send(Call::PlayerNext));
        self.close.connect_clicked(|_| bridge::send(Call::PlayerStop));
        self.album.connect_clicked(|_| bridge::send(Call::PlayerOpenNode));
        let weak = Rc::downgrade(self);
        self.play.connect_clicked(move |_| {
            let Some(b) = weak.upgrade() else { return };
            let playing = b.shown.borrow().as_ref().and_then(|s| s.now_playing.as_ref()).is_some_and(|n| n.playing);
            bridge::send(if playing { Call::PlayerPause } else { Call::PlayerResume });
        });
        // the scale seeks when let go (the web's range on release); a key's step seeks at once
        let weak = Rc::downgrade(self);
        self.scale.connect_change_value(move |_, scroll, value| {
            let Some(b) = weak.upgrade() else { return glib::Propagation::Proceed };
            if scroll == gtk::ScrollType::Jump {
                b.dragging.set(true);
                b.elapsed.set_label(&clock(value as i64));
            } else {
                bridge::send(Call::PlayerSeek { position_ms: value.max(0.0) as i64 });
            }
            glib::Propagation::Proceed
        });
        // the release seen whatever claimed the press (the scale's own drag claims it from a click gesture)
        let released = gtk::EventControllerLegacy::new();
        released.set_propagation_phase(gtk::PropagationPhase::Capture);
        let weak = Rc::downgrade(self);
        released.connect_event(move |_, event| {
            if matches!(event.event_type(), gtk::gdk::EventType::ButtonRelease | gtk::gdk::EventType::TouchEnd)
                && let Some(b) = weak.upgrade()
                && b.dragging.replace(false)
            {
                let at = b.scale.value().max(0.0) as i64;
                eprintln!("veyq: music bar: seek to {at} ms");
                bridge::send(Call::PlayerSeek { position_ms: at });
            }
            glib::Propagation::Proceed
        });
        self.scale.add_controller(released);
    }

    /// Follows the account's player from now on (once the core started).
    pub fn start(self: &Rc<Self>) {
        if self.watch.borrow().is_some() {
            return;
        }
        let Some((watch, updates)) = bridge::watch(&Watched::Player) else {
            return eprintln!("veyq: the core refused to show the player");
        };
        *self.watch.borrow_mut() = Some(watch);
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            while let Ok(update) = updates.recv().await {
                let Some(b) = weak.upgrade() else { return };
                match update {
                    Ok(Update::Player { shown }) => b.show(shown),
                    Ok(_) => {}
                    Err(e) => eprintln!("veyq: a player update did not read: {:?}", e.0),
                }
            }
        });
    }

    fn show(&self, shown: Option<PlayerShown>) {
        let was = self.shown.borrow().as_ref().and_then(|s| s.now_playing.as_ref()).map(|n| (n.track_id.clone(), n.playing));
        let now = shown.as_ref().and_then(|s| s.now_playing.clone());
        if was != now.as_ref().map(|n| (n.track_id.clone(), n.playing)) {
            eprintln!("veyq: music bar: {}", now.as_ref().map_or("hidden".to_owned(), |n| format!("{} {}", n.title, if n.playing { "playing" } else { "paused" })));
        }
        match shown.as_ref().and_then(|s| s.notice.as_ref()) {
            Some(n) if !self.said.replace(true) => {
                eprintln!("veyq: music bar said: {}", n.msg);
                self.toasts.add_toast(adw::Toast::builder().title(glib_escape(&said::notice(n))).timeout(5).build());
            }
            Some(_) => {}
            None => self.said.set(false),
        }
        *self.shown.borrow_mut() = shown;
        self.bar.set_revealed(now.is_some());
        if let Some(n) = now {
            self.draw(&n);
        }
    }

    fn draw(&self, n: &NowPlayingShown) {
        self.title.set_label(&n.title);
        let album = n.album.clone().unwrap_or_default();
        if let Some(label) = self.album.child().and_downcast::<gtk::Label>() {
            label.set_label(&album);
        }
        self.album.set_visible(!album.is_empty() && n.route.is_some());
        let open = words::music_open_node(&album);
        self.album.set_tooltip_text(Some(&open));
        self.album.update_property(&[gtk::accessible::Property::Label(&open)]);
        let (icon, label) = if n.playing {
            ("media-playback-pause-symbolic", words::music_pause())
        } else {
            ("media-playback-start-symbolic", words::music_resume())
        };
        self.play.set_icon_name(icon);
        self.play.set_tooltip_text(Some(&label));
        self.play.update_property(&[gtk::accessible::Property::Label(&label)]);
        self.scale.update_property(&[gtk::accessible::Property::Label(&words::music_progress(&n.title))]);
        self.scale.set_sensitive(n.duration_ms > 0);
        self.total.set_label(&if n.duration_ms > 0 { clock(n.duration_ms) } else { String::new() });
        if !self.dragging.get() {
            self.scale.set_range(0.0, n.duration_ms.max(1) as f64);
            self.scale.set_value(n.position_ms as f64);
            self.elapsed.set_label(&clock(n.position_ms));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The bar's times as a player says them: minutes and seconds, hours once there are any.
    #[test]
    fn a_time_is_said_as_minutes_and_seconds() {
        assert_eq!(clock(0), "0:00");
        assert_eq!(clock(59_999), "0:59");
        assert_eq!(clock(61_000), "1:01");
        assert_eq!(clock(3_725_000), "1:02:05");
        assert_eq!(clock(-5), "0:00");
    }
}
