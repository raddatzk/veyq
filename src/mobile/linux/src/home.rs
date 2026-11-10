//! Home and an application's list (ADR 0122): Home's level as the core groups it — the folders, then the contents —
//! under the filters the core offers, and an application's own list in its Favorites, Recent and the rest. Each drawn
//! as GNOME draws a list of places: boxed lists in a clamped, scrolled column, a filter as a toggle group.
use adw::prelude::*;

use crate::bridge::{self, wire::ApplicationId, wire::ArrangedList, wire::Call, wire::NodeRef, wire::ObjectRef,
    wire::ShellModelState, wire::ShellShown, wire::FolderRef};
use crate::{said, words};

/// The icon of a node's kind — Android's `kindIcon`, Apple's `Names.kindSymbol`, in GNOME's icon set.
pub fn kind_icon(kind: &str) -> &'static str {
    match kind {
        "FOLDER" => "folder-symbolic",
        "EXPENSES_POT" => "x-office-spreadsheet-symbolic",
        "MEMORY" => "camera-photo-symbolic",
        "CHAPTER" => "folder-pictures-symbolic",
        "DEPOT_DROPBOX" => "folder-download-symbolic",
        "SHARE_BOOKMARK" => "folder-publicshare-symbolic",
        _ => "text-x-generic-symbolic",
    }
}

/// An application's icon.
pub fn app_icon(app: ApplicationId) -> &'static str {
    match app {
        ApplicationId::Memories => "camera-photo-symbolic",
        ApplicationId::Depot => "folder-documents-symbolic",
        ApplicationId::Expenses => "x-office-spreadsheet-symbolic",
    }
}

/// An application's name, as the core names it ([ShellShown::application_words]).
pub fn app_name(shown: &ShellShown, app: ApplicationId) -> String {
    shown.application_words.iter().find(|w| w.application == app).map(|w| said::key(&w.word)).unwrap_or_default()
}

/// A row that opens [r] in its application when activated.
pub fn object_row(r: &ObjectRef) -> adw::ActionRow {
    let row = adw::ActionRow::builder().title(glib_escape(&r.name)).activatable(true).build();
    row.add_prefix(&gtk::Image::from_icon_name(kind_icon(&r.kind)));
    let open = r.clone();
    row.connect_activated(move |_| bridge::send(Call::ShellOpen { r#ref: open.clone() }));
    row
}

/// A row's title is markup in libadwaita: a name is shown as it is written.
pub fn glib_escape(text: &str) -> String {
    gtk::glib::markup_escape_text(text).into()
}

fn column() -> (gtk::ScrolledWindow, gtk::Box) {
    let content = gtk::Box::new(gtk::Orientation::Vertical, 24);
    content.set_margin_top(18);
    content.set_margin_bottom(24);
    content.set_margin_start(12);
    content.set_margin_end(12);
    let clamp = adw::Clamp::builder().maximum_size(720).child(&content).build();
    let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build();
    (scroll, content)
}

fn empty() -> adw::StatusPage {
    adw::StatusPage::builder().icon_name("folder-open-symbolic").title(words::list_empty()).vexpand(true).build()
}

fn filter_name(f: Option<ApplicationId>) -> String {
    match f {
        Some(app) => serde_json::to_value(app).ok().and_then(|v| v.as_str().map(str::to_owned)).unwrap_or_default(),
        None => "all".to_string(),
    }
}

/// Home: the filters (where the core offers more than "all"), then the level's groups (`Home.groups`): a folder
/// enters, an object opens in its application.
pub fn home(state: &ShellModelState, shown: &ShellShown) -> gtk::Widget {
    let (scroll, content) = column();
    if state.lists.filters.len() > 1 {
        content.append(&filter_group(state, shown));
    }
    if shown.groups.is_empty() {
        content.append(&empty());
    }
    for g in &shown.groups {
        let list = adw::PreferencesGroup::new();
        if let Some(heading) = &g.heading {
            list.set_title(&said::key(heading));
        }
        for n in &g.nodes {
            if g.folders {
                list.add(&folder_row(n));
            } else if let Some(o) = state.lists.objects.iter().find(|o| o.scope_id == n.scope_id && o.node_id == n.node_id) {
                list.add(&object_row(o));
            }
        }
        content.append(&list);
    }
    scroll.upcast()
}

fn folder_row(n: &NodeRef) -> adw::ActionRow {
    let row = adw::ActionRow::builder().title(glib_escape(&n.name)).activatable(true).build();
    row.add_prefix(&gtk::Image::from_icon_name(kind_icon(&n.kind)));
    row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
    let folder = FolderRef { node_id: n.node_id.clone(), name: n.name.clone() };
    row.connect_activated(move |_| bridge::send(Call::ShellEnter { folder: folder.clone() }));
    row
}

fn filter_group(state: &ShellModelState, shown: &ShellShown) -> adw::ToggleGroup {
    let filters = &state.lists.filters;
    let group = adw::ToggleGroup::builder().halign(gtk::Align::Center).build();
    for f in filters {
        let label = match f {
            Some(app) => app_name(shown, *app),
            None => words::filter_all(),
        };
        group.add(adw::Toggle::builder().label(label).name(filter_name(*f)).build());
    }
    group.set_active_name(Some(&filter_name(state.shell.filter)));
    let offered = filters.clone();
    group.connect_active_name_notify(move |g| {
        let name = g.active_name().map(String::from).unwrap_or_default();
        if let Some(f) = offered.iter().find(|f| filter_name(**f) == name) {
            bridge::send(Call::ShellFilter { application: *f });
        }
    });
    group
}

/// An application's own list: Favorites, Recent, then the rest, each where it holds something.
pub fn list(list: Option<&ArrangedList>) -> gtk::Widget {
    let (scroll, content) = column();
    let Some(l) = list.filter(|l| !(l.favorites.is_empty() && l.recent.is_empty() && l.all.is_empty())) else {
        content.append(&empty());
        return scroll.upcast();
    };
    for (title, refs) in [(Some(words::drawer_favorites()), &l.favorites), (Some(words::drawer_recent()), &l.recent), (None, &l.all)] {
        if refs.is_empty() {
            continue;
        }
        let group = adw::PreferencesGroup::new();
        if let Some(t) = title {
            group.set_title(&t);
        }
        for r in refs {
            group.add(&object_row(r));
        }
        content.append(&group);
    }
    scroll.upcast()
}
