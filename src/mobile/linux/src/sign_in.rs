//! The way in (ADR 0123's Consequences, the Linux app): what the core's `SessionModel.Gate` says — the full sign-in with
//! the Secret Key ([SignIn]), the unlock of an account this device trusts ([Unlock]) and, right after a sign-in, the offer
//! to stay signed in ([Offer]) — drawn as GNOME draws a form: a clamped boxed list of rows and one suggested button. The
//! person's input goes to the core as one call; the core decides what it means and answers with the next gate.
//!
//! The sign-in also takes the Emergency Kit (en.account.key-model § The Emergency Kit): "Open Emergency Kit" under the
//! Secret Key row picks the file (GTK's open dialog, PDFs and pictures) and hands the core its path
//! (`Session.SignInKit`), which reads it and asks this side to draw its pages ([crate::kit_drawing]); "Scan Emergency
//! Kit" beside it, only where the Camera portal names a camera, scans its code ([crate::qr_camera], kind `KIT`). Either
//! way the gate's `kit` fills the email and the server and the Secret Key row says "From your Emergency Kit" — the key
//! itself the core holds, never this side.
//!
//! Below them the two ways in from another device (ADR 0059 §1; `cr-app-device-linking`): "Scan a code from another
//! device", only where a camera is, and "Sign in with a QR code" — each a ceremony of [crate::device_link] over the
//! server the form names (nothing a scan reads changes it).
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, wire::Answer, wire::Call, wire::Gate, wire::KitShown, wire::Method, wire::ScanKind, wire::ScanShown, wire::ScanState, wire::StaySignedIn, wire::Why};
use crate::qr_camera::ScanView;
use crate::dev_login::DevSignIn;
use crate::{said, theme, words};

/// What the unlock of [method]'s record says, its line and its button, by the level its key is ([level], the record's
/// own, the core's): the password's line for a password-trusted account; for a remembered one the presence line where
/// the key asks the person, else (`LOGIN`: nothing is asked, so the unlock shows only after a resume that failed) the
/// line that says so and "Try again" (owner 2026-11-11).
pub fn unlock_words(method: Method, level: Option<StaySignedIn>) -> (String, String) {
    match (method, level) {
        (Method::Device, _) => (words::unlock_password_hint(), words::unlock_submit()),
        (Method::DeviceKey, Some(StaySignedIn::Login)) => (words::unlock_login_hint(), words::unlock_retry()),
        (Method::DeviceKey, _) => (words::unlock_remembered_hint(), words::unlock_submit()),
    }
}

/// The column every way-in page shares: the brand, a heading, then [rows], clamped and scrolling, under a bare header bar.
fn page(heading: &gtk::Label, rows: &[&gtk::Widget]) -> adw::ToolbarView {
    let column = gtk::Box::new(gtk::Orientation::Vertical, 18);
    column.set_margin_top(24);
    column.set_margin_bottom(24);
    column.set_margin_start(12);
    column.set_margin_end(12);
    let brand = gtk::Image::from_icon_name(theme::APP_ID);
    brand.set_pixel_size(96);
    heading.add_css_class("title-1");
    heading.set_wrap(true);
    heading.set_justify(gtk::Justification::Center);
    column.append(&brand);
    column.append(heading);
    for row in rows {
        column.append(*row);
    }
    let clamp = adw::Clamp::builder().maximum_size(420).child(&column).build();
    let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).child(&clamp).build();
    let page = adw::ToolbarView::new();
    page.add_top_bar(&adw::HeaderBar::builder().show_title(false).build());
    page.set_content(Some(&scroll));
    page
}

fn error_label() -> gtk::Label {
    let error = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
    error.add_css_class("error");
    error
}

fn show_error(label: &gtk::Label, error: &Option<String>) {
    match error {
        Some(key) => {
            label.set_label(&said::key(key));
            label.set_visible(true);
        }
        None => label.set_visible(false),
    }
}

/// The sign-in page: built once, given each gate the core sends.
pub struct SignIn {
    pub page: adw::ToolbarView,
    heading: gtk::Label,
    server: adw::EntryRow,
    email: adw::EntryRow,
    password: adw::PasswordEntryRow,
    secret_key: adw::PasswordEntryRow,
    /// The Secret Key row while a kit's key is held: "From your Emergency Kit", and the way back to typing it.
    kit_row: adw::ActionRow,
    open_kit: adw::ButtonRow,
    scan_kit: adw::ButtonRow,
    /// The two device-linking entries; the scan only where a camera is.
    link_scan: adw::ButtonRow,
    link_show: adw::ButtonRow,
    /// The kit's server replaced a different one the form held: said, never refused.
    other_server: gtk::Label,
    /// The kit the form last showed, so a new one fills the email and the server once.
    shown_kit: RefCell<Option<KitShown>>,
    submit: gtk::Button,
    cancel: gtk::Button,
    spinner: adw::Spinner,
    error: gtk::Label,
}

impl SignIn {
    pub fn new() -> Rc<Self> {
        let server = adw::EntryRow::new();
        server.set_title(&words::signin_server());
        server.set_input_purpose(gtk::InputPurpose::Url);
        let email = adw::EntryRow::new();
        email.set_title(&words::signin_email());
        email.set_input_purpose(gtk::InputPurpose::Email);
        let password = adw::PasswordEntryRow::new();
        password.set_title(&words::signin_password());
        let secret_key = adw::PasswordEntryRow::new();
        secret_key.set_title(&words::signin_secret_key());
        let kit_row = adw::ActionRow::builder().title(words::signin_secret_key()).subtitle(words::signin_kit_held()).visible(false).build();
        let clear = gtk::Button::builder().label(words::signin_kit_clear()).valign(gtk::Align::Center).build();
        clear.add_css_class("flat");
        kit_row.add_suffix(&clear);
        // the file first on a desktop (the kit CR's owner question 1); the scan only where there is a camera
        let open_kit = adw::ButtonRow::builder().title(words::signin_kit_open()).start_icon_name("document-open-symbolic").build();
        let scan_kit = adw::ButtonRow::builder().title(words::signin_kit_scan()).start_icon_name("camera-photo-symbolic").visible(false).build();

        let fields = adw::PreferencesGroup::new();
        for row in [server.upcast_ref::<gtk::Widget>(), email.upcast_ref(), password.upcast_ref(), secret_key.upcast_ref(), kit_row.upcast_ref(), open_kit.upcast_ref(), scan_kit.upcast_ref()] {
            fields.add(row);
        }
        let link_scan = adw::ButtonRow::builder().title(words::signin_link_scan()).start_icon_name("camera-photo-symbolic").visible(false).build();
        let link_show = adw::ButtonRow::builder().title(words::signin_link_show()).build();
        let links = adw::PreferencesGroup::new();
        links.add(&link_scan);
        links.add(&link_show);
        let other_server = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
        other_server.add_css_class("dim-label");
        // Staying signed in is no longer chosen here: the core offers it after the sign-in (ADR 0123 decision 8), drawn by `Offer`
        let error = error_label();
        let submit = gtk::Button::builder().label(words::signin_submit()).halign(gtk::Align::Center).build();
        submit.add_css_class("suggested-action");
        submit.add_css_class("pill");
        let cancel = gtk::Button::builder().label(words::signin_cancel()).halign(gtk::Align::Center).visible(false).build();
        cancel.add_css_class("flat");
        let spinner = adw::Spinner::builder().visible(false).build();
        let heading = gtk::Label::new(Some(&words::signin_title()));
        let page = page(&heading, &[fields.upcast_ref(), other_server.upcast_ref(), error.upcast_ref(), submit.upcast_ref(), cancel.upcast_ref(), spinner.upcast_ref(), links.upcast_ref()]);

        let s = Rc::new(Self {
            page,
            heading,
            server,
            email,
            password,
            secret_key,
            kit_row,
            open_kit,
            scan_kit,
            link_scan,
            link_show,
            other_server,
            shown_kit: RefCell::new(None),
            submit,
            cancel,
            spinner,
            error,
        });
        let ask = s.asker();
        let on_click = ask.clone();
        s.submit.connect_clicked(move |_| on_click());
        // Enter in the last field signs in, as a form's default button does
        s.secret_key.connect_entry_activated(move |_| ask());
        s.cancel.connect_clicked(|_| bridge::send(Call::SessionCancelAdd));
        clear.connect_clicked(|_| bridge::send(Call::SessionSignInKitClear));
        let w = Rc::downgrade(&s);
        s.open_kit.connect_activated(move |_| if let Some(s) = w.upgrade() {
            s.pick_kit();
        });
        let w = Rc::downgrade(&s);
        s.scan_kit.connect_activated(move |_| if let Some(s) = w.upgrade() {
            s.scan();
        });
        let w = Rc::downgrade(&s);
        s.link_scan.connect_activated(move |_| if let Some(s) = w.upgrade() {
            s.link(|server| Call::LinkScanFromTrusted { server });
        });
        let w = Rc::downgrade(&s);
        s.link_show.connect_activated(move |_| if let Some(s) = w.upgrade() {
            s.link(|server| Call::LinkShowForSignIn { server });
        });
        // the scans' entries only where the Camera portal names a camera (D7: no camera, no entry)
        let w = Rc::downgrade(&s);
        glib::spawn_future_local(async move {
            let present = crate::qr_camera::camera_present().await;
            eprintln!("veyq: the sign-in's kit and link scans {}", if present { "offered" } else { "not offered: no camera" });
            if let Some(s) = w.upgrade() {
                s.scan_kit.set_visible(present);
                s.link_scan.set_visible(present);
            }
        });
        s
    }

    /// The sign-in the form holds, sent; while a kit's key is held the Secret Key is sent empty, so the core signs in with it.
    fn asker(&self) -> impl Fn() + Clone + 'static {
        let (server, email, password, secret_key, kit_row) =
            (self.server.clone(), self.email.clone(), self.password.clone(), self.secret_key.clone(), self.kit_row.clone());
        move || {
            let typed = if kit_row.is_visible() { String::new() } else { secret_key.text().trim().to_string() };
            bridge::send(Call::SessionSignIn {
                server: server.text().trim().to_string(),
                email: email.text().trim().to_string(),
                password: password.text().to_string(),
                secret_key: typed,
            });
        }
    }

    /// A device link from the sign-in over the server the form names (`Link.ScanFromTrusted`, `Link.ShowForSignIn`).
    fn link(&self, make: impl FnOnce(String) -> Call) {
        crate::device_link::open(&self.page, make(self.server.text().trim().to_string()));
    }

    /// "Open Emergency Kit": GTK's open dialog for a PDF or a picture; the core reads the file at the path picked.
    fn pick_kit(self: &Rc<Self>) {
        let parent = self.page.root().and_downcast::<gtk::Window>();
        let w = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let kits = gtk::FileFilter::new();
            kits.add_mime_type("application/pdf");
            kits.add_mime_type("image/*");
            kits.set_name(Some(&words::signin_kit_open()));
            let filters = gtk::gio::ListStore::new::<gtk::FileFilter>();
            filters.append(&kits);
            let dialog = gtk::FileDialog::builder().title(words::signin_kit_open()).filters(&filters).default_filter(&kits).modal(true).build();
            let file = match dialog.open_future(parent.as_ref()).await {
                Ok(file) => file,
                Err(e) => {
                    eprintln!("veyq: no kit picked ({e})");
                    return;
                }
            };
            // the core opens the path itself; one that is no UTF-8 does not cross the wire
            let Some(path) = file.path().and_then(|p| p.to_str().map(str::to_owned)) else {
                eprintln!("veyq: the kit's path is no UTF-8");
                return;
            };
            let Some(s) = w.upgrade() else { return };
            let server = s.server.text().trim().to_string();
            let answered = bridge::call(&Call::SessionSignInKit { path, server }).await.map(|(a, _)| a);
            // the outcome alone: never the path or anything of the kit
            eprintln!("veyq: kit picked: {answered:?}");
        });
    }

    /// "Scan Emergency Kit": the camera in a dialog over a scan of kind `KIT`; the kit it finds goes to the sign-in in
    /// the core, and the dialog closes on it.
    fn scan(self: &Rc<Self>) {
        let hint = gtk::Label::builder().label(words::signin_kit_scan_hint()).wrap(true).justify(gtk::Justification::Center).build();
        hint.add_css_class("dim-label");
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).margin_start(12).margin_end(12).margin_bottom(12).build();
        let view_slot = gtk::Box::builder().vexpand(true).height_request(320).build();
        column.append(&view_slot);
        column.append(&hint);
        let content = adw::ToolbarView::new();
        content.add_top_bar(&adw::HeaderBar::new());
        content.set_content(Some(&column));
        let dialog = adw::Dialog::builder().title(words::signin_kit_scan()).content_width(520).content_height(480).child(&content).build();
        let closing = dialog.downgrade();
        let found = Rc::new(Cell::new(false));
        let seen = found.clone();
        let view = ScanView::open(ScanKind::Kit, Some(self.server.text().trim().to_string()), scan_said, move |shown| {
            eprintln!("veyq: kit scan {:?}", shown.state);
            if shown.state == ScanState::Found && !seen.replace(true) && let Some(d) = closing.upgrade() {
                d.close();
            }
        });
        view_slot.append(&view.widget);
        view.widget.set_hexpand(true);
        // the scan and its camera end with the dialog, however it closes
        dialog.connect_closed(move |_| view.close());
        dialog.present(self.page.root().as_ref());
    }

    /// Draws [gate] (a `Gate.SignIn`): the server and the email it knows, a kit's own replacing them once, the Secret Key
    /// row as typed or as the kit's, working or not, the core's error.
    pub fn show(&self, gate: &Gate) {
        let Gate::SignIn { working, error, server, email, adding, kit } = gate else { return };
        let new_kit = kit.is_some() && *self.shown_kit.borrow() != *kit;
        *self.shown_kit.borrow_mut() = kit.clone();
        if let Some(server) = server.as_deref().filter(|_| new_kit || self.server.text().is_empty()) {
            self.server.set_text(server);
        }
        if let Some(email) = email.as_deref().filter(|_| new_kit || self.email.text().is_empty()) {
            self.email.set_text(email);
        }
        self.secret_key.set_visible(kit.is_none());
        self.kit_row.set_visible(kit.is_some());
        if new_kit {
            self.secret_key.set_text("");
        }
        match kit.as_ref().and_then(|k| Some((k.server.as_deref()?, k.typed_server.as_deref()?))) {
            Some((made_at, before)) => {
                self.other_server.set_label(&words::signin_kit_other_server(made_at, before));
                self.other_server.set_visible(true);
            }
            None => self.other_server.set_visible(false),
        }
        self.heading.set_label(&if *adding { words::signin_add_title() } else { words::signin_title() });
        self.cancel.set_visible(*adding);
        self.cancel.set_sensitive(!working);
        self.submit.set_sensitive(!working);
        self.open_kit.set_sensitive(!working);
        self.link_scan.set_sensitive(!working);
        self.link_show.set_sensitive(!working);
        self.spinner.set_visible(*working);
        show_error(&self.error, error);
    }

    /// The password and the Secret Key typed, forgotten once the core has left the sign-in (signed in, unlocking): the
    /// page is built once per window, so without this a sign-out or "Use another account" showed them filled again, a
    /// press of Sign in away from the account (measured on the Fedora VM, 2026-11-13).
    pub fn forget(&self) {
        self.password.set_text("");
        self.secret_key.set_text("");
    }

    /// The debug build's dev principal: the form filled, and signed in through it unless [DevSignIn::fill_only].
    pub fn dev(&self, dev: DevSignIn) {
        self.server.set_text(&dev.server);
        self.email.set_text(&dev.email);
        self.password.set_text(&dev.password);
        self.secret_key.set_text(&dev.secret_key);
        if !dev.fill_only {
            self.asker()();
        }
    }
}

/// What the kit's scan shows but its camera: no camera or a refused one says to open the file or type the key instead
/// (the web's `scanError`); a found kit closes the dialog, so it shows nothing.
fn scan_said(shown: &ScanShown) -> Option<gtk::Widget> {
    match shown.state {
        ScanState::NoCamera | ScanState::Denied => Some(
            adw::StatusPage::builder()
                .icon_name("camera-disabled-symbolic")
                .description(glib::markup_escape_text(&words::signin_kit_scan_denied()).as_str())
                .vexpand(true)
                .build()
                .upcast(),
        ),
        _ => None,
    }
}

/// An account this device trusts (ADR 0123 decision 8; `Gate.Unlock`): the password alone for a password-trusted one, a
/// button that asks the core again for a remembered one, and the way to another account.
pub struct Unlock {
    pub page: adw::ToolbarView,
    account: adw::ActionRow,
    group: adw::PreferencesGroup,
    password: adw::PasswordEntryRow,
    submit: gtk::Button,
    other: gtk::Button,
    spinner: adw::Spinner,
    error: gtk::Label,
}

impl Unlock {
    pub fn new() -> Self {
        let account = adw::ActionRow::new();
        account.add_prefix(&gtk::Image::from_icon_name("avatar-default-symbolic"));
        let password = adw::PasswordEntryRow::new();
        password.set_title(&words::signin_password());
        let group = adw::PreferencesGroup::new();
        group.add(&account);
        group.add(&password);
        let error = error_label();
        let submit = gtk::Button::builder().label(words::unlock_submit()).halign(gtk::Align::Center).build();
        submit.add_css_class("suggested-action");
        submit.add_css_class("pill");
        let other = gtk::Button::builder().label(words::unlock_other()).halign(gtk::Align::Center).build();
        other.add_css_class("flat");
        let spinner = adw::Spinner::builder().visible(false).build();
        let heading = gtk::Label::new(Some(&words::unlock_title()));
        let page = page(&heading, &[group.upcast_ref(), error.upcast_ref(), submit.upcast_ref(), other.upcast_ref(), spinner.upcast_ref()]);

        let s = Self { page, account, group, password, submit, other, spinner, error };
        let unlock = {
            let password = s.password.clone();
            move || {
                // "" for a remembered account, whose row is hidden: the core asks the store itself
                let typed = if password.is_visible() { password.text().to_string() } else { String::new() };
                password.set_text("");
                bridge::send(Call::SessionUnlock { password: typed });
            }
        };
        let on_click = unlock.clone();
        s.submit.connect_clicked(move |_| on_click());
        s.password.connect_entry_activated(move |_| unlock());
        s.other.connect_clicked(|_| bridge::send(Call::SessionOtherAccount));
        s
    }

    /// Draws [gate] (a `Gate.Unlock`): whose account, the password where it opens with one, what opens it (the record's
    /// level), working or not, the error.
    pub fn show(&self, gate: &Gate) {
        let Gate::Unlock { record, working, error } = gate else { return };
        self.account.set_title(&record.email);
        self.account.set_subtitle(&record.server);
        let remembered = record.method == Method::DeviceKey;
        self.password.set_visible(!remembered);
        let (hint, button) = unlock_words(record.method, record.level);
        self.group.set_description(Some(&hint));
        self.submit.set_label(&button);
        self.submit.set_sensitive(!working);
        self.other.set_sensitive(!working);
        self.spinner.set_visible(*working);
        show_error(&self.error, error);
    }
}

impl Default for Unlock {
    fn default() -> Self {
        Self::new()
    }
}

/// One answer of the offer: stay signed in at a level, or "Not now".
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Choice {
    Stay(StaySignedIn),
    NotNow,
}

/// What a level is called on this platform, its title and its line; None for PRESENCE, which no store here offers
/// (`LinuxDeviceKeys` keeps LOGIN alone: the Secret Service has no check of its own) and whose words are each platform's
/// own (Windows Hello, Face ID).
pub fn level_words(level: StaySignedIn) -> Option<(String, String)> {
    match level {
        StaySignedIn::Login => Some((words::signin_remember(), words::offer_login_hint())),
        StaySignedIn::Presence => None,
    }
}

/// The offer's answers, in order: each level the core offers (`Gate.Signed.offer`, in the core's order) that has words
/// here, then "Not now" — each with its title and its line.
pub fn offer_choices(levels: &[StaySignedIn]) -> Vec<(Choice, String, String)> {
    let mut choices: Vec<_> = levels.iter().filter_map(|&l| level_words(l).map(|(title, hint)| (Choice::Stay(l), title, hint))).collect();
    choices.push((Choice::NotNow, words::offer_not_now(), words::offer_not_now_hint()));
    choices
}

/// The offer to stay signed in (ADR 0123 decision 8), shown while the core's `Gate.Signed`
/// carries one: a page of the way in, the sign-in's column, its answers activatable rows each with the line that says
/// what it means — GNOME's way to choose among described options (an `AdwAlertDialog`'s responses are bare labels, and
/// the choice continues the sign-in rather than interrupting the shell). Each row is one call; the core answers with the
/// next gate (a cancelled prompt keeps the offer, and this page with it).
pub struct Offer {
    pub page: adw::ToolbarView,
    group: adw::PreferencesGroup,
    rows: RefCell<Vec<adw::ActionRow>>,
    shown: RefCell<Vec<StaySignedIn>>,
    spinner: adw::Spinner,
    /// Says a level this device could not keep, where the person then is (the shell: the offer has ended).
    said: Rc<dyn Fn(String)>,
}

impl Offer {
    pub fn new(said: Rc<dyn Fn(String)>) -> Self {
        let group = adw::PreferencesGroup::new();
        let footer = gtk::Label::builder().label(words::offer_footer(&words::switcher_manage())).wrap(true).xalign(0.0).build();
        footer.add_css_class("dim-label");
        footer.add_css_class("caption");
        let spinner = adw::Spinner::builder().visible(false).build();
        let heading = gtk::Label::new(Some(&words::offer_title()));
        let page = page(&heading, &[group.upcast_ref(), footer.upcast_ref(), spinner.upcast_ref()]);
        Self { page, group, rows: RefCell::new(Vec::new()), shown: RefCell::new(Vec::new()), spinner, said }
    }

    /// Draws [levels], the offer's; the rows are built anew only when the levels change.
    pub fn show(&self, levels: &[StaySignedIn]) {
        if *self.shown.borrow() == levels {
            return;
        }
        *self.shown.borrow_mut() = levels.to_vec();
        for row in self.rows.borrow_mut().drain(..) {
            self.group.remove(&row);
        }
        let choices = offer_choices(levels);
        let rows: Vec<adw::ActionRow> = choices
            .iter()
            .map(|(_, title, hint)| {
                let row = adw::ActionRow::builder().title(title.as_str()).subtitle(hint.as_str()).activatable(true).build();
                row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
                row
            })
            .collect();
        for ((choice, _, _), row) in choices.into_iter().zip(&rows) {
            let (all, spinner, said) = (rows.clone(), self.spinner.clone(), self.said.clone());
            row.connect_activated(move |_| answer(choice, all.clone(), spinner.clone(), said.clone()));
            self.group.add(row);
        }
        *self.rows.borrow_mut() = rows;
    }
}

/// Sends [choice]; the rows wait meanwhile (a prompt may be up). What the core answers moves the gate on; a level it could
/// not keep is said through [said], since the offer has ended then (the password trust stays).
fn answer(choice: Choice, rows: Vec<adw::ActionRow>, spinner: adw::Spinner, said: Rc<dyn Fn(String)>) {
    let call = match choice {
        Choice::Stay(level) => Call::SessionStaySignedIn { level },
        Choice::NotNow => Call::SessionNotNow,
    };
    rows.iter().for_each(|r| r.set_sensitive(false));
    spinner.set_visible(true);
    glib::spawn_future_local(async move {
        let answered = bridge::call(&call).await.map(|(a, _)| a);
        rows.iter().for_each(|r| r.set_sensitive(true));
        spinner.set_visible(false);
        match answered {
            Ok(Answer::Done) => eprintln!("veyq: offer answered: {choice:?}"),
            // the prompt cancelled: the offer stands, as the core keeps it
            Ok(Answer::Refused { why: Why::TrustCancelled }) => eprintln!("veyq: offer {choice:?}: cancelled"),
            Ok(Answer::Refused { why: Why::TrustFailed }) => {
                eprintln!("veyq: offer {choice:?}: not kept");
                said(words::trust_failed());
            }
            other => eprintln!("veyq: offer {choice:?} answered {other:?}"),
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_remembered_unlock_says_what_opens_it() {
        assert_eq!(unlock_words(Method::DeviceKey, Some(StaySignedIn::Login)), (words::unlock_login_hint(), words::unlock_retry()));
        assert_eq!(unlock_words(Method::DeviceKey, Some(StaySignedIn::Presence)), (words::unlock_remembered_hint(), words::unlock_submit()));
        assert_eq!(unlock_words(Method::DeviceKey, None), (words::unlock_remembered_hint(), words::unlock_submit()));
        assert_eq!(unlock_words(Method::Device, Some(StaySignedIn::Login)), (words::unlock_password_hint(), words::unlock_submit()));
        assert_ne!(words::unlock_login_hint(), words::unlock_remembered_hint());
    }

    #[test]
    fn the_offer_lists_what_the_core_offers_then_not_now() {
        let choices: Vec<Choice> = offer_choices(&[StaySignedIn::Login]).into_iter().map(|c| c.0).collect();
        assert_eq!(choices, vec![Choice::Stay(StaySignedIn::Login), Choice::NotNow]);
        assert_eq!(offer_choices(&[StaySignedIn::Login])[0].1, words::signin_remember());
        assert_eq!(offer_choices(&[StaySignedIn::Login])[0].2, words::offer_login_hint());
        // PRESENCE has no words here (no Linux store offers it): never drawn as LOGIN's
        let presence: Vec<Choice> = offer_choices(&[StaySignedIn::Presence, StaySignedIn::Login]).into_iter().map(|c| c.0).collect();
        assert_eq!(presence, vec![Choice::Stay(StaySignedIn::Login), Choice::NotNow]);
        assert_eq!(offer_choices(&[]).into_iter().map(|c| c.0).collect::<Vec<_>>(), vec![Choice::NotNow]);
    }
}
