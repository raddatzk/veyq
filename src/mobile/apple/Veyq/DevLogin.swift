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
    /// `VEYQ_DEV_KIT_SCAN=1`: the sign-in opens its "Scan Emergency Kit" sheet once it shows, as its button does (with
    /// `VEYQ_DEV_CAMERA=<image>` the test camera shows the kit) — a pass where nothing may tap; the outcome goes to the
    /// unified log (`veyq-kit:`), never the kit's email or key.
    static var scansKit: Bool {
        #if DEBUG
        ProcessInfo.processInfo.environment["VEYQ_DEV_KIT_SCAN"] == "1"
        #else
        false
        #endif
    }

    /// `VEYQ_DEV_LINK=show|scan|signin-scan|signin-show`: a device-linking pass where nothing may tap
    /// (`cr-app-device-linking` phase 4). Once the app is signed in (`show`, `scan`: Settings' two entries) or shows its
    /// sign-in (`signin-…`: the form's two entries, over `VEYQ_DEV_SERVER` or the dev backend) it opens that ceremony as
    /// its button does. `VEYQ_DEV_LINK_DIR=<dir>` is where the pass reads the screen and answers in the person's place:
    /// the app writes the code it shows (`app-code.png`) and the number or six digits on screen (`app-shown.txt`); the
    /// pass writes the six digits it read off the other screen (`typed`, entered once the code shows) and, once it
    /// compared the numbers, the new device's name (`label`, which confirms as the slide's end does — the slide itself
    /// is a person's gesture, which this does not prove). The steps go to the unified log (`veyq-link:`), never a code,
    /// a number or a name.
    @MainActor
    private static func link(_ model: AppModel, _ env: [String: String]) {
        #if DEBUG
        guard let role = env["VEYQ_DEV_LINK"] else { return }
        let dir = env["VEYQ_DEV_LINK_DIR"].map { URL(fileURLWithPath: $0) }
        let read: (String) -> String? = { name in
            guard let dir, let text = try? String(contentsOf: dir.appendingPathComponent(name), encoding: .utf8) else { return nil }
            return text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        Task { @MainActor in
            opening: for _ in 0..<1200 {
                try? await Task.sleep(for: .milliseconds(100))
                switch (role, model.screen) {
                case ("show", .signed):
                    #if os(iOS)
                    let _: Void = model.openSettings() // the ceremony's sheet sits over Settings, as its entry opens it there
                    #endif
                    model.linkShow()
                case ("scan", .signed):
                    #if os(iOS)
                    let _: Void = model.openSettings()
                    #endif
                    model.linkScan()
                case ("signin-scan", .signIn): model.scanFromTrusted(server: env["VEYQ_DEV_SERVER"] ?? "veyq.localhost:8080")
                case ("signin-show", .signIn): model.showForSignIn(server: env["VEYQ_DEV_SERVER"] ?? "veyq.localhost:8080")
                default: continue opening
                }
                break opening
            }
            var typed = false
            var said = ""
            watching: for _ in 0..<6000 {
                try? await Task.sleep(for: .milliseconds(100))
                guard let link = model.link, let shown = model.linkShown else { break watching }
                // what the screen shows, for the pass to read in the person's place: the code as its picture, the number
                // or the six digits as text (the pass's own directory, as its screenshots would be)
                if let dir, "\(shown.step)" != said {
                    said = "\(shown.step)"
                    if shown.step == .showing, let png = AppleLink.shared.png(link: link, px: 520) {
                        try? png.write(to: dir.appendingPathComponent("app-code.png"))
                    }
                    if let digits = shown.code ?? shown.number {
                        try? digits.write(to: dir.appendingPathComponent("app-shown.txt"), atomically: true, encoding: .utf8)
                    }
                }
                if !typed, shown.step == .showing, link.role == .showForSignIn, let code = read("typed") {
                    typed = link.enterCode(code: code)
                }
                if shown.step == .confirming, let label = read("label") {
                    NSLog("veyq-link: dev confirms: %@", link.confirm(label: label) ? "moved on" : "refused")
                    break watching
                }
            }
            // the pass ends this device's session as Settings' "Sign Out" would (`signout`), so a run leaves no family
            for _ in 0..<6000 {
                try? await Task.sleep(for: .milliseconds(100))
                if read("signout") != nil {
                    model.devSignOut()
                    NSLog("veyq-link: dev signed out")
                    return
                }
            }
        }
        #endif
    }

    @MainActor
    static func run(_ model: AppModel) {
        #if DEBUG
        let env = ProcessInfo.processInfo.environment
        link(model, env)
        if env["VEYQ_DEV_LINK"]?.hasPrefix("signin-") == true { return }
        if scansKit {
            Task { @MainActor in
                for _ in 0..<600 {
                    try? await Task.sleep(for: .milliseconds(100))
                    if case .signIn(_, _, _, _, _, let shown) = model.screen, let shown {
                        NSLog("veyq-kit: kit held, server from %@, other server said: %@", "\(shown.serverFrom)", shown.typedServer != nil ? "true" : "false")
                        return
                    }
                }
                NSLog("veyq-kit: no kit within 60 s")
            }
            return
        }
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
