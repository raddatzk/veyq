import SwiftUI
import VeyqCore
#if os(iOS)
import BackgroundTasks
import UIKit
#endif

/// A pin's fetch outside the app (ADR 0140 decision 9). The core fetches in this process while the account is open
/// (`OfflineFetching`); on iOS leaving the app suspends the process, so while a fetch runs this asks the system for time:
/// a continued-processing task (`BGContinuedProcessingTask`, iOS 26), submitted while the app is in front when a fetch
/// begins — the system shows it with its progress and may run it on after the app is left —, and the time
/// `beginBackgroundTask` grants on leaving the app, where the system takes no such task (the simulator, a refusal). It
/// fetches nothing and opens no account itself; a fetch the system stops continues at the next open (the store appends).
/// The macOS app runs as long as it runs: nothing to ask for there.
@MainActor
final class OfflineBackground {
    static let shared = OfflineBackground()

    private var watch: OfflineFetching.Watch?
    /// Bytes fetched of the bytes the fetches set out to take; nil while none runs.
    private var progress: (done: Int64, total: Int64)?
    private var active = true

    #if os(iOS)
    /// The continued-processing tasks' identifiers: the bundle id, a context, a unique suffix (`BGTaskRequest.h`); the
    /// Info.plist permits the wildcard.
    nonisolated static let prefix = "app.veyq.offline"
    private var registered: Bool?
    private var continued: BGContinuedProcessingTask?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    #endif

    /// Follows the core's fetches, from the app's start.
    func start() {
        guard watch == nil else { return }
        watch = OfflineFetching.shared.watch { @Sendable p in
            let now: (done: Int64, total: Int64)? = p.map { ($0.done, $0.total) }
            Task { @MainActor in OfflineBackground.shared.changed(now) }
        }
    }

    /// The scene's phase: a fetch under way when the app is left asks for the time to continue.
    func phase(_ phase: ScenePhase) {
        active = phase == .active
        #if os(iOS)
        if phase == .background { hold() }
        #endif
    }

    private func changed(_ now: (done: Int64, total: Int64)?) {
        let began = progress == nil && now != nil
        progress = now
        #if os(iOS)
        guard now != nil else {
            finish()
            return
        }
        if began && active { submit() }
        report()
        #endif
    }

    #if os(iOS)
    private var percent: Int {
        guard let p = progress, p.total > 0 else { return 0 }
        return Int(min(100, max(0, p.done * 100 / p.total)))
    }

    /// A continued-processing task for the fetch that began (the person's own act: a pin, made in front).
    private func submit() {
        let scheduler = BGTaskScheduler.shared
        if registered == nil {
            // registered once, on first need (continued-processing tasks are exempt from registering at launch); on the
            // main queue, so its handler runs on the main actor
            registered = scheduler.register(forTaskWithIdentifier: "\(Self.prefix).*", using: .main) { task in
                MainActor.assumeIsolated { OfflineBackground.shared.began(task) }
            }
            if registered == false { print("veyq: the pin's fetch has no continued-processing task: not permitted here") }
        }
        guard registered == true, continued == nil else { return }
        let request = BGContinuedProcessingTaskRequest(
            identifier: "\(Self.prefix).\(UUID().uuidString)", title: say(.offlineFetchTitle), subtitle: say(.offlineFetchPercent, percent),
        )
        // now or not at all: a fetch queued for later is the next open's
        request.strategy = .fail
        do {
            try scheduler.submit(request)
        } catch {
            // the simulator answers `unavailable` (BGTaskScheduler.h); leaving the app then asks for background time
            print("veyq: the pin's fetch has no continued-processing task: \(error)")
        }
    }

    private func began(_ task: BGTask) {
        guard let task = task as? BGContinuedProcessingTask else {
            task.setTaskCompleted(success: false)
            return
        }
        guard progress != nil else {
            task.setTaskCompleted(success: true)
            return
        }
        task.expirationHandler = {
            Task { @MainActor in OfflineBackground.shared.expired() }
        }
        task.progress.totalUnitCount = 100
        continued = task
        report()
    }

    private func report() {
        guard let task = continued else { return }
        task.progress.completedUnitCount = Int64(percent)
        task.updateTitle(say(.offlineFetchTitle), subtitle: say(.offlineFetchPercent, percent))
    }

    /// The system ended the task: the fetch goes on while the process runs, and continues at the next open if not.
    private func expired() {
        continued?.setTaskCompleted(success: false)
        continued = nil
    }

    /// Leaving the app while a fetch runs: the time the system grants, ended once the fetch ends or the time is up.
    private func hold() {
        guard progress != nil, backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "veyq offline") {
            MainActor.assumeIsolated { OfflineBackground.shared.endHold() }
        }
    }

    private func endHold() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    private func finish() {
        continued?.progress.completedUnitCount = 100
        continued?.setTaskCompleted(success: true)
        continued = nil
        endHold()
    }
    #endif
}
