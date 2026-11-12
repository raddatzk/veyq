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
        // An image or a PDF dragged onto the app — a screenshot's thumbnail, a photo from another app (ADR 0129 decision 9).
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
            // as wide as the switcher's tenant and server need (the system's default left them cut on a Mac)
            DrawerList(model: model, atTop: true).navigationSplitViewColumnWidth(min: 240, ideal: 280, max: 360)
        } detail: {
            Bar(model: model, tabbed: false)
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
    /// The split view's sidebar, always beside: what it opens is a top-level destination with no back (core's
    /// `Shell.openAtTop`); the iPhone's drawer opens as a list does, back leading where it was.
    var atTop = false

    // Settings pinned at the foot, below what scrolls (owner 2026-11-10)
    var body: some View {
        VStack(spacing: 0) {
            list
            Divider()
            Button { } label: {
                HStack(spacing: 12) {
                    Tile(symbol: "gearshape")
                    Text(say(.drawerSettings)).foregroundStyle(Role.controlText)
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        .background { Chrome() }
    }

    private var list: some View {
        List {
            Section {
                Brand().padding(.vertical, 6)
                TenantSwitcher(model: model)
                // the web's sidebar "New": what a level creates, opened on Home
                Menu {
                    ForEach(AppKinds.shared.drawerCreates, id: \.self) { c in
                        Button(say(c.word), systemImage: Names.createSymbol(c)) { model.requestNew(c == .pot ? .pot : .folder) }
                    }
                } label: {
                    // drawn here: a Mac draws a bordered menu as its own grey pop-up, whatever its button style
                    // drawn by its own style: a Mac's menu draws a label's icon in its own control colour, never the
                    // label's (measured 2026-11-10: black on the green)
                    Label(say(.drawerNew), systemImage: "plus")
                        .labelStyle(FilledLabel())
                        .fontWeight(.semibold)
                        .foregroundStyle(Role.onFill)
                        .frame(maxWidth: .infinity, minHeight: 34)
                        .background(Role.primary, in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
                        .contentShape(RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
                }
                .menuStyle(.button)
                .buttonStyle(.plain)
                .menuIndicator(.hidden)
                row(say(.drawerHome), symbol: "house", selected: model.shell.isHome) { model.choose(nil) }
            }
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)

            Section { refs(model.drawer.favorites) } header: { SectionLabel(title: say(.drawerFavorites)) }
            Section { refs(model.drawer.recent) } header: { SectionLabel(title: say(.drawerRecent)) }
            Section {
                ForEach(model.applications, id: \.self) { app in
                    row(Names.application(app), symbol: Names.appSymbol(app), selected: model.shell.application == app && model.shell.openObject == nil) { model.choose(app) }
                }
            } header: { SectionLabel(title: say(.drawerApplications)) }
        }
        .listStyle(.sidebar)
        .scrollContentBackground(.hidden)
    }

    @ViewBuilder private func refs(_ list: [ObjectRef]) -> some View {
        if list.isEmpty {
            Text(say(.drawerNothingYet)).font(.subheadline).foregroundStyle(Role.muted).listRowBackground(Color.clear)
        }
        ForEach(list, id: \.nodeId) { ref in
            row(ref.name, symbol: Names.kindSymbol(ref.kind),
                selected: model.shell.openObject?.nodeId == ref.nodeId) { if atTop { model.openAtTop(ref) } else { model.open(ref) } }
        }
    }

    /// A sidebar row as the web draws it: hover and selection are only the `--app-hover` fill, never bold, never a bar.
    private func row(_ title: String, symbol: String, selected: Bool, action: @escaping () -> Void) -> some View {
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

/// A label on a filled control: icon and title side by side in the colour the label is given.
private struct FilledLabel: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 6) { configuration.icon; configuration.title }
    }
}

/// The switcher (owner 2026-11-10: spaces and tenants in one): the shown space, its tenant and server under it; the
/// tenant's spaces first — a space chosen opens its Home —, then every server's tenants (owner 2026-11-08: one server can
/// give several tenants, and the app connects to several servers), and managing servers.
struct TenantSwitcher: View {
    @Bindable var model: AppModel

    /// The tenant and the server under the space's name (core's `ShellWords.switcherSubtitle`).
    private var subtitle: String { ShellWords.shared.switcherSubtitle(space: model.space, tenant: model.tenant?.name ?? "", server: model.server) }

    var body: some View {
        Menu {
            Section(model.tenant?.name ?? "") {
                ForEach(model.spaces, id: \.scopeId) { space in
                    Button { model.switchSpace(space.scopeId); model.drawerOpen = false } label: {
                        Label(Names.spaceName(space), systemImage: space.scopeId == model.shell.space ? "checkmark" : Names.spaceSymbol(space.kind))
                    }
                }
            }
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
            Button { model.openManage() } label: { Label(say(.switcherManage), systemImage: "server.rack") }
        } label: {
            HStack(spacing: 10) {
                TenantAvatar(name: model.tenant?.name ?? "?")
                VStack(alignment: .leading, spacing: 1) {
                    Text(Names.spaceName(model.space)).fontWeight(.bold).foregroundStyle(Role.text).lineLimit(1)
                    Text(subtitle).font(.caption).foregroundStyle(Role.muted).lineLimit(1)
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
        .accessibilityLabel(say(.switcherOpen))
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
    /// The places as a tab bar (an iPhone), or — false, in a split view's detail column (an iPad, a Mac) — one page whose
    /// places are a segmented control in its toolbar: a tab view there showed no navigation bar, so no title, back or
    /// header actions, on an iPad (measured 2026-11-10).
    var tabbed = true

    private static let search = "search"
    private static let list = "list"

    /// Where the places' control goes: a Mac's toolbar centre; on an iPad beside back, since its centre (`.principal`)
    /// takes the inline title's place and left the subtitle under it, a second time below the large title (2026-11-10).
    private static var places: ToolbarItemPlacement {
        #if os(macOS)
        .principal
        #else
        .topBarLeading
        #endif
    }

    /// Back's place: on iOS the places' own, the leading end, so back stands before them.
    private static var back: ToolbarItemPlacement {
        #if os(macOS)
        .navigation
        #else
        .topBarLeading
        #endif
    }

    /// Where a page's actions go: the trailing end. A Mac puts `.primaryAction` at the leading one, beside the title.
    private static var actions: ToolbarItemPlacement {
        #if os(macOS)
        .automatic
        #else
        .primaryAction
        #endif
    }

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

    /// What the split view's toolbar search field holds; searching while it is not empty.
    @State private var query = ""
    /// The space whose trash is open.
    @State private var trashOf: TrashOf?
    /// The space whose top level's "Add files only" share is open.
    @State private var uploadShareOf: TrashOf?

    @ViewBuilder private var main: some View {
        if tabbed {
            TabView(selection: selection) {
                ForEach(tabs, id: \.self) { key in
                    Tab(Names.tab(key, app: model.shell.application, kind: model.shell.openObject?.kind), systemImage: Names.tabSymbol(key, app: model.shell.application, kind: model.shell.openObject?.kind), value: key) {
                        NavigationStack { content(key).modifier(Picking(model: model)).toolbar { toolbar }.background(Role.bg) }
                    }
                }
                Tab(value: Self.search, role: .search) {
                    NavigationStack { SearchList(model: model).modifier(Picking(model: model)).toolbar { toolbar } }
                }
            }
        } else {
            NavigationStack {
                Group {
                    if model.shell.searching { SearchHits(model: model, query: query) } else { content(selection.wrappedValue) }
                }
                .modifier(Picking(model: model))
                // Search in one place on every page: the toolbar's field at its trailing end, as the system's own apps
                // on an iPad and a Mac (owner 2026-11-10: a segment here, a button there was two places)
                .searchable(text: $query, placement: .toolbar, prompt: say(.searchHint))
                .onChange(of: query) { _, typed in if typed.isEmpty == model.shell.searching { model.search(!typed.isEmpty) } }
                .onChange(of: model.shell.searching) { _, on in if !on { query = "" } }
                .toolbar {
                    toolbar
                    // the bar's places: a segmented control, as the system's own apps on an iPad and a Mac
                    if !model.shell.barTabs.isEmpty {
                        // apart from back: the control is a capsule of its own, never inside back's glass (an iPad
                        // grouped both, the control's capsule nested in it, owner 2026-11-10)
                        ToolbarSpacer(.fixed, placement: Self.places)
                        ToolbarItem(placement: Self.places) {
                            Picker(say(.tabSearch), selection: selection) {
                                // symbols, their words for VoiceOver: a word each runs over the back button beside the sidebar
                                ForEach(tabs, id: \.self) { key in
                                    segment(Names.tab(key, app: model.shell.application, kind: model.shell.openObject?.kind), symbol: Names.tabSymbol(key, app: model.shell.application, kind: model.shell.openObject?.kind)).tag(key)
                                }
                            }
                            .pickerStyle(.segmented)
                            .fixedSize()
                        }
                        .sharedBackgroundVisibility(.hidden)
                    }
                }
                .background(Role.bg)
            }
        }
    }

    /// A segment of the places' control: its symbol, its word for VoiceOver and the pointer's tooltip — a `Label` there
    /// read out the symbol's own name ("gearshape", measured 2026-11-10 on a Mac).
    private func segment(_ word: String, symbol: String) -> some View {
        Image(systemName: symbol).accessibilityLabel(Text(word)).help(word)
    }

    var body: some View {
        main
        .sheet(item: $trashOf) { SpaceTrashSheet(model: model, scopeId: $0.scopeId) }
        .sheet(item: $uploadShareOf) { TopLevelUploadSheet(model: model, scopeId: $0.scopeId) }
        // what the model says once (an upload's refusal, its "Replace anyway")
        // its dismissal may run before its button's action (ADR 0127's trap): the action is the presented notice's own
        .alert(model.notice?.text ?? "", isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice?.done?(); model.notice = nil } }), presenting: model.notice) { n in
            if let action = n.action, let run = n.run {
                Button(action, role: .destructive) { Task { await run() } }
                Button(say(.signinCancel), role: .cancel) {}
            } else {
                Button(say(.ok), role: .cancel) {}
            }
        }
        // "Send a Copy…": what happens is said first — the copy leaves veyq decrypted —, then the system's share sheet
        .alert(say(.fileSendTitle), isPresented: Binding(get: { model.sendingCopy != nil }, set: { if !$0 { model.sendingCopy = nil } }), presenting: model.sendingCopy) { node in
            Button(say(.fileSendTitle)) {
                Task {
                    if let copy = await model.copyForSending(node) { model.sentCopy = copy } else {
                        model.notice = AppModel.Notice(text: say(.downloadFailed, node.name))
                    }
                }
            }
            Button(say(.signinCancel), role: .cancel) {}
        } message: { node in
            Text(say(.fileSendNotice, node.name))
        }
        #if os(iOS)
        .sheet(item: Binding(get: { model.sentCopy }, set: { model.sentCopy = $0 })) { copy in
            ActivitySheet(url: copy.url) { model.clearSentCopies() }
        }
        #endif
        // a file's "Replace…": the system's picker hands over one file
        .fileImporter(isPresented: Binding(get: { model.replacing != nil }, set: { if !$0 { model.replacing = nil } }), allowedContentTypes: [.item]) { result in
            guard let file = model.replacing, case .success(let url) = result else { return }
            model.replacing = nil
            model.replaceFile(file, url: url)
        }
        .overlay(alignment: .top) {
            if let name = model.depotState?.preparing {
                VStack(spacing: 4) {
                    ProgressView()
                    Text(say(.uploadPreparingFolder, name)).font(.footnote).foregroundStyle(Role.muted)
                }
                .padding(10)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                .padding(.top, 8)
            }
        }
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        // back where the places' control is too (an iPad's leading end), so it stands before it, not after
        ToolbarItem(placement: Self.back) {
            if model.shell.canGoBack {
                Button { model.goBack() } label: { Image(systemName: "chevron.backward") }
            } else if let onMenu {
                Button(action: onMenu) { Image(systemName: "line.3.horizontal") }
            }
        }
        // the title's room in the split view: the page's actions and Search keep the trailing end, also where the title
        // is Home's space switcher and takes less
        if !tabbed { ToolbarSpacer(.flexible) }
        // what is being uploaded (core's queue), while something is
        ToolbarItem(placement: Self.actions) { UploadsButton(model: model) }
        // An open object's header: core's actions for every kind (the web's `NodePage` + `NodeHeader`), with what its kind's
        // page contributes.
        ToolbarItem(placement: Self.actions) {
            if let ref = model.shell.openObject, let contribution = KindPage.of(ref.kind)?.header(ref.at, model) {
                NodeHeaderActions(model: model, node: ref.at, contribution: contribution).id(ref.nodeId)
            } else if model.shell.openObject == nil, model.shell.application == nil, !model.shell.searching, let space = model.shell.space {
                HStack {
                    // the top level is no link's root: its share offers "Add files only" alone (the web's level share)
                    if model.shell.folderAt(scopeId: space) == nil, model.linkRules().on, model.linkRules().dropBox {
                        Button { uploadShareOf = TrashOf(scopeId: space) } label: { Image(systemName: "square.and.arrow.up") }
                            .accessibilityLabel(say(.shareRoleUploader))
                    }
                    // The space's trash, from its Home (the web's level toolbar): every application's nodes.
                    Button { trashOf = TrashOf(scopeId: space) } label: { Image(systemName: "trash") }
                        .accessibilityLabel(say(.trashTitle))
                }
            }
        }
    }

    /// The head of the node on screen (core's, for every kind): its body places it first in its list.
    private func hero(_ node: NodeAt) -> NodeHero {
        NodeHero(model: model, node: node, canSet: AppKinds.shared.coverSettable(kind: node.kind, facts: KindPage.of(node.kind)?.header(node, model)?.facts))
    }

    @ViewBuilder private func content(_ key: String) -> some View {
        if let ref = model.shell.openObject, let body = KindPage.of(ref.kind)?.body {
            body(ref, key, model).environment(\.nodeHero, hero(ref.at))
        } else if let ref = model.shell.openObject {
            ObjectPlaceholder(ref: ref, tab: key)
        } else if let app = model.shell.application, app == .expenses {
            ExpensesList(model: model)
        } else if let app = model.shell.application {
            ArrangedListView(list: model.applicationList(app), onOpen: model.open, key: "app:\(app)")
                .navigationTitle(Names.application(app))
        } else {
            HomeView(model: model).environment(\.nodeHero, model.shell.space.flatMap { model.shell.folderAt(scopeId: $0) }.map(hero))
        }
    }
}

/// Home (ADR 0122): the shown space's tree, one level at a time, as the web's level view lists it — the folders, then
/// every application's objects; the filters at the top keep one application's objects, the folders stay. The space is
/// switched in the drawer's switcher, with the tenants (owner 2026-11-10); the title is its name.
struct HomeView: View {
    @Bindable var model: AppModel
    @State private var naming = false
    @State private var newName = ""
    @State private var nameProblem: String?
    @State private var creatingPot = false
    @State private var pickingFiles = false
    @State private var pickingFolder = false

    /// A group's rows: a folder enters, an object opens.
    @ViewBuilder private func rows(_ nodes: [NodeRef]) -> some View {
        ForEach(nodes, id: \.nodeId) { node in
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

    /// This level among the lists whose scroll is kept (`KeepsScroll`).
    private var levelKey: String { "home:\(model.shell.space ?? "")/\(model.shell.folder?.nodeId ?? "")" }

    private var title: String {
        if let folder = model.shell.folder { return folder.name }
        return Names.spaceName(model.space)
    }

    var body: some View {
        level.modifier(KeepsScroll(key: levelKey))
    }

    private var level: some View {
        List {
            NodeHeroRow()
            if model.filters.count > 1 {
                Picker(say(.filter), selection: Binding(get: { model.shell.filter }, set: { model.setFilter($0) })) {
                    ForEach(Array(model.filters.enumerated()), id: \.offset) { _, f in
                        Text(f.map { Names.application($0) } ?? say(.filterAll)).tag(f)
                    }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
            }
            let groups = model.levelGroups
            if groups.isEmpty {
                Text(say(.listEmpty)).foregroundStyle(Role.muted).listRowBackground(Color.clear)
            }
            // the folders apart from the rest, as the web's level view (core's `Home.groups`)
            ForEach(groups, id: \.folders) { group in
                Section {
                    rows(group.nodes)
                } header: {
                    if let heading = group.heading { SectionLabel(title: say(heading)) }
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
        // nothing is created in a guest's folder (the web's `folderRights`: no "New" there)
        .createMenu(say(.drawerNew), shown: model.canCreateHere) {
            ForEach(AppKinds.shared.homeCreates, id: \.self) { c in
                Button(say(c.word), systemImage: Names.createSymbol(c)) {
                    switch c {
                    case .pot: creatingPot = true
                    case .uploadFiles: pickingFiles = true
                    case .uploadFolder: pickingFolder = true
                    default: newName = ""; nameProblem = nil; naming = true
                    }
                }
            }
        }
        // Depot's "Upload files": the system's picker hands over the files chosen, uploaded here one after another
        .fileImporter(isPresented: $pickingFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result, !urls.isEmpty else { return }
            model.uploadFiles(urls)
        }
        // Depot's "Upload folder…": the folder picker hands over one directory, uploaded here whole (a second importer on its
        // own view: SwiftUI presents only one per view)
        .background(EmptyView().fileImporter(isPresented: $pickingFolder, allowedContentTypes: [.folder]) { result in
            guard case .success(let url) = result else { return }
            Task { await model.uploadFolder(url) }
        })
        .alert(say(.folderNew), isPresented: $naming) {
            TextField(say(.peopleName), text: $newName)
            Button(say(.createPotCreate)) {
                let name = newName
                Task {
                    if let problem = await model.createFolder(name: name) { nameProblem = problem; naming = true }
                }
            }
            Button(say(.signinCancel), role: .cancel) {}
        } message: {
            if let nameProblem { Text(nameProblem) }
        }
        .sheet(isPresented: $creatingPot) { NewPotSheet(model: model) }
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

    /// Picking a place for what was shared: only a kind that takes it can be chosen (ADR 0129 decision 1).
    private var takes: Bool { !picking || AppKinds.shared.takesShared(kind: ref.kind) }

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
    /// Whether the shell is picking a place for what another app shared (ADR 0129).
    @Entry var picking = false
}

/// A space whose trash is open.
struct TrashOf: Identifiable {
    let scopeId: String
    var id: String { scopeId }
}

/// Picking a place for what another app shared (ADR 0129): its banner below a page's navigation bar, which an inset on
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
                Text(incoming.files.isEmpty ? say(.shareNothing)
                     : say(.sharePick, incoming.files.count))
                    .fontWeight(.semibold)
                if incoming.refused > 0 {
                    Text(say(.shareRefused, incoming.refused)).font(.caption).foregroundStyle(Role.muted)
                }
            }
            Spacer()
            Button(say(.signinCancel), action: onCancel)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Role.accentSoft, ignoresSafeAreaEdges: [])
    }
}

struct ArrangedListView: View {
    let list: ArrangedList
    let onOpen: (ObjectRef) -> Void
    /// Which list this is, for where it was scrolled to (`KeepsScroll`).
    let key: String

    var body: some View {
        if list.favorites.isEmpty && list.recent.isEmpty && list.all.isEmpty {
            ContentUnavailableView(say(.listEmpty), systemImage: "tray")
        } else {
            List {
                if !list.favorites.isEmpty { Section { rows(list.favorites) } header: { SectionLabel(title: say(.drawerFavorites)) } }
                if !list.recent.isEmpty { Section { rows(list.recent) } header: { SectionLabel(title: say(.drawerRecent)) } }
                if !list.all.isEmpty { Section { rows(list.all) } }
            }
            .scrollContentBackground(.hidden)
            .modifier(KeepsScroll(key: key))
        }
    }

    private func rows(_ refs: [ObjectRef]) -> some View {
        ForEach(refs, id: \.nodeId) { ref in ObjectRow(ref: ref, onOpen: onOpen).listRowBackground(Role.surface) }
    }
}

/// A list keeps where it was scrolled to across leaving the view tree (the owner's report, 2026-11-12: a Pot opened near the
/// bottom came back to a list at its top). The bar belongs to the open object (ADR 0122), so opening one replaces the
/// list's tab and its `NavigationStack`; the list is composed anew on the way back, at its top. `ScrollViewReader.scrollTo`
/// and `scrollPosition` moved nothing in that list (iOS 27, measured 2026-11-12), so the offset itself is kept per list,
/// on the list's own `UIScrollView`, which joins the hierarchy a moment after its background does. A Mac shows the list
/// beside the object in its split view and needs none of it.
struct KeepsScroll: ViewModifier {
    let key: String

    func body(content: Content) -> some View {
        #if os(iOS)
        content.background(ScrollKeeper(key: key))
        #else
        content
        #endif
    }
}

#if os(iOS)
private struct ScrollKeeper: UIViewRepresentable {
    let key: String

    func makeUIView(context: UIViewRepresentableContext<ScrollKeeper>) -> ScrollKeeperView { ScrollKeeperView(key: key) }
    func updateUIView(_ view: ScrollKeeperView, context: UIViewRepresentableContext<ScrollKeeper>) { view.show(key) }
}

/// Finds the list's scroll view beside it, records its offset per key while shown and puts a kept one back.
final class ScrollKeeperView: UIView {
    /// Each list's offset, for the app's run (a place to come back to, not a preference).
    @MainActor static var offsets: [String: CGFloat] = [:]
    private var key: String
    private weak var scroll: UIScrollView?
    private var watch: NSKeyValueObservation?
    /// While a kept offset is being put back, the list's own first layouts are not recorded as its offset.
    private var restoring = false

    init(key: String) {
        self.key = key
        super.init(frame: .zero)
        isUserInteractionEnabled = false
    }

    required init?(coder: NSCoder) { nil }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        // a list leaving the screen is torn down over a few passes, its offset moved meanwhile: nothing of that is kept
        guard window != nil else {
            watch?.invalidate()
            watch = nil
            scroll = nil
            return
        }
        find(tries: 6)
    }

    /// The list's scroll view, once it is there: it joins the hierarchy after its background does.
    private func find(tries: Int) {
        guard window != nil, scroll == nil else { return }
        guard let found = Self.nearestScroll(from: self) else {
            if tries > 0 { DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in self?.find(tries: tries - 1) } }
            return
        }
        scroll = found
        watch = found.observe(\.contentOffset) { [weak self] view, _ in
            let y = view.contentOffset.y
            MainActor.assumeIsolated {
                guard let self, !self.restoring, self.window != nil else { return }
                Self.offsets[self.key] = y
            }
        }
        restore()
    }

    /// Another list in the same scroll view (Home's next level): its own offset, or its top.
    func show(_ next: String) {
        guard next != key else { return }
        key = next
        restore()
    }

    private func restore() {
        guard let scroll, let y = Self.offsets[key] else { return }
        restoring = true
        // the list lays out its rows over a few passes: put the offset back after them, within what it can scroll
        for delay in [0.0, 0.15, 0.4, 0.8] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self, weak scroll] in
                guard let self, let scroll else { return }
                let top = -scroll.adjustedContentInset.top
                let bottom = max(top, scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)
                scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: min(max(y, top), bottom)), animated: false)
                if delay == 0.8 { self.restoring = false }
            }
        }
    }

    /// The scroll view nearest to `view` among its ancestors' descendants: the list it is the background of.
    private static func nearestScroll(from view: UIView) -> UIScrollView? {
        var level: UIView? = view.superview
        for _ in 0..<6 {
            guard let here = level else { return nil }
            if let s = here as? UIScrollView { return s }
            var queue = here.subviews
            while !queue.isEmpty {
                let next = queue.removeFirst()
                if let s = next as? UIScrollView { return s }
                queue.append(contentsOf: next.subviews)
            }
            level = here.superview
        }
        return nil
    }
}
#endif

/// The iPhone's Search tab: its own field over the hits.
struct SearchList: View {
    @Bindable var model: AppModel
    @State private var query = ""

    var body: some View {
        SearchHits(model: model, query: query).searchable(text: $query, prompt: say(.searchHint))
    }
}

/// What a query finds among the tenant's objects; opening one ends the search.
struct SearchHits: View {
    @Bindable var model: AppModel
    let query: String

    var body: some View {
        let hits = ShellWords.shared.search(objects: model.objects, query: query)
        List(hits, id: \.nodeId) { ref in
            ObjectRow(ref: ref) { r in model.search(false); model.open(r) }
        }
        .navigationTitle(say(.tabSearch))
    }
}

struct ObjectPlaceholder: View {
    let ref: ObjectRef
    let tab: String

    var body: some View {
        ContentUnavailableView {
            Label { Text(ref.name) } icon: { Tile(symbol: Names.kindSymbol(ref.kind)) }
        } description: {
            Text(say(.notBuilt))
        }
        .navigationTitle(ref.name)
    }
}

/// Expenses' own list (its Pots), with "New pot" (uc.expenses.create-pot).
struct ExpensesList: View {
    @Bindable var model: AppModel
    @State private var creating = false

    var body: some View {
        ArrangedListView(list: model.applicationList(.expenses), onOpen: model.open, key: "app:\(ApplicationId.expenses)")
            .navigationTitle(Names.application(.expenses))
            .createAction(say(.createPot), shown: !model.spaces.isEmpty) { creating = true }
            .sheet(isPresented: $creating) { NewPotSheet(model: model) }
    }
}

/// The web's motifs (src/web/src/shared/ui/icons.ts) in SF Symbols: images, file, inbox, wallet, folder, link.
enum Names {
    static func application(_ app: ApplicationId) -> String { say(AppKinds.shared.applicationWord(app: app)) }

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

    static func spaceName(_ space: SpaceRef?) -> String { say(piece: ShellWords.shared.space(space: space)) }

    /// A "New" entry's symbol (the web's motifs in SF Symbols).
    static func createSymbol(_ c: AppKinds.Create) -> String {
        switch c {
        case .pot: "wallet.bifold"
        case .uploadFiles: "square.and.arrow.up.on.square"
        default: "folder.badge.plus"
        }
    }

    static func spaceSymbol(_ kind: String) -> String {
        switch kind {
        case "PERSONAL": "person"
        case "GROUP": "person.2"
        default: "house"
        }
    }

    /// A tab's name; Depot's one tab names the object open, a file or a drop box (`kind`).
    static func tab(_ key: String, app: ApplicationId?, kind: String? = nil) -> String {
        // a bar with no places of its own (Home, an application's list) holds one tab here, named by where it stands
        if key == "list" { return app.map(application) ?? say(.drawerHome) }
        return say(AppKinds.shared.tabWord(tab: BarTab(key: key), objectKind: kind))
    }

    static func tabSymbol(_ key: String, app: ApplicationId?, kind: String? = nil) -> String {
        switch key {
        case "memories.all": "photo.on.rectangle.angled"
        case "memories.map": "map"
        case "expenses.entries": "list.bullet"
        case "expenses.overview": "chart.bar"
        case "expenses.settings": "gearshape"
        case "depot.file": kind == DepotFiles.shared.DROPBOX ? "tray" : "doc"
        default: app.map(appSymbol) ?? "house"
        }
    }
}

#if os(iOS)
/// The system's share sheet for one file (a decrypted copy the person chose to send); [done] when it closes.
struct ActivitySheet: UIViewControllerRepresentable {
    let url: URL
    let done: () -> Void

    func makeUIViewController(context: UIViewControllerRepresentableContext<ActivitySheet>) -> UIActivityViewController {
        let vc = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        vc.completionWithItemsHandler = { _, _, _, _ in done() }
        return vc
    }

    func updateUIViewController(_ vc: UIActivityViewController, context: UIViewControllerRepresentableContext<ActivitySheet>) {}
}
#endif
