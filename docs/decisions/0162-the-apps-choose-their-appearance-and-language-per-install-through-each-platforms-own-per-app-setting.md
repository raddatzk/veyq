# ADR 0162 — The apps choose their appearance and language per install, through each platform's own per-app setting

- Status: Accepted (the coordinator's technical decisions under the owner's mandate of 2026-11-26: every app does what
  the web does)
- Built: yes — the core's plan, the bridge, Android, Apple (iOS, macOS), Linux and Windows (cr-apps-appearance-and-language,
  2026-11-28); the passes left are § Consequences'
- Date: 2026-11-28
- Model: [native-apps](../crosscutting/native-apps.md)

## Context

The web's Settings → *Persönlich* → *Darstellung und Sprache* ([ADR 0022](0022-settings-information-architecture.md) §5) offers the
appearance — System, Light, Dark — and the language — System, English, Deutsch —, kept in the browser's `localStorage`
(`veyq:theme`, `veyq:locale`), never synced and never sent, applied at once. The apps followed the system's theme and
language: the phones' section only opened the system's page for the app, the desktops drew nothing (native-apps.md
listed it as a gap). The owner's mandate (2026-11-26) makes it one: every app does what the web does, its look the
platform's ([ADR 0123](0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md) decision 9).

What the platforms give (remembered from their documentation, 2026-11-28): Android keeps an app's night mode
(`UiModeManager.setApplicationNightMode`, API 31) and its languages (`LocaleManager`, API 33, with the app's
`localeConfig`) itself and recreates the activity in them; Apple keeps a per-app language in the system's settings and
offers no API to set one, and its HIG advises against an appearance of an app's own; GNOME has no per-app language, nor
has an unpackaged WinUI app one it can rely on.

## Decision

1. **The choice is a plan of the core's.** `session/Appearance.kt`'s `ThemeChoice` (SYSTEM, LIGHT, DARK) and
   `LanguageChoice` (SYSTEM, EN, DE) are `@Plan` enums ([ADR 0154](0154-every-app-draws-the-cores-plans-through-a-core-ui-its-applications-plug-into-as-the-webs-do.md)
   decision 2): every app draws every entry in its order, each language named in its own tongue. Their ids are the web's
   stored values and their order the web's panel's (`AppearancePanel.vue`, `LOCALE_PREFERENCES`), held by
   `AppearanceIsTheWebsTest`. The Settings section `APPEARANCE` is offered on every app (its gate is gone); the bridge
   hands a desktop both lists as `SettingsShown.themes` / `languages` (`SettingsChoice`: the id and its word).
2. **Per install, on the device, never sent** — as the web's is per browser. The core keeps nothing of it: a language is
   read before the core starts. Where the platform keeps the setting itself, nothing else is kept.
3. **Each platform through its own mechanism:**
   - **Android:** the appearance through `setApplicationNightMode` (every version the app runs on), mirrored in
     `shared_prefs/veyq.appearance.xml` (the system has no getter) and handed back at each start; the language through
     `LocaleManager` (Android 13+), which the system's settings page for the app shows too. Both apply at once. Android
     12 has no per-app language, so its row is not drawn there. Material's segmented buttons.
   - **Apple:** the appearance chosen in the app (a `Picker`; *System* first and the default, the owner's mandate
     weighed over the HIG's advice), kept in the shared defaults (`veyq.appearance`) and applied at once — every window's
     `overrideUserInterfaceStyle` on iOS and iPadOS, `NSApp.appearance` on the Mac. The language is the system's per-app
     setting: the section opens iOS's Settings page for veyq, or the Mac's Language & Region, and keeps none of its own.
   - **Linux:** the app's own, `$XDG_DATA_HOME/veyq/appearance.ini` (a key file). The appearance applies at once
     (`AdwStyleManager`); the language at the next start — put first in `LANGUAGE` before gettext reads a word and
     before the bridge starts in `words::language()`. Two `AdwComboRow`s.
   - **Windows:** the app's own, `appearance.json` in the data folder, read once at the start: the language opens MRT's
     catalog in its culture, the appearance sets the application theme. Both apply at the next start: the application
     theme is settable only before the first window, and brushes a screen looks up in code are its. Two cards with a
     `ComboBox`, as Windows' Settings draws a choice. A debug build's `VEYQ_THEME` / `VEYQ_LANGUAGE` still win.
   A desktop's row says when its choice takes effect (`appearance_next_start`); no app restarts itself, since a restart
   ends the unlocked session and any upload under way.
4. **The TVs follow the system.** The choice is the account page's, which a TV does not draw
   ([ADR 0159](0159-the-veyq-app-runs-on-apple-tv-and-android-tv-as-a-viewing-form-of-the-one-app.md) decision 4).

## Consequences

- The words: `appearance_*` and the section's sub, twins of the web's (`words/web.json`). The web's choice and an app's
  are independent — two installs, two choices, as two browsers are.
- What an app writes for it is named in `src/mobile/CLAUDE.md` § Nothing on disk the app did not choose: two ids, no
  content.
- A Linux session under the C locale keeps English whatever is chosen (gettext ignores `LANGUAGE` there); a Flatpak needs
  the runtime's German locale for GTK's own words (`extra-languages`), as before. WinUI's own chrome speaks the system's
  language whatever the choice, as before ([src/mobile/CLAUDE.md](../../src/mobile/CLAUDE.md) § Each platform's guidelines).
- Linux keeps the file in the data directory beside the device store rather than the config directory: the development
  run's home volume keeps only `~/.local/share` (`run.py run --home`), and the Flatpak keeps both alike.
- Windows' title bar stays the system's under a chosen appearance (AppWindow draws it; seen on the VM 2026-11-28), inbox
  #1430.
- On Apple the language changes by the system relaunching the app, and the share extension draws in the system's
  appearance (it reaches no `UIApplication`).
- Proven by `AppearanceTest` (core, Android), `AppearanceIsTheWebsTest`, `DispatchTest`'s Settings case, the Linux
  `appearance` tests and `AppearanceKeptTest` (Windows), each bitten; passed on Android, Linux and Windows over each app's
  Settings harness (a choice taking, and surviving a restart); what is owed is [native-apps](../crosscutting/native-apps.md)
  § Open and inbox #1430.
- End-user view (docs site): [Appearance and language](../../site/src/content/docs/account/appearance-and-language.mdx).

## Decision log (cr-apps-appearance-and-language, 2026-11-28)

- **D1 — one core plan, no gate.** The choice's entries are core's `@Plan` enums `ThemeChoice` and `LanguageChoice`
  (ids = the web's stored values, order = the web's panel), held to the web by `AppearanceIsTheWebsTest`. Since every
  app now offers the section, `SettingsSection.Gates.appearance` is removed rather than set true everywhere.
- **D2 — the platform's own mechanism wherever one exists.** Android: `setApplicationNightMode` (API 31+, every version
  the app runs on) and `LocaleManager` (API 33+), both kept by the system, the activity recreated at once; Android's own
  settings page for the app shows the same language. The theme is mirrored in `shared_prefs/veyq.appearance.xml`
  (the system has no getter for an app's night mode) and handed back at every start so the two never part.
- **D3 — Apple: the language is the system's per-app setting; the appearance is chosen in the app.** Apple offers no
  in-app language API (only the undocumented `AppleLanguages` default) and its guidance sends people to Settings; the
  section opens iOS's page for veyq, or the Mac's Language & Region (whose Applications list sets it). The HIG advises
  against an app-specific appearance; the owner's parity mandate wins, *System* first and the default. Applied through
  `overrideUserInterfaceStyle` on every window (iOS; `preferredColorScheme(nil)` is unreliable at handing back) and
  `NSApp.appearance` (Mac), kept in `Shared.defaults`.
- **D4 — Android 12/12L show no language row.** No per-app locale exists below API 33 and AppCompat's backport needs
  `AppCompatActivity`; emulating one (wrapped contexts) would be a second mechanism the system's settings do not know.
  The appearance row is drawn on every version.
- **D5 — the desktops keep their own, and it applies at the next start.** GNOME and an unpackaged WinUI app have no
  per-app language; each keeps the ids itself (Linux `$XDG_DATA_HOME/veyq/appearance.ini`, Windows `appearance.json`
  in the data folder) and reads them before any word: Linux puts the language first in `LANGUAGE` before gettext and
  the bridge start (`Config.language` follows), Windows opens MRT's catalog in its culture (`MrtWords.Open`). The words
  are read once into widgets and the bridge's language is fixed at its start, so the language takes effect on the next
  start, which its row says (`appearance_next_start`). Linux's appearance applies at once (`AdwStyleManager`); Windows'
  only at the next start too — `Application.RequestedTheme` is settable only before the first window, and a brush a
  screen looks up in code is the application theme's (measured earlier, App.xaml.cs), so a window-only switch would draw
  light brushes on dark. No "Restart now": a restart ends the unlocked session and any upload, which the person should
  choose to do.
- **D6 — the TVs follow the system.** The choice lives on the account's Settings page, which a TV does not draw (ADR 0159
  decision 4: a TV's settings are its own few). Android TV shares the APK, so a per-app setting made on that TV through
  the system would apply; tvOS has no per-app language. Nothing is added to `TvSettings`.
- **D7 — PlansAreTheWebsTest's stale expectations** (ABOUT added by 699435b6b without the test) were fixed here: red on
  this branch before any change of this CR, found by its first run.

- **D8 — Linux keeps the file in `$XDG_DATA_HOME`, not `$XDG_CONFIG_HOME`.** The development run's home volume keeps
  only `~/.local/share` (found by the first Broadway restart, which lost the choice); the device store lives there too.
