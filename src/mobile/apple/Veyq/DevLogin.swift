import Foundation

/// Debug builds only — the app's counterpart of the web's `?devlogin` (src/web/CLAUDE.md): `apple/devlogin.py` reads
/// the seeded dev principal from `.run/.env` and launches the app in a simulator with it in the environment
/// (`SIMCTL_CHILD_…`); the app then signs in through its real sign-in — staying signed in with `VEYQ_DEV_REMEMBER=1` —
/// or, with `VEYQ_DEV_UNLOCK_PASSWORD` alone, unlocks the password-trusted account its start shows. A release build
/// compiles none of this.
enum DevLogin {
    @MainActor
    static func run(_ model: AppModel) {
        #if DEBUG
        let env = ProcessInfo.processInfo.environment
        if let password = env["VEYQ_DEV_UNLOCK_PASSWORD"] {
            model.devUnlock(password: password)
            return
        }
        guard let server = env["VEYQ_DEV_SERVER"], let email = env["VEYQ_DEV_EMAIL"],
              let password = env["VEYQ_DEV_PASSWORD"], let secretKey = env["VEYQ_DEV_SECRET_KEY"] else { return }
        model.signIn(server: server, email: email, password: password, secretKey: secretKey, remember: env["VEYQ_DEV_REMEMBER"] == "1")
        #endif
    }
}
