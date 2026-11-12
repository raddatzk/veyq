//! A drop box's page for a member (Depot's `DropBoxDetail.vue`; the Depot CR's D2 and D10), GNOME's way: the hint, then
//! each guest's folder as an `AdwPreferencesGroup` — titled by the guest's name, how many files it holds beneath, a menu at
//! its header (Move out…, Delete) — whose rows are its files, each opening the file's page; nobody's yet, an
//! `AdwStatusPage` with the core's words. A folder's delete is asked in an `AdwAlertDialog`, then trashed with Undo in a
//! toast; Move out opens the move sheet on the folder ([crate::move_sheet], which asks before the folder leaves the drop
//! box).
//!
//! Everything is the core's `DropBoxPage` (`DropBox.Open`, `Watched.DropBox`): the folders are read anew on each revision
//! of the shell's lists, so a Refresh (F5, `Shell.Refresh`) shows a guest's new folder; a delete is done only where it was
//! asked (`DropBox.Delete` is refused otherwise).
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::DropBoxShown, wire::NodeAt, wire::Update, wire::Watched};
use crate::home::{glib_escape, kind_icon};
use crate::{said, words};

/// A drop box on screen: opened in the core when made, closed when dropped.
pub struct DropBoxPage {
    pub node: NodeAt,
    inner: Rc<Inner>,
}

struct Inner {
    root: gtk::ScrolledWindow,
    body: gtk::Box,
    toasts: adw::ToastOverlay,
    id: Cell<Option<i64>>,
    watch: RefCell<Option<Watch>>,
    shown: RefCell<Option<DropBoxShown>>,
    /// The folder whose delete is asked on screen: a later state with the same question shows no second alert.
    asked: RefCell<Option<String>>,
}

impl DropBoxPage {
    /// Opens drop box [node] on a page; what its actions say goes to [toasts].
    pub fn open(node: &NodeAt, toasts: &adw::ToastOverlay) -> Self {
        let body = gtk::Box::new(gtk::Orientation::Vertical, 18);
        let clamp = adw::Clamp::builder().maximum_size(720).child(&body).margin_top(18).margin_bottom(18).margin_start(12).margin_end(12).build();
        let root = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build();
        let inner = Rc::new(Inner {
            root,
            body,
            toasts: toasts.clone(),
            id: Cell::new(None),
            watch: RefCell::new(None),
            shown: RefCell::new(None),
            asked: RefCell::new(None),
        });
        inner.actions();
        inner.draw();
        let weak = Rc::downgrade(&inner);
        let open = node.clone();
        glib::spawn_future_local(async move {
            let Ok((Answer::DropBoxOpened { r#box }, _)) = bridge::call(&Call::DropBoxOpen { node: open }).await else {
                eprintln!("veyq: the drop box did not open in the core");
                return;
            };
            let Some(inner) = weak.upgrade() else {
                bridge::send(Call::DropBoxClose { r#box });
                return;
            };
            inner.id.set(Some(r#box));
            let Some((watch, updates)) = bridge::watch(&Watched::DropBox { r#box }) else { return };
            *inner.watch.borrow_mut() = Some(watch);
            drop(inner);
            follow(weak, updates).await;
        });
        Self { node: node.clone(), inner }
    }

    pub fn widget(&self) -> &gtk::ScrolledWindow {
        &self.inner.root
    }
}

impl Drop for DropBoxPage {
    fn drop(&mut self) {
        self.inner.watch.borrow_mut().take();
        if let Some(r#box) = self.inner.id.get() {
            bridge::send(Call::DropBoxClose { r#box });
        }
    }
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::DropBox { shown, .. }) => {
                if let Some(s) = &shown {
                    eprintln!(
                        "veyq: drop box: {} folders [{}], failed {}, deleting {:?}",
                        s.folders.as_ref().map_or(0, Vec::len),
                        s.folders.iter().flatten().map(|f| format!("{} ({})", f.label, f.files.len())).collect::<Vec<_>>().join(", "),
                        s.failed,
                        s.deleting
                    );
                }
                *inner.shown.borrow_mut() = shown;
                inner.draw();
                inner.ask_delete();
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a drop box update did not read: {:?}", e.0),
        }
    }
}

impl Inner {
    /// Each folder's menu: `dropbox.move-out` and `dropbox.delete`, each with the folder's id.
    fn actions(self: &Rc<Self>) {
        let group = gio::SimpleActionGroup::new();
        for name in ["move-out", "delete"] {
            let action = gio::SimpleAction::new(name, Some(glib::VariantTy::STRING));
            let weak = Rc::downgrade(self);
            action.connect_activate(move |_, target| {
                let (Some(inner), Some(folder)) = (weak.upgrade(), target.and_then(|v| v.str().map(str::to_owned))) else { return };
                let Some(r#box) = inner.id.get() else { return };
                if name == "move-out" {
                    let label = inner.label_of(&folder).unwrap_or_default();
                    crate::move_sheet::open_call(&inner.root, &label, Call::DropBoxMoveOut { r#box, folder }, &inner.toasts);
                } else {
                    let toasts = inner.toasts.clone();
                    glib::spawn_future_local(async move {
                        crate::header::say(&toasts, bridge::call(&Call::DropBoxAskDelete { r#box, folder }).await.map(|(a, _)| a), "DropBox.AskDelete");
                    });
                }
            });
            group.add_action(&action);
        }
        self.root.insert_action_group("dropbox", Some(&group));
    }

    fn label_of(&self, folder: &str) -> Option<String> {
        self.shown.borrow().as_ref()?.folders.as_ref()?.iter().find(|f| f.id == folder).map(|f| f.label.clone())
    }

    fn draw(&self) {
        while let Some(child) = self.body.first_child() {
            self.body.remove(&child);
        }
        let shown = self.shown.borrow();
        let Some(folders) = shown.as_ref().and_then(|s| s.folders.as_ref()) else {
            self.body.append(&adw::Spinner::builder().width_request(32).height_request(32).halign(gtk::Align::Center).build());
            return;
        };
        let s = shown.as_ref().expect("folders are a state's");
        if folders.is_empty() {
            let empty = adw::StatusPage::builder().icon_name(kind_icon("DEPOT_DROPBOX")).description(said::key(&s.empty)).vexpand(true).build();
            self.body.append(&empty);
            return;
        }
        let hint = gtk::Label::builder().label(said::key(&s.hint)).wrap(true).xalign(0.0).build();
        hint.add_css_class("dim-label");
        self.body.append(&hint);
        for f in folders {
            let group = adw::PreferencesGroup::builder().title(glib_escape(&f.label)).description(said::notice(&f.count)).build();
            let menu = gio::Menu::new();
            let item = gio::MenuItem::new(Some(&said::notice(&f.move_out)), None);
            item.set_action_and_target_value(Some("dropbox.move-out"), Some(&f.id.to_variant()));
            menu.append_item(&item);
            let item = gio::MenuItem::new(Some(&said::notice(&f.delete)), None);
            item.set_action_and_target_value(Some("dropbox.delete"), Some(&f.id.to_variant()));
            menu.append_item(&item);
            let more = gtk::MenuButton::builder().icon_name("view-more-symbolic").menu_model(&menu).valign(gtk::Align::Center).tooltip_text(words::node_more()).build();
            more.add_css_class("flat");
            group.set_header_suffix(Some(&more));
            for file in &f.files {
                let row = adw::ActionRow::builder().title(glib_escape(&file.name)).activatable(true).build();
                row.add_prefix(&gtk::Image::from_icon_name(kind_icon(&file.kind)));
                row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
                let open = file.clone();
                row.connect_activated(move |_| bridge::send(Call::ShellOpen { r#ref: open.clone() }));
                group.add(&row);
            }
            self.body.append(&group);
        }
        if s.failed {
            let failed = gtk::Label::builder().label(words::share_failed()).wrap(true).xalign(0.0).build();
            failed.add_css_class("error");
            self.body.append(&failed);
        }
    }

    /// The delete the core asks: an `AdwAlertDialog` once per question; Delete trashes it (Undo in a toast), Cancel or a
    /// dismissal declines it.
    fn ask_delete(self: &Rc<Self>) {
        let shown = self.shown.borrow();
        let Some(s) = shown.as_ref() else { return };
        let Some(folder) = s.deleting.clone() else {
            *self.asked.borrow_mut() = None;
            return;
        };
        if self.asked.borrow().as_deref() == Some(folder.as_str()) {
            return;
        }
        *self.asked.borrow_mut() = Some(folder.clone());
        let (Some(title), Some(question)) = (&s.delete_title, &s.delete_question) else { return };
        let ask = adw::AlertDialog::builder().heading(said::notice(title)).body(said::notice(question)).build();
        ask.add_responses(&[("cancel", &words::entry_cancel()), ("delete", &said::key(&s.delete_accept))]);
        ask.set_response_appearance("delete", adw::ResponseAppearance::Destructive);
        ask.set_default_response(Some("cancel"));
        ask.set_close_response("cancel");
        let weak = Rc::downgrade(self);
        ask.connect_response(None, move |_, response| {
            let Some(inner) = weak.upgrade() else { return };
            let Some(r#box) = inner.id.get() else { return };
            if response != "delete" {
                bridge::send(Call::DropBoxCancelAsk { r#box });
                return;
            }
            let (folder, toasts) = (folder.clone(), inner.toasts.clone());
            glib::spawn_future_local(async move {
                match bridge::call(&Call::DropBoxDelete { r#box, folder }).await.map(|(a, _)| a) {
                    Ok(Answer::Trashed { notice, undoable }) => {
                        eprintln!("veyq: drop box folder trashed: {}, undo offered: {undoable}", notice.msg);
                        let toast = adw::Toast::new(&said::notice(&notice));
                        if undoable {
                            toast.set_button_label(Some(&words::trash_undo()));
                            let t = toasts.clone();
                            toast.connect_button_clicked(move |_| {
                                let t = t.clone();
                                glib::spawn_future_local(async move {
                                    crate::header::say(&t, bridge::call(&Call::DropBoxUndo { r#box }).await.map(|(a, _)| a), "DropBox.Undo");
                                });
                            });
                        }
                        toasts.add_toast(toast);
                    }
                    other => crate::header::say(&toasts, other, "DropBox.Delete"),
                }
            });
        });
        ask.present(Some(&self.root));
    }
}
