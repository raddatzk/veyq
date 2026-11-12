//! A medium's plaintext as a seekable `gio::InputStream` (the Depot CR's D7; ADR 0130 decision 2): GTK's media file reads it
//! (`gtk::MediaFile::for_input_stream`, GStreamer's `giostreamsrc` beneath, measured in Phase 4) in ranges from its own
//! streaming thread, and each range comes from the core's reader (`Reader.Read`), only the frames it needs decrypted there.
//! Nothing of the medium is written anywhere (ADR 0047); this side holds one window of at most [WINDOW] bytes it read last.
//!
//! A read blocks only the player's thread on the core's answer, which arrives on a core thread: never GTK's main loop.
use std::sync::Mutex;

use gtk::gio;
use gtk::glib;
use gtk::subclass::prelude::*;

use crate::bridge::{self, wire::Answer, wire::Call};

/// The bytes asked of the reader at once at least (one of the core's frames): a player's small reads are served from them.
pub const WINDOW: usize = 256 * 1024;

/// Where a stream's bytes come from: up to `length` bytes from `position` — fewer only at the end, none from it on.
pub trait Ranges: Send + 'static {
    fn read(&self, position: u64, length: usize) -> Result<Vec<u8>, String>;
    /// The stream is closed: the source lets go of what it holds.
    fn close(&self) {}
}

/// The core's reader `reader` (`File.Reader`'s answer), read through the bridge.
pub struct CoreReader(pub i64);

impl Ranges for CoreReader {
    fn read(&self, position: u64, length: usize) -> Result<Vec<u8>, String> {
        let call = Call::ReaderRead { reader: self.0, position: position as i64, length: length.min(i32::MAX as usize) as i32 };
        match bridge::call_blocking(&call) {
            Ok((Answer::Bytes { .. }, Some(bytes))) => Ok(bytes),
            Ok((other, _)) => Err(format!("Reader.Read answered {other:?}")),
            Err(e) => Err(format!("Reader.Read did not read: {:?}", e.0)),
        }
    }

    fn close(&self) {
        // its answer is not awaited: the call reaches the core when it is made
        drop(bridge::call(&Call::ReaderClose { reader: self.0 }));
    }
}

struct State {
    source: Option<Box<dyn Ranges>>,
    size: u64,
    position: u64,
    /// The bytes read last and where they start.
    window: Vec<u8>,
    window_at: u64,
}

mod imp {
    use super::*;

    pub struct ReaderStream {
        pub(super) state: Mutex<State>,
    }

    impl Default for ReaderStream {
        fn default() -> Self {
            Self { state: Mutex::new(State { source: None, size: 0, position: 0, window: Vec::new(), window_at: 0 }) }
        }
    }

    #[glib::object_subclass]
    impl ObjectSubclass for ReaderStream {
        const NAME: &'static str = "VeyqReaderStream";
        type Type = super::ReaderStream;
        type ParentType = gio::InputStream;
        type Interfaces = (gio::Seekable,);
    }

    impl ObjectImpl for ReaderStream {}

    impl InputStreamImpl for ReaderStream {
        fn read(&self, buffer: &mut [u8], _cancellable: Option<&gio::Cancellable>) -> Result<usize, glib::Error> {
            let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
            let n = (s.size.saturating_sub(s.position)).min(buffer.len() as u64) as usize;
            if n == 0 {
                return Ok(0);
            }
            let held = s.position >= s.window_at && s.position + n as u64 <= s.window_at + s.window.len() as u64;
            if !held {
                let Some(source) = s.source.as_ref() else {
                    return Err(glib::Error::new(gio::IOErrorEnum::Closed, "closed"));
                };
                let at = s.position;
                let bytes = source.read(at, n.max(WINDOW)).map_err(|e| glib::Error::new(gio::IOErrorEnum::Failed, &e))?;
                if bytes.len() < n {
                    return Err(glib::Error::new(gio::IOErrorEnum::Failed, "the reader ended early"));
                }
                s.window = bytes;
                s.window_at = at;
            }
            let from = (s.position - s.window_at) as usize;
            buffer[..n].copy_from_slice(&s.window[from..from + n]);
            s.position += n as u64;
            Ok(n)
        }

        fn close(&self, _cancellable: Option<&gio::Cancellable>) -> Result<(), glib::Error> {
            let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(source) = s.source.take() {
                source.close();
            }
            s.window = Vec::new();
            Ok(())
        }
    }

    impl SeekableImpl for ReaderStream {
        fn tell(&self) -> i64 {
            self.state.lock().unwrap_or_else(|e| e.into_inner()).position as i64
        }

        fn can_seek(&self) -> bool {
            true
        }

        fn seek(&self, offset: i64, type_: glib::SeekType, _cancellable: Option<&gio::Cancellable>) -> Result<(), glib::Error> {
            let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
            let base = match type_ {
                glib::SeekType::Cur => s.position as i64,
                glib::SeekType::End => s.size as i64,
                _ => 0,
            };
            let to = base.checked_add(offset).filter(|to| *to >= 0)
                .ok_or_else(|| glib::Error::new(gio::IOErrorEnum::InvalidArgument, "a seek before the start"))?;
            s.position = to as u64;
            Ok(())
        }

        fn can_truncate(&self) -> bool {
            false
        }

        fn truncate(&self, _offset: i64, _cancellable: Option<&gio::Cancellable>) -> Result<(), glib::Error> {
            Err(glib::Error::new(gio::IOErrorEnum::NotSupported, "read only"))
        }
    }
}

glib::wrapper! {
    /// A medium of [size] bytes read from a [Ranges] source at any offset.
    pub struct ReaderStream(ObjectSubclass<imp::ReaderStream>)
        @extends gio::InputStream,
        @implements gio::Seekable;
}

impl ReaderStream {
    pub fn new(size: u64, source: impl Ranges) -> Self {
        let stream: Self = glib::Object::new();
        {
            let mut s = stream.imp().state.lock().unwrap_or_else(|e| e.into_inner());
            s.size = size;
            s.source = Some(Box::new(source));
        }
        stream
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use gtk::prelude::*;
    use std::sync::Arc;

    /// A played reader: the bytes of a medium, each range asked recorded.
    struct Played {
        bytes: Arc<Vec<u8>>,
        asked: Arc<Mutex<Vec<(u64, usize)>>>,
        closed: Arc<Mutex<bool>>,
    }

    impl Ranges for Played {
        fn read(&self, position: u64, length: usize) -> Result<Vec<u8>, String> {
            self.asked.lock().unwrap().push((position, length));
            let from = (position as usize).min(self.bytes.len());
            let to = (from + length).min(self.bytes.len());
            Ok(self.bytes[from..to].to_vec())
        }
        fn close(&self) {
            *self.closed.lock().unwrap() = true;
        }
    }

    /// Three frames and a bit, each byte telling its frame and offset apart.
    fn medium() -> Vec<u8> {
        (0..3 * WINDOW + 777).map(|i| ((i * 7 + i / WINDOW) % 251) as u8).collect()
    }

    type Asked = Arc<Mutex<Vec<(u64, usize)>>>;

    fn played(bytes: &Arc<Vec<u8>>) -> (ReaderStream, Asked, Arc<Mutex<bool>>) {
        let (asked, closed) = (Arc::new(Mutex::new(Vec::new())), Arc::new(Mutex::new(false)));
        let stream = ReaderStream::new(bytes.len() as u64, Played { bytes: bytes.clone(), asked: asked.clone(), closed: closed.clone() });
        (stream, asked, closed)
    }

    fn read(stream: &ReaderStream, n: usize) -> Vec<u8> {
        let mut out = vec![0u8; n];
        let got = stream.read(&mut out, gio::Cancellable::NONE).expect("a read");
        out.truncate(got);
        out
    }

    #[test]
    fn small_reads_are_served_from_one_window_and_a_seek_reads_where_it_lands() {
        let bytes = Arc::new(medium());
        let (stream, asked, _) = played(&bytes);
        assert_eq!(read(&stream, 4096), bytes[..4096]);
        assert_eq!(read(&stream, 4096), bytes[4096..8192]);
        assert_eq!(*asked.lock().unwrap(), vec![(0, WINDOW)], "two small reads asked the reader twice");
        // a seek across the frames, from the start, from here and from the end
        stream.seek(2 * WINDOW as i64 + 5, glib::SeekType::Set, gio::Cancellable::NONE).unwrap();
        assert_eq!(stream.tell(), 2 * WINDOW as i64 + 5);
        assert_eq!(read(&stream, 10), bytes[2 * WINDOW + 5..2 * WINDOW + 15]);
        stream.seek(-20, glib::SeekType::Cur, gio::Cancellable::NONE).unwrap();
        assert_eq!(read(&stream, 10), bytes[2 * WINDOW - 5..2 * WINDOW + 5], "a range across a frame's edge");
        stream.seek(-100, glib::SeekType::End, gio::Cancellable::NONE).unwrap();
        assert_eq!(stream.tell() as usize, bytes.len() - 100);
        assert!(stream.seek(-1, glib::SeekType::Set, gio::Cancellable::NONE).is_err());
        assert!(stream.can_seek() && !stream.can_truncate());
    }

    #[test]
    fn a_read_at_the_end_is_short_and_past_it_is_empty() {
        let bytes = Arc::new(medium());
        let (stream, _, _) = played(&bytes);
        stream.seek(-50, glib::SeekType::End, gio::Cancellable::NONE).unwrap();
        assert_eq!(read(&stream, 4096), bytes[bytes.len() - 50..], "the short read at the end");
        assert_eq!(read(&stream, 4096), Vec::<u8>::new());
        stream.seek(10, glib::SeekType::End, gio::Cancellable::NONE).unwrap();
        assert_eq!(read(&stream, 4096), Vec::<u8>::new(), "past the end");
    }

    #[test]
    fn the_whole_medium_read_in_uneven_pieces_is_its_bytes_and_closing_lets_the_reader_go() {
        let bytes = Arc::new(medium());
        let (stream, asked, closed) = played(&bytes);
        let mut all = Vec::new();
        for n in [1, 4095, 300_000, 7, 65_536, WINDOW + 3].iter().cycle() {
            let got = read(&stream, *n);
            if got.is_empty() {
                break;
            }
            all.extend(got);
        }
        assert_eq!(all, *bytes);
        assert!(asked.lock().unwrap().iter().all(|(_, n)| *n >= WINDOW), "a range asked below the window");
        stream.close(gio::Cancellable::NONE).unwrap();
        assert!(*closed.lock().unwrap(), "the reader was not let go");
        let mut buf = [0u8; 4];
        stream.seek(0, glib::SeekType::Set, gio::Cancellable::NONE).unwrap();
        assert!(stream.read(&mut buf, gio::Cancellable::NONE).is_err(), "read after close");
    }
}
