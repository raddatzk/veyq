import CoreGraphics
import Foundation
import Observation
import UIKit
import VeyqCore

/// The veyq app on a TV (ADR 0159): core's way in (`SessionModel` — the picker, a PIN, a QR sign-in) and core's shell
/// (`ShellModel`) over the one account open, held for SwiftUI. A viewing app: the device is `DeviceForm.TV`, so every plan
/// the core hands it holds no authoring and no download (decision 2), and nothing here takes any away. The idle lock is
/// the core's (`IdleLock`), fed by the TV's focus and presses, its players and its scene (decision 4, ADR 0160).
@MainActor
@Observable
final class TvModel {
    typealias Opened = SessionModelOpened<VeyqAccount, TenantSession>

    /// The way in as core has it now (`SessionModel.current`).
    private(set) var gate: SessionModelGate = SessionModelGate.Starting.shared
    /// The offer after a QR sign-in (core's `Gate.Signed.offer`): the PIN asked; nil once answered.
    private(set) var offer: SessionModelOffer?
    /// The tenant's terms gate (core's `Gate.Signed.terms`, uc.account.accept-terms), drawn in place of the shell.
    private(set) var terms: SessionModelTermsGate?

    /// The terms gate's "I Agree" (core's `acceptTerms`).
    func acceptTerms() { Task { _ = try? await session.acceptTerms() } }
    private(set) var trustWorking = false
    /// What a change of this TV's trust left to say (a PIN that could not be kept).
    var said: String?

    /// The QR sign-in on screen (core's `DeviceLink`, role `showForSignIn`) and its state.
    private(set) var link: DeviceLink?
    private(set) var linkShown: DeviceLink.Shown?

    /// The shell of the account open, and what it shows now; nil while none is open.
    @ObservationIgnored private(set) var shellModel: ShellModel?
    private(set) var state: ShellModel.State?
    private(set) var tenantSession: TenantSession?
    var shell: Shell? { state?.shell }

    /// The top tab chosen (ADR 0159 decision 6: the drawer's content as a tab bar).
    var tab: String = TvTab.home

    /// Each listed node's cover (its framed part) and its row's facts (core's `RowDetails`).
    private(set) var covers: [String: (key: String, image: CGImage)] = [:]
    private(set) var details: [String: String] = [:]

    /// The search over the account open (core's `SearchModel`), made when the Search tab first shows.
    @ObservationIgnored private var searchModel: SearchModel?
    @ObservationIgnored private var searchWatch: SearchModel.Watch?
    private(set) var found: SearchShown?

    /// The account's music (core's `MusicPlayer`), a page's music through it (`PageMusic`), and what plays.
    @ObservationIgnored private(set) var pageMusic: PageMusic?
    @ObservationIgnored private var musicPlayer: MusicPlayer?
    @ObservationIgnored private var musicWatch: MusicPlayer.Watch?
    private(set) var nowPlaying: NowPlaying?
    var musicCommands: (any PlayerCommands)? { musicPlayer }

    /// The idle lock (core's, 15 minutes while nothing plays, and at every return from the background).
    @ObservationIgnored let idle = IdleLock(clock: { KotlinLong(value: Int64(ProcessInfo.processInfo.systemUptime * 1000)) })
    @ObservationIgnored private var playingNow: Set<String> = []

    @ObservationIgnored private let device: ThisDevice
    @ObservationIgnored private let session: SessionModel<VeyqAccount, TenantSession>
    @ObservationIgnored private let hidden: Hidden
    @ObservationIgnored private var gateWatch: SessionModelWatch?
    @ObservationIgnored private var followWatch: SessionModelWatch?
    @ObservationIgnored private var shellWatch: ShellModel.Watch?
    @ObservationIgnored private var linkWatch: DeviceLink.Watch?
    @ObservationIgnored private var rows = RowDetails.ReadCompanion.shared.EMPTY

    init() {
        AppComposition.shared.install()
        let keychain = TvKeychain()
        let defaults = UserDefaults.standard
        // a first start after an installation: the Keychain may still hold an earlier installation's items
        if !defaults.bool(forKey: "veyq.installed") {
            keychain.removeAll()
            defaults.set(true, forKey: "veyq.installed")
        }
        device = AppleSessions.shared.tvDevice(keychain: keychain, label: UIDevice.current.name, prompt: say(.deviceUnlockPrompt), defaults: defaults)
        // Recent is each account's, in memory on a TV (no store, inbox #1423): the device-wide list of before goes unread
        defaults.removeObject(forKey: "veyq.recent")
        hidden = Hidden(
            persist: { @Sendable keys in UserDefaults.standard.set(Array(keys), forKey: "veyq.hidden") },
            initial: Set(defaults.stringArray(forKey: "veyq.hidden") ?? [])
        )
        session = AppleSessions.shared.sessionModel(device: device, hidden: hidden, signInElsewhere: false)
        gateWatch = session.watch { @Sendable [weak self] _ in Task { @MainActor in self?.gateChanged() } }
        followWatch = session.watchFollowed { @Sendable [weak self] _ in Task { @MainActor in self?.follow() } }
    }

    /// The picker, or the sign-in where no account is kept: a TV never resumes an account by itself (ADR 0159 decision 4).
    func start() {
        Task { if let opened = try? await session.start() { await show(opened) } }
    }

    private func gateChanged() {
        let was = gate
        gate = session.current
        if let g = gate as? SessionModelGate.Signed {
            offer = g.offer
            terms = g.terms
            // an account opened: the idle time starts here
            if !(was is SessionModelGate.Signed) { idle.opened() }
        } else {
            offer = nil
            terms = nil
            closeTenant()
        }
    }

    /// The account shown ended on its own and the core opened another in its place.
    private func follow() {
        guard let opened = session.followed.value as? Opened else { return }
        Task { if shellModel?.tenant !== opened.session { await show(opened) } }
    }

    // MARK: The picker and the PIN (ADR 0159 decision 4, ADR 0160)

    /// A tile picked: its PIN unlock, or — kept without a PIN (owner 2026-11-26) — the account opened (core's `pick`).
    func pick(_ account: String) {
        Task { if let opened = try? await session.pick(key: account) { await show(opened) } }
    }

    func backToPicker() { session.backToPicker() }

    /// Only the account picked opens; whatever was open closes first (core's `unlockWithPin`).
    func unlockWithPin(_ pin: String) {
        Task { if let opened = try? await session.unlockWithPin(pin: pin) { await show(opened) } }
    }

    /// *Lock*, a return from the background, the idle lock: every open account closed, the picker shown.
    func lock() {
        closeLink()
        Task { try? await session.lock() }
    }

    /// *Add someone*: the QR sign-in.
    func addServer() { session.addServer() }

    func cancelAdd() { session.cancelAdd() }

    /// The offer's PIN, typed twice and the same: the account kept behind it; `done` gets core's answer.
    func stayWithPin(_ pin: String, done: @escaping (TrustChange?) -> Void) {
        Task {
            trustWorking = true
            let change = (try? await session.stayWithPin(pin: pin)) ?? nil
            trustWorking = false
            if change == .failed { said = say(.trustFailed) }
            done(change)
        }
    }

    /// *Don't keep me signed in*, or OK where this TV cannot keep an account: the account is held for this run only.
    func notNow() { _ = session.notNow() }

    /// *Stay signed in without a PIN*, confirmed after its warning (owner 2026-11-26): the account kept at the login level,
    /// which a TV's store keeps bound to nothing but this device.
    func stayWithoutPin() {
        Task {
            trustWorking = true
            let change = (try? await session.stay(level: .login)) ?? nil
            trustWorking = false
            if change == .failed { said = say(.trustFailed) }
        }
    }

    // MARK: The QR sign-in (ADR 0059's *Sign in with a QR code*, the TV's only way in)

    func showForSignIn(server: String) {
        let s = server.trimmingCharacters(in: .whitespaces)
        openLink { [session] in AppleLink.shared.showForSignIn(session: session, server: s) }
    }

    @ObservationIgnored private var linkMake: (@MainActor () -> DeviceLink?)?

    /// "Start over": a new ceremony, a new secret.
    func linkAgain() {
        guard let make = linkMake else { return }
        openLink(make)
    }

    func closeLink() {
        linkWatch?.stop()
        linkWatch = nil
        link?.close()
        link = nil
        linkShown = nil
    }

    private func openLink(_ make: @escaping @MainActor () -> DeviceLink?) {
        closeLink()
        guard let made = make() else { return }
        linkMake = make
        link = made
        linkShown = made.current
        nonisolated(unsafe) let watched = made
        let session = session
        // the TV signed in: the shell opens over the account the grant made, the PIN offered over it
        Task { @MainActor [weak self] in
            if let opened = try? await session.signedInBy(link: watched) { await self?.show(opened) }
        }
        linkWatch = made.watch { @Sendable [weak self] shown in
            nonisolated(unsafe) let shown = shown
            Task { @MainActor [weak self] in
                guard let self, watched === self.link else { return }
                self.linkShown = shown
                if shown.step == .done { self.closeLink() }
            }
        }
    }

    // MARK: The account open

    private func show(_ opened: Opened) async {
        let t = opened.session
        if let was = tenantSession, was !== t { closeTenant() }
        tenantSession = t
        let model = ShellModel(tenant: t, hidden: hidden, now: { @Sendable in KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) })
        shellModel = model
        openMusic(model)
        try? await model.load(openLastUsed: false)
        guard shellModel === model else { return }
        shellWatch = model.watch { @Sendable [weak self] _ in Task { @MainActor in self?.applyShell() } }
        tab = TvTab.home
        applyShell()
        await readDetails(t)
    }

    private func applyShell() {
        guard let m = shellModel, let s = m.current else { return }
        state = s
        // the Search tab follows the shell: a result opened leaves it for where it opened, Back returns to it
        if s.shell.searching { tab = TvTab.search }
        else if tab == TvTab.search { tab = s.shell.application.map(TvTab.app) ?? TvTab.home }
    }

    private func closeTenant() {
        closeSearch()
        closeMusic()
        shellWatch?.stop()
        shellWatch = nil
        shellModel = nil
        state = nil
        covers = [:]
        details = [:]
        rows = RowDetails.ReadCompanion.shared.EMPTY
        headers.values.forEach { $0.watch.stop() }
        headers = [:]
        tenantSession?.close()
        tenantSession = nil
        playingNow = []
        idle.playing(on: false)
    }

    // MARK: Core's actions on a node

    /// The headers handed out, one per node and name (core's `NodeHeaderModel`), each watched: a header's view reads
    /// `headerRevision` to be drawn anew. On a TV its plan holds the report alone, where the server takes one.
    @ObservationIgnored private var headers: [String: (model: NodeHeaderModel, watch: NodeHeaderModel.Watch)] = [:]
    private(set) var headerRevision = 0

    /// The header of `node` over the shell. A TV writes nothing (its rights are `NodeHeader.viewOnly`), so no write closes
    /// an open object or reads the lists anew; the facts are a writable page's, which on a TV opens no door either way.
    func header(_ node: NodeAt) -> NodeHeaderModel? {
        guard let m = shellModel else { return nil }
        let key = "\(node.scopeId)/\(node.nodeId)/\(node.name)"
        if let held = headers[key] { return held.model }
        let made = m.header(node: node, facts: AppKinds.Facts(canWrite: true, hasContent: true), closeOpen: { @Sendable _ in }, changed: { @Sendable in })
        let watch = made.watch { @Sendable _ in
            Task { @MainActor [weak self] in self?.headerRevision += 1 }
        }
        headers[key] = (made, watch)
        return made
    }

    /// The report of `node` to the tenant's moderators (core's `ReportSheet`).
    func reportSheet(_ node: NodeAt) -> VeyqCore.ReportSheet? { shellModel?.reportSheet(node: node) }

    /// The shell moved, at once (its transitions are `Shell`'s).
    func move(_ change: @escaping (Shell) -> Shell) {
        shellModel?.update(change: change)
        applyShell()
    }

    func choose(_ app: ApplicationId?) {
        if shell?.searching == true { shellModel?.search(on: false) }
        shellModel?.choose(app: app)
        applyShell()
    }

    func open(_ ref: ObjectRef) {
        shellModel?.open(ref: ref)
        applyShell()
    }

    func enter(_ folder: FolderRef) { move { $0.enter(folder: folder) } }

    var canGoBack: Bool { shell?.canGoBack ?? false }

    func goBack() { move { $0.goBack() } }

    /// The lists of the space shown (core's `Lists`).
    var lists: ShellModel.Lists? { state?.lists }

    // MARK: Covers and row facts (core's RowDetails, uc.platform.node-cover)

    private func readDetails(_ t: TenantSession) async {
        guard let read = try? await RowDetails.shared.read(tenant: t, previous: rows), tenantSession === t else { return }
        rows = read
        details = read.details.mapValues { pieceLine($0) }
        let changes = NodeCover.shared.changes(named: read.covers, held: covers.mapValues { $0.key })
        var next = covers.filter { changes.keep.contains($0.key) }
        for (nodeId, key) in changes.open {
            if let opened = (try? await AppleSessions.shared.openFramedCover(tenant: t, nodeId: nodeId)) ?? nil,
               let whole = CoverImages.image(opened.data as Data, maxPixels: Int(NodeCover.shared.THUMBNAIL_EDGE)) {
                let r = CoverFrame.companion.pixels(crop: opened.crop, width: Int32(whole.width), height: Int32(whole.height))
                let rect = CGRect(x: Int(r.get(index: 0)), y: Int(r.get(index: 1)), width: Int(r.get(index: 2)), height: Int(r.get(index: 3)))
                next[nodeId] = (key, whole.cropping(to: rect) ?? whole)
            }
        }
        if tenantSession === t { covers = next }
    }

    // MARK: Search (ADR 0125; ADR 0159 decision 3: the platform's own search)

    /// The Search tab showed: the shell searches, the core's model is made once per account.
    func searchShown() {
        guard let m = shellModel else { return }
        if shell?.searching != true { m.search(on: true); applyShell() }
        if searchModel == nil {
            let made = m.searchModel(words: TvSearchWords(), appearance: false, trace: nil)
            searchModel = made
            found = made.current
            searchWatch = made.watch { @Sendable [weak self] shown in
                nonisolated(unsafe) let shown = shown
                Task { @MainActor [weak self] in self?.found = shown }
            }
            made.focus()
        }
    }

    func searchText(_ text: String) { searchModel?.text(text: text) }

    func openResult(_ index: Int) { searchModel?.open(index: Int32(index)) }

    func moreResults() { searchModel?.more() }

    func chooseSuggestion(_ index: Int) { searchModel?.choose(index: Int32(index)) }

    func removeChip(_ index: Int) { searchModel?.removeChip(index: Int32(index)) }

    /// Search left by the tab bar: the shell stops searching.
    func searchLeft() {
        if shell?.searching == true { shellModel?.search(on: false); applyShell() }
    }

    private func closeSearch() {
        searchWatch?.stop()
        searchWatch = nil
        searchModel?.dispose()
        searchModel = nil
        found = nil
    }

    // MARK: Music (ADR 0142: a Memory's music plays on beside every page)

    private func openMusic(_ m: ShellModel) {
        let player = m.musicPlayer(newDeck: { AVDeck.make($0) }, controls: SystemMusicControls())
        musicPlayer = player
        pageMusic = PageMusic(player: player, untitled: { @Sendable n in say(.musicUntitled, n) })
        let id = ObjectIdentifier(player)
        musicWatch = player.watch { @Sendable [weak self] s in
            nonisolated(unsafe) let s = s
            Task { @MainActor [weak self] in
                guard let self, let now = self.musicPlayer, ObjectIdentifier(now) == id else { return }
                self.nowPlaying = s.nowPlaying
                self.playing("music", s.nowPlaying?.playing == true)
            }
        }
    }

    /// The music bar's close: the queue ends.
    func stopMusic() { musicPlayer?.stop() }

    private func closeMusic() {
        musicWatch?.stop()
        musicWatch = nil
        musicPlayer?.stop()
        musicPlayer = nil
        pageMusic = nil
        nowPlaying = nil
    }

    // MARK: The idle lock (ADR 0160 decision 6)

    /// A press or a focus move: the person is there.
    func input() { idle.input() }

    /// `source` plays (`on`) or stopped — a video, the slideshow, the music: the idle lock waits while any plays.
    func playing(_ source: String, _ on: Bool) {
        if on { playingNow.insert(source) } else { playingNow.remove(source) }
        idle.playing(on: !playingNow.isEmpty)
    }

    /// The app went to the background.
    func background() { idle.background() }

    /// The app is back: the account locks (every return, owner 2026-11-25).
    func foreground() {
        if idle.foreground() { lock() }
    }

    /// Polled about once a minute: 15 minutes idle with nothing playing locks.
    func poll() {
        if gate is SessionModelGate.Signed, idle.due() { lock() }
    }

    // MARK: The TV's settings

    var signedAccount: VeyqAccount? { session.signedTo?.account }

    /// The open account's name on the picker (core's `TrustedDevice.pickerLabel`); nil where this TV does not keep it —
    /// behind a PIN, or without one (owner 2026-11-26).
    var pickerLabel: String? {
        guard let t = signedAccount?.trusted, t.method == .devicePin || t.method == .deviceKey else { return nil }
        return t.pickerLabel
    }

    /// How this TV keeps the open account: true behind a PIN, false without one, nil not at all (this run only).
    var keptWithPin: Bool? {
        switch signedAccount?.trusted?.method {
        case .devicePin: true
        case .deviceKey: false
        default: nil
        }
    }

    /// Whether this TV can keep an account behind a PIN (core's `ThisDevice.offersPin`).
    var offersPin: Bool { device.offersPin }

    /// The account kept on without its PIN (owner 2026-11-26): `old` asked first through the server's count (core's
    /// `keepWithoutPin`); a wrap gone locks the TV.
    func keepWithoutPin(old: String, done: @escaping (VeyqAccount.PinChange?) -> Void) {
        guard let account = signedAccount else { return }
        Task {
            trustWorking = true
            let change = (try? await account.keepWithoutPin(old: old)) ?? nil
            trustWorking = false
            if change?.outcome == .gone { lock() }
            done(change)
        }
    }

    /// The account, kept without a PIN, kept behind `pin` from now on (core's `trustWithPin`); `done` says whether it was.
    func setPin(_ pin: String, done: @escaping (Bool) -> Void) {
        guard let account = signedAccount else { return }
        Task {
            trustWorking = true
            let outcome = try? await account.trustWithPin(pin: pin, label: nil)
            trustWorking = false
            let kept = outcome == .remembered
            if !kept { said = say(.trustFailed) }
            done(kept)
        }
    }

    func renameOnPicker(_ label: String) { signedAccount?.renameOnPicker(label: label) }

    /// The PIN changed from `old` to `new` (ADR 0160 decision 7); a wrap gone locks the TV.
    func changePin(old: String, new: String, done: @escaping (VeyqAccount.PinChange?) -> Void) {
        guard let account = signedAccount else { return }
        Task {
            trustWorking = true
            let change = (try? await account.changePin(old: old, new: new)) ?? nil
            trustWorking = false
            if change?.outcome == .gone { lock() }
            done(change)
        }
    }

    /// *Remove from this TV*: the account signed out here and its PIN trust forgotten; the picker or the sign-in follows.
    func removeHere() {
        guard let account = signedAccount else { return }
        Task { _ = try? await session.signOut(account: account) }
    }
}

/// The top tabs' values: Home, one per application, Search, Settings.
enum TvTab {
    static let home = "home"
    static let search = "search"
    static let settings = "settings"
    static func app(_ app: ApplicationId) -> String { "app:" + app.name }
}

/// The search's words as the catalog has them (core's `SearchWords`).
final class TvSearchWords: NSObject, SearchWords, @unchecked Sendable {
    func say(msg: Msg) async throws -> String { tvCatalogWord(msg) }
}

private func tvCatalogWord(_ msg: Msg) -> String { say(msg) }
