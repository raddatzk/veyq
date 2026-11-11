//! A space's trash (ADR 0127 decision 2; core's `TrashSheet`, the web's `SpaceTrashDialog.vue`), GNOME's way: an
//! `AdwDialog` from the switcher's menu, each deleted subtree's root a row with Restore and "Delete forever", the latter
//! asked first in an `AdwAlertDialog` naming it. A row whose name was taken meanwhile asks for another under it. Every
//! state is the core's (`Trash.*`, watched while the dialog is open); the dialog only lays it out.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::TrashShown, wire::Update, wire::Watched};
use crate::home::{glib_escape, kind_icon};
use crate::{said, words};

struct Inner {
    id: Cell<Option<i64>>,
    watch: RefCell<Option<Watch>>,
    dialog: adw::Dialog,
    body: gtk::Box,
    /// The new name's entry, kept across the redraws so typing keeps its place.
    retry: adw::EntryRow,
    typing: Cell<bool>,
}

/// Opens the trash of [scope_id] over [parent]; the core's sheet closes with the dialog.
pub fn open(parent: &impl IsA<gtk::Widget>, scope_id: String) {
    let body = gtk::Box::new(gtk::Orientation::Vertical, 12);
    body.set_margin_top(12);
    body.set_margin_bottom(24);
    body.set_margin_start(12);
    body.set_margin_end(12);
    let clamp = adw::Clamp::builder().maximum_size(640).child(&body).build();
    let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build();
    let view = adw::ToolbarView::new();
    view.add_top_bar(&adw::HeaderBar::new());
    view.set_content(Some(&scroll));
    let dialog = adw::Dialog::builder().title(words::trash_title()).content_width(560).content_height(520).child(&view).build();
    let retry = adw::EntryRow::builder().title(words::trash_retry_name()).build();
    let inner = Rc::new(Inner { id: Cell::new(None), watch: RefCell::new(None), dialog: dialog.clone(), body, retry, typing: Cell::new(false) });
    let weak = Rc::downgrade(&inner);
    inner.retry.connect_changed(move |e| {
        let Some(inner) = weak.upgrade() else { return };
        if let (Some(trash), false) = (inner.id.get(), inner.typing.get()) {
            bridge::send(Call::TrashType { trash, name: e.text().to_string() });
        }
    });
    let held = inner.clone();
    dialog.connect_closed(move |_| {
        held.watch.borrow_mut().take();
        if let Some(trash) = held.id.take() {
            bridge::send(Call::TrashClose { trash });
        }
    });
    inner.draw(None);
    dialog.present(Some(parent));
    let weak = Rc::downgrade(&inner);
    glib::spawn_future_local(async move {
        let Ok((Answer::TrashOpened { trash }, _)) = bridge::call(&Call::TrashOpen { scope_id }).await else {
            eprintln!("veyq: the trash did not open in the core");
            return;
        };
        let Some(inner) = weak.upgrade() else {
            bridge::send(Call::TrashClose { trash });
            return;
        };
        inner.id.set(Some(trash));
        let Some((watch, updates)) = bridge::watch(&Watched::Trash { trash }) else { return };
        *inner.watch.borrow_mut() = Some(watch);
        drop(inner);
        follow(weak, updates).await;
    });
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Trash { shown, .. }) => {
                if let Some(s) = &shown {
                    eprintln!("veyq: trash: {} rows, busy {}, error {:?}, renaming {}", s.rows.as_ref().map_or(-1, |r| r.len() as i64),
                        s.busy.is_some(), s.error, s.renaming.is_some());
                }
                inner.draw(shown.as_ref());
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a trash update did not read: {:?}", e.0),
        }
    }
}

impl Inner {
    fn draw(self: &Rc<Self>, shown: Option<&TrashShown>) {
        if let Some(parent) = self.retry.parent().and_downcast::<gtk::ListBox>() {
            parent.remove(&self.retry);
        }
        while let Some(child) = self.body.first_child() {
            self.body.remove(&child);
        }
        let status = |title: String| adw::StatusPage::builder().icon_name("user-trash-symbolic").title(title).vexpand(true).build();
        let Some(s) = shown else {
            let spinner = adw::Spinner::builder().width_request(32).height_request(32).build();
            self.body.append(&adw::StatusPage::builder().child(&spinner).vexpand(true).build());
            return;
        };
        if s.failed {
            self.body.append(&status(words::trash_load_failed()));
            return;
        }
        let Some(rows) = s.rows.as_ref() else {
            self.body.append(&adw::StatusPage::builder().child(&adw::Spinner::new()).vexpand(true).build());
            return;
        };
        if rows.is_empty() {
            self.body.append(&status(words::trash_empty()));
            return;
        }
        let hint = gtk::Label::builder().label(words::trash_hint()).wrap(true).xalign(0.0).build();
        hint.add_css_class("dim-label");
        self.body.append(&hint);
        let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
        list.add_css_class("boxed-list");
        let Some(trash) = self.id.get() else { return };
        for row in rows {
            let busy = s.busy.as_deref() == Some(row.id.as_str());
            let subtitle = match (&s.error_row, &s.error) {
                (Some(r), Some(e)) if *r == row.id => said::key(e),
                _ => words::trash_row_dates(&said::instant_day(&row.deleted_at), &said::instant_day(&row.purge_after)),
            };
            let line = adw::ActionRow::builder().title(glib_escape(&row.name)).subtitle(glib_escape(&subtitle)).sensitive(!busy).build();
            line.add_prefix(&gtk::Image::from_icon_name(kind_icon(&row.kind)));
            if row.restorable && s.renaming.as_deref() != Some(row.id.as_str()) {
                let restore = gtk::Button::builder().label(words::trash_restore()).valign(gtk::Align::Center).build();
                restore.add_css_class("flat");
                let id = row.id.clone();
                restore.connect_clicked(move |_| bridge::send(Call::TrashRestore { trash, row: id.clone() }));
                line.add_suffix(&restore);
            }
            let purge = gtk::Button::builder().label(words::trash_purge()).valign(gtk::Align::Center).build();
            purge.add_css_class("flat");
            purge.add_css_class("error");
            let (id, name, dialog) = (row.id.clone(), row.name.clone(), self.dialog.clone());
            purge.connect_clicked(move |_| ask_purge(&dialog, trash, id.clone(), &name));
            line.add_suffix(&purge);
            list.append(&line);
            // where its name was taken: another name, typed here, and the restore under it
            if s.renaming.as_deref() == Some(row.id.as_str()) {
                self.typing.set(true);
                if self.retry.text() != s.retry_name.as_str() {
                    self.retry.set_text(&s.retry_name);
                }
                self.typing.set(false);
                let again = gtk::Button::builder().label(words::trash_restore()).valign(gtk::Align::Center).sensitive(row.retryable).build();
                again.add_css_class("suggested-action");
                let id = row.id.clone();
                again.connect_clicked(move |_| bridge::send(Call::TrashRetry { trash, row: id.clone() }));
                let cancel = gtk::Button::builder().label(words::trash_cancel()).valign(gtk::Align::Center).build();
                cancel.connect_clicked(move |_| bridge::send(Call::TrashCancelRename { trash }));
                let actions = adw::ActionRow::new();
                actions.add_suffix(&cancel);
                actions.add_suffix(&again);
                list.append(&self.retry);
                list.append(&actions);
            }
        }
        self.body.append(&list);
    }
}

/// "Delete forever?", naming the row; confirmed, the row itself goes to the core (`Trash.Purge`).
fn ask_purge(parent: &adw::Dialog, trash: i64, row: String, name: &str) {
    let ask = adw::AlertDialog::builder().heading(words::trash_purge_confirm_title()).body(words::trash_purge_confirm(name)).build();
    ask.add_responses(&[("cancel", &words::trash_cancel()), ("purge", &words::trash_purge())]);
    ask.set_response_appearance("purge", adw::ResponseAppearance::Destructive);
    ask.set_default_response(Some("cancel"));
    ask.set_close_response("cancel");
    ask.connect_response(None, move |_, response| {
        if response == "purge" {
            bridge::send(Call::TrashPurge { trash, row: row.clone() });
        }
    });
    ask.present(Some(parent));
}
