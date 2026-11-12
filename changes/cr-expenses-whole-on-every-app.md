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
  - CR 3a's lessons, now in `src/mobile/CLAUDE.md` § Traps, which hold here (an answer overtaking an update, one `ContentDialog` at a time,
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
| R11 | **A Pot's copy reaches both desktops already** (CR 3a: `LiveBridgeTest.aPotIsCopied…`, both desktops' passes). | ADR 0123's Consequences, the Linux app and the Windows app |
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

**Status: done (2026-11-12).**

**Built:**
- core `expenses/EntryEditor.kt` (ADR 0127 decision 2): state (`isNew`, `form` — core's `EntryForm` —, `people`,
  `editable`, `invalid`, `tried`, `refusal` once tried, `preview`, `rate` as `Rate(FILLING|FILLED|MISSING, day)`, `pending`,
  `canAddReceipt`, `refusedPick`, `saving`, `notice`, `asking`, `done`), `watch`, `change(EditorChange)` over the sealed
  set D1 names, `addReceipts(Picked)`, `dropReceipt`, `save()` → `Saved(done, notice)`, `askDelete`/`cancelAsk`,
  `delete()`, `close`; over a `Door` (view, today, newId, rate, save, delete). `PotModel.editor(entryId, separator,
  pending)` opens it over the open Pot: its saves and deletes are the model's writes (`writing(said = false)`: saving
  shown, the live poll skipped, the Pot's message left alone). `PotModel.today()` and `LocalDays.iso`/`isoOf`: today the
  core's. `Receipts.READ_LIMIT`: what a device reads of a picked file at most. `PotSession.uploadedReceipts` and
  `saveEntry(…, own)`: a save tried again lists what an earlier one uploaded, never uploads it twice.
- `PotModel` has a seam for its open Pot (`Opened`, `SessionOpened`; an internal constructor), the live poll's skip rule
  moved into it from `PotSession.live` (gone), and its `commonTest` (`PotModelTest`); `EntryEditorTest` with
  `PotFixture` in `commonTest`.
- Android `EntryEditorSheet` and Apple `EntryEditorSheet` draw the model; Apple's `AppModel` holds and watches it
  (`openEditor`, `edit`, `saveEntry`, `askDelete`, `deleteEntry`, `closeEditor`, `editorState`). Gone: `FormState` and its
  conversions, both `RateNote`s, the Compose state and SwiftUI `@State` machine, `rateFor`/`skippedReceipts` on both app
  models, the receipts' count and caps, `LocalDate.now()`/`Date()` in the editor and in a settle-up's date.
- `words.json`: `entry_delete_confirm` (en, de).

**Met:**
- `EntryEditorTest` 11/11, `PotModelTest` 4/4; 17 mutations, each its case red and restored by
  `scratchpad/e1/bite.py` (`/bin/cp -f`): the refusal hidden until tried, a kind's change, the fill started by a
  currency, an answer for a changed currency dropped, the size cap, the count cap, a refused pick said, a stale save
  reading the other version, carrying its own receipts, going on top, never uploading twice, the skipped notice, delete
  only once asked, a new entry dated the door's today; `PotModel`: today the core's clock, the poll skipped while saving,
  a refusal as the message — all 17 bit.
- `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` green.
- The inventory's first locator finds nothing in `EntryEditor.kt`/`EntryReceipts.kt`/`.swift` (what it still finds is
  Phase 2's: `EntryHistory`'s restore window, the downloads' `now`).
- `:android:assembleDebug`, `xcodebuild -target Veyq -sdk macosx CODE_SIGNING_ALLOWED=NO` and `xcodebuild -scheme Veyq`
  signed for the iPhone 18 Pro simulator green; `linux/run.py build` (clippy, `cargo test`) and `windows/run.py build`
  (the VM; Veyq.Bridge.Tests and Veyq.Tests passed) green, nothing of theirs changed.
- `validate_mobile_words.py`, `validate_mobile_platform_guidelines.py` green.
- Android (throwaway AVD `veyq_e1_throwaway`, deleted), read through `uiautomator dump`: Amsterdam copied through the
  move sheet and renamed `App pot e1`; a new entry dated the core's today; Save on the empty form said the payers'
  refusal, none before; "Museum e1" with Anna 30 and Ben 20, CHF with its rate filled ("Computed from the ECB's euro
  reference rates of Nov 11, 2026", 1.082954299329), an item "Tickets" 20 split 70/30 by percent (its hint at 70 %), the
  preview 31.41 / 22.74 EUR, a JPEG and a PDF picked in the system's picker, saved (€54.15); reopened as saved; edited on
  the iPhone meanwhile, the Android save said "Someone else changed this entry…" and showed the iPhone's category, the
  receipts kept; saved on top; delete asked, cancelled, asked and done (in the deleted entries); the copy trashed and
  deleted forever.
- Apple (the booted iPhone 18 Pro, iOS 27, German): the copy's entry opened as Android saved it, its category changed and
  saved (the stale save above); a new entry dated 12.11.2026, Save said the payers' refusal, a second payer added, GBP
  filled its rate in the German separator ("1,195914755196", "Berechnet aus den Euro-Referenzkursen der EZB vom 11. Nov.
  2026"); an entry deleted through the ask ("Diesen Eintrag löschen? …", "Löschen" — no longer "peopleDeleteAccept");
  the live poll showed Android's delete without a refresh.

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

**Status: done (2026-11-12).**

**Built:**
- core `PotModel`: every write of the Pot's screens is one `PotModel.Op` on the open Pot (`Opened.write`, which
  `SessionOpened` maps onto `PotSession`; a test plays them); `record(rows)` dated `today()`, refused to a reader or with
  no rows; `restorable(entryId)` and `restore(entryId)` on the core's clock — outside the window nothing is sent and the
  message is `NOT_RESTORABLE` (`Notices.potRefused` → `ENTRY_NOT_RESTORABLE`), inside it `restoreEntry` on
  `latestRevision`; `settings()`; `createSheet(shell, language)`. Gone: `beginWrite`/`endWrite`, `write(op on a
  session)`, `Creating`/`creating`/`clearCreating`/`create`.
- core `PotSettings` (screen model): `setBaseCurrency(code)` (nothing when it is the Pot's, refused and said once money is
  in, the digits the core's table), `addCategory`, `renameCategory`, `addPerson`, `addMember`, `rename`, `toggleActive`,
  `askErase`/`erase`/`cancelAsk` (only a settled person is asked about, the erase only once asked), each answering
  whether it was stored; its refusal is its own `notice` (the write's `said = false`).
- core `PotCreateSheet` (screen model): space (`defaultSpace` of the shell's place) and currency (`defaultCurrency` of
  the platform's language), the members read again on a space change (a late answer for a space left dropped by a
  counter), the people kept and typed (`PotCreate.People`), `canCreate(name)`, `create(name, typed)` with `parentIn`
  from the place it opened at, its refusal a notice, `done`.
- core `Receipts.PDF_PAGES_SHOWN` (20), `pagesShown`, `pagesCut` (word `receipts_pages_cut`, en + de) and
  `mediaTypeOf`; `PotShare.BUDGET` (64 MiB) and `PotShare.Intake` (each a receipt, at most `MAX_FILES`, all within the
  budget, `room` what a device still reads), `split` over it; `PotView.line` never says a foreign amount without its
  currency. `AppleSessions.receiptMediaType`.
- `commonTest`: `PotModelTest` (+7), `PotCreateSheetTest` (7), `ReceiptsShownTest` (4), `PotViewMoneyTest` (2);
  `PotFixture` gains members, own categories, a tombstone's `purgeAfter` and `currencyFixed`.
- Android: `recordSettleUp`, `restorable`/`restoreEntry`, `potSettings`/`settingsWrite`, `potCreateSheet`/`potCreated` on
  the app model; `PotSettings` draws core's `PotSettings` (the erase dialog is its `asking`), `PotCreateSheet` draws core's;
  the PDF viewer renders `Receipts.pagesShown` and says `pagesCut`; `ShareIn` judges by `PotShare.Intake` and reads a
  stream only up to `room` (the 40 MiB `HEIC_READ_LIMIT` gone); `money()` without a currency writes a number alone.
  Gone: `potWrite`, `createPot`, `membersOf`, `creating`, `today()`, `minorUnitsOf`.
- Apple: `recordSettleUp`, `restorable`/`restoreEntry`, `openSettings`/`settingsWrite`/`askErase`/`cancelErase`
  (`settingsState`), `openCreateSheet` and its actions watched (`createState`); views `PotSettingsTab` and `NewPotSheet`
  (no view shadows the core's classes); the receipt's share type from `Receipts.mediaTypeOf` (ImageIO's sniff and import
  gone from `EntryReceipts`), `IncomingFiles` over `PotShare.Intake` (its own `budget` gone), `money()` without the
  `"EUR"` fallback. Gone: `potWrite`, `CreatingPot`, `createPot(scopeId:…)`, `membersOf`, the `"REFUSED"` literal.

**Met:**
- 34 mutations, each its case red and restored by `scratchpad/e2/bite.py` (`/bin/cp -f`), all bit: a settle-up on the
  core's today, refused to a reader, its refusal the message; a restore refused outside its window, on the core's clock,
  said as not restorable, written on the latest revision; the base currency refused once money is in, its own currency
  nothing to do, its digits the core's; an erase only once asked, asked only of the settled; the settings' refusal its
  notice and not the Pot's, a blank person never sent; New pot needs a name and a space, re-reads members on a space
  change, drops another space's member, drops a late answer, lands in the folder shown only in the space shown, starts
  on the shown space in the language's currency, takes a typed name, the currency's digits, says its refusal;
  `kindOf` a PDF, the media type from the bytes, the page cap and its notice, the share's budget and count; an amount's
  currency always there, a foreign one its own.
- `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest :android:assembleDebug` green;
  `xcodebuild -target Veyq -sdk macosx CODE_SIGNING_ALLOWED=NO` and `xcodebuild -scheme Veyq` signed for the iPhone 18 Pro
  simulator green (the iOS one again after the erase popover's fix).
- The inventory's first locator finds in the apps neither `potWrite`, `parentIn`, `defaultSpace`, `restorable(`'s
  platform clock nor `"REFUSED"`; `grep -n 'ImageIO\|CGImage' apple/Veyq/EntryReceipts.swift` finds nothing;
  `HEIC_READ_LIMIT` is gone.
- `validate_mobile_words.py`, `validate_mobile_platform_guidelines.py`, `validate_changes.py` green.
- `linux/run.py build` (clippy, `cargo test` 23 passed) and `windows/run.py build` (the VM: Veyq.Bridge.Tests 4 and
  Veyq.Tests 17 passed) green, nothing of theirs changed; the macOS build again green after the last Swift change.
- Android (throwaway AVD `veyq_e2_throwaway`, created and deleted), read through `uiautomator dump`: Amsterdam copied
  through the move sheet and renamed `App pot e2`; settle-up one row ("Ben pays Anna" €67.34, a transfer dated Nov 12,
  2026, both settled); the base currency "Fixed: the pot holds entries."; a person added ("Cleo e2", "Dan e2"), renamed
  ("Dana e2"), deactivated, its remove asked, cancelled (kept), asked and done; member "other" added; a category added
  and renamed ("Snacks e2 renamed"); an expense split among four with the 25-page PDF picked, so three settle-up rows,
  recorded all at once (three transfers of Nov 12); the PDF opened: "The first 20 of 25 pages are shown. Save the
  receipt to see all of them."; "Taxi" deleted (asked), its sheet "It can be restored until Dec 12, 2026", its history
  (Deleted / Taxi · €9.99 with writer and time), restored ("edited"); New pot from a folder of my own (`App folder e2`)
  with a member and a typed person ("App pot e2 new", USD as the English default), its base currency changed to AUD
  while empty ("In AUD"); `e2-share.jpg` shared from the Files app (long-press, Share, veyq), the pick mode, `App pot
  e2`, the editor with the receipt, saved ("Shared e2" €5.00) and the picture opened.
- Apple (the booted iPhone 18 Pro, German): `App pot e2` restored from the space's trash; New pot ("Neue Kasse") on the
  shown space in EUR, its members offered, a member added, "Erstellen" disabled without a name, cancelled; the settings
  ("Steht fest: Die Kasse hat Einträge."); settle-up one row and then "Alle eintragen" (everyone "ausgeglichen"); Cleo
  deactivated; "other" erased through the asked popover; Cleo's erase asked and dismissed by a tap outside (kept) and
  asked again; the shared receipt opened as a picture; "Shared e2" deleted through its ask and restored ("Er kann bis 12.
  Dez. 2026, 1:43 wiederhergestellt werden").

### Phase 3 — Bridge and desktops: the live poll, refresh, settle-up, an entry's history and restore

Also: the Pot page draws the core's head with its cover on both desktops (no desktop shows a cover outside its dialog
yet, native-apps § Open; `HeaderShown.cover`, `Header.CoverPicture` and `NodeCover.THUMBNAIL_EDGE` are there for it).

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

**Status: done (2026-11-12).**

**Built:**
- core `PotModel.live()` waits for the Pot's read when started while it opens and ends when that Pot closes or another
  opens (a poll never reads a Pot no screen shows); `refusal()` (a write's refusal as `Notices.potRefused`, a write
  refused before it was sent a plain refusal), for a platform that says it from the answer; a public constructor over a
  played Pot (`Opened`) and a public `PotView.Source`, the bridge's test seam; `PotView.Shown` gains `deleted` and
  `canRecordAll`. `PotModelTest` +3.
- bridge: `Watched.PotLive` (`PotModel.live` inside the watch's own job: a dropped watch stops it), `Pot.Record` and
  `Pot.Restore` (`Said` with the refusal), `Pot.History` → `EntryHistory` (rows with `historyLine`, writer, time;
  deleted; restorable on the core's clock; until), `Cover.Cut` → `CoverCut` (`CoverFrame.pixels`), `NO_SUCH_ENTRY`.
  `DispatchTest.aPotIsPolledWhileWatchedRefreshedSettledAndRestoredThroughCalls` over a played Pot and
  `aCoversCutIsTheCoresPixels`; `WireTest` samples; `LiveBridgeTest.aPotIsPolledSettledAndRestoredThroughCalls`.
- Linux: `pot.rs` — the poll watched while the page is mapped and the window active, Refresh (header bar,
  `win.pot-refresh`, F5 / Ctrl+R), settle-up rows' Record and Record all asked by an `AdwAlertDialog`, deleted entries,
  every row opening `entry_history.rs` (an `AdwDialog`: the history, a deleted entry's window and Restore), one
  `ScrolledWindow` kept across redraws; `header.rs` draws the head (`Header::hero`: `Header.CoverPicture`, `Cover.Cut`,
  cairo, 16:9 by the width given), placed first in each place's scroll.
- Windows: `PotPage` — the poll watched while loaded and `Window.Activated` not `Deactivated`, Refresh (CommandBar,
  F5 / Ctrl+R accelerators), settle-up asked by `Dialogs.Ask` (the action the default), deleted entries, rows opening
  `EntryHistoryDialog` (Restore its primary button), the scroll kept; `NodeHeader.Hero` (WIC `BitmapTransform.Bounds`
  of `Cover.Cut`). `Veyq.Drive` types F5.
- Words: `balances_record_question`, `balances_record_hint`, `balances_record_all_question`, `balances_record_all_hint`
  (plural), `pot_refresh`, `history_by` (en, de).
- `PotLiveTest` reads `VEYQ_ENV_FILE` (a worktree has no `.run/.env`) and `VEYQ_LIVE_POT_THIRD=<pot>` adds Cleo, who
  pays for everyone (a Pot that settles in two payments).

**Met:**
- 12 mutations, each its case red and restored by `scratchpad/e3/bite.py` (`/bin/cp -f`), all bit: `Pot.Refresh`,
  `Pot.Record`, `Pot.Restore` a no-op; `Pot.History`'s restorable not the core's clock; `PotLive` a no-op; `PotLive` left
  running after its unwatch (launched outside the watch's job); `Cover.Cut` not the core's pixels; `live()` giving up
  on a Pot still opening (core and bridge); `live()` outliving its Pot; a refusal before sending a plain refusal (core
  and bridge).
- `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` green; with `VEYQ_LIVE`,
  `LiveBridgeTest.aPotIsPolledSettledAndRestoredThroughCalls` green: Amsterdam copied as `App pot 8604`, the other
  device's entry seen through the poll after 20.3 s, one settle-up row and all recorded (every balance settled), the
  entry deleted by the other device, its history (Deleted, Live check · €7.00, by name) and restore, the copy purged.
- `linux/run.py build` (clippy `-D warnings`, `cargo test` 23 passed) and `windows/run.py build` (Veyq.Bridge.Tests,
  Veyq.Tests passed) green; `:android:assembleDebug`, `xcodebuild -target Veyq -sdk macosx CODE_SIGNING_ALLOWED=NO`
  and `xcodebuild -scheme Veyq` signed for the iPhone 18 Pro simulator green.
- `validate_mobile_words.py`, `validate_mobile_platform_guidelines.py`, `validate_changes.py` green.
- Linux (container, Broadway on 8097, headless Chrome), en/light then de/dark, on `Linux pot` (Amsterdam copied by the
  header's Copy, renamed): its cover set by `CoverCropLiveTest` kept, Cleo by `VEYQ_LIVE_POT_THIRD`, entries written
  elsewhere by `PotLiveTest` (`VEYQ_LIVE_POT_WRITE`, `VEYQ_LIVE_POLL`). With the download's save dialog open (the main
  window inactive, stderr "pot live poll off") an entry written elsewhere did not show for 40 s; the dialog cancelled
  ("on"), it showed at once (the poll catches up). Refresh by button, F5 and Ctrl+R (stderr "pot refresh"); a live
  entry's history; the deleted "Poll check" (its window "until 12/12/2026", history Deleted / Poll check · EUR 2.00)
  restored; one settle-up row asked and recorded, then Record all (2 transfers) asked and recorded, every balance
  "settled"; the head 16:9 with the crop's right half (blue, white disc); the scroll kept across an F5. The copy
  trashed and deleted forever. Shots: `scratchpad/e3/lx/`.
- Windows (VM, `run.py run --devlogin --unlock`, `drive`), en-US/light then de-DE/dark, on `Windows pot` (the same
  way): with the save picker open ("pot live poll off" in the log) an entry written at 03:06:38 did not arrive until
  the picker was cancelled at 03:07:22, then at once ("pot update: 15 entries"); Refresh by command, Ctrl+R and F5;
  an entry's history; the deleted entry restored; one row and all recorded; the head. The copy trashed and deleted
  forever; the pass ended with "Stop trusting" (the "Not now" its first start answered forgotten). Shots:
  `scratchpad/e3/win/`.

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

**Status: done (2026-11-12).**

**Built:**
- core: `PotModel.Opened` gains the editor's doors (`saveEntry`, `deleteEntry`, `rateFor`; `SessionOpened` maps them onto
  `PotSession`, a played Pot plays them), so the editor's writes go through the Pot the model opened; `EditorChange`,
  `EntryForm.Kind`/`Mode` and `EntryEditor.Rate` are `@Serializable` under names no host type takes (`Edit.<name>`,
  `EntryKind`, `SplitMode`, `RateState`, `EditorRate`); `EntryForm.typedIn` (what was typed for a person in a split's mode).
- bridge: `Editor.Open(pot, entry?, separator)` → `EditorOpened` (refused `NO_SUCH_ENTRY`), `Editor.Change` applied on
  the host's thread in the order called, `Editor.Save` / `Editor.Delete` → `EditorSaved(done, notice)` once the write
  ended, `Editor.AskDelete/CancelAsk/Close`, `Watched.Editor` → `Update.Editor(EditorShown)` (each field one core
  function's answer: the form, people, payers with `canRemovePayer`/`nextPayer`, `payersTotal`, items, the rest
  (`showsRest`), each split with `collapsedWords`, `typedIn` and `splitHint`, the categories' words, `Currencies.known`,
  the rate's fill, the preview, the refusal's words at its field); a closed Pot closes its editors (`Registry.removeIf`).
  `DispatchTest.anEntryIsWrittenEditedAndDeletedThroughTheEditorsCalls`, `WireTest` samples of every `EditorChange`,
  `LiveBridgeTest.anEntryIsWrittenEditedAndDeletedThroughCalls`.
- Linux: `entry_editor.rs` (an `AdwDialog` with an `AdwNavigationView`: the form, a split's page, the history page from
  `entry_history::page`), `typed.rs` (D5's rule, `cargo test`), "Add entry" the header bar's `create_action`, a live
  row opening the editor and a deleted one its history; `bridge::call` reaches the core when called;
  `said::decimal_separator`.
- Windows: `EntryEditorPage` (a page in place of the Pot's places; Save, Delete, Cancel in its `CommandBar`), `TypedText`
  (`TypedTextTest`), "Add entry" the content's `CreateAction.Button` while the places show; `PotPage` keeps the head's
  column itself and logs a draw that throws.
- words: `generate_words.py` writes one `%` into the desktops' catalogs (words.json's `%%` is Android's and Apple's
  format-string escape). `PotLiveTest`: `VEYQ_LIVE_POT_EDIT="<pot>|<title>"` retitles an entry from another device.

**Met:**
- 11 mutations, each its case red and restored by `scratchpad/e4/bite.py` (`/bin/cp -f`), all bit: `Editor.Change` a
  no-op; edits launched instead of applied in order (400 titles sent unawaited, the last not the one shown); `Editor.Save`
  answering before the write; `Editor.Delete`, `AskDelete`, `CancelAsk`, `Close` each a no-op; a closed Pot keeping its
  editors; `Watched.Editor` not the model's state; the editor's save and rate not the Pot's door.
  `scratchpad/e4/bite_typed.py`: D5's rule bitten twice in `cargo test` (a focused field set from its own echo; from
  every state).
- `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` green; with `VEYQ_LIVE`,
  `LiveBridgeTest.anEntryIsWrittenEditedAndDeletedThroughCalls` green: Amsterdam copied as `App pot 18030`, a save refused
  at `payers`, an expense with two payers and items by shares, percent and amounts read back from a fresh open, a refund
  and a transfer, CHF's rate filled from the tenant's service (1.082954299329 of 2026-11-11), a stale save (another
  device's title shown, the next save on top), the transfer deleted (among the deleted), the copy purged.
- `linux/run.py build` (clippy `-D warnings`, `cargo test` 28 passed) and `windows/run.py build` (Veyq.Bridge.Tests,
  Veyq.Tests 22 passed) green; `:android:assembleDebug`, `xcodebuild -target Veyq -sdk macosx CODE_SIGNING_ALLOWED=NO`
  and `xcodebuild -scheme Veyq` for the iPhone 18 Pro simulator green; `validate_mobile_words.py`,
  `validate_mobile_platform_guidelines.py` green.
- Linux (container, Broadway on 8097, headless Chrome), en/light then de/dark, on `Linux pot` (Amsterdam copied by the
  header's Copy, renamed): Save on the empty form said the payers' refusal under them, none before; a long title typed,
  four keys left, `X` landed there (the cursor kept); an amount typed `4050`, two left, the separator: `40.50` / `40,50`;
  two payers, an item split by percent (the hint "70.00 %, not 100 %" — `%%` before the words fix), the rest by amounts
  (en) and by shares (de), the preview (Anna 44.50, Ben 16.00); CHF / GBP with the rate's note as the currency row's
  subtitle (de `1,195914755196`); saved; the editor reopened, `PotLiveTest` (`VEYQ_LIVE_POT_EDIT`) retitled the entry
  elsewhere, Save said "Someone else changed this entry…" with their title in the focused field, saved on top; a refund;
  a transfer's Save refused at its amount (the row's error style); its history page; its delete asked and done. The copy
  trashed and deleted forever. Shots: `scratchpad/e4/lx/`.
- Windows (VM, `run.py run --devlogin`, then `--unlock`; `drive`), en-US/light then de-DE/dark, on `Windows pot` (the
  same way): the refusal in the InfoBar and under the payers; the title typed with the caret kept (`thex day`,
  `Txages`) and an amount typed into its middle (`410450` from `4050`); two payers, an item by percent (the hint
  "70,00 %, not 100 %"), the rest by amounts / shares, the preview; CHF / GBP filled (the note the rate box's
  description); saved; a stale save through `VEYQ_LIVE_POT_EDIT` (the InfoBar, their title); a refund; a transfer
  refused at its amount; the history expander; the delete asked by its `ContentDialog` and done. The copy trashed and
  deleted forever; the pass ended with "Diesem Gerät nicht mehr vertrauen" (`manage servers: None`). Shots:
  `scratchpad/e4/win/`.

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

**Status: done (2026-11-12).**

**Spike first (D4):** `Windows.Data.Pdf` reaches an unpackaged WinUI 3 build. `PdfPages` (the app's renderer:
`PdfDocument.LoadFromStreamAsync` over an `InMemoryRandomAccessStream`, a page to a PNG in memory) and `PdfPagesTest`, run
by `windows/run.py build` in the unpackaged x64 `Veyq.Tests` process on the VM (ARM64, x64 emulation): a one-page PDF
rendered at 400 × 200 with its left half black, 25 pages counted, bytes that are no PDF refused — committed first
(`fc4665032`), before any other code of the phase. No NuGet package.

**Built:**
- core: `PotModel.Opened.openReceipt` (`SessionOpened` maps it onto `PotSession`, a played Pot plays it), so
  `PotModel.openReceipt` reaches the open Pot's door; `Receipts.picked(path, bytes)`: a desktop's dialog names no type,
  so a picked file is named by its path's last part (`/` or `\`) and typed by its bytes (`mediaTypeOf`), bytes that are no
  image and no PDF refused whatever the name claims (`ReceiptsShownTest`).
- bridge: `Editor.AddReceipts(editor, paths)` (each path read by the core, at most `Receipts.MAX_BYTES`;
  `EntryEditor.addReceipts`), `Editor.DropReceipt` (applied in the order called, as an edit), `Receipt.Open(pot,
  receipt)` → `Answer.Bytes(mediaType)` with the receipt beside the JSON, `Receipt.Pages(total)` → `ReceiptPages(shown,
  cut)`, `Receipt.Save(pot, receipt, path)` (`NO_RECEIPT`, `NOT_WRITTEN`, nothing left at the path); `EditorShown.receipts`
  (`EditorReceipt`: id, name, held, opens), `canAddReceipt`, `refusedPick`. The native `DeviceFiles.read` joins its parts
  once (a 10 MiB receipt was appended 160 times over). `DispatchTest.aReceiptIsAddedByPathOpenedAndSavedThroughCalls`,
  `WireTest` samples, `LiveBridgeTest.aReceiptIsAttachedShownAndSavedThroughCalls`.
- Linux: the editor's Receipts group (Add receipt: GTK's open dialog for images and PDFs, several at once; a kept
  receipt's row opens the viewer; each taken off by its button; the core's refusal under the group); `receipt_viewer.rs`
  (an `AdwDialog`, Save a copy in its header bar; `gdk::Texture::from_bytes`; poppler-rs `Document::from_bytes`, each page
  onto a cairo surface at the dialog's width in device pixels as a `gdk::MemoryTexture`, the page cap and its words; GTK's
  save dialog → `Receipt.Save`; `cargo test` renders a PDF from memory). `Cargo.toml`: `poppler-rs` 0.26 (`v0_82`);
  Containerfile: `poppler-glib-devel`; `Cargo.lock` by `run.py lock` (two crates added).
- Windows: `EntryEditorPage`'s Receipts section (the open picker, several files; rows open `ReceiptViewerDialog`; Remove
  buttons; the refusal); `ReceiptViewerDialog` (a `ContentDialog` 640 wide, Save a copy its primary button: `BitmapImage`,
  `PdfPages` at the dialog's width × the rasterization scale, the cap's words, the save picker → `Receipt.Save`).
- words: `receipts_held`, `receipts_saved`, `receipts_not_saved` (en, de).
- The three fixes Phase 4 found, each its own commit: `EntryForm.of` writes a stored rate in the reader's separator
  (every app; `EntryFormTest`, bitten); a Pot's Windows page offers Add entry alone (New is the level's); the Linux editor's
  history page says History once (its header bar names it).

**Met:**
- The spike as above (`Veyq.Tests` 25 passed, `PdfPagesTest` 3 of them).
- 11 mutations of `Bridge.kt`, each `DispatchTest.aReceipt*` red and restored by `scratchpad/e5/bite.py` (`/bin/cp -f`),
  all bit: `AddReceipts` a no-op; a picked file read past the cap (the test's files record what the core asks to read);
  `Receipt.Open`'s payload not the receipt; no media type; `Receipt.Save` opening the path before the receipt (a file
  left); the picked ones not shown; a kept one not openable; `canAddReceipt` and `refusedPick` not the core's; the page cap
  not the core's; `DropReceipt` a no-op. `EntryFormTest.aStoredRate*` bitten (the verbatim rate red).
  `SecretFreeSchemaTest` unchanged and green.
- `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` green; with `VEYQ_LIVE` and
  `VEYQ_LIVE_RECEIPT_NO_ROOM=1`, `LiveBridgeTest.aReceiptIsAttachedShownAndSavedThroughCalls` green: Amsterdam copied as
  `App pot 62240`, a JPEG and a two-page PDF attached by path and saved, read back from a fresh open, each opened
  (`image/jpeg`, `application/pdf`) and saved to a path at its file's SHA-256, the PDF taken off and gone; under the
  one-byte quota a save named `Live Quittung.jpg` left out; the quota put back (204, none before); the copy purged.
- `linux/run.py build` (clippy `-D warnings`, `cargo test` 30 passed) and `windows/run.py build` (Veyq.Bridge.Tests 4,
  Veyq.Tests 25 passed, the NuGet gate green, no package added); `:android:assembleDebug` and `xcodebuild -target Veyq
  -sdk macosx CODE_SIGNING_ALLOWED=NO` green; `validate_mobile_words.py`, `validate_mobile_platform_guidelines.py`,
  `validate_changes.py` green.
- poppler-rs: 0.26.0 of 2026-02-21 on crates.io (MIT, the GNOME `World/Rust` group's, its repository active 2026-09),
  binding the glib and cairo gtk4 0.11 uses; not deprecated or archived. Fedora 44 carries poppler-glib 26.01.
- Linux (container, Broadway on 8097, headless Chrome), en/light then de/dark, on `Linux pot` (Amsterdam copied by the
  header's Copy, renamed): a JPEG and the 25-page PDF picked together in GTK's open dialog (held, "Added when the entry is
  saved"), saved; the PDF shown, 20 of 25 pages drawn and "The first 20 of 25 pages are shown…" / "Die ersten 20 von 25
  Seiten…"; Save a copy into the read-only source folder said "The copy could not be saved." (`NOT_WRITTEN`), into `/tmp`
  "Copy saved." / "Kopie gespeichert.", read back in the container at the file's SHA-256 (PDF and JPEG); the picture
  shown (480×320); 101 files picked at once: 100 held, Add receipt gone, the refusal under the group (en, de); the PDF
  taken off and saved, gone on reopening; the editor's history page with one History; under the held quota a save toasted
  "Saved without receipt-1-page.pdf: the space's storage is full." / "Ohne receipt-1-page.pdf gespeichert: …". The copy
  trashed and deleted forever. Shots: `scratchpad/e5/lx/`.
- Windows (VM, `run.py run --devlogin`, then `--unlock`; `drive`), en-US/light then de-DE/dark, on `Windows pot` (the
  same way): in a Pot the CommandBar holds Add entry and no New (`w01-pot-commands.png`); a JPEG and the PDF picked in the
  open picker, held, saved; the PDF shown (20 `receipt-page` images of 640×906, the cap's words in en and de), Save a copy
  through the save picker "Copy saved." / "Kopie gespeichert.", read back on the VM at the file's SHA-256 (`certutil`, PDF
  and JPEG); the picture shown (480×320); 101 picked: 100 rows, Add receipt gone, the refusal (en, de); the PDF taken off
  and gone; under the held quota "Saved without receipt-1-page.pdf…" / "Ohne r003.jpg gespeichert: …". The copy trashed and
  deleted forever; the pass ended with "Diesem Gerät nicht mehr vertrauen" (`manage servers: None`). Shots:
  `scratchpad/e5/win/`.

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

### Phase 1 (2026-11-12)

- **A stale save now does what the web does, which neither app did.** Both apps kept the form and the revision it was
  based on after a `409`, so the next save was stale again; and a stale or refused save uploaded its picked receipts
  again under the same ids. The editor reads the other writer's version into the form (`ED_STALE` says "here is their
  version"), carries the receipts it uploaded, and saves on top; the record's id is minted at open, as the web's
  `recordId`. Decided here (the web's rule and words), open to the owner.
- **The delete is asked** (D1's `asking`): the web asks nothing for an entry; a new word, `entry_delete_confirm`, says
  that it can be restored. The editor's delete already says `entry_delete` on Apple (D8's word, done in this phase since
  its editor was rewritten anyway).
- **An answer for a rate is dropped by a counter, not by cancelling** its request: a cancelled request makes "dropped
  when the currency changed" unbiteable, and USD → GBP → USD would otherwise let the first USD answer fill the field.
- **A text field must read the model synchronously after an edit**: both hosts set their drawn state from
  `editor.current` right after `change(…)` (Compose's `mutableStateOf`, Apple's `editorState`) and from the watch only for
  what the core changes itself (the rate). A field bound to a value that arrives a frame later loses keystrokes.
- **The editor's writes leave the Pot's message alone** (`PotModel.writing(said = false)`): the editor says its own
  outcome; the apps called `clearPotMessage()` on every close before.
- **A device reads at most `Receipts.READ_LIMIT`** of a picked file (Android a bounded read — `readNBytes` is API 33,
  the app's minimum 31 —, Apple `FileHandle.read(upToCount:)`, which read the file whole before); the refusal is the
  core's.
- **Earlier than planned:** a settle-up's date is the core's today on both apps (`PotModel.today()`), since `FormState`,
  which dated it on Apple, went. The restore window (`EntryHistory`) is still the platform's clock: Phase 2.
- **Deviations:** the platforms' views are `EntryEditorSheet` (CR 3a's shadowing trap); the copy is named `App pot e1`
  (renamed after the copy), not `App pot <n>`; the stale save was provoked from the iPhone simulator, a second device,
  not from the web (the web wants a password typed); the Apple pass ran on the booted iPhone 18 Pro the brief named, not a
  throwaway one, and the app stays installed and signed in there (no sign-out at the end).
- **Not verified on Apple** (the Simulator tool types no text): a typed title or amount, so no new entry saved there,
  no item split, no receipt picked (no files in the simulator's Photos or Files); Android's pass and the core's tests
  hold them. A shared file into a Pot (ADR 0129) now opens the editor through `openEditor(nil, pending:)` on Apple and
  `entryEditor(…, pending)` on Android — not passed by hand in this phase (Phase 2 passes a share).
- **Left in the dev instance by my runs:** nothing; `App pot e1` was deleted forever from the trash.

### Phase 2 (2026-11-12)

- **One write type for the Pot's screens (`PotModel.Op`)**: the record, the restore and every settings and people write
  go through `Opened.write(op)`, so `commonTest` plays each one without a session. The apps' `potWrite`, which handed a
  lambda over the session, and Apple's `beginWrite`/`endWrite` copy are gone. Decided here.
- **A restore outside its window says why** (`NOT_RESTORABLE` → `entry_not_restorable`, an existing word) instead of
  a generic refusal; nothing is sent. The window is `PotModel.now`, the clock the app hands the core once — the same
  device clock as before, but read in one place.
- **The settings say their own refusal** (`PotSettings.State.notice`, the write `said = false`), as the editor does:
  the Pot's message is left to the settle-up and the restore. The not-ready Pot's currency field shows the settings'
  notice now.
- **A share is judged in order by one intake** (`PotShare.Intake`): each a receipt, at most 100, all within 64 MiB —
  Apple's budget made every app's. Android's 40 MiB per-file read cap became "read no more than what is left of the
  budget" (`room`), a bounded read, so a provider that hides a stream's size is cut off too. A refused file no longer
  reorders the rest (`split` kept the order of the refused ones; now both lists keep the share's order).
- **An amount without a currency is a number alone on every platform** (D8): the desktops' rule, made Android's
  (was the locale's currency) and Apple's (was `"EUR"`). The wire keeps the currency nullable (`Piece.Money`,
  `NoticeArg.amount`) rather than changing the desktops' generated types in a phase that does not touch them; the core
  never hands one without — `PotViewMoneyTest` walks every amount a view says, and `PotView.line` drops a foreign
  amount whose currency is missing. Open to the owner's veto with the other D8 unifications.
- **The PDF's page cap is said** (`receipts_pages_cut`, a new word, en + de): Android rendered 20 pages silently before.
  Apple's PDFKit view pages lazily and shows all, which `PDF_PAGES_SHOWN` allows as "at least".
- **A trap met on Apple**: an iPhone shows a `confirmationDialog` as a popover with no Cancel button; a tap outside
  dismisses it, and a binding that ignores the dismissal (the cancel-before-action trap's answer) left the erase asked.
  The dismissal now cancels one main-queue turn later unless the destructive action confirmed meanwhile. Moved to
  `src/mobile/CLAUDE.md` § Traps at the docs phase.
- **New pot takes the shell's spaces** (`ShellModel.State.lists.spaces`), not the platform's copy; the name field is
  Apple's `peopleName` label as before (not changed here).
- **Deviations:** the Apple pass ran on the booted iPhone 18 Pro (signed in, as the brief said), not a throwaway one; it
  restored `App pot e2` from the trash rather than copying Amsterdam again. The Windows and Linux builds were the only
  desktop checks (Phase 2 changes nothing of theirs).
- **Not verified on Apple** (the Simulator tool types no text): a person or category added or renamed, New pot created,
  the base currency changed on an empty Pot, a share from Photos (no `--remember` trust for the extension was set up);
  Android's pass and `commonTest` hold them. No PDF of more than 20 pages on Apple (PDFKit shows all; nothing to cap).
- **Left in the dev instance by my runs:** `App pot e2` and `App folder e2` (holding `App pot e2 new`) are in the space's
  trash, removed for good on Dec 12, 2026. Deleting them forever was refused by this session's permission classifier
  (irreversible deletion); the owner purges them from the trash, or the server does on that day.

### Phase 3 (2026-11-12)

- **The live poll is a watch whose job is the poll** (D3 as decided): `flow { pot.live() }` emits nothing; the bitten
  alternative — a poll launched beside the watch — kept polling after its unwatch. The poll had two holes a desktop
  meets and a phone did not: started before the open's answer it returned at once (a desktop watches the poll as its
  page appears), and it kept polling a Pot closed under it. Both are `PotModel.live()`'s now (`commonTest` and the
  bridge's case, bitten). Decided here.
- **A write of the Pot's screens answers with what it says** (`Said`: the refusal, or none), never left to the watched
  message, which may arrive after the answer (CR 3a's trap); `PotModel.refusal()` maps a write refused before it was sent
  (nothing to record, a reader) to a plain refusal rather than nothing.
- **An entry's history is a call, not part of the view** (`Pot.History`): restorable is judged on the core's clock at
  the moment it is asked, and the history of every entry would bloat each update of the live poll. The deleted entries
  are in the view (`PotView.Shown.deleted`). Every live entry's row opens its history in this phase; Phase 4 opens the
  editor there and takes the history as its page (D5). Decided here.
- **Settle-up is asked on the desktops** (D5), where the phones record on a tap: new words for the question and for
  what is recorded (a transfer dated today; all: their count). Fluent's primary button is the default there
  (`Dialogs.Ask`); `Dialogs.Confirm` stays the destructive one.
- **The head is drawn from `Cover.Cut`**, a new call over `CoverFrame.pixels` (the math the core's): Linux cuts the
  downloaded texture into a cairo surface on a `DrawingArea` sized 16:9 from its width (a `GtkAspectFrame` gave the
  picture no height-for-width, and a height set inside `resize` was ignored until set on the next turn of the loop);
  Windows decodes with WIC's `BitmapTransform.Bounds`. A cover set from another device shows on the page's next open:
  the header reads it when it opens, as before (and on every platform).
- **A redraw must not lose the scroll** once updates arrive unasked: both desktops rebuilt their scroll on every state,
  so each live update jumped to the top (seen in the Linux pass). Both keep one scroll now; another place starts at
  its top.
- **The live tests need a third person for "Record all"** (the seeded Pots settle in one payment): `PotLiveTest`'s
  `VEYQ_LIVE_POT_THIRD`; and `PotLiveTest` read only `.run/.env`, which a worktree lacks — it reads `VEYQ_ENV_FILE` now.
- **Deviations:** the change elsewhere came from the core's live tests (another session, `PotLiveTest`) rather than the
  web (the web wants a password typed); "another window active" was each desktop's own save dialog (Broadway's GTK
  file dialog, Windows' save picker), the window the pass can raise. Times in the Linux container read UTC (its zone is
  not set for `run`; `run.py test` sets Berlin) — not changed here. The Windows pass's first start answered the offer
  with "Not now" (an offer stood open) and its end forgot it with "Stop trusting".
- **Phase 7 harvests:** `src/mobile/CLAUDE.md` names `VEYQ_LIVE_POT_THIRD` and `aPotIsPolled*` already; the traps above
  (a live update must keep the scroll; GTK's picture has no height-for-width; a size asked inside `resize`) and the
  watch-as-poll shape go there and to ADR 0123's Consequences.
- **Left in the dev instance by my runs:** nothing of this phase — `App pot 62903`, `App pot 4660`, `App pot 8604`,
  `Linux pot` and `Windows pot` were each trashed and deleted forever. Other sessions' `windows/run.py run` processes
  (PIDs 61912, 64097, 64732, 66678, started 2026-11-11 23:57–00:10) and a Linux container on 8095 were running and left
  alone.

### Phase 4 (2026-11-12)

- **An edit is applied on the host's thread, in the order sent** (`Bridge.call` applies `Editor.Change` before it
  launches the answer): each call was launched on `Dispatchers.Default`, so two keystrokes' edits could land the wrong way
  round and a field end on the earlier text (bitten: 400 titles sent unawaited). Linux's `bridge::call` reaches the core
  when called, not when its answer is first awaited; C#'s `CallAsync` already did. Decided here.
- **D5's rule, refined:** "the state's text differs from what it sent last" would rewrite a focused field to a text one
  keystroke behind (states lag the typing). A field remembers every text it sent: a focused field passes over a state
  that echoes one of them (and forgets the older ones, since states arrive in the order applied); any other text — a
  stale save's other version, a rate filled — is set, focused or not (`typed.rs`, `TypedText.cs`, the same five cases).
- **The editor's writes go through the Pot's door** (`PotModel.Opened.saveEntry/deleteEntry/rateFor`), so the bridge's
  `DispatchTest` plays a save and can bite "answered before the write"; `EditorDoor` no longer reaches the session.
- **The shown editor carries every rule's answer** (`EditorShown`): what the phones compute inline from the form
  (`payersTotal`, `showsRest`, `nextPayer`, `canRemovePayer`, `collapsedWords`, `splitHint`, the value in a split's mode)
  is the core's on the wire; `EntryForm.typedIn` is the one function added for it. The delete answers as a save does
  (`EditorSaved`), so a host closes on the answer. Decided here.
- **The history is the editor's page** on Linux (`entry_history::page`) and an `Expander` on Windows (the editor is a page
  there, D5); a deleted entry's row still opens the history dialog with its restore (the editor opens live entries only).
- **Traps met** (to `src/mobile/CLAUDE.md` § Traps at once):
  - `AdwComboRow`: a search or an expression set after its model replaces the selection, which notifies — the form sent
    "AED" as the currency at its build. Built in one builder; the drawing flag is held while a form is built too.
  - WinUI's `TextBox.TextChanged` is raised after the draw, so a drawing flag does not cover the state's own set: a
    lagging state set a payer's box to "" and that came back as an edit. A box remembers the text the state set.
  - WinUI: a head moved between two columns drawn in a row (the editor closed, then an update) read `Parent` null while
    its column still loaded; the add threw `0x800F1000` ("no installed components were detected") out of a watch's
    callback, which ended the app in combase with nothing in the log. `PotPage` keeps the column itself and logs a draw
    that throws.
  - words.json writes a percent sign `%%` (Android's and Apple's format strings); the desktops said "70.00 %%" until
    `generate_words.py` wrote one `%` into their catalogs.
  - Driving: Playwright names `Backspace` (not `BackSpace`) and presses no `Ü`; `Veyq.Drive`'s `key` has no `.` or `,`
    (a decimal goes in by `set`), a UIA `set` while a form rebuilds may land on the old box, and an `AutoSuggestBox`'s
    inner box is `#TextBox` (reached by Tab from the date).
- **Noticed, not changed:** an edited entry's rate shows as sealed (`1.082954299329`, a dot) where a filled one is in the
  reader's separator — `EntryForm.of` keeps `content.rate`; every app does the same (Phase 7 or a later CR). AdwComboRow
  rows of a payer show the person at the row's end beside the amount; GNOME's guideline holds, a wider dialog would read
  better. Windows' CommandBar shows "New" (the level's) beside "Add entry" in a Pot.
- **Deviations:** the stale save was provoked from a second core session (`PotLiveTest`, `VEYQ_LIVE_POT_EDIT`), not the
  web; the Windows dates and money read the VM's German culture under en-US words (Phase 3 the same); the Windows pass
  restarted the app five times while the draw crash was found (each `run.py run` of mine stopped by its PID); the first
  Windows start answered the offer with "Not now", the pass ended with Stop trusting.
- **Left in the dev instance by my runs:** nothing of this phase — `App pot 652` and `App pot 18030` (LiveBridgeTest),
  `Linux pot` and `Windows pot` were trashed and deleted forever. `App pot e2` and `App folder e2` (Phase 2) stay in the
  space's trash; other sessions' `windows/run.py run` processes (PIDs 61912, 64097, 64732, 66678) and the Linux
  container on 8095 were left alone.

### Phase 5 (2026-11-12)

- **`Windows.Data.Pdf` works unpackaged** (the spike, recorded first): no MSIX identity is needed for it, and the renderer
  stays in memory end to end (a stream in, a PNG stream out, `BitmapImage.SetSourceAsync`). Kept as `PdfPagesTest`, so
  a later SDK or packaging change that loses it goes red. Decided here.
- **A list of paths, not one:** `Editor.AddReceipts` takes every path one open dialog gave (both desktops' dialogs pick
  several, as Android's `OpenMultipleDocuments`), so the 101st is refused within one pick by the core's count, not by a
  host looping. `Editor.DropReceipt` rides the in-order path with `Editor.Change`: a drop followed at once by a save must
  not land after it (the DispatchTest sends both unawaited). Decided here.
- **The type is the bytes' on a desktop** (`Receipts.picked`): a path carries no media type, and guessing one from the
  extension would let a renamed text file through as a receipt; Android's picker names a type, so its rule is unchanged.
  `application/octet-stream` for bytes that are no image and no PDF makes `claims` refuse them. Decided here.
- **The page cap is a call** (`Receipt.Pages(total)`): the host knows the page count only after its renderer opened the
  document, and the cap and its words are the core's; a constant copied into Rust and C# would be a third and fourth copy.
- **The read cap is held where it can bite**: the core's own refusal of a file over `MAX_BYTES` hid a bridge that read a
  picked file whole (the first bite missed); the test's `DeviceFiles` now records the most the core asks to read.
- **The native read was quadratic** (`Exports.kt` appended each 64 KiB part to the array read so far): harmless for a
  cover, 160 copies for a 10 MiB receipt. It joins once now.
- **The bridge's live test sets the quota itself**, reading the core's internal `VeyqAccount.http` by reflection (the
  bridge's tests are no friends of `:core`); gated by `VEYQ_LIVE_RECEIPT_NO_ROOM=1`, since it touches the tenant's quota
  for every other session meanwhile. The desktops' passes used `DepotLiveTest`'s hold instead. Deviation from "with
  `VEYQ_LIVE_STORAGE_FIT`'s quota": the same one-byte rule (a quota of exactly one byte taken as a leftover), its own switch.
- **The three Phase 4 fixes**: the rate's separator is the core's for every app (Android and Apple too, unchanged code);
  Windows' Pot page drops New (Fluent's one creating action per page, as the phones); Linux's history group lost its title
  on the editor's page only (the dialog keeps it, headed by the entry).
- **Traps met** (to `src/mobile/CLAUDE.md` § Traps at once): a full storage must come after the pass's copy; GTK's
  multi-file dialog hands back a folder typed and entered; a popover survives two Playwright connections but its button's
  next click closes it; the Windows open picker takes typed names only after navigating its list, and `#1` is both a row
  and Open; `ContentDialogMaxWidth`; poppler-rs' `v0_82` and cairo's byte order.
- **Noticed, not changed:** the Linux picture viewer scales a picture narrower than the dialog up to its width
  (`gtk::Picture` fills the box; Windows' `Image` keeps 480 px); a receipt's row in the entries list says nothing of its
  receipts on either desktop (the phones do not either). The Windows open picker shows "Alle Dateien" over the filter
  list it was given (the system's word).
- **Deviations:** the de/dark Windows skip notice named `r003.jpg`, not
  the one-page PDF (the picker reopened in the folder of the 101); Android and Apple were built, not run (nothing of
  their screens changed but the rate's separator, which `EntryFormTest` holds); the Linux de pass ran in the window's
  unmaximized size after a stray double-click.
- **Phase 7 harvests:** ADR 0128's Consequences gain `Editor.AddReceipts` (the first open-picker consumer, a list of
  paths), `Receipt.Open` as `Answer.Bytes`, `Receipt.Pages`; ADR 0123's the renderers (poppler-glib, `Windows.Data.Pdf`
  unpackaged, measured) and native-apps § Open's Flatpak list gains poppler-glib; `src/mobile/CLAUDE.md` already names the
  calls and the traps above.
- **Left in the dev instance by my runs:** nothing of this phase — `App pot 62240` (LiveBridgeTest), `Linux pot` and
  `Windows pot` were trashed and deleted forever, the tenant's quota put back after each hold (four holds, each ended
  `BUILD SUCCESSFUL`). `App pot e2` and `App folder e2` (Phase 2) stay in the space's trash; `Amsterdam (2)`/`(3)` and the
  `Ui pot`s are not mine. Other sessions' `windows/run.py run` processes (PIDs 24480, 61912, 64097, 64732, 66678) and the
  Linux container on 8095 were left alone.
