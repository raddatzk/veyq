//! A kept link's page (ADR 0150 decision 6; the web's `BookmarkDetail.vue` and `BookmarkEmbed.vue`; cr-app-received-links
//! phase 9), GNOME's way: core's `BookmarkPage` (`Bookmark.Open`, `Watched.Bookmark`). While the tenant has
//! `core.share-embedding` on, the share shows in place under the shell's header — a [ReceivedView] in place, titled
//! "Shared content from …" —; otherwise an `AdwStatusPage` naming the link's host. Either way the page offers *Open on its
//! own page* (the link's screen above the shell, its password handed in memory by the core) and *Report shared content*
//! (that screen with its report asked, to the share's own operator). *Report name* is the header's report, which the core
//! makes a name's report for a bookmark (`AppKinds.reportsNameOnly`). A meta that does not read says so; its removal is
//! the header's delete.
//!
//! The page never holds the link, its password or its key: the core opens the meta and hands the host ids alone.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Answer, wire::BookmarkShown, wire::BookmarkState, wire::Call, wire::NodeAt, wire::Update, wire::Watched};
use crate::received::{self, Mode, ReceivedView};
use crate::words;

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
    /// Opens kept link [node] on a page inside [window].
    pub fn open(node: &NodeAt, window: &gtk::Window) -> Self {
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
            let Some((watch, updates)) = bridge::watch(&Watched::Bookmark { bookmark }) else { return };
            *inner.watch.borrow_mut() = Some(watch);
            drop(inner);
            follow(weak, updates).await;
        });
        Self { node: node.clone(), inner }
    }

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

/// What the page's two actions are offered for [state]: both once the meta read.
pub fn offers_actions(state: BookmarkState) -> bool {
    matches!(state, BookmarkState::InPlace | BookmarkState::OnItsOwn)
}

impl Inner {
    fn draw(self: &Rc<Self>) {
        let shown = self.shown.borrow().clone();
        let state = shown.as_ref().map(|s| s.state);
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
        if state.is_some_and(offers_actions) {
            self.root.append(&self.actions());
        }
        match s.state {
            BookmarkState::Loading => {
                let spinner = adw::Spinner::builder().width_request(32).height_request(32).build();
                self.root.append(&adw::StatusPage::builder().child(&spinner).vexpand(true).build());
            }
            BookmarkState::Unreadable => {
                self.root.append(&adw::StatusPage::builder().icon_name("dialog-warning-symbolic").description(words::bookmark_unreadable()).vexpand(true).build());
            }
            BookmarkState::OnItsOwn => {
                self.root.append(&adw::StatusPage::builder().icon_name("folder-publicshare-symbolic").title(crate::home::glib_escape(&s.host)).vexpand(true).build());
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

    /// *Open on its own page* and *Report shared content*, a row of buttons above the content.
    fn actions(self: &Rc<Self>) -> gtk::Box {
        let row = gtk::Box::builder().spacing(6).margin_start(12).margin_end(12).margin_top(6).margin_bottom(6).halign(gtk::Align::End).build();
        for (label, report) in [(words::bookmark_open_own_page(), false), (words::bookmark_report_share(), true)] {
            let button = gtk::Button::with_label(&label);
            button.add_css_class("flat");
            button.set_widget_name(if report { "bookmark-report-share" } else { "bookmark-own-page" });
            let w = Rc::downgrade(self);
            button.connect_clicked(move |_| {
                let Some(bookmark) = w.upgrade().and_then(|i| i.id.get()) else { return };
                let call = if report { Call::BookmarkReportShared { bookmark } } else { Call::BookmarkOwnPage { bookmark } };
                glib::spawn_future_local(async move {
                    match bridge::call(&call).await.map(|(a, _)| a) {
                        Ok(Answer::ReceivedOpened { received }) => received::present(received, None),
                        Ok(_) => eprintln!("veyq: the bookmark's link did not open"),
                        Err(e) => eprintln!("veyq: the bookmark's link's answer did not read: {:?}", e.0),
                    }
                });
            });
            row.append(&button);
        }
        row
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn its_actions_stand_once_the_meta_read_and_never_for_one_that_did_not() {
        assert!(offers_actions(BookmarkState::InPlace));
        assert!(offers_actions(BookmarkState::OnItsOwn));
        assert!(!offers_actions(BookmarkState::Loading));
        assert!(!offers_actions(BookmarkState::Unreadable));
    }
}
