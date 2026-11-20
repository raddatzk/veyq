import SwiftUI
import VeyqCore

/// Memories' section of a person's profile (ADR 0144 decision 11; the web's `PersonProfile.vue`'s Memories part) as it
/// registers (`MemoriesUi`'s `personSection`): the page under core's frame — "Memories with {name}", the person's Memories
/// as List | Tiles, or the Map taking the page below the frame. Its section (core's `PersonMemories`, read off the main
/// thread) is held while the profile shows; its List | Tiles | Map choice is the account's one view choice on this device
/// (ADR 0149 decision 8), Tiles before any.
struct PersonMemoriesPage: View {
    let model: AppModel
    let profile: PersonProfile
    let frame: PersonFrame
    @State private var section: PersonSection?

    var body: some View {
        Group {
            if let section, section.state.value.view == .map {
                // the map takes the page below the frame; the list scrolls with it
                VStack(spacing: 0) {
                    frame.frame.padding(.bottom, 8)
                    PersonMemoriesHead(model: model, profile: profile, section: section).padding(.horizontal)
                    MemoriesPinMap(model: model, pins: section.state.value.map) { section.memories.open(pin: $0) }
                }
            } else {
                List {
                    frame.frame
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                    if let safety = frame.safety { safety }
                    if let section {
                        Section {
                            PersonMemoriesRows(model: model, section: section)
                        } header: {
                            PersonMemoriesHead(model: model, profile: profile, section: section)
                        }
                    }
                }
            }
        }
        .task(id: profile.userId) {
            section?.close()
            section = model.personMemories(profile.userId)
        }
        .onDisappear {
            section?.close()
            section = nil
        }
    }
}

extension AppModel {
    /// Memories' section of person `userId`'s profile (core's `PersonMemories`), read on its open.
    func personMemories(_ userId: String) -> PersonSection? {
        shellModel.map { PersonSection($0.personMemories(userId: userId)) }
    }
}

/// Memories' section of a profile (core's `PersonMemories`) with its state on the main actor; read once on its open.
@MainActor
final class PersonSection {
    let memories: PersonMemories
    let state: CoreState<PersonMemories.State>
    private var load: AppleMemories.Watch?

    init(_ memories: PersonMemories) {
        self.memories = memories
        // core calls the watch on its threads: @Sendable, never this main-actor class's isolation (src/mobile/CLAUDE.md
        // § Traps; a profile opened crashed on libdispatch's main-queue assertion, 2026-11-18)
        state = CoreState({ memories.current }) { changed in AppleMemories.shared.watch(flow: memories.state) { @Sendable _ in changed() } }
        reload()
    }

    /// The person's Memories read (again: *Try again*), off the main thread — the index unseals each Memory's meta.
    func reload() {
        load?.stop()
        load = AppleMemories.shared.loadPerson(section: memories)
    }

    func view(_ view: PersonMemories.View) {
        memories.view(view: view)
        state.refresh()
    }

    func close() {
        load?.stop()
        state.stop()
    }
}

/// "Memories with {name}", the List | Tiles | Map switch once something is listed (a segmented control, as the system's own
/// apps switch a page between views of the same thing), and the partial list said.
private struct PersonMemoriesHead: View {
    let model: AppModel
    let profile: PersonProfile
    let section: PersonSection

    private var s: PersonMemories.State { section.state.value }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(profile.name.map { say(.memoryPeopleWithName, $0) } ?? say(.memoryPeopleWith))
                .font(.headline)
                .foregroundStyle(Role.text)
                .textCase(nil)
            if s.offersViews {
                Picker(say(.memoryPeopleWith), selection: Binding(get: { s.view }, set: { section.view($0); model.viewChosen() })) {
                    Text(say(.nodeViewList)).tag(PersonMemories.View.list)
                    Text(say(.nodeViewTiles)).tag(PersonMemories.View.tiles)
                    Text(say(.memoryViewMap)).tag(PersonMemories.View.map)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
            }
            if s.partial {
                Text(say(.memoryPeoplePartial)).font(.footnote).foregroundStyle(Role.muted).textCase(nil)
            }
        }
        .padding(.bottom, 4)
    }
}

/// The section's states and rows: loading, failed with *Try again*, empty, the person's Memories newest first (core's order).
private struct PersonMemoriesRows: View {
    let model: AppModel
    let section: PersonSection
    @Environment(\.covers) private var covers
    @State private var width: CGFloat = 0

    private var s: PersonMemories.State { section.state.value }

    var body: some View {
        if s.loading && s.rows.isEmpty {
            HStack(spacing: 8) {
                ProgressView()
                Text(say(.memoryPeopleLoading)).foregroundStyle(Role.muted)
            }
        } else if s.failed {
            Text(say(.memoryPeopleError)).foregroundStyle(Role.danger)
            Button(say(.memoryPeopleRetry)) { section.reload() }
        } else if s.empty {
            Text(say(.memoryPeopleEmpty)).foregroundStyle(Role.muted)
        } else if s.view == .tiles {
            // the tiles in one row of the list, its width the row's (ADR 0149 decision 8)
            NodeTiles(items: s.rows.map { row in
                NodeTileItem(id: row.ref.nodeId, name: row.ref.name, details: PersonMemoryRow.details(row), cover: covers[row.ref.nodeId], symbol: Names.kindSymbol(row.ref.kind)) {
                    section.memories.open(row: row)
                }
            }, width: width)
            .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { width = $0 }
            .listRowBackground(Color.clear)
        } else {
            ForEach(s.rows, id: \.ref.nodeId) { row in
                PersonMemoryRow(row: row) { section.memories.open(row: row) }
            }
        }
    }
}

/// One of the person's Memories as the level draws a row: its cover, its name, its date, place and group.
private struct PersonMemoryRow: View {
    let row: PersonMemories.Row
    let onOpen: () -> Void
    @Environment(\.covers) private var covers

    /// Its date, place and group, as its second line says them.
    static func details(_ row: PersonMemories.Row) -> String {
        [row.date.flatMap { MemoryDateText.text($0) }, row.placeName.flatMap { $0.isEmpty ? nil : $0 }, row.group.map { Names.spaceName($0) }]
            .compactMap { $0 }.joined(separator: " · ")
    }

    var body: some View {
        let details = Self.details(row)
        Button(action: onOpen) {
            HStack(spacing: 12) {
                if let cover = covers[row.ref.nodeId] {
                    Image(decorative: cover, scale: 1).resizable().scaledToFill()
                        .frame(width: 34, height: 34)
                        .clipShape(RoundedRectangle(cornerRadius: Radius.row, style: .continuous))
                } else {
                    Tile(symbol: Names.kindSymbol(row.ref.kind))
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.ref.name).fontWeight(.bold).foregroundStyle(Role.text)
                    if !details.isEmpty { Text(details).font(.caption).foregroundStyle(Role.muted) }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.forward").font(.footnote.weight(.semibold)).foregroundStyle(Role.muted)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
