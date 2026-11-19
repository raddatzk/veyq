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
            if let held = model.guests.screen {
                GuestLinkView(model: model, held: held).id(ObjectIdentifier(held))
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
        case .signIn(let working, let error, let adding, let server, let email, let kit):
            SignInView(
                working: working, error: error, adding: adding, server: server, email: email, kit: kit, onCancel: model.cancelAdd,
                onKit: model.readKit(_:typedServer:), onClearKit: model.clearKit, onScanKit: model.scanKit(typedServer:),
                onScanFromTrusted: linkEntry(model.scanFromTrusted(server:)),
                onShowForSignIn: linkEntry(model.showForSignIn(server:)),
                onOpenLink: model.inExtension ? nil : { model.guests.ask(model) }
            ) { server, email, password, secretKey in
                model.signIn(server: server, email: email, password: password, secretKey: secretKey)
            }
            // a device-linking ceremony from the sign-in's two entries (ADR 0059)
            .deviceLinkSheet(model)
        case .unlock(let record, let working, let error):
            UnlockView(
                record: record, working: working, error: error, onUnlock: model.unlock(password:), onOther: model.otherAccount,
                onOpenLink: model.inExtension ? nil : { model.guests.ask(model) }
            )
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

    /// A linking entry of the sign-in, or none in the share extension (the app signs in there, ADR 0132).
    private func linkEntry(_ open: @escaping (String) -> Void) -> ((String) -> Void)? {
        model.inExtension ? nil : open
    }
}
