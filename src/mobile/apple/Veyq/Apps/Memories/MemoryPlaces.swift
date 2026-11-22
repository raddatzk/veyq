import MapKit
import SwiftUI
import VeyqCore

// A Memory's places on Apple (ADR 0143): its date and place under its header and their editors (D5), the Memories bar's
// Map tab and the pin map a person's profile shares (D6), a photo's map in its details (D7) — each behind core's map gate
// (`MapViews.swift`).

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
                    dateEditor = model.memories.dateEditor(gallery)
                }
            }
            if place != nil || editable {
                let name = place.map { $0.pending ? say(.memoryPlacePending) : $0.name } ?? say(.memoryPlaceSet)
                fact(name, symbol: "mappin.and.ellipse", muted: place == nil || place?.pending == true, help: say(.memoryPlaceEdit)) {
                    placeEditor = model.memories.placeEditor(gallery)
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

/// Every Memory of the application's list with a place, as a pin (core's `MemoriesMap`, unsealed on the device from the
/// meta the tree holds), on `MemoriesPinMap`; a pin opens its Memory.
struct MemoriesMapTab: View {
    @Bindable var model: AppModel
    @State private var pins: MemoriesMap?

    var body: some View {
        MemoriesPinMap(model: model, pins: pins) { model.memories.openPin($0) }
            .navigationTitle(Names.application(.memories))
            .task(id: model.listsRevision) { pins = await model.memories.memoriesMap() }
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
