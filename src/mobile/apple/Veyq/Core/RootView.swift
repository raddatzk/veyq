import SwiftUI
import VeyqCore

/// What the app shows for its screen state; the app's and the share extension's one root (ADR 0138).
struct RootView: View {
    @Bindable var model: AppModel

    var body: some View {
        Group {
            // a received link open as its guest (ADR 0150 decision 4): a screen in the place of the sign-in or the shell,
            // which stay as the app model holds them and show again once the link is left (no shell page beneath reads
            // the models the guest's pages hold in its place)
            if let join = model.guests.joining {
                // an invite's join (ADR 0129): above everything, as a guest's screen is; what lies beneath stays as it was
                JoinView(model: model, held: join).id(ObjectIdentifier(join))
            } else if let held = model.guests.screen {
                GuestLinkView(model: model, held: held).id(ObjectIdentifier(held))
            } else if let gate = model.terms {
                // the tenant's terms gate (uc.account.accept-terms) stands in place of the shell, the offer behind it
                TermsGateView(gate: gate, onAccept: model.acceptTerms, onSignOut: model.signOutAtTerms)
            } else {
                screen
            }
        }
        // *Open a link*'s field, over the sign-in, the unlock or the shell
        .openLinkSheet(model)
    }

    @ViewBuilder private var screen: some View {
        switch model.screen {
        case .starting:
            VStack(spacing: 24) {
                Brand()
                ProgressView()
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Role.bg)
        case .signIn(let working, let error, let adding, let server, let email, let kit, let notice, let keepsNothing):
            SignInView(
                working: working, error: error, adding: adding, server: server, email: email, kit: kit, notice: notice,
                keepsNothing: keepsNothing, onCancel: model.cancelAdd,
                onLocked: lockedEntry, onForgot: forgotEntry,
                onKit: model.readKit(_:typedServer:), onClearKit: model.clearKit, onScanKit: model.scanKit(typedServer:),
                onScanFromTrusted: linkEntry(model.scanFromTrusted(server:)),
                onShowForSignIn: linkEntry(model.showForSignIn(server:)),
                onOpenLink: model.inExtension ? nil : { model.guests.ask(model) },
                tray: model.inExtension ? nil : model.signedOutTray,
                secretKeyProblem: model.secretKeyProblem(_:)
            ) { server, email, password, secretKey in
                model.signIn(server: server, email: email, password: password, secretKey: secretKey)
            }
            // a device-linking ceremony from the sign-in's two entries (ADR 0059)
            .deviceLinkSheet(model)
        case .locked(let server, let email, let working, let error):
            LockedView(
                server: server, email: email, working: working, error: error,
                onSignOutEverywhere: { model.signOutEverywhere() }, onBack: { model.lockedBack() }
            )
        case .recover(let server, let email, let working, let sent, let error):
            RecoverView(
                server: server, email: email, working: working, sent: sent, error: error,
                onSend: { server, email in model.requestRecovery(server: server, email: email) }, onBack: { model.backToSignIn() }
            )
        case .unlock(let record, let working, let error):
            UnlockView(
                record: record, working: working, error: error, inExtension: model.inExtension, onUnlock: model.unlock(password:),
                onOther: model.otherAccount, onForget: model.forgetUnlocked,
                onOpenLink: model.inExtension ? nil : { model.guests.ask(model) },
                tray: model.inExtension ? nil : model.signedOutTray
            )
            // the ask before its *Stop trusting this device* while writes made offline wait (inbox #1052)
            .modifier(LossAsking(model: model, here: true))
        case .needsApp:
            // The share extension with no account this device trusts: the app signs in (ADR 0138 decision 5).
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
                // the offer to stay signed in a sign-in left open (ADR 0123 decision 8), over the shell until it is answered
                .sheet(isPresented: Binding(get: { model.offer != nil }, set: { _ in })) {
                    OfferSheet(levels: model.offer?.levels ?? [], linked: model.offer?.linked ?? false, working: model.trustWorking, onAnswer: model.answerOffer)
                }
        }
    }

    /// The sign-in's locked entry (uc.account.sessions E3), or none in the share extension (the app signs in there).
    private var lockedEntry: ((String, String, String) -> Void)? {
        if model.inExtension { return nil }
        let model = model
        return { server, email, password in model.signInLocked(server: server, email: email, password: password) }
    }

    /// The sign-in's *Forgot your password?*, or none in the share extension.
    private var forgotEntry: ((String, String) -> Void)? {
        if model.inExtension { return nil }
        let model = model
        return { server, email in model.forgotPassword(server: server, email: email) }
    }

    /// A linking entry of the sign-in, or none in the share extension (the app signs in there, ADR 0132).
    private func linkEntry(_ open: @escaping (String) -> Void) -> ((String) -> Void)? {
        model.inExtension ? nil : open
    }
}
