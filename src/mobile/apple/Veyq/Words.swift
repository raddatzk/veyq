import Foundation
import VeyqCore

/// `msg` in the person's language (the String Catalog `Words`, generated from `src/mobile/words/words.json`: ADR 0127
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

/// A notice the core raised, in the person's language: its words inside it rendered too, a size as the system writes it.
func say(notice: Notice) -> String {
    let args: [CVarArg] = notice.args.map { a -> CVarArg in
        switch a {
        case let inner as Notice: return say(notice: inner)
        case let size as Bytes: return ByteCountFormatter.string(fromByteCount: size.count, countStyle: .file)
        case let moment as Moment: return Date(timeIntervalSince1970: Double(moment.epochMs) / 1000).formatted(date: .abbreviated, time: .shortened)
        case let joined as Joined: return joined.parts.map { ($0 as? Notice).map { say(notice: $0) } ?? "\($0)" }.joined(separator: joined.separator)
        default: return argument(a)
        }
    }
    return say(notice.msg, args)
}
