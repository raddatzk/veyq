//! An entry's history (the web's `EntryHistory.vue`), GNOME's way: an `AdwDialog` headed by the entry's title and
//! amount, listing every revision the Pot holds, newest first — what it said, who wrote it and when (`Pot.History`, each
//! line the core's `historyLine`). A deleted entry says so, until when it can be restored (its receipts' erasure, read
//! on the core's clock) and offers Restore (`Pot.Restore`); the dialog closes once the restore was stored, and a
//! refusal is a toast in it. The entry editor (Expenses' next phase) takes this list as its history page.
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, wire::Answer, wire::Call, wire::HistoryShown};
use crate::home::glib_escape;
use crate::{said, words};

struct Inner {
    dialog: adw::Dialog,
    content: gtk::Box,
    toasts: adw::ToastOverlay,
}

/// Opens entry [entry]'s history in Pot [pot] over [parent]: [title] and [amount] head it; [said] takes the restore's
/// outcome once the dialog closed.
pub fn open(parent: &impl IsA<gtk::Widget>, pot: i64, entry: &str, title: &str, amount: Option<&str>, said: &adw::ToastOverlay) {
    let bar = adw::HeaderBar::new();
    bar.set_title_widget(Some(&adw::WindowTitle::new(title, amount.unwrap_or_default())));
    let content = gtk::Box::new(gtk::Orientation::Vertical, 18);
    content.set_margin_top(12);
    content.set_margin_bottom(24);
    content.set_margin_start(12);
    content.set_margin_end(12);
    content.append(&adw::Spinner::builder().width_request(32).height_request(32).halign(gtk::Align::Center).build());
    let clamp = adw::Clamp::builder().maximum_size(560).child(&content).build();
    let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).propagate_natural_height(true).child(&clamp).build();
    let view = adw::ToolbarView::new();
    view.add_top_bar(&bar);
    view.set_content(Some(&scroll));
    let toasts = adw::ToastOverlay::new();
    toasts.set_child(Some(&view));
    let dialog = adw::Dialog::builder().child(&toasts).content_width(480).content_height(520).title(title).build();
    let inner = Rc::new(Inner { dialog: dialog.clone(), content, toasts });
    // the dialog holds its state while it is open; the answers reach it weakly
    let held = std::cell::RefCell::new(Some(inner.clone()));
    dialog.connect_closed(move |_| {
        held.borrow_mut().take();
    });
    dialog.present(Some(parent));

    let (w, entry, said) = (Rc::downgrade(&inner), entry.to_owned(), said.clone());
    glib::spawn_future_local(async move {
        let answered = bridge::call(&Call::PotHistory { pot, entry: entry.clone() }).await.map(|(a, _)| a);
        let Some(i) = w.upgrade() else { return };
        match answered {
            Ok(Answer::EntryHistory { rows, deleted, restorable, until }) => {
                eprintln!("veyq: entry history: {} revision(s), deleted {deleted}, restorable {restorable}", rows.len());
                i.show(&rows, deleted, restorable, until, pot, &entry, &said);
            }
            other => {
                eprintln!("veyq: Pot.History answered {other:?}");
                i.dialog.close();
            }
        }
    });
}

impl Inner {
    #[allow(clippy::too_many_arguments)]
    fn show(self: &Rc<Self>, rows: &[HistoryShown], deleted: bool, restorable: bool, until: Option<i64>, pot: i64, entry: &str, said: &adw::ToastOverlay) {
        while let Some(child) = self.content.first_child() {
            self.content.remove(&child);
        }
        if deleted {
            let mut lines = vec![words::entry_deleted_hint()];
            match until {
                Some(t) if restorable => lines.push(words::entry_restorable_until(&said::moment(t))),
                Some(_) => lines.push(words::entry_not_restorable()),
                None => {}
            }
            for l in lines {
                self.content.append(&gtk::Label::builder().label(l).wrap(true).xalign(0.0).build());
            }
        }
        let history = adw::PreferencesGroup::builder().title(words::history_show()).build();
        for r in rows {
            let by = r.writer.clone().unwrap_or_else(words::history_unknown_writer);
            let row = adw::ActionRow::builder()
                .title(glib_escape(&said::line(&r.line)))
                .subtitle(glib_escape(&words::history_by(&by, &said::moment(r.at))))
                .build();
            history.add(&row);
        }
        self.content.append(&history);
        if restorable {
            let restore = gtk::Button::builder().label(words::entry_restore()).halign(gtk::Align::Center).build();
            restore.add_css_class("pill");
            restore.add_css_class("suggested-action");
            let (w, entry, said) = (Rc::downgrade(self), entry.to_owned(), said.clone());
            restore.connect_clicked(move |button| {
                button.set_sensitive(false);
                let (w, entry, said, button) = (w.clone(), entry.clone(), said.clone(), button.clone());
                glib::spawn_future_local(async move {
                    let answered = bridge::call(&Call::PotRestore { pot, entry }).await.map(|(a, _)| a);
                    eprintln!("veyq: pot restore: {answered:?}");
                    let Some(i) = w.upgrade() else {
                        crate::header::say(&said, answered, "Pot.Restore");
                        return;
                    };
                    match answered {
                        Ok(Answer::Said { notice: None }) => {
                            i.dialog.close();
                        }
                        other => {
                            button.set_sensitive(true);
                            crate::header::say(&i.toasts, other, "Pot.Restore");
                        }
                    }
                });
            });
            self.content.append(&restore);
        }
    }
}
