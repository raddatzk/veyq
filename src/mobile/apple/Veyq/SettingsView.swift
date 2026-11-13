import SwiftUI
import VeyqCore

/// What the account's Settings page asks of whoever holds its model (`cr-app-account-settings` D1): each a call of core's
/// `AccountSettings`, so the page decides nothing. `AppModel` holds the real one; a debug build's `DevSettings` plays one.
@MainActor
protocol SettingsActions: AnyObject {
    func closeSettings()
    func renameDevice(_ label: String)
    func signOutFamily(_ familyId: String)
    func signOutOthers()
    func signOutAndRemoveKey(_ familyId: String)
    func removeSavedKey(_ deviceId: String)
    func removeEveryOtherSavedKey()
    /// Staying signed in at `level`; the outcome.
    func settingsStay(_ level: StaySignedIn) async -> TrustChange
    /// Back to the password trust with `password`; the outcome (a wrong one changes nothing).
    func settingsBackToPassword(_ password: String) async -> TrustChange
    func stopTrusting()
    func signOutHere()
}

/// A step the page asks to be confirmed first, as the web's `SessionsPanel` does: what it says and what it does.
private struct Confirm: Identifiable {
    let id = UUID()
    let title: String
    let message: String
    let action: String
    let run: @MainActor () -> Void
}

/// The account's Settings (`cr-app-account-settings`): the address it signs in with, this device — its name, how it
/// stays signed in, "Stop Trusting This Device" —, every session of the account and the devices with a saved key, the
/// entry to "Add a Device" while the instance links devices, on iOS the system's page for the app's language, and
/// signing out. A grouped form, as the system's own settings; every row is core's state, every action a call of
/// `actions`. A session's actions are swipe actions and a context menu on iOS, a menu at its end on a Mac. The page
/// shows no secret (inbox #886): an address, device names and dates.
struct SettingsView: View {
    let state: AccountSettingsState
    let actions: SettingsActions
    #if os(iOS)
    @Environment(\.openURL) private var openURL
    #endif
    @State private var confirm: Confirm?
    @State private var renaming = false
    @State private var name = ""
    @State private var askPassword = false
    @State private var password = ""
    @State private var trustSaid: String?

    var body: some View {
        Form {
            if let refusal = state.refusal {
                Section { Text(say(refusal)).foregroundStyle(Role.danger) }
            }
            account
            device
            sessions
            savedKeys
            if state.addDevice {
                // ADR 0059 §1: offered while the instance links devices; the linking is `cr-app-device-linking`'s, so
                // the row holds its place, not yet enabled — never a control that answers "not yet"
                Section {
                    Label(say(.accountSettingsAddDevice), systemImage: "qrcode").disabled(true).foregroundStyle(Role.muted)
                } footer: {
                    Text(say(.accountSettingsAddDeviceSub))
                }
            }
            #if os(iOS)
            // D3: the app follows the system's appearance; its language is the system's per-app setting
            Section {
                Button { if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) } } label: {
                    Label(say(.accountSettingsAppearance), systemImage: "globe")
                }
            } footer: {
                Text(say(.accountSettingsAppearanceSub))
            }
            #endif
            Section {
                Button(role: .destructive) { actions.signOutHere() } label: {
                    Label(say(.drawerSignOut), systemImage: "rectangle.portrait.and.arrow.right")
                }
                .disabled(state.working)
            } footer: {
                Text(say(.accountSettingsSignOutHint))
            }
        }
        .formStyle(.grouped)
        // the presentations of the page go on the Form (src/mobile/CLAUDE.md: a section's are presented by none)
        .alert(confirm?.title ?? "", isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }), presenting: confirm) { c in
            Button(c.action, role: .destructive) { c.run() }
            Button(say(.signinCancel), role: .cancel) {}
        } message: { c in
            Text(c.message)
        }
        .alert(say(.accountSettingsRename), isPresented: $renaming) {
            TextField(say(.accountSettingsRenamePlaceholder), text: $name)
            Button(say(.signinCancel), role: .cancel) {}
            Button(say(.ok)) { actions.renameDevice(name) }
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
        } message: {
            Text(say(.accountSettingsRenameHint))
        }
        .alert(say(.managePasswordTrust), isPresented: $askPassword) {
            SecureField(say(.signinPassword), text: $password)
            Button(say(.signinCancel), role: .cancel) { password = "" }
            Button(say(.ok)) {
                let typed = password
                password = ""
                Task {
                    switch await actions.settingsBackToPassword(typed) {
                    case .done: trustSaid = nil
                    case .wrongPassword: trustSaid = say(.manageWrongPassword)
                    default: trustSaid = say(.trustFailed)
                    }
                }
            }
            .disabled(password.isEmpty)
        } message: {
            Text(say(.managePasswordTrustHint))
        }
        .overlay(alignment: .top) { if state.working { ProgressView().padding(8) } }
    }

    private var account: some View {
        Section(say(.accountSettingsAccount)) {
            VStack(alignment: .leading, spacing: 4) {
                Label(state.email, systemImage: "at")
                if let confirmed = state.emailConfirmed {
                    Text(say(confirmed.boolValue ? .accountSettingsEmailConfirmed : .accountSettingsEmailUnconfirmed))
                        .font(.footnote).foregroundStyle(Role.muted)
                }
            }
        }
    }

    private var device: some View {
        let trust = state.device.trust
        return Section {
            HStack {
                Label(state.device.label, systemImage: Self.deviceSymbol).fontWeight(.semibold)
                Spacer()
                Button {
                    name = state.device.label
                    renaming = true
                } label: {
                    Image(systemName: "pencil")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(say(.accountSettingsRename))
                .help(say(.accountSettingsRename))
                .disabled(state.working)
            }
            // how it stays signed in: the changes "Manage Servers" offers, the same core state (D2)
            if let method = trust.method {
                ForEach(Array(TrustWords.changes(method, trust.level, trust.levels).enumerated()), id: \.offset) { _, change in
                    if let change {
                        Button {
                            Task { trustSaid = await actions.settingsStay(change) == .failed ? say(.trustFailed) : nil }
                        } label: {
                            Label(TrustWords.level(change).title, systemImage: TrustWords.symbol(change))
                        }
                    } else {
                        Button { askPassword = true } label: { Label(say(.managePasswordTrust), systemImage: "key") }
                    }
                }
                .disabled(state.working)
                Button(role: .destructive) {
                    confirm = Confirm(
                        title: say(.accountSettingsUntrustConfirmTitle), message: say(.accountSettingsUntrustConfirm),
                        action: say(.manageForgetDevice), run: { [actions] in actions.stopTrusting() })
                } label: {
                    Label(say(.manageForgetDevice), systemImage: "lock.slash")
                }
                .disabled(state.working)
            }
        } header: {
            Text(say(.accountSettingsThisDevice))
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text(trust.method.map { TrustWords.trusted($0, trust.level) } ?? say(.accountSettingsDeviceUntrusted))
                if let trustSaid { Text(trustSaid).foregroundStyle(Role.danger) }
            }
        }
    }

    private static var deviceSymbol: String {
        #if os(macOS)
        "laptopcomputer"
        #else
        UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone"
        #endif
    }

    private var sessions: some View {
        Section {
            if let why = state.registerRefusal {
                Text(say(why)).font(.footnote).foregroundStyle(Role.danger)
            }
            if let families = state.families {
                if families.isEmpty {
                    Text(say(.accountSettingsSessionsEmpty)).foregroundStyle(Role.muted)
                }
                ForEach(families, id: \.id) { f in familyRow(f) }
                if state.hasOthers {
                    Button(role: .destructive) {
                        confirm = Confirm(
                            title: say(.accountSettingsRevokeOthers), message: say(.accountSettingsRevokeOthersConfirm),
                            action: say(.accountSettingsRevokeOthers), run: { [actions] in actions.signOutOthers() })
                    } label: {
                        Label(say(.accountSettingsRevokeOthers), systemImage: "rectangle.portrait.and.arrow.right")
                    }
                    .disabled(state.working)
                }
            } else if state.refusal == nil {
                Text(say(.accountSettingsSessionsLoading)).foregroundStyle(Role.muted)
            }
        } header: {
            Text(say(.accountSettingsSessions))
        } footer: {
            Text(say(.accountSettingsSessionsSub))
        }
    }

    /// One session: its device's name (or what it is, where the register names none), its dates, its actions.
    private func familyRow(_ f: AccountSettingsFamily) -> some View {
        let name = f.label ?? say(f.current ? .accountSettingsThisDevice : .accountSettingsOtherDevice)
        let dates = [
            f.lastUsedAt.map { say(.accountSettingsLastUsed, Self.moment($0)) },
            f.createdAt.map { say(.accountSettingsSignedIn, Self.moment($0)) },
        ].compactMap { $0 }
        return HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(name).fontWeight(f.current ? .semibold : .regular)
                    if f.current {
                        Text(say(.accountSettingsCurrent))
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 8).padding(.vertical, 2)
                            .background(Role.accentSoft, in: Capsule())
                            .foregroundStyle(Role.accent)
                    }
                }
                if !dates.isEmpty { Text(dates.joined(separator: " · ")).font(.footnote).foregroundStyle(Role.muted) }
            }
            #if os(macOS)
            Spacer()
            Menu { familyActions(f) } label: { Image(systemName: "ellipsis.circle") }
                .menuStyle(.button).buttonStyle(.borderless).fixedSize()
                .accessibilityLabel(say(f.current ? .accountSettingsRevokeSelf : .accountSettingsRevoke))
                .disabled(state.working)
            #endif
        }
        #if os(iOS)
        .swipeActions(edge: .trailing) { familyActions(f).disabled(state.working) }
        .contextMenu { familyActions(f) }
        #endif
    }

    @ViewBuilder
    private func familyActions(_ f: AccountSettingsFamily) -> some View {
        let id = f.id
        if f.current {
            Button(role: .destructive) {
                confirm = Confirm(
                    title: say(.accountSettingsRevokeSelf), message: say(.accountSettingsRevokeSelfConfirm),
                    action: say(.accountSettingsRevokeSelf), run: { [actions] in actions.signOutFamily(id) })
            } label: {
                Label(say(.accountSettingsRevokeSelf), systemImage: "rectangle.portrait.and.arrow.right")
            }
        } else {
            Button(role: .destructive) {
                confirm = Confirm(
                    title: say(.accountSettingsRevoke), message: say(.accountSettingsRevokeConfirm),
                    action: say(.accountSettingsRevoke), run: { [actions] in actions.signOutFamily(id) })
            } label: {
                Label(say(.accountSettingsRevoke), systemImage: "rectangle.portrait.and.arrow.right")
            }
            if f.removesKey {
                Button(role: .destructive) {
                    confirm = Confirm(
                        title: say(.accountSettingsEscalate), message: say(.accountSettingsEscalateConfirm),
                        action: say(.accountSettingsEscalateAccept), run: { [actions] in actions.signOutAndRemoveKey(id) })
                } label: {
                    Label(say(.accountSettingsEscalate), systemImage: "key.slash")
                }
            }
        }
    }

    @ViewBuilder
    private var savedKeys: some View {
        if !state.savedKeyDevices.isEmpty || state.otherSavedKeys {
            Section {
                ForEach(state.savedKeyDevices, id: \.deviceId) { d in
                    let id = d.deviceId
                    let ask = {
                        confirm = Confirm(
                            title: say(.accountSettingsRemoveKey), message: say(.accountSettingsRemoveKeyConfirm, d.label),
                            action: say(.accountSettingsRemoveKey), run: { [actions] in actions.removeSavedKey(id) })
                    }
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Label(d.label, systemImage: "key")
                            Text(say(.accountSettingsSavedKeyRow)).font(.footnote).foregroundStyle(Role.muted)
                        }
                        Spacer()
                        Button(role: .destructive, action: ask) { Image(systemName: "trash") }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(say(.accountSettingsRemoveKey))
                            .help(say(.accountSettingsRemoveKey))
                            .disabled(state.working)
                    }
                }
                if state.otherSavedKeys {
                    Button(role: .destructive) {
                        confirm = Confirm(
                            title: say(.accountSettingsRemoveOtherKeys), message: say(.accountSettingsRemoveOtherKeysConfirm),
                            action: say(.accountSettingsRemoveOtherKeys), run: { [actions] in actions.removeEveryOtherSavedKey() })
                    } label: {
                        Label(say(.accountSettingsRemoveOtherKeys), systemImage: "key.slash")
                    }
                    .disabled(state.working)
                }
            } header: {
                Text(say(.accountSettingsSavedKeys))
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(say(.accountSettingsSavedKeysSub))
                    Text(say(.accountSettingsSavedKeysHint))
                }
            }
        }
    }

    /// A moment the server gave, as the system writes a date and time in the person's language.
    private static func moment(_ epochMs: KotlinLong) -> String {
        Date(timeIntervalSince1970: Double(epochMs.int64Value) / 1000).formatted(date: .abbreviated, time: .shortened)
    }
}

/// The page in its window: a navigation stack with its title and Done (iOS, a sheet over the shell), or the content of
/// the app's Settings window (macOS, `Settings { }`, which opens it on ⌘, and the drawer's Settings).
struct SettingsPage: View {
    @Bindable var model: AppModel

    var body: some View {
        Group {
            if let state = model.settings {
                #if os(macOS)
                SettingsView(state: state, actions: model)
                #else
                NavigationStack {
                    SettingsView(state: state, actions: model)
                        .navigationTitle(say(.drawerSettings))
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) { Button(say(.shareDone)) { model.closeSettings() } }
                        }
                }
                #endif
            } else {
                ContentUnavailableView(say(.drawerSettings), systemImage: "gearshape")
            }
        }
        .tint(Role.accent)
        #if os(macOS)
        .frame(minWidth: 480, idealWidth: 560, minHeight: 520)
        .onAppear { model.openSettings() }
        .onDisappear { model.closeSettings() }
        #endif
    }
}
