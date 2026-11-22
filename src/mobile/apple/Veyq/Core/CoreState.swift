import SwiftUI
import VeyqCore

/// A core model's state mirrored on the main actor: read through its `current` whenever core's watch says it changed
/// (`ApplePlatform.watch`, called on core's threads). Its owner stops it when the model goes.
@MainActor
@Observable
final class CoreState<Value> {
    private(set) var value: Value
    @ObservationIgnored private let read: () -> Value
    @ObservationIgnored private var watch: ApplePlatform.Watch?

    /// `read` the model's `current`; `watch` starts core's watch of its state (`ApplePlatform.watch(flow:)`), calling what
    /// it is handed on core's threads — so the closure handed to core is `@Sendable`: written in a main-actor class or a
    /// view it would inherit that isolation and trap on libdispatch's main-queue assertion (2026-11-18).
    init(_ read: @escaping () -> Value, watch: (@escaping @Sendable () -> Void) -> ApplePlatform.Watch) {
        self.read = read
        value = read()
        self.watch = watch { @Sendable [weak self] in
            Task { @MainActor [weak self] in self?.refresh() }
        }
    }

    /// Read again now: after a call that changed the model synchronously (§ Traps: a field reads core right after its edit).
    func refresh() { value = read() }

    func stop() {
        watch?.stop()
        watch = nil
    }
}
