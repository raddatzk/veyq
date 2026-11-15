import SwiftUI

@main
struct VeyqApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

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
        // a pin's fetch under way when the app is left asks the system for the time to go on (ADR 0140 decision 9)
        .onChange(of: scenePhase) { _, phase in OfflineBackground.shared.phase(phase) }
        #if os(macOS)
        .commands { LightboxCommands() }
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
                OfflineBackground.shared.start()
                model.start()
            }
    }
}
