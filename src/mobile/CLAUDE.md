# src/mobile — the veyq app

One app for every application ([ADR 0122](../../docs/decisions/0122-one-app-holds-every-application-the-drawer-chooses-the-bar-belongs-to-it.md)),
a Kotlin Multiplatform base under each platform's own UI ([ADR 0123](../../docs/decisions/0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md)).
A Gradle build of its own, run through its own wrapper.

- `core/` — the shared base: the v1 suite (`crypto/`), the generated API client (`build/generated/openapi`, from
  `contracts/api`, never edited), the session (`session/`), the navigation model (`navigation/`), and each
  application's own package (`expenses/`: the web's Expenses domain, a Pot's session and what its views show; `depot/`: Depot's files, each one
  core attachment, and how a file's page shows it), which
  uses core's session and is never used by it; an application's spec is generated into `api.<application>`.
- `android/` — the Android app (Compose). `devlogin.py` signs a debug build on a running emulator in as the seeded dev
  principal (`.run/.env`).
- `apple/` — the Apple app (SwiftUI, iOS and macOS). `xcodegen` in that directory writes `Veyq.xcodeproj` from
  `project.yml`; the project builds `VeyqCore.xcframework` itself before it compiles. `devlogin.py <device>`
  relaunches the installed debug app in a booted simulator, signed in as the seeded dev principal. `VeyqShare/` is the
  iOS share extension (ADR 0124), embedded in the app: it compiles every file of `Veyq/` but `VeyqApp.swift` (the
  entry point) and `DevLogin.swift`, under its own `ShareViewController`.

- `theme/generate_theme.py` — the web's colour roles and brand mark for every platform (ADR 0123 decision 7): the
  builds run it (`:core:generateTheme`, `:android:generateThemeRes`, the Apple project's pre-build step); its output
  is never committed. A colour or the logo changes in `src/web/`, never here.

## Prove your work

- `./gradlew :core:jvmTest :core:macosArm64Test` — the suite against `contracts/crypto/vectors/v1.json` (with its
  bite) over BouncyCastle and over libsodium, the navigation model, and the grant check (`GrantTrustTest`: the web's
  `openOwnGrant`/`verifyOwnGrant` rules against a server the test plays). Any change under
  `core/src/commonMain/kotlin/me/raddatz/veyq/mobile/crypto` runs both. The first Apple build compiles libsodium
  (`buildLibsodium`, a few minutes, once). A change to a rule in `session/Grants.kt`, `session/IdentityPins.kt` or `session/Anchoring.kt` needs
  the case that refuses it: remove the rule and `GrantTrustTest` must go red. Expenses' arithmetic and format are held
  by `ExpensesVectorsTest` (`contracts/expenses/v1-vectors.json`, the crypto vectors' `expense-entry`) and
  `LedgerRulesTest`; `BigTest` holds `expenses/Big.kt` to `java.math.BigInteger`. A rule of `expenses/Ledger.kt` or
  `Money.kt` changes only with the case that pins it; so does one of `expenses/PotWrites.kt` and `LedgerCheck.kt`
  (`PotWritesTest`, over real seals), of `EntryForm.kt` (`EntryFormTest`, over the vectors), of `ExchangeRates.kt` (`ExchangeRatesTest`), of `Receipts.kt` and core's `blob/Sniff.kt` (`ReceiptsTest`), of `PotShare.kt` (`PotShareTest`), of core's `session/LivePoll.kt` (`LivePollTest`, on virtual time), of core's `session/MemberAvatars.kt` (`MemberAvatarsTest`) and `session/ShareNames.kt` (`ShareNamesTest`), of `PotView.kt`'s people, balances and entries (`PotViewPeopleTest`, `PotViewEntriesTest`), of core's `session/NodeEpochWrite.kt` and `session/NodeMetaWrite.kt` (`NodeEpochWriteTest`, `NodeMetaWriteTest`), of
  core's `session/NodeMove.kt` browser rules, a guest's folder's included (`NodeMoveTest`), of `navigation/NodeHeader.kt`'s plan and
  its narrowing in a guest's folder (`NodeHeaderTest`), of core's `session/CoverFrame.kt` (`CoverFrameTest`), of core's `export/NodeExport.kt` archive (`FolderExportTest`, `ZipTest`) and its streamed `export/Sha256.kt` (`Sha256Test`), of Depot's `depot/FolderUpload.kt` plan (`FolderUploadTest`), of Depot's `depot/FileView.kt` and core's
  `blob/Sniff.kt` and `blob/DownloadName.kt` (`FileViewTest`), and of core's `session/NodeRekey.kt` frontier and what it
  leaves to another device (`NodeRekeyRulesTest`).
- `VEYQ_LIVE=http://veyq.localhost:8080 ./gradlew :core:jvmTest :core:macosArm64Test --tests '*LiveSignInTest*'` —
  signs in to a running dev instance as the seeded principal on both paths and reads its spaces, objects and
  favorites (`./gradlew backend:seed` at the repository root first). With `VEYQ_LIVE_MEMBER=1` as well,
  `--tests '*MemberLiveSignInTest*'` signs in as the seeded second member (`VEYQ_USER2_*`), whose TENANT grant the
  owner signed, so it opens only through the key check; the owner starts that run, since that password may be one a
  person chose. `--tests '*DeviceTrustLiveTest*'` walks the device trust (ADR 0123 decision 8) as the seeded principal:
  password-trusted, staying signed in, the register's join, sign-out; it removes what it enrolled.
  `--tests '*PotLiveTest*'` opens every seeded Pot and checks its revisions open, its ledger check passes, its balances
  sum to zero and its space's members read by name; with `VEYQ_LIVE_POT_WRITE=<pot name>` it adds one expense there
  ("App check"), which the web's Pot page then shows — the check that both clients read and write one format. It leaves
  that entry behind in the dev instance. `VEYQ_LIVE_POT_PEOPLE=<pot name>` adds a person there, renames, deactivates and
  removes them (only the erasure record stays); `VEYQ_LIVE_POT_SETTINGS=<pot name>` (a Pot without money entries) switches its
  currency and back and adds an own category ("App category renamed", which stays: categories are never removed).
  `VEYQ_LIVE_POT_CREATE=1` creates a Pot ("App pot <time>") with a name and a member, checks its name is taken then, and
  that a Pot the web created collides with the app's of its name (the slug token is the web's), and moves it to the trash.
  `VEYQ_LIVE_RATES=1` asks the tenant's exchange-rate service (a third party: Frankfurter on a dev instance) for today's table once.
  `VEYQ_LIVE_RECEIPT=<pot name>` adds an entry with a two-frame receipt there through the object store, reads it back
  to the byte and saves the entry without it, so the receipt is dropped (the entry stays). The dev backend's store
  endpoint must be one this machine reaches (`VEYQ_BUNDLED_STORE_PUBLIC_ENDPOINT`). `VEYQ_LIVE_POLL=<pot name>` opens
  the Pot twice, writes an entry ("Poll check") on one and deletes it, and checks the other's live poll sees both (the
  deleted entry stays in the Pot's deleted entries). `VEYQ_LIVE_SWITCH=<pot name>` switches Expenses off for the tenant
  (its owner's switch), checks the Pot reads read only and a write is refused, and switches it back on — with
  `VEYQ_LIVE_SWITCH_HOLD_S=<s>` it stays off that long first, for a UI pass (re-open each app: a tenant's set is read
  when it opens). `VEYQ_LIVE_AVATARS=1` syncs the tenant's profile pictures and checks a second sync asks nothing; a
  picture to open is set on the web's Settings > Your profile. `VEYQ_LIVE_NODE_ACTIONS=<pot name>` pins and unpins the
  Pot (each read back by a fresh session), and creates a Pot ("App trash <n>") that it trashes, restores under its name
  and trashes again — it stays in the space's trash. `VEYQ_LIVE_TRASH=<pot name>` trashes a fresh Pot ("App trash view
  <n>"), reads it in the trash by name, restores it past a Pot of its name under "… restored" and purges it (the Pot
  of the old name stays trashed). `VEYQ_LIVE_MOVE=<root folder name>` moves a fresh Pot ("App move <n>") into that
  folder and back, a fresh session opening it there each time, and checks a move to its place and onto a Pot of its name
  are refused; both Pots stay trashed. `VEYQ_LIVE_LINKS=<pot name>` mints a READ link with a label and a READ_WRITE link
  with a password and an end on that Pot and reads both back listed (they stay); `VEYQ_LIVE_LINKS_OUT=<file>` writes the
  first one's address there, for the web's guest page (a dev link names the backend's port, 8080: open it on Vite's). `VEYQ_LIVE_LINK_OFF=<root folder name>` creates a Pot there with an entry, a receipt and links A and B, switches A off
  — the Pot re-keyed on this device — and checks a fresh session reads the entry and the receipt and writes there at
  once; with `VEYQ_LIVE_LINKS_OUT` both addresses go to that file, for the web's guest page (A opens nothing, B the Pot). `VEYQ_LIVE_LINK_CHANGES=<root folder name>` creates a Pot there with an entry, edits a link in place,
  renews it (the Pot re-keyed, a fresh session still reads the entry) and renews, switches off and deletes a second;
  `VEYQ_LIVE_LINKS_OUT` gets the first one's old and new address (old opens nothing, new the Pot).
  `VEYQ_LIVE_SUBTREE=<root folder name>` (`--tests '*SubtreeRekeyLiveTest*'`) builds a folder there ("App subtree <n>")
  holding a Pot with an entry and a receipt and a folder with a Pot, switches a link on it off — the whole subtree
  re-keyed — then interrupts a second close after its root and resumes it as a member's device does, and reads both Pots
  back each time; everything stays. With `VEYQ_LIVE_SUBTREE_LEAVE=1` it stops at the interruption, for an app to finish:
  start one (the devlogin), then `VEYQ_LIVE_SUBTREE_DUE=<root folder name>` checks nothing of that space is due and
  every Pot of it opens. `VEYQ_LIVE_EXPORT=<pot name>` downloads that Pot as the web's ZIP and reads it back (every payload's SHA-256 against
  the manifest, an entry each, a CSV line each); `VEYQ_LIVE_EXPORT_OUT=<file>` keeps it there, for the web's import (at a
  level: New, Upload files, the archive — the web offers its import). `VEYQ_LIVE_COPY=<pot name>` copies that Pot beside itself (the next free name), checks a fresh session reads the same
  live entries, the same balances person by person and every receipt to the byte, and trashes the copy.
  `VEYQ_LIVE_COVER=<pot name>` sets a drawn JPEG as that Pot's cover, checks a fresh session opens it to the byte, and
  removes it again; with `VEYQ_LIVE_COVER_KEEP=1` it stays, for the web's rows and header. `VEYQ_LIVE_COVER_CROP=<pot name>`
  (`--tests '*CoverCropLiveTest*'`) sets a drawn cover framed on its left half, reads the crop back, frames it on its right
  half by a crop-only write (the blob kept) and removes it — kept with `VEYQ_LIVE_COVER_KEEP=1`: the web's header then shows
  the blue half with the white disc. `VEYQ_LIVE_FOLDER="<root folder>/<folder>"` (`--tests '*FolderLiveTest*'`) downloads that folder whole and
  reads the archive back (every folder a directory without content, a file its payload at its own path, every payload to its
  SHA-256; `VEYQ_LIVE_EXPORT_OUT=<file>` keeps it, for the web's import: New, Upload files, the archive), then copies it
  beside itself and checks a fresh session reads every Pot of the copy as the original's and every file at its place to the
  original's SHA-256, a drop box there a plain folder; the copy is trashed unless `VEYQ_LIVE_COPY_KEEP=1`.
  `VEYQ_LIVE_DEPOT=<root folder>` (`--tests '*DepotLiveTest*'`) uploads a text file of three frames ("App file <n>.txt")
  there, reads it back in a fresh session to the byte with its sealed type, name and modified time, replaces it and reads
  that back (a new blob; a replace over the old one refused as stale); it is trashed unless `VEYQ_LIVE_DEPOT_KEEP=1`, for
  the web's file page. `VEYQ_LIVE_DEPOT_READ="<root folder>/<file>"` reads a file the web uploaded and prints its size and
  SHA-256. `VEYQ_LIVE_FOLDER_UPLOAD=<root folder>` (`--tests "*DepotLiveTest.aDirectoryIs*"`) uploads a nested directory made
  in the test there twice ("App up", "App up (2)") and reads every file back to the byte; both stay. `VEYQ_LIVE_STORAGE_FIT="<root folder>/<folder>"`
  (`--tests "*DepotLiveTest.aCopyOrAFolder*"`) sets the tenant's quota to one byte on the admin listener (`VEYQ_ADMIN_BASE`,
  default `http://127.0.0.1:8090`; the seeded principal is the Server Owner), checks that folder's copy and a folder upload
  are said not to fit, and puts the tenant's quota as it was back however it ends — `VEYQ_LIVE_STORAGE_FIT_HOLD_S=<s>` (at
  most 600) holds it first, for an app's copy dialog. A run stopped by force leaves the one byte set: the next run takes a
  quota of exactly one byte for its own leftover and clears it.
  `VEYQ_LIVE_DROPBOX="<root folder>/<drop box>"` (`--tests '*DropBoxLiveTest*'`) opens a drop box the web made
  and its guests filled (the web's folder share dialog, "Add files only", then its link in a private window), lists each
  guest's folder under the web's label and prints each file's SHA-256, to hold against what was dropped. `VEYQ_LIVE_DROPBOX_CREATE=<root folder>`
  (`--tests "*DropBoxLiveTest.theAppMakes*"`) makes a drop box there ("App drop <n>") with its link, mints a second on its
  drop key and switches it off; `VEYQ_LIVE_LINKS_OUT` gets the first link, for a web guest to drop through. `VEYQ_LIVE_DROPBOX_REKEY_SETUP=<root
  folder>` (`--tests "*DropBoxLiveTest.aDropBoxIsReKeyed*"`) makes a folder there ("App dropre <n>") holding a drop box
  "Box" and writes its link to `VEYQ_LIVE_LINKS_OUT`; once a web guest dropped through it, `VEYQ_LIVE_DROPBOX_REKEY="<root
  folder>/App dropre <n>"` mints and switches off a READ link on the folder — the drop key replaced, every guest's `K_up`
  sealed anew — and checks nothing is due and a fresh session reads every guest's file (the same link takes files after). `VEYQ_LIVE_LINK_REACTIVATE=<root folder>`
  (`--tests "*DropBoxLiveTest.aSwitchedOff*"`) makes a folder there ("App reopen <n>") with a READ link and a drop box with
  its link, switches both off and on again at their old addresses; `VEYQ_LIVE_LINKS_OUT` gets both, for the web's guest page. `VEYQ_LIVE_REPORT=<pot name>` reports a fresh Pot beside it — its three keys open here, the server answers with a
  receipt — and trashes it; each run leaves an open report for the dev moderators, so run it once, not in a loop. `VEYQ_LIVE_RENAME=<pot name>` renames a fresh Pot twice, checks its
  names taken and a sibling's refused, and trashes it; `VEYQ_LIVE_RENAME_TARGET="<from>|<to>"` renames the root Pot
  `<from>` (created when there is none) and prints what the re-seal of its links did — with a link the web minted on it,
  that link's guest page then shows `<to>`. Gradle does not count an environment variable as an
  input: a run that changes only these needs `--rerun`, else the test is "up to date" and nothing runs.
- `VEYQ_LIVE=http://veyq.localhost:8080 VEYQ_SEED_TENANT="verein.localhost|Turnverein" ./gradlew :core:jvmTest --tests
  '*DevSecondTenantTest*'` — dev seeding, not a check: gives the seeded principal a second tenant (the seeder births only
  one), through the app's own suite, so the switcher has two to switch between. Skipped when it already exists.
- `./gradlew :android:assembleDebug` — the Android app; on an emulator, `python3 android/devlogin.py` signs it in
  (`--remember` staying signed in, `--unlock` with only the password for the unlock screen of a trusted account; a
  fresh start, since Android hands a running task no new extras). Apple's `devlogin.py <device>` takes the same flags.
  Neither stops a running app (an install does): one still running keeps the tree it read, so what the web changed
  meanwhile is not there — `adb shell am force-stop me.raddatz.veyq` first.
  A share to veyq (ADR 0124) is tried from a real sender — the Files app (`com.google.android.documentsui`): long-press,
  Share, veyq. On iOS from the Photos app: `xcrun simctl addmedia <udid> <image>`, the photo, Share, veyq; the extension
  resumes only an account the app left staying signed in (`devlogin.py <device> --remember`; answer its Face ID) or
  password-trusted, else it says to open veyq first. `adb shell am start -a android.intent.action.SEND --grant-read-uri-permission` with a MediaStore URI reaches
  the app but grants nothing (the shell may not), so the stream does not read (`veyq-share` in logcat).
- `xcodebuild -project apple/Veyq.xcodeproj -target Veyq -sdk macosx build CODE_SIGNING_ALLOWED=NO` (or
  `-sdk iphonesimulator`) — the Apple app compiles. On a fresh checkout run `./gradlew :core:assembleVeyqCoreDebugXCFramework`
  once first: Xcode looks for the framework before the project's own pre-build step can make it.

## Each platform's guidelines

Apple's HIG and Material 3 win over a look shared with the web ([ADR 0123](../../docs/decisions/0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md)
decision 9): before building a control, ask what the platform's own apps do, and do that. A view's creating action is
`CreateAction(…)` (Android, `CreateAction.kt`; with `menu` where a level creates several kinds) and `.createAction(…)` /
`.createMenu(…)` (Apple, `CreateAction.swift`), never a
floating button built by hand; `scripts/validate_mobile_platform_guidelines.py` holds that. Row actions: an overflow menu on Android, swipe actions and a context
menu on iOS.

A node's header is core's, for every kind (ADR 0123, the web's `NodePage` + `NodeHeader`): core's `navigation/NodeHeader.kt`
plans it (`NodeHeaderTest`), each app's `NodeHeaderActions` draws it and owns every dialog behind it, and a kind only
registers its page — `KindPages` (Android) / `KindPage` (Apple): it brings a share sheet, takes a cover, contributes a
subtitle, its rights narrowing, its copy and download doors and own actions, and draws its body. A new kind registers
there and its header order in `AppKinds.headerOrder`; it never builds header actions of its own. Its body places core's head
first in each of its scrolls — `LocalNodeHero.current(inset)` (Android) / `NodeHeroRow()` (Apple) —, never a cover of its own.

## Traps

- The Apple primitives are libsodium 1.0.22 through cinterop (`core/sodium/`): `build_libsodium.py` builds the three
  arm64 slices from the release tarball, refused unless it matches the pinned SHA-256. GitHub's release CDN answers a
  504 now and then; the script retries, and a tarball already in `build/libsodium/` is used as it is.
- `VeyqCore` has arm64 slices only, so `apple/project.yml` sets `ARCHS: arm64`; without it a Release build (what
  `xcodebuild -target` builds) asks Kotlin's Xcode hook for x86_64 and fails with "Xcode Requested Architecture Not
  Configured in Gradle".
- The trust marks (the pin store's revision, each scope's floor and held key, `session/TrustMarks.kt`) are the device's
  own, in SharedPreferences / UserDefaults, keyed by a derivation of the Master Key; clearing the app's data is the
  web's no-mark case (a fresh device), never a lockout.
- Staying signed in needs a screen lock: the unlock key cannot exist without one. On the emulator `adb shell
  locksettings set-pin <pin>` sets one and the prompt takes it by `adb shell input text`; it also needs the
  `USE_BIOMETRIC` permission, without which the prompt throws a `SecurityException`. The iOS Simulator stores a
  `.userPresence` item but does not enforce it (measured 2026-11-08), which is why `KeychainStore` asks through
  LocalAuthentication first; enrol and answer Face ID with `xcrun simctl spawn <udid> notifyutil -p
  com.apple.BiometricKit.enrollmentChanged` (after `-s … 1`) and `notifyutil -p com.apple.BiometricKit_Sim.pearl.match`.
  The Simulator keeps the Keychain across an uninstall: `xcrun simctl keychain <udid> reset` starts it empty.
- The emulator reaches the developer's machine at 10.0.2.2; the debug build maps every `*.localhost` name there and
  keeps the name in the Host header, since the backend picks the tenant from it. Use the tenant's name
  (`veyq.localhost:8080`), never `10.0.2.2:8080`, as the server.
- `VeyqAccount.http` puts the account's bearer on EVERY request it sends, whatever the host (the generated clients reach
  every tenant of the server through it). A request to anyone else — an external service the tenant names, the day's
  exchange rates, the object store's presigned URLs (`putToStore`, `getFromStore`) — goes through `VeyqAccount.outside`
  (`fetchOutside`): no bearer, no cookie, no header of the app's own.
- SwiftUI attaches a modifier on a `Form`'s or `List`'s `Section` to every row of it: a `.sheet` there is presented by
  none, an `.onChange` runs once a row. Presentations of a section go on the `Form` (`ReceiptPresenters`).
- A Kotlin name exported to Swift must not be one of NSObject's: an enum entry `SELF` becomes the class property `self`,
  which overrides `+[NSObject self]`, and the class's first use recurses until the stack overflows (measured 2026-11-09:
  `NodeMove.Block.SELF`, now `ITSELF`). Avoid `self`, `description`, `hash`, `class`, `copy`, `init`, `new` as names, and
  a method name that STARTS with `copy`, `new`, `alloc` or `init`: Objective-C's ownership rule makes Kotlin export it as
  `doCopyTo(…)` (measured 2026-11-09: `PotCopy.copyTo`, now `duplicate`).
- A Swift closure handed to core is called on core's threads (after a request answered, Ktor's), never only on the main
  one: written in the `@MainActor` model it is inferred main-actor isolated, and Swift 6 traps on the first call from
  elsewhere (`_dispatch_assert_queue_fail`, measured 2026-11-09: the re-key's rekey id). Mark it `@Sendable` and reach
  nothing of the model in it (`AppModel.tokenInputFor` is `nonisolated static` for that).
- The Simulator tool's tap does not reliably flip a SwiftUI `Toggle`: a short swipe across it does (measured
  2026-11-09, the report's good-faith switch). A Compose radio or checkbox row is tapped as the row only once the row
  is `selectable`/`toggleable` (`ReportSheet`): a bare control's label takes no tap, for a person either.
- Every file of `apple/Veyq/` but `VeyqApp.swift` and `DevLogin.swift` is also compiled into the share extension, which
  builds with `APPLICATION_EXTENSION_API_ONLY`: no `UIApplication.shared` or other API an extension may not use there,
  and nothing the app alone may do (moving or removing what it keeps) without `AppModel.inExtension` guarding it.
- On iOS what the share extension needs is shared (ADR 0124): defaults go through `Shared.defaults` (the app group's
  suite), never `UserDefaults.standard`, and a Keychain item through `KeychainStore` (the entitlements' first group is the
  shared one). A value only the app reads may stay its own, but nothing the session or the device trust reads.
