//! A Memory's banner on Linux (ADR 0144 decisions 4 and 5; Windows' `Banner.cs`, the web's `MemoryBanner`): above the
//! page's head, scrolling away with it, the picture whole in the core's frame (`Banner.Frame`: 16:9 above a 720 px column,
//! 1:1 at or below, never taller than 60 % of the window) and behind it the same picture scaled down to the core's
//! surround size and stretched back with cairo's bilinear filter — a blur by resampling, the web's 32 px trick, with no
//! blur API. A video's poster carries a play badge and the frame is its button (GNOME floats no button over a view); a click plays the banner through GTK's player over the core's reader
//! ([crate::core_ui::player]), the poster's surround staying behind it. The banner's dialog (`Banner.Open`, `Watched.Banner`) is an
//! `AdwDialog`: Cancel and *Save banner* in its header bar, *Choose an image or a video* (GTK's file dialog, images and
//! videos), the two previews drawn the same way, the save's progress with Cancel, *Remove banner*. Every rule is the
//! core's; the platform decodes, scales and draws.
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::{cairo, gdk, gio, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::BannerEditorShown, wire::BannerMedium, wire::BannerSaid, wire::BannerSave,
    wire::BannerShown, wire::Call, wire::Update, wire::Watched};
use crate::core_ui::player::Playing;
use crate::core_ui::words;

/// A picture held for drawing: the whole picture and its surround (scaled down to the core's size), both cairo surfaces.
#[derive(Clone)]
pub struct Drawn {
    whole: cairo::ImageSurface,
    surround: cairo::ImageSurface,
}

/// [texture] as cairo's surface (ARGB32, premultiplied).
fn surface_of(texture: &gdk::Texture) -> Option<cairo::ImageSurface> {
    let mut downloader = gdk::TextureDownloader::new(texture);
    downloader.set_format(gdk::MemoryFormat::B8g8r8a8Premultiplied);
    let (bytes, stride) = downloader.download_bytes();
    cairo::ImageSurface::create_for_data(bytes.to_vec(), cairo::Format::ARgb32, texture.width(), texture.height(), stride as i32).ok()
}

/// [whole] drawn into a surface [width] × [height] (the surround's size the core named).
fn scaled_to(whole: &cairo::ImageSurface, width: i32, height: i32) -> Option<cairo::ImageSurface> {
    let out = cairo::ImageSurface::create(cairo::Format::ARgb32, width.max(1), height.max(1)).ok()?;
    {
        let cr = cairo::Context::new(&out).ok()?;
        cr.scale(f64::from(width.max(1)) / f64::from(whole.width()), f64::from(height.max(1)) / f64::from(whole.height()));
        cr.set_source_surface(whole, 0.0, 0.0).ok()?;
        cr.source().set_filter(cairo::Filter::Best);
        cr.paint().ok()?;
    }
    out.flush();
    Some(out)
}

/// The surround's size for a picture of [width] × [height] pixels (`Banner.Frame` answers it with any frame).
async fn surround_size(width: i32, height: i32) -> Option<(i32, i32)> {
    match bridge::call(&Call::BannerFrame { column_width: 1000.0, viewport_height: 1000.0, picture_width: width, picture_height: height }).await {
        Ok((Answer::BannerFramed { surround_width, surround_height, .. }, _)) => Some((surround_width, surround_height)),
        other => {
            eprintln!("veyq: Banner.Frame answered {:?}", other.map(|(a, _)| a));
            None
        }
    }
}

/// A picture's bytes decoded and held for drawing: its whole and its surround.
pub async fn decode(bytes: Vec<u8>) -> Option<Drawn> {
    let texture = gdk::Texture::from_bytes(&glib::Bytes::from_owned(bytes)).map_err(|e| eprintln!("veyq: a banner picture did not decode: {e}")).ok()?;
    let whole = surface_of(&texture)?;
    let (sw, sh) = surround_size(whole.width(), whole.height()).await?;
    let surround = scaled_to(&whole, sw, sh)?;
    Some(Drawn { whole, surround })
}

/// Draws [drawn] into a frame [width] × [height]: the surround stretched to cover it (bilinear: the blur), the picture
/// contained in it, centred.
pub fn paint(cr: &cairo::Context, drawn: &Drawn, width: f64, height: f64) {
    let (frame_w, frame_h) = (width.max(1.0), height.max(1.0));
    let (ow, oh) = (f64::from(drawn.surround.width()), f64::from(drawn.surround.height()));
    let cover = (frame_w / ow).max(frame_h / oh);
    let _ = cr.save();
    cr.rectangle(0.0, 0.0, frame_w, frame_h);
    cr.clip();
    cr.translate((frame_w - ow * cover) / 2.0, (frame_h - oh * cover) / 2.0);
    cr.scale(cover, cover);
    if cr.set_source_surface(&drawn.surround, 0.0, 0.0).is_ok() {
        cr.source().set_filter(cairo::Filter::Bilinear);
        cr.source().set_extend(cairo::Extend::Pad);
        let _ = cr.paint();
    }
    let _ = cr.restore();
    let (pw, ph) = (f64::from(drawn.whole.width()), f64::from(drawn.whole.height()));
    let contain = (frame_w / pw).min(frame_h / ph);
    let _ = cr.save();
    cr.translate((frame_w - pw * contain) / 2.0, (frame_h - ph * contain) / 2.0);
    cr.scale(contain, contain);
    if cr.set_source_surface(&drawn.whole, 0.0, 0.0).is_ok() {
        cr.source().set_filter(cairo::Filter::Good);
        let _ = cr.paint();
    }
    let _ = cr.restore();
}

/// The banner above a page: built once, drawn from each gallery state.
pub struct BannerView {
    inner: Rc<Inner>,
}

struct Inner {
    root: gtk::Overlay,
    area: gtk::DrawingArea,
    play: gtk::Image,
    gallery: Cell<Option<i64>>,
    /// The banner drawn (its picture's cache key), the picture held, and the frame last asked for (column, viewport).
    key: RefCell<Option<String>>,
    medium: Cell<Option<BannerMedium>>,
    drawn: RefCell<Option<Drawn>>,
    asked: Cell<(i32, i32)>,
    playing: RefCell<Option<Playing>>,
    toasts: adw::ToastOverlay,
}

impl BannerView {
    pub fn new(toasts: &adw::ToastOverlay) -> Self {
        let area = gtk::DrawingArea::builder().hexpand(true).content_height(1).accessible_role(gtk::AccessibleRole::Button).build();
        area.set_widget_name("banner-frame");
        area.set_overflow(gtk::Overflow::Hidden);
        area.add_css_class("card");
        let root = gtk::Overlay::builder().child(&area).visible(false).build();
        root.set_widget_name("memory-banner");
        // a video's poster is the play button (GNOME has no button floated over a view): its badge, the frame the target
        let play = gtk::Image::builder().icon_name("media-playback-start-symbolic").pixel_size(32).halign(gtk::Align::Center).valign(gtk::Align::Center)
            .can_target(false).visible(false).build();
        play.add_css_class("osd");
        play.add_css_class("circular");
        root.add_overlay(&play);
        let inner = Rc::new(Inner {
            root,
            area,
            play,
            gallery: Cell::new(None),
            key: RefCell::new(None),
            medium: Cell::new(None),
            drawn: RefCell::new(None),
            asked: Cell::new((0, 0)),
            playing: RefCell::new(None),
            toasts: toasts.clone(),
        });
        let held = Rc::downgrade(&inner);
        inner.area.set_draw_func(move |_, cr, width, height| {
            if let Some(i) = held.upgrade()
                && let Some(d) = i.drawn.borrow().as_ref()
            {
                paint(cr, d, f64::from(width), f64::from(height));
            }
        });
        // the frame asked anew at each width (a size asked for during an allocation is taken at the next turn of the loop)
        let held = Rc::downgrade(&inner);
        inner.area.connect_resize(move |_, _, _| {
            if let Some(i) = held.upgrade() {
                glib::idle_add_local_once(move || i.frame());
            }
        });
        let held = Rc::downgrade(&inner);
        let click = gtk::GestureClick::new();
        click.connect_released(move |gesture, _, _, _| {
            if let Some(i) = held.upgrade()
                && i.play.is_visible()
            {
                gesture.set_state(gtk::EventSequenceState::Claimed);
                eprintln!("veyq: banner play pressed");
                i.start();
            }
        });
        inner.area.add_controller(click);
        let held = Rc::downgrade(&inner);
        let keys = gtk::EventControllerKey::new();
        keys.connect_key_pressed(move |_, key, _, _| {
            if matches!(key, gdk::Key::Return | gdk::Key::KP_Enter | gdk::Key::space)
                && let Some(i) = held.upgrade()
                && i.play.is_visible()
            {
                i.start();
                return glib::Propagation::Stop;
            }
            glib::Propagation::Proceed
        });
        inner.area.add_controller(keys);
        Self { inner }
    }

    /// The banner's dialog over [window] (*Add banner* where there is none, *Change banner* else).
    pub fn edit(&self, window: &gtk::Window) {
        self.inner.edit(window);
    }

    pub fn widget(&self) -> &gtk::Overlay {
        &self.inner.root
    }

    /// The banner as gallery [gallery]'s state says it: none hides the frame; a new picture is read (`Banner.Picture`) and
    /// drawn once it decoded — one that does not read shows nothing.
    pub fn draw(&self, banner: Option<&BannerShown>, gallery: i64) {
        let i = &self.inner;
        i.gallery.set(Some(gallery));
        let key = banner.map(|b| b.picture.clone());
        if *i.key.borrow() == key {
            return;
        }
        eprintln!("veyq: banner: {}", banner.map_or("none".into(), |b| format!("{:?}", b.medium)));
        *i.key.borrow_mut() = key;
        i.medium.set(banner.map(|b| b.medium));
        i.stop();
        *i.drawn.borrow_mut() = None;
        i.root.set_visible(false);
        i.video(false);
        if banner.is_none() {
            return;
        }
        let (weak, asked) = (Rc::downgrade(i), i.key.borrow().clone());
        glib::spawn_future_local(async move {
            let answered = bridge::call(&Call::BannerPicture { gallery }).await;
            let bytes = match answered {
                Ok((Answer::Bytes { .. }, Some(bytes))) => bytes,
                other => return eprintln!("veyq: Banner.Picture answered {:?}", other.map(|(a, _)| a)),
            };
            let Some(drawn) = decode(bytes).await else { return };
            let Some(i) = weak.upgrade().filter(|i| *i.key.borrow() == asked) else { return };
            *i.drawn.borrow_mut() = Some(drawn);
            i.asked.set((0, 0));
            i.root.set_visible(true);
            i.video(i.medium.get() == Some(BannerMedium::Video));
            i.frame();
            i.area.queue_draw();
        });
    }
}

impl Drop for BannerView {
    fn drop(&mut self) {
        self.inner.stop();
    }
}

impl Inner {
    /// Whether the frame offers its video: the badge shown, the frame a button (focusable, named) while it does.
    fn video(&self, offered: bool) {
        self.play.set_visible(offered);
        self.area.set_focusable(offered);
        self.area.set_cursor_from_name(offered.then_some("pointer"));
        self.area.set_tooltip_text(offered.then(words::memory_banner_play).as_deref());
        self.area.update_property(&[gtk::accessible::Property::Label(&if offered { words::memory_banner_play() } else { String::new() })]);
    }

    fn edit(&self, window: &gtk::Window) {
        if let Some(gallery) = self.gallery.get() {
            BannerDialog::open(gallery, window, &self.toasts, self.drawn.borrow().clone());
        }
    }

    /// The frame for the area's width in the window's height (`Banner.Frame`), its height set once answered.
    fn frame(self: &Rc<Self>) {
        let Some((pw, ph)) = self.drawn.borrow().as_ref().map(|d| (d.whole.width(), d.whole.height())) else { return };
        let width = self.area.width();
        let viewport = self.area.root().map_or(800, |r| r.height());
        if width <= 0 || self.asked.replace((width, viewport)) == (width, viewport) {
            return;
        }
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let call = Call::BannerFrame { column_width: f64::from(width), viewport_height: f64::from(viewport), picture_width: pw, picture_height: ph };
            match bridge::call(&call).await.map(|(a, _)| a) {
                Ok(Answer::BannerFramed { height, wide, .. }) => {
                    let Some(i) = weak.upgrade() else { return };
                    eprintln!("veyq: banner framed {width} × {height:.0} ({}) in a {viewport} high window", if wide { "16:9" } else { "1:1" });
                    i.area.set_content_height(height.round() as i32);
                    i.area.queue_draw();
                }
                other => eprintln!("veyq: Banner.Frame answered {other:?}"),
            }
        });
    }

    /// The video banner played over its poster (`Banner.Play`), with GTK's controls; nothing starts on its own.
    fn start(self: &Rc<Self>) {
        let Some(gallery) = self.gallery.get() else { return };
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let (reader, size) = match bridge::call(&Call::BannerPlay { gallery }).await.map(|(a, _)| a) {
                Ok(Answer::Reader { reader, size, media_type }) => {
                    eprintln!("veyq: banner video: reader {reader}, {size} B, {}", media_type.as_deref().unwrap_or("no type"));
                    (reader, size)
                }
                other => return eprintln!("veyq: Banner.Play answered {other:?}"),
            };
            let Some(i) = weak.upgrade() else { return bridge::send(Call::ReaderClose { reader }) };
            let failed = Rc::downgrade(&i);
            let playing = Playing::new(reader, size, &words::memory_banner_video(), true, move || {
                if let Some(i) = failed.upgrade() {
                    i.stop();
                    i.toasts.add_toast(adw::Toast::new(&words::memory_banner_play_failed()));
                }
            });
            playing.on_playing(|on| eprintln!("veyq: banner video {}", if on { "playing" } else { "stopped" }));
            i.video(false);
            i.root.add_overlay(&playing.video);
            *i.playing.borrow_mut() = Some(playing);
        });
    }

    /// The player let go (its stream closed, the core's reader with it), the poster's play button back.
    fn stop(&self) {
        if let Some(p) = self.playing.borrow_mut().take() {
            self.root.remove_overlay(&p.video);
            p.stop();
            self.video(self.medium.get() == Some(BannerMedium::Video) && self.drawn.borrow().is_some());
        }
    }
}

// --- the banner's dialog ---------------------------------------------------------------------------------------------

/// What the dialog says for the core's [said].
pub fn said_text(said: BannerSaid) -> String {
    match said {
        BannerSaid::TooLarge => words::memory_banner_too_large(&format!("40 {}", words::storage_unit_mb())),
        BannerSaid::Unreadable | BannerSaid::NotMedia => words::memory_banner_unreadable(),
        BannerSaid::VideoUnreadable => words::memory_banner_video_unreadable(),
        BannerSaid::Stale => words::memory_banner_stale(),
        BannerSaid::Busy | BannerSaid::Failed => words::memory_banner_failed(),
    }
}

struct BannerDialog {
    id: Cell<Option<i64>>,
    dialog: adw::Dialog,
    title: adw::WindowTitle,
    save: gtk::Button,
    choose: gtk::Button,
    remove: gtk::Button,
    wide: gtk::DrawingArea,
    phone: gtk::DrawingArea,
    progress: gtk::ProgressBar,
    progress_label: gtk::Label,
    stop: gtk::Button,
    said: gtk::Label,
    /// The picture the previews draw: the banner's own until a file is picked, then the pick's.
    drawn: Rc<RefCell<Option<Drawn>>>,
    pick: Cell<Option<i64>>,
    watch: RefCell<Option<Watch>>,
    window: gtk::Window,
}

/// A preview's area, [width] × [height], drawing [drawn].
fn preview(width: i32, height: i32, drawn: &Rc<RefCell<Option<Drawn>>>, name: &str) -> gtk::DrawingArea {
    let area = gtk::DrawingArea::builder().content_width(width).content_height(height).halign(gtk::Align::Center).build();
    area.set_overflow(gtk::Overflow::Hidden);
    area.add_css_class("card");
    area.set_widget_name(name);
    let held = drawn.clone();
    area.set_draw_func(move |_, cr, w, h| {
        if let Some(d) = held.borrow().as_ref() {
            paint(cr, d, f64::from(w), f64::from(h));
        }
    });
    area
}

impl BannerDialog {
    fn open(gallery: i64, window: &gtk::Window, toasts: &adw::ToastOverlay, current: Option<Drawn>) {
        let has = current.is_some();
        let title = adw::WindowTitle::new(&if has { words::memory_banner_change() } else { words::memory_banner_add() }, "");
        let bar = adw::HeaderBar::builder().title_widget(&title).show_end_title_buttons(false).show_start_title_buttons(false).build();
        // tall enough for the previews, the progress and *Remove banner* at once (a dialog does not grow when a state shows more)
        let dialog = adw::Dialog::builder().content_width(460).content_height(660).build();
        let cancel = gtk::Button::builder().label(words::entry_cancel()).build();
        let d = dialog.downgrade();
        cancel.connect_clicked(move |_| {
            if let Some(d) = d.upgrade() {
                d.close();
            }
        });
        bar.pack_start(&cancel);
        let save = gtk::Button::builder().label(words::memory_banner_save()).visible(false).build();
        save.add_css_class("suggested-action");
        save.set_widget_name("banner-save");
        bar.pack_end(&save);
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).margin_top(12).margin_bottom(18).margin_start(18).margin_end(18).build();
        let hint = gtk::Label::builder().label(words::memory_banner_hint()).wrap(true).xalign(0.0).build();
        hint.add_css_class("dim-label");
        column.append(&hint);
        let choose = gtk::Button::builder().label(words::memory_banner_choose()).halign(gtk::Align::Center).build();
        choose.add_css_class("pill");
        choose.set_widget_name("banner-choose");
        column.append(&choose);
        let drawn = Rc::new(RefCell::new(current));
        let wide = preview(320, 180, &drawn, "banner-preview-wide");
        let phone = preview(180, 180, &drawn, "banner-preview-phone");
        let previews = gtk::Box::builder().spacing(18).halign(gtk::Align::Center).build();
        for (area, label) in [(&wide, words::memory_banner_preview_wide()), (&phone, words::memory_banner_preview_phone())] {
            let b = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).valign(gtk::Align::End).build();
            b.append(area);
            let l = gtk::Label::new(Some(&label));
            l.add_css_class("caption");
            b.append(&l);
            previews.append(&b);
        }
        column.append(&previews);
        let progress = gtk::ProgressBar::builder().visible(false).build();
        let progress_label = gtk::Label::builder().xalign(0.0).visible(false).build();
        progress_label.add_css_class("caption");
        let stop = gtk::Button::builder().label(words::upload_cancel()).halign(gtk::Align::Start).visible(false).build();
        stop.set_widget_name("banner-cancel");
        column.append(&progress_label);
        column.append(&progress);
        column.append(&stop);
        let said = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
        said.add_css_class("error");
        said.set_widget_name("banner-said");
        column.append(&said);
        let remove = gtk::Button::builder().label(words::memory_banner_remove()).halign(gtk::Align::Center).visible(false).build();
        remove.add_css_class("destructive-action");
        remove.set_widget_name("banner-remove");
        column.append(&remove);
        let view = adw::ToolbarView::new();
        view.add_top_bar(&bar);
        view.set_content(Some(&gtk::ScrolledWindow::builder().child(&column).vexpand(true).hscrollbar_policy(gtk::PolicyType::Never).build()));
        dialog.set_child(Some(&view));
        let this = Rc::new(Self {
            id: Cell::new(None),
            dialog,
            title,
            save,
            choose,
            remove,
            wide,
            phone,
            progress,
            progress_label,
            stop,
            said,
            drawn,
            pick: Cell::new(None),
            watch: RefCell::new(None),
            window: window.clone(),
        });
        this.connect(toasts);
        // held until the dialog closes: a state only weak handlers reach is dropped at once (src/mobile/CLAUDE.md § Traps)
        let held = RefCell::new(Some(this.clone()));
        this.dialog.connect_closed(move |_| {
            if let Some(e) = held.borrow_mut().take() {
                e.watch.borrow_mut().take();
                if let Some(banner) = e.id.take() {
                    bridge::send(Call::BannerClose { banner });
                }
            }
        });
        let weak = Rc::downgrade(&this);
        glib::spawn_future_local(async move {
            let banner = match bridge::call(&Call::BannerOpen { gallery }).await.map(|(a, _)| a) {
                Ok(Answer::BannerOpened { banner }) => banner,
                other => {
                    eprintln!("veyq: Banner.Open answered {other:?}");
                    if let Some(e) = weak.upgrade() {
                        e.dialog.force_close();
                    }
                    return;
                }
            };
            let Some(e) = weak.upgrade() else { return bridge::send(Call::BannerClose { banner }) };
            e.id.set(Some(banner));
            eprintln!("veyq: banner dialog open");
            let Some((watch, updates)) = bridge::watch(&Watched::Banner { banner }) else { return };
            *e.watch.borrow_mut() = Some(watch);
            drop(e);
            while let Ok(update) = updates.recv().await {
                let Some(e) = weak.upgrade() else { return };
                match update {
                    Ok(Update::Banner { shown: Some(s), .. }) => e.draw(&s),
                    Ok(Update::Banner { shown: None, .. }) => {
                        e.id.set(None);
                        e.dialog.force_close();
                        return;
                    }
                    Ok(_) => {}
                    Err(err) => eprintln!("veyq: a banner dialog update did not read: {:?}", err.0),
                }
            }
        });
        this.dialog.present(Some(window));
    }

    fn connect(self: &Rc<Self>, toasts: &adw::ToastOverlay) {
        let weak = Rc::downgrade(self);
        self.choose.connect_clicked(move |_| {
            if let Some(e) = weak.upgrade() {
                e.choose();
            }
        });
        let weak = Rc::downgrade(self);
        self.stop.connect_clicked(move |_| {
            if let Some(e) = weak.upgrade()
                && let Some(banner) = e.id.get()
            {
                eprintln!("veyq: banner save cancel pressed");
                bridge::send(Call::BannerCancel { banner });
            }
        });
        for remove in [false, true] {
            let (weak, toasts) = (Rc::downgrade(self), toasts.clone());
            let button = if remove { &self.remove } else { &self.save };
            button.connect_clicked(move |_| {
                let Some(e) = weak.upgrade() else { return };
                let Some(banner) = e.id.get() else { return };
                eprintln!("veyq: banner {} pressed", if remove { "remove" } else { "save" });
                let (weak, toasts) = (Rc::downgrade(&e), toasts.clone());
                glib::spawn_future_local(async move {
                    let call = if remove { Call::BannerRemove { banner } } else { Call::BannerSave { banner } };
                    let answered = bridge::call(&call).await.map(|(a, _)| a);
                    eprintln!("veyq: {} answered {answered:?}", if remove { "Banner.Remove" } else { "Banner.Save" });
                    if let (Ok(Answer::BannerSaved { outcome: BannerSave::Done }), Some(e)) = (&answered, weak.upgrade()) {
                        e.dialog.close();
                    } else if !matches!(answered, Ok(Answer::BannerSaved { .. })) {
                        toasts.add_toast(adw::Toast::new(&words::memory_banner_failed()));
                    }
                });
            });
        }
    }

    /// GTK's open dialog for one image or video; its path to the core (`Banner.Pick`), which reads it where it lies.
    fn choose(self: &Rc<Self>) {
        let Some(banner) = self.id.get() else { return };
        let (weak, parent) = (Rc::downgrade(self), self.window.clone());
        glib::spawn_future_local(async move {
            let filter = gtk::FileFilter::new();
            filter.set_name(Some(&words::memory_banner_choose()));
            filter.add_mime_type("image/*");
            filter.add_mime_type("video/*");
            let filters = gio::ListStore::new::<gtk::FileFilter>();
            filters.append(&filter);
            let dialog = gtk::FileDialog::builder().title(words::memory_banner_choose()).modal(true).filters(&filters).default_filter(&filter).build();
            if let Some(pictures) = glib::user_special_dir(glib::UserDirectory::Pictures).filter(|d| d.is_dir()) {
                dialog.set_initial_folder(Some(&gio::File::for_path(pictures)));
            }
            let file = match dialog.open_future(Some(&parent)).await {
                Ok(file) => file,
                Err(e) => {
                    if !e.matches(gtk::DialogError::Dismissed) && !e.matches(gtk::DialogError::Cancelled) {
                        eprintln!("veyq: the open dialog failed: {e}");
                    }
                    return;
                }
            };
            let Some(path) = file.path().and_then(|p| p.to_str().map(str::to_owned)) else { return };
            if weak.upgrade().is_none() {
                return;
            }
            eprintln!("veyq: banner picked");
            let answered = bridge::call(&Call::BannerPick { banner, path }).await.map(|(a, _)| a);
            eprintln!("veyq: Banner.Pick answered {answered:?}");
        });
    }

    fn draw(self: &Rc<Self>, s: &BannerEditorShown) {
        eprintln!("veyq: banner dialog shown: has {}, picking {}, picked {:?}, saving {}, progress {:?}, said {:?}, done {}, save {}, remove {}",
            s.has_banner, s.picking, s.picked.as_ref().map(|p| p.medium), s.saving, s.progress, s.said, s.done, s.can_save, s.can_remove);
        self.title.set_title(&if s.has_banner { words::memory_banner_change() } else { words::memory_banner_add() });
        self.save.set_visible(s.can_save || s.saving);
        self.save.set_sensitive(s.can_save && !s.saving);
        self.choose.set_sensitive(!s.saving && !s.picking);
        self.remove.set_visible(s.can_remove && s.picked.is_none());
        self.remove.set_sensitive(!s.saving);
        self.progress.set_visible(s.saving);
        self.progress_label.set_visible(s.saving);
        self.stop.set_visible(s.saving && s.progress.is_some());
        match s.progress {
            Some(p) if p > 0.0 => {
                self.progress.set_fraction(p);
                self.progress_label.set_label(&words::memory_banner_uploading((p * 100.0).round() as i64));
            }
            _ => {
                self.progress.pulse();
                self.progress_label.set_label(&words::memory_banner_preparing());
            }
        }
        self.said.set_visible(s.said.is_some());
        if let Some(said) = s.said {
            self.said.set_label(&said_text(said));
        }
        if s.done {
            self.dialog.close();
            return;
        }
        // a new pick: its picture for the previews (`Banner.Preview`)
        let pick = s.picked.as_ref().map(|p| p.pick);
        if pick.is_some() && self.pick.replace(pick) != pick {
            let (Some(banner), weak) = (self.id.get(), Rc::downgrade(self)) else { return };
            glib::spawn_future_local(async move {
                let bytes = match bridge::call(&Call::BannerPreview { banner }).await {
                    Ok((Answer::Bytes { .. }, Some(bytes))) => bytes,
                    other => return eprintln!("veyq: Banner.Preview answered {:?}", other.map(|(a, _)| a)),
                };
                let Some(drawn) = decode(bytes).await else { return };
                let Some(e) = weak.upgrade() else { return };
                *e.drawn.borrow_mut() = Some(drawn);
                e.wide.queue_draw();
                e.phone.queue_draw();
                eprintln!("veyq: banner previews drawn");
            });
        }
    }
}
