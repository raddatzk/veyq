//! A tenant's terms (uc.account.accept-terms; the web's `TermsAcceptView` and `TenantTermsPanel`): the member's gate
//! ([TermsGateView], the core's `Gate.Signed.terms`), shown as the window's face in place of the shell while it stands —
//! the space's terms, the operator section after them, *I agree* and *Sign out*, the core deciding what each press means
//! and answering with the next gate —, and Settings' Terms ([page], `SettingsSection.TERMS`), a subpage of the Settings
//! dialog that reads them read-only (`Settings.Terms`). The privacy notice's passage on the guest-write record opens in the
//! browser.
use adw::prelude::*;
use gtk::{gio, glib};

use crate::bridge::{self, wire::Answer, wire::Call, wire::TermsShown, wire::TermsGate};
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
        self.column.append(&terms_text(&gate.shown));
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

/// Settings' Terms: a subpage read when it opens, the terms read-only with the note that the owner edits them in the browser.
pub fn page() -> adw::NavigationPage {
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
        let shown = match bridge::call(&Call::SettingsTerms).await.map(|(a, _)| a) {
            Ok(Answer::Terms { shown }) => shown,
            other => {
                eprintln!("veyq: Settings.Terms answered {other:?}");
                None
            }
        };
        while let Some(child) = column.first_child() {
            column.remove(&child);
        }
        match shown {
            Some(shown) => {
                column.append(&label(&words::terms_readonly_note(), true));
                column.append(&terms_text(&shown));
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

/// The terms as the core hands them: the space's text (or that it has none), the operator section under its label and
/// note, and the record's pointer with its link.
fn terms_text(shown: &TermsShown) -> gtk::Box {
    let b = gtk::Box::new(gtk::Orientation::Vertical, 12);
    if shown.has_terms && !shown.terms.trim().is_empty() {
        b.append(&markdown(&shown.terms));
    } else {
        b.append(&label(&words::terms_none_set(), true));
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
                Block { text: lines.join("\n").replace("**", ""), heading: false }
            }
        })
        .collect()
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
}
