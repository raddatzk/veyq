import SwiftUI
import VeyqCore

/// The shell (ADR 0122). The drawer chooses what the app is in; the bar at the bottom belongs to the chosen
/// application — its own places, or those of its open object — and ends with Search, which on iOS is the system's
/// search tab. On a wide screen (iPad, Mac) the drawer stays open as the sidebar. Its look is the web's (ADR 0123
/// decision 7): the accent for selection, the green for creating, the web's tiles and section labels.
struct ShellView: View {
    @Bindable var model: AppModel
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif

    var body: some View {
        Group {
            #if os(iOS)
            if sizeClass == .compact { compact } else { wide }
            #else
            wide
            #endif
        }
        .tint(Role.accent)
    }

    private var wide: some View {
        NavigationSplitView {
            DrawerList(model: model)
        } detail: {
            Bar(model: model)
        }
    }

    #if os(iOS)
    private var compact: some View {
        ZStack(alignment: .leading) {
            Bar(model: model, onMenu: { withAnimation(.snappy) { model.drawerOpen = true } })
            if model.drawerOpen {
                Role.text.opacity(0.25).ignoresSafeArea()
                    .onTapGesture { withAnimation(.snappy) { model.drawerOpen = false } }
                DrawerList(model: model)
                    .frame(width: 310)
                    .clipShape(UnevenRoundedRectangle(bottomTrailingRadius: Radius.card, topTrailingRadius: Radius.card, style: .continuous))
                    .shadow(color: .black.opacity(0.12), radius: 20, x: 6)
                    .ignoresSafeArea(edges: .vertical)
                    .transition(.move(edge: .leading))
            }
        }
    }
    #endif
}

/// The drawer: the brand, New, Favorites, Recent, the applications, Spaces, then account and settings.
struct DrawerList: View {
    @Bindable var model: AppModel

    var body: some View {
        List {
            Section {
                Brand().padding(.vertical, 6)
                Button { } label: {
                    Label("New", systemImage: "plus").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Role.primary)
                .foregroundStyle(Role.onFill)
                .buttonBorderShape(.roundedRectangle(radius: Radius.control))
            }
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)

            Section { refs(model.drawer.favorites) } header: { SectionLabel(title: "Favorites") }
            Section { refs(model.drawer.recent) } header: { SectionLabel(title: "Recent") }
            Section {
                ForEach(model.applications, id: \.self) { app in
                    row(Names.application(app), symbol: Names.appSymbol(app), selected: model.shell.application == app && model.shell.openObject == nil) { model.choose(app) }
                }
                row("Spaces", symbol: "square.3.layers.3d", selected: model.shell.application == nil && model.shell.openObject == nil) { model.choose(nil) }
            } header: { SectionLabel(title: "Applications") }
            Section {
                row("Settings", symbol: "gearshape", selected: false) { }
                row("Sign out", symbol: "rectangle.portrait.and.arrow.right", selected: false) { model.signOut() }
            }
        }
        .listStyle(.sidebar)
        .scrollContentBackground(.hidden)
        .background { Chrome() }
    }

    @ViewBuilder private func refs(_ list: [ObjectRef]) -> some View {
        if list.isEmpty {
            Text("Nothing yet").font(.subheadline).foregroundStyle(Role.muted).listRowBackground(Color.clear)
        }
        ForEach(list, id: \.nodeId) { ref in
            row(LocalizedStringKey(ref.name), symbol: Names.kindSymbol(ref.kind),
                selected: model.shell.openObject?.nodeId == ref.nodeId) { model.open(ref) }
        }
    }

    /// A sidebar row as the web draws it: hover and selection are only the `--app-hover` fill, never bold, never a bar.
    private func row(_ title: LocalizedStringKey, symbol: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Tile(symbol: symbol)
                Text(title).foregroundStyle(selected ? Role.text : Role.controlText)
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(
            RoundedRectangle(cornerRadius: Radius.row, style: .continuous).fill(selected ? Role.hover : Color.clear).padding(.horizontal, 6)
        )
        .listRowSeparator(.hidden)
    }
}

/// The chosen application's bar: its places (or its open object's), then Search.
struct Bar: View {
    @Bindable var model: AppModel
    var onMenu: (() -> Void)? = nil

    private static let search = "search"
    private static let list = "list"

    private var tabs: [String] {
        let keys = model.shell.barTabs.map { $0.key }
        return keys.isEmpty ? [Self.list] : keys
    }

    private var selection: Binding<String> {
        Binding(
            get: { model.shell.searching ? Self.search : (model.shell.selectedTab?.key ?? Self.list) },
            set: { key in
                if key == Self.search { model.search(true) }
                else if let tab = model.shell.barTabs.first(where: { $0.key == key }) { model.select(tab) }
                else { model.search(false) }
            }
        )
    }

    var body: some View {
        TabView(selection: selection) {
            ForEach(tabs, id: \.self) { key in
                Tab(Names.tab(key, app: model.shell.application), systemImage: Names.tabSymbol(key, app: model.shell.application), value: key) {
                    NavigationStack { content(key).toolbar { toolbar }.background(Role.bg) }
                }
            }
            Tab(value: Self.search, role: .search) {
                NavigationStack { SearchList(model: model).toolbar { toolbar } }
            }
        }
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .navigation) {
            if model.shell.openObject != nil {
                Button { model.close() } label: { Image(systemName: "chevron.backward") }
            } else if let onMenu {
                Button(action: onMenu) { Image(systemName: "line.3.horizontal") }
            }
        }
    }

    @ViewBuilder private func content(_ key: String) -> some View {
        if let ref = model.shell.openObject {
            ObjectPlaceholder(ref: ref, tab: key)
        } else if let app = model.shell.application {
            ArrangedListView(list: model.applicationList(app), onOpen: model.open)
                .navigationTitle(Names.application(app))
        } else {
            SpacesView(model: model)
        }
    }
}

/// One object as a row of the web's lists: its kind's tile, its name in bold.
struct ObjectRow: View {
    let ref: ObjectRef
    let onOpen: (ObjectRef) -> Void

    var body: some View {
        Button { onOpen(ref) } label: {
            HStack(spacing: 12) {
                Tile(symbol: Names.kindSymbol(ref.kind))
                Text(ref.name).fontWeight(.bold).foregroundStyle(Role.text)
                Spacer(minLength: 0)
                Image(systemName: "chevron.forward").font(.footnote.weight(.semibold)).foregroundStyle(Role.muted)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct ArrangedListView: View {
    let list: ArrangedList
    let onOpen: (ObjectRef) -> Void

    var body: some View {
        if list.favorites.isEmpty && list.recent.isEmpty && list.all.isEmpty {
            ContentUnavailableView("Nothing here yet", systemImage: "tray")
        } else {
            List {
                if !list.favorites.isEmpty { Section { rows(list.favorites) } header: { SectionLabel(title: "Favorites") } }
                if !list.recent.isEmpty { Section { rows(list.recent) } header: { SectionLabel(title: "Recent") } }
                if !list.all.isEmpty { Section { rows(list.all) } }
            }
            .scrollContentBackground(.hidden)
        }
    }

    private func rows(_ refs: [ObjectRef]) -> some View {
        ForEach(refs, id: \.nodeId) { ref in ObjectRow(ref: ref, onOpen: onOpen).listRowBackground(Role.surface) }
    }
}

struct SpacesView: View {
    @Bindable var model: AppModel

    var body: some View {
        List {
            ForEach(model.spaces, id: \.scopeId) { space in
                Section {
                    let inSpace = model.objects.filter { $0.scopeId == space.scopeId }
                    if inSpace.isEmpty { Text("Nothing here yet").foregroundStyle(Role.muted) }
                    ForEach(inSpace, id: \.nodeId) { ref in ObjectRow(ref: ref, onOpen: model.open).listRowBackground(Role.surface) }
                } header: { SectionLabel(title: LocalizedStringKey(space.name)) }
            }
        }
        .scrollContentBackground(.hidden)
        .navigationTitle("Spaces")
    }
}

struct SearchList: View {
    @Bindable var model: AppModel
    @State private var query = ""

    var body: some View {
        let hits = query.trimmingCharacters(in: .whitespaces).isEmpty ? [] : model.objects.filter { $0.name.localizedCaseInsensitiveContains(query) }
        List(hits, id: \.nodeId) { ref in
            ObjectRow(ref: ref) { r in model.search(false); model.open(r) }
        }
        .searchable(text: $query, prompt: "Search names")
        .navigationTitle("Search")
    }
}

struct ObjectPlaceholder: View {
    let ref: ObjectRef
    let tab: String

    var body: some View {
        ContentUnavailableView {
            Label { Text(ref.name) } icon: { Tile(symbol: Names.kindSymbol(ref.kind)) }
        } description: {
            Text("This view is not built in the app yet.")
        }
        .navigationTitle(ref.name)
    }
}

/// The web's motifs (src/web/src/shared/ui/icons.ts) in SF Symbols: images, file, inbox, wallet, folder, link.
enum Names {
    static func application(_ app: ApplicationId) -> LocalizedStringKey {
        switch app {
        case .memories: "Memories"
        case .depot: "Depot"
        case .expenses: "Expenses"
        default: "Application"
        }
    }

    static func appSymbol(_ app: ApplicationId) -> String {
        switch app {
        case .memories: "photo.on.rectangle.angled"
        case .depot: "doc"
        case .expenses: "wallet.bifold"
        default: "square"
        }
    }

    static func kindSymbol(_ kind: String) -> String {
        switch kind {
        case "MEMORY", "CHAPTER": "photo.on.rectangle.angled"
        case "FILE": "doc"
        case "DEPOT_DROPBOX": "tray"
        case "EXPENSES_POT": "wallet.bifold"
        case "SHARE_BOOKMARK": "link"
        default: "folder"
        }
    }

    static func tab(_ key: String, app: ApplicationId?) -> LocalizedStringKey {
        switch key {
        case "memories.all": "Memories"
        case "memories.map": "Map"
        case "depot.files": "Files"
        case "expenses.entries": "Entries"
        case "expenses.overview": "Overview"
        case "expenses.settings": "Settings"
        default: app.map(application) ?? "Spaces"
        }
    }

    static func tabSymbol(_ key: String, app: ApplicationId?) -> String {
        switch key {
        case "memories.all": "photo.on.rectangle.angled"
        case "memories.map": "map"
        case "expenses.entries": "list.bullet"
        case "expenses.overview": "chart.bar"
        case "expenses.settings": "gearshape"
        case "depot.files": "doc"
        default: app.map(appSymbol) ?? "square.3.layers.3d"
        }
    }
}
