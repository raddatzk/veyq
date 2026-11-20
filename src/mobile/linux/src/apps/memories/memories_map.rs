//! The Memories bar's Map tab on Linux (ADR 0143 decision 6; Windows' Map tab in `ShellView`, Apple's `MemoriesMapTab`):
//! every Memory of the application's list that has a place, a pin each with its cover, clustered by the core at the zoom
//! shown (`Map.OpenMemories`, drawn by [crate::core_ui::raster_map]); a pin opens its Memory, a cluster is framed, and at the
//! highest zoom the Memories sharing one spot are listed. Where none has a place yet the map says so over itself. The
//! bar's places stand above it as the Pot's do (GNOME's inline view switcher, [crate::core_ui::places::places]).
use std::cell::RefCell;
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, wire::Answer, wire::BarTab, wire::Call, wire::MapStatus, wire::ShownTab};
use crate::core_ui::raster_map::RasterMap;
use crate::core_ui::words;

/// The Map tab on screen: its map opened in the core when made, closed when dropped.
pub struct MemoriesMap {
    root: gtk::Box,
    places: RefCell<(Vec<ShownTab>, Option<BarTab>, Option<gtk::Widget>)>,
    map: Rc<RefCell<Option<RasterMap>>>,
}

impl MemoriesMap {
    pub fn open(window: &gtk::Window) -> Self {
        let root = gtk::Box::builder().orientation(gtk::Orientation::Vertical).vexpand(true).build();
        let overlay = gtk::Overlay::builder().vexpand(true).hexpand(true).margin_start(12).margin_end(12).margin_bottom(12).margin_top(6).build();
        let empty = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).halign(gtk::Align::Center).valign(gtk::Align::End)
            .margin_bottom(36).visible(false).can_target(false).build();
        empty.add_css_class("osd");
        empty.add_css_class("toolbar");
        let title = gtk::Label::builder().label(words::memories_map_empty()).build();
        title.add_css_class("heading");
        let hint = gtk::Label::builder().label(words::memories_map_empty_hint()).wrap(true).max_width_chars(40).justify(gtk::Justification::Center).build();
        empty.append(&title);
        empty.append(&hint);
        overlay.add_overlay(&empty);
        root.append(&overlay);
        let map = Rc::new(RefCell::new(None));
        let (held, window, slot, empty) = (Rc::downgrade(&map), window.clone(), overlay.downgrade(), empty.downgrade());
        glib::spawn_future_local(async move {
            let id = match bridge::call(&Call::MapOpenMemories).await.map(|(a, _)| a) {
                Ok(Answer::MapOpened { map }) => map,
                other => return eprintln!("veyq: Map.OpenMemories answered {other:?}"),
            };
            let (Some(held), Some(slot)) = (held.upgrade(), slot.upgrade()) else { return bridge::send(Call::MapClose { map: id }) };
            eprintln!("veyq: memories map open ({id})");
            let m = RasterMap::open(id, 300, true, &window);
            m.widget().set_vexpand(true);
            m.on_changed(move |s| {
                if let Some(e) = empty.upgrade() {
                    e.set_visible(s.is_some_and(|s| matches!(s.state, MapStatus::MapStateShown) && s.clusters.is_empty() && s.view.is_some()));
                }
            });
            slot.set_child(Some(m.widget()));
            *held.borrow_mut() = Some(m);
        });
        Self { root, places: RefCell::new((Vec::new(), None, None)), map }
    }

    pub fn widget(&self) -> &gtk::Box {
        &self.root
    }

    /// The bar's places above the map, drawn anew only when they changed.
    pub fn places(&self, tabs: &[ShownTab], selected: Option<&BarTab>) {
        let mut p = self.places.borrow_mut();
        if p.0 == tabs && p.1.as_ref() == selected {
            return;
        }
        if let Some(old) = p.2.take() {
            self.root.remove(&old);
        }
        let bar = crate::core_ui::places::places(tabs, selected);
        self.root.prepend(&bar);
        *p = (tabs.to_vec(), selected.cloned(), Some(bar));
    }
}

impl Drop for MemoriesMap {
    fn drop(&mut self) {
        // the map closes in the core as its RasterMap goes
        self.map.borrow_mut().take();
    }
}
