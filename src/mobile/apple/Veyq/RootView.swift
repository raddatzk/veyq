import SwiftUI
import VeyqCore

/// What the app shows for its screen state; the app's and the share extension's one root (ADR 0126).
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
        case .needsApp:
            // The share extension with no account this device trusts: the app signs in (ADR 0126 decision 5).
            VStack(spacing: 16) {
                Brand()
                Text(say(.shareExtensionSignInFirst)).multilineTextAlignment(.center).foregroundStyle(Role.text)
                Button(say(.receiptsClose)) { model.cancelShare() }
            }
            .padding(32)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Role.bg)
        case .signed:
            ShellView(model: model)
        }
    }
}
