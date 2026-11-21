//! A kept link's page (ADR 0150 decision 6; the web's `BookmarkDetail.vue` and `BookmarkEmbed.vue`; cr-app-received-links
//! phase 9, #1032, #1035), GNOME's way: the shell's own page of a node no application owns. A row opens it through the core
//! ([open_kept]: `Shell.OpenBookmark`), whose state then names it (`Shell.bookmark`, `ShellShown.headed`), so the shell's
//! back leaves it and core's header heads it as every node — its rename, move, favourite, delete (an unreadable one's too)
//! and *Report* (a name's report on a kept link, `AppKinds.reportsNameOnly`). Its body is core's `BookmarkPage`
//! (`Bookmark.Open`, `Watched.Bookmark`): while the tenant has `core.share-embedding` on, the share shows in place — a
//! [ReceivedView] in place, its region named "Shared content from …" —; otherwise an `AdwStatusPage` naming the link's
//! host. Either way the header's plan offers *Open on its own page* (the link's screen above the shell, its password handed
//! in memory by the core) and *Report shared content* (that screen with its report asked, to the share's own operator),
//! done through this page's model, whose id the page tells the shell ([BookmarkPage::open]'s `told`). A meta that does not
//! read says so.
//!
//! The page never holds the link, its password or its key: the core opens the meta and hands the host ids alone.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Answer, wire::BookmarkShown, wire::BookmarkState, wire::Call, wire::NodeAt, wire::Update, wire::Watched};
use crate::core_ui::received::{Mode, ReceivedView};
use crate::core_ui::words;

/// The kind of a kept link (core's `AppKinds.BOOKMARK_KIND`).
pub const KIND: &str = "SHARE_BOOKMARK";

/// Opens kept link [node]'s page (a row's, a tile's activation): the core's shell opens it over what is on screen.
pub fn open_kept(node: &NodeAt) {
    bridge::send(Call::ShellOpenBookmark { node: node.clone() });
}

/// Whether the shell's page of [now] stays the page of [next]: the same node, whatever its name became.
pub fn same_node(now: &NodeAt, next: &NodeAt) -> bool {
    now.scope_id == next.scope_id && now.node_id == next.node_id
}

/// A kept link on screen: opened in the core when made, closed when dropped.
pub struct BookmarkPage {
    pub node: NodeAt,
    inner: Rc<Inner>,
}

struct Inner {
    root: gtk::Box,
    window: gtk::Window,
    id: Cell<Option<i64>>,
    watch: RefCell<Option<Watch>>,
    shown: RefCell<Option<BookmarkShown>>,
    /// The share in place while it shows, by its received link's id.
    in_place: RefCell<Option<ReceivedView>>,
}

impl BookmarkPage {
    /// Opens kept link [node]'s page inside [window], as the shell's content; [told] its model's id once the core opened it
    /// (the header's own entries are done through it, `HeaderFacts.bookmark`).
    pub fn open(node: &NodeAt, window: &gtk::Window, told: impl Fn(i64) + 'static) -> Self {
        let root = gtk::Box::builder().orientation(gtk::Orientation::Vertical).vexpand(true).build();
        let inner = Rc::new(Inner { root, window: window.clone(), id: Cell::new(None), watch: RefCell::new(None), shown: RefCell::new(None), in_place: RefCell::new(None) });
        inner.draw();
        let weak = Rc::downgrade(&inner);
        let open = node.clone();
        glib::spawn_future_local(async move {
            let Ok((Answer::BookmarkOpened { bookmark }, _)) = bridge::call(&Call::BookmarkOpen { node: open }).await else {
                eprintln!("veyq: the bookmark did not open in the core");
                return;
            };
            let Some(inner) = weak.upgrade() else {
                bridge::send(Call::BookmarkClose { bookmark });
                return;
            };
            inner.id.set(Some(bookmark));
            told(bookmark);
            let Some((watch, updates)) = bridge::watch(&Watched::Bookmark { bookmark }) else { return };
            *inner.watch.borrow_mut() = Some(watch);
            drop(inner);
            follow(weak, updates).await;
        });
        Self { node: node.clone(), inner }
    }

    /// The page's model's id, once the core opened it.
    pub fn id(&self) -> Option<i64> {
        self.inner.id.get()
    }

    /// The page's body, the shell's content while it shows.
    pub fn widget(&self) -> &gtk::Box {
        &self.inner.root
    }
}

impl Drop for BookmarkPage {
    fn drop(&mut self) {
        self.inner.watch.borrow_mut().take();
        self.inner.in_place.borrow_mut().take();
        if let Some(bookmark) = self.inner.id.get() {
            bridge::send(Call::BookmarkClose { bookmark });
        }
    }
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Bookmark { shown, .. }) => {
                eprintln!("veyq: bookmark: {:?}", shown.as_ref().map(|s| s.state));
                *inner.shown.borrow_mut() = shown;
                inner.draw();
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a bookmark update did not read: {:?}", e.0),
        }
    }
}

impl Inner {
    fn draw(self: &Rc<Self>) {
        let shown = self.shown.borrow().clone();
        // the share in place stays while the same link shows; anything else is drawn anew
        let same_in_place = shown.as_ref().and_then(|s| s.received).is_some_and(|r| self.in_place.borrow().as_ref().is_some_and(|v| v.received == r));
        if same_in_place {
            return;
        }
        while let Some(c) = self.root.first_child() {
            self.root.remove(&c);
        }
        self.in_place.borrow_mut().take();
        let Some(s) = shown else {
            let spinner = adw::Spinner::builder().width_request(32).height_request(32).build();
            self.root.append(&adw::StatusPage::builder().child(&spinner).vexpand(true).build());
            return;
        };
        match s.state {
            BookmarkState::Loading => {
                let spinner = adw::Spinner::builder().width_request(32).height_request(32).build();
                self.root.append(&adw::StatusPage::builder().child(&spinner).vexpand(true).build());
            }
            BookmarkState::Unreadable => {
                self.root.append(&adw::StatusPage::builder().icon_name("dialog-warning-symbolic").description(words::bookmark_unreadable()).vexpand(true).build());
            }
            BookmarkState::OnItsOwn => {
                self.root.append(&adw::StatusPage::builder().icon_name("folder-publicshare-symbolic").title(crate::core_ui::home::glib_escape(&s.host)).vexpand(true).build());
            }
            BookmarkState::InPlace => {
                let Some(received) = s.received else { return };
                // the region named for assistive technology: whose content this is
                let region = gtk::Box::builder().orientation(gtk::Orientation::Vertical).vexpand(true).accessible_role(gtk::AccessibleRole::Region).build();
                region.update_property(&[gtk::accessible::Property::Label(&words::bookmark_embed_region(&s.host))]);
                let view = ReceivedView::new(received, Mode::InPlace, &self.window, None, || {});
                region.append(view.widget());
                self.root.append(&region);
                *self.in_place.borrow_mut() = Some(view);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_page_stays_for_its_node_renamed_and_goes_for_another() {
        let at = |scope: &str, node: &str, name: &str| NodeAt { scope_id: scope.into(), node_id: node.into(), kind: KIND.into(), name: name.into() };
        assert!(same_node(&at("s", "n", "Summer"), &at("s", "n", "Summer 2026")), "a rename keeps the page");
        assert!(!same_node(&at("s", "n", "Summer"), &at("s", "m", "Summer")));
        assert!(!same_node(&at("s", "n", "Summer"), &at("t", "n", "Summer")));
    }
}
