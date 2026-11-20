import SwiftUI

/// A secret on screen stays out of the system's snapshot of the app (inbox #886; `src/mobile/CLAUDE.md` § Secrets on
/// screen): iOS writes the scene's snapshot to the app's `Library/SplashBoard/Snapshots` when the scene leaves the
/// foreground, so a view that shows a secret in clear — the kit scanner's camera, which shows the kit's code (the
/// Secret Key) — is covered with the ground colour whenever the scene is not active. The one way for every screen;
/// a masked field (`SecureField`) needs none.
struct SecretOnScreen: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content.overlay { if scenePhase != .active { Role.bg } }
    }
}

extension View {
    /// Covers this view while the scene is not active, so no system snapshot holds what it shows (`SecretOnScreen`).
    func secretOnScreen() -> some View { modifier(SecretOnScreen()) }
}
