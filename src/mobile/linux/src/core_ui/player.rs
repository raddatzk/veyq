//! A medium played from the core's reader (ADR 0137 decision 2), shared by a Depot file's page (`apps::depot::file_page`) and a
//! Memory's lightbox (`apps::memories::lightbox`): GTK's own player (`gtk::Video`) over a `gtk::MediaFile` reading
//! [crate::core_ui::reader_stream] — the core's ranges, decrypted in memory, never a file — from GStreamer's thread, the system's
//! GStreamer decoding it. What it cannot decode raises the stream's error, handed to the page to say its words.
use std::cell::Cell;

use gtk::glib;
use gtk::prelude::*;

use crate::core_ui::reader_stream::{CoreReader, ReaderStream};

/// A medium playing: its view, and the player and stream behind it, let go by [Playing::stop].
pub struct Playing {
    pub video: gtk::Video,
    media: gtk::MediaFile,
    stream: ReaderStream,
}

impl Playing {
    /// The medium behind core reader [reader] ([size] bytes), its view named [label]; [autoplay] where the person asked to
    /// play it (never on its own). [failed] runs once the player said it cannot play it, after its own signal returned.
    pub fn new(reader: i64, size: i64, label: &str, autoplay: bool, failed: impl Fn() + Clone + 'static) -> Self {
        let stream = ReaderStream::new(size.max(0) as u64, CoreReader(reader));
        let media = gtk::MediaFile::for_input_stream(&stream);
        media.connect_error_notify(move |media| {
            let Some(error) = media.error() else { return };
            eprintln!("veyq: the medium does not play here: {error}");
            let failed = failed.clone();
            glib::idle_add_local_once(failed);
        });
        media.connect_prepared_notify(|media| {
            if media.is_prepared() {
                eprintln!("veyq: medium prepared: {} s, video {}, audio {}, seekable {}", media.duration() / 1_000_000,
                    media.has_video(), media.has_audio(), media.is_seekable());
            }
        });
        // the position on stderr each fifth second, for a pass to read the play and a seek from
        let logged = Cell::new(-1i64);
        media.connect_timestamp_notify(move |media| {
            let at = media.timestamp() / 5_000_000;
            if at != logged.get() {
                logged.set(at);
                eprintln!("veyq: medium at {} s of {} s", media.timestamp() / 1_000_000, media.duration() / 1_000_000);
            }
        });
        let video = gtk::Video::builder().media_stream(&media).autoplay(autoplay).vexpand(true).hexpand(true).build();
        video.update_property(&[gtk::accessible::Property::Label(label)]);
        Self { video, media, stream }
    }

    /// [f] told each time the medium starts playing (true) or stops — paused, ended, failed — (false): a Memory's lightbox
    /// interrupts the music while its video plays (ADR 0142 decision 13).
    pub fn on_playing(&self, f: impl Fn(bool) + 'static) {
        self.media.connect_playing_notify(move |media| f(media.is_playing()));
    }

    /// Stops it: the player let go first (its pipeline stopped), then the stream closed, which lets the core's reader go.
    pub fn stop(self) {
        self.media.pause();
        self.media.clear();
        if let Err(e) = self.stream.close(gtk::gio::Cancellable::NONE) {
            eprintln!("veyq: the medium's stream did not close: {e}");
        }
    }
}
