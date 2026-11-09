import Foundation
import VeyqCore

/// `msg` in the person's language (the String Catalog `Words`, generated from `src/mobile/words/words.json`: ADR 0127
/// decision 3), its placeholders filled from `args` — a plural's count first, as an `Int`.
func say(_ msg: Msg, _ args: CVarArg...) -> String {
    say(msg, args)
}

private func say(_ msg: Msg, _ args: [CVarArg]) -> String {
    let format = Bundle.main.localizedString(forKey: msg.key, value: nil, table: "Words")
    return args.isEmpty ? format : String(format: format, locale: .current, arguments: args)
}

/// A notice the core raised, in the person's language: its words inside it rendered too, a size as the system writes it.
func say(_ notice: Notice) -> String {
    let args: [CVarArg] = notice.args.map { a -> CVarArg in
        switch a {
        case let inner as Notice: return say(inner)
        case let size as Bytes: return ByteCountFormatter.string(fromByteCount: size.count, countStyle: .file)
        case let n as KotlinInt: return Int(truncating: n)
        case let s as String: return s
        default: return "\(a)"
        }
    }
    return say(notice.msg, args)
}
