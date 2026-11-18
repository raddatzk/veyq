import SwiftUI
import VeyqCore

/// A person's profile (ADR 0144 decision 11; the web's `PersonProfile.vue`), core's frame — their picture at its largest,
/// their name, `~username` under a real name, a former member said so — and then each application's section, Memories'
/// "Memories with {name}" (`PersonMemoriesSection`). Drawn where the shell's place is (core's `Shell.person`, over what was
/// on screen: back returns there), its title the person's name; no bar of its own.
struct PersonScreen: View {
    @Bindable var model: AppModel
    let userId: String
    /// Memories' section while the profile shows (core's `PersonMemories`, read off the main thread); its List | Map choice
    /// is not stored: it opens on the list.
    @State private var section: PersonSection?

    private var profile: PersonProfile? { model.profile(userId) }

    var body: some View {
        Group {
            if let profile {
                if let section, section.state.value.view == .map {
                    // the map takes the page below the frame; the list scrolls with it
                    VStack(spacing: 0) {
                        frame(profile).padding(.bottom, 8)
                        PersonMemoriesHead(profile: profile, section: section).padding(.horizontal)
                        MemoriesPinMap(model: model, pins: section.state.value.map) { section.memories.open(pin: $0) }
                    }
                } else {
                    List {
                        frame(profile)
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                        if let section {
                            Section {
                                PersonMemoriesRows(model: model, section: section)
                            } header: {
                                PersonMemoriesHead(profile: profile, section: section)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(profile.map(Self.name) ?? "")
        .task(id: userId) {
            section?.close()
            section = profile.flatMap { $0.sections.contains(.memories) ? model.personMemories(userId) : nil }
        }
        .onDisappear {
            section?.close()
            section = nil
        }
    }

    /// The name a profile carries: the directory's, else the former or the unknown member's words.
    static func name(_ profile: PersonProfile) -> String {
        profile.name ?? say(profile.former ? .memoryParticipantsFormer : .peopleUnknownMember)
    }

    /// Core's frame: the picture at its largest, the name, `~username` under a real name, a former member's hint.
    private func frame(_ profile: PersonProfile) -> some View {
        VStack(spacing: 6) {
            PersonAvatar(name: Self.name(profile), userId: profile.former ? nil : profile.userId, size: 112)
            Text(Self.name(profile)).font(.title2.weight(.bold)).multilineTextAlignment(.center).padding(.top, 6)
            if let handle = profile.handle { Text(handle).font(.subheadline).foregroundStyle(Role.muted) }
            if profile.former { Text(say(.peopleFormerMemberHint)).font(.subheadline).foregroundStyle(Role.muted) }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
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

/// "Memories with {name}", the List | Map switch once something is listed (a segmented control, as the system's own apps
/// switch a page between views of the same thing), and the partial list said.
private struct PersonMemoriesHead: View {
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
                Picker(say(.memoryPeopleWith), selection: Binding(get: { s.view }, set: { section.view($0) })) {
                    Text(say(.nodeViewList)).tag(PersonMemories.View.list)
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

    var body: some View {
        let details = [row.date.flatMap { MemoryDateText.text($0) }, row.placeName.flatMap { $0.isEmpty ? nil : $0 }, row.group.map { Names.spaceName($0) }]
            .compactMap { $0 }
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
                    if !details.isEmpty { Text(details.joined(separator: " · ")).font(.caption).foregroundStyle(Role.muted) }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.forward").font(.footnote.weight(.semibold)).foregroundStyle(Role.muted)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
