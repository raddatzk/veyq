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
                            Toggle(isOn: Binding(get: { !t.hidden || t.current }, set: { model.setTenantHidden(server.account, t.tenant, !$0) })) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(t.tenant.name).fontWeight(.semibold)
                                    Text(t.current ? "Shown now" : t.tenant.domain).font(.caption).foregroundStyle(Role.muted)
                                }
                            }
                            .disabled(t.current)
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
                            Label("Sign out of this server", systemImage: "rectangle.portrait.and.arrow.right")
                        }
                    } header: {
                        Label(server.label, systemImage: "server.rack")
                    }
                }
                Section {
                    Button { model.addServer() } label: { Label("Add server", systemImage: "plus") }
                } footer: {
                    Text("Hidden tenants and spaces stay on the server and are only left out on this device.")
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Servers")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { model.managing = nil } }
            }
        }
        .tint(Role.accent)
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 480)
        #endif
    }
}
