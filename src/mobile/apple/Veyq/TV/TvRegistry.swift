import SwiftUI
import VeyqCore

/// A kind's page on a TV as an application registers it (the phone's `KindPage`, ADR 0154 decision 3): the whole page of
/// an object of the kind, drawn for focus and the remote. What it may do is the core's plans on a TV (ADR 0159 decision 2).
struct TvKindPage {
    let body: @MainActor (ObjectRef, TvModel) -> AnyView
}

/// The TV's registry (the phone's `Registry`): each application registers its kinds' TV pages from the composition root
/// (`Applications.swift`), and the TV's shell asks it — the core UI names no application
/// (`scripts/validate_mobile_ui_direction.py`). A kind registered twice is refused, a lookup before the seal fails.
@MainActor
enum TvRegistry {
    private static var kinds: [String: TvKindPage] = [:]
    private(set) static var isSealed = false

    static func kind(_ page: TvKindPage, _ kind: String...) {
        precondition(!isSealed, "the registry is sealed")
        for k in kind { precondition(kinds.updateValue(page, forKey: k) == nil, "the kind \(k) is registered twice") }
    }

    static func seal() { isSealed = true }

    static func page(_ kind: String) -> TvKindPage? {
        precondition(isSealed, "the registry is read before the composition root registered the applications")
        return kinds[kind]
    }
}
