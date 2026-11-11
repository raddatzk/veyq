//! A node's header (the header CR's Phase 2; ADR 0127 decision 2): core's `NodeHeaderModel`, opened per node on screen
//! (`Header.Open`) and watched while it shows, drawn GNOME's way — the plan's buttons in the content's header bar (the
//! heart as a toggle, a Pot's Download), the rest in its "More" menu with the menu's last group behind a separator —, its
//! rename and its delete asked in an `AdwAlertDialog`, the trash's Undo an `AdwToast` held for exactly as long as the core
//! offers it (no timer here: the toast goes when the state's `undo` does). What is offered, in which order and with which
//! words is the core's; this lays it out.
//!
//! The move and the copy open core's sheet ([crate::move_sheet]); a folder's download goes where GTK's save dialog says
//! (`Header.Download`, under the core's archive name), a Pot's through its page (`Pot.Download`). The share, the report
//! and the cover are the CR's later phases: their ids are not drawn yet ([DRAWN]), never shown as a control that answers
//! "not yet".
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::Facts, wire::HeaderShown, wire::NodeAt, wire::Update, wire::Watched};
use crate::{said, words};

/// The most buttons the content's header bar holds beside its title (GNOME: a few, the rest in the menu).
pub const MAX_BUTTONS: i32 = 2;

/// The header's ids drawn so far; every other id of the plan is left out until its phase.
pub const DRAWN: &[&str] = &["favorite", "rename", "delete", "download", "move", "copy"];

/// Whether [id] is drawn for a node of [kind]: a download where it is the node's archive (a file's is its own bytes,
/// Depot's page).
pub fn drawn(id: &str, kind: &str) -> bool {
    DRAWN.contains(&id) && (id != "download" || kind != "FILE")
}

/// The window's action an id fires (`win.header-<id>`; a Pot's download is the Pot page's `win.pot-download`).
pub fn action_of(id: &str, kind: &str) -> String {
    if id == "download" && kind == "EXPENSES_POT" { "win.pot-download".into() } else { format!("win.header-{id}") }
}

/// The header of one node, opened in the core when made and closed when dropped.
pub struct Header {
    pub node: NodeAt,
    inner: Rc<Inner>,
}

struct Inner {
    id: Cell<Option<i64>>,
    shown: RefCell<Option<HeaderShown>>,
    watch: RefCell<Option<Watch>>,
    /// Where it draws: the header bar's end, and what to redraw there when the state changes.
    bar: gtk::Box,
    toasts: adw::ToastOverlay,
    undo_toast: RefCell<Option<adw::Toast>>,
    /// Whether it still draws into the bar: a header kept for its Undo after its page left does not.
    on_bar: Cell<bool>,
    /// Facts the page learnt before the core answered the open: sent once it did.
    later: RefCell<Option<Option<Facts>>>,
    /// A state showed the trash's undo offered: its end is the next state without it.
    seen_undo: Cell<bool>,
}

impl Header {
    /// Opens the header of [node] (its page knowing [facts]), drawing into [bar] and saying what it did in [toasts].
    pub fn open(node: &NodeAt, facts: Option<Facts>, bar: &gtk::Box, toasts: &adw::ToastOverlay) -> Self {
        let inner = Rc::new(Inner {
            id: Cell::new(None),
            shown: RefCell::new(None),
            watch: RefCell::new(None),
            bar: bar.clone(),
            toasts: toasts.clone(),
            undo_toast: RefCell::new(None),
            on_bar: Cell::new(true),
            later: RefCell::new(None),
            seen_undo: Cell::new(false),
        });
        clear(bar);
        let weak = Rc::downgrade(&inner);
        let call = Call::HeaderOpen { node: node.clone(), facts, max_buttons: MAX_BUTTONS };
        glib::spawn_future_local(async move {
            let Ok((Answer::HeaderOpened { header }, _)) = bridge::call(&call).await else {
                eprintln!("veyq: the header did not open in the core");
                return;
            };
            let Some(inner) = weak.upgrade() else {
                bridge::send(Call::HeaderClose { header });
                return;
            };
            inner.id.set(Some(header));
            if let Some(facts) = inner.later.borrow_mut().take() {
                bridge::send(Call::HeaderFacts { header, facts });
            }
            let Some((watch, updates)) = bridge::watch(&Watched::Header { header }) else { return };
            *inner.watch.borrow_mut() = Some(watch);
            drop(inner);
            follow(weak, updates).await;
        });
        Self { node: node.clone(), inner }
    }

    /// What the kind's page knows now (a Pot read: whether it takes writes).
    pub fn facts(&self, facts: Option<Facts>) {
        match self.inner.id.get() {
            Some(header) => bridge::send(Call::HeaderFacts { header, facts }),
            None => *self.inner.later.borrow_mut() = Some(facts),
        }
    }

    /// Whether the trash's Undo still stands: the header is kept until it is over.
    pub fn undoing(&self) -> bool {
        self.inner.undo_toast.borrow().is_some()
    }

    /// Stops drawing into the bar (another node's header took it); a standing Undo stays.
    pub fn leave_bar(&self) {
        self.inner.on_bar.set(false);
    }

    /// Pins or unpins: the heart turns at once (the core's state), a failure is said.
    pub fn pin(&self) {
        let Some(header) = self.inner.id.get() else { return };
        let toasts = self.inner.toasts.clone();
        glib::spawn_future_local(async move {
            say(&toasts, bridge::call(&Call::HeaderPin { header }).await.map(|(a, _)| a), "Header.Pin");
        });
    }

    /// Rename: the name asked for in an alert dialog over [parent], asked again with the core's problem.
    pub fn rename(&self, parent: &impl IsA<gtk::Widget>) {
        let Some(header) = self.inner.id.get() else { return };
        let toasts = self.inner.toasts.clone();
        let parent = parent.clone().upcast::<gtk::Widget>();
        ask_name(&parent, &words::node_rename(), &words::node_name(), &self.node.name, None, &words::node_rename(), move |name, again| {
            let toasts = toasts.clone();
            glib::spawn_future_local(async move {
                match bridge::call(&Call::HeaderRename { header, name: name.clone() }).await.map(|(a, _)| a) {
                    Ok(Answer::Renamed { problem: Some(p), .. }) => again(name, said::notice(&p)),
                    Ok(Answer::Renamed { problem: None, notice }) => {
                        eprintln!("veyq: header rename: Renamed");
                        if let Some(n) = notice {
                            toasts.add_toast(adw::Toast::new(&said::notice(&n)));
                        }
                    }
                    other => say(&toasts, other, "Header.Rename"),
                }
            });
        });
    }

    /// The move ([copy] false) or copy sheet over [parent] (core's `MoveSheet`, [crate::move_sheet]).
    pub fn move_sheet(&self, parent: &impl IsA<gtk::Widget>, copy: bool) {
        crate::move_sheet::open(parent, &self.node, copy, &self.inner.toasts);
    }

    /// Downloads the node as the web's ZIP: GTK's save dialog (the file chooser portal where there is one) suggests the
    /// core's name, the core writes the file the dialog chose and removes it when it did not finish, and a toast says what
    /// the core said (with what it left out).
    pub fn download(&self, parent: &gtk::Window) {
        let Some(header) = self.inner.id.get() else { return };
        let Some(suggested) = self.inner.shown.borrow().as_ref().map(|s| s.archive_name.clone()) else { return };
        let (name, parent, toasts) = (self.node.name.clone(), parent.clone(), self.inner.toasts.clone());
        glib::spawn_future_local(async move {
            let failed = || adw::Toast::new(&words::download_failed(&name));
            let dialog = gtk::FileDialog::builder().title(words::node_download()).initial_name(suggested).modal(true).build();
            if let Some(downloads) = glib::user_special_dir(glib::UserDirectory::Downloads).filter(|d| d.is_dir()) {
                dialog.set_initial_folder(Some(&gio::File::for_path(downloads)));
            }
            let file = match dialog.save_future(Some(&parent)).await {
                Ok(file) => file,
                Err(e) if e.matches(gtk::DialogError::Dismissed) || e.matches(gtk::DialogError::Cancelled) => return,
                Err(e) => {
                    eprintln!("veyq: the save dialog failed: {e}");
                    toasts.add_toast(failed());
                    return;
                }
            };
            // the core opens the path itself; one that is no UTF-8 does not cross the wire
            let Some(path) = file.path().and_then(|p| p.to_str().map(str::to_owned)) else {
                eprintln!("veyq: the save dialog chose no local UTF-8 path");
                toasts.add_toast(failed());
                return;
            };
            let call = Call::HeaderDownload { header, path, deleted_name: words::pot_deleted_person() };
            match bridge::call(&call).await.map(|(answer, _)| answer) {
                Ok(Answer::Downloaded { notice, left_out, .. }) => {
                    eprintln!("veyq: header download: {}, left out: {}", notice.msg, left_out.is_some());
                    let said = [Some(&notice), left_out.as_ref()].into_iter().flatten().map(said::notice).collect::<Vec<_>>().join(" ");
                    toasts.add_toast(adw::Toast::new(&said));
                }
                other => {
                    eprintln!("veyq: Header.Download answered {other:?}");
                    toasts.add_toast(failed());
                }
            }
        });
    }

    /// The delete: asked first, with the kind's title and the core's question; then to the trash, its Undo offered.
    pub fn delete(&self, parent: &impl IsA<gtk::Widget>) {
        let Some(header) = self.inner.id.get() else { return };
        let shown = self.inner.shown.borrow();
        let Some(shown) = shown.as_ref() else { return };
        let title = shown.plan.as_ref().and_then(|p| p.last.iter().find(|a| a.id == "delete")).and_then(|a| a.word.as_deref()).map(said::key).unwrap_or_default();
        let ask = adw::AlertDialog::builder().heading(&title).body(said::notice(&shown.delete_question)).build();
        ask.add_responses(&[("cancel", &words::entry_cancel()), ("delete", &words::entry_delete())]);
        ask.set_response_appearance("delete", adw::ResponseAppearance::Destructive);
        ask.set_default_response(Some("cancel"));
        ask.set_close_response("cancel");
        let weak = Rc::downgrade(&self.inner);
        ask.connect_response(None, move |_, response| {
            if response != "delete" {
                return;
            }
            let weak = weak.clone();
            glib::spawn_future_local(async move {
                let answered = bridge::call(&Call::HeaderTrash { header }).await.map(|(a, _)| a);
                let Some(inner) = weak.upgrade() else { return };
                match answered {
                    Ok(Answer::Trashed { notice, undoable }) => {
                        eprintln!("veyq: header trash: {}, undo offered: {undoable}", notice.msg);
                        inner.offer(&said::notice(&notice), undoable, header);
                    }
                    other => say(&inner.toasts, other, "Header.Trash"),
                }
            });
        });
        ask.present(Some(parent));
    }
}

impl Inner {
    /// What the trash said, with Undo where the core offers it now: a toast that stays until the offer ends.
    fn offer(self: &Rc<Self>, text: &str, undo: bool, header: i64) {
        let toast = adw::Toast::new(text);
        if undo {
            toast.set_button_label(Some(&words::trash_undo()));
            toast.set_timeout(0);
            let toasts = self.toasts.clone();
            toast.connect_button_clicked(move |_| {
                let toasts = toasts.clone();
                glib::spawn_future_local(async move {
                    let answered = bridge::call(&Call::HeaderUndo { header }).await.map(|(a, _)| a);
                    eprintln!("veyq: header undo: {answered:?}");
                    say(&toasts, answered, "Header.Undo");
                });
            });
            *self.undo_toast.borrow_mut() = Some(toast.clone());
        }
        self.toasts.add_toast(toast);
    }

    fn draw(&self) {
        let shown = self.shown.borrow();
        // the offer ended (its window, or the undo): the toast goes with it — once a state showed the offer, so an
        // update from before the trash that arrives after its answer ends nothing
        let offered = shown.as_ref().is_some_and(|s| s.undo.is_some());
        if offered {
            self.seen_undo.set(true);
        } else if self.seen_undo.replace(false)
            && let Some(t) = self.undo_toast.borrow_mut().take()
        {
            eprintln!("veyq: header undo offer ended");
            t.dismiss();
        }
        let Some(s) = shown.as_ref() else { return };
        if s.gone || !self.on_bar.get() {
            return;
        }
        clear(&self.bar);
        let Some(plan) = s.plan.as_ref() else { return };
        let kind = s.node.kind.as_str();
        let menu = gio::Menu::new();
        let first = gio::Menu::new();
        let last = gio::Menu::new();
        // GNOME's header bar packs from the end: the menu outermost, the buttons before it
        for a in plan.buttons.iter().filter(|a| drawn(&a.id, kind)) {
            let label = a.word.as_deref().map(said::key).unwrap_or_default();
            let button = if a.id == "favorite" {
                let icon = if s.pinned == Some(true) { "starred-symbolic" } else { "non-starred-symbolic" };
                gtk::Button::builder().icon_name(icon).tooltip_text(&label).action_name(action_of(&a.id, kind)).build()
            } else {
                gtk::Button::builder().icon_name(icon_of(&a.id)).tooltip_text(&label).action_name(action_of(&a.id, kind)).build()
            };
            button.update_property(&[gtk::accessible::Property::Label(&label)]);
            self.bar.append(&button);
        }
        for (group, ids) in [(&first, &plan.menu), (&last, &plan.last)] {
            for a in ids.iter().filter(|a| drawn(&a.id, kind)) {
                group.append(a.word.as_deref().map(said::key).as_deref(), Some(&action_of(&a.id, kind)));
            }
        }
        menu.append_section(None, &first);
        menu.append_section(None, &last);
        if first.n_items() + last.n_items() > 0 {
            let more = gtk::MenuButton::builder().icon_name("view-more-symbolic").tooltip_text(words::node_more()).menu_model(&menu).build();
            self.bar.append(&more);
        }
    }
}

impl Drop for Header {
    fn drop(&mut self) {
        self.inner.watch.borrow_mut().take();
        if let Some(t) = self.inner.undo_toast.borrow_mut().take() {
            t.dismiss();
        }
        if let Some(header) = self.inner.id.get() {
            bridge::send(Call::HeaderClose { header });
        }
    }
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Header { shown, .. }) => {
                if let Some(s) = &shown {
                    eprintln!("veyq: header {}: pinned {:?}, undo {}, gone {}, plan {}", s.node.kind, s.pinned, s.undo.is_some(), s.gone,
                        s.plan.as_ref().map(|p| [&p.buttons, &p.menu, &p.last].iter().map(|g| g.iter().map(|a| a.id.as_str()).collect::<Vec<_>>().join(",")).collect::<Vec<_>>().join(" | ")).unwrap_or_default());
                }
                *inner.shown.borrow_mut() = shown;
                inner.draw();
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a header update did not read: {:?}", e.0),
        }
    }
}

fn clear(bar: &gtk::Box) {
    while let Some(child) = bar.first_child() {
        bar.remove(&child);
    }
}

/// A plan's button's icon, in GNOME's icon set.
pub fn icon_of(id: &str) -> &'static str {
    match id {
        "download" => "folder-download-symbolic",
        "move" => "folder-symbolic",
        "copy" => "edit-copy-symbolic",
        "rename" => "document-edit-symbolic",
        "delete" => "user-trash-symbolic",
        _ => "view-more-symbolic",
    }
}

/// Says what an action answered, where it says something: its notice as a toast, an unexpected answer on stderr.
pub fn say(toasts: &adw::ToastOverlay, answered: Result<Answer, bridge::Unreadable>, what: &str) {
    match answered {
        Ok(Answer::Said { notice: None }) | Ok(Answer::Done) => eprintln!("veyq: {what}: Done"),
        Ok(Answer::Said { notice: Some(n) }) => {
            eprintln!("veyq: {what}: {}", n.msg);
            toasts.add_toast(adw::Toast::new(&said::notice(&n)));
        }
        other => eprintln!("veyq: {what} answered {other:?}"),
    }
}

/// A name asked for in an `AdwAlertDialog` over [parent] (a rename, a new folder): [heading], an entry titled [field]
/// holding [name], [problem] under it where the core refused the last one, and [confirm]. [send] gets the name typed and
/// a way to ask again with the core's problem.
pub fn ask_name(
    parent: &gtk::Widget,
    heading: &str,
    field: &str,
    name: &str,
    problem: Option<&str>,
    confirm: &str,
    send: impl Fn(String, Box<dyn Fn(String, String)>) + Clone + 'static,
) {
    let entry = adw::EntryRow::builder().title(field).text(name).build();
    let rows = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    rows.add_css_class("boxed-list");
    rows.append(&entry);
    let ask = adw::AlertDialog::builder().heading(heading).extra_child(&rows).build();
    if let Some(p) = problem {
        ask.set_body(p);
    }
    ask.add_responses(&[("cancel", &words::entry_cancel()), ("confirm", confirm)]);
    ask.set_response_appearance("confirm", adw::ResponseAppearance::Suggested);
    ask.set_default_response(Some("confirm"));
    ask.set_close_response("cancel");
    ask.set_response_enabled("confirm", !name.trim().is_empty());
    let on_text = ask.clone();
    entry.connect_changed(move |e| on_text.set_response_enabled("confirm", !e.text().trim().is_empty()));
    entry.set_activates_default(true);
    let focus = entry.clone();
    let (heading, field, confirm) = (heading.to_owned(), field.to_owned(), confirm.to_owned());
    let at = parent.clone();
    ask.connect_response(None, move |_, response| {
        if response != "confirm" {
            return;
        }
        let (at, heading, field, confirm, again_send) = (at.clone(), heading.clone(), field.clone(), confirm.clone(), send.clone());
        let again: Box<dyn Fn(String, String)> = Box::new(move |name, problem| {
            ask_name(&at, &heading, &field, &name, Some(&problem), &confirm, again_send.clone());
        });
        send(entry.text().to_string(), again);
    });
    ask.present(Some(parent));
    focus.grab_focus();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_phases_actions_so_far_are_drawn_and_a_download_where_it_is_the_nodes_archive() {
        for id in ["favorite", "rename", "delete", "move", "copy", "download"] {
            assert!(drawn(id, "FOLDER") && drawn(id, "EXPENSES_POT"), "{id}");
        }
        assert!(!drawn("download", "FILE"), "a file's download is its own bytes");
        for id in ["share", "report", "cover", "an-applications-own"] {
            assert!(!drawn(id, "FOLDER") && !drawn(id, "EXPENSES_POT"), "{id} drawn before its phase");
        }
        assert_eq!(action_of("download", "EXPENSES_POT"), "win.pot-download");
        assert_eq!(action_of("download", "FOLDER"), "win.header-download");
        assert_eq!(action_of("move", "EXPENSES_POT"), "win.header-move");
        assert_eq!(action_of("rename", "FOLDER"), "win.header-rename");
    }
}
