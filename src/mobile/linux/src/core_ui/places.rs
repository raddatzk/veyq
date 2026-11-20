//! The bar's places (ADR 0122 decision 4: an application's places — Expenses' Entries, Overview and Settings of an open
//! Pot, Memories' list and Map), drawn GNOME's way as an inline view switcher above the body; which places stand and which
//! is chosen is the core's (`ShellShown.tabs`, `selectedTab`), a choice told to the shell (`Shell.Select`).
use adw::prelude::*;

use crate::bridge::{self, wire::BarTab, wire::Call, wire::ShownTab};
use crate::core_ui::said;

/// The bar's places as a toggle group above a page's or a list's body (GNOME's inline view switcher); a choice is the shell's.
pub fn places(tabs: &[ShownTab], selected: Option<&BarTab>) -> gtk::Widget {
    let group = adw::ToggleGroup::builder().halign(gtk::Align::Center).margin_top(12).margin_bottom(6).build();
    for t in tabs {
        group.add(adw::Toggle::builder().label(said::key(&t.word)).name(t.tab.key.clone()).build());
    }
    if let Some(s) = selected {
        group.set_active_name(Some(&s.key));
    }
    group.connect_active_name_notify(|g| {
        if let Some(key) = g.active_name() {
            bridge::send(Call::ShellSelect { tab: BarTab { key: key.into() } });
        }
    });
    group.upcast()
}
