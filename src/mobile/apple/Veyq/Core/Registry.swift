import SwiftUI
import VeyqCore

/// A kind's page as an application registers it (the web's `KindDescriptor`, ADR 0154 decision 3): what the kind's page
/// contributes to core's header (`header`: nil while the page cannot tell yet), the page's body under it (`body`, on its
/// bar tab `key`; nil: not built — a folder's is core's level) and the page a link's guest is shown of it (`guest`, share
/// view = member view). What the header offers is core's (`NodeHeaderModel`, `AppKinds`); core draws it for every kind,
/// never an application.
struct KindPage {
    let header: @MainActor (NodeAt, AppModel) -> HeaderContribution?
    /// The body of an object of the kind on its bar tab (`key`).
    var body: (@MainActor (ObjectRef, String, AppModel) -> AnyView)?
    /// The page of the kind behind a link's gates (core's `GuestPage` of it), with the guest's link held.
    var guest: (@MainActor (GuestPage, GuestHeld, AppModel) -> AnyView)? = nil
    /// The kind's own actions on a link's bar (a file's download and replace, a Memory's music), drawn before *Keep*.
    var guestActions: (@MainActor (GuestPage, GuestHeld, AppModel) -> AnyView)? = nil
}

/// A create dialog an application registers (the web's `createDialog`): presented as a sheet by core's New over the space
/// and the folder shown (either nil where the place has none); it closes itself.
typealias CreateDialog = @MainActor (_ model: AppModel, _ space: String?, _ folder: String?) -> AnyView

/// What a person's profile hands an application's section (ADR 0144 decision 11): core's frame — the picture, the name,
/// the handle — and the safety number below it (nil for a former member); the section draws the page with them.
struct PersonFrame {
    let frame: AnyView
    let safety: AnyView?
}

/// What an application registers for itself beside its kinds: its own list in the drawer (`list`, on the bar tab `key`;
/// nil: core's arranged list), the dialogs of what it creates (`creates`, the web's `createDialog`: opened by core's New)
/// and its section of a person's profile (`personSection`: it draws the profile with core's `PersonFrame`); what it holds in
/// the app's model for the open tenant (`holding`, its `Holding`: built once per model, in registration order) and what it
/// presents over the shell (`shell`: its own alerts and pickers, drawn behind the shell's content).
struct ApplicationUi {
    let id: ApplicationId
    var list: (@MainActor (AppModel, String) -> AnyView)? = nil
    var creates: [AppKinds.Create: CreateDialog] = [:]
    var personSection: (@MainActor (PersonProfile, AppModel, PersonFrame) -> AnyView)? = nil
    var holding: (@MainActor (AppModel) -> any Holding)? = nil
    var shell: (@MainActor (AppModel) -> AnyView)? = nil
}

/// The core UI's one registry (the web's `core/applications/registry.ts`): each application registers into it from the
/// composition root (`Applications.swift`, the web's `applications.ts`), and the shell, a link's page, New and a person's
/// profile ask it — the core UI names no application (`scripts/validate_mobile_ui_direction.py`). A kind or an application
/// registered twice is refused, and a lookup before the composition root sealed it fails rather than draw nothing.
@MainActor
enum Registry {
    private static var kinds: [String: KindPage] = [:]
    private static var applications: [ApplicationId: ApplicationUi] = [:]
    /// The applications in the order they registered (the holdings are built and asked in it).
    private static var order: [ApplicationUi] = []
    private(set) static var isSealed = false

    static func kind(_ page: KindPage, _ kind: String...) {
        precondition(!isSealed, "the registry is sealed")
        for k in kind { precondition(kinds.updateValue(page, forKey: k) == nil, "the kind \(k) is registered twice") }
    }

    static func application(_ ui: ApplicationUi) {
        precondition(!isSealed, "the registry is sealed")
        precondition(applications.updateValue(ui, forKey: ui.id) == nil, "the application \(ui.id) is registered twice")
        order.append(ui)
    }

    /// Called once by the composition root after every registration.
    static func seal() { isSealed = true }

    static func page(_ kind: String) -> KindPage? {
        ready()
        return kinds[kind]
    }

    static func application(_ id: ApplicationId) -> ApplicationUi? {
        ready()
        return applications[id]
    }

    /// Every registered application, in registration order.
    static func registered() -> [ApplicationUi] {
        ready()
        return order
    }

    static func create(_ create: AppKinds.Create) -> CreateDialog? {
        ready()
        return applications.values.lazy.compactMap { $0.creates[create] }.first
    }

    private static func ready() {
        precondition(isSealed, "the registry is read before the composition root registered the applications")
    }
}

/// Core's own kinds as they register: the folder and the kept link (`KindPage.bookmark`).
@MainActor
enum KindPages {
    static func register(_ registry: Registry.Type) {
        registry.kind(folder, "FOLDER")
        registry.kind(.bookmark, AppKinds.shared.BOOKMARK_KIND)
    }

    /// Core's folder: its ZIP and its copy with everything under it the build exports and copies (`NodeExport`, `SubtreeCopy`).
    private static let folder = KindPage(header: { node, model in
        HeaderContribution(
            download: { await model.downloadNode(node, to: $0) }
        )
    })
}
