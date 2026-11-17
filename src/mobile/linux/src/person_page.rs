//! A person's profile on Linux (ADR 0144 decisions 11–13; Windows' `PersonPage.cs`): a page of the shell while its state
//! names a person (`Shell.person`), GNOME's way — their avatar at its largest, their name, the `~username` line under a real
//! name, a former member's hint, then Memories' section: "Memories with {name}", its loading, failure with *Try again*,
//! empty and partial words, and once something is listed a List | Map toggle group (not stored: it opens on the list).
//! The list is a boxed list of rows like the Memories list's (cover, name, date, place, the group), newest first as the
//! core orders them; a row opens its Memory (`Shell.Open`). The map is ADR 0143's Memories map over the person's located
//! Memories (`Person.Map`, drawn by [crate::raster_map]); a pin opens its Memory. Every state is the core's
//! (`Person.Open`, `Watched.Person`).
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::MapStatus, wire::MemoriesView, wire::PersonMemoriesShown,
    wire::PersonRowShown, wire::PersonShown, wire::Update, wire::Watched};
use crate::home::{cover_prefix, glib_escape, kind_icon};
use crate::raster_map::RasterMap;
use crate::{avatars, said, words};

/// A person's page on screen: opened in the core when made, closed when dropped.
pub struct PersonPage {
    pub user_id: String,
    inner: Rc<Inner>,
}

struct Inner {
    root: gtk::ScrolledWindow,
    avatar_slot: gtk::Box,
    name: gtk::Label,
    username: gtk::Label,
    hint: gtk::Label,
    heading: gtk::Label,
    switch: adw::ToggleGroup,
    spinner: adw::Spinner,
    status: gtk::Label,
    retry: gtk::Button,
    partial: gtk::Label,
    list: gtk::ListBox,
    map_slot: gtk::Overlay,
    map_empty: gtk::Box,
    map: RefCell<Option<RasterMap>>,
    /// Whether the map is wanted (asked or shown).
    map_on: Cell<bool>,
    id: Cell<Option<i64>>,
    rows: RefCell<Vec<PersonRowShown>>,
    drawn_avatar: RefCell<Option<(String, bool)>>,
    drawing: Cell<bool>,
    watch: RefCell<Option<Watch>>,
    window: gtk::Window,
}

/// A row's second line: its date, its place and the group it lies in, as the Memories list says each.
pub fn row_subtitle(row: &PersonRowShown) -> String {
    let date = row.date.as_ref().and_then(crate::memory_facts::date_text);
    let group = row.group_name.as_ref().map(said::piece);
    [date, row.place.clone(), group].into_iter().flatten().filter(|s| !s.is_empty()).collect::<Vec<_>>().join(" · ")
}

impl PersonPage {
    /// Person [user_id]'s page over [window].
    pub fn open(user_id: &str, window: &gtk::Window) -> Self {
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).margin_top(24).margin_bottom(24).margin_start(12).margin_end(12).build();
        let avatar_slot = gtk::Box::builder().halign(gtk::Align::Center).build();
        let name = gtk::Label::builder().wrap(true).justify(gtk::Justification::Center).build();
        name.add_css_class("title-1");
        name.set_widget_name("person-name");
        let username = gtk::Label::builder().visible(false).build();
        username.add_css_class("dim-label");
        let hint = gtk::Label::builder().label(words::people_former_member_hint()).visible(false).build();
        hint.add_css_class("dim-label");
        for w in [avatar_slot.upcast_ref::<gtk::Widget>(), name.upcast_ref(), username.upcast_ref(), hint.upcast_ref()] {
            column.append(w);
        }
        let head = gtk::Box::builder().spacing(12).margin_top(18).build();
        let heading = gtk::Label::builder().xalign(0.0).hexpand(true).wrap(true).build();
        heading.add_css_class("title-4");
        heading.set_widget_name("person-memories");
        let switch = adw::ToggleGroup::builder().visible(false).valign(gtk::Align::Center).build();
        switch.set_widget_name("person-view");
        for (name, icon, tip) in [("list", "view-list-symbolic", words::node_view_list()), ("map", "mark-location-symbolic", words::memory_view_map())] {
            switch.add(adw::Toggle::builder().name(name).icon_name(icon).tooltip(tip).build());
        }
        switch.set_active_name(Some("list"));
        head.append(&heading);
        head.append(&switch);
        column.append(&head);
        let spinner = adw::Spinner::builder().width_request(32).height_request(32).halign(gtk::Align::Center).visible(false).build();
        let status = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
        status.set_widget_name("person-status");
        let retry = gtk::Button::builder().label(words::memory_people_retry()).halign(gtk::Align::Start).visible(false).build();
        let partial = gtk::Label::builder().label(words::memory_people_partial()).wrap(true).xalign(0.0).visible(false).build();
        partial.add_css_class("dim-label");
        let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).visible(false).build();
        list.add_css_class("boxed-list");
        list.set_widget_name("person-rows");
        let map_slot = gtk::Overlay::builder().height_request(420).visible(false).build();
        let map_empty = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).halign(gtk::Align::Center).valign(gtk::Align::End)
            .margin_bottom(36).visible(false).can_target(false).build();
        map_empty.add_css_class("osd");
        map_empty.add_css_class("toolbar");
        let empty_title = gtk::Label::builder().label(words::memories_map_empty()).build();
        empty_title.add_css_class("heading");
        map_empty.append(&empty_title);
        map_slot.add_overlay(&map_empty);
        for w in [spinner.upcast_ref::<gtk::Widget>(), status.upcast_ref(), retry.upcast_ref(), partial.upcast_ref(), list.upcast_ref(), map_slot.upcast_ref()] {
            column.append(w);
        }
        let clamp = adw::Clamp::builder().maximum_size(720).child(&column).build();
        let root = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build();
        let inner = Rc::new(Inner {
            root,
            avatar_slot,
            name,
            username,
            hint,
            heading,
            switch,
            spinner,
            status,
            retry,
            partial,
            list,
            map_slot,
            map_empty,
            map: RefCell::new(None),
            map_on: Cell::new(false),
            id: Cell::new(None),
            rows: RefCell::new(Vec::new()),
            drawn_avatar: RefCell::new(None),
            drawing: Cell::new(false),
            watch: RefCell::new(None),
            window: window.clone(),
        });
        Inner::connect(&inner);
        let (weak, user) = (Rc::downgrade(&inner), user_id.to_owned());
        glib::spawn_future_local(async move {
            let person = match bridge::call(&Call::PersonOpen { user_id: user }).await.map(|(a, _)| a) {
                Ok(Answer::PersonOpened { person }) => person,
                other => return eprintln!("veyq: Person.Open answered {other:?}"),
            };
            let Some(inner) = weak.upgrade() else { return bridge::send(Call::PersonClose { person }) };
            inner.id.set(Some(person));
            eprintln!("veyq: person's page open");
            let Some((watch, updates)) = bridge::watch(&Watched::Person { person }) else { return };
            *inner.watch.borrow_mut() = Some(watch);
            drop(inner);
            while let Ok(update) = updates.recv().await {
                let Some(inner) = weak.upgrade() else { return };
                match update {
                    Ok(Update::Person { shown: Some(s), .. }) => inner.draw(&s),
                    Ok(Update::Person { shown: None, .. }) => {
                        inner.id.set(None);
                        return;
                    }
                    Ok(_) => {}
                    Err(e) => eprintln!("veyq: a person update did not read: {:?}", e.0),
                }
            }
        });
        Self { user_id: user_id.to_owned(), inner }
    }

    pub fn widget(&self) -> &gtk::ScrolledWindow {
        &self.inner.root
    }
}

impl Drop for PersonPage {
    fn drop(&mut self) {
        self.inner.map.borrow_mut().take();
        self.inner.watch.borrow_mut().take();
        if let Some(person) = self.inner.id.take() {
            bridge::send(Call::PersonClose { person });
        }
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
            let view = if s.active_name().as_deref() == Some("map") { MemoriesView::Map } else { MemoriesView::List };
            eprintln!("veyq: person view {view:?}");
            bridge::send(Call::PersonView { person, view });
        });
    }

    fn draw(self: &Rc<Self>, s: &PersonShown) {
        let name = s.name.clone().unwrap_or_else(|| if s.former { words::memory_participants_former() } else { words::people_unknown_member() });
        eprintln!("veyq: person shown: named {}, former {}, picture {}, username {}, sections {:?}", s.name.is_some(), s.former, s.picture,
            s.username.is_some(), s.sections);
        self.name.set_label(&name);
        self.username.set_visible(s.username.is_some());
        self.username.set_label(s.username.as_deref().unwrap_or_default());
        self.hint.set_visible(s.former);
        let avatar_key = (name.clone(), s.picture);
        if self.drawn_avatar.replace(Some(avatar_key.clone())) != Some(avatar_key) {
            while let Some(c) = self.avatar_slot.first_child() {
                self.avatar_slot.remove(&c);
            }
            self.avatar_slot.append(&avatars::avatar(&name, s.picture.then_some(s.user_id.as_str()), 128));
        }
        self.heading.set_label(&if s.name.is_some() { words::memory_people_with_name(&name) } else { words::memory_people_with() });
        match &s.memories {
            Some(m) => self.draw_memories(m),
            None => {
                for w in [self.heading.upcast_ref::<gtk::Widget>(), self.switch.upcast_ref(), self.list.upcast_ref(), self.map_slot.upcast_ref(), self.status.upcast_ref()] {
                    w.set_visible(false);
                }
            }
        }
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
        self.switch.set_active_name(Some(if m.view == MemoriesView::Map { "map" } else { "list" }));
        self.drawing.set(false);
        let map = m.view == MemoriesView::Map && m.offers_views;
        self.list.set_visible(!map && !m.rows.is_empty());
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
