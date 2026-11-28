//! Settings' *Who's who* (core's `SettingsSection.WHOS_WHO` over `PersonTrust.whosWho`, the web's `PeopleOverviewPanel`;
//! inbox #967): every other member of the open space with the one state their key has here — its badge, *New* beside it,
//! who verified them ("you" first), a new identity, a replaced key —, in the core's order, as a boxed list on a subpage of
//! the Settings dialog, as its Terms is. A row, and its *Compare safety numbers* where this account has not verified them,
//! opens their profile in the shell, where the compare lives; the Settings dialog closes for it. The core decides every
//! word and whether the compare is offered. Windows' `WhosWhoDialog`.

use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, wire::Answer, wire::Call, wire::WhosWho, wire::Other, wire::WhosWhoState};
use crate::core_ui::received::said_msg;
use crate::core_ui::{avatars, words};

/// The subpage, read when it opens and at each *Try again*; [leave] closes Settings once a person is chosen, [reset] opens
/// *Start a list over* where this device refuses its own pin store (`WhosWho.trustReset`, the web's `TrustResetLink`).
pub fn page(leave: Rc<dyn Fn()>, reset: Rc<dyn Fn()>) -> adw::NavigationPage {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).margin_top(24).margin_bottom(24).margin_start(12).margin_end(12).build();
    let view = adw::ToolbarView::new();
    view.add_top_bar(&adw::HeaderBar::new());
    let clamp = adw::Clamp::builder().maximum_size(640).child(&column).build();
    view.set_content(Some(&gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build()));
    let page = adw::NavigationPage::builder().title(words::whos_who_title()).child(&view).build();
    page.set_widget_name("whos-who");
    load(column, leave, reset);
    page
}

fn load(column: gtk::Box, leave: Rc<dyn Fn()>, reset: Rc<dyn Fn()>) {
    while let Some(child) = column.first_child() {
        column.remove(&child);
    }
    column.append(&label(&words::whos_who_sub(), true));
    let spinner = adw::Spinner::new();
    column.append(&spinner);
    glib::spawn_future_local(async move {
        let read = match bridge::call(&Call::TrustWhosWho).await.map(|(a, _)| a) {
            Ok(Answer::WhosWho { whos_who }) => Some(whos_who),
            other => {
                eprintln!("veyq: Trust.WhosWho answered {}", if other.is_ok() { "another answer" } else { "unreadable" });
                None
            }
        };
        column.remove(&spinner);
        draw(&column, read, leave, reset);
    });
}

fn draw(column: &gtk::Box, read: Option<WhosWho>, leave: Rc<dyn Fn()>, reset: Rc<dyn Fn()>) {
    let (said, retry) = match &read {
        None => (Some(words::whos_who_load_failed()), true),
        Some(w) => (w.said.as_ref().map(said_msg), w.retry),
    };
    if let Some(said) = said {
        let line = label(&said, false);
        line.set_widget_name("whos-who-said");
        column.append(&line);
        if retry {
            let again = gtk::Button::builder().label(words::people_retry()).halign(gtk::Align::Start).build();
            again.set_widget_name("whos-who-retry");
            let (c, l, r) = (column.clone(), leave.clone(), reset.clone());
            again.connect_clicked(move |_| load(c.clone(), l.clone(), r.clone()));
            column.append(&again);
        }
        if read.as_ref().is_some_and(|w| w.trust_reset) {
            let start = gtk::Button::builder().label(words::trust_reset_link()).halign(gtk::Align::Start).build();
            start.add_css_class("flat");
            start.set_widget_name("whos-who-trust-reset");
            start.connect_clicked(move |_| reset());
            column.append(&start);
        }
        return;
    }
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("boxed-list");
    list.set_widget_name("whos-who-list");
    for row in read.map(|w| w.people).unwrap_or_default() {
        list.append(&person_row(&row, leave.clone()));
    }
    column.append(&list);
}

/// One member: the picture, the name and `@username`, the state and what the core says beside it; the row opens the
/// profile, and *Compare safety numbers* where the core offers it.
fn person_row(row: &Other, leave: Rc<dyn Fn()>) -> adw::ActionRow {
    let r = adw::ActionRow::builder().title(glib::markup_escape_text(&row.name).as_str()).subtitle(glib::markup_escape_text(&lines(row)).as_str()).activatable(true).build();
    r.set_widget_name(&format!("whos-who-{}", row.user_id));
    r.upcast_ref::<gtk::Widget>().update_property(&[gtk::accessible::Property::Label(&words::whos_who_open_profile(&row.name))]);
    r.add_prefix(&avatars::avatar(&row.name, Some(row.user_id.as_str()), 40));
    if let Some(word) = &row.state_word {
        let badge = gtk::Label::new(Some(&said_msg(word)));
        badge.add_css_class("caption");
        badge.add_css_class(match row.state {
            WhosWhoState::CantConfirm => "error",
            WhosWhoState::Verified => "success",
            WhosWhoState::NotVerified | WhosWhoState::Unchecked => "dim-label",
        });
        badge.set_widget_name("whos-who-state");
        r.add_suffix(&badge);
    }
    let open = {
        let user_id = row.user_id.clone();
        move || {
            bridge::send(Call::ShellOpenPerson { user_id: user_id.clone() });
            leave();
        }
    };
    let open = Rc::new(open);
    if row.compare {
        let compare = gtk::Button::builder().label(words::whos_who_compare()).valign(gtk::Align::Center).build();
        compare.set_widget_name("whos-who-compare");
        let o = open.clone();
        compare.connect_clicked(move |_| o());
        r.add_suffix(&compare);
    }
    r.connect_activated(move |_| open());
    r
}

/// The subtitle: `@username`, *New*, then the lines the core's row says.
fn lines(row: &Other) -> String {
    let mut lines = vec![row.subtitle.clone()];
    if row.is_new {
        lines.push(words::safety_state_new());
    }
    if let Some(note) = &row.note {
        lines.push(said_msg(note));
    }
    if row.state == WhosWhoState::Verified {
        let mut by: Vec<String> = Vec::new();
        if row.verified_by_me {
            by.push(words::safety_verified_by_you());
        }
        by.extend(row.verified_by.iter().cloned());
        lines.push(words::safety_verified_by(&by.join(", ")));
    }
    if let Some(at) = &row.rotated_text {
        lines.push(words::trust_rotated(at));
    }
    if row.replaced {
        lines.push(words::safety_replaced());
    }
    lines.join("\n")
}

fn label(text: &str, dim: bool) -> gtk::Label {
    let l = gtk::Label::builder().label(text).wrap(true).xalign(0.0).build();
    if dim {
        l.add_css_class("dim-label");
    }
    l
}
