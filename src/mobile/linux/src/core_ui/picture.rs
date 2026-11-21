//! A picture held at the core's edge (ADR 0137 decision 1: an image decoded in memory only, at most the edge the core
//! names): a file's page, a row's cover, a Memory's lightbox and its contributions hold what GTK decoded through
//! [within_edge].
use gtk::prelude::*;
use gtk::{gdk, glib};

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
