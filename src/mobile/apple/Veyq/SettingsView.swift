import SwiftUI
import VeyqCore

/// What the account's Settings page asks of whoever holds its model (`uc.account.sessions`; ADR 0136 decision 2): each a call of core's
/// `AccountSettings`, so the page decides nothing. `AppModel` holds the real one; a debug build's `DevSettings` plays one.
@MainActor
protocol SettingsActions: AnyObject {
    func closeSettings()
    func renameDevice(_ label: String)
    /// Renames the device of session family `familyId` — any device the register names, every session of it (ADR 0148 D1).
    func renameFamily(_ familyId: String, _ label: String)
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
    /// "Add a Device"'s "Show a Code": this device shows its code, the new device scans (ADR 0059 §1).
    func linkShow()
    /// "Add a Device"'s "Scan a Code": the new device shows its sign-in code, this device scans.
    func linkScan()
    /// "Storage on This Device" (ADR 0140 decision 10): the limit typed as `amount` in `unit`; core refuses what is no size.
    func setOfflineLimit(_ amount: String, _ unit: LimitUnit)
    func turnOfflineLimitOff()
    func clearOfflineCache()
    func unpinOffline(_ node: String)
    /// "Remove All Offline Content", asked first (core's `askingRemoveAll`), then `removeAllOffline` or the cancel.
    func askRemoveAllOffline()
    func cancelRemoveAllOffline()
    func removeAllOffline()
}

extension SettingsActions {
    func linkShow() {}
    func linkScan() {}
    func setOfflineLimit(_ amount: String, _ unit: LimitUnit) {}
    func turnOfflineLimitOff() {}
    func clearOfflineCache() {}
    func unpinOffline(_ node: String) {}
    func askRemoveAllOffline() {}
    func cancelRemoveAllOffline() {}
    func removeAllOffline() {}
}

/// A step the page asks to be confirmed first, as the web's `SessionsPanel` does: what it says and what it does.
private struct Confirm: Identifiable {
    let id = UUID()
    let title: String
    let message: String
    let action: String
    let run: @MainActor () -> Void
}

/// The account's Settings (`uc.account.sessions`): the address it signs in with, this device — its name, how it
/// stays signed in, "Stop Trusting This Device" —, every session of the account and the devices with a saved key, the
/// entry to "Add a Device" while the instance links devices, on iOS the system's page for the app's language, and
/// signing out. A grouped form, as the system's own settings; every row is core's state, every action a call of
/// `actions`. A session's actions are swipe actions and a context menu on iOS, a menu at its end on a Mac. The page
/// shows no secret (inbox #886): an address, device names and dates.
struct SettingsView: View {
    let state: AccountSettingsState
    let actions: SettingsActions
    /// The app's model, for this device's map services (ADR 0143 D2); nil where the page is played without one.
    var maps: AppModel? = nil
    /// The section a search result chose (`SettingsSection.id`, ADR 0146 decision 9): the page scrolls to it, then says
    /// `scrolled`.
    var scrollTo: String? = nil
    var scrolled: () -> Void = {}
    #if os(iOS)
    @Environment(\.openURL) private var openURL
    #endif
    @State private var confirm: Confirm?
    @State private var renaming = false
    /// The rename's target: nil this device (`renameDevice`), else the family whose device is renamed (`renameFamily`).
    @State private var renamingFamily: AccountSettingsFamily?
    @State private var name = ""
    /// The account's flow open over the page (ADR 0148 decision 10), each a sheet of its own.
    @State private var flow: AccountFlow?
    @State private var askPassword = false
    @State private var password = ""
    @State private var trustSaid: String?
    /// The limit's free size field as typed, and its unit; set from core's field whenever that changes.
    @State private var limitAmount = ""
    @State private var limitUnit: LimitUnit = .gb

    var body: some View {
        ScrollViewReader { proxy in
            form
                .onChange(of: scrollTo, initial: true) { _, section in
                    guard let section else { return }
                    scrolled()
                    anchored = section
                    DispatchQueue.main.async { proxy.scrollTo(section, anchor: .top) }
                    // the sessions and the saved keys read after the page opened move what is below them: the section is
                    // scrolled to again as they arrive, for a moment
                    Task {
                        try? await Task.sleep(for: .seconds(3))
                        if anchored == section { anchored = nil }
                    }
                }
                .onChange(of: layoutKey) {
                    guard let anchored else { return }
                    DispatchQueue.main.async { proxy.scrollTo(anchored, anchor: .top) }
                }
        }
    }

    /// The section a search result chose, while what reads after the page opened may still move it.
    @State private var anchored: String?

    /// What the page reads after it opened, which changes its sections' heights.
    private var layoutKey: String {
        "\(state.families?.count ?? -1)|\(state.savedKeyDevices.count)|\(state.otherSavedKeys)|\(state.addDevice)|\(state.storage?.items.count ?? -1)"
    }

    /// The page's sections, each anchored by its `SettingsSection.id` (the search opens the page there).
    private var form: some View {
        Form {
            if let refusal = state.refusal {
                Section { Text(say(refusal)).foregroundStyle(Role.danger) }
            }
            account.id("account")
            if let model = maps {
                flowSection(.profile, model).id("profile")
                flowSection(.password, model).id("password")
                flowSection(.email, model).id("email")
            }
            device.id("device")
            sessions.id("sessions")
            savedKeys.id("saved-keys")
            if state.addDevice {
                // ADR 0059 §1, offered while the instance links devices: both halves, so whichever device has a camera
                // scans — "Show a Code" (the new device scans) and, where this one has a camera, "Scan a Code"
                Section {
                    Button { actions.linkShow() } label: { Label(say(.linkShow), systemImage: "qrcode") }
                        .disabled(state.working)
                    if QrCamera.available {
                        Button { actions.linkScan() } label: { Label(say(.linkScan), systemImage: "qrcode.viewfinder") }
                            .disabled(state.working)
                    }
                } header: {
                    Text(say(.accountSettingsAddDevice))
                } footer: {
                    Text(say(.accountSettingsAddDeviceSub) + " " + say(.linkIntro))
                }
                .id("add-device")
            }
            storage.id("storage")
            if let maps { MapServicesSection(model: maps).id("maps") }
            #if os(iOS)
            // D3: the app follows the system's appearance; its language is the system's per-app setting
            Section {
                Button { if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) } } label: {
                    Label(say(.accountSettingsAppearance), systemImage: "globe")
                }
            } footer: {
                Text(say(.accountSettingsAppearanceSub))
            }
            .id("appearance")
            #endif
            if let model = maps {
                flowSection(.security, model).id("security")
                flowSection(.erase, model).id("erase")
            }
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
        // the name goes into the field as the alert appears, never before presenting it: a Mac's alert keeps its text
        // field across presentations, and a value the binding already held when it was presented again never reached
        // that field, which showed empty (inbox #917); the change made once it shows is pushed into the live field
        .onChange(of: renaming) { _, open in
            guard open else { return }
            let label = renamingFamily?.label ?? state.device.label
            DispatchQueue.main.async { name = label }
        }
        .alert(say(.accountSettingsRename), isPresented: $renaming) {
            TextField(say(.accountSettingsRenamePlaceholder), text: $name)
            Button(say(.signinCancel), role: .cancel) { renamingFamily = nil }
            Button(say(.ok)) {
                if let family = renamingFamily, !family.current { actions.renameFamily(family.id, name) } else { actions.renameDevice(name) }
                renamingFamily = nil
            }
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
        .alert(say(.accountSettingsStorageRemoveAll), isPresented: Binding(get: { state.askingRemoveAll }, set: { if !$0, state.askingRemoveAll { actions.cancelRemoveAllOffline() } })) {
            Button(say(.accountSettingsStorageRemoveAllConfirm), role: .destructive) { actions.removeAllOffline() }
            Button(say(.signinCancel), role: .cancel) { actions.cancelRemoveAllOffline() }
        } message: {
            Text(say(.accountSettingsStorageRemoveAllQuestion))
        }
        .task(id: limitKey) {
            guard let field = state.storage?.field else { return }
            limitAmount = field.amount
            limitUnit = field.unit
        }
        .overlay(alignment: .top) { if state.working { ProgressView().padding(8) } }
        .sheet(item: $flow) { f in
            if let model = maps { AccountFlowSheet(flow: f, model: model) }
        }
    }

    /// A section that opens one of the account's flows (ADR 0148 decision 10): its rubric, what it is for, the button.
    private func flowSection(_ f: AccountFlow, _ model: AppModel) -> some View {
        Section {
            Button(role: f == .erase ? .destructive : nil) { flow = f } label: { Label(f.action, systemImage: f.symbol) }
                .disabled(state.working)
        } header: {
            Text(f.title)
        } footer: {
            Text(f.sub)
        }
    }

    /// Opens the rename for this device (`family` nil) or for the device of `family`; the name is set once the alert shows.
    private func rename(_ family: AccountSettingsFamily?) {
        renamingFamily = family
        name = "" // the label is set once the alert shows (`onChange(of: renaming)`), so it is always a change (inbox #917)
        renaming = true
    }

    /// Core's free size field as one value: the field is set anew from it whenever core's changes.
    private var limitKey: String {
        guard let f = state.storage?.field else { return "" }
        return "\(f.amount)|\(f.unit.name)|\(f.off)"
    }

    private static func size(_ bytes: Int64) -> String { ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file) }

    /// "Storage on This Device" (ADR 0140 decision 10), as the system's own storage pages show it: the limit (a free size
    /// with its unit, or Off), what the recently viewed content and the items available offline use and the volume's
    /// free space as values, each item available offline with its state and its removal, then "Clear Cache" and "Remove All
    /// Offline Content" (asked first). Where the device keeps no store of the account, only the reason core gives.
    @ViewBuilder
    private var storage: some View {
        if let storage = state.storage {
            Section {
                LabeledContent(say(.accountSettingsStorageLimit)) {
                    Text(storage.field.off ? say(.accountSettingsStorageLimitOff) : "\(storage.field.amount) \(say(storage.field.unit.word))")
                }
                HStack {
                    TextField(say(.accountSettingsStorageLimit), text: $limitAmount)
                        #if os(iOS)
                        .keyboardType(.decimalPad)
                        #endif
                        .labelsHidden()
                    Picker(say(.accountSettingsStorageLimit), selection: $limitUnit) {
                        ForEach([LimitUnit.mb, LimitUnit.gb], id: \.self) { u in Text(say(u.word)).tag(u) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    .fixedSize()
                }
                Button(say(.accountSettingsStorageLimitSet)) { actions.setOfflineLimit(limitAmount, limitUnit) }
                    .disabled(state.working || limitAmount.trimmingCharacters(in: .whitespaces).isEmpty)
                if !storage.field.off {
                    Button(say(.accountSettingsStorageLimitOff)) { actions.turnOfflineLimitOff() }
                        .disabled(state.working)
                }
            } header: {
                Text(say(.accountSettingsStorage))
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(say(.accountSettingsStorageLimitHint))
                    if state.refusal == .accountSettingsStorageLimitInvalid {
                        Text(say(.accountSettingsStorageLimitInvalid)).foregroundStyle(Role.danger)
                    }
                }
            }
            Section {
                LabeledContent(say(.accountSettingsStorageAutomatic), value: Self.size(storage.automatic))
                LabeledContent(say(.accountSettingsStoragePinned), value: Self.size(storage.pinned))
                if let free = storage.free { LabeledContent(say(.accountSettingsStorageFree), value: Self.size(free.int64Value)) }
            }
            Section {
                if storage.items.isEmpty {
                    Text(say(.accountSettingsStorageItemsEmpty)).foregroundStyle(Role.muted)
                }
                ForEach(storage.items, id: \.node) { item in offlineRow(item) }
            } header: {
                Text(say(.accountSettingsStoragePinned))
            }
            Section {
                Button(say(.accountSettingsStorageClearCache)) { actions.clearOfflineCache() }
                    .disabled(state.working)
                Button(say(.accountSettingsStorageRemoveAll), role: .destructive) { actions.askRemoveAllOffline() }
                    .disabled(state.working)
            }
        } else if let why = state.storageUnavailable {
            Section(say(.accountSettingsStorage)) {
                Text(say(why)).foregroundStyle(Role.muted)
            }
        }
    }

    /// One item available offline: its name (core's, from the tree the app holds), its state and size, its removal — a
    /// swipe action and a context menu on iOS, a button at its end on a Mac (as a saved key's row).
    private func offlineRow(_ item: OfflineItem) -> some View {
        let node = item.node
        return HStack {
            VStack(alignment: .leading, spacing: 3) {
                Label(say(notice: item.title), systemImage: Names.kindSymbol(item.kind))
                Text([item.said.map { say(notice: $0) }, Self.size(item.bytes)].compactMap { $0 }.joined(separator: " · "))
                    .font(.footnote).foregroundStyle(Role.muted)
            }
            #if os(macOS)
            Spacer()
            Button(role: .destructive) { actions.unpinOffline(node) } label: { Image(systemName: "trash") }
                .buttonStyle(.borderless)
                .accessibilityLabel(say(.accountSettingsStorageRemoveItem))
                .help(say(.accountSettingsStorageRemoveItem))
                .disabled(state.working)
            #endif
        }
        #if os(iOS)
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) { actions.unpinOffline(node) } label: {
                Label(say(.accountSettingsStorageRemoveItem), systemImage: "trash")
            }
            .disabled(state.working)
        }
        .contextMenu {
            Button(role: .destructive) { actions.unpinOffline(node) } label: {
                Label(say(.accountSettingsStorageRemoveItem), systemImage: "trash")
            }
        }
        #endif
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
                    rename(nil)
                } label: {
                    Image(systemName: "pencil")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(say(.accountSettingsRename))
                .help(say(.accountSettingsRename))
                .disabled(state.working)
            }
            // how it stays signed in: the changes "Manage Servers" offers, the same core state (D2); a device a password
            // change left without trust is offered each level and the password trust (inbox #1009)
            ForEach(Array(TrustWords.changes(trust.method, trust.level, trust.levels).enumerated()), id: \.offset) { _, change in
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
            if trust.method != nil {
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
        // the web's `renameDeviceOfFamily`: only a device the register names (core writes nothing for another)
        if f.label != nil {
            Button { rename(f) } label: { Label(say(.accountSettingsRename), systemImage: "pencil") }
        }
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
    #if os(macOS)
    @Environment(\.dismissWindow) private var dismissWindow
    #endif

    var body: some View {
        Group {
            if let state = model.settings {
                #if os(macOS)
                SettingsView(state: state, actions: model, maps: model, scrollTo: model.settingsAnchor) { model.settingsAnchor = nil }
                #else
                NavigationStack {
                    SettingsView(state: state, actions: model, maps: model, scrollTo: model.settingsAnchor) { model.settingsAnchor = nil }
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
        // "Add a Device"'s ceremony, over the page (ADR 0059)
        .deviceLinkSheet(model)
        // Sign out, Stop trusting, Remove all offline content asked first while writes made offline wait
        .modifier(LossAsking(model: model, here: model.settings != nil))
        .tint(Role.accent)
        #if os(macOS)
        .frame(minWidth: 480, idealWidth: 560, minHeight: 520)
        .onAppear { model.openSettings() }
        .onDisappear { model.closeSettings() }
        // the page went while the window shows (an erasure, a sign-out from it): the window goes with it, never an empty
        // "Settings" left over the sign-in (the macOS pass, 2026-11-19); opened while signed out it shows the placeholder
        .onChange(of: model.settings == nil) { was, gone in if gone && !was { dismissWindow() } }
        #endif
    }
}
