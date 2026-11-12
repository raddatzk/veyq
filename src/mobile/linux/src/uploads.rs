//! The uploads (the Depot CR's D2, D5 and D10), GNOME's way: files and a folder picked through GTK's own dialog (the portal's
//! on a desktop) and handed to the core by their paths — the core opens and reads each at offsets a frame at a time
//! (`Upload.Files`, `Upload.Folder`, ADR 0130 decision 3), so nothing of a file crosses and none is held whole —, a file's
//! Replace chosen before its dialog opens (`File.ChooseReplace`, `Upload.Replace`), and the queue as a header bar
//! `gtk::MenuButton` shown while it lists anything: its icon's tooltip the core's one line, its popover each batch with its
//! items, their progress, Cancel and Retry, Cancel all and a finished batch's Close. What the uploads say once — a folder's
//! outcome, a replace offered — is an `AdwToast`, its button the offer's action (`Upload.TakeOffer`), dismissed
//! otherwise (`Upload.Seen`).
//!
//! Every state is the core's `DepotUploads` (`Watched.Uploads`); every action one call.
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, wire::Answer, wire::Call, wire::Update, wire::UploadStatus, wire::UploadsShown, wire::Watched};
use crate::home::glib_escape;
use crate::{header, said, words};

/// The queue's button and what it last drew.
pub struct Uploads {
    button: gtk::MenuButton,
    list: gtk::Box,
    toasts: adw::ToastOverlay,
    /// The said shown last (its id), so an update that still carries it shows no second toast.
    said: Cell<Option<i64>>,
    /// Each item's status and progress last logged, so stderr says a change once (a pass reads it).
    logged: RefCell<std::collections::HashMap<String, (UploadStatus, i32)>>,
    _watch: RefCell<Option<bridge::Watch>>,
}

impl Uploads {
    /// The queue's button (pack it into a header bar), saying in [toasts]; it follows the open tenant's uploads once
    /// [Uploads::start]ed.
    pub fn new(toasts: &adw::ToastOverlay) -> Rc<Self> {
        let list = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).width_request(360).margin_top(12).margin_bottom(12).margin_start(12).margin_end(12).build();
        let scroll = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .propagate_natural_height(true)
            .propagate_natural_width(true)
            .max_content_height(480)
            .child(&list)
            .build();
        let popover = gtk::Popover::builder().child(&scroll).build();
        let button = gtk::MenuButton::builder().icon_name("document-send-symbolic").popover(&popover).visible(false).build();
        button.set_tooltip_text(Some(&words::upload_title()));
        button.update_property(&[gtk::accessible::Property::Label(&words::upload_title())]);
        Rc::new(Self {
            button,
            list,
            toasts: toasts.clone(),
            said: Cell::new(None),
            logged: RefCell::new(std::collections::HashMap::new()),
            _watch: RefCell::new(None),
        })
    }

    /// Follows the uploads (`Watched.Uploads`), once the core runs: the shell is made before it starts, and a watch asked
    /// for then is refused (measured: no update ever came). Calling it again does nothing.
    pub fn start(self: &Rc<Self>) {
        if self._watch.borrow().is_some() {
            return;
        }
        let Some((watch, updates)) = bridge::watch(&Watched::Uploads) else {
            eprintln!("veyq: the uploads were not watched");
            return;
        };
        *self._watch.borrow_mut() = Some(watch);
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            while let Ok(update) = updates.recv().await {
                let Some(u) = weak.upgrade() else { return };
                match update {
                    Ok(Update::Uploads { shown }) => u.draw(shown.as_ref()),
                    other => eprintln!("veyq: an uploads update did not read: {other:?}"),
                }
            }
        });
    }

    pub fn button(&self) -> &gtk::MenuButton {
        &self.button
    }

    fn draw(self: &Rc<Self>, shown: Option<&UploadsShown>) {
        while let Some(child) = self.list.first_child() {
            self.list.remove(&child);
        }
        let Some(s) = shown else {
            self.button.set_visible(false);
            return;
        };
        self.log(s);
        let summary = s.summary.as_ref().map(said::notice);
        self.button.set_visible(summary.is_some() || s.preparing.is_some());
        let tip = summary.clone().or_else(|| s.preparing.as_ref().map(said::notice)).unwrap_or_else(words::upload_title);
        self.button.set_tooltip_text(Some(&tip));

        // the head: the title, the one line, Cancel all while anything may still go up
        let head = gtk::Box::new(gtk::Orientation::Horizontal, 12);
        let titles = gtk::Box::builder().orientation(gtk::Orientation::Vertical).hexpand(true).build();
        let title = gtk::Label::builder().label(words::upload_title()).xalign(0.0).build();
        title.add_css_class("heading");
        titles.append(&title);
        if let Some(line) = &summary {
            let line = gtk::Label::builder().label(line.as_str()).xalign(0.0).build();
            line.add_css_class("dim-label");
            titles.append(&line);
        }
        head.append(&titles);
        if s.active > 0 {
            let all = gtk::Button::builder().label(words::upload_cancel_all()).valign(gtk::Align::Center).build();
            all.connect_clicked(|_| {
                eprintln!("veyq: uploads: cancel all pressed");
                bridge::send(Call::UploadCancelAll);
            });
            head.append(&all);
        }
        self.list.append(&head);
        if let Some(p) = &s.preparing {
            let row = gtk::Box::new(gtk::Orientation::Horizontal, 8);
            row.append(&adw::Spinner::new());
            row.append(&gtk::Label::builder().label(said::notice(p)).xalign(0.0).wrap(true).build());
            self.list.append(&row);
        }
        for batch in &s.batches {
            let top = gtk::Box::new(gtk::Orientation::Horizontal, 6);
            let label = gtk::Label::builder().label(batch.label.as_str()).xalign(0.0).hexpand(true).ellipsize(gtk::pango::EllipsizeMode::End).build();
            label.add_css_class("caption-heading");
            top.append(&label);
            if batch.finished {
                let close = gtk::Button::builder().label(words::upload_close()).build();
                close.add_css_class("flat");
                let id = batch.id.clone();
                close.connect_clicked(move |_| bridge::send(Call::UploadAcknowledge { batch: id.clone() }));
                top.append(&close);
            }
            self.list.append(&top);
            let rows = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
            rows.add_css_class("boxed-list");
            for item in &batch.items {
                // one line each, the name cut at its end: a long name never squeezes the row's controls
                let row = adw::ActionRow::builder().title(glib_escape(&item.name)).title_lines(1).subtitle(glib_escape(&said::notice(&item.line))).subtitle_lines(2).build();
                if item.failed {
                    row.add_css_class("error");
                }
                if item.status == UploadStatus::Uploading || item.status == UploadStatus::Preparing {
                    let bar = gtk::ProgressBar::builder().fraction(item.progress).valign(gtk::Align::Center).hexpand(false).width_request(72).build();
                    if item.status == UploadStatus::Preparing {
                        bar.pulse();
                    }
                    row.add_suffix(&bar);
                }
                if item.can_cancel {
                    let cancel = gtk::Button::builder().icon_name("process-stop-symbolic").tooltip_text(words::upload_cancel()).valign(gtk::Align::Center).build();
                    cancel.add_css_class("flat");
                    cancel.update_property(&[gtk::accessible::Property::Label(&words::upload_cancel())]);
                    let (id, name) = (item.id.clone(), item.name.clone());
                    cancel.connect_clicked(move |_| {
                        eprintln!("veyq: upload {name}: cancel pressed");
                        bridge::send(Call::UploadCancel { item: id.clone() });
                    });
                    row.add_suffix(&cancel);
                }
                if item.can_retry {
                    let retry = gtk::Button::builder().icon_name("view-refresh-symbolic").tooltip_text(words::upload_retry()).valign(gtk::Align::Center).build();
                    retry.add_css_class("flat");
                    retry.update_property(&[gtk::accessible::Property::Label(&words::upload_retry())]);
                    let (id, name) = (item.id.clone(), item.name.clone());
                    retry.connect_clicked(move |_| {
                        eprintln!("veyq: upload {name}: retry pressed");
                        bridge::send(Call::UploadRetry { item: id.clone() });
                    });
                    row.add_suffix(&retry);
                }
                rows.append(&row);
            }
            self.list.append(&rows);
        }
        self.say(s);
    }

    /// What the uploads say now, as a toast once: its button the offer's action, dismissed otherwise.
    fn say(&self, s: &UploadsShown) {
        let Some(told) = &s.said else { return };
        if self.said.replace(Some(told.id)) == Some(told.id) {
            return;
        }
        let text = told.notices.iter().map(said::notice).collect::<Vec<_>>().join("\n");
        eprintln!("veyq: uploads say {}: {}", told.id, told.notices.iter().map(|n| n.msg.as_str()).collect::<Vec<_>>().join(", "));
        let toast = adw::Toast::builder().title(glib_escape(&text)).build();
        let id = told.id;
        let taken = Rc::new(Cell::new(false));
        if let Some(action) = &told.action {
            toast.set_button_label(Some(&said::key(action)));
            toast.set_timeout(0);
            let taken = taken.clone();
            toast.connect_button_clicked(move |_| {
                taken.set(true);
                eprintln!("veyq: uploads said {id}: its action taken");
                bridge::send(Call::UploadTakeOffer { said: id });
            });
        }
        toast.connect_dismissed(move |_| {
            if !taken.get() {
                bridge::send(Call::UploadSeen { said: id });
            }
        });
        self.toasts.add_toast(toast);
    }

    /// Each item's status once, and its progress at each tenth, on stderr (a pass reads them before a screenshot).
    fn log(&self, s: &UploadsShown) {
        let mut logged = self.logged.borrow_mut();
        for item in s.batches.iter().flat_map(|b| &b.items) {
            let tenth = (item.progress * 10.0).floor() as i32;
            let now = (item.status, tenth);
            if logged.get(&item.id) != Some(&now) {
                logged.insert(item.id.clone(), now);
                eprintln!("veyq: upload {}: {:?} {}% of {} B", item.name, item.status, tenth * 10, item.size_bytes);
            }
        }
    }
}

/// Where New's uploads go: a space, the folder there (None: its top) and the name the queue labels the batch with.
pub struct Level {
    pub scope_id: String,
    pub parent_id: Option<String>,
    pub label: String,
}

/// New's "Upload files": GTK's open dialog picks several, their paths go to the core (`Upload.Files`).
pub fn pick_files(parent: &gtk::Window, level: Level, toasts: &adw::ToastOverlay) {
    let (parent, toasts) = (parent.clone(), toasts.clone());
    glib::spawn_future_local(async move {
        let dialog = gtk::FileDialog::builder().title(words::depot_upload()).modal(true).build();
        let picked = match dialog.open_multiple_future(Some(&parent)).await {
            Ok(picked) => picked,
            Err(e) => return dismissed(&e, "open"),
        };
        let paths = paths_of(&picked);
        if paths.is_empty() {
            return;
        }
        eprintln!("veyq: upload files: {} picked", paths.len());
        let answer = bridge::call(&Call::UploadFiles { scope_id: level.scope_id, parent_id: level.parent_id, label: level.label, paths }).await.map(|(a, _)| a);
        queued(&toasts, answer, "Upload.Files");
    });
}

/// New's "Upload folder…": GTK's folder dialog picks one directory, which the core walks and uploads (`Upload.Folder`).
pub fn pick_folder(parent: &gtk::Window, level: Level, toasts: &adw::ToastOverlay) {
    let (parent, toasts) = (parent.clone(), toasts.clone());
    glib::spawn_future_local(async move {
        let dialog = gtk::FileDialog::builder().title(words::depot_upload_folder()).modal(true).build();
        let picked = match dialog.select_folder_future(Some(&parent)).await {
            Ok(picked) => picked,
            Err(e) => return dismissed(&e, "folder"),
        };
        let Some(path) = local_path(&picked) else { return };
        eprintln!("veyq: upload folder: picked");
        let answer = bridge::call(&Call::UploadFolder { scope_id: level.scope_id, parent_id: level.parent_id, path }).await.map(|(a, _)| a);
        queued(&toasts, answer, "Upload.Folder");
    });
}

/// A file's Replace (the header's, on its page [file]): chosen in the core first — the replace expects the content shown
/// now —, then GTK's open dialog picks the one file whose path goes to the core (`Upload.Replace`).
pub fn replace(parent: &gtk::Window, file: i64, toasts: &adw::ToastOverlay) {
    let (parent, toasts) = (parent.clone(), toasts.clone());
    glib::spawn_future_local(async move {
        match bridge::call(&Call::FileChooseReplace { file }).await.map(|(a, _)| a) {
            Ok(Answer::Done) => {}
            other => return eprintln!("veyq: File.ChooseReplace answered {other:?}"),
        }
        let dialog = gtk::FileDialog::builder().title(words::depot_replace_title()).modal(true).build();
        let picked = match dialog.open_future(Some(&parent)).await {
            Ok(picked) => picked,
            Err(e) => return dismissed(&e, "replace"),
        };
        let Some(path) = local_path(&picked) else { return };
        let answer = bridge::call(&Call::UploadReplace { file, path }).await.map(|(a, _)| a);
        queued(&toasts, answer, "Upload.Replace");
    });
}

fn dismissed(e: &glib::Error, what: &str) {
    if !e.matches(gtk::DialogError::Dismissed) && !e.matches(gtk::DialogError::Cancelled) {
        eprintln!("veyq: the {what} dialog failed: {e}");
    }
}

/// A picked file's local path; one with none (a remote URI) or no UTF-8 does not cross the wire.
fn local_path(file: &gio::File) -> Option<String> {
    let path = file.path().and_then(|p| p.to_str().map(str::to_owned));
    if path.is_none() {
        eprintln!("veyq: a picked file has no local UTF-8 path");
    }
    path
}

fn paths_of(picked: &gio::ListModel) -> Vec<String> {
    (0..picked.n_items()).filter_map(|i| picked.item(i).and_downcast::<gio::File>()).filter_map(|f| local_path(&f)).collect()
}

/// An upload call's answer: how many were queued on stderr, anything else said as the header says it.
fn queued(toasts: &adw::ToastOverlay, answer: Result<Answer, bridge::Unreadable>, what: &str) {
    match answer {
        Ok(Answer::Queued { count }) => eprintln!("veyq: {what}: {count} queued"),
        other => header::say(toasts, other, what),
    }
}
