//! A file's page (Depot's file viewer; the Depot CR's D1, D4 and D10), GNOME's way: the file's content in place — a picture
//! (`gtk::Picture`, decoded by GTK and held at the core's edge at most), a text (a read-only monospace `gtk::TextView`, the
//! cut said in an `AdwBanner`), a PDF (its pages rendered by poppler as they come into view, [crate::receipt_viewer]'s
//! renderer), or what the core says where it shows none (`AdwStatusPage`) — and its facts beneath, one row with the uploader.
//! Everything is the core's `FileModel` (`File.Open`, `Watched.File`): what shows is decided by the bytes, and an image's
//! and a PDF's bytes come beside the answer (`File.Bytes`, ADR 0128 decision 1), asked again when the content's revision
//! turns. Nothing decrypted is written to show it (ADR 0047).
//!
//! "Save a copy" is the header's download of a file (`win.file-save`): GTK's save dialog names the file, the core writes it
//! there frame by frame (`File.Save`), and a toast says how far it is with Cancel (`Watched.Save`, `Save.Cancel`); the save
//! goes on when the page leaves, its toast in the shell's overlay.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::{gdk, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::Facts, wire::FileShown, wire::FileViewKind, wire::NodeAt,
    wire::SaveEnd, wire::Update, wire::Watched};
use crate::home::glib_escape;
use crate::{said, words};

/// The width a page of a PDF is laid out at, in logical pixels.
const PDF_WIDTH: i32 = 720;

/// The page's own spacing between two PDF pages, in logical pixels.
const PDF_GAP: i32 = 12;

/// What a file's page tells of each read: its header's facts.
type OnFacts = Box<dyn Fn(Facts)>;

/// A file on screen: opened in the core when made, closed when dropped.
pub struct FilePage {
    pub node: NodeAt,
    inner: Rc<Inner>,
}

struct Inner {
    root: gtk::Box,
    /// Where the content shows (replaced when what shows changes) and the facts beneath it.
    content: gtk::Box,
    facts: adw::ActionRow,
    id: Cell<Option<i64>>,
    shown: RefCell<Option<FileShown>>,
    /// What the content shows now: its kind and the content's revision; drawn anew only when either changed.
    drawn: RefCell<Option<(FileViewKind, i32)>>,
    watch: RefCell<Option<Watch>>,
    on_facts: RefCell<Option<OnFacts>>,
    /// The PDF shown, its pages rendered as they come into view.
    pdf: RefCell<Option<Rc<PdfPages>>>,
}

impl FilePage {
    /// Opens file [node] on a page.
    pub fn open(node: &NodeAt) -> Self {
        let root = gtk::Box::new(gtk::Orientation::Vertical, 0);
        let content = gtk::Box::builder().orientation(gtk::Orientation::Vertical).vexpand(true).build();
        root.append(&content);
        let facts = adw::ActionRow::builder().title("").build();
        let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
        list.add_css_class("boxed-list");
        list.append(&facts);
        let clamp = adw::Clamp::builder().maximum_size(720).child(&list).margin_top(12).margin_bottom(12).margin_start(12).margin_end(12).visible(false).build();
        root.append(&clamp);
        let inner = Rc::new(Inner {
            root,
            content,
            facts,
            id: Cell::new(None),
            shown: RefCell::new(None),
            drawn: RefCell::new(None),
            watch: RefCell::new(None),
            on_facts: RefCell::new(None),
            pdf: RefCell::new(None),
        });
        inner.draw();
        let weak = Rc::downgrade(&inner);
        let open = node.clone();
        glib::spawn_future_local(async move {
            let Ok((Answer::FileOpened { file }, _)) = bridge::call(&Call::FileOpen { node: open }).await else {
                eprintln!("veyq: the file did not open in the core");
                return;
            };
            let Some(inner) = weak.upgrade() else {
                bridge::send(Call::FileClose { file });
                return;
            };
            inner.id.set(Some(file));
            let Some((watch, updates)) = bridge::watch(&Watched::File { file }) else { return };
            *inner.watch.borrow_mut() = Some(watch);
            drop(inner);
            follow(weak, updates).await;
        });
        Self { node: node.clone(), inner }
    }

    pub fn widget(&self) -> &gtk::Box {
        &self.inner.root
    }

    /// Told the header's facts each time the file is read (`FileShown.headerFacts`).
    pub fn on_facts(&self, f: impl Fn(Facts) + 'static) {
        *self.inner.on_facts.borrow_mut() = Some(Box::new(f));
        if let Some(facts) = self.inner.shown.borrow().as_ref().and_then(|s| s.header_facts.clone())
            && let Some(f) = self.inner.on_facts.borrow().as_ref()
        {
            f(facts);
        }
    }

    /// "Save a copy": GTK's save dialog (the portal's where there is one) suggests the core's name, the core writes the
    /// file there, and a toast in [toasts] says how far it is, with Cancel, then how it ended.
    pub fn save(&self, parent: &gtk::Window, toasts: &adw::ToastOverlay) {
        let Some(file) = self.inner.id.get() else { return };
        let shown = self.inner.shown.borrow();
        let name = shown.as_ref().and_then(|s| s.download_name.clone()).unwrap_or_else(|| self.node.name.clone());
        drop(shown);
        let (parent, toasts) = (parent.clone(), toasts.clone());
        glib::spawn_future_local(async move {
            let failed = || adw::Toast::new(&words::download_failed(&name));
            let dialog = gtk::FileDialog::builder().title(words::node_download()).initial_name(name.as_str()).modal(true).build();
            if let Some(downloads) = glib::user_special_dir(glib::UserDirectory::Downloads).filter(|d| d.is_dir()) {
                dialog.set_initial_folder(Some(&gtk::gio::File::for_path(downloads)));
            }
            let chosen = match dialog.save_future(Some(&parent)).await {
                Ok(chosen) => chosen,
                Err(e) if e.matches(gtk::DialogError::Dismissed) || e.matches(gtk::DialogError::Cancelled) => return,
                Err(e) => {
                    eprintln!("veyq: the save dialog failed: {e}");
                    toasts.add_toast(failed());
                    return;
                }
            };
            // the core opens the path itself; one that is no UTF-8 does not cross the wire
            let Some(path) = chosen.path().and_then(|p| p.to_str().map(str::to_owned)) else {
                eprintln!("veyq: the save dialog chose no local UTF-8 path");
                toasts.add_toast(failed());
                return;
            };
            let save = match bridge::call(&Call::FileSave { file, path: path.clone() }).await.map(|(a, _)| a) {
                Ok(Answer::Saving { save }) => save,
                other => {
                    eprintln!("veyq: File.Save answered {other:?}");
                    toasts.add_toast(failed());
                    return;
                }
            };
            eprintln!("veyq: saving a copy to {path} (save {save})");
            saving(save, &toasts).await;
        });
    }
}

/// A save's toast: its progress with Cancel while it runs, replaced by how it ended.
async fn saving(save: i64, toasts: &adw::ToastOverlay) {
    let Some((_watch, updates)) = bridge::watch(&Watched::Save { save }) else { return };
    let toast = adw::Toast::builder().title("").timeout(0).button_label(words::download_cancel()).build();
    toast.connect_button_clicked(move |_| {
        eprintln!("veyq: save {save}: cancel pressed");
        bridge::send(Call::SaveCancel { save });
    });
    toasts.add_toast(toast.clone());
    let mut logged = -1;
    while let Ok(update) = updates.recv().await {
        let Ok(Update::Save { shown: Some(s), .. }) = update else { continue };
        if s.end.is_none() {
            toast.set_title(&glib_escape(&said::notice(&s.notice)));
            // a line on stderr each tenth, for a pass to read the progress from
            let tenth = (s.fraction * 10.0).floor() as i32;
            if tenth != logged {
                logged = tenth;
                eprintln!("veyq: save {save}: {} of {} B", s.written, s.total);
            }
            continue;
        }
        eprintln!("veyq: save {save} ended {:?}: {} of {} B", s.end, s.written, s.total);
        toast.dismiss();
        let done = adw::Toast::new(&glib_escape(&said::notice(&s.notice)));
        if s.end == Some(SaveEnd::Saved) {
            done.set_timeout(5);
        }
        toasts.add_toast(done);
        break;
    }
}

impl Drop for FilePage {
    fn drop(&mut self) {
        self.inner.watch.borrow_mut().take();
        if let Some(file) = self.inner.id.get() {
            bridge::send(Call::FileClose { file });
        }
    }
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::File { shown, .. }) => {
                if let Some(s) = &shown {
                    eprintln!("veyq: file shown: {:?}, revision {}, {}", s.view, s.revision, s.media_type.as_deref().unwrap_or("-"));
                    if let (Some(facts), Some(f)) = (s.header_facts.clone(), inner.on_facts.borrow().as_ref()) {
                        f(facts);
                    }
                }
                *inner.shown.borrow_mut() = shown;
                inner.draw();
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a file update did not read: {:?}", e.0),
        }
    }
}

impl Inner {
    fn draw(self: &Rc<Self>) {
        let shown = self.shown.borrow();
        let Some(s) = shown.as_ref() else {
            self.replace(&spinner(&words::file_opening()));
            return;
        };
        // the facts beneath: size, type and modified on one line, the uploader under it
        let line = said::line(&s.facts);
        self.facts.set_title(&glib_escape(&line));
        self.facts.set_subtitle(&s.uploader.as_ref().map(|u| glib_escape(&said::notice(u))).unwrap_or_default());
        if let Some(clamp) = self.facts.ancestor(adw::Clamp::static_type()) {
            clamp.set_visible(!line.is_empty());
        }
        let now = (s.view, s.revision);
        if self.drawn.borrow().as_ref() == Some(&now) {
            return;
        }
        *self.drawn.borrow_mut() = Some(now);
        self.pdf.borrow_mut().take();
        match s.view {
            FileViewKind::Loading => self.replace(&spinner(&s.words.as_deref().map(said::key).unwrap_or_default())),
            FileViewKind::Text => self.replace(&text_view(s.text.as_deref().unwrap_or_default(), s.truncated.as_ref().map(said::notice))),
            FileViewKind::Image => {
                self.replace(&spinner(&words::file_opening()));
                let edge = s.edge.unwrap_or(4096);
                self.load(move |inner, bytes, _| inner.replace(&picture(bytes, edge)));
            }
            FileViewKind::Pdf => {
                self.replace(&spinner(&words::file_opening()));
                self.load(|inner, bytes, scale| match PdfPages::open(bytes, scale) {
                    Some(pages) => {
                        inner.replace(&pages.widget());
                        let pages = Rc::new(pages);
                        PdfPages::start(&pages);
                        *inner.pdf.borrow_mut() = Some(pages);
                    }
                    None => inner.replace(&message(&words::file_unsupported())),
                });
            }
            // nothing shows here: what the core says, and Save a copy in the header bar
            _ => self.replace(&message(&s.words.as_deref().map(said::key).unwrap_or_default())),
        }
    }

    fn replace(&self, widget: &impl IsA<gtk::Widget>) {
        while let Some(child) = self.content.first_child() {
            self.content.remove(&child);
        }
        self.content.append(widget);
    }

    /// The shown content's bytes (`File.Bytes`), handed to [show] on the main loop with the window's scale; what does not
    /// arrive is said as the core's failure.
    fn load(self: &Rc<Self>, show: impl FnOnce(&Rc<Inner>, Vec<u8>, i32) + 'static) {
        let Some(file) = self.id.get() else { return };
        let weak = Rc::downgrade(self);
        let asked = *self.drawn.borrow();
        glib::spawn_future_local(async move {
            let answered = bridge::call(&Call::FileBytes { file }).await;
            let Some(inner) = weak.upgrade() else { return };
            // another content was asked for meanwhile: this answer is not shown
            if *inner.drawn.borrow() != asked {
                return;
            }
            match answered {
                Ok((Answer::Bytes { media_type }, Some(bytes))) => {
                    eprintln!("veyq: file bytes: {} B, {}", bytes.len(), media_type.as_deref().unwrap_or("no type"));
                    let scale = inner.root.scale_factor().max(1);
                    show(&inner, bytes, scale);
                }
                other => {
                    eprintln!("veyq: File.Bytes answered {:?}", other.map(|(a, _)| a));
                    inner.replace(&message(&words::file_open_failed()));
                }
            }
        });
    }
}

fn spinner(label: &str) -> gtk::Widget {
    let page = adw::StatusPage::builder().title(glib_escape(label)).vexpand(true).build();
    page.set_child(Some(&adw::Spinner::builder().width_request(32).height_request(32).halign(gtk::Align::Center).build()));
    page.add_css_class("compact");
    page.upcast()
}

/// What the page says in the content's place (too large, empty, not shown here, not uploaded yet, failed).
fn message(words: &str) -> gtk::Widget {
    let page = adw::StatusPage::builder().icon_name("text-x-generic-symbolic").title(glib_escape(words)).vexpand(true).build();
    page.add_css_class("compact");
    page.upcast()
}

/// A text, read only, in a monospace view of its own scroll; a cut one says so above it.
fn text_view(text: &str, cut: Option<String>) -> gtk::Widget {
    let column = gtk::Box::new(gtk::Orientation::Vertical, 0);
    if let Some(cut) = cut {
        let banner = adw::Banner::new(&glib_escape(&cut));
        banner.set_revealed(true);
        column.append(&banner);
    }
    let view = gtk::TextView::builder().editable(false).cursor_visible(false).monospace(true).wrap_mode(gtk::WrapMode::WordChar)
        .top_margin(12).bottom_margin(12).left_margin(12).right_margin(12).build();
    view.buffer().set_text(text);
    view.update_property(&[gtk::accessible::Property::Label(&words::tab_depot_file())]);
    let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&view).build();
    column.append(&scroll);
    eprintln!("veyq: file text shown: {} characters", text.chars().count());
    column.upcast()
}

/// A picture decoded by GTK from the bytes, held at [edge] pixels on its longest side at most (the core's
/// `FileView.IMAGE_EDGE`), shown at most its own size; one GTK cannot decode is said as not shown here.
fn picture(bytes: Vec<u8>, edge: i32) -> gtk::Widget {
    let texture = match gdk::Texture::from_bytes(&glib::Bytes::from_owned(bytes)) {
        Ok(t) => t,
        Err(e) => {
            eprintln!("veyq: the file's picture did not decode: {e}");
            return message(&words::file_unsupported());
        }
    };
    let (w, h) = (texture.width(), texture.height());
    let held = within_edge(&texture, edge);
    eprintln!("veyq: file picture decoded: {w}×{h}, held at {}×{}", held.width(), held.height());
    let picture = gtk::Picture::for_paintable(&held);
    picture.set_can_shrink(true);
    picture.set_content_fit(gtk::ContentFit::ScaleDown);
    picture.set_vexpand(true);
    picture.set_margin_top(12);
    picture.set_margin_bottom(12);
    picture.set_margin_start(12);
    picture.set_margin_end(12);
    picture.update_property(&[gtk::accessible::Property::Label(&format!("{w}×{h}"))]);
    picture.upcast()
}

/// [texture] as it is where its longest side is [edge] pixels at most, else scaled down to that (cairo, from its pixels):
/// the decoded whole is dropped once the smaller one is made.
pub fn within_edge(texture: &gdk::Texture, edge: i32) -> gdk::Texture {
    let (w, h) = (texture.width(), texture.height());
    let longest = w.max(h);
    if longest <= edge || edge <= 0 {
        return texture.clone();
    }
    let scale = f64::from(edge) / f64::from(longest);
    let (nw, nh) = (((f64::from(w) * scale).round() as i32).max(1), ((f64::from(h) * scale).round() as i32).max(1));
    let mut downloader = gdk::TextureDownloader::new(texture);
    downloader.set_format(gdk::MemoryFormat::B8g8r8a8Premultiplied);
    let (whole, stride) = downloader.download_bytes();
    let Ok(source) = gtk::cairo::ImageSurface::create_for_data(whole.to_vec(), gtk::cairo::Format::ARgb32, w, h, stride as i32) else {
        return texture.clone();
    };
    let Ok(mut target) = gtk::cairo::ImageSurface::create(gtk::cairo::Format::ARgb32, nw, nh) else { return texture.clone() };
    {
        let Ok(cr) = gtk::cairo::Context::new(&target) else { return texture.clone() };
        cr.scale(f64::from(nw) / f64::from(w), f64::from(nh) / f64::from(h));
        if cr.set_source_surface(&source, 0.0, 0.0).is_err() || cr.paint().is_err() {
            return texture.clone();
        }
    }
    target.flush();
    let stride = target.stride() as usize;
    let Ok(data) = target.data().map(|d| d.to_vec()) else { return texture.clone() };
    gdk::MemoryTexture::new(nw, nh, gdk::MemoryFormat::B8g8r8a8Premultiplied, &glib::Bytes::from_owned(data), stride).upcast()
}

/// A PDF's pages in a scroll, each a frame of its page's size at first; a page is rendered (poppler, from memory) when it
/// comes near the view and let go again when it is far from it, so a long PDF holds a few pages' pixels, not all.
struct PdfPages {
    pdf: poppler::Document,
    scroll: gtk::ScrolledWindow,
    column: gtk::Box,
    /// Which pages hold their pixels now.
    rendered: RefCell<Vec<bool>>,
    scale: i32,
}

impl PdfPages {
    fn open(bytes: Vec<u8>, scale: i32) -> Option<Self> {
        let pdf = match poppler::Document::from_bytes(&glib::Bytes::from_owned(bytes), None) {
            Ok(pdf) => pdf,
            Err(e) => {
                eprintln!("veyq: the file's PDF did not open: {e}");
                return None;
            }
        };
        let column = gtk::Box::new(gtk::Orientation::Vertical, PDF_GAP);
        column.set_margin_top(12);
        column.set_margin_bottom(12);
        let clamp = adw::Clamp::builder().maximum_size(PDF_WIDTH).child(&column).build();
        let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build();
        Some(Self { pdf, scroll, column, rendered: RefCell::new(Vec::new()), scale })
    }

    fn widget(&self) -> gtk::Widget {
        self.scroll.clone().upcast()
    }

    /// The pages the core lets show (`File.Pages`) laid out as frames, then drawn as they come into view.
    fn start(this: &Rc<Self>) {
        let total = this.pdf.n_pages();
        let weak = Rc::downgrade(this);
        glib::spawn_future_local(async move {
            let (shown, cut) = match bridge::call(&Call::FilePages { total }).await.map(|(a, _)| a) {
                Ok(Answer::ReceiptPages { shown, cut }) => (shown, cut),
                other => {
                    eprintln!("veyq: File.Pages answered {other:?}");
                    return;
                }
            };
            let Some(this) = weak.upgrade() else { return };
            let mut pictures = Vec::new();
            for i in 0..shown {
                let Some(page) = this.pdf.page(i) else { break };
                let (w, h) = page.size();
                let height = if w > 0.0 { (h * f64::from(PDF_WIDTH) / w).round() as i32 } else { PDF_WIDTH };
                let frame = gtk::Picture::builder().can_shrink(true).content_fit(gtk::ContentFit::Contain).build();
                frame.set_size_request(PDF_WIDTH, height.max(1));
                frame.add_css_class("card");
                frame.update_property(&[gtk::accessible::Property::Label(&format!("{} / {}", i + 1, total))]);
                this.column.append(&frame);
                pictures.push(frame);
            }
            if let Some(cut) = cut {
                let note = gtk::Label::builder().label(said::notice(&cut)).wrap(true).xalign(0.0).build();
                note.add_css_class("dim-label");
                this.column.append(&note);
            }
            eprintln!("veyq: file PDF: {} of {total} pages laid out", pictures.len());
            *this.rendered.borrow_mut() = vec![false; pictures.len()];
            // the frames, held by the handlers that draw them
            let held = Rc::new(pictures);
            let adjustment = this.scroll.vadjustment();
            for signal in ["value-changed", "changed"] {
                let (weak, held) = (Rc::downgrade(&this), held.clone());
                adjustment.connect_local(signal, false, move |_| {
                    if let Some(this) = weak.upgrade() {
                        this.near(&held);
                    }
                    None
                });
            }
            let (weak, held) = (Rc::downgrade(&this), held.clone());
            glib::idle_add_local_once(move || {
                if let Some(this) = weak.upgrade() {
                    this.near(&held);
                }
            });
        });
    }

    /// Renders the pages within a screen of the view and lets go of those more than three screens away.
    fn near(&self, pictures: &[gtk::Picture]) {
        let adjustment = self.scroll.vadjustment();
        let (top, page) = (adjustment.value(), adjustment.page_size().max(1.0));
        let (near_from, near_to) = (top - page, top + 2.0 * page);
        let (far_from, far_to) = (top - 3.0 * page, top + 4.0 * page);
        let mut rendered = self.rendered.borrow_mut();
        let mut changed = false;
        for (i, frame) in pictures.iter().enumerate() {
            let Some(bounds) = frame.compute_bounds(&self.column) else { continue };
            // the column starts at the scroll's top (its margin aside): a frame's y is its place in the scroll
            let (y0, y1) = (f64::from(bounds.y()), f64::from(bounds.y() + bounds.height()));
            let near = y1 >= near_from && y0 <= near_to;
            let far = y1 < far_from || y0 > far_to;
            if near && !rendered[i] {
                let Some(p) = self.pdf.page(i as i32) else { continue };
                if let Some(r) = crate::receipt_viewer::render(&p, PDF_WIDTH * self.scale) {
                    let texture = gdk::MemoryTexture::new(r.width, r.height, gdk::MemoryFormat::B8g8r8a8Premultiplied, &glib::Bytes::from_owned(r.data), r.stride);
                    frame.set_paintable(Some(&texture));
                    rendered[i] = true;
                    changed = true;
                }
            } else if far && rendered[i] {
                frame.set_paintable(None::<&gdk::Paintable>);
                rendered[i] = false;
                changed = true;
            }
        }
        if changed {
            let held: Vec<String> = rendered.iter().enumerate().filter(|(_, r)| **r).map(|(i, _)| (i + 1).to_string()).collect();
            eprintln!("veyq: file PDF pages held: {}", held.join(","));
        }
    }
}
