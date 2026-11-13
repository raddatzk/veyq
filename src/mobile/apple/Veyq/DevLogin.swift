import Foundation
import VeyqCore

/// Debug builds only — the app's counterpart of the web's `?devlogin` (src/web/CLAUDE.md): `apple/devlogin.py` reads
/// the seeded dev principal from `.run/.env` and launches the app in a simulator with it in the environment
/// (`SIMCTL_CHILD_…`); the app then signs in through its real sign-in — its offer to stay signed in answered with the
/// presence level with `VEYQ_DEV_REMEMBER=1` — or, with `VEYQ_DEV_UNLOCK_PASSWORD` alone, unlocks the password-trusted
/// account its start shows and fills "Manage servers"' password field once. With `VEYQ_DEV_KIT=<path>` the sign-in reads
/// that Emergency Kit file once it shows, through the same read as a file the open panel or the Files app picked
/// (`AppModel.readKit`: coordinated, in memory, drawn by PDFKit) — a pass where nothing may click the panel; the outcome
/// goes to the unified log (`veyq-kit:`), never the kit's email or key. A release build compiles none of this.
enum DevLogin {
    @MainActor
    static func run(_ model: AppModel) {
        #if DEBUG
        let env = ProcessInfo.processInfo.environment
        if let kit = env["VEYQ_DEV_KIT"] {
            Task { @MainActor in
                for _ in 0..<100 {
                    if case .signIn = model.screen { break }
                    try? await Task.sleep(for: .milliseconds(100))
                }
                NSLog("veyq-kit: reading the picked file")
                model.readKit(URL(fileURLWithPath: kit), typedServer: "veyq.localhost:8080")
                for _ in 0..<300 {
                    try? await Task.sleep(for: .milliseconds(100))
                    if case .signIn(let working, let error, _, _, _, let shown) = model.screen, !working, shown != nil || error != nil {
                        NSLog("veyq-kit: %@", shown.map { "kit held, server from \($0.serverFrom), other server said: \($0.typedServer != nil)" } ?? "no kit (\(String(describing: error)))")
                        return
                    }
                }
                NSLog("veyq-kit: no answer within 30 s")
            }
            return
        }
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
