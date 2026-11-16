//! The core's raster map drawn on Linux (ADR 0143 decisions 1 and 11; Windows' `RasterMap`, Android's `MapView`): a
//! `gtk::Widget` of its own whose `snapshot` appends each tile's texture at the place the viewport's one affine formula
//! gives it from the normalised Web Mercator space the core hands — px = (p.x − view.x) · 256 · 2^zoom + width/2, the same
//! for y — and the overlays over them: a track's line as a `gsk::Path` stroked in its colour with its activity's dash, the
//! photos' dots, a photo's point with its direction needle, the Memories map's pins with their covers and counts, the place
//! editor's fixed centre pin. The tile provider's attribution is always shown at its foot (ADR 0018); "Turn map services
//! off" and the zoom buttons sit at its top end in an `osd` box, as libshumate's `ShumateSimpleMap` places its own. Every
//! rule is the core's — which tiles, when one may be fetched (the consent), clustering, framing —; this draws what it is
//! handed and reports gestures: a drag pans, the wheel and a pinch zoom about the pointer, a double click zooms in, `+`
//! `−` and the arrows on the keyboard, each told as `Map.View` with its centre (at most every 100 ms during a gesture and
//! once at its end — a centre is sent for a member's gesture only, so on the place editor's map it is the member's pan). A
//! size change sends the size alone; the core's view is adopted whenever its `moved` turns (its opening, a cluster framed,
//! a track selected, *Follow the photos*).
//!
//! Before the consent the consent's offer stands in the map's place (uc.memory.place A6), worded as the web's
//! `MapConsentPrompt`; with no map served, or offline, why there is none. A withdrawal tears the map down at once: every
//! tile let go. Tiles are held decoded in memory only, a bounded few per map (ADR 0143 decision 4); nothing is written to
//! disk. A pin is drawn, not a widget: a click is hit-tested against the pins and told to the core (`Map.Tap`).
use std::cell::{Cell, RefCell};
use std::collections::{HashMap, HashSet, VecDeque};
use std::rc::{Rc, Weak};
use std::time::{Duration, Instant};

use adw::prelude::*;
use gtk::{gdk, glib, graphene, gsk};
use gtk::subclass::prelude::*;

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::MapShown, wire::MapStatus, wire::PinShown, wire::Update, wire::Watched};
use crate::home::glib_escape;
use crate::words;

/// A tile's edge in layout pixels at its own zoom (Web Mercator's 256).
const TILE: f64 = 256.0;
/// The zooms a member may reach (the web's Leaflet stops at 19).
const MIN_ZOOM: f64 = 1.0;
const MAX_ZOOM: f64 = 19.0;
/// How many decoded tiles one map holds; the least recently drawn first out.
const TILES_HELD: usize = 160;
/// How far a press may move and still be a click, in layout pixels.
const SLOP: f64 = 4.0;
/// How near a click must land to a pin's centre to be on it.
const PIN_REACH: f64 = 24.0;
/// A pin's radius, a dot's, a photo's point's.
const PIN_RADIUS: f64 = 18.0;
const DOT_RADIUS: f64 = 4.5;
const PHOTO_RADIUS: f64 = 7.0;
/// How often a gesture tells the core where it stands, at most.
const TELL_EVERY: Duration = Duration::from_millis(100);
/// How many zooms away a held tile may still be drawn under the view's own while they arrive.
const UNDER_ZOOMS: i32 = 2;
/// The widest a tile is drawn (eight times its own edge); a wider one waits for the view's own tiles.
const MAX_TILE_PX: f32 = 2048.0;

/// Where a normalised point ([x], [y]) is drawn in a [width] × [height] widget whose view is centred on ([cx], [cy]) at
/// [zoom]: the viewport's one formula (ADR 0143 decision 11).
pub fn px(x: f64, y: f64, cx: f64, cy: f64, zoom: f64, width: f64, height: f64) -> (f64, f64) {
    let scale = TILE * 2f64.powf(zoom);
    ((x - cx) * scale + width / 2.0, (y - cy) * scale + height / 2.0)
}

/// A colour the core hands as ARGB.
pub fn argb(color: i64) -> gdk::RGBA {
    let c = color as u32;
    let part = |shift: u32| ((c >> shift) & 0xff) as f32 / 255.0;
    gdk::RGBA::new(part(16), part(8), part(0), part(24))
}

/// What the canvas draws with, and what it tells of its size: its owner's.
type Draw = Box<dyn Fn(&MapCanvas, &gtk::Snapshot)>;
type Resized = Box<dyn Fn(i32, i32)>;
/// What a map's owner is told of each state.
type Changed = Box<dyn Fn(Option<&MapShown>)>;

mod imp {
    use super::*;

    /// The drawing surface: what it draws and what its size tells are its owner's ([super::Inner]).
    #[derive(Default)]
    pub struct MapCanvas {
        pub draw: RefCell<Option<Draw>>,
        pub resized: RefCell<Option<Resized>>,
    }

    #[glib::object_subclass]
    impl ObjectSubclass for MapCanvas {
        const NAME: &'static str = "VeyqMapCanvas";
        type Type = super::MapCanvas;
        type ParentType = gtk::Widget;
    }

    impl ObjectImpl for MapCanvas {}

    impl WidgetImpl for MapCanvas {
        fn snapshot(&self, snapshot: &gtk::Snapshot) {
            if let Some(draw) = self.draw.borrow().as_ref() {
                draw(&self.obj(), snapshot);
            }
        }

        fn size_allocate(&self, width: i32, height: i32, baseline: i32) {
            self.parent_size_allocate(width, height, baseline);
            if let Some(resized) = self.resized.borrow().as_ref() {
                resized(width, height);
            }
        }
    }
}

glib::wrapper! {
    pub struct MapCanvas(ObjectSubclass<imp::MapCanvas>) @extends gtk::Widget, @implements gtk::Accessible, gtk::Buildable, gtk::ConstraintTarget;
}

/// One tile held decoded: its zoom, its corner and edge in normalised space, its picture.
#[derive(Clone)]
struct Held {
    z: i32,
    x: f64,
    y: f64,
    size: f64,
    texture: gdk::Texture,
}

/// A map on screen: watched while it stands; closed in the core when dropped if it opened it ([RasterMap::own]).
pub struct RasterMap {
    inner: Rc<Inner>,
}

struct Inner {
    map: i64,
    owns: bool,
    root: gtk::Overlay,
    stack: gtk::Stack,
    canvas: MapCanvas,
    status: gtk::Box,
    chrome: gtk::Box,
    attribution: gtk::Label,
    shown: RefCell<Option<MapShown>>,
    /// The view drawn: its centre in normalised space and its zoom; whether one is held yet; the core's `moved` adopted last.
    view: Cell<(f64, f64, f64)>,
    has_view: Cell<bool>,
    moved: Cell<i64>,
    size: Cell<(i32, i32)>,
    /// The size told the core last (none: tell it at the next allocation).
    told_size: Cell<Option<(i32, i32)>>,
    told_at: Cell<Option<Instant>>,
    tell_pending: Cell<bool>,
    held: RefCell<(HashMap<String, Held>, VecDeque<String>)>,
    reading: RefCell<HashSet<String>>,
    /// The pins' covers by node id (none: it has none); asked once each.
    covers: RefCell<HashMap<String, Option<gdk::Texture>>>,
    /// A press and the view it started from; whether it moved beyond a click.
    press: Cell<Option<(f64, f64, f64)>>,
    dragged: Cell<bool>,
    pinch_from: Cell<f64>,
    pointer: Cell<(f64, f64)>,
    status_drawn: RefCell<String>,
    watch: RefCell<Option<Watch>>,
    closed: Cell<bool>,
    window: gtk::Window,
    changed: RefCell<Option<Changed>>,
}

impl RasterMap {
    /// Map [map] (opened by its owner's call) drawn [height] tall at the least over [window]; [owns]: closed in the core
    /// when dropped (`Map.Close`) — a place editor's and a Map view's close with their own.
    pub fn open(map: i64, height: i32, owns: bool, window: &gtk::Window) -> Self {
        let canvas: MapCanvas = glib::Object::builder().property("accessible-role", gtk::AccessibleRole::Img).build();
        canvas.set_focusable(true);
        canvas.set_hexpand(true);
        canvas.set_vexpand(true);
        canvas.set_overflow(gtk::Overflow::Hidden);
        canvas.update_property(&[gtk::accessible::Property::Label(&words::memory_view_map())]);
        canvas.set_widget_name("raster-map");
        let status = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).valign(gtk::Align::Center).margin_top(18)
            .margin_bottom(18).margin_start(18).margin_end(18).build();
        let stack = gtk::Stack::builder().hhomogeneous(false).vhomogeneous(false).build();
        stack.add_named(&canvas, Some("map"));
        stack.add_named(&status, Some("status"));
        stack.set_visible_child_name("map");
        // the controls at the top end, GNOME's on-map buttons (`osd`), one linked box
        let chrome = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).halign(gtk::Align::End).valign(gtk::Align::Start)
            .margin_top(8).margin_end(8).visible(false).build();
        let attribution = gtk::Label::builder().halign(gtk::Align::End).valign(gtk::Align::End).xalign(1.0).wrap(true).visible(false).build();
        attribution.add_css_class("caption");
        attribution.add_css_class("osd");
        attribution.add_css_class("map-attribution");
        attribution.set_widget_name("map-attribution");
        let root = gtk::Overlay::builder().child(&stack).build();
        root.set_size_request(-1, height);
        root.set_overflow(gtk::Overflow::Hidden);
        root.add_css_class("card");
        root.add_overlay(&chrome);
        root.add_overlay(&attribution);
        style_once();
        let inner = Rc::new(Inner {
            map,
            owns,
            root,
            stack,
            canvas,
            status,
            chrome,
            attribution,
            shown: RefCell::new(None),
            view: Cell::new((0.5, 0.5, 2.0)),
            has_view: Cell::new(false),
            moved: Cell::new(-1),
            size: Cell::new((0, 0)),
            told_size: Cell::new(None),
            told_at: Cell::new(None),
            tell_pending: Cell::new(false),
            held: RefCell::new((HashMap::new(), VecDeque::new())),
            reading: RefCell::new(HashSet::new()),
            covers: RefCell::new(HashMap::new()),
            press: Cell::new(None),
            dragged: Cell::new(false),
            pinch_from: Cell::new(0.0),
            pointer: Cell::new((0.0, 0.0)),
            status_drawn: RefCell::new(String::new()),
            watch: RefCell::new(None),
            closed: Cell::new(false),
            window: window.clone(),
            changed: RefCell::new(None),
        });
        Inner::build_chrome(&inner);
        Inner::connect(&inner);
        let weak = Rc::downgrade(&inner);
        if let Some((watch, updates)) = bridge::watch(&Watched::Map { map }) {
            *inner.watch.borrow_mut() = Some(watch);
            glib::spawn_future_local(follow(weak, updates));
        }
        Self { inner }
    }

    pub fn widget(&self) -> &gtk::Overlay {
        &self.inner.root
    }

    pub fn id(&self) -> i64 {
        self.inner.map
    }

    /// Told each state of the map (none once it closed in the core).
    pub fn on_changed(&self, f: impl Fn(Option<&MapShown>) + 'static) {
        *self.inner.changed.borrow_mut() = Some(Box::new(f));
    }
}

impl Drop for RasterMap {
    fn drop(&mut self) {
        let inner = &self.inner;
        inner.closed.set(true);
        inner.watch.borrow_mut().take();
        inner.drop_tiles();
        *inner.canvas.imp().draw.borrow_mut() = None;
        *inner.canvas.imp().resized.borrow_mut() = None;
        if inner.owns {
            bridge::send(Call::MapClose { map: inner.map });
        }
    }
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Map { shown, .. }) => inner.updated(shown),
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a map update did not read: {:?}", e.0),
        }
    }
}

/// How a map's state is said in the log.
fn state_name(s: &MapStatus) -> String {
    match s {
        MapStatus::MapStateShown => "shown".into(),
        MapStatus::MapStateConsent { renewal, .. } => format!("consent ({})", if *renewal { "renewal" } else { "first" }),
        MapStatus::MapStateOffline => "offline".into(),
        MapStatus::MapStateNotOffered => "not offered".into(),
    }
}

/// The recipients a consent given now names (`map_consent_hosts`, …); a renewal says it is asked again for them.
pub fn consent_hosts(tile_host: Option<&str>, geocoder_host: Option<&str>, renewal: bool) -> Option<String> {
    let tiles = tile_host?;
    Some(match (geocoder_host, renewal) {
        (Some(places), false) => words::map_consent_hosts(tiles, places),
        (Some(places), true) => words::map_consent_renew_both(tiles, places),
        (None, false) => words::map_consent_hosts_tiles(tiles),
        (None, true) => words::map_consent_renew_tiles(tiles),
    })
}

/// The consent's offer where a map would stand (the web's `MapConsentPrompt`, uc.memory.place A6): what turning on sends
/// where, the recipients by name, and the one button — or why there is no map. Shared with the place editor's name-only
/// form and Settings.
pub fn offer(state: &MapStatus) -> gtk::Widget {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).valign(gtk::Align::Center).build();
    match state {
        MapStatus::MapStateConsent { tile_host, geocoder_host, renewal } => {
            let title = gtk::Label::builder().label(words::map_consent_title()).xalign(0.0).wrap(true).build();
            title.add_css_class("heading");
            column.append(&title);
            let body = gtk::Label::builder().label(words::map_consent_body()).xalign(0.0).wrap(true).build();
            body.add_css_class("dim-label");
            column.append(&body);
            if let Some(hosts) = consent_hosts(tile_host.as_deref(), geocoder_host.as_deref(), *renewal) {
                column.append(&gtk::Label::builder().label(hosts).xalign(0.0).wrap(true).build());
            }
            let on = gtk::Button::builder().label(words::map_consent_enable()).halign(gtk::Align::Start).build();
            on.add_css_class("pill");
            on.add_css_class("suggested-action");
            on.set_widget_name("map-consent-enable");
            on.connect_clicked(|_| {
                eprintln!("veyq: map services: turn on pressed");
                glib::spawn_future_local(async {
                    match bridge::call(&Call::MapTurnOn).await.map(|(a, _)| a) {
                        Ok(Answer::Done) => eprintln!("veyq: Map.TurnOn: Done"),
                        other => eprintln!("veyq: Map.TurnOn answered {other:?}"),
                    }
                });
            });
            column.append(&on);
        }
        other => {
            let text = if matches!(other, MapStatus::MapStateOffline) { words::map_needs_connection() } else { words::map_not_offered() };
            let line = gtk::Box::builder().spacing(8).halign(gtk::Align::Center).build();
            line.append(&gtk::Image::from_icon_name(if matches!(other, MapStatus::MapStateOffline) { "network-offline-symbolic" } else { "mark-location-symbolic" }));
            let label = gtk::Label::builder().label(text).wrap(true).build();
            line.append(&label);
            line.add_css_class("dim-label");
            column.append(&line);
        }
    }
    adw::Clamp::builder().maximum_size(560).child(&column).build().upcast()
}

/// *Turn map services off*: this device's consent withdrawn at once (GDPR Art. 7(3)); every map drops its tiles.
pub fn turn_off() {
    eprintln!("veyq: map services: turn off pressed");
    glib::spawn_future_local(async {
        match bridge::call(&Call::MapTurnOff).await.map(|(a, _)| a) {
            Ok(Answer::Done) => eprintln!("veyq: Map.TurnOff: Done"),
            other => eprintln!("veyq: Map.TurnOff answered {other:?}"),
        }
    });
}

impl Inner {
    fn build_chrome(this: &Rc<Self>) {
        let button = |icon: &str, name: String, id: &str| {
            let b = gtk::Button::builder().icon_name(icon).tooltip_text(&name).build();
            b.add_css_class("osd");
            b.add_css_class("circular");
            b.update_property(&[gtk::accessible::Property::Label(&name)]);
            b.set_widget_name(id);
            b
        };
        let off = button("system-shutdown-symbolic", words::map_off(), "map-off");
        off.connect_clicked(|_| turn_off());
        let zoom_in = button("zoom-in-symbolic", words::map_zoom_in(), "map-zoom-in");
        let zoom_out = button("zoom-out-symbolic", words::map_zoom_out(), "map-zoom-out");
        for (b, by) in [(&zoom_in, 1.0), (&zoom_out, -1.0)] {
            let weak = Rc::downgrade(this);
            b.connect_clicked(move |_| {
                if let Some(i) = weak.upgrade() {
                    let (w, h) = i.size.get();
                    i.zoom_by(by, f64::from(w) / 2.0, f64::from(h) / 2.0);
                }
            });
        }
        this.chrome.append(&off);
        this.chrome.append(&zoom_in);
        this.chrome.append(&zoom_out);
    }

    fn connect(this: &Rc<Self>) {
        let weak = Rc::downgrade(this);
        *this.canvas.imp().draw.borrow_mut() = Some(Box::new(move |canvas, snapshot| {
            if let Some(i) = weak.upgrade() {
                i.snapshot(canvas, snapshot);
            }
        }));
        let weak = Rc::downgrade(this);
        *this.canvas.imp().resized.borrow_mut() = Some(Box::new(move |w, h| {
            if let Some(i) = weak.upgrade() {
                i.size.set((w, h));
                if i.told_size.get() != Some((w, h)) && w > 0 && h > 0 {
                    // told on the next turn of the loop: the core's answer may draw, never inside the allocation
                    let weak = Rc::downgrade(&i);
                    glib::idle_add_local_once(move || {
                        if let Some(i) = weak.upgrade() {
                            i.tell_size();
                        }
                    });
                }
            }
        }));
        // a drag pans; a press that did not move is a click (a pin), two a zoom in
        let drag = gtk::GestureDrag::new();
        let weak = Rc::downgrade(this);
        drag.connect_drag_begin(move |_, _, _| {
            if let Some(i) = weak.upgrade() {
                let (x, y, z) = i.view.get();
                i.press.set(Some((x, y, z)));
                i.dragged.set(false);
                i.canvas.grab_focus();
            }
        });
        let weak = Rc::downgrade(this);
        drag.connect_drag_update(move |_, dx, dy| {
            let Some(i) = weak.upgrade() else { return };
            let Some((x0, y0, z)) = i.press.get() else { return };
            if !i.dragged.get() && dx.hypot(dy) < SLOP {
                return;
            }
            i.dragged.set(true);
            let scale = TILE * 2f64.powf(z);
            i.view.set(((x0 - dx / scale).clamp(0.0, 1.0), (y0 - dy / scale).clamp(0.0, 1.0), z));
            i.canvas.queue_draw();
            i.tell_soon();
        });
        let weak = Rc::downgrade(this);
        drag.connect_drag_end(move |_, _, _| {
            if let Some(i) = weak.upgrade() {
                i.press.set(None);
                if i.dragged.get() {
                    i.tell_view();
                }
            }
        });
        this.canvas.add_controller(drag);
        let click = gtk::GestureClick::new();
        let weak = Rc::downgrade(this);
        click.connect_released(move |_, n, x, y| {
            let Some(i) = weak.upgrade() else { return };
            if i.dragged.get() {
                return;
            }
            if n == 2 {
                i.zoom_by(1.0, x, y);
            } else if n == 1 {
                i.tapped(x, y);
            }
        });
        this.canvas.add_controller(click);
        let motion = gtk::EventControllerMotion::new();
        let weak = Rc::downgrade(this);
        motion.connect_motion(move |_, x, y| {
            if let Some(i) = weak.upgrade() {
                i.pointer.set((x, y));
            }
        });
        this.canvas.add_controller(motion);
        let scroll = gtk::EventControllerScroll::new(gtk::EventControllerScrollFlags::VERTICAL);
        let weak = Rc::downgrade(this);
        scroll.connect_scroll(move |_, _, dy| {
            if let Some(i) = weak.upgrade() {
                let (x, y) = i.pointer.get();
                i.zoom_by(-dy * 0.5, x, y);
            }
            glib::Propagation::Stop
        });
        this.canvas.add_controller(scroll);
        let pinch = gtk::GestureZoom::new();
        let weak = Rc::downgrade(this);
        pinch.connect_begin(move |_, _| {
            if let Some(i) = weak.upgrade() {
                i.pinch_from.set(i.view.get().2);
            }
        });
        let weak = Rc::downgrade(this);
        pinch.connect_scale_changed(move |g, scale| {
            if let Some(i) = weak.upgrade() {
                let (cx, cy) = g.bounding_box_center().unwrap_or_else(|| { let (w, h) = i.size.get(); (f64::from(w) / 2.0, f64::from(h) / 2.0) });
                let target = i.pinch_from.get() + scale.max(0.01).log2();
                i.zoom_by(target - i.view.get().2, cx, cy);
            }
        });
        this.canvas.add_controller(pinch);
        let keys = gtk::EventControllerKey::new();
        let weak = Rc::downgrade(this);
        keys.connect_key_pressed(move |_, key, _, _| {
            let Some(i) = weak.upgrade() else { return glib::Propagation::Proceed };
            let (w, h) = i.size.get();
            let (cx, cy) = (f64::from(w) / 2.0, f64::from(h) / 2.0);
            const STEP: f64 = 64.0;
            match key {
                gdk::Key::plus | gdk::Key::KP_Add | gdk::Key::equal => i.zoom_by(1.0, cx, cy),
                gdk::Key::minus | gdk::Key::KP_Subtract => i.zoom_by(-1.0, cx, cy),
                gdk::Key::Left => i.pan_by(STEP, 0.0),
                gdk::Key::Right => i.pan_by(-STEP, 0.0),
                gdk::Key::Up => i.pan_by(0.0, STEP),
                gdk::Key::Down => i.pan_by(0.0, -STEP),
                _ => return glib::Propagation::Proceed,
            }
            glib::Propagation::Stop
        });
        this.canvas.add_controller(keys);
    }

    fn updated(self: &Rc<Self>, shown: Option<MapShown>) {
        if self.closed.get() {
            return;
        }
        let Some(s) = shown else {
            eprintln!("veyq: map {}: closed in the core", self.map);
            *self.shown.borrow_mut() = None;
            self.drop_tiles();
            self.draw_state();
            if let Some(f) = self.changed.borrow().as_ref() {
                f(None);
            }
            return;
        };
        let was_shown = matches!(self.shown.borrow().as_ref().map(|s| &s.state), Some(MapStatus::MapStateShown));
        if let Some(v) = &s.view
            && (!self.has_view.get() || v.moved != self.moved.get())
            && self.press.get().is_none()
        {
            self.has_view.set(true);
            self.moved.set(v.moved);
            self.view.set((v.x, v.y, v.zoom));
        }
        eprintln!("veyq: map {} shown: {}, zoom {:.1}, moved {}, {} tiles, {} tracks, {} dots, {} clusters, photo {}, centre pin {}",
            self.map, state_name(&s.state), s.view.as_ref().map_or(-1.0, |v| v.zoom), s.view.as_ref().map_or(-1, |v| v.moved), s.tiles.len(),
            s.tracks.len(), s.dots.len(), s.clusters.len(), s.photo.is_some(), s.centre_pin);
        let shown_now = matches!(s.state, MapStatus::MapStateShown);
        *self.shown.borrow_mut() = Some(s);
        if !shown_now && was_shown {
            self.drop_tiles();
        }
        if shown_now && !was_shown {
            // the canvas shows again: its size is told anew at its next allocation
            self.told_size.set(None);
        }
        self.draw_state();
        if shown_now {
            self.read_tiles();
            self.read_covers();
        }
        self.canvas.queue_draw();
        if let Some(f) = self.changed.borrow().as_ref() {
            f(self.shown.borrow().as_ref());
        }
    }

    /// Every tile let go at once (a withdrawal, the map closed): nothing of it stays drawn or held.
    fn drop_tiles(&self) {
        let mut held = self.held.borrow_mut();
        held.0.clear();
        held.1.clear();
        self.reading.borrow_mut().clear();
        self.covers.borrow_mut().clear();
        self.canvas.queue_draw();
    }

    /// What the map's place shows: the map, or the consent's offer, or why there is none.
    fn draw_state(&self) {
        let shown = self.shown.borrow();
        let state = shown.as_ref().map(|s| &s.state);
        let on = matches!(state, Some(MapStatus::MapStateShown) | None);
        self.chrome.set_visible(matches!(state, Some(MapStatus::MapStateShown)));
        let attribution = shown.as_ref().and_then(|s| s.attribution.clone()).filter(|a| !a.is_empty());
        self.attribution.set_visible(matches!(state, Some(MapStatus::MapStateShown)) && attribution.is_some());
        self.attribution.set_label(&attribution.unwrap_or_default());
        self.stack.set_visible_child_name(if on { "map" } else { "status" });
        let key = state.map(|s| format!("{s:?}")).unwrap_or_default();
        if *self.status_drawn.borrow() == key {
            return;
        }
        *self.status_drawn.borrow_mut() = key;
        while let Some(child) = self.status.first_child() {
            self.status.remove(&child);
        }
        if let Some(s) = state.filter(|_| !on) {
            self.status.append(&offer(s));
        }
    }

    /// Each tile in view not held asked for once (`Map.Tile`), decoded by GTK and held.
    fn read_tiles(self: &Rc<Self>) {
        let tiles = self.shown.borrow().as_ref().map(|s| s.tiles.clone()).unwrap_or_default();
        for t in tiles {
            if self.held.borrow().0.contains_key(&t.tile) || !self.reading.borrow_mut().insert(t.tile.clone()) {
                continue;
            }
            let (weak, map) = (Rc::downgrade(self), self.map);
            glib::spawn_future_local(async move {
                let answered = bridge::call(&Call::MapTile { map, tile: t.tile.clone() }).await;
                let Some(i) = weak.upgrade() else { return };
                if !i.reading.borrow_mut().remove(&t.tile) {
                    return; // let go meanwhile (a withdrawal)
                }
                let texture = match answered {
                    Ok((Answer::Bytes { .. }, Some(bytes))) => match gdk::Texture::from_bytes(&glib::Bytes::from_owned(bytes)) {
                        Ok(texture) => texture,
                        Err(e) => return eprintln!("veyq: a map tile did not decode: {e}"),
                    },
                    other => return eprintln!("veyq: Map.Tile {} answered {:?}", t.tile, other.map(|(a, _)| a)),
                };
                let z = t.tile.split('/').next().and_then(|z| z.parse().ok()).unwrap_or(0);
                let mut held = i.held.borrow_mut();
                held.0.insert(t.tile.clone(), Held { z, x: t.x, y: t.y, size: t.size, texture });
                held.1.retain(|k| *k != t.tile);
                held.1.push_back(t.tile.clone());
                while held.1.len() > TILES_HELD {
                    if let Some(old) = held.1.pop_front() {
                        held.0.remove(&old);
                    }
                }
                drop(held);
                i.canvas.queue_draw();
            });
        }
    }

    /// Each pin's cover asked for once (`Shell.RowCover`, the list rows' own).
    fn read_covers(self: &Rc<Self>) {
        let pins: Vec<String> = self.shown.borrow().as_ref().map(|s| s.clusters.iter().filter_map(|c| c.pin.cover.clone()).collect()).unwrap_or_default();
        for node in pins {
            if self.covers.borrow().contains_key(&node) {
                continue;
            }
            self.covers.borrow_mut().insert(node.clone(), None);
            let weak = Rc::downgrade(self);
            glib::spawn_future_local(async move {
                let texture = crate::home::row_cover_texture(&node).await;
                if let Some(i) = weak.upgrade()
                    && i.covers.borrow().contains_key(&node)
                {
                    i.covers.borrow_mut().insert(node, texture);
                    i.canvas.queue_draw();
                }
            });
        }
    }

    /// The view moved by a drag of ([dx], [dy]) layout pixels: drawn at once, the core told soon.
    fn pan_by(self: &Rc<Self>, dx: f64, dy: f64) {
        if !self.has_view.get() {
            return;
        }
        let (x, y, z) = self.view.get();
        let scale = TILE * 2f64.powf(z);
        self.view.set(((x - dx / scale).clamp(0.0, 1.0), (y - dy / scale).clamp(0.0, 1.0), z));
        self.canvas.queue_draw();
        self.tell_view();
    }

    /// The zoom changed by [by] about ([px], [py]): the point under it stays where it is.
    fn zoom_by(self: &Rc<Self>, by: f64, px: f64, py: f64) {
        let (w, h) = self.size.get();
        if w <= 0 || !self.has_view.get() {
            return;
        }
        let (x, y, z) = self.view.get();
        let zoom = (z + by).clamp(MIN_ZOOM, MAX_ZOOM);
        if (zoom - z).abs() < 1e-9 {
            return;
        }
        let (dx, dy) = (px - f64::from(w) / 2.0, py - f64::from(h) / 2.0);
        let (wx, wy) = (x + dx / (TILE * 2f64.powf(z)), y + dy / (TILE * 2f64.powf(z)));
        let scale = TILE * 2f64.powf(zoom);
        self.view.set(((wx - dx / scale).clamp(0.0, 1.0), (wy - dy / scale).clamp(0.0, 1.0), zoom));
        self.canvas.queue_draw();
        self.tell_soon();
    }

    /// A gesture's view told at most every 100 ms, the last one always.
    fn tell_soon(self: &Rc<Self>) {
        let due = self.told_at.get().is_none_or(|t| t.elapsed() >= TELL_EVERY);
        if due {
            self.tell_view();
        } else if !self.tell_pending.replace(true) {
            let weak = Rc::downgrade(self);
            glib::timeout_add_local_once(TELL_EVERY, move || {
                if let Some(i) = weak.upgrade() {
                    i.tell_pending.set(false);
                    i.tell_view();
                }
            });
        }
    }

    /// The member's view told to the core (`Map.View` with its centre): its tiles follow.
    fn tell_view(&self) {
        let (w, h) = self.size.get();
        if self.closed.get() || w <= 0 || !self.has_view.get() {
            return;
        }
        self.told_at.set(Some(Instant::now()));
        let (x, y, zoom) = self.view.get();
        bridge::send(Call::MapView { map: self.map, x: Some(x), y: Some(y), zoom: Some(zoom), width: f64::from(w), height: f64::from(h),
            scale: f64::from(self.canvas.scale_factor()) });
    }

    /// The map's size told alone: the core opens at its view for that size, or keeps its own.
    fn tell_size(&self) {
        let (w, h) = self.size.get();
        if self.closed.get() || w <= 0 || h <= 0 || self.told_size.get() == Some((w, h)) {
            return;
        }
        self.told_size.set(Some((w, h)));
        eprintln!("veyq: map {}: {w} × {h}", self.map);
        bridge::send(Call::MapView { map: self.map, x: None, y: None, zoom: None, width: f64::from(w), height: f64::from(h),
            scale: f64::from(self.canvas.scale_factor()) });
    }

    /// A click on the map: the pin nearest under it told to the core (`Map.Tap`) — its Memory opened, or the Memories
    /// sharing its spot listed; else the core framed them.
    fn tapped(self: &Rc<Self>, x: f64, y: f64) {
        let shown = self.shown.borrow();
        let Some(s) = shown.as_ref().filter(|s| !s.clusters.is_empty()) else { return };
        let (cx, cy, z) = self.view.get();
        let (w, h) = self.size.get();
        let hit = s.clusters.iter().map(|c| {
            let (px, py) = px(c.at.x, c.at.y, cx, cy, z, f64::from(w), f64::from(h));
            (c, (px - x).hypot(py - y))
        }).filter(|(_, d)| *d <= PIN_REACH).min_by(|a, b| a.1.total_cmp(&b.1)).map(|(c, _)| c.pin.node.node_id.clone());
        drop(shown);
        let Some(node_id) = hit else { return };
        let (map, window) = (self.map, self.window.clone());
        eprintln!("veyq: map {map}: pin {node_id} tapped");
        glib::spawn_future_local(async move {
            match bridge::call(&Call::MapTap { map, node_id }).await.map(|(a, _)| a) {
                Ok(Answer::MapTapped { open: Some(pin), .. }) => open_pin(&pin),
                Ok(Answer::MapTapped { listed, .. }) if !listed.is_empty() => list_pins(&window, &listed),
                Ok(Answer::MapTapped { .. }) => eprintln!("veyq: Map.Tap: framed"),
                other => eprintln!("veyq: Map.Tap answered {other:?}"),
            }
        });
    }

    fn snapshot(&self, canvas: &MapCanvas, snapshot: &gtk::Snapshot) {
        let (w, h) = (f64::from(canvas.width()), f64::from(canvas.height()));
        let bounds = graphene::Rect::new(0.0, 0.0, w as f32, h as f32);
        let dark = adw::StyleManager::default().is_dark();
        snapshot.append_color(&if dark { gdk::RGBA::new(0.16, 0.16, 0.18, 1.0) } else { gdk::RGBA::new(0.92, 0.91, 0.88, 1.0) }, &bounds);
        let shown = self.shown.borrow();
        let Some(s) = shown.as_ref().filter(|s| matches!(s.state, MapStatus::MapStateShown)) else { return };
        if !self.has_view.get() {
            return;
        }
        snapshot.push_clip(&bounds);
        self.draw_map(snapshot, s, w, h);
        snapshot.pop();
    }

    fn draw_map(&self, snapshot: &gtk::Snapshot, s: &MapShown, w: f64, h: f64) {
        let canvas = &self.canvas;
        let (cx, cy, zoom) = self.view.get();
        let at = |x: f64, y: f64| px(x, y, cx, cy, zoom, w, h);
        let rect = |x: f64, y: f64, size: f64| {
            let (l, t) = at(x, y);
            let (r, b) = at(x + size, y + size);
            let (l, t, r, b) = (l.round(), t.round(), r.round(), b.round());
            graphene::Rect::new(l as f32, t as f32, (r - l) as f32, (b - t) as f32)
        };
        // a tile drawn far beyond its own size is left out: GSK renders a scaled texture whole, so a tile of zoom 2 shown
        // at zoom 11 (130,000 px wide, while the view zoomed ahead of the core's tiles) had the app killed for its memory
        // (measured in the container, 2026-11-16)
        let in_view = |r: &graphene::Rect| r.width() <= MAX_TILE_PX && r.x() < w as f32 && r.y() < h as f32 && r.x() + r.width() > 0.0 && r.y() + r.height() > 0.0;
        // the tiles: those of other zooms held underneath (the last view's, until the new ones arrive), then the view's own
        let held = self.held.borrow();
        let current = s.tiles.first().and_then(|t| t.tile.split('/').next()).and_then(|z| z.parse::<i32>().ok());
        let near = |z: i32| current.is_some_and(|c| (c - z).abs() <= UNDER_ZOOMS);
        let mut under: Vec<&Held> = held.0.values().filter(|t| Some(t.z) != current && near(t.z)).collect();
        under.sort_by_key(|t| -(t.z - current.unwrap_or(t.z)).abs());
        for t in under {
            let r = rect(t.x, t.y, t.size);
            if in_view(&r) {
                snapshot.append_scaled_texture(&t.texture, gsk::ScalingFilter::Linear, &r);
            }
        }
        for t in &s.tiles {
            let r = rect(t.x, t.y, t.size);
            if let Some(h) = held.0.get(&t.tile).filter(|_| in_view(&r)) {
                snapshot.append_scaled_texture(&h.texture, gsk::ScalingFilter::Linear, &r);
            }
        }
        drop(held);
        // the tracks, each segment one path, dashed by the activity; the selected one heavier
        for line in &s.tracks {
            let stroke = gsk::Stroke::new(if line.selected { 6.0 } else { 4.0 });
            stroke.set_line_cap(gsk::LineCap::Round);
            stroke.set_line_join(gsk::LineJoin::Round);
            if let Some(dash) = line.dash.as_ref().filter(|d| !d.is_empty()) {
                stroke.set_line_cap(gsk::LineCap::Butt);
                stroke.set_dash(&dash.iter().map(|d| *d as f32).collect::<Vec<_>>());
            }
            let builder = gsk::PathBuilder::new();
            for segment in &line.segments {
                for (k, p) in segment.iter().enumerate() {
                    let (x, y) = at(p.x, p.y);
                    if k == 0 { builder.move_to(x as f32, y as f32) } else { builder.line_to(x as f32, y as f32) }
                }
            }
            snapshot.append_stroke(&builder.to_path(), &stroke, &argb(line.color));
        }
        let accent = adw::StyleManager::default().accent_color_rgba();
        let white = gdk::RGBA::WHITE;
        let circle = |x: f64, y: f64, r: f64| {
            let b = gsk::PathBuilder::new();
            b.add_circle(&graphene::Point::new(x as f32, y as f32), r as f32);
            b.to_path()
        };
        // the photos' dots
        for d in &s.dots {
            let (x, y) = at(d.x, d.y);
            let path = circle(x, y, DOT_RADIUS);
            snapshot.append_fill(&path, gsk::FillRule::Winding, &accent);
            snapshot.append_stroke(&path, &gsk::Stroke::new(1.5), &white);
        }
        // a photo's point with the direction it faced
        if let Some(p) = &s.photo {
            let (x, y) = at(p.at.x, p.at.y);
            if let Some(dir) = p.direction {
                let a = f64::from(dir).to_radians();
                let b = gsk::PathBuilder::new();
                b.move_to(x as f32, y as f32);
                b.line_to((x + a.sin() * 26.0) as f32, (y - a.cos() * 26.0) as f32);
                let stroke = gsk::Stroke::new(3.0);
                stroke.set_line_cap(gsk::LineCap::Round);
                snapshot.append_stroke(&b.to_path(), &stroke, &accent);
            }
            let path = circle(x, y, PHOTO_RADIUS);
            snapshot.append_fill(&path, gsk::FillRule::Winding, &accent);
            snapshot.append_stroke(&path, &gsk::Stroke::new(2.0), &white);
        }
        // the Memories map's pins: each its cover in a circle (the pin's mark where it has none), its count beside it
        let covers = self.covers.borrow();
        for c in &s.clusters {
            let (x, y) = at(c.at.x, c.at.y);
            let path = circle(x, y, PIN_RADIUS);
            snapshot.append_fill(&path, gsk::FillRule::Winding, &accent);
            let cover = c.pin.cover.as_ref().and_then(|n| covers.get(n)).and_then(Option::as_ref);
            if let Some(t) = cover {
                let r = graphene::Rect::new((x - PIN_RADIUS) as f32, (y - PIN_RADIUS) as f32, (PIN_RADIUS * 2.0) as f32, (PIN_RADIUS * 2.0) as f32);
                // the cover cut to a square in the circle (its middle)
                let (tw, th) = (f64::from(t.width()), f64::from(t.height()));
                let fit = (PIN_RADIUS * 2.0) / tw.min(th);
                let (dw, dh) = (tw * fit, th * fit);
                let drawn = graphene::Rect::new((x - dw / 2.0) as f32, (y - dh / 2.0) as f32, dw as f32, dh as f32);
                snapshot.push_rounded_clip(&gsk::RoundedRect::from_rect(r, PIN_RADIUS as f32));
                snapshot.append_scaled_texture(t, gsk::ScalingFilter::Linear, &drawn);
                snapshot.pop();
            } else {
                text(canvas, snapshot, "●", x, y, &white);
            }
            snapshot.append_stroke(&path, &gsk::Stroke::new(2.5), &white);
            if c.count > 1 {
                let (bx, by) = (x + PIN_RADIUS * 0.75, y - PIN_RADIUS * 0.75);
                let badge = circle(bx, by, 10.0);
                snapshot.append_fill(&badge, gsk::FillRule::Winding, &gdk::RGBA::new(0.1, 0.1, 0.12, 0.9));
                text(canvas, snapshot, &c.count.to_string(), bx, by, &white);
            }
        }
        drop(covers);
        // the place editor's fixed centre pin: its tip on the centre
        if s.centre_pin {
            let (x, y) = (w / 2.0, h / 2.0);
            let b = gsk::PathBuilder::new();
            b.move_to(x as f32, y as f32);
            b.line_to((x - 8.0) as f32, (y - 16.0) as f32);
            b.line_to((x + 8.0) as f32, (y - 16.0) as f32);
            b.close();
            b.add_circle(&graphene::Point::new(x as f32, (y - 20.0) as f32), 10.0);
            let pin = b.to_path();
            snapshot.append_fill(&pin, gsk::FillRule::Winding, &accent);
            snapshot.append_stroke(&pin, &gsk::Stroke::new(2.0), &white);
            snapshot.append_fill(&circle(x, y - 20.0, 3.5), gsk::FillRule::Winding, &white);
        }
    }
}

/// [s] centred on ([x], [y]) in [color], in the widget's own font.
fn text(canvas: &MapCanvas, snapshot: &gtk::Snapshot, s: &str, x: f64, y: f64, color: &gdk::RGBA) {
    let layout = canvas.create_pango_layout(Some(s));
    let (lw, lh) = layout.pixel_size();
    snapshot.save();
    snapshot.translate(&graphene::Point::new((x - f64::from(lw) / 2.0) as f32, (y - f64::from(lh) / 2.0) as f32));
    snapshot.append_layout(&layout, color);
    snapshot.restore();
}

/// A pin's Memory opened where the shell opens an object.
fn open_pin(pin: &PinShown) {
    eprintln!("veyq: map: open {}", pin.node.node_id);
    if let Some(page) = &pin.page {
        bridge::send(Call::ShellOpen { r#ref: page.clone() });
    } else {
        crate::memory_page::open_node(&pin.node);
    }
}

/// The Memories sharing one spot at the highest zoom, listed in a dialog; a row opens its Memory.
fn list_pins(window: &gtk::Window, pins: &[PinShown]) {
    eprintln!("veyq: map: {} Memories listed at one spot", pins.len());
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("boxed-list");
    let dialog = adw::AlertDialog::builder().heading(words::map_cluster_title(pins.len() as i64)).extra_child(&list).build();
    dialog.add_response("close", &words::entry_cancel());
    dialog.set_close_response("close");
    for p in pins {
        let row = adw::ActionRow::builder().title(glib_escape(&p.node.name)).activatable(true).build();
        row.add_prefix(&gtk::Image::from_icon_name(crate::home::kind_icon(&p.node.kind)));
        let (pin, d) = (p.clone(), dialog.downgrade());
        row.connect_activated(move |_| {
            if let Some(d) = d.upgrade() {
                d.close();
            }
            open_pin(&pin);
        });
        list.append(&row);
    }
    dialog.present(Some(window));
}

/// The attribution's look: small, at the map's foot, its text readable over any tile.
fn style_once() {
    thread_local! { static DONE: Cell<bool> = const { Cell::new(false) }; }
    if DONE.with(|d| d.replace(true)) {
        return;
    }
    let css = gtk::CssProvider::new();
    css.load_from_string(".map-attribution { padding: 2px 6px; margin: 0; border-radius: 6px 0 0 0; }");
    if let Some(display) = gdk::Display::default() {
        gtk::style_context_add_provider_for_display(&display, &css, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The one formula: the view's centre is the widget's middle, one tile at zoom 0 is 256 px, and a point half a tile
    /// east at zoom 1 is 128 px right of the middle.
    #[test]
    fn a_normalised_point_lands_where_the_viewports_formula_puts_it() {
        assert_eq!(px(0.5, 0.5, 0.5, 0.5, 3.0, 400.0, 300.0), (200.0, 150.0));
        assert_eq!(px(0.75, 0.5, 0.5, 0.5, 0.0, 400.0, 300.0), (264.0, 150.0));
        let (x, y) = px(0.5 + 0.25, 0.5 - 0.125, 0.5, 0.5, 1.0, 0.0, 0.0);
        assert!((x - 128.0).abs() < 1e-9 && (y + 64.0).abs() < 1e-9);
    }

    /// A track's colour arrives as ARGB.
    #[test]
    fn a_colour_reads_as_argb() {
        let c = argb(0xFF_E5_39_35);
        assert_eq!((c.alpha(), c.red()), (1.0, 229.0 / 255.0));
        assert!((c.green() - 57.0 / 255.0).abs() < 1e-6 && (c.blue() - 53.0 / 255.0).abs() < 1e-6);
    }

    /// The consent names both recipients, a renewal says it asks again, and no tile host names none.
    #[test]
    fn the_consent_names_its_recipients() {
        assert_eq!(consent_hosts(Some("tile.example"), Some("geo.example"), false).as_deref(), Some(words::map_consent_hosts("tile.example", "geo.example").as_str()));
        assert_eq!(consent_hosts(Some("tile.example"), None, true).as_deref(), Some(words::map_consent_renew_tiles("tile.example").as_str()));
        assert_eq!(consent_hosts(None, Some("geo.example"), false), None);
    }
}
