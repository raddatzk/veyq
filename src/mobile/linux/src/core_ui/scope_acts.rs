//! What the tenant's Members and a group's page share (the web's `TenantMembersPanel` and `GroupMembersPanel`): the scope's
//! identity header ([header]: its icon opened on this device, its initial otherwise, beside its name) with an owner's
//! *Add icon* / *Change icon* through the app's one square crop ([profile_edit::pick_square]) and *Remove icon* behind a
//! question, the member rows' trust badge ([badge]), and an owner's *Links* and *New lock* sections ([links_and_lock]),
//! each a danger button behind its question. The icon is the core's to seal and open (`Settings.Icon`, `IconSet`,
//! `IconClear` over `ScopeIcons`); what an act says is a toast of the Settings dialog.
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gdk, glib};

use crate::bridge::{self, wire::Answer, wire::Call, wire::Msg};
use crate::core_ui::groups::Host;
use crate::core_ui::profile_edit::{self, SquareHost};
use crate::core_ui::received::said_msg;
use crate::core_ui::{said, words};

/// The header of [scope_id] named [name]: the icon (asked of the core now and after each change of it), and where
/// [owner] its acts; [enabled] false while the page runs an act.
pub fn header(host: &Host, scope_id: &str, name: &str, owner: bool) -> gtk::Box {
    let avatar = adw::Avatar::builder().size(64).text(name).show_initials(true).build();
    avatar.set_widget_name("scope-icon");
    let title = gtk::Label::builder().label(name).wrap(true).xalign(0.0).build();
    title.add_css_class("title-2");
    title.set_widget_name("scope-name");
    let side = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).valign(gtk::Align::Center).hexpand(true).build();
    side.append(&title);
    let row = gtk::Box::builder().spacing(16).build();
    row.set_widget_name("scope-header");
    row.append(&avatar);
    row.append(&side);
    let acts = owner.then(|| {
        let choose = gtk::Button::builder().label(words::space_icon_add()).build();
        choose.set_widget_name("scope-icon-choose");
        let remove = gtk::Button::builder().label(words::space_icon_remove()).visible(false).build();
        remove.add_css_class("destructive-action");
        remove.set_widget_name("scope-icon-remove");
        let buttons = gtk::Box::builder().spacing(8).build();
        buttons.append(&choose);
        buttons.append(&remove);
        side.append(&buttons);
        (choose, remove)
    });
    let icon = Rc::new(Icon { host: host.clone(), scope_id: scope_id.to_owned(), avatar, acts });
    if let Some((choose, remove)) = &icon.acts {
        let i = icon.clone();
        choose.connect_clicked(move |_| i.pick());
        let i = icon.clone();
        remove.connect_clicked(move |b| i.ask_clear(b));
    }
    icon.read();
    row
}

struct Icon {
    host: Host,
    scope_id: String,
    avatar: adw::Avatar,
    /// An owner's *Add/Change icon* and *Remove icon*.
    acts: Option<(gtk::Button, gtk::Button)>,
}

impl Icon {
    /// The icon as the core opens it now (`NO_PICTURE`: none, the initial); the owner's buttons follow whether one is held.
    fn read(self: &Rc<Self>) {
        let i = self.clone();
        glib::spawn_future_local(async move {
            let answered = bridge::call(&Call::SettingsIcon { scope_id: i.scope_id.clone() }).await;
            let texture = match answered {
                Ok((Answer::Bytes { .. }, Some(bytes))) => match gdk::Texture::from_bytes(&glib::Bytes::from_owned(bytes)) {
                    Ok(t) => Some(t),
                    Err(e) => {
                        eprintln!("veyq: a scope's icon did not decode ({e})");
                        None
                    }
                },
                other => {
                    eprintln!("veyq: Settings.Icon: {}", crate::core_ui::manage::answer_kind(&other.map(|(a, _)| a)));
                    None
                }
            };
            eprintln!("veyq: a scope's icon: held {}", texture.is_some());
            i.avatar.set_custom_image(texture.as_ref());
            if let Some((choose, remove)) = &i.acts {
                choose.set_label(&if texture.is_some() { words::space_icon_change() } else { words::space_icon_add() });
                remove.set_visible(texture.is_some());
            }
        });
    }

    /// *Add icon* / *Change icon*: the square pick, its picture set as the icon (`Settings.IconSet`).
    fn pick(self: &Rc<Self>) {
        let title = self.acts.as_ref().map(|(c, _)| c.label().map(|l| l.to_string()).unwrap_or_default()).unwrap_or_default();
        let host = SquareHost {
            parent: self.avatar.clone().upcast(),
            push: self.host.push.clone(),
            pop: self.host.pop.clone(),
            toast: self.host.toast.clone(),
        };
        let i = self.clone();
        profile_edit::pick_square(host, title.clone(), title, Rc::new(move |path, pose| {
            let i = i.clone();
            Box::pin(async move {
                let call = Call::SettingsIconSet { scope_id: i.scope_id.clone(), path, pose };
                i.changed(bridge::call(&call).await.map(|(a, _)| a), "Settings.IconSet")
            })
        }));
    }

    /// *Remove icon*'s question, then `Settings.IconClear`.
    fn ask_clear(self: &Rc<Self>, over: &gtk::Button) {
        let i = self.clone();
        ask(over, &words::space_icon_remove(), &words::space_icon_remove_confirm(), &words::space_icon_remove(), "scope-icon-remove-confirm", move || {
            let i = i.clone();
            glib::spawn_future_local(async move {
                let answered = bridge::call(&Call::SettingsIconClear { scope_id: i.scope_id.clone() }).await.map(|(a, _)| a);
                i.changed(answered, "Settings.IconClear");
            });
        });
    }

    /// An icon act's answer said; the icon read anew where it went through.
    fn changed(self: &Rc<Self>, answered: Result<Answer, bridge::Unreadable>, what: &str) -> bool {
        match answered {
            Ok(Answer::IconChanged { done, notice }) => {
                eprintln!("veyq: {what}: done {done}");
                (self.host.toast)(said::notice_toast(&notice));
                if done {
                    self.read();
                }
                done
            }
            other => {
                eprintln!("veyq: {what}: {}", crate::core_ui::manage::answer_kind(&other));
                (self.host.toast)(said::word_toast("space_icon_failed", &words::space_icon_failed()));
                false
            }
        }
    }
}

/// A member row's trust badge (`badgeWord`) and its note (`badgeNote`, an anomaly's) as the row's suffix; none: nothing.
pub fn badge(row: &adw::ActionRow, word: Option<&Msg>, note: Option<&Msg>) {
    let Some(word) = word else { return };
    let col = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(2).valign(gtk::Align::Center).build();
    let badge = gtk::Label::builder().label(said_msg(word)).xalign(1.0).build();
    badge.add_css_class("caption");
    badge.add_css_class(if note.is_some() { "warning" } else { "accent" });
    badge.set_widget_name("member-trust");
    col.append(&badge);
    if let Some(note) = note {
        let line = gtk::Label::builder().label(said_msg(note)).wrap(true).max_width_chars(24).xalign(1.0).build();
        line.add_css_class("caption");
        line.add_css_class("dim-label");
        line.set_widget_name("member-trust-note");
        col.append(&line);
    }
    row.add_suffix(&col);
}

/// An owner's *Links* and *New lock* sections: *Turn off every link* ([links]) and *Give it a new lock* ([lock]), each
/// behind its question; [lock_sub] and [lock_confirm] the space's or the group's words; [run] the page's act runner.
pub fn links_and_lock(lock_sub: &str, lock_confirm: &str, links: Call, lock: Call, run: Rc<dyn Fn(Call)>) -> [adw::PreferencesGroup; 2] {
    let section = |title: String, sub: &str, name: &str, label: String, icon: &str| {
        let group = adw::PreferencesGroup::builder().title(title).description(sub).build();
        group.set_widget_name(name);
        let content = adw::ButtonContent::builder().icon_name(icon).label(label).build();
        let button = gtk::Button::builder().child(&content).halign(gtk::Align::Start).build();
        button.add_css_class("destructive-action");
        group.add(&button);
        (group, button)
    };
    let (links_group, links_button) = section(words::space_links_title(), &words::space_links_sub(), "scope-links", words::space_links_revoke_all(), "network-offline-symbolic");
    links_button.set_widget_name("scope-links-revoke");
    let r = run.clone();
    links_button.connect_clicked(move |b| {
        let (r, call) = (r.clone(), links.clone());
        let all = words::space_links_revoke_all();
        ask(b, &all, &words::space_links_revoke_all_confirm(), &all, "scope-links-revoke-confirm", move || r(call.clone()));
    });
    let (lock_group, lock_button) = section(words::space_lock_title(), lock_sub, "scope-lock", words::space_lock_action(), "system-lock-screen-symbolic");
    lock_button.set_widget_name("scope-new-lock");
    let confirm = lock_confirm.to_owned();
    lock_button.connect_clicked(move |b| {
        let (r, call) = (run.clone(), lock.clone());
        ask(b, &words::space_lock_confirm_title(), &confirm, &words::space_lock_action(), "scope-new-lock-confirm", move || r(call.clone()));
    });
    [links_group, lock_group]
}

/// A danger act's question: [heading], [body], [accept] in the destructive look beside *Cancel*; [then] on accept.
fn ask(over: &impl IsA<gtk::Widget>, heading: &str, body: &str, accept: &str, name: &str, then: impl Fn() + 'static) {
    let alert = adw::AlertDialog::builder().heading(heading).body(body).build();
    alert.set_widget_name(name);
    alert.add_responses(&[("cancel", &words::signin_cancel()), ("accept", accept)]);
    alert.set_response_appearance("accept", adw::ResponseAppearance::Destructive);
    alert.set_default_response(Some("cancel"));
    alert.set_close_response("cancel");
    alert.connect_response(None, move |_, response| {
        if response == "accept" {
            then();
        }
    });
    alert.present(Some(over));
}
