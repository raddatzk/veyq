//! Settings' *Members* (core's `SettingsSection.MEMBERS` over `TenantMembers`, the web's `TenantMembersPanel` roster;
//! uc.spaces.remove-tenant-member): every member of the open space with their role and state, as a boxed list on a
//! subpage of the Settings dialog, as its Who's who is. A row's acts — *Make co-owner*, *Remove co-owner*, *Remove
//! member* — are its menu button's, offered as the core says; the removal asks first in an `AdwAlertDialog` with the
//! option to turn off every link of the space. What an act says is a toast of the dialog. Windows' `MembersDialog`.

use std::rc::Rc;

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, wire::Answer, wire::Call, wire::Roster, wire::RosterMember};
use crate::core_ui::received::said_msg;
use crate::core_ui::{avatars, said, words};

/// The subpage, read when it opens, at each *Try again* and after each act that went through; [toast] says an act's words.
pub fn page(toast: Rc<dyn Fn(adw::Toast)>) -> adw::NavigationPage {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).margin_top(24).margin_bottom(24).margin_start(12).margin_end(12).build();
    let view = adw::ToolbarView::new();
    view.add_top_bar(&adw::HeaderBar::new());
    let clamp = adw::Clamp::builder().maximum_size(640).child(&column).build();
    view.set_content(Some(&gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build()));
    let page = adw::NavigationPage::builder().title(words::members_title()).child(&view).build();
    page.set_widget_name("members");
    load(Rc::new(Page { column, toast }));
    page
}

struct Page {
    column: gtk::Box,
    toast: Rc<dyn Fn(adw::Toast)>,
}

fn load(p: Rc<Page>) {
    clear(&p.column);
    p.column.append(&label(&words::members_sub(), true));
    let spinner = adw::Spinner::new();
    p.column.append(&spinner);
    glib::spawn_future_local(async move {
        let read = match bridge::call(&Call::SettingsMembers).await.map(|(a, _)| a) {
            Ok(Answer::Members { roster }) => Some(roster),
            other => {
                eprintln!("veyq: Members.Read answered {}", if other.is_ok() { "another answer" } else { "unreadable" });
                None
            }
        };
        draw(&p, read);
    });
}

fn clear(column: &gtk::Box) {
    while let Some(child) = column.first_child() {
        column.remove(&child);
    }
}

fn draw(p: &Rc<Page>, read: Option<Roster>) {
    clear(&p.column);
    p.column.append(&label(&words::members_sub(), true));
    let (said, retry) = match &read {
        None => (Some(words::members_load_failed()), true),
        Some(r) => (r.said.as_ref().map(said_msg), r.retry),
    };
    if let Some(said) = said {
        let line = label(&said, false);
        line.set_widget_name("members-said");
        p.column.append(&line);
        if retry {
            let again = gtk::Button::builder().label(words::people_retry()).halign(gtk::Align::Start).build();
            again.set_widget_name("members-retry");
            let p2 = p.clone();
            again.connect_clicked(move |_| load(p2.clone()));
            p.column.append(&again);
        }
        return;
    }
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("boxed-list");
    list.set_widget_name("members-list");
    for row in read.map(|r| r.rows).unwrap_or_default() {
        list.append(&member_row(p, &row));
    }
    p.column.append(&list);
}

/// One member: the picture, the title, the line under it (`@username`, the address, the role) and the badges; the acts
/// the core offers in the row's menu.
fn member_row(p: &Rc<Page>, row: &RosterMember) -> adw::ActionRow {
    let mut line: Vec<String> = Vec::new();
    line.extend(row.username_line.clone());
    line.extend(row.email.clone());
    line.push(said_msg(&row.role_word));
    let r = adw::ActionRow::builder().title(glib::markup_escape_text(&row.title).as_str()).subtitle(glib::markup_escape_text(&said::joined(&line)).as_str()).build();
    r.set_widget_name(&format!("member-{}", row.user_id));
    r.add_prefix(&avatars::avatar(row.real_name.as_deref().unwrap_or(&row.username), Some(row.user_id.as_str()), 40));
    let status = gtk::Label::new(Some(&said_msg(&row.status_word)));
    status.add_css_class("caption");
    status.add_css_class("success");
    r.add_suffix(&status);
    if let Some(pending) = &row.pending_word {
        let badge = gtk::Label::new(Some(&said_msg(pending)));
        badge.add_css_class("caption");
        badge.add_css_class("dim-label");
        badge.set_widget_name("member-pending");
        r.add_suffix(&badge);
    }
    if row.promote || row.demote || row.remove {
        let group = gio::SimpleActionGroup::new();
        let menu = gio::Menu::new();
        let act = |name: &str, label: String, call: Call| {
            let a = gio::SimpleAction::new(name, None);
            let p2 = p.clone();
            a.connect_activate(move |_, _| run(&p2, call.clone()));
            group.add_action(&a);
            menu.append(Some(&label), Some(&format!("member.{name}")));
        };
        if row.promote {
            act("promote", words::members_promote(), Call::SettingsMemberRole { user_id: row.user_id.clone(), owner: true });
        }
        if row.demote {
            act("demote", words::members_demote(), Call::SettingsMemberRole { user_id: row.user_id.clone(), owner: false });
        }
        if row.remove {
            let a = gio::SimpleAction::new("remove", None);
            let (p2, user_id, w) = (p.clone(), row.user_id.clone(), r.clone());
            a.connect_activate(move |_, _| ask_remove(&p2, &w, user_id.clone()));
            group.add_action(&a);
            menu.append(Some(&words::members_remove()), Some("member.remove"));
        }
        r.insert_action_group("member", Some(&group));
        let button = gtk::MenuButton::builder().icon_name("view-more-symbolic").menu_model(&menu).valign(gtk::Align::Center).build();
        button.add_css_class("flat");
        button.set_tooltip_text(Some(&row.title));
        button.set_widget_name(&format!("member-menu-{}", row.user_id));
        r.add_suffix(&button);
    }
    r
}

/// The removal's question (the web's `askWithOptions`): its words, the opt-in to turn off every link, Remove and Cancel.
fn ask_remove(p: &Rc<Page>, over: &adw::ActionRow, user_id: String) {
    let alert = adw::AlertDialog::builder().heading(words::members_remove()).body(words::members_remove_confirm()).build();
    let links = gtk::CheckButton::builder().label(words::members_remove_revoke_links()).build();
    links.set_widget_name("member-remove-links");
    let hint = label(&words::members_remove_revoke_links_hint(), true);
    hint.add_css_class("caption");
    let extra = gtk::Box::new(gtk::Orientation::Vertical, 6);
    extra.append(&links);
    extra.append(&hint);
    alert.set_extra_child(Some(&extra));
    alert.add_responses(&[("cancel", &words::signin_cancel()), ("remove", &words::members_remove())]);
    alert.set_response_appearance("remove", adw::ResponseAppearance::Destructive);
    alert.set_default_response(Some("cancel"));
    alert.set_close_response("cancel");
    let p2 = p.clone();
    alert.connect_response(None, move |_, response| {
        if response == "remove" {
            run(&p2, Call::SettingsMemberRemove { user_id: user_id.clone(), revoke_links: links.is_active() });
        }
    });
    alert.present(Some(over));
}

/// One act: its words said as a toast, the roster read anew where it went through.
fn run(p: &Rc<Page>, call: Call) {
    p.column.set_sensitive(false);
    let p = p.clone();
    glib::spawn_future_local(async move {
        let answer = bridge::call(&call).await.map(|(a, _)| a);
        p.column.set_sensitive(true);
        match answer {
            Ok(Answer::MembersChanged { done, notice }) => {
                (p.toast)(said::notice_toast(&notice));
                if done {
                    load(p.clone());
                }
            }
            other => eprintln!("veyq: a member's act answered {}", if other.is_ok() { "another answer" } else { "unreadable" }),
        }
    });
}

fn label(text: &str, dim: bool) -> gtk::Label {
    let l = gtk::Label::builder().label(text).wrap(true).xalign(0.0).build();
    if dim {
        l.add_css_class("dim-label");
    }
    l
}
