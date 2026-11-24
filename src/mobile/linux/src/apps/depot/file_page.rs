//! A file's page (Depot's file viewer; ADR 0123's Consequences, the Linux app's Depot whole), GNOME's way: the file's content in place — a picture
//! (`gtk::Picture`, decoded by GTK and held at the core's edge at most), a text (a read-only monospace `gtk::TextView`, the
//! cut said in an `AdwBanner`), a PDF (its pages rendered by poppler as they come into view, [crate::core_ui::pdf_render]'s
//! renderer, its text selected and copied through [crate::core_ui::pdf_text]), an audio or video played by GTK's own player (`gtk::Video` over a `gtk::MediaFile` reading the core's reader
//! through [crate::core_ui::reader_stream], the system's GStreamer decoding it; what it cannot decode is said as not shown here), or
//! what the core says where it shows none (`AdwStatusPage`) — and its facts beneath, one row with the uploader.
//! Everything is the core's `FileModel` (`File.Open`, `Watched.File`): what shows is decided by the bytes, and an image's
//! and a PDF's bytes come beside the answer (`File.Bytes`, ADR 0137 decision 1), asked again when the content's revision
//! turns; a medium is read in ranges (`File.Reader`, ADR 0137 decision 2). Nothing decrypted is written to show it (ADR 0047).
//!
//! "Save a copy" is the header's download of a file (`win.file-save`): GTK's save dialog names the file, the core writes it
//! there frame by frame (`File.Save`), and a toast says how far it is with Cancel (`Watched.Save`, `Save.Cancel`); the save
//! goes on when the page leaves, its toast in the shell's overlay.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::{gdk, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::Facts, wire::FileShown, wire::FileViewKind, wire::NodeAt,
    wire::Update, wire::Watched};
use crate::core_ui::home::glib_escape;
use crate::core_ui::pdf_text::{PageFrame, PdfText};
use crate::core_ui::picture::within_edge;
use crate::core_ui::player::Playing;
use crate::core_ui::saving::saving;
use crate::core_ui::{said, words};

/// The width a page of a PDF is laid out at, in logical pixels.
const PDF_WIDTH: i32 = 720;

/// The page's own spacing between two PDF pages, in logical pixels.
const PDF_GAP: i32 = 12;

/// What a file's page tells of each read: its header's facts and the page's id in the core.
type OnFacts = Box<dyn Fn(Facts, i64)>;

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
    /// The medium playing over the core's reader.
    media: RefCell<Option<Playing>>,
    /// The file's name, which a password-protected PDF's prompt names.
    name: String,
}

impl FilePage {
    /// Opens file [node] on a page.
    pub fn open(node: &NodeAt) -> Self {
        Self::over(node, None)
    }

    /// File [node]'s page over the model the core already opened as [file] (a received link's guest page, `ReceivedPage.file`):
    /// watched as a member's, closed with the page as a member's is.
    pub fn opened(node: &NodeAt, file: i64) -> Self {
        Self::over(node, Some(file))
    }

    fn over(node: &NodeAt, opened: Option<i64>) -> Self {
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
            media: RefCell::new(None),
            name: node.name.clone(),
        });
        inner.draw();
        let weak = Rc::downgrade(&inner);
        let open = node.clone();
        glib::spawn_future_local(async move {
            let file = match opened {
                Some(file) => file,
                None => {
                    let Ok((Answer::FileOpened { file }, _)) = bridge::call(&Call::FileOpen { node: open }).await else {
                        eprintln!("veyq: the file did not open in the core");
                        // said in its place, never a spinner left turning
                        if let Some(inner) = weak.upgrade() {
                            inner.replace(&message(&words::file_open_failed()));
                        }
                        return;
                    };
                    file
                }
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

    /// Told the header's facts each time the file is read (`FileShown.headerFacts`), with the page's id.
    pub fn on_facts(&self, f: impl Fn(Facts, i64) + 'static) {
        *self.inner.on_facts.borrow_mut() = Some(Box::new(f));
        if let Some(facts) = self.inner.shown.borrow().as_ref().and_then(|s| s.header_facts.clone())
            && let Some(id) = self.inner.id.get()
            && let Some(f) = self.inner.on_facts.borrow().as_ref()
        {
            f(facts, id);
        }
    }

    /// The page's id in the core, once it opened (`File.Open`).
    pub fn id(&self) -> Option<i64> {
        self.inner.id.get()
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
            let failed = || said::word_toast("download_failed", &words::download_failed(&name));
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

impl Drop for FilePage {
    fn drop(&mut self) {
        self.inner.watch.borrow_mut().take();
        self.inner.stop_media();
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
                    if let (Some(facts), Some(id), Some(f)) = (s.header_facts.clone(), inner.id.get(), inner.on_facts.borrow().as_ref()) {
                        f(facts, id);
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
        self.stop_media();
        match s.view {
            FileViewKind::Loading => self.replace(&spinner(&s.words.as_deref().map(said::key).unwrap_or_default())),
            FileViewKind::Text => self.replace(&text_view(s.text.as_deref().unwrap_or_default(), s.truncated.as_ref().map(said::notice))),
            FileViewKind::Image if s.media_type.as_deref() == Some(SVG) => {
                self.replace(&spinner(&words::file_opening()));
                self.load(SvgZoom::show);
            }
            FileViewKind::Image => {
                self.replace(&spinner(&words::file_opening()));
                let edge = s.edge.unwrap_or(4096);
                self.load(move |inner, bytes, _| inner.replace(&picture(bytes, edge)));
            }
            FileViewKind::Pdf => {
                self.replace(&spinner(&words::file_opening()));
                self.load(|inner, bytes, scale| inner.show_pdf(Rc::new(bytes), scale, None));
            }
            FileViewKind::Media => {
                self.replace(&spinner(&words::file_opening()));
                self.play(s.words.clone());
            }
            // nothing shows here: what the core says, and Save a copy in the header bar
            _ => self.replace(&message(&s.words.as_deref().map(said::key).unwrap_or_default())),
        }
    }

    /// The PDF in [bytes] shown, opened with [password] where one was typed. A password-protected PDF asks for its password
    /// in the page first, as the web's does (`FileViewer.vue`): a password entry whose text goes to poppler and nowhere
    /// else, emptied at each try; a wrong one said; Cancel says the PDF is protected and leaves Save a copy.
    fn show_pdf(self: &Rc<Self>, bytes: Rc<Vec<u8>>, scale: i32, password: Option<String>) {
        let asked = *self.drawn.borrow();
        match PdfPages::open(&bytes, scale, password.as_deref()) {
            Opened::Pages(pages) => {
                self.replace(&pages.widget());
                let pages = Rc::new(pages);
                PdfPages::start(&pages);
                *self.pdf.borrow_mut() = Some(pages);
            }
            Opened::Locked => {
                let weak = Rc::downgrade(self);
                let open = move |typed: String| {
                    if let Some(inner) = weak.upgrade().filter(|inner| *inner.drawn.borrow() == asked) {
                        inner.show_pdf(bytes.clone(), scale, Some(typed));
                    }
                };
                let weak = Rc::downgrade(self);
                let cancel = move || {
                    if let Some(inner) = weak.upgrade().filter(|inner| *inner.drawn.borrow() == asked) {
                        inner.replace(&message(&words::file_pdf_password_declined()));
                    }
                };
                self.replace(&pdf_password(&self.name, password.is_some(), open, cancel));
            }
            Opened::Failed => self.replace(&message(&words::file_unsupported())),
        }
    }

    /// The medium played through a reader the core opened for this page (`File.Reader`): GTK's media file reads it in
    /// ranges from GStreamer's thread; a medium the system cannot decode says the core's [unplayable] words instead (Save a
    /// copy stays in the header bar).
    fn play(self: &Rc<Self>, unplayable: Option<String>) {
        let Some(file) = self.id.get() else { return };
        let weak = Rc::downgrade(self);
        let asked = *self.drawn.borrow();
        glib::spawn_future_local(async move {
            let answered = bridge::call(&Call::FileReader { file }).await.map(|(a, _)| a);
            let inner = weak.upgrade().filter(|inner| *inner.drawn.borrow() == asked);
            let (reader, size) = match (answered, inner.as_ref()) {
                (Ok(Answer::Reader { reader, size, media_type }), Some(_)) => {
                    eprintln!("veyq: file medium: reader {reader}, {size} B, {}", media_type.as_deref().unwrap_or("no type"));
                    (reader, size)
                }
                // the page went or shows another content meanwhile: the reader is let go
                (Ok(Answer::Reader { reader, .. }), None) => return bridge::send(Call::ReaderClose { reader }),
                (other, inner) => {
                    eprintln!("veyq: File.Reader answered {other:?}");
                    if let Some(inner) = inner {
                        inner.replace(&message(&words::file_open_failed()));
                    }
                    return;
                }
            };
            let Some(inner) = inner else { return };
            let weak = Rc::downgrade(&inner);
            let playing = Playing::new(reader, size, &words::tab_depot_file(), false, move || {
                // let go of the player, then say what the core says of a medium not shown here
                if let Some(inner) = weak.upgrade() {
                    inner.stop_media();
                    inner.replace(&message(&unplayable.as_deref().map(said::key).unwrap_or_else(words::file_unsupported)));
                }
            });
            let video = playing.video.clone();
            *inner.media.borrow_mut() = Some(playing);
            inner.replace(&video);
        });
    }

    /// Stops the medium playing, if one is: the player let go first (its pipeline stopped), then the stream closed, which
    /// lets the core's reader go.
    fn stop_media(&self) {
        let held = self.media.borrow_mut().take();
        if let Some(playing) = held {
            playing.stop();
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

/// A text, read only and selectable, in a monospace view of its own scroll; a cut one says so above it. The web's two
/// conveniences (`FileViewer.vue`): *Copy* puts exactly what is shown on the clipboard (a cut text its shown part, which the
/// button and its note say), and *Wrap lines* wraps long lines instead of scrolling sideways (on at first, as on the web).
fn text_view(text: &str, cut: Option<String>) -> gtk::Widget {
    let column = gtk::Box::new(gtk::Orientation::Vertical, 0);
    let truncated = cut.is_some();
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

    let tools = gtk::Box::builder().orientation(gtk::Orientation::Horizontal).spacing(12).margin_top(8).margin_start(12).margin_end(12).build();
    let copy = gtk::Button::with_label(&if truncated { words::file_text_copy_shown() } else { words::file_text_copy() });
    let wrap = gtk::CheckButton::builder().label(words::file_text_wrap()).active(true).build();
    let copied = gtk::Label::builder().xalign(0.0).hexpand(true).wrap(true).build();
    copied.add_css_class("dim-label");
    let shown = text.to_owned();
    let note = copied.clone();
    copy.connect_clicked(move |b| {
        b.clipboard().set_text(&shown);
        note.set_label(&if truncated { words::file_text_copied_shown() } else { words::file_text_copied() });
        eprintln!("veyq: file text copied: {} characters", shown.chars().count());
        let note = note.clone();
        // "copied" shows for the core's `COPIED_MS` (the web's copy notes)
        glib::timeout_add_local_once(crate::core_ui::said::copied_for(), move || note.set_label(""));
    });
    let (v, sc) = (view.clone(), scroll.clone());
    wrap.connect_toggled(move |w| {
        v.set_wrap_mode(if w.is_active() { gtk::WrapMode::WordChar } else { gtk::WrapMode::None });
        sc.set_hscrollbar_policy(if w.is_active() { gtk::PolicyType::Never } else { gtk::PolicyType::Automatic });
    });
    tools.append(&copy);
    tools.append(&wrap);
    tools.append(&copied);
    column.append(&tools);
    column.append(&scroll);
    eprintln!("veyq: file text shown: {} characters", text.chars().count());
    column.upcast()
}

/// The prompt of a password-protected PDF (the web's `FileViewer.vue`): its name, a password entry, Open and Cancel, the
/// hint that it is not saved, or a wrong one said.
fn pdf_password(name: &str, wrong: bool, open: impl Fn(String) + 'static, cancel: impl Fn() + 'static) -> gtk::Widget {
    let page = adw::StatusPage::builder().icon_name("dialog-password-symbolic").title(glib_escape(&words::file_pdf_password_title(name))).vexpand(true).build();
    page.add_css_class("compact");
    let column = gtk::Box::new(gtk::Orientation::Vertical, 12);
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("boxed-list");
    let entry = adw::PasswordEntryRow::builder().title(words::file_pdf_password_label()).build();
    list.append(&entry);
    let note = gtk::Label::builder().label(if wrong { words::file_pdf_password_wrong() } else { words::file_pdf_password_hint() }).wrap(true).xalign(0.0).build();
    note.add_css_class(if wrong { "error" } else { "dim-label" });
    let buttons = gtk::Box::builder().orientation(gtk::Orientation::Horizontal).spacing(12).halign(gtk::Align::Center).build();
    let go = gtk::Button::builder().label(words::file_pdf_password_open()).sensitive(false).build();
    go.add_css_class("suggested-action");
    go.add_css_class("pill");
    let back = gtk::Button::builder().label(words::file_pdf_password_cancel()).build();
    back.add_css_class("pill");
    buttons.append(&back);
    buttons.append(&go);
    let open = Rc::new(open);
    let submit = {
        let (entry, open) = (entry.clone(), open.clone());
        move || {
            let typed = entry.text().to_string();
            if typed.is_empty() {
                return;
            }
            entry.set_text("");
            open(typed);
        }
    };
    let submit = Rc::new(submit);
    {
        let go = go.clone();
        entry.connect_changed(move |e| go.set_sensitive(!e.text().is_empty()));
    }
    {
        let submit = submit.clone();
        entry.connect_entry_activated(move |_| submit());
    }
    go.connect_clicked(move |_| submit());
    {
        let entry = entry.clone();
        back.connect_clicked(move |_| {
            entry.set_text("");
            cancel();
        });
    }
    column.append(&list);
    column.append(&note);
    column.append(&buttons);
    let clamp = adw::Clamp::builder().maximum_size(420).child(&column).build();
    page.set_child(Some(&clamp));
    page.upcast()
}

/// The bytes as GTK decodes a picture: its own loaders for PNG, JPEG and TIFF, everything else — WebP, AVIF, HEIC and an SVG
/// the core decided is one (`FileView.svgAsImage`, inbox #1059) — through gdk-pixbuf and glycin, whose SVG loader is librsvg
/// in a sandbox of its own: librsvg runs no script and loads no resource an SVG names but a `data:` URL (the bytes come
/// with no base file, so no `file:` either), and glycin's sandbox gives the loader no network and no file system. The test
/// below holds that against hostile files.
fn decoded(bytes: Vec<u8>) -> Result<gdk::Texture, glib::Error> {
    gdk::Texture::from_bytes(&glib::Bytes::from_owned(bytes))
}

/// A picture decoded by GTK from the bytes, held at [edge] pixels on its longest side at most (the core's
/// `FileView.IMAGE_EDGE`), shown at most its own size; one GTK cannot decode is said as not shown here.
fn picture(bytes: Vec<u8>, edge: i32) -> gtk::Widget {
    let texture = match decoded(bytes) {
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

/// An SVG's type (core's `FileView.SVG`).
const SVG: &str = "image/svg+xml";

/// [bytes] (an SVG) drawn by GTK's loaders — gdk-pixbuf over glycin's sandboxed librsvg, as [decoded] — at [width] ×
/// [height] pixels: the loader is asked for that size before it draws, so librsvg renders the vectors at it (never an
/// enlarged bitmap).
fn rendered(bytes: &[u8], width: i32, height: i32) -> Result<gdk::Texture, glib::Error> {
    let loader = gtk::gdk_pixbuf::PixbufLoader::new();
    loader.connect_size_prepared(move |l, _, _| l.set_size(width, height));
    loader.write(bytes)?;
    loader.close()?;
    let pixbuf = loader.pixbuf().ok_or_else(|| glib::Error::new(glib::FileError::Failed, "no picture"))?;
    let format = if pixbuf.has_alpha() { gdk::MemoryFormat::R8g8b8a8 } else { gdk::MemoryFormat::R8g8b8 };
    Ok(gdk::MemoryTexture::new(pixbuf.width(), pixbuf.height(), format, &pixbuf.read_pixel_bytes(), pixbuf.rowstride() as usize).upcast())
}

/// An SVG on a file's page (inbox #1059; ADR 0155 § The file facts' slice D15), zoomable and sharp at every zoom as the
/// web's is: at ×1 the picture fitted into the view (never past its own size); at each zoom step (the core's,
/// `File.SvgPage`) the picture laid out at the fitted size × the step, and only the part of it in view drawn again by the
/// loader at that part's size × the screen's scale, within the core's cap (`File.SvgRaster`) — the web's tile (inbox #1112:
/// gdk-pixbuf draws an SVG only whole, so the part is cut by the SVG's own root, `svg_clipped`, and the loader and its
/// sandbox stay as they are). Under the sharp part the ×1 picture stretched to the zoom's size, shown while a scroll's
/// part is drawn. A `foreignObject` never gets here (`SvgView.refused`, in the core's open).
struct SvgZoom {
    bytes: Vec<u8>,
    first: gdk::Texture,
    natural: (f64, f64),
    steps: Vec<f64>,
    step: Cell<usize>,
    scale: i32,
    scroll: gtk::ScrolledWindow,
    picture: gtk::Picture,
    /// A zoom's layout: the stretched ×1 picture under the sharp part, both placed in it.
    canvas: gtk::Fixed,
    backdrop: gtk::Picture,
    tile: gtk::Picture,
    /// The zoom's laid-out size, in logical pixels.
    laid: Cell<(i32, i32)>,
    /// Each draw asked counts one up; an answer for an older one is dropped.
    asked: Cell<u64>,
    level: gtk::Label,
    buttons: [gtk::Button; 3],
}

impl SvgZoom {
    /// The SVG in [bytes] shown on [inner]'s page; one the loader cannot draw is said as not shown here.
    fn show(inner: &Rc<Inner>, bytes: Vec<u8>, scale: i32) {
        let first = match decoded(bytes.clone()) {
            Ok(t) => t,
            Err(e) => {
                eprintln!("veyq: the file's SVG did not decode: {e}");
                return inner.replace(&message(&words::file_unsupported()));
            }
        };
        let weak = Rc::downgrade(inner);
        let asked = *inner.drawn.borrow();
        glib::spawn_future_local(async move {
            let steps = match bridge::call(&Call::FileSvgPage).await {
                Ok((Answer::SvgPage { steps, .. }, _)) => steps,
                other => {
                    eprintln!("veyq: File.SvgPage answered {:?}", other.map(|(a, _)| a));
                    vec![1.0]
                }
            };
            let Some(inner) = weak.upgrade().filter(|inner| *inner.drawn.borrow() == asked) else { return };
            let zoom = Rc::new(SvgZoom::new(bytes, first, steps, scale));
            inner.replace(&zoom.widget());
        });
    }

    fn new(bytes: Vec<u8>, first: gdk::Texture, steps: Vec<f64>, scale: i32) -> Self {
        let button = |icon: &str, label: String| {
            let b = gtk::Button::builder().icon_name(icon).tooltip_text(&label).build();
            b.update_property(&[gtk::accessible::Property::Label(&label)]);
            b.add_css_class("flat");
            b
        };
        // a Fixed gives each child its minimum size: a picture that may shrink is exactly its size request
        let sized = || {
            let p = gtk::Picture::new();
            p.set_can_shrink(true);
            p.set_content_fit(gtk::ContentFit::Fill);
            p
        };
        let canvas = gtk::Fixed::new();
        canvas.set_halign(gtk::Align::Start);
        canvas.set_valign(gtk::Align::Start);
        let (backdrop, tile) = (sized(), sized());
        backdrop.set_paintable(Some(&first));
        canvas.put(&backdrop, 0.0, 0.0);
        canvas.put(&tile, 0.0, 0.0);
        SvgZoom {
            bytes,
            natural: (first.width() as f64, first.height() as f64),
            first,
            steps,
            step: Cell::new(0),
            scale,
            scroll: gtk::ScrolledWindow::builder().vexpand(true).hexpand(true).build(),
            picture: gtk::Picture::new(),
            canvas,
            backdrop,
            tile,
            laid: Cell::new((0, 0)),
            asked: Cell::new(0),
            level: gtk::Label::new(Some("100 %")),
            buttons: [
                button("zoom-out-symbolic", words::file_zoom_out()),
                button("zoom-in-symbolic", words::file_zoom_in()),
                button("zoom-fit-best-symbolic", words::file_zoom_fit()),
            ],
        }
    }

    fn widget(self: &Rc<Self>) -> gtk::Widget {
        let bar = gtk::Box::new(gtk::Orientation::Horizontal, 4);
        bar.set_halign(gtk::Align::End);
        let [out, zoom_in, fit] = &self.buttons;
        bar.append(out);
        bar.append(&self.level);
        bar.append(zoom_in);
        bar.append(fit);
        for (b, to) in [(out, -1i32), (zoom_in, 1), (fit, i32::MIN)] {
            let weak = Rc::downgrade(self);
            b.connect_clicked(move |_| {
                if let Some(z) = weak.upgrade() {
                    let next = if to == i32::MIN { 0 } else { (z.step.get() as i32 + to).clamp(0, z.steps.len() as i32 - 1) as usize };
                    z.zoom(next);
                }
            });
        }
        // a scroll, or the view's size changing, moves the part in view: drawn again once the scroll rests
        for adjustment in [self.scroll.hadjustment(), self.scroll.vadjustment()] {
            let weak = Rc::downgrade(self);
            adjustment.connect_value_changed(move |_| {
                if let Some(z) = weak.upgrade() {
                    z.part_soon();
                }
            });
            let weak = Rc::downgrade(self);
            adjustment.connect_changed(move |_| {
                if let Some(z) = weak.upgrade() {
                    z.part_soon();
                }
            });
        }
        // ×1: the picture at its own size, fitted into the view by GTK (never enlarged)
        self.picture.set_paintable(Some(&self.first));
        self.picture.set_can_shrink(true);
        self.picture.set_content_fit(gtk::ContentFit::ScaleDown);
        self.picture.update_property(&[gtk::accessible::Property::Label(&format!("{}×{}", self.natural.0, self.natural.1))]);
        self.canvas.update_property(&[gtk::accessible::Property::Label(&format!("{}×{}", self.natural.0, self.natural.1))]);
        self.scroll.set_child(Some(&self.picture));
        self.steps_shown();
        let column = gtk::Box::new(gtk::Orientation::Vertical, 8);
        column.set_vexpand(true);
        column.append(&bar);
        column.append(&self.scroll);
        column.upcast()
    }

    fn steps_shown(&self) {
        let i = self.step.get();
        self.level.set_text(&format!("{} %", (self.steps[i] * 100.0).round()));
        self.buttons[0].set_sensitive(i > 0);
        self.buttons[1].set_sensitive(i + 1 < self.steps.len());
        self.buttons[2].set_sensitive(i > 0);
    }

    /// Zoomed to step [next]: the picture laid out at the fitted size × the step, its part in view drawn once laid out.
    fn zoom(self: &Rc<Self>, next: usize) {
        let was = self.step.replace(next);
        self.steps_shown();
        self.asked.set(self.asked.get() + 1);
        if next == 0 {
            return self.scroll.set_child(Some(&self.picture));
        }
        let (w, h) = self.natural;
        let room = (self.scroll.width().max(1) as f64, self.scroll.height().max(1) as f64);
        let fit = (room.0 / w).min(room.1 / h).min(1.0);
        let laid = ((w * fit * self.steps[next]).round().max(1.0) as i32, (h * fit * self.steps[next]).round().max(1.0) as i32);
        self.laid.set(laid);
        self.backdrop.set_size_request(laid.0, laid.1);
        self.tile.set_paintable(None::<&gdk::Paintable>);
        if was == 0 {
            self.scroll.set_child(Some(&self.canvas));
        }
        self.part_soon();
    }

    /// The part in view drawn again a moment after the last scroll or resize (each asks once, the last one draws).
    fn part_soon(self: &Rc<Self>) {
        if self.step.get() == 0 {
            return;
        }
        let asked = self.asked.get() + 1;
        self.asked.set(asked);
        let weak = Rc::downgrade(self);
        glib::timeout_add_local_once(PART_REST, move || {
            if let Some(z) = weak.upgrade().filter(|z| z.asked.get() == asked) {
                z.draw_part(asked);
            }
        });
    }

    /// The part of the zoomed picture in view (`svg_region`), drawn by the loader at its size × the screen's scale within
    /// the core's cap; an SVG whose root cannot be cut (`svg_clipped`: not UTF-8, no `<svg>` root) is drawn whole, as
    /// before #1112.
    fn draw_part(self: &Rc<Self>, asked: u64) {
        let (h, v) = (self.scroll.hadjustment(), self.scroll.vadjustment());
        let laid = self.laid.get();
        let Some(part) = svg_region(laid, (h.value(), v.value()), (h.page_size(), v.page_size()), self.natural) else { return };
        self.canvas.move_(&self.backdrop, part.origin.0 as f64, part.origin.1 as f64);
        let step = self.steps[self.step.get()];
        let weak = Rc::downgrade(self);
        let scale = self.scale as f64;
        glib::spawn_future_local(async move {
            let raster = bridge::call(&Call::FileSvgRaster { width: part.size.0 as f64, height: part.size.1 as f64, ratio: scale }).await;
            let Some(z) = weak.upgrade().filter(|z| z.asked.get() == asked) else { return };
            let Ok((Answer::SvgRaster { width, height, ratio }, _)) = raster else {
                return eprintln!("veyq: File.SvgRaster answered {:?}", raster.map(|(a, _)| a));
            };
            let (drawn, at, size) = match svg_clipped(&z.bytes, z.natural, part.natural, (width, height)) {
                Some(cut) => (rendered(&cut, width, height), part.at, part.size),
                None => {
                    let whole = bridge::call(&Call::FileSvgRaster { width: laid.0 as f64, height: laid.1 as f64, ratio: scale }).await;
                    let Ok((Answer::SvgRaster { width, height, .. }, _)) = whole else { return };
                    if z.asked.get() != asked {
                        return;
                    }
                    (rendered(&z.bytes, width, height), (0, 0), laid)
                }
            };
            match drawn {
                Ok(t) => {
                    eprintln!(
                        "veyq: file SVG at ×{step}: {}×{} px for the part {}×{} at {},{} of {}×{} ({ratio:.2} px each)",
                        t.width(), t.height(), size.0, size.1, at.0, at.1, laid.0, laid.1
                    );
                    z.tile.set_paintable(Some(&t));
                    z.tile.set_size_request(size.0, size.1);
                    z.canvas.move_(&z.tile, (part.origin.0 + at.0) as f64, (part.origin.1 + at.1) as f64);
                }
                Err(e) => eprintln!("veyq: the file's SVG did not draw at ×{step}: {e}"),
            }
        });
    }
}

/// How long a zoom's scroll rests before its part in view is drawn again: a debounce, no notice.
const PART_REST: std::time::Duration = std::time::Duration::from_millis(80);

/// The part of a zoomed SVG in view (inbox #1112), from the zoom's laid-out size, the scroll's position and the view's size.
#[derive(Debug, Clone, Copy, PartialEq)]
struct SvgRegion {
    /// Where the laid-out picture stands in the scroll: centred in the view on an axis where it is smaller than the view.
    origin: (i32, i32),
    /// The part in view in the laid-out picture's logical pixels, whole pixels covering it: its top left and its size.
    at: (i32, i32),
    size: (i32, i32),
    /// The same part in the SVG's natural pixels (its decoded size): x, y, width, height.
    natural: (f64, f64, f64, f64),
}

/// The part of a picture laid out at [laid] in view of a scroll at [scrolled] with a view of [view], all in logical pixels,
/// and that part in the SVG's [natural] pixels; none when nothing of it is in view.
fn svg_region(laid: (i32, i32), scrolled: (f64, f64), view: (f64, f64), natural: (f64, f64)) -> Option<SvgRegion> {
    let axis = |laid: f64, scrolled: f64, view: f64| {
        let origin = ((view - laid) / 2.0).max(0.0).floor();
        let from = (scrolled - origin).max(0.0).floor().min(laid);
        let to = (scrolled - origin + view).min(laid).ceil();
        (origin as i32, from as i32, (to - from).max(0.0) as i32)
    };
    let (ox, x, w) = axis(laid.0 as f64, scrolled.0, view.0);
    let (oy, y, h) = axis(laid.1 as f64, scrolled.1, view.1);
    if laid.0 < 1 || laid.1 < 1 || w < 1 || h < 1 {
        return None;
    }
    let (kx, ky) = (natural.0 / laid.0 as f64, natural.1 / laid.1 as f64);
    Some(SvgRegion { origin: (ox, oy), at: (x, y), size: (w, h), natural: (x as f64 * kx, y as f64 * ky, w as f64 * kx, h as f64 * ky) })
}

/// The SVG in [bytes] cut to [part] of its [natural] pixels (x, y, width, height) and sized [pixels]: its root's
/// `viewBox` set to that part in the SVG's own units and its `width` and `height` to the pixels, so the loader draws that
/// part alone, at that size. Nothing else in the file changes. None where the root is not found: bytes that are not UTF-8,
/// or a document whose first element is not an `svg`.
fn svg_clipped(bytes: &[u8], natural: (f64, f64), part: (f64, f64, f64, f64), pixels: (i32, i32)) -> Option<Vec<u8>> {
    let text = std::str::from_utf8(bytes).ok()?;
    let (start, end) = svg_root(text)?;
    let tag = &text[start..end];
    let name_end = 1 + tag[1..].find(|c: char| c.is_whitespace() || c == '/' || c == '>')?;
    let (mut kept, mut view_box, mut aspect) = (String::new(), None, "");
    let mut rest = &tag[name_end..];
    loop {
        let attribute = rest.trim_start();
        if attribute.starts_with('/') || attribute.starts_with('>') {
            rest = attribute;
            break;
        }
        let eq = attribute.find('=')?;
        let name = attribute[..eq].trim();
        let after = attribute[eq + 1..].trim_start();
        let quote = after.chars().next().filter(|q| *q == '"' || *q == '\'')?;
        let close = 1 + after[1..].find(quote)?;
        let value = &after[1..close];
        match name {
            "width" | "height" => {}
            "viewBox" => view_box = svg_view_box(value),
            "preserveAspectRatio" => aspect = value,
            _ => {
                kept.push(' ');
                kept.push_str(name);
                kept.push('=');
                kept.push_str(&after[..=close]);
            }
        }
        rest = &after[close + 1..];
    }
    let (x, y, w, h) = svg_user_part(view_box, aspect, natural, part);
    let root = format!(
        "{}{kept} width=\"{}\" height=\"{}\" viewBox=\"{x} {y} {w} {h}\" preserveAspectRatio=\"none\"{rest}",
        &tag[..name_end], pixels.0, pixels.1
    );
    Some([&text[..start], &root, &text[end..]].concat().into_bytes())
}

/// The byte range of the document's root start tag where that root is an `svg` (any prefix), past the prolog: the XML
/// declaration, processing instructions, comments and a DOCTYPE with its internal subset.
fn svg_root(text: &str) -> Option<(usize, usize)> {
    let mut i = 0;
    loop {
        let lt = i + text[i..].find('<')?;
        let rest = &text[lt..];
        if rest.starts_with("<?") {
            i = lt + rest.find("?>")? + 2;
        } else if rest.starts_with("<!--") {
            i = lt + rest.find("-->")? + 3;
        } else if rest.starts_with("<!") {
            i = lt + markup_end(rest, true)?;
        } else {
            let name = rest[1..].split(|c: char| c.is_whitespace() || c == '/' || c == '>').next()?;
            return (name.rsplit(':').next() == Some("svg")).then_some((lt, lt + markup_end(rest, false)?));
        }
    }
}

/// The end (past its `>`) of the markup [text] starts with: quotes skipped, and for a declaration its `[ … ]` subset.
fn markup_end(text: &str, declaration: bool) -> Option<usize> {
    let (mut quote, mut depth) = (None, 0);
    for (i, c) in text.char_indices() {
        match (quote, c) {
            (Some(q), c) if c == q => quote = None,
            (Some(_), _) => {}
            (None, '"' | '\'') => quote = Some(c),
            (None, '[') if declaration => depth += 1,
            (None, ']') if declaration => depth -= 1,
            (None, '>') if depth == 0 => return Some(i + 1),
            _ => {}
        }
    }
    None
}

/// A `viewBox`'s four numbers; none where it has not four, or a side that is not positive (the SVG then has none).
fn svg_view_box(value: &str) -> Option<[f64; 4]> {
    let numbers: Vec<f64> = value.split(|c: char| c.is_whitespace() || c == ',').filter(|s| !s.is_empty()).map(str::parse::<f64>).collect::<Result<_, _>>().ok()?;
    let &[x, y, w, h] = numbers.as_slice() else { return None };
    (w > 0.0 && h > 0.0).then_some([x, y, w, h])
}

/// [part] of the SVG's [natural] pixels in its own user units: through its [view_box] as its `preserveAspectRatio`
/// ([aspect], SVG's default `xMidYMid meet` where none) places it in the natural size; without one a user unit is a pixel.
fn svg_user_part(view_box: Option<[f64; 4]>, aspect: &str, natural: (f64, f64), part: (f64, f64, f64, f64)) -> (f64, f64, f64, f64) {
    let Some([a, b, c, d]) = view_box else { return part };
    let words: Vec<&str> = aspect.split_whitespace().filter(|w| *w != "defer").collect();
    let align = words.first().copied().unwrap_or("xMidYMid");
    let (sx, sy, tx, ty) = if align == "none" {
        (natural.0 / c, natural.1 / d, 0.0, 0.0)
    } else {
        let (fx, fy) = (natural.0 / c, natural.1 / d);
        let s = if words.get(1) == Some(&"slice") { fx.max(fy) } else { fx.min(fy) };
        let at = |min: &str, max: &str, room: f64| {
            if align.contains(min) {
                0.0
            } else if align.contains(max) {
                room
            } else {
                room / 2.0
            }
        };
        (s, s, at("xMin", "xMax", natural.0 - c * s), at("YMin", "YMax", natural.1 - d * s))
    };
    (a + (part.0 - tx) / sx, b + (part.1 - ty) / sy, part.2 / sx, part.3 / sy)
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
    /// The selection of the pages' text, once they are laid out.
    text: RefCell<Option<Rc<PdfText>>>,
}

/// What opening a PDF came to: its pages, its password still asked (none, or a wrong one), or no PDF poppler reads.
enum Opened {
    Pages(PdfPages),
    Locked,
    Failed,
}

impl PdfPages {
    /// The PDF in [bytes], opened by poppler with [password] where one was typed (it goes nowhere else and is not kept).
    fn open(bytes: &[u8], scale: i32, password: Option<&str>) -> Opened {
        let pdf = match poppler::Document::from_bytes(&glib::Bytes::from(bytes), password) {
            Ok(pdf) => pdf,
            Err(e) if e.matches(poppler::Error::Encrypted) => {
                eprintln!("veyq: the file's PDF asks for its password");
                return Opened::Locked;
            }
            Err(e) => {
                eprintln!("veyq: the file's PDF did not open: {e}");
                return Opened::Failed;
            }
        };
        let column = gtk::Box::new(gtk::Orientation::Vertical, PDF_GAP);
        column.set_margin_top(12);
        column.set_margin_bottom(12);
        let clamp = adw::Clamp::builder().maximum_size(PDF_WIDTH).child(&column).build();
        let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build();
        Opened::Pages(Self { pdf, scroll, column, rendered: RefCell::new(Vec::new()), scale, text: RefCell::new(None) })
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
                    if let Some(this) = weak.upgrade() {
                        this.column.append(&message(&words::file_open_failed()));
                    }
                    return;
                }
            };
            let Some(this) = weak.upgrade() else { return };
            let (mut pictures, mut frames) = (Vec::new(), Vec::new());
            for i in 0..shown {
                let Some(page) = this.pdf.page(i) else { break };
                let (w, h) = page.size();
                let height = if w > 0.0 { (h * f64::from(PDF_WIDTH) / w).round() as i32 } else { PDF_WIDTH };
                let label = words::file_pdf_page(i64::from(i) + 1, i64::from(total));
                let frame = PageFrame::new(PDF_WIDTH, height.max(1), page, i as usize, &label, "file");
                frame.picture.update_property(&[gtk::accessible::Property::Label(&label)]);
                this.column.append(&frame.overlay);
                pictures.push(frame.picture.clone());
                frames.push(frame);
            }
            // the text selected and copied over the pages (inbox #955)
            *this.text.borrow_mut() = Some(PdfText::attach(&this.pdf, &this.column, frames, &words::tab_depot_file(), "file"));
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
                if let Some(r) = crate::core_ui::pdf_render::render(&p, PDF_WIDTH * self.scale) {
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
    use std::time::Duration;

    /// The alpha of [texture]'s pixel at (x, y).
    fn alpha(texture: &gdk::Texture, x: usize, y: usize) -> u8 {
        let stride = texture.width() as usize * 4;
        let mut pixels = vec![0u8; stride * texture.height() as usize];
        texture.download(&mut pixels, stride);
        pixels[y * stride + x * 4 + 3]
    }

    /// Inbox #1059's threat model on Linux: an SVG drawn as a picture runs nothing in it and fetches nothing it names. Every
    /// hostile file names a listener on this host's loopback — a script's `fetch`, an `onload`, an `<image>` (both `href`
    /// spellings), a `<use>`, an `<feImage>`, CSS `@import` and `url()`, a `foreignObject`'s frame and image, an XInclude and
    /// an external entity — and the listener must see no connection. An external entity naming a file must not expand: the
    /// same document with an internal entity (the control) draws a rectangle the file's entity would draw. Runs where glycin
    /// runs (`run.py`'s SANDBOX_SKIPS: the Fedora 44 container), through the call the file page draws with.
    #[test]
    fn an_svg_draws_with_nothing_in_it_run_or_fetched() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("a loopback listener");
        let port = listener.local_addr().unwrap().port();
        listener.set_nonblocking(true).unwrap();
        let hits = Arc::new(AtomicUsize::new(0));
        let stop = Arc::new(AtomicBool::new(false));
        let (counted, stopped) = (hits.clone(), stop.clone());
        let watcher = std::thread::spawn(move || {
            while !stopped.load(Ordering::SeqCst) {
                if listener.accept().is_ok() {
                    counted.fetch_add(1, Ordering::SeqCst);
                }
                std::thread::sleep(Duration::from_millis(5));
            }
        });
        let at = format!("http://127.0.0.1:{port}");

        let benign = r##"<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20"><rect width="40" height="20" fill="#2a6"/></svg>"##;
        let picture = decoded(benign.as_bytes().to_vec()).expect("an SVG decodes");
        assert_eq!((picture.width(), picture.height()), (40, 20));
        assert_eq!(alpha(&picture, 5, 5), 255, "the benign SVG draws");

        let open = r#"<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="40" height="20""#;
        let hostile = [
            format!(r#"{open}><script>fetch("{at}/script")</script><rect width="9" height="9"/></svg>"#),
            format!(r#"{open} onload="fetch('{at}/onload')"><rect width="9" height="9"/></svg>"#),
            format!(r#"{open}><image href="{at}/image.png" width="9" height="9"/><image xlink:href="{at}/xlink.png" width="9" height="9"/></svg>"#),
            format!(r#"{open}><use href="{at}/use.svg#x"/></svg>"#),
            format!(r#"{open}><filter id="f"><feImage href="{at}/feimage.png"/></filter><rect width="9" height="9" filter="url(#f)"/></svg>"#),
            format!(r#"{open}><style>@import url({at}/import.css); rect {{ fill: url({at}/fill.svg#p) }}</style><rect width="9" height="9"/></svg>"#),
            format!(r#"{open}><foreignObject width="40" height="20"><iframe xmlns="http://www.w3.org/1999/xhtml" src="{at}/frame"/><img xmlns="http://www.w3.org/1999/xhtml" src="{at}/img.png"/></foreignObject></svg>"#),
            format!(r#"{open} xmlns:xi="http://www.w3.org/2001/XInclude"><xi:include href="{at}/include.svg"/></svg>"#),
            format!(r#"<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY e SYSTEM "{at}/entity">]>{open}><text>&e;</text></svg>"#),
        ];
        for svg in &hostile {
            // drawn or refused: either is fine, a connection is not
            let _ = decoded(svg.clone().into_bytes());
        }

        let file = std::env::temp_dir().join(format!("veyq-xxe-{}", std::process::id()));
        std::fs::write(&file, "30").unwrap();
        let entity = |declaration: String| {
            format!(r##"<?xml version="1.0"?><!DOCTYPE svg [{declaration}]><svg xmlns="http://www.w3.org/2000/svg" width="40" height="20"><rect width="&w;" height="20" fill="#2a6"/></svg>"##)
        };
        let control = decoded(entity(r#"<!ENTITY w "30">"#.to_string()).into_bytes()).expect("an internal entity decodes");
        assert_eq!(alpha(&control, 20, 10), 255, "the control: an expanded entity draws the rectangle");
        let external = entity(format!(r#"<!ENTITY w SYSTEM "file://{}">"#, file.display()));
        if let Ok(drawn) = decoded(external.into_bytes()) {
            assert_eq!(alpha(&drawn, 20, 10), 0, "an external entity naming a file expanded");
        }
        std::fs::remove_file(&file).ok();

        std::thread::sleep(Duration::from_millis(500));
        stop.store(true, Ordering::SeqCst);
        watcher.join().unwrap();
        assert_eq!(hits.load(Ordering::SeqCst), 0, "an SVG drawn as a picture reached the network");
    }

    /// A zoom stays sharp (inbox #1059, owner 2026-11-22): the loader draws the SVG's vectors at the size asked, so a 10-unit
    /// square's edge at ×10 falls between two pixels, one opaque and one clear — an enlarged 10-pixel bitmap would smear it
    /// across several partly covered pixels. Runs where glycin runs (SANDBOX_SKIPS).
    #[test]
    fn a_zoomed_svg_is_drawn_at_its_size_not_enlarged() {
        let svg = br##"<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20"><rect x="0" y="0" width="10" height="20" fill="#000"/></svg>"##;
        let natural = decoded(svg.to_vec()).expect("an SVG decodes");
        assert_eq!((natural.width(), natural.height()), (20, 20));
        let zoomed = rendered(svg, 200, 200).expect("the SVG draws at a size asked");
        assert_eq!((zoomed.width(), zoomed.height()), (200, 200));
        assert_eq!(alpha(&zoomed, 99, 100), 255, "inside the square's edge");
        assert_eq!(alpha(&zoomed, 100, 100), 0, "outside the square's edge");
        let partly = (90..110).filter(|x| !matches!(alpha(&zoomed, *x, 100), 0 | 255)).count();
        assert_eq!(partly, 0, "the edge is smeared: an enlarged bitmap");
    }

    /// The part of a zoom in view (inbox #1112): the scroll's window on the laid-out picture in whole pixels, clipped to the
    /// picture, centred where the picture is smaller than the view, and the same part in the SVG's natural pixels.
    #[test]
    fn the_part_in_view_is_the_scrolls_window_on_the_laid_out_picture() {
        // scrolled into the middle of a picture laid out at 10× its 200×100 natural size
        let middle = svg_region((2000, 1000), (500.0, 200.0), (400.0, 300.0), (200.0, 100.0)).unwrap();
        assert_eq!(middle, SvgRegion { origin: (0, 0), at: (500, 200), size: (400, 300), natural: (50.0, 20.0, 40.0, 30.0) });
        // at the far end: only what is left of the picture
        let end = svg_region((2000, 1000), (1800.0, 900.0), (400.0, 300.0), (200.0, 100.0)).unwrap();
        assert_eq!((end.at, end.size), ((1800, 900), (200, 100)));
        assert_eq!(end.natural, (180.0, 90.0, 20.0, 10.0));
        // a fractional scroll: whole pixels covering the view
        let between = svg_region((2000, 1000), (10.5, 0.0), (400.0, 300.0), (200.0, 100.0)).unwrap();
        assert_eq!((between.at, between.size), ((10, 0), (401, 300)));
        // smaller than the view: centred, all of it in view
        let small = svg_region((200, 100), (0.0, 0.0), (400.0, 300.0), (20.0, 10.0)).unwrap();
        assert_eq!(small, SvgRegion { origin: (100, 100), at: (0, 0), size: (200, 100), natural: (0.0, 0.0, 20.0, 10.0) });
        // wider than the view, lower than it: scrolls across, centred down
        let wide = svg_region((1000, 100), (300.0, 0.0), (400.0, 300.0), (100.0, 10.0)).unwrap();
        assert_eq!((wide.origin, wide.at, wide.size), ((0, 100), (300, 0), (400, 100)));
        // nothing laid out, or nothing in view
        assert_eq!(svg_region((0, 0), (0.0, 0.0), (400.0, 300.0), (20.0, 10.0)), None);
        assert_eq!(svg_region((200, 100), (0.0, 0.0), (0.0, 0.0), (20.0, 10.0)), None);
    }

    /// A part of the natural pixels in the SVG's own units: through its viewBox as its preserveAspectRatio places it.
    #[test]
    fn a_part_maps_into_the_svgs_own_units() {
        // no viewBox: a user unit is a pixel
        assert_eq!(svg_user_part(None, "", (40.0, 20.0), (10.0, 5.0, 20.0, 10.0)), (10.0, 5.0, 20.0, 10.0));
        // a viewBox of the same shape, scaled ×10 and moved
        assert_eq!(svg_user_part(Some([100.0, 50.0, 4.0, 2.0]), "", (40.0, 20.0), (10.0, 5.0, 20.0, 10.0)), (101.0, 50.5, 2.0, 1.0));
        // a square viewBox in a 40×20 picture: `xMidYMid meet` scales it ×10 and centres it, 10 pixels in
        assert_eq!(svg_user_part(Some([0.0, 0.0, 2.0, 2.0]), "", (40.0, 20.0), (10.0, 0.0, 20.0, 20.0)), (0.0, 0.0, 2.0, 2.0));
        assert_eq!(svg_user_part(Some([0.0, 0.0, 2.0, 2.0]), "xMinYMin meet", (40.0, 20.0), (0.0, 0.0, 20.0, 20.0)), (0.0, 0.0, 2.0, 2.0));
        assert_eq!(svg_user_part(Some([0.0, 0.0, 2.0, 2.0]), "xMaxYMid", (40.0, 20.0), (20.0, 0.0, 20.0, 20.0)), (0.0, 0.0, 2.0, 2.0));
        // `slice` scales by the larger side
        assert_eq!(svg_user_part(Some([0.0, 0.0, 2.0, 2.0]), "xMinYMin slice", (40.0, 20.0), (0.0, 0.0, 40.0, 20.0)), (0.0, 0.0, 2.0, 1.0));
        // `none` stretches each side alone
        assert_eq!(svg_user_part(Some([0.0, 0.0, 2.0, 2.0]), "none", (40.0, 20.0), (20.0, 10.0, 20.0, 10.0)), (1.0, 1.0, 1.0, 1.0));
        assert_eq!(svg_view_box("0,0 4 2"), Some([0.0, 0.0, 4.0, 2.0]));
        assert_eq!(svg_view_box("0 0 0 2"), None);
        assert_eq!(svg_view_box("0 0 4"), None);
    }

    /// Cutting an SVG changes its root's size and viewBox alone, past a prolog that names `<svg` and `>` where no element is.
    #[test]
    fn a_cut_svg_changes_its_roots_size_and_view_alone() {
        let svg = r#"<?xml version="1.0"?>
<!-- an <svg> in a comment -->
<!DOCTYPE svg [<!ENTITY a "<svg width='1'>">]>
<svg xmlns="http://www.w3.org/2000/svg" width="40" height='20' viewBox="0 0 4 2" data-x="a>b"><rect width="4" height="2"/></svg>"#;
        let cut = String::from_utf8(svg_clipped(svg.as_bytes(), (40.0, 20.0), (10.0, 5.0, 20.0, 10.0), (400, 200)).unwrap()).unwrap();
        let expected = svg.replace(
            r#"<svg xmlns="http://www.w3.org/2000/svg" width="40" height='20' viewBox="0 0 4 2" data-x="a>b">"#,
            r#"<svg xmlns="http://www.w3.org/2000/svg" data-x="a>b" width="400" height="200" viewBox="1 0.5 2 1" preserveAspectRatio="none">"#,
        );
        assert_eq!(cut, expected);
        // a prefixed root, self-closing, no size at all
        let bare = r#"<svg:svg xmlns:svg="http://www.w3.org/2000/svg"/>"#;
        let cut = String::from_utf8(svg_clipped(bare.as_bytes(), (300.0, 150.0), (0.0, 0.0, 30.0, 15.0), (60, 30)).unwrap()).unwrap();
        assert_eq!(cut, r#"<svg:svg xmlns:svg="http://www.w3.org/2000/svg" width="60" height="30" viewBox="0 0 30 15" preserveAspectRatio="none"/>"#);
        // no svg root, or not UTF-8: not cut
        assert_eq!(svg_clipped(b"<html><svg/></html>", (1.0, 1.0), (0.0, 0.0, 1.0, 1.0), (1, 1)), None);
        assert_eq!(svg_clipped(&[0xff, 0xfe, b'<'], (1.0, 1.0), (0.0, 0.0, 1.0, 1.0), (1, 1)), None);
    }

    /// The part in view drawn alone and sharp (inbox #1112): a 20-unit square is black up to x = 12; its units 5 to 15 cut
    /// and drawn at 100×200 put that edge at x = 70, between one opaque and one clear pixel. The whole picture drawn at
    /// that size would put it at 60, a root not cut (no viewBox) at 12, and a part enlarged from a coarser bitmap would
    /// smear it. With a viewBox of other units alike. Runs where glycin runs (SANDBOX_SKIPS).
    #[test]
    fn the_part_in_view_is_drawn_alone_and_sharp() {
        for svg in [
            r##"<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20"><rect x="0" y="0" width="12" height="20" fill="#000"/></svg>"##,
            r##"<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 2 2"><rect x="0" y="0" width="1.2" height="2" fill="#000"/></svg>"##,
        ] {
            let cut = svg_clipped(svg.as_bytes(), (20.0, 20.0), (5.0, 0.0, 10.0, 20.0), (100, 200)).expect("the root is cut");
            let part = rendered(&cut, 100, 200).expect("the part draws");
            assert_eq!((part.width(), part.height()), (100, 200));
            assert_eq!(alpha(&part, 0, 100), 255, "the part's left is black: {svg}");
            assert_eq!(alpha(&part, 69, 100), 255, "inside the edge: {svg}");
            assert_eq!(alpha(&part, 70, 100), 0, "outside the edge: {svg}");
            let partly = (55..85).filter(|x| !matches!(alpha(&part, *x, 100), 0 | 255)).count();
            assert_eq!(partly, 0, "the edge is smeared: {svg}");
        }
    }
}
