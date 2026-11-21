//! One's own profile in the space open now (ADR 0148 decision 2; the web's `ProfilePanel` and `ProfilePictureDialog`), GNOME's
//! way: an `AdwDialog` with an `AdwNavigationView` — the profile's page (the picture, the username, the real name, "Ask
//! before adding me to groups", Save in the header bar) and, pushed over it, the square cropper of a picked picture. Opened
//! from Settings' *Your profile* and from the header's *Edit profile* on one's own person page; both open the same core
//! model (`Profile.Open`, `Watched.Profile`).
//!
//! The picture is the platform's to decode and draw, the core's to encode, pad and seal (ADR 0136 decision 1): GTK decodes
//! the picked file (`gdk::Texture`), the cropper's pose is the core's math (`Cover.Pose` with a square frame of
//! [EDGE] × [EDGE]), and the core asks this host to draw that pose as RGBA (`Picture.Square`, [answer]) before it encodes.
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::{cairo, gdk, gio, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::HostReply, wire::HostRequest, wire::PictureDraft, wire::Pose,
    wire::ProfileModelState, wire::Update, wire::Watched};
use crate::core_ui::{said, words};

/// The picture's edge in pixels (core's `ProfileModel.EDGE`, the web's `AVATAR_EDGE`); the cropper's frame is as wide.
const EDGE: i32 = 256;

struct Inner {
    dialog: adw::Dialog,
    nav: adw::NavigationView,
    toasts: adw::ToastOverlay,
    save: gtk::Button,
    avatar: adw::Avatar,
    choose: gtk::Button,
    remove: gtk::Button,
    picture_hint: gtk::Label,
    picture_error: gtk::Label,
    username: adw::EntryRow,
    username_error: gtk::Label,
    first: adw::EntryRow,
    last: adw::EntryRow,
    name_hint: gtk::Label,
    consent: adw::SwitchRow,
    error: gtk::Label,
    id: Cell<Option<i64>>,
    watch: RefCell<Option<Watch>>,
    /// Set while a state is drawn, so the rows' own signals send nothing.
    drawing: Cell<bool>,
    /// The fields are filled from the core once, at the first loaded state, and again after a save.
    filled_at: Cell<Option<i32>>,
    /// What the avatar shows: (the draft, whether the server holds one, saves) as drawn last.
    drawn_picture: RefCell<Option<(PictureDraft, bool, i32)>>,
    refusal: RefCell<Option<String>>,
}

/// Opens one's own profile over [parent].
pub fn open(parent: &gtk::Widget) {
    let save = gtk::Button::with_label(&words::profile_save());
    save.add_css_class("suggested-action");
    save.set_sensitive(false);
    let bar = adw::HeaderBar::new();
    bar.pack_end(&save);
    let avatar = adw::Avatar::builder().size(96).show_initials(true).halign(gtk::Align::Center).build();
    let choose = gtk::Button::builder().label(words::profile_picture_choose()).build();
    choose.add_css_class("pill");
    let remove = gtk::Button::builder().label(words::profile_picture_remove()).build();
    remove.add_css_class("pill");
    remove.add_css_class("destructive-action");
    let buttons = gtk::Box::builder().spacing(12).halign(gtk::Align::Center).build();
    buttons.append(&choose);
    buttons.append(&remove);
    let picture_hint = gtk::Label::builder().label(words::profile_picture_hint()).wrap(true).justify(gtk::Justification::Center).build();
    picture_hint.add_css_class("dim-label");
    let picture_error = error_label();
    let username = adw::EntryRow::builder().title(words::profile_username()).build();
    let username_error = error_label();
    let first = adw::EntryRow::builder().title(words::profile_first_name()).build();
    let last = adw::EntryRow::builder().title(words::profile_last_name()).build();
    let name_hint = gtk::Label::builder().label(words::profile_no_name_yet()).wrap(true).xalign(0.0).visible(false).build();
    name_hint.add_css_class("dim-label");
    let consent = adw::SwitchRow::builder().title(words::profile_consent_group_add()).subtitle(words::profile_consent_group_add_hint()).build();
    let error = error_label();

    let col = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).build();
    col.append(&gtk::Label::builder().label(words::profile_sub()).wrap(true).xalign(0.0).build());
    col.append(&avatar);
    col.append(&buttons);
    col.append(&picture_hint);
    col.append(&picture_error);
    let who = adw::PreferencesGroup::builder().description(words::profile_username_hint()).build();
    who.add(&username);
    col.append(&who);
    col.append(&username_error);
    let names = adw::PreferencesGroup::new();
    names.add(&first);
    names.add(&last);
    col.append(&names);
    col.append(&name_hint);
    let consent_group = adw::PreferencesGroup::new();
    consent_group.add(&consent);
    col.append(&consent_group);
    col.append(&error);
    let clamp = adw::Clamp::builder().maximum_size(520).margin_top(12).margin_bottom(24).margin_start(12).margin_end(12).child(&col).build();
    let view = adw::ToolbarView::new();
    view.add_top_bar(&bar);
    view.set_content(Some(&gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build()));
    let nav = adw::NavigationView::new();
    nav.add(&adw::NavigationPage::builder().title(words::profile_title()).tag("profile").child(&view).build());
    let toasts = { let t = adw::ToastOverlay::new(); t.set_child(Some(&nav)); t };
    let dialog = adw::Dialog::builder().title(words::profile_title()).content_width(560).content_height(720).child(&toasts).build();
    dialog.set_widget_name("profile");

    let inner = Rc::new(Inner {
        dialog: dialog.clone(),
        nav,
        toasts,
        save: save.clone(),
        avatar,
        choose: choose.clone(),
        remove: remove.clone(),
        picture_hint,
        picture_error,
        username: username.clone(),
        username_error,
        first: first.clone(),
        last: last.clone(),
        name_hint,
        consent: consent.clone(),
        error,
        id: Cell::new(None),
        watch: RefCell::new(None),
        drawing: Cell::new(false),
        filled_at: Cell::new(None),
        drawn_picture: RefCell::new(None),
        refusal: RefCell::new(None),
    });
    // each field sent as typed (the core applies them in the order sent)
    for (row, which) in [(&username, 0), (&first, 1), (&last, 2)] {
        let w = Rc::downgrade(&inner);
        row.connect_changed(move |r| {
            let Some(i) = w.upgrade() else { return };
            if i.drawing.get() {
                return;
            }
            let (Some(profile), value) = (i.id.get(), r.text().to_string()) else { return };
            bridge::send(match which {
                0 => Call::ProfileUsername { profile, value },
                1 => Call::ProfileFirstName { profile, value },
                _ => Call::ProfileLastName { profile, value },
            });
        });
    }
    let w = Rc::downgrade(&inner);
    consent.connect_active_notify(move |s| {
        let Some(i) = w.upgrade() else { return };
        if let (false, Some(profile)) = (i.drawing.get(), i.id.get()) {
            bridge::send(Call::ProfileConsent { profile, on: s.is_active() });
        }
    });
    let w = Rc::downgrade(&inner);
    save.connect_clicked(move |_| {
        if let Some(profile) = w.upgrade().and_then(|i| i.id.get()) {
            bridge::send(Call::ProfileSave { profile });
        }
    });
    let w = Rc::downgrade(&inner);
    remove.connect_clicked(move |_| {
        if let Some(profile) = w.upgrade().and_then(|i| i.id.get()) {
            bridge::send(Call::ProfileRemovePicture { profile });
        }
    });
    let w = Rc::downgrade(&inner);
    choose.connect_clicked(move |_| {
        if let Some(i) = w.upgrade() {
            i.pick();
        }
    });

    let w = Rc::downgrade(&inner);
    glib::spawn_future_local(async move {
        let profile = match bridge::call(&Call::ProfileOpen).await.map(|(a, _)| a) {
            Ok(Answer::ProfileOpened { profile }) => profile,
            other => {
                eprintln!("veyq: Profile.Open answered {other:?}");
                if let Some(i) = w.upgrade() {
                    i.toasts.add_toast(adw::Toast::new(&words::profile_load_failed()));
                }
                return;
            }
        };
        let Some(i) = w.upgrade() else { return bridge::send(Call::ProfileClose { profile }) };
        i.id.set(Some(profile));
        eprintln!("veyq: profile open");
        let Some((watch, updates)) = bridge::watch(&Watched::Profile { profile }) else { return };
        *i.watch.borrow_mut() = Some(watch);
        drop(i);
        while let Ok(update) = updates.recv().await {
            let Some(i) = w.upgrade() else { return };
            match update {
                Ok(Update::Profile { shown: Some(s), .. }) => i.draw(&s),
                Ok(Update::Profile { shown: None, .. }) => {
                    i.id.set(None);
                    i.dialog.force_close();
                    return;
                }
                Ok(_) => {}
                Err(e) => eprintln!("veyq: a profile update did not read: {:?}", e.0),
            }
        }
    });
    let held = RefCell::new(Some(inner));
    dialog.connect_closed(move |_| {
        if let Some(i) = held.borrow_mut().take() {
            i.watch.borrow_mut().take();
            if let Some(profile) = i.id.take() {
                bridge::send(Call::ProfileClose { profile });
            }
        }
    });
    dialog.present(Some(parent));
}

fn error_label() -> gtk::Label {
    let l = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
    l.add_css_class("error");
    l
}

fn said_refusal(label: &gtk::Label, refusal: Option<&String>) {
    label.set_visible(refusal.is_some());
    label.set_label(&refusal.map(|r| said::key(r)).unwrap_or_default());
}

impl Inner {
    fn draw(self: &Rc<Self>, s: &ProfileModelState) {
        eprintln!("veyq: profile: loaded {}, key {}, picture {:?} (server {}), working {}, saves {}, refusal {:?}, username {:?}, picture {:?}",
            s.loaded, s.has_key, s.picture, s.server_has_picture, s.working, s.saves, s.refusal, s.username_refusal, s.picture_refusal);
        self.drawing.set(true);
        if s.loaded && self.filled_at.get() != Some(s.saves) {
            self.username.set_text(&s.username);
            self.first.set_text(&s.first_name);
            self.last.set_text(&s.last_name);
            if self.filled_at.get().is_some() {
                self.toasts.add_toast(adw::Toast::new(&words::profile_saved()));
            }
            self.filled_at.set(Some(s.saves));
        }
        self.consent.set_active(s.require_group_add_consent);
        self.drawing.set(false);
        let editable = s.loaded && !s.working;
        self.save.set_sensitive(editable);
        self.username.set_sensitive(editable);
        self.consent.set_sensitive(editable);
        for w in [self.first.upcast_ref::<gtk::Widget>(), self.last.upcast_ref(), self.choose.upcast_ref()] {
            w.set_sensitive(editable && s.has_key);
        }
        self.name_hint.set_visible(s.loaded && !s.has_key);
        let has_picture = match s.picture {
            PictureDraft::Set => true,
            PictureDraft::Clear => false,
            PictureDraft::Keep => s.server_has_picture,
        };
        self.remove.set_visible(has_picture);
        self.remove.set_sensitive(editable && s.has_key);
        self.choose.set_label(&if has_picture { words::profile_picture_change() } else { words::profile_picture_choose() });
        self.picture_hint.set_label(&match (s.has_key, s.picture) {
            (false, _) => words::profile_picture_no_key_yet(),
            (_, PictureDraft::Set) => words::profile_picture_pending_set(),
            (_, PictureDraft::Clear) => words::profile_picture_pending_clear(),
            _ => words::profile_picture_hint(),
        });
        let name = format!("{} {}", s.first_name, s.last_name);
        self.avatar.set_text(Some(if name.trim().is_empty() { s.username.as_str() } else { name.trim() }));
        said_refusal(&self.username_error, s.username_refusal.as_ref());
        said_refusal(&self.picture_error, s.picture_refusal.as_ref());
        said_refusal(&self.error, s.refusal.as_ref());
        if *self.refusal.borrow() != s.refusal {
            *self.refusal.borrow_mut() = s.refusal.clone();
        }
        let key = (s.picture, s.server_has_picture, s.saves);
        if self.drawn_picture.replace(Some(key)) != Some(key) {
            self.draw_picture(has_picture);
        }
    }

    /// The picture the core holds (the draft's, else the server's), decoded by GTK; none: the initials.
    fn draw_picture(self: &Rc<Self>, has_picture: bool) {
        if !has_picture {
            self.avatar.set_custom_image(None::<&gdk::Paintable>);
            return;
        }
        let Some(profile) = self.id.get() else { return };
        let w = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let answered = bridge::call(&Call::ProfilePictureBytes { profile }).await;
            let Some(i) = w.upgrade() else { return };
            match answered {
                Ok((Answer::Bytes { .. }, Some(bytes))) => match gdk::Texture::from_bytes(&glib::Bytes::from_owned(bytes)) {
                    Ok(t) => i.avatar.set_custom_image(Some(&t)),
                    Err(e) => eprintln!("veyq: profile: the picture did not decode ({e})"),
                },
                other => eprintln!("veyq: Profile.PictureBytes answered {:?}", other.map(|(a, _)| a)),
            }
        });
    }

    /// "Choose a picture": GTK's file dialog (the portal's where there is one), then the square cropper over it.
    fn pick(self: &Rc<Self>) {
        let w = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let images = gtk::FileFilter::new();
            images.add_mime_type("image/*");
            images.set_name(Some(&words::profile_picture_label()));
            let filters = gio::ListStore::new::<gtk::FileFilter>();
            filters.append(&images);
            let dialog = gtk::FileDialog::builder().title(words::profile_picture_choose()).filters(&filters).default_filter(&images).modal(true).build();
            let parent = w.upgrade().and_then(|i| i.dialog.root()).and_downcast::<gtk::Window>();
            let file = match dialog.open_future(parent.as_ref()).await {
                Ok(file) => file,
                Err(e) => return eprintln!("veyq: profile: no picture chosen ({e})"),
            };
            let Some(i) = w.upgrade() else { return };
            let (Some(path), texture) = (file.path().and_then(|p| p.to_str().map(str::to_owned)), file.path().map(gdk::Texture::from_filename)) else { return };
            match texture {
                Some(Ok(t)) => Cropper::push(&i, path, &t),
                other => {
                    eprintln!("veyq: profile: the picture did not decode ({:?})", other.map(|r| r.err()));
                    i.toasts.add_toast(adw::Toast::new(&words::profile_picture_not_image()));
                }
            }
        });
    }
}

/// The square cropper (the web's `ProfilePictureDialog` around its cropper): the picture under an [EDGE] × [EDGE] frame,
/// dragged and zoomed (1–3), the pose the core's; "Use this picture" hands the core the path and the pose.
struct Cropper {
    area: gtk::DrawingArea,
    zoom: gtk::Scale,
    surface: cairo::ImageSurface,
    size: (i32, i32),
    pose: RefCell<Option<Pose>>,
    drawn: Cell<(f64, f64)>,
    from: RefCell<Option<Pose>>,
    asked: Cell<u64>,
    drawing: Cell<bool>,
}

impl Cropper {
    fn push(profile: &Rc<Inner>, path: String, texture: &gdk::Texture) {
        let (tw, th) = (texture.width(), texture.height());
        // held at most four frames wide, which the 3× zoom still draws sharply
        let scale = (f64::from(EDGE * 4) / f64::from(tw.min(th).max(1))).min(1.0);
        let (w, h) = (((f64::from(tw) * scale).round() as i32).max(1), ((f64::from(th) * scale).round() as i32).max(1));
        let Some(surface) = crate::core_ui::cover::scaled(texture, w, h) else {
            profile.toasts.add_toast(adw::Toast::new(&words::profile_picture_not_image()));
            return;
        };
        let area = gtk::DrawingArea::builder().content_width(EDGE).content_height(EDGE).halign(gtk::Align::Center).build();
        area.set_overflow(gtk::Overflow::Hidden);
        area.add_css_class("card");
        area.update_property(&[gtk::accessible::Property::Label(&words::profile_picture_label())]);
        let zoom = gtk::Scale::with_range(gtk::Orientation::Horizontal, 1.0, 3.0, 0.01);
        zoom.set_draw_value(false);
        zoom.set_hexpand(true);
        zoom.update_property(&[gtk::accessible::Property::Label(&words::cover_zoom())]);
        let zoom_row = gtk::Box::new(gtk::Orientation::Horizontal, 12);
        zoom_row.append(&gtk::Image::from_icon_name("zoom-out-symbolic"));
        zoom_row.append(&zoom);
        zoom_row.append(&gtk::Image::from_icon_name("zoom-in-symbolic"));
        let use_it = gtk::Button::builder().label(words::profile_picture_use()).halign(gtk::Align::Center).build();
        use_it.add_css_class("pill");
        use_it.add_css_class("suggested-action");
        let col = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).build();
        col.append(&gtk::Label::builder().label(words::profile_picture_dialog_intro()).wrap(true).xalign(0.0).build());
        col.append(&area);
        col.append(&zoom_row);
        col.append(&use_it);
        let clamp = adw::Clamp::builder().maximum_size(520).margin_top(12).margin_bottom(24).margin_start(12).margin_end(12).child(&col).build();
        let view = adw::ToolbarView::new();
        view.add_top_bar(&adw::HeaderBar::new());
        view.set_content(Some(&gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build()));
        let page = adw::NavigationPage::builder().title(words::profile_picture_label()).tag("crop").child(&view).build();
        let c = Rc::new(Cropper {
            area: area.clone(),
            zoom: zoom.clone(),
            surface,
            size: (w, h),
            pose: RefCell::new(None),
            drawn: Cell::new((0.0, 0.0)),
            from: RefCell::new(None),
            asked: Cell::new(0),
            drawing: Cell::new(false),
        });
        let weak = Rc::downgrade(&c);
        area.set_draw_func(move |_, cr, _, _| {
            if let Some(c) = weak.upgrade() {
                c.paint(cr);
            }
        });
        let drag = gtk::GestureDrag::new();
        let weak = Rc::downgrade(&c);
        drag.connect_drag_begin(move |_, _, _| {
            if let Some(c) = weak.upgrade() {
                *c.from.borrow_mut() = c.pose.borrow().clone();
            }
        });
        let weak = Rc::downgrade(&c);
        drag.connect_drag_update(move |_, dx, dy| {
            let Some(c) = weak.upgrade() else { return };
            let from = c.from.borrow().clone();
            if from.is_some() {
                c.ask(from, dx, dy, 1.0);
            }
        });
        area.add_controller(drag);
        let weak = Rc::downgrade(&c);
        zoom.connect_value_changed(move |s| {
            let Some(c) = weak.upgrade() else { return };
            let at = c.pose.borrow().clone();
            if let (false, Some(at)) = (c.drawing.get(), at) {
                let factor = s.value() / at.zoom;
                c.ask(Some(at), 0.0, 0.0, factor);
            }
        });
        // the cropper lives as long as its page: its button holds it
        let (held, p) = (c.clone(), Rc::downgrade(profile));
        use_it.connect_clicked(move |b| {
            let (c, Some(i)) = (&held, p.upgrade()) else { return };
            let (Some(pose), Some(profile)) = (c.pose.borrow().clone(), i.id.get()) else { return };
            b.set_sensitive(false);
            let (path, b) = (path.clone(), b.clone());
            glib::spawn_future_local(async move {
                let answered = bridge::call(&Call::ProfilePicture { profile, path, pose }).await.map(|(a, _)| a);
                eprintln!("veyq: Profile.Picture: {}", crate::core_ui::manage::answer_kind(&answered));
                b.set_sensitive(true);
                match answered {
                    Ok(Answer::Done) => {
                        i.nav.pop();
                    }
                    _ => i.toasts.add_toast(adw::Toast::new(&words::profile_picture_not_image())),
                }
            });
        });
        profile.nav.push(&page);
        // a fresh picture opens centred (the core's `centred`)
        c.ask(None, 0.0, 0.0, 1.0);
    }

    /// Asks the core where the picture sits after a drag or a zoom (`Cover.Pose` with the square frame); only the latest
    /// ask's answer is drawn.
    fn ask(self: &Rc<Self>, at: Option<Pose>, pan_x: f64, pan_y: f64, factor: f64) {
        let n = self.asked.get() + 1;
        self.asked.set(n);
        let call = Call::CoverPose {
            image_width: f64::from(self.size.0),
            image_height: f64::from(self.size.1),
            frame_width: f64::from(EDGE),
            frame_height: f64::from(EDGE),
            at,
            crop: None,
            pan_x,
            pan_y,
            zoom: factor,
        };
        let w = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let answered = bridge::call(&call).await;
            let Some(c) = w.upgrade() else { return };
            if c.asked.get() != n {
                return;
            }
            match answered {
                Ok((Answer::CoverPosed { pose, width, height, .. }, _)) => {
                    c.drawn.set((width, height));
                    c.drawing.set(true);
                    c.zoom.set_value(pose.zoom);
                    c.drawing.set(false);
                    *c.pose.borrow_mut() = Some(pose);
                    c.area.queue_draw();
                }
                other => eprintln!("veyq: Cover.Pose answered {:?}", other.map(|(a, _)| a)),
            }
        });
    }

    fn paint(&self, cr: &cairo::Context) {
        let Some(pose) = self.pose.borrow().clone() else { return };
        let (dw, dh) = self.drawn.get();
        cr.save().ok();
        cr.translate(pose.x, pose.y);
        cr.scale(dw / f64::from(self.size.0), dh / f64::from(self.size.1));
        if cr.set_source_surface(&self.surface, 0.0, 0.0).is_ok() {
            cr.source().set_filter(cairo::Filter::Good);
            cr.paint().ok();
        }
        cr.restore().ok();
    }
}

/// The core's `Picture.Square`: the picture at its path drawn as the pose says into an `edge` × `edge` frame, answered as
/// RGBA beside the reply; None for any other request.
pub fn answer(request: &HostRequest) -> Option<(HostReply, Option<Vec<u8>>)> {
    let HostRequest::PictureSquare { path, pose, edge } = request else { return None };
    Some(match square_of_file(path, pose, *edge) {
        Ok(rgba) => (HostReply::Pixels { width: *edge, height: *edge }, Some(rgba)),
        Err(why) => {
            eprintln!("veyq: a profile picture was not drawn: {why}");
            (HostReply::Failed { error: why }, None)
        }
    })
}

fn square_of_file(path: &str, pose: &Pose, edge: i32) -> Result<Vec<u8>, String> {
    if edge <= 0 {
        return Err("no edge".into());
    }
    let texture = gdk::Texture::from_filename(path).map_err(|e| format!("the picture did not decode ({e})"))?;
    let mut downloader = gdk::TextureDownloader::new(&texture);
    downloader.set_format(gdk::MemoryFormat::B8g8r8a8Premultiplied);
    let (bytes, stride) = downloader.download_bytes();
    let mut copy = bytes.to_vec();
    let drawn = square(&copy, texture.width(), texture.height(), stride, pose, edge);
    copy.fill(0);
    drawn
}

/// The picture of [width] × [height] (cairo's ARGB32 rows at [stride], premultiplied) under an [edge] × [edge] frame at
/// [pose] — at zoom 1 it covers the frame (`CoverFrame.baseScale`), its top-left at the pose's — as RGBA, 4 bytes a pixel,
/// not premultiplied.
pub fn square(bgra: &[u8], width: i32, height: i32, stride: usize, pose: &Pose, edge: i32) -> Result<Vec<u8>, String> {
    if width <= 0 || height <= 0 {
        return Err("an empty picture".into());
    }
    let whole = cairo::ImageSurface::create_for_data(bgra.to_vec(), cairo::Format::ARgb32, width, height, stride as i32).map_err(|e| e.to_string())?;
    let mut out = cairo::ImageSurface::create(cairo::Format::ARgb32, edge, edge).map_err(|e| e.to_string())?;
    {
        let cr = cairo::Context::new(&out).map_err(|e| e.to_string())?;
        let base = (f64::from(edge) / f64::from(width)).max(f64::from(edge) / f64::from(height));
        cr.translate(pose.x, pose.y);
        cr.scale(base * pose.zoom, base * pose.zoom);
        cr.set_source_surface(&whole, 0.0, 0.0).map_err(|e| e.to_string())?;
        cr.source().set_filter(cairo::Filter::Best);
        cr.paint().map_err(|e| e.to_string())?;
    }
    out.flush();
    let stride = out.stride() as usize;
    let data = out.data().map_err(|e| e.to_string())?;
    let e = edge as usize;
    let mut rgba = Vec::with_capacity(e * e * 4);
    for y in 0..e {
        for px in data[y * stride..y * stride + e * 4].as_chunks::<4>().0 {
            let a = u32::from(px[3]);
            let un = |c: u8| (u32::from(c) * 255 + a / 2).checked_div(a).map_or(0, |v| v.min(255) as u8);
            rgba.extend_from_slice(&[un(px[2]), un(px[1]), un(px[0]), px[3]]);
        }
    }
    Ok(rgba)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A picture 200 × 100, its left half red, its right half blue (ARGB32, little-endian B G R A, opaque).
    fn halves() -> Vec<u8> {
        let mut px = Vec::new();
        for _ in 0..100 {
            for x in 0..200 {
                px.extend_from_slice(if x < 100 { &[0, 0, 255, 255] } else { &[255, 0, 0, 255] });
            }
        }
        px
    }

    fn at(rgba: &[u8], edge: usize, x: usize, y: usize) -> [u8; 4] {
        let i = (y * edge + x) * 4;
        [rgba[i], rgba[i + 1], rgba[i + 2], rgba[i + 3]]
    }

    #[test]
    fn the_square_is_drawn_at_the_cores_pose() {
        let px = halves();
        // centred at zoom 1 (the core's `centred`: the 8 × 4 drawing at x -2): the frame shows the middle, red then blue
        let centred = square(&px, 200, 100, 800, &Pose { zoom: 1.0, x: -2.0, y: 0.0 }, 4).unwrap();
        assert_eq!(centred.len(), 4 * 4 * 4);
        assert_eq!(at(&centred, 4, 0, 1), [255, 0, 0, 255]);
        assert_eq!(at(&centred, 4, 3, 1), [0, 0, 255, 255]);
        // panned to the left edge: red all over
        let left = square(&px, 200, 100, 800, &Pose { zoom: 1.0, x: 0.0, y: 0.0 }, 4).unwrap();
        assert_eq!(at(&left, 4, 3, 3), [255, 0, 0, 255]);
        // zoomed 2× and panned to the right end (16 wide, x -12): blue all over
        let right = square(&px, 200, 100, 800, &Pose { zoom: 2.0, x: -12.0, y: -2.0 }, 4).unwrap();
        assert_eq!(at(&right, 4, 0, 0), [0, 0, 255, 255]);
    }

    #[test]
    fn only_a_square_request_is_answered_here() {
        assert!(answer(&HostRequest::StoreRead { name: "a".into() }).is_none());
        let missing = HostRequest::PictureSquare { path: "/no/such/picture.png".into(), pose: Pose { zoom: 1.0, x: 0.0, y: 0.0 }, edge: 4 };
        assert!(matches!(answer(&missing), Some((HostReply::Failed { .. }, None))));
    }
}
