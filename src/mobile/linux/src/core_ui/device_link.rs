//! One device-linking ceremony on screen (ADR 0059), in any of its four roles: the
//! core's `DeviceLink` decides every step (`Link.*`, `Watched.Link`: `LinkShown`), this draws its state in an `AdwDialog`
//! and hands the person's acts back — the camera into the link's own scan ([ScanView::open_link]), the six digits typed
//! (`Link.EnterCode`), the slide's end (`Link.Confirm`) and the close (`Link.Close`). Android's `DeviceLinkScreen` in GTK.
//!
//! The code this side shows reaches it only as a picture (`Link.Code`, a PNG): its text, the channel key and the grant
//! never cross the bridge. Linux has no capture of the dialog to keep it from (`src/mobile/CLAUDE.md` § Secrets on
//! screen). A new device's link that is done needs nothing here: the bridge opens the account it signed in, and the
//! session's gate moves on under the dialog, which then closes.
//!
//! The slide (ADR 0059 §7, ADR 0136's Consequences) is a `GtkScale` that confirms only when a pointer gesture that began at its start
//! (≤ 15 %) ends at its end (≥ 98 %), and springs back otherwise — a click on the track's end warps there in one move and
//! does not confirm. Keyboard: the arrow keys move it in ten steps, Return at the end confirms; Home, End, the Page keys
//! and the scroll wheel are taken and move nothing. GTK offers an accessible no named action of its own (Android's custom
//! action, Apple's named one), so a screen reader reads the sentence as the scale's label and confirms by the keys.
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gdk, glib};

use crate::bridge::{
    self, Watch,
    wire::{Answer, Call, LinkHint, LinkRefusal, LinkRole, LinkShown, LinkStep, ScanShown, ScanState, Update, Watched},
};
use crate::core_ui::qr_camera::ScanView;
use crate::core_ui::words;

/// The side of the code picture, in logical pixels.
const CODE_SIDE: i32 = 280;
/// Where a pointer's gesture must begin to confirm, and where it must end.
const START: f64 = 0.15;
const END: f64 = 0.98;
/// The keys move the slide in ten steps, as the web's slide.
const STEPS: f64 = 10.0;

/// Opens the ceremony [opening] starts (one of the four `Link.*` roles' calls) in a dialog over [parent].
pub fn open(parent: &impl IsA<gtk::Widget>, opening: Call) {
    let ceremony = Ceremony::new(opening);
    let held = RefCell::new(Some(ceremony.clone()));
    ceremony.dialog.connect_closed(move |_| {
        if let Some(c) = held.borrow_mut().take() {
            c.end();
        }
    });
    ceremony.dialog.present(Some(parent));
    ceremony.start();
}

/// Whether [role] is the trusted device's (signed in, letting another in).
fn trusted(role: LinkRole) -> bool {
    matches!(role, LinkRole::ShowForNewDevice | LinkRole::ScanSignInCode)
}

/// The dialog's title for the role the opening call names.
fn title(opening: &Call) -> String {
    match opening {
        Call::LinkScanFromTrusted { .. } => words::link_join_title(),
        Call::LinkShowForSignIn { .. } => words::signin_link_show(),
        _ => words::account_settings_add_device(),
    }
}

struct Ceremony {
    dialog: adw::Dialog,
    column: gtk::Box,
    opening: Call,
    id: Cell<Option<i64>>,
    watch: RefCell<Option<Watch>>,
    /// What was drawn last: a state that differs only in what does not change the screen is not drawn anew (the scan's
    /// camera and what is typed stay).
    drawn: RefCell<Option<LinkShown>>,
    scan: RefCell<Option<Rc<ScanView>>>,
    /// The new device's six digits and the new device's name, kept across redraws so what is typed stays.
    code: adw::EntryRow,
    label: adw::EntryRow,
    /// The six digits' button while it is drawn, and the slide while it is.
    send: RefCell<Option<gtk::Button>>,
    slide: RefCell<Option<Rc<Slide>>>,
}

impl Ceremony {
    fn new(opening: Call) -> Rc<Self> {
        let column = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(16)
            .margin_top(12)
            .margin_bottom(24)
            .margin_start(12)
            .margin_end(12)
            .build();
        let clamp = adw::Clamp::builder().maximum_size(480).child(&column).build();
        let scroll = gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build();
        let content = adw::ToolbarView::new();
        content.add_top_bar(&adw::HeaderBar::new());
        content.set_content(Some(&scroll));
        let dialog = adw::Dialog::builder().title(title(&opening)).content_width(560).content_height(720).child(&content).build();
        let releases = content.clone();
        let code = adw::EntryRow::builder().title(words::link_sign_in_code()).input_purpose(gtk::InputPurpose::Digits).build();
        let label = adw::EntryRow::builder().title(words::link_label()).build();
        let c = Rc::new(Self {
            dialog,
            column,
            opening,
            id: Cell::new(None),
            watch: RefCell::new(None),
            drawn: RefCell::new(None),
            scan: RefCell::new(None),
            code,
            label,
            send: RefCell::new(None),
            slide: RefCell::new(None),
        });
        // the six digits: digits alone, six at most; the button only with six, Enter sends as it does
        let weak = Rc::downgrade(&c);
        let editing = Cell::new(false);
        c.code.connect_changed(move |row| {
            if editing.replace(true) {
                return;
            }
            let text: String = row.text().chars().filter(char::is_ascii_digit).take(6).collect();
            if row.text() != text {
                row.set_text(&text);
                row.set_position(-1);
            }
            editing.set(false);
            if let Some(c) = weak.upgrade()
                && let Some(send) = c.send.borrow().as_ref()
            {
                send.set_sensitive(text.len() == 6);
            }
        });
        let weak = Rc::downgrade(&c);
        c.code.connect_entry_activated(move |_| {
            if let Some(c) = weak.upgrade() {
                c.enter_code();
            }
        });
        // the slide's sentence names the device as it is typed; it moves only while a name is
        let weak = Rc::downgrade(&c);
        c.label.connect_changed(move |_| {
            if let Some(c) = weak.upgrade() {
                c.name_slide();
            }
        });
        c.watch_releases(&releases);
        c
    }

    /// A pointer's release anywhere in the dialog's [content] ends the slide's gesture: one past the scale's end never
    /// reaches the scale (measured under Broadway: a drag carried past the track's end was released over the dialog).
    fn watch_releases(self: &Rc<Self>, content: &adw::ToolbarView) {
        let release = gtk::EventControllerLegacy::new();
        release.set_propagation_phase(gtk::PropagationPhase::Capture);
        let weak = Rc::downgrade(self);
        release.connect_event(move |_, event| {
            let ended = event.event_type();
            if matches!(ended, gdk::EventType::ButtonRelease | gdk::EventType::TouchEnd | gdk::EventType::TouchCancel)
                && let Some(c) = weak.upgrade()
                && let Some(slide) = c.slide.borrow().clone()
            {
                slide.released(ended == gdk::EventType::TouchCancel);
            }
            glib::Propagation::Proceed
        });
        content.add_controller(release);
    }

    /// The six digits typed, sent once (`Link.EnterCode`; the core takes one).
    fn enter_code(&self) {
        let (Some(link), code) = (self.id.get(), self.code.text().to_string()) else { return };
        if code.len() != 6 {
            return;
        }
        let answer = bridge::call(&Call::LinkEnterCode { link, code });
        glib::spawn_future_local(async move {
            eprintln!("veyq-link: the code sent: {:?}", answer.await.map(|(a, _)| a));
        });
    }

    fn name_slide(&self) {
        if let Some(slide) = self.slide.borrow().as_ref() {
            let name = self.label.text().trim().to_string();
            let enabled = !name.is_empty();
            slide.name(&if enabled { name } else { words::link_slide_no_name() }, enabled);
        }
    }

    /// Opens a link of this ceremony's role and follows its state; a link already open (Start over) is closed first.
    fn start(self: &Rc<Self>) {
        self.close_link();
        self.code.set_text("");
        self.label.set_text("");
        self.drawn.replace(None);
        self.clear();
        self.column.append(&waiting(None));
        let weak = Rc::downgrade(self);
        let answer = bridge::call(&self.opening);
        glib::spawn_future_local(async move {
            let link = match answer.await.map(|(a, _)| a) {
                Ok(Answer::LinkOpened { link }) => link,
                other => {
                    eprintln!("veyq-link: opening answered {other:?}");
                    if let Some(c) = weak.upgrade() {
                        c.dialog.force_close();
                    }
                    return;
                }
            };
            let Some(c) = weak.upgrade() else {
                bridge::send(Call::LinkClose { link });
                return;
            };
            c.id.set(Some(link));
            let Some((watch, updates)) = bridge::watch(&Watched::Link { link }) else { return };
            c.watch.replace(Some(watch));
            drop(c);
            while let Ok(update) = updates.recv().await {
                let Some(c) = weak.upgrade() else { return };
                if c.id.get() != Some(link) {
                    return;
                }
                match update {
                    Ok(Update::Link { shown: Some(shown), .. }) => c.show(link, shown),
                    // closed in the core (its tenant closed, or the link ended there): the dialog goes with it
                    Ok(Update::Link { shown: None, .. }) => {
                        eprintln!("veyq-link: closed in the core");
                        c.id.set(None);
                        c.dialog.force_close();
                        return;
                    }
                    Ok(_) => {}
                    Err(e) => eprintln!("veyq-link: an update did not read: {:?}", e.0),
                }
            }
        });
    }

    /// Ends the link this dialog holds (its polls stop, its scan closes).
    fn close_link(&self) {
        self.watch.replace(None);
        if let Some(scan) = self.scan.take() {
            scan.close();
        }
        if let Some(link) = self.id.take() {
            bridge::send(Call::LinkClose { link });
        }
    }

    /// The dialog closed, however: the link ends with it.
    fn end(&self) {
        self.close_link();
        self.send.replace(None);
        self.slide.replace(None);
        self.clear();
    }

    fn clear(&self) {
        while let Some(child) = self.column.first_child() {
            self.column.remove(&child);
        }
    }

    fn show(self: &Rc<Self>, link: i64, shown: LinkShown) {
        // the step, the refusal and the hint, never a code, a number or a name
        eprintln!("veyq-link: {:?} {:?} refusal {:?} hint {:?}", shown.role, shown.step, shown.refusal, shown.hint);
        if self.drawn.borrow().as_ref() == Some(&shown) {
            return;
        }
        let same_step = self.drawn.borrow().as_ref().is_some_and(|d| d.step == shown.step);
        self.drawn.replace(Some(shown.clone()));
        // a new device's link is done: the account it signed in is open, the gate under this dialog moved on
        if shown.step == LinkStep::Done && !trusted(shown.role) {
            self.id.set(None);
            self.watch.replace(None);
            self.dialog.force_close();
            return;
        }
        if shown.step != LinkStep::Scanning
            && let Some(scan) = self.scan.take()
        {
            scan.close();
        }
        // a scan's hint changed (the other direction's code seen): its camera stays, the words under it are drawn anew
        if same_step && shown.step == LinkStep::Scanning && self.scan.borrow().is_some() {
            if let Some(last) = self.column.last_child()
                && last.widget_name() == "link-other-direction"
            {
                self.column.remove(&last);
            }
            self.other_direction(&shown);
            return;
        }
        self.clear();
        self.send.replace(None);
        self.slide.replace(None);
        let c = &self.column;
        match shown.step {
            LinkStep::Opening => {
                let showing = matches!(shown.role, LinkRole::ShowForSignIn | LinkRole::ShowForNewDevice);
                c.append(&waiting(showing.then(words::link_sign_in_opening)));
            }
            LinkStep::Showing if shown.role == LinkRole::ShowForNewDevice => {
                c.append(&copy(&words::link_scan_hint()));
                c.append(&self.code_picture(link, words::link_code_alt()));
                c.append(&waiting(Some(words::link_waiting())));
                same_server(c, &shown);
            }
            LinkStep::Showing => {
                c.append(&copy(&words::link_sign_in_intro()));
                c.append(&self.code_picture(link, words::link_code_alt_sign_in()));
                c.append(&copy(&words::link_sign_in_scan_hint()));
                c.append(&self.six_digits());
            }
            LinkStep::Scanning => self.scanning(link, &shown),
            LinkStep::Code => {
                c.append(&copy(&words::link_code_prompt()));
                c.append(&digits(shown.code.as_deref().unwrap_or_default()));
                c.append(&consequence());
                c.append(&waiting(Some(words::link_code_waiting())));
                same_server(c, &shown);
            }
            LinkStep::Waiting if shown.role == LinkRole::ScanFromTrusted => {
                c.append(&copy(&words::link_join_intro()));
                c.append(&digits(shown.number.as_deref().unwrap_or_default()));
                c.append(&copy(&words::link_join_compare()));
                c.append(&waiting(Some(words::link_join_waiting())));
                same_server(c, &shown);
            }
            LinkStep::Waiting => {
                c.append(&waiting(Some(words::link_sign_in_waiting())));
                same_server(c, &shown);
            }
            LinkStep::Confirming => self.confirming(link, &shown),
            LinkStep::Sealing => c.append(&waiting(Some(words::link_sealing()))),
            LinkStep::Finishing => c.append(&waiting(Some(words::link_finishing()))),
            LinkStep::Done => {
                let sealed = gtk::Label::builder().label(words::link_sealed()).wrap(true).justify(gtk::Justification::Center).build();
                sealed.add_css_class("title-3");
                c.append(&sealed);
                c.append(&copy(&words::link_sealed_hint()));
                c.append(&self.close_button(true));
            }
            LinkStep::Ended => self.ended(&shown),
        }
    }

    /// This side's code as the core drew it, dark on light whatever the theme (a scanner reads that), [alt] for a
    /// screen reader. The picture is asked at the window's scale, so a module is whole device pixels.
    fn code_picture(&self, link: i64, alt: String) -> gtk::Widget {
        let picture = gtk::Picture::builder().content_fit(gtk::ContentFit::Contain).can_shrink(true).halign(gtk::Align::Center).build();
        picture.set_size_request(CODE_SIDE, CODE_SIDE);
        picture.set_alternative_text(Some(&alt));
        picture.set_widget_name("link-qr");
        let px = CODE_SIDE * self.dialog.scale_factor().max(1);
        let answer = bridge::call(&Call::LinkCode { link, px });
        let target = picture.downgrade();
        glib::spawn_future_local(async move {
            match answer.await {
                Ok((Answer::Bytes { .. }, Some(png))) => {
                    let Some(picture) = target.upgrade() else { return };
                    match gdk::Texture::from_bytes(&glib::Bytes::from_owned(png)) {
                        Ok(texture) => picture.set_paintable(Some(&texture)),
                        Err(e) => eprintln!("veyq-link: the code's picture did not decode ({e})"),
                    }
                }
                other => eprintln!("veyq-link: Link.Code answered {:?}", other.map(|(a, _)| a)),
            }
        });
        picture.upcast()
    }

    /// The new device's six digits (§6): editable until sent, sent once; the button only with six digits.
    fn six_digits(self: &Rc<Self>) -> gtk::Widget {
        let list = boxed_list(&self.code);
        let hint = gtk::Label::builder().label(words::link_sign_in_code_hint()).wrap(true).xalign(0.0).build();
        hint.add_css_class("dim-label");
        hint.add_css_class("caption");
        let send = gtk::Button::builder().label(words::link_sign_in_send()).halign(gtk::Align::Center).build();
        send.add_css_class("suggested-action");
        send.add_css_class("pill");
        send.set_widget_name("link-send");
        send.set_sensitive(self.code.text().len() == 6);
        let weak = Rc::downgrade(self);
        send.connect_clicked(move |_| {
            if let Some(c) = weak.upgrade() {
                c.enter_code();
            }
        });
        self.send.replace(Some(send.clone()));
        let column = gtk::Box::new(gtk::Orientation::Vertical, 8);
        column.append(&list);
        column.append(&hint);
        column.append(&send);
        column.upcast()
    }

    /// The camera into the link's own scan; the other direction's code said with the other entry's name, the scan going on.
    fn scanning(self: &Rc<Self>, link: i64, shown: &LinkShown) {
        let new_device = shown.role == LinkRole::ScanFromTrusted;
        let scan = match self.scan.borrow().as_ref() {
            Some(scan) => scan.clone(),
            None => {
                let denied = if new_device { words::link_join_scan_denied() } else { words::link_scan_denied() };
                let view = ScanView::open_link(link, move |s| scan_said(s, &denied), |s| eprintln!("veyq-link: scan {:?}", s.state));
                view.widget.set_hexpand(true);
                view.widget.set_size_request(-1, 360);
                view
            }
        };
        if let Some(parent) = scan.widget.parent().and_downcast::<gtk::Box>() {
            parent.remove(&scan.widget);
        }
        self.column.append(&scan.widget);
        self.scan.replace(Some(scan));
        self.column.append(&copy(&if new_device { words::link_join_scan_hint() } else { words::link_scan_sign_in_hint() }));
        self.other_direction(shown);
    }

    fn other_direction(&self, shown: &LinkShown) {
        if shown.hint == Some(LinkHint::OtherDirection) {
            let said = if shown.role == LinkRole::ScanFromTrusted { words::link_other_direction_new() } else { words::link_other_direction_trusted() };
            let line = copy(&said);
            line.add_css_class("error");
            line.set_widget_name("link-other-direction");
            self.column.append(&line);
        }
    }

    /// The trusted device's confirmation (§5, §7): the number compared or the proof held, the name, then the slide.
    fn confirming(self: &Rc<Self>, link: i64, shown: &LinkShown) {
        let compared = shown.role == LinkRole::ShowForNewDevice;
        let c = &self.column;
        c.append(&copy(&if compared { words::link_compare_prompt() } else { words::link_proven() }));
        if compared {
            c.append(&digits(shown.number.as_deref().unwrap_or_default()));
        }
        c.append(&boxed_list(&self.label));
        let hint = gtk::Label::builder().label(words::link_label_hint()).wrap(true).xalign(0.0).build();
        hint.add_css_class("dim-label");
        hint.add_css_class("caption");
        c.append(&hint);
        let weak = Rc::downgrade(self);
        let slide = Slide::new(move |done| {
            let Some(c) = weak.upgrade() else { return done(false) };
            let label = c.label.text().trim().to_string();
            if label.is_empty() {
                return done(false);
            }
            let answer = bridge::call(&Call::LinkConfirm { link, label });
            glib::spawn_future_local(async move {
                let answer = answer.await.map(|(a, _)| a);
                eprintln!("veyq-link: the slide's end: {}", if matches!(answer, Ok(Answer::Done)) { "moved on" } else { "refused" });
                done(matches!(answer, Ok(Answer::Done)));
            });
        });
        self.slide.replace(Some(slide.clone()));
        self.name_slide();
        c.append(&slide.widget);
        c.append(&consequence());
        if compared {
            let mismatch = self.close_button(false);
            mismatch.set_label(&words::link_mismatch());
            mismatch.remove_css_class("flat");
            mismatch.set_widget_name("link-mismatch");
            c.append(&mismatch);
        } else {
            let cancel = self.close_button(false);
            cancel.set_label(&words::signin_cancel());
            c.append(&cancel);
        }
    }

    /// How the ceremony ended, in this role's words, and the way to start over.
    fn ended(self: &Rc<Self>, shown: &LinkShown) {
        let role = shown.role;
        let new_device = !trusted(role);
        let (said, hint) = match shown.refusal {
            Some(LinkRefusal::Off) => (Some(words::link_off()), None),
            Some(LinkRefusal::Gone) => (
                Some(words::link_gone()),
                match role {
                    LinkRole::ScanFromTrusted => Some(words::link_join_gone_hint()),
                    LinkRole::ShowForSignIn => Some(words::link_sign_in_gone_hint()),
                    _ => None,
                },
            ),
            Some(LinkRefusal::Refused) => match role {
                LinkRole::ShowForNewDevice => (Some(words::link_answer_refused()), Some(words::link_answer_refused_hint())),
                LinkRole::ScanSignInCode => (Some(words::link_scan_refused()), Some(words::link_scan_refused_hint())),
                _ => (Some(words::link_join_refused()), None),
            },
            Some(LinkRefusal::Failed) => (Some(if new_device { words::link_join_failed() } else { words::link_grant_failed() }), None),
            None => (None, None),
        };
        let c = &self.column;
        if let Some(said) = said {
            let line = gtk::Label::builder().label(said).wrap(true).justify(gtk::Justification::Center).build();
            line.add_css_class("title-4");
            line.add_css_class("error");
            c.append(&line);
        }
        if let Some(hint) = hint {
            c.append(&copy(&hint));
        }
        same_server(c, shown);
        if shown.refusal != Some(LinkRefusal::Off) {
            let again = gtk::Button::builder().label(words::link_again()).halign(gtk::Align::Center).build();
            again.add_css_class("suggested-action");
            again.add_css_class("pill");
            let weak = Rc::downgrade(self);
            again.connect_clicked(move |_| {
                if let Some(c) = weak.upgrade() {
                    c.start();
                }
            });
            c.append(&again);
        }
        c.append(&self.close_button(false));
    }

    /// "Close" (the suggested one where nothing else is left to do), which ends the link with the dialog.
    fn close_button(&self, suggested: bool) -> gtk::Button {
        let button = gtk::Button::builder().label(words::link_close()).halign(gtk::Align::Center).build();
        button.add_css_class(if suggested { "suggested-action" } else { "flat" });
        button.add_css_class("pill");
        let dialog = self.dialog.downgrade();
        button.connect_clicked(move |_| {
            if let Some(d) = dialog.upgrade() {
                d.close();
            }
        });
        button
    }
}

/// What a confirmation calls back: whether the link moved on.
type Answered = Box<dyn FnOnce(bool)>;

/// The slide (ADR 0059 §7): a scale from 0 to 1 that confirms only by a gesture carried from its start to its end, or by the
/// arrow keys to the end and Return. The sentence it confirms is its label, for the eye and for a screen reader.
struct Slide {
    widget: gtk::Box,
    sentence: gtk::Label,
    scale: gtk::Scale,
    adjustment: gtk::Adjustment,
    /// The spring back while it runs; the next gesture or key stops it.
    back: RefCell<Option<adw::TimedAnimation>>,
    /// A pointer is down on the scale, and the value its gesture began at (the first value it moved to).
    pressed: Cell<bool>,
    from: Cell<Option<f64>>,
    /// A confirmation is under way: nothing moves the slide until its answer.
    confirming: Cell<bool>,
    /// Asks the link to confirm and calls back whether it moved on.
    ask: Box<dyn Fn(Answered)>,
}

impl Slide {
    /// [confirm] is asked at the slide's end and calls back whether the link moved on; one that did not springs back.
    fn new(confirm: impl Fn(Answered) + 'static) -> Rc<Self> {
        let adjustment = gtk::Adjustment::new(0.0, 0.0, 1.0, 1.0 / STEPS, 1.0 / STEPS, 0.0);
        let scale = gtk::Scale::builder().orientation(gtk::Orientation::Horizontal).adjustment(&adjustment).draw_value(false).hexpand(true).build();
        scale.set_widget_name("link-slide");
        let sentence = gtk::Label::builder().wrap(true).justify(gtk::Justification::Center).build();
        sentence.add_css_class("heading");
        let keys = gtk::Label::builder().label(words::link_slide_keys()).build();
        keys.add_css_class("dim-label");
        keys.add_css_class("caption");
        let widget = gtk::Box::new(gtk::Orientation::Vertical, 6);
        widget.append(&sentence);
        widget.append(&scale);
        widget.append(&keys);
        let slide = Rc::new(Self {
            widget,
            sentence,
            scale: scale.clone(),
            adjustment: adjustment.clone(),
            back: RefCell::new(None),
            pressed: Cell::new(false),
            from: Cell::new(None),
            confirming: Cell::new(false),
            ask: Box::new(confirm),
        });

        let s = Rc::downgrade(&slide);
        adjustment.connect_value_changed(move |a| {
            let Some(s) = s.upgrade() else { return };
            if s.pressed.get() && s.from.get().is_none() {
                s.from.set(Some(a.value()));
            }
        });

        // the pointer: seen before the scale's own gestures (capture), never taken from them. A release past the scale
        // never reaches it (measured under Broadway, a drag carried past the track's end): the dialog's content sees that
        // one ([Ceremony::watch_releases]); [Slide::released] takes the first of the two
        let pointer = gtk::EventControllerLegacy::new();
        pointer.set_propagation_phase(gtk::PropagationPhase::Capture);
        let s = Rc::downgrade(&slide);
        pointer.connect_event(move |_, event| {
            let Some(s) = s.upgrade() else { return glib::Propagation::Proceed };
            let kind = event.event_type();
            match kind {
                gdk::EventType::ButtonPress | gdk::EventType::TouchBegin => {
                    if s.confirming.get() {
                        return glib::Propagation::Stop;
                    }
                    s.stop_back();
                    s.pressed.set(true);
                    s.from.set(None);
                }
                gdk::EventType::ButtonRelease | gdk::EventType::TouchEnd | gdk::EventType::TouchCancel => {
                    s.released(kind == gdk::EventType::TouchCancel);
                }
                _ => {}
            }
            glib::Propagation::Proceed
        });
        scale.add_controller(pointer);

        let keys = gtk::EventControllerKey::new();
        keys.set_propagation_phase(gtk::PropagationPhase::Capture);
        let s = Rc::downgrade(&slide);
        keys.connect_key_pressed(move |_, key, _, _| {
            let Some(s) = s.upgrade() else { return glib::Propagation::Proceed };
            use gdk::Key;
            let moved = |by: f64| {
                let step = ((s.adjustment.value() * STEPS).round() + by).clamp(0.0, STEPS);
                s.adjustment.set_value(step / STEPS);
            };
            let taken = [Key::Right, Key::Left, Key::Up, Key::Down, Key::KP_Right, Key::KP_Left, Key::KP_Up, Key::KP_Down,
                Key::Home, Key::End, Key::KP_Home, Key::KP_End, Key::Page_Up, Key::Page_Down, Key::KP_Page_Up, Key::KP_Page_Down];
            if s.confirming.get() {
                return if taken.contains(&key) { glib::Propagation::Stop } else { glib::Propagation::Proceed };
            }
            if taken.contains(&key) {
                s.stop_back();
            }
            match key {
                Key::Right | Key::Up | Key::KP_Right | Key::KP_Up => moved(1.0),
                Key::Left | Key::Down | Key::KP_Left | Key::KP_Down => moved(-1.0),
                Key::Return | Key::KP_Enter | Key::ISO_Enter if s.adjustment.value() >= END => s.confirm(),
                // a key that jumps to the end would be the click the slide refuses: taken, nothing moves
                k if taken.contains(&k) => {}
                _ => return glib::Propagation::Proceed,
            }
            glib::Propagation::Stop
        });
        scale.add_controller(keys);

        // the wheel would step it to the end with no gesture: taken
        let wheel = gtk::EventControllerScroll::new(gtk::EventControllerScrollFlags::BOTH_AXES);
        wheel.set_propagation_phase(gtk::PropagationPhase::Capture);
        wheel.connect_scroll(|_, _, _| glib::Propagation::Stop);
        scale.add_controller(wheel);
        slide
    }

    /// The sentence with the device's [name]; the slide moves only while a name is typed ([enabled]).
    fn name(&self, name: &str, enabled: bool) {
        let sentence = words::link_slide(name);
        self.sentence.set_label(&sentence);
        self.scale.update_property(&[gtk::accessible::Property::Label(&sentence), gtk::accessible::Property::Description(&words::link_slide_keys())]);
        self.scale.set_sensitive(enabled);
        if !enabled {
            self.spring_back();
        }
    }

    /// A pointer was released (or its touch cancelled, [cancelled]) anywhere in the dialog: a gesture pressed on the
    /// scale that began at its start and ended at its end confirms; any other springs back.
    fn released(self: &Rc<Self>, cancelled: bool) {
        if !self.pressed.replace(false) {
            return;
        }
        let carried = self.from.take().is_some_and(|from| from <= START);
        if carried && !cancelled && self.adjustment.value() >= END {
            self.confirm();
        } else {
            self.spring_back();
        }
    }

    fn confirm(self: &Rc<Self>) {
        if self.confirming.replace(true) {
            return;
        }
        let s = Rc::downgrade(self);
        (self.ask)(Box::new(move |moved| {
            let Some(s) = s.upgrade() else { return };
            s.confirming.set(false);
            if !moved {
                s.spring_back();
            }
        }));
    }

    fn stop_back(&self) {
        if let Some(a) = self.back.take() {
            a.pause();
        }
    }

    fn spring_back(&self) {
        self.stop_back();
        if self.adjustment.value() <= 0.0 {
            return;
        }
        let target = adw::PropertyAnimationTarget::new(&self.adjustment, "value");
        let animation = adw::TimedAnimation::builder()
            .widget(&self.scale)
            .value_from(self.adjustment.value())
            .value_to(0.0)
            .duration(250)
            .target(&target)
            .easing(adw::Easing::EaseOutCubic)
            .build();
        animation.play();
        self.back.replace(Some(animation));
    }
}

/// An `AdwEntryRow` in a boxed list of its own (taken out of the one it sat in, for a redraw).
fn boxed_list(row: &adw::EntryRow) -> gtk::ListBox {
    if let Some(parent) = row.parent().and_downcast::<gtk::ListBox>() {
        parent.remove(row);
    }
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("boxed-list");
    list.append(row);
    list
}

/// A line of the ceremony's words.
fn copy(text: &str) -> gtk::Label {
    gtk::Label::builder().label(text).wrap(true).justify(gtk::Justification::Center).build()
}

/// Work under way, and what it waits for.
fn waiting(text: Option<String>) -> gtk::Widget {
    let row = gtk::Box::builder().orientation(gtk::Orientation::Horizontal).spacing(12).halign(gtk::Align::Center).build();
    row.append(&adw::Spinner::new());
    if let Some(text) = text {
        let label = gtk::Label::builder().label(text).wrap(true).build();
        label.add_css_class("dim-label");
        row.append(&label);
    }
    row.upcast()
}

/// The hint the core raises once the other device has been silent for the first window (both devices must use the same server, ADR 0059 §4).
fn same_server(column: &gtk::Box, shown: &LinkShown) {
    if shown.hint == Some(LinkHint::SameServer) {
        let line = copy(&words::link_same_server());
        line.add_css_class("dim-label");
        column.append(&line);
    }
}

/// The consequence the slide stands against (ADR 0059 §7): signing the device out later does not take the key back.
fn consequence() -> gtk::Label {
    let line = copy(&words::link_consequence());
    line.add_css_class("error");
    line
}

/// Five or six digits to compare or to type, large, spaced and monospaced.
fn digits(digits: &str) -> gtk::Label {
    let attrs = gtk::pango::AttrList::new();
    attrs.insert(gtk::pango::AttrInt::new_letter_spacing(6 * gtk::pango::SCALE));
    attrs.insert(gtk::pango::AttrFloat::new_scale(2.0));
    let label = gtk::Label::builder().label(digits).attributes(&attrs).selectable(true).build();
    label.add_css_class("title-1");
    label.add_css_class("monospace");
    label.set_widget_name("link-digits");
    label
}

/// What the link's scan shows but its camera: no camera or a refused one says to let the other device scan instead.
fn scan_said(shown: &ScanShown, denied: &str) -> Option<gtk::Widget> {
    match shown.state {
        ScanState::NoCamera | ScanState::Denied => Some(
            adw::StatusPage::builder()
                .icon_name("camera-disabled-symbolic")
                .description(glib::markup_escape_text(denied).as_str())
                .vexpand(true)
                .build()
                .upcast(),
        ),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_the_two_settings_roles_are_the_trusted_devices() {
        assert!(trusted(LinkRole::ShowForNewDevice));
        assert!(trusted(LinkRole::ScanSignInCode));
        assert!(!trusted(LinkRole::ScanFromTrusted));
        assert!(!trusted(LinkRole::ShowForSignIn));
    }
}
