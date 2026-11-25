import SwiftUI
import VeyqCore

/// The composition root (ADR 0154 decision 3; the web's `src/web/src/applications.ts`): the one file of the app that names
/// the applications' UIs. Core's own kinds and each application register into the core UI's `Registry` here, once, before
/// anything is drawn (the app's `VeyqApp.init`, the share extension's `viewDidLoad`); on a Mac it hands the app the menu
/// commands and the windows an application adds. `scripts/validate_mobile_ui_direction.py` holds every other file of the
/// core UI from naming an application, and an application from naming another.
///
/// The TV's form (ADR 0159 decision 1, the target VeyqTV) composes its own pages here too: the one place an `os(tvOS)`
/// branch stands outside `TV/`, because the guard admits one composition root and this file is it.
@MainActor
enum Applications {
    #if os(tvOS)
    /// Each application's TV pages into the TV's registry (`TV/TvRegistry.swift`), before anything is drawn.
    static func register() {
        if TvRegistry.isSealed { return }
        MemoriesTv.register(TvRegistry.self)
        DepotTv.register(TvRegistry.self)
        ExpensesTv.register(TvRegistry.self)
        TvRegistry.seal()
    }
    #else
    static func register() {
        if Registry.isSealed { return }
        KindPages.register(Registry.self)
        MemoriesUi.register(Registry.self)
        DepotUi.register(Registry.self)
        ExpensesUi.register(Registry.self)
        Registry.seal()
    }
    #endif

    #if os(macOS)
    /// The menu commands the applications add (Memories' "Open in New Window" for its lightbox).
    static var commands: some Commands { MemoriesUi.commands }

    /// The windows the applications open beside the main one (a Memory's lightbox in a window of its own).
    static func windows(_ model: AppModel) -> some Scene { MemoriesUi.windows(model) }
    #endif
}
