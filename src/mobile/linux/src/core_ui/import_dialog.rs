//! A download's import (ADR 0152; core's `ImportDialog`, the web's `ImportSubtreeDialog.vue`), GNOME's way: no entry of
//! its own — every Depot upload screens what it is handed first (`Import.Screen` for files picked or dropped,
//! `Import.ScreenFolder` for a directory), and a veyq download among them is offered in an `AdwDialog` holding an
//! `AdwNavigationView`: a page while the download reads, then the page of what would be created and where, what is left
//! out per application and the room it needs, its run's progress in nodes, or the page of why it is refused. Cancel and
//! *Import* (or *Import the rest*, the suggested action) sit in the header bar, *Upload as a file* beside them for a ZIP.
//! Cancel while it runs stops it and the core trashes what it made; the dialog cannot be closed otherwise meanwhile. Once
//! done the dialog closes and a toast says the imported line. Every rule is the core's (`Watched.Import`); this lays it
//! out. Paths in, words out: nothing of the download crosses (ADR 0137).
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::ImportShown, wire::ImportStep, wire::Update, wire::Watched};
use crate::core_ui::home::glib_escape;
use crate::core_ui::uploads::{self, Level};
use crate::core_ui::{said, words};

/// Files picked or dropped into [level], screened (`Import.Screen`): a download among them offered as an import over
/// [parent], the rest queued.
pub fn screen_files(parent: &gtk::Widget, level: &Level, paths: Vec<String>, toasts: &adw::ToastOverlay) {
    let call = Call::ImportScreen {
        scope_id: level.scope_id.clone(),
        parent_id: level.parent_id.clone(),
        label: level.label.clone(),
        paths,
        deleted_name: words::pot_deleted_person(),
    };
    screened(parent, call, "Import.Screen", toasts);
}

/// A directory picked or dropped into [level], screened (`Import.ScreenFolder`): an unpacked download offered as an
/// import over [parent], any other uploaded as its tree.
pub fn screen_folder(parent: &gtk::Widget, level: &Level, path: String, toasts: &adw::ToastOverlay) {
    let call = Call::ImportScreenFolder {
        scope_id: level.scope_id.clone(),
        parent_id: level.parent_id.clone(),
        label: level.label.clone(),
        path,
        deleted_name: words::pot_deleted_person(),
    };
    screened(parent, call, "Import.ScreenFolder", toasts);
}

/// An upload screen's [call] sent: an import it offers opened over [parent], anything else answered as an upload's.
pub fn screened(parent: &gtk::Widget, call: Call, what: &'static str, toasts: &adw::ToastOverlay) {
    let (parent, toasts) = (parent.clone(), toasts.clone());
    let answer = bridge::call(&call);
    glib::spawn_future_local(async move {
        match answer.await.map(|(a, _)| a) {
            Ok(Answer::ImportOffered { import, queued, notice }) => {
                eprintln!("veyq: {what}: an import offered, {queued} queued");
                if let Some(n) = notice {
                    toasts.add_toast(adw::Toast::new(&glib_escape(&said::notice(&n))));
                }
                open(&parent, import, &toasts);
            }
            other => uploads::queued(&toasts, other, what),
        }
    });
}

/// The pages the navigation view holds for [shown]: one per step, by its tag.
pub fn page_tag(shown: &ImportShown) -> &'static str {
    match shown.step {
        ImportStep::Reading => "reading",
        ImportStep::Invalid => "invalid",
        ImportStep::Ready | ImportStep::Running | ImportStep::Done => "ready",
    }
}

/// *Import* is shown while the download reads and wherever something may be imported (the web's `step === 'reading' ||
/// canImport`), sensitive only where the core offers it.
pub fn offers_import(shown: &ImportShown) -> bool {
    match shown.step {
        ImportStep::Reading => true,
        ImportStep::Ready | ImportStep::Running => shown.total > 0,
        _ => false,
    }
}

struct Inner {
    id: i64,
    watch: RefCell<Option<Watch>>,
    dialog: adw::Dialog,
    nav: adw::NavigationView,
    toasts: adw::ToastOverlay,
    /// The shown page's *Import* and *Upload as a file* (each page's header bar has its own).
    import_button: RefCell<gtk::Button>,
    as_file: RefCell<gtk::Button>,
    /// The page drawn last, by its tag; its content is drawn anew on each update.
    page: RefCell<String>,
    content: RefCell<Option<gtk::Box>>,
    /// The dialog is gone (closed by the person, the core or the done import).
    closed: Cell<bool>,
    /// The done import's line was said.
    told: Cell<bool>,
}

/// Opens the import [import] over [parent]; [toasts] says the imported line once the dialog closed.
pub fn open(parent: &gtk::Widget, import: i64, toasts: &adw::ToastOverlay) {
    let nav = adw::NavigationView::new();
    let dialog = adw::Dialog::builder().content_width(480).content_height(420).title(words::import_title()).child(&nav).build();
    let inner = Rc::new(Inner {
        id: import,
        watch: RefCell::new(None),
        dialog: dialog.clone(),
        nav: nav.clone(),
        toasts: toasts.clone(),
        import_button: RefCell::new(gtk::Button::new()),
        as_file: RefCell::new(gtk::Button::new()),
        page: RefCell::new(String::new()),
        content: RefCell::new(None),
        closed: Cell::new(false),
        told: Cell::new(false),
    });
    let held = inner.clone();
    dialog.connect_closed(move |_| held.end());
    inner.show_page("reading", None);
    dialog.present(Some(parent));
    let Some((watch, updates)) = bridge::watch(&Watched::Import { import }) else {
        eprintln!("veyq: import {import}: its watch did not start");
        return;
    };
    *inner.watch.borrow_mut() = Some(watch);
    let weak = Rc::downgrade(&inner);
    drop(inner);
    glib::spawn_future_local(follow(weak, updates));
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Import { shown: Some(s), .. }) => {
                eprintln!(
                    "veyq: import {}: {:?}, {} items, left out {}, fits {:?}, {} of {}, said {}, can import {}, as file {}, closed {}",
                    inner.id,
                    s.step,
                    s.total,
                    s.left_out.len(),
                    s.fits,
                    s.done,
                    s.total,
                    s.said.iter().map(|n| n.msg.as_str()).collect::<Vec<_>>().join(", "),
                    s.can_import,
                    s.can_as_file,
                    s.closed
                );
                inner.draw(&s);
            }
            Ok(Update::Import { shown: None, .. }) => {
                inner.closed.set(true);
                inner.dialog.force_close();
                return;
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: an import update did not read: {:?}", e.0),
        }
    }
}

impl Inner {
    /// The dialog went: the core's import closes (a run stops and its nodes go to the trash), the watch ends.
    fn end(&self) {
        if !self.closed.replace(true) {
            bridge::send(Call::ImportCancel { import: self.id });
        }
        self.watch.borrow_mut().take();
    }

    fn draw(self: &Rc<Self>, s: &ImportShown) {
        if s.step == ImportStep::Done && !self.told.replace(true) {
            let line = s.said.iter().map(said::notice).collect::<Vec<_>>().join("\n");
            self.toasts.add_toast(adw::Toast::new(&glib_escape(&line)));
        }
        if s.closed || s.step == ImportStep::Done {
            // done (the dialog's close closes the core's import), or closed by the core already (an upload as a file)
            if s.closed {
                self.closed.set(true);
            }
            self.dialog.force_close();
            return;
        }
        let running = s.step == ImportStep::Running;
        self.dialog.set_can_close(!running);
        let tag = page_tag(s);
        if *self.page.borrow() != tag {
            self.show_page(tag, Some(s));
        }
        let import_button = self.import_button.borrow();
        import_button.set_label(&said::key(&s.import_word));
        import_button.set_visible(offers_import(s));
        import_button.set_sensitive(s.can_import && !running);
        let as_file = self.as_file.borrow();
        as_file.set_visible(s.can_as_file);
        as_file.set_sensitive(!running);
        if let Some(content) = self.content.borrow().as_ref() {
            fill(content, s);
        }
    }

    /// The page of [tag] in place of the one shown (a step's page, never one to go back to).
    fn show_page(self: &Rc<Self>, tag: &str, s: Option<&ImportShown>) {
        let header = adw::HeaderBar::new();
        header.set_show_start_title_buttons(false);
        header.set_show_end_title_buttons(false);
        let cancel = gtk::Button::builder().label(words::entry_cancel()).build();
        let weak = Rc::downgrade(self);
        cancel.connect_clicked(move |_| {
            let Some(inner) = weak.upgrade() else { return };
            eprintln!("veyq: import {}: cancel", inner.id);
            // a run's Cancel goes through the core first: what it made is trashed, then the core closes the import
            inner.closed.set(true);
            bridge::send(Call::ImportCancel { import: inner.id });
            inner.dialog.force_close();
        });
        header.pack_start(&cancel);
        let import = self.id;
        let import_button = gtk::Button::builder().label(words::import_submit()).sensitive(false).build();
        import_button.add_css_class("suggested-action");
        import_button.connect_clicked(move |_| {
            eprintln!("veyq: import {import}: run");
            bridge::send(Call::ImportRun { import });
        });
        let as_file = gtk::Button::builder().label(words::import_upload_as_file()).visible(false).build();
        as_file.connect_clicked(move |_| {
            eprintln!("veyq: import {import}: upload as a file");
            bridge::send(Call::ImportAsFile { import });
        });
        header.pack_end(&import_button);
        header.pack_end(&as_file);
        *self.import_button.borrow_mut() = import_button;
        *self.as_file.borrow_mut() = as_file;
        let content = gtk::Box::new(gtk::Orientation::Vertical, 12);
        content.set_margin_top(12);
        content.set_margin_bottom(24);
        content.set_margin_start(12);
        content.set_margin_end(12);
        if let Some(s) = s {
            fill(&content, s);
        } else {
            reading(&content);
        }
        let holder = adw::Clamp::builder().maximum_size(560).child(&content).build();
        let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&holder).build();
        let view = adw::ToolbarView::new();
        view.add_top_bar(&header);
        view.set_content(Some(&scroll));
        let page = adw::NavigationPage::with_tag(&view, &words::import_title(), tag);
        page.set_can_pop(false);
        self.nav.replace(&[page]);
        *self.page.borrow_mut() = tag.to_owned();
        *self.content.borrow_mut() = Some(content);
    }
}

fn clear(content: &gtk::Box) {
    while let Some(child) = content.first_child() {
        content.remove(&child);
    }
}

fn label(text: &str) -> gtk::Label {
    gtk::Label::builder().label(text).wrap(true).xalign(0.0).build()
}

fn reading(content: &gtk::Box) {
    clear(content);
    let row = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    row.append(&adw::Spinner::builder().width_request(24).height_request(24).build());
    row.append(&label(&words::import_reading()));
    content.append(&row);
}

/// The page's content for [s]: what reads, what would be made, what is left out, the run, what the core says.
fn fill(content: &gtk::Box, s: &ImportShown) {
    if s.step == ImportStep::Reading {
        return reading(content);
    }
    clear(content);
    if s.step != ImportStep::Invalid {
        let root = label(&s.root);
        root.add_css_class("heading");
        content.append(&root);
        if let Some(summary) = &s.summary
            && s.fits != Some(false)
        {
            content.append(&label(&said::notice(summary)));
        }
        if !s.left_out.is_empty() {
            let skipped = label(&words::import_skipped());
            content.append(&skipped);
            for n in &s.left_out {
                let line = label(&format!("• {}", said::notice(n)));
                line.add_css_class("dim-label");
                content.append(&line);
            }
        }
        if let (ImportStep::Running, Some(progress)) = (&s.step, &s.progress) {
            content.append(&label(&said::notice(progress)));
            let bar = gtk::ProgressBar::new();
            bar.set_fraction(f64::from(s.done) / f64::from(s.total.max(1)));
            content.append(&bar);
        } else if s.can_import {
            let note = label(&words::import_notice());
            note.add_css_class("dim-label");
            content.append(&note);
        }
    }
    for n in &s.said {
        let line = label(&said::notice(n));
        if s.step != ImportStep::Invalid && n.msg != "import_nothing" {
            line.add_css_class("error");
        }
        content.append(&line);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn shown(step: ImportStep, total: i32) -> ImportShown {
        serde_json::from_value(serde_json::json!({
            "step": step, "root": "Trip", "summary": null, "leftOut": [], "fits": null, "done": 0, "total": total,
            "progress": null, "said": [], "canImport": total > 0, "importWord": "import_submit", "canAsFile": true, "closed": false,
        }))
        .unwrap()
    }

    #[test]
    fn import_is_offered_while_reading_and_where_something_may_be_imported() {
        assert!(offers_import(&shown(ImportStep::Reading, 0)));
        assert!(offers_import(&shown(ImportStep::Ready, 3)));
        assert!(!offers_import(&shown(ImportStep::Ready, 0)), "nothing to import offers only Cancel");
        assert!(!offers_import(&shown(ImportStep::Invalid, 0)));
    }

    #[test]
    fn a_step_has_its_page_and_a_run_stays_on_the_summary() {
        assert_eq!(page_tag(&shown(ImportStep::Reading, 0)), "reading");
        assert_eq!(page_tag(&shown(ImportStep::Ready, 3)), "ready");
        assert_eq!(page_tag(&shown(ImportStep::Running, 3)), "ready");
        assert_eq!(page_tag(&shown(ImportStep::Invalid, 0)), "invalid");
    }
}
