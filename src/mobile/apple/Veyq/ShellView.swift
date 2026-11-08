import SwiftUI
import VeyqCore

/// The shell (ADR 0122). The drawer chooses what the app is in; the bar at the bottom belongs to the chosen
/// application — its own places, or those of its open object — and ends with Search, which on iOS is the system's
/// search tab. On a wide screen (iPad, Mac) the drawer stays open as the sidebar.
struct ShellView: View {
    @Bindable var model: AppModel
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif

    var body: some View {
        #if os(iOS)
        if sizeClass == .compact {
            compact
        } else {
            wide
        }
        #else
        wide
        #endif
    }

    private var wide: some View {
        NavigationSplitView {
            DrawerList(model: model)
                .navigationTitle("veyq")
        } detail: {
            Bar(model: model)
        }
    }

    #if os(iOS)
    private var compact: some View {
        ZStack(alignment: .leading) {
            Bar(model: model, onMenu: { withAnimation(.snappy) { model.drawerOpen = true } })
            if model.drawerOpen {
                Color.black.opacity(0.3).ignoresSafeArea()
                    .onTapGesture { withAnimation(.snappy) { model.drawerOpen = false } }
                DrawerList(model: model)
                    .frame(width: 310)
                    .background(.regularMaterial)
                    .transition(.move(edge: .leading))
            }
        }
    }
    #endif
}

/// The drawer: New, Favorites, Recent, the applications, Spaces, then account and settings.
struct DrawerList: View {
    @Bindable var model: AppModel

    var body: some View {
        List {
            Button { } label: { Label("New", systemImage: "plus") }
            Section("Favorites") { refs(model.drawer.favorites) }
            Section("Recent") { refs(model.drawer.recent) }
            Section("Applications") {
                ForEach(model.applications, id: \.self) { app in
                    Button { model.choose(app) } label: { Label(Names.application(app), systemImage: Names.icon(app)) }
                }
                Button { model.choose(nil) } label: { Label("Spaces", systemImage: "square.stack.3d.up") }
            }
            Section {
                Button { } label: { Label("Settings", systemImage: "gearshape") }
                Button(role: .destructive) { model.signOut() } label: { Label("Sign out", systemImage: "rectangle.portrait.and.arrow.right") }
            }
        }
    }

    @ViewBuilder private func refs(_ list: [ObjectRef]) -> some View {
        if list.isEmpty {
            Text("Nothing yet").foregroundStyle(.secondary)
        }
        ForEach(list, id: \.nodeId) { ref in
            Button { model.open(ref) } label: { Label(ref.name, systemImage: Names.icon(ref.application)) }
        }
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
                Tab(Names.tab(key, app: model.shell.application), systemImage: Names.tabIcon(key), value: key) {
                    NavigationStack { content(key).toolbar { toolbar } }
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

struct ArrangedListView: View {
    let list: ArrangedList
    let onOpen: (ObjectRef) -> Void

    var body: some View {
        if list.favorites.isEmpty && list.recent.isEmpty && list.all.isEmpty {
            ContentUnavailableView("Nothing here yet", systemImage: "tray")
        } else {
            List {
                if !list.favorites.isEmpty { Section("Favorites") { rows(list.favorites) } }
                if !list.recent.isEmpty { Section("Recent") { rows(list.recent) } }
                if !list.all.isEmpty { Section { rows(list.all) } }
            }
        }
    }

    private func rows(_ refs: [ObjectRef]) -> some View {
        ForEach(refs, id: \.nodeId) { ref in
            Button { onOpen(ref) } label: { Label(ref.name, systemImage: Names.icon(ref.application)) }
        }
    }
}

struct SpacesView: View {
    @Bindable var model: AppModel

    var body: some View {
        List {
            ForEach(model.spaces, id: \.scopeId) { space in
                Section(space.name) {
                    let inSpace = model.objects.filter { $0.scopeId == space.scopeId }
                    if inSpace.isEmpty { Text("Nothing here yet").foregroundStyle(.secondary) }
                    ForEach(inSpace, id: \.nodeId) { ref in
                        Button { model.open(ref) } label: { Label(ref.name, systemImage: Names.icon(ref.application)) }
                    }
                }
            }
        }
        .navigationTitle("Spaces")
    }
}

struct SearchList: View {
    @Bindable var model: AppModel
    @State private var query = ""

    var body: some View {
        let hits = query.trimmingCharacters(in: .whitespaces).isEmpty ? [] : model.objects.filter { $0.name.localizedCaseInsensitiveContains(query) }
        List(hits, id: \.nodeId) { ref in
            Button { model.search(false); model.open(ref) } label: { Label(ref.name, systemImage: Names.icon(ref.application)) }
        }
        .searchable(text: $query, prompt: "Search names")
        .navigationTitle("Search")
    }
}

struct ObjectPlaceholder: View {
    let ref: ObjectRef
    let tab: String

    var body: some View {
        ContentUnavailableView(ref.name, systemImage: Names.icon(ref.application), description: Text("This view is not built in the app yet."))
            .navigationTitle(ref.name)
    }
}

enum Names {
    static func application(_ app: ApplicationId) -> LocalizedStringKey {
        switch app {
        case .memories: "Memories"
        case .depot: "Depot"
        case .expenses: "Expenses"
        default: "Application"
        }
    }

    static func icon(_ app: ApplicationId) -> String {
        switch app {
        case .memories: "photo.on.rectangle"
        case .depot: "folder"
        case .expenses: "creditcard"
        default: "square"
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

    static func tabIcon(_ key: String) -> String {
        switch key {
        case "memories.map": "map"
        case "expenses.overview": "chart.bar"
        case "expenses.settings": "gearshape"
        case "depot.files": "doc"
        default: "list.bullet"
        }
    }
}
