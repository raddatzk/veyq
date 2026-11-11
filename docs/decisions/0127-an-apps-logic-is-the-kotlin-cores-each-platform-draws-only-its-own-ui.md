# ADR 0127 — An app's logic is the Kotlin core's; each platform draws only its own UI, Windows and Linux among them
- Status: Accepted
- Built: partly — decisions 1 to 3 for Android and Apple (the screens' logic is the core's, the words one catalog);
  decision 4 for Linux's first slice (sign-in, the shell, Home, a Pot read only, Search), run in a Fedora 44 container
  under GTK's Broadway backend, not yet in a desktop session or as a Flatpak; decision 5 for Windows' first slice, the
  same one: the core as a `mingwX64` DLL behind the same bridge, its C# types generated, a WinUI 3 app run unpackaged on
  an ARM64 Windows 11 VM under x64 emulation, not yet on an x64 PC or as an MSIX; on both, the device trust with the
  core reaching the secure store itself (2026-11-11), and a node's header with every action on it, its sheets' models
  moved into the core first (2026-11-12; the Consequences). Measured 2026-11-10 to 2026-11-12
  (`src/mobile/linux/run.py` and `src/mobile/windows/run.py`, each `test`, `bridge` and `build`, `./gradlew
  :bridge:jvmTest`, a browser pass on Linux, a UI Automation pass on Windows): ADR 0123's Consequences, the core on
  Linux and on Windows, the bridge, the Linux app and the Windows app
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
- The Windows app cannot be built on the developer's Mac: everything Kotlin cross-compiles there, and the C# without a
  Windows API builds and tests there, but the WinUI build, the core's tests and the app run on a Windows host
  (`src/mobile/windows/run.py` drives one over ssh), and the apps' CI job (still open) needs a Windows runner.
- **The bridge carries no key, so on Linux and Windows the core reaches the secure store itself** (found building the
  bridge, 2026-11-10; built 2026-11-11). Decision 1 has a platform hold the secure store, but `DeviceKeys`' calls carry
  the Master Key to seal and the refresh token to keep; forwarded to a host, both would cross the bridge. So the store
  is the core's own code, in `linuxMain` and `mingwMain`, and **the key never crosses the bridge**: the host hands the
  core only its window (Windows' HWND, the owner of Hello's prompt) and the prompt's words.
  - **Linux: the Secret Service through libsecret, reached by `dlopen`** (`core/secret/`, `LinuxDeviceKeys`). A small
    header of our own declares the few functions and `SecretSchema` it calls (`secret_password_store_sync`,
    `lookup_sync`, `clear_sync`, `secret_service_get_sync` for the capability), so `libveyq.so` has no `NEEDED`
    libsecret, no glib or libsecret header enters the Mac's build, and the app runs where libsecret is absent, offering
    nothing (decision 4). Every key is an item of the default collection; the unlock key has no presence check (the
    Secret Service has none), so Linux offers the login level alone. A Flatpak runtime must carry libsecret.
  - **Windows: DPAPI and the Passport key storage provider through cinterop** (`core/wincred/`, `WindowsDeviceKeys`):
    only the header's C helpers are exposed, so windows.h's types stay the platform library's and only integers, bytes
    and UTF-16 strings cross. DPAPI (`CryptProtectData`, the current user, no UI) keeps the bind keys, the secrets and
    the login level's unlock key; the presence level's 32 bytes are encrypted to an RSA key in the Microsoft Passport
    key storage provider (NCrypt), whose decryption Windows refuses without Hello. Hello's availability is cryptngc's
    `NgcGetDefaultDecryptionKeyName` (an undocumented export loaded at run time, as KeePassWinHello checks it), not
    `dsregcmd`, whose `NgcSet` says NO on a local account with a PIN. Rejected: `UserConsentVerifier`, a dialog only,
    which leaves the bytes open without it; `KeyCredentialManager`, WinRT, which Kotlin/Native reaches only through
    raw COM activation.
  - What each store guarantees is [security-model](../crosscutting/security-model.md) § What a trusted device rests on;
    what was built and measured, ADR 0123's Consequences, the device trust.
- **Every primitive on Windows is libsodium's, AES-256-GCM among them** (owner 2026-11-10: one source per platform, as
  Apple's and Linux's). libsodium's AES-GCM needs AES-NI, PCLMUL and AVX. An x64 CPU without AVX, and a Windows-on-ARM
  PC whose x64 emulation does not show AVX, are refused at start, as a CPU without AES instructions is on Linux. CNG for
  AES-GCM alone was rejected: two sources of primitives on one platform. What looked at first like emulation lacking AVX
  was our build's defect: libsodium's `configure` reads XCR0 on Windows x64 only through the `_xgetbv` intrinsic, which
  clang accepts only under `-mxsave`, so without the flag no CPU had AVX, an x64 PC's neither (measured 2026-11-10; ADR
  0123's Consequences, the core on Windows).
- **"The platform's side of a file" (decision 1) is read as: the dialog and the grant are the platform's, the bytes the
  core's** ([ADR 0128](0128-the-bridge-carries-shown-bytes-beside-the-json-ranges-through-a-reader-and-files-by-path.md)
  decision 3). The platform's save or open dialog chooses the file and hands over its path; the core opens it and reads
  or writes it (`blob/FileSink`, `blob/FileSource`), removing a download that did not finish. The Apple app did so
  first (`AppleDepot`); Linux and Windows do so through the bridge, where nothing of a file's content crosses.
- **When the members' pictures are read is the core's** (`session/AvatarsModel`, decision 1): Android and Apple had
  each synced them when a tenant opened, a rule in two apps before a third and fourth copy. They now watch the core's
  model, and Linux and Windows read its id set through the bridge (ADR 0128 decision 1).
- **A node's header and its sheets are core screen models** (2026-11-11/12, before Linux and Windows drew them; decision
  2's shape, each rule a `commonTest` case killed by a mutation; what the desktops built and measured is ADR 0123's
  Consequences, the Linux app and the Windows app). Android and Apple held each of these in their app models and draw
  them from the core now:
  - `navigation/NodeHeaderModel` (built per node by `ShellModel.header`): the rights composed from pinned, links on, the
    identity folder, a kind's doors (`AppKinds.doors`, a Pot's copy and cover only while writable) and whether a report
    is offered — read once per tenant, and asked again by the next header after a failed read, not kept as "not
    offered" as both apps did (a report is a legal duty: the safe side); the plan and its words; pin with its optimistic
    state, its revert and notice; trash with its undo, which the core holds open for `TRASH_UNDO_MS` (6 s) and then
    drops, so a host shows Undo until the state ends it and keeps no clock; rename; and the cover: its frame read when a
    kind that takes one opens, `saveCover`, `removeCover`.
  - `navigation/TrashSheet.purge(row)` takes the row the person confirmed (Apple's confirmation binding had cleared the
    row before the button's action, so "Delete forever" sent nothing).
  - `navigation/MoveSheet`: move and copy through one browser (the place's block, the guest folder's confirm, the copy's
    way by kind — a Pot by its open model, the rest `SubtreeCopy` —, no room as a notice with the copy's size, the
    left-out lines). **A move's undo is the core's 6 s window, as a trash's**: Android had shown it as a long snackbar,
    Apple for 6 s — a technical unification, open to the owner's veto. A move that ended a guest's folder (out of a drop
    box) offers no undo: the server refuses putting it back, which Apple's offered undo always met.
  - `ShellModel.download` writes a node's ZIP into `blob/FileSink`, removing a half-written one.
  - `navigation/ShareSheet`: the links, the form (`LinkForm` held as the sheet's state), mint, edit, renew, off and on,
    delete, the confirms, the fresh address, a folder's "Add files only". A link's end is a local day turned into an
    instant by the core (`session/LocalDays`). Its tests play the server's links at the sheet's door;
    `ShareLinks`' and `ShareLinkChanges`' HTTP and crypto are held by the live tests alone (a played tenant's scope keys
    and tree were judged not worth their size).
  - **The re-key's resume starts in the core** when a tenant is shown (`SessionModel.Door.opened`, never `open`, which
    "Manage servers" also calls for a tenant read only for its spaces); `TenantSession.rekeyed` then refreshes the shell,
    forgets the tree and re-reads an open Pot — not each row's details, as both apps did: a re-key changes keys, not
    what a row shows. No app starts it.
  - `navigation/ReportSheet`: offered or not, the form's rules (`NodeReport`), one send, and the privacy passage's
    address built by the core (`NodeReport.privacyUrl`), no longer by each app.
  - The cover's spec is `NodeCover`'s constants (long edge, quality, thumbnail edge, `encodedSize`) beside its diff
    (`NodeCover.changes`); decoding, cropping by hand and encoding stay the platform's, the crop's math `CoverFrame`'s.
    **Android and Apple still write a cover through `NodeCover` themselves**, not through the header model's cover
    calls, which Linux and Windows use: deliberate — the move took only the spec and the diff, and their cover screens
    had nothing left to decide. Moving them onto the header model is open, never needed for a rule.
  - Where the shell decided differently by accident, the core settled it on every app: an object opened at the top and
    trashed leaves for its application's list (`Shell.leave`, `ShellModel.afterTrash`; it stayed open on a closed model),
    and a renamed open object keeps its place and `top` (`ShellModel.afterRename`).
- The repository's Rust (`src/mobile/linux/`) is held to the no-deprecated rule by reading: cargo has no deprecation
  gate, as the JavaScript install has.
  The repository's C# (`src/mobile/windows/`) has one: `run.py build` is red on any NuGet package `dotnet package list`
  calls deprecated or vulnerable, transitive ones included.
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
