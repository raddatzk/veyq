---
id: cr-tv-app
title: The veyq app on Apple TV and Android TV — a viewing form of the one app, signed in by QR, each account behind a PIN the hardware binds and the server counts
status: in-progress
satisfies: ["uc.account.onboard-device", "uc.account.remember-this-device", "uc.account.unlock"]
adrs: ["0159", "0160", "0059", "0005", "0122", "0123", "0154", "0140", "0142", "0058"]
invariants:
  - "the server holds the DEVICE_PIN wrap, its share S and its verifier V, and never the hardware factor F: it can test no PIN and open no wrap (ADR 0160 decision 4)"
  - "the server releases S only to a matching V, and the tenth failure in a row deletes the wrap and its share (ADR 0160 decision 3)"
  - "a TV opens one account at a time behind that account's PIN; no PIN is shared between accounts (ADR 0159 decision 4, ADR 0160 decision 1)"
  - "the TV form removes every authoring action in the core's plans, never per screen (ADR 0159 decision 2)"
  - "a TV keeps no offline store and draws nothing of an account outside the lock (ADR 0159 decisions 7, 8)"
---

> **Status: in progress — the owner said go on 2026-11-25 and answered § Owner questions.** Refined 2026-11-25
> from inbox #1331 (owner 2026-11-25: "zeitnah angehen, damit wir alle Oberflächen zusammen pflegen können").

## Goal

The veyq app on Apple TV and Android TV / Google TV, as a form of the one app
([ADR 0159](../docs/decisions/0159-the-veyq-app-runs-on-apple-tv-and-android-tv-as-a-viewing-form-of-the-one-app.md)):
signed in by [ADR 0059](../docs/decisions/0059-device-linking.md)'s *Sign in with a QR code* (built), viewing every application
read-only and with no download (Memories, Depot, Expenses) and Search, several household members each with their own account behind their own PIN
([ADR 0160](../docs/decisions/0160-a-tv-unlocks-an-account-by-a-pin-the-device-hardware-binds-and-the-server-counts.md)).

## Context & constraints

- **What the app has today** (measured 2026-11-25 on `0b2f300fe`, by a read-only spike; the locators are § Inventory):
  several servers and accounts at once (`core/…/navigation/Connections.kt`, the switcher in `navigation/ShellWords.kt`),
  all resumed after one authentication (`SessionModel.kt`); both sides of the device link on Android and Apple
  (`session/DeviceLink.kt` `Role`), the new device's side camera-free (`SignInPlan.entries` always offers `LINK_SHOW`);
  levels `PRESENCE`/`LOGIN` only (`session/DeviceTrust.kt` `StaySignedIn`), offered only with a screen lock
  (`KeystoreDeviceKeys` on `keyguard.isDeviceSecure`; Apple through `LAContext` and
  `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`); no Secure Enclave, no StrongBox, no idle lock; Apple targets
  iOS + macOS only (`apple/project.yml`, Kotlin `iosArm64`, `iosSimulatorArm64`, `macosArm64`), libsodium and libwebp
  slices for those only; Android minSdk 31, no `androidx.tv`, no `LEANBACK_LAUNCHER`; players: AVPlayer with
  `ReaderLoader: AVAssetResourceLoaderDelegate`, Android `MediaPlayer` over `MediaDataSource`.
- **Platform facts** — verified 2026-11-25 from the source named, otherwise marked remembered:
  - tvOS `com.apple.developer.user-management`: `runs-as-current-user` (tvOS 14+) separates the app's data, preferences
    and keychain per Apple TV user; the `…-with-user-independent-keychain` variant (tvOS 16+) adds an opt-in shared
    keychain area (`kSecUseUserIndependentKeychain`); `get-current-user` deprecated in tvOS 16 —
    developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.user-management,
    …/tvservices/personalizing-your-app-for-each-user-on-apple-tv. Whether Apple must approve it: not found (looks
    self-serve, unverified).
  - tvOS profiles switch from Control Center with no passcode documented — support.apple.com/guide/tv/use-tvos-control-center-atvb5f549664/tvos.
  - Secure Enclave on Apple TV HD and later — support.apple.com/guide/security/secure-enclave-sec59b0b31ff/web;
    `kSecAttrTokenIDSecureEnclave` tvOS 9+, CryptoKit `SecureEnclave` tvOS 13+; **`LAContext` not on tvOS** —
    developer.apple.com/documentation/localauthentication/lacontext. A tvOS device passcode: not found (remembered: none).
  - App Store Connect: tvOS joins the iOS record and bundle id (universal purchase), "from a separate Xcode target" —
    developer.apple.com/help/app-store-connect/create-an-app-record/add-platforms; Xcode's own doc allows tvOS in one
    multiplatform target — …/xcode/configuring-a-multiplatform-app-target. `@FocusState`, `focusable`, `focusSection()`
    tvOS 15+.
  - tvOS storage: ~500 KB persistent `NSUserDefaults`, everything else purgeable — Apple's archived *App Programming
    Guide for tvOS* (archived: currency unverified). `AVPlayerViewController` and `AVAssetResourceLoaderDelegate` on tvOS.
  - Android TV: `LEANBACK_LAUNCHER` required, `android.software.leanback required=false` for phone + TV,
    `android.hardware.touchscreen required=false` mandatory, `android:banner` 320×180; "a single app that supports both
    mobile devices and TV devices" recommended; Compose the recommended toolkit — developer.android.com/training/tv/get-started/create.
    `androidx.tv:tv-material` 1.1.0 and `tv-foundation` 1.0.0 stable (2026-05-06), TV lazy layouts deprecated for the
    standard ones — developer.android.com/jetpack/androidx/releases/tv.
  - Android 16 CDD 9.11: TVs MUST have a TEE-backed Keystore [T-0-1] (devices launched on earlier versions exempt);
    StrongBox only STRONGLY RECOMMENDED; attempt limits for the lock screen only — source.android.com/docs/compatibility/16/android-16-cdd.
    An auth-bound key needs a secure lock screen — developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder.
    No app-level counted PIN in Keystore: remembered (none found there).
  - Google TV profiles can each be PIN-locked — support.google.com/googletv/answer/15330528; whether they separate an
    app's data: **unverified** (a non-Google source says adult profiles share third-party apps).
- **The machine** (machine hints, true of this CR's planned form — re-derive against what you build): the tvOS
  simulator has **no Secure Enclave**, so the PIN level is not offered there and a simulator pass covers the picker,
  the link and viewing, never the PIN; CoreSimulator was out of date under Xcode 27.2 (simulators disabled, `simctl`
  hangs) — check it first, and record a pass that cannot run as waiting, not as done. Never AVD `veyq_pixel`; create a
  TV AVD of your own (a Google TV system image). The trusted side of a QR sign-in in an unattended pass is a core live
  test fed the TV's payload text (read from the TV's UI semantics dump or a debug log), not a phone.

## Decisions (settled here; the why is in the ADRs)

1. **One CR, phases in the repo's order:** contracts → backend → core → Android → Apple → harvest. The PIN level is not
   split off: nothing but a TV takes it (ADR 0160 decision 8), and a PIN level without a TV to prove it on is half a
   feature (one PR per feature, owner 2026-11-23).
2. **The share and the verifier live with the wrap.** `DEVICE_PIN` is written through the existing
   `POST /api/account/wraps` with two more fields (`pinShare`, `pinVerifier`, both 32 bytes base64, required for
   `DEVICE_PIN` and refused for every other method); the server keeps them and a failure count on the wrap's row;
   the unlock is `POST /api/account/wraps/{id}/pin-unlock` `{verifier}` → `200 {share}` | `403 {attemptsLeft}` |
   `404` (the wrap is gone — the tenth failure deleted it, or another device removed it). Deleting the wrap deletes
   all three. The count is a constant 10 (owner question 2).
3. **The unlock is authenticated by the TV's session** (its refresh token kept as every app keeps one); a thief holds
   that too and gains nothing from it: the token reaches only ciphertext, and `V` needs `F`.
4. **The device form is an input to the core's plans** (`DeviceForm.PHONE | DESKTOP | TV` or the name the core's
   plans already use for a host trait — read `navigation/Plan.kt` and the plans' inputs first), and the TV form's answer
   is one place per plan that removes the authoring entries, tested per plan like rights.
5. **The account picker's label** is kept in the device store in the clear, beside the trust record (`host#userId`),
   named in `src/mobile/CLAUDE.md` § Nothing on disk the app did not choose when it lands.
6. **The idle lock is the core's** (`session/IdleLock` or beside `SessionModel`): inputs (input seen, playback running,
   app backgrounded, *Lock* chosen), one answer (lock now); 15 minutes (owner question 4). Each platform feeds it.
7. **The user-management entitlement is the owner's step at the end** (owner 2026-11-20); until then the tvOS build
   runs as the default user, and ADR 0159 decision 5's per-user separation is listed as waiting.
8. **Not touched:** `src/mobile/apple/Veyq/Veyq.entitlements` (the owner's local signing state), the web, admin-web,
   Linux and Windows.

## Inventory (locators — run, read as lists)

- Wrap-method consumers a new enum value reaches:
  `grep -rn "DEVICE_KEY" contracts src/backend/core/src/main src/web/src src/admin-web/src src/mobile/core/src src/mobile/android/src src/mobile/apple/Veyq scripts`
- The levels and their offers: `grep -rn "StaySignedIn\|staySignedIn" src/mobile --include=*.kt --include=*.swift`
- The plans the TV form must answer: `grep -rln "@Plan" src/mobile/core/src src/mobile/apps`
- What `Veyq/Core` imports that tvOS lacks (`LocalAuthentication`, `PhotosUI`, `PDFKit`, `UniformTypeIdentifiers`' pickers …):
  `grep -rhoE "^import [A-Za-z]+" src/mobile/apple/Veyq | sort | uniq -c`
- The Kotlin Apple targets and native slices: `grep -rn "iosArm64\|iosSimulatorArm64\|macosArm64\|sodiumSlices" src/mobile --include=*.kts` and `src/mobile/core/sodium/build_libsodium.py`
- The operator-blind probe's operation list: `grep -rn "everyContractOperationIsProbedOrExcludedWithAReason" src/backend`
- The published pages that describe signing in a new device and the levels: `grep -rln "QR\|Stay signed in\|Angemeldet bleiben" site/src/content/docs`

## Phases

### Phase 1 — contracts

`DEVICE_PIN` in `MasterKeyWrap.method` (`_common.openapi.yaml`), the two enrolment fields and the unlock operation in
`account.openapi.yaml` (decision 2); vectors `pin_kek` and `pin_verifier` in `contracts/crypto/vectors/v1.json`
(fixed `F`, `S`, PIN → `V`, salt, KEK, and a wrap opened by it); the new operation classified in the operator-blind
probe; the use case `uc.account.unlock-with-a-pin` written under `docs/features/account/use-cases/` from ADR 0160
(before any code reads it).
**Done when:** `pnpm run generate:api`, the contracts guards and `validate_wrap_method_vocabulary.py` green;
`pnpm run check` in `src/web/` and `src/admin-web/` run once and every red the new enum value causes named by its line
(predicted none: an added enum value reddens only an exhaustive switch — the first locator says which).

### Phase 2 — backend

`WrapMethod.DEVICE_PIN` (device-local, does not survive a credential change), the migration (share, verifier, failure
count on the wrap's row), enrolment validation, the unlock (constant-time compare, count, delete at 10, reset on
success), the rate limiter's bucket for it. Tests: a slice test that the tenth wrong verifier deletes the wrap and the
eleventh answers 404, a success resets the count, another account's wrap id answers 404, and the probe sees no
plaintext. **Done when:** `./gradlew check` in `src/backend/` green.

### Phase 3 — core

The derivations against the phase-1 vectors (the conformance suite); `StaySignedIn.PIN`; `DeviceKeys` gains the
hardware factor (absent → the level is not offered); enrol, unlock, change-PIN and the wrap-gone path in `session/`;
the account picker and its labels; the idle lock (decision 6); the TV device form in every plan (decision 4); the
`tvosArm64` and `tvosSimulatorArm64` targets in `core`, `apps/*` and `composition`, with tvOS slices of libsodium and
libwebp. Tests over a fake `DeviceKeys`: an unlock with the wrong PIN ten times ends signed out of that account only; a
picker never opens a second account without its PIN; the TV form leaves no authoring entry in any plan.
**Done when:** `./gradlew check` in `src/mobile/` green, including the tvOS targets' compilation and the conformance
suite; `validate_mobile_module_graph.py` and `validate_mobile_structure.py` green.

### Phase 4 — Android TV

`KeystoreDeviceKeys`: the PIN factor (HMAC-SHA256, no user authentication, StrongBox tried first, the TEE otherwise)
and the PIN level offered on a TV; the manifest (decision of ADR 0159 § 1) with a `TvActivity` and banner;
`androidx.tv:tv-material`; the TV UI: picker, PIN pad, the navigation drawer, Memories (list, gallery, Chapters,
lightbox, slideshow with music, video, music), Depot (tree, media viewers, the "opens on another device" row),
settings (*Lock*, *Add someone*, change PIN, sign out this account); the idle lock fed. **Done when:** `./gradlew check`
green, and a live pass on a Google TV AVD (not `veyq_pixel`): sign in by QR (the trusted side a core live test), set
a PIN, restart, unlock, view a Memory's slideshow with music and a video, idle into the lock, ten wrong PINs remove the
account; whether two Google TV profiles see each other's accounts, measured if the image has profiles, else listed
for the owner's device pass.

### Phase 5 — Apple TV

`project.yml` target `VeyqTV` (tvOS 26, the iOS target's bundle id, its own root `Veyq/TV`, no entitlements file
added — decision 7); `KeychainStore` without `LAContext` on tvOS; the Secure Enclave factor (ADR 0160 decision 2); the
same UI as phase 4 in SwiftUI with the focus engine, a top tab bar, AVPlayer for video. **Done when:** the tvOS build
compiles in Xcode, `scripts/` guards green, a tvOS simulator pass of picker, link and viewing (the PIN level absent
there by construction), and the Secure Enclave path listed for the owner's pass on a real Apple TV.

### Phase 6 — harvest

`docs/crosscutting/native-apps.md` (the TV form), `en.account.key-model` (the fourth level), `src/mobile/CLAUDE.md`
(the picker's label on disk, the tvOS traps found), the published pages EN + DE (Use: signing in a TV, the PIN;
Security: what the PIN level guards against and its residual), ADRs 0159 and 0160 Accepted with `Built`, inbox #1331
deleted, the CR deleted. **Done when:** every `scripts/validate_*.py` green (guard_gaps only if a validator changed).

## Owner questions — answered 2026-11-25

The owner accepted the recommendations 1, 2, 4 and 5 and changed question 3's scope:

1. **The PIN is per account on this TV** — not per instance or tenant (ADR 0160 decision 1).
2. **The attempt limit is a constant 10** — no Server-Owner setting.
3. **Every application works on a TV, read-only and with no download** (owner, quote: "Alle Anwendungen sollten auch
   auf TV funktionieren, aber wie du sagst nur lesend und ohne download"): Expenses too, read-only (this absorbs inbox
   #1350); Memories and Depot as planned; any other application the one app registers. No download, export, save,
   send, open-elsewhere or keep-offline action anywhere on a TV; Depot's files a TV cannot show stay listed and offer no
   download. ADR 0159 decision 3 records it. **Search too** (owner 2026-11-25, through the coordinator): read-only like
   every application, drawn with each platform's own search component and system keyboard — on tvOS the standard
   layout (keyboard grid beside the results as focusable tiles), on Android TV the system input method and a Compose
   for TV tile grid; never a hand-built keyboard.
4. **The idle lock:** 15 minutes without input while nothing plays, and at every return from the background.
5. **Android TV keeps `minSdk` 31** (inbox #1353 stays an idea).

## Definition of done

Every phase's Done-when; the invariants above each named by a test (the server's by phase 2's slice test, the core's by
phase 3's); the owner's end-of-line steps listed in the PR: `VeyqTV` provisioning and
`com.apple.developer.user-management` = `runs-as-current-user` plus the iOS target's app group and keychain group, tvOS
added to the App Store Connect record, the TV banner and the Play Console's TV form factor, and the device passes
(Secure Enclave on a real Apple TV, two Apple TV users, Google TV profiles).

## Decision log

- **2026-11-25 — Search on a TV is in (owner):** see § Owner questions 3. Read-only by nature, and its index is the
  core's `SearchModel` in memory (it writes nothing), so ADR 0159's objection — a TV's purgeable storage — does not
  apply. Inbox #1355 resolved by it.

- **2026-11-25 — the PIN is a level in meaning, not a `StaySignedIn` entry in code.** ADR 0160 decision 1 calls it the
  fourth level; the code models it as `TrustedDevice.Method.DEVICE_PIN` plus `DeviceKeys.pinFactors` (null: not offered),
  because its key is per account (a `StaySignedIn` level is one unlock key shared by every account at it) and its open is
  the server's (`pin-unlock`), not `openUnlockable`. An entry in `StaySignedIn` would put a branch that can never run into
  every desktop store and the bridge. The offer is `Offer.pin` on a TV; ADR 0160's wording follows at harvest.
- **2026-11-25 — a DEVICE_PIN wrap's `kdfParams` carry the Argon2id parameters alone** (16 bytes,
  `V1.encodePinKdfParams`): the salt is HKDF(F ‖ S) and never leaves the device — with it, whoever holds the wrap (the
  server) would search the PIN offline. The contract's description says so.
- **2026-11-25 — DEVICE_PIN rides `core.remember-device.passwordless`**: a TV never sees the password, so enrolling is
  refused (422) and the wrap withheld from the key material while that switch is off, exactly as DEVICE_KEY.
- **2026-11-25 — the unlock's answers:** `403 pin_incorrect` with `attemptsLeft` (a body of its own, `PinUnlockRefused`,
  written by `httpPinIncorrect` beside the one other custom body), `404 not_found` for no wrap of the caller's with that id
  (never had, removed, the tenth failure — indistinguishable on purpose), a per-account `USER` rate bucket on top of the
  wrap's count. The row is locked (`PESSIMISTIC_WRITE`) for the count.
- **2026-11-25 — the vectors** were computed by an independent implementation over `@noble` (the seal construction first
  re-derived `seal.sym.master-key` byte for byte); `pin_kek` pins the production Argon2id cost (m = 32 MiB), so the
  conformance suite checks it only at production cost, the verifier and the salts always.
- **2026-11-25 — the TV form is one fact, `ThisDevice.form`** (`DeviceForm.PHONE | DESKTOP | TV`, the host sets it), read
  as `ShellModel.viewOnly` — the shell's creates, uploads, drops, shares, trash and download answer nothing, and every
  header and row goes through `NodeHeader.viewOnly` (all writes, the download and "Available offline" off). Memories reads
  it through `MemoryRights.Access.Member.viewOnly`, Depot through `FileRights.of(…, viewOnly)`, Expenses through
  `PotDoor.viewOnly` (writes and the Pot's download refused in the model, not only in the plan). Search's settings
  sections are empty on a TV (its settings are its own few).
- **2026-11-25 — no report on a TV (our reading, for the owner):** the report is a form a remote types badly; every other
  device of the account offers it. We read the DSA's notice duty (Art. 16) as binding the service — reachable on every
  other client — not each client. A question the law leaves open; if the owner reads it otherwise, `NodeHeader.viewOnly`
  keeps `report` and both TV UIs draw the report flow.
- **2026-11-25 — a TV signs in by its QR code only** (`SignInPlan.tvEntries`: *show a code*, and Cancel while adding):
  the PIN level exists because a TV never sees the password or the Secret Key.
- **2026-11-25 — the picker's default name is the account's address**, not its profile name: a profile name is a tenant's
  and sealed under its keys, the picker shows before any key is open. The TV's settings rename it
  (`VeyqAccount.renameOnPicker`); ADR 0159 decision 4's wording follows at harvest.
- **2026-11-25 — a TV's unlock renews its kept session first** (the refresh token is kept as a remembered account keeps
  it), then asks for the share; a refresh refused or a factor missing (a reinstall, another Apple TV user) forgets the
  account here — it needs a new pairing (ADR 0160's Consequences).
- **2026-11-25 — `lock()` retires every open connection** (keys zeroed, the kept session kept: `holdsKeptSession` counts
  DEVICE_PIN), so the next unlock renews it; unlocking another account closes the open one first — one at a time.
- **2026-11-25 — tvOS has no `LAContext`**: `staySignedIn` asks `deviceOwnerAuthenticationAvailable()`, an `expect`
  whose iOS and macOS actuals are the LAContext probe and whose tvOS one is false — a TV offers neither presence nor login.

- **2026-11-25 — a video plays on a TV through a right of its own** (`MemoryRights.Rights.play`, true on a TV,
  included in `download`): `GalleryModel.reader` asked for `download`, which a TV lacks, so the Android pass found no video
  playing. Reading a medium in ranges into a player is viewing; saving it is taking it away.
- **2026-11-25 — Android: the PIN factor is offered only on hardware** — a probe key's `KeyInfo` level must be TEE,
  StrongBox or unknown-secure; a key created later is checked again and refused if software-held (ADR 0160: a
  non-exportable hardware key). Measured: the Google TV emulator's Keystore is software (level 0), so there the TV says
  `tv_pin_not_offered` and keeps nothing.
- **2026-11-25 — Android TV draws Memories and Depot on TV pages of their own** (`KindPage.tv`), the Pot, a person and a
  kept link on the phone's page (its plans read-only on a TV already); every application's list is the core's arranged
  list (Favorites, Recent, All as tiles). A TV page owns its whole page, so Memories' map tab is never drawn — the core's
  `Shell.barTabs` still names it, which only a page that draws the bar would show.
- **2026-11-25 — a Depot file a TV cannot show**: the TV page draws `FileModel.Shown.Text`, `Pdf` and `Message` as
  `tv_opens_elsewhere` (an exhaustive drawing of the core's answer); images and media go to the viewers.
- **2026-11-25 — Android TV's controls:** the slideshow starts the Memory's music with it; video plays in a D-pad player of
  its own (`TvReaderPlayer`: centre/Enter/media key play-pause, left/right ±10 s) — `MediaController` is touch-only; Search
  is a text field the system IME (Gboard for TV, voice included) fills and a tv-material card grid — no leanback
  `SearchSupportFragment`; text fields are left with the D-pad (`tvField`); the idle lock is fed by `onUserInteraction`
  (overriding `dispatchKeyEvent` is a `RestrictedApi` lint error), `onStop`/`onStart` and the players; the banner is the
  mark on the dark surface, generated by `generate_theme.py`. Memories' header keeps MUSIC on a TV: it plays and stops
  the Memory's music, which is viewing.

## Retrospective

(filled by the implementer)
