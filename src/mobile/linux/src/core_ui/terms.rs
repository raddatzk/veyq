//! A tenant's terms (uc.account.accept-terms; the web's `TermsAcceptView` and `TenantTermsPanel`): the member's gate
//! ([TermsGateView], the core's `Gate.Signed.terms`), shown as the window's face in place of the shell while it stands —
//! the space's terms, the operator section after them, *I agree* and *Sign out*, the core deciding what each press means
//! and answering with the next gate —, and Settings' Terms ([page], `SettingsSection.TERMS`), a subpage of the Settings
//! dialog that reads them read-only (`Settings.Terms`). The privacy notice's passage on the guest-write record opens in the
//! browser.
use adw::prelude::*;
use gtk::{gio, glib};

use std::cell::RefCell;
use std::rc::Rc;

use crate::bridge::{self, wire::Answer, wire::Call, wire::LangText, wire::TermsChange, wire::TermsDraft, wire::TermsGate, wire::TermsShown};
use crate::core_ui::sign_in::{error_label, show_error};
use crate::core_ui::words;

/// The gate's page: rebuilt from each state the core sends.
pub struct TermsGateView {
    pub page: adw::ToolbarView,
    column: gtk::Box,
}

impl Default for TermsGateView {
    fn default() -> Self {
        Self::new()
    }
}

impl TermsGateView {
    pub fn new() -> Self {
        let column = column();
        let page = adw::ToolbarView::new();
        page.add_top_bar(&adw::HeaderBar::builder().show_title(false).build());
        page.set_content(Some(&scrolled(&column)));
        page.set_widget_name("terms-gate");
        Self { page, column }
    }

    /// The gate [gate] over the connection [connection] (`Gate.Signed.connectionKey`), whose sign-out is its other way out.
    pub fn show(&self, gate: &TermsGate, connection: &str) {
        while let Some(child) = self.column.first_child() {
            self.column.remove(&child);
        }
        let brand = gtk::Image::from_icon_name(crate::core_ui::theme::APP_ID);
        brand.set_pixel_size(96);
        self.column.append(&brand);
        let heading = gtk::Label::builder().label(words::terms_gate_title()).wrap(true).justify(gtk::Justification::Center).accessible_role(gtk::AccessibleRole::Heading).build();
        heading.add_css_class("title-1");
        self.column.append(&heading);
        self.column.append(&label(&words::terms_gate_intro(), false));
        self.column.append(&terms_text(&gate.shown, true));
        let error = error_label();
        error.set_widget_name("terms-error");
        show_error(&error, &gate.error);
        self.column.append(&error);
        let accept = gtk::Button::builder().label(words::terms_accept()).halign(gtk::Align::Center).sensitive(!gate.working).build();
        accept.add_css_class("suggested-action");
        accept.add_css_class("pill");
        accept.set_widget_name("terms-accept");
        accept.connect_clicked(|_| bridge::send(Call::SessionAcceptTerms));
        self.column.append(&accept);
        if gate.working {
            self.column.append(&adw::Spinner::new());
        }
        let sign_out = gtk::Button::builder().label(words::drawer_sign_out()).halign(gtk::Align::Center).sensitive(!gate.working).build();
        sign_out.add_css_class("flat");
        sign_out.set_widget_name("terms-sign-out");
        let connection = connection.to_string();
        sign_out.connect_clicked(move |_| bridge::send(Call::SessionSignOut { connection: connection.clone() }));
        self.column.append(&sign_out);
    }
}

/// Settings' Terms: a subpage read when it opens — for an owner the editor over the core's draft (`Terms.Editing`,
/// `Terms.Edit`, `Terms.Save`; the web's `TenantTermsPanel`), for a member the terms read-only; [toast] says a save's words.
pub fn page(toast: Rc<dyn Fn(adw::Toast)>) -> adw::NavigationPage {
    let column = column();
    let spinner = adw::Spinner::new();
    column.append(&spinner);
    column.append(&label(&words::terms_loading(), true));
    let view = adw::ToolbarView::new();
    view.add_top_bar(&adw::HeaderBar::new());
    view.set_content(Some(&scrolled(&column)));
    let page = adw::NavigationPage::builder().title(words::terms_settings_title()).child(&view).build();
    page.set_widget_name("terms-page");
    glib::spawn_future_local(async move {
        let editing = match bridge::call(&Call::SettingsTermsEditing).await.map(|(a, _)| a) {
            Ok(Answer::TermsEditing { editing }) => editing,
            other => {
                eprintln!("veyq: Terms.Editing answered {other:?}");
                None
            }
        };
        clear(&column);
        match editing {
            Some(e) if e.owner => {
                let editor = Rc::new(Editor { column: column.clone(), shown: e.shown.clone(), draft: RefCell::new(e.draft.clone()), preview: RefCell::new(String::new()), toast });
                let first = e.draft.clone();
                // the first preview is the core's too: an edit that changes nothing answers it
                editor.edit(first, TermsChange::Text, String::new(), None);
            }
            Some(e) => {
                column.append(&label(&words::terms_readonly_note(), true));
                column.append(&terms_text(&e.shown, true));
            }
            None => {
                let failed = label(&words::terms_load_failed(), false);
                failed.add_css_class("error");
                failed.set_widget_name("terms-load-failed");
                column.append(&failed);
            }
        }
    });
    page
}

fn clear(column: &gtk::Box) {
    while let Some(child) = column.first_child() {
        column.remove(&child);
    }
}

/// The owner's editor: the draft the core last answered, drawn anew after each change of its languages; a typed text
/// asks the core for the new draft and preview without redrawing the field being typed in.
struct Editor {
    column: gtk::Box,
    shown: TermsShown,
    draft: RefCell<TermsDraft>,
    preview: RefCell<String>,
    toast: Rc<dyn Fn(adw::Toast)>,
}

impl Editor {
    /// [change] on [lang] applied by the core to [draft]; the editor redrawn, or only its warning and preview after a typed text ([typed]).
    fn edit(self: &Rc<Self>, draft: TermsDraft, change: TermsChange, lang: String, typed: Option<String>) {
        let text = typed.clone().unwrap_or_default();
        let call = Call::SettingsTermsEdit { draft, change, lang, text };
        let me = self.clone();
        glib::spawn_future_local(async move {
            match bridge::call(&call).await.map(|(a, _)| a) {
                Ok(Answer::TermsDrafted { draft, preview }) => {
                    *me.draft.borrow_mut() = draft;
                    *me.preview.borrow_mut() = preview;
                    if typed.is_some() { me.redraw_tail() } else { me.draw() }
                }
                other => eprintln!("veyq: Terms.Edit answered {other:?}"),
            }
        });
    }

    fn draw(self: &Rc<Self>) {
        clear(&self.column);
        let draft = self.draft.borrow().clone();
        self.column.append(&label(&words::terms_editor_sub(), true));
        if draft.shows_revision {
            let revision = label(&words::terms_revision(i64::from(draft.revision)), false);
            revision.add_css_class("heading");
            revision.set_widget_name("terms-revision");
            self.column.append(&revision);
        }
        for t in &draft.texts {
            self.column.append(&self.language(t));
        }
        if !draft.addable.is_empty() {
            let menu = gio::Menu::new();
            let group = gio::SimpleActionGroup::new();
            for l in &draft.addable {
                let a = gio::SimpleAction::new(&format!("add-{}", l.lang), None);
                let (me, lang) = (self.clone(), l.lang.clone());
                a.connect_activate(move |_, _| { let d = me.draft.borrow().clone(); me.edit(d, TermsChange::Add, lang.clone(), None) });
                group.add_action(&a);
                menu.append(Some(&l.name), Some(&format!("terms.add-{}", l.lang)));
            }
            let add = gtk::MenuButton::builder().label(words::terms_add_language()).menu_model(&menu).halign(gtk::Align::Start).build();
            add.insert_action_group("terms", Some(&group));
            add.set_widget_name("terms-add");
            self.column.append(&add);
        }
        let tail = gtk::Box::new(gtk::Orientation::Vertical, 12);
        tail.set_widget_name("terms-tail");
        self.column.append(&tail);
        self.redraw_tail();
    }

    /// One language's text: its own name, the field, and its *Insert template* and remove, each asked first.
    fn language(self: &Rc<Self>, t: &LangText) -> gtk::Box {
        let b = gtk::Box::new(gtk::Orientation::Vertical, 6);
        b.set_widget_name(&format!("terms-lang-{}", t.lang));
        let name = gtk::Label::builder().label(t.name.as_str()).xalign(0.0).build();
        name.add_css_class("heading");
        b.append(&name);
        let buffer = gtk::TextBuffer::new(None);
        buffer.set_text(&t.text);
        let field = gtk::TextView::builder().buffer(&buffer).wrap_mode(gtk::WrapMode::WordChar).monospace(true).top_margin(8).bottom_margin(8).left_margin(8).right_margin(8).build();
        field.update_property(&[gtk::accessible::Property::Label(&t.name), gtk::accessible::Property::Placeholder(&words::terms_placeholder())]);
        field.set_widget_name(&format!("terms-text-{}", t.lang));
        let frame = gtk::Frame::builder().child(&gtk::ScrolledWindow::builder().min_content_height(180).child(&field).build()).build();
        frame.add_css_class("view");
        b.append(&frame);
        let (me, lang) = (self.clone(), t.lang.clone());
        buffer.connect_changed(move |buf| {
            let text = buf.text(&buf.start_iter(), &buf.end_iter(), false).to_string();
            let d = me.draft.borrow().clone();
            me.edit(d, TermsChange::Text, lang.clone(), Some(text));
        });
        let acts = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        if t.templated {
            let template = gtk::Button::builder().label(words::terms_insert_template()).build();
            template.add_css_class("flat");
            template.set_widget_name(&format!("terms-template-{}", t.lang));
            let (me, lang, ask_words, replaces) = (self.clone(), t.lang.clone(), said_msg_with(&t.template_ask, &t.name), t.template_replaces);
            template.connect_clicked(move |w| {
                let (me, lang) = (me.clone(), lang.clone());
                ask(w, &words::terms_insert_template(), &ask_words, &words::terms_insert_template(), replaces, move || {
                    let d = me.draft.borrow().clone();
                    me.edit(d, TermsChange::Template, lang.clone(), None)
                });
            });
            acts.append(&template);
        }
        let remove = gtk::Button::builder().label(words::terms_remove_lang(&t.name)).build();
        remove.add_css_class("flat");
        remove.set_widget_name(&format!("terms-remove-{}", t.lang));
        let (me, lang, name) = (self.clone(), t.lang.clone(), t.name.clone());
        remove.connect_clicked(move |w| {
            let (me, lang) = (me.clone(), lang.clone());
            ask(w, &words::terms_remove_lang(&name), &words::terms_remove_lang_ask(&name), &words::terms_remove_lang(&name), true, move || {
                let d = me.draft.borrow().clone();
                me.edit(d, TermsChange::Remove, lang.clone(), None)
            });
        });
        acts.append(&remove);
        b.append(&acts);
        b
    }

    /// The blank language's warning, the hint, *Save terms*, the preview and the operator section: what a typed text moves.
    fn redraw_tail(self: &Rc<Self>) {
        let mut child = self.column.first_child();
        let tail = loop {
            match child {
                Some(c) if c.widget_name() == "terms-tail" => break c.downcast::<gtk::Box>().ok(),
                Some(c) => child = c.next_sibling(),
                None => break None,
            }
        };
        let Some(tail) = tail else { return };
        clear(&tail);
        let draft = self.draft.borrow().clone();
        if let (Some(_), Some(names)) = (&draft.blank_warning, &draft.blank_names) {
            let warning = label(&words::terms_blank_locale_warning(names), false);
            warning.add_css_class("warning");
            warning.set_widget_name("terms-blank");
            tail.append(&warning);
        }
        let hint = label(&words::terms_markdown_hint("{contact}"), true);
        hint.add_css_class("caption");
        tail.append(&hint);
        let save = gtk::Button::builder().label(words::terms_save()).halign(gtk::Align::Start).build();
        save.add_css_class("suggested-action");
        save.add_css_class("pill");
        save.set_widget_name("terms-save");
        let me = self.clone();
        save.connect_clicked(move |b| me.save(b));
        tail.append(&save);
        let preview = self.preview.borrow().clone();
        if !preview.is_empty() {
            tail.append(&gtk::Separator::new(gtk::Orientation::Horizontal));
            let heading = gtk::Label::builder().label(words::terms_preview()).xalign(0.0).accessible_role(gtk::AccessibleRole::Heading).build();
            heading.add_css_class("heading");
            tail.append(&heading);
            tail.append(&markdown(&preview));
        }
        tail.append(&terms_text(&self.shown, false));
    }

    fn save(self: &Rc<Self>, button: &gtk::Button) {
        button.set_sensitive(false);
        let draft = self.draft.borrow().clone();
        let (me, button) = (self.clone(), button.clone());
        glib::spawn_future_local(async move {
            match bridge::call(&Call::SettingsTermsSave { draft }).await.map(|(a, _)| a) {
                Ok(Answer::TermsSaved { draft, notice }) => {
                    (me.toast)(crate::core_ui::said::notice_toast(&notice));
                    if let Some(next) = draft {
                        me.edit(next, TermsChange::Text, String::new(), None);
                        return;
                    }
                }
                other => eprintln!("veyq: Terms.Save answered {other:?}"),
            }
            button.set_sensitive(true);
        });
    }
}

/// A word of one text argument, as the core named it (its name the catalog's key in capitals, as `said_msg` reads it).
fn said_msg_with(m: &crate::bridge::wire::Msg, arg: &str) -> String {
    let key = serde_json::to_value(m).ok().and_then(|v| v.as_str().map(str::to_lowercase)).unwrap_or_default();
    crate::core_ui::said::notice(&crate::bridge::wire::Notice { msg: key, args: vec![crate::bridge::wire::NoticeArg::Text { text: arg.to_string() }], kind: crate::bridge::wire::NoticeKind::Said })
}

/// Asks [heading] with [body] first; [accept] (destructive where it [destroys] something written) runs [then].
fn ask(over: &impl IsA<gtk::Widget>, heading: &str, body: &str, accept: &str, destroys: bool, then: impl Fn() + 'static) {
    let alert = adw::AlertDialog::builder().heading(heading).body(body).build();
    alert.add_responses(&[("cancel", &words::signin_cancel()), ("accept", accept)]);
    alert.set_response_appearance("accept", if destroys { adw::ResponseAppearance::Destructive } else { adw::ResponseAppearance::Suggested });
    alert.set_default_response(Some("cancel"));
    alert.set_close_response("cancel");
    alert.connect_response(None, move |_, response| {
        if response == "accept" {
            then();
        }
    });
    alert.present(Some(over));
}

/// The terms as the core hands them: the space's text (or that it has none), the operator section under its label and
/// note, and the record's pointer with its link.
fn terms_text(shown: &TermsShown, with_terms: bool) -> gtk::Box {
    let b = gtk::Box::new(gtk::Orientation::Vertical, 12);
    if with_terms {
        if shown.has_terms && !shown.terms.trim().is_empty() {
            b.append(&markdown(&shown.terms));
        } else {
            b.append(&label(&words::terms_none_set(), true));
        }
    }
    b.append(&gtk::Separator::new(gtk::Orientation::Horizontal));
    let section = gtk::Label::builder().label(words::terms_operator_section_label()).xalign(0.0).accessible_role(gtk::AccessibleRole::Heading).build();
    section.add_css_class("heading");
    b.append(&section);
    let note = label(&words::terms_operator_section_note(), true);
    note.add_css_class("caption");
    b.append(&note);
    if !shown.operator_section.trim().is_empty() {
        b.append(&markdown(&shown.operator_section));
    }
    if !shown.record.trim().is_empty() {
        b.append(&markdown(&shown.record));
        let link = gtk::Button::builder().label(words::terms_record_link()).halign(gtk::Align::Start).build();
        link.add_css_class("flat");
        link.set_widget_name("terms-privacy");
        let uri = shown.privacy_url.clone();
        link.connect_clicked(move |b| {
            let window = b.root().and_downcast::<gtk::Window>();
            gtk::UriLauncher::new(&uri).launch(window.as_ref(), gio::Cancellable::NONE, |r| {
                if let Err(e) = r {
                    eprintln!("veyq: the browser did not open: {e}");
                }
            });
        });
        b.append(&link);
    }
    b
}

/// A text written in Markdown, drawn as its paragraphs (a heading's line bold, a list's items with a bullet): selectable,
/// never followed.
fn markdown(text: &str) -> gtk::Box {
    let b = gtk::Box::new(gtk::Orientation::Vertical, 6);
    for block in blocks(text) {
        let l = label(&block.text, false);
        if block.heading {
            l.add_css_class("heading");
        }
        b.append(&l);
    }
    b
}

struct Block {
    text: String,
    heading: bool,
}

fn blocks(text: &str) -> Vec<Block> {
    text.trim()
        .split("\n\n")
        .map(str::trim)
        .filter(|b| !b.is_empty())
        // a heading is its own line: the lines after it in the same block are the paragraph under it
        .flat_map(|b| match (b.starts_with('#'), b.split_once('\n')) {
            (true, Some((head, rest))) if !rest.trim().is_empty() => vec![head.trim(), rest.trim()],
            _ => vec![b],
        })
        .map(|b| {
            if b.starts_with('#') {
                Block { text: b.trim_start_matches('#').trim().to_string(), heading: true }
            } else {
                let lines: Vec<String> = b
                    .lines()
                    .map(|l| {
                        let l = l.trim();
                        if let Some(rest) = l.strip_prefix("- ").or_else(|| l.strip_prefix("* ")) { format!("• {rest}") } else { l.to_string() }
                    })
                    .collect();
                Block { text: unemphasized(&lines.join("\n").replace("**", "")), heading: false }
            }
        })
        .collect()
}

/// [text] with each `*emphasis*` shown as its words: a `*` that opens before a non-space and the next `*` on the line
/// that closes after one (the operator section's "*Report*", inbox #1418's German pass: the apps drew the stars).
fn unemphasized(text: &str) -> String {
    text.split('\n')
        .map(|line| {
            let c: Vec<char> = line.chars().collect();
            let mut drop = vec![false; c.len()];
            let mut i = 0;
            while i < c.len() {
                if c[i] == '*'
                    && i + 1 < c.len()
                    && !c[i + 1].is_whitespace()
                    && c[i + 1] != '*'
                    && let Some(j) = (i + 1..c.len()).find(|&j| c[j] == '*')
                    && !c[j - 1].is_whitespace()
                {
                    drop[i] = true;
                    drop[j] = true;
                    i = j + 1;
                    continue;
                }
                i += 1;
            }
            c.iter().zip(drop).filter(|(_, d)| !d).map(|(ch, _)| ch).collect::<String>()
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn label(text: &str, dim: bool) -> gtk::Label {
    let l = gtk::Label::builder().label(text).wrap(true).xalign(0.0).selectable(true).build();
    if dim {
        l.add_css_class("dim-label");
    }
    l
}

fn column() -> gtk::Box {
    gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).margin_top(24).margin_bottom(24).margin_start(12).margin_end(12).build()
}

fn scrolled(column: &gtk::Box) -> gtk::ScrolledWindow {
    let clamp = adw::Clamp::builder().maximum_size(640).child(column).build();
    gtk::ScrolledWindow::builder().hscrollbar_policy(gtk::PolicyType::Never).vexpand(true).child(&clamp).build()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_markdown_text_is_its_paragraphs_a_heading_bold_a_list_bulleted() {
        let b = blocks("## Rules\n\nBe kind.\n- one\n* **two**\n\n\n# Contact\nWrite to us.");
        assert_eq!(b.len(), 4);
        assert!(b[0].heading && b[0].text == "Rules");
        assert!(!b[1].heading);
        assert_eq!(b[1].text, "Be kind.\n• one\n• two");
        assert!(b[2].heading && b[2].text == "Contact");
        assert!(!b[3].heading && b[3].text == "Write to us.", "the line under a heading is no heading");
    }

    #[test]
    fn an_emphasis_is_shown_as_its_words() {
        let b = blocks("Use *Report* on what you see.\n* a *b* c\n2 * 3 * 4 stays");
        assert_eq!(b[0].text, "Use Report on what you see.\n• a b c\n2 * 3 * 4 stays");
    }
}
