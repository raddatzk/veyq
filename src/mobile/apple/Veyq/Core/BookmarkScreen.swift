import SwiftUI
import VeyqCore

/// A kept link's page (core's `BookmarkPage`) and its state on the main actor; the share it shows in place as a guest's
/// screen (`GuestHeld`), made once per in-place screen and let go with the page.
@MainActor
@Observable
final class BookmarkHeld {
    let page: BookmarkPage
    let state: CoreState<BookmarkPage.State>
    @ObservationIgnored private weak var model: AppModel?
    @ObservationIgnored private var inPlace: GuestHeld?

    init(_ page: BookmarkPage, model: AppModel) {
        self.page = page
        self.model = model
        state = CoreState({ page.state.value as! BookmarkPage.State }) { changed in
            AppleMemories.shared.watch(flow: page.state) { @Sendable _ in changed() }
        }
    }

    /// The in-place share's guest screen of `screen` (one per screen; read while the page is drawn, so it is not observed).
    func held(_ screen: GuestScreen) -> GuestHeld? {
        if let inPlace, inPlace.screen === screen { return inPlace }
        guard let model else { return nil }
        inPlace?.close()
        let made = GuestHeld(screen, model: model)
        inPlace = made
        return made
    }

    func stop() {
        inPlace?.close()
        inPlace = nil
        state.stop()
    }
}

extension KindPage {
    /// Core's kept link (ADR 0150 decision 6; the web's `BookmarkDetail`): *Open on its own page* first and *Report shared
    /// content* beside core's report — which, on a bookmark, reports its name only to this instance (core's
    /// `ShellModel.reportSheet`). Its rename, move, favourite and delete are core's; it is never shared on and takes no cover.
    static let bookmark = KindPage(header: { node, model in
        guard let held = model.bookmarkPage(node) else { return HeaderContribution() }
        let page = held.page
        if held.state.value is BookmarkPage.StateLoading || held.state.value is BookmarkPage.StateUnreadable { return HeaderContribution() }
        return HeaderContribution(actions: [
            OwnAction(id: .openOwnPage, label: say(.bookmarkOpenOwnPage), systemImage: "arrow.up.forward.square") { model.bookmarkOwnPage(page) },
            OwnAction(id: .reportShared, label: say(.bookmarkReportShare), systemImage: "flag") { model.bookmarkReportShared(page) },
        ])
    }, body: { ref, _, model in AnyView(BookmarkScreen(model: model, node: ref.at)) })
}

/// A kept link's page (ADR 0150 decision 6, ADR 0121 decision 1): while the tenant embeds, the share in place under the
/// header — the link's guest view, its region named by the link's host —; otherwise the row opens the link on its own page
/// at once, the person back at the list once it is left, and the page says where the link points. A meta that does not read
/// says so; its delete is the header's.
struct BookmarkScreen: View {
    @Bindable var model: AppModel
    /// The kept link (core's `Shell.bookmark`, #1032: a node of core's, no application's object).
    let node: NodeAt

    var body: some View {
        Group {
            if let held = model.bookmarkPage(node) {
                switch held.state.value {
                case is BookmarkPage.StateUnreadable:
                    Text(say(.bookmarkUnreadable)).foregroundStyle(Role.muted).padding()
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                        .accessibilityIdentifier("bookmark-unreadable")
                case let s as BookmarkPage.StateInPlace:
                    if let guest = held.held(s.screen) {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(say(.bookmarkEmbedRegion, s.host)).font(.caption).foregroundStyle(Role.muted)
                                .padding(.horizontal).padding(.vertical, 4)
                                .accessibilityIdentifier("bookmark-in-place")
                            GuestContent(model: model, held: guest)
                        }
                        // *Back* inside the link steps back in it; at its root the shell's own back leaves the page
                        .toolbar {
                            if guest.depth > 1 {
                                ToolbarItem(placement: .navigation) {
                                    Button { guest.back() } label: { Image(systemName: "chevron.up") }
                                        .accessibilityLabel(say(.back))
                                }
                            }
                        }
                    }
                case let s as BookmarkPage.StateOnItsOwn:
                    VStack(alignment: .leading, spacing: 12) {
                        Text(s.host).font(.headline)
                        Button { model.bookmarkOwnPage(held.page) } label: { Label(say(.bookmarkOpenOwnPage), systemImage: "arrow.up.forward.square") }
                    }
                    .padding()
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    // embedding off: the row opens the link directly (the web's), the shell back at the list behind it
                    .task(id: node.nodeId) {
                        model.bookmarkOwnPage(held.page)
                        model.goBack()
                    }
                default:
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .navigationTitle(node.name)
        .onDisappear { model.closeBookmark() }
    }
}
