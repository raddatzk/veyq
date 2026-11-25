import SwiftUI
import VeyqCore

/// Search on a TV (ADR 0125; ADR 0159 decision 3, owner 2026-11-25): tvOS's own search — the tab bar's search tab
/// (`Tab(role: .search)`) with SwiftUI's `.searchable`, whose field is the system keyboard (dictation and the Siri Remote's
/// voice with it) —, and the core's results (`SearchModel`) as focusable tiles under it. A result opens what the drawer
/// would; the core has no picture for a result, so a tile shows its kind's symbol. Read-only: the core's index is held in
/// memory for the session and writes nothing.
struct TvSearch: View {
    @Bindable var model: TvModel
    @State private var text = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 40) {
                    if let shown = model.found {
                        if !shown.chips.isEmpty {
                            ScrollView(.horizontal) {
                                HStack(spacing: 24) {
                                    ForEach(Array(shown.chips.enumerated()), id: \.offset) { i, chip in
                                        Button { model.input(); model.removeChip(i) } label: {
                                            Label(say(piece: chip.label), systemImage: "xmark.circle")
                                        }
                                        .accessibilityLabel(say(.searchRemoveChip, say(piece: chip.label)))
                                    }
                                }
                                .padding(.vertical, 20)
                            }
                            .scrollClipDisabled()
                        }
                        ForEach(Array(shown.status.enumerated()), id: \.offset) { _, n in
                            Text(say(notice: n)).foregroundStyle(.secondary)
                        }
                        if shown.asked && shown.results.isEmpty {
                            TvMessage(text: say(.searchEmpty))
                        } else if !shown.asked {
                            Text(say(.searchExplained)).foregroundStyle(.secondary)
                        }
                        if !shown.results.isEmpty {
                            Text(say(.searchResults)).font(.headline).foregroundStyle(.secondary)
                            TvGrid {
                                ForEach(Array(shown.results.enumerated()), id: \.element.id) { i, r in
                                    Button { model.input(); model.openResult(i) } label: {
                                        TvTile(name: r.name.map(\.text).joined(), line: pieceLine(r.line), image: nil, symbol: TvSearchSymbols.of(r.icon))
                                    }
                                    .buttonStyle(.card)
                                }
                            }
                            if shown.more > 0 {
                                Button(say(.searchShowMore, shown.more)) { model.input(); model.moreResults() }
                            }
                        }
                    }
                }
                .padding(.horizontal, 20)
            }
            .searchable(text: $text, prompt: say(.searchHint))
            .searchSuggestions {
                if let shown = model.found {
                    ForEach(Array(shown.suggestions.enumerated()), id: \.offset) { i, s in
                        Button { model.input(); model.chooseSuggestion(i) } label: {
                            Label(say(piece: s.label), systemImage: TvSearchSymbols.of(s.icon))
                        }
                    }
                }
            }
            .onChange(of: text) { _, now in model.input(); model.searchText(now) }
        }
        .onAppear {
            model.searchShown()
            text = model.found?.text ?? ""
        }
    }
}

/// Core's icon kinds (the web's icon names) in SF Symbols (the phone's `SearchSymbols`).
enum TvSearchSymbols {
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
