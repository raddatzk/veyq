//! veyq's one ask for a donation (ADR 0124 decision 7: the apps outside the stores show the web's banner): a quiet bar
//! above the signed-in shell (`ShellShown.donationBanner`) and a received link's page (`ReceivedShown.donationBanner`) — the
//! web's text, *Donate* opening the donation page in the browser, and *Close and don't show again*, which sends [dismiss]
//! (the core keeps it on this device, and for a member on the account). When it shows is the core's (`Donation`).
use adw::prelude::*;

use crate::bridge::{self, wire::Call};
use crate::core_ui::{home, words};

/// The bar, hidden until the core says it shows; its close sends [dismiss].
pub fn bar(dismiss: Call) -> gtk::Revealer {
    let text = gtk::Label::builder().wrap(true).xalign(0.0).hexpand(true).build();
    text.set_markup(&format!("<b>{}</b>\n{}", home::glib_escape(&words::project_banner_label()), home::glib_escape(&words::project_banner_text())));
    let close = gtk::Button::builder().label(words::project_banner_dismiss()).valign(gtk::Align::Center).build();
    close.add_css_class("flat");
    close.set_widget_name("donation-banner-dismiss");
    let donate = gtk::Button::builder().label(words::project_banner_donate()).valign(gtk::Align::Center).build();
    donate.set_widget_name("donation-banner-donate");
    donate.connect_clicked(|b| crate::core_ui::received::launch(b, &words::project_donate_url()));
    let row = gtk::Box::builder().spacing(12).margin_start(12).margin_end(12).margin_top(6).margin_bottom(6).build();
    row.append(&text);
    row.append(&close);
    row.append(&donate);
    row.update_property(&[gtk::accessible::Property::Label(&words::project_banner_label())]);
    let revealer = gtk::Revealer::builder().child(&row).reveal_child(false).build();
    revealer.set_widget_name("donation-banner");
    let shown = revealer.downgrade();
    close.connect_clicked(move |_| {
        if let Some(r) = shown.upgrade() {
            r.set_reveal_child(false);
        }
        bridge::send(dismiss.clone());
    });
    revealer
}
