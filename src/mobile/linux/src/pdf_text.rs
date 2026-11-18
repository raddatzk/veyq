//! A PDF's text selected and copied on a file's page (inbox #955; Apple's `PDFView` and Android 15's `selectContent` do
//! this themselves, GTK has no view that does): poppler reads the text — `Page::selected_region` for the highlight,
//! `Page::selected_text` for what is copied — and this overlay over [crate::file_page]'s rendered pages does the rest.
//!
//! A drag over the pages selects, from the page it starts on to the page it is over, a page between them whole; a double
//! click selects a word and a triple click a line (poppler's `Word` and `Line` styles, the drag then extends by them).
//! Ctrl+A selects every page shown, Escape lets go. Ctrl+C and the context menu's *Copy* put the selected text on the
//! clipboard — the one place the text ever goes, and only on that action: nothing of it is logged (a count of
//! characters only) or written. A page without a text layer (a scan) highlights nothing and copies nothing.
//!
//! The geometry is pure ([Frame], [Anchor], [spans]) and tested apart from GTK; the widgets hold no state but [PdfText]'s.
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use gtk::prelude::*;
use gtk::{gdk, gio, glib};

use crate::words;

/// How far a selection reaches past the point under the pointer: a glyph (a press and a drag), the word (a double click)
/// or the line (a triple click).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Granularity {
    Glyph,
    Word,
    Line,
}

impl Granularity {
    /// The granularity of the [n]th press of a click (GTK's `n-press`): one a glyph, two a word, three and on a line.
    pub fn of_press(n: i32) -> Self {
        match n {
            ..=1 => Self::Glyph,
            2 => Self::Word,
            _ => Self::Line,
        }
    }

    fn style(self) -> poppler::SelectionStyle {
        match self {
            Self::Glyph => poppler::SelectionStyle::Glyph,
            Self::Word => poppler::SelectionStyle::Word,
            Self::Line => poppler::SelectionStyle::Line,
        }
    }
}

/// A point on a page, in the page's own points (poppler's: the origin at its top left).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Anchor {
    pub page: usize,
    pub x: f64,
    pub y: f64,
}

/// A page as it is laid out: its frame's allocation in the column's coordinates and the page's size in points. The
/// frame shows the page fitted into it, its aspect kept and centred (`gtk::ContentFit::Contain`).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Frame {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
    pub page_width: f64,
    pub page_height: f64,
}

impl Frame {
    /// Where the page is painted inside the frame, relative to the frame: its left, top and the scale from points to
    /// pixels.
    pub fn painted(&self) -> (f64, f64, f64) {
        if self.page_width <= 0.0 || self.page_height <= 0.0 {
            return (0.0, 0.0, 0.0);
        }
        let scale = (self.width / self.page_width).min(self.height / self.page_height);
        let (w, h) = (self.page_width * scale, self.page_height * scale);
        ((self.width - w) / 2.0, (self.height - h) / 2.0, scale)
    }

    /// The column's point (x, y) on this page, held within it.
    fn point(&self, x: f64, y: f64) -> (f64, f64) {
        let (left, top, scale) = self.painted();
        if scale <= 0.0 {
            return (0.0, 0.0);
        }
        (((x - self.x - left) / scale).clamp(0.0, self.page_width), ((y - self.y - top) / scale).clamp(0.0, self.page_height))
    }

    fn contains(&self, x: f64, y: f64) -> bool {
        let (left, top, scale) = self.painted();
        let (x0, y0) = (self.x + left, self.y + top);
        scale > 0.0 && x >= x0 && x <= x0 + self.page_width * scale && y >= y0 && y <= y0 + self.page_height * scale
    }
}

/// The page under the column's point (x, y), where a press starts a selection; none in a gap or beside the pages.
pub fn hit(frames: &[Frame], x: f64, y: f64) -> Option<Anchor> {
    let page = frames.iter().position(|f| f.contains(x, y))?;
    let (px, py) = frames[page].point(x, y);
    Some(Anchor { page, x: px, y: py })
}

/// The page a drag over the column's point (x, y) reaches: the one it is over, in the gap below a page that page, above
/// the first the first and below the last the last, its point held within that page — a drag past the pages selects to their edge.
pub fn reach(frames: &[Frame], x: f64, y: f64) -> Option<Anchor> {
    if frames.is_empty() {
        return None;
    }
    // the last page whose top is above the point: in a gap the page before it, above the first page the first
    let page = frames.iter().rposition(|f| y >= f.y).unwrap_or(0);
    let (px, py) = frames[page].point(x, y);
    Some(Anchor { page, x: px, y: py })
}

/// Every page shown, whole: what Ctrl+A selects.
pub fn whole(sizes: &[(f64, f64)]) -> Option<(Anchor, Anchor)> {
    let last = sizes.len().checked_sub(1)?;
    let (w, h) = sizes[last];
    Some((Anchor { page: 0, x: 0.0, y: 0.0 }, Anchor { page: last, x: w, y: h }))
}

/// What poppler is asked of each page a selection from [a] to [b] touches: the page and the selection's start and end
/// points on it (x1, y1, x2, y2). On one page the two points as they are (poppler orders them in the text's flow); over
/// several, the first page from its point to its end, a page between whole, the last from its start to its point.
pub fn spans(a: Anchor, b: Anchor, sizes: &[(f64, f64)]) -> Vec<(usize, [f64; 4])> {
    let (first, last) = if a.page <= b.page { (a, b) } else { (b, a) };
    if last.page >= sizes.len() {
        return Vec::new();
    }
    if first.page == last.page {
        return vec![(a.page, [a.x, a.y, b.x, b.y])];
    }
    (first.page..=last.page)
        .map(|p| {
            let (w, h) = sizes[p];
            let span = if p == first.page {
                [first.x, first.y, w, h]
            } else if p == last.page {
                [0.0, 0.0, last.x, last.y]
            } else {
                [0.0, 0.0, w, h]
            };
            (p, span)
        })
        .collect()
}

fn rectangle(span: [f64; 4]) -> poppler::Rectangle {
    let mut r = poppler::Rectangle::new();
    r.set_x1(span[0]);
    r.set_y1(span[1]);
    r.set_x2(span[2]);
    r.set_y2(span[3]);
    r
}

/// The text a selection from [a] to [b] holds, its pages' parts joined by a line break; empty where no page has a text
/// layer under it.
pub fn text(pdf: &poppler::Document, a: Anchor, b: Anchor, granularity: Granularity) -> String {
    let sizes = sizes(pdf, a.page.max(b.page) + 1);
    let parts: Vec<String> = spans(a, b, &sizes)
        .into_iter()
        .filter_map(|(p, span)| pdf.page(p as i32)?.selected_text(granularity.style(), &mut rectangle(span)))
        .map(|t| t.to_string())
        .filter(|t| !t.is_empty())
        .collect();
    parts.join("\n")
}

fn sizes(pdf: &poppler::Document, count: usize) -> Vec<(f64, f64)> {
    (0..count).map(|p| pdf.page(p as i32).map(|page| page.size()).unwrap_or((0.0, 0.0))).collect()
}

/// A selection: where it started, where it reaches, and by what it grows.
#[derive(Clone, Copy, Debug, PartialEq)]
struct Selection {
    from: Anchor,
    to: Anchor,
    granularity: Granularity,
}

/// One page's frame on screen: the overlay that holds the rendered picture, and the area the highlight is drawn in.
pub struct PageFrame {
    pub overlay: gtk::Overlay,
    pub picture: gtk::Picture,
    area: gtk::DrawingArea,
}

impl PageFrame {
    /// A frame [width] × [height] for a page; the picture is drawn by [crate::file_page], the highlight here.
    pub fn new(width: i32, height: i32) -> Self {
        let picture = gtk::Picture::builder().can_shrink(true).content_fit(gtk::ContentFit::Contain).build();
        picture.set_size_request(width, height);
        let area = gtk::DrawingArea::builder().can_target(false).build();
        let overlay = gtk::Overlay::builder().child(&picture).build();
        overlay.add_overlay(&area);
        overlay.add_css_class("card");
        overlay.set_overflow(gtk::Overflow::Hidden);
        overlay.set_cursor_from_name(Some("text"));
        Self { overlay, picture, area }
    }
}

/// The selection over a PDF's pages in [column]: the gestures, the keys, the context menu and the highlight.
pub struct PdfText {
    pdf: poppler::Document,
    column: gtk::Box,
    frames: Vec<PageFrame>,
    selection: Cell<Option<Selection>>,
    copy: gio::SimpleAction,
    menu: RefCell<Option<gtk::PopoverMenu>>,
}

impl PdfText {
    /// The selection over [frames], the pages of [pdf] laid out in [column] in order.
    pub fn attach(pdf: &poppler::Document, column: &gtk::Box, frames: Vec<PageFrame>) -> Rc<Self> {
        let copy = gio::SimpleAction::new("copy", None);
        copy.set_enabled(false);
        let this = Rc::new(Self { pdf: pdf.clone(), column: column.clone(), frames, selection: Cell::new(None), copy, menu: RefCell::new(None) });
        column.set_focusable(true);
        // the column takes the focus at a press; its viewport would scroll the whole column to it, mid-drag
        if let Some(viewport) = column.ancestor(gtk::Viewport::static_type()).and_downcast::<gtk::Viewport>() {
            viewport.set_scroll_to_focus(false);
        }
        column.update_property(&[gtk::accessible::Property::Label(&words::tab_depot_file())]);
        for (i, frame) in this.frames.iter().enumerate() {
            let weak = Rc::downgrade(&this);
            frame.area.set_draw_func(move |_, cr, _, _| {
                if let Some(this) = weak.upgrade() {
                    this.draw(i, cr);
                }
            });
        }
        this.actions();
        this.gestures();
        this
    }

    fn actions(self: &Rc<Self>) {
        let group = gio::SimpleActionGroup::new();
        let weak = Rc::downgrade(self);
        self.copy.connect_activate(move |_, _| {
            if let Some(this) = weak.upgrade() {
                this.copy_selection();
            }
        });
        group.add_action(&self.copy);
        let select_all = gio::SimpleAction::new("select-all", None);
        let weak = Rc::downgrade(self);
        select_all.connect_activate(move |_, _| {
            if let Some(this) = weak.upgrade() {
                this.select_all();
            }
        });
        group.add_action(&select_all);
        let clear = gio::SimpleAction::new("clear", None);
        let weak = Rc::downgrade(self);
        clear.connect_activate(move |_, _| {
            if let Some(this) = weak.upgrade() {
                this.set(None);
            }
        });
        group.add_action(&clear);
        self.column.insert_action_group("pdf", Some(&group));
        let keys = gtk::ShortcutController::new();
        for (trigger, action) in [("<Control>c", "pdf.copy"), ("<Control>a", "pdf.select-all"), ("Escape", "pdf.clear")] {
            keys.add_shortcut(gtk::Shortcut::new(gtk::ShortcutTrigger::parse_string(trigger), Some(gtk::NamedAction::new(action))));
        }
        self.column.add_controller(keys);
    }

    fn gestures(self: &Rc<Self>) {
        // a press starts a selection (its count the granularity), a drag extends it
        let click = gtk::GestureClick::builder().button(gdk::BUTTON_PRIMARY).build();
        let weak = Rc::downgrade(self);
        click.connect_pressed(move |_, n, x, y| {
            let Some(this) = weak.upgrade() else { return };
            this.column.grab_focus();
            let selection = hit(&this.layout(), x, y).map(|at| Selection { from: at, to: at, granularity: Granularity::of_press(n) });
            this.set(selection);
        });
        self.column.add_controller(click);
        let drag = gtk::GestureDrag::builder().button(gdk::BUTTON_PRIMARY).build();
        let weak = Rc::downgrade(self);
        drag.connect_drag_update(move |d, dx, dy| {
            let Some(this) = weak.upgrade() else { return };
            let (Some(selection), Some((x, y))) = (this.selection.get(), d.start_point()) else { return };
            if let Some(to) = reach(&this.layout(), x + dx, y + dy) {
                this.set(Some(Selection { to, ..selection }));
            }
        });
        let weak = Rc::downgrade(self);
        drag.connect_drag_end(move |_, _, _| {
            if let Some(this) = weak.upgrade() {
                this.said();
            }
        });
        self.column.add_controller(drag);
        // the context menu: Copy, where something is selected
        let menu_click = gtk::GestureClick::builder().button(gdk::BUTTON_SECONDARY).build();
        let weak = Rc::downgrade(self);
        menu_click.connect_pressed(move |_, _, x, y| {
            if let Some(this) = weak.upgrade() {
                this.column.grab_focus();
                this.menu_at(x, y);
            }
        });
        self.column.add_controller(menu_click);
        let weak = Rc::downgrade(self);
        self.column.connect_destroy(move |_| {
            if let Some(menu) = weak.upgrade().and_then(|this| this.menu.borrow_mut().take()) {
                menu.unparent();
            }
        });
    }

    fn menu_at(&self, x: f64, y: f64) {
        let mut held = self.menu.borrow_mut();
        let menu = held.get_or_insert_with(|| {
            let model = gio::Menu::new();
            model.append(Some(words::file_text_copy().as_str()), Some("pdf.copy"));
            let menu = gtk::PopoverMenu::from_model(Some(&model));
            menu.set_has_arrow(false);
            menu.set_halign(gtk::Align::Start);
            menu.set_parent(&self.column);
            menu
        });
        menu.set_pointing_to(Some(&gdk::Rectangle::new(x as i32, y as i32, 1, 1)));
        menu.popup();
    }

    /// The pages' frames as they are laid out now, in the column's coordinates.
    fn layout(&self) -> Vec<Frame> {
        self.frames
            .iter()
            .enumerate()
            .map(|(i, f)| {
                let bounds = f.overlay.compute_bounds(&self.column);
                let (page_width, page_height) = self.pdf.page(i as i32).map(|p| p.size()).unwrap_or((0.0, 0.0));
                let (x, y, width, height) = bounds
                    .map(|b| (f64::from(b.x()), f64::from(b.y()), f64::from(b.width()), f64::from(b.height())))
                    .unwrap_or((0.0, 0.0, 0.0, 0.0));
                Frame { x, y, width, height, page_width, page_height }
            })
            .collect()
    }

    fn set(&self, selection: Option<Selection>) {
        let before = self.selection.replace(selection);
        if before != selection {
            self.copy.set_enabled(selection.is_some());
            for frame in &self.frames {
                frame.area.queue_draw();
            }
        }
    }

    fn select_all(&self) {
        let sizes = sizes(&self.pdf, self.frames.len());
        self.set(whole(&sizes).map(|(from, to)| Selection { from, to, granularity: Granularity::Glyph }));
        self.said();
    }

    /// The selection's size on stderr, for a pass to read (a count of characters, never the text); a selection that
    /// holds no text (a page without a text layer) is let go, so Copy is not offered for nothing.
    fn said(&self) {
        let Some(s) = self.selection.get() else { return };
        let count = text(&self.pdf, s.from, s.to, s.granularity).chars().count();
        eprintln!("veyq: file PDF selection: pages {}–{}, {count} characters", s.from.page.min(s.to.page) + 1, s.from.page.max(s.to.page) + 1);
        if count == 0 {
            self.set(None);
        }
    }

    /// The selected text on the clipboard: what Ctrl+C and the menu's Copy do, and the one place the text goes.
    fn copy_selection(&self) {
        let Some(s) = self.selection.get() else { return };
        let selected = text(&self.pdf, s.from, s.to, s.granularity);
        if selected.is_empty() {
            eprintln!("veyq: file PDF copy: no text selected");
            return;
        }
        let clipboard = self.column.clipboard();
        clipboard.set_text(&selected);
        let count = selected.chars().count();
        eprintln!("veyq: file PDF text copied: {count} characters");
        // no `announce` of the copy: GTK 4.22's ended the app with SIGSEGV in the container (src/mobile/CLAUDE.md)
        if cfg!(debug_assertions) {
            // a debug build reads the clipboard back, for a pass to prove what it holds: whether it is the selection,
            // never what it says
            glib::spawn_future_local(async move {
                let back = clipboard.read_text_future().await.ok().flatten().map(|t| t.to_string());
                eprintln!("veyq: file PDF clipboard read back: {} characters, the selection: {}", back.as_ref().map_or(0, |t| t.chars().count()), back.as_deref() == Some(selected.as_str()));
            });
        }
    }

    /// The highlight on page [i]: the selection's region on it, poppler's, in the accent colour.
    fn draw(&self, i: usize, cr: &gtk::cairo::Context) {
        let Some(s) = self.selection.get() else { return };
        let Some(frame) = self.layout().get(i).copied() else { return };
        let sizes = sizes(&self.pdf, s.from.page.max(s.to.page) + 1);
        let Some((_, span)) = spans(s.from, s.to, &sizes).into_iter().find(|(p, _)| *p == i) else { return };
        let Some(page) = self.pdf.page(i as i32) else { return };
        let (left, top, scale) = frame.painted();
        let Some(region) = page.selected_region(scale, s.granularity.style(), &mut rectangle(span)) else { return };
        let accent = adw::StyleManager::default().accent_color_rgba();
        cr.set_source_rgba(f64::from(accent.red()), f64::from(accent.green()), f64::from(accent.blue()), 0.35);
        for r in (0..region.num_rectangles()).map(|n| region.rectangle(n)) {
            cr.rectangle(left + f64::from(r.x()), top + f64::from(r.y()), f64::from(r.width()), f64::from(r.height()));
        }
        let _ = cr.fill();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A Letter page's height laid out 720 wide.
    const LETTER_HEIGHT: f64 = 720.0 * 792.0 / 612.0;

    /// A US Letter page (612 × 792 points) laid out 720 wide at the column's top, as the file page lays it out.
    fn letter(y: f64) -> Frame {
        Frame { x: 0.0, y, width: 720.0, height: LETTER_HEIGHT, page_width: 612.0, page_height: 792.0 }
    }

    #[test]
    fn a_press_count_is_its_granularity() {
        assert_eq!(Granularity::of_press(1), Granularity::Glyph);
        assert_eq!(Granularity::of_press(2), Granularity::Word);
        assert_eq!(Granularity::of_press(3), Granularity::Line);
        assert_eq!(Granularity::of_press(4), Granularity::Line);
    }

    #[test]
    fn a_page_is_painted_fitted_and_centred() {
        // wider than the page's aspect: centred across, full height
        let f = Frame { x: 0.0, y: 0.0, width: 1000.0, height: 792.0, page_width: 612.0, page_height: 792.0 };
        let (left, top, scale) = f.painted();
        assert_eq!((left, top, scale), (194.0, 0.0, 1.0));
        // taller: centred down, full width
        let f = Frame { x: 0.0, y: 0.0, width: 306.0, height: 600.0, page_width: 612.0, page_height: 792.0 };
        let (left, top, scale) = f.painted();
        assert_eq!((left, top, scale), (0.0, 102.0, 0.5));
    }

    #[test]
    fn a_press_finds_its_page_and_point() {
        let frames = [letter(12.0), letter(12.0 + LETTER_HEIGHT + 12.0)];
        let scale = 720.0 / 612.0;
        let at = hit(&frames, 72.0 * scale, 12.0 + 100.0 * scale).unwrap();
        assert_eq!(at.page, 0);
        assert!((at.x - 72.0).abs() < 1e-9 && (at.y - 100.0).abs() < 1e-9, "{at:?}");
        let at = hit(&frames, 300.0 * scale, 12.0 + LETTER_HEIGHT + 12.0 + 50.0 * scale).unwrap();
        assert_eq!(at.page, 1);
        assert!((at.x - 300.0).abs() < 1e-9 && (at.y - 50.0).abs() < 1e-9, "{at:?}");
        // the gap between two pages and the margin above the first start nothing
        assert_eq!(hit(&frames, 100.0, 12.0 + LETTER_HEIGHT + 6.0), None);
        assert_eq!(hit(&frames, 100.0, 4.0), None);
    }

    #[test]
    fn a_press_beside_a_centred_page_starts_nothing() {
        let frames = [Frame { x: 0.0, y: 0.0, width: 1000.0, height: 792.0, page_width: 612.0, page_height: 792.0 }];
        assert_eq!(hit(&frames, 100.0, 300.0), None);
        assert_eq!(hit(&frames, 900.0, 300.0), None);
        assert_eq!(hit(&frames, 194.0 + 306.0, 300.0).map(|a| a.x), Some(306.0));
    }

    #[test]
    fn a_drag_past_the_pages_reaches_their_edge() {
        let frames = [letter(12.0), letter(12.0 + LETTER_HEIGHT + 12.0)];
        // in the gap below page one: still page one, at its foot
        let at = reach(&frames, 100.0, 12.0 + LETTER_HEIGHT + 6.0).unwrap();
        assert_eq!((at.page, at.y), (0, 792.0));
        // above the first page: its top
        let at = reach(&frames, 100.0, -50.0).unwrap();
        assert_eq!((at.page, at.y), (0, 0.0));
        // below the last page and right of it: the last page's bottom right corner
        let at = reach(&frames, 5000.0, 99_999.0).unwrap();
        assert_eq!((at.page, at.x, at.y), (1, 612.0, 792.0));
        assert_eq!(reach(&[], 0.0, 0.0), None);
    }

    #[test]
    fn a_selection_over_pages_takes_a_page_between_whole() {
        let sizes = [(612.0, 792.0), (500.0, 700.0), (612.0, 792.0)];
        let a = Anchor { page: 0, x: 72.0, y: 100.0 };
        let b = Anchor { page: 2, x: 300.0, y: 200.0 };
        let expected = vec![(0, [72.0, 100.0, 612.0, 792.0]), (1, [0.0, 0.0, 500.0, 700.0]), (2, [0.0, 0.0, 300.0, 200.0])];
        assert_eq!(spans(a, b, &sizes), expected);
        // dragged upwards: the same pages, the same spans
        assert_eq!(spans(b, a, &sizes), expected);
        // on one page the points stay as they are, poppler orders them
        assert_eq!(spans(b, Anchor { page: 2, x: 10.0, y: 20.0 }, &sizes), vec![(2, [300.0, 200.0, 10.0, 20.0])]);
        // a page the sizes do not know is no selection
        assert_eq!(spans(a, Anchor { page: 3, x: 0.0, y: 0.0 }, &sizes), vec![]);
    }

    #[test]
    fn select_all_takes_every_page_shown() {
        let (from, to) = whole(&[(612.0, 792.0), (500.0, 700.0)]).unwrap();
        assert_eq!(from, Anchor { page: 0, x: 0.0, y: 0.0 });
        assert_eq!(to, Anchor { page: 1, x: 500.0, y: 700.0 });
        assert_eq!(whole(&[]), None);
    }

    /// A PDF of three Letter pages in Helvetica 20: two lines on the first, one on the second, none on the third (a page
    /// without a text layer, as a scan's).
    fn fixture() -> poppler::Document {
        let pages: [&[&str]; 3] = [&["Hello veyq world", "Second line here"], &["Page two text"], &[]];
        let mut objects = vec![
            "<< /Type /Catalog /Pages 2 0 R >>".to_owned(),
            format!("<< /Type /Pages /Kids [{}] /Count 3 >>", (0..3).map(|i| format!("{} 0 R", 4 + 2 * i)).collect::<Vec<_>>().join(" ")),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_owned(),
        ];
        for (i, lines) in pages.iter().enumerate() {
            let shown: Vec<String> = lines.iter().enumerate().map(|(j, t)| format!("1 0 0 1 72 {} Tm ({t}) Tj", 700 - 40 * j)).collect();
            let ops = if lines.is_empty() { String::new() } else { format!("BT /F1 20 Tf {} ET", shown.join(" ")) };
            objects.push(format!("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents {} 0 R >>", 5 + 2 * i));
            objects.push(format!("<< /Length {} >>\nstream\n{ops}\nendstream", ops.len()));
        }
        let mut out = "%PDF-1.4\n".to_owned();
        let mut offsets = Vec::new();
        for (k, o) in objects.iter().enumerate() {
            offsets.push(out.len());
            out += &format!("{} 0 obj\n{o}\nendobj\n", k + 1);
        }
        let xref = out.len();
        out += &format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1);
        for o in offsets {
            out += &format!("{o:010} 00000 n \n");
        }
        out += &format!("trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n", objects.len() + 1);
        poppler::Document::from_bytes(&glib::Bytes::from_owned(out.into_bytes()), None).expect("the fixture opens")
    }

    #[test]
    fn a_drag_selects_the_text_under_it() {
        let pdf = fixture();
        // the first line's baseline is 92 points from the top; from left of "Hello" to right of "world"
        let text = text(&pdf, Anchor { page: 0, x: 60.0, y: 85.0 }, Anchor { page: 0, x: 400.0, y: 85.0 }, Granularity::Glyph);
        assert_eq!(text, "Hello veyq world");
    }

    #[test]
    fn a_double_click_selects_the_word_and_a_triple_the_line() {
        let pdf = fixture();
        // "veyq" runs from about 123 to 165 points
        let on_veyq = Anchor { page: 0, x: 140.0, y: 85.0 };
        assert_eq!(text(&pdf, on_veyq, on_veyq, Granularity::Word), "veyq");
        assert_eq!(text(&pdf, on_veyq, on_veyq, Granularity::Line), "Hello veyq world");
    }

    #[test]
    fn select_all_copies_every_page_and_a_page_without_text_adds_nothing() {
        let pdf = fixture();
        let (from, to) = whole(&sizes(&pdf, 3)).unwrap();
        let all = text(&pdf, from, to, Granularity::Glyph);
        assert_eq!(all.lines().map(str::trim).collect::<Vec<_>>(), ["Hello veyq world", "Second line here", "Page two text"]);
        // the third page alone: no text layer, nothing selected
        let (from, to) = (Anchor { page: 2, x: 0.0, y: 0.0 }, Anchor { page: 2, x: 612.0, y: 792.0 });
        assert_eq!(text(&pdf, from, to, Granularity::Glyph), "");
    }
}
