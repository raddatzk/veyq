//! A QR scan's camera (the QR CR's D4 and D5): the core's scan (`Scan.Open`) fed with the camera's luminance plane, a
//! frame at a time as `Scan.Frame`'s inbound payload, its state watched (`Watched::Scan`). The code is read by the core and
//! its text never crosses: this side sees only what the scan's kind made of it ([ScanShown::found]).
//!
//! The camera is the xdg Camera portal (`ashpd`): it says whether there is one (`IsCameraPresent`), asks the person
//! (`AccessCamera`) and hands a PipeWire remote that shows only the cameras (`OpenPipeWireRemote`), which GStreamer's
//! `pipewiresrc` reads. No portal, no camera, or a camera that never delivers is `NONE`; a refused access is `DENIED`.
//! There is no `v4l2src` fallback: a desktop without the portal scans with the other device. A debug build reads
//! `VEYQ_DEV_CAMERA=<image>` as its camera instead (`filesrc` → `imagefreeze`, ten frames a second), so the way from a frame
//! to `FOUND` is measured where no camera is.
//!
//! One pipeline, teed: `GRAY8` to an appsink whose frames go to the core from GStreamer's streaming thread
//! (`bridge::call_blocking_with`: the call's answer is the backpressure, and a frame that arrives meanwhile is dropped by
//! the leaky queue), `RGBx` to an appsink whose frames reach GTK's main loop as a `gdk::MemoryTexture` (the preview).
use std::cell::{Cell, RefCell};
use std::os::fd::{AsRawFd, OwnedFd};
use std::rc::Rc;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

use gstreamer as gst;
use gstreamer_video::prelude::*;
use gstreamer_app as gst_app;
use gstreamer_video as gst_video;
use gtk::gdk;
use gtk::glib;
use gtk::prelude::*;

use crate::bridge::{
    self,
    wire::{Answer, Call, Camera, ScanKind, ScanShown, ScanState, Update, Watched},
};

/// A luminance plane as the core takes it: its size, the bytes a row and the bytes, from the plane's first row on.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Plane {
    pub width: i32,
    pub height: i32,
    pub stride: i32,
    pub bytes: Vec<u8>,
}

/// The first plane of a video [sample] (GRAY8: the only one), copied with the stride GStreamer laid it out at.
pub fn plane(sample: &gst::Sample) -> Option<Plane> {
    let info = gst_video::VideoInfo::from_caps(sample.caps()?).ok()?;
    let buffer = sample.buffer()?;
    let frame = gst_video::VideoFrameRef::from_buffer_ref_readable(buffer, &info).ok()?;
    let stride = *frame.plane_stride().first()?;
    let height = frame.height() as i32;
    let width = frame.width() as i32;
    let data = frame.plane_data(0).ok()?;
    // the last row needs only its width: a plane may end there
    let needed = stride as usize * (height as usize - 1) + width as usize;
    Some(Plane { width, height, stride, bytes: data[..needed.min(data.len())].to_vec() })
}

/// The pipeline's source for a debug build's test camera at [path]: the image frozen into ten frames a second. None for a
/// path the description cannot quote.
pub fn test_source(path: &str) -> Option<String> {
    if path.is_empty() || path.contains(['"', '\\', '\n']) {
        return None;
    }
    Some(format!("filesrc location=\"{path}\" ! decodebin ! videoconvert ! imagefreeze ! video/x-raw,framerate=10/1"))
}

/// Everything after the source: the scan's GRAY8 and the preview's RGBx, each behind a leaky queue of one frame.
const TAIL: &str = "videoconvert ! tee name=t \
    t. ! queue leaky=downstream max-size-buffers=1 ! videoconvert ! video/x-raw,format=GRAY8 ! appsink name=scan max-buffers=1 drop=true \
    t. ! queue leaky=downstream max-size-buffers=1 ! videoconvert ! video/x-raw,format=RGBx ! appsink name=preview max-buffers=1 drop=true";

/// A preview frame on its way to GTK's main loop.
struct Shown {
    width: i32,
    height: i32,
    stride: usize,
    bytes: Vec<u8>,
}

/// The running camera: its pipeline, the bus watch, and the portal's PipeWire remote, held as long as the pipeline reads it.
struct Running {
    pipeline: gst::Pipeline,
    _bus: gst::bus::BusWatchGuard,
    _remote: Option<OwnedFd>,
}

impl Drop for Running {
    fn drop(&mut self) {
        let _ = self.pipeline.set_state(gst::State::Null);
    }
}

/// A scan's view: the preview while the scan starts or scans, the caller's widget for every other state.
pub struct ScanView {
    pub widget: gtk::Stack,
    picture: gtk::Picture,
    other: gtk::Box,
    scan: Cell<Option<i64>>,
    running: RefCell<Option<Running>>,
    /// Set once the scan is closed: the streaming thread sends nothing after it.
    closed: Arc<AtomicBool>,
    watch: RefCell<Option<bridge::Watch>>,
}

impl ScanView {
    /// Opens a scan of [kind] and its camera. [other] draws every state but the preview's (the caller's words: what to do
    /// without a camera, what was found); [changed] hears each state.
    pub fn open(kind: ScanKind, other: impl Fn(&ScanShown) -> Option<gtk::Widget> + 'static, changed: impl Fn(&ScanShown) + 'static) -> Rc<ScanView> {
        let picture = gtk::Picture::builder().content_fit(gtk::ContentFit::Contain).hexpand(true).vexpand(true).build();
        let other_box = gtk::Box::builder().orientation(gtk::Orientation::Vertical).hexpand(true).vexpand(true).valign(gtk::Align::Fill).build();
        let widget = gtk::Stack::new();
        widget.add_named(&picture, Some("preview"));
        widget.add_named(&other_box, Some("other"));
        let view = Rc::new(ScanView {
            widget,
            picture,
            other: other_box,
            scan: Cell::new(None),
            running: RefCell::new(None),
            closed: Arc::new(AtomicBool::new(false)),
            watch: RefCell::new(None),
        });
        let v = view.clone();
        glib::spawn_future_local(async move {
            let scan = match bridge::call(&Call::ScanOpen { kind }).await {
                Ok((Answer::ScanOpened { scan }, _)) => scan,
                other => {
                    eprintln!("veyq: Scan.Open answered {other:?}");
                    return;
                }
            };
            if v.closed.load(Ordering::SeqCst) {
                drop(bridge::call(&Call::ScanClose { scan }));
                return;
            }
            v.scan.set(Some(scan));
            let Some((watch, updates)) = bridge::watch(&Watched::Scan { scan }) else { return };
            v.watch.replace(Some(watch));
            let weak = Rc::downgrade(&v);
            glib::spawn_future_local(async move {
                while let Ok(update) = updates.recv().await {
                    let Some(v) = weak.upgrade() else { return };
                    match update {
                        Ok(Update::Scan { shown: Some(shown), .. }) => {
                            v.show(&shown, &other);
                            changed(&shown);
                        }
                        Ok(_) => {}
                        Err(e) => eprintln!("veyq: a scan update did not read: {:?}", e.0),
                    }
                }
            });
            let camera = v.start(scan).await;
            if let Err(camera) = camera {
                drop(bridge::call(&Call::ScanCamera { scan, camera }));
            }
        });
        view
    }

    fn show(&self, shown: &ScanShown, other: &impl Fn(&ScanShown) -> Option<gtk::Widget>) {
        match shown.state {
            ScanState::Starting | ScanState::Scanning => self.widget.set_visible_child_name("preview"),
            _ => {
                // nothing more to read: the camera stops at once (the light goes off)
                self.running.replace(None);
                while let Some(child) = self.other.first_child() {
                    self.other.remove(&child);
                }
                if let Some(w) = other(shown) {
                    self.other.append(&w);
                }
                self.widget.set_visible_child_name("other");
            }
        }
    }

    /// Starts the camera for [scan]: Ok once its pipeline plays (`RUNNING` follows with its first frame), else what to say.
    async fn start(self: &Rc<Self>, scan: i64) -> Result<(), Camera> {
        gst::init().map_err(|e| {
            eprintln!("veyq: GStreamer did not start: {e}");
            Camera::None
        })?;
        let (source, remote) = match dev_camera() {
            Some(source) => (source, None),
            None => {
                let fd = portal_camera().await?;
                (format!("pipewiresrc fd={} keepalive-time=1000", fd.as_raw_fd()), Some(fd))
            }
        };
        let pipeline = gst::parse::launch(&format!("{source} ! {TAIL}"))
            .ok()
            .and_then(|e| e.downcast::<gst::Pipeline>().ok())
            .ok_or_else(|| {
                eprintln!("veyq: the camera's pipeline did not build (a GStreamer plugin missing?)");
                Camera::None
            })?;
        let sink = |name: &str| pipeline.by_name(name).and_then(|e| e.downcast::<gst_app::AppSink>().ok());
        let (Some(scan_sink), Some(preview_sink)) = (sink("scan"), sink("preview")) else { return Err(Camera::None) };

        let delivered = Arc::new(AtomicBool::new(false));
        let frames = Arc::new(AtomicU64::new(0));
        let (closed, first) = (self.closed.clone(), delivered.clone());
        scan_sink.set_callbacks(
            gst_app::AppSinkCallbacks::builder()
                .new_sample(move |s| {
                    let sample = s.pull_sample().map_err(|_| gst::FlowError::Eos)?;
                    if closed.load(Ordering::SeqCst) {
                        return Err(gst::FlowError::Flushing);
                    }
                    let Some(p) = plane(&sample) else { return Ok(gst::FlowSuccess::Ok) };
                    // the camera runs once a frame is there to read: the scan reads none before it
                    if !first.swap(true, Ordering::SeqCst) {
                        let _ = bridge::call_blocking(&Call::ScanCamera { scan, camera: Camera::Running });
                        eprintln!("veyq-scan: camera running, {}x{} at a stride of {}", p.width, p.height, p.stride);
                    }
                    let frame = Call::ScanFrame { scan, width: p.width, height: p.height, stride: p.stride };
                    match bridge::call_blocking_with(&frame, Some(&p.bytes)) {
                        Ok((Answer::FrameTaken { taken }, _)) => {
                            if taken && frames.fetch_add(1, Ordering::Relaxed).is_multiple_of(50) {
                                eprintln!("veyq-scan: {} frames taken", frames.load(Ordering::Relaxed));
                            }
                        }
                        other => eprintln!("veyq-scan: Scan.Frame answered {other:?}"),
                    }
                    Ok(gst::FlowSuccess::Ok)
                })
                .build(),
        );
        let (tx, rx) = async_channel::bounded::<Shown>(1);
        preview_sink.set_callbacks(
            gst_app::AppSinkCallbacks::builder()
                .new_sample(move |s| {
                    let sample = s.pull_sample().map_err(|_| gst::FlowError::Eos)?;
                    if let Some(p) = plane(&sample) {
                        // a frame the main loop has not drawn yet is replaced by none: the next one comes
                        let _ = tx.try_send(Shown { width: p.width, height: p.height, stride: p.stride as usize, bytes: p.bytes });
                    }
                    Ok(gst::FlowSuccess::Ok)
                })
                .build(),
        );
        let picture = self.picture.downgrade();
        glib::spawn_future_local(async move {
            while let Ok(shown) = rx.recv().await {
                let Some(picture) = picture.upgrade() else { return };
                // a plane cut after its last row's pixels: padded to whole rows for the texture
                let mut bytes = shown.bytes;
                bytes.resize(shown.stride * shown.height as usize, 0);
                let texture = gdk::MemoryTexture::new(shown.width, shown.height, gdk::MemoryFormat::R8g8b8x8, &glib::Bytes::from_owned(bytes), shown.stride);
                picture.set_paintable(Some(&texture));
            }
        });

        let bus = pipeline.bus().ok_or(Camera::None)?;
        let weak = Rc::downgrade(self);
        let seen = delivered.clone();
        let guard = bus
            .add_watch_local(move |_, message| {
                if let gst::MessageView::Error(e) = message.view() {
                    eprintln!("veyq: the camera failed: {}", e.error());
                    if let Some(v) = weak.upgrade() {
                        v.running.replace(None);
                        // a camera that never delivered a frame is none; one that stopped says so on stderr alone
                        if !seen.load(Ordering::SeqCst) {
                            drop(bridge::call(&Call::ScanCamera { scan, camera: Camera::None }));
                        }
                    }
                }
                glib::ControlFlow::Continue
            })
            .map_err(|_| Camera::None)?;
        if pipeline.set_state(gst::State::Playing).is_err() {
            eprintln!("veyq: the camera's pipeline did not play");
            return Err(Camera::None);
        }
        if self.closed.load(Ordering::SeqCst) {
            let _ = pipeline.set_state(gst::State::Null);
            return Ok(());
        }
        self.running.replace(Some(Running { pipeline, _bus: guard, _remote: remote }));
        Ok(())
    }

    /// Ends the scan and its camera; the view shows nothing more.
    pub fn close(&self) {
        if self.closed.swap(true, Ordering::SeqCst) {
            return;
        }
        self.running.replace(None);
        self.watch.replace(None);
        if let Some(scan) = self.scan.get() {
            drop(bridge::call(&Call::ScanClose { scan }));
        }
    }
}

impl Drop for ScanView {
    fn drop(&mut self) {
        self.close();
    }
}

/// The Camera portal's PipeWire remote, or what the scan says instead: `NONE` without a portal or a camera, `DENIED` when
/// the access was refused.
async fn portal_camera() -> Result<OwnedFd, Camera> {
    use ashpd::desktop::camera::Camera as Portal;
    let portal = Portal::new().await.map_err(|e| {
        eprintln!("veyq-scan: no Camera portal ({e})");
        Camera::None
    })?;
    if !portal.is_present().await.unwrap_or(false) {
        eprintln!("veyq-scan: the Camera portal names no camera");
        return Err(Camera::None);
    }
    let request = portal.request_access(Default::default()).await.map_err(|e| {
        eprintln!("veyq-scan: the camera's access was not asked ({e})");
        Camera::None
    })?;
    if let Err(e) = request.response() {
        eprintln!("veyq-scan: the camera's access was refused ({e})");
        return Err(Camera::Denied);
    }
    portal.open_pipe_wire_remote(Default::default()).await.map_err(|e| {
        eprintln!("veyq-scan: the camera's PipeWire remote did not open ({e})");
        Camera::None
    })
}

/// A debug build's test camera, `VEYQ_DEV_CAMERA=<image>`; None in a release build or without it.
fn dev_camera() -> Option<String> {
    #[cfg(debug_assertions)]
    {
        let path = std::env::var("VEYQ_DEV_CAMERA").ok().filter(|p| !p.is_empty())?;
        let source = test_source(&path);
        match &source {
            Some(_) => eprintln!("veyq-scan: the test camera reads {path} (debug build)"),
            None => eprintln!("veyq-scan: VEYQ_DEV_CAMERA names a path the pipeline cannot quote"),
        }
        source
    }
    #[cfg(not(debug_assertions))]
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(description: &str) -> gst::Sample {
        gst::init().expect("GStreamer starts");
        let pipeline = gst::parse::launch(description).unwrap().downcast::<gst::Pipeline>().unwrap();
        let sink = pipeline.by_name("out").unwrap().downcast::<gst_app::AppSink>().unwrap();
        pipeline.set_state(gst::State::Playing).unwrap();
        let sample = sink.pull_sample().expect("a frame");
        pipeline.set_state(gst::State::Null).unwrap();
        sample
    }

    #[test]
    fn a_gray8_plane_is_taken_at_its_stride_with_its_last_row_whole() {
        // 101 px a row is laid out at 104 bytes (GStreamer rounds a GRAY8 row to 4): the core reads the stride
        let p = plane(&sample("videotestsrc num-buffers=1 pattern=white ! video/x-raw,format=GRAY8,width=101,height=7 ! appsink name=out")).unwrap();
        assert_eq!((p.width, p.height, p.stride), (101, 7, 104));
        assert_eq!(p.bytes.len(), 104 * 6 + 101);
        for row in 0..7 {
            assert!(p.bytes[row * 104..row * 104 + 101].iter().all(|&b| b > 200), "row {row} is the picture's");
        }
    }

    #[test]
    fn the_test_cameras_path_is_quoted_or_refused() {
        assert_eq!(
            test_source("/tmp/code.png").as_deref(),
            Some("filesrc location=\"/tmp/code.png\" ! decodebin ! videoconvert ! imagefreeze ! video/x-raw,framerate=10/1")
        );
        for bad in ["", "/tmp/a\"b.png", "/tmp/a\\b.png", "/tmp/a\nb.png"] {
            assert_eq!(test_source(bad), None, "{bad:?}");
        }
    }
}
