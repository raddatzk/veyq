import MapKit
import SwiftUI
import UniformTypeIdentifiers
import VeyqCore

/// A Memory's or a chapter's **Map** view (the web's `MemoryTracks.vue`, core's `TrackList`; ADR 0143 D8): MapKit's map with
/// the photos' dots and the node's GPX tracks as `MapPolyline`s — each in its colour, dashed by its activity, the selected
/// one heavier —, and the list under it: a track's show/hide switch, its swatch, its activity above its name, its numbers.
/// A track's actions — edit, download, report, remove — are swipe actions and a context menu on iOS, a context menu on a
/// Mac (HIG); *Add GPX track* is the page's creating action, its labels asked in a sheet before anything is uploaded.
/// Offline the list shows and no map (decision 9). The tracks are read only while this view shows (core's `active`).
struct MemoryMapView<Switch: View>: View {
    let model: AppModel
    let tracks: TrackList
    let gallery: GalleryModel.State?
    /// The banner above the header (ADR 0144 decision 4); nil where the Memory has none.
    var banner: AnyView?
    let facts: AnyView?
    let viewSwitch: Switch
    @State private var position: MapCameraPosition = .automatic
    @State private var mapSize: CGSize = .zero
    @State private var framed = false
    @State private var editing: TrackList.Entry?
    @State private var reporting: TrackList.Entry?
    @State private var downloaded: URL?
    @State private var failed: String?

    private var state: TrackList.State? { model.tracksShown }
    private var points: [LatLng] { gallery?.gpsPoints ?? [] }

    var body: some View {
        List {
            if let banner { banner }
            NodeHeroRow()
            if let facts { facts.listRowSeparator(.hidden) }
            viewSwitch
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
            MapGate(model: model) { map }
                .frame(height: 320)
                .clipShape(RoundedRectangle(cornerRadius: Radius.card))
                .listRowInsets(EdgeInsets(top: 4, leading: 8, bottom: 4, trailing: 8))
                .listRowSeparator(.hidden)
            Section(say(.trackTitle)) {
                if let entries = state?.entries {
                    if entries.isEmpty {
                        Text(say(state?.rights.add == true ? .trackEmptyHint : .trackEmpty)).foregroundStyle(Role.muted)
                    }
                    ForEach(entries, id: \.mediaId) { row($0) }
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
        }
        .onAppear { tracks.active(on: true) }
        .onDisappear { tracks.active(on: false) }
        .sheet(isPresented: Binding(get: { !(state?.asking.isEmpty ?? true) }, set: { if !$0, !(state?.asking.isEmpty ?? true) { tracks.cancel() } })) {
            TrackLabelsSheet(asked: state?.asking ?? [], confirm: { labels in _ = tracks.confirm(labels: labels) }, cancel: { tracks.cancel() })
        }
        .sheet(item: $editing) { entry in
            TrackLabelsSheet(editing: entry) { label in
                Task { _ = try? await tracks.edit(mediaId: entry.mediaId, label: label) }
            }
        }
        .sheet(item: $reporting) { entry in
            ReportSheet(model: model, ref: tracks.gallery.node, make: { tracks.reportSheet(mediaId: entry.mediaId) })
        }
        // what an add or an edit left to say (a refused file, a conflict), once
        .alert(state?.said.map { say(notice: $0) } ?? failed ?? "", isPresented: Binding(get: { state?.said != nil || failed != nil }, set: { if !$0 { tracks.seen(); failed = nil } })) {
            Button(say(.ok), role: .cancel) {}
        }
        #if os(iOS)
        .fileMover(isPresented: Binding(get: { downloaded != nil }, set: { if !$0 { cleanUpDownload() } }), file: downloaded) { _ in }
        #endif
    }

    private var map: some View {
        let drawn = state?.drawn ?? []
        return Map(position: $position) {
            ForEach(Array(points.enumerated()), id: \.offset) { _, p in
                Annotation("", coordinate: MapMath.coordinate(p), anchor: .center) {
                    Circle().fill(Role.accent).stroke(.white, lineWidth: 1.5).frame(width: 9, height: 9)
                }
            }
            ForEach(drawn, id: \.mediaId) { entry in
                let selected = state?.selected == entry.mediaId
                ForEach(Array(entry.lines.enumerated()), id: \.offset) { _, line in
                    MapPolyline(coordinates: line.map(MapMath.coordinate))
                        .stroke(Color(argb: entry.color), style: Self.stroke(entry, selected: selected))
                }
            }
        }
        .mapControls {
            #if os(macOS)
            MapZoomStepper() // a Mac's map zooms by its stepper; iOS by the pinch
            #endif
            MapCompass()
            MapScaleView()
        }
        .onGeometryChange(for: CGSize.self, of: { $0.size }) { mapSize = $0; frame() }
        .onChange(of: drawn.count) { frame() }
    }

    /// The map frames the photos and the tracks read so far, once; a selected track frames itself.
    private func frame(selected: TrackList.Entry? = nil) {
        guard mapSize != .zero else { return }
        if let selected {
            position = MapMath.framing(selected.lines.flatMap { $0 }, size: mapSize)
            return
        }
        let all = points + (state?.drawn ?? []).flatMap { $0.lines.flatMap { $0 } }
        guard !framed, !all.isEmpty else { return }
        framed = (state?.entries.map { e in e.allSatisfy { $0.status != .loading } } ?? false)
        position = MapMath.framing(all, size: mapSize)
    }

    /// A track's line: 4 points (6 selected), dashed by its activity (core's `TrackLabel.dash`).
    static func stroke(_ entry: TrackList.Entry, selected: Bool) -> StrokeStyle {
        StrokeStyle(lineWidth: selected ? 6 : 4, lineCap: .round, lineJoin: .round, dash: (entry.dash ?? []).map { CGFloat(truncating: $0) })
    }

    private func name(_ entry: TrackList.Entry) -> String { entry.name ?? say(.trackUnnamed, Int(entry.number)) }

    private func row(_ entry: TrackList.Entry) -> some View {
        let name = name(entry)
        let rights = state?.rights
        let shown = !(state?.hidden.contains(entry.mediaId) ?? false)
        return HStack(spacing: 12) {
            Toggle(say(.trackShow, name), isOn: Binding(get: { shown }, set: { tracks.setShown(mediaId: entry.mediaId, shown: $0) }))
                .labelsHidden()
                .toggleStyle(.switch)
                .controlSize(.mini)
            TrackSwatch(color: Color(argb: entry.color), dash: entry.dash)
            Button {
                let selecting = state?.selected != entry.mediaId
                tracks.select(mediaId: selecting ? entry.mediaId : nil)
                if selecting { frame(selected: entry) }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    if let activity = entry.activity {
                        Text(Self.activityWord(activity)).font(.caption).foregroundStyle(Role.muted)
                    }
                    Text(name).fontWeight(state?.selected == entry.mediaId ? .semibold : .regular)
                    switch entry.status {
                    case .loading: Text(say(.trackLoading)).font(.caption).foregroundStyle(Role.muted)
                    case .failed: Text(say(entry.blocked ? .memoryBlocked : .trackUnreadable)).font(.caption).foregroundStyle(Role.muted)
                    default:
                        if !entry.facts.isEmpty { Text(Self.facts(entry.facts)).font(.caption).monospacedDigit().foregroundStyle(Role.muted) }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        #if os(iOS)
        .swipeActions(edge: .trailing) {
            if tracks.removes(mediaId: entry.mediaId) {
                Button(role: .destructive) { remove(entry) } label: { Label(say(.trackRemove), systemImage: "trash") }
            }
            if rights?.edit == true {
                Button { editing = entry } label: { Label(say(.trackEdit), systemImage: "pencil") }
            }
        }
        #endif
        .contextMenu {
            if rights?.edit == true {
                Button { editing = entry } label: { Label(say(.trackEdit), systemImage: "pencil") }
            }
            if rights?.download == true, !entry.blocked {
                Button { Task { await download(entry) } } label: { Label(say(.trackDownload), systemImage: "arrow.down.circle") }
            }
            if rights?.report == true {
                Button { reporting = entry } label: { Label(say(.reportAction), systemImage: "flag") }
            }
            if tracks.removes(mediaId: entry.mediaId) {
                Divider()
                Button(role: .destructive) { remove(entry) } label: { Label(say(.trackRemove), systemImage: "trash") }
            }
        }
    }

    /// The numbers beside a track (core's `TrackFacts`): a length in the device's locale, the rest in the catalog's words.
    static func facts(_ facts: [TrackFactsFact]) -> String {
        facts.map { fact -> String in
            if let d = fact as? TrackFactsFactDistance {
                let m = Measurement(value: d.value, unit: d.kilometres ? UnitLength.kilometers : UnitLength.meters)
                return m.formatted(.measurement(width: .abbreviated, usage: .asProvided, numberFormatStyle: .number.precision(.fractionLength(d.kilometres ? 1 : 0))))
            }
            return (fact as? TrackFactsFactWords).map { say(notice: $0.notice) } ?? ""
        }
        .joined(separator: " · ")
    }

    /// An activity's words, by its sealed token (core's `TrackLabel.ACTIVITIES`).
    static func activityWord(_ token: String) -> String {
        switch token {
        case "hiking": say(.trackActivityHiking)
        case "walking": say(.trackActivityWalking)
        case "running": say(.trackActivityRunning)
        case "cycling": say(.trackActivityCycling)
        case "mountain-biking": say(.trackActivityMountainBiking)
        case "road-cycling": say(.trackActivityRoadCycling)
        case "gravel-cycling": say(.trackActivityGravelCycling)
        case "skiing": say(.trackActivitySkiing)
        case "water": say(.trackActivityWater)
        default: say(.trackActivityOther)
        }
    }

    private func remove(_ entry: TrackList.Entry) {
        Task {
            if let said = try? await tracks.remove(mediaId: entry.mediaId) { failed = say(notice: said) }
        }
    }

    /// The track's GPX saved under its file's name: a Mac where its save panel says, iOS through the system's save sheet.
    private func download(_ entry: TrackList.Entry) async {
        let fileName = entry.fileName ?? "\(name(entry)).gpx"
        #if os(macOS)
        let panel = NSSavePanel()
        panel.nameFieldStringValue = fileName
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let end = try? await AppleMemories.shared.saveTrack(tracks: tracks, mediaId: entry.mediaId, path: url.path)
        if end != .saved { failed = say(.trackDownloadFailed) }
        #else
        guard let dir = AppModel.downloadDir() else { return }
        let url = dir.appendingPathComponent(fileName)
        let end = try? await AppleMemories.shared.saveTrack(tracks: tracks, mediaId: entry.mediaId, path: url.path)
        if end == .saved {
            downloaded = url
        } else {
            try? FileManager.default.removeItem(at: dir)
            failed = say(.trackDownloadFailed)
        }
        #endif
    }

    private func cleanUpDownload() {
        if let url = downloaded { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        downloaded = nil
    }
}

extension TrackList.Entry: @retroactive Identifiable {
    public var id: String { mediaId }
}

/// A track's colour with its activity's dash, as its line is drawn.
struct TrackSwatch: View {
    let color: Color
    let dash: [KotlinInt]?

    var body: some View {
        Canvas { context, size in
            var path = Path()
            path.move(to: CGPoint(x: 0, y: size.height / 2))
            path.addLine(to: CGPoint(x: size.width, y: size.height / 2))
            context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: 4, lineCap: .round, dash: (dash ?? []).map { CGFloat(truncating: $0) }))
        }
        .frame(width: 28, height: 10)
        .accessibilityHidden(true)
    }
}

/// The GPX type a picker offers: `.gpx` files, of whatever type the system gives them.
let trackPickTypes: [UTType] = [UTType(filenameExtension: "gpx") ?? .xml]

/// A track's name and activity: asked for each accepted file before anything is uploaded (*Cancel* uploads nothing, A9),
/// or edited on one track.
struct TrackLabelsSheet: View {
    private struct Field: Identifiable {
        let id: Int
        let title: String
        let hint: String?
        var name: String
        var activity: String?
    }

    @State private var fields: [Field]
    private let adding: Bool
    private let done: ([TrackLabel.Label]) -> Void
    private let cancelled: () -> Void
    @Environment(\.dismiss) private var dismiss

    init(asked: [TrackList.Asked], confirm: @escaping ([TrackLabel.Label]) -> Void, cancel: @escaping () -> Void) {
        _fields = State(initialValue: asked.enumerated().map { i, a in Field(id: i, title: a.fileName, hint: a.fileTrackName, name: "", activity: nil) })
        adding = true
        done = confirm
        cancelled = cancel
    }

    init(editing entry: TrackList.Entry, save: @escaping (TrackLabel.Label) -> Void) {
        _fields = State(initialValue: [Field(id: 0, title: entry.fileName ?? "", hint: entry.fileTrackName, name: entry.ownName ?? "", activity: entry.activity)])
        adding = false
        done = { labels in if let l = labels.first { save(l) } }
        cancelled = {}
    }

    var body: some View {
        NavigationStack {
            Form {
                if adding { Text(say(.trackAddHint)).font(.footnote).foregroundStyle(Role.muted) }
                ForEach($fields) { $field in
                    Section(field.title) {
                        TextField(say(.trackNameLabel), text: $field.name, prompt: field.hint.map { Text($0) })
                        Picker(say(.trackActivityLabel), selection: $field.activity) {
                            Text(say(.trackActivityNone)).tag(String?.none)
                            ForEach(TrackLabel.shared.ACTIVITIES, id: \.self) { token in
                                Text(MemoryMapView<EmptyView>.activityWord(token)).tag(Optional(token))
                            }
                        }
                        if !adding { Text(say(.trackNameHint)).font(.footnote).foregroundStyle(Role.muted) }
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle(adding ? say(fields.count == 1 ? .trackAddTitle : .trackAddTitleMany) : say(.trackEditTitle))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(say(.signinCancel)) {
                        cancelled()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(adding ? say(fields.count == 1 ? .trackAddConfirm : .trackAddConfirmMany) : say(.entrySave)) {
                        done(fields.map { f in
                            let n = f.name.trimmingCharacters(in: .whitespacesAndNewlines)
                            return TrackLabel.Label(name: n.isEmpty ? nil : n, activity: f.activity)
                        })
                        dismiss()
                    }
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 360)
        #endif
    }
}
