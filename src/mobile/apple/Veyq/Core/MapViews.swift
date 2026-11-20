import MapKit
import SwiftUI
import VeyqCore

// A Memory's places and maps on Apple (ADR 0143): MapKit draws every map (decision 1) — the Memories bar's Map tab, the
// place editor's, a photo's in its details and a Memory's Map view with its tracks — under core's map services
// (`MapServices`, decision 2). A `Map` is mounted only while core's state is `Shown`: before the consent the offer stands
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

// MARK: A Memory's date and place under its header (ADR 0143 D5)

/// How a Memory's date reads: a day, a range, "Since …", as the device writes dates; nil without one.
enum MemoryDateText {
    private static let iso: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    static func date(_ day: String?) -> Date? { day.flatMap { iso.date(from: $0) } }
    static func day(_ date: Date) -> String { iso.string(from: date) }

    static func text(_ d: MemoryDate) -> String? {
        guard let from = date(d.from) else { return nil }
        if d.mode == .ongoing { return say(.memoryDateSince, from.formatted(date: .abbreviated, time: .omitted)) }
        guard let to = date(d.to), to > from else { return from.formatted(date: .abbreviated, time: .omitted) }
        let f = DateIntervalFormatter()
        f.dateStyle = .medium
        f.timeStyle = .none
        return f.string(from: from, to: to)
    }

    /// A Memories row's second line: its range, or nothing.
    static func row(_ d: MemoryDate) -> String? { text(d) }
}

/// The facts line under a Memory's or a chapter's header: its date and its place, each a button to its editor where
/// whoever looks may edit its meta and online (core's `dateEditor`/`placeEditor` answer nil otherwise).
struct MemoryFactsLine: View {
    let model: AppModel
    let gallery: GalleryModel
    let state: GalleryModel.State
    @State private var dateEditor: DateEditor?
    @State private var placeEditor: PlaceEditor?

    private var editable: Bool { state.rights.editMeta && state.offline == nil }

    var body: some View {
        let date = MemoryDateText.text(state.date)
        let place = state.facts?.location
        HStack(spacing: 16) {
            if date != nil || editable {
                fact(date ?? say(.memoryDateNone), symbol: "calendar", muted: date == nil, help: say(.memoryDateEdit)) {
                    dateEditor = model.dateEditor(gallery)
                }
            }
            if place != nil || editable {
                let name = place.map { $0.pending ? say(.memoryPlacePending) : $0.name } ?? say(.memoryPlaceSet)
                fact(name, symbol: "mappin.and.ellipse", muted: place == nil || place?.pending == true, help: say(.memoryPlaceEdit)) {
                    placeEditor = model.placeEditor(gallery)
                }
            }
            Spacer(minLength: 0)
        }
        .font(.subheadline)
        .sheet(item: Binding(get: { dateEditor.map(EditorOf.init) }, set: { if $0 == nil { dateEditor = nil } })) { of in
            DateEditorSheet(editor: of.editor as! DateEditor)
        }
        .sheet(item: Binding(get: { placeEditor.map(EditorOf.init) }, set: { if $0 == nil { placeEditor?.close(); placeEditor = nil } })) { of in
            PlaceEditorSheet(model: model, editor: of.editor as! PlaceEditor)
        }
    }

    @ViewBuilder private func fact(_ text: String, symbol: String, muted: Bool, help: String, edit: @escaping () -> Void) -> some View {
        if editable {
            Button(action: edit) { Label(text, systemImage: symbol) }
                .buttonStyle(.borderless)
                .foregroundStyle(muted ? Role.muted : Role.text)
                .help(help)
                .accessibilityHint(help)
        } else {
            Label(text, systemImage: symbol).foregroundStyle(muted ? Role.muted : Role.text)
        }
    }
}

/// An editor shown as a sheet, by its identity.
private struct EditorOf: Identifiable {
    let editor: AnyObject
    var id: ObjectIdentifier { ObjectIdentifier(editor) }
}

/// A Memory's date editor (core's `DateEditor`, the web's `MemoryDateEditor.vue`): the four modes with the system's date
/// pickers; `auto` previews the range the photos give. The fields are read back from core right after each change (§ Traps).
struct DateEditorSheet: View {
    let editor: DateEditor
    @State private var state: DateEditor.State
    @State private var saving = false
    @State private var failed = false
    @Environment(\.dismiss) private var dismiss

    init(editor: DateEditor) {
        self.editor = editor
        _state = State(initialValue: editor.current)
    }

    var body: some View {
        NavigationStack {
            Form {
                Picker(say(.memoryDateTitle), selection: Binding(get: { state.mode }, set: { editor.mode(mode: $0); state = editor.current })) {
                    Text(say(.memoryDateModeAuto)).tag(MemoryDate.Mode.auto_)
                    Text(say(.memoryDateModeSingle)).tag(MemoryDate.Mode.single)
                    Text(say(.memoryDateModeRange)).tag(MemoryDate.Mode.range)
                    Text(say(.memoryDateModeOngoing)).tag(MemoryDate.Mode.ongoing)
                }
                switch state.mode {
                case .auto_:
                    if let range = MemoryDateText.text(editor.autoRange) {
                        LabeledContent(say(.memoryDateModeAuto), value: range)
                        Text(say(.memoryDateAutoHint)).font(.footnote).foregroundStyle(Role.muted)
                    } else {
                        Text(say(.memoryDateAutoEmpty)).foregroundStyle(Role.muted)
                    }
                case .single:
                    day(say(.memoryDateDay), state.from) { editor.from(day: $0) }
                case .range:
                    day(say(.memoryDateFrom), state.from) { editor.from(day: $0) }
                    day(say(.memoryDateTo), state.to) { editor.to(day: $0) }
                    if state.rangeInvalid { Text(say(.memoryDateInvalidRange)).foregroundStyle(Role.danger) }
                default:
                    day(say(.memoryDateStart), state.from) { editor.from(day: $0) }
                }
                if failed { Text(say(.nodeSaveFailed)).foregroundStyle(Role.danger) }
            }
            .formStyle(.grouped)
            .navigationTitle(say(.memoryDateTitle))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(say(.entrySave)) { save() }.disabled(!state.canSave || saving)
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 320)
        #endif
    }

    /// A day's picker: an empty field takes today when first touched, as the system's own pickers start.
    private func day(_ label: String, _ value: String, set: @escaping (String) -> Void) -> some View {
        DatePicker(label, selection: Binding(
            get: { MemoryDateText.date(value) ?? Date() },
            set: { set(MemoryDateText.day($0)); state = editor.current }
        ), displayedComponents: .date)
        .onAppear { if value.isEmpty { set(MemoryDateText.day(Date())); state = editor.current } }
    }

    private func save() {
        saving = true
        failed = false
        Task {
            let r = try? await editor.save()
            saving = false
            state = editor.current
            if r is EditorSaveSaved { dismiss() } else if r is EditorSaveReopen { failed = false } else { failed = true }
        }
    }
}

/// A Memory's place editor (core's `PlaceEditor`, the web's `MemoryLocationEditor.vue`): MapKit's map panned under a fixed
/// centre pin — no drag, no search —, the level picker from the operator's geocoder, the typed name, *Follow the photos*
/// and *Remove location*. Without the map (none served, no consent, offline) the name field alone, the consent's offer
/// where the map would stand (A4, A6). A member's pan is told from the app's own framing by MapKit's `positionedByUser`.
struct PlaceEditorSheet: View {
    let model: AppModel
    let editor: PlaceEditor
    @State private var state: PlaceEditor.State
    @State private var position: MapCameraPosition = .automatic
    @State private var mapSize: CGSize = .zero
    @State private var watch: AppleMemories.Watch?
    @State private var saving = false
    @State private var failed = false
    @Environment(\.dismiss) private var dismiss

    init(model: AppModel, editor: PlaceEditor) {
        self.model = model
        self.editor = editor
        _state = State(initialValue: editor.current)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    MapGate(model: model) { map }
                        .frame(height: 280)
                        .listRowInsets(EdgeInsets())
                    if state.withMap {
                        follow
                    }
                }
                Section(say(.memoryPlaceName)) {
                    if state.showLevelPicker {
                        Picker(say(.memoryPlaceName), selection: Binding(get: { state.chosenLevel }, set: { if let k = $0 { editor.pickLevel(key: k) } })) {
                            ForEach(state.levels, id: \.key) { level in
                                Text(level.name).tag(Optional(level.key))
                            }
                        }
                        Button(say(.memoryPlaceNameOwn)) { editor.toggleNameMode() }
                    } else {
                        TextField(say(.memoryPlaceName), text: Binding(get: { state.name }, set: { editor.typeName(text: $0); state = editor.current }), prompt: Text(say(.memoryPlaceNamePlaceholder)))
                        if !state.levels.isEmpty {
                            Button(say(.memoryPlaceNameFromMap)) { editor.toggleNameMode() }
                        }
                    }
                    if !state.withMap { Text(say(.memoryPlaceNameOnly)).font(.footnote).foregroundStyle(Role.muted) }
                }
                Section {
                    Button(say(.memoryPlaceRemove), role: .destructive) { remove() }
                }
                if failed { Text(say(.nodeSaveFailed)).foregroundStyle(Role.danger) }
            }
            .formStyle(.grouped)
            .navigationTitle(say(.memoryPlaceTitle))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(say(.entrySave)) { save() }.disabled(!state.canSave || saving)
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 480, minHeight: 600)
        #endif
        .onAppear {
            let id = ObjectIdentifier(editor)
            watch = AppleMemories.shared.watchPlace(editor: editor) { @Sendable s in
                nonisolated(unsafe) let s = s
                Task { @MainActor in if ObjectIdentifier(editor) == id { state = s } }
            }
            editor.open()
        }
        .onDisappear { watch?.stop() }
        // the consent given in the editor: the map stands where the offer stood
        .onChange(of: model.mapsRevision) { editor.mapsTurnedOn() }
    }

    private var map: some View {
        Map(position: $position, interactionModes: [.pan, .zoom]) {
            ForEach(Array(editor.points.enumerated()), id: \.offset) { _, p in
                Annotation("", coordinate: MapMath.coordinate(p), anchor: .center) {
                    Circle().fill(Role.accent).stroke(.white, lineWidth: 1.5).frame(width: 8, height: 8)
                }
            }
        }
        .mapControls {
            #if os(macOS)
            MapZoomStepper() // a Mac's map zooms by its stepper; iOS by the pinch
            #endif
            MapCompass()
        }
        .overlay {
            // the fixed centre pin: the map moves under it; greyed while no point is set (E3)
            VStack(spacing: 4) {
                Image(systemName: "mappin")
                    .font(.title)
                    .foregroundStyle(state.derived == nil ? Role.muted : Role.danger)
                    .offset(y: -14)
                if state.derived == nil {
                    Text(say(.memoryPlaceMoveToSet)).font(.caption).padding(4).background(.regularMaterial, in: Capsule())
                }
            }
            .allowsHitTesting(false)
        }
        .onGeometryChange(for: CGSize.self, of: { $0.size }) { size in
            let first = mapSize == .zero
            mapSize = size
            if first { position = .region(MapMath.region(editor.initialView(width: size.width, height: size.height))) }
        }
        .onMapCameraChange(frequency: .onEnd) { context in
            let c = context.region.center
            editor.frame(at: LatLng(lat: c.latitude, lng: c.longitude), user: position.positionedByUser)
            state = editor.current
        }
    }

    @ViewBuilder private var follow: some View {
        if state.derivedMode == .auto_ {
            Label(say(.memoryPlaceFollowsPhotos), systemImage: "photo.on.rectangle").foregroundStyle(Role.muted)
        } else {
            HStack {
                Label(say(.memoryPlaceCustom), systemImage: "mappin").foregroundStyle(Role.muted)
                Spacer()
                if let m = editor.medoid {
                    Button(say(.memoryPlaceFollowAgain)) {
                        editor.followAgain()
                        state = editor.current
                        position = .region(MapMath.region(Viewport(center: m, zoom: PlaceEditor.companion.DEFAULT_ZOOM, width: mapSize.width, height: mapSize.height)))
                    }
                }
            }
        }
    }

    private func save() {
        saving = true
        Task {
            let r = try? await editor.save()
            saving = false
            if r is EditorSaveSaved { dismiss() } else if r is EditorSaveReopen { dismiss() } else { failed = true }
        }
    }

    private func remove() {
        Task {
            let r = try? await editor.remove()
            if r is EditorSaveSaved { dismiss() } else { failed = true }
        }
    }
}

// MARK: The Memories bar's Map tab (ADR 0143 D6)

/// Every Memory of the application's list with a place, as a pin (core's `MemoriesMap`, unsealed on the device from the
/// meta the tree holds), on `MemoriesPinMap`; a pin opens its Memory.
struct MemoriesMapTab: View {
    @Bindable var model: AppModel
    @State private var pins: MemoriesMap?

    var body: some View {
        MemoriesPinMap(model: model, pins: pins) { model.openPin($0) }
            .navigationTitle(Names.application(.memories))
            .task(id: model.listsRevision) { pins = await model.memoriesMap() }
    }
}

/// Memories as pins on MapKit's map behind the consent's gate: the Map tab's and a person's profile's (ADR 0144 decision
/// 13), the same map either way. Grouped as core groups them at MapKit's zoom; a group's tap frames its pins, at the top
/// zoom lists them; a pin `open`s its Memory. Pins carry the Memory's cover where its row has one; `pins` nil while they read.
struct MemoriesPinMap: View {
    let model: AppModel
    let pins: MemoriesMap?
    let open: (MemoriesMap.Pin) -> Void
    @State private var position: MapCameraPosition = .automatic
    @State private var region: MKCoordinateRegion?
    @State private var size: CGSize = .zero
    @State private var listed: [MemoriesMap.Pin]?
    @Environment(\.covers) private var covers

    var body: some View {
        MapGate(model: model) { map }
            .sheet(item: Binding(get: { listed.map(PinList.init) }, set: { if $0 == nil { listed = nil } })) { list in
                NavigationStack {
                    List(list.pins, id: \.nodeId) { pin in
                        Button(pin.name) { listed = nil; open(pin) }
                    }
                    .navigationTitle(say(.mapClusterTitle, list.pins.count))
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button(say(.signinCancel)) { listed = nil } } }
                }
                #if os(macOS)
                .frame(minWidth: 360, minHeight: 320)
                #endif
            }
    }

    private var map: some View {
        let zoom = region.map { MapMath.zoom($0, width: size.width) } ?? MemoryLocation.companion.WORLD_ZOOM
        let groups = (pins?.clusters(zoom: zoom) ?? []).map(PinGroup.init)
        return Map(position: $position) {
            ForEach(Array(groups.enumerated()), id: \.offset) { _, group in
                Annotation(group.title, coordinate: MapMath.coordinate(group.cluster.center)) {
                    Button { tap(group) } label: { marker(group) }
                        .buttonStyle(.plain)
                        .accessibilityLabel(group.label)
                }
            }
        }
        .mapControls {
            #if os(macOS)
            MapZoomStepper() // a Mac's map zooms by its stepper; iOS by the pinch
            #endif
            MapCompass()
        }
        .overlay {
            if let pins, pins.pins.isEmpty {
                ContentUnavailableView(say(.memoriesMapEmpty), systemImage: "map", description: Text(say(.memoriesMapEmptyHint)))
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Radius.card))
                    .padding()
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .onGeometryChange(for: CGSize.self, of: { $0.size }) { size = $0 }
        .onMapCameraChange(frequency: .onEnd) { region = $0.region }
        .onChange(of: pins?.pins.count) {
            guard let pins, region == nil else { return }
            position = MapMath.framing(pins.pins.map(\.at), size: size)
        }
    }

    @ViewBuilder private func marker(_ group: PinGroup) -> some View {
        if let pin = group.single, pin.hasCover, let cover = covers[pin.nodeId] {
            Image(decorative: cover, scale: 1)
                .resizable().scaledToFill()
                .frame(width: 40, height: 40)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(.white, lineWidth: 2))
                .shadow(radius: 2)
        } else if group.single != nil {
            Image(systemName: "photo.on.rectangle.angled")
                .foregroundStyle(.white)
                .frame(width: 32, height: 32)
                .background(Role.accent, in: Circle())
                .overlay(Circle().stroke(.white, lineWidth: 2))
        } else {
            Text(group.members.count, format: .number)
                .font(.callout.bold()).monospacedDigit()
                .foregroundStyle(.white)
                .frame(minWidth: 32, minHeight: 32)
                .background(Role.accent, in: Circle())
                .overlay(Circle().stroke(.white, lineWidth: 2))
        }
    }

    private func tap(_ group: PinGroup) {
        guard let pins else { return }
        let view = region.map { MapMath.viewport($0, size: size) }
            ?? Viewport(center: group.cluster.center, zoom: MemoryLocation.companion.WORLD_ZOOM, width: size.width, height: size.height)
        switch pins.tap(cluster: group.cluster, view: view, maxZoom: Double(Mercator.shared.MAX_ZOOM)) {
        case let tapped as MemoriesMapTapOpen: open(tapped.pin)
        case let frame as MemoriesMapTapFrame: withAnimation { position = .region(MapMath.region(frame.view)) }
        case let list as MemoriesMapTapList: listed = list.pins
        default: break
        }
    }
}

/// One of core's clusters with its pins typed (a Kotlin generic reaches Swift untyped).
private struct PinGroup {
    let cluster: Cluster<MemoriesMap.Pin>
    let members: [MemoriesMap.Pin]

    init(_ cluster: Cluster<MemoriesMap.Pin>) {
        self.cluster = cluster
        members = cluster.members.compactMap { $0 as? MemoriesMap.Pin }
    }

    var single: MemoriesMap.Pin? { members.count == 1 ? members[0] : nil }
    /// MapKit's title under a lone pin: its Memory's name; a group shows its count instead.
    var title: String { single?.name ?? "" }
    var label: String { single?.name ?? say(.mapClusterTitle, members.count) }
}

private struct PinList: Identifiable {
    let pins: [MemoriesMap.Pin]
    var id: String { pins.map(\.nodeId).joined(separator: ",") }
}

// MARK: A photo's map in its details (ADR 0143 D7)

/// The photo's position on a small map at core's zoom, the direction it faced where its EXIF names one; *Turn map services
/// off* on it (the gate's). Its place name is resolved once ever by core, as the details show.
struct PhotoMapRow: View {
    let model: AppModel
    let map: PhotoPlace.PhotoMap

    var body: some View {
        MapGate(model: model) {
            Map(initialPosition: .region(MapMath.region(Viewport(center: map.at, zoom: map.zoom, width: 320, height: 180))), interactionModes: []) {
                Annotation("", coordinate: MapMath.coordinate(map.at), anchor: .center) {
                    if let direction = map.directionTrue {
                        Image(systemName: "location.north.fill")
                            .rotationEffect(.degrees(Double(truncating: direction)))
                            .foregroundStyle(Role.accent)
                            .font(.title3)
                    } else {
                        Circle().fill(Role.accent).stroke(.white, lineWidth: 2).frame(width: 12, height: 12)
                    }
                }
            }
        }
        .frame(height: 180)
        .clipShape(RoundedRectangle(cornerRadius: Radius.card))
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
