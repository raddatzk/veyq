//! A view's creating action, GNOME's way (ADR 0123 decision 9; CR core-as-a-native-library D12): a "+" in the header
//! bar — or at the sidebar's top — drawn with `list-add-symbolic`, its words as tooltip and accessible label; where a
//! level creates several kinds, the same "+" opens a menu of them. GNOME has no floating action button: nothing here
//! or elsewhere floats a button over a view through a `gtk::Overlay` (`scripts/validate_mobile_platform_guidelines.py`
//! holds both). Every view takes its creating action from here.
use gtk::gio;
use gtk::prelude::*;

/// The symbolic icon of every creating action.
pub const ICON: &str = "list-add-symbolic";

/// The "+" that creates one kind: [label] is its words (e.g. `words::pot_new()`), said as tooltip and to a screen reader.
pub fn create_action(label: &str, on_create: impl Fn() + 'static) -> gtk::Button {
    let button = gtk::Button::builder().icon_name(ICON).tooltip_text(label).build();
    button.update_property(&[gtk::accessible::Property::Label(label)]);
    button.connect_clicked(move |_| on_create());
    button
}

/// The "+" of a level that creates several kinds: it opens [menu], one entry per kind.
pub fn create_menu(label: &str, menu: &gio::MenuModel) -> gtk::MenuButton {
    let button = gtk::MenuButton::builder().icon_name(ICON).tooltip_text(label).menu_model(menu).build();
    button.update_property(&[gtk::accessible::Property::Label(label)]);
    button
}
