//! A picked photo's or video's tiers, made for the core (ADR 0137's Consequences, the tiers; ADR 0161 decision 1: decoding
//! and encoding are the platform's, the spec — edges, qualities, the poster's moment — the core's, in the request). The
//! core asks on its upload queue's own thread (`Media.Duration`, then `Media.Tiers`), never GTK's, and this answers there:
//! - a photo through gdk-pixbuf, which reads it through glycin's loaders and turns it upright by its EXIF orientation
//!   (`apply_embedded_orientation`; GTK's own `gdk::Texture` loader keeps a JPEG as stored, measured 2026-11-13);
//! - a video's poster through GStreamer: `filesrc ! decodebin ! videoflip(auto) ! videoconvert ! appsink` (RGB), paused,
//!   sought to the core's moment, the frame there;
//! - a picked file's length (`Media.Duration`: a video's for its poster, a music track's for its facts, ADR 0142 decision
//!   7) from the same `playbin` paused at its start — with its video only, else, where that says no length (a track: the
//!   video-only pipeline stands still over a WAV and says none, measured 2026-11-16 by this module's test), with its
//!   sound only into a `fakesink`, nothing heard;
//! - the preview scaled to fit the core's long edge (never enlarged) over white, the thumbnail made from it, both JPEGs
//!   encoded by gdk-pixbuf in memory (`save_to_bufferv`) and handed back as the reply's payload, `preview ‖ thumbnail`
//!   (ADR 0137 decision 3, inward). Nothing is written to disk (ADR 0047).
//!
//! What the platform cannot decode (a HEIC without an HEVC decoder, a codec the system lacks, no file) is answered with
//! no tiers, and the original goes up alone with its icon — the phones' rule.
use std::time::Duration;

use gstreamer as gst;
use gstreamer::prelude::*;
use gstreamer_app as gst_app;
use gtk::gdk_pixbuf::{Colorspace, InterpType, Pixbuf};
use gtk::glib;

use crate::bridge::wire::{HostReply, HostRequest};

/// How long a video's pipeline may take to stand still at a frame before it is given up.
const SETTLE: Duration = Duration::from_secs(20);

/// The core's spec for one medium's tiers (`MediaUpload`'s, in `Media.Tiers`).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Spec {
    pub preview_edge: i32,
    pub preview_quality: f64,
    pub thumbnail_edge: i32,
    pub thumbnail_quality: f64,
    pub poster_at_ms: i64,
}

/// A medium's tiers: its upright size, and the two JPEGs.
#[derive(Debug)]
pub struct Tiers {
    pub width: i32,
    pub height: i32,
    pub preview: Vec<u8>,
    pub thumbnail: Vec<u8>,
}

/// The reply to the core's `Media.Duration`, `Media.Slice` and `Media.Tiers`, with a slice's WAV or the tiers as its
/// payload; None for every other request.
pub fn answer(request: &HostRequest) -> Option<(HostReply, Option<Vec<u8>>)> {
    match request {
        HostRequest::MediaDuration { path } => {
            let ms = duration_ms(path);
            eprintln!("veyq: media duration: {}", ms.map_or_else(|| "unknown".to_owned(), |ms| format!("{ms} ms")));
            Some((HostReply::Value { value: ms.map(|ms| ms.to_string()) }, None))
        }
        HostRequest::MediaSlice { path, from_ms, to_ms } => Some(match audio_slice(path, *from_ms, *to_ms) {
            Ok(wav) => {
                eprintln!("veyq: media slice: {} B", wav.len());
                (HostReply::Wav, Some(wav))
            }
            Err(e) => {
                eprintln!("veyq: media slice: none ({e})");
                (HostReply::Failed { error: "not decoded".into() }, None)
            }
        }),
        HostRequest::MediaTiers { path, media_type, preview_edge, preview_quality, thumbnail_edge, thumbnail_quality, poster_at_ms } => {
            let spec = Spec {
                preview_edge: *preview_edge,
                preview_quality: *preview_quality,
                thumbnail_edge: *thumbnail_edge,
                thumbnail_quality: *thumbnail_quality,
                poster_at_ms: *poster_at_ms,
            };
            Some(match tiers(path, media_type, &spec) {
                Ok(t) => {
                    eprintln!("veyq: media tiers: {}×{}, preview {} B, thumbnail {} B", t.width, t.height, t.preview.len(), t.thumbnail.len());
                    let reply = HostReply::Tiers { width: Some(t.width), height: Some(t.height), preview_size: t.preview.len() as i32 };
                    (reply, Some([t.preview, t.thumbnail].concat()))
                }
                Err(e) => {
                    eprintln!("veyq: media tiers: none ({e})");
                    (HostReply::Tiers { width: None, height: None, preview_size: 0 }, None)
                }
            })
        }
        _ => None,
    }
}

/// The tiers of the file at [path], a photo or a video by the core's [media_type].
pub fn tiers(path: &str, media_type: &str, spec: &Spec) -> Result<Tiers, String> {
    let upright = if media_type.starts_with("video/") { poster(path, spec.poster_at_ms)? } else { photo(path)? };
    let (width, height) = (upright.width(), upright.height());
    let preview = fitted(&upright, spec.preview_edge)?;
    drop(upright);
    let thumbnail = fitted(&preview, spec.thumbnail_edge)?;
    Ok(Tiers { width, height, preview: jpeg(&preview, spec.preview_quality)?, thumbnail: jpeg(&thumbnail, spec.thumbnail_quality)? })
}

/// The photo at [path], decoded and turned upright by its EXIF orientation.
fn photo(path: &str) -> Result<Pixbuf, String> {
    let stored = Pixbuf::from_file(path).map_err(|e| format!("the photo did not decode: {e}"))?;
    Ok(stored.apply_embedded_orientation().unwrap_or(stored))
}

/// The size [width] × [height] fits at with its long edge at most [edge], never enlarged (the web's `mediaDeriver.ts`).
pub fn fit(width: i32, height: i32, edge: i32) -> (i32, i32) {
    let longest = width.max(height);
    if longest <= edge || longest <= 0 || edge <= 0 {
        return (width.max(1), height.max(1));
    }
    let scale = f64::from(edge) / f64::from(longest);
    (((f64::from(width) * scale).round() as i32).max(1), ((f64::from(height) * scale).round() as i32).max(1))
}

/// [source] scaled to fit [edge], drawn over white without alpha (a JPEG has none).
fn fitted(source: &Pixbuf, edge: i32) -> Result<Pixbuf, String> {
    let (w, h) = fit(source.width(), source.height(), edge);
    let out = Pixbuf::new(Colorspace::Rgb, false, 8, w, h).ok_or("no memory for the tier")?;
    out.fill(0xffff_ffff);
    let (sx, sy) = (f64::from(w) / f64::from(source.width()), f64::from(h) / f64::from(source.height()));
    source.composite(&out, 0, 0, w, h, 0.0, 0.0, sx, sy, InterpType::Hyper, 255);
    Ok(out)
}

/// [pixbuf] as a JPEG at [quality] (0–1), in memory.
fn jpeg(pixbuf: &Pixbuf, quality: f64) -> Result<Vec<u8>, String> {
    let percent = ((quality * 100.0).round() as i32).clamp(1, 100).to_string();
    pixbuf.save_to_bufferv("jpeg", &[("quality", percent.as_str())]).map_err(|e| format!("the JPEG did not encode: {e}"))
}

/// The video at [path], its picture decoded to RGB and turned upright by its orientation tag, paused at its first frame:
/// GStreamer's `playbin` with its video only (its sound neither decoded nor played) into an `appsink`.
fn video(path: &str) -> Result<(gst::Pipeline, gst_app::AppSink), String> {
    gst::init().map_err(|e| format!("GStreamer did not start: {e}"))?;
    let uri = glib::filename_to_uri(path, None).map_err(|e| format!("no URI for the file: {e}"))?;
    let frames = gst::parse::bin_from_description(
        "videoflip video-direction=auto ! videoconvert ! video/x-raw,format=RGB ! appsink name=frames sync=false max-buffers=1",
        true,
    )
    .map_err(|e| format!("no sink: {e}"))?;
    let sink = frames.by_name("frames").and_then(|e| e.downcast::<gst_app::AppSink>().ok()).ok_or("no sink")?;
    let pipeline = gst::ElementFactory::make("playbin")
        .property("uri", uri.as_str())
        .property("video-sink", &frames)
        .build()
        .map_err(|e| format!("no playbin: {e}"))?
        .downcast::<gst::Pipeline>()
        .map_err(|_| "no pipeline")?;
    pipeline.set_property_from_str("flags", "video");
    if pipeline.set_state(gst::State::Paused).is_err() {
        let _ = pipeline.set_state(gst::State::Null);
        return Err("the video did not open".into());
    }
    if let Err(e) = settle(&pipeline) {
        let _ = pipeline.set_state(gst::State::Null);
        return Err(e);
    }
    Ok((pipeline, sink))
}

/// Waits until [pipeline] stands still (its state reached, or a seek done), or says why not.
fn settle(pipeline: &gst::Pipeline) -> Result<(), String> {
    let bus = pipeline.bus().ok_or("no bus")?;
    let (result, _, _) = pipeline.state(gst::ClockTime::from_seconds(SETTLE.as_secs()));
    if result.is_err() {
        let why = bus.pop_filtered(&[gst::MessageType::Error]).map(|m| match m.view() {
            gst::MessageView::Error(e) => e.error().to_string(),
            _ => String::new(),
        });
        return Err(format!("the video did not decode: {}", why.unwrap_or_default()));
    }
    Ok(())
}

/// The length of the video or the audio at [path] in milliseconds; None where it does not open or says none.
pub fn duration_ms(path: &str) -> Option<i64> {
    video_duration_ms(path).or_else(|| audio_duration_ms(path))
}

/// The length of the video at [path]; None where it does not open or says none (a file without a picture).
fn video_duration_ms(path: &str) -> Option<i64> {
    let (pipeline, _sink) = video(path).ok()?;
    let ms = pipeline.query_duration::<gst::ClockTime>().map(|d| d.mseconds() as i64);
    let _ = pipeline.set_state(gst::State::Null);
    ms
}

/// The length of the audio at [path]: `playbin` with its sound only, into a `fakesink`, paused at its start.
fn audio_duration_ms(path: &str) -> Option<i64> {
    gst::init().ok()?;
    let uri = glib::filename_to_uri(path, None).ok()?;
    let sink = gst::ElementFactory::make("fakesink").build().ok()?;
    let pipeline = gst::ElementFactory::make("playbin")
        .property("uri", uri.as_str())
        .property("audio-sink", &sink)
        .build()
        .ok()?
        .downcast::<gst::Pipeline>()
        .ok()?;
    pipeline.set_property_from_str("flags", "audio");
    let ms = (pipeline.set_state(gst::State::Paused).is_ok() && settle(&pipeline).is_ok())
        .then(|| pipeline.query_duration::<gst::ClockTime>().map(|d| d.mseconds() as i64))
        .flatten();
    let _ = pipeline.set_state(gst::State::Null);
    ms
}

/// The frame of the video at [path] at [at_ms], upright.
fn poster(path: &str, at_ms: i64) -> Result<Pixbuf, String> {
    let (pipeline, sink) = video(path)?;
    let frame = (|| {
        if at_ms > 0 {
            pipeline
                .seek_simple(gst::SeekFlags::FLUSH | gst::SeekFlags::ACCURATE, gst::ClockTime::from_mseconds(at_ms as u64))
                .map_err(|e| format!("the video did not seek: {e}"))?;
            settle(&pipeline)?;
        }
        let sample = sink.try_pull_preroll(gst::ClockTime::from_seconds(SETTLE.as_secs())).ok_or("no frame")?;
        let caps = sample.caps().ok_or("a frame without caps")?;
        let info = gstreamer_video::VideoInfo::from_caps(caps).map_err(|e| format!("a frame without a size: {e}"))?;
        let buffer = sample.buffer().ok_or("a frame without pixels")?;
        let map = buffer.map_readable().map_err(|e| format!("the frame did not read: {e}"))?;
        let (w, h, stride) = (info.width() as i32, info.height() as i32, info.stride()[0]);
        let bytes = glib::Bytes::from(map.as_slice());
        Ok::<_, String>(Pixbuf::from_bytes(&bytes, Colorspace::Rgb, false, 8, w, h, stride))
    })();
    let _ = pipeline.set_state(gst::State::Null);
    frame
}

/// No slice is read past this many PCM bytes: 30 s of 8 channels at 192 kHz in 16 bit is 88 MiB; a cap, not a size.
const SLICE_CAP: usize = 96 * 1024 * 1024;

/// The audio of the file at [path] from [from_ms] up to [to_ms] (None: its end), decoded by GStreamer to 16-bit PCM and
/// wrapped as a WAV for the core's silence scan (inbox #932): `playbin` with its sound only into an `appsink` that does not
/// wait for the clock, seeked to the slice — only the slice is decoded, never the whole file.
pub fn audio_slice(path: &str, from_ms: i64, to_ms: Option<i64>) -> Result<Vec<u8>, String> {
    gst::init().map_err(|e| format!("GStreamer did not start: {e}"))?;
    let uri = glib::filename_to_uri(path, None).map_err(|e| format!("no URI for the file: {e}"))?;
    let pcm = gst::parse::bin_from_description(
        "audioconvert ! audio/x-raw,format=S16LE,layout=interleaved ! appsink name=pcm sync=false",
        true,
    )
    .map_err(|e| format!("no sink: {e}"))?;
    let sink = pcm.by_name("pcm").and_then(|e| e.downcast::<gst_app::AppSink>().ok()).ok_or("no sink")?;
    let pipeline = gst::ElementFactory::make("playbin")
        .property("uri", uri.as_str())
        .property("audio-sink", &pcm)
        .build()
        .map_err(|e| format!("no playbin: {e}"))?
        .downcast::<gst::Pipeline>()
        .map_err(|_| "no pipeline")?;
    pipeline.set_property_from_str("flags", "audio");
    let result = (|| {
        pipeline.set_state(gst::State::Paused).map_err(|_| "the audio did not open")?;
        settle(&pipeline)?;
        let from = gst::ClockTime::from_mseconds(from_ms.max(0) as u64);
        let (stop_type, stop) = match to_ms {
            Some(ms) => (gst::SeekType::Set, gst::ClockTime::from_mseconds(ms.max(0) as u64)),
            None => (gst::SeekType::None, gst::ClockTime::ZERO),
        };
        pipeline
            .seek(1.0, gst::SeekFlags::FLUSH | gst::SeekFlags::ACCURATE, gst::SeekType::Set, from, stop_type, stop)
            .map_err(|_| "the slice did not seek")?;
        settle(&pipeline)?;
        pipeline.set_state(gst::State::Playing).map_err(|_| "the audio did not play")?;
        let (mut rate, mut channels) = (0i32, 0i32);
        let mut bytes: Vec<u8> = Vec::new();
        while let Some(sample) = sink.try_pull_sample(gst::ClockTime::from_seconds(SETTLE.as_secs())) {
            if rate == 0 {
                let s = sample.caps().and_then(|c| c.structure(0)).ok_or("no caps")?;
                rate = s.get::<i32>("rate").map_err(|_| "no rate")?;
                channels = s.get::<i32>("channels").map_err(|_| "no channels")?;
            }
            let buffer = sample.buffer().ok_or("no buffer")?;
            let map = buffer.map_readable().map_err(|_| "an unreadable buffer")?;
            bytes.extend_from_slice(map.as_slice());
            if bytes.len() > SLICE_CAP {
                return Err("the slice is too large".to_owned());
            }
        }
        if rate <= 0 || channels <= 0 {
            return Err("no audio decoded".to_owned());
        }
        Ok(wav(&bytes, channels as u16, rate as u32))
    })();
    let _ = pipeline.set_state(gst::State::Null);
    result
}

/// Interleaved 16-bit PCM wrapped as a WAV file (the core's `PcmWav`, for a decoded slice).
pub fn wav(pcm: &[u8], channels: u16, rate: u32) -> Vec<u8> {
    let block = u32::from(channels) * 2;
    let data = (pcm.len() as u32) - (pcm.len() as u32) % block;
    let mut out = Vec::with_capacity(44 + data as usize);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&(36 + data).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&channels.to_le_bytes());
    out.extend_from_slice(&rate.to_le_bytes());
    out.extend_from_slice(&(rate * block).to_le_bytes());
    out.extend_from_slice(&(block as u16).to_le_bytes());
    out.extend_from_slice(&16u16.to_le_bytes());
    out.extend_from_slice(b"data");
    out.extend_from_slice(&data.to_le_bytes());
    out.extend_from_slice(&pcm[..data as usize]);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use gtk::gdk_pixbuf::prelude::*;

    /// The core's spec (`MediaUpload`), as the request brings it.
    const SPEC: Spec = Spec { preview_edge: 2048, preview_quality: 0.85, thumbnail_edge: 512, thumbnail_quality: 0.8, poster_at_ms: 100 };

    /// A file made for one test in the temporary directory, removed with it.
    struct Made(std::path::PathBuf);

    impl Drop for Made {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
        }
    }

    impl Made {
        fn path(&self) -> &str {
            self.0.to_str().unwrap()
        }
    }

    fn made(name: &str, bytes: &[u8]) -> Made {
        let path = std::env::temp_dir().join(format!("veyq-tiers-{}-{name}", std::process::id()));
        std::fs::write(&path, bytes).unwrap();
        Made(path)
    }

    /// A JPEG [w] × [h], its left half red and its right half blue, carrying EXIF [orientation] where given.
    fn halves(w: i32, h: i32, orientation: Option<u16>) -> Vec<u8> {
        let p = Pixbuf::new(Colorspace::Rgb, false, 8, w, h).unwrap();
        p.fill(0x0000_ffff);
        p.new_subpixbuf(0, 0, w / 2, h).fill(0xff00_00ff);
        let plain = p.save_to_bufferv("jpeg", &[("quality", "95")]).unwrap();
        let Some(o) = orientation else { return plain };
        // an APP1 segment after SOI: Exif, a little-endian TIFF header and one IFD entry, 0x0112 (orientation) SHORT
        let mut tiff = b"II*\0".to_vec();
        tiff.extend_from_slice(&8u32.to_le_bytes());
        tiff.extend_from_slice(&1u16.to_le_bytes());
        tiff.extend_from_slice(&0x0112u16.to_le_bytes());
        tiff.extend_from_slice(&3u16.to_le_bytes());
        tiff.extend_from_slice(&1u32.to_le_bytes());
        tiff.extend_from_slice(&o.to_le_bytes());
        tiff.extend_from_slice(&[0, 0, 0, 0, 0, 0]);
        let mut app1 = b"Exif\0\0".to_vec();
        app1.extend_from_slice(&tiff);
        let mut out = plain[..2].to_vec();
        out.extend_from_slice(&[0xff, 0xe1]);
        out.extend_from_slice(&((app1.len() + 2) as u16).to_be_bytes());
        out.extend_from_slice(&app1);
        out.extend_from_slice(&plain[2..]);
        out
    }

    fn decoded(jpeg: &[u8]) -> Pixbuf {
        let loader = gtk::gdk_pixbuf::PixbufLoader::new();
        loader.write(jpeg).unwrap();
        loader.close().unwrap();
        loader.pixbuf().unwrap()
    }

    /// The colour at (x, y): (r, g, b).
    fn at(p: &Pixbuf, x: i32, y: i32) -> (u8, u8, u8) {
        let bytes = p.read_pixel_bytes();
        let i = (y * p.rowstride() + x * p.n_channels()) as usize;
        (bytes[i], bytes[i + 1], bytes[i + 2])
    }

    fn reddish((r, g, b): (u8, u8, u8)) -> bool {
        r > 180 && g < 80 && b < 80
    }

    fn bluish((r, g, b): (u8, u8, u8)) -> bool {
        b > 180 && r < 80 && g < 80
    }

    #[test]
    fn a_size_fits_its_long_edge_and_is_never_enlarged() {
        assert_eq!(fit(4000, 3000, 2048), (2048, 1536));
        assert_eq!(fit(3000, 4000, 512), (384, 512));
        assert_eq!(fit(800, 600, 2048), (800, 600), "never enlarged");
        assert_eq!(fit(2048, 10, 512), (512, 3));
    }

    /// The photo comes upright by its EXIF orientation (6: turned a quarter clockwise to be seen), at the core's edges, as
    /// two JPEGs; the size said is the upright one.
    #[test]
    fn a_photo_is_turned_by_its_exif_and_fitted_to_the_cores_edges() {
        let file = made("turned.jpg", &halves(3000, 1000, Some(6)));
        let t = tiers(file.path(), "image/jpeg", &SPEC).expect("a JPEG decodes");
        assert_eq!((t.width, t.height), (1000, 3000), "the upright size");
        let preview = decoded(&t.preview);
        assert_eq!((preview.width(), preview.height()), (683, 2048), "the preview at the long edge");
        // stored with red on its left, shown turned clockwise: red on top
        assert!(reddish(at(&preview, 340, 100)) && bluish(at(&preview, 340, 1948)), "turned by its EXIF");
        let thumbnail = decoded(&t.thumbnail);
        assert_eq!((thumbnail.width(), thumbnail.height()), (171, 512));
        assert!(reddish(at(&thumbnail, 85, 30)) && bluish(at(&thumbnail, 85, 480)));
        assert_eq!(&t.preview[..2], &[0xff, 0xd8], "a JPEG");

        let plain = made("plain.jpg", &halves(800, 600, None));
        let t = tiers(plain.path(), "image/jpeg", &SPEC).unwrap();
        let preview = decoded(&t.preview);
        assert_eq!((preview.width(), preview.height()), (800, 600), "a small photo is not enlarged");
        assert!(reddish(at(&preview, 100, 300)) && bluish(at(&preview, 700, 300)), "unturned without EXIF");
    }

    /// A video's poster is its frame at the core's moment: a clip whose first second is red and second blue, asked at
    /// 1.5 s, is blue; its length is said in milliseconds.
    #[test]
    fn a_videos_poster_is_its_frame_at_the_cores_moment() {
        gst::init().unwrap();
        let path = std::env::temp_dir().join(format!("veyq-tiers-{}-clip.webm", std::process::id()));
        let clip = Made(path);
        // red for one second, then blue, 320×240 VP8 in WebM (gst-plugins-good)
        let launch = format!(
            "videotestsrc pattern=red num-buffers=10 ! video/x-raw,width=320,height=240,framerate=10/1 ! c.sink_0 \
             videotestsrc pattern=blue num-buffers=10 ! video/x-raw,width=320,height=240,framerate=10/1 ! c.sink_1 \
             concat name=c ! videoconvert ! vp8enc deadline=1 ! webmmux ! filesink location={}",
            clip.path()
        );
        let make = gst::parse::launch(&launch).unwrap();
        make.set_state(gst::State::Playing).unwrap();
        let bus = make.bus().unwrap();
        let end = bus.timed_pop_filtered(gst::ClockTime::from_seconds(60), &[gst::MessageType::Eos, gst::MessageType::Error]).unwrap();
        assert!(matches!(end.view(), gst::MessageView::Eos(_)), "the clip was made: {end:?}");
        make.set_state(gst::State::Null).unwrap();

        let ms = duration_ms(clip.path()).expect("its length");
        assert!((1900..=2100).contains(&ms), "{ms} ms");
        let blue = tiers(clip.path(), "video/webm", &Spec { poster_at_ms: 1500, ..SPEC }).expect("a poster");
        assert_eq!((blue.width, blue.height), (320, 240));
        let poster = decoded(&blue.preview);
        assert!(bluish(at(&poster, 160, 120)), "the frame at 1.5 s: {:?}", at(&poster, 160, 120));
        let red = tiers(clip.path(), "video/webm", &SPEC).unwrap();
        assert!(reddish(at(&decoded(&red.thumbnail), 160, 120)), "the frame at 0.1 s");
    }

    /// A music track's length (ADR 0142 decision 7): a file with sound and no picture is measured by its sound — the
    /// video-only pipeline says no length for it (what `Media.Duration` answered for a track before).
    #[test]
    fn a_tracks_length_is_read_from_its_sound() {
        // 1.5 s of 16-bit mono PCM at 8 kHz, silent: a WAV's header and its samples
        let samples = 12_000u32;
        let mut wav = Vec::new();
        wav.extend_from_slice(b"RIFF");
        wav.extend_from_slice(&(36 + samples * 2).to_le_bytes());
        wav.extend_from_slice(b"WAVEfmt ");
        wav.extend_from_slice(&16u32.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes());
        wav.extend_from_slice(&8_000u32.to_le_bytes());
        wav.extend_from_slice(&16_000u32.to_le_bytes());
        wav.extend_from_slice(&2u16.to_le_bytes());
        wav.extend_from_slice(&16u16.to_le_bytes());
        wav.extend_from_slice(b"data");
        wav.extend_from_slice(&(samples * 2).to_le_bytes());
        wav.resize(wav.len() + samples as usize * 2, 0);
        let track = made("track.wav", &wav);
        assert_eq!(video_duration_ms(track.path()), None, "no picture, no length from the video's pipeline");
        assert_eq!(duration_ms(track.path()), Some(1500));
    }

    /// What the platform does not decode has no tiers, so the original goes up alone.
    #[test]
    fn a_file_that_does_not_decode_has_no_tiers() {
        let junk = made("junk.jpg", b"not a picture at all");
        assert!(tiers(junk.path(), "image/jpeg", &SPEC).is_err());
        assert!(tiers(junk.path(), "video/mp4", &SPEC).is_err());
        assert_eq!(duration_ms(junk.path()), None);
        let request = HostRequest::MediaTiers {
            path: junk.path().into(),
            media_type: "image/jpeg".into(),
            preview_edge: 2048,
            preview_quality: 0.85,
            thumbnail_edge: 512,
            thumbnail_quality: 0.8,
            poster_at_ms: 0,
        };
        assert_eq!(answer(&request), Some((HostReply::Tiers { width: None, height: None, preview_size: 0 }, None)));
        assert_eq!(answer(&HostRequest::StoreRead { name: "x".into() }), None, "the store's requests are the store's");
    }

    /// The reply carries the preview's size and both JPEGs as its payload, the preview first.
    #[test]
    fn the_reply_packs_the_preview_then_the_thumbnail() {
        let file = made("packed.jpg", &halves(1200, 900, None));
        let request = HostRequest::MediaTiers {
            path: file.path().into(),
            media_type: "image/jpeg".into(),
            preview_edge: 2048,
            preview_quality: 0.85,
            thumbnail_edge: 512,
            thumbnail_quality: 0.8,
            poster_at_ms: 0,
        };
        let (reply, payload) = answer(&request).unwrap();
        let HostReply::Tiers { width, height, preview_size } = reply else { panic!("{reply:?}") };
        assert_eq!((width, height), (Some(1200), Some(900)));
        let payload = payload.unwrap();
        let (preview, thumbnail) = payload.split_at(preview_size as usize);
        assert_eq!((decoded(preview).width(), decoded(thumbnail).width()), (1200, 512));
    }

    /// A slice is decoded to its own span only (inbox #932): 3 s of a WAV (silence, a tone, silence), its head and its tail.
    #[test]
    fn a_slice_of_audio_is_decoded_to_its_span_as_a_wav() {
        let rate = 8000u32;
        let samples: Vec<u8> = (0..3 * rate)
            .flat_map(|i| {
                let v: i16 = if (rate..2 * rate).contains(&i) { 16000 } else { 0 };
                v.to_le_bytes()
            })
            .collect();
        let file = made("slice.wav", &wav(&samples, 1, rate));
        let head = audio_slice(file.path(), 0, Some(1500)).expect("the head decodes");
        let tail = audio_slice(file.path(), 2000, None).expect("the tail decodes");
        let seconds = |w: &[u8]| {
            let rate = u32::from_le_bytes(w[24..28].try_into().unwrap());
            let block = u16::from_le_bytes(w[32..34].try_into().unwrap());
            (w.len() - 44) as f64 / f64::from(block) / f64::from(rate)
        };
        assert!((seconds(&head) - 1.5).abs() < 0.1, "the head is {} s", seconds(&head));
        assert!((seconds(&tail) - 1.0).abs() < 0.1, "the tail is {} s", seconds(&tail));
        let junk = made("junk.mp3", b"not audio at all");
        assert!(audio_slice(junk.path(), 0, Some(1000)).is_err());
        let (reply, payload) = answer(&HostRequest::MediaSlice { path: junk.path().into(), from_ms: 0, to_ms: None }).unwrap();
        assert_eq!((reply, payload), (HostReply::Failed { error: "not decoded".into() }, None));
    }
}
