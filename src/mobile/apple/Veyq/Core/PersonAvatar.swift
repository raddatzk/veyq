import SwiftUI

extension EnvironmentValues {
    /// The members' pictures this device opened, by user id (core's `AvatarsModel`, published by `AppModel.avatars`).
    @Entry var avatars: [String: Data] = [:]
}

/// One person's avatar (the web's `PersonAvatar.vue`, ADR 0084): a listed member's profile picture when this device holds
/// one, else the letter of the name on the web's avatar roles (`--app-line`, `--app-control-text`) — also for a picture
/// that does not decode (never a broken image). Decorative: the name
/// is always written beside it.
struct PersonAvatar: View {
    let name: String
    let userId: String?
    var size: CGFloat = 32
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
        guard let userId, let data = avatars[userId] else { return nil }
        #if os(iOS)
        return UIImage(data: data).map(Image.init(uiImage:))
        #else
        return NSImage(data: data).map(Image.init(nsImage:))
        #endif
    }
}
