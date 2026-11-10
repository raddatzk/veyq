# ADR 0127 — An app's logic is the Kotlin core's; each platform draws only its own UI, Windows and Linux among them
- Status: Accepted
- Built: partly — decisions 1 to 3 for Android and Apple (the screens' logic is the core's, the words one catalog);
  decision 4 for Linux's first slice (sign-in, the shell, Home, a Pot read only, Search), run in a Fedora 44 container
  under GTK's Broadway backend, not yet in a desktop session or as a Flatpak; decision 5's bridge, which the Linux app
  uses, but not its Windows half (`mingwX64`, the C# types, WinUI). Measured 2026-11-10 (`src/mobile/linux/run.py test`,
  `bridge` and `build`, `./gradlew :bridge:jvmTest`, a browser pass): ADR 0123's Consequences, the core on Linux, the
  bridge and the Linux app
- Date: 2026-11-09 (decision 4 revised 2026-11-10: Linux in Rust over the native core, not on the JVM)
- Model: [native-apps](../crosscutting/native-apps.md)

## Context

[ADR 0123](0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md) put a Kotlin Multiplatform core under a
native UI for Android (Compose) and the Apple platforms (SwiftUI). The core holds the suite, the API, the session, the
navigation model and each application's domain; but each app's model (`android/…/AppModel.kt`, `apple/Veyq/AppModel.swift`,
some 1,500 lines each) still holds logic both write again: when the lists are read anew, which outcome raises which
notice, the sign-in, unlock and trust flow, what a page shows and what it may do. Two copies of a rule are two chances
for one platform to be wrong; the upload queue (2026-11-09) was the first piece moved into the core for that reason,
the web's split taken over.

The owner adds Windows and Linux (2026-11-09), each with its own native UI rather than a shared one — four UIs over one
core — and asks for as much as possible in the Kotlin core. With four UIs, every rule left in an app model is written
four times.

## Decision

1. **The line: everything but drawing and the device is the core's.** The core holds the suite, the API, the session,
   the navigation model, every application's domain and flows, the upload queue — and each screen's model: what it
   shows (its state), what a tap does (its actions), the rights that narrow both, what is read anew after a write, and
   which notice an outcome raises (a key with its arguments, never a sentence). A platform holds only:
   - its UI, built to its own guidelines (ADR 0123 decision 9; Fluent on Windows, the GNOME HIG on Linux);
   - what only the device can do: the system's pickers, share sheets, save dialogs and drops; the secure store
     (Android Keystore, the Keychain, Windows Hello and DPAPI, the Secret Service); decoding pictures and video for a
     medium's tiers (`MediaUpload.Deriver`); the share extension, notifications and widgets;
   - the platform's side of a file: reading a picked one at any offset (core's `UploadSource`) and writing a download.
   A rule that does not touch a screen or the device and sits in a platform is a defect to move, as a second copy of
   key-material logic is (root `CLAUDE.md`).

2. **A screen's model is a core class a UI observes.** It exposes its state as a flow (a Kotlin UI collects it) and a
   watch with a callback (Swift and the bridge of decision 5 observe it, as `UploadQueue.watch` does), and its actions as
   plain functions; it is tested in `commonTest` with no UI at all. Moving a screen keeps its behaviour: the slice that
   moves it changes no rule and is proved by the tests the rules gain, then by both apps unchanged on a device.

3. **The words have one source.** The core keeps the catalog (English and German, the keys the screen models raise
   and every label), and the build generates each platform's resources from it — Android's `strings.xml`, Apple's
   String Catalog, Windows' `.resw`, Linux's gettext catalog —, as the theme is generated from the web's colours
   (ADR 0123 decision 7). Today's two catalogs (Android's and Apple's) are merged into it as the screens move.

4. **Linux: GTK 4 and libadwaita in Rust, the core as a Kotlin/Native library** (owner 2026-11-10: no JVM in the
   app, and the way GNOME's own apps are built). The Linux app is Rust with gtk4-rs and libadwaita-rs (both checked
   2026-11-10: maintained, MIT, community-run — gtk-rs and GNOME's World group). The core joins as a `linuxX64` and a
   `linuxArm64` shared library — the suite's primitives libsodium's, built from the pinned release as for Apple (ADR
   0123 decision 3) — through the same narrow bridge as Windows (decision 5), its Rust types generated from the core's
   models. It ships as a Flatpak on the GNOME runtime. Its secure store is the Secret Service.
   **One Linux UI for every desktop** (owner 2026-11-10): KDE Plasma and the others get the same libadwaita app. The
   Flatpak brings GTK and libadwaita with the runtime, the system's dialogs come through the xdg-desktop-portals (KDE's
   own file chooser under Plasma), the colour scheme through the Settings portal, the secure store through any Secret
   Service (gnome-keyring, KWallet, KeePassXC); libadwaita does not take a desktop's theme, so under Plasma it looks
   like a GNOME app. Where no Secret Service runs, the app works without staying signed in. **It is built and tested
   against the runtime it ships on** — the GNOME SDK (`org.gnome.Sdk`) of the Flatpak's runtime version — so the
   distribution a developer or a test VM runs is never what the app is proved against.

5. **Windows: WinUI 3 in C#, the core as a Kotlin/Native library.** The Windows app is C# on the Windows App SDK. The
   core joins as a `mingwX64` DLL — the suite's primitives libsodium's, built from the pinned release as for Apple
   (ADR 0123 decision 3), the API over Ktor's WinHttp engine — held to the same vectors by the same `Conformance`. C#
   reaches it through one narrow bridge rather than Kotlin/Native's whole C export: a call with a serialized request
   and its answer, and one callback for every model's watch, both in the core's own serialization; the C# types are
   generated from the core's models, never written by hand. Its secure store is Windows Hello with DPAPI. **The bridge
   is one, for Windows and Linux alike** (decision 4): its C side, its serialization and its generator are shared, each
   UI language only adding its generated types. Kotlin/Native has no Windows ARM64 target (only `mingw_x64` in 2.4.21's
   platform libraries, measured 2026-11-10), so the Windows app is an x64 process: native on x64 PCs, under Windows'
   x64 emulation on an ARM one.

6. **The order.** The screens move into the core first (decision 2), application by application, Android and Apple
   thinned in the same slice; then the large screens (an Android tablet and window, the iPad, macOS) are run and laid
   out to their guidelines; then the bridge with Linux, which runs in a container on the developer's Mac (GTK's
   Broadway backend draws it into a browser) and in a VM; then Windows over the same bridge, which needs a Windows
   machine or VM. No new feature lands in an app model meanwhile: a feature built during the move is built in
   the core.

## Consequences

- Four UIs over one core. Each feature costs its screen four times, but its rules once; the screen models' tests carry
  what the apps' live passes carried alone so far.
- ADR 0123 decision 1's targets grow by `mingwX64`, `linuxX64` and `linuxArm64`; the JVM target serves Android alone.
  "UI is never shared" stands. The repository gains Rust, for the Linux UI alone.
- The Windows app cannot be built on the developer's Mac: its slice needs a Windows host, and the apps' CI job (still
  open) a Windows runner.
- **The bridge carries no key, so a platform behind it does not hold the device trust as decision 1 has a platform hold
  the secure store** (found building it, 2026-11-10). `DeviceKeys`' calls carry the Master Key to seal and the refresh
  token to keep; forwarded to the host, both would cross the bridge. The Linux app therefore keeps no device trust yet,
  and every start signs in. Its Secret Service is reached by the core itself — libsecret through cinterop, the key
  never leaving the library — or the bridge's rule is revisited; never a host request that carries a key. Windows'
  DPAPI meets the same choice.
- The repository's Rust (`src/mobile/linux/`) is held to the no-deprecated rule by reading: cargo has no deprecation
  gate, as the JavaScript install has.
- What is still open per platform is [native-apps](../crosscutting/native-apps.md)' to keep.

## Alternatives considered

- **Compose Multiplatform for Android, Windows and Linux.** One Compose UI for three platforms, the cheapest by far.
  Rejected by the owner (2026-11-09): each platform's own UI.
- **A desktop shell around the web client (Tauri).** Parity at once, and a client the operator does not serve (ADR
  0026). Rejected: the web's look and navigation instead of the app's (ADR 0122), and the TypeScript suite instead of
  the core.
- **Logic left in each app model.** Rejected: four copies of every rule.
- **Windows through Kotlin/Native's whole C export, or the core on a JVM beside a C# UI.** The first exposes every
  class as handles and function tables a C# hand-maintains; the second runs a second runtime and process for a UI.
  The narrow bridge (decision 5) keeps the surface to one call and one callback.
- **A second Linux UI for KDE Plasma (Qt with Kirigami, over the same bridge).** Plasma's own look and theme. Rejected
  by the owner (2026-11-10): every screen a fifth time; the libadwaita app works under Plasma through the portals and
  the Secret Service, and only its look is GNOME's.
- **Linux on the JVM, in Kotlin through java-gi** (decision 4 as first taken, 2026-11-09). No bridge, maintained
  bindings, one language, and a spike ran (2026-11-10: GTK 4.22 and libadwaita 1.9 from an ARM container, java-gi
  1.0.0-RC3 on JDK 25). Replaced by the owner (2026-11-10): a Java runtime inside every Linux install, where the
  bridge decision 5 builds anyway serves a native UI in GNOME's own way.
- **Linux on Kotlin/Native with GTK through cinterop.** No bridge either, but GObject's signals and lifetimes written
  by hand with no maintained bindings, and Kotlin's collector beside GObject's reference counts where a lifetime goes
  wrong.
