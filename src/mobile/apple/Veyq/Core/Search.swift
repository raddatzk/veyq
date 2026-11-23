import SwiftUI
import VeyqCore

// The search (ADR 0146 decisions 7, 8, 9, 12): core's `SearchModel` over every space of the tenant, drawn in the system's
// own search field — the split view's toolbar field (iPad, Mac) and the iPhone's search tab. Its chips are the field's
// tokens, the chips offered for the text its suggestions (*Filter by*), the results a list under *Results*. Everything
// shown is core's `SearchShown`; the field decides nothing.

/// The catalog's words for core's search (what it matches a kind or a month by, how it names a section).
final class SearchCatalog: NSObject, SearchWords, @unchecked Sendable {
    func say(msg: Msg) async throws -> String { catalogWord(msg) }
}

private func catalogWord(_ msg: Msg) -> String { say(msg) }

extension AppModel {
    /// The tenant's search, made as the field is first used (a lock or a switch disposed the one before).
    var searcher: SearchModel? {
        if let m = searchModel { return m }
        guard let shell = shellModel else { return nil }
        #if os(iOS)
        let appearance = true // the iOS page's appearance section (the system's page for the app's language)
        #else
        let appearance = false
        #endif
        let m = shell.searchModel(words: SearchCatalog(), appearance: appearance)
        let id = ObjectIdentifier(m)
        searchModel = m
        searchWatch = m.watch { @Sendable state in
            nonisolated(unsafe) let state = state
            Task { @MainActor [weak self] in
                // a state of a search disposed meanwhile is dropped
                guard let self, let held = self.searchModel, ObjectIdentifier(held) == id else { return }
                self.searchChanged(state)
            }
        }
        return m
    }

    /// The tenant closed (a lock, a switch, a sign-out): its search is disposed, nothing of it stays.
    func closeSearch() {
        searchWatch?.stop()
        searchWatch = nil
        searchModel?.dispose()
        searchModel = nil
        found = nil
        settingsAnchor = nil
    }

    private func searchChanged(_ state: SearchShown) {
        let was = found?.openSettingsCount ?? 0
        let opened = found?.openedCount ?? 0
        found = state
        // a result opened (inbox #959): core emptied the field in this state; the field's own text empties too, and the
        // search gives up its place where the result left it standing (the page on screen chosen, a settings section) — a
        // node or a person already moved the shell, whose back returns to an empty search
        if state.openedCount != opened {
            searchFieldReset += 1
            if shell.searching { search(false) }
        }
        // a settings result: the Settings page opens there (the field opens the Mac's window, `SearchField`) — whenever
        // core's count of openings moves, the same section chosen twice included; a later state keeping the section is
        // no new opening
        if let section = state.openSettings, state.openSettingsCount != was {
            settingsAnchor = section
            #if os(iOS)
            let _: Void = openSettings() // the account's page (not the open Pot's)
            #endif
        }
    }

    func searchFocus() { searcher?.focus() }
    func searchText(_ text: String) { searcher?.text(text: text) }
    func searchChoose(_ index: Int) { searcher?.choose(index: Int32(index)) }
    func searchRemoveChip(_ index: Int) { searcher?.removeChip(index: Int32(index)) }
    func searchMore() { searcher?.more() }
    func searchOpen(_ index: Int) { searcher?.open(index: Int32(index)) }
    func searchClose() { searcher?.close() }
}

/// A chip as the field's token: its id (core's chip key), its type's word, its value, its symbol.
struct SearchToken: Identifiable, Hashable {
    let id: String
    let word: String
    let label: String
    let symbol: String

    init(_ chip: ChipShown) {
        id = chip.id
        word = say(chip.word)
        label = say(piece: chip.label)
        symbol = SearchSymbols.of(chip.icon)
    }

    /// An offered chip, as the token it becomes (its id the chip's key: core's `SuggestionShown.id` is "chip:" and it).
    init(_ s: SuggestionShown) {
        id = String(s.id.dropFirst(SearchToken.offered.count))
        word = say(s.word)
        label = say(piece: s.label)
        symbol = SearchSymbols.of(s.icon)
    }

    static let offered = "chip:"
}

/// Core's icon kinds (the web's icon names) in SF Symbols, as the shell's own kinds draw them.
enum SearchSymbols {
    static func of(_ icon: String) -> String {
        switch icon {
        case "folder", "folder-open": "folder"
        case "bookmark": "link"
        case "file": "doc"
        case "inbox": "tray"
        case "wallet": "wallet.bifold"
        case "images": "photo.on.rectangle.angled"
        case "image": "photo"
        case "play": "play.rectangle"
        case "user": "person"
        case "users": "person.2"
        case "settings": "gearshape"
        case "calendar": "calendar"
        case "map-marker": "mappin.and.ellipse"
        case "tag": "tag"
        default: "doc"
        }
    }
}

/// The search field on a page: the system's `.searchable` with core's chips as its tokens and the chips offered for the
/// text as its suggestions. `drivesShell`: the split view's field, whose page shows the results while something is asked
/// (the iPhone's search tab shows them as its own page). Return takes the first entry, a token removed in the field removes
/// its chip; ⌘F focuses the field (`SearchCommands`); on a Mac Escape empties it, which closes the search.
struct SearchField: ViewModifier {
    @Bindable var model: AppModel
    var placement: SearchFieldPlacement = .automatic
    var drivesShell = false
    @State private var text = ""
    @FocusState private var focused: Bool
    /// A suggestion was taken: the field's own clearing of its text is not sent, core's rest of the text is shown.
    @State private var choosing = false
    /// How many tokens the field holds as last edited (core's chips follow a moment later).
    @State private var fieldTokens = 0
    #if os(macOS)
    @Environment(\.openSettings) private var openSettings
    #endif

    private var tokens: Binding<[SearchToken]> {
        Binding(
            get: { (model.found?.chips ?? []).map(SearchToken.init) },
            set: { now in
                let chips = model.found?.chips ?? []
                let held = Set(now.map(\.id))
                if let gone = chips.firstIndex(where: { !held.contains($0.id) }) {
                    model.searchRemoveChip(gone)
                } else if let added = now.first(where: { t in !chips.contains { $0.id == t.id } }),
                          let index = model.found?.suggestions.firstIndex(where: { $0.id == SearchToken.offered + added.id }) {
                    take(index)
                }
                fieldTokens = now.count
                asked(text: text, chips: now.count)
            }
        )
    }

    private var typed: Binding<String> {
        Binding(
            get: { text },
            set: { now in
                // the field empties its text as it takes a suggestion: core's rest of the text follows (`choosing`)
                if choosing, now.isEmpty { return }
                text = now
                model.searchText(now)
                asked(text: now, chips: fieldTokens)
            }
        )
    }

    /// Suggestion `index` taken: its chip set by core, the text what core leaves of it.
    private func take(_ index: Int) {
        choosing = true
        model.searchChoose(index)
    }

    /// The split view's page shows the results while something is asked; nothing asked, the search closes.
    private func asked(text: String, chips: Int) {
        let asking = !text.isEmpty || chips > 0
        if !asking { model.searchClose() }
        guard drivesShell, asking != model.shell.searching else { return }
        model.search(asking)
    }

    /// Return: the first entry — the first chip offered, else the first result.
    private func submit() {
        guard let found = model.found else { return }
        if !found.suggestions.isEmpty { take(0) } else if !found.results.isEmpty { model.searchOpen(0) }
    }

    func body(content: Content) -> some View {
        content
            .searchable(text: typed, tokens: tokens, placement: placement, prompt: say(.searchHint)) { token in
                Label(token.label, systemImage: token.symbol).accessibilityHint(Text(token.word))
            }
            .searchSuggestions {
                let offered = model.found?.asked == true ? model.found?.suggestions ?? [] : []
                if !offered.isEmpty {
                    Section(say(.searchFilterBy)) {
                        ForEach(offered, id: \.id) { s in
                            SuggestionRow(suggestion: s).searchCompletion(SearchToken(s))
                        }
                    }
                }
            }
            .searchFocused($focused)
            .onSubmit(of: .search) { submit() }
            .onChange(of: focused) { _, on in if on { model.searchFocus() } }
            // a suggestion taken: the text is what core left of it
            .onChange(of: model.found?.chips.count) {
                fieldTokens = model.found?.chips.count ?? 0
                guard choosing, let found = model.found else { return }
                choosing = false
                text = found.text
            }
            .onChange(of: model.searchFieldReset) { text = ""; choosing = false }
            // what a screen reader hears: the chip added or removed, then the count (ADR 0146 decision 12)
            .onChange(of: announcement) { _, said in
                if !said.isEmpty { AccessibilityNotification.Announcement(said).post() }
            }
            #if os(macOS)
            .onChange(of: model.settingsAnchor) { _, section in if section != nil { openSettings() } }
            .focusedSceneValue(\.searchFocus, SearchFocus { focused = true })
            #endif
    }

    private var announcement: String {
        guard let found = model.found, found.asked else { return "" }
        return found.announcement.map { say(notice: $0) }.joined(separator: " ")
    }
}

/// One chip offered: its value, an *In:* node's place under it, its type's word at the end.
private struct SuggestionRow: View {
    let suggestion: SuggestionShown

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: SearchSymbols.of(suggestion.icon)).foregroundStyle(Role.accent).frame(width: 20)
            VStack(alignment: .leading, spacing: 2) {
                Text(say(piece: suggestion.label))
                if !suggestion.`where`.isEmpty {
                    Text(pieceLine(suggestion.`where`)).font(.caption).foregroundStyle(Role.muted)
                }
            }
            Spacer(minLength: 8)
            Text(say(suggestion.word)).font(.caption).foregroundStyle(Role.muted)
        }
    }
}

/// What the search found (core's `SearchShown`): the status (a space loading, spaces not searched, offline's photos, the
/// empty state), the results under *Results* — each its symbol (a person their picture), its name bold where it matched,
/// its second line — and *Show {count} more*. A calm focus shows nothing.
struct SearchResults: View {
    @Bindable var model: AppModel

    var body: some View {
        let found = model.found
        List {
            if let found, found.asked {
                if !found.status.isEmpty {
                    Section {
                        ForEach(Array(found.status.enumerated()), id: \.offset) { _, n in
                            Text(say(notice: n)).font(.callout).foregroundStyle(Role.muted)
                        }
                    }
                }
                if !found.results.isEmpty {
                    Section(say(.searchResults)) {
                        ForEach(Array(found.results.enumerated()), id: \.element.id) { i, r in
                            Button { model.searchOpen(i) } label: { ResultRow(result: r) }
                                .buttonStyle(.plain)
                        }
                        if found.more > 0 {
                            Button(say(.searchShowMore, Int(found.more))) { model.searchMore() }
                        }
                    }
                }
            }
        }
        .navigationTitle(say(.tabSearch))
    }
}

/// One result row: its symbol (a person's picture), its name with the matched part bold, its second line.
private struct ResultRow: View {
    let result: ResultShown

    /// A person's row: their user id (core's row id is ":person:" and it).
    private var person: String? {
        let prefix = ":person:"
        return result.id.hasPrefix(prefix) ? String(result.id.dropFirst(prefix.count)) : nil
    }

    var body: some View {
        HStack(spacing: 12) {
            if let person {
                PersonAvatar(name: result.name.map(\.text).joined(), userId: person, size: 34)
            } else {
                Tile(symbol: SearchSymbols.of(result.icon))
            }
            VStack(alignment: .leading, spacing: 2) {
                Self.parts(result.name).foregroundStyle(Role.text)
                let line = pieceLine(result.line)
                if !line.isEmpty || result.reason != nil {
                    (Text(line) + (result.reason.map { (line.isEmpty ? Text("") : Text(Shown.shared.SEPARATOR)) + Self.parts($0) } ?? Text("")))
                        .font(.caption).foregroundStyle(Role.muted)
                }
            }
            Spacer(minLength: 0)
            // the web's badges (inbox #962): *You are here* on the page on screen, the values its chips matched — capsules,
            // as the system tags a row
            if result.here {
                Self.badge(say(.searchHere), tint: Role.accent)
            }
            ForEach(Array(result.matched.enumerated()), id: \.offset) { _, v in
                Self.badge(say(piece: v), tint: Role.muted)
            }
            Image(systemName: "chevron.forward").font(.footnote.weight(.semibold)).foregroundStyle(Role.muted)
        }
        .contentShape(Rectangle())
    }

    /// A small capsule at a row's end, its text in `tint`.
    static func badge(_ text: String, tint: Color) -> some View {
        Text(text)
            .font(.caption2.weight(.medium))
            .lineLimit(1)
            .foregroundStyle(tint)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(tint.opacity(0.14), in: Capsule())
    }

    /// A name in its parts, bold where it matched.
    static func parts(_ parts: [PartShown]) -> Text {
        parts.reduce(Text("")) { t, p in t + (p.strong ? Text(p.text).bold() : Text(p.text)) }
    }
}

#if os(macOS)
/// ⌘F: the focused window's search field, for the Edit menu (`SearchCommands`).
struct SearchFocus {
    let focus: () -> Void
}

extension FocusedValues {
    @Entry var searchFocus: SearchFocus?
}

/// The Edit menu's search: ⌘F focuses the window's search field (ADR 0146 decision 12).
struct SearchCommands: Commands {
    @FocusedValue(\.searchFocus) private var searchFocus

    var body: some Commands {
        CommandGroup(after: .textEditing) {
            Button(say(.tabSearch)) { searchFocus?.focus() }
                .keyboardShortcut("f", modifiers: .command)
                .disabled(searchFocus == nil)
        }
    }
}
#endif
