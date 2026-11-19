//! Drag and drop onto a level and onto a folder's row (ADR 0123's Consequences, the Linux app's Depot whole), GNOME's way: a `gtk::DropTarget` for a
//! `gdk::FileList` (what Files hands over, through the portal on a sandboxed desktop) taking a copy; the row or the level
//! under the pointer is highlighted by the theme's own `:drop(active)` style. Each dropped file's local path goes to the
//! core as a picked one does (`Import.Screen`, all files of one drop in one batch), each dropped directory's to
//! `Import.ScreenFolder`, which the core walks (ADR 0137 decision 3: the core opens and reads each at offsets, nothing of a
//! file crosses). A dropped veyq download — a ZIP, or its folder unpacked — is offered as an import (ADR 0152 decision 1,
//! `import_dialog`), the rest uploaded. A dropped item with no local path (a remote location without a mount) is refused
//! and said (`depot_drop_not_local`).
//!
//! What a drop is sorted into is this side's only rule ([sort], [calls]); where a drop is taken at all is the core's: the
//! level takes one where its New offers Upload files (`ShellShown.creates`).
use adw::prelude::*;
use gtk::{gdk, gio};

use crate::bridge::wire::Call;
use crate::uploads::Level;
use crate::{home::glib_escape, import_dialog, words};

/// A drop sorted: the files' paths, the directories' paths, and the names of what has no local path.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct Sorted {
    pub files: Vec<String>,
    pub folders: Vec<String>,
    pub refused: Vec<String>,
}

/// Sorts dropped [files] by what they are on this computer: a directory (a link to one included) is a folder, anything
/// else with a local UTF-8 path a file (the core says what it cannot open), the rest refused under its shown name.
pub fn sort(files: &[gio::File]) -> Sorted {
    let mut sorted = Sorted::default();
    for file in files {
        match file.path().and_then(|p| p.to_str().map(str::to_owned)) {
            Some(path) if file.query_file_type(gio::FileQueryInfoFlags::NONE, gio::Cancellable::NONE) == gio::FileType::Directory => {
                sorted.folders.push(path)
            }
            Some(path) => sorted.files.push(path),
            None => sorted.refused.push(file.basename().map(|b| b.to_string_lossy().into_owned()).unwrap_or_else(|| file.uri().into())),
        }
    }
    sorted
}

/// The calls a sorted drop onto [level] makes, each screened for a download: the files as one batch, then one folder per
/// directory; [deleted_name] the `pot_deleted_person` word an imported Pot names an erased person by.
pub fn calls(level: &Level, sorted: &Sorted, deleted_name: &str) -> Vec<Call> {
    let mut calls = Vec::new();
    if !sorted.files.is_empty() {
        calls.push(Call::ImportScreen {
            scope_id: level.scope_id.clone(),
            parent_id: level.parent_id.clone(),
            label: level.label.clone(),
            paths: sorted.files.clone(),
            deleted_name: deleted_name.to_owned(),
        });
    }
    for path in &sorted.folders {
        calls.push(Call::ImportScreenFolder {
            scope_id: level.scope_id.clone(),
            parent_id: level.parent_id.clone(),
            label: level.label.clone(),
            path: path.clone(),
            deleted_name: deleted_name.to_owned(),
        });
    }
    calls
}

/// Where a drop onto a level goes: the level itself, and each of its folders' rows into that folder.
#[derive(Clone)]
pub struct Drops {
    pub level: Level,
    pub toasts: adw::ToastOverlay,
}

impl Drops {
    /// The level's own drop target.
    pub fn onto_level(&self) -> gtk::DropTarget {
        target(self.level.clone(), &self.toasts)
    }

    /// The drop target of the row of the folder [node_id] named [name] on this level.
    pub fn onto_folder(&self, node_id: &str, name: &str) -> gtk::DropTarget {
        let level = Level { scope_id: self.level.scope_id.clone(), parent_id: Some(node_id.to_owned()), label: name.to_owned() };
        target(level, &self.toasts)
    }
}

/// A drop target uploading into [level], saying in [toasts]: attach it to the level's list or a folder's row.
pub fn target(level: Level, toasts: &adw::ToastOverlay) -> gtk::DropTarget {
    let target = gtk::DropTarget::new(gdk::FileList::static_type(), gdk::DragAction::COPY);
    let toasts = toasts.clone();
    target.connect_drop(move |target, value, _, _| {
        let Ok(list) = value.get::<gdk::FileList>() else { return false };
        let sorted = sort(&list.files());
        eprintln!(
            "veyq: dropped onto {}: {} files, {} folders, {} refused",
            level.label,
            sorted.files.len(),
            sorted.folders.len(),
            sorted.refused.len()
        );
        for name in &sorted.refused {
            toasts.add_toast(adw::Toast::new(&glib_escape(&words::depot_drop_not_local(name))));
        }
        // an offered import's dialog sits over the window the drop landed in
        let Some(parent) = target.widget().and_then(|w| w.root()).map(|r| r.upcast::<gtk::Widget>()) else { return false };
        for call in calls(&level, &sorted, &words::pot_deleted_person()) {
            let what = if matches!(call, Call::ImportScreenFolder { .. }) { "Import.ScreenFolder" } else { "Import.Screen" };
            import_dialog::screened(&parent, call, what, &toasts);
        }
        !(sorted.files.is_empty() && sorted.folders.is_empty())
    });
    target
}

#[cfg(test)]
mod tests {
    use super::*;

    fn level() -> Level {
        Level { scope_id: "s1".into(), parent_id: Some("f1".into()), label: "Trip".into() }
    }

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
        std::fs::write(root.join("b.jpg"), b"b").unwrap();
        std::fs::write(root.join("Reise ü").join("c.txt"), b"c").unwrap();
        Made(root)
    }

    #[test]
    fn a_dropped_file_list_reaches_the_import_screens_with_its_paths() {
        let root = made("sorted");
        let path = |name: &str| root.0.join(name).to_str().unwrap().to_owned();
        let list = gdk::FileList::from_array(&[
            gio::File::for_path(root.0.join("a.txt")),
            gio::File::for_path(root.0.join("Reise ü")),
            gio::File::for_path(root.0.join("b.jpg")),
        ]);
        let sorted = sort(&list.files());
        assert_eq!(sorted, Sorted { files: vec![path("a.txt"), path("b.jpg")], folders: vec![path("Reise ü")], refused: vec![] });
        let sent: Vec<serde_json::Value> = calls(&level(), &sorted, "Deleted").iter().map(|c| serde_json::to_value(c).unwrap()).collect();
        let files = serde_json::to_value(Call::ImportScreen {
            scope_id: "s1".into(),
            parent_id: Some("f1".into()),
            label: "Trip".into(),
            paths: vec![path("a.txt"), path("b.jpg")],
            deleted_name: "Deleted".into(),
        })
        .unwrap();
        let folder = serde_json::to_value(Call::ImportScreenFolder {
            scope_id: "s1".into(),
            parent_id: Some("f1".into()),
            label: "Trip".into(),
            path: path("Reise ü"),
            deleted_name: "Deleted".into(),
        })
        .unwrap();
        assert_eq!(sent, vec![files, folder], "the files as one batch, the directory walked by the core, each screened for a download");
    }

    #[test]
    fn a_dropped_item_without_a_local_path_is_refused_and_nothing_is_sent_for_it() {
        let root = made("far");
        let list = gdk::FileList::from_array(&[gio::File::for_uri("https://example.invalid/far/away.pdf"), gio::File::for_path(root.0.join("a.txt"))]);
        let sorted = sort(&list.files());
        assert_eq!(sorted.refused, vec!["away.pdf".to_string()]);
        assert_eq!(sorted.files.len(), 1);
        let only_far = Sorted { refused: sorted.refused.clone(), ..Sorted::default() };
        assert!(calls(&level(), &only_far, "Deleted").is_empty(), "a drop of nothing local sends nothing");
    }
}
