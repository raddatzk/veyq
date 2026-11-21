import SwiftUI
import VeyqCore

/// Minor units of `currency` as the person's locale writes money.
func money(_ minor: Int64, _ currency: String?, _ minorUnits: Int32) -> String {
    let value = Decimal(minor) / pow(Decimal(10), Int(minorUnits))
    // D8: the core hands every amount with its currency; none is never a guessed one, but a number alone (the desktops' rule)
    guard let currency else { return value.formatted(.number.precision(.fractionLength(Int(minorUnits)))) }
    return value.formatted(.currency(code: currency).precision(.fractionLength(Int(minorUnits))))
}

/// An entry's `YYYY-MM-DD` as the person's locale writes a day.
func localDay(_ iso: String) -> String {
    let parse = DateFormatter()
    parse.locale = Locale(identifier: "en_US_POSIX")
    parse.dateFormat = "yyyy-MM-dd"
    guard let date = parse.date(from: iso) else { return iso }
    return date.formatted(date: .abbreviated, time: .omitted)
}

/// One piece of a row's line (core's `Piece`), written Apple's way.
func say(piece: Piece) -> String {
    switch piece {
    case let p as Piece.Said: say(notice: p.notice)
    case let p as Piece.Plain: p.text
    case let p as Piece.Day: localDay(p.iso)
    case let p as Piece.Size: ByteCountFormatter.string(fromByteCount: p.bytes, countStyle: .file)
    case let p as Piece.Money: money(p.amount, p.currency, p.minorUnits)
    default: ""
    }
}

/// The reader's decimal separator as a UTF-16 unit (a Kotlin `Char`), as amounts are typed over.
var decimalSeparator: unichar { (Locale.current.decimalSeparator ?? ".").utf16.first ?? 46 }

/// How a Memory's date reads: a day, a range, "Since …", as the device writes dates; nil without one.
enum MemoryDateText {
    private static let iso: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    static func date(_ day: String?) -> Date? { day.flatMap { iso.date(from: $0) } }
    static func day(_ date: Date) -> String { iso.string(from: date) }

    static func text(_ d: MemoryDate) -> String? {
        guard let from = date(d.from) else { return nil }
        if d.mode == .ongoing { return say(.memoryDateSince, from.formatted(date: .abbreviated, time: .omitted)) }
        guard let to = date(d.to), to > from else { return from.formatted(date: .abbreviated, time: .omitted) }
        let f = DateIntervalFormatter()
        f.dateStyle = .medium
        f.timeStyle = .none
        return f.string(from: from, to: to)
    }

    /// A Memories row's second line: its range, or nothing.
    static func row(_ d: MemoryDate) -> String? { text(d) }
}
