//! A gallery's filter (ADR 0149 decision 5; the web's `galleryFilter.ts`, `uc.memory.gallery` step 8), GNOME's way: a
//! `gtk::SearchBar` over the photos — its entry the free text over the originals' names, the core's suggestions for that
//! text as pill buttons under it (a day, a year, a place, a kind; Enter takes the first), the chips chosen as pills with their
//! remove, and beside the entry the marks' mode as a drop-down (All | Everyone's | Only mine) where the core offers it.
//! Every rule is the core's (`Filter.Text|Chip|Remove|Mode|Clear`, `GalleryShown.filter`): the grid and the lightbox show
//! what it keeps. It is stored nowhere; leaving the page clears it.
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;

use crate::bridge::{self, wire::Call, wire::ChipShown, wire::FilterShown, wire::MarkMode};
use crate::core_ui::typed::Typed;
use crate::core_ui::words;

/// The marks' modes in the drop-down's order.
const MODES: [MarkMode; 3] = [MarkMode::All, MarkMode::Everyone, MarkMode::Mine];

/// The filter over one gallery's photos.
pub struct FilterBar {
    inner: Rc<Inner>,
}

struct Inner {
    bar: gtk::SearchBar,
    entry: gtk::SearchEntry,
    mode: gtk::DropDown,
    chips: gtk::FlowBox,
    suggestions: gtk::FlowBox,
    gallery: Cell<Option<i64>>,
    shown: RefCell<Option<FilterShown>>,
    typed: RefCell<Typed>,
    /// While the state is set on the widgets: their own signals send nothing.
    drawing: Cell<bool>,
}

impl FilterBar {
    pub fn new() -> Self {
        let entry = gtk::SearchEntry::builder().hexpand(true).placeholder_text(words::media_filter_placeholder()).build();
        entry.update_property(&[gtk::accessible::Property::Label(&words::media_filter_label())]);
        entry.set_widget_name("gallery-filter");
        let names = [words::media_marks_filter_all(), words::media_marks_filter_everyone(), words::media_marks_filter_mine()];
        let mode = gtk::DropDown::from_strings(&names.iter().map(String::as_str).collect::<Vec<_>>());
        mode.set_tooltip_text(Some(&words::media_marks_filter_label()));
        mode.update_property(&[gtk::accessible::Property::Label(&words::media_marks_filter_label())]);
        mode.set_widget_name("gallery-marks-mode");
        mode.set_visible(false);
        let line = gtk::Box::builder().spacing(6).build();
        line.append(&entry);
        line.append(&mode);
        let flow = || gtk::FlowBox::builder().selection_mode(gtk::SelectionMode::None).column_spacing(6).row_spacing(6).max_children_per_line(30).visible(false).build();
        let (chips, suggestions) = (flow(), flow());
        chips.set_widget_name("gallery-filter-chips");
        suggestions.set_widget_name("gallery-filter-suggestions");
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).hexpand(true).build();
        column.append(&line);
        column.append(&suggestions);
        column.append(&chips);
        let clamp = adw::Clamp::builder().maximum_size(720).child(&column).build();
        let bar = gtk::SearchBar::builder().child(&clamp).search_mode_enabled(true).show_close_button(false).visible(false).build();
        let inner = Rc::new(Inner {
            bar,
            entry,
            mode,
            chips,
            suggestions,
            gallery: Cell::new(None),
            shown: RefCell::new(None),
            typed: RefCell::new(Typed::default()),
            drawing: Cell::new(false),
        });
        Inner::connect(&inner);
        Self { inner }
    }

    pub fn widget(&self) -> &gtk::SearchBar {
        &self.inner.bar
    }

    /// The core's filter of gallery [gallery] (none: not offered here — a medium's facts are not open to whoever looks).
    pub fn draw(&self, filter: Option<&FilterShown>, gallery: i64) {
        self.inner.gallery.set(Some(gallery));
        self.inner.draw(filter);
    }
}

impl Default for FilterBar {
    fn default() -> Self {
        Self::new()
    }
}

impl Inner {
    fn connect(this: &Rc<Self>) {
        let weak = Rc::downgrade(this);
        this.entry.connect_search_changed(move |e| {
            let Some(i) = weak.upgrade() else { return };
            if i.drawing.get() {
                return;
            }
            let Some(gallery) = i.gallery.get() else { return };
            let text = e.text().to_string();
            i.typed.borrow_mut().sent(&text);
            bridge::send(Call::FilterText { gallery, text });
        });
        // Enter takes the first suggestion, as a search's chips do
        let weak = Rc::downgrade(this);
        this.entry.connect_activate(move |_| {
            let Some(i) = weak.upgrade() else { return };
            let first = i.shown.borrow().as_ref().and_then(|f| f.suggestions.first().map(|c| c.chip.clone()));
            if let (Some(chip), Some(gallery)) = (first, i.gallery.get()) {
                eprintln!("veyq: filter chip taken (Enter)");
                bridge::send(Call::FilterChip { gallery, chip });
            }
        });
        // Escape in the entry clears the whole filter (GNOME's search: Escape leaves it)
        let weak = Rc::downgrade(this);
        this.entry.connect_stop_search(move |_| {
            if let Some(i) = weak.upgrade()
                && let Some(gallery) = i.gallery.get()
            {
                eprintln!("veyq: filter cleared");
                bridge::send(Call::FilterClear { gallery });
            }
        });
        let weak = Rc::downgrade(this);
        this.mode.connect_selected_notify(move |d| {
            let Some(i) = weak.upgrade() else { return };
            if i.drawing.get() {
                return;
            }
            let (Some(gallery), Some(mode)) = (i.gallery.get(), MODES.get(d.selected() as usize).copied()) else { return };
            eprintln!("veyq: filter marks mode {mode:?}");
            bridge::send(Call::FilterMode { gallery, mode });
        });
    }

    fn draw(self: &Rc<Self>, filter: Option<&FilterShown>) {
        let changed = self.shown.borrow().as_ref() != filter;
        if !changed {
            return;
        }
        *self.shown.borrow_mut() = filter.cloned();
        self.bar.set_visible(filter.is_some_and(|f| f.offered));
        let Some(f) = filter else { return };
        eprintln!("veyq: filter shown: {} of {}, mode {:?} (offered {}), {} chips, {} suggestions", f.shown, f.total, f.mode, f.mode_offered, f.chips.len(), f.suggestions.len());
        self.drawing.set(true);
        let focused = self.entry.has_focus() || self.entry.state_flags().contains(gtk::StateFlags::FOCUS_WITHIN);
        if let Some(text) = self.typed.borrow_mut().take(focused, &self.entry.text(), &f.text) {
            self.entry.set_text(&text);
            self.entry.set_position(-1);
        }
        self.mode.set_visible(f.mode_offered);
        self.mode.set_selected(MODES.iter().position(|m| *m == f.mode).unwrap_or(0) as u32);
        self.drawing.set(false);
        self.chips.remove_all();
        for c in &f.chips {
            self.chips.append(&self.chip(c));
        }
        self.chips.set_visible(!f.chips.is_empty());
        self.suggestions.remove_all();
        for c in &f.suggestions {
            self.suggestions.append(&self.suggestion(c));
        }
        self.suggestions.set_visible(!f.suggestions.is_empty());
    }

    /// A chip chosen: its label and its remove, as a pill.
    fn chip(self: &Rc<Self>, c: &ChipShown) -> gtk::Widget {
        let label = label_of(c);
        let content = adw::ButtonContent::builder().label(&label).icon_name("window-close-symbolic").build();
        let b = gtk::Button::builder().child(&content).tooltip_text(&label).build();
        b.add_css_class("pill");
        b.add_css_class("small");
        let weak = Rc::downgrade(self);
        let chip = c.chip.clone();
        b.connect_clicked(move |_| {
            if let Some(i) = weak.upgrade()
                && let Some(gallery) = i.gallery.get()
            {
                eprintln!("veyq: filter chip removed");
                bridge::send(Call::FilterRemove { gallery, chip: chip.clone() });
            }
        });
        b.upcast()
    }

    /// A suggestion for the text: the chip it would add.
    fn suggestion(self: &Rc<Self>, c: &ChipShown) -> gtk::Widget {
        let b = gtk::Button::builder().label(label_of(c)).build();
        b.add_css_class("pill");
        b.add_css_class("small");
        let weak = Rc::downgrade(self);
        let chip = c.chip.clone();
        b.connect_clicked(move |_| {
            if let Some(i) = weak.upgrade()
                && let Some(gallery) = i.gallery.get()
            {
                eprintln!("veyq: filter chip taken");
                bridge::send(Call::FilterChip { gallery, chip: chip.clone() });
            }
        });
        b.upcast()
    }
}

/// A chip as it reads: a day the desktop's way (the core labels it by its ISO day in the device's zone, `day:<iso>`, inbox
/// #989), any other by the core's label.
pub fn label_of(c: &ChipShown) -> String {
    match c.chip.strip_prefix("day:") {
        Some(iso) => crate::core_ui::said::day(iso),
        None => c.label.clone(),
    }
}

/// What an empty grid says while a filter keeps nothing (E5: an empty grid never reads as an empty Memory): the marks'
/// own words where the mode alone keeps nothing, else every filter in force named.
pub fn empty_text(f: &FilterShown) -> Option<String> {
    let narrowed = !f.chips.is_empty() || !f.text.trim().is_empty() || f.mode != MarkMode::All;
    if !narrowed || f.shown > 0 || f.total == 0 {
        return None;
    }
    if f.chips.is_empty() && f.text.trim().is_empty() {
        return Some(if f.mode == MarkMode::Mine { words::media_marks_empty_mine() } else { words::media_marks_empty_everyone() });
    }
    let mut parts: Vec<String> = f.chips.iter().map(label_of).collect();
    if !f.text.trim().is_empty() {
        parts.push(words::media_filter_text(f.text.trim()));
    }
    match f.mode {
        MarkMode::Mine => parts.push(words::media_filter_marks_mine()),
        MarkMode::Everyone => parts.push(words::media_filter_marks_everyone()),
        MarkMode::All => {}
    }
    Some(words::media_filter_empty(&parts.join(", ")))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn filter(chips: &[&str], text: &str, mode: MarkMode, shown: i32) -> FilterShown {
        FilterShown {
            offered: true,
            mode,
            mode_offered: true,
            chips: chips.iter().map(|c| ChipShown { chip: format!("k:{c}"), label: (*c).into(), rest: None }).collect(),
            text: text.into(),
            suggestions: vec![],
            shown,
            total: 5,
        }
    }

    /// A day chip reads the desktop's way; any other as the core labels it.
    #[test]
    fn a_day_chip_reads_the_desktops_way() {
        let day = ChipShown { chip: "day:2024-05-03".into(), label: "2024-05-03".into(), rest: None };
        assert_eq!(label_of(&day), crate::core_ui::said::day("2024-05-03"));
        let place = ChipShown { chip: "place:berlin".into(), label: "Berlin".into(), rest: None };
        assert_eq!(label_of(&place), "Berlin");
    }

    /// An empty grid under a filter says why (E5): the mode alone in the marks' words, else every filter in force named.
    #[test]
    fn an_empty_filtered_grid_names_what_keeps_nothing() {
        assert_eq!(empty_text(&filter(&[], "", MarkMode::All, 0)), None, "no filter: the gallery's own empty");
        assert_eq!(empty_text(&filter(&["2024"], "", MarkMode::All, 3)), None, "something shown");
        assert_eq!(empty_text(&filter(&[], "", MarkMode::Mine, 0)), Some(words::media_marks_empty_mine()));
        assert_eq!(empty_text(&filter(&[], "", MarkMode::Everyone, 0)), Some(words::media_marks_empty_everyone()));
        assert_eq!(
            empty_text(&filter(&["2024", "Berlin"], " beach ", MarkMode::Mine, 0)),
            Some(words::media_filter_empty(&format!("2024, Berlin, {}, {}", words::media_filter_text("beach"), words::media_filter_marks_mine())))
        );
    }
}
