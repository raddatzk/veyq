import SwiftUI
import VeyqCore
#if os(iOS)
import BackgroundTasks
import UIKit
#endif

/// Work the core does in this process that leaving the app must not stop: a pin's fetch (ADR 0140 decision 9,
/// `OfflineFetching`) and an upload (ADR 0145 decision 1, `UploadsUnderway`) — one instance each, each with its own
/// progress. On iOS leaving the app suspends the process, so while such work runs this asks the system for time: a
/// continued-processing task (`BGContinuedProcessingTask`, iOS 26), submitted while the app is in front when the work
/// begins — the system shows it with its progress and may run it on after the app is left —, and the time
/// `beginBackgroundTask` grants on leaving the app, where the system takes no such task (the simulator, a refusal). It does
/// none of the work and opens no account itself: a fetch the system stops continues at the next open (the store appends);
/// an upload it stops fails, as any cut-off upload does. The macOS app runs as long as it runs: nothing to ask for there.
@MainActor
final class ContinuedWork {
    /// A pin's fetch: "Making content available offline", how far.
    static let offline = ContinuedWork(context: "offline", name: "veyq offline", title: { _ in say(.offlineFetchTitle) }, percent: { say(.offlineFetchPercent, $0) })
    /// Uploads: "Uploading 3 files", how far.
    static let uploads = ContinuedWork(context: "uploads", name: "veyq uploads", title: { say(.uploadBackgroundTitle, $0) }, percent: { say(.uploadBackgroundPercent, $0) })

    /// Both, from the app's start.
    static func start() {
        offline.follow { report in OfflineFetching.shared.watch { @Sendable p in report(p.map { (Int64(0), $0.done, $0.total) }) } }
        uploads.follow { report in UploadsUnderway.shared.watch { @Sendable p in report(p.map { (Int64($0.files), $0.done, $0.total) }) } }
    }

    /// The scene's phase, told to both.
    static func phase(_ phase: ScenePhase) {
        offline.phase(phase)
        uploads.phase(phase)
    }

    private let context: String
    private let name: String
    private let title: (Int) -> String
    private let percentText: (Int) -> String
    private var watch: AnyObject?
    /// Files (where counted), bytes done of the bytes the work set out to take; nil while none runs.
    private var progress: (files: Int64, done: Int64, total: Int64)?
    private var active = true

    /// How many files the work has still to go (uploads count theirs; 0 while none runs): what a Mac's quit asks about.
    var files: Int { Int(progress?.files ?? 0) }

    private init(context: String, name: String, title: @escaping (Int) -> String, percent: @escaping (Int) -> String) {
        self.context = context
        self.name = name
        self.title = title
        self.percentText = percent
    }

    #if os(iOS)
    /// The continued-processing tasks' identifiers: the bundle id, a context, a unique suffix (`BGTaskRequest.h`); the
    /// Info.plist permits each context's wildcard.
    nonisolated static let prefix = "app.veyq"
    private var registered: Bool?
    private var continued: BGContinuedProcessingTask?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    #endif

    /// Follows the core's work through [watching], which hands a callback the core calls on every change.
    private func follow(_ watching: (@escaping @Sendable ((Int64, Int64, Int64)?) -> Void) -> AnyObject) {
        guard watch == nil else { return }
        let me = self
        watch = watching { @Sendable now in
            let p: (files: Int64, done: Int64, total: Int64)? = now.map { (files: $0.0, done: $0.1, total: $0.2) }
            Task { @MainActor in me.changed(p) }
        }
    }

    /// The scene's phase: work under way when the app is left asks for the time to continue.
    private func phase(_ phase: ScenePhase) {
        active = phase == .active
        #if os(iOS)
        if phase == .background { hold() }
        #endif
    }

    private func changed(_ now: (files: Int64, done: Int64, total: Int64)?) {
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

    private var titleNow: String { title(Int(progress?.files ?? 0)) }

    /// A continued-processing task for the work that began (the person's own act: a pin, a pick, made in front).
    private func submit() {
        let scheduler = BGTaskScheduler.shared
        let prefix = "\(Self.prefix).\(context)"
        if registered == nil {
            // registered once, on first need (continued-processing tasks are exempt from registering at launch); on the
            // main queue, so its handler runs on the main actor
            let me = self
            registered = scheduler.register(forTaskWithIdentifier: "\(prefix).*", using: .main) { task in
                MainActor.assumeIsolated { me.began(task) }
            }
            if registered == false { print("veyq: \(context) has no continued-processing task: not permitted here") }
        }
        guard registered == true, continued == nil else { return }
        let request = BGContinuedProcessingTaskRequest(identifier: "\(prefix).\(UUID().uuidString)", title: titleNow, subtitle: percentText(percent))
        // now or not at all: work queued for later is the next open's
        request.strategy = .fail
        do {
            try scheduler.submit(request)
        } catch {
            // the simulator answers `unavailable` (BGTaskScheduler.h); leaving the app then asks for background time
            print("veyq: \(context) has no continued-processing task: \(error)")
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
        let me = self
        task.expirationHandler = {
            Task { @MainActor in me.expired() }
        }
        task.progress.totalUnitCount = 100
        continued = task
        report()
    }

    private func report() {
        guard let task = continued else { return }
        task.progress.completedUnitCount = Int64(percent)
        task.updateTitle(titleNow, subtitle: percentText(percent))
    }

    /// The system ended the task: the work goes on while the process runs.
    private func expired() {
        continued?.setTaskCompleted(success: false)
        continued = nil
    }

    /// Leaving the app while work runs: the time the system grants, ended once the work ends or the time is up.
    private func hold() {
        guard progress != nil, backgroundTask == .invalid else { return }
        let me = self
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: name) {
            MainActor.assumeIsolated { me.endHold() }
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
