#if DEBUG && os(macOS)
import AppKit
import OSLog

/// A debug build's trace of each click on a Mac (inbox #1029, #1030): the view the window hit-tests at the click and the
/// first responder before it and a moment after, by their class names alone — no text, no value. A field that does not
/// take a click shows here as a hit on something above it (a hit that is not the field) or as a field hit whose focus did
/// not move. Read with `log stream --level debug --predicate 'subsystem == "app.veyq" AND category == "focus"'`.
///
/// Why it exists: replicas of the gallery's filter bar and of the account sheets' grouped forms took every synthesized
/// click on every field (2026-11-23, in-process, `NSWindow.sendEvent`), so the cause lies in what only the running app
/// has; this names it at the next click pass instead of a guess.
@MainActor
enum FocusTrace {
    private static let log = Logger(subsystem: "app.veyq", category: "focus")
    private static var monitor: Any?

    static func start() {
        guard monitor == nil else { return }
        monitor = NSEvent.addLocalMonitorForEvents(matching: .leftMouseDown) { event in
            MainActor.assumeIsolated { trace(event) }
            return event
        }
    }

    private static func trace(_ event: NSEvent) {
        guard let window = event.window, let frame = window.contentView?.superview ?? window.contentView else { return }
        let hit = described(frame.hitTest(event.locationInWindow))
        let before = described(window.firstResponder)
        let place = "\(type(of: window))"
        // the field takes first responder as its mouse-down is handled, which may track until the mouse is up
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            MainActor.assumeIsolated {
                let after = described(window.firstResponder)
                log.debug("click in \(place, privacy: .public): hit \(hit, privacy: .public); first responder \(before, privacy: .public) -> \(after, privacy: .public)")
            }
        }
    }

    /// A responder by its class; the field a field editor edits by the field's.
    private static func described(_ responder: NSResponder?) -> String {
        guard let responder else { return "none" }
        if let editor = responder as? NSTextView, editor.isFieldEditor, let field = editor.delegate as? NSTextField {
            return "editing \(type(of: field))"
        }
        return "\(type(of: responder))"
    }
}
#endif
