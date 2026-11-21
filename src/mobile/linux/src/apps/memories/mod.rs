//! Memories on Linux (ADR 0079's first application; ADR 0154 decision 3): a Memory's or a chapter's page ([memory_page])
//! with its [banner], its participants and capture time ([curation]), its facts line ([memory_facts]), its filter
//! ([gallery_filter]), its [lightbox], its Music view ([music_view]), its Map view ([memory_map_view]) and its
//! Contributions view ([contributions]); the bar's Map tab ([memories_map]); New Memory ([memory_create]); and its section
//! of a person's profile ([person_memories]). [register] hands each to the core UI's registry.
pub mod banner;
pub mod contributions;
pub mod curation;
pub mod gallery_filter;
pub mod lightbox;
pub mod memories_map;
pub mod memory_create;
pub mod memory_facts;
pub mod memory_map_view;
pub mod memory_page;
pub mod music_view;
pub mod person_memories;

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;

use crate::apps::memories::memories_map::MemoriesMap;
use crate::apps::memories::memory_page::{offers_chapter, MemoryPage};
use crate::apps::memories::person_memories::PersonMemories;
use crate::bridge::{self, wire::ApplicationId, wire::Call, wire::Create, wire::Facts, wire::NodeAt, wire::ObjectRef, wire::ReceivedPage,
    wire::ReceivedPageKind, wire::ShellShown};
use crate::core_ui::home::{self, glib_escape};
use crate::core_ui::registry::{ApplicationList, ApplicationUi, CreateDialog, Frame, GuestFrame, GuestPage, KindPage, Page, PersonSection, Registry};
use crate::core_ui::{create_action, said, words};

/// Memories' kinds and its application into [registry].
pub fn register(registry: &mut Registry) {
    registry.kind(ApplicationId::Memories, &["MEMORY", "CHAPTER"], KindPage {
        body,
        guest: Some(GuestPage { kind: ReceivedPageKind::Gallery, body: guest, add_names_first: true }),
        actions: &["memory-upload", "memory-music", "memory-banner"],
        refreshes: true,
        facts_later: false,
    });
    registry.application(ApplicationUi {
        id: ApplicationId::Memories,
        list: Some(|| Box::new(MemoriesList::default())),
        creates: &[CreateDialog { create: Create::Memory, action: "create-memory", open: memory_create::open }],
        person_section: Some(|window| Box::new(PersonMemories::new(window)) as Box<dyn PersonSection>),
        queued_open: None,
    });
}

/// A gallery's page, held by the shell or a guest's screen.
struct Gallery(Rc<MemoryPage>);

impl Page for Gallery {
    fn widget(&self) -> gtk::Widget {
        self.0.widget().clone().upcast()
    }

    fn hero(&self, hero: Option<gtk::Widget>) {
        self.0.hero(hero);
    }

    fn refresh(&self) -> bool {
        self.0.reload();
        true
    }

    /// The header's own "Add photos" (`win.memory-upload`, a guest's own write), music action (`win.memory-music`) and
    /// banner (`win.memory-banner`).
    fn act(&self, action: &str, _window: &gtk::Window, _toasts: &adw::ToastOverlay) {
        match action {
            "memory-upload" | "add" => self.0.add(),
            "memory-music" => self.0.music(),
            "memory-banner" => self.0.edit_banner(),
            _ => {}
        }
    }
}

/// A Memory's or a chapter's page in the shell: each state sets the count under the title, its "Add chapter" (the page's
/// own creating action), and once its gallery's id, the header's own upload.
fn body(o: &ObjectRef, frame: &Frame) -> Box<dyn Page> {
    let node = NodeAt { scope_id: o.scope_id.clone(), node_id: o.node_id.clone(), kind: o.kind.clone(), name: o.name.clone() };
    let page = Rc::new(MemoryPage::open(&node, &frame.window, &frame.toasts, &frame.nav));
    let add = create_action::create_action(&words::memory_add_chapter(), || {});
    add.set_visible(false);
    frame.start.append(&add);
    let weak = Rc::downgrade(&page);
    add.connect_clicked(move |_| {
        if let Some(page) = weak.upgrade() {
            page.create_chapter();
        }
    });
    let (subtitle, facts, add) = (frame.subtitle.clone(), frame.facts.clone(), add.downgrade());
    let told = Cell::new(None);
    page.on_shown(move |shown, gallery| {
        subtitle(&glib_escape(&said::notice(&shown.count)));
        if let Some(add) = add.upgrade() {
            add.set_visible(offers_chapter(shown));
        }
        if told.get() != Some(gallery) && facts(Some(Facts { can_write: true, has_content: true }), None, Some(gallery)) {
            told.set(Some(gallery));
        }
    });
    Box::new(Gallery(page))
}

/// A gallery on a guest's screen: a chapter opened inside the link, the guest's *Add photos* where the core offers it.
fn guest(p: &ReceivedPage, frame: &GuestFrame) -> Option<Box<dyn Page>> {
    let gallery = p.gallery?;
    let page = MemoryPage::opened(&frame.node, gallery, &frame.window, &frame.root, &frame.nav);
    let id = frame.received;
    page.on_chapter(move |chapter| bridge::send(Call::ReceivedOpenNode { received: id, node: chapter.node_id.clone() }));
    let add = frame.add.downgrade();
    page.on_shown(move |shown, _| {
        if let Some(add) = add.upgrade() {
            add.set_tooltip_text(Some(&words::memory_add()));
            add.update_property(&[gtk::accessible::Property::Label(&words::memory_add())]);
            add.set_visible(shown.can_upload);
        }
    });
    Some(Box::new(Gallery(Rc::new(page))))
}

/// Memories' list: the bar's places above it and the rows' dates asked for, or its Map tab in its place while that is
/// chosen ([MemoriesMap], its map closed when it goes).
#[derive(Default)]
struct MemoriesList {
    map: RefCell<Option<MemoriesMap>>,
}

impl ApplicationList for MemoriesList {
    fn instead(&self, shown: &ShellShown, window: &gtk::Window) -> Option<gtk::Widget> {
        if !shown.selected_tab.as_ref().is_some_and(|t| t.key == "memories.map") {
            self.map.borrow_mut().take();
            return None;
        }
        let mut map = self.map.borrow_mut();
        let map = map.get_or_insert_with(|| MemoriesMap::open(window));
        map.places(&shown.tabs, shown.selected_tab.as_ref());
        Some(map.widget().clone().upcast())
    }

    fn around(&self, list: gtk::Widget, shown: &ShellShown, back_from_page: bool) -> gtk::Widget {
        let key = shown.list.as_ref().map(|l| l.all.iter().chain(&l.favorites).chain(&l.recent).map(|o| o.node_id.as_str()).collect::<Vec<_>>().join(","));
        home::ask_dates(&key.unwrap_or_default(), back_from_page);
        if shown.tabs.len() > 1 {
            // the bar's places above the list, as the Map tab and the Pot draw them
            let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
            column.append(&crate::core_ui::places::places(&shown.tabs, shown.selected_tab.as_ref()));
            column.append(&list);
            column.upcast()
        } else {
            list
        }
    }
}
