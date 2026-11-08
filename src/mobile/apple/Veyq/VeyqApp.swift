import SwiftUI

@main
struct VeyqApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                .task {
                    DevLogin.run(model)
                    model.start()
                }
        }
    }
}
