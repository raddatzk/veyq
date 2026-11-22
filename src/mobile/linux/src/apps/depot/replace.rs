//! A file's Replace (ADR 0154 decision 3, inbox #1066): a member's, from its page's header, into the tenant's queue
//! (`Upload.Replace`), and a READ_WRITE guest's, from the link's bar, into the link's own (`Received.Replace`) — each chosen
//! in the core first (`File.ChooseReplace`: the replace expects the content shown now), then GTK's open dialog picks the
//! one file whose path goes to the core.
use gtk::glib;

use crate::bridge::{self, wire::Answer, wire::Call, wire::Why};
use crate::core_ui::uploads::{dismissed, local_path, queued};
use crate::core_ui::words;

/// A file's Replace (the header's, on its page [file]): chosen in the core first — the replace expects the content shown
/// now —, then GTK's open dialog picks the one file whose path goes to the core (`Upload.Replace`).
pub fn member(parent: &gtk::Window, file: i64, toasts: &adw::ToastOverlay) {
    let (parent, toasts) = (parent.clone(), toasts.clone());
    glib::spawn_future_local(async move {
        match bridge::call(&Call::FileChooseReplace { file }).await.map(|(a, _)| a) {
            Ok(Answer::Done) => {}
            other => return eprintln!("veyq: File.ChooseReplace answered {other:?}"),
        }
        let dialog = gtk::FileDialog::builder().title(words::depot_replace_title()).modal(true).build();
        let picked = match dialog.open_future(Some(&parent)).await {
            Ok(picked) => picked,
            Err(e) => return dismissed(&e, "replace"),
        };
        let Some(path) = local_path(&picked) else { return };
        let answer = bridge::call(&Call::UploadReplace { file, path }).await.map(|(a, _)| a);
        queued(&toasts, answer, "Upload.Replace");
    });
}

/// A link's file on screen replaced as its READ_WRITE guest (#1035): Replace chosen on its page, a file picked, and
/// `Received.Replace` — the link's own queue, never the tenant's, so a signed-out guest replaces too.
pub fn guest(parent: &gtk::Window, received: i64, file: i64, toasts: &adw::ToastOverlay) {
    let (parent, toasts) = (parent.clone(), toasts.clone());
    glib::spawn_future_local(async move {
        match bridge::call(&Call::FileChooseReplace { file }).await.map(|(a, _)| a) {
            Ok(Answer::Done) => {}
            other => return eprintln!("veyq: File.ChooseReplace answered {other:?}"),
        }
        let dialog = gtk::FileDialog::builder().title(words::depot_replace_title()).modal(true).build();
        let picked = match dialog.open_future(Some(&parent)).await {
            Ok(picked) => picked,
            Err(e) => return dismissed(&e, "replace"),
        };
        let Some(path) = local_path(&picked) else { return };
        let answer = bridge::call(&Call::ReceivedReplace { received, path }).await.map(|(a, _)| a);
        // the guest declined the core's naming at the write: nothing to say, it chose so
        if let Ok(Answer::Refused { why: Why::NotChosen }) = answer {
            return eprintln!("veyq: Received.Replace: not chosen");
        }
        queued(&toasts, answer, "Received.Replace");
    });
}
