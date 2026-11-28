//! *Start a list over* (core's `TrustReset`, ADR 0028 decision 7; the web's `TrustResetPanel` and `TrustResetDialog`), GNOME's
//! way: a subpage of the Settings dialog listing each list this device refuses — its name, why, *Start over…* — and the
//! question before it is started over as an `AdwAlertDialog`: what it costs and that the server can wait, the
//! acknowledgement as its extra child (unticked at every open), *Cancel* and the destructive *Start over*, enabled only
//! once acknowledged. Every row, word and outcome is the core's (`TrustReset.*`, watched as `Watched.TrustReset`); no
//! password or key is asked. Leaving the subpage closes the flow (`TrustReset.Close`).
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::Answer, wire::ResetQuestion, wire::Call, wire::TrustResetState, wire::Update, wire::Watched};
use crate::core_ui::{said, words};

/// What the subpage holds while it shows: its flow's id, its watch, the question drawn now.
#[derive(Default)]
struct Held {
    flow: Cell<Option<i64>>,
    watch: RefCell<Option<Watch>>,
    asking: RefCell<Option<adw::AlertDialog>>,
    /// Set while this side writes the acknowledgement's check, so no `Acknowledge` goes back.
    quiet: Cell<bool>,
}

/// The subpage over the account [connection] names.
pub fn page(connection: String) -> adw::NavigationPage {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).margin_top(24).margin_bottom(24).margin_start(12).margin_end(12).build();
    let view = adw::ToolbarView::new();
    view.add_top_bar(&adw::HeaderBar::new());
    let clamp = adw::Clamp::builder().maximum_size(640).child(&column).build();
    view.set_content(Some(&gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build()));
    let page = adw::NavigationPage::builder().title(words::trust_reset_title()).child(&view).build();
    page.set_widget_name("trust-reset");
    let sub = gtk::Label::builder().label(words::trust_reset_sub()).wrap(true).xalign(0.0).build();
    sub.add_css_class("dim-label");
    column.append(&sub);
    let body = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).build();
    column.append(&body);
    let held = Rc::new(Held::default());
    let (h, b, over) = (held.clone(), body.clone(), page.clone());
    glib::spawn_future_local(async move {
        match bridge::call(&Call::TrustResetOpen { connection }).await.map(|(a, _)| a) {
            Ok(Answer::FlowOpened { flow }) => {
                h.flow.set(Some(flow));
                let Some((watch, updates)) = bridge::watch(&Watched::TrustReset { flow }) else { return };
                *h.watch.borrow_mut() = Some(watch);
                while let Ok(update) = updates.recv().await {
                    let Ok(Update::TrustReset { shown, .. }) = update else { continue };
                    let Some(s) = shown else { return };
                    draw(&h, &b, &over, &s);
                }
            }
            other => eprintln!("veyq: TrustReset.Open answered {}", crate::core_ui::manage::answer_kind(&other)),
        }
    });
    let h = held.clone();
    page.connect_hidden(move |_| {
        if let Some(d) = h.asking.borrow_mut().take() {
            d.force_close();
        }
        h.watch.borrow_mut().take();
        if let Some(flow) = h.flow.take() {
            bridge::send(Call::TrustResetClose { flow });
        }
    });
    page
}

fn send(held: &Held, make: impl FnOnce(i64) -> Call) {
    if let Some(flow) = held.flow.get() {
        bridge::send(make(flow));
    }
}

fn draw(held: &Rc<Held>, body: &gtk::Box, over: &adw::NavigationPage, s: &TrustResetState) {
    eprintln!("veyq: trust reset: loading {}, rows {}, said {:?}, asking {}", s.loading, s.rows.len(), s.said, s.asking.is_some());
    while let Some(child) = body.first_child() {
        body.remove(&child);
    }
    if let Some(said) = &s.said {
        let l = gtk::Label::builder().label(said::key(said)).wrap(true).xalign(0.0).build();
        l.set_widget_name("trust-reset-said");
        body.append(&l);
    }
    if s.loading {
        body.append(&adw::Spinner::new());
    } else if let Some(failed) = &s.load_failed {
        let l = gtk::Label::builder().label(said::key(failed)).wrap(true).xalign(0.0).build();
        l.add_css_class("error");
        body.append(&l);
    } else if s.none {
        body.append(&gtk::Label::builder().label(words::trust_reset_none()).wrap(true).xalign(0.0).build());
    } else {
        let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
        list.add_css_class("boxed-list");
        list.set_widget_name("trust-reset-rows");
        for row in &s.rows {
            let r = adw::ActionRow::builder().title(said::key(&row.title)).subtitle(said::key(&row.why)).build();
            let start = gtk::Button::builder().label(words::trust_reset_action()).valign(gtk::Align::Center).build();
            start.add_css_class("destructive-action");
            let (h, document) = (held.clone(), row.document);
            start.connect_clicked(move |_| send(&h, |flow| Call::TrustResetAsk { flow, document }));
            r.add_suffix(&start);
            list.append(&r);
        }
        body.append(&list);
    }
    question(held, over, s.asking.as_ref());
}

/// The question as an `AdwAlertDialog` while the state asks, closed once it does not.
fn question(held: &Rc<Held>, over: &adw::NavigationPage, ask: Option<&ResetQuestion>) {
    let Some(ask) = ask else {
        if let Some(d) = held.asking.borrow_mut().take() {
            d.force_close();
        }
        return;
    };
    let existing = held.asking.borrow().clone();
    // confirmed: the dialog went with the response, the outcome is the page's (a failure opens it again)
    if existing.is_none() && ask.working {
        return;
    }
    let dialog = match existing {
        Some(d) => d,
        None => {
            let d = adw::AlertDialog::builder().heading(said::key(&ask.title)).body(format!("{}\n\n{}", said::key(&ask.cost), words::trust_reset_server_can_wait())).build();
            d.set_widget_name("trust-reset-question");
            d.add_responses(&[("cancel", &words::signin_cancel()), ("confirm", &words::trust_reset_confirm())]);
            d.set_response_appearance("confirm", adw::ResponseAppearance::Destructive);
            d.set_default_response(Some("cancel"));
            d.set_close_response("cancel");
            let extra = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
            let check = gtk::CheckButton::builder().label(words::trust_reset_acknowledge()).build();
            check.set_widget_name("trust-reset-acknowledge");
            let failed = gtk::Label::builder().wrap(true).xalign(0.0).visible(false).build();
            failed.add_css_class("error");
            extra.append(&check);
            extra.append(&failed);
            d.set_extra_child(Some(&extra));
            let h = held.clone();
            check.connect_toggled(move |c| {
                if !h.quiet.get() {
                    let on = c.is_active();
                    send(&h, |flow| Call::TrustResetAcknowledge { flow, on });
                }
            });
            let h = held.clone();
            d.connect_response(None, move |_, response| {
                h.asking.borrow_mut().take();
                if response == "confirm" {
                    // the page waits for the outcome; a failure asks again with its line
                    send(&h, |flow| Call::TrustResetConfirm { flow });
                } else {
                    send(&h, |flow| Call::TrustResetCancel { flow });
                }
            });
            d.present(Some(over));
            *held.asking.borrow_mut() = Some(d.clone());
            d
        }
    };
    dialog.set_response_enabled("confirm", ask.acknowledged && !ask.working);
    dialog.set_response_enabled("cancel", !ask.working);
    if let Some(extra) = dialog.extra_child().and_downcast::<gtk::Box>()
        && let Some(check) = extra.first_child().and_downcast::<gtk::CheckButton>()
    {
        held.quiet.set(true);
        check.set_active(ask.acknowledged);
        check.set_sensitive(!ask.working);
        held.quiet.set(false);
        if let Some(failed) = check.next_sibling().and_downcast::<gtk::Label>() {
            failed.set_visible(ask.failed.is_some());
            failed.set_label(&ask.failed.as_ref().map(|f| said::key(f)).unwrap_or_default());
        }
    }
}
