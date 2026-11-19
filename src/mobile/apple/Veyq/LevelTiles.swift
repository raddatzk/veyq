import SwiftUI
import VeyqCore

/// How a level is drawn (ADR 0149 decision 8): as rows or as tiles, the account's one choice on this device (core's
/// `ViewModes`), offered as Files' *View* menu — List and Tiles — in the level's toolbar; its symbol the view shown.
struct ViewModeMenu: View {
    @Bindable var model: AppModel

    /// Where a page's actions go: the trailing end (a Mac puts `.primaryAction` at the leading one, beside the title).
    static var placement: ToolbarItemPlacement {
        #if os(macOS)
        .automatic
        #else
        .primaryAction
        #endif
    }

    static func symbol(_ mode: ViewMode) -> String {
        switch mode {
        case .tiles: "square.grid.2x2"
        case .map: "map"
        default: "list.bullet"
        }
    }

    var body: some View {
        let current = model.levelView
        Menu {
            Picker(say(.nodeViewMode), selection: Binding(get: { current }, set: { model.chooseView($0) })) {
                ForEach(ViewMode.companion.LEVEL, id: \.self) { mode in
                    Label(say(mode.word), systemImage: Self.symbol(mode)).tag(mode)
                }
            }
            .pickerStyle(.inline)
        } label: {
            Label(say(.nodeViewMode), systemImage: Self.symbol(current)).labelStyle(.iconOnly)
        }
        .menuIndicator(.hidden)
        .help(say(.nodeViewMode))
    }
}

/// One node as a tile: its cover square (a folder's cover or the folder symbol, an object's cover or its kind's symbol),
/// its name below and the row's second line under it — the platform's album grid (Photos, Files' icons). It opens as its
/// row does; while the shell picks a place for what was shared, a tile that cannot take it is dimmed as its row is.
struct NodeTileItem: Identifiable {
    let id: String
    let name: String
    var details: String?
    var cover: CGImage?
    let symbol: String
    var folder = false
    var enabled = true
    let open: () -> Void
}

/// Tiles in core's grid (`TileGrid`): at least two columns on a phone, more as the window widens, each a square cover with
/// its words below; lazy, so only the tiles near the viewport are built.
struct NodeTiles: View {
    let items: [NodeTileItem]
    /// The width the grid is laid out in (measured by the page).
    let width: CGFloat

    var body: some View {
        let gap = CGFloat(TileGrid.shared.GAP)
        let columns = Int(TileGrid.shared.columns(width: Double(width)))
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: gap, alignment: .top), count: max(columns, 1)), alignment: .leading, spacing: gap) {
            ForEach(items) { NodeTile(item: $0) }
        }
    }
}

private struct NodeTile: View {
    let item: NodeTileItem

    var body: some View {
        Button(action: item.open) {
            VStack(alignment: .leading, spacing: 4) {
                Color.clear
                    .aspectRatio(1, contentMode: .fit)
                    .overlay {
                        if let cover = item.cover {
                            Image(decorative: cover, scale: 1).resizable().scaledToFill()
                        } else {
                            ZStack {
                                Rectangle().fill(item.folder ? Color("veyq-info-soft") : Role.accentSoft)
                                Image(systemName: item.symbol)
                                    .font(.system(size: 34, weight: .medium))
                                    .foregroundStyle(item.folder ? Color("veyq-info-text") : Role.accent)
                            }
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                Text(item.name).fontWeight(.bold).foregroundStyle(Role.text).lineLimit(2)
                if let details = item.details, !details.isEmpty {
                    Text(details).font(.caption).foregroundStyle(Role.muted).lineLimit(1)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!item.enabled)
        .opacity(item.enabled ? 1 : 0.38)
        .accessibilityElement(children: .combine)
    }
}

extension NodeTileItem {
    /// An object's tile, as `ObjectRow` draws its row: its cover, its kind's symbol, the application's facts about it.
    init(ref: ObjectRef, details: [String: [String]], covers: [String: CGImage], picking: Bool, onOpen: @escaping (ObjectRef) -> Void) {
        self.init(
            id: ref.nodeId, name: ref.name, details: details[ref.nodeId]?.joined(separator: " · "), cover: covers[ref.nodeId],
            symbol: Names.kindSymbol(ref.kind), enabled: !picking || AppKinds.shared.takesShared(kind: ref.kind)
        ) { onOpen(ref) }
    }
}
