//! Settings → Map services on Linux (ADR 0143 decision 2; the web's `MapsPanel`, Windows' `MapServicesSection`): this
//! device's consent to the open tenant's map services as a preferences group — the services served now by their hosts
//! (the map's and the place names'), what each receives, and one switch row that gives the consent for exactly those hosts
//! or withdraws it at once (`Map.TurnOn`, `Map.TurnOff`); where the consent was given for other hosts, which, and that it
//! holds only for them. With no map served (or offline) it says so and offers nothing. Followed live through
//! `Watched.MapServices` while the dialog shows; built once, so the switch is never re-made while it toggles.
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::bridge::{self, Watch, wire::MapServicesShown, wire::MapStatus, wire::Update, wire::Watched};
use crate::core_ui::home::glib_escape;
use crate::core_ui::words;

/// The Map services group of one Settings dialog.
pub struct MapServices {
    group: adw::PreferencesGroup,
    inner: Rc<Inner>,
}

struct Inner {
    group: adw::PreferencesGroup,
    tiles: adw::ActionRow,
    places: adw::ActionRow,
    switch: adw::SwitchRow,
    receive: adw::ActionRow,
    given_for: adw::ActionRow,
    not_offered: adw::ActionRow,
    drawing: Cell<bool>,
    watch: RefCell<Option<Watch>>,
}

impl MapServices {
    pub fn new() -> Self {
        let group = adw::PreferencesGroup::builder().title(words::settings_maps()).description(words::settings_maps_sub()).visible(false).build();
        let value_row = |title: String| {
            let row = adw::ActionRow::builder().title(title).subtitle_selectable(true).build();
            row.add_css_class("property");
            row
        };
        let tiles = value_row(words::settings_maps_tiles());
        let places = value_row(words::settings_maps_places());
        let switch = adw::SwitchRow::builder().title(words::settings_maps_switch()).subtitle(glib_escape(&words::settings_maps_switch_hint())).build();
        switch.set_widget_name("settings-maps-switch");
        let receive = adw::ActionRow::builder().subtitle(glib_escape(&words::settings_maps_receive())).build();
        let given_for = adw::ActionRow::builder().visible(false).build();
        given_for.add_css_class("warning");
        let not_offered = adw::ActionRow::builder().subtitle(glib_escape(&words::settings_maps_not_offered())).visible(false).build();
        for r in [tiles.upcast_ref::<gtk::Widget>(), places.upcast_ref(), receive.upcast_ref(), switch.upcast_ref(), given_for.upcast_ref(), not_offered.upcast_ref()] {
            group.add(r);
        }
        let inner = Rc::new(Inner {
            group: group.clone(),
            tiles,
            places,
            switch,
            receive,
            given_for,
            not_offered,
            drawing: Cell::new(false),
            watch: RefCell::new(None),
        });
        let weak = Rc::downgrade(&inner);
        inner.switch.connect_active_notify(move |row| {
            let Some(i) = weak.upgrade() else { return };
            if i.drawing.get() {
                return;
            }
            if row.is_active() { turn_on() } else { crate::core_ui::raster_map::turn_off() }
        });
        if let Some((watch, updates)) = bridge::watch(&Watched::MapServices) {
            *inner.watch.borrow_mut() = Some(watch);
            let weak = Rc::downgrade(&inner);
            glib::spawn_future_local(async move {
                while let Ok(update) = updates.recv().await {
                    let Some(i) = weak.upgrade() else { return };
                    match update {
                        Ok(Update::MapServices { shown }) => i.draw(shown.as_ref()),
                        Ok(_) => {}
                        Err(e) => eprintln!("veyq: a map services update did not read: {:?}", e.0),
                    }
                }
            });
        }
        Self { group, inner }
    }

    pub fn group(&self) -> &adw::PreferencesGroup {
        &self.group
    }
}

impl Default for MapServices {
    fn default() -> Self {
        Self::new()
    }
}

impl Drop for MapServices {
    fn drop(&mut self) {
        self.inner.watch.borrow_mut().take();
    }
}

fn turn_on() {
    eprintln!("veyq: settings: map services on");
    glib::spawn_future_local(async {
        let answered = bridge::call(&bridge::wire::Call::MapTurnOn).await.map(|(a, _)| a);
        eprintln!("veyq: Map.TurnOn answered {answered:?}");
    });
}

/// What the consent note says (`settings_maps_given_for`): only where it is asked anew — for the hosts it was given for,
/// or that it named none.
pub fn given_for(s: &MapServicesShown) -> Option<String> {
    let MapStatus::MapStateConsent { renewal: true, .. } = s.state else { return None };
    Some(match (&s.consent_tile_host, &s.consent_geocoder_host) {
        (None, None) => words::settings_maps_given_for_unrecorded(),
        (tiles, places) => words::settings_maps_given_for(tiles.as_deref().unwrap_or(&words::settings_maps_none()), places.as_deref().unwrap_or(&words::settings_maps_none())),
    })
}

impl Inner {
    fn draw(&self, shown: Option<&MapServicesShown>) {
        let Some(s) = shown else {
            self.group.set_visible(false);
            return;
        };
        eprintln!("veyq: settings: map services {:?}, consent for {:?} / {:?}", s.state, s.consent_tile_host, s.consent_geocoder_host);
        self.group.set_visible(true);
        let none = matches!(s.state, MapStatus::MapStateNotOffered | MapStatus::MapStateOffline);
        self.not_offered.set_subtitle(&glib_escape(&if matches!(s.state, MapStatus::MapStateOffline) { words::map_needs_connection() } else { words::settings_maps_not_offered() }));
        self.not_offered.set_visible(none);
        for r in [&self.tiles, &self.places, &self.receive] {
            r.set_visible(!none);
        }
        self.switch.set_visible(!none);
        if none {
            self.given_for.set_visible(false);
            return;
        }
        // the hosts served now: the consent's ask names them; once it holds, they are those it was given for
        let (tiles, places) = match &s.state {
            MapStatus::MapStateConsent { tile_host, geocoder_host, .. } => (tile_host.clone(), geocoder_host.clone()),
            _ => (s.consent_tile_host.clone(), s.consent_geocoder_host.clone()),
        };
        self.tiles.set_subtitle(&glib_escape(&tiles.unwrap_or_else(words::settings_maps_none)));
        self.places.set_subtitle(&glib_escape(&places.unwrap_or_else(words::settings_maps_none)));
        let on = matches!(s.state, MapStatus::MapStateShown);
        if self.switch.is_active() != on {
            self.drawing.set(true);
            self.switch.set_active(on);
            self.drawing.set(false);
        }
        let note = given_for(s);
        self.given_for.set_visible(note.is_some());
        self.given_for.set_subtitle(&glib_escape(&note.unwrap_or_default()));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn shown(state: MapStatus, tiles: Option<&str>, places: Option<&str>) -> MapServicesShown {
        MapServicesShown { state, consent_tile_host: tiles.map(str::to_owned), consent_geocoder_host: places.map(str::to_owned) }
    }

    /// The note says for which hosts an earlier consent holds only where it is asked anew, and that a consent naming none
    /// holds for nothing; a consent that holds, or a first ask, says nothing.
    #[test]
    fn the_consent_note_names_the_hosts_it_was_given_for() {
        let renewal = || MapStatus::MapStateConsent { tile_host: Some("tile.new".into()), geocoder_host: None, renewal: true };
        assert_eq!(given_for(&shown(renewal(), Some("tile.old"), Some("geo.old"))), Some(words::settings_maps_given_for("tile.old", "geo.old")));
        assert_eq!(given_for(&shown(renewal(), None, None)), Some(words::settings_maps_given_for_unrecorded()));
        assert_eq!(given_for(&shown(MapStatus::MapStateShown, Some("tile.old"), None)), None);
        let first = MapStatus::MapStateConsent { tile_host: Some("t".into()), geocoder_host: None, renewal: false };
        assert_eq!(given_for(&shown(first, None, None)), None);
    }
}
