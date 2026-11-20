//! Expenses on Linux (ADR 0154 decision 3): an open Pot ([pot]) with its Settings ([pot_settings]), the entry editor
//! ([entry_editor]) with an entry's history ([entry_history]), and New pot ([pot_create]). A receipt's picture is the core
//! UI's (`receipt_viewer`, which a file's PDF and the Emergency Kit draw too). [register] hands each to the core UI's
//! registry.
pub mod entry_editor;
pub mod entry_history;
pub mod pot;
pub mod pot_create;
pub mod pot_settings;

use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::apps::expenses::pot::PotPage;
use crate::bridge::{self, wire::Answer, wire::ApplicationId, wire::BarTab, wire::Call, wire::Create, wire::Facts, wire::ObjectRef,
    wire::QueuedOpen, wire::ReceivedPage, wire::ReceivedPageKind, wire::ShownTab};
use crate::core_ui::registry::{ApplicationUi, CreateDialog, Frame, GuestFrame, GuestPage, KindPage, Page, Registry};
use crate::core_ui::{create_action, header, words};

/// Expenses' kind and its application into [registry].
pub fn register(registry: &mut Registry) {
    registry.kind(ApplicationId::Expenses, &["EXPENSES_POT"], KindPage {
        body,
        guest: Some(GuestPage { kind: ReceivedPageKind::Pot, body: guest, add_names_first: false }),
        actions: &["pot-download"],
        refreshes: true,
        facts_later: true,
    });
    registry.application(ApplicationUi {
        id: ApplicationId::Expenses,
        list: None,
        // New's Pot (`PotCreate.Open`): its dialog, over the window; the shell opens the Pot it made
        creates: &[CreateDialog { create: Create::Pot, action: "create-pot", open: |window, toasts, _| pot_create::open(window, toasts) }],
        person_section: None,
        queued_open: Some(open_queued),
    });
}

/// An open Pot, held by the shell or a guest's screen.
struct Opened(Rc<PotPage>);

impl Page for Opened {
    fn widget(&self) -> gtk::Widget {
        self.0.widget().clone().upcast()
    }

    fn hero(&self, hero: Option<gtk::Widget>) {
        self.0.hero(hero);
    }

    fn places(&self, tabs: &[ShownTab], selected: Option<&BarTab>) {
        self.0.places(tabs, selected);
    }

    fn refresh(&self) -> bool {
        self.0.refresh();
        true
    }

    /// The header's download of a Pot (`win.pot-download`), and a guest's own write, *Add entry* (the editor's save asks
    /// the guest's name where the core says, `PotDoor.named`).
    fn act(&self, action: &str, window: &gtk::Window, toasts: &adw::ToastOverlay) {
        match action {
            "pot-download" => self.0.download(window, toasts),
            "add" => self.0.add_entry(),
            _ => {}
        }
    }
}

/// An open Pot in the shell: once read, its *Add entry* (the page's own creating action) where the core offers it
/// (offline too, where an entry queues, never by `canWrite`), and its header learns whether it takes writes
/// (`AppKinds.Facts`).
fn body(o: &ObjectRef, frame: &Frame) -> Box<dyn Page> {
    let page = Rc::new(PotPage::open(o, &frame.window, &frame.toasts));
    let add = create_action::create_action(&words::pot_add(), || {});
    add.set_visible(false);
    frame.start.append(&add);
    let weak = Rc::downgrade(&page);
    add.connect_clicked(move |_| {
        if let Some(page) = weak.upgrade() {
            page.add_entry();
        }
    });
    let (facts, add) = (frame.facts.clone(), add.downgrade());
    page.on_read(move |can_write, offers_entries| {
        if let Some(add) = add.upgrade() {
            add.set_visible(offers_entries);
        }
        facts(Some(Facts { can_write, has_content: true }), None, None);
    });
    Box::new(Opened(page))
}

/// A Pot on a guest's screen: no bar there, so the page holds its places; *Add entry* where the core offers it
/// (`offersEntries`: a writer's, and a READ_WRITE guest's not named yet, asked at the save).
fn guest(p: &ReceivedPage, frame: &GuestFrame) -> Option<Box<dyn Page>> {
    let pot = p.pot?;
    let r = ObjectRef { scope_id: String::new(), node_id: String::new(), kind: p.node_kind.clone(), application: ApplicationId::Expenses, name: p.name.clone() };
    let page = PotPage::opened(&r, pot, &frame.window, &frame.root);
    page.own_places();
    let add = frame.add.downgrade();
    page.on_read(move |_, offers_entries| {
        let Some(add) = add.upgrade() else { return };
        add.set_tooltip_text(Some(&words::pot_add()));
        add.update_property(&[gtk::accessible::Property::Label(&words::pot_add())]);
        add.set_visible(offers_entries);
    });
    Some(Box::new(Opened(Rc::new(page))))
}

/// A held Pot entry's *Open* (`QueuedOpen.Entry`, ADR 0153 decision 4): the Pot opened as a model of its own for the
/// editor (`Pot.Open`), closed with it, the held editor over the newer version.
fn open_queued(open: &QueuedOpen, window: &gtk::Window, toasts: &adw::ToastOverlay) -> bool {
    let QueuedOpen::QueuedOpenEntry { scope, pot, write, .. } = open else { return false };
    let r = ObjectRef { scope_id: scope.clone(), node_id: pot.clone(), kind: "EXPENSES_POT".into(), application: ApplicationId::Expenses, name: String::new() };
    let (window, toasts, write) = (window.clone(), toasts.clone(), write.clone());
    glib::spawn_future_local(async move {
        match bridge::call(&Call::PotOpen { r#ref: r }).await.map(|(a, _)| a) {
            Ok(Answer::PotOpened { pot }) => entry_editor::open_held(&window, pot, &write, &toasts, move || bridge::send(Call::PotClose { pot })),
            other => header::say(&toasts, other, "Pot.Open"),
        }
    });
    true
}
