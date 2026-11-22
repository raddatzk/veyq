//! An Emergency Kit file drawn for the core's read (ADR 0136 decision 1: the platform draws, the core decides; ADR 0137
//! decision 3, a drawn page's pixels riding in beside the host's reply): the core reads the file the open dialog picked by
//! its path, decides what needs drawing (`KitImport`: a kit found in the file's bytes needs none) and asks this host —
//! `Pdf.Pages`, `Pdf.Page`, `Picture.Luma` — on its own thread, answered at once from there. A PDF is drawn by poppler-glib
//! (as a receipt's, [crate::core_ui::pdf_render::render]), opened from the path, a picture decoded by GTK (`gdk::Texture`);
//! each drawing goes back as its luminance, packed row after row. Nothing is written anywhere: the drawing is memory
//! only, and the bytes this side made of it are zeroed once handed over (cairo's own surfaces are let go, not zeroed).
use gtk::{cairo, gdk, glib};
use gtk::prelude::*;

use crate::bridge::wire::{HostReply, HostRequest};

/// A drawing's reply and the luminance beside it, or None for a request that is no drawing (the store's).
pub fn answer(request: &HostRequest) -> Option<(HostReply, Option<Vec<u8>>)> {
    let drawn = match request {
        HostRequest::PdfPages { path } => {
            return Some(match open(path) {
                Ok(pdf) => (HostReply::Value { value: Some(pdf.n_pages().to_string()) }, None),
                Err(why) => failed(why),
            });
        }
        HostRequest::PdfPage { path, page, width } => pdf_page(path, *page, *width),
        HostRequest::PictureLuma { path, long_side } => picture(path, *long_side),
        _ => return None,
    };
    Some(match drawn {
        Ok(luma) => (HostReply::Pixels { width: luma.width, height: luma.height }, Some(luma.bytes)),
        Err(why) => failed(why),
    })
}

fn failed(why: String) -> (HostReply, Option<Vec<u8>>) {
    eprintln!("veyq: a kit file was not drawn: {why}");
    (HostReply::Failed { error: why }, None)
}

/// A drawing as the core takes it: 8 bits a pixel, 0 black, 255 white, [width] × [height] packed.
#[derive(Debug)]
pub struct Luma {
    pub width: i32,
    pub height: i32,
    pub bytes: Vec<u8>,
}

fn open(path: &str) -> Result<poppler::Document, String> {
    let uri = glib::filename_to_uri(path, None).map_err(|e| format!("no URI for the path ({e})"))?;
    poppler::Document::from_file(&uri, None).map_err(|e| format!("the PDF did not open ({e})"))
}

/// Page [page] (1-based) of the PDF at [path], [width] px wide on white.
pub fn pdf_page(path: &str, page: i32, width: i32) -> Result<Luma, String> {
    if width <= 0 {
        return Err("no width".into());
    }
    let pdf = open(path)?;
    let p = pdf.page(page - 1).ok_or_else(|| format!("no page {page}"))?;
    let mut drawn = crate::core_ui::pdf_render::render(&p, width).ok_or("the page did not draw")?;
    let luma = luminance(&drawn.data, drawn.width, drawn.height, drawn.stride);
    drawn.data.fill(0);
    Ok(luma)
}

/// The picture at [path], scaled so its longer side is [long_side] px, over white.
pub fn picture(path: &str, long_side: i32) -> Result<Luma, String> {
    if long_side <= 0 {
        return Err("no size".into());
    }
    let texture = gdk::Texture::from_filename(path).map_err(|e| format!("the picture did not decode ({e})"))?;
    let (tw, th) = (texture.width(), texture.height());
    if tw <= 0 || th <= 0 {
        return Err("an empty picture".into());
    }
    let scale = f64::from(long_side) / f64::from(tw.max(th));
    let (w, h) = (((f64::from(tw) * scale).round() as i32).max(1), ((f64::from(th) * scale).round() as i32).max(1));
    let mut downloader = gdk::TextureDownloader::new(&texture);
    downloader.set_format(gdk::MemoryFormat::B8g8r8a8Premultiplied);
    let (bytes, stride) = downloader.download_bytes();
    let mut surface = {
        let whole = cairo::ImageSurface::create_for_data(bytes.to_vec(), cairo::Format::ARgb32, tw, th, stride as i32)
            .map_err(|e| e.to_string())?;
        let out = cairo::ImageSurface::create(cairo::Format::ARgb32, w, h).map_err(|e| e.to_string())?;
        {
            let cr = cairo::Context::new(&out).map_err(|e| e.to_string())?;
            cr.set_source_rgb(1.0, 1.0, 1.0);
            cr.paint().map_err(|e| e.to_string())?;
            cr.scale(f64::from(w) / f64::from(tw), f64::from(h) / f64::from(th));
            cr.set_source_surface(&whole, 0.0, 0.0).map_err(|e| e.to_string())?;
            cr.source().set_filter(cairo::Filter::Best);
            cr.paint().map_err(|e| e.to_string())?;
        }
        out.flush();
        out
    };
    let stride = surface.stride() as usize;
    let data = surface.data().map_err(|e| e.to_string())?;
    Ok(luminance(&data, w, h, stride))
}

/// cairo's ARGB32 rows (little-endian B, G, R, A, drawn on white so opaque) as packed luminance (BT.601, as the phones take
/// it).
pub fn luminance(bgra: &[u8], width: i32, height: i32, stride: usize) -> Luma {
    let (w, h) = (width as usize, height as usize);
    let mut bytes = Vec::with_capacity(w * h);
    for y in 0..h {
        let row = &bgra[y * stride..y * stride + w * 4];
        bytes.extend(row.as_chunks::<4>().0.iter().map(|p| ((u32::from(p[2]) * 299 + u32::from(p[1]) * 587 + u32::from(p[0]) * 114 + 500) / 1000) as u8));
    }
    Luma { width, height, bytes }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bridge::{self, DeviceStore, Start, wire};

    fn fixture(name: &str) -> String {
        let dir = std::env::var("VEYQ_KIT_FIXTURES").expect("run.py sets VEYQ_KIT_FIXTURES (the web's kit fixtures)");
        format!("{dir}/{name}")
    }

    #[test]
    fn luminance_reads_cairos_rows_at_their_stride() {
        // two pixels a row at a stride of 12: black, white, then the padding the stride leaves
        let rows = [0, 0, 0, 255, 255, 255, 255, 255, 9, 9, 9, 9, 0, 0, 255, 255, 255, 0, 0, 255, 9, 9, 9, 9];
        let l = luminance(&rows, 2, 2, 12);
        assert_eq!((l.width, l.height), (2, 2));
        // B, G, R: black, white; then pure red (R 255 → 76) and pure blue (B 255 → 29)
        assert_eq!(l.bytes, vec![0, 255, 76, 29]);
    }

    #[test]
    fn a_path_that_is_no_pdf_or_no_page_is_failed() {
        assert!(matches!(answer(&HostRequest::PdfPages { path: "/no/such/kit.pdf".into() }), Some((HostReply::Failed { .. }, None))));
        let printed = fixture("kit-printed.pdf");
        assert!(matches!(answer(&HostRequest::PdfPage { path: printed, page: 9, width: 2400 }), Some((HostReply::Failed { .. }, None))));
        assert!(answer(&HostRequest::StoreRead { name: "a".into() }).is_none(), "the store's request is no drawing");
    }

    /// The printed kit's first page drawn here, by poppler from the path, and handed to the core over the bridge: the core
    /// starts with a store that keeps nothing, the sign-in shows, and `Session.SignInKit` on the printed kit (no token in
    /// its bytes, no `Keywords`) asks this host for its pages — the core's decoder reads the QR this side drew and the
    /// sign-in's gate carries the kit (the fixture's fake account), never its Secret Key. The only test that starts the
    /// core: `veyq_init` starts it once per process.
    #[test]
    fn the_printed_kits_page_drawn_here_is_read_by_the_core_over_the_bridge() {
        // The whole case — the drawing here and the core's read — runs on a thread of its own and is waited for KIT_READ
        // at most: a call, a reply or a gate that never comes fails the test, never hangs the run. (It hung idle in 3 of
        // 7 `run.py build`s, 2026-11-13: the core's first store read was replied before `veyq_init` had published the
        // core, the reply dropped and `SessionStart` never answered — fixed in the bridge, `Started`; and a host that
        // stays silent now fails the ask, `Host.Silent`, so a lost reply is a Failed answer within its 30 s.)
        let (done, outcome) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let _ = done.send(draw_and_read_the_printed_kit());
        });
        let kit = match outcome.recv_timeout(KIT_READ) {
            Ok(read) => read.unwrap_or_else(|why| panic!("{why}")),
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => panic!("the kit was not drawn and read within {KIT_READ:?}"),
            // the thread panicked (an assertion of the drawing): its message is on stderr
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => panic!("the kit's drawing failed"),
        };
        assert_eq!(kit.email, "alex.beispiel@example.org");
        assert_eq!(kit.server.as_deref(), Some("https://familie.example.org"));
        assert_eq!(kit.server_from, wire::ServerFrom::Token);
    }

    /// How long the drawing and the core's read may take: a few seconds where they end (measured 2 s alone, up to 9 s
    /// with twice the CPUs busy), and past the host's 30 s, after which a lost reply is answered Failed.
    const KIT_READ: std::time::Duration = std::time::Duration::from_secs(60);

    /// The printed kit's first page drawn here, then read by the core over the bridge ([read_kit_over_the_bridge]).
    fn draw_and_read_the_printed_kit() -> Result<wire::KitShown, String> {
        let printed = fixture("kit-printed.pdf");
        match answer(&HostRequest::PdfPages { path: printed.clone() }) {
            Some((HostReply::Value { value: Some(n) }, None)) => assert!(n.parse::<i32>().unwrap() >= 1),
            other => panic!("the printed kit did not open: {other:?}"),
        }
        let page = pdf_page(&printed, 1, 2400).expect("its first page draws");
        assert_eq!(page.width, 2400);
        assert_eq!(page.bytes.len(), 2400 * page.height as usize);
        assert!(page.height > 3000, "an A4 page 2400 px wide is about 3394 px high: {}", page.height);
        assert!(page.bytes.iter().any(|&b| b < 64) && page.bytes.iter().any(|&b| b > 192), "the page drew nothing");
        read_kit_over_the_bridge(printed)
    }

    /// The core started over a store that keeps nothing, the sign-in shown, the printed kit at [printed] read through
    /// `Session.SignInKit`, and the kit the sign-in's gate carries; the core shut down again.
    fn read_kit_over_the_bridge(printed: String) -> Result<wire::KitShown, String> {
        let config = wire::Config { device: "kit-drawing test".into(), window: 0, prompt: String::new(), offline_root: None, language: crate::core_ui::words::language().into(), net_log: false };
        if bridge::start(&config, DeviceStore::in_memory()) != Start::Started {
            return Err("the core did not start".into());
        }
        let (_watch, gates) = bridge::watch(&wire::Watched::Session).ok_or("the session is not watched")?;
        let started = bridge::call_blocking(&wire::Call::SessionStart);
        if !matches!(started, Ok((wire::Answer::Done, None))) {
            bridge::shutdown();
            return Err(format!("the start was answered {started:?}, not Done"));
        }
        let answered = bridge::call_blocking(&wire::Call::SessionSignInKit { path: printed, server: String::new() });
        if !matches!(answered, Ok((wire::Answer::Done, None))) {
            bridge::shutdown();
            return Err(format!("the core read no kit from the pages drawn here: {answered:?}"));
        }
        // the gate that carries the kit was sent before the call was answered
        let mut kit = None;
        while let Ok(Ok(update)) = gates.recv_blocking() {
            if let wire::Update::Session { gate: wire::Gate::SignIn { kit: Some(k), .. } } = update {
                kit = Some(k);
                break;
            }
        }
        bridge::shutdown();
        kit.ok_or_else(|| "the gate carries no kit".into())
    }
}
