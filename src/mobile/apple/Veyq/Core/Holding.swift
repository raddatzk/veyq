import Foundation
import SwiftUI
import VeyqCore

/// What an application holds in the app's one model for the open tenant (ADR 0154 decision 3, inbox #1066; Android's
/// `core/Holding.kt`): the models of its pages on screen, its dialogs, its uploads. The application registers it
/// (`ApplicationUi.holding`) and reaches its own through an extension of `AppModel` it declares (`model.memories`); the
/// core UI names no application's model, it asks every holding the questions below — each a moment of the tenant's life
/// or a core flow an application answers for its own kinds. A question an application does not answer keeps its default
/// (the protocol's extension): nothing to close, not its target.
@MainActor
protocol Holding: AnyObject {
    /// Another tenant is about to be shown (or the same one again): what was open of the one before closes.
    func leave()
    /// The tenant `tenant` is shown over `shell` (core's): its models are built (`switched`: another tenant than before).
    func shown(_ shell: ShellModel, _ tenant: TenantSession, switched: Bool)
    /// The session locked, signed out or is starting again (the gate left Signed): the tenant's dialogs go.
    func locked()
    /// The account shown signed out and no other opened: everything of the tenant closes.
    func signedOut()
    /// The lists were read anew: a page on screen reads itself anew.
    func listsRead()
    /// The last line each of this application's rows says, by node id (a Memory's date).
    func rowLines(_ shell: ShellModel) async -> [String: String]
    /// Node `ref` went to the trash: a page of it this application holds open closes.
    func trashed(_ ref: NodeAt)
    /// `node`'s move or copy sheet where its copy is this application's (a Pot's, by the open Pot); nil otherwise.
    func moveSheet(_ shell: ShellModel, _ node: NodeAt, copying: Bool, changed: @escaping @Sendable () -> Void) -> MoveSheet?
    /// Where *Add here* puts the waiting files on a page this application holds on screen (a Memory's gallery); nil elsewhere.
    func addHereTarget(_ shell: ShellModel, inbox: ShareInbox, at: Shell) -> AddHere?
    /// *Add here* on `target` where its place is this application's (`label` the level's name): true once answered — the
    /// application took the files and ended the share (`AppModel.shareTaken`), or refused them; false where it is another's.
    func addHere(_ target: AddHere, label: String) -> Bool
    /// Files dropped onto `target` where it is this application's; nil where it is another's.
    func drop(_ shell: ShellModel, _ target: DropTarget, _ items: [Dropped]) async -> DropOutcome?
    /// Whether files dropped on the app go straight into `open`, the object on screen (an open Pot's new expense).
    func takesDrop(_ open: ObjectRef, _ incoming: ShareInbox.Incoming) -> Bool
    /// What the share extension handed over for `target` queued where this application keeps it; false where it is another's.
    func pickUp(_ target: ShareHandoff.Target, _ sources: [UploadSource]) -> Bool
    /// The tray's *Open* of a held write `opens` where it opens this application's page (a Pot's held entry); false otherwise.
    func openQueued(_ opens: QueuedOpen) -> Bool
    /// A link's page `page` on screen: its member page's model held in the shell's place while it shows (share view =
    /// member view, ADR 0150 decision 5); false where the page is another's.
    func adopt(_ page: GuestPage) -> Bool
    /// A link's page `page` left: its model goes, the member's place is the shell's again; false where it is another's.
    func release(_ page: GuestPage) -> Bool
    /// Whether an editor of this application is open over the page (a guest's naming waits while it is).
    var editing: Bool { get }
    /// The shell's uploads into a level — the tray, New's *Upload files* and *Upload folder* — where this application keeps files.
    var levelUploads: LevelUploads? { get }
    /// The tiers of a picked photo or video where this application derives them (a link guest's and a Mac's import too).
    var deriver: DerivationDeriver? { get }
}

extension Holding {
    func leave() {}
    func shown(_ shell: ShellModel, _ tenant: TenantSession, switched: Bool) {}
    func locked() {}
    func signedOut() {}
    func listsRead() {}
    func rowLines(_ shell: ShellModel) async -> [String: String] { [:] }
    func trashed(_ ref: NodeAt) {}
    func moveSheet(_ shell: ShellModel, _ node: NodeAt, copying: Bool, changed: @escaping @Sendable () -> Void) -> MoveSheet? { nil }
    func addHereTarget(_ shell: ShellModel, inbox: ShareInbox, at: Shell) -> AddHere? { nil }
    func addHere(_ target: AddHere, label: String) -> Bool { false }
    func drop(_ shell: ShellModel, _ target: DropTarget, _ items: [Dropped]) async -> DropOutcome? { nil }
    func takesDrop(_ open: ObjectRef, _ incoming: ShareInbox.Incoming) -> Bool { false }
    func pickUp(_ target: ShareHandoff.Target, _ sources: [UploadSource]) -> Bool { false }
    func openQueued(_ opens: QueuedOpen) -> Bool { false }
    func adopt(_ page: GuestPage) -> Bool { false }
    func release(_ page: GuestPage) -> Bool { false }
    var editing: Bool { false }
    var levelUploads: LevelUploads? { nil }
    var deriver: DerivationDeriver? { nil }
}

/// What the shell's tray shows: the upload queue and a folder still being prepared (`preparing`, its name).
struct TrayShown {
    let queue: UploadQueue.State
    let preparing: String?
}

/// What a tray draws (`tray`) and its actions: the shell's (`LevelUploads`) or the sign-in's and the unlock's
/// (`SignedOutTray`).
@MainActor
protocol TrayActions: AnyObject {
    var tray: TrayShown? { get }
    func cancelAll()
    func acknowledge(_ batchId: String)
    func cancel(_ id: String)
    func retry(_ id: String)
}

/// The shell's uploads into a level (core UI's tray and New, ADR 0154 decision 3), as the application that keeps files
/// answers them: what the tray shows (`tray`: nil while no tenant is open) and its actions, and the picked files and folder
/// New puts under the level shown.
@MainActor
protocol LevelUploads: TrayActions {
    /// The picked `urls` queued as new files under the level shown.
    func upload(_ urls: [URL])
    /// The directory at `url` uploaded whole under the level shown.
    func uploadFolder(_ url: URL) async
    /// A READ_WRITE link guest's *Upload folder* (core's `GuestLevel.creates`, owner 2026-11-22): the directory at `url`
    /// uploaded whole into `level` as the guest; what it says, each line.
    func uploadFolderAsGuest(_ url: URL, level: GuestLevel) async -> [VeyqCore.Notice]
}
