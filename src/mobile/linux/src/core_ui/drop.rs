//! Drag and drop onto a level, a folder's row and a gallery (ADR 0123's Consequences, the Linux app's Depot whole; ADR
//! 0154 decision 1), GNOME's way: a `gtk::DropTarget` for a `gdk::FileList` (what Files hands over, through the portal on
//! a sandboxed desktop) taking a copy; the row or the level under the pointer is highlighted by the theme's own
//! `:drop(active)` style.
//!
//! Every rule is the core's one drop (`Shell.Drop`, `ShellModel.drop`): this side names what was dropped on
//! ([DropOn]) and tells each item a file from a folder by its file system ([items]) — nothing sorted, nothing judged
//! here. Where a drop target stands at all is the core's too: the level where `ShellShown.dropsHere`, a folder's row
//! where `ShellShown.dropRows` names it (never by its kind). The core's answer (`Answer.Dropped`) is drawn: its notices
//! said in order, an offered import opened (`import_dialog`, one per drop), a refusal said by its notice. Paths in, never
//! bytes (ADR 0137 decision 3).
use adw::prelude::*;
use gtk::{gdk, gio, glib};

use crate::bridge::wire::{Answer, Call, DropItem, DropOn, NodeAt, NodeRef};
use crate::bridge::{self, Unreadable};
use crate::core_ui::{header, import_dialog, said, words};

/// Each dropped file as the file system tells it: a directory (a link to one included) a folder, anything else with a
/// local UTF-8 path a file (the core says what it cannot open), the rest no local item, by its shown name.
pub fn items(files: &[gio::File]) -> Vec<DropItem> {
    files
        .iter()
        .map(|file| match file.path().and_then(|p| p.to_str().map(str::to_owned)) {
            Some(path) if file.query_file_type(gio::FileQueryInfoFlags::NONE, gio::Cancellable::NONE) == gio::FileType::Directory => {
                DropItem::DroppedFolder { path }
            }
            Some(path) => DropItem::DroppedFile { path },
            None => DropItem::DroppedNotLocal { name: file.basename().map(|b| b.to_string_lossy().into_owned()).unwrap_or_else(|| file.uri().into()) },
        })
        .collect()
}

/// The core's one drop for [items] onto [on]; [deleted_name] the `pot_deleted_person` word an imported Pot names an
/// erased person by.
pub fn call(on: DropOn, items: Vec<DropItem>) -> Call {
    Call::ShellDrop { on, items, deleted_name: words::pot_deleted_person() }
}

/// Where a drop onto the level shown goes, as the core says (`ShellShown.dropsHere`, `dropRows`).
#[derive(Clone)]
pub struct Drops {
    /// The level's title as drawn (the batch's name), where the level takes a drop; None: it takes none.
    pub level: Option<String>,
    /// The folders among the level's rows that take a drop.
    pub rows: Vec<String>,
    pub toasts: adw::ToastOverlay,
}

impl Drops {
    /// The level's own drop target, where the core offers one.
    pub fn onto_level(&self) -> Option<gtk::DropTarget> {
        self.level.as_ref().map(|label| target(DropOn::DropOnLevel { label: label.clone() }, &self.toasts))
    }

    /// The drop target of row [n], where the core offers one (`dropRows`).
    pub fn onto_row(&self, n: &NodeRef) -> Option<gtk::DropTarget> {
        row_on(&self.rows, n).map(|on| target(on, &self.toasts))
    }
}

/// What a drop onto row [n] lands on, where [rows] (`ShellShown.dropRows`) names it; None: the row takes no drop.
pub fn row_on(rows: &[String], n: &NodeRef) -> Option<DropOn> {
    rows.contains(&n.node_id).then(|| DropOn::DropOnRow {
        folder: NodeAt { scope_id: n.scope_id.clone(), node_id: n.node_id.clone(), kind: n.kind.clone(), name: n.name.clone() },
    })
}

/// A drop target handing what is dropped onto [on] to the core, saying in [toasts].
pub fn target(on: DropOn, toasts: &adw::ToastOverlay) -> gtk::DropTarget {
    let target = gtk::DropTarget::new(gdk::FileList::static_type(), gdk::DragAction::COPY);
    let toasts = toasts.clone();
    target.connect_drop(move |target, value, _, _| {
        let Ok(list) = value.get::<gdk::FileList>() else { return false };
        let Some(parent) = target.widget().and_then(|w| w.root()).map(|r| r.upcast::<gtk::Widget>()) else { return false };
        send(&parent, on.clone(), items(&list.files()), &toasts);
        true
    });
    target
}

/// Sends [items] dropped (or picked) onto [on] and draws the core's answer over [parent].
pub fn send(parent: &gtk::Widget, on: DropOn, items: Vec<DropItem>, toasts: &adw::ToastOverlay) {
    if items.is_empty() {
        return;
    }
    eprintln!("veyq: dropped {} items onto {}", items.len(), on_name(&on));
    let answer = bridge::call(&call(on, items));
    let (parent, toasts) = (parent.clone(), toasts.clone());
    glib::spawn_future_local(async move {
        let answer = answer.await.map(|(a, _)| a);
        if let Some(import) = answered(&toasts, answer) {
            import_dialog::open(&parent, import, &toasts);
        }
    });
}

/// The core's answer to a drop said in [toasts], in its order; the import it offers, if any.
pub fn answered(toasts: &adw::ToastOverlay, answer: Result<Answer, Unreadable>) -> Option<i64> {
    match answer {
        Ok(Answer::Dropped { queued, notices, import, refused }) => {
            eprintln!("veyq: Shell.Drop: {queued} queued, import {import:?}, refused {refused}, {} said", notices.len());
            for n in &notices {
                eprintln!("veyq: Shell.Drop: {}", n.msg);
                toasts.add_toast(said::notice_toast(n));
            }
            import
        }
        other => {
            header::say(toasts, other, "Shell.Drop");
            None
        }
    }
}

fn on_name(on: &DropOn) -> String {
    match on {
        DropOn::DropOnLevel { label } => format!("the level {label}"),
        DropOn::DropOnRow { folder } => format!("the row {}", folder.name),
        DropOn::DropOnGallery { gallery } => format!("gallery {gallery}"),
        DropOn::DropOnBuckets { buckets } => format!("the bucket of contributions {buckets}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A directory of two files and a folder, made for one test, removed with it.
    struct Made(std::path::PathBuf);

    impl Drop for Made {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn made(test: &str) -> Made {
        let root = std::env::temp_dir().join(format!("veyq-drop-{}-{test}", std::process::id()));
        std::fs::create_dir_all(root.join("Reise ü")).unwrap();
        std::fs::write(root.join("a.txt"), b"a").unwrap();
        std::fs::write(root.join("b.zip"), b"b").unwrap();
        std::fs::write(root.join("Reise ü").join("c.txt"), b"c").unwrap();
        Made(root)
    }

    #[test]
    fn a_dropped_file_list_reaches_the_cores_one_drop_as_it_lies_unsorted() {
        let root = made("items");
        let path = |name: &str| root.0.join(name).to_str().unwrap().to_owned();
        let list = gdk::FileList::from_array(&[
            gio::File::for_path(root.0.join("a.txt")),
            gio::File::for_path(root.0.join("Reise ü")),
            gio::File::for_uri("https://example.invalid/far/away.pdf"),
            gio::File::for_path(root.0.join("b.zip")),
        ]);
        let sent = serde_json::to_value(call(DropOn::DropOnLevel { label: "Trip".into() }, items(&list.files()))).unwrap();
        let wanted = serde_json::to_value(Call::ShellDrop {
            on: DropOn::DropOnLevel { label: "Trip".into() },
            items: vec![
                DropItem::DroppedFile { path: path("a.txt") },
                DropItem::DroppedFolder { path: path("Reise ü") },
                DropItem::DroppedNotLocal { name: "away.pdf".into() },
                DropItem::DroppedFile { path: path("b.zip") },
            ],
            deleted_name: words::pot_deleted_person(),
        })
        .unwrap();
        assert_eq!(sent, wanted, "one call, in the order dropped: the core sorts, judges and offers the import");
    }

    fn row(id: &str, kind: &str) -> NodeRef {
        NodeRef { scope_id: "s1".into(), node_id: id.into(), parent_id: None, kind: kind.into(), name: id.into(), identity: false, created_at: None }
    }

    #[test]
    fn a_row_takes_a_drop_only_where_the_core_names_it_never_by_its_kind() {
        let rows = vec!["f1".to_string()];
        let on = serde_json::to_value(row_on(&rows, &row("f1", "FOLDER"))).unwrap();
        assert_eq!(on["type"], "DropOnRow", "a folder the core offers takes a drop: {on}");
        assert_eq!(on["folder"]["nodeId"], "f1");
        assert!(row_on(&rows, &row("f2", "FOLDER")).is_none(), "a folder the core does not offer (a guest's identity folder) takes none");
    }
}
