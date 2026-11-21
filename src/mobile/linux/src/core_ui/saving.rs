//! A save as it runs and ends (a file's Save a copy, a Memory's original saved from its lightbox, a track's or a GPX track's
//! download): its progress with Cancel in an `AdwBanner` of its own along the shell's content pane, how
//! it ended as a toast. Core UI: every application that saves runs it ([saving]); the shell gives it its place
//! ([show_saves_in]).
use std::cell::RefCell;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, wire::Call, wire::Update, wire::Watched};
use crate::core_ui::home::glib_escape;
use crate::core_ui::{said, words};

thread_local! {
    /// Where a running save's banner stands (the shell's, [show_saves_in]).
    static SAVES: RefCell<Option<glib::WeakRef<gtk::Box>>> = const { RefCell::new(None) };
}

/// The shell's place for the running saves' banners ([saving]).
pub fn show_saves_in(saves: &gtk::Box) {
    SAVES.with_borrow_mut(|s| *s = Some(saves.downgrade()));
}

/// A save as it runs and ends (a file's Save a copy, a Memory's original saved from its lightbox, a track's or a GPX
/// track's download): its progress with Cancel in an `AdwBanner` of its own in the shell's place for it — the toasts
/// show one at a time, and a progress that never times out held every later toast (an upload's) back until it ended —,
/// then how it ended as a toast in [toasts].
pub async fn saving(save: i64, toasts: &adw::ToastOverlay) {
    let Some((_watch, updates)) = bridge::watch(&Watched::Save { save }) else { return };
    let banner = adw::Banner::builder().title("").button_label(words::download_cancel()).revealed(true).build();
    banner.connect_button_clicked(move |_| {
        eprintln!("veyq: save {save}: cancel pressed");
        bridge::send(Call::SaveCancel { save });
    });
    let host = SAVES.with_borrow(|s| s.as_ref().and_then(glib::WeakRef::upgrade));
    if let Some(h) = &host {
        h.append(&banner);
    }
    let mut logged = -1;
    while let Ok(update) = updates.recv().await {
        let Ok(Update::Save { shown: Some(s), .. }) = update else { continue };
        if s.end.is_none() {
            banner.set_title(&glib_escape(&said::notice(&s.notice)));
            // a line on stderr each tenth, for a pass to read the progress from
            let tenth = (s.fraction * 10.0).floor() as i32;
            if tenth != logged {
                logged = tenth;
                eprintln!("veyq: save {save}: {} of {} B", s.written, s.total);
            }
            continue;
        }
        eprintln!("veyq: save {save} ended {:?}: {} of {} B", s.end, s.written, s.total);
        // a save's end lives as its notice's kind says: saved briefly, a failure until closed
        toasts.add_toast(said::notice_toast(&s.notice));
        break;
    }
    // ended, or its watch went with the tenant: the banner goes either way
    if let Some(h) = &host {
        h.remove(&banner);
    }
}
