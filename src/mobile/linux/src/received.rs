//! A received link opened in the app as its guest (ADR 0150 decisions 2–4, 7 and 8; cr-app-received-links phase 9),
//! GNOME's way. *Open a link* ([open_link]) is an `AdwDialog` with the link's field, a Paste button that reads the
//! clipboard on the tap only, and the core's reason where the text is no link the app opens — with *Open in the browser*
//! for a moderator's review (`Received.Open` answers `ReceivedProblem`). It stands on the sign-in page under the sign-in
//! and in the switcher's menu (owner 2026-11-19).
//!
//! An opened link is a [ReceivedView]: a screen above the shell (the window's content while it stands, [present]) with its
//! own header — *Back* inside the link, at its root a question first (`link_leave_*`: the key lives only in memory), the
//! root's or the page's name with the link's host, and a menu with *Keep* (signed in: a space picked, the personal space
//! first), *Report shared content* (to the link's own operator) and *Not you?* —, no sidebar, no bar. Behind the gates
//! (the password, the terms, the note — each the core's `ShareGates` state) it shows the member's own page of the kind
//! with a guest's rights: a level's rows, a file's page ([crate::file_page]), a Memory's ([crate::memory_page]), a Pot's
//! ([crate::pot]) over the models the core registered for the link (`ReceivedPage`), or a WRITE_ONLY guest's page
//! (its files added by path, what it sent in this visit). A kept link's page shows the same view in place
//! ([crate::bookmark_page]).
//!
//! Before any guest write the web's notice of the guest-write record stands under the header while the link writes
//! (`ReceivedShown.recordDays`, ADR 0093 § 10, inbox #1018), its *more* opening the link's instance's privacy notice;
//! a write that needs the guest's name asks it first (`share_guest_name_*`, the notice in the question too).
//!
//! What the host keeps of the link: nothing but the text the person pasted, held by the view only to offer *Open in the
//! browser* where the core refuses the link's instance; no update carries the link or its key (ADR 0150 decision 7), and
//! nothing here writes either anywhere or logs it.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{
    self, Watch,
    wire::{Answer, ApplicationId, Call, Msg, NodeAt, ObjectRef, ReceivedGate, ReceivedPage, ReceivedPageKind, ReceivedRefusal, ReceivedShown, Update, Watched},
};
use crate::file_page::FilePage;
use crate::home::{glib_escape, kind_icon};
use crate::memory_page::MemoryPage;
use crate::pot::PotPage;
use crate::{said, words};

thread_local! {
    /// How an opened link becomes the window's screen: set once by the window (`main.rs`).
    static PRESENTER: RefCell<Option<Presenter>> = const { RefCell::new(None) };
}

/// How a received link's screen is shown: its id and, for a pasted link, the text for the browser.
type Presenter = Rc<dyn Fn(i64, Option<String>)>;

/// One of the screen's own actions, run on its inner state.
type OnInner = Rc<dyn Fn(&Rc<Inner>)>;

/// Sets how a received link's screen is shown above everything else: [f] is handed the link's id and, for a pasted one,
/// the text to offer the browser where the app refuses its instance.
pub fn set_presenter(f: impl Fn(i64, Option<String>) + 'static) {
    PRESENTER.with(|p| *p.borrow_mut() = Some(Rc::new(f)));
}

/// Shows received link [received] as its own screen ([set_presenter]).
pub fn present(received: i64, browser: Option<String>) {
    if let Some(f) = PRESENTER.with(|p| p.borrow().clone()) {
        f(received, browser);
    } else {
        eprintln!("veyq: a received link opened with no screen to show it");
        bridge::send(Call::ReceivedClose { received });
    }
}

/// *Open a link* over [parent]: the field, Paste, the core's reason, *Open in the browser* for what only a browser opens.
pub fn open_link(parent: &impl IsA<gtk::Widget>) {
    let entry = adw::EntryRow::builder().title(words::link_paste_hint()).input_purpose(gtk::InputPurpose::Url).build();
    entry.set_widget_name("open-link-text");
    let paste = gtk::Button::builder().label(words::link_paste()).valign(gtk::Align::Center).build();
    paste.add_css_class("flat");
    entry.add_suffix(&paste);
    let group = adw::PreferencesGroup::new();
    group.add(&entry);
    let problem = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
    problem.add_css_class("error");
    let browser = gtk::Button::builder().label(words::link_open_in_browser()).halign(gtk::Align::Center).visible(false).build();
    browser.add_css_class("pill");
    let open = gtk::Button::builder().label(words::link_open()).halign(gtk::Align::Center).sensitive(false).build();
    open.add_css_class("suggested-action");
    open.add_css_class("pill");
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).margin_top(12).margin_bottom(24).margin_start(12).margin_end(12).build();
    for w in [group.upcast_ref::<gtk::Widget>(), problem.upcast_ref(), open.upcast_ref(), browser.upcast_ref()] {
        column.append(w);
    }
    let view = adw::ToolbarView::new();
    view.add_top_bar(&adw::HeaderBar::new());
    view.set_content(Some(&column));
    let dialog = adw::Dialog::builder().title(words::link_open()).content_width(520).child(&view).build();

    // the clipboard read on the tap only, never on its own (ADR 0150 decision 4)
    let e = entry.clone();
    paste.connect_clicked(move |b| {
        let (e, clipboard) = (e.clone(), b.clipboard());
        glib::spawn_future_local(async move {
            if let Ok(Some(text)) = clipboard.read_text_future().await {
                e.set_text(&text);
            }
        });
    });
    let (o, p, b) = (open.clone(), problem.clone(), browser.clone());
    entry.connect_changed(move |e| {
        o.set_sensitive(!e.text().trim().is_empty());
        p.set_visible(false);
        b.set_visible(false);
    });
    let (e, d, p, b) = (entry.clone(), dialog.clone(), problem.clone(), browser.clone());
    let ask = Rc::new(move || {
        let text = e.text().to_string();
        if text.trim().is_empty() {
            return;
        }
        let (d, p, b) = (d.clone(), p.clone(), b.clone());
        glib::spawn_future_local(async move {
            match bridge::call(&Call::ReceivedOpen { text: text.clone() }).await.map(|(a, _)| a) {
                Ok(Answer::ReceivedOpened { received }) => {
                    d.close();
                    present(received, Some(text.trim().to_string()));
                }
                Ok(Answer::ReceivedProblem { problem, browser }) => {
                    p.set_label(&said_msg(&problem));
                    p.set_visible(true);
                    b.set_visible(browser);
                }
                other => eprintln!("veyq: Open a link answered {}", answer_kind(&other)),
            }
        });
    });
    let a = ask.clone();
    open.connect_clicked(move |_| a());
    entry.connect_entry_activated(move |_| ask());
    let e = entry.clone();
    browser.connect_clicked(move |b| launch(b, e.text().trim()));
    dialog.present(Some(parent));
}

/// Hands [uri] to the system browser (the same thing a click on the link in a chat would have done), never logged.
fn launch(from: &impl IsA<gtk::Widget>, uri: &str) {
    let window = from.root().and_downcast::<gtk::Window>();
    gtk::UriLauncher::new(uri).launch(window.as_ref(), gio::Cancellable::NONE, |r| {
        if let Err(e) = r {
            eprintln!("veyq: the browser did not open: {e}");
        }
    });
}

/// A `Msg` the core answered with (an enum on the wire, its name the catalog's key in capitals), in the person's words.
pub fn said_msg(m: &Msg) -> String {
    let name = serde_json::to_value(m).ok().and_then(|v| v.as_str().map(str::to_lowercase)).unwrap_or_default();
    said::key(&name)
}

/// An answer's kind alone for stderr (a received link's fields are no one's business there).
fn answer_kind(a: &Result<Answer, bridge::Unreadable>) -> String {
    match a {
        Ok(a) => serde_json::to_value(a).ok().and_then(|v| v["type"].as_str().map(str::to_owned)).unwrap_or_default(),
        Err(_) => "unreadable".into(),
    }
}

/// How a received link's screen stands: on its own above the shell, or in place on a kept link's page.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Mode {
    OnItsOwn,
    InPlace,
}

/// What a guest's header offers: *Back* (on its own: always, at the root it asks first; in place: inside the link only),
/// *Keep*, the report and the page's own write.
#[derive(Debug, PartialEq, Eq)]
pub struct Offers {
    pub back: bool,
    pub keep: bool,
    pub report: bool,
    pub not_you: bool,
}

/// [Offers] for [s] in [mode].
pub fn offers(s: &ReceivedShown, mode: Mode) -> Offers {
    let ready = s.gate == ReceivedGate::Ready;
    Offers {
        back: mode == Mode::OnItsOwn || !s.at_root,
        keep: ready && s.keep_offered && mode == Mode::OnItsOwn,
        report: ready,
        not_you: ready && s.guest_name.is_some(),
    }
}

/// The guest-write record's notice for [days] (the web's two sentences), or none for a link that does not write.
pub fn record_notice(days: Option<i32>) -> Option<String> {
    days.filter(|d| *d > 0).map(|d| format!("{} {}", words::share_guest_record_kept(d as i64), words::share_guest_record_objection()))
}

/// What the content shows, as a key: drawn anew only when it changed (a level's rows, another page, another gate).
pub fn drawn_key(s: &ReceivedShown) -> String {
    let page = s.page.as_ref().map(|p| {
        let rows: Vec<&str> = p.rows.iter().map(|r| r.id.as_str()).collect();
        format!("{:?}|{:?}|{:?}|{:?}|{}|{}", p.kind, p.file, p.gallery, p.pot, rows.join(","), p.sent.join("\n"))
    });
    format!("{:?}|{:?}|{}|{}", s.gate, s.refusal, s.asked_again, page.unwrap_or_default())
}

/// The page a kind's own module draws, held while it shows.
enum Held {
    /// Held only so the file's page stays open (its model closes when it goes).
    #[allow(dead_code)]
    File(FilePage),
    Memory(MemoryPage),
    Pot(PotPage),
}

/// A received link's screen ([Mode]): its header, the notice of the record, the gate or the page.
pub struct ReceivedView {
    pub received: i64,
    inner: Rc<Inner>,
}

struct Inner {
    received: i64,
    mode: Mode,
    /// What the window shows: the toasts over the navigation the lightbox is pushed onto.
    root: adw::ToastOverlay,
    nav: adw::NavigationView,
    window: gtk::Window,
    title: adw::WindowTitle,
    back: gtk::Button,
    /// The page's own write: a Memory's *Add photos*, a Pot's *Add entry*, shown where the page takes one.
    add: gtk::Button,
    menu: gtk::MenuButton,
    actions: gio::SimpleActionGroup,
    notice: gtk::Box,
    notice_text: gtk::Label,
    notice_more: gtk::LinkButton,
    guest_line: gtk::Box,
    guest_text: gtk::Label,
    content: adw::Bin,
    shown: RefCell<Option<ReceivedShown>>,
    drawn: RefCell<String>,
    held: RefCell<Option<Held>>,
    password: RefCell<Option<adw::PasswordEntryRow>>,
    watch: RefCell<Option<Watch>>,
    /// The pasted text, for *Open in the browser* where the core refuses the link's instance; none for a kept link.
    browser: Option<String>,
    leave_asked: Cell<bool>,
    report_opened: Cell<bool>,
    left: Cell<bool>,
    on_left: RefCell<Option<Box<dyn Fn()>>>,
}

impl ReceivedView {
    /// The screen of received link [received] in [mode] over [window]; [on_left] runs once the link was let go.
    pub fn new(received: i64, mode: Mode, window: &gtk::Window, browser: Option<String>, on_left: impl Fn() + 'static) -> Self {
        let title = adw::WindowTitle::new("", "");
        let back = gtk::Button::builder().icon_name("go-previous-symbolic").tooltip_text(words::back()).build();
        back.update_property(&[gtk::accessible::Property::Label(&words::back())]);
        let add = crate::create_action::create_action(&words::memory_add(), || {});
        add.set_visible(false);
        let menu = gtk::MenuButton::builder().icon_name("view-more-symbolic").tooltip_text(words::node_more()).visible(false).build();
        let header = adw::HeaderBar::new();
        header.set_title_widget(Some(&title));
        header.pack_start(&back);
        header.pack_start(&add);
        header.pack_end(&menu);
        if mode == Mode::InPlace {
            // in place the window's own buttons are the shell's header's
            header.set_show_end_title_buttons(false);
            header.set_show_start_title_buttons(false);
            header.set_show_back_button(false);
        }
        let notice_text = gtk::Label::builder().wrap(true).xalign(0.0).build();
        notice_text.add_css_class("caption");
        let notice_more = gtk::LinkButton::with_label("about:blank", &words::share_guest_record_more());
        notice_more.set_halign(gtk::Align::Start);
        let notice = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(2).margin_start(12).margin_end(12).margin_top(6).margin_bottom(6).visible(false).build();
        notice.set_widget_name("guest-write-record-notice");
        notice.append(&notice_text);
        notice.append(&notice_more);
        let guest_text = gtk::Label::builder().wrap(true).xalign(0.0).hexpand(true).build();
        let not_you = gtk::Button::builder().label(words::share_guest_not_you()).valign(gtk::Align::Center).build();
        not_you.add_css_class("flat");
        let guest_line = gtk::Box::builder().spacing(6).margin_start(12).margin_end(12).visible(false).build();
        guest_line.append(&guest_text);
        guest_line.append(&not_you);
        let content = adw::Bin::builder().vexpand(true).build();
        let view = adw::ToolbarView::new();
        view.add_top_bar(&header);
        view.add_top_bar(&notice);
        view.add_top_bar(&guest_line);
        view.set_content(Some(&content));
        let nav = adw::NavigationView::new();
        nav.add(&adw::NavigationPage::new(&view, &words::link_open()));
        let root = adw::ToastOverlay::new();
        root.set_child(Some(&nav));
        let actions = gio::SimpleActionGroup::new();
        root.insert_action_group("received", Some(&actions));
        let inner = Rc::new(Inner {
            received,
            mode,
            root,
            nav,
            window: window.clone(),
            title,
            back,
            add,
            menu,
            actions,
            notice,
            notice_text,
            notice_more,
            guest_line,
            guest_text,
            content,
            shown: RefCell::new(None),
            drawn: RefCell::new(String::new()),
            held: RefCell::new(None),
            password: RefCell::new(None),
            watch: RefCell::new(None),
            browser,
            leave_asked: Cell::new(false),
            report_opened: Cell::new(false),
            left: Cell::new(false),
            on_left: RefCell::new(Some(Box::new(on_left))),
        });
        Inner::connect(&inner, &not_you);
        inner.draw();
        if let Some((watch, updates)) = bridge::watch(&Watched::Received { received }) {
            *inner.watch.borrow_mut() = Some(watch);
            glib::spawn_future_local(follow(Rc::downgrade(&inner), updates));
        }
        Self { received, inner }
    }

    pub fn widget(&self) -> &adw::ToastOverlay {
        &self.inner.root
    }
}

impl Drop for ReceivedView {
    fn drop(&mut self) {
        self.inner.watch.borrow_mut().take();
        self.inner.held.borrow_mut().take();
        // on its own the screen's going lets go of the link (the window closed); in place the kept link's page does
        if self.inner.mode == Mode::OnItsOwn && !self.inner.left.get() {
            bridge::send(Call::ReceivedClose { received: self.received });
        }
    }
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Received { shown, .. }) => {
                if let Some(s) = &shown {
                    // the gate and the page's kind alone: never a name, the host or the link
                    eprintln!("veyq: received: {:?}, page {:?}, at root {}, leaving {}", s.gate, s.page.as_ref().map(|p| p.kind), s.at_root, s.leaving);
                }
                match shown {
                    Some(s) if !s.left => {
                        *inner.shown.borrow_mut() = Some(s);
                        inner.draw();
                    }
                    _ => inner.leave(),
                }
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a received link's update did not read: {:?}", e.0),
        }
    }
}

impl Inner {
    fn connect(self: &Rc<Self>, not_you: &gtk::Button) {
        let id = self.received;
        self.back.connect_clicked(move |_| bridge::send(Call::ReceivedBack { received: id }));
        let w = Rc::downgrade(self);
        self.add.connect_clicked(move |_| {
            if let Some(i) = w.upgrade() {
                i.write(|i| match i.held.borrow().as_ref() {
                    Some(Held::Memory(p)) => p.add(),
                    Some(Held::Pot(p)) => p.add_entry(),
                    _ => {}
                });
            }
        });
        let w = Rc::downgrade(self);
        not_you.connect_clicked(move |_| {
            if let Some(i) = w.upgrade() {
                i.ask_not_you();
            }
        });
        let w = Rc::downgrade(self);
        self.notice_more.connect_activate_link(move |_| {
            if let Some(i) = w.upgrade()
                && let Some(uri) = i.shown.borrow().as_ref().and_then(|s| s.privacy.clone())
            {
                launch(&i.root, &uri);
            }
            glib::Propagation::Stop
        });
        let add = |name: &str, f: OnInner| {
            let action = gio::SimpleAction::new(name, None);
            let w = Rc::downgrade(self);
            action.connect_activate(move |_, _| {
                if let Some(i) = w.upgrade() {
                    f(&i);
                }
            });
            self.actions.add_action(&action);
        };
        add("keep", Rc::new(|i| i.keep()));
        add("report", Rc::new(|i| i.report()));
        add("not-you", Rc::new(|i| i.ask_not_you()));
    }

    fn draw(self: &Rc<Self>) {
        let shown = self.shown.borrow().clone();
        let Some(s) = shown else {
            self.title.set_title("");
            self.back.set_visible(self.mode == Mode::OnItsOwn);
            self.set_content(&spinner());
            return;
        };
        let ready = s.gate == ReceivedGate::Ready;
        let name = s.page.as_ref().map(|p| p.name.clone()).filter(|n| !n.is_empty()).unwrap_or_else(|| s.root_name.clone());
        self.title.set_title(if ready && !name.is_empty() { &name } else { &s.host });
        self.title.set_subtitle(if ready { &s.host } else { "" });
        let o = offers(&s, self.mode);
        self.back.set_visible(o.back);
        self.draw_menu(&o);
        match record_notice(s.record_days) {
            Some(text) => {
                self.notice_text.set_label(&text);
                self.notice_more.set_visible(s.privacy.is_some());
                self.notice.set_visible(true);
            }
            None => self.notice.set_visible(false),
        }
        match s.guest_name.as_ref().filter(|_| ready) {
            Some(n) => {
                self.guest_text.set_label(&words::share_guest_as(n));
                self.guest_line.set_visible(true);
            }
            None => self.guest_line.set_visible(false),
        }
        let key = drawn_key(&s);
        if *self.drawn.borrow() != key {
            *self.drawn.borrow_mut() = key;
            self.draw_content(&s);
        }
        if let Some(pw) = self.password.borrow().as_ref() {
            pw.set_sensitive(!s.busy);
        }
        if s.leaving && !self.leave_asked.get() {
            self.leave_asked.set(true);
            self.ask_leave();
        } else if !s.leaving {
            self.leave_asked.set(false);
        }
        if s.report_asked && ready && !self.report_opened.get() {
            self.report_opened.set(true);
            bridge::send(Call::ReceivedReportShown { received: self.received });
            self.report();
        }
    }

    fn draw_menu(&self, o: &Offers) {
        let menu = gio::Menu::new();
        if o.keep {
            menu.append(Some(&words::link_keep()), Some("received.keep"));
        }
        if o.report {
            menu.append(Some(&words::bookmark_report_share()), Some("received.report"));
        }
        if o.not_you {
            menu.append(Some(&words::share_guest_not_you()), Some("received.not-you"));
        }
        self.menu.set_visible(menu.n_items() > 0);
        self.menu.set_menu_model(Some(&menu));
    }

    fn set_content(&self, w: &impl IsA<gtk::Widget>) {
        self.content.set_child(Some(w));
    }

    fn draw_content(self: &Rc<Self>, s: &ReceivedShown) {
        self.held.borrow_mut().take();
        self.password.borrow_mut().take();
        self.add.set_visible(false);
        match s.gate {
            ReceivedGate::Loading => self.set_content(&spinner()),
            ReceivedGate::Refused => {
                let title = match s.refusal {
                    Some(ReceivedRefusal::OtherVersion) => words::link_refused_other_version(&s.host),
                    Some(ReceivedRefusal::NoLinks) => words::link_refused_no_links(&s.host),
                    _ => words::link_refused_unreachable(&s.host),
                };
                let page = adw::StatusPage::builder().icon_name("dialog-warning-symbolic").description(glib_escape(&title)).vexpand(true).build();
                if let Some(text) = self.browser.clone() {
                    let b = gtk::Button::builder().label(words::link_open_in_browser()).halign(gtk::Align::Center).build();
                    b.add_css_class("pill");
                    b.connect_clicked(move |b| launch(b, &text));
                    page.set_child(Some(&b));
                }
                self.set_content(&page);
            }
            ReceivedGate::Locked => self.draw_password(s),
            ReceivedGate::Terms => {
                let id = self.received;
                let texts = [s.terms.as_str(), s.operator_section.as_str(), s.record.as_str()];
                self.set_content(&gate(&words::share_terms_title(), &words::share_terms_intro(), &texts, &words::share_accept_continue(), move || {
                    bridge::send(Call::ReceivedAcceptTerms { received: id })
                }));
            }
            ReceivedGate::Note => {
                let id = self.received;
                self.set_content(&gate(&words::share_note_title(), &words::share_note_intro(), &[s.note.as_str()], &words::share_accept_continue(), move || {
                    bridge::send(Call::ReceivedAcceptNote { received: id })
                }));
            }
            ReceivedGate::Unavailable => self.set_content(
                &adw::StatusPage::builder().icon_name("action-unavailable-symbolic").title(words::share_unavailable()).description(words::share_unavailable_hint()).vexpand(true).build(),
            ),
            ReceivedGate::Ready => match &s.page {
                None => self.set_content(&spinner()),
                Some(p) => self.draw_page(s, p),
            },
        }
    }

    fn draw_password(self: &Rc<Self>, s: &ReceivedShown) {
        let entry = adw::PasswordEntryRow::builder().title(words::share_password_label()).build();
        let group = adw::PreferencesGroup::new();
        group.add(&entry);
        let wrong = gtk::Label::builder().label(words::share_password_wrong()).wrap(true).visible(s.asked_again).build();
        wrong.add_css_class("error");
        let unlock = gtk::Button::builder().label(words::share_password_unlock()).halign(gtk::Align::Center).build();
        unlock.add_css_class("suggested-action");
        unlock.add_css_class("pill");
        let id = self.received;
        let e = entry.clone();
        let send = Rc::new(move || {
            let password = e.text().to_string();
            if password.is_empty() {
                return;
            }
            e.set_text("");
            bridge::send(Call::ReceivedPassword { received: id, password });
        });
        let s2 = send.clone();
        unlock.connect_clicked(move |_| s2());
        entry.connect_entry_activated(move |_| send());
        let column = column();
        column.append(&heading(&words::share_password_title()));
        column.append(&body(&words::share_password_intro()));
        column.append(&group);
        column.append(&wrong);
        column.append(&unlock);
        self.set_content(&scrolled(&column));
        entry.grab_focus();
        *self.password.borrow_mut() = Some(entry);
    }

    fn draw_page(self: &Rc<Self>, s: &ReceivedShown, p: &ReceivedPage) {
        let node = NodeAt { scope_id: String::new(), node_id: String::new(), kind: p.node_kind.clone(), name: p.name.clone() };
        let id = self.received;
        match p.kind {
            ReceivedPageKind::Level => {
                if p.rows.is_empty() {
                    self.set_content(&adw::StatusPage::builder().icon_name(kind_icon(&p.node_kind)).title(glib_escape(&p.name)).vexpand(true).build());
                    return;
                }
                let group = adw::PreferencesGroup::new();
                for r in &p.rows {
                    let row = adw::ActionRow::builder().title(glib_escape(&r.name)).activatable(true).build();
                    row.add_prefix(&gtk::Image::from_icon_name(kind_icon(&r.kind)));
                    row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
                    let node = r.id.clone();
                    row.connect_activated(move |_| bridge::send(Call::ReceivedOpenNode { received: id, node: node.clone() }));
                    group.add(&row);
                }
                let column = column();
                column.append(&group);
                self.set_content(&scrolled(&column));
            }
            ReceivedPageKind::File => {
                let Some(file) = p.file else { return };
                let page = FilePage::opened(&node, file);
                self.set_content(page.widget());
                *self.held.borrow_mut() = Some(Held::File(page));
            }
            ReceivedPageKind::Gallery => {
                let Some(gallery) = p.gallery else { return };
                let page = MemoryPage::opened(&node, gallery, &self.window, &self.root, &self.nav);
                page.on_chapter(move |chapter| bridge::send(Call::ReceivedOpenNode { received: id, node: chapter.node_id.clone() }));
                let add = self.add.downgrade();
                page.on_shown(move |shown, _| {
                    if let Some(add) = add.upgrade() {
                        add.set_tooltip_text(Some(&words::memory_add()));
                        add.update_property(&[gtk::accessible::Property::Label(&words::memory_add())]);
                        add.set_visible(shown.can_upload);
                    }
                });
                self.set_content(page.widget());
                *self.held.borrow_mut() = Some(Held::Memory(page));
            }
            ReceivedPageKind::Pot => {
                let Some(pot) = p.pot else { return };
                let r = ObjectRef { scope_id: String::new(), node_id: String::new(), kind: p.node_kind.clone(), application: ApplicationId::Expenses, name: p.name.clone() };
                let page = PotPage::opened(&r, pot, &self.window, &self.root);
                let add = self.add.downgrade();
                page.on_read(move |can_write| {
                    if let Some(add) = add.upgrade() {
                        add.set_tooltip_text(Some(&words::pot_add()));
                        add.update_property(&[gtk::accessible::Property::Label(&words::pot_add())]);
                        add.set_visible(can_write);
                    }
                });
                self.set_content(page.widget());
                *self.held.borrow_mut() = Some(Held::Pot(page));
            }
            ReceivedPageKind::DropBox | ReceivedPageKind::Uploader => self.draw_upload_page(s, p),
            ReceivedPageKind::NotShown => self.set_content(
                &adw::StatusPage::builder().icon_name(kind_icon(&p.node_kind)).title(glib_escape(&p.name)).description(words::share_not_shown()).vexpand(true).build(),
            ),
        }
    }

    /// A WRITE_ONLY guest's page (a drop box's, a Memory's uploader): its files added by path, what it sent in this visit.
    fn draw_upload_page(self: &Rc<Self>, _s: &ReceivedShown, p: &ReceivedPage) {
        let column = column();
        column.append(&adw::StatusPage::builder().icon_name(kind_icon(&p.node_kind)).title(glib_escape(&p.name)).build());
        let add = gtk::Button::builder().label(words::memory_add()).halign(gtk::Align::Center).build();
        add.add_css_class("suggested-action");
        add.add_css_class("pill");
        add.set_widget_name("guest-upload");
        let w = Rc::downgrade(self);
        add.connect_clicked(move |_| {
            if let Some(i) = w.upgrade() {
                i.write(|i| i.pick_and_upload());
            }
        });
        column.append(&add);
        if !p.sent.is_empty() {
            let group = adw::PreferencesGroup::new();
            for name in &p.sent {
                let row = adw::ActionRow::builder().title(glib_escape(name)).build();
                row.add_prefix(&gtk::Image::from_icon_name("object-select-symbolic"));
                group.add(&row);
            }
            column.append(&group);
        }
        self.set_content(&scrolled(&column));
    }

    /// GTK's open dialog (several files), their paths to the core (`Received.Upload`).
    fn pick_and_upload(self: &Rc<Self>) {
        let (id, parent, toasts) = (self.received, self.window.clone(), self.root.clone());
        glib::spawn_future_local(async move {
            let dialog = gtk::FileDialog::builder().title(words::memory_add()).modal(true).build();
            let picked = match dialog.open_multiple_future(Some(&parent)).await {
                Ok(picked) => picked,
                Err(e) => {
                    if !e.matches(gtk::DialogError::Dismissed) && !e.matches(gtk::DialogError::Cancelled) {
                        eprintln!("veyq: the open dialog failed: {e}");
                    }
                    return;
                }
            };
            let paths: Vec<String> = (0..picked.n_items())
                .filter_map(|i| picked.item(i).and_downcast::<gio::File>())
                .filter_map(|f| f.path().and_then(|p| p.to_str().map(str::to_owned)))
                .collect();
            if paths.is_empty() {
                return;
            }
            match bridge::call(&Call::ReceivedUpload { received: id, paths }).await.map(|(a, _)| a) {
                Ok(Answer::Done) => {}
                other => {
                    eprintln!("veyq: a guest's upload answered {}", answer_kind(&other));
                    toasts.add_toast(adw::Toast::new(&words::share_failed()));
                }
            }
        });
    }

    /// A guest's write: its name asked first where the link writes and the guest has none (the web's naming step), the
    /// write itself once the core took the name.
    fn write(self: &Rc<Self>, then: impl Fn(&Rc<Inner>) + 'static) {
        let named = self.shown.borrow().as_ref().is_some_and(|s| s.guest_name.is_some());
        if named {
            return then(self);
        }
        let then: OnInner = Rc::new(then);
        let entry = gtk::Entry::builder().activates_default(true).build();
        let body = match record_notice(self.shown.borrow().as_ref().and_then(|s| s.record_days)) {
            Some(n) => format!("{}\n\n{}", words::share_guest_name_hint(), n),
            None => words::share_guest_name_hint(),
        };
        let ask = adw::AlertDialog::builder().heading(words::share_guest_name_title()).body(body).extra_child(&entry).build();
        ask.add_responses(&[("cancel", &words::entry_cancel()), ("name", &words::share_save())]);
        ask.set_response_appearance("name", adw::ResponseAppearance::Suggested);
        ask.set_default_response(Some("name"));
        ask.set_close_response("cancel");
        let w = Rc::downgrade(self);
        ask.connect_response(None, move |_, response| {
            let name = entry.text().trim().to_string();
            if response != "name" || name.is_empty() {
                return;
            }
            let Some(i) = w.upgrade() else { return };
            let (id, w, then) = (i.received, Rc::downgrade(&i), then.clone());
            glib::spawn_future_local(async move {
                let answer = bridge::call(&Call::ReceivedName { received: id, name }).await.map(|(a, _)| a);
                let Some(i) = w.upgrade() else { return };
                match answer {
                    Ok(Answer::Done) => then(&i),
                    other => {
                        eprintln!("veyq: the guest's name answered {}", answer_kind(&other));
                        i.root.add_toast(adw::Toast::new(&words::share_failed()));
                    }
                }
            });
        });
        ask.present(Some(&self.root));
    }

    fn ask_not_you(self: &Rc<Self>) {
        let ask = adw::AlertDialog::builder().heading(words::share_guest_not_you_title()).build();
        ask.add_responses(&[("cancel", &words::entry_cancel()), ("forget", &words::share_guest_not_you_accept())]);
        ask.set_response_appearance("forget", adw::ResponseAppearance::Destructive);
        ask.set_close_response("cancel");
        let id = self.received;
        ask.connect_response(None, move |_, response| {
            if response == "forget" {
                bridge::send(Call::ReceivedForgetGuest { received: id });
            }
        });
        ask.present(Some(&self.root));
    }

    fn ask_leave(self: &Rc<Self>) {
        let ask = adw::AlertDialog::builder().heading(words::link_leave_title()).body(words::link_leave_body()).build();
        ask.add_responses(&[("stay", &words::entry_cancel()), ("leave", &words::link_leave_accept())]);
        ask.set_response_appearance("leave", adw::ResponseAppearance::Destructive);
        ask.set_default_response(Some("stay"));
        ask.set_close_response("stay");
        let id = self.received;
        ask.connect_response(None, move |_, response| {
            bridge::send(if response == "leave" { Call::ReceivedLeave { received: id } } else { Call::ReceivedStay { received: id } });
        });
        ask.present(Some(&self.root));
    }

    /// *Keep*: the places the core offers (the personal space first), one picked, the link kept at its top.
    fn keep(self: &Rc<Self>) {
        let w = Rc::downgrade(self);
        let id = self.received;
        glib::spawn_future_local(async move {
            let Ok((Answer::KeepPlaces { places }, _)) = bridge::call(&Call::ReceivedKeepPlaces { received: id }).await else { return };
            let Some(i) = w.upgrade() else { return };
            if places.is_empty() {
                return;
            }
            let group = adw::PreferencesGroup::new();
            let mut first: Option<gtk::CheckButton> = None;
            let chosen: Rc<RefCell<(String, String)>> = Rc::new(RefCell::new((places[0].space.scope_id.clone(), said::piece(&places[0].name))));
            for p in &places {
                let check = gtk::CheckButton::new();
                if let Some(f) = &first {
                    check.set_group(Some(f));
                } else {
                    check.set_active(true);
                    first = Some(check.clone());
                }
                let name = said::piece(&p.name);
                let row = adw::ActionRow::builder().title(glib_escape(&name)).activatable_widget(&check).build();
                row.add_prefix(&check);
                let (c, scope) = (chosen.clone(), p.space.scope_id.clone());
                check.connect_toggled(move |b| {
                    if b.is_active() {
                        *c.borrow_mut() = (scope.clone(), name.clone());
                    }
                });
                group.add(&row);
            }
            let ask = adw::AlertDialog::builder().heading(words::link_keep_where()).extra_child(&group).build();
            ask.add_responses(&[("cancel", &words::entry_cancel()), ("keep", &words::link_keep())]);
            ask.set_response_appearance("keep", adw::ResponseAppearance::Suggested);
            ask.set_default_response(Some("keep"));
            ask.set_close_response("cancel");
            let toasts = i.root.clone();
            ask.connect_response(None, move |_, response| {
                if response != "keep" {
                    return;
                }
                let (scope, name) = chosen.borrow().clone();
                let toasts = toasts.clone();
                glib::spawn_future_local(async move {
                    let said = match bridge::call(&Call::ReceivedKeep { received: id, scope }).await.map(|(a, _)| a) {
                        Ok(Answer::Done) => words::link_kept(&name),
                        other => {
                            eprintln!("veyq: Keep answered {}", answer_kind(&other));
                            words::bookmark_create_failed()
                        }
                    };
                    toasts.add_toast(adw::Toast::new(&said));
                });
            });
            ask.present(Some(&i.root));
        });
    }

    /// *Report shared content*: core's report sheet of the link, to its own operator ([crate::report]).
    fn report(self: &Rc<Self>) {
        let name = self.shown.borrow().as_ref().map(|s| s.root_name.clone()).unwrap_or_default();
        let node = NodeAt { scope_id: String::new(), node_id: String::new(), kind: String::new(), name };
        crate::report::open_asked(&self.root, &node, Call::ReceivedReport { received: self.received }, |other| {
            eprintln!("veyq: the link's report answered {}", answer_kind(&other));
        });
    }

    /// The link let go: the screen returns where the person was (once).
    fn leave(&self) {
        if self.left.replace(true) {
            return;
        }
        self.held.borrow_mut().take();
        if let Some(f) = self.on_left.borrow_mut().take() {
            f();
        }
    }
}

fn spinner() -> gtk::Widget {
    let spinner = adw::Spinner::builder().width_request(32).height_request(32).build();
    adw::StatusPage::builder().child(&spinner).vexpand(true).build().upcast()
}

fn column() -> gtk::Box {
    gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).margin_top(24).margin_bottom(24).margin_start(12).margin_end(12).build()
}

fn scrolled(column: &gtk::Box) -> gtk::ScrolledWindow {
    let clamp = adw::Clamp::builder().maximum_size(640).child(column).build();
    gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build()
}

fn heading(text: &str) -> gtk::Label {
    let l = gtk::Label::builder().label(text).wrap(true).justify(gtk::Justification::Center).accessible_role(gtk::AccessibleRole::Heading).build();
    l.add_css_class("title-2");
    l
}

fn body(text: &str) -> gtk::Label {
    gtk::Label::builder().label(text).wrap(true).xalign(0.0).selectable(true).build()
}

/// A gate of texts to accept (the terms and their operator's section and record, or the note), then [accept].
fn gate(title: &str, intro: &str, texts: &[&str], accept: &str, on_accept: impl Fn() + 'static) -> gtk::ScrolledWindow {
    let column = column();
    column.append(&heading(title));
    column.append(&body(intro));
    for t in texts.iter().filter(|t| !t.trim().is_empty()) {
        let text = body(t);
        let frame = gtk::Frame::builder().child(&text).build();
        text.set_margin_top(12);
        text.set_margin_bottom(12);
        text.set_margin_start(12);
        text.set_margin_end(12);
        column.append(&frame);
    }
    let button = gtk::Button::builder().label(accept).halign(gtk::Align::Center).build();
    button.add_css_class("suggested-action");
    button.add_css_class("pill");
    button.connect_clicked(move |_| on_accept());
    column.append(&button);
    scrolled(&column)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn shown(gate: ReceivedGate) -> ReceivedShown {
        serde_json::from_value(serde_json::json!({
            "host": "photos.example.org", "gate": gate, "refusal": null, "askedAgain": false, "busy": false,
            "terms": "", "operatorSection": "", "record": "", "note": "", "rootName": "Summer", "role": "READ_WRITE",
            "page": null, "atRoot": true, "guestName": null, "leaving": false, "left": false, "keepOffered": true,
            "reportAsked": false, "recordDays": null, "privacy": null
        }))
        .expect("a ReceivedShown reads")
    }

    #[test]
    fn on_its_own_back_always_stands_in_place_only_inside_the_link() {
        let mut s = shown(ReceivedGate::Ready);
        assert!(offers(&s, Mode::OnItsOwn).back);
        assert!(!offers(&s, Mode::InPlace).back);
        s.at_root = false;
        assert!(offers(&s, Mode::InPlace).back);
    }

    #[test]
    fn keep_and_the_report_only_behind_the_gates_keep_only_on_its_own() {
        let s = shown(ReceivedGate::Locked);
        assert_eq!(offers(&s, Mode::OnItsOwn), Offers { back: true, keep: false, report: false, not_you: false });
        let s = shown(ReceivedGate::Ready);
        assert_eq!(offers(&s, Mode::OnItsOwn), Offers { back: true, keep: true, report: true, not_you: false });
        assert!(!offers(&s, Mode::InPlace).keep);
    }

    #[test]
    fn the_record_notice_stands_for_a_writing_link_with_its_days_and_never_for_one_that_does_not_write() {
        let _held = crate::LOCALE.lock();
        assert_eq!(record_notice(None), None);
        assert_eq!(record_notice(Some(0)), None);
        let n = record_notice(Some(30)).expect("a notice for 30 days");
        assert!(n.contains("30"), "{n}");
        assert!(n.contains(&words::share_guest_record_objection()), "{n}");
    }

    #[test]
    fn a_problem_is_said_in_the_catalogs_words_by_its_key() {
        let _held = crate::LOCALE.lock();
        assert_eq!(said_msg(&serde_json::from_value(serde_json::json!("LINK_PROBLEM_NO_KEY")).expect("a Msg reads")), words::link_problem_no_key());
    }

    #[test]
    fn the_content_is_drawn_anew_for_another_gate_or_page_never_for_a_busy_flag() {
        let a = shown(ReceivedGate::Locked);
        let mut b = shown(ReceivedGate::Locked);
        b.busy = true;
        assert_eq!(drawn_key(&a), drawn_key(&b));
        b.asked_again = true;
        assert_ne!(drawn_key(&a), drawn_key(&b));
        assert_ne!(drawn_key(&a), drawn_key(&shown(ReceivedGate::Terms)));
    }
}
