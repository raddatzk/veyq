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
  core attachment, and a file's page as a screen model, `FileModel`: what it shows decided by the bytes, an image, a text
  and a PDF held in memory, a medium read through a `RangeReader`, never a decrypted file written to show one; its "Save a
  copy", `FileSave` (written a frame at a time where the person chose, its progress, cancelled with nothing left); the header's
  file actions and the opened file's rights, `FileRights`; the uploads as the shell shows them, `DepotUploads` (files and a
  folder into a level, a replace expected over the content shown when it was chosen, each item's settle and its replace
  offer said once, the tray's acknowledge on the model's clock), a walk's rule `FolderUpload.keeps`, a drop box's page
  `DropBoxPage` (read anew on each revision of the shell's lists, a folder's delete only after asking) and the top level's
  "Add files only" `ShareSheet.topLevel`; `memories/`: a Memory's gallery, each medium under the key its origin
  fixes), which
  uses core's session and is never used by it; an application's spec is generated into `api.<application>`.
- **The device trust** ([ADR 0123](../../docs/decisions/0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md)
  decision 8; what each store guarantees is security-model § What a trusted device rests on): the flows are core's
  (`session/Account.kt`, `SessionModel.kt`: every sign-in enrols the password trust, `Gate.Signed.offer` offers staying
  signed in at each level the store has, `stay(level)`, `notNow()`, Manage's `stay(account, level)` and
  `trustWithPassword`), over the `DeviceKeys` interface (`session/DeviceTrust.kt`; `StaySignedIn` is a level, `PRESENCE`
  or `LOGIN`, and `DeviceKeys.staySignedIn` the set a store offers). The stores: Android `KeystoreDeviceKeys`, Apple
  `AppleDeviceKeys` (`appleMain`) over `KeychainStore.swift`, Windows `WindowsDeviceKeys` (`mingwMain`, the cinterop
  `core/wincred/`: DPAPI and the Passport key storage provider), Linux `LinuxDeviceKeys` (`linuxMain`, the cinterop
  `core/secret/`: libsecret `dlopen`ed, never linked). `DeviceKeysContract` (`commonTest`) is what every store meets,
  read only through the interface: a new store gets a subclass of it, run on its platform. `SoftwareDeviceKeys`
  (`core/src/testDevice/`, compiled into core's `commonTest` and `:bridge`'s `jvmTest`) is the test store; its
  `prompts`/`authenticates` counters are the flows' tests' extras, not the contract.
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
  the path the host's dialog gave, which the core opens (`Pot.Download` and `Receipt.Save` through `DeviceFiles`, core's
  `FileSink`; `Editor.AddReceipts` reads each picked path, never more than `Receipts.MAX_BYTES`, through `FileSource`).
  A receipt shown is `Receipt.Open`'s payload, decoded in memory and never written to disk. A file's page is `File.Open`
  (`Watched.File`: `FileShown`; an image's or a PDF's bytes `File.Bytes`, asked again when its `revision` turns), and its Save a
  copy `File.Save(file, path)` → `Saving(save)`, followed by `Watched.Save` and stopped by `Save.Cancel`, which answers once
  the partial file is gone; a save outlives its page and stops with its tenant.
  The generated layer copies a payload once (Rust `Option<Vec<u8>>`, C# `byte[]?`); no screen touches a pointer.
- `linux/` — the Linux app (ADR 0127 decision 4): a Cargo package in Rust (gtk4-rs, libadwaita-rs), with `Cargo.lock`
  committed and every cargo run `--locked`. `src/lib.rs` is the library: `bridge` (the safe wrapper over the six
  functions, the host function), `words`, `said` (money, days and sizes the desktop's way), `theme`, `create_action`
  and one module per screen (`sign_in` with the unlock, `shell` with Search, `home`, `pot` with its download,
  `pot_settings` (a Pot's Settings place, editable in place) and `pot_create` (New pot),
  `entry_editor` (an entry's editor; `typed`: when a text field is set from the state), `receipt_viewer` (a receipt
  shown from memory: a picture through GTK, a PDF through poppler-rs) and `entry_history`, `file_page` (a Depot file's page
  and its Save a copy with its toast), `manage`
  for "Manage servers"; `header` with `trash`, `move_sheet`, `share_sheet`, `report` and `cover` for a node's header and
  its sheets; `dev_login` in a debug build only), and `avatars` (`Held`, no widget and tested: the open
  tenant's pictures, asked for again only when the shell's id set changes; drawn as `adw::Avatar`).
  `src/main.rs` is the app. `build.rs` reads the generated bridge, words and theme from `VEYQ_GENERATED` and the
  library from `VEYQ_LIB_DIR`, both set by `run.py`. `Containerfile` (Fedora 44 by digest) is its build and run
  environment. `run.py` is its one entry point (§ Prove your work); `run.py lock` is the one writer of `Cargo.lock`.
  Crates are held to the no-deprecated rule by reading each one's repository: cargo has no deprecation gate.
- `windows/` — the Windows app (ADR 0127 decision 5; ADR 0123's Consequences, the Windows app): .NET 10 projects in C#.
  `Veyq.Bridge` (net10.0, no Windows API) is the safe wrapper over the six functions, the host's device store under
  `%LOCALAPPDATA%\veyq\`, and the hand-off to the UI thread; `Veyq.Bridge.Tests` its round trip and the real DLL.
  `Veyq` is the app (WinUI 3, unpackaged, self-contained, `win-x64`; its assembly `Veyq.App`): `App.xaml.cs`,
  `MainWindow`, one class per screen (`SignInView`, `ShellView` with Search, `HomeView`, `PotPage` with its download;
  a node's header `NodeHeader` with `HeaderPlan`, and its sheets `TrashDialog`, `MoveDialog`, `ShareDialog` with
  `ShareDays`, `ReportDialog`, `CoverDialog`, every `ContentDialog` shown through `Dialogs.Show`),
  `AvatarsHeld` (`Held<T>`, no control, Linux's `avatars::Held` rule for rule; `AvatarsTest`) and `Avatars` (decodes
  each picture through `BitmapImage`, sets it on every `PersonPicture` of that id), `Act` (sends a
  call, logs its answer), `PotSettingsView` (a Pot's Settings place, editable in place), `PotCreateDialog` (New pot),
  `FilePage` (a Depot file's page; its Save a copy's `InfoBar` is `ShellView.Saving`), `ListScrolls` (where each list was
  scrolled to; `ListScrollsTest`), `EntryEditorPage` (an entry's editor, a page in place of the Pot's places) with
  `ReceiptViewerDialog` and `PdfPages` (a receipt's PDF through `Windows.Data.Pdf`, from memory; `PdfPagesTest`), `TypedText`
  (when a text box is set from the state; `TypedTextTest`), `Words`/`MrtWords` and `SaidNotice` (the words), `Said` (money, days and sizes the system's
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
  (`PotWritesTest`, over real seals), of `EntryForm.kt` (`EntryFormTest`, over the vectors), of `ExchangeRates.kt` (`ExchangeRatesTest`), of `Currencies.kt` (`CurrenciesTest`: the web's `Intl` codes and digits), of `Receipts.kt` and core's `blob/Sniff.kt` (`ReceiptsTest`), of `PotShare.kt` (`PotShareTest`), of the Pot's screen models over a Pot the test plays (`PotFixture`, `commonTest`) — `expenses/EntryEditor.kt` (`EntryEditorTest`), `PotModel.kt` with its record, restore, live poll and `PotSettings` (`PotModelTest`), `PotCreateSheet.kt` (`PotCreateSheetTest`), what a receipt is shown as (`ReceiptsShownTest`) and every amount said with its currency (`PotViewMoneyTest`) —, of core's `session/LivePoll.kt` (`LivePollTest`, on virtual time), of core's `session/MemberAvatars.kt` (`MemberAvatarsTest`) and `session/ShareNames.kt` (`ShareNamesTest`), of `PotView.kt`'s people, balances and entries, an entry row's line and badge among them (`PotViewPeopleTest`, `PotViewEntriesTest`), of core's `session/NodeEpochWrite.kt` and `session/NodeMetaWrite.kt` (`NodeEpochWriteTest`, `NodeMetaWriteTest`), of
  core's `session/NodeMove.kt` browser rules, a guest's folder's included (`NodeMoveTest`), of `navigation/NodeHeader.kt`'s plan and
  its narrowing in a guest's folder (`NodeHeaderTest`), of core's `session/CoverFrame.kt` (`CoverFrameTest`), of core's `export/NodeExport.kt` archive (`FolderExportTest`, `ZipTest`) and its streamed `export/Sha256.kt` (`Sha256Test`), of Depot's `depot/FolderUpload.kt` plan (`FolderUploadTest`), of what Depot's rows and file page say (`DepotWordsTest`), of Memories' `memories/MemoryMedia.kt` listed tier and order (`MemoryMediaTest`) and `memories/MediaDetails.kt` (`MediaDetailsTest`, `MediaDetailsShownTest`), of core's `blob/RangeReader.kt` (`RangeReaderTest`), of core's `blob/FileSink.kt`, `FileSource.kt` and `Files.kt` (`FilesTest`, `nativeTest`: on macOS, Linux and Windows, through a non-ASCII name, the directory listed by the platform's own calls), of core's `session/AvatarsModel.kt` (`AvatarsModelTest`), of core's `blob/UploadQueue.kt` (`UploadQueueTest`, the web's `queue.test.ts` rules on virtual time), of core's `session/LinkForm.kt` (`LinkFormTest`: the new-link form's rules) and `session/LinkRow.kt` (`LinkRowTest`: a link's row and what a member may do with it), of Expenses' `expenses/ShareInbox.kt` (`ShareInboxTest`: shared files from arrival to their Pot), of core's `navigation/ShellModel.kt` (`ShellModelTest`: the shell after a read and after a folder on Home's path changed), `navigation/TrashSheet.kt` (`TrashSheetTest`: a space's trash sheet) and `navigation/ShellWords.kt` with `AppKinds`' words (`ShellWordsTest`), of core's `session/SessionModel.kt` (`SessionModelTest`: the start, sign-in, unlock, switcher and sign-out over a door the test plays), of core's `words/Notices.kt` (`NoticesTest`: every outcome's key with as many arguments as its words take), of core's `media/ExifReader.kt` and `media/VideoHeader.kt` and Memories' `memories/MediaMetadata.kt` (`MediaMetadataFixtureTest` over the web's own fixtures, held to what the web's `readExif.fixture.test.ts` and `videoHeader.test.ts` expect; `MediaMetadataNativeTest` on the Apple path; `XmpValueTest`: its XMP reads across line breaks), of `memories/MediaUpload.kt`'s type and core's `crypto/BlobMeta.kt` digest (`MediaUploadTest`), of Depot's `depot/FileView.kt` and core's
  `blob/Sniff.kt` and `blob/DownloadName.kt` (`FileViewTest`), of Depot's `depot/FileModel.kt` with its `FileRights` and
  `ShellModel.downloadAs` (a file downloads as itself, any other node as its ZIP) (`FileModelTest`, over a file the test
  plays), of Depot's `depot/DepotUploads.kt` with `DepotFiles.offerFor` and `collision` (`DepotUploadsTest`, over a level the
  test plays, on virtual time) and `depot/DropBoxPage.kt` (`DropBoxPageTest`), and of core's `session/NodeRekey.kt` frontier and what it
  leaves to another device (`NodeRekeyRulesTest`).
- `./gradlew :bridge:jvmTest` — the bridge (ADR 0123's Consequences, the bridge):
  - `DispatchTest` covers the way in, over a door the test plays: a reader read backwards in uneven ranges and closed
    with the tenant, a picture's bytes and its refusal, a file's page and its Save a copy (`File.*`, `Watched.File`/`Save`,
    `Save.Cancel` over a played file: the bytes and type the core's, a save watched as soon as it is answered, a cancelled
    one leaving nothing, a FILE's header download the file itself), and every call of a node's header and its sheets (`Header.*`,
    `Trash.*`, `Move.*`, `Share.*`, `Report.*`, `Cover.*`), and a Pot's (`Pot.*`, `Watched.PotLive`) and its entry editor's
    (`Editor.*`, `Watched.Editor`, over a played Pot: edits applied in the order sent, a save answered once its write
    ended) with its receipts (`Editor.AddReceipts`/`DropReceipt`, `Receipt.Open`/`Pages`/`Save`: a path that does not
    open, a file past the cap read no further, the 101st refused, nothing left at a path on a failure), its settings
    (`PotSettings.*`, `Watched.PotSettings`: each change answered `Stored` once its write ended, an erase only once asked,
    a closed Pot closing them) and New pot (`PotCreate.*`, `Watched.PotCreate` over a played sheet: the name applied in the
    order sent, the members of the space chosen, the refusal in the answer), each bitten by making it a no-op.
  - `WireTest` writes and reads back a sample of every variant of every sealed type the wire reaches. The samples go
    to `build/generated/bridge/samples`, which the Rust test reads.
  - `SecretFreeSchemaTest` checks that nothing the core sends has a field named like a key, seal, token, secret or
    password, and that no byte array travels in JSON (a field, a list's element, a map's value). An address crosses
    outward only in a field it names: a share link's, a key by its `#k=` (`linkAddresses`), and a public page's
    (`publicAddresses`); a new one is added there by name, never as a blanket rule.
  - `BridgeHeaderTest` holds `bridge.h` against Kotlin/Native's headers, Linux's and mingw's.

  A root or sealed variant added to the wire is in the samples by derivation. With `VEYQ_LIVE` set,
  `--tests '*LiveBridgeTest*'` signs in through a `Call` and reads the shell, Home's groups, Search and every seeded
  Pot over the real core, the principal's picture through `Avatar.Picture` (its SHA-256 the core's own read's), and
  downloads Amsterdam through `Pot.Download`, every payload to the manifest. It is the only proof of the shell's, Search's and the Pot's dispatch, so a change there
  runs it live. Its header cases (`aFolderIs*`, `aPotIsCopied*`, `aLinkIs*`, `aCover*`) run with `VEYQ_LIVE` alone and
  clean up what they made (`aPotIsPolled*` too: an `App pot <n>` copy polled, settled and restored, then purged; `anEntryIsWritten*`: an `App pot
  <n>` copy written through the editor's calls — every kind, the split's modes, a rate filled, a refusal, a stale save, a
  delete — each read back from a fresh open, then purged; `aReceiptIs*`: an `App pot <n>` copy with a JPEG and a PDF
  attached by path, opened and saved to their SHA-256, one taken off, and with `VEYQ_LIVE_RECEIPT_NO_ROOM=1` the tenant's
  quota set to one byte for a save that names its receipt left out, put back however the run ends; `aPotIsCreated*`: an
  `App pot <n>` made by New pot in a folder of its own with a member and a typed person, set up through the settings'
  calls — currency, a category, a person to its erase, another member, the currency refused after an entry — and copied,
  the copy read back as the original, the folder with both purged); `aReport*` needs `VEYQ_LIVE_REPORT_BRIDGE=1` (it leaves one report: run it once),
  `aCopyWithoutRoom*` `VEYQ_LIVE_NO_ROOM=1` while `DepotLiveTest` holds the one-byte quota, `aFileIsShownAndSaved*` (an `App
  depot <n>` filled by the core's upload in a second session, its JPEG, text and PDF shown by call at their SHA-256, a 300 MiB
  file saved and a second save cancelled, then purged), `aDesktopPassFolder*` with `VEYQ_LIVE_DEPOT_PASS=<name>` (not a
  check: a desktop pass's Depot folder at the personal top level, filled; `VEYQ_LIVE_DEPOT_PASS_PURGE=1` purges it), and
  `theLinksOfAFolder*`
  writes a desktop pass's links out for the web's guest page — each case's doc says what it reads.
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
  - `run [--port N] [--devlogin [--fill|--unlock]] [--no-secret-service]` serves the app under `gtk4-broadwayd` at
    `http://127.0.0.1:8095`, for the browser pane. `LANGUAGE=de VEYQ_LOCALE=de_DE.UTF-8` gives German, and
    `ADW_DEBUG_COLOR_SCHEME=prefer-dark` gives dark. It runs under a session bus with gnome-keyring-daemon, its home's
    data (the device store, the keyring and the password it opens with) on the volume `veyq-linux-home`, so a second
    start resumes what the first trusted; `docker volume rm veyq-linux-home` starts from nothing. `--no-secret-service`
    runs without both (nothing offered after the sign-in, every start signs in). `--fill` only fills the sign-in, for
    a pass to press it; `--unlock` hands the password alone to a password-trusted account's unlock and fills Manage's
    way back to the password once (the pass presses its button). The app's stderr says `session <gate>, offer: […]`,
    `offer answered: …` and `manage …: Done`/`Refused …`: read it before each shot.
  - `lock` writes `Cargo.lock` anew.

  With `VEYQ_LIVE=http://veyq.localhost:8080`, `test --tests '*LiveSignInTest*'` and `bridge` sign in as the seeded
  principal, and `run --devlogin` signs the debug build in through its own sign-in. The backend picks the tenant from
  the Host, so the container keeps the origin's name: socat forwards it to the host. The principal comes from
  `.run/.env`, which a worktree does not have: give `VEYQ_ENV_FILE=<main checkout>/.run/.env`. A pass that moves a
  blob (a cover, a receipt) sets `VEYQ_LIVE_STORE` to the object store's public endpoint, a dev instance's Vite origin
  (`http://veyq.localhost:8081`), whose port is forwarded too; without it no blob goes up from the container.
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
  - `run [--width W --height H] [--devlogin [--fill|--unlock]]` builds the app, starts it in the host's logged-on
    session and holds the tunnel to the backend until Ctrl+C (run it in the background). `VEYQ_LANGUAGE` (`en-US`,
    `de-DE`) and `VEYQ_THEME` (`light`, `dark`) set a debug build's. `--fill` and `--unlock` are Linux's. The log says
    `session <gate>, offer: …`, `offer answered: …`, `level: …` beside an unlock and `manage servers: <method per
    server>`.
  - `hello-probe [--check] [--timeout S]` runs `WindowsDeviceKeys`' Hello half (`mingwTest`'s `HelloProbe`) in the
    logged-on session: a seal and an open each ask for the PIN, which **the person at the host's console enters — an
    agent never types one**; then an open within the 15 s window (not asked), one after it (press Cancel: `cancelled =
    true`), and the key deleted. It waits for the probe's process, never a clock sized for a machine, and fetches the
    outcome last (`build/windows/hello-probe.txt`). `--check` stops before the first key: Hello's availability in that
    session, no prompt. Brief the person on every prompt the run may raise first, the uncertain ones marked.
  - `drive <step>...` prints the app's log, then runs `Veyq.Drive`'s steps (`tree`, `invoke <id>`, `select <id>`,
    `set <id> <text>`, `key`, `type <id> <text>` and `press <id> [keys]` (a system dialog's entry and button), `scroll <id>
    [percent]` (a ScrollViewer through UIA's ScrollPattern), `size`,
    `maximize`, `wait`, `sleep`, `shot <name>.png`) and prints what each saw. Read the log and the tree first; a `shot`
    (to `build/windows/shots/`) is the proof, not the probe.
  - `register` registers the scheduled tasks `veyq-app` and `veyq-drive` once per Windows host. It is the host owner's
    step: a persistent change on a machine.

  With `VEYQ_LIVE=http://veyq.localhost:8080`, `test --tests '*LiveSignInTest*'` and `bridge` sign in over WinHTTP, and
  `run --devlogin` signs the debug build in through its own sign-in; the principal comes from `VEYQ_ENV_FILE` and a
  blob's way from `VEYQ_LIVE_STORE` (tunnelled beside the backend's port) as for Linux. A pass starts from a state it
  sets (`select home`), never the one the app reopened.
- `VEYQ_LIVE=http://veyq.localhost:8080 ./gradlew :core:jvmTest :core:macosArm64Test --tests '*LiveSignInTest*'` —
  signs in to a running dev instance as the seeded principal on both paths and reads its spaces, objects and
  favorites (`./gradlew backend:seed` at the repository root first). With `VEYQ_LIVE_MEMBER=1` as well,
  `--tests '*MemberLiveSignInTest*'` signs in as the seeded second member (`VEYQ_USER2_*`), whose TENANT grant the
  owner signed, so it opens only through the key check; the owner starts that run, since that password may be one a
  person chose. `--tests '*DeviceTrustLiveTest*'` walks the device trust (ADR 0123 decision 8) as the seeded principal:
  the password trust, the login level (nothing asked), a resume, the presence level (the login wrap gone), a resume
  asked once and a cancel, the way back to the password trust (a wrong password refused), the register's join,
  sign-out; it removes what it enrolled. The core's JVM tests read the principal from `VEYQ_ENV_FILE` (a worktree:
  `<main checkout>/.run/.env`); with a missing file they are red, which tells a live run from a skipped one. A
  store change runs it with `:bridge:jvmTest --tests '*LiveBridgeTest*'` (the same walk through the bridge's calls).
  `--tests '*PotLiveTest*'` opens every seeded Pot and checks its revisions open, its ledger check passes, its balances
  sum to zero and its space's members read by name; with `VEYQ_LIVE_POT_WRITE=<pot name>` it adds one expense there
  ("App check"), which the web's Pot page then shows — the check that both clients read and write one format. It leaves
  that entry behind in the dev instance. `VEYQ_LIVE_POT_PEOPLE=<pot name>` adds a person there, renames, deactivates and
  removes them (only the erasure record stays); `VEYQ_LIVE_POT_SETTINGS=<pot name>` (a Pot without money entries) switches its
  currency and back and adds an own category ("App category renamed", which stays: categories are never removed).
  `VEYQ_LIVE_POT_THIRD=<pot name>` adds a person "Cleo" who pays 30.00 for everyone, so that Pot settles in more than
  one payment (a desktop pass's Record all); both stay. `VEYQ_LIVE_POT_EDIT="<pot name>|<entry title>"` retitles that entry
  "<entry title> (elsewhere)" from this second session, so a desktop pass's open editor saves stale.
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
  (`--remember` answers the offer after the sign-in with the presence level, as a person would — the emulator then asks
  for its screen lock; `--unlock` hands only the password, for the unlock screen of a password-trusted account, and
  fills Manage's way back to the password once; a fresh start, since Android hands a running task no new extras).
  Apple's `devlogin.py <device>` takes the same flags. Both read the principal from `VEYQ_ENV_FILE` (a worktree:
  `<main checkout>/.run/.env`). The steps go to logcat (`veyq-trust`) and to Apple's unified log (`veyq-trust:`).
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
WinUI's own chrome (the pane's button, back, the scroll bar, a `ToggleSwitch`'s On/Off) speaks the system's language
whatever the app's words are, as on every Windows app, so a switch carries no words of its own: its header says what it
turns on; a built-in item that shows a word of its own (`NavigationView`'s Settings) is replaced
by one carrying the catalog's.

A node's header is core's, for every kind (ADR 0123, the web's `NodePage` + `NodeHeader`): core's `navigation/NodeHeader.kt`
plans it (`NodeHeaderTest`), each app's `NodeHeaderActions` draws it and owns every dialog behind it, and a kind only
registers its page — `KindPages` (Android) / `KindPage` (Apple): it brings a share sheet, takes a cover, contributes a
subtitle, its rights narrowing, its copy and download doors and own actions, and draws its body. The header's state and
actions are core's `NodeHeaderModel` and its sheets core's `TrashSheet`, `MoveSheet`, `ShareSheet` and `ReportSheet`
(ADR 0127's Consequences): an app draws them and decides nothing. Linux (`header.rs`) and Windows (`NodeHeader.cs`)
draw the same plan, but only the ids in `header::DRAWN` / `HeaderPlan.Drawn`: an action whose screen a desktop lacks
is left out, never drawn as a control that answers "not yet"; a slice that draws it adds its id. A new kind registers
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
- Staying signed in needs a screen lock or passcode on Android and Apple: the store offers neither level without one
  (the presence key cannot exist, and the login level would open for anyone holding the phone), so no offer shows. On
  the emulator `adb shell
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
- DocumentsUI's multi-select (long-press, then taps) is drawn while `uiautomator dump` still lists the plain list: read
  "N selected" from a screenshot and press Select by its place. A 220 MB upload from the swiftshader emulator took about
  20 minutes (2026-11-12): a pass with large files starts their upload first and works on beside it — an app install
  meanwhile kills the queue.
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
  A SwiftUI view must not take a core class's name either: a view `ShareSheet` shadowed core's, so the view is
  `NodeShareSheet`. The bridge's generated C# and Rust take a wire class's simple name too: `Action` clashed with
  `System.Action` (now `PlanAction`), so a wire class avoids the platforms' common type names.
- A SwiftUI alert or confirmation dialog whose binding cancels on dismissal runs that cancel BEFORE the button's action:
  Apple's "Delete forever" in the space's trash sent nothing, its row already cleared (measured 2026-11-11). A question
  that is a core sheet's state is cancelled only by its Cancel button, and the action hands the row it confirmed
  (`TrashSheet.purge(row)`).
- An iPhone shows a `confirmationDialog` as a popover with no Cancel button: a tap outside dismisses it, and a binding
  that ignores its dismissal (the answer to the trap above) left the erase asked (measured 2026-11-12, a Pot's person).
  The dismissal cancels one main-queue turn later unless the destructive action confirmed meanwhile.
- A text field on the phones reads the model synchronously after its own edit: both hosts set their drawn state from
  `editor.current` right after `change(…)` (Compose's `mutableStateOf`, Apple's `editorState`), and from the watch only
  what the core changes itself (a rate filled). A field bound to a value that arrives a frame later loses keystrokes.
- A SwiftUI view that observes a core model it does not own follows it through a watch its owner holds, never one
  tied to the view's lifecycle: a rename hands the view another header, and a box started on appear kept the old one
  (Report vanished from the menu). The header redraws on `AppModel.headerRevision`, which a watch on every handed-out
  header moves.
- A Swift closure handed to core is called on core's threads (after a request answered, Ktor's), never only on the main
  one: written in the `@MainActor` model it is inferred main-actor isolated, and Swift 6 traps on the first call from
  elsewhere (`_dispatch_assert_queue_fail`, measured 2026-11-09: the re-key's rekey id). Mark it `@Sendable` and reach
  nothing of the model in it. A persist closure is one too (`AppModel.init`'s, which say so):
  `RecentUses`' and `Hidden`'s crashed the sign-out of the last server (`RecentUses.clear` runs on a core thread,
  measured 2026-11-11), which only a pass that signs out of its last account reaches — so an Apple pass ends that way.
- `simctl launch --stdout` wrote no file (measured 2026-11-11): the Apple app's lines are read from the unified log
  (`NSLog`, read with `log show`), Android's from logcat — text before every screenshot.
- A screen model that decrypts or walks a tree runs it off its caller's thread (`withContext(Dispatchers.Default)`, as
  `MoveSheet` does): the copy's preview in Android's `LaunchedEffect` stopped the slow emulator answering (2026-11-11).
- Kotlin initialises properties in declaration order: a flow a collector in `init` writes is declared above that
  `init`, or the first value meets a null field (Android's `_offer`, the first start crashed, measured 2026-11-11).
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
- A phone shows a file without writing it (ADR 0047): Android hands a PDF to `PdfRenderer` through a `memfd`
  (`Os.memfd_create`, API 30+: anonymous memory, no path) and plays a medium through `ReaderPlayer`'s `MediaDataSource`
  over core's `RangeReader` (`ReaderPlayer.kt`, shared with Memories); Apple opens a PDF with `PDFDocument(data:)` and plays
  through `ReaderPlayback.swift`'s `AVAssetResourceLoader`. The only decrypted copies are the ones the person asked for —
  "Send a copy" (`cacheDir/shared`, `tmp/shared`) and iOS's download for `fileMover` (`tmp/downloads`) —, both swept at
  every start; a Mac's download goes to the save panel's path through core's `FileSink`.
- SwiftUI did not present an alert again once it was answered and its binding turned true anew (measured 2026-11-12: the
  second replace offer of a batch never showed, its state set, even 1.5 s later): what `DepotUploads` says has an alert on a
  view of its own per said (`.id(said.id)`, `ShellView`). And a `.fileImporter`'s binding is cleared on dismissal BEFORE its
  completion runs: a completion that reads the state the binding clears finds nothing (the picked file for Replace was
  dropped) — keep what it needs apart (`AppModel.replaceChosen`). The move sheet's "move out of the drop box?" fell into
  the cancel-before-action trap above (nothing moved); it now cancels only on Cancel.
- A coroutine that was cancelled fails every suspending call at once, its own clean-up's too: a cancelled Depot upload
  left a "not uploaded yet" file because its abandon's listing and trash ran cancelled. A give-up runs `NonCancellable`
  (`DepotFiles.upload`, `UploadJob.abandon`). A failed upload whose give-up could not reach the server (offline) leaves its
  empty file, and the retry then collides with it and offers its replace (measured on the emulator in airplane mode).
- A guest's new folder in a drop box shows on the phones only once the tenant's tree is read anew: the tree is cached, and
  nothing polls it; a pass force-stops and relaunches the app (`devlogin.py`) after a guest dropped through the web.
- Driving the web from a mobile pass (headless system Chrome, `?devlogin`): a level row's `locator.click()` waits forever
  for a stable element; click the row's bounding box with `page.mouse`, or open by hash (`#app-depot-2/alpha-txt`). A file
  page's Replace is its hidden `input.files-picker` (`setInputFiles`), then "Ersetzen".
- Under a host load around 20 the swiftshader emulator raises "isn't responding" on a long editor sheet: the main thread is
  idle (`kill -3` after `adb root`, `/data/anr/trace_*`), so tap Wait, never Close; a pass reads its state after it.
- On iOS one SwiftUI `Text` of a megabyte drew nothing (measured 2026-11-12, a file's cut text): a long text is a line a
  row in a `LazyVStack` (`FileText`). AVFoundation plays no WebM: a file page reads the asset's `isPlayable` through the
  resource loader and says `file_unsupported` (D7 of the Depot CR); Android plays it.
- The shell shows an open object in its list's place and the bar is the object's (ADR 0122), so a list leaves the view
  tree and is composed anew on the way back, at its top (the owner's report, 2026-11-12). Android keeps each list's
  `LazyListState` in the shell (`ListStates`, by list key). On iOS neither `ScrollViewReader.scrollTo` nor
  `scrollPosition` moved that `List` (iOS 27, measured 2026-11-12: the call ran, the list stayed at its top), so
  `KeepsScroll` keeps the offset of the list's own `UIScrollView` per key, found beside its background a moment after
  that background joins the window.
- The Simulator tool's tap does not reliably flip a SwiftUI `Toggle`: a short swipe across it does (measured
  2026-11-09, the report's good-faith switch). A tap with `duration: 0.15` registers where a plain one is often lost.
  The tool cannot type text ("iOS on this simulator does not take a paste from the Mac", 2026-11-11): a pass leaves a
  field empty or fills it through `devlogin.py`. Its screenshots lag 2–3 s, so a 6 s Undo is out of its reach (the tap
  lands on the row below once the toast is gone): a timed flow is proven on Android and in `commonTest`, and Apple's
  Undo is named open (native-apps § Open). An emulator under load is no clock either: a screencap took ~6 s on the
  swiftshader AVD, so a window is measured from logcat's timestamps, never from screenshots. A Compose radio or checkbox row is tapped as the row only once the row
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
- A GTK dialog's state that only weak handlers reach is dropped at once: Linux's first cover dialog did nothing on
  "Choose a picture", every `Weak` upgrading to nothing. A dialog module holds its state until the dialog's `closed`
  (`cover.rs`, `share_sheet.rs`).
- Broadway hands the app's clipboard to no browser: a Linux pass reads a link's address back through the bridge
  (`LiveBridgeTest.theLinksOfAFolder*`, `VEYQ_LIVE_SHARE_LIST`) for the web's guest page; the copy is proven by its
  toast and stderr.
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
  defects of this kind showed only in a screenshot, never in the tree: take each form's shot early. A third: a frame
  sized from its layout inside a `ContentDialog`'s scroller collapsed to 480×2 (the cover's): a drawing surface there
  has a size of its own.
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
- The container runs with `--rm`: what the app keeps outside `~/.local/share` (the volume `veyq-linux-home`) is gone at
  the next `run.py run`, and with `--no-secret-service` all of it, Recent included. The device store, gnome-keyring's
  `keyrings/` and the collection's password live on that volume, so a second start resumes what the first trusted;
  without the volume every start would find no store. A pass that trusted something ends with Stop trusting, and
  `docker volume rm veyq-linux-home` starts from nothing. A pass that shows Recent there opens
  its objects first in that run (remembered from the bridge's Linux pass, 2026-11-11).
- The browser pane, hidden, showed a Broadway switch unturned after a click that had turned it (measured 2026-11-11, the
  device trust's Linux pass): the frames lag, not the input. A pass whose pane is hidden drives Broadway from headless
  system Chrome through the workspace's Playwright (`channel: 'chrome'`), one connection per run of steps, and reads
  each state from the app's stderr before its screenshot. `run.py run`'s app is stopped by closing its window: `docker
  stop` ends it by SIGKILL after the timeout (exit 137).
- Windows Hello, as measured 2026-11-11 on the VM (a local account, Windows 11 26H2, x64 under Prism):
  - `dsregcmd /status` is no test for Hello on a local account: `NgcSet : NO` with a PIN set. The core asks cryptngc's
    `NgcGetDefaultDecryptionKeyName` (`WindowsDeviceKeys`' `available`).
  - Enrolment asks for the PIN: creating the Passport key (`NCryptFinalizeKey`) prompts, then each open does. A pass's
    brief to the person lists every prompt.
  - A cancelled prompt is `0x80090036` (`NTE_USER_CANCELLED`) or the smart-card stack's `SCARD_W_CANCELLED_BY_USER`,
    `DeviceAuthFailed(cancelled = true)`, and keeps the screen. A prompt nobody answered stays open, possibly behind
    the app, until Windows closes it (about 17 minutes once) and reads as a failure, not a cancel: the generic line.
  - The prompt is owned by the app's window (`Config.window`, the HWND, `NCRYPT_WINDOW_HANDLE_PROPERTY`); a prompt
    without an owner may open behind the app.
- WinUI shows one `ContentDialog` at a time: a password asked from inside Manage servers is a field in place, not a
  second dialog. A control's content reused under a new parent after its dialog closed crashed the second open of
  Manage servers: build a dialog's content per open, and a pass opens every dialog twice in one run.
- The common file dialog on Windows cannot be pressed through UI Automation: its Save invoked by UIA arrives as an
  input-synchronous call and the dialog cannot reach the file broker (`RPC_E_CANTCALLOUT_ININPUTSYNCCALL`, nothing
  written), and a file name set as a UIA `Value` shows in the tree but is not read. `Veyq.Drive`'s `type` and `press`
  focus the element and send real keys (`SendInput`). The picker is a window of the app's own process. The open
  picker takes a typed name only once it has finished loading (the first `type` landed nowhere): a pass reads the name
  box's value in the tree before it presses Open.
- Windows' share sheet, used once, came back as a stale window in later screenshots while UI Automation drove the
  `ContentDialog` beneath, until the app restarted (2026-11-11; whether a person sees it is open, native-apps § Open):
  a pass that shares does it last, or restarts the app after.
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
- An answer can arrive before the watch's update its call caused: Linux's first trash toast had no Undo, read from a
  state the update had not reached yet. A write's answer carries what the host needs at once (`Trashed.undoable`,
  `MoveSent`), and a host ends an Undo only on the state's change from offered to not, so an update from before the
  write ends nothing.
- A pass ends in the state the next one needs, not only "no trust": the device trust's passes end with Stop trusting
  (which also forgets "Not now", so the next sign-in offers again) and, on Apple, a sign-out. A "Not now" left behind
  once hid the offer the next pass's first step looked for (2026-11-11).
- A dev origin is the backend, not the web: the address the core builds for a page (`NodeReport.privacyUrl`,
  `<origin>/privacy#reporter-contact`) is right where one origin serves both, but on the dev stack it opens the
  backend's 404 (Windows' Edge) — check the page on Vite's origin (8081), as a dev link's guest page is.
- An app is refused at sign-in by a backend of another major (`server major 1, app 0`): a branch whose `VERSION` lags
  the running dev backend's cannot run a live pass until `main` is merged into it.
- A download's failure on Windows is shown with a nearly full volume (a small FAT VHD filled to one free cluster): the
  common file dialog itself refuses a folder the user cannot write to before the core is called (remembered, not
  measured), so Linux's read-only folder has no counterpart there.
- The bridge launches each call on `Dispatchers.Default`, so two calls sent one after the other may be dispatched the other
  way round. An edit stream is applied on the host's thread in the order called (`Bridge.call`, `Editor.Change`; bitten
  by 400 titles sent unawaited), and Linux's `bridge::call` hands the call to the core when it is called, not when its
  answer is first awaited (C#'s `CallAsync` does the same). A new stream of edits goes the same way (`Editor.DropReceipt`,
  `PotCreate.Name`). No test sees the order of two trivial calls: `Dispatchers.Default` kept them in order in practice,
  so a `PotCreate.Name` launched instead of applied stayed green (2026-11-12); the order rests on `Bridge.call`'s path.
- A text field is set from the state by D5's rule (`linux/src/typed.rs`, `windows/Veyq/TypedText.cs`, the same cases):
  states lag the typing by a keystroke or two, so a focused field passes over a state that echoes a text it sent and
  takes any other (a stale save's version, a rate filled). "Differs from what it sent last" alone rewrote the field to an
  older text.
- An `AdwComboRow` whose search or expression is set after its model replaces its selection and notifies `selected`:
  the editor sent "AED" as the currency at its build (measured 2026-11-12). Build a combo row in one builder, and hold the
  drawing flag while a form is built, not only while it is updated.
- WinUI's `TextBox.TextChanged` is raised after the code that set `Text` returned, so a drawing flag does not cover the
  state's own set: a payer's box set to an older state's "" came back as an edit and the save was refused (measured
  2026-11-12). A box remembers the text the state set and passes that change over (`EntryEditorPage.Box`).
- WinUI: an element moved between two panels drawn in a row read `Parent` null while the first still loaded, and its
  add to the second threw `0x800F1000` ("no installed components were detected"); thrown out of a watch's callback it
  ended the app in combase with nothing in the log (the Pot's head after the editor closed, measured 2026-11-12). A page
  keeps the panel it put an element in itself (`PotPage._heroHolder`), and a page's draw logs what it throws.
- words.json writes a percent sign `%%`, as Android's and Apple's format strings read it; `generate_words.py` writes one
  `%` into the desktops' catalogs (`desktop_text`), and both desktops' words tests expect that.
- Driving the desktops' editors: Playwright names `Backspace` and presses no `Ü` (Broadway); `Veyq.Drive`'s `key` has no
  `.` or `,` — a decimal goes in by `set`, a UIA `set` while a form rebuilds may land on the box it replaces (wait for the
  rebuild), a UIA `select` leaves a `ComboBox`'s drop-down open, and an `AutoSuggestBox`'s inner box is `#TextBox`
  (Search's too): reach it by `press ed-date tab tab`.
- A desktop pass that needs a full storage (a receipt's skipped notice) copies its Pot first — a copy needs room — and
  then holds the one-byte quota with `DepotLiveTest.aCopyOrAFolder*` (`VEYQ_LIVE_STORAGE_FIT="Reisen/AppUpUi"`,
  `VEYQ_LIVE_STORAGE_FIT_HOLD_S`), started once the app's `run.py run` finished its own Gradle step: the quota is set about
  a minute after the start and put back when the hold ends. The bridge's `LiveBridgeTest` sets it itself: the core's
  bearer-carrying client is internal to `:core`, so the test reads `VeyqAccount.http` by reflection (a test's reach only).
- GTK's open dialog (Broadway, the container): a folder's path typed and Enter pressed in a dialog that opens several
  files hands the folder back as the one file picked (the core refuses it). Type its parent's path, then open the folder
  by a double-click on its row; Ctrl+A in the list picks every file. A double-click a little off the dialog lands on the
  window's title bar and unmaximizes it. A popover stays open across two Playwright connections, but a click on its
  button in the second closes it: open a menu and pick from it in one run of steps.
- Windows' open picker (`Veyq.Drive`): a name typed into `#1148` lands only once the dialog was navigated through its
  list (`select <n>`, `press <n> enter` on a folder's row); several files go in as `"a.jpg" "b.pdf"`, or by `press 0
  ctrl+a` in the list and `press 0 enter`. The id `1` is both the second row and Open: a `press 1` may take either.
- A `ContentDialog` is at most `ContentDialogMaxWidth` wide (548 by default), whatever its content asks: a dialog that
  shows a receipt sets that resource on itself (`ReceiptViewerDialog`).
- poppler-rs opens a document over bytes in memory only with its `v0_82` feature (`Document::from_bytes`, a
  `glib::Bytes` the document holds); `from_data` borrows a slice that must outlive the document. cairo's `ARgb32` is
  B, G, R, A premultiplied in memory on the targets the app is built for (`gdk::MemoryFormat::B8g8r8a8Premultiplied`).
- A closed WinUI `ComboBox` takes each type-ahead key as a selection, and a box rebuilt on every update hands the next
  key to its replacement: "c h f" typed into the settings' base currency wrote CAD (measured 2026-11-12). A box the
  state updates is kept, only its selection set, and writes on `SelectionChangedTrigger.Committed`
  (`PotSettingsView`). Even so, a closed box's first key commits, and the Pot page's redraw after that write takes the
  focus from the box (the place's panel is moved into each redraw), so the next keys land nowhere; in the open list a
  letter moves to the next item starting with it and further letters move nothing (through `Veyq.Drive`'s keys): a
  person reaches CHF by Enter, `c` until CHF, Enter.
- Driving Windows' Pot screens (`Veyq.Drive`): a virtualized `ComboBox` item is chosen by keys — `press <id> enter` opens
  the list, `key c`, `key enter` — or by `select <id>:<item>` while the list is open (an item not yet realized is not
  in the tree); Home's rows open by `invoke` (a `select` only selects); `wait` takes an AutomationId, `sleep`
  milliseconds.
- Broadway: Enter in an `AdwComboRow`'s search selects nothing; click the item in the same Playwright run that typed the
  search (a second connection finds the popover closed at its first click).
- `scripts/validate_mobile_platform_guidelines.py` refuses `list-add-symbolic` anywhere but the create action (a "+" on
  a settings row is a labelled button) and, on Windows, any button aligned at the bottom (`VerticalAlignment.Bottom`
  in an initializer or set on a variable): a dialog's or place's actions sit in the flow, not pinned.
- A redraw on the desktops must not lose the scroll once updates arrive unasked (the live poll): a scroll rebuilt per
  state jumped to the top on every tick (the Linux Pot page, 2026-11-12). Both Pot pages keep one scroll across redraws;
  another place starts at its top.
- GTK: a `gtk::Picture` in a `GtkAspectFrame` got no height for its width, and a height asked inside a widget's
  `resize` was ignored until the next turn of the loop: the Pot's 16:9 head is a `DrawingArea` sized from its width
  (`header.rs`, `Header::hero`). The same for a scroll: a value set in the adjustment's `changed` during its allocation moved
  the scroll bar's thumb but drew the list at its top (measured 2026-11-12); `shell::ListScrolls` sets it on idle.
- The shell draws a list anew on every state and when an object opened from it closes (the object takes its place), so on
  the desktops too a list came back at its top (the owner's report, met on Linux 2026-11-12): `ListScrolls` (Linux
  `shell.rs`, Windows `ListScrolls.cs` with `ShellView.Keep`) keeps each list's offset by its place and puts a new list back
  once it is laid out tall enough.
- One WinUI `TextBox` holding a megabyte drew only its first line, even 15 s later (measured 2026-11-12, a file's cut text):
  a long text is a virtualized `ListView` a line a row (`FilePage.Text`), as on iOS. The Linux container has no monospace
  font, so a `TextView`'s `monospace` falls back to the proportional one there (a desktop has one).
- A session's tree is cached and `Shell.Refresh` does not read it anew: a desktop sees another device's upload only after it
  signs in again. A live case that writes through a second session writes before the bridge signs in
  (`LiveBridgeTest.aFileIsShownAndSaved*`).
- Driving a file's save on the desktops: GTK's save dialog takes a whole path in its name field (`key Control+a`, `type
  /tmp/big.bin`, `key Enter`); a 300 MiB save in the container ends in about 3 s, so a Cancel is clicked in the same
  Playwright run. On Windows the save picker's name is typed into `FileNameControlHost` and saved by `press
  FileNameControlHost enter` (a `key enter` goes to the app's window, not the picker's).
- `linux/run.py run` leaves the container's zone UTC (an instant there is said in UTC); `run.py test` links Berlin. A
  pass reading times off the Linux app reads them as UTC.
- Never hold a XAML element through a `WeakReference`: once its managed wrapper was collected, `TryGetTarget` resolves
  the native object's own weak reference into a second wrapper, and on an element being torn down (a closed dialog's
  `PersonPicture`s) the GC's reference tracking was left with a freed object. The app died with `0xc0000005` in
  `coreclr.dll` — `WalkExternalTrackerObjects` during a GC, or the finalizer releasing that wrapper — at a later,
  unrelated draw (measured 2026-11-12 from two dumps; 2 crashes in 4 tries before the fix, none in 6 after). A host keeps
  an element strongly while it is loaded (`Avatars.cs`), keyed by the managed object (`ConditionalWeakTable`) where it
  must be weak.
- A native crash of the Windows app is read from a WER dump: the VM keeps full dumps of `Veyq.App.exe` in
  `F:\veyq\dumps` (`HKLM\SOFTWARE\Microsoft\Windows\Windows Error Reporting\LocalDumps\Veyq.App.exe`, three kept).
  The app is x64 under emulation, so its dump needs x64 tools: `dotnet tool install dotnet-dump --tool-path
  F:\veyq\tools-x64 --arch x64`, run as `"C:\Program Files\dotnet\x64\dotnet.exe" <its dotnet-dump.dll> analyze`
  (the arm64 one says "SOS does not support the current target architecture"; `clrstack`, `dso`, `lno`, `verifyheap`),
  and the native stack from WinDbg's `amd64\cdb.exe` (`winget install Microsoft.WinDbg`; `-z <dump>`, `.ecxr; kn`,
  `s -q` to find who holds a pointer).
