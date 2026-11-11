---
id: cr-expenses-whole-on-every-app
title: Expenses whole on Linux and Windows — the Pot's screens moved into the core first, then bridged and drawn
status: planned
satisfies: []
adrs: ["0127", "0128", "0123", "0122"]
invariants:
  - every rule is the core's (ADR 0127 decision 1) — what Android's and Apple's Pot screens still decide (the entry editor's form, the rate fill, receipts held, the date of a settle-up, the restore window, the settings' and New pot's drafts) moves into core screen models first, both apps thinned in the same phase and unchanged on a device (decision 2)
  - the bridge only carries — each call one line onto a core model, no key, seal or token outward (SecretFreeSchemaTest); a receipt goes in as a path the host's picker gave and out to a save dialog's path, a shown receipt rides beside the JSON (ADR 0128)
  - no word in a desktop but from src/mobile/words/words.json; each desktop follows its platform's guideline (ADR 0123 decision 9)
  - no new dependency that is deprecated, vendor-locked or closed (root CLAUDE.md); a receipt is never written to disk to be shown
  - Android, the Apple app and the web behave as before, except where a rule they disagreed on is made one (D8); `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` and both desktops' `run.py build` stay green in every phase
---

## Goal

CR 3b of the owner's cut for bringing Linux and Windows to the Android and Apple apps (2026-11-11, "passt"; "plane CR
3", 2026-11-11). CR 3a (`cr-the-node-header-on-every-app`) brings a node's header and its actions, a Pot's copy among
them. This CR brings **Expenses whole** to both desktops, at the level Android and Apple have today:
- **the entry editor:** new and edit, every kind (expense, refund, transfer), payers, items, the split's modes,
  another currency with its rate filled from the tenant's service, the refusals, the preview, a stale save, delete;
- **receipts:** attach from the system's open picker, show an image or a PDF, save one, the notice of those a full
  storage left out;
- **settle-up:** record one payment or all;
- **an entry's history** and a deleted entry's restore;
- **settings and people:** base currency, own categories, add a person or a member, rename, (de)activate, erase;
- **New pot** from New;
- **the live poll** while a Pot is shown, and pull to refresh.

Copy is CR 3a's (its Phase 3, measured below); this CR only passes it again on a Pot it made.

## Context & constraints

- **Substrate:** branch `claude/windows-cr-f6c73c` after CR 3a is done and deleted (its Phase 5 is in progress at
  refine, its Phase 6 is the docs). CR 3a's Pot model registry, header doors and move sheet are this CR's floor.
- **Read first:**
  - ADR 0127 (decisions 1 and 2), ADR 0128 (all four decisions), ADR 0123 decision 9;
  - `src/mobile/CLAUDE.md` whole (the bridge, the traps, each platform's guideline);
  - `docs/apps/expenses.md` (the Pot, Entries, Exchange rates, Receipts, What the client computes, Rights);
  - CR 3a's retrospectives, whose traps hold here (an answer overtaking an update, one `ContentDialog` at a time,
    `doNew…` in Swift, a SwiftUI view shadowing a core class, decrypting off the caller's thread, an emulator is no
    clock, Broadway's clipboard).
- **Where what runs:** as in CR 3a.
  - The Kotlin runs on this Mac.
  - Linux runs in the container: `linux/run.py run --port 8097 --devlogin --no-secret-service` (the default port is
    8095; CR 3a's passes used 8097), driven from headless system Chrome when the browser pane is hidden.
  - Windows runs on the ARM64 VM (`windows/run.py run --devlogin`, `run.py drive`).
  - Android runs on a throwaway AVD (never `veyq_pixel`); Apple on a throwaway iPhone 18 Pro simulator (iOS 27).
  - The dev backend (`veyq.localhost:8080`) is never started twice or stopped.
- **The live tests write only into their own Pots.** Each live case copies a seeded Pot (Amsterdam) through the move
  sheet into a Pot named `App pot <n>`, writes there, and trashes and purges it at the end; a seeded Pot is never written.
  Each desktop pass does the same by hand (`Linux pot`, `Windows pot`).

## Measured at refine (2026-11-11)

From a read of the core, both apps, the bridge and both desktops (two Explore passes, their numbers checked again here).
Cites are into `src/mobile/`, the core being `core/src/commonMain/kotlin/me/raddatz/veyq/mobile/`. Commands, from
`src/mobile`:
- `find android/src apple -name '*.kt' -o -name '*.swift' | grep -iE 'pot|entry|receipt|pdf' | xargs wc -l`
- `find core/src -path '*expenses*' -type f | xargs wc -l`; `ls core/src/commonTest/kotlin/me/raddatz/veyq/mobile/expenses/`
- `grep -n '@SerialName' bridge/src/commonMain/kotlin/me/raddatz/veyq/mobile/bridge/Wire.kt | grep -i pot`
- `grep -rnE 'startLive|\.live\(\)|PotRefresh' android apple linux windows`
- `grep -rniE 'pdf|poppler|Windows.Data.Pdf' linux windows`; `grep -n 'port' linux/run.py`
- `grep -rln 'FileSource' core/src bridge/src`

| # | What | Where |
|---|---|---|
| R1 | **The bridge carries a Pot read only.** `Pot.Open/Refresh/Close/Download/ArchiveName`, `Watched.Pot` → `Update.Pot(pot, PotModel.State, pictures)`; a Pot's copy through `Move.Open`. No call writes an entry, records a payment, opens or adds a receipt, edits settings or people, creates a Pot, or runs the live poll. | `bridge/.../Wire.kt:141-156, 411, 452`; `Bridge.kt:258-273` |
| R2 | **Both desktops draw a Pot read only** (entries, overview with balances, settle-up rows, by category, total; settings' currency and people; the ZIP download). No add, no editor, no receipt. Neither sends `Pot.Refresh` from its UI, so a desktop Pot never updates after its open. | `linux/src/pot.rs` (328), `windows/Veyq/PotPage.cs` (377) |
| R3 | **The rules are mostly the core's already**, as functions: `EntryForm` (`contentOf`, `preview`, `withKind`, `withCurrency`, `toggled`, `typed`, `everyoneEqually`, `splitHint`, `collapsedWords`), `PotView` (`blankForm`, `editing`, `check`, `preview`, `receiptAllowed`, `history`, `historyLine`, `restorable`, `purgeAfter`, `settleUp`), `PotSession` (`saveEntry`, `deleteEntry`, `restoreEntry`, `recordPayments`, `rateFor`, the settings' and people's writes), `ExchangeRates`, `Receipts`, `PotCreate`, `PotShare`, `ShareInbox`. The one screen model is `PotModel`. | `expenses/` (6420 lines with its tests) |
| R4 | **Still decided in both apps** (a third and fourth copy would follow): | |
|  | — **the editor's state machine:** the form, `tried`, the refusal shown only after a try, pending receipts, save, the skipped receipts' notice, delete | `android/.../EntryEditor.kt:89-222` (410); `apple/Veyq/EntryEditor.swift:155-217` (399) |
|  | — **Apple's hand-written copy of `EntryForm`** (`struct FormState` with conversions both ways and its own date format) | `apple/Veyq/EntryEditor.swift:5-70` |
|  | — **the rate fill:** `ExchangeRates.wanted`, `rateFor`, the stale check, `fill`, a `RateNote` type, each app's own | `EntryEditor.kt:131-144, 402-406`; `EntryEditor.swift:100, 200-217` |
|  | — **receipts held:** the running count, `MAX_PER_ENTRY`, the refused flag, the size cap at read, the id minted | `android/.../EntryReceipts.kt:52-112`; `apple/Veyq/EntryReceipts.swift:86-108` |
|  | — **Apple sniffs a receipt again with ImageIO**, a second copy of core's `blob/Sniff` | `EntryReceipts.swift:160-166` |
|  | — **the PDF's shown pages:** Android caps at 20 pages, 1080 px; Apple's PDFKit view shows all | `EntryReceipts.kt:179-212`; `PDFPages.swift` |
|  | — **today:** a settle-up's date, a new entry's date and the rate's day are the platform's `LocalDate.now()` / `Date()` | `android/.../PotScreen.kt:238, 246`, `AppModel.kt` `rateFor`; `PotScreen.swift:220-223` |
|  | — **a deleted entry's restore window** judged against the platform's clock | `android/.../EntryHistory.kt`; `apple/Veyq/EntryHistory.swift` |
|  | — **settings' drafts and confirms** (rename, erase asked, the currency guard) | `android/.../PotSettings.kt` (245); `apple/Veyq/PotSettings.swift` (225) |
|  | — **New pot's form:** the default space and currency, the members loaded per space, people kept, the enable rule, `parentIn` (Apple computes it in its app model, falling back to a literal `"REFUSED"`) | `android/.../PotCreateSheet.kt` (142); `apple/Veyq/PotCreateSheet.swift` (94), `AppModel.swift:1130-1241` |
|  | — **the live poll's lifetime** (Android `repeatOnLifecycle(STARTED)`, Apple a `.task` on `scenePhase`) | `PotScreen.kt:77-79`; `PotScreen.swift:63-65` |
|  | — **Apple's `potWrite` repeats `PotModel.write`** by hand with `beginWrite`/`endWrite` | `AppModel.swift:1130-1241` |
| R5 | **The apps disagree:** an amount without a currency (Apple falls back to `"EUR"`, Android to the locale's); shared files into a Pot (Android a 40 MiB HEIC read cap and `PotShare.split`, Apple `PotShare.takes`, `MAX_FILES` and a 64 MiB budget); the editor's delete label (`entry_delete` on Android, `peopleDeleteAccept` on Apple); saving a receipt (Android the gallery or a save dialog, Apple `ShareLink`). | as R4's files; `ShareIn.kt`, `IncomingFiles.swift` |
| R6 | **Test gaps:** `PotModel` has no test anywhere but the bridge's `WireTest`; the Expenses tests are `jvmTest` (`EntryFormTest`, `PotLiveTest`, `PotWritesTest`, …) except three small `commonTest` ones (`CurrenciesTest`, `PotCreatePeopleTest`, `ShareInboxTest`). `DispatchTest` has one Pot case (refusals without a tenant). | `core/src/commonTest/.../expenses/`, `bridge/src/jvmTest/` |
| R7 | **The live poll is unreachable from a desktop:** `PotModel.live()`/`startLive()` have no bridge call. | `expenses/PotModel.kt:88-97` |
| R8 | **No file goes in by path over the bridge yet:** `blob/FileSource.kt` (`nativeMain`) exists, no call uses it; native-apps § Open says Depot's uploads bring it. The Reader registry has no consumer either. | `core/src/nativeMain/.../blob/FileSource.kt`; `Bridge.kt:363` |
| R9 | **No PDF renderer on either desktop**; images decode through `gdk::Texture::from_bytes` (glycin) and `BitmapImage` (WIC) for avatars only. Linux's crates: `gtk4` 0.11, `libadwaita` 0.9, `async-channel`, `serde`, `serde_json`; Windows' packages: the Windows App SDK 2.6.5 and the SDK build tools, MSTest. | `linux/Cargo.toml`, `linux/Containerfile`; `windows/Directory.Packages.props` |
| R10 | **The words are there:** 182 Expenses keys in `words.json`, all with German, generated into every platform's catalog; the desktops use 13 (Linux) and 12 (Windows) of them. | `words/words.json` |
| R11 | **A Pot's copy reaches both desktops already** (CR 3a Phase 3: `LiveBridgeTest.aPotIsCopied…`, both desktops' passes). | CR 3a § Phase 3 |
| R12 | **No published page speaks for the native apps:** `git grep -li "linux\|windows" -- site/src/content/docs` finds only `run/operations.md`, which is about the server. | `site/` |

## Decisions

### D1 — The entry editor is a core screen model, `EntryEditor`, before any desktop draws it

ADR 0127 decision 2's shape (state flow, `watch`, plain actions), tested in `commonTest` over a played door (the Pot's
save, delete and rate, as CR 3a's sheets' doors play the server), the largest move of R4:
- **State:** the form (`EntryForm`, the core's, never a platform's copy); `tried`; the refusal, shown only once tried
  (`PotView.check`); the preview rows and total; the rate's note (filling, filled from a day's table, typed); the pending
  receipts (id, name, size, type) and the kept ones; whether another receipt is allowed (`receiptAllowed` over the
  held count); a refused pick; saving; the outcome's notice (stale, refused, invalid, the skipped receipts); `asking`
  (delete confirmed); `done`.
- **Actions:** `open` (new from `blankForm`, or an entry from `editing`), one `change(EditorChange)` over a sealed set of
  edits (kind, date, description, category, currency, amount, rate, a payer added, removed or typed, an item added,
  removed or typed, the split's mode, a person toggled or typed, everyone equally, expanded), `addReceipt(PendingReceipt)`,
  `dropReceipt(id)`, `save`, `askDelete`, `delete`, `cancelAsk`, `close`.
- **The rate fill runs in the model**: a currency changed to one the base is not and an empty rate start the fill
  (`ExchangeRates.wanted`), a stale answer (the currency changed meanwhile) is dropped, the note is state.
- **The decimal separator is the platform's**, handed at `open` (locale data, as money formatting stays the
  platform's); every typed text stays the text typed (`EntryForm` keeps strings), so a host's field is never rewritten
  under the cursor.
- Android's Compose state and Apple's `FormState`, `RateNote` and `SplitEditor` conversions go; both draw the model.
  Apple observes it through a watch the app model holds (CR 3a Phase 1's lesson), not one tied to a view's lifecycle.

### D2 — The Pot's other screens are the core's too

- **Settle-up:** `PotModel.record(rows)` (one row, or all of `settleUp`), the date the core's today (D7).
- **History and restore:** `PotModel.restore(entryId)` judges `restorable` against the core's clock and writes
  `restoreEntry(latestRevision)`; the history's lines stay `PotView.history`/`historyLine`.
- **`PotSettings`** (a screen model): the base currency (refused once money is in, the core's rule), own categories
  added and renamed, a person added, a member added, renamed, (de)activated, erased (`asking` before an erase), each a
  `PotModel.write` with its refusal as a notice key; the typed text arrives with the call, no draft lives in the core.
- **`PotCreateSheet`** (a screen model, replacing what `PotModel.creating` half holds): the default space and currency
  (`PotCreate.defaultSpace`, `defaultCurrency` of the language the platform hands), the members read when the space
  changes, the people kept and typed, `parentIn` from the shell's place, the enable rule, saving, the refusal, the
  created Pot opened.
- **Receipts shown:** what a receipt is (`Receipts.kindOf` over core's `Sniff`, Apple's ImageIO copy gone), how many of
  a PDF's pages a viewer shows (`Receipts.PDF_PAGES_SHOWN = 20`, Android's cap made the core's; Apple's PDFKit view
  pages lazily and keeps showing all, which the constant allows as "at least"), and the name it is saved as
  (`downloadName`).
- **`PotModel` gains its `commonTest`** (R6): the live poll skipped while saving, a write's refusal as the message,
  `close` of another ref ignored, a re-key re-reading.

### D3 — The bridge carries the models, one line each

- **Held by id, as Pots are:** `Watched.Editor(id)` → `EditorShown`, `Watched.PotSettings(id)`,
  `Watched.PotCreate(id)`. The editor is opened on a Pot model the host holds (`Editor.Open(pot, entry?, separator)`).
- **Every action a `Call`:** `Editor.Change(editor, change)` (the sealed `EditorChange` as the wire's), `Editor.Save`,
  `Editor.AskDelete/Delete/CancelAsk/Close`; `Pot.Record(pot, rows)`, `Pot.Restore(pot, entry)`; the settings' and New
  pot's calls likewise. A wire class's simple name avoids the hosts' common names (CR 3a's `Action` trap).
- **An answer says what the host must act on at once** (CR 3a Phase 2's trap): `Editor.Save` answers
  `EditorSaved(done, notice)`, so a host closes the editor on the answer, never on a watched state that may arrive after.
- **Receipts by path and beside the JSON (ADR 0128):**
  - in: `Editor.AddReceipt(editor, path)` — the core opens the picked file (`FileSource`), reads at most
    `Receipts.MAX_BYTES`, sniffs it and mints its id. Nothing of its content crosses; the bridge's first open-picker
    consumer (R8).
  - shown: `Receipt.Open(pot, receiptId)` answers `Answer.Bytes(mediaType)` (decision 1): a receipt is at most 10 MiB
    and is decoded whole, so no reader; the Reader registry stays for Depot's and Memories' large blobs.
  - saved: `Receipt.Save(pot, receiptId, path)` into `FileSink` (decision 3), the name from `Receipt.Name`.
- **The live poll is a watch:** `Watched.PotLive(pot)` delivers no update of its own (`Update.Pot` carries what a tick
  changed); its lifetime is the poll's, so a host's dropped `Watch` (Rust) or disposed one (C#) stops it structurally.
  Rejected: a `Pot.Live`/`Pot.Unlive` pair, which a page torn down without its stop call would leave polling.
- `SecretFreeSchemaTest` holds unchanged: a path goes inward only, a receipt's bytes ride only as a payload.

### D4 — A receipt's picture and PDF on the desktops: the platform's own renderers, nothing written to disk

- **Images:** `gdk::Texture::from_bytes` (glycin's loaders, as avatars) / `BitmapImage` (WIC). A kind the host cannot
  decode (HEIC on a Windows without the codec, WebP without the Store's extension) is offered for saving, as Android
  offers `OTHER`.
- **PDF on Linux: poppler-glib through `poppler-rs`** — GNOME's own PDF stack (Papers, GNOME's document viewer, renders
  PDF through poppler), bound by `poppler-rs`, kept in GNOME's `World/Rust` group beside gtk-rs (0.26 in Debian testing,
  2026-08; remembered from a web search at refine, checked against crates.io at implement). A page is rendered by
  `poppler::Page::render` onto a cairo `ImageSurface` at the view's width and scale and shown as a `gdk::MemoryTexture`;
  the document opens from the bytes in memory (`Document::from_bytes`). Licence: poppler is GPL-2.0-or-later, which an
  AGPL-3.0-only app may use (GPLv3's section 13). The Containerfile gains `poppler-glib-devel`; the Flatpak must carry
  poppler-glib (native-apps § Open, the Flatpak's list). Rejected: PDFium (Google's, no GNOME packaging, a prebuilt
  binary in practice); MuPDF (not GNOME's, its Rust bindings a single vendor's); glycin (no PDF loader); handing the PDF
  to the system's viewer, which writes the decrypted receipt to a file another app reads.
- **PDF on Windows: `Windows.Data.Pdf`** — `PdfDocument.LoadFromStreamAsync` over an `InMemoryRandomAccessStream`,
  `PdfPage.RenderToStreamAsync` per page into a `BitmapImage`; part of the Windows SDK the app builds against, no NuGet
  package. Whether an unpackaged WinUI 3 app reaches it is checked first in Phase 5 (remembered: it is a desktop-callable
  WinRT API); if not, Phase 5 stops and records it, and WinUI's WebView2 PDF viewer is **not** the fallback (it needs the
  bytes at a URL or file).
- Both show at most `Receipts.PDF_PAGES_SHOWN` pages and say so when there are more (the Android words).

### D5 — The screens, each to its platform's guideline

- **Linux (GNOME HIG):**
  - "New entry" a header bar button (`list-add-symbolic`) on a writable Pot; a row opens the editor;
  - the editor an `AdwDialog` holding an `AdwNavigationView` (the form; the split's page; receipts; the history), the
    form as `AdwPreferencesGroup`s of `AdwEntryRow`s and `AdwComboRow`s (kind, currency with search, category, person),
    the date through a `gtk::Calendar` popover (as CR 3a's share form), Save the header bar's suggested action, delete a
    destructive row at the foot asked by an `AdwAlertDialog`; the refusal under its field (`AdwEntryRow`'s error style),
    the rate's note as the row's subtitle;
  - a receipt's viewer an `AdwDialog` (a `gtk::Picture`, or the PDF's pages in a `gtk::ScrolledWindow`) with Save;
  - settle-up's rows each with "Record", all with one, asked by an `AdwAlertDialog`;
  - settings' place editable in place (`AdwEntryRow` with apply, `AdwComboRow`, `AdwSwitchRow` for active);
  - New pot an `AdwDialog` from New;
  - notices as `AdwToast`s.
- **Windows (Fluent):**
  - "New entry" the content `CommandBar`'s primary command;
  - **the editor a page in the content frame, not a `ContentDialog`**: WinUI shows one `ContentDialog` at a time
    (CR 3a's trap), and the editor opens the receipt viewer and the delete's confirm over itself. The form in a
    `ScrollViewer` of `ComboBox`es, `TextBox`es (amounts are text; the core parses), `CalendarDatePicker`, an
    `AutoSuggestBox` for the currency; Save, Cancel and Delete in its `CommandBar`; the refusal an `InfoBar` and the
    field's description;
  - a receipt's viewer a `ContentDialog` (an `Image`, or the PDF's pages in a `ScrollViewer`) with Save;
  - settle-up's Record in place, asked by a `ContentDialog`; settings editable in place; New pot a `ContentDialog`;
  - notices as the page's `InfoBar`.
  - No new control library (the Community Toolkit's `SettingsCard` is not taken: the controls WinUI has suffice).
- **Every word** from `words.json`, reusing Android's and Apple's keys (R10); a key neither app has is added in English
  and German only where a desktop control needs one.
- **A host never rewrites a text field it is typing into from an update**: it sets a field's text from state only when
  the field is not focused or the state's text differs from what it sent last (D1: the core keeps typed text as typed).

### D6 — The live poll's lifetime is the shown Pot, as on the phones

The host watches `Watched.PotLive(pot)` while the Pot's page is mapped and its window is active (GTK: the page mapped
and `gtk::Window:is-active`; WinUI: the page loaded and `Window.Activated` not `Deactivated`), as Android's `STARTED`
and Apple's `scenePhase == .active`. Pull to refresh is a header bar / `CommandBar` "Refresh" (F5 / Ctrl+R) sending
`Pot.Refresh` (R2): desktops have no pull gesture.

### D7 — Today and the clock are the core's

A new entry's date, a settle-up's date, the rate's day and a restore's window are read from the core's clock through
`session/LocalDays` (`dayOf(now())`), as CR 3a made a link's end. No platform hands a date except one the person
picked (an ISO day). Android's `LocalDate.now()`/`System.currentTimeMillis()` and Apple's `Date()` in these paths go.

### D8 — Where the apps disagree, one rule (R5)

- **An amount always comes with its currency:** the core hands the base or the entry's currency with every amount it
  shows; Apple's `"EUR"` fallback goes. Where the implementer finds an amount the core hands without one, that is the
  defect fixed in the core.
- **Shared files into a Pot are judged by one rule, `PotShare.takes`** (count `MAX_FILES` and the byte budget, core
  constants); Android's own 40 MiB HEIC cap becomes that budget. The HEIC re-encode stays the device's (decoding is the
  platform's, ADR 0127 decision 1).
- **The editor's delete says `entry_delete`** on every app (Apple's `peopleDeleteAccept` was a person's word).
- **Saving a receipt** stays each platform's own door (gallery or save dialog, `ShareLink`, the desktops' save
  dialogs): a device door, not a rule.
- Technical unifications, recorded for the owner's veto.

### D9 — `satisfies: []`, as CR 3a

The Expenses use cases (`uc.expenses.*`) are satisfied by the web and both apps; this CR brings platform parity, whose
record is native-apps' table and § Built, by area, not a use case's acceptance criteria.

## Owner steps

None. Throwaway emulators and simulators; the VMs are disposable (owner 2026-11-10).

## Inventory (locators — run at implement time)

- What the apps still decide: `grep -nE 'LocalDate.now|Date\(\)|currentTimeMillis|ExchangeRates\.|RateNote|FormState|MAX_PER_ENTRY|receiptAllowed|restorable|recordPayments|potWrite|parentIn|defaultSpace|startLive|livePot' src/mobile/android/src/main/kotlin/me/raddatz/veyq/mobile/android/*.kt src/mobile/apple/Veyq/*.swift`
- The core's names a move touches, tests included: `grep -rn 'PotModel\|EntryForm\|PotView\b\|PendingReceipt\|creating' src/mobile/core/src src/mobile/bridge/src --include=*.kt -l`
- The bridge's Pot surface: `grep -n 'Pot\|Receipt\|Editor' src/mobile/bridge/src/commonMain/kotlin/me/raddatz/veyq/mobile/bridge/Wire.kt`
- The desktops' Pot pages and words: `grep -rn 'words::pot_\|Words\.Pot\|PotOpen\|PotRefresh' src/mobile/linux/src src/mobile/windows/Veyq`
- Words to reuse: `/usr/bin/python3 -c "import json; d=json.load(open('src/mobile/words/words.json')); print([k for k in d if k.split('_')[0] in ('ed','pot','entry','receipts','people','settings','history','problem','cat','create')])"`
- The flow surfaces: `grep -n 'Expenses\|a Pot' docs/crosscutting/native-apps.md docs/inbox.md`

## Phases

### Phase 1 — The entry editor in the core: `EntryEditor` (form, rate fill, receipts held, save, delete), `PotModel`'s tests, today the core's

Android and Apple thinned.

**Done when:**
- `commonTest` pins each moved rule, each bitten (mutated → its case red → restored, by a script): the refusal hidden
  until tried; a kind's change keeping what it may; the rate fill started by a foreign currency and dropped when the
  currency changed meanwhile; the receipts' count and size caps and a refused pick; a stale save read again and said;
  the skipped receipts' notice; delete only after asking; a new entry dated the core's today. `PotModelTest`: the live
  poll skipped while saving, a write's refusal as the message.
- `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` green.
- Gone from the apps: `FormState` and its conversions, both `RateNote`s, the editor's Compose/SwiftUI state machine, the
  receipts' count and caps, `LocalDate.now()`/`Date()` in the editor (the inventory's first locator finds none there).
- `:android:assembleDebug`, `xcodebuild -target Veyq -sdk macosx CODE_SIGNING_ALLOWED=NO` and a signed iOS 27 simulator
  build green; both desktops' `run.py build` green (nothing of theirs changes).
- Android (throwaway AVD, deleted after) and Apple (throwaway iPhone 18 Pro, deleted after), on a Pot copy: a new
  expense with two payers and an item split by percent, another currency with its rate filled, a refusal shown after
  Save, an edit, a stale save provoked from the web, a receipt added (image and PDF), delete asked and done — read as
  text first (`uiautomator dump` / the simulator's accessibility), unchanged from before.

### Phase 2 — The Pot's other screens in the core: record, restore, `PotSettings`, `PotCreateSheet`, receipts shown, D8

Android and Apple thinned.

**Done when:**
- `commonTest` pins, bitten: a settle-up recorded on the core's today; a restore refused outside its window and written
  inside it; the base currency refused once money is in; an erase only after asking; New pot's enable rule, its
  members re-read on a space change, `parentIn` from the place; `Receipts.kindOf` for a PDF, a JPEG and other bytes;
  `PotShare.takes` with the budget Android now uses; an amount's currency always there.
- Gone from the apps: the settings' confirms and currency guard, New pot's defaults, enable rule and `parentIn`
  (Apple's `"REFUSED"` literal), Apple's ImageIO sniff and its `potWrite` copy, Android's 40 MiB cap, `peopleDeleteAccept`
  on the editor's delete.
- Builds as Phase 1. Android and Apple on a Pot copy: settle-up one row and all, an entry deleted and restored, its
  history, the base currency changed on an empty Pot and refused on a full one, a category added and renamed, a person
  added, renamed, deactivated and erased, a member added, New pot from a level with two people, a PDF receipt opened
  (Android's page cap shown on a PDF of more than 20 pages), a picture shared into a Pot from the system's share sheet.

### Phase 3 — Bridge and desktops: the live poll, refresh, settle-up, an entry's history and restore

**Done when:**
- `DispatchTest` drives `Watched.PotLive`, `Pot.Refresh`, `Pot.Record`, `Pot.Restore` over a played Pot, bitten (each
  made a no-op → red; an unwatched poll left running → red).
- `LiveBridgeTest.aPotIsPolledSettledAndRestoredThroughCalls` (`VEYQ_LIVE`): an `App pot <n>` copy; an entry written
  by a second session seen through the poll within its cadence; one settle-up row and then all recorded and read back
  (the balances settled); an entry deleted by the second session and restored; the copy purged.
- `linux/run.py build` (clippy `-D warnings`, `cargo test`) and `windows/run.py build` (Veyq.Tests) green; both guards
  (`scripts/validate_mobile_words.py`, `validate_mobile_platform_guidelines.py`) green.
- Linux (container, Broadway on 8097) and Windows (VM), each en/light and de/dark, on `Linux pot` / `Windows pot` (a
  copy): an entry added on the web appears without a refresh while the window is active and not while another window
  is; Refresh; settle-up one and all, asked; an entry's history; a deleted entry restored; the copy purged.

### Phase 4 — Bridge and desktops: the entry editor (every kind, payers, items, split modes, currency and rate, refusals, preview, stale, delete)

**Done when:**
- `DispatchTest` drives `Editor.Open/Change/Save/AskDelete/Delete/CancelAsk/Close` and `Watched.Editor` over a played
  Pot, bitten (`Editor.Change` a no-op, `Save` answering before the write → red); `WireTest` samples every
  `EditorChange`.
- `LiveBridgeTest.anEntryIsWrittenEditedAndDeletedThroughCalls`: on an `App pot <n>` copy, an expense with two payers,
  items split by shares, percent and amounts; a refund; a transfer; another currency with its rate filled from the
  tenant's service (and typed where the test's tenant names none); a refusal answered; a stale save; delete; each read
  back from a fresh Pot open; the copy purged.
- Builds and guards as Phase 3; `cargo test` covers D5's text-field rule (a focused field kept).
- Linux and Windows, en/light and de/dark: every kind, the split's three modes, two payers, an item, a foreign currency
  with its rate's note, a refusal under its field, the preview, an edit, a stale save provoked from the web, delete
  asked; typing a long description and an amount keeps the cursor (D5).

### Phase 5 — Bridge and desktops: receipts (attach by path, show an image and a PDF, save one, the skipped notice)

**Done when:**
- First, on the VM: an unpackaged WinUI 3 build renders a one-page PDF through `Windows.Data.Pdf` (D4); recorded either
  way.
- `DispatchTest` drives `Editor.AddReceipt` (a path that does not open answered, a file over `MAX_BYTES` refused, the
  101st refused), `Receipt.Open` (a payload and its media type), `Receipt.Save` (nothing left at the path on a failure),
  bitten; `SecretFreeSchemaTest` unchanged and green.
- `LiveBridgeTest.aReceiptIsAttachedShownAndSavedThroughCalls`: on a copy, a JPEG and a PDF attached by path, saved
  with the entry, opened (each payload's SHA-256 the file's), saved to a path and read back; a receipt dropped by an
  edit gone; with `VEYQ_LIVE_STORAGE_FIT`'s one-byte quota held, the entry saved and the receipt named skipped (the quota
  put back however the run ends); the copy purged.
- The Containerfile gains `poppler-glib-devel`, `Cargo.toml` `poppler-rs` (its version read from crates.io, its
  maintenance checked: no deprecated crate), `Cargo.lock` updated by `run.py lock`; no NuGet package added.
- Linux and Windows, en/light and de/dark: a picture and a PDF picked through the system's open dialog, shown (a PDF of
  more than 20 pages shows 20 and says so), saved through the save dialog and read back from the container / the VM;
  the 101st refused; the skipped notice under the held quota.

### Phase 6 — Bridge and desktops: settings and people, New pot, a fresh Pot copied

**Done when:**
- `DispatchTest` drives the `PotSettings` and `PotCreate` calls and watches, bitten.
- `LiveBridgeTest.aPotIsCreatedSetUpAndCopiedThroughCalls`: New pot `App pot <n>` with two people in a folder; its base
  currency changed while empty, refused after an entry; a category added and renamed; a person added, renamed,
  deactivated and erased; a member added; the Pot copied (CR 3a's path) and the copy read back; both purged.
- Builds and guards as Phase 3. New's Pot entry is enabled on both desktops (CR 3a showed it disabled).
- Linux and Windows, en/light and de/dark: New pot from a folder and from a space's root, its settings and people as
  above, the copy, every refusal said; the Pots purged at the end. Windows ends with Stop trusting.

### Phase 7 — The docs

- ADR 0127's Consequences: the Pot's screen models moved (`EntryEditor`, `PotSettings`, `PotCreateSheet`, today the
  core's); its Built line.
- ADR 0128's Consequences: the first open-picker consumer (`Editor.AddReceipt`), a receipt shown as `Answer.Bytes`.
- ADR 0123's Consequences: Expenses on the desktops; D4's renderers.
- `src/mobile/CLAUDE.md`: the models, the bridge calls, the traps this CR met.
- native-apps: the Linux and Windows rows (Expenses whole), § Built, by area, § Open ("a Pot's writes are not built"
  gone; the Flatpak's carry list gains poppler-glib).
- `docs/apps/expenses.md` only where a rule moved changes what it says (D8).
- Published pages: none (R12); said so in the harvest.
- The CR's harvest, then the CR is deleted.

**Done when** the trust-chain guards are green.

## Not in this CR

- **Shared files into a Pot on the desktops** (the system's share-in, ADR 0129): GNOME has no share target, and Windows'
  needs a packaged app (MSIX, the packaging CR). The rule is one already (D8).
- **Drag and drop** of a receipt onto the editor or a Pot (Depot's CR brings drag and drop to the desktops first).
- **A camera capture** for receipts: neither phone has one (Memories' camera only).
- Reading a receipt's text on the device (#549), a reminder of what one owes (#550).
- Depot's and Memories' pages on the desktops (their CRs).

## Owner questions

None open. D8's unifications (one currency rule, one share-intake rule, one delete word) are technical and open to the
owner's veto. D4's renderers (poppler-glib on Linux, `Windows.Data.Pdf` on Windows) were decided here.

## Retrospective
