import SwiftUI
import VeyqCore

extension EnvironmentValues {
    /// The members' pictures this device opened, by user id (core's `AvatarsModel`, published by `AppModel.avatars`).
    @Entry var avatars: [String: Data] = [:]
}

/// One person's avatar (the web's `PersonAvatar.vue`, ADR 0084): a listed member's profile picture when this device holds
/// one, else the letter of the name on the web's avatar roles (`--app-line`, `--app-control-text`) — also for a picture
/// that does not decode (never a broken image). Decorative: the name
/// is always written beside it. A picture held as bytes — a space's or a group's icon, the join's chosen picture — is
/// drawn from `data` the same way, its letter the same where there is none.
struct PersonAvatar: View {
    let name: String
    let userId: String?
    var size: CGFloat = 32
    /// Encoded picture bytes drawn instead of the listed member's (a scope's icon, the join's picture).
    var data: Data? = nil
    @Environment(\.avatars) private var avatars

    var body: some View {
        Group {
            if let image = picture {
                image.resizable().scaledToFill()
            } else {
                Text(letter)
                    .font(.system(size: size * 0.5))
                    .foregroundStyle(Role.controlText)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Role.line)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }

    private var letter: String {
        let first = String(name.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased()
        return first.isEmpty ? "?" : first
    }

    private var picture: Image? {
        guard let data = self.data ?? userId.flatMap({ avatars[$0] }) else { return nil }
        #if os(iOS)
        return UIImage(data: data).map(Image.init(uiImage:))
        #else
        return NSImage(data: data).map(Image.init(nsImage:))
        #endif
    }
}

extension Data {
    /// A Kotlin `ByteArray` the core hands over (an icon's or a chosen picture's encoded bytes, a few KiB) copied into Swift.
    init(kotlin bytes: KotlinByteArray) {
        let n = Int(bytes.size)
        var out = Data(count: n)
        out.withUnsafeMutableBytes { raw in
            let p = raw.bindMemory(to: Int8.self)
            for i in 0..<n { p[i] = bytes.get(index: Int32(i)) }
        }
        self = out
    }
}
