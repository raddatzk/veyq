import SwiftUI
import VeyqCore
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// One device-linking ceremony on screen (ADR 0059; `cr-app-device-linking` phase 4), in any of its four roles: core's
/// `DeviceLink` decides every step, this draws its state and hands the person's acts back — the camera into its scan,
/// the six digits typed (`enterCode`), the slide's end (`confirm`), and `onClose` (which closes the link). A sheet with
/// its own navigation bar, as iOS asks a task of its own to be.
///
/// **A secret on screen** (`src/mobile/CLAUDE.md` § Secrets on screen): the code it shows is the channel's secret and
/// its camera may show the other side's, so the whole sheet is `.secretOnScreen()` — covered while the scene is not
/// active, so iOS's snapshot holds no frame of it.
struct DeviceLinkView: View {
    let link: DeviceLink
    let shown: DeviceLink.Shown
    let onAgain: () -> Void
    let onClose: () -> Void

    private var role: DeviceLink.Role { link.role }
    private var trusted: Bool { role == .showForNewDevice || role == .scanSignInCode }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) { step }
                    .padding(24)
                    .frame(maxWidth: 520)
                    .frame(maxWidth: .infinity)
            }
            .secretOnScreen()
            .background(Role.bg)
            .navigationTitle(title)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(say(.signinCancel), action: onClose)
                }
            }
        }
        .tint(Role.accent)
        .interactiveDismissDisabled(shown.step == .sealing || shown.step == .finishing)
        #if os(macOS)
        .frame(minWidth: 520, minHeight: 560)
        #endif
    }

    private var title: String {
        switch role {
        case .scanFromTrusted: return say(.linkJoinTitle)
        case .showForSignIn: return say(.signinLinkShow)
        default: return say(.accountSettingsAddDevice)
        }
    }

    @ViewBuilder private var step: some View {
        switch shown.step {
        case .opening:
            waiting(role == .showForNewDevice || role == .showForSignIn ? say(.linkSignInOpening) : nil)
        case .showing:
            if role == .showForNewDevice {
                copy(say(.linkScanHint))
                LinkCode(link: link)
                waiting(say(.linkWaiting))
                sameServer
            } else {
                copy(say(.linkSignInIntro))
                LinkCode(link: link)
                copy(say(.linkSignInScanHint))
                LinkSixDigits(link: link)
            }
        case .scanning:
            scanning
        case .code:
            copy(say(.linkCodePrompt))
            digits(shown.code ?? "")
            consequence
            waiting(say(.linkCodeWaiting))
            sameServer
        case .waiting:
            if role == .scanFromTrusted {
                copy(say(.linkJoinIntro))
                digits(shown.number ?? "")
                copy(say(.linkJoinCompare))
                waiting(say(.linkJoinWaiting))
            } else {
                waiting(say(.linkSignInWaiting))
            }
            sameServer
        case .confirming:
            LinkConfirming(link: link, number: role == .showForNewDevice ? shown.number : nil, onClose: onClose)
        case .sealing:
            waiting(say(.linkSealing))
        case .finishing:
            waiting(say(.linkFinishing))
        case .done:
            if trusted {
                Text(say(.linkSealed)).font(.headline).multilineTextAlignment(.center)
                copy(say(.linkSealedHint))
                Button(say(.linkClose), action: onClose).buttonStyle(.borderedProminent)
            } else {
                waiting(say(.linkFinishing))
            }
        default:
            ended
        }
    }

    private func copy(_ text: String) -> some View {
        Text(text).multilineTextAlignment(.center).foregroundStyle(Role.text).frame(maxWidth: .infinity)
    }

    private func waiting(_ text: String?) -> some View {
        HStack(spacing: 12) {
            ProgressView()
            if let text { Text(text).foregroundStyle(Role.muted) }
        }
    }

    private func digits(_ text: String) -> some View {
        Text(text).font(.system(size: 44, weight: .semibold, design: .monospaced)).tracking(6).textSelection(.disabled)
    }

    private var consequence: some View {
        Text(say(.linkConsequence)).font(.callout).multilineTextAlignment(.center).foregroundStyle(Role.danger)
    }

    @ViewBuilder private var sameServer: some View {
        if shown.hint == .sameServer { Text(say(.linkSameServer)).font(.callout).multilineTextAlignment(.center).foregroundStyle(Role.muted) }
    }

    @ViewBuilder private var scanning: some View {
        if let scan = AppleLink.shared.scan(link: link) {
            let newDevice = role == .scanFromTrusted
            QrCameraView(scan: scan) { state in
                if state is QrScanStateDenied || state is QrScanStateNoCamera {
                    Text(newDevice ? say(.linkJoinScanDenied) : say(.linkScanDenied))
                        .multilineTextAlignment(.center)
                        .padding(24)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .frame(height: 360)
            .clipShape(RoundedRectangle(cornerRadius: Radius.control))
            copy(newDevice ? say(.linkJoinScanHint) : say(.linkScanSignInHint))
            if shown.hint == .otherDirection {
                Text(newDevice ? say(.linkOtherDirectionNew) : say(.linkOtherDirectionTrusted))
                    .multilineTextAlignment(.center).foregroundStyle(Role.danger)
            }
        }
    }

    @ViewBuilder private var ended: some View {
        let newDevice = !trusted
        let words: (String?, String?) = {
            switch shown.refusal {
            case .off: return (say(.linkOff), nil)
            case .gone:
                return (say(.linkGone), role == .scanFromTrusted ? say(.linkJoinGoneHint) : role == .showForSignIn ? say(.linkSignInGoneHint) : nil)
            case .refused:
                switch role {
                case .showForNewDevice: return (say(.linkAnswerRefused), say(.linkAnswerRefusedHint))
                case .scanSignInCode: return (say(.linkScanRefused), say(.linkScanRefusedHint))
                default: return (say(.linkJoinRefused), nil)
                }
            case .failed: return (newDevice ? say(.linkJoinFailed) : say(.linkGrantFailed), nil)
            default: return (nil, nil)
            }
        }()
        if let said = words.0 { Text(said).font(.headline).multilineTextAlignment(.center).foregroundStyle(Role.danger) }
        if let hint = words.1 { copy(hint) }
        sameServer
        if shown.refusal != .off {
            Button(say(.linkAgain), action: onAgain).buttonStyle(.borderedProminent)
        }
        Button(say(.linkClose), action: onClose)
    }
}

/// This side's code: core's PNG, drawn pixel for pixel (dark on light whatever the appearance — a scanner reads that).
private struct LinkCode: View {
    let link: DeviceLink
    @State private var image: Image?

    var body: some View {
        Group {
            if let image {
                image.interpolation(.none).resizable().scaledToFit()
            } else {
                Color.white
            }
        }
        .frame(width: 260, height: 260)
        .accessibilityLabel(link.role == .showForSignIn ? say(.linkCodeAltSignIn) : say(.linkCodeAlt))
        .onAppear {
            guard image == nil, let data = AppleLink.shared.png(link: link, px: 520) else { return }
            #if os(iOS)
            if let ui = UIImage(data: data) { image = Image(uiImage: ui) }
            #else
            if let ns = NSImage(data: data) { image = Image(nsImage: ns) }
            #endif
        }
    }
}

/// The new device's six digits, typed and sent once (§6): editable until sent, one try.
private struct LinkSixDigits: View {
    let link: DeviceLink
    @State private var code = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField(say(.linkSignInCode), text: $code)
                .textContentType(.oneTimeCode)
                .font(.title2.monospacedDigit())
            #if os(iOS)
                .keyboardType(.numberPad)
            #endif
                .textFieldStyle(.roundedBorder)
                .onChange(of: code) { code = String(code.filter(\.isNumber).prefix(6)) }
                .onSubmit { if code.count == 6 { _ = link.enterCode(code: code) } }
            Text(say(.linkSignInCodeHint)).font(.footnote).foregroundStyle(Role.muted)
            Button {
                _ = link.enterCode(code: code)
            } label: {
                Text(say(.linkSignInSend)).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(code.count != 6)
        }
    }
}

/// The trusted device's confirmation (§5, §7): the number compared or the proof held, the name, then the slide.
private struct LinkConfirming: View {
    let link: DeviceLink
    /// The number both screens compare (add a new device); nil where the proof held (sign in with a QR code).
    let number: String?
    let onClose: () -> Void
    @State private var label = ""

    var body: some View {
        VStack(spacing: 20) {
            Text(number != nil ? say(.linkComparePrompt) : say(.linkProven)).multilineTextAlignment(.center)
            if let number {
                Text(number).font(.system(size: 44, weight: .semibold, design: .monospaced)).tracking(6)
            }
            VStack(alignment: .leading, spacing: 6) {
                TextField(say(.linkLabel), text: $label).textFieldStyle(.roundedBorder)
                Text(say(.linkLabelHint)).font(.footnote).foregroundStyle(Role.muted)
            }
            let name = label.trimmingCharacters(in: .whitespaces)
            LinkSlide(
                sentence: say(.linkSlide, name.isEmpty ? say(.linkSlideNoName) : name),
                enabled: !name.isEmpty
            ) { link.confirm(label: label) }
            Text(say(.linkConsequence)).font(.callout).multilineTextAlignment(.center).foregroundStyle(Role.danger)
            if number != nil {
                Button(say(.linkMismatch), role: .cancel, action: onClose).buttonStyle(.bordered)
            }
        }
    }
}

/// The slide that confirms (ADR 0059 §7, D5): the system's `Slider`, which confirms only at its end and springs back
/// otherwise — a deliberate gesture, not a tap. Keyboard: the arrow keys move it, Return at the end confirms (a key
/// never confirms by itself). VoiceOver reads `sentence` and confirms through the action of that name. `onConfirm`
/// answers whether the link moved on; one that did not springs back.
struct LinkSlide: View {
    let sentence: String
    let enabled: Bool
    let onConfirm: () -> Bool
    @State private var value = 0.0

    private static let end = 0.98
    private static let steps = 10.0

    var body: some View {
        VStack(spacing: 8) {
            Text(sentence).font(.headline).multilineTextAlignment(.center)
            Slider(value: $value, in: 0...1) { editing in
                guard !editing else { return }
                if value >= Self.end { confirm() } else { back() }
            }
            .disabled(!enabled)
            // ten steps, as the web's slide: deliberate, never one key to the end (Home, End and the page keys are taken)
            .onKeyPress(keys: [.rightArrow, .upArrow]) { _ in
                value = min(Self.steps, (value * Self.steps).rounded() + 1) / Self.steps
                return .handled
            }
            .onKeyPress(keys: [.leftArrow, .downArrow]) { _ in
                value = max(0, (value * Self.steps).rounded() - 1) / Self.steps
                return .handled
            }
            .onKeyPress(keys: [.home, .end, .pageUp, .pageDown]) { _ in .handled }
            .onKeyPress(.return) {
                guard value >= Self.end else { return .ignored }
                confirm()
                return .handled
            }
            .accessibilityLabel(sentence)
            .accessibilityAction(named: sentence) { _ = onConfirm() }
            Text(say(.linkSlideKeys)).font(.footnote).foregroundStyle(Role.muted)
        }
    }

    private func confirm() {
        if !onConfirm() { back() }
    }

    private func back() {
        withAnimation(.spring) { value = 0 }
    }
}

extension View {
    /// The model's device-linking ceremony as a sheet over this view (the sign-in, Settings); closing it closes the link.
    /// `here` false leaves the ceremony to another view's sheet (a Mac's Settings window while it is open).
    func deviceLinkSheet(_ model: AppModel, here: Bool = true) -> some View {
        sheet(isPresented: Binding(get: { here && model.link != nil }, set: { if !$0 { model.closeLink() } })) {
            if let link = model.link, let shown = model.linkShown {
                DeviceLinkView(link: link, shown: shown, onAgain: model.linkAgain, onClose: model.closeLink)
            }
        }
    }
}
