//! Depot on Linux (ADR 0154 decision 3): a file's page ([file_page], a PDF's text selected through [pdf_text]) and a
//! drop box's page for a member ([drop_box]), a file's Replace, a member's and a link guest's ([replace]). Depot's uploads,
//! its drop and a download's import are the core UI's (the tray, New's uploads, a drop onto any level). [register] hands
//! each page to the core UI's registry.
pub mod drop_box;
pub mod file_page;
pub mod pdf_text;
pub mod replace;

use adw::prelude::*;

use crate::apps::depot::drop_box::DropBoxPage;
use crate::apps::depot::file_page::FilePage;
use crate::bridge::wire::{ApplicationId, NodeAt, ObjectRef, ReceivedPage, ReceivedPageKind};
use crate::core_ui::registry::{ApplicationUi, Frame, GuestFrame, GuestPage, KindPage, Page, Registry};
use crate::core_ui::received;

/// Depot's kinds and its application into [registry].
pub fn register(registry: &mut Registry) {
    registry.kind(ApplicationId::Depot, &["FILE"], KindPage {
        body: file,
        guest: Some(GuestPage { kind: ReceivedPageKind::File, body: guest_file }),
        actions: &["file-save", "file-replace"],
        refreshes: false,
        facts_later: true,
    });
    registry.kind(ApplicationId::Depot, &["DEPOT_DROPBOX"], KindPage { body: drop_box, guest: None, actions: &[], refreshes: true, facts_later: false });
    registry.application(ApplicationUi { id: ApplicationId::Depot, list: None, creates: &[], person_section: None, queued_open: None });
}

fn node_of(o: &ObjectRef) -> NodeAt {
    NodeAt { scope_id: o.scope_id.clone(), node_id: o.node_id.clone(), kind: o.kind.clone(), name: o.name.clone() }
}

impl Page for FilePage {
    fn widget(&self) -> gtk::Widget {
        FilePage::widget(self).clone().upcast()
    }

    /// The header's download of a file, its Save a copy (`win.file-save`, a guest's *Download*), and its own Replace
    /// (`win.file-replace`: chosen first, then its open dialog, `uploads`).
    fn act(&self, action: &str, window: &gtk::Window, toasts: &adw::ToastOverlay) {
        match action {
            "file-save" | "save" => self.save(window, toasts),
            "file-replace" => {
                if let Some(id) = self.id() {
                    replace::member(window, id, toasts);
                }
            }
            _ => {}
        }
    }

    fn file(&self) -> Option<i64> {
        self.id()
    }
}

/// A file's page in the shell: once read, its header learns what it may do (`FileModel.Header.facts`).
fn file(o: &ObjectRef, frame: &Frame) -> Box<dyn Page> {
    let page = FilePage::open(&node_of(o));
    let facts = frame.facts.clone();
    page.on_facts(move |f, id| {
        facts(Some(f), Some(id), None);
    });
    Box::new(page)
}

/// A file on a guest's screen: its own actions as its header offers them a member — *Download* where it holds content,
/// *Replace* where the guest may (`FileModel`'s rights, a READ_WRITE guest's).
fn guest_file(p: &ReceivedPage, frame: &GuestFrame) -> Option<Box<dyn Page>> {
    let file = p.file?;
    let page = FilePage::opened(&frame.node, file);
    let received = frame.received;
    let (save, replace) = (frame.save.downgrade(), frame.replace.downgrade());
    page.on_facts(move |facts, _| {
        if let Some(save) = save.upgrade() {
            save.set_visible(facts.has_content);
        }
        if let Some(replace) = replace.upgrade() {
            replace.set_visible(received::replace_offered(facts.can_write));
        }
    });
    Some(Box::new(GuestFile { page, received }))
}

/// A file on a guest's screen: the member's page, its *Replace* the link's own (`replace::guest`, `Received.Replace`).
struct GuestFile {
    page: FilePage,
    received: i64,
}

impl Page for GuestFile {
    fn widget(&self) -> gtk::Widget {
        Page::widget(&self.page)
    }

    /// The link bar's *Download* (`save`) is the page's; its *Replace* goes into the link's own queue.
    fn act(&self, action: &str, window: &gtk::Window, toasts: &adw::ToastOverlay) {
        match action {
            "replace" => {
                if let Some(file) = self.page.id() {
                    replace::guest(window, self.received, file, toasts);
                }
            }
            other => Page::act(&self.page, other, window, toasts),
        }
    }

    fn file(&self) -> Option<i64> {
        self.page.id()
    }
}

impl Page for DropBoxPage {
    fn widget(&self) -> gtk::Widget {
        DropBoxPage::widget(self).clone().upcast()
    }

    fn hero(&self, hero: Option<gtk::Widget>) {
        DropBoxPage::hero(self, hero);
    }
}

/// A drop box's page in the shell; Refresh reads the shell's lists anew, which it follows.
fn drop_box(o: &ObjectRef, frame: &Frame) -> Box<dyn Page> {
    Box::new(DropBoxPage::open(&node_of(o), &frame.toasts))
}
