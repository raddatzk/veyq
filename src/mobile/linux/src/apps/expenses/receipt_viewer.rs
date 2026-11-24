//! A receipt opened (the web's `ReceiptViewer`; ADR 0123's Consequences, Expenses whole; Expenses' own since inbox #1066,
//! its pages drawn by the core UI's [crate::core_ui::pdf_render]), GNOME's way: an `AdwDialog` with the receipt's
//! name, Save a copy in its header bar and the receipt below. Its bytes come beside the answer (`Receipt.Open`, ADR 0137)
//! and are shown from memory, never written to disk: a picture decoded by GTK (`gdk::Texture::from_bytes`, glycin's
//! loaders), a PDF by GNOME's own PDF stack (poppler-glib through poppler-rs, from the bytes; each page rendered onto a
//! cairo surface at the dialog's width and shown as a `gdk::MemoryTexture`) — as many pages as the core says
//! (`Receipt.Pages`), and what it says when that is not all. A PDF's pages are the core UI's, as a Depot file's are
//! (inbox #1220): their text selected and copied ([crate::core_ui::pdf_text]) and read by a screen reader, each page a
//! read-only text box over poppler's text ([crate::core_ui::pdf_reading]). Any other file is offered for saving only. Save a copy asks
//! GTK's save dialog for a path and the core writes the receipt there (`Receipt.Save`). Offline (ADR 0153 decision 6) a
//! receipt this device does not keep — never shown here, nor fetched by the Pot's pin — is refused `NotKeptOffline` and
//! says so, not that it is unreadable; one shown before opens from the store.
use adw::prelude::*;
use gtk::{gdk, glib};

use crate::bridge::{self, wire::Answer, wire::Call, wire::Why};
use crate::core_ui::home::glib_escape;
use crate::core_ui::pdf_text::{PageFrame, PdfText};
use crate::core_ui::{pdf_render, said, words};

/// The width a receipt is laid out at in the dialog, in logical pixels.
const WIDTH: i32 = 640;

/// Opens receipt [receipt] of Pot [pot], listed as [name], over [parent].
pub fn open(parent: &impl IsA<gtk::Widget>, pot: i64, receipt: &str, name: &str) {
    let header = adw::HeaderBar::new();
    let save = gtk::Button::builder().label(words::receipts_save()).sensitive(false).build();
    header.pack_end(&save);
    let body = gtk::Box::new(gtk::Orientation::Vertical, 12);
    body.set_margin_top(12);
    body.set_margin_bottom(24);
    body.set_margin_start(12);
    body.set_margin_end(12);
    let loading = adw::Spinner::builder().width_request(32).height_request(32).halign(gtk::Align::Center).build();
    body.append(&loading);
    let clamp = adw::Clamp::builder().maximum_size(WIDTH).child(&body).build();
    let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build();
    let toasts = adw::ToastOverlay::new();
    toasts.set_child(Some(&scroll));
    let view = adw::ToolbarView::new();
    view.add_top_bar(&header);
    view.set_content(Some(&toasts));
    let dialog = adw::Dialog::builder().title(name).content_width(WIDTH + 48).content_height(760).child(&view).build();

    let (pot_id, receipt_id, file_name) = (pot, receipt.to_owned(), name.to_owned());
    let (d, t) = (dialog.clone(), toasts.clone());
    save.connect_clicked(move |_| save_copy(&d, &t, pot_id, &receipt_id, &file_name));

    let (receipt, label) = (receipt.to_owned(), name.to_owned());
    let (b, s, scale) = (body.clone(), save.clone(), parent.scale_factor().max(1));
    glib::spawn_future_local(async move {
        let answered = bridge::call(&Call::ReceiptOpen { pot, receipt }).await;
        b.remove(&loading);
        match answered {
            Ok((Answer::Bytes { media_type }, Some(bytes))) => {
                s.set_sensitive(true);
                eprintln!("veyq: receipt opened: {} B, {}", bytes.len(), media_type.as_deref().unwrap_or("no receipt type"));
                match media_type.as_deref() {
                    Some("application/pdf") => show_pdf(&b, bytes, scale, &label).await,
                    Some(t) if t.starts_with("image/") => show_picture(&b, bytes),
                    _ => b.append(&only_saved()),
                }
            }
            Ok((Answer::Refused { why: Why::NotKeptOffline }, _)) => {
                eprintln!("veyq: Receipt.Open: not kept offline");
                b.append(&status(&words::offline_not_kept(), None));
            }
            other => {
                eprintln!("veyq: Receipt.Open answered {other:?}");
                b.append(&status(&words::receipts_unreadable(), None));
            }
        }
    });
    dialog.present(Some(parent));
}

/// A picture decoded by GTK from the bytes; one GTK cannot decode is offered for saving.
fn show_picture(body: &gtk::Box, bytes: Vec<u8>) {
    match gdk::Texture::from_bytes(&glib::Bytes::from_owned(bytes)) {
        Ok(texture) => {
            eprintln!("veyq: receipt picture decoded: {}×{}", texture.width(), texture.height());
            body.append(&fitted(&texture));
        }
        Err(e) => {
            eprintln!("veyq: the receipt's picture did not decode: {e}");
            body.append(&only_saved());
        }
    }
}

/// A PDF's pages, as many as the core says, each rendered once at the dialog's width in device pixels ([scale]) into a
/// page frame of the core UI's, whose text is selected, copied and read aloud over [body] (named [label], the receipt's
/// name); the main loop draws between two pages. A page that does not render ends the pages shown: the frames are the
/// document's first pages in order, as the selection counts them.
async fn show_pdf(body: &gtk::Box, bytes: Vec<u8>, scale: i32, label: &str) {
    let pdf = match poppler::Document::from_bytes(&glib::Bytes::from_owned(bytes), None) {
        Ok(pdf) => pdf,
        Err(e) => {
            eprintln!("veyq: the receipt's PDF did not open: {e}");
            body.append(&only_saved());
            return;
        }
    };
    let total = pdf.n_pages();
    let (shown, cut) = match bridge::call(&Call::ReceiptPages { total }).await.map(|(a, _)| a) {
        Ok(Answer::ReceiptPages { shown, cut }) => (shown, cut),
        other => {
            eprintln!("veyq: Receipt.Pages answered {other:?}");
            return;
        }
    };
    let mut frames = Vec::new();
    for i in 0..shown {
        let Some(page) = pdf.page(i) else { break };
        let Some(r) = pdf_render::render(&page, WIDTH * scale) else {
            eprintln!("veyq: page {} of the receipt's PDF did not render", i + 1);
            break;
        };
        // cairo's ARGB32 is native-endian premultiplied: B, G, R, A in memory on every target the app is built for
        let texture = gdk::MemoryTexture::new(r.width, r.height, gdk::MemoryFormat::B8g8r8a8Premultiplied, &glib::Bytes::from_owned(r.data), r.stride);
        let page_label = words::file_pdf_page(i64::from(i) + 1, i64::from(total));
        let frame = PageFrame::new(WIDTH, frame_height(r.width, r.height), page, i as usize, &page_label, "receipt");
        frame.picture.set_paintable(Some(&texture));
        frame.picture.update_property(&[gtk::accessible::Property::Label(&page_label)]);
        body.append(&frame.overlay);
        frames.push(frame);
        // the page shown before the next is rendered
        glib::timeout_future(std::time::Duration::ZERO).await;
    }
    let drawn = frames.len();
    eprintln!("veyq: receipt PDF: {drawn} of {total} pages drawn");
    if drawn > 0 {
        // the selection's handlers hold it weakly: the body holds it, until the dialog lets go of the body
        let held = std::cell::RefCell::new(Some(PdfText::attach(&pdf, body, frames, label, "receipt")));
        body.connect_destroy(move |_| drop(held.borrow_mut().take()));
    }
    if let Some(cut) = cut {
        let note = gtk::Label::builder().label(said::notice(&cut)).wrap(true).xalign(0.0).build();
        note.add_css_class("dim-label");
        body.append(&note);
    }
    if drawn == 0 {
        body.append(&only_saved());
    }
}

/// [texture] at most [WIDTH] logical pixels wide, its height kept in proportion (a picture asks no height for its width),
/// and never larger than its own size: one pixel of it at most one logical pixel, as Windows' `Image` shows it
/// (`ContentFit::ScaleDown`; GTK's default, `Contain`, scaled a narrow picture up to the dialog's width).
fn fitted(texture: &impl IsA<gdk::Paintable>) -> gtk::Picture {
    let (tw, th) = (texture.intrinsic_width().max(1), texture.intrinsic_height().max(1));
    let picture = gtk::Picture::for_paintable(texture);
    picture.set_can_shrink(true);
    picture.set_content_fit(gtk::ContentFit::ScaleDown);
    picture.set_hexpand(false);
    let width = tw.min(WIDTH);
    picture.set_size_request(width, (f64::from(th) * f64::from(width) / f64::from(tw)).round() as i32);
    picture.set_halign(gtk::Align::Center);
    picture
}

/// A page frame's height at [WIDTH] for a page rendered [width] × [height] device pixels: its aspect kept.
fn frame_height(width: i32, height: i32) -> i32 {
    if width <= 0 {
        return WIDTH;
    }
    ((f64::from(height) * f64::from(WIDTH) / f64::from(width)).round() as i32).max(1)
}

/// A file shown only as a copy to save (no picture, no PDF, or one that does not decode here).
fn only_saved() -> adw::StatusPage {
    status(&words::receipts_cannot_show(), Some(&words::receipts_save_to_open()))
}

fn status(title: &str, description: Option<&str>) -> adw::StatusPage {
    let page = adw::StatusPage::builder().icon_name("text-x-generic-symbolic").title(glib_escape(title)).build();
    if let Some(d) = description {
        page.set_description(Some(&glib_escape(d)));
    }
    page.add_css_class("compact");
    page
}

/// "Save a copy": GTK's save dialog names the file, the core writes the receipt there.
fn save_copy(dialog: &adw::Dialog, toasts: &adw::ToastOverlay, pot: i64, receipt: &str, name: &str) {
    let parent = dialog.root().and_downcast::<gtk::Window>();
    let (toasts, receipt, name) = (toasts.clone(), receipt.to_owned(), name.to_owned());
    glib::spawn_future_local(async move {
        let chooser = gtk::FileDialog::builder().title(words::receipts_save()).initial_name(name.as_str()).modal(true).build();
        if let Some(downloads) = glib::user_special_dir(glib::UserDirectory::Downloads).filter(|d| d.is_dir()) {
            chooser.set_initial_folder(Some(&gtk::gio::File::for_path(downloads)));
        }
        let file = match chooser.save_future(parent.as_ref()).await {
            Ok(file) => file,
            Err(e) if e.matches(gtk::DialogError::Dismissed) || e.matches(gtk::DialogError::Cancelled) => return,
            Err(e) => {
                eprintln!("veyq: the receipt's save dialog failed: {e}");
                toasts.add_toast(said::word_toast("receipts_not_saved", &words::receipts_not_saved()));
                return;
            }
        };
        // the core opens the path itself; one that is no UTF-8 does not cross the wire
        let Some(path) = file.path().and_then(|p| p.to_str().map(str::to_owned)) else {
            toasts.add_toast(said::word_toast("receipts_not_saved", &words::receipts_not_saved()));
            return;
        };
        let answered = bridge::call(&Call::ReceiptSave { pot, receipt, path: path.clone() }).await.map(|(a, _)| a);
        eprintln!("veyq: receipt saved to {path}: {answered:?}");
        toasts.add_toast(if matches!(answered, Ok(Answer::Done)) {
            said::word_toast("receipts_saved", &words::receipts_saved())
        } else {
            said::word_toast("receipts_not_saved", &words::receipts_not_saved())
        });
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_pages_frame_keeps_its_aspect_at_the_dialogs_width() {
        // a Letter page rendered at twice the width: 1280 × 1656 device pixels, a frame 640 × 828
        assert_eq!(frame_height(1280, 1656), 828);
        // a landscape page, at one pixel to one
        assert_eq!(frame_height(640, 400), 400);
        // a page rendered at no width: a square frame, never one of no height
        assert_eq!(frame_height(0, 0), WIDTH);
        assert_eq!(frame_height(5000, 1), 1);
    }
}
