import SwiftUI

@main
struct VeyqApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                .task { DevLogin.run(model) }
        }
    }
}

struct RootView: View {
    @Bindable var model: AppModel

    var body: some View {
        switch model.screen {
        case .signIn(let working, let error, let adding):
            SignInView(working: working, error: error, adding: adding, onCancel: model.cancelAdd) { server, email, password, secretKey in
                model.signIn(server: server, email: email, password: password, secretKey: secretKey)
            }
        case .signed:
            ShellView(model: model)
        }
    }
}
