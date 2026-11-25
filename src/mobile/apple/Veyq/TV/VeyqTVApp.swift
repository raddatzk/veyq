import AVFoundation
import SwiftUI
import UIKit
import VeyqCore

/// The veyq app on Apple TV (ADR 0159): the TV's form of the one app, its own root over the same core. The scene feeds the
/// idle lock (ADR 0160 decision 6): every return from the background locks, a focus move counts as input, and the lock's
/// clock is asked once a minute.
@main
struct VeyqTVApp: App {
    @State private var model: TvModel
    @Environment(\.scenePhase) private var phase

    init() {
        Applications.register()
        // a Memory's music and a video play as media do on a TV (the session's category; tvOS sets none for the app)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        _model = State(initialValue: TvModel())
    }

    var body: some Scene {
        WindowGroup {
            TvRoot(model: model)
                .task { model.start() }
                .task {
                    while !Task.isCancelled {
                        // a fifteenth of the core's idle time, a minute: the rule is minutes, not seconds (`IdleLock.due`)
                        try? await waitCore(IdleLock.companion.IDLE_MS / 15)
                        model.poll()
                    }
                }
                .onReceive(NotificationCenter.default.publisher(for: UIFocusSystem.didUpdateNotification)) { _ in model.input() }
                .onChange(of: phase) { _, now in
                    switch now {
                    case .background: model.background()
                    case .active: model.foreground()
                    default: break
                    }
                }
        }
    }
}
