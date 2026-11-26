import SwiftUI
import UIKit
import VeyqCore

/// The TV's way in (ADR 0159 decision 4, ADR 0160): the picker, a PIN, the QR sign-in, the PIN's offer — then the shell.
/// Everything shown is core's gate (`SessionModel.current`) as it is now.
struct TvRoot: View {
    @Bindable var model: TvModel

    var body: some View {
        ZStack {
            Role.bg.ignoresSafeArea()
            gateView
        }
        .tint(Role.accent)
        .fullScreenCover(isPresented: Binding(get: { model.link != nil }, set: { if !$0 { model.closeLink() } })) {
            if let link = model.link, let shown = model.linkShown {
                TvLinkView(link: link, shown: shown, onAgain: model.linkAgain, onClose: model.closeLink)
            }
        }
    }

    @ViewBuilder private var gateView: some View {
        switch model.gate {
        case let g as SessionModelGate.SignIn: TvSignIn(model: model, gate: g)
        case let g as SessionModelGate.Picker: TvPicker(model: model, gate: g)
        case let g as SessionModelGate.Unlock:
            if g.asksPin { TvPinUnlock(model: model, gate: g) } else { TvStarting() }
        case is SessionModelGate.Signed:
            if let offer = model.offer { TvOffer(model: model, offer: offer) } else { TvShell(model: model) }
        // a TV's sign-in is its QR code only (`SignInPlan.tvEntries`): the locked sign-in, the reset and the share
        // extension's gate are never reached here
        default: TvStarting()
        }
    }
}

/// While core starts or works: the brand and a spinner.
struct TvStarting: View {
    var body: some View {
        VStack(spacing: 40) {
            TvBrand()
            ProgressView()
        }
    }
}

/// The brand at a TV's size.
struct TvBrand: View {
    var body: some View {
        HStack(spacing: 18) {
            Image("VeyqMark").resizable().scaledToFit().frame(width: 60, height: 64)
            Text(verbatim: "veyq").font(.system(size: 52, weight: .heavy)).foregroundStyle(Role.text)
        }
    }
}

/// A way-in page: the brand on top, its content centred below.
struct TvPage<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 36) {
            TvBrand()
            content
        }
        .frame(maxWidth: 1100)
        .padding(60)
    }
}

/// A TV's sign-in (ADR 0159 decision 4): the server, typed through the system keyboard (dictation with it), and core's
/// TV plan (`SignInPlan.tvEntries`: *Sign in with a QR code*, Cancel while adding) drawn whole — a TV never sees a
/// password or a Secret Key.
private struct TvSignIn: View {
    @Bindable var model: TvModel
    let gate: SessionModelGate.SignIn
    @State private var server = TvSignIn.devServer
    @FocusState private var showFocused: Bool

    var body: some View {
        TvPage {
            Text(say(gate.adding ? .signinAddTitle : .signinTitle)).font(.title2)
            TextField(say(.signinServer), text: $server)
                .keyboardType(.URL)
                .textContentType(.URL)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .frame(maxWidth: 800)
            if let e = gate.error { Text(say(e)).foregroundStyle(Role.danger).multilineTextAlignment(.center) }
            if let n = gate.notice { Text(say(n)).multilineTextAlignment(.center) }
            HStack(spacing: 30) {
                ForEach(SignInPlan.shared.tvEntries(adding: gate.adding), id: \.self) { entry in
                    switch entry.entry {
                    case .linkShow:
                        Button {
                            model.input()
                            model.showForSignIn(server: server)
                        } label: { Label(say(.signinLinkShow), systemImage: "qrcode") }
                        .disabled(gate.working || server.trimmingCharacters(in: .whitespaces).isEmpty)
                        .focused($showFocused)
                    case .cancel:
                        Button(say(.signinCancel)) { model.cancelAdd() }.disabled(gate.working)
                    // a phone's and a computer's entries: never in a TV's plan
                    case .submit, .locked, .linkScan, .kitOpen, .kitScan, .forgot, .openLink:
                        EmptyView()
                    }
                }
            }
            if gate.working { ProgressView() }
        }
        .onAppear {
            if server.isEmpty { server = gate.server ?? "" }
            showFocused = !server.isEmpty
        }
    }
}

/// The account picker (ADR 0159 decision 4): every account kept on this TV under its name here and its server — picking
/// one asks its PIN, or opens it where it is kept without one —, then *Add someone*. The error says why it shows again (the account removed after ten wrong PINs).
private struct TvPicker: View {
    @Bindable var model: TvModel
    let gate: SessionModelGate.Picker

    var body: some View {
        TvPage {
            Text(say(.tvPickerTitle)).font(.title2)
            if let e = gate.error { Text(say(e)).foregroundStyle(Role.danger).multilineTextAlignment(.center) }
            ScrollView(.horizontal) {
                HStack(spacing: 48) {
                    ForEach(gate.tiles, id: \.account) { tile in
                        Button { model.input(); model.pick(tile.account) } label: {
                            TvPersonTile(initial: String(tile.label.prefix(1)).uppercased(), symbol: nil, title: tile.label, line: tile.host)
                        }
                        .buttonStyle(.card)
                    }
                    Button { model.input(); model.addServer() } label: {
                        TvPersonTile(initial: nil, symbol: "person.badge.plus", title: say(.tvPickerAdd), line: nil)
                    }
                    .buttonStyle(.card)
                }
                .padding(40)
            }
            .scrollClipDisabled()
        }
    }
}

/// One account's tile on the picker: its initial in a circle (or a symbol), its name, its server.
private struct TvPersonTile: View {
    let initial: String?
    let symbol: String?
    let title: String
    let line: String?

    var body: some View {
        VStack(spacing: 16) {
            ZStack {
                Circle().fill(Role.accentSoft)
                if let initial { Text(initial).font(.system(size: 64, weight: .semibold)).foregroundStyle(Role.accent) }
                if let symbol { Image(systemName: symbol).font(.system(size: 56)).foregroundStyle(Role.accent) }
            }
            .frame(width: 150, height: 150)
            Text(title).font(.headline).lineLimit(2).multilineTextAlignment(.center)
            if let line { Text(line).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
        }
        .frame(width: 260, height: 300)
        .padding(20)
    }
}

/// The PIN of the account picked (ADR 0160 decision 3): a wrong one says the tries left (core's `attemptsLeft`); Back
/// returns to the picker.
private struct TvPinUnlock: View {
    @Bindable var model: TvModel
    let gate: SessionModelGate.Unlock

    var body: some View {
        TvPage {
            Text(gate.record.pickerLabel).font(.title3)
            TvPinPad(title: say(.tvPinEnterTitle), hint: nil, error: error, working: gate.working) { pin in
                model.input()
                model.unlockWithPin(pin)
            }
        }
        .onExitCommand { if !gate.working { model.backToPicker() } }
    }

    private var error: String? {
        guard let e = gate.error else { return nil }
        if let left = gate.attemptsLeft { return say(e, left) }
        return say(e)
    }
}

/// The offer after the QR sign-in (ADR 0160 decision 1, core's `Offer.pin`): a PIN asked twice, then kept — the
/// recommended way, first and focused. Below it, where core's offer has the login level (`Offer.levels`), *Stay Signed In
/// Without a PIN* (owner 2026-11-26): never taken at once, it asks first in an alert carrying the warning (the HIG's way
/// to confirm a choice with a consequence), its Cancel the default. *Don't keep me signed in* declines. Where this TV has
/// no hardware factor (`Offer.pin` false) it says so; with no way to keep the account at all, OK declines.
private struct TvOffer: View {
    @Bindable var model: TvModel
    let offer: SessionModelOffer
    @State private var first: String?
    @State private var error: String?
    @State private var asksWithoutPin = false

    private var withoutPin: Bool { offer.levels.contains(StaySignedIn.login) }

    var body: some View {
        TvPage {
            if !offer.pin && !withoutPin {
                Text(say(.tvPinNotOffered)).multilineTextAlignment(.center).frame(maxWidth: 900)
                Button(say(.ok)) { model.notNow() }
            } else {
                if offer.pin { pinPads } else { Text(say(.tvPinUnavailable)).multilineTextAlignment(.center).frame(maxWidth: 900) }
                if withoutPin {
                    Button(say(.tvStayNoPin)) { model.input(); asksWithoutPin = true }.disabled(model.trustWorking)
                }
                Button(say(.tvPinSkip)) { model.notNow() }.disabled(model.trustWorking)
            }
        }
        .tvNoPinAlert(isPresented: $asksWithoutPin) { model.stayWithoutPin() }
    }

    @ViewBuilder private var pinPads: some View {
        Group {
            if let held = first {
                TvPinPad(title: say(.tvPinConfirmTitle), hint: nil, error: nil, working: model.trustWorking) { pin in
                    first = nil
                    guard pin == held else { error = say(.tvPinMismatch); return }
                    model.stayWithPin(pin) { change in if change != .done { error = say(.trustFailed) } }
                }
                .id("confirm")
            } else {
                TvPinPad(title: say(.tvPinSetTitle), hint: say(.tvPinSetHint), error: error, working: model.trustWorking) { pin in
                    error = nil
                    first = pin
                }
                .id("first")
            }
        }
    }
}

extension View {
    /// The confirmation before a TV keeps an account without a PIN (owner 2026-11-26): the question, the warning — anyone
    /// with the remote opens the account here, from any other profile too —, Cancel first and focused, then the choice.
    /// A page of its own, never `.alert`: tvOS focuses an alert's first button, which is never the cancel one, so a second
    /// press of the remote took the choice (tvOS pass 2026-11-26). Menu cancels.
    func tvNoPinAlert(isPresented: Binding<Bool>, onConfirm: @escaping () -> Void) -> some View {
        fullScreenCover(isPresented: isPresented) {
            TvNoPinConfirm(
                onCancel: { isPresented.wrappedValue = false },
                onConfirm: { isPresented.wrappedValue = false; onConfirm() },
            )
        }
    }
}

private struct TvNoPinConfirm: View {
    let onCancel: () -> Void
    let onConfirm: () -> Void
    private enum Choice { case cancel, confirm }
    @FocusState private var focused: Choice?

    var body: some View {
        ZStack {
            Role.bg.ignoresSafeArea()
            page
        }
        .tint(Role.accent)
        .defaultFocus($focused, .cancel)
        .onAppear { focused = .cancel }
        .onExitCommand { onCancel() }
    }

    private var page: some View {
        TvPage {
            Text(say(.tvStayNoPinTitle)).font(.title2)
            Text(say(.tvStayNoPinWarning)).multilineTextAlignment(.center).foregroundStyle(.secondary)
            HStack(spacing: 30) {
                Button(say(.signinCancel)) { onCancel() }.focused($focused, equals: .cancel)
                Button(say(.tvStayNoPin)) { onConfirm() }.focused($focused, equals: .confirm)
            }
        }
    }
}

/// Six digits from the remote (ADR 0160 decision 1: digits only, a remote's number pad): a focusable 0–9 pad, the digits
/// shown as dots, Delete; the sixth digit hands the PIN on and empties the pad.
struct TvPinPad: View {
    let title: String
    let hint: String?
    let error: String?
    let working: Bool
    let onPin: (String) -> Void
    @State private var digits = ""
    @FocusState private var focused: String?

    private static let rows = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"]]

    var body: some View {
        VStack(spacing: 28) {
            Text(title).font(.title2)
            if let hint { Text(hint).multilineTextAlignment(.center).foregroundStyle(.secondary).frame(maxWidth: 900) }
            HStack(spacing: 22) {
                ForEach(0..<6, id: \.self) { i in
                    Circle()
                        .strokeBorder(Role.text, lineWidth: 3)
                        .background(Circle().fill(i < digits.count ? Role.text : .clear))
                        .frame(width: 28, height: 28)
                }
            }
            .accessibilityElement()
            .accessibilityLabel("\(digits.count) / 6")
            if let error { Text(error).foregroundStyle(Role.danger).multilineTextAlignment(.center).frame(maxWidth: 900) }
            VStack(spacing: 18) {
                ForEach(Self.rows, id: \.self) { row in
                    HStack(spacing: 18) { ForEach(row, id: \.self) { key($0) } }
                }
                HStack(spacing: 18) {
                    Color.clear.frame(width: 140, height: 90)
                    key("0")
                    Button { model(delete: true) } label: {
                        Image(systemName: "delete.left").frame(width: 110, height: 60)
                    }
                    .accessibilityLabel(say(.tvPinDelete))
                    .disabled(working || digits.isEmpty)
                }
            }
            .focusSection()
            if working { ProgressView() }
        }
        .onAppear { focused = "5" }
    }

    private func key(_ d: String) -> some View {
        Button { model(digit: d) } label: {
            Text(d).font(.title).monospacedDigit().frame(width: 110, height: 60)
        }
        .focused($focused, equals: d)
        .disabled(working)
    }

    private func model(digit: String? = nil, delete: Bool = false) {
        if delete { digits = String(digits.dropLast()); return }
        guard let digit, digits.count < 6 else { return }
        digits += digit
        if digits.count == 6 {
            let pin = digits
            digits = ""
            onPin(pin)
        }
    }
}

extension TvSignIn {
    /// Debug builds only: `VEYQ_DEV_SERVER` (handed by `simctl launch` as `SIMCTL_CHILD_VEYQ_DEV_SERVER`) fills the server
    /// field, as the app's `DevLogin` does — a simulator pass types no address through the D-pad. A release build starts empty.
    static var devServer: String {
        #if DEBUG
        ProcessInfo.processInfo.environment["VEYQ_DEV_SERVER"] ?? ""
        #else
        ""
        #endif
    }
}
