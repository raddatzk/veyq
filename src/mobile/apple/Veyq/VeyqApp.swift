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

struct RootView: View {
    @Bindable var model: AppModel

    var body: some View {
        switch model.screen {
        case .starting:
            VStack(spacing: 24) {
                Brand()
                ProgressView()
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Role.bg)
        case .signIn(let working, let error, let adding, let server, let email):
            SignInView(working: working, error: error, adding: adding, server: server, email: email, onCancel: model.cancelAdd) { server, email, password, secretKey, remember in
                model.signIn(server: server, email: email, password: password, secretKey: secretKey, remember: remember)
            }
        case .unlock(let record, let working, let error):
            UnlockView(record: record, working: working, error: error, onUnlock: model.unlock(password:), onOther: model.otherAccount)
        case .signed:
            ShellView(model: model)
        }
    }
}
