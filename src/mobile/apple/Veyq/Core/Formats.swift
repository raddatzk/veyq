import SwiftUI
import VeyqCore

// Every size, day, moment and amount the app shows is the core's text (`Shown`, ADR 0154 decision 5), written in the
// catalog's language as the web writes it; nothing here formats a value of its own.

/// Minor units of `currency` as the web writes money (core's `Shown.money`); a number alone without a currency (D8).
func money(_ minor: Int64, _ currency: String?, _ minorUnits: Int32) -> String {
    Shown.shared.money(amount: minor, currency: currency, minorUnits: minorUnits, language: shownLanguage)
}

/// An entry's `YYYY-MM-DD` as the web writes a day (`Shown.day`: "Nov 20, 2026", "20.11.2026").
func localDay(_ iso: String) -> String { Shown.shared.day(iso: iso, language: shownLanguage) }

/// A byte count as the web writes it (`Shown.size`, base 1000).
func shownSize(_ bytes: Int64) -> String { Shown.shared.size(bytes: bytes, language: shownLanguage) }

/// An instant (ms since the epoch) in the device's zone as the web writes a date and time (`Shown.moment`).
func shownMoment(_ epochMs: Int64) -> String { Shown.shared.moment(epochMs: epochMs, language: shownLanguage) }

/// A server instant (ISO 8601) as `shownMoment` writes it.
func shownMoment(_ instant: String) -> String { Shown.shared.moment(instant: instant, language: shownLanguage) }

/// The local day an instant (ms since the epoch) falls on, as the web writes a day (`Shown.dayOf`).
func shownDayOf(_ epochMs: Int64) -> String { Shown.shared.dayOf(epochMs: epochMs, language: shownLanguage) }

/// The local day a server instant (ISO 8601) falls on, as the web writes a day.
func shownDayOf(_ instant: String) -> String { Shown.shared.dayOf(instant: instant, language: shownLanguage) }

/// A track's length or the player's running clock (`Shown.length`: `m:ss`, `h:mm:ss`, to the nearest second).
func shownLength(_ ms: Int64) -> String { Shown.shared.length(ms: ms) }

/// One piece of a row's line (core's `Piece`), its value written by the core (`Shown.piece`).
func say(piece: Piece) -> String {
    switch Shown.shared.piece(p: piece, language: shownLanguage) {
    case let p as Piece.Said: say(notice: p.notice)
    case let p as Piece.Plain: p.text
    default: ""
    }
}

/// The reader's decimal separator as a UTF-16 unit (a Kotlin `Char`), as amounts are typed over.
var decimalSeparator: unichar { (Locale.current.decimalSeparator ?? ".").utf16.first ?? 46 }

/// A `YYYY-MM-DD` as a date picker takes it — the device's midnight of that day — and back; never shown as text.
enum IsoDay {
    /// The Gregorian calendar in the device's zone, whatever calendar the person reads in: an ISO day is a Gregorian one.
    private static let gregorian = Calendar(identifier: .gregorian)

    static func date(_ iso: String?) -> Date? {
        let parts = (iso ?? "").split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return gregorian.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }

    static func iso(_ date: Date) -> String {
        let c = gregorian.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }
}

/// Waits the `ms` the core named — a notice's life (`NoticeKind.lifeMs`), "copied" (`Notice.COPIED_MS`), "nothing plays"
/// (`Notice.NOTHING_PLAYS_MS`) —; throws once the calling task is cancelled. The app holds no duration of its own (ADR 0154
/// decision 4).
func waitCore(_ ms: Int64) async throws { try await Task.sleep(for: Duration.milliseconds(ms)) }

/// Suspends until the calling task is cancelled (a live poll held for as long as its view's task runs), with no timer.
func untilCancelled() async {
    let (stream, continuation) = AsyncStream<Never>.makeStream()
    // the continuation is held until the end, so only the task's cancellation ends the stream
    defer { continuation.finish() }
    for await _ in stream {}
}
