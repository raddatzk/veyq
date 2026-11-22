//! A PDF's page drawn from memory (ADR 0137: shown bytes never written to disk): poppler-glib through poppler-rs renders
//! it onto a white cairo surface at the width asked, its pixels handed back as cairo's ARGB32 rows — a receipt's pages
//! (`apps::expenses::receipt_viewer`), a file's (`apps::depot::file_page`) and the Emergency Kit's ([super::kit_drawing]).
use gtk::cairo;

/// A page rendered: its pixels as cairo's ARGB32 rows.
pub(crate) struct Rendered {
    pub(crate) width: i32,
    pub(crate) height: i32,
    pub(crate) stride: usize,
    pub(crate) data: Vec<u8>,
}

/// One page onto a white cairo surface [width] pixels wide.
pub(crate) fn render(page: &poppler::Page, width: i32) -> Option<Rendered> {
    let (w, h) = page.size();
    if w <= 0.0 || h <= 0.0 {
        return None;
    }
    let scale = f64::from(width) / w;
    let height = (h * scale).ceil() as i32;
    let mut surface = cairo::ImageSurface::create(cairo::Format::ARgb32, width, height).ok()?;
    {
        let cr = cairo::Context::new(&surface).ok()?;
        cr.set_source_rgb(1.0, 1.0, 1.0);
        cr.paint().ok()?;
        cr.scale(scale, scale);
        page.render(&cr);
    }
    surface.flush();
    let stride = surface.stride() as usize;
    let data = surface.data().ok()?.to_vec();
    Some(Rendered { width, height, stride, data })
}

#[cfg(test)]
mod tests {
    use gtk::glib;

    use super::*;

    /// A PDF of [pages] pages, 200 × 100 pt each, the left half of each filled black (as the Windows app's `PdfPagesTest`).
    fn pdf(pages: usize) -> Vec<u8> {
        let mut objects = vec![
            "<< /Type /Catalog /Pages 2 0 R >>".to_owned(),
            format!("<< /Type /Pages /Kids [{}] /Count {pages} >>", (0..pages).map(|i| format!("{} 0 R", 3 + 2 * i)).collect::<Vec<_>>().join(" ")),
        ];
        let draw = "0 0 0 rg 0 0 100 100 re f";
        for i in 0..pages {
            objects.push(format!("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Contents {} 0 R >>", 4 + 2 * i));
            objects.push(format!("<< /Length {} >>\nstream\n{draw}\nendstream", draw.len()));
        }
        let mut out = String::from("%PDF-1.4\n");
        let mut offsets = Vec::new();
        for (i, o) in objects.iter().enumerate() {
            offsets.push(out.len());
            out.push_str(&format!("{} 0 obj\n{o}\nendobj\n", i + 1));
        }
        let xref = out.len();
        out.push_str(&format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1));
        for o in offsets {
            out.push_str(&format!("{o:010} 00000 n \n"));
        }
        out.push_str(&format!("trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n", objects.len() + 1));
        out.into_bytes()
    }

    #[test]
    fn a_pdf_opens_from_memory_and_a_page_renders_at_the_width_asked() {
        let doc = poppler::Document::from_bytes(&glib::Bytes::from_owned(pdf(25)), None).expect("the PDF opens from memory");
        assert_eq!(doc.n_pages(), 25);
        let r = render(&doc.page(0).expect("its first page"), 400).expect("the page renders");
        assert_eq!((r.width, r.height), (400, 200));
        // B, G, R, A: the left half black, the right half white
        let at = |x: usize, y: usize| r.data[y * r.stride + x * 4];
        assert!(at(50, 100) < 32, "the left half is not drawn black");
        assert!(at(350, 100) > 224, "the right half is not white");
    }

    #[test]
    fn bytes_that_are_no_pdf_do_not_open() {
        assert!(poppler::Document::from_bytes(&glib::Bytes::from_static(b"no pdf"), None).is_err());
    }
}
