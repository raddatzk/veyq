import SwiftUI
import VeyqCore

/// "Manage servers" (owner 2026-11-08): every server the app is signed in to, with its tenants and their spaces — each
/// shown or hidden on this device —, signing out of one server, and adding another.
struct ManageView: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss
    /// The server whose way back to the password trust asks for the password now, and what is typed there.
    @State private var askPassword: VeyqAccount?
    @State private var password = ""

    var body: some View {
        NavigationStack {
            Form {
                ForEach(model.managing ?? []) { server in
                    Section {
                        ForEach(server.tenants) { t in
                            Toggle(isOn: Binding(get: { t.shown }, set: { model.setTenantHidden(server.account, t.tenant, !$0) })) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(t.tenant.name).fontWeight(.semibold)
                                    Text(t.subtitle).font(.caption).foregroundStyle(Role.muted)
                                }
                            }
                            .disabled(!t.switchable)
                            if let spaces = t.spaces {
                                ForEach(spaces) { s in
                                    Toggle(Names.spaceName(s.space), isOn: Binding(get: { !s.hidden }, set: { model.setSpaceHidden(s.space.scopeId, !$0) }))
                                        .disabled(!s.canHide)
                                        .padding(.leading, 20)
                                }
                            } else {
                                ProgressView().padding(.leading, 20)
                            }
                        }
                        Button(role: .destructive) { model.signOut(server.account) } label: {
                            Label(say(.manageSignOut), systemImage: "rectangle.portrait.and.arrow.right")
                        }
                        if let trusted = server.trusted {
                            // the changes to the trust (D7): the other level, back to the password
                            ForEach(Array(TrustWords.changes(trusted, server.level, server.levels).enumerated()), id: \.offset) { _, change in
                                if let change {
                                    Button { model.stayAt(server.account, change) } label: {
                                        Label(TrustWords.level(change).title, systemImage: TrustWords.symbol(change))
                                    }
                                } else {
                                    Button {
                                        password = model.takeDevPasswordTrust() ?? ""
                                        askPassword = server.account
                                    } label: {
                                        Label(say(.managePasswordTrust), systemImage: "key")
                                    }
                                }
                            }
                            .disabled(model.trustWorking)
                            Button(role: .destructive) { model.forgetDevice(server.account) } label: {
                                Label(say(.manageForgetDevice), systemImage: "lock.slash")
                            }
                            .disabled(model.trustWorking)
                        }
                    } header: {
                        Label(server.label, systemImage: "server.rack")
                    } footer: {
                        // How this device trusts the account; ending it means the next sign-in asks for the Secret Key.
                        VStack(alignment: .leading, spacing: 4) {
                            if let trusted = server.trusted { Text(TrustWords.trusted(trusted, server.level)) }
                            if let said = model.manageSaid[server.account.key] { Text(said).foregroundStyle(.red) }
                        }
                    }
                }
                Section {
                    Button { model.addServer() } label: { Label(say(.switcherAddServer), systemImage: "plus") }
                } footer: {
                    Text(say(.manageHint))
                }
            }
            .formStyle(.grouped)
            // "Back to Your Password" asks for it (a presentation of a section goes on the Form: src/mobile/CLAUDE.md)
            .alert(say(.managePasswordTrust), isPresented: Binding(get: { askPassword != nil }, set: { if !$0 { askPassword = nil } })) {
                SecureField(say(.signinPassword), text: $password)
                Button(say(.signinCancel), role: .cancel) { askPassword = nil; password = "" }
                Button(say(.ok)) {
                    guard let account = askPassword else { return }
                    let typed = password
                    askPassword = nil
                    password = ""
                    Task { _ = await model.backToPassword(account, typed) }
                }
                .disabled(password.isEmpty)
            } message: {
                Text(say(.managePasswordTrustHint))
            }
            .navigationTitle(say(.manageTitle))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(say(.shareDone)) { model.managing = nil } }
            }
        }
        .tint(Role.accent)
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 480)
        #endif
    }
}
