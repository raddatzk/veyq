#if os(iOS)
import BackgroundTasks
import VeyqCore

/// *Sync in the background* on iOS (ADR 0153 decision 8): one app-refresh task
/// (`BGAppRefreshTaskRequest`, `app.veyq.background-sync`, permitted in the Info.plist), requested while the core says a
/// run is wanted (`ThisDevice.backgroundWanted`: an account at the login level with its switch on) and cancelled when not;
/// requested again at each run and each app start. Its handler runs core's `BackgroundRun` through `AppleBackground` within
/// the time the system grants; the system's expiration cancels it, and the task is completed once the run closed its
/// account. The system decides whether and when it runs: rarely, and never while the iPhone is locked — the login level's
/// key is readable only while unlocked, and a run that finds it closed ends having sent nothing. Nothing is decided here;
/// a Mac runs none (the core gives it no switch).
@MainActor
enum BackgroundSync {
    static let identifier = "app.veyq.background-sync"
    /// The earliest a request may run after it is made: Android's period (WorkManager's floor); the system runs it later.
    private static let period: TimeInterval = 15 * 60
    /// The run's time: an app refresh is granted about 30 seconds; the run ends before, so its close — the keys zeroed — is
    /// never the part cut off.
    private static let runTime: TimeInterval = 25

    private static var device: ThisDevice?
    private static var watch: AppleMemories.Watch?
    /// The run under way and the task it answers: held here, never captured by core's callback (neither is Sendable).
    private static var running: (run: AppleBackground.Run, task: BGTask)?

    /// From the app's launch (a task must be registered before it ends): the handler, then the request kept to what the
    /// core says.
    static func start(_ device: ThisDevice) {
        guard watch == nil else { return }
        self.device = device
        // on the main queue, so the handler runs on the main actor
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { task in
            MainActor.assumeIsolated { run(task) }
        }
        if !registered { print("veyq: the background sync has no app-refresh task: not permitted here") }
        watch = AppleMemories.shared.watch(flow: device.backgroundWanted) { @Sendable wanted in
            let on = (wanted as? KotlinBoolean)?.boolValue == true
            Task { @MainActor in schedule(on) }
        }
    }

    /// The request made anew (replacing one there) while a run is [wanted], or cancelled.
    private static func schedule(_ wanted: Bool) {
        guard wanted else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
            return
        }
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: period)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // the simulator answers `unavailable`; the person turned the app's background refresh off: no run
            print("veyq: the background sync was not scheduled: \(error)")
        }
    }

    private static func run(_ task: BGTask) {
        guard let device, running == nil else {
            task.setTaskCompleted(success: false)
            return
        }
        // the next one first: a run the system cuts off still leaves one requested
        schedule(!device.backgroundAccounts().isEmpty)
        let started = Date()
        let deadline = Int64((started.timeIntervalSince1970 + runTime) * 1000)
        let run = AppleBackground.shared.start(device: device, deadline: deadline) { @Sendable summary in
            Task { @MainActor in finished(summary, started) }
        }
        running = (run, task)
        task.expirationHandler = {
            Task { @MainActor in running?.run.cancel() }
        }
    }

    private static func finished(_ summary: String?, _ started: Date) {
        guard let task = running?.task else { return }
        running = nil
        // the outcome per account and the count, never a key, a name or an address
        print("veyq: background run: \(summary ?? "stopped"), \(Int(Date().timeIntervalSince(started) * 1000)) ms")
        task.setTaskCompleted(success: summary != nil)
    }
}
#endif
