//! A level's selection (core's `LevelSelection`, the web's `LevelView` Select mode, inbox #1049), GNOME's way — Files'
//! selection mode: the header bar's toggle turns it on and off, each row a check button the row's activation turns, and an
//! action bar under the level with the count, *All*, *None*, every action of the core's plan in its order (enabled where
//! some selected row allows it, what it cannot reach under the bar) and *Cancel*. Each action opens the core's own sheet
//! over the selected rows it reaches: the move and copy sheet (`Select.Move`), the report (`Select.Report`), the delete's
//! one question (`Select.Delete`) and its one Undo.
use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, wire::Answer, wire::Call, wire::NodeAt, wire::NodeRef, wire::SelectionAction, wire::ShownSelection, wire::Update, wire::Watched};
use crate::core_ui::header::say;
use crate::core_ui::{move_sheet, report, said, words};

/// The level's Select tool: pressed while the level picks.
pub fn tool(on: bool) -> gtk::ToggleButton {
    let label = words::select_action();
    let b = gtk::ToggleButton::builder().icon_name("selection-mode-symbolic").active(on).tooltip_text(&label).build();
    b.update_property(&[gtk::accessible::Property::Label(&label)]);
    b.set_widget_name("level-select");
    b.connect_toggled(|b| bridge::send(if b.is_active() { Call::SelectStart } else { Call::SelectCancel }));
    b
}

/// A level's row while it picks: its name and kind, its check before them; activating the row turns its node.
pub fn row(n: &NodeRef, icon: &str, sel: &ShownSelection) -> adw::ActionRow {
    let row = adw::ActionRow::builder().title(glib::markup_escape_text(&n.name)).activatable(true).build();
    let check = gtk::CheckButton::builder().active(sel.selected.contains(&n.node_id)).valign(gtk::Align::Center).build();
    row.add_prefix(&gtk::Image::from_icon_name(icon));
    row.add_prefix(&check);
    row.set_activatable_widget(Some(&check));
    let id = n.node_id.clone();
    check.connect_toggled(move |_| bridge::send(Call::SelectToggle { node_id: id.clone() }));
    row
}

/// The selection's bar under the level: the count, *All*, *None*, the plan's actions in order, *Cancel*, and what an
/// action cannot reach under it.
pub fn bar(sel: &ShownSelection, toasts: &adw::ToastOverlay) -> gtk::Widget {
    let action_bar = gtk::ActionBar::new();
    action_bar.set_widget_name("selection-bar");
    let count = gtk::Label::builder().label(said::notice(&sel.count)).build();
    count.add_css_class("heading");
    count.update_property(&[gtk::accessible::Property::Label(&said::notice(&sel.count))]);
    action_bar.pack_start(&count);
    let all = gtk::Button::with_label(&words::select_all());
    all.connect_clicked(|_| bridge::send(Call::SelectAll));
    action_bar.pack_start(&all);
    let none = gtk::Button::with_label(&words::select_none());
    none.connect_clicked(|_| bridge::send(Call::SelectNone));
    action_bar.pack_start(&none);
    let actions = gtk::Box::new(gtk::Orientation::Horizontal, 6);
    for a in &sel.actions {
        let b = gtk::Button::builder().label(said::key(&a.word)).sensitive(a.enabled).build();
        let toasts = toasts.clone();
        // a sheet over the selection is titled by how many are selected while it reads
        let title = said::notice(&sel.count);
        match a.id {
            SelectionAction::Move => { b.connect_clicked(move |b| move_sheet::open_call(b, &title, Call::SelectMove { copy: false, deleted_name: words::pot_deleted_person() }, &toasts)); }
            SelectionAction::Copy => { b.connect_clicked(move |b| move_sheet::open_call(b, &title, Call::SelectMove { copy: true, deleted_name: words::pot_deleted_person() }, &toasts)); }
            SelectionAction::Report => {
                b.connect_clicked(move |b| report::open_by(b, &NodeAt { scope_id: String::new(), node_id: String::new(), kind: String::new(), name: title.clone() }, Call::SelectReport));
            }
            SelectionAction::Delete => {
                b.add_css_class("destructive-action");
                b.connect_clicked(move |b| delete(b, &toasts));
            }
        }
        actions.append(&b);
    }
    action_bar.pack_start(&actions);
    let cancel = gtk::Button::with_label(&words::entry_cancel());
    cancel.connect_clicked(|_| bridge::send(Call::SelectCancel));
    action_bar.pack_end(&cancel);
    let column = gtk::Box::new(gtk::Orientation::Vertical, 0);
    for a in &sel.actions {
        if let Some(n) = &a.cannot {
            let hint = gtk::Label::builder().label(said::notice(n)).xalign(0.0).wrap(true).margin_start(12).margin_end(12).build();
            hint.add_css_class("dim-label");
            hint.add_css_class("caption");
            column.append(&hint);
        }
    }
    column.append(&action_bar);
    column.update_property(&[gtk::accessible::Property::Label(&words::select_bar())]);
    column.upcast()
}

/// The selection's delete: its one question, then each selected node to the trash, what came of it said with its one Undo
/// while the core offers it.
fn delete(parent: &gtk::Button, toasts: &adw::ToastOverlay) {
    let (parent, toasts) = (parent.clone(), toasts.clone());
    glib::spawn_future_local(async move {
        let (id, title, question) = match bridge::call(&Call::SelectDelete).await.map(|(a, _)| a) {
            Ok(Answer::SelectionDelete { delete, title, question }) => (delete, title, question),
            other => return say(&toasts, other, "Select.Delete"),
        };
        let ask = adw::AlertDialog::builder().heading(said::notice(&title)).body(said::notice(&question)).build();
        ask.add_responses(&[("cancel", &words::entry_cancel()), ("delete", &words::entry_delete())]);
        ask.set_response_appearance("delete", adw::ResponseAppearance::Destructive);
        ask.set_default_response(Some("cancel"));
        ask.set_close_response("cancel");
        ask.connect_response(None, move |_, response| {
            if response != "delete" {
                bridge::send(Call::SelectDeleteClose { delete: id });
                return;
            }
            let toasts = toasts.clone();
            glib::spawn_future_local(async move {
                match bridge::call(&Call::SelectDeleteRun { delete: id }).await.map(|(a, _)| a) {
                    Ok(Answer::Trashed { notice, undoable }) => offer(&toasts, &notice, undoable, id),
                    other => {
                        say(&toasts, other, "Select.DeleteRun");
                        bridge::send(Call::SelectDeleteClose { delete: id });
                    }
                }
            });
        });
        ask.present(Some(&parent));
    });
}

/// What the delete said; with its Undo where the core offers one, the toast standing until the core's offer ends.
fn offer(toasts: &adw::ToastOverlay, notice: &crate::bridge::wire::Notice, undoable: bool, id: i64) {
    let toast = said::notice_toast(notice);
    if !undoable {
        toasts.add_toast(toast);
        bridge::send(Call::SelectDeleteClose { delete: id });
        return;
    }
    toast.set_button_label(Some(&words::trash_undo()));
    let t = toasts.clone();
    toast.connect_button_clicked(move |_| {
        let t = t.clone();
        glib::spawn_future_local(async move {
            let answered = bridge::call(&Call::SelectDeleteUndo { delete: id }).await.map(|(a, _)| a);
            say(&t, answered, "Select.DeleteUndo");
            bridge::send(Call::SelectDeleteClose { delete: id });
        });
    });
    toasts.add_toast(toast.clone());
    // the toast goes when the core's offer ends, and the core's delete with it
    let Some((watch, updates)) = bridge::watch(&Watched::SelectionDelete { delete: id }) else { return };
    glib::spawn_future_local(async move {
        let _watch = watch;
        let mut seen = false;
        while let Ok(update) = updates.recv().await {
            if let Ok(Update::SelectionDelete { undo, .. }) = update {
                if undo.is_some() {
                    seen = true;
                } else if seen {
                    toast.dismiss();
                    bridge::send(Call::SelectDeleteClose { delete: id });
                    return;
                }
            }
        }
    });
}
