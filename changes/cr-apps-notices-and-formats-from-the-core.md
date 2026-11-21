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
  **Built** (2026-11-21): `src/web/src/shared/formatVectors.ts` renders every case through the web's formatters
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
- [ ] **3 bridge** — the kind and the shown strings on the wire (no `Bytes`/`Moment`/`Amount` left for a host to
  format). · Done when: `:bridge:jvmTest` green.
- [ ] **4 the guard** — F4, switched on per platform in its phase. · Done when: the guard green; `validate_guard_gaps.py`
  run once. **Bites:** a `FromSeconds(6)` at a notice, a `ByteCountFormatter`, a `" · "` literal — each red alone.
- [ ] **5 Android** · **6 Apple** · **7 Linux** · **8 Windows** — each platform's formatters and durations removed, the
  notice host mapping F1, the guard on. · Done when: each platform's build (as the CR before) and the guards green.
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
