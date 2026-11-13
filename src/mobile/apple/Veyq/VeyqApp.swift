import SwiftUI

@main
struct VeyqApp: App {
    @State private var model = AppModel()

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
        #if os(macOS)
        // the account's Settings as a Mac app keeps them: its Settings window, on ⌘, and from the sidebar's Settings
        Settings {
            SettingsPage(model: model)
        }
        #endif
    }

    private var root: some View {
        RootView(model: model)
            .task {
                DevLogin.run(model)
                model.start()
            }
    }
}
