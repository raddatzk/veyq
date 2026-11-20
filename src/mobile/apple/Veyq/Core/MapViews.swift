import MapKit
import SwiftUI
import VeyqCore

// The core UI's maps on Apple (ADR 0143): MapKit draws every map (decision 1) — Memories' (`MemoryPlaces.swift`: the bar's
// Map tab, the place editor's, a photo's in its details; `MemoryMapView.swift`: a Memory's Map view with its tracks) —
// under core's map services (`MapServices`, decision 2), whose gate, consent offer and Settings section are this file's. A `Map` is mounted only while core's state is `Shown`: before the consent the offer stands
// in its place, after a withdrawal or with the operator's switch off nothing of MapKit is drawn, and offline there is no
// map (decision 9). No `showsUserLocation`, no location key: the app never asks where the device is. What MapKit keeps on
// disk is Apple's residual (src/mobile/CLAUDE.md § Nothing on disk). Every rule is core's; these views draw and report.

// MARK: The gate

/// A map's place (the web's `MapConsentPrompt` + `MapOffButton`): the map while core's state is `Shown`, with *Turn map
/// services off* over it; the consent's offer, worded as a setting, while it is asked; else why there is none.
struct MapGate<Content: View>: View {
    let model: AppModel
    @ViewBuilder let content: () -> Content

    var body: some View {
        let state = model.mapShown
        if state is MapStateShown {
            content()
                .overlay(alignment: .topTrailing) { MapOffButton(model: model).padding(8) }
        } else if let consent = state as? MapStateConsent {
            MapConsentOffer(model: model, consent: consent)
        } else {
            Label(say(state is MapStateOffline ? .mapNeedsConnection : .mapNotOffered), systemImage: "map")
                .foregroundStyle(Role.muted)
                .frame(maxWidth: .infinity, minHeight: 120)
        }
    }
}

/// *Turn map services off* on every map: this device's consent withdrawn at once (GDPR Art. 7(3)), the map unmounted.
struct MapOffButton: View {
    let model: AppModel

    var body: some View {
        Button { model.mapsOff() } label: { Label(say(.mapOff), systemImage: "map") }
            .labelStyle(.iconOnly)
            .buttonStyle(.bordered)
            .background(.regularMaterial, in: Capsule())
            .help(say(.mapOff))
            .accessibilityLabel(say(.mapOff))
    }
}

/// The consent's offer where a map would stand (uc.memory.place A6): what turning on sends where, the recipients by name —
/// on Apple the map's is Apple Maps (owner, 2026-11-16) —, and the one button.
struct MapConsentOffer: View {
    let model: AppModel
    let consent: MapStateConsent

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(say(.mapConsentTitle), systemImage: "map").font(.headline)
            Text(say(.mapConsentBody)).font(.callout).foregroundStyle(Role.muted)
            if let hosts = Self.hosts(consent) { Text(hosts).font(.callout) }
            Button(say(.mapConsentEnable)) { model.mapsOn() }
                .buttonStyle(.borderedProminent)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The recipients a consent given now names; a renewal says it is asked again for them.
    static func hosts(_ consent: MapStateConsent) -> String? {
        guard let tiles = consent.hosts.tiles else { return nil }
        if let places = consent.hosts.places {
            return say(consent.renewal ? .mapConsentRenewBoth : .mapConsentHosts, tiles, places)
        }
        return say(consent.renewal ? .mapConsentRenewTiles : .mapConsentHostsTiles, tiles)
    }
}

// MARK: Core's viewport and MapKit's region

enum MapMath {
    /// A core viewport (Web Mercator, 256 px tiles) as the region MapKit shows.
    static func region(_ v: Viewport) -> MKCoordinateRegion {
        let world = Mercator.shared.worldPx(zoom: v.zoom)
        let c = Mercator.shared.project(p: v.center)
        let top = Mercator.shared.unproject(p: MapPoint(x: c.x, y: max(0, c.y - v.height / 2 / world)))
        let bottom = Mercator.shared.unproject(p: MapPoint(x: c.x, y: min(1, c.y + v.height / 2 / world)))
        return MKCoordinateRegion(
            center: coordinate(v.center),
            span: MKCoordinateSpan(latitudeDelta: max(0.0001, top.lat - bottom.lat), longitudeDelta: min(360, max(0.0001, v.width / world * 360)))
        )
    }

    /// The zoom (core's level) a MapKit region of `width` points shows: what core clusters at.
    static func zoom(_ r: MKCoordinateRegion, width: Double) -> Double {
        log2(360 * max(width, 1) / (Mercator.shared.TILE_PX * max(r.span.longitudeDelta, 1e-9)))
    }

    static func viewport(_ r: MKCoordinateRegion, size: CGSize) -> Viewport {
        Viewport(center: LatLng(lat: r.center.latitude, lng: r.center.longitude), zoom: zoom(r, width: size.width), width: size.width, height: size.height)
    }

    static func coordinate(_ p: LatLng) -> CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: p.lat, longitude: p.lng) }

    /// The camera framing `points` in a map of `size` (core's `Viewport.fit`); the world view without a point.
    static func framing(_ points: [LatLng], size: CGSize) -> MapCameraPosition {
        let w = max(size.width, 200), h = max(size.height, 200)
        let v = Viewport.companion.fit(points: points, width: w, height: h, padding: Viewport.companion.FIT_PADDING_PX, maxZoom: Viewport.companion.FIT_MAX_ZOOM)
            ?? Viewport(center: MemoryLocation.companion.WORLD_CENTER, zoom: MemoryLocation.companion.WORLD_ZOOM, width: w, height: h)
        return .region(region(v))
    }
}

/// A colour core hands as ARGB.
extension Color {
    init(argb: Int64) {
        let v = UInt64(bitPattern: argb)
        self.init(.sRGB, red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255, opacity: Double((v >> 24) & 0xFF) / 255)
    }
}

// MARK: Settings → Map services (ADR 0143 D2)

/// This device's consent to the map services (the web's `MapsPanel.vue`): the recipients — on Apple the map's is Apple
/// Maps —, what they receive, the one switch; a consent given for other hosts named as such.
struct MapServicesSection: View {
    @Bindable var model: AppModel

    var body: some View {
        Section {
            if let maps = model.maps, maps.offered {
                let _ = model.mapsRevision
                LabeledContent(say(.settingsMapsTiles), value: maps.currentHosts.tiles ?? say(.settingsMapsNone))
                LabeledContent(say(.settingsMapsPlaces), value: maps.currentHosts.places ?? say(.settingsMapsNone))
                Text(say(.settingsMapsReceive)).font(.footnote).foregroundStyle(Role.muted)
                Toggle(say(.settingsMapsSwitch), isOn: Binding(get: { model.mapState is MapStateShown }, set: { $0 ? model.mapsOn() : model.mapsOff() }))
                if let given = maps.consentGivenFor, !(model.mapState is MapStateShown) {
                    if let tiles = given.tiles {
                        Text(say(.settingsMapsGivenFor, tiles, given.places ?? say(.settingsMapsNone))).font(.footnote).foregroundStyle(Role.muted)
                    } else {
                        Text(say(.settingsMapsGivenForUnrecorded)).font(.footnote).foregroundStyle(Role.muted)
                    }
                }
                Text(say(.settingsMapsSwitchHint)).font(.footnote).foregroundStyle(Role.muted)
            } else {
                Text(say(.settingsMapsNotOffered)).foregroundStyle(Role.muted)
            }
        } header: {
            Text(say(.settingsMaps))
        } footer: {
            Text(say(.settingsMapsSub))
        }
    }
}
