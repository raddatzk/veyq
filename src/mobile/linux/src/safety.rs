//! Safety numbers on a person's page (ADR 0148 decision 3; the web's `PersonSafetyNumber`), GNOME's way: a group below the
//! name. On one's own page *Your safety number* → *Show*, an `AdwDialog` with the digits, the chain code and the code to
//! scan; on another member's page their state (verified, verified by, not verified, new, a new identity, replaced, can't
//! confirm) and *Compare* → an `AdwDialog` with an `AdwNavigationView`: their number and chain code, *Scan their code* (where
//! the Camera portal names a camera; only a `veyqid:` code is taken) and *Compare digits* → "They match" / "They don't
//! match". Every state and verdict is the core's (`Trust.*`); a safety number is no secret.
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gdk, glib};

use crate::bridge::{self, wire::Answer, wire::Call, wire::Outcome, wire::PersonTrustStatus, wire::ScanKind, wire::ScanState, wire::Shown};
use crate::qr_camera::ScanView;
use crate::{said, words};

/// The safety number's group for [user_id]'s page ([mine]: one's own), named [name]; hidden where they are no member here.
pub fn section(user_id: &str, mine: bool, name: &str) -> adw::PreferencesGroup {
    let group = adw::PreferencesGroup::builder().margin_top(12).build();
    group.set_widget_name("person-safety");
    if mine {
        group.set_title(&words::safety_mine_title());
        let row = adw::ActionRow::builder().title(words::safety_mine_title()).activatable(true).build();
        let show = gtk::Button::builder().label(words::safety_show()).valign(gtk::Align::Center).build();
        row.add_suffix(&show);
        row.set_activatable_widget(Some(&show));
        show.connect_clicked(move |b| mine_dialog(b.upcast_ref()));
        group.add(&row);
        return group;
    }
    group.set_title(&words::safety_theirs_title());
    group.set_visible(false);
    let slot = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    slot.add_css_class("boxed-list");
    group.add(&slot);
    draw_theirs(&group, &slot, user_id.to_owned(), name.to_owned());
    group
}

/// Reads [user]'s state (`Trust.Person`) and draws it into [group]'s [slot]: one row of the state and *Compare*.
fn draw_theirs(group: &adw::PreferencesGroup, slot: &gtk::ListBox, user: String, name: String) {
    let (weak, slot_weak) = (group.downgrade(), slot.downgrade());
    glib::spawn_future_local(async move {
        let answered = bridge::call(&Call::TrustPerson { user_id: user.clone() }).await.map(|(a, _)| a);
        let (Some(group), Some(slot)) = (weak.upgrade(), slot_weak.upgrade()) else { return };
        slot.remove_all();
        let (shown, status) = match answered {
            Ok(Answer::PersonTrust { shown: Some(shown), status }) => (shown, status),
            Ok(Answer::PersonTrust { shown: None, .. }) => return group.set_visible(false),
            other => {
                eprintln!("veyq: Trust.Person answered {other:?}");
                let row = adw::ActionRow::builder().title(words::safety_load_error()).build();
                slot.append(&row);
                return group.set_visible(true);
            }
        };
        eprintln!("veyq: safety: {status:?}, number {}, verified by {}, by me {}, new {}, rotated {}, replaced {}, anomaly {}",
            shown.number.is_some(), shown.verified_by.len(), shown.verified_by_me, shown.is_new, shown.rotated_at.is_some(), shown.replaced, shown.anomaly);
        let row = adw::ActionRow::builder().title(status_word(status, &shown)).subtitle(state_line(&shown)).use_markup(false).subtitle_lines(0).build();
        row.set_widget_name("safety-state");
        row.add_prefix(&gtk::Image::from_icon_name(match status {
            Some(PersonTrustStatus::Verified) => "security-high-symbolic",
            Some(PersonTrustStatus::CantConfirm) => "dialog-warning-symbolic",
            _ => "security-medium-symbolic",
        }));
        if shown.number.is_some() && !shown.anomaly {
            let compare = gtk::Button::builder().label(words::safety_open()).valign(gtk::Align::Center).build();
            compare.set_widget_name("safety-compare");
            let (g, sl, u, n) = (group.clone(), slot.clone(), user.clone(), name.clone());
            compare.connect_clicked(move |b| theirs_dialog(b.upcast_ref(), (&g, &sl), &u, &n, &shown));
            row.add_suffix(&compare);
        }
        slot.append(&row);
        group.set_visible(true);
    });
}

/// The state's word, the web's: verified, not verified, new, can't confirm.
fn status_word(status: Option<PersonTrustStatus>, shown: &Shown) -> String {
    match status {
        Some(PersonTrustStatus::Verified) => words::safety_state_verified(),
        Some(PersonTrustStatus::New) => words::safety_state_new(),
        Some(PersonTrustStatus::CantConfirm) => words::trust_anomaly(),
        _ if shown.number.is_none() => words::safety_unreadable(),
        _ => words::safety_state_unverified(),
    }
}

/// The lines under the state: who verified them, a new identity, a replaced key, the anomaly's note.
pub fn state_line(shown: &Shown) -> String {
    let mut lines = Vec::new();
    if shown.anomaly {
        lines.push(words::trust_anomaly_note());
    }
    let mut by: Vec<String> = Vec::new();
    if shown.verified_by_me {
        by.push(words::safety_verified_by_you());
    }
    by.extend(shown.verified_by.iter().cloned());
    if !by.is_empty() {
        lines.push(words::safety_verified_by(&by.join(", ")));
    }
    if let Some(at) = shown.rotated_at {
        lines.push(words::trust_rotated(&said::moment(at)));
    }
    if shown.replaced {
        lines.push(words::safety_replaced());
    }
    if shown.number.is_none() && !shown.anomaly {
        lines.push(words::safety_unreadable());
    }
    lines.join("\n")
}

fn label(text: &str) -> gtk::Label {
    gtk::Label::builder().label(text).wrap(true).xalign(0.0).build()
}

/// The digits as the web shows them: monospace, selectable, wrapping between groups.
fn digits(number: &str) -> gtk::Label {
    let l = gtk::Label::builder().label(number).wrap(true).selectable(true).xalign(0.0).build();
    l.add_css_class("monospace");
    l.add_css_class("title-3");
    l
}

fn page(title: &str, tag: &str, body: &impl IsA<gtk::Widget>) -> adw::NavigationPage {
    let clamp = adw::Clamp::builder().maximum_size(520).margin_top(12).margin_bottom(24).margin_start(12).margin_end(12).child(body).build();
    let view = adw::ToolbarView::new();
    view.add_top_bar(&adw::HeaderBar::new());
    view.set_content(Some(&gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build()));
    adw::NavigationPage::builder().title(title).tag(tag).child(&view).build()
}

fn column() -> gtk::Box {
    gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).build()
}

/// One's own safety number: the digits, the chain code and the code another member scans.
fn mine_dialog(over: &gtk::Widget) {
    let col = column();
    col.append(&label(&words::safety_mine_intro()));
    let code = gtk::Picture::builder().width_request(240).height_request(240).halign(gtk::Align::Center).can_shrink(true).build();
    code.set_alternative_text(Some(&words::safety_qr_alt()));
    code.set_widget_name("safety-code");
    col.append(&code);
    let number = digits("");
    number.set_widget_name("safety-number");
    col.append(&number);
    let chain_title = label(&words::safety_chain_code());
    chain_title.add_css_class("heading");
    col.append(&chain_title);
    let chain = digits("");
    chain.set_widget_name("safety-chain");
    col.append(&chain);
    let hint = label(&words::safety_mine_chain_code_hint());
    hint.add_css_class("dim-label");
    col.append(&hint);
    let dialog = adw::Dialog::builder().title(words::safety_mine_title()).content_width(520).content_height(680).child(&page(&words::safety_mine_title(), "mine", &col)).build();
    let (n, c, p) = (number.downgrade(), chain.downgrade(), code.downgrade());
    glib::spawn_future_local(async move {
        match bridge::call(&Call::TrustMine).await.map(|(a, _)| a) {
            Ok(Answer::SafetyNumber { number, chain_code }) => {
                if let (Some(n), Some(c)) = (n.upgrade(), c.upgrade()) {
                    n.set_label(&number);
                    c.set_label(&chain_code);
                }
            }
            other => {
                eprintln!("veyq: Trust.Mine answered {other:?}");
                if let Some(n) = n.upgrade() {
                    n.remove_css_class("monospace");
                    n.set_label(&words::safety_mine_unavailable());
                }
                return;
            }
        }
        match bridge::call(&Call::TrustMineCode { px: 480 }).await {
            Ok((Answer::Bytes { .. }, Some(png))) => match gdk::Texture::from_bytes(&glib::Bytes::from_owned(png)) {
                Ok(t) => {
                    if let Some(p) = p.upgrade() {
                        p.set_paintable(Some(&t));
                    }
                }
                Err(e) => eprintln!("veyq: the safety code did not decode ({e})"),
            },
            other => {
                eprintln!("veyq: Trust.MineCode answered {:?}", other.map(|(a, _)| a));
                if let Some(p) = p.upgrade() {
                    p.set_visible(false);
                }
            }
        }
    });
    dialog.present(Some(over));
}

/// Another member's number to compare: by their code or by the digits; a verdict re-reads the page's state ([group]).
fn theirs_dialog(over: &gtk::Widget, (group, slot): (&adw::PreferencesGroup, &gtk::ListBox), user: &str, name: &str, shown: &Shown) {
    let nav = adw::NavigationView::new();
    let toasts = { let t = adw::ToastOverlay::new(); t.set_child(Some(&nav)); t };
    let dialog = adw::Dialog::builder().title(words::safety_theirs_dialog_title(name)).content_width(520).content_height(680).child(&toasts).build();
    let col = column();
    col.append(&label(&words::safety_theirs_intro(name)));
    let number = digits(shown.number.as_deref().unwrap_or_default());
    number.set_widget_name("safety-number");
    col.append(&number);
    let chain_title = label(&words::safety_chain_code());
    chain_title.add_css_class("heading");
    col.append(&chain_title);
    col.append(&digits(shown.chain_code.as_deref().unwrap_or_default()));
    let hint = label(&words::safety_chain_code_hint(name));
    hint.add_css_class("dim-label");
    col.append(&hint);
    let verdict = label("");
    verdict.set_visible(false);
    verdict.set_widget_name("safety-verdict");
    let scan = gtk::Button::builder().label(words::safety_scan()).halign(gtk::Align::Center).visible(false).build();
    scan.add_css_class("pill");
    let compare = gtk::Button::builder().label(words::safety_compare()).halign(gtk::Align::Center).build();
    compare.add_css_class("pill");
    compare.add_css_class("suggested-action");
    col.append(&scan);
    col.append(&compare);
    col.append(&verdict);
    nav.add(&page(&words::safety_theirs_dialog_title(name), "theirs", &col));
    // a verdict: said on the first page, the page's state read again
    let said_verdict = {
        let (verdict, nav, group, slot, user, name) = (verdict.clone(), nav.clone(), group.clone(), slot.clone(), user.to_owned(), name.to_owned());
        Rc::new(move |answered: Result<Answer, ()>| {
            let outcome = match answered {
                Ok(Answer::TrustOutcome { outcome }) => outcome,
                other => {
                    eprintln!("veyq: a compare answered {other:?}");
                    Outcome::Failed
                }
            };
            eprintln!("veyq: safety compare: {outcome:?}");
            verdict.set_label(&outcome_words(outcome, &name));
            verdict.remove_css_class("error");
            verdict.remove_css_class("success");
            verdict.add_css_class(if outcome == Outcome::Verified { "success" } else { "error" });
            verdict.set_visible(true);
            nav.pop_to_tag("theirs");
            draw_theirs(&group, &slot, user.clone(), name.clone());
        })
    };
    // the digits read aloud
    let (n, u, v, nm) = (nav.clone(), user.to_owned(), said_verdict.clone(), name.to_owned());
    compare.connect_clicked(move |_| {
        let col = column();
        col.append(&label(&words::safety_compare_hint(&nm)));
        let row = gtk::Box::builder().spacing(12).halign(gtk::Align::Center).build();
        for (word, matched) in [(words::safety_they_match(), true), (words::safety_they_differ(), false)] {
            let b = gtk::Button::with_label(&word);
            b.add_css_class("pill");
            b.add_css_class(if matched { "suggested-action" } else { "destructive-action" });
            let (u, v) = (u.clone(), v.clone());
            b.connect_clicked(move |_| {
                let (u, v) = (u.clone(), v.clone());
                glib::spawn_future_local(async move {
                    let answered = bridge::call(&Call::TrustCompareDigits { user_id: u, r#match: matched }).await.map(|(a, _)| a).map_err(|_| ());
                    v(answered);
                });
            });
            row.append(&b);
        }
        col.append(&row);
        n.push(&page(&words::safety_compare(), "digits", &col));
    });
    // their code scanned: only a `veyqid:` is taken, compared in the core
    let (n, u, v, nm) = (nav.clone(), user.to_owned(), said_verdict, name.to_owned());
    scan.connect_clicked(move |_| {
        let col = column();
        col.append(&label(&words::safety_scan_hint(&nm)));
        let slot = gtk::Box::builder().vexpand(true).height_request(320).build();
        col.append(&slot);
        let p = page(&words::safety_scan(), "scan", &col);
        let other = |shown: &crate::bridge::wire::ScanShown| match shown.state {
            ScanState::NoCamera | ScanState::Denied => Some(label(&words::safety_scan_error()).upcast()),
            _ => None,
        };
        let held: Rc<std::cell::RefCell<Option<Rc<ScanView>>>> = Rc::new(std::cell::RefCell::new(None));
        let (h, u, v, once) = (held.clone(), u.clone(), v.clone(), std::cell::Cell::new(false));
        let view = ScanView::open(ScanKind::Safety, None, other, move |shown| {
            if shown.state != ScanState::Found || once.replace(true) {
                return;
            }
            let Some(scan) = h.borrow().as_ref().and_then(|s| s.scan_id()) else { return };
            let (u, v) = (u.clone(), v.clone());
            glib::spawn_future_local(async move {
                let answered = bridge::call(&Call::TrustCompareScanned { user_id: u, scan }).await.map(|(a, _)| a).map_err(|_| ());
                v(answered);
            });
        });
        slot.append(&view.widget);
        view.widget.set_hexpand(true);
        *held.borrow_mut() = Some(view.clone());
        // the scan and its camera end with its page
        p.connect_hidden(move |_| {
            if let Some(v) = held.borrow_mut().take() {
                v.close();
            }
        });
        n.push(&p);
    });
    let s = scan.downgrade();
    glib::spawn_future_local(async move {
        let present = crate::qr_camera::camera_present().await;
        eprintln!("veyq: safety scan {}", if present { "offered" } else { "not offered: no camera" });
        if let Some(s) = s.upgrade() {
            s.set_visible(present);
        }
    });
    dialog.present(Some(over));
}

/// A compare's verdict in the web's words.
pub fn outcome_words(outcome: Outcome, name: &str) -> String {
    match outcome {
        Outcome::Verified => words::safety_verified(name),
        Outcome::Mismatch => words::safety_mismatch(name),
        Outcome::Foreign => words::safety_foreign(),
        Outcome::Outdated => words::safety_outdated(name),
        Outcome::Moved => words::safety_moved(name),
        Outcome::Unanchored => words::safety_unanchored(name),
        Outcome::Failed => words::safety_failed(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn shown() -> Shown {
        Shown {
            user_id: "u".into(), anomaly: false, number: Some("12345".into()), chain_code: Some("67890".into()), is_new: false,
            verified_by: vec![], verified_by_me: false, rotated_at: None, replaced: false,
        }
    }

    #[test]
    fn the_state_line_names_who_verified_with_you_first() {
        assert_eq!(state_line(&shown()), "");
        let by = Shown { verified_by: vec!["Ada".into()], verified_by_me: true, ..shown() };
        assert_eq!(state_line(&by), words::safety_verified_by(&format!("{}, Ada", words::safety_verified_by_you())));
        let replaced = Shown { replaced: true, ..shown() };
        assert_eq!(state_line(&replaced), words::safety_replaced());
        let unreadable = Shown { number: None, ..shown() };
        assert_eq!(state_line(&unreadable), words::safety_unreadable());
    }

    #[test]
    fn each_verdict_has_its_words() {
        assert_eq!(outcome_words(Outcome::Verified, "Ada"), words::safety_verified("Ada"));
        assert_eq!(outcome_words(Outcome::Foreign, "Ada"), words::safety_foreign());
        assert_ne!(outcome_words(Outcome::Mismatch, "Ada"), outcome_words(Outcome::Verified, "Ada"));
    }
}
