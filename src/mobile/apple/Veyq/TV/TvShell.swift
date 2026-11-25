import SwiftUI
import VeyqCore

/// The shell on a TV (ADR 0159 decision 6): the drawer's content as tvOS's tab bar at the top — Home, each application
/// (core's `DrawerSection.APPLICATIONS`), Search, Settings — and the page the shell is at under it: Home's level with
/// the favourites and the recent above it, an application's arranged list, an open object as its kind's TV page. Every
/// list and plan is the core's, which on a TV holds no authoring and no download (decision 2); nothing is filtered here.
/// Only the chosen tab draws its page: one shell, one page model open at a time.
struct TvShell: View {
    @Bindable var model: TvModel

    var body: some View {
        TabView(selection: Binding(get: { model.tab }, set: { chosen($0) })) {
            Tab(say(.drawerHome), systemImage: "house", value: TvTab.home) {
                if model.tab == TvTab.home { TvPlace(model: model) }
            }
            ForEach(applications, id: \.name) { app in
                Tab(TvNames.application(app), systemImage: TvNames.appSymbol(app), value: TvTab.app(app)) {
                    if model.tab == TvTab.app(app) { TvPlace(model: model) }
                }
            }
            Tab(value: TvTab.search, role: .search) {
                if model.tab == TvTab.search { TvSearch(model: model) }
            }
            Tab(say(.drawerSettings), systemImage: "gearshape", value: TvTab.settings) {
                if model.tab == TvTab.settings { TvSettings(model: model) }
            }
        }
    }

    /// The applications' tabs: the drawer's sections drawn whole — on a TV its New is empty (the core's TV form creates
    /// nothing), its favourites and recent are Home's shelves, its applications are tabs.
    private var applications: [ApplicationId] {
        guard let lists = model.lists, let m = model.shellModel else { return [] }
        var apps: [ApplicationId] = []
        for section in m.drawerSections(lists: lists) {
            switch section.entry {
            case .applications: apps = lists.applications
            case .theNew, .favorites, .recent: break
            }
        }
        return apps
    }

    private func chosen(_ tab: String) {
        model.input()
        let was = model.tab
        model.tab = tab
        if was == TvTab.search, tab != TvTab.search { model.searchLeft() }
        switch tab {
        case TvTab.home: model.choose(nil)
        case TvTab.search, TvTab.settings: break
        default:
            if let app = applications.first(where: { TvTab.app($0) == tab }) { model.choose(app) }
        }
    }
}

/// Where the shell is, under a tab: an open object, a person's page, a kept link, or the list of the tab.
struct TvPlace: View {
    @Bindable var model: TvModel

    var body: some View {
        VStack(spacing: 0) {
            Group {
                if let shell = model.shell {
                    if let ref = shell.openObject {
                        TvObject(model: model, ref: ref)
                    } else if let person = shell.person {
                        TvPerson(model: model, userId: person)
                    } else if shell.bookmark != nil {
                        // a kept link is a received link: a TV opens none (ADR 0159 decision 2, inbox #1351)
                        TvMessage(text: say(.tvOpensElsewhere))
                    } else if let app = shell.application, let lists = model.lists {
                        TvArranged(model: model, title: TvNames.application(app), list: lists.applicationList(app: app))
                    } else {
                        TvHome(model: model)
                    }
                } else {
                    ProgressView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            TvMusicBar(model: model)
        }
        .onExitCommand(perform: model.canGoBack ? { model.input(); model.goBack() } : nil)
    }
}

/// An open object: its kind's TV page (the registry's), under its name.
private struct TvObject: View {
    @Bindable var model: TvModel
    let ref: ObjectRef

    var body: some View {
        if let page = TvRegistry.page(ref.kind) {
            page.body(ref, model).id(ref.scopeId + "/" + ref.nodeId)
        } else {
            VStack(alignment: .leading, spacing: 20) {
                Text(ref.name).font(.title2)
                TvMessage(text: say(.tvOpensElsewhere))
            }
        }
    }
}

/// A person's page (a search found a member): the name the space knows them by.
private struct TvPerson: View {
    @Bindable var model: TvModel
    let userId: String

    var body: some View {
        let profile = model.shellModel?.profile(userId: userId)
        VStack(spacing: 30) {
            ZStack {
                Circle().fill(Role.accentSoft)
                Image(systemName: "person").font(.system(size: 80)).foregroundStyle(Role.accent)
            }
            .frame(width: 200, height: 200)
            Text(profile?.name ?? profile?.handle ?? "").font(.title2)
            if let handle = profile?.handle, profile?.name != nil { Text(handle).foregroundStyle(.secondary) }
        }
    }
}

/// Home (ADR 0122 decision 3): the favourites and the recent as shelves, then the space's level in its groups, as tiles.
private struct TvHome: View {
    @Bindable var model: TvModel

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 50) {
                if let lists = model.lists, let shell = model.shell {
                    Text(shell.folder?.displayName ?? say(piece: ShellWords.shared.space(space: lists.space(shell: shell)))).font(.title2)
                    if shell.isHome, let m = model.shellModel {
                        ForEach(m.drawerSections(lists: lists), id: \.self) { section in
                            switch section.entry {
                            case .favorites: TvShelf(model: model, title: say(.drawerFavorites), refs: lists.drawer.favorites)
                            case .recent: TvShelf(model: model, title: say(.drawerRecent), refs: lists.drawer.recent)
                            // the applications are the tab bar's; a TV's New is empty (the core's TV form creates nothing)
                            case .applications, .theNew: EmptyView()
                            }
                        }
                    }
                    let groups = lists.groups(shell: shell)
                    if groups.isEmpty { TvMessage(text: say(.drawerNothingYet)) }
                    ForEach(Array(groups.enumerated()), id: \.offset) { _, group in
                        VStack(alignment: .leading, spacing: 24) {
                            if let h = group.heading { Text(say(h)).font(.headline).foregroundStyle(.secondary) }
                            TvGrid {
                                ForEach(group.nodes, id: \.nodeId) { node in TvNodeTile(model: model, node: node) }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 20)
        }
    }
}

/// An application's list (ADR 0122 decision 5): core's arranged list — Favorites, Recent, then the rest.
private struct TvArranged: View {
    @Bindable var model: TvModel
    let title: String
    let list: ArrangedList

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 50) {
                Text(title).font(.title2)
                if list.favorites.isEmpty && list.recent.isEmpty && list.all.isEmpty { TvMessage(text: say(.drawerNothingYet)) }
                if !list.favorites.isEmpty { TvShelf(model: model, title: say(.drawerFavorites), refs: list.favorites) }
                if !list.recent.isEmpty { TvShelf(model: model, title: say(.drawerRecent), refs: list.recent) }
                if !list.all.isEmpty {
                    VStack(alignment: .leading, spacing: 24) {
                        if !list.favorites.isEmpty || !list.recent.isEmpty { Text(say(.filterAll)).font(.headline).foregroundStyle(.secondary) }
                        TvGrid { ForEach(list.all, id: \.nodeId) { ref in TvObjectTile(model: model, ref: ref) } }
                    }
                }
            }
            .padding(.horizontal, 20)
        }
    }
}

/// A row of tiles that scrolls sideways (tvOS's shelf).
private struct TvShelf: View {
    @Bindable var model: TvModel
    let title: String
    let refs: [ObjectRef]

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text(title).font(.headline).foregroundStyle(.secondary)
            ScrollView(.horizontal) {
                LazyHStack(spacing: 48) {
                    ForEach(refs, id: \.nodeId) { ref in TvObjectTile(model: model, ref: ref).frame(width: TvTile.width) }
                }
                .padding(.vertical, 30)
            }
            .scrollClipDisabled()
        }
        .focusSection()
    }
}

/// Tiles in as many columns as fit.
struct TvGrid<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: TvTile.width), spacing: 48)], spacing: 60) { content }
            .focusSection()
    }
}

/// A node of Home's level: a folder enters, an object opens, a kept link is shown (it opens elsewhere).
private struct TvNodeTile: View {
    @Bindable var model: TvModel
    let node: NodeRef

    var body: some View {
        if let folder = node.asFolder() {
            Button { model.input(); model.enter(folder) } label: {
                TvTile(name: node.displayName, line: nil, image: model.covers[node.nodeId]?.image, symbol: "folder")
            }
            .buttonStyle(.card)
        } else if let ref = node.asObject() {
            TvObjectTile(model: model, ref: ref)
        } else {
            Button { model.input(); if let at = node.asBookmark() { model.move { $0.openBookmark(node: at) } } } label: {
                TvTile(name: node.displayName, line: nil, image: nil, symbol: TvNames.kindSymbol(node.kind))
            }
            .buttonStyle(.card)
        }
    }
}

/// An object's tile: its cover, its name, what its application says of it.
private struct TvObjectTile: View {
    @Bindable var model: TvModel
    let ref: ObjectRef

    var body: some View {
        Button { model.input(); model.open(ref) } label: {
            TvTile(name: ref.name, line: model.details[ref.nodeId], image: model.covers[ref.nodeId]?.image, symbol: TvNames.kindSymbol(ref.kind))
        }
        .buttonStyle(.card)
    }
}

/// A TV tile (the card button's focus look is the platform's): a 16:9 picture or the kind's symbol, a name, a line.
struct TvTile: View {
    static let width: CGFloat = 340
    let name: String
    let line: String?
    let image: CGImage?
    let symbol: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack {
                Role.surfaceMuted
                if let image {
                    Image(decorative: image, scale: 1).resizable().scaledToFill()
                } else {
                    Image(systemName: symbol).font(.system(size: 54)).foregroundStyle(Role.accent)
                }
            }
            .frame(width: Self.width, height: Self.width * 9 / 16)
            .clipped()
            VStack(alignment: .leading, spacing: 4) {
                Text(name).font(.callout).lineLimit(1)
                if let line { Text(line).font(.caption2).foregroundStyle(.secondary).lineLimit(1) }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(width: Self.width, alignment: .leading)
        }
    }
}

/// A line where a page has nothing else to show.
struct TvMessage: View {
    let text: String

    var body: some View {
        Text(text).font(.headline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// The music bar while a queue is loaded (ADR 0142 decision 5): the title, the position, and previous, play/pause, next and
/// close as buttons the focus reaches.
private struct TvMusicBar: View {
    @Bindable var model: TvModel

    var body: some View {
        if let now = model.nowPlaying, let c = model.musicCommands {
            HStack(spacing: 30) {
                Image(systemName: "music.note")
                VStack(alignment: .leading, spacing: 8) {
                    Text(now.title).font(.callout).lineLimit(1)
                    ProgressView(value: Double(now.positionMs), total: Double(max(now.durationMs, 1)))
                }
                Button { c.previous() } label: { Image(systemName: "backward.fill") }.accessibilityLabel(say(.musicPrevious))
                if now.playing {
                    Button { c.pause() } label: { Image(systemName: "pause.fill") }.accessibilityLabel(say(.musicPause))
                } else {
                    Button { c.resume() } label: { Image(systemName: "play.fill") }.accessibilityLabel(say(.musicResume))
                }
                Button { c.next() } label: { Image(systemName: "forward.fill") }.accessibilityLabel(say(.musicNext))
                Button { model.stopMusic() } label: { Image(systemName: "xmark") }.accessibilityLabel(say(.musicClose))
            }
            .padding(.horizontal, 40)
            .padding(.vertical, 16)
            .focusSection()
        }
    }
}

/// An application's and a kind's name and symbol (the phone's `Names`, ShellView.swift).
enum TvNames {
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
}
