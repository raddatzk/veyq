//! *Invite member* and what an invite made (uc.spaces.add-member, uc.spaces.manage-member-invites; the web's
//! `InviteMemberDialog` and `InviteLinkResult` over core's `TenantInvites`), GNOME's way: an `AdwDialog` whose
//! `AdwToggleGroup` switches the two ways — a link first (ADR 0129 decision 1), then a mail —, the link's optional label
//! only owners see, the mail's address and its optional own password (masked, at least 10 characters) instead of a
//! generated code. While it runs it says so; a refusal stands under the form. What it made is shown once ([result]): a
//! link inside the message around it — editable, as the web's — under the warning that whoever holds it can join, with
//! *Copy* and *Open in email* (`mailto:` with the invitation's subject); or a mailed invite's words and its code with
//! *Copy code* (none where the owner chose a password). A pending invite's *Rename* asks in an `AdwAlertDialog`
//! ([rename]), its *Cancel invite* too ([ask_revoke]); a re-issue's new link or code is shown in a dialog of its own
//! ([result_dialog]). The link and the code are made to be handed on (src/mobile/CLAUDE.md § Secrets on screen): nothing
//! here keeps or logs them. Members' rows draw the pending list (`members.rs`).
use std::cell::Cell;
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, wire::Answer, wire::Call, wire::NoticeArg, wire::PendingInvite, wire::TenantInvitesOutcome};
use crate::core_ui::{said, words};

/// The least a password the owner chooses may be, in characters (`PasswordChange.MIN_PASSWORD_LENGTH`, as the web's form).
const MIN_PASSWORD: usize = 10;

/// *Invite member* over [over]; [done] runs once an invite went out (the roster and its pending list are read anew).
pub fn invite(over: &impl IsA<gtk::Widget>, done: impl Fn() + 'static) {
    let toasts = adw::ToastOverlay::new();
    let header = adw::HeaderBar::new();
    let view = adw::ToolbarView::new();
    view.add_top_bar(&header);
    let column = column();
    view.set_content(Some(&scrolled(&column)));
    toasts.set_child(Some(&view));
    let dialog = adw::Dialog::builder().title(words::members_invite()).content_width(540).content_height(620).child(&toasts).build();
    dialog.set_widget_name("invite");

    let way = adw::ToggleGroup::builder().halign(gtk::Align::Center).build();
    way.add(adw::Toggle::builder().label(words::members_way_link()).name("link").build());
    way.add(adw::Toggle::builder().label(words::members_way_mail()).name("mail").build());
    way.set_active_name(Some("link"));
    way.set_widget_name("invite-way");
    let intro = dim(&words::members_link_intro());

    let label_row = adw::EntryRow::builder().title(words::members_link_label()).build();
    label_row.set_widget_name("invite-link-label");
    let link_group = adw::PreferencesGroup::builder().description(words::members_link_label_hint()).build();
    link_group.add(&label_row);

    let email_row = adw::EntryRow::builder().title(words::members_email()).input_purpose(gtk::InputPurpose::Email).build();
    email_row.set_widget_name("invite-email");
    let password_row = adw::PasswordEntryRow::builder().title(words::members_invite_password_label()).visible(false).build();
    password_row.set_widget_name("invite-password");
    let mail_group = adw::PreferencesGroup::builder().visible(false).build();
    mail_group.add(&email_row);
    mail_group.add(&password_row);
    let use_password = gtk::Button::builder().label(words::members_invite_use_password()).halign(gtk::Align::Start).visible(false).build();
    use_password.add_css_class("flat");
    use_password.set_widget_name("invite-use-password");
    let note = dim(&words::members_invite_password_note());
    note.set_visible(false);

    let working = gtk::Box::builder().spacing(12).visible(false).build();
    working.append(&adw::Spinner::new());
    working.append(&dim(&words::members_invite_securing()));
    let refused = error_label();
    refused.set_widget_name("invite-refused");
    let submit = pill(&words::members_link_create(), "suggested-action");
    submit.set_widget_name("invite-submit");

    let form = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).build();
    for w in [way.upcast_ref::<gtk::Widget>(), intro.upcast_ref(), link_group.upcast_ref(), mail_group.upcast_ref(), use_password.upcast_ref(), note.upcast_ref()] {
        form.append(w);
    }
    column.append(&form);
    for w in [working.upcast_ref::<gtk::Widget>(), refused.upcast_ref(), submit.upcast_ref()] {
        column.append(w);
    }

    let own = Rc::new(Cell::new(false));
    let mail = Rc::new(Cell::new(false));
    // what Submit may send: a link always, a mail with an address (and a password long enough where one is chosen)
    let check = {
        let (s, e, p, own, mail) = (submit.clone(), email_row.clone(), password_row.clone(), own.clone(), mail.clone());
        Rc::new(move || {
            let ok = !mail.get() || (!e.text().trim().is_empty() && (!own.get() || p.text().chars().count() >= MIN_PASSWORD));
            s.set_sensitive(ok);
        })
    };
    {
        let (intro, lg, mg, up, note, s, r, own, mail, check, p) =
            (intro.clone(), link_group.clone(), mail_group.clone(), use_password.clone(), note.clone(), submit.clone(), refused.clone(), own.clone(), mail.clone(), check.clone(), password_row.clone());
        way.connect_active_name_notify(move |g| {
            let is_mail = g.active_name().as_deref() == Some("mail");
            mail.set(is_mail);
            intro.set_label(&if is_mail { words::members_invite_intro() } else { words::members_link_intro() });
            lg.set_visible(!is_mail);
            mg.set_visible(is_mail);
            up.set_visible(is_mail);
            note.set_visible(is_mail && own.get());
            p.set_visible(own.get());
            s.set_label(&if is_mail { words::members_invite_submit() } else { words::members_link_create() });
            r.set_visible(false);
            check();
        });
    }
    {
        let (p, note, own, check) = (password_row.clone(), note.clone(), own.clone(), check.clone());
        use_password.connect_clicked(move |b| {
            own.set(!own.get());
            if !own.get() {
                p.set_text("");
            }
            p.set_visible(own.get());
            note.set_visible(own.get());
            b.set_label(&if own.get() { words::members_invite_use_code() } else { words::members_invite_use_password() });
            check();
        });
    }
    {
        let c = check.clone();
        email_row.connect_changed(move |_| c());
        let c = check.clone();
        password_row.connect_changed(move |_| c());
    }

    let done: Rc<dyn Fn()> = Rc::new(done);
    let send = {
        let (column, form, working, refused, submit, header, toasts, dialog) =
            (column.clone(), form.clone(), working.clone(), refused.clone(), submit.clone(), header.clone(), toasts.clone(), dialog.clone());
        let (label_row, email_row, password_row, own, mail) = (label_row.clone(), email_row.clone(), password_row.clone(), own.clone(), mail.clone());
        Rc::new(move || {
            if !submit.is_sensitive() || working.is_visible() {
                return;
            }
            let is_mail = mail.get();
            let call = if is_mail {
                let password = own.get().then(|| password_row.text().to_string());
                Call::SettingsInviteMail { email: email_row.text().trim().to_string(), password }
            } else {
                let label = label_row.text().trim().to_string();
                Call::SettingsInviteLink { label: (!label.is_empty()).then_some(label) }
            };
            form.set_sensitive(false);
            submit.set_sensitive(false);
            working.set_visible(true);
            refused.set_visible(false);
            let (column, form, working, refused, submit, header, toasts, dialog, password_row, done) =
                (column.clone(), form.clone(), working.clone(), refused.clone(), submit.clone(), header.clone(), toasts.clone(), dialog.clone(), password_row.clone(), done.clone());
            glib::spawn_future_local(async move {
                let answer = bridge::call(&call).await.map(|(a, _)| a);
                working.set_visible(false);
                form.set_sensitive(true);
                submit.set_sensitive(true);
                match answer {
                    Ok(Answer::Invited { outcome }) if outcome.done => {
                        password_row.set_text("");
                        eprintln!("veyq: invite: made ({})", if is_mail { "mail" } else { "link" });
                        done();
                        clear(&column);
                        column.append(&result(&outcome, &toasts));
                        let finish = gtk::Button::builder().label(words::share_done()).build();
                        finish.add_css_class("suggested-action");
                        finish.set_widget_name("invite-done");
                        let d = dialog.clone();
                        finish.connect_clicked(move |_| {
                            d.close();
                        });
                        header.pack_end(&finish);
                    }
                    Ok(Answer::Invited { outcome }) => {
                        refused.set_label(&said::notice(&outcome.notice));
                        refused.set_visible(true);
                    }
                    other => {
                        eprintln!("veyq: invite answered {}", if other.is_ok() { "another answer" } else { "unreadable" });
                        refused.set_label(&if is_mail { words::members_invite_failed() } else { words::members_link_failed() });
                        refused.set_visible(true);
                    }
                }
            });
        })
    };
    let s = send.clone();
    submit.connect_clicked(move |_| s());
    let s = send.clone();
    label_row.connect_entry_activated(move |_| s());
    let s = send.clone();
    email_row.connect_entry_activated(move |_| s());
    password_row.connect_entry_activated(move |_| send());
    dialog.present(Some(over));
}

/// What an invite or a re-issue made, shown once ([outcome] done): the link inside its message, or the mail's code.
pub fn result(outcome: &TenantInvitesOutcome, toasts: &adw::ToastOverlay) -> gtk::Box {
    let col = column();
    let status = gtk::Box::builder().spacing(12).build();
    status.append(&gtk::Image::from_icon_name("object-select-symbolic"));
    let said_line = text(&said::notice(&outcome.notice));
    said_line.set_widget_name("invite-result-notice");
    status.append(&said_line);
    col.append(&status);
    if outcome.address.is_some() {
        let message = outcome.message.as_ref().map(said::notice).unwrap_or_default();
        let heading = text(&words::members_link_message_label());
        heading.add_css_class("heading");
        col.append(&heading);
        let buffer = gtk::TextBuffer::new(None);
        buffer.set_text(&message);
        let edit = gtk::TextView::builder().buffer(&buffer).wrap_mode(gtk::WrapMode::WordChar).top_margin(12).bottom_margin(12).left_margin(12).right_margin(12).build();
        edit.set_widget_name("invite-link-message");
        edit.update_property(&[gtk::accessible::Property::Label(&words::members_link_message_label())]);
        let frame = gtk::Frame::builder().child(&gtk::ScrolledWindow::builder().min_content_height(140).hscrollbar_policy(gtk::PolicyType::Never).child(&edit).build()).build();
        col.append(&frame);
        let warning = text(&words::members_link_warning());
        warning.add_css_class("error");
        col.append(&warning);
        let buttons = gtk::Box::builder().spacing(12).halign(gtk::Align::Center).build();
        let copy = pill(&words::members_link_copy(), "suggested-action");
        copy.set_widget_name("invite-link-copy");
        let (b, t) = (buffer.clone(), toasts.clone());
        copy.connect_clicked(move |w| {
            w.clipboard().set_text(&all_of(&b));
            eprintln!("veyq: invite: the message with its link copied");
            t.add_toast(said::copied_toast(&words::members_link_copied()));
        });
        buttons.append(&copy);
        let mail = pill(&words::members_link_mail(), "");
        mail.set_widget_name("invite-link-mail");
        let space = outcome.message.as_ref().and_then(|m| m.args.first()).and_then(|a| match a {
            NoticeArg::Text { text } => Some(text.clone()),
            _ => None,
        });
        let b = buffer.clone();
        mail.connect_clicked(move |w| {
            let subject = words::members_link_mail_subject(space.as_deref().unwrap_or_default());
            let uri = format!("mailto:?subject={}&body={}", glib::Uri::escape_string(&subject, None, false), glib::Uri::escape_string(&all_of(&b), None, false));
            let window = w.root().and_downcast::<gtk::Window>();
            gtk::UriLauncher::new(&uri).launch(window.as_ref(), gio::Cancellable::NONE, |r| {
                if let Err(e) = r {
                    eprintln!("veyq: invite: no mail app opened ({e})");
                }
            });
        });
        buttons.append(&mail);
        col.append(&buttons);
    } else if outcome.mailed_to.is_some() {
        col.append(&text(&if outcome.own_choice { words::members_invite_sent_password_intro() } else { words::members_invite_sent_intro() }));
        if let Some(code) = &outcome.code {
            let heading = text(&words::members_invite_code_label());
            heading.add_css_class("heading");
            col.append(&heading);
            let shown = gtk::Label::builder().label(code.as_str()).selectable(true).halign(gtk::Align::Center).build();
            shown.add_css_class("title-2");
            shown.add_css_class("monospace");
            shown.set_widget_name("invite-code");
            col.append(&shown);
            let copy = pill(&words::members_invite_code_copy(), "suggested-action");
            copy.set_widget_name("invite-code-copy");
            let (c, t) = (code.clone(), toasts.clone());
            copy.connect_clicked(move |w| {
                w.clipboard().set_text(&c);
                eprintln!("veyq: invite: the code copied");
                t.add_toast(said::copied_toast(&words::members_link_copied()));
            });
            col.append(&copy);
            col.append(&dim(&words::members_invite_code_note()));
        }
    }
    col
}

/// A re-issue's new link or code over [over], shown once as an invite's result is.
pub fn result_dialog(over: &impl IsA<gtk::Widget>, outcome: &TenantInvitesOutcome) {
    let toasts = adw::ToastOverlay::new();
    let header = adw::HeaderBar::new();
    let view = adw::ToolbarView::new();
    view.add_top_bar(&header);
    let column = column();
    column.append(&result(outcome, &toasts));
    view.set_content(Some(&scrolled(&column)));
    toasts.set_child(Some(&view));
    let title = if outcome.address.is_some() { words::members_link_result_title() } else { words::members_invite_code_label() };
    let dialog = adw::Dialog::builder().title(title).content_width(540).content_height(560).child(&toasts).build();
    dialog.set_widget_name("invite-result");
    let finish = gtk::Button::builder().label(words::share_done()).build();
    finish.add_css_class("suggested-action");
    let d = dialog.clone();
    finish.connect_clicked(move |_| {
        d.close();
    });
    header.pack_end(&finish);
    dialog.present(Some(over));
}

/// A link invite's label edited (the web's label dialog) over [over]: its draft, *Rename* and Cancel; empty clears it.
/// [save] is handed the label to set.
pub fn rename(over: &impl IsA<gtk::Widget>, invite: &PendingInvite, save: impl Fn(Option<String>) + 'static) {
    let alert = adw::AlertDialog::builder().heading(words::members_label_title()).body(words::members_link_label_hint()).build();
    let entry = gtk::Entry::builder().text(invite.label.as_deref().unwrap_or_default()).placeholder_text(words::members_link_label()).activates_default(true).build();
    entry.set_widget_name("invite-label-field");
    entry.update_property(&[gtk::accessible::Property::Label(&words::members_link_label())]);
    alert.set_extra_child(Some(&entry));
    alert.add_responses(&[("cancel", &words::signin_cancel()), ("save", &words::members_label_edit())]);
    alert.set_response_appearance("save", adw::ResponseAppearance::Suggested);
    alert.set_default_response(Some("save"));
    alert.set_close_response("cancel");
    alert.connect_response(None, move |_, response| {
        if response == "save" {
            let label = entry.text().trim().to_string();
            save((!label.is_empty()).then_some(label));
        }
    });
    alert.present(Some(over));
}

/// *Cancel invite*'s question over [over]; [then] runs once it is answered *Cancel invite*.
pub fn ask_revoke(over: &impl IsA<gtk::Widget>, then: impl Fn() + 'static) {
    let alert = adw::AlertDialog::builder().heading(words::members_revoke_confirm_title()).body(words::members_revoke_confirm()).build();
    alert.add_responses(&[("cancel", &words::signin_cancel()), ("revoke", &words::members_revoke())]);
    alert.set_response_appearance("revoke", adw::ResponseAppearance::Destructive);
    alert.set_default_response(Some("cancel"));
    alert.set_close_response("cancel");
    alert.connect_response(None, move |_, response| {
        if response == "revoke" {
            then();
        }
    });
    alert.present(Some(over));
}

fn all_of(buffer: &gtk::TextBuffer) -> String {
    buffer.text(&buffer.start_iter(), &buffer.end_iter(), false).to_string()
}

fn clear(column: &gtk::Box) {
    while let Some(child) = column.first_child() {
        column.remove(&child);
    }
}

fn column() -> gtk::Box {
    gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).build()
}

fn scrolled(column: &gtk::Box) -> gtk::ScrolledWindow {
    let clamp = adw::Clamp::builder().maximum_size(520).margin_top(12).margin_bottom(24).margin_start(12).margin_end(12).child(column).build();
    gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build()
}

fn text(words: &str) -> gtk::Label {
    gtk::Label::builder().label(words).wrap(true).xalign(0.0).build()
}

fn dim(words: &str) -> gtk::Label {
    let l = text(words);
    l.add_css_class("dim-label");
    l
}

fn error_label() -> gtk::Label {
    let l = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
    l.add_css_class("error");
    l
}

fn pill(words: &str, css: &str) -> gtk::Button {
    let b = gtk::Button::builder().label(words).halign(gtk::Align::Center).build();
    b.add_css_class("pill");
    if !css.is_empty() {
        b.add_css_class(css);
    }
    b
}
