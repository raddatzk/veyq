import SwiftUI
import VeyqCore

/// "Manage servers" (owner 2026-11-08): every server the app is signed in to, with its tenants and their spaces — each
/// shown or hidden on this device —, signing out of one server, and adding another.
struct ManageView: View {
    @Bindable var model: AppModel
    @Environment(\.dismiss) private var dismiss

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
                        if server.trusted != nil {
                            Button(role: .destructive) { model.forgetDevice(server.account) } label: {
                                Label(say(.manageForgetDevice), systemImage: "lock.slash")
                            }
                        }
                    } header: {
                        Label(server.label, systemImage: "server.rack")
                    } footer: {
                        // How this device trusts the account; ending it means the next sign-in asks for the Secret Key.
                        if let trusted = server.trusted {
                            Text(trusted == .deviceKey ? say(.manageTrustedRemembered) : say(.manageTrustedPassword))
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
