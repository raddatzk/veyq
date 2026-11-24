//! A PDF page's text for a screen reader (inbox #963): each page of a PDF shown with [crate::core_ui::pdf_text] — a Depot
//! file's, an Expenses receipt's (inbox #1220) — is a [PageText], the area [crate::core_ui::pdf_text] draws its highlight in, which implements GTK's `GtkAccessibleText`
//! (GTK 4.14, its extents 4.16, its caret set 4.22) over poppler's text of that page — so Orca reads the page, moves
//! through it by character, word, sentence and line, and finds the character under the pointer. Apple's `PDFView` and
//! Edge's viewer on Windows expose their text themselves; GTK has no PDF view that does.
//!
//! The page is a read-only multi-line text box (role `TextBox`, `ReadOnly`, `MultiLine`: what GNOME's read-only
//! `GtkTextView` is), focusable: Tab moves from page to page, the arrows move a caret through the text (Ctrl by word,
//! Home and End the line, Ctrl+Home and Ctrl+End the page), and a press on the text puts the caret there. The caret is
//! drawn while the page has the focus, the focus ring while it is visible.
//!
//! The page's text is read from poppler only when an assistive technology first asks for it (or the caret first moves),
//! and is held in memory only, as long as the page is: never logged (a count of characters only) and never written.
//! Its character boxes come from `poppler_page_get_text_layout`, which poppler-rs 0.26 does not bind (one rectangle per
//! character of `poppler_page_get_text`, evince's page accessibility reads the two the same way); where the two do not
//! match the text is still read, only its extents are not told.
//!
//! The text's units and the caret's moves are pure ([PageReading], [Move]) and tested apart from GTK.
use std::cell::{Cell, OnceCell, RefCell};

use gtk::glib::translate::ToGlibPtr;
use gtk::prelude::*;
use gtk::subclass::prelude::*;
use gtk::{gdk, glib, graphene, gsk};

use crate::core_ui::pdf_text::{Frame, What};

/// What a query or a move steps by: GTK's granularities as this page can tell them. A PDF's text names no paragraph, so
/// a paragraph is told as its line.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Unit {
    Character,
    Word,
    Sentence,
    Line,
}

/// A caret's move by a key: a character, a word, a line up or down (keeping its column where the line is long enough),
/// the line's start or end, the page's start or end.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Move {
    Left,
    Right,
    WordLeft,
    WordRight,
    Up,
    Down,
    LineStart,
    LineEnd,
    Top,
    Bottom,
}

/// A page's text as a screen reader reads it: offsets in characters (what `GtkAccessibleText` counts in), the units
/// around an offset, and each character's box on the page in points (top-left origin, poppler's).
pub struct PageReading {
    text: String,
    chars: Vec<char>,
    /// The byte each character starts at, and the text's length after the last.
    starts: Vec<usize>,
    /// Each character's box (x1, y1, x2, y2); empty where poppler's layout does not match the text one to one.
    boxes: Vec<[f64; 4]>,
}

impl PageReading {
    /// The reading of [text], with [boxes] one per character (dropped where their count is not the text's).
    pub fn new(text: String, boxes: Vec<[f64; 4]>) -> Self {
        let chars: Vec<char> = text.chars().collect();
        let mut starts: Vec<usize> = text.char_indices().map(|(i, _)| i).collect();
        starts.push(text.len());
        let boxes = if boxes.len() == chars.len() { boxes } else { Vec::new() };
        Self { text, chars, starts, boxes }
    }

    /// Its length in characters.
    pub fn len(&self) -> u32 {
        u32::try_from(self.chars.len()).unwrap_or(u32::MAX)
    }

    pub fn is_empty(&self) -> bool {
        self.chars.is_empty()
    }

    /// Whether each character's box is known.
    pub fn has_boxes(&self) -> bool {
        !self.boxes.is_empty()
    }

    /// The text from [start] to [end] (characters, [end] excluded; past the text, or `G_MAXUINT`, its end).
    pub fn slice(&self, start: u32, end: u32) -> &str {
        let end = end.min(self.len()) as usize;
        let start = (start as usize).min(end);
        &self.text[self.starts[start]..self.starts[end]]
    }

    /// Where each [unit] starts: GTK's boundaries run from a unit's start to the next one's, so a word holds the space
    /// after it and a line its line break.
    fn boundaries(&self, unit: Unit) -> Vec<u32> {
        let c = &self.chars;
        let at = |i: usize| u32::try_from(i).unwrap_or(u32::MAX);
        match unit {
            Unit::Character => (0..c.len()).map(at).collect(),
            Unit::Word => (0..c.len()).filter(|&i| !c[i].is_whitespace() && (i == 0 || c[i - 1].is_whitespace())).map(at).collect(),
            Unit::Sentence => (0..c.len())
                .filter(|&i| {
                    if i == 0 {
                        return true;
                    }
                    if c[i].is_whitespace() || !c[i - 1].is_whitespace() {
                        return false;
                    }
                    // the last character before the whitespace ends a sentence
                    c[..i].iter().rev().find(|ch| !ch.is_whitespace()).is_some_and(|ch| matches!(ch, '.' | '!' | '?' | '…'))
                })
                .map(at)
                .collect(),
            Unit::Line => (0..c.len()).filter(|&i| i == 0 || c[i - 1] == '\n').map(at).collect(),
        }
    }

    /// The [unit] around [offset]: its start and end, the end the next unit's start (a character: the one at it).
    pub fn around(&self, offset: u32, unit: Unit) -> (u32, u32) {
        let n = self.len();
        let o = offset.min(n);
        if unit == Unit::Character {
            return (o, (o + 1).min(n));
        }
        let starts = self.boundaries(unit);
        let start = starts.iter().rev().find(|&&b| b <= o).copied().unwrap_or(0);
        let end = starts.iter().find(|&&b| b > o).copied().unwrap_or(n);
        (start, end)
    }

    /// The end of the line [offset] is on, before its line break.
    fn line_end(&self, offset: u32) -> u32 {
        let (start, end) = self.around(offset, Unit::Line);
        if end > start && self.chars[end as usize - 1] == '\n' { end - 1 } else { end }
    }

    /// Where the caret at [caret] goes by [m].
    pub fn moved(&self, caret: u32, m: Move) -> u32 {
        let n = self.len();
        let c = caret.min(n);
        match m {
            Move::Left => c.saturating_sub(1),
            Move::Right => (c + 1).min(n),
            Move::WordLeft => self.boundaries(Unit::Word).into_iter().rev().find(|&b| b < c).unwrap_or(0),
            Move::WordRight => self.boundaries(Unit::Word).into_iter().find(|&b| b > c).unwrap_or(n),
            Move::LineStart => self.around(c, Unit::Line).0,
            Move::LineEnd => self.line_end(c),
            Move::Top => 0,
            Move::Bottom => n,
            Move::Up | Move::Down => {
                let lines = self.boundaries(Unit::Line);
                let Some(line) = lines.iter().rposition(|&b| b <= c) else { return c };
                let target = if m == Move::Up { line.checked_sub(1) } else { Some(line + 1).filter(|&t| t < lines.len()) };
                // past the first line up, the text's start; past the last down, its end — as a text view does
                let Some(target) = target else { return if m == Move::Up { 0 } else { n } };
                let column = c - lines[line];
                (lines[target] + column).min(self.line_end(lines[target]))
            }
        }
    }

    /// The box (x, y, width, height, in points) around the characters from [start] to [end], line breaks left out;
    /// none where no box is known.
    pub fn extents(&self, start: u32, end: u32) -> Option<[f64; 4]> {
        if self.boxes.is_empty() {
            return None;
        }
        let end = end.min(self.len()) as usize;
        let start = (start as usize).min(end);
        let mut union: Option<[f64; 4]> = None;
        for i in start..end {
            if self.chars[i] == '\n' {
                continue;
            }
            let b = self.boxes[i];
            union = Some(match union {
                None => b,
                Some(u) => [u[0].min(b[0]), u[1].min(b[1]), u[2].max(b[2]), u[3].max(b[3])],
            });
        }
        union.map(|u| [u[0], u[1], u[2] - u[0], u[3] - u[1]])
    }

    /// The character whose box holds the page's point (x, y), in points; none off the text.
    pub fn offset_at(&self, x: f64, y: f64) -> Option<u32> {
        let i = self.boxes.iter().zip(&self.chars).position(|(b, ch)| *ch != '\n' && x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3])?;
        u32::try_from(i).ok()
    }

    /// Where the caret at [offset] stands: (x, top, bottom) in points — the left edge of the character at it, past a
    /// line's end the right edge of the character before it; none where no box is known.
    pub fn caret_at(&self, offset: u32) -> Option<(f64, f64, f64)> {
        if self.boxes.is_empty() {
            return None;
        }
        let o = offset.min(self.len()) as usize;
        if o < self.chars.len() && self.chars[o] != '\n' {
            let b = self.boxes[o];
            return Some((b[0], b[1], b[3]));
        }
        let before = o.checked_sub(1).filter(|&i| self.chars[i] != '\n')?;
        let b = self.boxes[before];
        Some((b[2], b[1], b[3]))
    }
}

/// The page's text and its characters' boxes, read from poppler (the boxes through the call poppler-rs does not bind).
fn read(page: &poppler::Page) -> PageReading {
    let text = page.text().map(|t| t.to_string()).unwrap_or_default();
    let mut rects: *mut poppler::ffi::PopplerRectangle = std::ptr::null_mut();
    let mut n: std::ffi::c_uint = 0;
    // SAFETY: the page is alive for the call; poppler fills `rects` with a newly allocated array of `n` rectangles that
    // the caller owns and frees with g_free, which is done below once they are copied.
    let filled = unsafe { poppler::ffi::poppler_page_get_text_layout(page.to_glib_none().0, &mut rects, &mut n) };
    let mut boxes = Vec::new();
    if !rects.is_null() {
        if filled != glib::ffi::GFALSE {
            // SAFETY: `rects` points at `n` initialised rectangles (above)
            boxes = unsafe { std::slice::from_raw_parts(rects, n as usize) }.iter().map(|r| [r.x1, r.y1, r.x2, r.y2]).collect();
        }
        // SAFETY: allocated by poppler with g_malloc, freed once
        unsafe { glib::ffi::g_free(rects.cast()) };
    }
    PageReading::new(text, boxes)
}

fn unit(granularity: gtk::AccessibleTextGranularity) -> Unit {
    match granularity {
        gtk::AccessibleTextGranularity::Character => Unit::Character,
        gtk::AccessibleTextGranularity::Word => Unit::Word,
        gtk::AccessibleTextGranularity::Sentence => Unit::Sentence,
        // a PDF's text names no paragraph: its line
        _ => Unit::Line,
    }
}

mod imp {
    use super::*;

    #[derive(Default)]
    pub struct PageText {
        pub page: RefCell<Option<poppler::Page>>,
        /// The page's place in the document, from 0: what the log names.
        pub index: Cell<usize>,
        /// What the log calls the PDF.
        pub what: Cell<&'static str>,
        pub reading: OnceCell<PageReading>,
        pub caret: Cell<u32>,
    }

    impl PageText {
        pub fn reading(&self) -> &PageReading {
            self.reading.get_or_init(|| {
                let reading = self.page.borrow().as_ref().map(read).unwrap_or_else(|| PageReading::new(String::new(), Vec::new()));
                // a count, never the text
                eprintln!(
                    "veyq: {} PDF page {} read for assistive technologies: {} characters, boxes: {}",
                    self.what.get(),
                    self.index.get() + 1,
                    reading.len(),
                    reading.has_boxes()
                );
                reading
            })
        }
    }

    #[glib::object_subclass]
    impl ObjectSubclass for PageText {
        const NAME: &'static str = "VeyqPdfPageText";
        type Type = super::PageText;
        type ParentType = gtk::DrawingArea;
        type Interfaces = (gtk::AccessibleText,);

        fn class_init(klass: &mut Self::Class) {
            // GNOME's read-only text: a text box that says it is read-only (GtkTextView's role)
            klass.set_accessible_role(gtk::AccessibleRole::TextBox);
        }
    }

    impl ObjectImpl for PageText {
        fn constructed(&self) {
            self.parent_constructed();
            let obj = self.obj();
            obj.set_focusable(true);
            obj.update_property(&[gtk::accessible::Property::ReadOnly(true), gtk::accessible::Property::MultiLine(true)]);
            let keys = gtk::EventControllerKey::new();
            let weak = obj.downgrade();
            keys.connect_key_pressed(move |_, key, _, state| {
                let Some(obj) = weak.upgrade() else { return glib::Propagation::Proceed };
                let word = state.contains(gdk::ModifierType::CONTROL_MASK);
                if state.contains(gdk::ModifierType::SHIFT_MASK) || state.contains(gdk::ModifierType::ALT_MASK) {
                    return glib::Propagation::Proceed;
                }
                let m = match key {
                    gdk::Key::Left | gdk::Key::KP_Left => if word { Move::WordLeft } else { Move::Left },
                    gdk::Key::Right | gdk::Key::KP_Right => if word { Move::WordRight } else { Move::Right },
                    gdk::Key::Up | gdk::Key::KP_Up if !word => Move::Up,
                    gdk::Key::Down | gdk::Key::KP_Down if !word => Move::Down,
                    gdk::Key::Home | gdk::Key::KP_Home => if word { Move::Top } else { Move::LineStart },
                    gdk::Key::End | gdk::Key::KP_End => if word { Move::Bottom } else { Move::LineEnd },
                    _ => return glib::Propagation::Proceed,
                };
                let to = obj.imp().reading().moved(obj.imp().caret.get(), m);
                obj.place(to);
                glib::Propagation::Stop
            });
            obj.add_controller(keys);
            let focus = gtk::EventControllerFocus::new();
            let weak = obj.downgrade();
            focus.connect_enter(move |_| {
                if let Some(obj) = weak.upgrade() {
                    obj.reveal();
                    obj.queue_draw();
                }
            });
            let weak = obj.downgrade();
            focus.connect_leave(move |_| {
                if let Some(obj) = weak.upgrade() {
                    obj.queue_draw();
                }
            });
            obj.add_controller(focus);
        }
    }

    impl WidgetImpl for PageText {
        fn snapshot(&self, snapshot: &gtk::Snapshot) {
            self.parent_snapshot(snapshot);
            let obj = self.obj();
            let (width, height) = (obj.width() as f32, obj.height() as f32);
            if obj.state_flags().contains(gtk::StateFlags::FOCUS_VISIBLE) {
                let accent = adw::StyleManager::default().accent_color_rgba();
                let ring = gsk::RoundedRect::from_rect(graphene::Rect::new(0.0, 0.0, width, height), 12.0);
                snapshot.append_border(&ring, &[2.0; 4], &[accent; 4]);
            }
            if !obj.has_focus() {
                return;
            }
            if let Some((x, top, bottom)) = obj.caret_rect() {
                snapshot.append_color(&obj.color(), &graphene::Rect::new(x as f32 - 1.0, top as f32, 2.0, (bottom - top).max(1.0) as f32));
            }
        }
    }

    impl DrawingAreaImpl for PageText {}

    impl AccessibleTextImpl for PageText {
        fn contents(&self, start: u32, end: u32) -> Option<glib::Bytes> {
            Some(glib::Bytes::from(self.reading().slice(start, end).as_bytes()))
        }

        fn contents_at(&self, offset: u32, granularity: gtk::AccessibleTextGranularity) -> Option<(u32, u32, glib::Bytes)> {
            let reading = self.reading();
            let (start, end) = reading.around(offset, unit(granularity));
            Some((start, end, glib::Bytes::from(reading.slice(start, end).as_bytes())))
        }

        fn caret_position(&self) -> u32 {
            self.caret.get().min(self.reading().len())
        }

        fn selection(&self) -> Vec<gtk::AccessibleTextRange> {
            // the pointer's selection is held in page points (pdf_text), not in this text's offsets: none told
            Vec::new()
        }

        fn attributes(&self, _offset: u32) -> Vec<(gtk::AccessibleTextRange, glib::GString, glib::GString)> {
            Vec::new()
        }

        fn default_attributes(&self) -> Vec<(glib::GString, glib::GString)> {
            Vec::new()
        }

        fn extents(&self, start: u32, end: u32) -> Option<graphene::Rect> {
            let [x, y, w, h] = self.reading().extents(start, end)?;
            let (left, top, scale) = self.obj().painted();
            if scale <= 0.0 {
                return None;
            }
            Some(graphene::Rect::new((left + x * scale) as f32, (top + y * scale) as f32, (w * scale) as f32, (h * scale) as f32))
        }

        fn offset(&self, point: &graphene::Point) -> Option<u32> {
            let (left, top, scale) = self.obj().painted();
            if scale <= 0.0 {
                return None;
            }
            self.reading().offset_at((f64::from(point.x()) - left) / scale, (f64::from(point.y()) - top) / scale)
        }

        fn set_caret_position(&self, position: u32) -> bool {
            self.obj().place(position);
            true
        }

        fn set_selection(&self, _selection: usize, _range: gtk::AccessibleTextRange) -> bool {
            false
        }
    }
}

glib::wrapper! {
    /// A PDF page's area over its picture: the highlight [crate::core_ui::pdf_text] draws, and the page's text for a
    /// screen reader.
    pub struct PageText(ObjectSubclass<imp::PageText>)
        @extends gtk::DrawingArea, gtk::Widget,
        @implements gtk::Accessible, gtk::AccessibleText, gtk::Buildable, gtk::ConstraintTarget;
}

impl PageText {
    /// The area of [page], the [index]th of the document, named [label] (its place among the pages), of the PDF the log
    /// calls [what].
    pub fn new(page: poppler::Page, index: usize, label: &str, what: What) -> Self {
        let this: Self = glib::Object::new();
        this.imp().page.replace(Some(page));
        this.imp().index.set(index);
        this.imp().what.set(what);
        this.update_property(&[gtk::accessible::Property::Label(label)]);
        this
    }

    /// The page's size in points.
    fn page_size(&self) -> (f64, f64) {
        self.imp().page.borrow().as_ref().map(|p| p.size()).unwrap_or((0.0, 0.0))
    }

    /// Where the page is painted in this area: its left, top and the scale from points to pixels.
    fn painted(&self) -> (f64, f64, f64) {
        let (page_width, page_height) = self.page_size();
        Frame { x: 0.0, y: 0.0, width: f64::from(self.width()), height: f64::from(self.height()), page_width, page_height }.painted()
    }

    /// The caret's line in this area's pixels: (x, top, bottom).
    fn caret_rect(&self) -> Option<(f64, f64, f64)> {
        let (x, top, bottom) = self.imp().reading().caret_at(self.imp().caret.get())?;
        let (left, ptop, scale) = self.painted();
        (scale > 0.0).then_some((left + x * scale, ptop + top * scale, ptop + bottom * scale))
    }

    /// The caret at [offset]: told to the screen reader, drawn, and scrolled into view where the page has the focus.
    pub fn place(&self, offset: u32) {
        let offset = offset.min(self.imp().reading().len());
        if self.imp().caret.replace(offset) != offset {
            self.update_caret_position();
        }
        self.queue_draw();
        if self.has_focus() {
            self.reveal();
        }
    }

    /// The caret at the character under the page's point (x, y) in points, where there is one.
    pub fn place_at(&self, x: f64, y: f64) {
        if let Some(offset) = self.imp().reading().offset_at(x, y) {
            self.place(offset);
        }
    }

    /// Scrolls the caret's line into view (the page's top where it has none): the column's viewport does not scroll to
    /// the focus ([crate::core_ui::pdf_text] turns it off), so Tab and the arrows would leave the view behind.
    fn reveal(&self) {
        let Some(scroll) = self.ancestor(gtk::ScrolledWindow::static_type()).and_downcast::<gtk::ScrolledWindow>() else { return };
        let Some(content) = scroll.child().and_then(|viewport| viewport.first_child()) else { return };
        let (top, bottom) = match self.caret_rect() {
            Some((_, top, bottom)) => (top, bottom),
            None => (0.0, f64::from(self.height()).min(scroll.vadjustment().page_size())),
        };
        let (Some(from), Some(to)) = (
            self.compute_point(&content, &graphene::Point::new(0.0, top as f32)),
            self.compute_point(&content, &graphene::Point::new(0.0, bottom as f32)),
        ) else {
            return;
        };
        let adjustment = scroll.vadjustment();
        let (value, page) = (adjustment.value(), adjustment.page_size());
        const MARGIN: f64 = 24.0;
        let (y0, y1) = (f64::from(from.y()), f64::from(to.y()));
        if y0 < value {
            adjustment.set_value((y0 - MARGIN).max(0.0));
        } else if y1 > value + page {
            adjustment.set_value(y1 - page + MARGIN);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reading(text: &str) -> PageReading {
        PageReading::new(text.to_owned(), Vec::new())
    }

    #[test]
    fn a_slice_counts_characters_not_bytes() {
        let r = reading("Grüße aus veyq\nZweite Zeile");
        assert_eq!(r.len(), 27);
        assert_eq!(r.slice(0, 5), "Grüße");
        assert_eq!(r.slice(6, 9), "aus");
        // past the end, or G_MAXUINT: to the end; a start past the end: nothing
        assert_eq!(r.slice(15, u32::MAX), "Zweite Zeile");
        assert_eq!(r.slice(40, u32::MAX), "");
        assert_eq!(r.slice(9, 6), "");
    }

    #[test]
    fn a_word_runs_to_the_next_words_start() {
        let r = reading("Hello veyq world\nSecond line");
        // on "veyq", at its start or within: the word and the space after it
        assert_eq!(r.around(6, Unit::Word), (6, 11));
        assert_eq!(r.around(7, Unit::Word), (6, 11));
        assert_eq!(r.slice(6, 11), "veyq ");
        // on the space after "veyq": still that word
        assert_eq!(r.around(10, Unit::Word), (6, 11));
        // the line's last word holds its line break
        assert_eq!(r.slice(r.around(12, Unit::Word).0, r.around(12, Unit::Word).1), "world\n");
        // the text's last word runs to its end
        assert_eq!(r.around(26, Unit::Word), (24, 28));
        assert_eq!(r.around(28, Unit::Word), (24, 28));
    }

    #[test]
    fn a_line_runs_to_the_next_lines_start() {
        let r = reading("Hello veyq world\nSecond line\nThird");
        assert_eq!(r.around(3, Unit::Line), (0, 17));
        assert_eq!(r.slice(0, 17), "Hello veyq world\n");
        assert_eq!(r.around(17, Unit::Line), (17, 29));
        assert_eq!(r.around(32, Unit::Line), (29, 34));
        // a paragraph is told as its line
        assert_eq!(unit(gtk::AccessibleTextGranularity::Paragraph), Unit::Line);
    }

    #[test]
    fn a_sentence_ends_at_its_stop() {
        let r = reading("It is sealed. Nobody reads it! Not even\nthe operator? Yes");
        assert_eq!(r.around(3, Unit::Sentence), (0, 14));
        assert_eq!(r.slice(14, 31), "Nobody reads it! ");
        assert_eq!(r.around(14, Unit::Sentence), (14, 31));
        assert_eq!(r.around(20, Unit::Sentence), (14, 31));
        // a line break inside a sentence does not end it
        assert_eq!(r.around(45, Unit::Sentence), (31, 54));
        assert_eq!(r.around(55, Unit::Sentence), (54, 57));
    }

    #[test]
    fn a_character_is_the_one_at_the_offset() {
        let r = reading("ab");
        assert_eq!(r.around(1, Unit::Character), (1, 2));
        assert_eq!(r.around(2, Unit::Character), (2, 2));
        assert_eq!(r.around(9, Unit::Character), (2, 2));
    }

    #[test]
    fn the_caret_moves_by_character_word_and_line() {
        let r = reading("Hello veyq world\nab\nSecond line");
        assert_eq!(r.moved(0, Move::Left), 0);
        assert_eq!(r.moved(0, Move::Right), 1);
        assert_eq!(r.moved(1, Move::WordRight), 6);
        assert_eq!(r.moved(6, Move::WordRight), 11);
        assert_eq!(r.moved(11, Move::WordLeft), 6);
        assert_eq!(r.moved(8, Move::WordLeft), 6);
        assert_eq!(r.moved(3, Move::WordLeft), 0);
        assert_eq!(r.moved(12, Move::LineStart), 0);
        assert_eq!(r.moved(3, Move::LineEnd), 16);
        // down from column 8: the short line's end, then column 2 of the next (the column the short line held it at)
        assert_eq!(r.moved(8, Move::Down), 19);
        assert_eq!(r.moved(19, Move::Down), 22);
        assert_eq!(r.moved(22, Move::Up), 19);
        assert_eq!(r.moved(19, Move::Up), 2);
        // past the first line up, the start; past the last down, the end
        assert_eq!(r.moved(4, Move::Up), 0);
        assert_eq!(r.moved(25, Move::Down), r.len());
        assert_eq!(r.moved(25, Move::Top), 0);
        assert_eq!(r.moved(3, Move::Bottom), r.len());
        // an empty page keeps its caret at 0
        assert_eq!(reading("").moved(0, Move::Down), 0);
    }

    #[test]
    fn boxes_give_extents_and_the_character_under_a_point() {
        // "ab\nc": a and b side by side, c on the next line
        let boxes = vec![[10.0, 10.0, 20.0, 30.0], [20.0, 10.0, 30.0, 30.0], [30.0, 10.0, 30.0, 30.0], [10.0, 40.0, 20.0, 60.0]];
        let r = PageReading::new("ab\nc".to_owned(), boxes);
        assert_eq!(r.extents(0, 2), Some([10.0, 10.0, 20.0, 20.0]));
        // the line break is left out, the next line taken in
        assert_eq!(r.extents(1, 4), Some([10.0, 10.0, 20.0, 50.0]));
        assert_eq!(r.extents(3, 3), None);
        assert_eq!(r.offset_at(25.0, 20.0), Some(1));
        assert_eq!(r.offset_at(15.0, 50.0), Some(3));
        assert_eq!(r.offset_at(15.0, 35.0), None);
        // the caret: before a character, or after the line's last one
        assert_eq!(r.caret_at(1), Some((20.0, 10.0, 30.0)));
        assert_eq!(r.caret_at(2), Some((30.0, 10.0, 30.0)));
        assert_eq!(r.caret_at(4), Some((20.0, 40.0, 60.0)));
    }

    #[test]
    fn boxes_that_do_not_match_the_text_are_dropped() {
        let r = PageReading::new("abc".to_owned(), vec![[0.0, 0.0, 1.0, 1.0]]);
        assert!(!r.has_boxes());
        assert_eq!(r.extents(0, 3), None);
        assert_eq!(r.offset_at(0.5, 0.5), None);
        assert_eq!(r.caret_at(0), None);
        // the text is still read
        assert_eq!(r.slice(0, 3), "abc");
    }

    #[test]
    fn poppler_gives_a_box_for_every_character_of_a_pages_text() {
        let pdf = crate::core_ui::pdf_text::tests::fixture();
        let r = read(&pdf.page(0).expect("page one"));
        assert!(r.has_boxes(), "poppler's layout matches its text one to one");
        let text = r.slice(0, u32::MAX).to_owned();
        assert_eq!(text.lines().map(str::trim).collect::<Vec<_>>(), ["Hello veyq world", "Second line here"]);
        // "veyq" stands from about 123 to 165 points, its baseline 92 points from the top
        let v = u32::try_from(text.find("veyq").expect("veyq")).unwrap();
        let [x, y, w, h] = r.extents(v, v + 4).expect("its box");
        assert!((115.0..130.0).contains(&x) && (155.0..175.0).contains(&(x + w)), "{x} {w}");
        assert!(y < 92.0 && y + h > 80.0, "{y} {h}");
        assert_eq!(r.offset_at(140.0, 85.0).map(|o| r.around(o, Unit::Word)), Some((v, v + 5)));
        // the page without a text layer reads as empty, and that is all
        let none = read(&pdf.page(2).expect("page three"));
        assert!(none.is_empty());
        assert_eq!(none.slice(0, u32::MAX), "");
    }
}
