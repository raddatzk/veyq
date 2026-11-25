import SwiftUI
import VeyqCore

/// Core's report sheet as SwiftUI observes it: its state, moved by the sheet's watch onto the main actor. Its own file so
/// the TV's report (`TvReportView`) observes it too — the TV target compiles no `AppModel`.
@MainActor @Observable
final class ReportBox {
    private(set) var sheet: VeyqCore.ReportSheet?
    private(set) var state: VeyqCore.ReportSheet.State?
    @ObservationIgnored private var watch: VeyqCore.ReportSheet.Watch?

    func start(_ sheet: VeyqCore.ReportSheet) {
        self.sheet = sheet
        state = sheet.current
        watch = sheet.watch { @Sendable _ in
            Task { @MainActor [weak self] in
                if let current = self?.sheet?.current { self?.state = current }
            }
        }
    }

    func stop() { watch?.stop() }
}
