//! A node's move and copy sheet (ADR 0127 decision 2; core's `MoveSheet`, the web's `MoveNodeDialog` and
//! `CopyNodesDialog`), GNOME's way: an `AdwDialog` holding an `AdwNavigationView` of places, one page per place of the
//! core's trail (back goes up, as Files' location does), each page listing the places one may go into — the node itself and
//! what lies below it listed but not to be opened —, its bottom bar saying why the place takes nothing (or a copy's
//! missing room) beside "Move here" / "Copy here". A move that ends a guest's folder is asked first in an
//! `AdwAlertDialog`. Once done the dialog closes and a toast says so, a move's with Undo for exactly as long as the core
//! offers it (no timer here: the toast goes when the state's `undo` does). Every state is the core's (`Move.*`, watched by
//! id); this lays it out.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::MoveShown, wire::NodeAt, wire::Update, wire::Watched};
use crate::home::{glib_escape, kind_icon};
use crate::{said, words};

/// A navigation page's tag for a place of the trail (null: the space's root).
pub fn tag_of(node_id: Option<&str>) -> String {
    node_id.map_or_else(|| "root".to_owned(), |id| format!("node:{id}"))
}

/// The place a navigation page's tag names (`None`: the root).
pub fn place_of(tag: &str) -> Option<String> {
    tag.strip_prefix("node:").map(str::to_owned)
}

/// How the navigation view goes from the pages it holds ([now]) to the core's trail ([then]): one page pushed, back to
/// one it holds, or every page anew.
#[derive(Debug, PartialEq, Eq)]
pub enum Sync {
    Same,
    Push,
    PopTo(String),
    Replace,
}

pub fn sync(now: &[String], then: &[String]) -> Sync {
    if now == then {
        Sync::Same
    } else if then.len() == now.len() + 1 && then.starts_with(now) {
        Sync::Push
    } else if !then.is_empty() && now.starts_with(then) {
        Sync::PopTo(then.last().cloned().unwrap_or_default())
    } else {
        Sync::Replace
    }
}

struct Inner {
    id: Cell<Option<i64>>,
    watch: RefCell<Option<Watch>>,
    dialog: adw::Dialog,
    nav: adw::NavigationView,
    toasts: adw::ToastOverlay,
    shown: RefCell<Option<MoveShown>>,
    /// A sync of the navigation view runs: its pops are not the person's back.
    syncing: Cell<bool>,
    /// The guest folder's confirm is on screen.
    asking: Cell<bool>,
    /// The dialog is closed (done or dismissed).
    closed: Cell<bool>,
    /// A move's Undo stands in a toast; the sheet stays open in the core until the offer ends.
    undo_toast: RefCell<Option<adw::Toast>>,
    seen_undo: Cell<bool>,
}

/// Opens the move ([copy] false) or copy sheet of [node] over [parent], saying what it did in [toasts].
pub fn open(parent: &impl IsA<gtk::Widget>, node: &NodeAt, copy: bool, toasts: &adw::ToastOverlay) {
    let nav = adw::NavigationView::new();
    let dialog = adw::Dialog::builder().content_width(520).content_height(600).child(&nav).build();
    let inner = Rc::new(Inner {
        id: Cell::new(None),
        watch: RefCell::new(None),
        dialog: dialog.clone(),
        nav: nav.clone(),
        toasts: toasts.clone(),
        shown: RefCell::new(None),
        syncing: Cell::new(false),
        asking: Cell::new(false),
        closed: Cell::new(false),
        undo_toast: RefCell::new(None),
        seen_undo: Cell::new(false),
    });
    // the first page while the tree is read
    let spinner = adw::Spinner::builder().width_request(32).height_request(32).build();
    let loading = adw::ToolbarView::new();
    loading.add_top_bar(&adw::HeaderBar::new());
    loading.set_content(Some(&adw::StatusPage::builder().child(&spinner).vexpand(true).build()));
    nav.add(&adw::NavigationPage::with_tag(&loading, &node.name, "loading"));
    // back in the navigation view is the person's: the core goes to the page now shown
    let weak = Rc::downgrade(&inner);
    nav.connect_popped(move |nav, _| {
        let Some(inner) = weak.upgrade() else { return };
        if inner.syncing.get() {
            return;
        }
        if let (Some(m), Some(tag)) = (inner.id.get(), nav.visible_page().and_then(|p| p.tag())) {
            bridge::send(Call::MoveGo { r#move: m, at: place_of(&tag) });
        }
    });
    let held = inner.clone();
    dialog.connect_closed(move |_| {
        held.closed.set(true);
        // a move's Undo standing keeps the sheet in the core until its offer ends
        if held.undo_toast.borrow().is_none() {
            held.end();
        }
    });
    dialog.present(Some(parent));
    let weak = Rc::downgrade(&inner);
    let call = Call::MoveOpen { node: node.clone(), copy, deleted_name: words::pot_deleted_person() };
    glib::spawn_future_local(async move {
        let Ok((Answer::MoveOpened { r#move: m }, _)) = bridge::call(&call).await else {
            eprintln!("veyq: the move sheet did not open in the core");
            return;
        };
        let Some(inner) = weak.upgrade() else {
            bridge::send(Call::MoveClose { r#move: m });
            return;
        };
        inner.id.set(Some(m));
        let Some((watch, updates)) = bridge::watch(&Watched::Move { r#move: m }) else { return };
        *inner.watch.borrow_mut() = Some(watch);
        drop(inner);
        follow(weak, updates).await;
    });
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Move { shown, .. }) => {
                if let Some(s) = &shown {
                    eprintln!(
                        "veyq: move sheet{}: at {:?}, {} places, here {:?}, problem {:?}, no room {}, busy {}, confirming {}, done {}, undo {}",
                        if s.copying { " (copy)" } else { "" },
                        s.trail.last().and_then(|c| c.node_id.clone()), s.places.len(), s.here, s.problem.as_ref().map(|p| &p.msg),
                        s.no_room.is_some(), s.busy, s.confirming, s.done.is_some(), s.undo.is_some()
                    );
                }
                *inner.shown.borrow_mut() = shown;
                inner.draw();
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a move sheet update did not read: {:?}", e.0),
        }
    }
}

impl Inner {
    /// The core's sheet closed and its watch ended.
    fn end(&self) {
        self.watch.borrow_mut().take();
        if let Some(m) = self.id.take() {
            bridge::send(Call::MoveClose { r#move: m });
        }
    }

    fn draw(self: &Rc<Self>) {
        // a move's undo: the toast goes when the core's offer ends (once a state showed it), and the sheet with it
        let offered = self.shown.borrow().as_ref().is_some_and(|s| s.undo.is_some());
        if offered {
            self.seen_undo.set(true);
        } else if self.seen_undo.replace(false)
            && let Some(t) = self.undo_toast.borrow_mut().take()
        {
            eprintln!("veyq: move undo offer ended");
            t.dismiss();
            if self.closed.get() {
                self.end();
            }
        }
        if self.closed.get() {
            return;
        }
        let shown = self.shown.borrow().clone();
        let Some(s) = shown else { return };
        if !s.loaded {
            return;
        }
        self.dialog.set_title(&said::notice(&s.title));
        let then: Vec<String> = s.trail.iter().map(|c| tag_of(c.node_id.as_deref())).collect();
        let now: Vec<String> = (0..self.nav.navigation_stack().n_items())
            .filter_map(|i| self.nav.navigation_stack().item(i).and_downcast::<adw::NavigationPage>())
            .filter_map(|p| p.tag().map(|t| t.to_string()))
            .collect();
        self.syncing.set(true);
        match sync(&now, &then) {
            Sync::Same => {}
            Sync::Push => {
                if let Some(c) = s.trail.last() {
                    self.nav.push(&page(&said::piece(&c.name), &then[then.len() - 1]));
                }
            }
            Sync::PopTo(tag) => {
                self.nav.pop_to_tag(&tag);
            }
            Sync::Replace => {
                let pages: Vec<adw::NavigationPage> = s.trail.iter().zip(&then).map(|(c, tag)| page(&said::piece(&c.name), tag)).collect();
                self.nav.replace(&pages);
            }
        }
        self.syncing.set(false);
        if let Some(top) = self.nav.visible_page() {
            self.fill(&top, &s);
        }
        // a move that ends a guest's folder: asked once, the answer sent
        if s.confirming && !self.asking.replace(true) {
            self.ask_confirm(&s);
        } else if !s.confirming {
            self.asking.set(false);
        }
    }

    /// The shown page's content: the hint, what a copy leaves behind and out, the places, and the bottom bar.
    fn fill(self: &Rc<Self>, page: &adw::NavigationPage, s: &MoveShown) {
        let Some(m) = self.id.get() else { return };
        let body = gtk::Box::new(gtk::Orientation::Vertical, 18);
        body.set_margin_top(12);
        body.set_margin_bottom(18);
        body.set_margin_start(12);
        body.set_margin_end(12);
        let hint = gtk::Label::builder().label(said::key(&s.hint)).wrap(true).xalign(0.0).build();
        hint.add_css_class("dim-label");
        body.append(&hint);
        if s.copying {
            body.append(&lines(&words::copy_losses(), &s.losses.iter().map(said::notice).collect::<Vec<_>>()));
            if !s.left_out.is_empty() {
                body.append(&lines(&words::copy_left_out(), &s.left_out.iter().map(said::notice).collect::<Vec<_>>()));
            }
        }
        if let Some(empty) = &s.empty {
            let label = gtk::Label::builder().label(said::notice(empty)).wrap(true).xalign(0.0).build();
            label.add_css_class("dim-label");
            body.append(&label);
        } else {
            let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
            list.add_css_class("boxed-list");
            for place in &s.places {
                let row = adw::ActionRow::builder().title(glib_escape(&place.name)).build();
                row.add_prefix(&gtk::Image::from_icon_name(kind_icon(&place.kind)));
                match &place.blocked {
                    Some(word) => {
                        row.set_subtitle(&glib_escape(&said::key(word)));
                        row.set_sensitive(false);
                    }
                    None => {
                        row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
                        row.set_activatable(!s.busy);
                        let at = place.node_id.clone();
                        row.connect_activated(move |_| bridge::send(Call::MoveGo { r#move: m, at: Some(at.clone()) }));
                    }
                }
                list.append(&row);
            }
            body.append(&list);
        }
        let clamp = adw::Clamp::builder().maximum_size(560).child(&body).build();
        let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build();
        // the bottom bar: why the place takes nothing (or what went wrong, or the missing room), and the button
        let status = gtk::Label::builder().wrap(true).xalign(0.0).hexpand(true).build();
        if let Some(n) = s.problem.as_ref().or(s.no_room.as_ref()) {
            status.set_label(&said::notice(n));
            status.add_css_class("error");
        } else if let Some(word) = &s.here {
            status.set_label(&said::key(word));
            status.add_css_class("dim-label");
        }
        let submit = gtk::Button::builder().label(said::key(&s.submit)).sensitive(s.can_choose).valign(gtk::Align::Center).build();
        submit.add_css_class("suggested-action");
        submit.add_css_class("pill");
        let weak = Rc::downgrade(self);
        submit.connect_clicked(move |_| {
            let weak = weak.clone();
            glib::spawn_future_local(async move {
                let answered = bridge::call(&Call::MoveHere { r#move: m }).await.map(|(a, _)| a);
                if let Some(inner) = weak.upgrade() {
                    inner.sent(answered, "Move.Here");
                }
            });
        });
        let bottom = gtk::Box::new(gtk::Orientation::Horizontal, 12);
        bottom.set_margin_top(6);
        bottom.set_margin_bottom(6);
        bottom.set_margin_start(12);
        bottom.set_margin_end(12);
        bottom.append(&status);
        bottom.append(&submit);
        let view = adw::ToolbarView::new();
        view.add_top_bar(&adw::HeaderBar::new());
        view.set_content(Some(&scroll));
        view.add_bottom_bar(&bottom);
        page.set_child(Some(&view));
    }

    /// What a write answered: done (the dialog closes, a toast says it, a move's with Undo), the confirm asked, or why not.
    fn sent(self: &Rc<Self>, answered: Result<Answer, bridge::Unreadable>, what: &str) {
        match answered {
            Ok(Answer::MoveSent { done: Some(done), undoable, .. }) => {
                eprintln!("veyq: {what}: {}, undo offered: {undoable}", done.msg);
                let toast = adw::Toast::new(&said::notice(&done));
                if undoable && let Some(m) = self.id.get() {
                    toast.set_button_label(Some(&words::trash_undo()));
                    toast.set_timeout(0);
                    let toasts = self.toasts.clone();
                    let held = self.clone();
                    toast.connect_button_clicked(move |_| {
                        let (toasts, held) = (toasts.clone(), held.clone());
                        glib::spawn_future_local(async move {
                            let answered = bridge::call(&Call::MoveUndo { r#move: m }).await.map(|(a, _)| a);
                            eprintln!("veyq: move undo: {answered:?}");
                            crate::header::say(&toasts, answered, "Move.Undo");
                            held.undo_toast.borrow_mut().take();
                            held.end();
                        });
                    });
                    *self.undo_toast.borrow_mut() = Some(toast.clone());
                }
                self.toasts.add_toast(toast);
                self.dialog.close();
            }
            Ok(Answer::MoveSent { confirming: true, .. }) => eprintln!("veyq: {what}: the guest folder's confirm asked"),
            Ok(Answer::MoveSent { problem, .. }) => eprintln!("veyq: {what}: not sent ({:?})", problem.map(|p| p.msg)),
            other => eprintln!("veyq: {what} answered {other:?}"),
        }
    }

    fn ask_confirm(self: &Rc<Self>, s: &MoveShown) {
        let Some(m) = self.id.get() else { return };
        let ask = adw::AlertDialog::builder().heading(words::move_identity_title()).body(said::notice(&s.confirm_question)).build();
        ask.add_responses(&[("cancel", &words::trash_cancel()), ("move", &words::move_identity_accept())]);
        ask.set_response_appearance("move", adw::ResponseAppearance::Destructive);
        ask.set_default_response(Some("cancel"));
        ask.set_close_response("cancel");
        let weak = Rc::downgrade(self);
        ask.connect_response(None, move |_, response| {
            if response != "move" {
                bridge::send(Call::MoveCancelConfirm { r#move: m });
                return;
            }
            let weak = weak.clone();
            glib::spawn_future_local(async move {
                let answered = bridge::call(&Call::MoveConfirm { r#move: m }).await.map(|(a, _)| a);
                if let Some(inner) = weak.upgrade() {
                    inner.sent(answered, "Move.Confirm");
                }
            });
        });
        ask.present(Some(&self.dialog));
    }
}

/// A navigation page for a place, titled with its name; its content is set when it is the one shown.
fn page(title: &str, tag: &str) -> adw::NavigationPage {
    adw::NavigationPage::with_tag(&gtk::Box::new(gtk::Orientation::Vertical, 0), title, tag)
}

/// A heading and its lines, as a copy says what it leaves behind or out.
fn lines(heading: &str, items: &[String]) -> gtk::Box {
    let group = gtk::Box::new(gtk::Orientation::Vertical, 4);
    let title = gtk::Label::builder().label(heading).xalign(0.0).wrap(true).build();
    title.add_css_class("heading");
    group.append(&title);
    for item in items {
        let line = gtk::Label::builder().label(item).xalign(0.0).wrap(true).margin_start(12).build();
        line.add_css_class("dim-label");
        group.append(&line);
    }
    group
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tags(ids: &[Option<&str>]) -> Vec<String> {
        ids.iter().map(|id| tag_of(*id)).collect()
    }

    #[test]
    fn the_navigation_view_follows_the_cores_trail() {
        let root = tags(&[None]);
        let trips = tags(&[None, Some("trips")]);
        let year = tags(&[None, Some("trips"), Some("2026")]);
        assert_eq!(sync(&trips, &trips), Sync::Same);
        assert_eq!(sync(&trips, &year), Sync::Push, "a place gone into is pushed");
        assert_eq!(sync(&year, &root), Sync::PopTo("root".into()), "a crumb or back pops to it");
        assert_eq!(sync(&[], &year), Sync::Replace, "the first trail builds every page");
        assert_eq!(sync(&tags(&[None, Some("other")]), &trips), Sync::Replace);
        assert_eq!(place_of(&tag_of(Some("trips"))).as_deref(), Some("trips"));
        assert_eq!(place_of(&tag_of(None)), None);
        assert_eq!(place_of("loading"), None);
    }
}
