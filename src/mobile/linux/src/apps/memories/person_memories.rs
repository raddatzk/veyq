//! Memories' section of a person's profile on Linux (ADR 0144 decisions 11–13; Windows' `PersonPage.cs`), under core's
//! frame ([crate::core_ui::person_page]), GNOME's way: "Memories with {name}", its loading, failure with *Try again*,
//! empty and partial words, and once something is listed a List | Tiles | Map toggle group (the account's view choice on
//! this device, ADR 0149 decision 8: the core opens it on that choice). The list is a boxed list of rows like the Memories
//! list's (cover, name, date, place, the group), newest first as the core orders them; the tiles the same Memories as a
//! grid ([crate::core_ui::tiles]); a row or a tile opens its Memory (`Shell.Open`). The map is ADR 0143's Memories map over
//! the person's located Memories (`Person.Map`, drawn by [crate::core_ui::raster_map]); a pin opens its Memory. Every
//! state is the core's (`PersonShown.memories`, `Person.Retry`, `Person.View`).
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, wire::Answer, wire::Call, wire::MapStatus, wire::MemoriesView, wire::PersonMemoriesShown, wire::PersonRowShown,
    wire::PersonShown};
use crate::core_ui::home::{cover_prefix, glib_escape, kind_icon};
use crate::core_ui::raster_map::RasterMap;
use crate::core_ui::registry::PersonSection;
use crate::core_ui::tiles::{self, Tile};
use crate::core_ui::{said, words};

/// The section on a person's page, drawn from each of its states.
pub struct PersonMemories {
    inner: Rc<Inner>,
}

struct Inner {
    root: gtk::Box,
    heading: gtk::Label,
    switch: adw::ToggleGroup,
    spinner: adw::Spinner,
    status: gtk::Label,
    retry: gtk::Button,
    partial: gtk::Label,
    list: gtk::ListBox,
    /// The tiles' place: the same rows as a grid, drawn anew with them.
    tiles: gtk::Box,
    map_slot: gtk::Overlay,
    map_empty: gtk::Box,
    map: RefCell<Option<RasterMap>>,
    /// Whether the map is wanted (asked or shown).
    map_on: Cell<bool>,
    /// The person's model in the core, as the page's frame last drew it.
    id: Cell<Option<i64>>,
    rows: RefCell<Vec<PersonRowShown>>,
    drawing: Cell<bool>,
    window: gtk::Window,
}

/// A row's second line: its date, its place and the group it lies in, as the Memories list says each.
pub fn row_subtitle(row: &PersonRowShown) -> String {
    let date = row.date.as_ref().and_then(crate::core_ui::said::date_text);
    let group = row.group_name.as_ref().map(said::piece);
    [date, row.place.clone(), group].into_iter().flatten().filter(|s| !s.is_empty()).collect::<Vec<_>>().join(" · ")
}

impl PersonMemories {
    /// The section over [window].
    pub fn new(window: &gtk::Window) -> Self {
        let root = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).build();
        let head = gtk::Box::builder().spacing(12).margin_top(18).build();
        let heading = gtk::Label::builder().xalign(0.0).hexpand(true).wrap(true).build();
        heading.add_css_class("title-4");
        heading.set_widget_name("person-memories");
        let switch = adw::ToggleGroup::builder().visible(false).valign(gtk::Align::Center).build();
        switch.set_widget_name("person-view");
        for (name, icon, tip) in [
            ("list", "view-list-symbolic", words::node_view_list()),
            ("tiles", "view-grid-symbolic", words::node_view_tiles()),
            ("map", "mark-location-symbolic", words::memory_view_map()),
        ] {
            switch.add(adw::Toggle::builder().name(name).icon_name(icon).tooltip(tip).build());
        }
        switch.set_active_name(Some("list"));
        head.append(&heading);
        head.append(&switch);
        root.append(&head);
        let spinner = adw::Spinner::builder().width_request(32).height_request(32).halign(gtk::Align::Center).visible(false).build();
        let status = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
        status.set_widget_name("person-status");
        let retry = gtk::Button::builder().label(words::memory_people_retry()).halign(gtk::Align::Start).visible(false).build();
        let partial = gtk::Label::builder().label(words::memory_people_partial()).wrap(true).xalign(0.0).visible(false).build();
        partial.add_css_class("dim-label");
        let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).visible(false).build();
        list.add_css_class("boxed-list");
        list.set_widget_name("person-rows");
        let tiles = gtk::Box::builder().orientation(gtk::Orientation::Vertical).visible(false).build();
        tiles.set_widget_name("person-tiles");
        let map_slot = gtk::Overlay::builder().height_request(420).visible(false).build();
        let map_empty = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).halign(gtk::Align::Center).valign(gtk::Align::End)
            .margin_bottom(36).visible(false).can_target(false).build();
        map_empty.add_css_class("osd");
        map_empty.add_css_class("toolbar");
        let empty_title = gtk::Label::builder().label(words::memories_map_empty()).build();
        empty_title.add_css_class("heading");
        map_empty.append(&empty_title);
        map_slot.add_overlay(&map_empty);
        for w in [spinner.upcast_ref::<gtk::Widget>(), status.upcast_ref(), retry.upcast_ref(), partial.upcast_ref(), list.upcast_ref(), tiles.upcast_ref(), map_slot.upcast_ref()] {
            root.append(w);
        }
        let inner = Rc::new(Inner {
            root,
            heading,
            switch,
            spinner,
            status,
            retry,
            partial,
            list,
            tiles,
            map_slot,
            map_empty,
            map: RefCell::new(None),
            map_on: Cell::new(false),
            id: Cell::new(None),
            rows: RefCell::new(Vec::new()),
            drawing: Cell::new(false),
            window: window.clone(),
        });
        Inner::connect(&inner);
        Self { inner }
    }
}

impl PersonSection for PersonMemories {
    fn widget(&self) -> gtk::Widget {
        self.inner.root.clone().upcast()
    }

    fn draw(&self, person: i64, s: &PersonShown) {
        let i = &self.inner;
        i.id.set(Some(person));
        let name = s.name.clone().unwrap_or_default();
        i.heading.set_label(&if s.name.is_some() { words::memory_people_with_name(&name) } else { words::memory_people_with() });
        match &s.memories {
            Some(m) => i.draw_memories(m),
            None => {
                for w in [i.heading.upcast_ref::<gtk::Widget>(), i.switch.upcast_ref(), i.list.upcast_ref(), i.tiles.upcast_ref(), i.map_slot.upcast_ref(), i.status.upcast_ref()] {
                    w.set_visible(false);
                }
            }
        }
    }
}

impl Drop for PersonMemories {
    fn drop(&mut self) {
        self.inner.map.borrow_mut().take();
    }
}

impl Inner {
    fn connect(this: &Rc<Self>) {
        let weak = Rc::downgrade(this);
        this.retry.connect_clicked(move |_| {
            if let Some(i) = weak.upgrade()
                && let Some(person) = i.id.get()
            {
                bridge::send(Call::PersonRetry { person });
            }
        });
        let weak = Rc::downgrade(this);
        this.switch.connect_active_name_notify(move |s| {
            let Some(i) = weak.upgrade() else { return };
            if i.drawing.get() {
                return;
            }
            let Some(person) = i.id.get() else { return };
            let view = match s.active_name().as_deref() {
                Some("map") => MemoriesView::Map,
                Some("tiles") => MemoriesView::Tiles,
                _ => MemoriesView::List,
            };
            eprintln!("veyq: person view {view:?}");
            bridge::send(Call::PersonView { person, view });
        });
    }

    fn draw_memories(self: &Rc<Self>, m: &PersonMemoriesShown) {
        eprintln!("veyq: person's memories: loading {}, failed {}, empty {}, partial {}, views {}, {:?}, {} rows, {} located",
            m.loading, m.failed, m.empty, m.partial, m.offers_views, m.view, m.rows.len(), m.located);
        self.drawing.set(true);
        self.spinner.set_visible(m.loading);
        self.retry.set_visible(m.failed);
        let status = if m.loading {
            Some(words::memory_people_loading())
        } else if m.failed {
            Some(words::memory_people_error())
        } else if m.empty {
            Some(words::memory_people_empty())
        } else {
            None
        };
        self.status.set_visible(status.is_some());
        self.status.set_label(&status.unwrap_or_default());
        self.partial.set_visible(m.partial);
        self.switch.set_visible(m.offers_views);
        self.switch.set_active_name(Some(match m.view {
            MemoriesView::Map => "map",
            MemoriesView::Tiles => "tiles",
            MemoriesView::List => "list",
        }));
        self.drawing.set(false);
        let map = m.view == MemoriesView::Map && m.offers_views;
        let tiles = m.view == MemoriesView::Tiles && m.offers_views;
        self.list.set_visible(!map && !tiles && !m.rows.is_empty());
        self.tiles.set_visible(tiles && !m.rows.is_empty());
        self.map_slot.set_visible(map);
        self.map_empty.set_visible(map && m.located == 0);
        if *self.rows.borrow() != m.rows {
            *self.rows.borrow_mut() = m.rows.clone();
            while let Some(c) = self.list.first_child() {
                self.list.remove(&c);
            }
            for r in &m.rows {
                let row = adw::ActionRow::builder().title(glib_escape(&r.r#ref.name)).activatable(true).build();
                let subtitle = row_subtitle(r);
                if !subtitle.is_empty() {
                    row.set_subtitle(&glib_escape(&subtitle));
                }
                row.add_prefix(&cover_prefix(gtk::Image::from_icon_name(kind_icon(&r.r#ref.kind)), &r.r#ref.node_id));
                row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
                let open = r.r#ref.clone();
                row.connect_activated(move |_| {
                    eprintln!("veyq: a person's Memory opened");
                    bridge::send(Call::ShellOpen { r#ref: open.clone() });
                });
                self.list.append(&row);
            }
            while let Some(c) = self.tiles.first_child() {
                self.tiles.remove(&c);
            }
            let tiles = m.rows.iter().map(|r| {
                let open = r.r#ref.clone();
                let subtitle = row_subtitle(r);
                Tile { name: r.r#ref.name.clone(), subtitle: (!subtitle.is_empty()).then_some(subtitle), dated: false, icon: kind_icon(&r.r#ref.kind),
                    node_id: r.r#ref.node_id.clone(), open: Box::new(move || {
                        eprintln!("veyq: a person's Memory opened");
                        bridge::send(Call::ShellOpen { r#ref: open.clone() });
                    }) }
            }).collect();
            self.tiles.append(&tiles::grid(tiles));
        }
        if self.map_on.replace(map) != map {
            if map { self.open_map() } else { self.close_map() }
        }
    }

    /// The person's Memories map (`Person.Map`), its pins opening their Memories.
    fn open_map(self: &Rc<Self>) {
        let Some(person) = self.id.get() else { return };
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let id = match bridge::call(&Call::PersonMap { person }).await.map(|(a, _)| a) {
                Ok(Answer::MapOpened { map }) => map,
                other => return eprintln!("veyq: Person.Map answered {other:?}"),
            };
            let Some(i) = weak.upgrade() else { return bridge::send(Call::MapClose { map: id }) };
            if i.map.borrow().is_some() || !i.map_on.get() {
                return bridge::send(Call::MapClose { map: id });
            }
            eprintln!("veyq: person's map open ({id})");
            let m = RasterMap::open(id, 420, true, &i.window);
            m.widget().set_vexpand(true);
            let empty = i.map_empty.downgrade();
            m.on_changed(move |s| {
                if let Some(e) = empty.upgrade()
                    && let Some(s) = s
                    && matches!(s.state, MapStatus::MapStateShown)
                    && s.view.is_some()
                {
                    e.set_visible(s.clusters.is_empty());
                }
            });
            i.map_slot.set_child(Some(m.widget()));
            *i.map.borrow_mut() = Some(m);
        });
    }

    fn close_map(&self) {
        self.map_slot.set_child(None::<&gtk::Widget>);
        self.map.borrow_mut().take();
    }
}
