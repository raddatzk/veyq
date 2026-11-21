import SwiftUI
import VeyqCore

// MARK: Item marks (ADR 0149 decision 4)

/// A tile's mark: a badge, not a button — the heart, filled where the caller marked it, with how many did where that is
/// above zero (a personal space has no count). A tile is one target (the HIG); the mark is toggled in the lightbox and from
/// the tile's context menu.
struct MarkBadge: View {
    let mark: GalleryModel.Mark

    var body: some View {
        HStack(spacing: 3) {
            Image(systemName: mark.mine ? "heart.fill" : "heart")
            if let count = mark.count?.intValue, count > 0 { Text(count, format: .number).monospacedDigit() }
        }
        .font(.caption2.weight(.semibold))
        .foregroundStyle(.white)
        .padding(.horizontal, 6)
        .padding(.vertical, 3)
        .background(.black.opacity(0.55), in: Capsule())
        .accessibilityHidden(true)
    }

    /// What a screen reader says of the badge, as the tile's value: how many marked it, and that the caller did.
    static func said(_ mark: GalleryModel.Mark) -> String {
        var parts: [String] = []
        if let count = mark.count?.intValue, count > 0 { parts.append(say(.mediaMarksCount, count)) }
        if mark.mine { parts.append(say(.mediaMarksYours)) }
        return parts.joined(separator: ", ")
    }
}

/// The mark of medium `mediaId` flipped (core's `toggleMark`: refused before the own marks are read; what did not land is put
/// back and said through the gallery's `markSaid`). Its words say the mark is shared with the space (the web's).
struct MarkButton: View {
    let gallery: GalleryModel
    let mediaId: String
    let mine: Bool
    /// The lightbox's: the heart filled while marked; a menu's entry keeps the outline it toggles to.
    var filled = false

    var body: some View {
        Button {
            Task { _ = try? await gallery.toggleMark(mediaId: mediaId) }
        } label: {
            Label(say(mine ? .mediaMarksUnmark : .mediaMarksMark), systemImage: filled ? (mine ? "heart.fill" : "heart") : (mine ? "heart.slash" : "heart"))
        }
        .help(say(mine ? .mediaMarksUnmark : .mediaMarksMark))
    }
}

/// The marks' mode — all, everyone's, only mine — as the platform's filter menu (the filled symbol while it narrows).
/// Shown where core offers it (a member's, not in a personal space).
struct MarksModeMenu: View {
    let gallery: GalleryModel
    let filter: GalleryFilter.State

    var body: some View {
        Menu {
            Picker(say(.mediaMarksFilterLabel), selection: Binding(get: { filter.mode }, set: { mode in
                let f = gallery.current.filter
                gallery.filter(filter: GalleryFilter.State(mode: mode, chips: f.chips, text: f.text))
            })) {
                Text(say(.mediaMarksFilterAll)).tag(GalleryFilter.Mode.all)
                Text(say(.mediaMarksFilterEveryone)).tag(GalleryFilter.Mode.everyone)
                Text(say(.mediaMarksFilterMine)).tag(GalleryFilter.Mode.mine)
            }
            .pickerStyle(.inline)
        } label: {
            Label(say(.mediaMarksFilterLabel), systemImage: filter.mode == .all ? "line.3.horizontal.decrease.circle" : "line.3.horizontal.decrease.circle.fill")
                .labelStyle(.iconOnly)
        }
        .menuIndicator(.hidden)
        .fixedSize()
        .help(say(.mediaMarksFilterLabel))
    }
}

// MARK: The gallery's filter (ADR 0149 decision 5)

/// A filter chip as the field's token: its key (core's `GalleryFilter.key`), the chip, its words and its symbol.
struct FilterToken: Identifiable, Hashable {
    let id: String
    let chip: GalleryFilter.Chip
    let label: String
    let symbol: String

    init(_ chip: GalleryFilter.Chip) {
        self.chip = chip
        id = GalleryFilter.shared.key(chip: chip)
        label = GalleryFilterWords.label(chip)
        symbol = GalleryFilterWords.symbol(chip)
    }

    static func == (a: Self, b: Self) -> Bool { a.id == b.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

/// A chip offered for the text: the chip and what is left of the text once it is taken.
struct FilterSuggestion: Identifiable {
    let token: FilterToken
    let rest: String
    var id: String { token.id }
}

/// The filter's words: a chip's, and the empty grid's naming every filter in force.
enum GalleryFilterWords {
    static func label(_ chip: GalleryFilter.Chip) -> String {
        if let day = chip as? GalleryFilter.ChipDay { return day.label }
        guard let search = (chip as? GalleryFilter.ChipSearch)?.chip else { return "" }
        if let year = search as? SearchChip.Year { return String(year.year) }
        if let place = search as? SearchChip.Place { return place.label }
        if let kind = search as? SearchChip.Kind { return kind.label }
        return ""
    }

    static func symbol(_ chip: GalleryFilter.Chip) -> String {
        if chip is GalleryFilter.ChipDay { return "calendar" }
        let search = (chip as? GalleryFilter.ChipSearch)?.chip
        if search is SearchChip.Place { return "mappin.and.ellipse" }
        if search is SearchChip.Kind { return "photo.on.rectangle" }
        return "calendar"
    }

    /// The empty grid's words: the marks' own where only the mode narrows, else every filter in force named.
    static func empty(_ filter: GalleryFilter.State) -> String {
        let text = filter.text.trimmingCharacters(in: .whitespaces)
        if filter.chips.isEmpty, text.isEmpty {
            switch filter.mode {
            case .mine: return say(.mediaMarksEmptyMine)
            case .everyone: return say(.mediaMarksEmptyEveryone)
            default: break
            }
        }
        var parts = filter.chips.map(label)
        if !text.isEmpty { parts.append(say(.mediaFilterText, text)) }
        switch filter.mode {
        case .mine: parts.append(say(.mediaFilterMarksMine))
        case .everyone: parts.append(say(.mediaFilterMarksEveryone))
        default: break
        }
        return say(.mediaFilterEmpty, parts.joined(separator: ", "))
    }

    /// The chips core offers for the filter's text, in the person's words for a photo and a video.
    static func suggestions(_ gallery: GalleryModel) -> [FilterSuggestion] {
        gallery.suggestions(photo: say(.searchKindPhoto), video: say(.searchKindVideo)).compactMap { pair in
            guard let chip = pair.first else { return nil }
            return FilterSuggestion(token: FilterToken(chip), rest: (pair.second as String?) ?? "")
        }
    }

    /// Suggestion `s` taken: its chip added, the text what is left of it.
    static func take(_ s: FilterSuggestion, _ gallery: GalleryModel, text: Binding<String>) {
        let f = gallery.current.filter
        text.wrappedValue = s.rest
        gallery.filter(filter: GalleryFilter.State(mode: f.mode, chips: f.chips + [s.token.chip], text: s.rest))
    }

    /// The chips now `kept` (a token removed in the field), the text as it stands.
    static func keep(_ kept: [FilterToken], _ gallery: GalleryModel) {
        let f = gallery.current.filter
        let ids = Set(kept.map(\.id))
        gallery.filter(filter: GalleryFilter.State(mode: f.mode, chips: f.chips.filter { ids.contains(GalleryFilter.shared.key(chip: $0)) }, text: f.text))
    }
}

/// An iPhone's filter: the system's search field in the page's navigation bar, the chips its tokens and the chips offered
/// for the text its suggestions (Files' and Photos' own search on a phone). Nothing where it is not `shown`.
struct GalleryFilterField: ViewModifier {
    let shown: Bool
    let gallery: GalleryModel?
    let filter: GalleryFilter.State?
    @Binding var text: String

    func body(content: Content) -> some View {
        #if os(iOS)
        if shown, let gallery, let filter {
            content
                .searchable(
                    text: $text,
                    tokens: Binding(get: { filter.chips.map(FilterToken.init) }, set: { GalleryFilterWords.keep($0, gallery) }),
                    placement: .navigationBarDrawer(displayMode: .automatic),
                    prompt: say(.mediaFilterPlaceholder)
                ) { token in
                    Label(token.label, systemImage: token.symbol)
                }
                .searchSuggestions {
                    if !text.isEmpty {
                        ForEach(GalleryFilterWords.suggestions(gallery)) { s in
                            Button { GalleryFilterWords.take(s, gallery, text: $text) } label: {
                                Label(s.token.label, systemImage: s.token.symbol)
                            }
                        }
                    }
                }
                .accessibilityHint(say(.mediaFilterLabel))
        } else {
            content
        }
        #else
        content
        #endif
    }
}

/// An iPad's and a Mac's filter, a field of the page's own above the grid (the toolbar's field is the shell's search): the
/// text, the chips in force as removable tokens, and the chips offered for the text below it while something is typed.
struct GalleryFilterBar: View {
    let gallery: GalleryModel
    let filter: GalleryFilter.State
    @Binding var text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").foregroundStyle(Role.muted)
                TextField(say(.mediaFilterPlaceholder), text: $text)
                    .textFieldStyle(.plain)
                    .accessibilityLabel(say(.mediaFilterLabel))
                    .onSubmit {
                        // Return takes the first chip offered
                        if let first = GalleryFilterWords.suggestions(gallery).first { GalleryFilterWords.take(first, gallery, text: $text) }
                    }
                if !text.isEmpty || !filter.chips.isEmpty {
                    Button {
                        text = ""
                        gallery.filter(filter: GalleryFilter.State(mode: filter.mode, chips: [], text: ""))
                    } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(Role.muted) }
                        .buttonStyle(.plain)
                        .accessibilityLabel(say(.mediaFilterClear))
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(Role.surface, in: RoundedRectangle(cornerRadius: Radius.row, style: .continuous))
            let tokens = filter.chips.map(FilterToken.init)
            let offered = text.isEmpty ? [] : GalleryFilterWords.suggestions(gallery)
            if !tokens.isEmpty || !offered.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(tokens) { t in
                            Button { GalleryFilterWords.keep(tokens.filter { $0.id != t.id }, gallery) } label: {
                                Label(t.label, systemImage: "xmark")
                            }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.small)
                            .accessibilityHint(say(.searchRemoveChip, t.label))
                        }
                        ForEach(offered) { s in
                            Button { GalleryFilterWords.take(s, gallery, text: $text) } label: {
                                Label(s.token.label, systemImage: s.token.symbol)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                    }
                }
            }
        }
    }
}
