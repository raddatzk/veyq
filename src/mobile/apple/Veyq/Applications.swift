import SwiftUI
import VeyqCore

/// The composition root (ADR 0154 decision 3; the web's `src/web/src/applications.ts`): the one file of the app that names
/// the applications' UIs. Core's own kinds and each application register into the core UI's `Registry` here, once, before
/// anything is drawn (the app's `VeyqApp.init`, the share extension's `viewDidLoad`); on a Mac it hands the app the menu
/// commands and the windows an application adds. `scripts/validate_mobile_ui_direction.py` holds every other file of the
/// core UI from naming an application, and an application from naming another.
@MainActor
enum Applications {
    static func register() {
        if Registry.isSealed { return }
        KindPages.register(Registry.self)
        MemoriesUi.register(Registry.self)
        DepotUi.register(Registry.self)
        ExpensesUi.register(Registry.self)
        Registry.seal()
    }

    #if os(macOS)
    /// The menu commands the applications add (Memories' "Open in New Window" for its lightbox).
    static var commands: some Commands { MemoriesUi.commands }

    /// The windows the applications open beside the main one (a Memory's lightbox in a window of its own).
    static func windows(_ model: AppModel) -> some Scene { MemoriesUi.windows(model) }
    #endif
}
