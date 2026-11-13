import SwiftUI

@main
struct VeyqApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            #if DEBUG
            if DevScan.requested {
                DevScanView() // the QR camera's test harness, no sign-in
            } else {
                root
            }
            #else
            root
            #endif
        }
    }

    private var root: some View {
        RootView(model: model)
            .task {
                DevLogin.run(model)
                model.start()
            }
    }
}
