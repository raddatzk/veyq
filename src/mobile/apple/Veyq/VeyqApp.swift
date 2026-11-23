import SwiftUI
import VeyqCore

@main
struct VeyqApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    #if os(macOS)
    /// A quit while files upload asks first (ADR 0145 decision 2).
    @NSApplicationDelegateAdaptor(QuitGuard.self) private var quitGuard
    #endif

    init() {
        // each application into the core UI's registry, before anything is drawn (ADR 0154 decision 3)
        Applications.register()
        #if DEBUG
        // the music's format matrix, no sign-in and no window needed (DevAudio)
        if let dir = DevAudio.requested { Task { await DevAudio.run(dir) } }
        #endif
        #if DEBUG && os(macOS)
        // each click's hit view and first responder in the unified log (FocusTrace, inbox #1029 and #1030)
        FocusTrace.start()
        #endif
        #if os(iOS)
        // registered before the launch ends, also when the system launches the app only for a run (no scene then)
        BackgroundSync.start(AppModel.appDevice)
        #endif
    }

    var body: some Scene {
        WindowGroup {
            #if DEBUG
            if DevScan.requested {
                DevScanView() // the QR camera's test harness, no sign-in
            } else if let variant = DevSettings.requested {
                DevSettingsView(variant: variant) // the Settings page over a played account, no sign-in
            } else {
                root
            }
            #else
            root
            #endif
        }
        // a pin's fetch or an upload under way when the app is left asks the system for the time to go on (ADR 0140
        // decision 9, ADR 0145 decision 1)
        .onChange(of: scenePhase) { _, phase in
            ContinuedWork.phase(phase)
            // what the share extension handed over while the app was away (ADR 0138 decision 6)
            if phase == .active { model.pickUpHandoffs() }
        }
        #if os(macOS)
        .commands {
            Applications.commands
            SearchCommands()
        }
        #endif
        #if os(macOS)
        // the account's Settings as a Mac app keeps them: its Settings window, on ⌘, and from the sidebar's Settings
        Settings {
            SettingsPage(model: model)
        }
        // the windows an application opens beside the main one (a Memory's lightbox in a window of its own)
        Applications.windows(model)
        #endif
    }

    private var root: some View {
        RootView(model: model)
            .task {
                DevLogin.run(model)
                ContinuedWork.start()
                model.start()
            }
            .onOpenURL { url in openDeepLink(url) }
            #if os(macOS)
            // a veyq: link goes to the window already open, never a new one
            .handlesExternalEvents(preferring: ["*"], allowing: ["*"])
            #endif
    }

    /// A `veyq:` link (ADR 0150 § O): `veyq://link` opens *Open a link* over whatever shows — the sign-in, the unlock or
    /// the open tenant —, anything else only brings the app to the front, which the system already did. Nothing of the URL
    /// is read but what core's `DeepLink.parse` reads, and nothing of it is kept or logged. A field already open keeps
    /// what the person typed there.
    private func openDeepLink(_ url: URL) {
        guard !model.inExtension, DeepLinkCompanion.shared.parse(uri: url.absoluteString) is DeepLinkLink else { return }
        if model.guests.asking == nil { model.guests.ask(model) }
    }
}

#if os(macOS)
/// The Mac app keeps running with its windows closed, so its uploads go on; a quit (⌘Q, the menu) while files still
/// upload asks first — keeping on is the default — and with nothing under way quits as before (ADR 0145 decision 2).
@MainActor
final class QuitGuard: NSObject, NSApplicationDelegate {
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        let files = ContinuedWork.uploads.files
        guard files > 0 else { return .terminateNow }
        let alert = NSAlert()
        alert.messageText = say(.uploadQuitTitle)
        alert.informativeText = say(.uploadQuitBody, files)
        alert.addButton(withTitle: say(.uploadQuitKeep))
        let quit = alert.addButton(withTitle: say(.uploadQuitAnyway))
        quit.hasDestructiveAction = true
        return alert.runModal() == .alertSecondButtonReturn ? .terminateNow : .terminateCancel
    }
}
#endif
