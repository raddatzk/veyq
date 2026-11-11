# src/mobile — the veyq app

One app for every application ([ADR 0122](../../docs/decisions/0122-one-app-holds-every-application-the-drawer-chooses-the-bar-belongs-to-it.md)),
a Kotlin Multiplatform base under each platform's own UI ([ADR 0123](../../docs/decisions/0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md)).
A Gradle build of its own, run through its own wrapper.

- `core/` — the shared base: the v1 suite (`crypto/`), the generated API client (`build/generated/openapi`, from
  `contracts/api`, never edited), the session (`session/`), the navigation model (`navigation/`), a photo's and a video's metadata read from their bytes (`media/`),
  files on the device by the path a platform's dialog gave (`blob/FileSink`, `blob/FileSource`, `nativeMain`: the
  core opens, writes and reads them, and removes a download that did not finish; the one call that differs is
  `blob/Files.kt`'s, `posixMain` for Apple and Linux, `mingwMain` for Windows; [ADR 0128](../../docs/decisions/0128-the-bridge-carries-shown-bytes-beside-the-json-ranges-through-a-reader-and-files-by-path.md) decision 3), when the members'
  pictures are read (`session/AvatarsModel`, held as `TenantSession.pictures`: synced when the tenant opens and when
  `peopleRead` gets a different set; every app watches it), and each
  application's own package (`expenses/`: the web's Expenses domain, a Pot's session and what its views show; `depot/`: Depot's files, each one
  core attachment, and how a file's page shows it; `memories/`: a Memory's gallery, each medium under the key its origin
  fixes), which
  uses core's session and is never used by it; an application's spec is generated into `api.<application>`.
- `android/` — the Android app (Compose). `devlogin.py` signs a debug build on a running emulator in as the seeded dev
  principal (`.run/.env`).
- `apple/` — the Apple app (SwiftUI, iOS and macOS). `xcodegen` in that directory writes `Veyq.xcodeproj` from
  `project.yml`; the project builds `VeyqCore.xcframework` itself before it compiles. `devlogin.py <device>`
  relaunches the installed debug app in a booted simulator, signed in as the seeded dev principal. `VeyqShare/` is the
  iOS share extension (ADR 0129), embedded in the app: it compiles every file of `Veyq/` but `VeyqApp.swift` (the
  entry point) and `DevLogin.swift`, under its own `ShareViewController`.
- `bridge/` — the core as one shared library, `libveyq.so` on Linux and `veyq.dll` on Windows (ADR 0127 decision 5;
  what was built and measured is ADR 0123's Consequences, the bridge). `bridge.h` is its whole C surface, six functions written
  by hand and held to Kotlin/Native's own header by `BridgeHeaderTest`. `commonMain` holds the wire (`Wire.kt`: the
  `Call`s, the `HostRequest`s, what the core sends), the dispatch onto the core's models (`Bridge.kt`) and the host's
  device store (`Host.kt`). `bridgedMain/Exports.kt` holds the six `@CName` functions (`bridgedMain` is what Linux and Windows
  share, in `core` as here; each target's own set brings only its Ktor engine). `jvmMain/Bindings.kt` writes
  `bridge.rs` and `Bridge.g.cs` (`:bridge:generateBindings`, into `build/generated/bridge`, never committed). `smoke/smoke.c` is a C
  host built against `bridge.h` alone. A rule found in the dispatch is a defect to move into the core; a field the UI
  does not draw, and any key, seal or token, is never part of a state the bridge sends (`SecretFreeSchemaTest`).
  Beside the JSON go three channels ([ADR 0128](../../docs/decisions/0128-the-bridge-carries-shown-bytes-beside-the-json-ranges-through-a-reader-and-files-by-path.md)): bytes a UI shows as `Answer.Bytes`' payload (`Avatar.Picture`; the
  state names the ids to ask for, an update never carries bytes), a blob's ranges through a reader held by id
  (`Answer.Reader`, `Reader.Read`, `Reader.Close`, closed with the tenant; `Bridge.openReader` the door), and files by
  the path the host's dialog gave, which the core opens (`Pot.Download` through `DeviceFiles`, core's `FileSink`).
  The generated layer copies a payload once (Rust `Option<Vec<u8>>`, C# `byte[]?`); no screen touches a pointer.
- `linux/` — the Linux app (ADR 0127 decision 4): a Cargo package in Rust (gtk4-rs, libadwaita-rs), with `Cargo.lock`
  committed and every cargo run `--locked`. `src/lib.rs` is the library: `bridge` (the safe wrapper over the six
  functions, the host function), `words`, `said` (money, days and sizes the desktop's way), `theme`, `create_action`
  and one module per screen (`sign_in`, `shell` with Search, `home`, `pot` with its download; `dev_login` in a debug
  build only), and `avatars` (`Held`, no widget and tested: the open tenant's pictures, asked for again only when the
  shell's id set changes; drawn as `adw::Avatar`).
  `src/main.rs` is the app. `build.rs` reads the generated bridge, words and theme from `VEYQ_GENERATED` and the
  library from `VEYQ_LIB_DIR`, both set by `run.py`. `Containerfile` (Fedora 44 by digest) is its build and run
  environment. `run.py` is its one entry point (§ Prove your work); `run.py lock` is the one writer of `Cargo.lock`.
  Crates are held to the no-deprecated rule by reading each one's repository: cargo has no deprecation gate.
- `windows/` — the Windows app (ADR 0127 decision 5; ADR 0123's Consequences, the Windows app): .NET 10 projects in C#.
  `Veyq.Bridge` (net10.0, no Windows API) is the safe wrapper over the six functions, the host's device store under
  `%LOCALAPPDATA%\veyq\`, and the hand-off to the UI thread; `Veyq.Bridge.Tests` its round trip and the real DLL.
  `Veyq` is the app (WinUI 3, unpackaged, self-contained, `win-x64`; its assembly `Veyq.App`): `App.xaml.cs`,
  `MainWindow`, one class per screen (`SignInView`, `ShellView` with Search, `HomeView`, `PotPage` with its download),
  `AvatarsHeld` (`Held<T>`, no control, Linux's `avatars::Held` rule for rule; `AvatarsTest`) and `Avatars` (decodes
  each picture through `BitmapImage`, sets it on every `PersonPicture` of that id), `Act` (sends a
  call, logs its answer), `Words`/`MrtWords` and `SaidNotice` (the words), `Said` (money, days and sizes the system's
  way), `CreateAction`, `Log` (`%LOCALAPPDATA%\veyq\log.txt`), and in a debug build `DevLogin` and `DebugEnv`
  (`VEYQ_THEME`, `VEYQ_LANGUAGE`). `Veyq.Tests` holds the words and formats against the app's `.pri`. `Veyq.Drive` is the
  pass's driver on the Windows host (UI Automation; its steps in `Program.cs`). Packages are central and exact
  (`Directory.Packages.props`), each project's `packages.lock.json` committed and every restore `--locked-mode`,
  nuget.org the only source (`nuget.config`), the SDK in `global.json`. A project must set no property that implies an
  SDK-versioned package (trimming, AOT, `IsAotCompatible`). `run.py` is its one entry point (§ Prove your work).

- `words/` — every word the apps show (ADR 0127 decision 3): `words.json` (English and German, `{n}` / `{n:int}`
  placeholders, plurals by CLDR category) is the one source; `generate_words.py` writes the core's `Msg` keys
  (`:core:generateWords`), Android's resources and `Context.words` (`:android:generateWords`; the app has no
  `strings.xml` of its own) and the Apple String Catalog `Words` (the project's `preGenCommand` and pre-build step;
  `say(msg, …)`, `say(notice)`), the Linux app's gettext catalogs with `words.rs` (`--gettext`, which `run.py build`
  runs; `build.rs` compiles each `.po` with `msgfmt --check`) and the Windows app's `.resw` catalogs with `Words.g.cs`
  (`--resw`, which `windows/run.py build` runs; a plural is one entry per CLDR category, `key#one`, and each catalog
  names its `_language`). A word is added there, never in a platform; what the
  core raises is a `Notice` (`words/Notices.kt`). `apple_en` / `apple_de` give a key Apple's own form (Title Case on a
  button or menu entry). `scripts/validate_mobile_words.py` holds it: no `strings.xml`, no `String(localized:`, no
  English literal handed to a SwiftUI view, to a GTK widget's shown text or to a WinUI control's (C# or XAML), no `.po`
  under `linux/`, no `.resw` under `windows/`. A test of the
  words asserts a language whose words differ from the fallback: English is gettext's `msgid`, so an English-only run
  passes over a catalog that never loaded.
- `theme/generate_theme.py` — the web's colour roles and brand mark for every platform (ADR 0123 decision 7): the
  builds run it (`:core:generateTheme`, `:android:generateThemeRes`, the Apple project's pre-build step, and `--gtk`,
  libadwaita's `style.css`, the icon and the desktop entry, in `run.py build`; `--winui`, WinUI's `Theme.xaml` and
  `veyq.ico`, in `windows/run.py build`); its output is never committed. The icons render on the Mac (Quick Look), so
  the `.ico` travels to the Windows host with the tree. The Linux icon resolves through the GResource's `icons/`, which
  GtkApplication adds to the icon theme (`theme::use_app_icon` says so on stderr); the Windows exe carries the `.ico`
  as `ApplicationIcon` and the window sets it with `AppWindow.SetIcon`. A colour or the logo changes in `src/web/`,
  never here.

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
  (`PotWritesTest`, over real seals), of `EntryForm.kt` (`EntryFormTest`, over the vectors), of `ExchangeRates.kt` (`ExchangeRatesTest`), of `Currencies.kt` (`CurrenciesTest`: the web's `Intl` codes and digits), of `Receipts.kt` and core's `blob/Sniff.kt` (`ReceiptsTest`), of `PotShare.kt` (`PotShareTest`), of core's `session/LivePoll.kt` (`LivePollTest`, on virtual time), of core's `session/MemberAvatars.kt` (`MemberAvatarsTest`) and `session/ShareNames.kt` (`ShareNamesTest`), of `PotView.kt`'s people, balances and entries, an entry row's line and badge among them (`PotViewPeopleTest`, `PotViewEntriesTest`), of core's `session/NodeEpochWrite.kt` and `session/NodeMetaWrite.kt` (`NodeEpochWriteTest`, `NodeMetaWriteTest`), of
  core's `session/NodeMove.kt` browser rules, a guest's folder's included (`NodeMoveTest`), of `navigation/NodeHeader.kt`'s plan and
  its narrowing in a guest's folder (`NodeHeaderTest`), of core's `session/CoverFrame.kt` (`CoverFrameTest`), of core's `export/NodeExport.kt` archive (`FolderExportTest`, `ZipTest`) and its streamed `export/Sha256.kt` (`Sha256Test`), of Depot's `depot/FolderUpload.kt` plan (`FolderUploadTest`), of what Depot's rows and file page say (`DepotWordsTest`), of Memories' `memories/MemoryMedia.kt` listed tier and order (`MemoryMediaTest`) and `memories/MediaDetails.kt` (`MediaDetailsTest`, `MediaDetailsShownTest`), of core's `blob/RangeReader.kt` (`RangeReaderTest`), of core's `blob/FileSink.kt`, `FileSource.kt` and `Files.kt` (`FilesTest`, `nativeTest`: on macOS, Linux and Windows, through a non-ASCII name, the directory listed by the platform's own calls), of core's `session/AvatarsModel.kt` (`AvatarsModelTest`), of core's `blob/UploadQueue.kt` (`UploadQueueTest`, the web's `queue.test.ts` rules on virtual time), of core's `session/LinkForm.kt` (`LinkFormTest`: the new-link form's rules) and `session/LinkRow.kt` (`LinkRowTest`: a link's row and what a member may do with it), of Expenses' `expenses/ShareInbox.kt` (`ShareInboxTest`: shared files from arrival to their Pot), of core's `navigation/ShellModel.kt` (`ShellModelTest`: the shell after a read and after a folder on Home's path changed), `navigation/TrashSheet.kt` (`TrashSheetTest`: a space's trash sheet) and `navigation/ShellWords.kt` with `AppKinds`' words (`ShellWordsTest`), of core's `session/SessionModel.kt` (`SessionModelTest`: the start, sign-in, unlock, switcher and sign-out over a door the test plays), of core's `words/Notices.kt` (`NoticesTest`: every outcome's key with as many arguments as its words take), of core's `media/ExifReader.kt` and `media/VideoHeader.kt` and Memories' `memories/MediaMetadata.kt` (`MediaMetadataFixtureTest` over the web's own fixtures, held to what the web's `readExif.fixture.test.ts` and `videoHeader.test.ts` expect; `MediaMetadataNativeTest` on the Apple path; `XmpValueTest`: its XMP reads across line breaks), of `memories/MediaUpload.kt`'s type and core's `crypto/BlobMeta.kt` digest (`MediaUploadTest`), of Depot's `depot/FileView.kt` and core's
  `blob/Sniff.kt` and `blob/DownloadName.kt` (`FileViewTest`), and of core's `session/NodeRekey.kt` frontier and what it
  leaves to another device (`NodeRekeyRulesTest`).
- `./gradlew :bridge:jvmTest` — the bridge (ADR 0123's Consequences, the bridge):
  - `DispatchTest` covers the way in, over a door the test plays: a reader read backwards in uneven ranges and closed
    with the tenant, a picture's bytes and its refusal.
  - `WireTest` writes and reads back a sample of every variant of every sealed type the wire reaches. The samples go
    to `build/generated/bridge/samples`, which the Rust test reads.
  - `SecretFreeSchemaTest` checks that nothing the core sends has a field named like a key, seal, token, secret or
    password, and that no byte array travels in JSON (a field, a list's element, a map's value).
  - `BridgeHeaderTest` holds `bridge.h` against Kotlin/Native's headers, Linux's and mingw's.

  A root or sealed variant added to the wire is in the samples by derivation. With `VEYQ_LIVE` set,
  `--tests '*LiveBridgeTest*'` signs in through a `Call` and reads the shell, Home's groups, Search and every seeded
  Pot over the real core, the principal's picture through `Avatar.Picture` (its SHA-256 the core's own read's), and
  downloads Amsterdam through `Pot.Download`, every payload to the manifest. It is the only proof of the shell's, Search's and the Pot's dispatch, so a change there
  runs it live.
- `python3 linux/run.py <verb>` — everything Linux. From the repository root the launcher runs the same verbs as
  `./gradlew linux:coreTest`, `linux:bridge`, `linux:build` and `linux:run`. The Kotlin is cross-compiled here by this
  build's Gradle, and the rest runs in the Fedora 44 image (Docker). Each verb is in `run.py`'s docstring:
  - `test [--tests <pattern>]` runs the core's Linux test binary. That is the whole common suite, the conformance over
    libsodium, `NormalizationTest` and `WallClockTest`; any change to the suite, to `nfc`/`nfkd` or to the wall clock
    runs it. It runs inside a session bus of its own with gnome-keyring-daemon, whose login collection a password drawn
    in the container unlocks, so `LinuxDeviceKeys`' contract runs over a real Secret Service; without `--tests` the
    Secret Service probe runs twice more, without a session bus and with `libsecret-1.so.0` moved away.
  - `bridge` checks `libveyq.so`'s exports and runs the C smoke.
  - `build` generates the bridge's Rust, the words and the theme, then runs `cargo clippy -- -D warnings`,
    `cargo test` and `cargo build` (remembered: about 25 s after a Rust edit alone).
  - `run [--port N] [--devlogin]` serves the app under `gtk4-broadwayd` at `http://127.0.0.1:8095`, for the browser
    pane. `LANGUAGE=de VEYQ_LOCALE=de_DE.UTF-8` gives German, and `ADW_DEBUG_COLOR_SCHEME=prefer-dark` gives dark.
  - `lock` writes `Cargo.lock` anew.

  With `VEYQ_LIVE=http://veyq.localhost:8080`, `test --tests '*LiveSignInTest*'` and `bridge` sign in as the seeded
  principal, and `run --devlogin` signs the debug build in through its own sign-in. The backend picks the tenant from
  the Host, so the container keeps the origin's name: socat forwards it to the host. The principal comes from
  `.run/.env`, which a worktree does not have: give `VEYQ_ENV_FILE=<main checkout>/.run/.env`.
- `python3 windows/run.py <verb>` — everything Windows; from the repository root `./gradlew windows:coreTest`,
  `windows:bridge`, `windows:build` and `windows:run`. The Kotlin is cross-compiled here; what needs Windows runs on the
  Windows host over ssh (`VEYQ_WINDOWS_SSH`, by default the test VM of native-apps § Open), into `F:\veyq\` there,
  where the tree goes as a tar of this working tree. Each verb is in `run.py`'s docstring:
  - `test [--tests <pattern>]` runs the core's Windows test binary there: the common suite, the conformance over
    libsodium, `NormalizationTest`, `WallClockTest` (the host's zone must be W. Europe Standard Time). Any change to the
    suite, to `bridgedMain` or to a C library's build runs it.
  - `bridge` links `veyq.dll`, holds its export table to `bridge.h`'s six and `veyq_symbols` (`objdump -p`, here) and
    runs the C smoke there.
  - `build` generates the C#, the words and the theme, gates every NuGet package (`dotnet package list --deprecated`
    and `--vulnerable`, here), runs `Veyq.Bridge.Tests` here, and there builds the app and `Veyq.Drive` and runs
    `Veyq.Bridge.Tests` and `Veyq.Tests` as x64 processes.
  - `run [--width W --height H] [--devlogin]` builds the app, starts it in the host's logged-on session and holds the
    tunnel to the backend until Ctrl+C (run it in the background). `VEYQ_LANGUAGE` (`en-US`, `de-DE`) and `VEYQ_THEME`
    (`light`, `dark`) set a debug build's.
  - `drive <step>...` prints the app's log, then runs `Veyq.Drive`'s steps (`tree`, `invoke <id>`, `select <id>`,
    `set <id> <text>`, `key`, `type <id> <text>` and `press <id> [keys]` (a system dialog's entry and button), `size`,
    `maximize`, `wait`, `sleep`, `shot <name>.png`) and prints what each saw. Read the log and the tree first; a `shot`
    (to `build/windows/shots/`) is the proof, not the probe.
  - `register` registers the scheduled tasks `veyq-app` and `veyq-drive` once per Windows host. It is the host owner's
    step: a persistent change on a machine.

  With `VEYQ_LIVE=http://veyq.localhost:8080`, `test --tests '*LiveSignInTest*'` and `bridge` sign in over WinHTTP, and
  `run --devlogin` signs the debug build in through its own sign-in; the principal comes from `VEYQ_ENV_FILE` as for
  Linux. A pass starts from a state it sets (`select home`), never the one the app reopened.
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
  in the test there twice (the second beside the first, "App up (n)"), its files sent by core's upload queue, and reads every file back to the byte; both stay. `VEYQ_LIVE_STORAGE_FIT="<root folder>/<folder>"`
  (`--tests "*DepotLiveTest.aCopyOrAFolder*"`) sets the tenant's quota to one byte on the admin listener (`VEYQ_ADMIN_BASE`,
  default `http://127.0.0.1:8090`; the seeded principal is the Server Owner), checks that folder's copy and a folder upload
  are said not to fit, and puts the tenant's quota as it was back however it ends — `VEYQ_LIVE_STORAGE_FIT_HOLD_S=<s>` (at
  most 600) holds it first, for an app's copy dialog. A run stopped by force leaves the one byte set: the next run takes a
  quota of exactly one byte for its own leftover and clears it.
  `VEYQ_LIVE_MEMORY=<memory name>` (`--tests '*MemoryLiveTest*'`) reads that Memory's gallery the web filled — every row's
  listed tier and original meta, each tile and preview (a JPEG where the web derived one) with its size and SHA-256, its
  details with the EXIF tier, and its original read whole and through the range reader backwards in uneven pieces, the
  same bytes (a video longer than one frame proves the reader: `ffmpeg` makes one, the web's gallery uploads it) —;
  `*` lists every Memory and chapter with its count instead.
  `VEYQ_LIVE_MEMORY_UPLOAD=<memory name>` (`--tests '*MemoryUploadLiveTest*'`) uploads the web's EXIF fixture photo and its
  dated video fixture there with tiers drawn in the test, and reads both back in a fresh session — capture time and its
  source, position, camera, size, each original to the byte, each tier a JPEG; both stay, for the web's gallery and
  lightbox (its details then name the camera the fixture carries).
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
  input: a run that changes only these needs `--rerun`, else the test is "up to date" and nothing runs. `--rerun` is a
  task option and binds to the one task before it: `./gradlew :core:jvmTest --rerun :core:macosArm64Test --rerun`
  (measured 2026-11-10: a single trailing `--rerun` re-ran only the last task and reported the first `UP-TO-DATE`).
- `VEYQ_LIVE=http://veyq.localhost:8080 VEYQ_SEED_TENANT="verein.localhost|Turnverein" ./gradlew :core:jvmTest --tests
  '*DevSecondTenantTest*'` — dev seeding, not a check: gives the seeded principal a second tenant (the seeder births only
  one), through the app's own suite, so the switcher has two to switch between. Skipped when it already exists.
- `./gradlew :android:assembleDebug` — the Android app; on an emulator, `python3 android/devlogin.py` signs it in
  (`--remember` staying signed in, `--unlock` with only the password for the unlock screen of a trusted account; a
  fresh start, since Android hands a running task no new extras). Apple's `devlogin.py <device>` takes the same flags.
  Neither stops a running app (an install does): one still running keeps the tree it read, so what the web changed
  meanwhile is not there — `adb shell am force-stop me.raddatz.veyq` first.
  A share to veyq (ADR 0129) is tried from a real sender — the Files app (`com.google.android.documentsui`): long-press,
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
menu on iOS. On Linux the GNOME HIG's way, as libadwaita draws it: the creating action is `create_action(…)` /
`create_menu(…)` (`linux/src/create_action.rs`) in a header bar or the sidebar, `list-add-symbolic`, never a button
overlaid on a view (GNOME has no floating action button); the same guard holds it. On Windows Fluent's way, as
Windows' own Settings and Explorer draw it: the creating action is New, the first command of the content's
`CommandBar`, built only in `windows/Veyq/CreateAction.cs`, never a button laid over a view; the same guard holds it.
WinUI's own chrome (the pane's button, back, the scroll bar) speaks the system's language whatever the app's words
are, as on every Windows app; a built-in item that shows a word of its own (`NavigationView`'s Settings) is replaced
by one carrying the catalog's.

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
- A simulator build with `CODE_SIGNING_ALLOWED=NO` carries no entitlements, so the Keychain refuses every item: the
  device trust is never enrolled (`veyq: this device could not be trusted with the account: … bind key not stored` on
  the console, measured 2026-11-10) and the next start is the full sign-in. Build for a simulator signed (the scheme's
  default, no flag); `CODE_SIGNING_ALLOWED=NO` only proves a compile.
- The macOS app runs from a build without a team: `xcodebuild -scheme Veyq -destination 'platform=macOS'
  CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= CODE_SIGN_ENTITLEMENTS=` (ad hoc). The same keychain
  refusal holds there, so every start signs in anew; the `VEYQ_DEV_*` variables `DevLogin.swift` reads, set on the
  binary's own process (`Contents/MacOS/veyq`), sign it in.
- A `TabView` in a `NavigationSplitView`'s detail showed no navigation bar on an iPad (iPadOS 26, measured
  2026-11-10): no title, no back, no header actions. The split view's detail is therefore one `NavigationStack` page
  whose places are a segmented `Picker` in the toolbar (`Bar(tabbed: false)`); its segments are an `Image` with an
  `accessibilityLabel`, since a `Label` there was read out by its symbol's name on a Mac.
- On a Mac `.primaryAction` is the toolbar's LEADING end, beside the title (an iPad's is the trailing one): a page's
  actions there take `.automatic` (`Bar.actions`). A Mac also draws a `Menu` as its own grey pop-up whatever its
  button style: a filled one is drawn in its label, with `.menuStyle(.button)` and `.buttonStyle(.plain)` (the
  sidebar's New), and `toolbarTitleMenu` shows no chevron there, so the switcher was not found (owner 2026-11-10).
- iOS 26 groups neighbouring toolbar items in one glass capsule: a segmented control beside back sat nested in back's
  (owner 2026-11-10). A control with a capsule of its own takes `.sharedBackgroundVisibility(.hidden)` and a
  `ToolbarSpacer(.fixed)` before it. A `.principal` item on an iPad takes the inline title's place but leaves its
  subtitle under it, a second time below the large title: the places' control sits `.topBarLeading` there.
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
  An emulator with a screen lock that was booted cold keeps its user locked until the lock screen is passed once: no
  app's activity resolves then (`am start` answers "Activity class … does not exist", `devlogin.py` "adb failed",
  measured 2026-11-10) — reinstalling does not help. Unlock it first: `adb shell input keyevent 82`, swipe up, `adb
  shell input text <pin>`, Enter (`adb shell dumpsys trust` then says `deviceLocked=0`).
- A tablet is tried on the phone emulator: `adb shell wm size 2560x1600` and `wm density 320` (1280 dp, expanded;
  `1600x2560` is 800 dp, medium), `wm size reset` and `wm density reset` after. While the size is changed
  `uiautomator dump` lists every Compose node as invisible (measured 2026-11-10): look with `adb exec-out screencap -p`.
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
  `doCopyTo(…)` (measured 2026-11-09: `PotCopy.copyTo`, now `duplicate`). An enum entry's Swift name takes `the` instead: the
  catalog's `COPY_HINT` is `Msg.theCopyHint` in Swift (measured 2026-11-09).
- A Swift closure handed to core is called on core's threads (after a request answered, Ktor's), never only on the main
  one: written in the `@MainActor` model it is inferred main-actor isolated, and Swift 6 traps on the first call from
  elsewhere (`_dispatch_assert_queue_fail`, measured 2026-11-09: the re-key's rekey id). Mark it `@Sendable` and reach
  nothing of the model in it (`AppModel.tokenInputFor` is `nonisolated static` for that).
- A `SurfaceView` inside a Compose `Dialog` got no surface (measured 2026-11-09, the lightbox's video: the player stayed
  unprepared, black): draw a player there into a `TextureView`.
- Android's photo picker hands a photo over with its GPS zeroed and under a name of its own (`34.jpg`, measured
  2026-11-09: the bytes the same size, the position 0/0, which the reader drops). The app therefore asks once for full
  media access (`READ_MEDIA_*` and `ACCESS_MEDIA_LOCATION`, one system dialog) and, where it was given, reads the media
  store's item of the picked one's id with `setRequireOriginal` (`AppModel.pickedMedium`): place and name kept. "Limited
  access" keeps the picker's copy. `adb shell pm revoke me.raddatz.veyq android.permission.READ_MEDIA_IMAGES` (and the
  others) plus clearing the app's data asks again.
- A value a sheet shows that its presenter loads after the sheet opened did not reach it on iOS (measured 2026-11-09, the
  lightbox's uploader): the sheet loads what it shows itself, in its own `.task`.
- The Simulator tool's tap does not reliably flip a SwiftUI `Toggle`: a short swipe across it does (measured
  2026-11-09, the report's good-faith switch). A Compose radio or checkbox row is tapped as the row only once the row
  is `selectable`/`toggleable` (`ReportSheet`): a bare control's label takes no tap, for a person either.
- Every file of `apple/Veyq/` but `VeyqApp.swift` and `DevLogin.swift` is also compiled into the share extension, which
  builds with `APPLICATION_EXTENSION_API_ONLY`: no `UIApplication.shared` or other API an extension may not use there,
  and nothing the app alone may do (moving or removing what it keeps) without `AppModel.inExtension` guarding it.
- On iOS what the share extension needs is shared (ADR 0129): defaults go through `Shared.defaults` (the app group's
  suite), never `UserDefaults.standard`, and a Keychain item through `KeychainStore` (the entitlements' first group is the
  shared one). A value only the app reads may stay its own, but nothing the session or the device trust reads.
- Linux's C libraries (libsodium, utf8proc) are compiled by Kotlin/Native's own toolchain. It is downloaded by
  `:core:downloadKotlinNativeDistribution`, which `buildLibsodium` and `buildUtf8proc` depend on, and found by
  `core/konan_toolchain.py` through `konan.properties`. It is never a container's compiler, since a library built
  against a newer glibc carries versioned symbols the link against Kotlin/Native's old sysroot cannot satisfy.
- `kotlin.mpp.enableCInteropCommonization=true` (`gradle.properties`) is what lets the shared native source sets see
  the `sodium` and `utf8proc` interop. Without it every per-target compile stays green and the metadata compiles
  (`:core:compileNativeMainKotlinMetadata`, what the IDE and `:bridge` read) are red. Gradle prints "CInterop
  Commonization Disabled" at configure time.
- A C string API reads to the first NUL: utf8proc's `utf8proc_NFC` cut a name at an embedded U+0000, so
  `Nfc.bridged.kt` calls `utf8proc_map` with the length.
- `libveyq.so` needs `libcrypt.so.1` (Ktor's statically linked libcurl), which Fedora 44 has only with
  `libxcrypt-compat`. The `Containerfile` installs it; a Flatpak runtime must carry it.
- libsecret is never linked: `core/secret/veyq_secret.h` `dlopen`s `libsecret-1.so.0` and declares the few types and
  functions it calls itself, so `libveyq.so` has no `NEEDED` entry for it and runs without it (`LinuxDeviceKeys` then
  keeps nothing). `dlopen` is libdl's in Kotlin/Native's sysroot (glibc 2.19/2.25), which Kotlin/Native links already.
  A Flatpak runtime must carry libsecret for staying signed in.
- gnome-keyring-daemon in the container aborts as root ("error dropping process capabilities") unless the container
  may lock memory (`--cap-add=IPC_LOCK`), and `--unlock` creates the login collection only where
  `~/.local/share/keyrings` exists; otherwise it asks gcr's prompter, which has no display there (measured 2026-11-11).
  `run.py test` does both.
- The bridge library is linked with `-Wl,--exclude-libs,ALL`. Without it, libcurl's, OpenSSL's, libsodium's and
  utf8proc's symbols are exported (11,348, measured 2026-11-10), and a host that loads its own libcrypto binds to
  ours. `run.py bridge` is red on any symbol beyond `bridge.h`'s six and Kotlin/Native's own.
- A pointer the bridge hands to a callback is valid only during that call: the host copies what it keeps. The core
  copies what the host hands in before it returns (`bridge.h`, `Exports.kt`). An answer or update arrives on a core
  thread; the Rust side reaches a widget only from GTK's main loop, through `async_channel`.
- glibc's gettext ignores `LANGUAGE` under a C locale, and C.UTF-8 is C. Under `LANG=C.UTF-8 LANGUAGE=de` every word
  was English (measured 2026-11-10). The image carries `glibc-langpack-en`/`-de`, and `run.py` sets
  `LANG=en_US.UTF-8`.
- kotlinx-datetime on Linux reads the zone from `/etc/localtime`, not from `TZ`. With only `TZ` set it read UTC
  (measured 2026-11-10). `run.py test` links the container's `/etc/localtime` to Europe/Berlin and sets `TZ` for glibc.
- The process's locale is one for every test thread: a Rust test that sets `LC_ALL` or `LC_MONETARY` holds
  `crate::LOCALE` while it does.
- Money in the Linux app is glibc's locale data, which knows only the locale's own currency symbol. Any other currency
  is written by its code (`EUR 1,234.56` under en_US), where Android and Apple write `€1,234.56` (native-apps § Open).
- `include!` takes no inner attribute, so the generated `bridge.rs` carries none. The app's `wire` module holds the
  lints' allowances.
- Broadway in the browser pane takes a click only after the pointer has moved there: hover first, then click.
  `computer type` reaches no GTK entry, but `computer key` with space-separated letters does; a password is never
  typed, `run --devlogin` signs in. A hidden pane lags Broadway's frames, so a screenshot can show the state before the
  last action: hover before each one, and read the state from the app's stderr first. A Broadway window keeps its own
  size until it is maximized once; after that a viewport resize moves it across the 600 sp breakpoint live.
- A new dependency or target owes the verification-metadata write from an empty Gradle home
  (`GRADLE_USER_HOME=<empty dir> ./gradlew --write-verification-metadata sha256 <the tasks that resolve it>`). The
  Linux targets' warm write missed 90 lines of parent POMs and BOMs that a fresh runner fetches. A new module that
  resolves nothing new still runs it once, as evidence that the file stays unchanged. Seed that empty home with the
  wrapper's Gradle distribution alone if its download times out: it is no dependency. After any build-script edit run
  one task with `--warning-mode all`: the build says only "deprecated features were used" (`val bridgedMain by
  getting` was one, for Gradle 10).
- What Linux and Windows share is `bridgedMain` (`bridgedTest`), between `nativeMain` and `linuxMain`/`mingwMain`, in
  `core` and `:bridge` alike: what was `linuxMain`'s (`nfc`/`nfkd`, the wall clock, `Exports.kt`) lives there, and each
  target's own set brings only its Ktor engine. Its metadata compiles (`:core:compileBridgedMainKotlinMetadata`) are the
  check that the interop reaches it, not the per-target compiles.
- On mingw, libsodium's `configure` finds no `_xgetbv` under clang without `-mxsave`, so it never reads XCR0, finds no
  AVX on any CPU and refuses AES-256-GCM at runtime — silently, until the conformance test. `build_libsodium.py` adds
  the flag and fails unless `config.log` says the intrinsic was found (ADR 0123's Consequences, the core on Windows).
  Kotlin/Native's LLVM has no `llvm-ranlib`: `RANLIB` is `llvm-ar s`.
- A cinterop task packs the static library into its klib without taking it as an input: a rebuilt `libsodium.a` left
  the old one inside, and the test binary red, until `--rerun` (measured 2026-11-10). `core/build.gradle.kts`'s
  `libraryInput` declares each `sodium` and `utf8proc` library its interop's input; a new interop over a static library
  does the same.
- `veyq.dll` exports only `bridge.h`'s six and `veyq_symbols` because Kotlin/Native's own `veyq.def` is handed to the
  linker; without it lld exports every symbol of the link (185, measured 2026-11-10), and `--exclude-all-symbols`
  removes the six too. `run.py bridge` is red on anything else. The smoke's import library is written from that `.def`
  with a `LIBRARY veyq.dll` line added, else the exe imports from a DLL with an empty name.
- The app's assembly is `Veyq.App`, never `veyq`: a .NET app's own assembly is `<AssemblyName>.dll` beside its exe, and
  as `veyq` it overwrote the core's native `veyq.dll` in the build output. The app then found no `veyq_init` and died in
  `combase.dll` (`0xC000027B`) with nothing in its log (measured 2026-11-10).
- The taskbar keeps an exe's icon by its path: `F:\veyq\app\Veyq.App.exe` ran without an icon first, and after the icon
  came the title bar showed it but the taskbar kept the default one until the user's icon cache was cleared
  (`ie4uinit.exe -show`, then the app started anew; measured 2026-11-11). A changed icon is checked that way, never
  taken as missing.
- A property that implies an SDK package makes the lock file the SDK's: `IsAotCompatible` added
  `Microsoft.NET.ILLink.Tasks` at the SDK's runtime version, and the VM's locked restore under another SDK was red
  (NU1004). The first locked restore of a new C# project runs on both hosts.
- An ssh command on Windows runs in session 0, which has no desktop: an app started from it crashes at once
  (`0xC000027B`, WER `80070005`). The app and `Veyq.Drive` start in the logged-on session through the scheduled tasks
  `register` sets up. Over ssh `$env:USERDOMAIN` reads `WORKGROUP`, which maps to no SID (`0x80070534`), so `register`
  names the account by `[Security.Principal.WindowsIdentity]::GetCurrent().Name`.
- `run.py` sends every PowerShell script as `-EncodedCommand` (UTF-16LE, base64), so nothing is quoted through cmd.exe,
  and reads what PowerShell prints as the console's code page, CP850 on the German VM. `dotnet` writes UTF-8 there, so
  `run.py` sets its words English (`DOTNET_CLI_UI_LANGUAGE`).
- Windows resolves no `*.localhost`. The Windows host reaches the Mac's dev backend through `ssh -R`, and its `hosts`
  file maps `veyq.localhost` and every seeded tenant's name to `127.0.0.1`; the backend picks the tenant from the Host
  header, which WinHTTP sends from the URL. A new seeded tenant needs its line there.
- `StrFormatByteSizeEx` ignores the thread's culture (`SetThreadUILanguage` and `SetThreadLocale` changed nothing,
  measured 2026-11-10): a size is in the user's culture only, and a test asserts it in the host's.
- `VEYQ_THEME` sets the application's theme, not only the window's: a brush looked up in code is the application
  theme's, and under the window's dark alone a row's caption stayed light's, dark on dark (measured 2026-11-11).
- UI Automation, as the driver sees it: a `NavigationViewItem` has no Invoke, only SelectionItem, so a pane item is
  chosen by its selection (`SelectionChanged`); a chosen Search hit arrives in `QuerySubmitted` as its row's content,
  not the `ListViewItem` (both carry the hit as `Tag`); a flyout, a menu and a suggestion list are top-level windows
  of their own, which `Veyq.Drive` searches too.
- A width-capped column straight in a `ScrollViewer` sits off centre and clips (the Pot's amounts were cut off, measured
  2026-11-11): a list fills the width, a capped column sits inside a stretching `Grid`. Under LeftMinimal the pane's
  button and back lie over the content's top left, so the title takes a left inset there (`DisplayModeChanged`). Two
  defects of this kind showed only in a screenshot, never in the tree: take each form's shot early.
- A XAML comment may not contain `--` (WMC9997), and generated C# carries its own `#nullable enable` (CS8669).
- mingw's `fopen` reads its path in the ANSI code page: a UTF-8 name is not refused but written under mojibake
  (`Büro € 中文.bin` became `BÃ¼ro â‚¬ ä¸­æ–‡.bin` on the German VM, measured 2026-11-11), and a round trip through the
  same call passes. The core therefore opens through `_wfopen`/`_wremove` over UTF-16 (`Files.mingw.kt`), and
  `FilesTest` lists the directory through Windows' own wide calls, never the calls under test.
- A payload beside the JSON: NULL means none, a non-NULL pointer with length 0 an empty one (a range past a blob's
  end). `Exports.kt` hands an empty payload as a pointer to a byte never read; both hosts' copy tests hold `Some([])`
  / an empty array apart from NULL.
- `type` is the wire's class discriminator: kotlinx.serialization refuses a variant with a field of that name, so a
  media type on the wire is `mediaType`.
- WebP, the web's picture format, is not every platform's own. GTK 4.22 decodes it through glycin's loaders
  (`glycin-loaders`, which gtk4 pulls in Fedora; without them "No image loaders are configured" and every person shows
  initials), so a Flatpak's runtime must carry them. Windows decodes it through the Store's Webp Image Extensions, not
  the OS image's WIC: without the package the decode throws (`COMException`), so MSIX must depend on it. Its
  registration needs the logged-on session; over ssh it is refused (`0x80070005`).
- GTK's own file dialog in the Fedora container speaks English under `LANGUAGE=de` (Home, Cancel, Save): the image
  carries no gtk4 catalogs; only our words in it (its title) are German. On a desktop the portal's dialog is the
  system's. The container's working directory is the read-only source mount, where GTK's dialog opens — a write there
  is the failure case, not a bug.
- The container runs with `--rm`: what the app keeps on the device lives in the container and is gone at the next
  `run.py run`, Recent with it. A pass that shows Recent opens its objects first in that run (remembered from the
  bridge's Linux pass, 2026-11-11).
- The common file dialog on Windows cannot be pressed through UI Automation: its Save invoked by UIA arrives as an
  input-synchronous call and the dialog cannot reach the file broker (`RPC_E_CANTCALLOUT_ININPUTSYNCCALL`, nothing
  written), and a file name set as a UIA `Value` shows in the tree but is not read. `Veyq.Drive`'s `type` and `press`
  focus the element and send real keys (`SendInput`). The picker is a window of the app's own process.
- `PersonPicture` is not in UIA's control view: a tree tells no picture from initials. The app's log says each decode
  (`a member's picture decoded: <bytes> B, <w>×<h>`); a shot is the proof.
- A picture replaced under the same user id keeps the id set, so no update tells a host to ask again. Nothing reads
  the people anew yet; the first caller of `AvatarsModel.peopleRead` owes the hosts that signal (a revision per id,
  say; native-apps § Open).
- Only the shell's `ShellShown.pictures` feeds a host's picture cache. `Update.Pot.pictures` is empty while no Pot is
  open, so feeding it would drop every picture held and ask them all again at the shell's next update.
- A watch delivers a state once per change. A test that waits on one watch for one fact misses a later fact that
  arrived meanwhile (the pictures' update came while `LiveBridgeTest` waited for the open object): read a fresh watch,
  which delivers the state as it is now.
- An app is refused at sign-in by a backend of another major (`server major 1, app 0`): a branch whose `VERSION` lags
  the running dev backend's cannot run a live pass until `main` is merged into it.
- A download's failure on Windows is shown with a nearly full volume (a small FAT VHD filled to one free cluster): the
  common file dialog itself refuses a folder the user cannot write to before the core is called (remembered, not
  measured), so Linux's read-only folder has no counterpart there.
