//! Settings' *Members* (core's `SettingsSection.MEMBERS` over `TenantMembers`, the web's `TenantMembersPanel` roster;
//! uc.spaces.remove-tenant-member): every member of the open space with their role and state, as a boxed list on a
//! subpage of the Settings dialog, as its Who's who is. A row's acts — *Make co-owner*, *Remove co-owner*, *Remove
//! member* — are its menu button's, offered as the core says; the removal asks first in an `AdwAlertDialog` with the
//! option to turn off every link of the space. What an act says is a toast of the dialog. Windows' `MembersDialog`.
//!
//! An owner's *Invite member* stands above the roster ([invites::invite], uc.spaces.add-member) and their pending invites
//! below it (uc.spaces.manage-member-invites; none: no group): each its address or label (or "Link invitation"), its
//! validity, the *Invited* badge and its menu button — *Rename* (a link invite only), *Send again* / *New link* (its new
//! link or code shown once, [invites::result_dialog]) and *Cancel invite* (asked first).
//!
//! Above everything the space's identity header ([scope_acts::header]: its icon, which an owner adds, changes or removes,
//! uc.spaces.set-space-icon); on each member row the trust badge and its note; the removal's question offers a new lock
//! too; and an owner's *Links* and *New lock* sections close the page ([scope_acts::links_and_lock]).

use std::rc::Rc;

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, wire::Answer, wire::Call, wire::PendingInvite, wire::Roster, wire::RosterMember};
use crate::core_ui::received::said_msg;
use crate::core_ui::groups::Host;
use crate::core_ui::{avatars, invites, said, scope_acts, words};

/// The subpage, read when it opens, at each *Try again* and after each act that went through; [host]'s toasts say an
/// act's words, its subpages hold the icon's crop. [tenant]: the open tenant's id, the scope of its icon (None: no header).
pub fn page(host: Host, tenant: Option<String>) -> adw::NavigationPage {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).margin_top(24).margin_bottom(24).margin_start(12).margin_end(12).build();
    let view = adw::ToolbarView::new();
    view.add_top_bar(&adw::HeaderBar::new());
    let clamp = adw::Clamp::builder().maximum_size(640).child(&column).build();
    view.set_content(Some(&gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build()));
    let page = adw::NavigationPage::builder().title(words::members_title()).child(&view).build();
    page.set_widget_name("members");
    load(Rc::new(Page { column, toast: host.toast.clone(), host, tenant }));
    page
}

struct Page {
    column: gtk::Box,
    toast: Rc<dyn Fn(adw::Toast)>,
    host: Host,
    tenant: Option<String>,
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
    let Some(roster) = read else { return };
    eprintln!(
        "veyq: members: {} rows ({} badged), owner acts {}, tenant {}",
        roster.rows.len(),
        roster.rows.iter().filter(|r| r.badge_word.is_some()).count(),
        roster.owner_acts,
        p.tenant.is_some()
    );
    if let Some(tenant) = &p.tenant {
        p.column.prepend(&scope_acts::header(&p.host, tenant, &roster.tenant_name, roster.owner_acts));
    }
    let again: Rc<dyn Fn()> = { let p = p.clone(); Rc::new(move || load(p.clone())) };
    for line in scope_acts::status_lines(&p.host, &roster.status, Call::SettingsMembersRequeue, again) {
        p.column.append(&line);
    }
    if roster.owner {
        // the owners' *Invite member* (uc.spaces.add-member), the web's button above the roster
        let content = adw::ButtonContent::builder().icon_name("contact-new-symbolic").label(words::members_invite()).build();
        let invite = gtk::Button::builder().child(&content).halign(gtk::Align::Start).build();
        invite.set_widget_name("members-invite");
        let p2 = p.clone();
        invite.connect_clicked(move |b| {
            let p3 = p2.clone();
            invites::invite(b, move || load(p3.clone()));
        });
        p.column.append(&invite);
    }
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("boxed-list");
    list.set_widget_name("members-list");
    for row in &roster.rows {
        list.append(&member_row(p, row));
    }
    p.column.append(&list);
    // the owners' pending invites (only an owner's roster carries them); none: no group
    if roster.owner && !roster.invites.is_empty() {
        let group = adw::PreferencesGroup::builder().title(words::members_pending_title()).description(words::members_pending_sub()).build();
        group.set_widget_name("members-pending");
        for invite in &roster.invites {
            group.add(&invite_row(p, invite));
        }
        p.column.append(&group);
    }
    if roster.owner_acts {
        let run_it: Rc<dyn Fn(Call)> = { let p = p.clone(); Rc::new(move |call| run(&p, call)) };
        for g in scope_acts::links_and_lock(&words::space_lock_sub(), &words::space_lock_confirm(), Call::SettingsMembersRevokeLinks, Call::SettingsMembersNewLock, run_it) {
            p.column.append(&g);
        }
    }
}

/// One pending invite: its title (the address, the label, or "Link invitation"), its validity, the *Invited* badge and
/// its acts in the row's menu.
fn invite_row(p: &Rc<Page>, invite: &PendingInvite) -> adw::ActionRow {
    let title = invite.title.clone().or_else(|| invite.title_word.as_ref().map(said_msg)).unwrap_or_default();
    let subtitle = match &invite.subtitle {
        Some(parts) => said::joined(&parts.iter().map(said::notice).collect::<Vec<_>>()),
        None => said::notice(&invite.expires),
    };
    let r = adw::ActionRow::builder().title(glib::markup_escape_text(&title).as_str()).subtitle(glib::markup_escape_text(&subtitle).as_str()).build();
    r.set_widget_name(&format!("invite-{}", invite.invite_id));
    r.add_prefix(&gtk::Image::from_icon_name(if invite.link { "insert-link-symbolic" } else { "mail-unread-symbolic" }));
    let badge = gtk::Label::new(Some(&words::members_status_invited()));
    badge.add_css_class("caption");
    badge.add_css_class("accent");
    r.add_suffix(&badge);
    let group = gio::SimpleActionGroup::new();
    let menu = gio::Menu::new();
    if invite.link {
        let a = gio::SimpleAction::new("rename", None);
        let (p2, inv, w) = (p.clone(), invite.clone(), r.clone());
        a.connect_activate(move |_, _| {
            let (p3, id) = (p2.clone(), inv.invite_id.clone());
            invites::rename(&w, &inv, move |label| run_invite(&p3, Call::SettingsInviteLabel { invite_id: id.clone(), label }));
        });
        group.add_action(&a);
        menu.append(Some(&words::members_label_edit()), Some("invite.rename"));
    }
    let a = gio::SimpleAction::new("reissue", None);
    let (p2, inv) = (p.clone(), invite.clone());
    a.connect_activate(move |_, _| run_invite(&p2, Call::SettingsInviteReissue { invite: inv.clone() }));
    group.add_action(&a);
    menu.append(Some(&if invite.link { words::members_reissue_link() } else { words::members_reissue() }), Some("invite.reissue"));
    let a = gio::SimpleAction::new("revoke", None);
    let (p2, id, w) = (p.clone(), invite.invite_id.clone(), r.clone());
    a.connect_activate(move |_, _| {
        let (p3, id) = (p2.clone(), id.clone());
        invites::ask_revoke(&w, move || run_invite(&p3, Call::SettingsInviteRevoke { invite_id: id.clone() }));
    });
    group.add_action(&a);
    menu.append(Some(&words::members_revoke()), Some("invite.revoke"));
    r.insert_action_group("invite", Some(&group));
    let button = gtk::MenuButton::builder().icon_name("view-more-symbolic").menu_model(&menu).valign(gtk::Align::Center).build();
    button.add_css_class("flat");
    button.set_tooltip_text(Some(&title));
    button.set_widget_name(&format!("invite-menu-{}", invite.invite_id));
    r.add_suffix(&button);
    r
}

/// An invite's act: a re-issue's new link or code shown once, any other's words said as a toast; the roster read anew
/// where it went through.
fn run_invite(p: &Rc<Page>, call: Call) {
    let reissue = matches!(call, Call::SettingsInviteReissue { .. });
    p.column.set_sensitive(false);
    let p = p.clone();
    glib::spawn_future_local(async move {
        let answer = bridge::call(&call).await.map(|(a, _)| a);
        p.column.set_sensitive(true);
        match answer {
            Ok(Answer::Invited { outcome }) => {
                eprintln!("veyq: an invite's act: done {}", outcome.done);
                if reissue && outcome.done && (outcome.address.is_some() || outcome.code.is_some()) {
                    invites::result_dialog(&p.column, &outcome);
                } else {
                    (p.toast)(said::notice_toast(&outcome.notice));
                }
                if outcome.done {
                    load(p.clone());
                }
            }
            other => eprintln!("veyq: an invite's act answered {}", if other.is_ok() { "another answer" } else { "unreadable" }),
        }
    });
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
    scope_acts::badge(&r, row.badge_word.as_ref(), row.badge_note.as_ref());
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

/// The removal's question (the web's `askWithOptions`): its words, the opt-ins to turn off every link and to give the
/// space a new lock (after the removal, the member left out of it), each unchecked, Remove and Cancel.
fn ask_remove(p: &Rc<Page>, over: &adw::ActionRow, user_id: String) {
    let alert = adw::AlertDialog::builder().heading(words::members_remove()).body(words::members_remove_confirm()).build();
    let links = gtk::CheckButton::builder().label(words::members_remove_revoke_links()).build();
    links.set_widget_name("member-remove-links");
    let hint = label(&words::members_remove_revoke_links_hint(), true);
    hint.add_css_class("caption");
    let lock = gtk::CheckButton::builder().label(words::space_lock_remove_option()).build();
    lock.set_widget_name("member-remove-lock");
    let lock_hint = label(&words::space_lock_remove_option_hint(), true);
    lock_hint.add_css_class("caption");
    let extra = gtk::Box::new(gtk::Orientation::Vertical, 6);
    extra.append(&links);
    extra.append(&hint);
    extra.append(&lock);
    extra.append(&lock_hint);
    alert.set_extra_child(Some(&extra));
    alert.add_responses(&[("cancel", &words::signin_cancel()), ("remove", &words::members_remove())]);
    alert.set_response_appearance("remove", adw::ResponseAppearance::Destructive);
    alert.set_default_response(Some("cancel"));
    alert.set_close_response("cancel");
    let p2 = p.clone();
    alert.connect_response(None, move |_, response| {
        if response == "remove" {
            run(&p2, Call::SettingsMemberRemove { user_id: user_id.clone(), revoke_links: links.is_active(), new_lock: lock.is_active() });
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
                eprintln!("veyq: a member's act: done {done}");
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
