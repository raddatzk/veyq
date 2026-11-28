import SwiftUI
import VeyqCore

/// This install's appearance and language (ADR 0162; the web's Persönlich → Darstellung und Sprache).
///
/// **The appearance** is chosen here, every entry of core's `ThemeChoice` in its order, and kept in the shared defaults
/// (`Shared.defaults`, `veyq.appearance`: the entry's `id`, the web's stored value). It applies at once: on iOS and iPadOS
/// as every window's `overrideUserInterfaceStyle` (SwiftUI's `preferredColorScheme(nil)` does not always hand a window
/// back to the system's), on the Mac as `NSApp.appearance`, which every window, sheet and alert of the app reads. Apple's HIG advises against an appearance of an app's own; the
/// owner's parity mandate (2026-11-26: every app does what the web does) decides, and *System* stays the first entry and
/// the default, so nobody who never chooses meets it (ADR 0162 decision 3).
///
/// **The language** is the system's per-app language, which Apple keeps and offers in its own settings (iOS's Settings →
/// veyq → Language, the Mac's System Settings → General → Language & Region → Applications): the section opens it, as
/// Apple's guidance asks, and keeps no language of its own. Changing it relaunches the app in it.
enum Appearance {
    static let key = "veyq.appearance"

    static func choice(_ id: String) -> ThemeChoice { ThemeChoice.companion.of(id: id) }

    #if os(macOS)
    /// The app's appearance for the kept [id], every window's: none follows the system's.
    static func apply(_ id: String) {
        NSApp.appearance = switch choice(id).entry {
        case .system: nil
        case .light: NSAppearance(named: .aqua)
        case .dark: NSAppearance(named: .darkAqua)
        }
    }
    #else
    /// Every window's style for the kept [id]: unspecified follows the system's. The app's alone: the share extension
    /// reaches no `UIApplication` and draws in the system's appearance.
    @available(iOSApplicationExtension, unavailable)
    static func apply(_ id: String) {
        let style: UIUserInterfaceStyle = switch choice(id).entry {
        case .system: .unspecified
        case .light: .light
        case .dark: .dark
        }
        for scene in UIApplication.shared.connectedScenes {
            for window in (scene as? UIWindowScene)?.windows ?? [] { window.overrideUserInterfaceStyle = style }
        }
    }
    #endif

    /// Where the system keeps this app's language: iOS's page for the app in Settings, the Mac's Language & Region.
    static var languageSettings: URL? {
        #if os(iOS)
        URL(string: UIApplication.openSettingsURLString)
        #else
        URL(string: "x-apple.systempreferences:com.apple.Localization-Settings.extension")
        #endif
    }
}

/// Settings' *Appearance and language* section: the appearance's picker, and the way to the system's language setting.
struct AppearanceSection: View {
    @AppStorage(Appearance.key, store: Shared.defaults) private var theme = ThemeChoice.system.id
    @Environment(\.openURL) private var openURL

    var body: some View {
        Section {
            Picker(say(.appearanceTheme), selection: $theme) {
                ForEach(ThemeChoice.entries, id: \.id) { t in Text(say(t.word)).tag(t.id) }
            }
            .accessibilityIdentifier("settings-theme")
            LabeledContent(say(.appearanceLanguage)) {
                Button {
                    if let url = Appearance.languageSettings { openURL(url) }
                } label: {
                    #if os(iOS)
                    Text(say(.appearanceLanguageOpen))
                    #else
                    Text(say(.appearanceLanguageOpenMac))
                    #endif
                }
                .accessibilityIdentifier("settings-language")
            }
        } header: {
            Text(say(.accountSettingsAppearance))
        } footer: {
            Text(say(.appearanceLanguageElsewhere))
        }
    }
}

extension View {
    /// The kept appearance (ADR 0162), applied as this scene shows and whenever it changes: a new iPad window included.
    @available(iOSApplicationExtension, unavailable)
    func keptAppearance(_ id: String) -> some View {
        onChange(of: id, initial: true) { _, now in Appearance.apply(now) }
            .onAppear { Appearance.apply(id) }
    }
}
