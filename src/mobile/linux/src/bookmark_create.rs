//! New → Bookmark (ADR 0150 decision 6; the web's `BookmarkCreateDialog.vue`, uc.sharing.keep-a-received-link;
//! cr-app-received-links phase 9, inbox #1020), GNOME's way: an `AdwDialog` with the link — checked by the core as it is
//! typed (`Bookmark.Check`: why it is no link to keep, the host the name defaults to) —, its password, its name (the
//! host where none is typed), and in a group space the line that everyone who opens the place opens the link. *Keep link*
//! sends it whole (`Bookmark.Create`), the core sealing the meta; a save that did not go through says so and keeps the
//! form. Nothing of the link is kept here once the dialog closes.
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, wire::Answer, wire::Call, wire::NodeAt};
use crate::words;

/// The dialog over [parent], keeping the link in [scope_id] under [parent_id] (null: the space's top); [made] is told the
/// bookmark created.
pub fn open(parent: &impl IsA<gtk::Widget>, scope_id: String, parent_id: Option<String>, made: impl Fn(NodeAt) + 'static) {
    let link = adw::EntryRow::builder().title(words::bookmark_link_label()).input_purpose(gtk::InputPurpose::Url).build();
    link.set_widget_name("bookmark-link");
    let password = adw::PasswordEntryRow::builder().title(words::bookmark_password_label()).build();
    let name = adw::EntryRow::builder().title(words::node_name()).build();
    let fields = adw::PreferencesGroup::builder().description(words::bookmark_password_hint()).build();
    fields.add(&link);
    fields.add(&password);
    fields.add(&name);
    let intro = gtk::Label::builder().label(words::bookmark_create_intro()).wrap(true).xalign(0.0).build();
    let problem = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
    problem.add_css_class("error");
    let group_line = gtk::Label::builder().label(words::bookmark_who_can_open()).wrap(true).xalign(0.0).visible(false).build();
    group_line.add_css_class("dim-label");
    let failed = gtk::Label::builder().label(words::bookmark_create_failed()).wrap(true).xalign(0.0).visible(false).build();
    failed.add_css_class("error");
    let save = gtk::Button::builder().label(words::bookmark_save()).halign(gtk::Align::Center).sensitive(false).build();
    save.add_css_class("suggested-action");
    save.add_css_class("pill");
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).margin_top(12).margin_bottom(24).margin_start(12).margin_end(12).build();
    for w in [intro.upcast_ref::<gtk::Widget>(), fields.upcast_ref(), problem.upcast_ref(), group_line.upcast_ref(), failed.upcast_ref(), save.upcast_ref()] {
        column.append(w);
    }
    let view = adw::ToolbarView::new();
    view.add_top_bar(&adw::HeaderBar::new());
    view.set_content(Some(&column));
    let dialog = adw::Dialog::builder().title(words::bookmark_create_title()).content_width(520).child(&view).build();

    let (scope, s, p, n, g, f) = (scope_id.clone(), save.clone(), problem.clone(), name.clone(), group_line.clone(), failed.clone());
    link.connect_changed(move |e| {
        let text = e.text().to_string();
        let (scope, s, p, n, g) = (scope.clone(), s.clone(), p.clone(), n.clone(), g.clone());
        f.set_visible(false);
        glib::spawn_future_local(async move {
            let Ok((Answer::BookmarkChecked { problem, host, group_space }, _)) = bridge::call(&Call::BookmarkCheck { scope_id: scope, text }).await else { return };
            p.set_visible(problem.is_some());
            if let Some(problem) = &problem {
                p.set_label(&crate::received::said_msg(problem));
            }
            g.set_visible(group_space);
            // the name defaults to the host (the core's rule): shown as the field's hint while none is typed
            n.set_title(&host.clone().map(|h| format!("{} ({h})", words::node_name())).unwrap_or_else(words::node_name));
            s.set_sensitive(problem.is_none() && host.is_some());
        });
    });
    let (d, l, pw, n, f) = (dialog.clone(), link.clone(), password.clone(), name.clone(), failed.clone());
    let made = Rc::new(made);
    save.connect_clicked(move |b| {
        b.set_sensitive(false);
        let call = Call::BookmarkCreate { scope_id: scope_id.clone(), parent_id: parent_id.clone(), text: l.text().to_string(), password: pw.text().to_string(), name: n.text().trim().to_string() };
        let (d, f, b, made) = (d.clone(), f.clone(), b.clone(), made.clone());
        glib::spawn_future_local(async move {
            match bridge::call(&call).await.map(|(a, _)| a) {
                Ok(Answer::Created { node: Some(node), .. }) => {
                    eprintln!("veyq: new bookmark: made");
                    d.close();
                    made(node);
                }
                _ => {
                    f.set_visible(true);
                    b.set_sensitive(true);
                }
            }
        });
    });
    dialog.present(Some(parent));
    link.grab_focus();
}
