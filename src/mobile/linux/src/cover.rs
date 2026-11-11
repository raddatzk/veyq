//! A node's cover (the header CR's Phase 5; the web's `CoverDialog` around its 16:9 cropper), GNOME's way: an `AdwDialog`
//! with Cancel and Save in its header bar over the 16:9 frame, a zoom slider under it, "Choose a picture" (GTK's file
//! dialog, the portal's where there is one) and, where the node has a cover, "Remove cover". A drag pans the picture.
//!
//! The device's part is the platform's (ADR 0127 decision 1): GTK decodes the picked picture (`gdk::Texture`, glycin's
//! loaders), cairo scales it to the size the core names (`Cover.Fit`, the web's `COVER_SPEC`) and draws it, gdk-pixbuf
//! encodes the JPEG at the core's quality into a file the core reads (`Header.CoverSave` by path, ADR 0128 decision 3),
//! which is removed after. Where the picture sits is the core's math (`Cover.Pose`, `CoverFrame`), asked per change: the
//! answer of the latest ask is drawn, an older one that arrives after it is dropped.
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::{cairo, gdk, gio, glib};

use crate::bridge::{self, wire::Answer, wire::Call, wire::Crop, wire::NodeAt, wire::Pose};
use crate::words;

/// A picture held for the frame: cairo's surface at the size it is framed (and, picked here, encoded) at.
struct Picture {
    surface: cairo::ImageSurface,
    width: i32,
    height: i32,
    /// Picked here (to be uploaded), or the stored cover (only its crop moves).
    picked: bool,
    /// The JPEG quality the core named for a picked picture, 0–100.
    quality: i32,
}

struct Inner {
    header: i64,
    dialog: adw::Dialog,
    area: gtk::DrawingArea,
    zoom: gtk::Scale,
    save: gtk::Button,
    cancel: gtk::Button,
    choose: gtk::Button,
    problem: gtk::Label,
    spinner: adw::Spinner,
    picture: RefCell<Option<Picture>>,
    /// Where the picture sits, the size it is drawn at there and the crop that shows: the core's last answer drawn.
    pose: RefCell<Option<Pose>>,
    drawn: Cell<(f64, f64)>,
    crop: RefCell<Option<Crop>>,
    /// The stored cover's crop, until its picture is framed.
    stored: RefCell<Option<Crop>>,
    /// The number of the latest ask: an answer of an older one is not drawn.
    asked: Cell<u64>,
    /// The pose a drag started at.
    drag_from: RefCell<Option<Pose>>,
    /// A draw sets the slider: its signal is not the person's.
    drawing: Cell<bool>,
    saving: Cell<bool>,
}

/// Opens the cover dialog of [node] over [parent] for header [header], whose cover is framed at [stored] (none: no cover).
pub fn open(parent: &impl IsA<gtk::Widget>, header: i64, node: &NodeAt, stored: Option<Crop>, toasts: &adw::ToastOverlay) {
    let bar = adw::HeaderBar::builder()
        .title_widget(&adw::WindowTitle::new(&words::cover_title(), &node.name))
        .show_end_title_buttons(false)
        .show_start_title_buttons(false)
        .build();
    let cancel = gtk::Button::with_label(&words::entry_cancel());
    let save = gtk::Button::with_label(&words::cover_save());
    save.add_css_class("suggested-action");
    save.set_sensitive(false);
    bar.pack_start(&cancel);
    bar.pack_end(&save);

    let hint = gtk::Label::builder().label(words::cover_hint()).wrap(true).xalign(0.0).build();
    hint.add_css_class("dim-label");
    let area = gtk::DrawingArea::builder().hexpand(true).build();
    area.update_property(&[gtk::accessible::Property::Label(&words::cover_title())]);
    let spinner = adw::Spinner::builder().width_request(32).height_request(32).halign(gtk::Align::Center).valign(gtk::Align::Center).visible(stored.is_some()).build();
    let overlay = gtk::Overlay::builder().child(&area).build();
    overlay.add_overlay(&spinner);
    overlay.set_overflow(gtk::Overflow::Hidden);
    overlay.add_css_class("card");
    let frame = gtk::AspectFrame::builder().ratio(16.0 / 9.0).obey_child(false).child(&overlay).build();
    let zoom = gtk::Scale::with_range(gtk::Orientation::Horizontal, 1.0, 3.0, 0.01);
    zoom.set_draw_value(false);
    zoom.set_sensitive(false);
    zoom.update_property(&[gtk::accessible::Property::Label(&words::cover_zoom())]);
    let zoom_row = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    zoom_row.append(&gtk::Image::from_icon_name("zoom-out-symbolic"));
    zoom.set_hexpand(true);
    zoom_row.append(&zoom);
    zoom_row.append(&gtk::Image::from_icon_name("zoom-in-symbolic"));
    let problem = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
    problem.add_css_class("error");
    let choose = gtk::Button::builder().label(words::cover_choose()).build();
    choose.add_css_class("pill");
    let remove = gtk::Button::builder().label(words::cover_remove()).visible(stored.is_some()).build();
    remove.add_css_class("pill");
    remove.add_css_class("destructive-action");
    let buttons = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    buttons.set_halign(gtk::Align::Center);
    buttons.append(&choose);
    buttons.append(&remove);

    let body = gtk::Box::new(gtk::Orientation::Vertical, 18);
    for w in [hint.upcast_ref::<gtk::Widget>(), frame.upcast_ref(), zoom_row.upcast_ref(), problem.upcast_ref(), buttons.upcast_ref()] {
        body.append(w);
    }
    let clamp = adw::Clamp::builder().maximum_size(640).margin_top(12).margin_bottom(18).margin_start(12).margin_end(12).child(&body).build();
    let view = adw::ToolbarView::new();
    view.add_top_bar(&bar);
    view.set_content(Some(&gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).child(&clamp).build()));
    let dialog = adw::Dialog::builder().content_width(600).content_height(560).child(&view).build();

    let inner = Rc::new(Inner {
        header,
        dialog: dialog.clone(),
        area: area.clone(),
        zoom: zoom.clone(),
        save: save.clone(),
        cancel: cancel.clone(),
        choose: choose.clone(),
        problem,
        spinner,
        picture: RefCell::new(None),
        pose: RefCell::new(None),
        drawn: Cell::new((0.0, 0.0)),
        crop: RefCell::new(None),
        stored: RefCell::new(stored.clone()),
        asked: Cell::new(0),
        drag_from: RefCell::new(None),
        drawing: Cell::new(false),
        saving: Cell::new(false),
    });

    let w = Rc::downgrade(&inner);
    area.set_draw_func(move |_, cr, width, height| {
        if let Some(i) = w.upgrade() {
            i.paint(cr, width, height);
        }
    });
    let w = Rc::downgrade(&inner);
    area.connect_resize(move |_, _, _| {
        if let Some(i) = w.upgrade() {
            // the frame's size changed: the same crop, posed anew for it
            let crop = i.crop.borrow().clone().or_else(|| i.stored.borrow().clone());
            i.pose(None, crop, 0.0, 0.0, 1.0);
        }
    });
    let drag = gtk::GestureDrag::new();
    let w = Rc::downgrade(&inner);
    drag.connect_drag_begin(move |_, _, _| {
        if let Some(i) = w.upgrade() {
            *i.drag_from.borrow_mut() = i.pose.borrow().clone();
        }
    });
    let w = Rc::downgrade(&inner);
    drag.connect_drag_update(move |_, dx, dy| {
        let Some(i) = w.upgrade() else { return };
        let from = i.drag_from.borrow().clone();
        if from.is_some() {
            i.pose(from, None, dx, dy, 1.0);
        }
    });
    area.add_controller(drag);
    let w = Rc::downgrade(&inner);
    zoom.connect_value_changed(move |s| {
        let Some(i) = w.upgrade() else { return };
        if i.drawing.get() {
            return;
        }
        let at = i.pose.borrow().clone();
        if let Some(at) = at {
            let factor = s.value() / at.zoom;
            i.pose(Some(at), None, 0.0, 0.0, factor);
        }
    });
    let w = Rc::downgrade(&inner);
    cancel.connect_clicked(move |_| {
        if let Some(i) = w.upgrade() {
            i.dialog.close();
        }
    });
    let w = Rc::downgrade(&inner);
    choose.connect_clicked(move |_| {
        if let Some(i) = w.upgrade() {
            i.choose();
        }
    });
    let (w, t) = (Rc::downgrade(&inner), toasts.clone());
    save.connect_clicked(move |_| {
        if let Some(i) = w.upgrade() {
            i.save(&t);
        }
    });
    let (w, t) = (Rc::downgrade(&inner), toasts.clone());
    remove.connect_clicked(move |_| {
        if let Some(i) = w.upgrade() {
            i.remove(&t);
        }
    });
    // the dialog holds its state while it is open; every handler reaches it weakly
    let held = RefCell::new(Some(inner.clone()));
    dialog.connect_closed(move |_| {
        held.borrow_mut().take();
    });
    dialog.present(Some(parent));

    // the stored cover: its picture asked of the core, framed as stored
    if stored.is_some() {
        let w = Rc::downgrade(&inner);
        glib::spawn_future_local(async move {
            let answered = bridge::call(&Call::HeaderCoverPicture { header }).await;
            let Some(i) = w.upgrade() else { return };
            i.spinner.set_visible(false);
            match answered {
                Ok((Answer::Bytes { .. }, Some(bytes))) => match gdk::Texture::from_bytes(&glib::Bytes::from_owned(bytes)) {
                    Ok(texture) => {
                        eprintln!("veyq: cover: the stored picture decoded, {}×{}", texture.width(), texture.height());
                        i.hold(&texture, texture.width(), texture.height(), false, 0);
                    }
                    Err(e) => {
                        eprintln!("veyq: cover: the stored picture did not decode: {e}");
                        i.say(&words::cover_unreadable());
                    }
                },
                other => eprintln!("veyq: Header.CoverPicture answered {:?}", other.map(|(a, _)| a)),
            }
        });
    }
}

impl Inner {
    /// Asks the core where the picture sits: from [at] (or the [crop] shown, or centred), panned and zoomed by [factor].
    /// Only the latest ask's answer is drawn.
    fn pose(self: &Rc<Self>, at: Option<Pose>, crop: Option<Crop>, pan_x: f64, pan_y: f64, factor: f64) {
        let (width, height) = (self.area.width(), self.area.height());
        let Some((iw, ih)) = self.picture.borrow().as_ref().map(|p| (p.width, p.height)) else { return };
        if width <= 0 || height <= 0 {
            return;
        }
        let n = self.asked.get() + 1;
        self.asked.set(n);
        let call = Call::CoverPose {
            image_width: iw as f64,
            image_height: ih as f64,
            frame_width: width as f64,
            frame_height: height as f64,
            at,
            crop,
            pan_x,
            pan_y,
            zoom: factor,
        };
        let w = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let answered = bridge::call(&call).await;
            let Some(i) = w.upgrade() else { return };
            if i.asked.get() != n {
                return;
            }
            match answered {
                Ok((Answer::CoverPosed { pose, width, height, crop }, _)) => {
                    i.drawn.set((width, height));
                    i.drawing.set(true);
                    i.zoom.set_value(pose.zoom);
                    i.drawing.set(false);
                    *i.pose.borrow_mut() = Some(pose);
                    *i.crop.borrow_mut() = Some(crop);
                    i.save.set_sensitive(!i.saving.get());
                    i.zoom.set_sensitive(!i.saving.get());
                    i.area.queue_draw();
                }
                other => eprintln!("veyq: Cover.Pose answered {:?}", other.map(|(a, _)| a)),
            }
        });
    }

    /// Holds [texture] scaled to [width] × [height] for the frame, and frames it: as stored, or a picked one centred.
    fn hold(self: &Rc<Self>, texture: &gdk::Texture, width: i32, height: i32, picked: bool, quality: i32) {
        let Some(surface) = scaled(texture, width, height) else {
            self.say(&words::cover_unreadable());
            return;
        };
        *self.picture.borrow_mut() = Some(Picture { surface, width, height, picked, quality });
        *self.pose.borrow_mut() = None;
        *self.crop.borrow_mut() = None;
        let crop = if picked { None } else { self.stored.borrow().clone() };
        self.pose(None, crop, 0.0, 0.0, 1.0);
    }

    fn paint(&self, cr: &cairo::Context, _width: i32, _height: i32) {
        let picture = self.picture.borrow();
        let (Some(p), Some(pose)) = (picture.as_ref(), self.pose.borrow().clone()) else { return };
        // at the pose and the size the core answered (`CoverFrame`'s math, never this host's)
        let (dw, dh) = self.drawn.get();
        cr.save().ok();
        cr.translate(pose.x, pose.y);
        cr.scale(dw / p.width as f64, dh / p.height as f64);
        if cr.set_source_surface(&p.surface, 0.0, 0.0).is_ok() {
            cr.source().set_filter(cairo::Filter::Good);
            cr.paint().ok();
        }
        cr.restore().ok();
    }

    fn say(&self, text: &str) {
        self.problem.set_label(text);
        self.problem.set_visible(true);
    }

    /// "Choose a picture": GTK's open dialog for one image, decoded by GTK, scaled to the size the core names.
    fn choose(self: &Rc<Self>) {
        let w = Rc::downgrade(self);
        let parent = self.dialog.root().and_downcast::<gtk::Window>();
        glib::spawn_future_local(async move {
            let images = gtk::FileFilter::new();
            images.add_mime_type("image/*");
            images.set_name(Some(&words::cover_choose()));
            let filters = gio::ListStore::new::<gtk::FileFilter>();
            filters.append(&images);
            let dialog = gtk::FileDialog::builder().title(words::cover_choose()).filters(&filters).default_filter(&images).modal(true).build();
            let file = match dialog.open_future(parent.as_ref()).await {
                Ok(file) => file,
                Err(e) => {
                    eprintln!("veyq: cover: no picture chosen ({e})");
                    return;
                }
            };
            let Some(i) = w.upgrade() else { return };
            i.problem.set_visible(false);
            let texture = match file.path().map(gdk::Texture::from_filename) {
                Some(Ok(t)) => t,
                other => {
                    eprintln!("veyq: cover: the picture did not decode: {:?}", other.map(|r| r.err()));
                    i.say(&words::cover_unreadable());
                    return;
                }
            };
            let (w0, h0) = (texture.width(), texture.height());
            match bridge::call(&Call::CoverFit { width: w0, height: h0 }).await.map(|(a, _)| a) {
                Ok(Answer::CoverFit { width, height, quality_percent, .. }) => {
                    eprintln!("veyq: cover: picked {w0}×{h0}, framed and encoded at {width}×{height}, quality {quality_percent}");
                    i.hold(&texture, width, height, true, quality_percent);
                }
                other => eprintln!("veyq: Cover.Fit answered {other:?}"),
            }
        });
    }

    fn busy(&self, on: bool) {
        self.saving.set(on);
        self.save.set_sensitive(!on && self.pose.borrow().is_some());
        self.zoom.set_sensitive(!on && self.pose.borrow().is_some());
        self.cancel.set_sensitive(!on);
        self.choose.set_sensitive(!on);
    }

    /// Save: a picked picture encoded to the core's spec into a file the core reads, framed by the crop shown; the stored
    /// one only framed anew. The dialog closes once the core said it landed, else says why.
    fn save(self: &Rc<Self>, toasts: &adw::ToastOverlay) {
        let Some(crop) = self.crop.borrow().clone() else { return };
        let picked = self.picture.borrow().as_ref().filter(|p| p.picked).map(|p| (encode(&p.surface, p.quality), p.width, p.height));
        let path = match picked {
            None => None,
            Some((Ok(path), w, h)) => {
                eprintln!("veyq: cover: encoded {w}×{h} to {path}");
                Some(path)
            }
            Some((Err(e), _, _)) => {
                eprintln!("veyq: cover: the JPEG was not written: {e}");
                self.say(&words::cover_failed());
                return;
            }
        };
        self.busy(true);
        let (w, toasts, header) = (Rc::downgrade(self), toasts.clone(), self.header);
        glib::spawn_future_local(async move {
            let answered = bridge::call(&Call::HeaderCoverSave { header, path: path.clone(), crop: crop.clone() }).await.map(|(a, _)| a);
            if let Some(p) = &path {
                let _ = std::fs::remove_file(p);
            }
            eprintln!("veyq: Header.CoverSave ({}, crop {:?}) answered {answered:?}", if path.is_some() { "picked" } else { "framed anew" }, crop);
            let Some(i) = w.upgrade() else { return };
            i.busy(false);
            match answered {
                Ok(Answer::Said { notice: None }) => {
                    let _ = &toasts;
                    i.dialog.close();
                }
                Ok(Answer::Said { notice: Some(n) }) => i.say(&crate::said::notice(&n)),
                _ => i.say(&words::cover_failed()),
            }
        });
    }

    fn remove(self: &Rc<Self>, toasts: &adw::ToastOverlay) {
        self.busy(true);
        let (w, toasts, header) = (Rc::downgrade(self), toasts.clone(), self.header);
        glib::spawn_future_local(async move {
            let answered = bridge::call(&Call::HeaderCoverRemove { header }).await.map(|(a, _)| a);
            eprintln!("veyq: Header.CoverRemove answered {answered:?}");
            let Some(i) = w.upgrade() else { return };
            i.busy(false);
            match answered {
                Ok(Answer::Said { notice: None }) => {
                    let _ = &toasts;
                    i.dialog.close();
                }
                Ok(Answer::Said { notice: Some(n) }) => i.say(&crate::said::notice(&n)),
                _ => i.say(&words::cover_failed()),
            }
        });
    }
}

/// [texture] drawn at [width] × [height] into cairo's surface (ARGB32, premultiplied: the frame draws it, the encoder reads it).
fn scaled(texture: &gdk::Texture, width: i32, height: i32) -> Option<cairo::ImageSurface> {
    let (tw, th) = (texture.width(), texture.height());
    let mut downloader = gdk::TextureDownloader::new(texture);
    downloader.set_format(gdk::MemoryFormat::B8g8r8a8Premultiplied);
    let (bytes, stride) = downloader.download_bytes();
    let whole = cairo::ImageSurface::create_for_data(bytes.to_vec(), cairo::Format::ARgb32, tw, th, stride as i32).ok()?;
    let out = cairo::ImageSurface::create(cairo::Format::ARgb32, width.max(1), height.max(1)).ok()?;
    {
        let cr = cairo::Context::new(&out).ok()?;
        cr.scale(width as f64 / tw as f64, height as f64 / th as f64);
        cr.set_source_surface(&whole, 0.0, 0.0).ok()?;
        cr.source().set_filter(cairo::Filter::Best);
        cr.paint().ok()?;
    }
    out.flush();
    Some(out)
}

/// [surface] as a JPEG at [quality] (0–100) in a file of its own, its path returned (gdk-pixbuf's saver).
fn encode(surface: &cairo::ImageSurface, quality: i32) -> Result<String, String> {
    let (w, h) = (surface.width(), surface.height());
    let rgb = rgb_of(surface)?;
    let pixbuf = gtk::gdk_pixbuf::Pixbuf::from_bytes(&glib::Bytes::from_owned(rgb), gtk::gdk_pixbuf::Colorspace::Rgb, false, 8, w, h, w * 3);
    let path = glib::tmp_dir().join(format!("veyq-cover-{}-{}.jpg", std::process::id(), glib::monotonic_time()));
    pixbuf.savev(&path, "jpeg", &[("quality", &quality.to_string())]).map_err(|e| e.to_string())?;
    path.to_str().map(str::to_owned).ok_or_else(|| "no UTF-8 path".into())
}

/// The pixels of [surface] (ARGB32, premultiplied, little-endian B G R A) as packed RGB over white, row by row.
fn rgb_of(surface: &cairo::ImageSurface) -> Result<Vec<u8>, String> {
    let (w, h, stride) = (surface.width() as usize, surface.height() as usize, surface.stride() as usize);
    let mut copy = cairo::ImageSurface::create(cairo::Format::ARgb32, w as i32, h as i32).map_err(|e| e.to_string())?;
    {
        let cr = cairo::Context::new(&copy).map_err(|e| e.to_string())?;
        cr.set_source_rgb(1.0, 1.0, 1.0);
        cr.paint().map_err(|e| e.to_string())?;
        cr.set_source_surface(surface, 0.0, 0.0).map_err(|e| e.to_string())?;
        cr.paint().map_err(|e| e.to_string())?;
    }
    copy.flush();
    let data = copy.data().map_err(|e| e.to_string())?;
    Ok(bgra_to_rgb(&data, w, h, stride))
}

/// Packed RGB from little-endian ARGB32 rows of [stride] bytes (opaque: the alpha dropped).
pub fn bgra_to_rgb(data: &[u8], w: usize, h: usize, stride: usize) -> Vec<u8> {
    let mut rgb = Vec::with_capacity(w * h * 3);
    for row in 0..h {
        for px in data[row * stride..row * stride + w * 4].as_chunks::<4>().0 {
            rgb.extend_from_slice(&[px[2], px[1], px[0]]);
        }
    }
    rgb
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn argb32_rows_become_packed_rgb_without_their_padding() {
        // two pixels a row and a stride of 12: the last four bytes of each row are padding
        let data = [
            0x00, 0x00, 0xff, 0xff, 0x10, 0x20, 0x30, 0xff, 9, 9, 9, 9, //
            0xff, 0x00, 0x00, 0xff, 0x01, 0x02, 0x03, 0xff, 9, 9, 9, 9,
        ];
        assert_eq!(bgra_to_rgb(&data, 2, 2, 12), vec![0xff, 0, 0, 0x30, 0x20, 0x10, 0, 0, 0xff, 3, 2, 1]);
    }
}
