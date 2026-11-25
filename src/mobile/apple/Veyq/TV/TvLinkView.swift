import SwiftUI
import UIKit
import VeyqCore

/// The TV's QR sign-in (ADR 0059's *Sign in with a QR code*, the new device's side, role `showForSignIn`): core's
/// `DeviceLink` decides every step, this draws it — the code beside `tv_signin_hint`, then the six digits the phone shows,
/// typed on the remote's pad and sent once. The code on screen is the channel's secret, so the cover hides it whenever
/// the scene is not active (`src/mobile/CLAUDE.md` § Secrets on screen).
struct TvLinkView: View {
    let link: DeviceLink
    let shown: DeviceLink.Shown
    let onAgain: () -> Void
    let onClose: () -> Void
    @Environment(\.scenePhase) private var phase

    var body: some View {
        ZStack {
            Role.bg.ignoresSafeArea()
            if phase == .active {
                VStack(spacing: 36) {
                    Text(say(.signinLinkShow)).font(.title2)
                    step
                }
                .frame(maxWidth: 1500)
                .padding(60)
            }
        }
        .onExitCommand(perform: onClose)
    }

    @ViewBuilder private var step: some View {
        switch shown.step {
        case .opening:
            waiting(say(.linkSignInOpening))
        case .showing:
            HStack(alignment: .center, spacing: 80) {
                VStack(spacing: 24) {
                    TvLinkCode(link: link)
                    Text(say(.tvSigninHint)).multilineTextAlignment(.center).frame(maxWidth: 520)
                }
                TvPinPad(title: say(.linkSignInCode), hint: say(.linkSignInCodeHint), error: nil, working: false) { code in
                    _ = link.enterCode(code: code)
                }
            }
        case .waiting:
            waiting(say(.linkSignInWaiting))
            sameServer
        case .sealing:
            waiting(say(.linkSealing))
        case .finishing, .done:
            waiting(say(.linkFinishing))
        default:
            ended
        }
    }

    private func waiting(_ text: String) -> some View {
        HStack(spacing: 20) {
            ProgressView()
            Text(text).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private var sameServer: some View {
        if shown.hint == .sameServer { Text(say(.linkSameServer)).multilineTextAlignment(.center).foregroundStyle(.secondary) }
    }

    @ViewBuilder private var ended: some View {
        let words: (String?, String?) = {
            switch shown.refusal {
            case .off: return (say(.linkOff), nil)
            case .gone: return (say(.linkGone), say(.linkSignInGoneHint))
            case .refused, .failed: return (say(.linkJoinFailed), nil)
            default: return (nil, nil)
            }
        }()
        if let said = words.0 { Text(said).font(.headline).multilineTextAlignment(.center).foregroundStyle(Role.danger) }
        if let hint = words.1 { Text(hint).multilineTextAlignment(.center) }
        sameServer
        HStack(spacing: 30) {
            if shown.refusal != .off { Button(say(.linkAgain), action: onAgain) }
            Button(say(.linkClose), action: onClose)
        }
    }
}

/// The TV's code: core's PNG, drawn pixel for pixel, dark on light (a camera reads that).
private struct TvLinkCode: View {
    let link: DeviceLink
    @State private var image: Image?

    var body: some View {
        Group {
            if let image { image.interpolation(.none).resizable().scaledToFit() } else { Color.white }
        }
        .frame(width: 480, height: 480)
        .accessibilityLabel(say(.linkCodeAltSignIn))
        .onAppear {
            guard image == nil, let data = AppleLink.shared.png(link: link, px: 960), let ui = UIImage(data: data) else { return }
            image = Image(uiImage: ui)
        }
    }
}
