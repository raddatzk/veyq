import Foundation
import VeyqCore

/// Debug builds only — the app's counterpart of the web's `?devlogin` (src/web/CLAUDE.md): `apple/devlogin.py` reads
/// the seeded dev principal from `.run/.env` and launches the app in a simulator with it in the environment
/// (`SIMCTL_CHILD_…`); the app then signs in through its real sign-in — its offer to stay signed in answered with the
/// presence level with `VEYQ_DEV_REMEMBER=1` — or, with `VEYQ_DEV_UNLOCK_PASSWORD` alone, unlocks the password-trusted
/// account its start shows and fills "Manage servers"' password field once. A release build compiles none of this.
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
        var then: (@MainActor () -> Void)?
        if env["VEYQ_DEV_REMEMBER"] == "1" {
            then = { model.answerOffer(StaySignedIn.presence) }
        }
        model.signIn(server: server, email: email, password: password, secretKey: secretKey, then: then)
        #endif
    }
}
