//! The core UI's registry (ADR 0154 decision 3; the web's `core/applications/registry.ts`, Android's `core/Registry.kt`):
//! how the core UI reaches an application without naming it. Each application registers, from its one `register` (called
//! by the composition root, `crate::applications`), per kind its page body — what the shell draws inside its frame, the
//! header with its hero above it, the notices and the tray around it ([KindPage]) — and the page a received link's screen
//! shows a guest of that kind ([GuestPage]); and per application its own list, its New dialogs and its section of a
//! person's profile ([ApplicationUi]). What the core plans — the header's order, what New offers, the share's answers —
//! stays the Kotlin core's `AppKinds`; this holds only what the platform draws. A kind or an application registered twice
//! is refused, and nothing is looked up before [Registry::seal].
use std::rc::Rc;
use std::sync::OnceLock;

use crate::bridge::{self, wire::ApplicationId, wire::BarTab, wire::Call, wire::Create, wire::Facts, wire::NodeAt, wire::ObjectRef,
    wire::PersonShown, wire::QueuedOpen, wire::ReceivedPage, wire::ReceivedPageKind, wire::ShellShown, wire::ShownTab};
use crate::core_ui::uploads::Level;

/// What the shell hands a kind's page as it opens it: the frame around the body.
pub struct Frame {
    pub window: gtk::Window,
    pub toasts: adw::ToastOverlay,
    /// The content's navigation, where a page pushes what it opens over itself (a Memory's lightbox).
    pub nav: adw::NavigationView,
    /// The page's own creating action's place at the start of the content's header bar (a Pot's *Add entry*, a Memory's
    /// *Add chapter*); emptied when the page goes.
    pub start: gtk::Box,
    /// Tells the header on screen what the page read — its facts, the page's file and its gallery in the core
    /// (`Header::facts`); false where no header stands to be told.
    pub facts: TellFacts,
    /// The line under the content's title (a gallery's count); emptied when the page goes.
    pub subtitle: Rc<dyn Fn(&str)>,
}

/// Tells the header its facts, the page's file and its gallery; false where no header stands ([Frame::facts]).
pub type TellFacts = Rc<dyn Fn(Option<Facts>, Option<i64>, Option<i64>) -> bool>;

/// An application's section of a person's page, made over the window ([ApplicationUi::person_section]).
pub type MakeSection = fn(&gtk::Window) -> Box<dyn PersonSection>;

/// A held write's *Open* an application opens itself ([ApplicationUi::queued_open]).
pub type OpenQueued = fn(&QueuedOpen, &gtk::Window, &adw::ToastOverlay) -> bool;

/// What a received link's screen hands a kind's guest page: the link, the screen's place and the header's buttons the
/// page shows where it offers them — its own write ([GuestFrame::add]), a file's *Download* and *Replace*.
pub struct GuestFrame {
    pub received: i64,
    /// The page's node as the link names it (no ids: a guest's page is the link's).
    pub node: NodeAt,
    pub window: gtk::Window,
    pub root: adw::ToastOverlay,
    pub nav: adw::NavigationView,
    pub add: gtk::Button,
    pub save: gtk::Button,
    pub replace: gtk::Button,
}

/// A kind's page body on screen, held while it shows; dropped, it closes what it opened in the core.
pub trait Page {
    fn widget(&self) -> gtk::Widget;
    /// The node's head (`Header::hero`), the frame's, placed first in the page's scroll.
    fn hero(&self, _hero: Option<gtk::Widget>) {}
    /// The bar's places the core shows with the page (`ShellShown.tabs`).
    fn places(&self, _tabs: &[ShownTab], _selected: Option<&BarTab>) {}
    /// Refresh (F5, Ctrl+R): true where the page read anew itself, false where the shell's lists are read anew.
    fn refresh(&self) -> bool {
        false
    }
    /// One of its kind's window actions ([KindPage::actions], fired by the header's own actions) or, on a guest's screen,
    /// `add` (the page's own write) and `save` (a file's *Download*).
    fn act(&self, _action: &str, _window: &gtk::Window, _toasts: &adw::ToastOverlay) {}
    /// The file the page shows, by its id in the core (a guest's *Replace*).
    fn file(&self) -> Option<i64> {
        None
    }
}

/// A kind's page body opened in the shell for the object [ObjectRef] inside [Frame].
pub type Body = fn(&ObjectRef, &Frame) -> Box<dyn Page>;

/// A kind's page on a guest's screen for the link's page; none where the core's page carries no model for it yet.
pub type Guest = fn(&ReceivedPage, &GuestFrame) -> Option<Box<dyn Page>>;

/// A kind's page for a guest (share view = member view, ADR 0150 decision 5): the member's page over the link's model.
pub struct GuestPage {
    /// The variant of the link's page it draws (`ReceivedPage.kind`).
    pub kind: ReceivedPageKind,
    pub body: Guest,
    /// Whether the page's own write asks the guest's name first (`Received.Name`: a Memory's *Add photos*); else the
    /// core asks it at the write.
    pub add_names_first: bool,
}

/// What an application registers per kind it owns.
pub struct KindPage {
    pub body: Body,
    pub guest: Option<GuestPage>,
    /// The window actions (`win.<name>`) the header's own actions fire on this kind's page (`header::doer`).
    pub actions: &'static [&'static str],
    /// Whether the shell's Refresh stands on the page.
    pub refreshes: bool,
    /// Whether its header learns its facts from the page once read (a Pot, a file); else the header opens knowing them.
    pub facts_later: bool,
}

/// An application's own list, where its list shows, held while it does (the Memories bar's places and Map tab).
pub trait ApplicationList {
    /// Its own page in place of the list where it shows one (the Map tab); none: the list.
    fn instead(&self, shown: &ShellShown, window: &gtk::Window) -> Option<gtk::Widget>;
    /// The list core arranged ([list]) with what the application puts around it; [back_from_page]: one of its pages
    /// closed onto it.
    fn around(&self, list: gtk::Widget, shown: &ShellShown, back_from_page: bool) -> gtk::Widget;
}

/// An application's section of a person's profile, under core's frame (the avatar, the name, the safety number).
pub trait PersonSection {
    fn widget(&self) -> gtk::Widget;
    /// The person's state, its model [person] in the core.
    fn draw(&self, person: i64, shown: &PersonShown);
}

/// One of New's creates an application draws its own dialog for, fired as the window's `win.<action>`.
pub struct CreateDialog {
    pub create: Create,
    pub action: &'static str,
    /// Opens it over the window, the level shown (none on a page that is no level).
    pub open: fn(&gtk::Window, &adw::ToastOverlay, Option<Level>),
}

/// What an application registers once.
pub struct ApplicationUi {
    pub id: ApplicationId,
    /// Its own list (none: core's arranged list alone), made when its list shows.
    pub list: Option<fn() -> Box<dyn ApplicationList>>,
    pub creates: &'static [CreateDialog],
    pub person_section: Option<MakeSection>,
    /// A held write's *Open* from the uploads' tray (`Queued.Open`) the application opens itself (a Pot entry's held
    /// editor); true where it did.
    pub queued_open: Option<OpenQueued>,
}

/// Every application's registrations, filled by the composition root, then sealed.
#[derive(Default)]
pub struct Registry {
    kinds: Vec<(&'static str, ApplicationId, KindPage)>,
    applications: Vec<ApplicationUi>,
}

static SEALED: OnceLock<Registry> = OnceLock::new();

/// The sealed registry; read before [Registry::seal] it fails (the composition root runs first, in `main`).
pub fn registry() -> &'static Registry {
    SEALED.get().expect("the registry was read before it was sealed: applications::register runs first")
}

impl Registry {
    /// [page] for each of [kinds], [application]'s; a kind or a guest page's variant registered before is refused.
    pub fn kind(&mut self, application: ApplicationId, kinds: &[&'static str], page: KindPage) {
        for k in kinds {
            assert!(self.kinds.iter().all(|(r, _, _)| r != k), "the kind {k} is registered twice");
        }
        if let Some(g) = &page.guest {
            assert!(self.guest(g.kind).is_none(), "a guest's page {:?} is registered twice", g.kind);
        }
        let (first, rest) = kinds.split_first().expect("a page for no kind");
        // one page for several kinds (a Memory and a chapter): each kind holds the same functions
        for k in rest {
            let copy = KindPage { body: page.body, guest: None, actions: page.actions, refreshes: page.refreshes, facts_later: page.facts_later };
            self.kinds.push((k, application, copy));
        }
        self.kinds.push((first, application, page));
    }

    /// [ui], registered once per application.
    pub fn application(&mut self, ui: ApplicationUi) {
        assert!(self.applications.iter().all(|a| a.id != ui.id), "the application {:?} is registered twice", ui.id);
        self.applications.push(ui);
    }

    /// Ends registering: from now on the registry is read ([registry]); sealed twice is refused.
    pub fn seal(self) {
        assert!(SEALED.set(self).is_ok(), "the registry is sealed twice");
    }

    /// The page registered for [kind].
    pub fn page(&self, kind: &str) -> Option<&KindPage> {
        self.kinds.iter().find(|(k, _, _)| *k == kind).map(|(_, _, p)| p)
    }

    /// The application that registered [kind].
    pub fn application_of(&self, kind: &str) -> Option<ApplicationId> {
        self.kinds.iter().find(|(k, _, _)| *k == kind).map(|(_, a, _)| *a)
    }

    /// The guest's page registered for a link's page of [kind].
    pub fn guest(&self, kind: ReceivedPageKind) -> Option<&GuestPage> {
        self.kinds.iter().filter_map(|(_, _, p)| p.guest.as_ref()).find(|g| g.kind == kind)
    }

    /// Every kind page's window actions, each once.
    pub fn actions(&self) -> Vec<&'static str> {
        let mut out: Vec<&'static str> = Vec::new();
        for a in self.kinds.iter().flat_map(|(_, _, p)| p.actions.iter()) {
            if !out.contains(a) {
                out.push(a);
            }
        }
        out
    }

    pub fn applications(&self) -> &[ApplicationUi] {
        &self.applications
    }

    pub fn ui(&self, id: ApplicationId) -> Option<&ApplicationUi> {
        self.applications.iter().find(|a| a.id == id)
    }

    /// The dialog an application registered for [create].
    pub fn create(&self, create: Create) -> Option<&CreateDialog> {
        self.applications.iter().flat_map(|a| a.creates.iter()).find(|c| c.create == create)
    }
}

/// [node] opened where the shell opens an object, as the application that registered its kind (a map's pin).
pub fn open_node(node: &NodeAt) {
    let Some(application) = registry().application_of(&node.kind) else {
        return eprintln!("veyq: no application registered the kind {}", node.kind);
    };
    let r = ObjectRef { scope_id: node.scope_id.clone(), node_id: node.node_id.clone(), kind: node.kind.clone(), application, name: node.name.clone() };
    bridge::send(Call::ShellOpen { r#ref: r });
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Empty;
    impl Page for Empty {
        fn widget(&self) -> gtk::Widget {
            unreachable!("never drawn here")
        }
    }

    fn body(_: &ObjectRef, _: &Frame) -> Box<dyn Page> {
        Box::new(Empty)
    }

    fn guest(_: &ReceivedPage, _: &GuestFrame) -> Option<Box<dyn Page>> {
        None
    }

    fn page(guest_kind: Option<ReceivedPageKind>) -> KindPage {
        KindPage { body, guest: guest_kind.map(|kind| GuestPage { kind, body: guest, add_names_first: false }), actions: &["a", "b"], refreshes: false, facts_later: false }
    }

    fn ui(id: ApplicationId) -> ApplicationUi {
        ApplicationUi { id, list: None, creates: &[], person_section: None, queued_open: None }
    }

    #[test]
    fn each_kind_finds_its_page_and_its_application_and_the_actions_are_listed_once() {
        let mut r = Registry::default();
        r.kind(ApplicationId::Memories, &["MEMORY", "CHAPTER"], page(Some(ReceivedPageKind::Gallery)));
        r.kind(ApplicationId::Depot, &["FILE"], page(None));
        assert_eq!(r.application_of("CHAPTER"), Some(ApplicationId::Memories));
        assert_eq!(r.application_of("FILE"), Some(ApplicationId::Depot));
        assert!(r.page("FOLDER").is_none(), "core's kinds are none of an application's");
        assert!(r.guest(ReceivedPageKind::Gallery).is_some());
        assert!(r.guest(ReceivedPageKind::Pot).is_none());
        assert_eq!(r.actions(), vec!["a", "b"]);
    }

    #[test]
    #[should_panic(expected = "registered twice")]
    fn a_kind_registered_twice_is_refused() {
        let mut r = Registry::default();
        r.kind(ApplicationId::Memories, &["MEMORY"], page(None));
        r.kind(ApplicationId::Depot, &["MEMORY"], page(None));
    }

    #[test]
    #[should_panic(expected = "registered twice")]
    fn a_guests_page_registered_twice_is_refused() {
        let mut r = Registry::default();
        r.kind(ApplicationId::Memories, &["MEMORY"], page(Some(ReceivedPageKind::File)));
        r.kind(ApplicationId::Depot, &["FILE"], page(Some(ReceivedPageKind::File)));
    }

    /// No test seals the registry (the composition root alone does, in `main`), so here it is read unsealed.
    #[test]
    #[should_panic(expected = "before it was sealed")]
    fn a_lookup_before_the_seal_fails() {
        registry();
    }

    #[test]
    #[should_panic(expected = "registered twice")]
    fn an_application_registered_twice_is_refused() {
        let mut r = Registry::default();
        r.application(ui(ApplicationId::Expenses));
        r.application(ui(ApplicationId::Expenses));
    }
}
