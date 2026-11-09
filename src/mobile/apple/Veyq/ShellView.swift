import CoreGraphics
import SwiftUI
import VeyqCore

/// The shell (ADR 0122). The drawer chooses what the app is in — Home (a space's tree) or an application; the bar at
/// the bottom belongs to the chosen application — its own places, or those of its open object — and ends with Search,
/// which on iOS is the system's search tab. On a wide screen (iPad, Mac) the drawer stays open as the sidebar. Its look
/// is the web's (ADR 0123 decision 7).
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
        // A delete's notice and its Undo over the whole shell: a page under a tab bar draws no overlay above it.
        .overlay(alignment: .bottom) { TrashNotice(model: model) }
        .environment(\.rowDetails, model.rowDetails)
        .environment(\.covers, model.covers.mapValues(\.image))
        .environment(\.avatars, model.avatars)
        .environment(\.picking, model.incoming != nil)
        // An image or a PDF dragged onto the app — a screenshot's thumbnail, a photo from another app (ADR 0124 decision 9).
        .onDrop(of: IncomingFiles.accepted, isTargeted: nil) { providers in
            Task { model.receiveDrop(await IncomingFiles.read(providers)) }
            return true
        }
        .sheet(isPresented: Binding(get: { model.managing != nil }, set: { if !$0 { model.managing = nil } })) {
            ManageView(model: model)
        }
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

/// The drawer: the brand, the tenant and server switcher, New, Home, Favorites, Recent, the applications, then
/// settings and signing out of the current server.
struct DrawerList: View {
    @Bindable var model: AppModel

    var body: some View {
        List {
            Section {
                Brand().padding(.vertical, 6)
                TenantSwitcher(model: model)
                // the web's sidebar "New": what a level creates, opened on Home
                Menu {
                    Button("New Folder", systemImage: "folder.badge.plus") { model.requestNew(.folder) }
                    Button("New Pot", systemImage: "wallet.bifold") { model.requestNew(.pot) }
                } label: {
                    Label("New", systemImage: "plus").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Role.primary)
                .foregroundStyle(Role.onFill)
                .buttonBorderShape(.roundedRectangle(radius: Radius.control))
                row("Home", symbol: "house", selected: model.shell.isHome) { model.choose(nil) }
            }
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)

            Section { refs(model.drawer.favorites) } header: { SectionLabel(title: "Favorites") }
            Section { refs(model.drawer.recent) } header: { SectionLabel(title: "Recent") }
            Section {
                ForEach(model.applications, id: \.self) { app in
                    row(Names.application(app), symbol: Names.appSymbol(app), selected: model.shell.application == app && model.shell.openObject == nil) { model.choose(app) }
                }
            } header: { SectionLabel(title: "Applications") }
            Section {
                row("Settings", symbol: "gearshape", selected: false) { }
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

/// The switcher: the current tenant and server; every server's tenants, and adding a server (owner 2026-11-08: one
/// server can give several tenants, and the app connects to several servers).
struct TenantSwitcher: View {
    @Bindable var model: AppModel

    var body: some View {
        Menu {
            ForEach(model.servers) { server in
                Section(server.label) {
                    ForEach(server.tenants, id: \.tenantId) { tenant in
                        Button {
                            model.switchTo(server.account, tenant)
                        } label: {
                            if tenant.tenantId == model.tenant?.tenantId && server.label == model.server {
                                Label(tenant.name, systemImage: "checkmark")
                            } else {
                                Text(tenant.name)
                            }
                        }
                    }
                }
            }
            Divider()
            Button { model.openManage() } label: { Label("Manage servers", systemImage: "server.rack") }
        } label: {
            HStack(spacing: 10) {
                TenantAvatar(name: model.tenant?.name ?? "?")
                VStack(alignment: .leading, spacing: 1) {
                    Text(model.tenant?.name ?? "").fontWeight(.bold).foregroundStyle(Role.text).lineLimit(1)
                    Text(model.server).font(.caption).foregroundStyle(Role.muted).lineLimit(1)
                }
                Spacer(minLength: 0)
                if model.switching { ProgressView().controlSize(.small) } else {
                    Image(systemName: "chevron.up.chevron.down").font(.footnote.weight(.semibold)).foregroundStyle(Role.muted)
                }
            }
            .padding(10)
            .background(Role.hover, in: RoundedRectangle(cornerRadius: Radius.row, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Switch tenant or server")
    }
}

/// The web's tenant avatar: the initial on the accent's gradient (`.sidebar-tenant-avatar`).
struct TenantAvatar: View {
    let name: String

    var body: some View {
        Text(String(name.prefix(1)).uppercased())
            .font(.system(size: 15, weight: .heavy))
            .foregroundStyle(Role.onFill)
            .frame(width: 34, height: 34)
            .background(LinearGradient(colors: [Role.accent, Role.accent.opacity(0.72)], startPoint: .topLeading, endPoint: .bottomTrailing),
                        in: RoundedRectangle(cornerRadius: Radius.row, style: .continuous))
    }
}

/// The chosen application's bar: its places (or its open object's), then Search. Home has Search alone.
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

    /// The space whose trash is open.
    @State private var trashOf: TrashOf?

    var body: some View {
        TabView(selection: selection) {
            ForEach(tabs, id: \.self) { key in
                Tab(Names.tab(key, app: model.shell.application), systemImage: Names.tabSymbol(key, app: model.shell.application), value: key) {
                    NavigationStack { content(key).modifier(Picking(model: model)).toolbar { toolbar }.background(Role.bg) }
                }
            }
            Tab(value: Self.search, role: .search) {
                NavigationStack { SearchList(model: model).modifier(Picking(model: model)).toolbar { toolbar } }
            }
        }
        .sheet(item: $trashOf) { SpaceTrashSheet(model: model, scopeId: $0.scopeId) }
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .navigation) {
            if model.shell.canGoBack {
                Button { model.goBack() } label: { Image(systemName: "chevron.backward") }
            } else if let onMenu {
                Button(action: onMenu) { Image(systemName: "line.3.horizontal") }
            }
        }
        // An open object's header: core's actions for every kind (the web's `NodePage` + `NodeHeader`), with what its kind's
        // page contributes.
        ToolbarItem(placement: .primaryAction) {
            if let ref = model.shell.openObject, let contribution = KindPage.of(ref.kind)?.header(ref.at, model) {
                NodeHeaderActions(model: model, node: ref.at, contribution: contribution).id(ref.nodeId)
            } else if model.shell.openObject == nil, model.shell.application == nil, !model.shell.searching, let space = model.shell.space {
                // The space's trash, from its Home (the web's level toolbar): every application's nodes.
                Button { trashOf = TrashOf(scopeId: space) } label: { Image(systemName: "trash") }
                    .accessibilityLabel("Trash")
            }
        }
    }

    @ViewBuilder private func content(_ key: String) -> some View {
        if let ref = model.shell.openObject, let body = KindPage.of(ref.kind)?.body {
            body(ref, key, model)
        } else if let ref = model.shell.openObject {
            ObjectPlaceholder(ref: ref, tab: key)
        } else if let app = model.shell.application, app == .expenses {
            ExpensesList(model: model)
        } else if let app = model.shell.application {
            ArrangedListView(list: model.applicationList(app), onOpen: model.open)
                .navigationTitle(Names.application(app))
        } else {
            HomeView(model: model)
        }
    }
}

/// Home (ADR 0122): the shown space's tree, one level at a time, as the web's level view lists it — the folders, then
/// every application's objects; the filters at the top keep one application's objects, the folders stay. The space is
/// switched from the title (owner 2026-11-08), iOS' own title menu.
struct HomeView: View {
    @Bindable var model: AppModel
    @State private var naming = false
    @State private var newName = ""
    @State private var nameProblem: String?
    @State private var creatingPot = false

    private var title: String {
        if let folder = model.shell.folder { return folder.name }
        return Names.spaceName(model.space)
    }

    var body: some View {
        List {
            if model.filters.count > 1 {
                Picker("Filter", selection: Binding(get: { model.shell.filter }, set: { model.setFilter($0) })) {
                    ForEach(Array(model.filters.enumerated()), id: \.offset) { _, f in
                        Text(f.map { Names.application($0) } ?? "All").tag(f)
                    }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
            }
            let level = model.level
            if level.isEmpty {
                Text("Nothing here yet").foregroundStyle(Role.muted).listRowBackground(Color.clear)
            }
            ForEach(level, id: \.nodeId) { node in
                if let folder = node.asFolder() {
                    Button { model.enter(folder) } label: {
                        HStack(spacing: 12) {
                            if let cover = model.covers[node.nodeId]?.image {
                                Image(decorative: cover, scale: 1).resizable().scaledToFill()
                                    .frame(width: 34, height: 34)
                                    .clipShape(RoundedRectangle(cornerRadius: Radius.row, style: .continuous))
                            } else {
                                FolderTile()
                            }
                            Text(node.name).fontWeight(.bold).foregroundStyle(Role.text)
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.forward").font(.footnote.weight(.semibold)).foregroundStyle(Role.muted)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Role.surface)
                } else if let ref = node.asObject() {
                    ObjectRow(ref: ref, onOpen: model.open).listRowBackground(Role.surface)
                }
            }
        }
        // the level's "New" (the web's menu): a folder here, or a Pot here
        // a folder Home shows: core's actions on it, as the web's level header has them
        .toolbar {
            // one "More" menu (as Files' folder menu): a level's bar keeps its room for its title
            if let space = model.shell.space, let folder = model.shell.folderAt(scopeId: space), let contribution = KindPage.of(folder.kind)?.header(folder, model) {
                ToolbarItem(placement: .primaryAction) {
                    NodeHeaderActions(model: model, node: folder, contribution: contribution, maxButtons: 0).id(folder.nodeId)
                }
            }
        }
        .createMenu("New", shown: model.shell.space != nil) {
            Button("New Folder", systemImage: "folder.badge.plus") { newName = ""; nameProblem = nil; naming = true }
            Button("New Pot", systemImage: "wallet.bifold") { creatingPot = true }
        }
        .alert("New Folder", isPresented: $naming) {
            TextField("Name", text: $newName)
            Button("Create") {
                let name = newName
                Task {
                    if let problem = await model.createFolder(name: name) { nameProblem = problem; naming = true }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            if let nameProblem { Text(nameProblem) }
        }
        .sheet(isPresented: $creatingPot) { PotCreateSheet(model: model) }
        // what the drawer's "New" asked for
        .onChange(of: model.newRequest, initial: true) { _, asked in
            switch asked {
            case .folder: newName = ""; nameProblem = nil; naming = true
            case .pot: creatingPot = true
            case nil: return
            }
            model.newRequest = nil
        }
        .scrollContentBackground(.hidden)
        .navigationTitle(title)
        #if os(iOS)
        // The inline title is where iOS shows its title menu's chevron; a large title would hide the switcher.
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .modifier(SpaceSwitcherMenu(model: model, shown: model.shell.folder == nil))
    }
}

/// The space switcher in Home's title, at a space's root only: inside a folder the title is the folder's, and a title menu
/// with nothing in it would still take the bar's room with its chevron.
private struct SpaceSwitcherMenu: ViewModifier {
    @Bindable var model: AppModel
    let shown: Bool

    func body(content: Content) -> some View {
        if shown {
            content.toolbarTitleMenu {
                ForEach(model.spaces, id: \.scopeId) { space in
                    Button { model.switchSpace(space.scopeId) } label: {
                        Label(Names.spaceName(space), systemImage: space.scopeId == model.shell.space ? "checkmark" : Names.spaceSymbol(space.kind))
                    }
                }
            }
        } else {
            content
        }
    }
}

/// The web's folder tile: the info role's soft fill and text.
struct FolderTile: View {
    var body: some View {
        Image(systemName: "folder")
            .font(.system(size: 15, weight: .medium))
            .foregroundStyle(Color("veyq-info-text"))
            .frame(width: 34, height: 34)
            .background(Color("veyq-info-soft"), in: RoundedRectangle(cornerRadius: Radius.row, style: .continuous))
    }
}

/// What each object's row says beside its name, by node id (`AppModel.rowDetails`).
extension EnvironmentValues {
    @Entry var rowDetails: [String: [String]] = [:]
    /// Each listed node's cover, by node id (`AppModel.covers`).
    @Entry var covers: [String: CGImage] = [:]
}

/// One object as a row of the web's lists: its kind's tile, its name in bold, and the application's facts about it (a
/// Pot: its currency), left out when it has none.
struct ObjectRow: View {
    let ref: ObjectRef
    let onOpen: (ObjectRef) -> Void
    @Environment(\.rowDetails) private var details
    @Environment(\.picking) private var picking
    @Environment(\.covers) private var covers

    /// Picking a place for what was shared: only a kind that takes it can be chosen (ADR 0124 decision 1).
    private var takes: Bool { !picking || ref.kind == PotShare.shared.KIND }

    var body: some View {
        Button { onOpen(ref) } label: {
            HStack(spacing: 12) {
                // the node's cover where it has one (core's row visual), else its kind's tile
                if let cover = covers[ref.nodeId] {
                    Image(decorative: cover, scale: 1).resizable().scaledToFill()
                        .frame(width: 34, height: 34)
                        .clipShape(RoundedRectangle(cornerRadius: Radius.row, style: .continuous))
                } else {
                    Tile(symbol: Names.kindSymbol(ref.kind))
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(ref.name).fontWeight(.bold).foregroundStyle(Role.text)
                    if let d = details[ref.nodeId], !d.isEmpty { Text(d.joined(separator: " · ")).font(.caption).foregroundStyle(Role.muted) }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.forward").font(.footnote.weight(.semibold)).foregroundStyle(Role.muted)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!takes)
        .opacity(takes ? 1 : 0.38)
    }
}

extension EnvironmentValues {
    /// Whether the shell is picking a place for what another app shared (ADR 0124).
    @Entry var picking = false
}

/// A space whose trash is open.
struct TrashOf: Identifiable {
    let scopeId: String
    var id: String { scopeId }
}

/// Picking a place for what another app shared (ADR 0124): its banner below a page's navigation bar, which an inset on
/// the shell would slide under.
struct Picking: ViewModifier {
    let model: AppModel

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .top, spacing: 0) {
            if let incoming = model.incoming { ShareBanner(incoming: incoming, onCancel: model.cancelShare) }
        }
    }
}

/// The pick mode's banner: what waits and where it can go, with its cancel.
struct ShareBanner: View {
    let incoming: AppModel.Incoming
    let onCancel: () -> Void

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(incoming.files.isEmpty ? String(localized: "Nothing shared can go here: only images and PDFs up to 10 MB.")
                     : incoming.files.count == 1 ? String(localized: "Choose a Pot for this file") : String(localized: "Choose a Pot for these \(incoming.files.count) files"))
                    .fontWeight(.semibold)
                if incoming.refused > 0 {
                    Text("\(incoming.refused) left out: only images and PDFs up to 10 MB.").font(.caption).foregroundStyle(Role.muted)
                }
            }
            Spacer()
            Button("Cancel", action: onCancel)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Role.accentSoft, ignoresSafeAreaEdges: [])
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

/// Expenses' own list (its Pots), with "New pot" (uc.expenses.create-pot).
struct ExpensesList: View {
    @Bindable var model: AppModel
    @State private var creating = false

    var body: some View {
        ArrangedListView(list: model.applicationList(.expenses), onOpen: model.open)
            .navigationTitle(Names.application(.expenses))
            .createAction("New pot", shown: !model.spaces.isEmpty) { creating = true }
            .sheet(isPresented: $creating) { PotCreateSheet(model: model) }
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

    static func spaceName(_ space: SpaceRef?) -> String {
        switch space?.kind {
        case nil: String(localized: "Home")
        case "PERSONAL": String(localized: "Personal")
        case "GROUP": String(localized: "Group")
        default: space!.name
        }
    }

    static func spaceSymbol(_ kind: String) -> String {
        switch kind {
        case "PERSONAL": "person"
        case "GROUP": "person.2"
        default: "house"
        }
    }

    static func tab(_ key: String, app: ApplicationId?) -> LocalizedStringKey {
        switch key {
        case "memories.all": "Memories"
        case "memories.map": "Map"
        case "depot.file": "File"
        case "expenses.entries": "Entries"
        case "expenses.overview": "Overview"
        case "expenses.settings": "Settings"
        default: app.map(application) ?? "Home"
        }
    }

    static func tabSymbol(_ key: String, app: ApplicationId?) -> String {
        switch key {
        case "memories.all": "photo.on.rectangle.angled"
        case "memories.map": "map"
        case "expenses.entries": "list.bullet"
        case "expenses.overview": "chart.bar"
        case "expenses.settings": "gearshape"
        case "depot.file": "doc"
        default: app.map(appSymbol) ?? "house"
        }
    }
}
