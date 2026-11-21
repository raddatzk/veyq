---
id: cr-apps-notices-and-formats-from-the-core
title: A notice carries its lifetime and every shown size, date, length, amount and joined line is the core's text, held to vectors from the web's formatters
status: planned
satisfies: []
adrs: ["0154", "0136", "0039"]
invariants:
  - "a platform holds no notice duration and no size, date, money or length formatter; it draws the core's text and maps the core's lifetime onto its control (ADR 0154 decisions 4 and 5)"
  - "the apps say what the web says: every format held to vectors the web's own formatters produce, in English and German"
  - "no word in an app but from src/mobile/words/words.json"
---

## Goal

ADR 0154 decisions 4 and 5 (the audit's group C). After this CR a notice names its kind — said briefly, held until
closed, or held for a window the core ends — and each app shows it for that long; "copied" and "nothing plays" are core
constants; and every size, date, date range, length, amount and joined line an app shows is a string the core made from
the catalog, the same on all four apps and the same as the web's (Windows' 1024-based sizes and numeric dates, the
6 s "copied" and three truncating music clocks among what changes).

## Context & constraints

- **Read first:** ADR 0154 decisions 4–6; ADR 0039 (the web's transient feedback); the web's
  `src/web/src/shared/state/useToast.ts`, `shared/formatBytes.ts`, `features/expenses/amounts.ts`,
  `features/memories/memoryDateRange.ts`, `features/memories/music/audio.ts` (`formatTrackLength`); the core's
  `words/Notices.kt` (`Notice`, `Piece`, `Joined`, `Moment`, `Amount`), `MusicQueue.formatLength`, `session/LocalDays`.
- **Order:** after `cr-apps-structure-from-core-plans` (the notice host and the header text are core UI by then); its
  core phase can run earlier, beside the platforms' phases of the CR before it.
- **Touches `src/web/`** only by a test that pins the vectors to the web's formatters (phase 1).
- **Out of scope:** a region's own forms beyond the app's two languages (en-GB's day-month order): named open.

## Measured at refine (2026-11-20)

Branch `claude/core-owns-refine` at `a75188eb8`. `C=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`, `M=src/mobile`, `W=src/web/src`.

| # | What | Where / command |
|---|---|---|
| R1 | `Notice(msg, args)` has no lifetime or kind; the core runs only the undo window (`TRASH_UNDO_MS`). | `sed -n 25,60p $C/words/Notices.kt`; `grep -rn "TRASH_UNDO_MS" $C $M/apple/Veyq $M/android/src $M/linux/src $M/windows/Veyq` |
| R2 | Each platform's durations: Android Short/Long/Indefinite and `delay(6_000)`, `NOTHING_PLAYS_MS`; Apple the trash window reused for every notice, `.seconds(6)`/`.seconds(5)`; Linux `set_timeout(0)`/`5`, `timeout_add_seconds_local_once(6…)`; Windows InfoBars until closed, `FromSeconds(5)`/`(6)`. | `grep -rnE "Snackbar(Duration)?\|LENGTH_(SHORT\|LONG)\|delay\([0-9_]{4,}" $M/android/src --include='*.kt'`; `grep -rnE "Task\.sleep\(for: \.(seconds\|milliseconds)\|seconds\([0-9]" $M/apple/Veyq`; `grep -rnE "set_timeout\|\.timeout\(\|timeout_add_seconds" $M/linux/src`; `grep -rnE "FromSeconds\|new InfoBar" $M/windows/Veyq --include='*.cs'` |
| R3 | The web: success 4 s (`SUCCESS_LIFE_MS`), an error until closed, an undo its own window (`TRASH_UNDO_MS` 6 s), "copied" 2 s (6 s in `FileViewer.vue` alone), "nothing plays" 5 s (`AudioBar.vue`). | `grep -n "LIFE_MS\|life" $W/shared/state/useToast.ts`; `grep -rn "2000\|6000\|NOTICE_MS" $W/core/audio/AudioBar.vue $W/features/depot/components/FileViewer.vue $W/core/node/trashUndo.ts 2>/dev/null; grep -rln "copied" $W \| head` |
| R4 | Sizes: Android `formatShortFileSize`, Apple `ByteCountFormatter`, Linux `glib::format_size` (base 1000), Windows `StrFormatByteSizeEx` (base 1024); the web `formatBytes` base 1000. | `grep -rnE "formatShortFileSize\|ByteCountFormatter\|format_size\|StrFormatByteSize" $M/android/src $M/apple/Veyq $M/linux/src $M/windows/Veyq`; `sed -n 1,30p $W/shared/formatBytes.ts` |
| R5 | Money and dates per platform (Android `NumberFormat`/`ofLocalizedDate(MEDIUM)`, Apple `.currency`/`.dateTime…abbreviated`, Windows `CurrencyFormatter`/`ToString("d")`, Linux `memory_facts.rs`' `date_text`); the web `Intl` with `dateStyle: 'medium'` and `amounts.ts`. | `grep -rnE "NumberFormat\|ofLocalized\|\.currency\(\|dateTime\|CurrencyFormatter\|ToString\(\"d\"\)\|date_text\|DateText" $M/android/src $M/apple/Veyq $M/linux/src $M/windows/Veyq \| grep -v Test` |
| R6 | " · " joined in platform code: Android 20, Apple 19, Linux 14, Windows 12 (the core 6). | `grep -rnoE '"[^"]* · [^"]*"' $M/android/src $M/apple/Veyq $M/linux/src $M/windows/Veyq` |
| R7 | Music clocks: Android `MusicQueue.formatLength`; Apple `musicTime`, Linux `clock`, Windows `MusicTime.Said` floor instead of rounding. | `grep -rn "formatLength\|musicTime\|fn clock\|MusicTime" $M/android/src $M/apple/Veyq $M/linux/src $M/windows/Veyq` |
| R8 | `LocalDays` used in no platform. | `grep -rlE 'LocalDays\|local_days' $M/android/src $M/apple/Veyq $M/linux/src $M/windows/Veyq` |

## Decisions

- **F1 — `Notice` names its kind.** `Notice.kind`: `SAID` (a success, `SAID_MS`), `HELD` (a refusal or failure, until
  closed), `UNDO` (the core's window, ended by the state); the default derived from the raising site, each site
  reviewed. Inline notes are constants beside it (`COPIED_MS`, `NOTHING_PLAYS_MS`). Values Q1's. A platform maps a kind
  onto its control: a `SAID` snackbar/toast/InfoBar closes itself after `SAID_MS`, a `HELD` one carries a close action,
  an `UNDO` one shows until the state drops the offer.
- **F2 — `words/Shown`, the core's formats.** `size(bytes)`, `day(LocalDate)`, `moment(instant)`, `dayRange(a, b)`,
  `money(Amount)`, `length(ms)` (the track list and the running clock alike), `joined(parts)` and a count with its
  noun, each in the app's language; a `Notice`'s `Piece`s rendered through them, so a platform fills a sentence with
  strings only. The day of a local date comes through `LocalDays`.
- **F3 — vectors from the web.** `src/mobile/words/format-vectors.json`: inputs and the web's output for `en` and `de`
  (sizes across the unit boundaries, dates and ranges, amounts in every currency a Pot offers — so its symbols and
  patterns serve as the core's money table —, lengths), written by a script under `src/web/` over the web's own
  formatters and pinned by a web unit test that re-renders them; `ShownVectorsTest` holds the core to the file.
  **The web stays the source of these formats (decided 2026-11-21, against ADR 0155's turn-around).** ADR 0155's
  Consequences make the core's output the one source for what *moved*; these formats did not move, and moving them now
  misses decision 7's budget by a measured factor: a throwaway `@JsExport` façade of `Shown` (size, day, moment, Memory
  day and range, money, list, length) bundled beside the search's entry grew it from 78,824 B min / 27,691 B gz to
  91,955 / 32,658 — **4,967 B gz of own code** against the **703 B gz** (1,221 min) of TypeScript it would delete
  (`formatBytes`, `formatMoney`+`decimalOf`, `memoryDateRange`'s two; esbuild `--minify`, `gzip -9`): **7.1×, R = 3.0**
  allows ~2.1 KB. It would also give up `Intl` for the browser's own locale data and put the money table in the eager
  set, and the search's entry carried the façade's code whether imported or not (one module), the granularity decision 7
  asks to measure first. So the web keeps `Intl`, the vectors run web → core as F3 says, and the turn comes with the
  move that owns a format (Expenses brings `formatMoney`; then `Shown` is the source and `format-vectors.json` is
  written by a core test, as `SearchVectorsTest` writes the search's).
- **F4 — the guard.** The words guard gains `platform-format`: no duration literal at a notice or an inline note, no
  size/date/money formatter (R4's and R5's names), no `" · "` literal in a platform's UI; exceptions only a player's
  own media time it passes to the core.

## Phases

- [x] **1 vectors** — F3: the script, the file, the web's pinning test. · Done when: `pnpm run check` in `src/web` green.
  **Bite:** one size vector edited → the web test red.
  **Built** (2026-11-21): `src/web/src/test/formatVectors.ts` (test support, so it may read core and the applications) renders every case through the web's formatters
  (`formatBytes`, `formatMoney` over `knownCurrencies`/`minorUnitsOf`, `memoryDateRange`, `joinList`, `formatTrackLength`,
  and `Intl` with the date call sites' options, a moment in UTC); `scripts/format-vectors.mts` writes
  `src/mobile/words/format-vectors.json` (ICU 77.1: 262 sizes, 19 days, 34 moments, 17 Memory days, 4 ranges, 815 amounts
  in all 163 currencies, 25 numbers, 4 lists, 22 lengths); `formatVectors.test.ts` re-renders it. Measured: `pnpm run
  check` in `src/web` green; one `"1.1 kB"` edited to `"1.2 kB"` → `size` red, restored (`/bin/cp -f`) → green. A list
  (`joinList`) and a currency-less number joined the CR's kinds: both are shown text the apps write too.
- [x] **2 core** — F1, F2; every notice site's kind; the vectors test. · Done when: `:core:jvmTest :core:macosArm64Test`
  green. **Bites:** `size` at base 1024 → the vectors red; a refusal raised as `SAID` → its site's test red.
  **Built** (2026-11-21): `Notice.kind` (`NoticeKind.SAID`/`HELD`/`UNDO`, `lifeMs`; `Notice.SAID_MS` 4 s, `COPIED_MS` 2 s,
  `NOTHING_PLAYS_MS` 5 s; the undo's window stays `ShellModel.TRASH_UNDO_MS` 6 s), part of its equality; the default
  `NoticeKind.of(msg)` — `SAID` for the success words (`COPY_DONE`, `MOVE_MOVED`, `DOWNLOAD_DONE`, `DOWNLOAD_CANCELLED`,
  `IMPORT_IMPORTED`(`_LEFT_OUT`), `OFFLINE_SENT`), `UNDO` for `TRASH_MOVED` and `MEMORY_MOVED_TO_TRASH`, every other word
  `HELD` —, changed at the sites where the same word lives otherwise (`living(kind)`): a move's offered undo is `UNDO`,
  a trash without the node as it was or queued offline is `SAID`, an offline trash's "waiting to send" `SAID`.
  `words/Shown.kt`: `size`, `day` (ISO or local epoch day), `dayOf` (an instant's local day), `moment` (an instant in the
  device's zone, `LocalDays.wallOf`), `memoryDay`, `dayRange`, `money`, `number`, `list`, `length` (`MusicQueue.formatLength`
  now calls it), `joined`/`SEPARATOR`, and `args(notice)` / `piece(p)` that write a notice's or a line's values in a
  language; free of kotlinx.serialization, so a later web façade may reach it. A platform's word list beyond the
  success words, and the platform-picked toasts (`R.string.link_kept` and the like), become core notices in phases 5–8,
  each adding its word to `NoticeKind`'s table. A count with its noun stays the catalog's plural (`{1:int}`), not a
  `Shown` function. Measured: `:core:jvmTest :core:macosArm64Test :core:compileKotlinJs` green (one red first,
  `MoveSheetTest` pinning the undo without its kind — updated to `UNDO`, re-run green on both); `ShownVectorsTest` 10/10
  first try (all 1,202 cases × 2 languages). Bites, one run: `size` dividing by 1024 → `sizes` red (442 of 524 cases);
  `DELETE_REFUSED_GONE` added to the success words → `aTrashIsAnUndoAndItsRefusalsAreHeld` red; both restored
  (`/bin/cp -f`) → green.
- [x] **3 bridge** — the kind and the shown strings on the wire (no `Bytes`/`Moment`/`Amount` left for a host to
  format). · Done when: `:bridge:jvmTest` green.
  **Built** (2026-11-21): the bridge writes with `wireIn(Config.language)` (`Bridge.written`), whose serializers module
  (`WireWords.module`, beside the serializers in `Notices.kt` so `Shown` stays free of kotlinx.serialization) hands
  `NoticeSerializer` and the new `PieceSerializer` the host's language: a notice carries `kind` and its sizes, moments and
  amounts as `text` (`NoticeArg`'s `bytes`/`moment`/`amount` variants are gone), a line's `Piece` is `said` or `plain`
  (its `day`/`size`/`money` written first). `Call.NoticeLifetimes` → `Answer.Lifetimes(saidMs, undoMs, copiedMs,
  nothingPlaysMs)`: the one place a host reads a duration. The hosts, kept compiling with the least: Linux's `said.rs`
  and Windows' `SaidNotice.cs` lost the arms of the removed variants, every hand-built `Notice` names `kind: Held`
  (`said.rs`, four Rust tests, `ImportTextTest.cs` — whose `Bytes(2048)` became the text `"2 kB"` —, `SearchPlanTest.cs`).
  **Open for phases 7–8** (both read the one wire, so phase 7 adds the shown string and phase 8 follows): the raw values
  a host still formats itself — the account settings' storage sizes, an offline item's `bytes`, a session family's
  `createdAt`/`lastUsedAt`, the safety page's `rotatedAt`, a trash row's `deletedAt`/`purgeAfter`, a link's
  `createdAt`/`expiresAt`, an entry's history `at` and `restorableUntil`, a Pot's total (`said::money` over the view's
  currency), a Memory's `MemoryDateShown`, the entry editor's date and a rate's day, the gallery filter's day, the
  lightbox's size and moment, the music clocks (`linux/run.py`'s `said::*` callers, measured by `grep -rnE
  "said::(size|day|moment|money|instant_day|instant_moment)\(" src/mobile/linux/src`: 18 sites, Windows' `Said.*` alike);
  and a day the host itself picked (a link's end in a calendar), which needs the core's text through a call. Measured:
  `:bridge:jvmTest` green, 139 tests (two `DispatchTest` cases first red where they cast a value the wire now writes —
  now asserting the text: `CHF 50.50`, a size); `:core:jvmTest`/`:core:macosArm64Test` of `words.*` and
  `:core:compileKotlinJs` green; `linux/run.py bridge` green; `:bridge:generateBindings` writes `NoticeKind`, a
  `Notice.kind`, a `Piece` of `Plain`/`Said` and the lifetimes call in both `bridge.rs` and `Bridge.g.cs`. Not compiled:
  the Rust and C# hosts (their builds need the Flatpak SDK and the Windows host; phases 7–8 build them). **Bite:**
  `WireWords.languageOf` ignoring its module → `ShownWireTest` 2/2 red, restored → green.
- [x] **4 the guard** — F4, switched on per platform in its phase. · Done when: the guard green; `validate_guard_gaps.py`
  run once. **Bites:** a `FromSeconds(6)` at a notice, a `ByteCountFormatter`, a `" · "` literal — each red alone.
  **Built** (2026-11-21): `validate_mobile_words.py`'s tenth rule, `platform-format` (`check_platform_format`): per
  platform the notice and timer durations of a literal, the size/date/money formatters by name, and a `" · "` literal, in
  the same sources the literal rules read; `PLATFORM_FORMAT_ON` is empty — each of phases 5–8 adds its platform once its
  own are gone —, and `PLATFORM_FORMAT_EXCEPTIONS` names (platform, file, match) with why (a player's own media time, a
  timer that is no notice's). Switched on everywhere today it names **218 sites**, each phase's work list: Android 15
  durations, 35 formatters, 20 joins; Apple 19, 31, 19; Linux 11, 8, 14; Windows 17, 17, 12 (the joins exactly R6's
  counts; `check_platform_format(on={…all four…})`). Measured: every `scripts/validate_*.py` green with
  `/usr/bin/python3`; `validate_guard_gaps.py --force` green (104 cases). **Bites**, each in a throwaway tree with only
  its platform switched on, green with the clean line, red with the planted one alone, green with the platform off:
  `TimeSpan.FromSeconds(6)` beside an InfoBar (Windows), `ByteCountFormatter()` (Apple), `parts.join(" · ")` (Linux).
- [x] **5 Android** — the platform's formatters and durations removed, the notice host mapping F1, the guard on.
  **Built** (2026-11-21): `core/NoticeHost.kt` is the one place a notice meets Material's snackbar — every notice shown
  `Indefinite` (the guard's one Android exception) and closed by `SnackbarHostState.show(text, kind, action, ended)`: a
  `SAID` one `lifeMs` after it appears (not while queued), a `HELD` one with a close until closed, an undo until the core
  ends it; `AppModel.Notice` carries the `kind` (an upload's offer or a refusal among its lines is `HELD`). Every
  `showSnackbar`/`Toast` goes through it; the platform-picked words became core notices (`TRUST_FAILED`,
  `DOWNLOAD_FAILED`, `SHARE_REFUSED`, `MEMORY_BANNER_FAILED`, `MEMORY_BUCKETS_*_FAILED`, `SHARE_DELETE_FAILED` held;
  `LINK_KEPT`, `PROFILE_SAVED`, `RECEIPTS_SAVED_GALLERY` added to `NoticeKind`'s success words); a receipt's gallery
  save is said inside its dialog, "copied" (a link, a text file) is an inline note for `COPIED_MS`. `Formats.kt` and
  `NoticeText.kt` write through `Shown` in the app's language (`Context.language`): `text(notice)` over `Shown.args`,
  `piece` over `Shown.piece`, `money`, `size`, `moment`, `localDate`, a Memory's date `Shown.dayRange`/`memoryDay` (the
  web's numeric range, no longer `DateUtils.formatDateRange`), `List<String>.joined()`. Core additions, each pinned to
  the web: `Shown.dayLabel`'s day chip (phase 6's, merged: Android hands `GalleryFilter.Calendar.local` — it showed `dMMM`
  — and its ISO overload is the vectors' input, so the chip is also held to `galleryFilter.ts`' own output), `Shown.distance` (a track's length, `trackFacts.ts`' `formatDistance`), both new sections of
  `format-vectors.json` written by `formatVectors.ts`; `Shown.clock` (the capture-time editor's time, the moment's own
  time part). The map's camera wait is a `snapshotFlow`, the time picker takes Material's own 24-hour default.
  Exceptions (`PLATFORM_FORMAT_EXCEPTIONS`): `SnackbarDuration.Indefinite` in `NoticeHost.kt`, the settings page's
  two scroll-poll delays, the debug build's playback probe. Measured: `:core:jvmTest :core:macosArm64Test` (words)
  `:core:compileKotlinJs :android:assembleDebug` green; `ShownVectorsTest` 13/13 and `NoticeKindTest` 5/5 first try;
  `formatVectors.test.ts` 12/12; every `scripts/validate_*.py` but guard_gaps green. **Bites:** a
  `formatShortFileSize`, a `" · "` join, a `delay(4_000)` in `NoticeHost.kt` → the guard red each; `März` → `Maerz`,
  the distance's whole-kilometre rule dropped and `LINK_KEPT` out of the success words → `dayLabels`, `distances`,
  `aSuccessIsSaid` red; a `dayLabel` and a `distance` vector edited → the web test red; all restored → green.
  **Smoke** (throwaway on an API 36 AVD, erased): a copy's "copied to" gone after ~4 s, a trash's Undo after ~6 s, an
  upload's "Replace" offer still up after 14 s with its Dismiss, a link's "Copied" note ~2 s; a 1.5 MB file reads "1.5 MB ·
  Modified Nov 21, 2026, 2:29 PM" / "1,5 MB · Geändert 21.11.2026, 14:29", a link "Created Nov 21, 2026 · Expires Dec
  21, 2026" / "Erstellt am 21.11.2026 · Läuft ab am 21.12.2026", the offline banner "as of Nov 21, 2026, 2:28 PM". Not
  smoked: a Pot's amounts (the smoke's Pot was made without people, so it offered no entry; amounts are the vectors' `money`).
  Seen, not this CR's: the shell's row details (`AppModel.rowDetails`) keep the language they were read in until the
  app restarts after an in-app language switch — as before.
- [ ] **6 Apple** · **7 Linux** · **8 Windows** — each platform's formatters and durations removed, the
  notice host mapping F1, the guard on. · Done when: each platform's build (as the CR before) and the guards green.
  **6 Apple — Built** (2026-11-21): every value the app shows goes through the core's `Shown` in the catalog's language
  (`shownLanguage`, `Bundle.main.preferredLocalizations`): `Formats.swift`'s `money`, `localDay`, `shownSize`,
  `shownMoment`, `shownDayOf`, `shownLength` (the music bar's clocks, `musicTime` gone), `say(piece:)` over `Shown.piece`,
  `say(notice:)` over `Shown.args`, a line by `pieceLine`/`joinedLine` (`Shown.joined`); a Memory's date the web's numeric
  `memoryDay`/`dayRange`; the date pickers' ISO day by a Gregorian `IsoDay`, no `DateFormatter`. The notice host maps
  `NoticeKind`: `AppModel.show` — `HELD` the shell's alert until closed, `SAID` at the bottom closing after its `lifeMs`,
  `UNDO` with its Undo until core drops the offer —, the uploads' said that only says something went through, the
  import's line and a name dialog's notice among them; a guest's screens by `saidNotice`; "copied" (a file's text, a
  link) for `COPIED_MS`, "nothing plays" for `NOTHING_PLAYS_MS`; the live polls held by `untilCancelled()`, no timer.
  Core addition: `Shown.dayLabel`/`dayLabelOf` (the web's gallery day chip, `day: 'numeric', month: 'long'`) and
  `GalleryFilter.Calendar.local(language)`, which Apple now hands (`ShownDayLabelTest`, Node's strings). Exceptions
  (`PLATFORM_FORMAT_EXCEPTIONS`): the Pot create sheet's `currency(code:)` call, the fade's 25 ms step, the debug test
  camera's rate, Settings' anchor settle, `DevAudio`/`DevLogin` (debug only). Open: a GPX track's distance stays the
  system's `Measurement` (no web vector). Measured: `validate_mobile_words.py` green with `apple` on; `:core:jvmTest`
  (`words.*`, `GalleryFilter*`), `:core:macosArm64Test` (`words.*`), `:core:compileKotlinJs`,
  `:core:assembleVeyqCoreDebugXCFramework`, `xcodegen`, the macOS ad-hoc build and the iOS simulator compile green.
  **Bites:** a planted `ByteCountFormatter` and a planted `Task.sleep(for: .seconds(4))` in `Formats.swift` → the guard
  red, restored (`/bin/cp -f`) → green; "März" misspelt → `ShownDayLabelTest` red, restored → green. Unclicked (build
  only): every notice's life and every value on the Mac and the simulator.
  - [x] **7 Linux** — **Built** (2026-11-21): the first step, `run.py build` at the phase-4 tip, green (phase 3's `said.rs`
    compiles). **The wire's open values (phase 3's list) now arrive as the core's text** — `words/ShownText` (core,
    `Notices.kt` beside the other wire serializers): a value held raw, written by `Shown` in the encoder's
    `WireWords.module` language, a plain string on the wire (so Rust and C# read `String`) —, added beside each raw value
    the host still reads or in place of one it only showed: `AccountSettings.Storage` `automaticText`/`pinnedText`/
    `freeText`, `OfflineItem.bytesText`, `PersonTrust.Shown.rotatedText`, `EntryEditor.Rate.dayText`, `PotView`'s
    `ShownEntry`/`ShownSettle`/`ShownCategory` `amountText` and `Shown.totalText`; the bridge's `SettingsFamily`
    `createdText`/`lastUsedText`, `Answer.EntryHistory.untilText`, `HistoryShown.atText`, `EditorShown.dateText`,
    `DateShown` `fromText`/`toText`, `MemoryDateShown.text` (the web's numeric day or closed range; an ongoing one's
    first day, worded "Since …" by the host), `DetailRow` `sizeText`/`momentText`, `ShownLink` `createdText`/`expiresText`,
    `ShownEnd.dayText` (the picked day, so no call is needed for it), `TrashShown.Row` `deletedText`/`purgeText`,
    `ReportShown.receivedText`, `NowPlayingShown` `positionText`/`durationText`, and `ChipShown.label` typed `ShownText`
    (a day chip the core's day; the wire unchanged). All additive for Windows (phase 8 reads the same fields).
    `Answer.Lifetimes` gained `saidWords`/`undoWords` (core's `NoticeKind.of` over every word: the kind of a toast a
    desktop raises itself) and `separator` (`Shown.SEPARATOR`), and is answered at once, before the core's start
    (`Call.NoticeLifetimes` beside the host-ordered calls), so Linux asks it once, blocking, right after `bridge::start`.
    `NoticeKind`'s success words gained `PROFILE_SAVED`, `RECEIPTS_SAVED`, `LINK_KEPT`, the Linux app's own success toasts.
    Linux: `said.rs` holds the one toast (`said::toast` / `notice_toast` / `word_toast` / `copied_toast` /
    `nothing_plays_toast`: `SAID` the core's `SAID_MS`, `HELD` and `UNDO` until closed or dropped), `joined` and
    `copied_for`; every `adw::Toast` goes through it (73 sites, each platform word by its key's kind); glib's
    `format_size`/`%x`, `localeconv` money and ICU's currency names are gone (`build.rs` links no `libicuuc` any more; the
    CLAUDE.md trap about it removed), the music clock is the core's (the elapsed label keeps the playing position while
    the scale is held), and every `" · "` is `said::joined`. Guard: `PLATFORM_FORMAT_ON = {"linux"}`; the Linux duration
    rule also names an `adw::Toast::new`/`builder` outside `said.rs` (libadwaita's unseen default 5 s); exceptions:
    `said.rs`' one toast and `localeconv` (the decimal separator a person types), and four layout/refresh timers that are
    no notice's (`lightbox.rs` 3 s details re-read, `account_settings.rs` 250/100 ms scroll, `shell.rs` 400 ms).
    Measured: `run.py build` green (clippy `-D warnings`, `cargo test` 101 + 5, the build's `*WireTest*`);
    `:bridge:jvmTest` green (`ShownWireTest` two new cases, `SecretFreeSchemaTest` naming the new text fields, `WireTest`'s
    chip); `:core:jvmTest` (`words.*`) and `:core:compileKotlinJs` green; every `scripts/validate_*.py` green with
    `/usr/bin/python3` (guard_gaps skipped). **Bites:** `format_size`, an `adw::Toast::new` and a `" · "` planted in
    `trash.rs` — each red alone, restored green; `kind_of` answering `Said` for every word → `said` test red, restored
    green; `MemoryDateShown.text` with its ongoing test inverted → `ShownWireTest` red, restored green. `LINK_KEPT` dropped from the
    success words → `NoticeKindTest.aDesktopsOwnSuccessIsSaid` red, restored green. Smoke: see the
    report of this phase. After the merge of phases 5–6: a track's distance is `TrackFactShown.distanceText`
    (`Shown.distance`), and the bridge's gallery calendar is `GalleryFilter.Calendar.local(language)` (its day chip the
    web's label; `LocalCalendar.kt` gone, Windows' too). ADR 0123's Linux lines (`localeconv`, `%x`, `instant_day`) are history for phase 10.
  **8 Windows — Built** (2026-11-21): a notice lives by the core's kind in one place, `Core/NoticeBar.cs` (the InfoBar
  under the bar: `SAID` a Success closed after `saidMs`, `HELD` an Error until its close, `UNDO` with its Undo until the
  owner's `EndOffer` when the core's offer ends — NodeHeader's trash, MoveDialog's move, a drop box's and a gallery's
  trash); what is said is a `Spoken` (`Core/Lives.cs`: a core notice with its kind, several of them held where any is, or a
  word of the app's own by its key, living as the core's `saidWords`/`undoWords` say), and `Lives` takes the core's
  `Notice.Lifetimes` once at start (its lifetimes, word kinds and separator: "copied" for `copiedMs`, the player's
  "nothing plays" for `nothingPlaysMs`, every line joined by `Lives.Joined`). Every `Action<bool, string>` say became
  `Action<Spoken>`. `Said.cs` (`CurrencyFormatter`, `StrFormatByteSizeEx` — base 1024 —, the `"d"`/`"g"` dates) and
  `MusicTime` (a truncating clock) are gone; the app draws phase 7's `…Text` fields (storage, an offline item, a
  session's dates, the safety page's rotation, the trash's and a link's days, a report's receipt, an entry's history and
  rate day, a Pot's amounts and total, a Memory's date, the lightbox's size and moment, the music clocks, a track's
  distance) and `ChipShown.label`. Core addition: the banner dialog's too-large bound, `BannerEditorShown.tooLargeBound`
  (`BannerWrite.MAX_IMAGE_NAMED`: "40 MB", the web's BannerDialog — Windows wrote 40 MiB as "40.0 MB"),
  `ShownWireTest.theBannersBoundIsTheWebs`; `RECEIPTS_SAVED` among the success words. Exceptions
  (`PLATFORM_FORMAT_EXCEPTIONS`, 14): the two picked days encoded as ISO for the core, PdfWeb's folder Guid and its delete
  retry, four layout/animation timers (Settings' scroll, the device link's slide, the map's viewport, a list's scroll put
  back) and six waits in tests. Measured: `run.py build` green before the change (phase 3's `SaidNotice.cs` compiled, 80 +
  5 tests) and after the merges of phases 5–7 (`VEYQ_WINDOWS_DIR=F:\veyq-nfw`, own tasks `*-nfw`): the app, Veyq.Drive,
  Veyq.Bridge.Tests 5/5 here and there, Veyq.Tests 76/76 (`LivesTest` new; `SaidTest` and the clock's test gone with what
  they held), PdfWebTest 1/1; `:bridge:jvmTest :core:jvmTest`(words) `:core:compileKotlinJs` green; every
  `scripts/validate_*.py` but guard_gaps green with `windows` on. **Bites:** a planted `.ToString("N0")`,
  `TimeSpan.FromSeconds(6)` and `"a · b"` in `NoticeBar.cs` → the guard red each, restored green; `MAX_IMAGE_NAMED` =
  `MAX_IMAGE_BYTES` → `theBannersBoundIsTheWebs` red, restored green; `Lives.KindOf` ignoring the core's table (on the
  host) → `LivesTest` red, restored green. **Smoke** (a `tw-win` throwaway on the VM, English, erased after): the app
  logged `lives read: said 4000 ms, 13 words not held`; a folder's trash showed "“Smoke” moved to the trash." with its Undo,
  offered 16:18:45.578 and ended 16:18:51.579 (6.0 s) with the bar gone; a copy's "“Smoke” is copied to …" there at 1.5 s
  and gone at 5.5 s; Settings read "10 kB", "0 B", "31.3 GB" and "Signed in Nov 21, 2026, 4:16 PM"; the trash row
  "Deleted Nov 21, 2026 · removed for good on Dec 21, 2026". Not smoked: a refusal staying (none reachable in a fresh
  tenant without a second device; the mapping is `NoticeBar`'s one switch), German, a Pot's amounts, a file's size, a
  Memory's range and the music clocks (each the core's text, held by the vectors and `ShownWireTest`).
- [ ] **9 the passes** — § Passes.
- [ ] **10 harvest** — ADR 0154's `Built:` (decisions 4, 5); `native-apps.md`; the words guard's docstring; the Use
  pages that quote a notice's behaviour, if any (`grep -rln -i "undo\|rückgängig" site/src/content/docs`). · Done when:
  every guard but guard_gaps green.

## Passes

A throwaway on Android, Linux and Windows, in English and German (Apple's the owner's or named open): a file of 1.5 MB
(each app and the web say the same), a Pot entry in EUR and in USD, a Memory's date range, a music track's length and
running clock; a success (closes after `SAID_MS`), a refusal (stays until closed), a trash's Undo (gone with the
window), "copied" (gone after `COPIED_MS`) — each beside the web's.

## Owner questions

1. **How long a notice stays.** *Recommendation:* the web's — a success 4 s, a refusal or failure until closed, an Undo
   the core's 6 s window, "copied" 2 s, "nothing plays" 5 s; today the apps show "copied" for 6 s and treat other
   notices four ways.
2. **Dates on every app.** *Recommendation:* the web's `dateStyle: 'medium'` — in English with the month's name
   ("Nov 20, 2026"), in German numeric ("20.11.2026") —, a Memory's range the web's numeric one; Windows' numeric
   English dates and Apple's abbreviated German month change.

**Answered (owner 2026-11-20): every question as recommended.**

## Definition of done

Phases 1–10 checked; every bite seen red and green; the passes run as throwaways and erased; every guard but guard_gaps
green; `git diff --stat` over `src/backend/` and `contracts/` empty, `src/web/` only phase 1's script and test.

## Retrospective

_(filled at the end of the implementation)_
