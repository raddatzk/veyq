//! A node's report to its server's moderators (ADR 0127 decision 2; core's `ReportSheet`, the web's `ReportDialog`,
//! ADR 0093), GNOME's way: an `AdwDialog` with Cancel and Send in its header bar over a preferences page — what is reported
//! (the node whole or only its name) and the category as radio rows, the reason in a text view, the good-faith
//! confirmation as a check row, the optional contact in an `AdwEntryRow` and the privacy notice's passage on it, opened in
//! the browser (`gtk::UriLauncher`, the address the core's). Sent, the page shows the receipt.
//!
//! Every rule is the core's (`Report.*`, watched by id): what may be sent, the note under a category, the room of each field
//! (a longer text is cut here to what the core keeps), one send at a time. The typed reason and contact go in and are not
//! echoed back.
use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Answer, wire::Call, wire::NodeAt, wire::ReportShown, wire::Update, wire::Watched};
use crate::{said, words};

/// [text] cut to [most] UTF-16 units (the web's `maxlength`, the core's count), never inside a character.
pub fn cut(text: &str, most: usize) -> &str {
    let mut units = 0;
    for (at, c) in text.char_indices() {
        units += c.len_utf16();
        if units > most {
            return &text[..at];
        }
    }
    text
}

struct Inner {
    id: Cell<Option<i64>>,
    watch: RefCell<Option<Watch>>,
    dialog: adw::Dialog,
    stack: gtk::Stack,
    send: gtk::Button,
    cancel: gtk::Button,
    whole: gtk::CheckButton,
    name_only: gtk::CheckButton,
    scope_hint: gtk::Label,
    categories: gtk::ListBox,
    category_checks: RefCell<Vec<(String, gtk::CheckButton)>>,
    note: gtk::Label,
    reason: gtk::TextView,
    good_faith: gtk::CheckButton,
    contact: adw::EntryRow,
    problem: gtk::Label,
    form: gtk::Widget,
    receipt: gtk::Label,
    shown: RefCell<Option<ReportShown>>,
    /// A draw sets the controls: their signals are not the person's.
    drawing: Cell<bool>,
}

/// Opens the report sheet of [node] over [parent].
pub fn open(parent: &impl IsA<gtk::Widget>, node: &NodeAt) {
    let header = adw::HeaderBar::builder().title_widget(&adw::WindowTitle::new(&words::report_title(), &node.name)).show_end_title_buttons(false).show_start_title_buttons(false).build();
    let cancel = gtk::Button::with_label(&words::entry_cancel());
    let send = gtk::Button::with_label(&words::report_send());
    send.add_css_class("suggested-action");
    header.pack_start(&cancel);
    header.pack_end(&send);

    let intro = gtk::Label::builder().label(words::report_intro()).wrap(true).xalign(0.0).build();
    intro.add_css_class("dim-label");

    // what is reported: radio rows (GNOME: a choice among a few is a group of check buttons)
    let whole = gtk::CheckButton::new();
    let name_only = gtk::CheckButton::new();
    name_only.set_group(Some(&whole));
    let scope = adw::PreferencesGroup::builder().title(words::report_scope()).build();
    for (check, title) in [(&whole, words::report_scope_whole()), (&name_only, words::report_scope_name())] {
        let row = adw::ActionRow::builder().title(title).activatable_widget(check).build();
        row.add_prefix(check);
        scope.add(&row);
    }
    let scope_hint = gtk::Label::builder().wrap(true).xalign(0.0).build();
    scope_hint.add_css_class("dim-label");
    scope_hint.add_css_class("caption");

    let categories = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    categories.add_css_class("boxed-list");
    let category_group = adw::PreferencesGroup::builder().title(words::report_category()).build();
    category_group.add(&categories);
    let note = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
    note.add_css_class("accent");

    let reason = gtk::TextView::builder().wrap_mode(gtk::WrapMode::WordChar).accepts_tab(false).top_margin(10).bottom_margin(10).left_margin(12).right_margin(12).build();
    reason.update_property(&[gtk::accessible::Property::Label(&words::report_reason())]);
    let reason_frame = gtk::Frame::builder().child(&gtk::ScrolledWindow::builder().child(&reason).min_content_height(110).hscrollbar_policy(gtk::PolicyType::Never).build()).build();
    reason_frame.add_css_class("view");
    let reason_group = adw::PreferencesGroup::builder().title(words::report_reason()).description(words::report_reason_hint()).build();
    reason_group.add(&reason_frame);

    let good_faith = gtk::CheckButton::new();
    let faith_row = adw::ActionRow::builder().title(words::report_good_faith()).activatable_widget(&good_faith).build();
    faith_row.add_prefix(&good_faith);
    let faith_list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    faith_list.add_css_class("boxed-list");
    faith_list.append(&faith_row);

    let contact = adw::EntryRow::builder().title(words::report_contact()).input_purpose(gtk::InputPurpose::Email).build();
    // GDPR Art. 13 applies where a contact is taken: the notice's passage on it, in the browser
    let privacy = adw::ButtonRow::builder().title(words::report_privacy()).end_icon_name("adw-external-link-symbolic").build();
    let contact_group = adw::PreferencesGroup::builder().description(words::report_contact_hint()).build();
    contact_group.add(&contact);
    contact_group.add(&privacy);

    let problem = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
    problem.add_css_class("error");

    let body = gtk::Box::new(gtk::Orientation::Vertical, 18);
    for w in [
        intro.upcast_ref::<gtk::Widget>(), scope.upcast_ref(), scope_hint.upcast_ref(), category_group.upcast_ref(), note.upcast_ref(),
        reason_group.upcast_ref(), faith_list.upcast_ref(), contact_group.upcast_ref(), problem.upcast_ref(),
    ] {
        body.append(w);
    }
    let form = page(&body);

    // the receipt
    let sent = gtk::Label::builder().label(words::report_sent()).wrap(true).xalign(0.0).build();
    sent.add_css_class("title-4");
    let receipt = gtk::Label::builder().wrap(true).xalign(0.0).selectable(true).build();
    let close = gtk::Button::builder().label(words::report_close()).halign(gtk::Align::End).build();
    close.add_css_class("pill");
    close.add_css_class("suggested-action");
    let done = gtk::Box::new(gtk::Orientation::Vertical, 12);
    for w in [sent.upcast_ref::<gtk::Widget>(), receipt.upcast_ref(), close.upcast_ref()] {
        done.append(w);
    }
    let done_page = page(&done);

    let stack = gtk::Stack::builder().transition_type(gtk::StackTransitionType::Crossfade).build();
    stack.add_named(&form, Some("form"));
    stack.add_named(&done_page, Some("sent"));
    let view = adw::ToolbarView::new();
    view.add_top_bar(&header);
    view.set_content(Some(&stack));
    let dialog = adw::Dialog::builder().content_width(520).content_height(680).child(&view).build();

    let inner = Rc::new(Inner {
        id: Cell::new(None),
        watch: RefCell::new(None),
        dialog: dialog.clone(),
        stack,
        send: send.clone(),
        cancel: cancel.clone(),
        whole,
        name_only,
        scope_hint,
        categories,
        category_checks: RefCell::new(Vec::new()),
        note,
        reason,
        good_faith,
        contact,
        problem,
        form: form.upcast(),
        receipt,
        shown: RefCell::new(None),
        drawing: Cell::new(false),
    });
    inner.wire(&privacy, &close);
    let held = inner.clone();
    dialog.connect_closed(move |_| {
        held.watch.borrow_mut().take();
        if let Some(report) = held.id.take() {
            bridge::send(Call::ReportClose { report });
        }
    });
    dialog.present(Some(parent));
    let weak = Rc::downgrade(&inner);
    let call = Call::ReportOpen { node: node.clone() };
    glib::spawn_future_local(async move {
        let Ok((Answer::ReportOpened { report }, _)) = bridge::call(&call).await else {
            eprintln!("veyq: the report sheet did not open in the core");
            return;
        };
        let Some(inner) = weak.upgrade() else {
            bridge::send(Call::ReportClose { report });
            return;
        };
        inner.id.set(Some(report));
        let Some((watch, updates)) = bridge::watch(&Watched::Report { report }) else { return };
        *inner.watch.borrow_mut() = Some(watch);
        drop(inner);
        follow(weak, updates).await;
    });
}

/// A page of the dialog: its content clamped and scrolled, with GNOME's margins.
fn page(content: &impl IsA<gtk::Widget>) -> gtk::ScrolledWindow {
    let clamp = adw::Clamp::builder().maximum_size(560).margin_top(12).margin_bottom(18).margin_start(12).margin_end(12).child(content).build();
    gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build()
}

async fn follow(weak: Weak<Inner>, updates: async_channel::Receiver<Result<Update, bridge::Unreadable>>) {
    while let Ok(update) = updates.recv().await {
        let Some(inner) = weak.upgrade() else { return };
        match update {
            Ok(Update::Report { shown, .. }) => {
                if let Some(s) = &shown {
                    eprintln!(
                        "veyq: report sheet: whole {}, category {:?}, good faith {}, can send {}, sending {}, problem {:?}, sent {}, reference {:?}",
                        s.whole, s.category, s.good_faith, s.can_send, s.sending, s.problem.as_ref().map(|p| &p.msg), s.sent, s.reference
                    );
                }
                *inner.shown.borrow_mut() = shown;
                inner.draw();
            }
            Ok(_) => {}
            Err(e) => eprintln!("veyq: a report sheet update did not read: {:?}", e.0),
        }
    }
}

impl Inner {
    /// Sends [call] on the sheet open in the core (each change of the form).
    fn send_call(&self, call: impl FnOnce(i64) -> Call) {
        if let Some(report) = self.id.get() {
            bridge::send(call(report));
        }
    }

    fn wire(self: &Rc<Self>, privacy: &adw::ButtonRow, close: &gtk::Button) {
        let w = Rc::downgrade(self);
        self.cancel.connect_clicked(move |_| { if let Some(i) = w.upgrade() { i.dialog.close(); } });
        let w = Rc::downgrade(self);
        close.connect_clicked(move |_| { if let Some(i) = w.upgrade() { i.dialog.close(); } });
        let w = Rc::downgrade(self);
        self.send.connect_clicked(move |_| {
            let Some(i) = w.upgrade() else { return };
            let Some(report) = i.id.get() else { return };
            glib::spawn_future_local(async move {
                // the problem shows under the form from the state
                let answered = bridge::call(&Call::ReportSend { report }).await.map(|(a, _)| a);
                eprintln!("veyq: Report.Send answered {answered:?}");
            });
        });
        let w = Rc::downgrade(self);
        self.whole.connect_toggled(move |c| {
            let Some(i) = w.upgrade() else { return };
            if !i.drawing.get() && c.is_active() {
                i.send_call(|report| Call::ReportWhole { report, whole: true });
            }
        });
        let w = Rc::downgrade(self);
        self.name_only.connect_toggled(move |c| {
            let Some(i) = w.upgrade() else { return };
            if !i.drawing.get() && c.is_active() {
                i.send_call(|report| Call::ReportWhole { report, whole: false });
            }
        });
        let w = Rc::downgrade(self);
        self.reason.buffer().connect_changed(move |b| {
            let Some(i) = w.upgrade() else { return };
            if i.drawing.get() {
                return;
            }
            let text = b.text(&b.start_iter(), &b.end_iter(), false).to_string();
            let most = i.shown.borrow().as_ref().map_or(usize::MAX, |s| s.reason_max as usize);
            let kept = cut(&text, most).to_owned();
            if kept.len() != text.len() {
                // the core keeps no more: the field holds no more either
                i.drawing.set(true);
                b.set_text(&kept);
                i.drawing.set(false);
            }
            i.send_call(|report| Call::ReportReason { report, reason: kept });
        });
        let w = Rc::downgrade(self);
        self.good_faith.connect_toggled(move |c| {
            let Some(i) = w.upgrade() else { return };
            if !i.drawing.get() {
                let on = c.is_active();
                i.send_call(|report| Call::ReportGoodFaith { report, on });
            }
        });
        let w = Rc::downgrade(self);
        self.contact.connect_changed(move |e| {
            let Some(i) = w.upgrade() else { return };
            if i.drawing.get() {
                return;
            }
            let text = e.text().to_string();
            let most = i.shown.borrow().as_ref().map_or(usize::MAX, |s| s.contact_max as usize);
            let kept = cut(&text, most).to_owned();
            if kept.len() != text.len() {
                i.drawing.set(true);
                e.set_text(&kept);
                i.drawing.set(false);
            }
            i.send_call(|report| Call::ReportContact { report, contact: kept });
        });
        let w = Rc::downgrade(self);
        privacy.connect_activated(move |row| {
            let Some(i) = w.upgrade() else { return };
            let Some(url) = i.shown.borrow().as_ref().map(|s| s.privacy_url.clone()) else { return };
            let window = row.root().and_downcast::<gtk::Window>();
            gtk::UriLauncher::new(&url).launch(window.as_ref(), gtk::gio::Cancellable::NONE, move |r| match r {
                Ok(()) => eprintln!("veyq: privacy: opened {url}"),
                Err(e) => eprintln!("veyq: privacy: {url} did not open: {e}"),
            });
        });
    }

    fn draw(self: &Rc<Self>) {
        let shown = self.shown.borrow();
        let Some(s) = shown.as_ref() else {
            self.dialog.close();
            return;
        };
        self.drawing.set(true);
        if self.category_checks.borrow().is_empty() {
            // the categories in the core's order, one radio row each
            let mut checks = Vec::new();
            let mut first: Option<gtk::CheckButton> = None;
            for c in &s.categories {
                let check = gtk::CheckButton::new();
                if let Some(f) = &first {
                    check.set_group(Some(f));
                } else {
                    first = Some(check.clone());
                }
                let row = adw::ActionRow::builder().title(said::key(&c.word)).activatable_widget(&check).build();
                row.add_prefix(&check);
                self.categories.append(&row);
                let (w, id) = (Rc::downgrade(self), c.id.clone());
                check.connect_toggled(move |b| {
                    let Some(i) = w.upgrade() else { return };
                    if !i.drawing.get() && b.is_active() {
                        let category = id.clone();
                        i.send_call(|report| Call::ReportCategory { report, category });
                    }
                });
                checks.push((c.id.clone(), check));
            }
            *self.category_checks.borrow_mut() = checks;
        }
        if s.sent {
            self.stack.set_visible_child_name("sent");
            let at = s.received_at.as_deref().map(said::instant_moment).unwrap_or_default();
            self.receipt.set_label(&words::report_receipt(s.reference.as_deref().unwrap_or_default(), &at));
            self.send.set_visible(false);
            self.cancel.set_visible(false);
        } else {
            self.stack.set_visible_child_name("form");
            self.whole.set_active(s.whole);
            self.name_only.set_active(!s.whole);
            self.scope_hint.set_label(&said::key(&s.scope_hint));
            for (id, check) in self.category_checks.borrow().iter() {
                check.set_active(s.category.as_deref() == Some(id.as_str()));
            }
            self.note.set_visible(s.note.is_some());
            if let Some(n) = &s.note {
                self.note.set_label(&said::key(n));
            }
            self.good_faith.set_active(s.good_faith);
            self.problem.set_visible(s.problem.is_some());
            if let Some(p) = &s.problem {
                self.problem.set_label(&said::notice(p));
            }
            self.form.set_sensitive(!s.sending);
            self.send.set_sensitive(s.can_send);
            self.cancel.set_sensitive(!s.sending);
        }
        self.drawing.set(false);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_text_is_cut_to_the_cores_room_in_utf16_units_never_inside_a_character() {
        assert_eq!(cut("abc", 2), "ab");
        assert_eq!(cut("abc", 3), "abc");
        // an emoji is two UTF-16 units: it is kept whole or left out
        assert_eq!(cut("a😀b", 2), "a");
        assert_eq!(cut("a😀b", 3), "a😀");
        assert_eq!(cut("äö", 1), "ä");
    }
}
