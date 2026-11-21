//! New Memory (`Shell.CreateMemory`) in the level shown, Memories' own create in New (registered as `win.create-memory`):
//! named in an alert dialog that asks again with the core's problem, *I was there* and *Determine location
//! automatically* offered where the core says; the Memory made opens (the web's `MemoryNodeDialog`).
use adw::prelude::*;
use gtk::glib;

use crate::apps::memories::memory_page;
use crate::bridge::{self, wire::Answer, wire::Call};
use crate::core_ui::header;
use crate::core_ui::uploads::Level;
use crate::core_ui::{said, words};

/// New Memory over [window] in [level] (none: nothing is made, as on a page that is no level).
pub fn open(window: &gtk::Window, toasts: &adw::ToastOverlay, level: Option<Level>) {
    let Some(level) = level else { return };
    let (toasts, window) = (toasts.clone(), window.clone());
    glib::spawn_future_local(async move {
        // *I was there* (ADR 0144 decision 2): offered ticked where the core says so, not remembered; *Determine location
        // automatically* (uc.memory.place A1): offered unticked where the map services are shown
        let (offered, offers_place) = match bridge::call(&Call::ShellOffersWasThere { scope_id: level.scope_id.clone() }).await.map(|(a, _)| a) {
            Ok(Answer::WasThere { offered, auto_place }) => (offered, auto_place),
            _ => (false, false),
        };
        let check_row = |active: bool, name: &str, title: String, subtitle: String| {
            let check = gtk::CheckButton::builder().active(active).valign(gtk::Align::Center).build();
            check.set_widget_name(name);
            let row = adw::ActionRow::builder().title(title).subtitle(subtitle).build();
            row.add_prefix(&check);
            row.set_activatable_widget(Some(&check));
            (check, row.upcast::<gtk::ListBoxRow>())
        };
        let (place, place_row) = check_row(false, "auto-place", words::memory_place_auto(), words::memory_place_auto_hint());
        let (check, was_there_row) = check_row(true, "was-there", words::memory_participants_was_there(), words::memory_participants_was_there_hint());
        let rows = [offers_place.then_some(place_row), offered.then_some(was_there_row)].into_iter().flatten().collect();
        eprintln!("veyq: new memory: I was there offered {offered}, the place {offers_place}");
        header::ask_name_with(window.upcast_ref(), &words::memory_create_memory_title(), &words::node_name(), "", None, &words::folder_create(), rows, move |name, again| {
        let (toasts, scope_id, parent_id) = (toasts.clone(), level.scope_id.clone(), level.parent_id.clone());
        let was_there = offered && check.is_active();
        let auto_place = offers_place && place.is_active();
        glib::spawn_future_local(async move {
            match bridge::call(&Call::ShellCreateMemory { scope_id, parent_id, name: name.clone(), was_there, auto_place }).await.map(|(a, _)| a) {
                Ok(Answer::Created { node: Some(node), .. }) => {
                    eprintln!("veyq: new memory: made");
                    memory_page::open_node(&node);
                }
                // made offline: it waits to be sent and shows where it was made; the dialog closes, the notice is said
                Ok(Answer::Created { queued: true, notice, .. }) => {
                    eprintln!("veyq: new memory: queued");
                    if let Some(n) = notice {
                        toasts.add_toast(said::notice_toast(&n));
                    }
                }
                Ok(Answer::Created { notice: Some(n), .. }) => {
                    eprintln!("veyq: new memory: {}", n.msg);
                    again(name, said::notice(&n));
                }
                other => header::say(&toasts, other, "Shell.CreateMemory"),
            }
        });
        });
    });
}
