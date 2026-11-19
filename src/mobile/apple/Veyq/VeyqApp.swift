import SwiftUI

@main
struct VeyqApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    #if os(macOS)
    /// A quit while files upload asks first (ADR 0145 decision 2).
    @NSApplicationDelegateAdaptor(QuitGuard.self) private var quitGuard
    #endif

    init() {
        #if DEBUG
        // the music's format matrix, no sign-in and no window needed (DevAudio)
        if let dir = DevAudio.requested { Task { await DevAudio.run(dir) } }
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
            LightboxCommands()
            SearchCommands()
        }
        #endif
        #if os(macOS)
        // the account's Settings as a Mac app keeps them: its Settings window, on ⌘, and from the sidebar's Settings
        Settings {
            SettingsPage(model: model)
        }
        // a Memory's lightbox in a window of its own, only when asked ("Open in New Window"): its value carries ids alone,
        // and it is not restored at the next start (its models are the open tenant's, opened anew each time)
        WindowGroup(id: "lightbox", for: LightboxWindowValue.self) { $value in
            if let value { LightboxWindow(model: model, value: value) }
        }
        .restorationBehavior(.disabled)
        .defaultSize(width: 960, height: 720)
        #endif
    }

    private var root: some View {
        RootView(model: model)
            .task {
                DevLogin.run(model)
                ContinuedWork.start()
                model.start()
            }
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
