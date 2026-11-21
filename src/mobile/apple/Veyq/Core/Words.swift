import Foundation
import VeyqCore

/// `msg` in the person's language (the String Catalog `Words`, generated from `src/mobile/words/words.json`: ADR 0136
/// decision 3), its placeholders filled from `args` — a plural's count first, as an `Int`.
func say(_ msg: Msg, _ args: Any...) -> String {
    say(msg, args.map(argument))
}

/// One argument as a format takes it: a whole number as an `Int` (the catalog's `%lld`), anything else as its text.
private func argument(_ a: Any) -> CVarArg {
    switch a {
    case let n as Int: return n
    case let n as Int32: return Int(n)
    case let n as Int64: return Int(n)
    case let n as KotlinInt: return Int(truncating: n)
    case let n as KotlinLong: return Int(truncating: n)
    case let s as String: return s
    default: return "\(a)"
    }
}

private func say(_ msg: Msg, _ args: [CVarArg]) -> String {
    let format = Bundle.main.localizedString(forKey: msg.key, value: nil, table: "Words")
    // a format's `%%` is a percent sign with arguments or without
    return args.isEmpty ? format.replacingOccurrences(of: "%%", with: "%") : String(format: format, locale: .current, arguments: args)
}

/// The app's language as its catalog speaks it (`en`, `de`): what the core's `Shown` writes a value in, so a size, a
/// day or an amount reads as the web writes it beside the words around it (ADR 0154 decision 5).
let shownLanguage: String = Bundle.main.preferredLocalizations.first ?? "en"

/// A notice the core raised, in the person's language: its words inside it rendered too, every value written by the
/// core's `Shown` first.
func say(notice: Notice) -> String {
    let written = Shown.shared.args(notice: notice, language: shownLanguage)
    let args: [CVarArg] = written.args.map { a -> CVarArg in
        switch a {
        case let inner as Notice: return say(notice: inner)
        case let joined as Joined: return joined.parts.map { ($0 as? Notice).map { say(notice: $0) } ?? "\($0)" }.joined(separator: joined.separator)
        default: return argument(a)
        }
    }
    return say(written.msg, args)
}

/// A line's parts joined as every app and the web join them (core's `Shown.joined`).
func joinedLine(_ parts: [String]) -> String { Shown.shared.joined(parts: parts) }

/// A row's line of the core's pieces, each written in the person's language and joined (`Shown.piece`, `Shown.joined`).
func pieceLine(_ pieces: [Piece]) -> String { joinedLine(pieces.map { say(piece: $0) }) }
