//! Settings' *Groups* and *Group invitations* (core's `SettingsSection.GROUPS` and `GROUP_INVITATIONS` over
//! `TenantGroups`; uc.spaces.add-group-member, uc.spaces.consent-group-add, uc.spaces.manage-group-members,
//! uc.spaces.rename-group, uc.spaces.delete-group), each a subpage of the Settings dialog as its Members is.
//!
//! *Groups* ([page]) lists the caller's groups, an owner's first: each its name (or *Untitled group*), its role, and
//! whether it is being deleted or behind its epoch; its "+" (`create_action`, where the core says the caller may create
//! one) asks for the name in an `AdwAlertDialog` (the web's `CreateGroupDialog`) and opens the new group's page. The list
//! is read each time it shows, so what a group's page changed is there on the way back.
//!
//! A group's page ([group]) is the web's `GroupMembersPanel` without the group's icon, its new lock and its links card
//! (gaps the tenant's Members leaves to the browser too): an owner's name editable in place, *Add member* by username,
//! the members with their menu button — *Make co-owner*, *Remove co-owner*, *Remove* (asked first in an
//! `AdwAlertDialog` with the option to turn off every link of the group: the group's removal-rotation) and *Withdraw* for
//! a proposal not yet answered —, and the danger zone's *Delete group* (asked first) or, deleted, *Restore group*. A
//! member who owns nothing there reads the core's read-only note. *Group invitations* ([invitations], the web's
//! `GroupInvitationsView`) offers *Accept* and *Decline* on each. What an act says is a toast of the dialog; each page is
//! read anew after an act that went through.

use std::rc::Rc;

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, wire::Answer, wire::Call, wire::GroupMember, wire::GroupPage, wire::GroupRow, wire::Groups, wire::Invitation, wire::Invitations, wire::Msg};
use crate::core_ui::create_action::create_action;
use crate::core_ui::received::said_msg;
use crate::core_ui::{avatars, said, words};

/// What a subpage needs of the Settings dialog: its toasts and its subpage stack.
#[derive(Clone)]
pub struct Host {
    pub toast: Rc<dyn Fn(adw::Toast)>,
    pub push: Rc<dyn Fn(&adw::NavigationPage)>,
}

impl Host {
    /// The host over [dialog], held weakly: the pages it pushes are the dialog's own.
    pub fn of(dialog: &adw::PreferencesDialog) -> Host {
        let (toasts, pushes) = (dialog.downgrade(), dialog.downgrade());
        Host {
            toast: Rc::new(move |t| {
                if let Some(d) = toasts.upgrade() {
                    d.add_toast(t);
                }
            }),
            push: Rc::new(move |p| {
                if let Some(d) = pushes.upgrade() {
                    d.push_subpage(p);
                }
            }),
        }
    }

    fn say(&self, outcome_notice: Option<&crate::bridge::wire::Notice>) {
        if let Some(n) = outcome_notice {
            (self.toast)(said::notice_toast(n));
        }
    }
}

struct Shell {
    page: adw::NavigationPage,
    column: gtk::Box,
}

/// A subpage's frame: its header bar (handed back for a creating action) and a clamped column.
fn shell(title: &str, name: &str) -> (Shell, adw::HeaderBar) {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).margin_top(24).margin_bottom(24).margin_start(12).margin_end(12).build();
    let view = adw::ToolbarView::new();
    let header = adw::HeaderBar::new();
    view.add_top_bar(&header);
    let clamp = adw::Clamp::builder().maximum_size(640).child(&column).build();
    view.set_content(Some(&gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build()));
    let page = adw::NavigationPage::builder().title(title).child(&view).build();
    page.set_widget_name(name);
    (Shell { page, column }, header)
}

// ---- the list ----

struct List {
    shell: Shell,
    host: Host,
    create: gtk::Button,
}

/// *Groups*: the caller's groups, read each time the subpage shows.
pub fn page(host: Host) -> adw::NavigationPage {
    let (shell, header) = shell(&words::groups_title(), "groups");
    let create = create_action(&words::groups_create_title(), || {});
    create.set_widget_name("groups-create");
    create.set_visible(false);
    header.pack_start(&create);
    let list = Rc::new(List { shell, host, create: create.clone() });
    let weak = Rc::downgrade(&list);
    create.connect_clicked(move |b| {
        if let Some(l) = weak.upgrade() {
            ask_create(&l, b);
        }
    });
    let page = list.shell.page.clone();
    // the list lives as long as its page: the page's `shown` holds it
    page.connect_shown(move |_| load_list(list.clone()));
    page
}

fn load_list(l: Rc<List>) {
    clear(&l.shell.column);
    l.shell.column.append(&adw::Spinner::new());
    glib::spawn_future_local(async move {
        let read = match bridge::call(&Call::SettingsGroups).await.map(|(a, _)| a) {
            Ok(Answer::GroupsListed { groups }) => Some(groups),
            other => {
                eprintln!("veyq: Settings.Groups answered {}", if other.is_ok() { "another answer" } else { "unreadable" });
                None
            }
        };
        draw_list(&l, read);
    });
}

fn draw_list(l: &Rc<List>, read: Option<Groups>) {
    clear(&l.shell.column);
    let Some(groups) = read.filter(|g| g.said.is_none()) else {
        l.create.set_visible(false);
        let line = label(&words::members_load_failed(), false);
        line.set_widget_name("groups-said");
        l.shell.column.append(&line);
        let again = gtk::Button::builder().label(words::people_retry()).halign(gtk::Align::Start).build();
        again.set_widget_name("groups-retry");
        let l2 = l.clone();
        again.connect_clicked(move |_| load_list(l2.clone()));
        l.shell.column.append(&again);
        return;
    };
    eprintln!(
        "veyq: groups: {} rows ({} owned, {} deleted, {} behind), create {}",
        groups.rows.len(),
        groups.rows.iter().filter(|r| r.owner).count(),
        groups.rows.iter().filter(|r| r.deleted).count(),
        groups.rows.iter().filter(|r| r.behind).count(),
        groups.can_create
    );
    l.create.set_visible(groups.can_create);
    if groups.rows.is_empty() {
        let line = label(&words::groups_none_owned(), true);
        line.set_widget_name("groups-none");
        l.shell.column.append(&line);
        return;
    }
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("boxed-list");
    list.set_widget_name("groups-list");
    for (i, row) in groups.rows.iter().enumerate() {
        list.append(&group_row(l, row, i));
    }
    l.shell.column.append(&list);
}

fn title_of(name: &str, word: Option<&Msg>) -> String {
    if name.is_empty() { word.map(said_msg).unwrap_or_else(words::groups_untitled) } else { name.to_owned() }
}

/// One group: its name, its role and what holds it (being deleted, behind its epoch); it opens the group's page.
fn group_row(l: &Rc<List>, row: &GroupRow, i: usize) -> adw::ActionRow {
    let title = title_of(&row.name, row.title_word.as_ref());
    let mut line = vec![if row.owner { words::members_role_owner() } else { words::members_role_member() }];
    if row.deleted {
        line.push(words::groups_pending_sub());
    }
    let r = adw::ActionRow::builder().title(glib::markup_escape_text(&title).as_str()).subtitle(glib::markup_escape_text(&said::joined(&line)).as_str()).activatable(true).build();
    // by its place in the list, never by a name: a pass reads the rows without their content
    r.set_widget_name(&format!("group-{i}"));
    r.add_prefix(&adw::Avatar::builder().text(title.as_str()).show_initials(true).size(40).build());
    if row.behind {
        let badge = gtk::Label::new(Some(&words::members_pending()));
        badge.add_css_class("caption");
        badge.add_css_class("dim-label");
        badge.set_wrap(true);
        badge.set_max_width_chars(18);
        badge.set_widget_name("group-behind");
        r.add_suffix(&badge);
    }
    r.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
    let (host, scope) = (l.host.clone(), row.scope_id.clone());
    r.connect_activated(move |_| (host.push)(&group(host.clone(), scope.clone(), title.clone())));
    r
}

/// *Create group*'s question (the web's `CreateGroupDialog`): the name, *Create* and Cancel; the new group's page opens.
fn ask_create(l: &Rc<List>, over: &gtk::Button) {
    let alert = adw::AlertDialog::builder().heading(words::groups_create_title()).build();
    let entry = gtk::Entry::builder().placeholder_text(words::groups_name_label()).activates_default(true).build();
    entry.set_widget_name("groups-create-name");
    entry.update_property(&[gtk::accessible::Property::Label(&words::groups_name_label())]);
    alert.set_extra_child(Some(&entry));
    alert.add_responses(&[("cancel", &words::signin_cancel()), ("create", &words::folder_create())]);
    alert.set_response_appearance("create", adw::ResponseAppearance::Suggested);
    alert.set_response_enabled("create", false);
    alert.set_default_response(Some("create"));
    alert.set_close_response("cancel");
    let a = alert.clone();
    entry.connect_changed(move |e| a.set_response_enabled("create", !e.text().trim().is_empty()));
    let l2 = l.clone();
    alert.connect_response(None, move |_, response| {
        if response != "create" {
            return;
        }
        let name = entry.text().trim().to_string();
        let l3 = l2.clone();
        glib::spawn_future_local(async move {
            match bridge::call(&Call::SettingsGroupCreate { name: name.clone() }).await.map(|(a, _)| a) {
                Ok(Answer::GroupChanged { outcome }) => {
                    eprintln!("veyq: group create: done {}", outcome.done);
                    l3.host.say(outcome.notice.as_ref());
                    if let (true, Some(scope)) = (outcome.done, outcome.scope_id) {
                        (l3.host.push)(&group(l3.host.clone(), scope, name));
                    } else if outcome.done {
                        load_list(l3.clone());
                    }
                }
                other => eprintln!("veyq: group create answered {}", if other.is_ok() { "another answer" } else { "unreadable" }),
            }
        });
    });
    alert.present(Some(over));
}

// ---- a group's page ----

struct Group {
    shell: Shell,
    host: Host,
    scope: String,
}

/// A group's page, [title] its name as the list showed it until the page is read.
pub fn group(host: Host, scope: String, title: String) -> adw::NavigationPage {
    let (shell, _) = shell(&title, "group");
    let g = Rc::new(Group { shell, host, scope });
    let page = g.shell.page.clone();
    load_group(g);
    page
}

fn load_group(g: Rc<Group>) {
    clear(&g.shell.column);
    g.shell.column.append(&adw::Spinner::new());
    glib::spawn_future_local(async move {
        let read = match bridge::call(&Call::SettingsGroup { scope_id: g.scope.clone() }).await.map(|(a, _)| a) {
            Ok(Answer::GroupPage { page }) => Some(page),
            other => {
                eprintln!("veyq: Settings.Group answered {}", if other.is_ok() { "another answer" } else { "unreadable" });
                None
            }
        };
        draw_group(&g, read);
    });
}

fn draw_group(g: &Rc<Group>, read: Option<GroupPage>) {
    clear(&g.shell.column);
    let failed = match &read {
        None => true,
        Some(p) => p.said == Some(Msg::MembersLoadFailed),
    };
    if failed {
        let line = label(&words::members_load_failed(), false);
        line.set_widget_name("group-said");
        g.shell.column.append(&line);
        let again = gtk::Button::builder().label(words::people_retry()).halign(gtk::Align::Start).build();
        again.set_widget_name("group-retry");
        let g2 = g.clone();
        again.connect_clicked(move |_| load_group(g2.clone()));
        g.shell.column.append(&again);
        return;
    }
    let Some(p) = read else { return };
    eprintln!(
        "veyq: group page: owner {}, deleted {}, {} members ({} owners, {} awaiting consent, {} pending), readonly {}",
        p.owner,
        p.deleted,
        p.members.len(),
        p.members.iter().filter(|m| m.owner).count(),
        p.members.iter().filter(|m| m.pending_consent).count(),
        p.members.iter().filter(|m| m.pending).count(),
        p.readonly_note.is_some()
    );
    g.shell.page.set_title(&title_of(&p.name, None));
    if let Some(note) = &p.readonly_note {
        let line = label(&said_msg(note), true);
        line.set_widget_name("group-readonly");
        g.shell.column.append(&line);
    }
    if p.owner {
        g.shell.column.append(&name_group(g, &p));
        g.shell.column.append(&add_group(g));
    }
    let members = adw::PreferencesGroup::builder().title(words::groups_members()).description(words::groups_members_sub()).build();
    members.set_widget_name("group-members");
    if p.members.is_empty() {
        members.add(&adw::ActionRow::builder().title(p.said.as_ref().map(said_msg).unwrap_or_else(words::members_empty)).build());
    }
    for m in &p.members {
        members.add(&member_row(g, m));
    }
    g.shell.column.append(&members);
    if p.owner {
        g.shell.column.append(&danger_zone(g, p.deleted));
    }
}

/// The owner's rename in place: the name's row, applied by its button or Enter.
fn name_group(g: &Rc<Group>, p: &GroupPage) -> adw::PreferencesGroup {
    let group = adw::PreferencesGroup::new();
    let row = adw::EntryRow::builder().title(words::groups_name_label()).text(p.name.as_str()).show_apply_button(true).build();
    row.set_widget_name("group-name");
    let g2 = g.clone();
    row.connect_apply(move |r| {
        let name = r.text().trim().to_string();
        if !name.is_empty() {
            run(&g2, "rename", Call::SettingsGroupRename { scope_id: g2.scope.clone(), name });
        }
    });
    group.add(&row);
    group
}

/// *Add member* by username (the web's inline form): the field and its *Add*, Enter too.
fn add_group(g: &Rc<Group>) -> adw::PreferencesGroup {
    let group = adw::PreferencesGroup::builder().description(words::groups_add_member_intro()).build();
    let row = adw::EntryRow::builder().title(words::groups_username_placeholder()).build();
    row.set_widget_name("group-add-username");
    let add = gtk::Button::builder().label(words::groups_add_member()).valign(gtk::Align::Center).sensitive(false).build();
    add.add_css_class("suggested-action");
    add.set_widget_name("group-add");
    row.add_suffix(&add);
    let a = add.clone();
    row.connect_changed(move |r| a.set_sensitive(!r.text().trim().is_empty()));
    let send = {
        let (g2, row) = (g.clone(), row.clone());
        Rc::new(move || {
            let username = row.text().trim().to_string();
            if !username.is_empty() {
                run(&g2, "add", Call::SettingsGroupAdd { scope_id: g2.scope.clone(), username });
            }
        })
    };
    let s = send.clone();
    add.connect_clicked(move |_| s());
    row.connect_entry_activated(move |_| send());
    group.add(&row);
    group
}

/// One member: the picture, the title, `@username` and the role, the status and pending badges; an owner's acts in
/// the row's menu as the core offers them.
fn member_row(g: &Rc<Group>, m: &GroupMember) -> adw::ActionRow {
    let mut line: Vec<String> = Vec::new();
    line.extend(m.username_line.clone());
    line.push(said_msg(&m.role_word));
    let r = adw::ActionRow::builder().title(glib::markup_escape_text(&m.title).as_str()).subtitle(glib::markup_escape_text(&said::joined(&line)).as_str()).build();
    r.set_widget_name(&format!("group-member-{}", m.user_id));
    r.add_prefix(&avatars::avatar(m.real_name.as_deref().unwrap_or(&m.username), Some(m.user_id.as_str()), 40));
    let status = gtk::Label::new(Some(&said_msg(&m.status_word)));
    status.add_css_class("caption");
    status.add_css_class(if m.pending_consent { "dim-label" } else { "success" });
    r.add_suffix(&status);
    if let Some(pending) = &m.pending_word {
        let badge = gtk::Label::new(Some(&said_msg(pending)));
        badge.add_css_class("caption");
        badge.add_css_class("dim-label");
        badge.set_wrap(true);
        badge.set_max_width_chars(18);
        badge.set_widget_name("group-member-pending");
        r.add_suffix(&badge);
    }
    if m.promote || m.demote || m.remove || m.rescind {
        let actions = gio::SimpleActionGroup::new();
        let menu = gio::Menu::new();
        let act = |name: &'static str, label: String, call: Call| {
            let a = gio::SimpleAction::new(name, None);
            let g2 = g.clone();
            a.connect_activate(move |_, _| run(&g2, name, call.clone()));
            actions.add_action(&a);
            menu.append(Some(&label), Some(&format!("member.{name}")));
        };
        let (scope, user) = (g.scope.clone(), m.user_id.clone());
        if m.promote {
            act("promote", words::groups_promote(), Call::SettingsGroupRole { scope_id: scope.clone(), user_id: user.clone(), owner: true });
        }
        if m.demote {
            act("demote", words::groups_demote(), Call::SettingsGroupRole { scope_id: scope.clone(), user_id: user.clone(), owner: false });
        }
        if m.rescind {
            act("rescind", words::groups_rescind(), Call::SettingsGroupRemove { scope_id: scope.clone(), user_id: user.clone(), revoke_links: false, rescind: true });
        }
        if m.remove {
            let a = gio::SimpleAction::new("remove", None);
            let (g2, w) = (g.clone(), r.clone());
            a.connect_activate(move |_, _| ask_remove(&g2, &w, user.clone()));
            actions.add_action(&a);
            menu.append(Some(&words::groups_remove()), Some("member.remove"));
        }
        r.insert_action_group("member", Some(&actions));
        let button = gtk::MenuButton::builder().icon_name("view-more-symbolic").menu_model(&menu).valign(gtk::Align::Center).build();
        button.add_css_class("flat");
        button.set_tooltip_text(Some(&m.title));
        button.set_widget_name(&format!("group-member-menu-{}", m.user_id));
        r.add_suffix(&button);
    }
    r
}

/// The removal's question (the web's inline confirm): its words, the opt-in to turn off every link, Remove and Cancel.
fn ask_remove(g: &Rc<Group>, over: &adw::ActionRow, user_id: String) {
    let alert = adw::AlertDialog::builder().heading(words::groups_remove_confirm()).build();
    let links = gtk::CheckButton::builder().label(words::groups_remove_revoke_links()).build();
    links.set_widget_name("group-remove-links");
    let hint = label(&words::groups_remove_revoke_links_hint(), true);
    hint.add_css_class("caption");
    let extra = gtk::Box::new(gtk::Orientation::Vertical, 6);
    extra.append(&links);
    extra.append(&hint);
    alert.set_extra_child(Some(&extra));
    alert.add_responses(&[("cancel", &words::signin_cancel()), ("remove", &words::groups_remove_confirm_yes())]);
    alert.set_response_appearance("remove", adw::ResponseAppearance::Destructive);
    alert.set_default_response(Some("cancel"));
    alert.set_close_response("cancel");
    let g2 = g.clone();
    alert.connect_response(None, move |_, response| {
        if response == "remove" {
            let call = Call::SettingsGroupRemove { scope_id: g2.scope.clone(), user_id: user_id.clone(), revoke_links: links.is_active(), rescind: false };
            run(&g2, if links.is_active() { "remove with links" } else { "remove" }, call);
        }
    });
    alert.present(Some(over));
}

/// The danger zone (the web's): *Delete group* asked first, or a deleted group's *Restore group*.
fn danger_zone(g: &Rc<Group>, deleted: bool) -> adw::PreferencesGroup {
    let group = adw::PreferencesGroup::builder()
        .title(words::groups_danger_zone())
        .description(if deleted { words::groups_pending_sub() } else { words::groups_delete_sub() })
        .build();
    group.set_widget_name("group-danger");
    let button = if deleted {
        let b = gtk::Button::builder().label(words::groups_restore()).halign(gtk::Align::Start).build();
        b.add_css_class("suggested-action");
        b.set_widget_name("group-restore");
        let g2 = g.clone();
        b.connect_clicked(move |_| run(&g2, "restore", Call::SettingsGroupDelete { scope_id: g2.scope.clone(), restore: true }));
        b
    } else {
        let b = gtk::Button::builder().label(words::groups_delete_group()).halign(gtk::Align::Start).build();
        b.add_css_class("destructive-action");
        b.set_widget_name("group-delete");
        let g2 = g.clone();
        b.connect_clicked(move |b| ask_delete(&g2, b));
        b
    };
    group.add(&button);
    group
}

fn ask_delete(g: &Rc<Group>, over: &gtk::Button) {
    let alert = adw::AlertDialog::builder().heading(words::groups_delete_confirm()).body(words::groups_delete_sub()).build();
    alert.add_responses(&[("cancel", &words::signin_cancel()), ("delete", &words::groups_delete_confirm_yes())]);
    alert.set_response_appearance("delete", adw::ResponseAppearance::Destructive);
    alert.set_default_response(Some("cancel"));
    alert.set_close_response("cancel");
    let g2 = g.clone();
    alert.connect_response(None, move |_, response| {
        if response == "delete" {
            run(&g2, "delete", Call::SettingsGroupDelete { scope_id: g2.scope.clone(), restore: false });
        }
    });
    alert.present(Some(over));
}

/// One act on the group: its words said as a toast, the page read anew where it went through.
fn run(g: &Rc<Group>, what: &'static str, call: Call) {
    g.shell.column.set_sensitive(false);
    let g = g.clone();
    glib::spawn_future_local(async move {
        let answer = bridge::call(&call).await.map(|(a, _)| a);
        g.shell.column.set_sensitive(true);
        match answer {
            Ok(Answer::GroupChanged { outcome }) => {
                eprintln!("veyq: group {what}: done {}", outcome.done);
                g.host.say(outcome.notice.as_ref());
                if outcome.done {
                    load_group(g.clone());
                }
            }
            other => eprintln!("veyq: group {what} answered {}", if other.is_ok() { "another answer" } else { "unreadable" }),
        }
    });
}

// ---- the invitations ----

struct Inbox {
    shell: Shell,
    host: Host,
}

/// *Group invitations*: the caller's pending proposals, each with *Accept* and *Decline*.
pub fn invitations(host: Host) -> adw::NavigationPage {
    let (shell, _) = shell(&words::invitations_title(), "group-invitations");
    let inbox = Rc::new(Inbox { shell, host });
    let page = inbox.shell.page.clone();
    load_inbox(inbox);
    page
}

fn load_inbox(i: Rc<Inbox>) {
    clear(&i.shell.column);
    i.shell.column.append(&label(&words::invitations_sub(), true));
    i.shell.column.append(&adw::Spinner::new());
    glib::spawn_future_local(async move {
        let read = match bridge::call(&Call::SettingsGroupInvitations).await.map(|(a, _)| a) {
            Ok(Answer::GroupInvitations { invitations }) => Some(invitations),
            other => {
                eprintln!("veyq: Settings.GroupInvitations answered {}", if other.is_ok() { "another answer" } else { "unreadable" });
                None
            }
        };
        draw_inbox(&i, read);
    });
}

fn draw_inbox(i: &Rc<Inbox>, read: Option<Invitations>) {
    clear(&i.shell.column);
    i.shell.column.append(&label(&words::invitations_sub(), true));
    let (rows, said) = match read {
        None => (Vec::new(), Some(Msg::InvitationsLoadFailed)),
        Some(inv) => (inv.rows, inv.said),
    };
    eprintln!("veyq: group invitations: {} rows", rows.len());
    if let Some(said) = said {
        let line = label(&said_msg(&said), false);
        line.set_widget_name("invitations-said");
        i.shell.column.append(&line);
        if said == Msg::InvitationsLoadFailed {
            let again = gtk::Button::builder().label(words::people_retry()).halign(gtk::Align::Start).build();
            again.set_widget_name("invitations-retry");
            let i2 = i.clone();
            again.connect_clicked(move |_| load_inbox(i2.clone()));
            i.shell.column.append(&again);
        }
        return;
    }
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("boxed-list");
    list.set_widget_name("invitations-list");
    for (n, inv) in rows.iter().enumerate() {
        list.append(&invitation_row(i, inv, n));
    }
    i.shell.column.append(&list);
}

fn invitation_row(i: &Rc<Inbox>, inv: &Invitation, n: usize) -> adw::ActionRow {
    let title = inv.name.clone().unwrap_or_else(|| inv.title_word.as_ref().map(said_msg).unwrap_or_else(words::invitations_unnamed_group));
    let r = adw::ActionRow::builder().title(glib::markup_escape_text(&title).as_str()).subtitle(glib::markup_escape_text(&said::notice(&inv.added_by_line)).as_str()).build();
    r.set_widget_name(&format!("invitation-{n}"));
    let accept = gtk::Button::builder().label(words::invitations_accept()).valign(gtk::Align::Center).build();
    accept.add_css_class("suggested-action");
    accept.set_widget_name(&format!("invitation-accept-{n}"));
    let decline = gtk::Button::builder().label(words::invitations_decline()).valign(gtk::Align::Center).build();
    decline.set_widget_name(&format!("invitation-decline-{n}"));
    for (button, yes) in [(&accept, true), (&decline, false)] {
        let (i2, scope) = (i.clone(), inv.scope_id.clone());
        button.connect_clicked(move |_| answer(&i2, scope.clone(), yes));
    }
    r.add_suffix(&decline);
    r.add_suffix(&accept);
    r
}

fn answer(i: &Rc<Inbox>, scope_id: String, accept: bool) {
    i.shell.column.set_sensitive(false);
    let i = i.clone();
    glib::spawn_future_local(async move {
        let answer = bridge::call(&Call::SettingsGroupInvitationAnswer { scope_id, accept }).await.map(|(a, _)| a);
        i.shell.column.set_sensitive(true);
        match answer {
            Ok(Answer::GroupChanged { outcome }) => {
                eprintln!("veyq: group invitation {}: done {}", if accept { "accepted" } else { "declined" }, outcome.done);
                i.host.say(outcome.notice.as_ref());
                if outcome.done {
                    load_inbox(i.clone());
                }
            }
            other => eprintln!("veyq: a group invitation's answer answered {}", if other.is_ok() { "another answer" } else { "unreadable" }),
        }
    });
}

fn clear(column: &gtk::Box) {
    while let Some(child) = column.first_child() {
        column.remove(&child);
    }
}

fn label(text: &str, dim: bool) -> gtk::Label {
    let l = gtk::Label::builder().label(text).wrap(true).xalign(0.0).build();
    if dim {
        l.add_css_class("dim-label");
    }
    l
}
