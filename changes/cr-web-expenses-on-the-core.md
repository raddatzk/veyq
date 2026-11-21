---
id: cr-web-expenses-on-the-core
title: Expenses' rules move onto the Kotlin core for the web — `@veyq/core-js/expenses`, the JSON seam, the TypeScript twins deleted
status: planned
satisfies: ["uc.expenses.balances", "uc.expenses.record-entries", "uc.expenses.verify-ledger", "uc.expenses.exchange-rates", "uc.expenses.entry-history", "uc.expenses.manage-participants"]
adrs: ["0155", "0154", "0108", "0079", "0136", "0046"]
invariants:
  - "operator-blind: the move changes where a seal is computed (Kotlin over the web's own @noble/*), never what is sealed, under which purpose, or what the server sees; contracts/crypto/vectors/v1.json's expense-entry case opens through the shipped module"
  - "code a façade reaches carries no kotlinx.serialization, coroutines, Ktor or NotOnTheWeb refusal (ADR 0155 decision 4), held by the package's slice check"
  - "no screen model with a flow crosses (ADR 0155 decision 8): what keeps state over time stays TypeScript in this move"
  - "a Long never crosses the façade; amounts in a format shape are JavaScript numbers (safe integers, entry.v1.schema.json), money results are bigint"
  - "no behaviour a member sees changes, except the currency list and digits named in decision 6"
---

## Goal

ADR 0155 decision 7's first application move (inbox #1046, P2). The web's Expenses rules — the money arithmetic, the
entry format's reader and writer, the revision codec, the ledger, the ledger check, the money shown as text, the currency
digits, the amount parsing and the exchange-rate arithmetic — are today written twice: TypeScript in
`src/web/src/features/expenses/` and its Kotlin port in `src/mobile/core/.../expenses/` (each Kotlin file says "a port
of …ts"). After this CR the web draws them through one façade, `@veyq/core-js/expenses`, and the TypeScript is deleted.
The pilot paid the Kotlin standard library's floor; this move pays only Expenses' own code, at most **25 KB gz** across
the eager set and Expenses' chunks together (decision 7). It is also where the JSON seam of decision 4 gets built, and
where ADR 0154's money vectors turn around (the core becomes their source).

## Context & constraints

- **The substrate** (named before every number below): branch `claude/windows-cr-f6c73c` at `8b760b0f3`, 2026-11-21,
  the pilot merged. Nothing was built for this refinement (the main session was building on the machine); every size is
  either measured on sources with esbuild (command given) or remembered from ADR 0155's probe.
- **What is eager today, and why it stays eager.** `register.ts` (eager) hands the registry `validatePotContent`
  (`contentSchema.ts`), which core's dry run `planImport` calls synchronously; it reaches `readRevisionBody`,
  `namedParticipants`, the format's patterns; `levelRows.ts` reaches `potMeta.ts`, which reaches `format.ts` and
  `ledger.ts`'s types. So Expenses' rules sit in the eager set and, per decision 7, the eager set carries them. Making
  `validateContent` asynchronous to make the rules lazy is out of scope: it changes core's registry contract for one
  application's bytes.
- **The TypeScript the move deletes** (measured: `esbuild <entry re-exporting the files> --bundle --minify --format=esm
  --external:@veyq/*`, then gzip level 9, `esbuild@0.28.2` from the workspace's pnpm store): `domain/{money,format,codec,
  revisions,heads,ledger}.ts` **15,209 B min / 5,686 B gz**; `amounts.ts` 1,325 / 770; both together **16,363 / 6,194**.
  `exchangeRates.ts`'s `parseTable`/`crossRate` come on top (not measured apart; the file keeps its fetch and cache).
- **The Kotlin it adds** (remembered, ADR 0155 § Context): the Expenses slice `expenses/{Big,Money,Currencies,Ledger,
  LedgerCheck,Format,Codec}` with `V1`, `Contexts`, `Pad`, variant B, measured ~20 KB gz of own code beside the floor.
  This CR adds to it the JSON seam, the façade's shape conversions, `Shown.money`/`number`, the pure part of
  `ExchangeRates`, and the amount parsers. ADR 0154 measured all of `Shown` behind a throwaway façade at 4,967 B gz;
  money alone is a part of that. Expected net: ~20 + ~4 − ~6.5 ≈ 18 KB gz against the 25 KB bound — inside, but not by a
  margin to spend on scope creep (decision 2 below keeps the scope to the rules).
- **One compiled module today.** `:core:jsPackage` writes the core as one ES module, `dist/veyq-mobile-core.mjs`, with the
  libraries beside it (Kotlin's default per-module granularity; `ls src/packages/core-js/dist` in a checkout that built
  it), and `package.json` maps `./search` straight to it. A second entry mapped the same way would expose every Expenses
  name through `@veyq/core-js/search` too, so `validate_web_core_imports_no_applications.py` (which judges an import by
  its entry name, `CORE_JS_ENTRY`) would pass a core file calling Expenses' ledger through the search entry. Decision 4
  closes that before the second entry exists.
- **The apps pay only the seam** (ADR 0155 § Consequences): their behaviour is held by the tests that hold it today
  (`ExpensesVectorsTest`, `LedgerRulesTest`, `BigTest`, `EntryFormTest`, `PotExportTest`, `QueuedTest`, the `Pot*` tests).

## Decisions (technical, settled here)

1. **The phase order is contracts → core → core-js → web** (ADR 0155 § Consequences; `changes/README.md` § Phases).
   Nothing of the backend or `contracts/api` changes.

2. **The scope is the rules the probe measured, plus what keeps `money.ts` from being deletable.** Moved, and their
   TypeScript deleted: `domain/money.ts`, `domain/format.ts` (its logic), `domain/codec.ts`, `domain/revisions.ts`,
   `domain/heads.ts`, `domain/ledger.ts` (its logic), `amounts.ts` (all of it: digits, the currency list, money as text,
   the amount and rate parsers) and `exchangeRates.ts`'s `parseTable` and `crossRate` (they are why `money.ts`'s fractions
   are imported outside `domain/`; the fetch, the per-day cache and `setRateSourceForTest` stay). **Not in this move**,
   though pure and twinned: `entryForm.ts` (`EntryForm.kt`), `domain/potMeta.ts` (`PotMeta.kt`, kotlinx-bound),
   `people.ts`, `domain/receiptRules.ts` (`Receipts.kt`), `domain/potApi.ts`'s write sealing (`PotWrites.kt`), `rekey.ts`
   (`PotRekey.kt`), `report.ts`, `contentSchema.ts`/`exportImport.ts` (`PotImport.kt`/`PotExport.kt`). They keep calling
   the façade for every rule they use today. They are the next Expenses move; its budget is the owner's question below.
   Why not all of Expenses now: the 25 KB bound was set from this slice's measurement, and `EntryForm.kt` alone is 352
   lines; a move that misses its bound reverts whole, so the measured slice goes first.

3. **The JSON seam (decision 4) is a small JSON tree of the core's own, in common code.** A sealed tree type (object,
   array, string, number, boolean, null; a number is the IEEE double `JSON.parse` yields), and an `expect` text codec
   (`parse(text)`, `stringify(tree)`): on JavaScript `JSON.parse`/`JSON.stringify`, walked into the tree; on the JVM and
   native kotlinx.serialization's `Json`, converted. `Format.kt`'s reader and writer and the rate table's reader move
   onto the tree. The apps' remaining kotlinx callers (`PotImport`, `PotExport`, `QueuedExpenses`, `EntryEditor`, the JVM
   tests) convert at their edge with two common helpers (`JsonElement` ⇄ tree) in a file of their own, which no façade
   reaches — the slice check proves it. Why a tree of our own, not `dynamic` on JS: common code stays one reader with one
   set of branches, the apps run it as today, and the conversion is linear in a ≤ 16 KiB body.
   **The two parsers stay two** (decision 4 keeps kotlinx for the apps), so where they could disagree the vectors pin
   one answer: a duplicate key (last wins — what `JSON.parse` has always done for every body the web wrote or read; the
   JVM/native side conforms if kotlinx differs), `1e2`/`100.0`/`-0` as integers, a number past 2^53, a lone surrogate.
   Phase 1 writes these cases before any code moves, so a disagreement found there is a finding about today's tree.

4. **One module, one entry file per façade.** The module stays one (per-module granularity); `jsPackage` writes beside
   it `dist/search.mjs`/`.d.mts` and `dist/expenses.mjs`/`.d.mts`, each re-exporting exactly the declarations its
   façade's package exports (`me.raddatz.veyq.mobile.<entry>.web`, ADR 0155 decision 5), derived from those sources'
   `@JsExport` declarations, never listed by hand; `package.json` maps `./search` and `./expenses` to them. The direction
   guard then has teeth: a name from the other entry does not type-check. Why not per-file granularity now: both
   consumers are eager, so splitting buys no bytes today and adds a build-output change to the byte-identical step. **The
   granularity measurement decision 7 asks for** is taken in phase 3: the `search` entry bundled alone before and after
   the Expenses façade exists. If it grows (the bundler cannot drop the other façade's code from one module), the ADR
   records that a lazy-only consumer (Memories, Depot) needs per-file granularity first; if it does not, it records that.
   This CR does not decide that future move, it hands it its number.

5. **The façade's surface: plain values, no handle.** `jsMain/.../expenses/web/ExpensesWeb.kt` + `Shapes.kt`, as the
   search's. It exports what the web's callers call, no more (phase 3 derives the list from the imports the inventory's
   first two locators print), in these groups:
   - *format*: `readRevisionBody(parsed)` (the schema's reader; refusal as a thrown error carrying `path`), the format's
     numbers and patterns as one `formatRules()` value (pattern sources, `MAX_HEADS`, `MAX_RECEIPTS`, `PERCENT_WHOLE`,
     `DEFAULT_CATEGORIES`), `isMoney`;
   - *codec*: `sealRevision`, `openRevision`, `openRevisionWithKey`, `revisionKeyOf`, `rewrapRevisionKey`,
     `revisionDigest`, `SIZE_CLASSES` — keys and bodies as `Uint8Array`, passed to Kotlin as `Int8Array` **views of the
     same buffer, never copies** (`JsPrimitives`' rule), so a key the web zeroes is zeroed;
   - *revisions and the check*: `openServedRevisions`, `byRecord`, `headsToName`, `currentHeads`, `checkLedger`;
   - *ledger*: `buildLedger`, `entryProblem`, `entryEffect`, `namedParticipants`, `splitPeople`, `splitBreaks`,
     `restOf`, `rankOf`, `joinOrder`, `isSettled`, `participantMemberOf`;
   - *money as text and input*: `formatMoney`, `decimalOf`, `amountText`, `parseAmount`, `parseRate`, `minorUnitsOf`,
     `knownCurrencies`; *rates*: `parseTable`, `crossRate`.
   Shapes: a body is the format's JSON object exactly (`v`, `heads`, its kind's fields) — the web's `RevisionBody` union
   stays as the web's type-only declaration it draws by, and one compile-time assignability check holds the façade's
   shapes to it both ways (the search's rule). Money results are `bigint` (`Big` crosses as its decimal string to
   `BigInt`), maps are JavaScript `Map`s (the web's `PotLedger` reads `.get`), enums cross as the web's string unions
   (`'payers-unbalanced'`, `'live'`), a time as epoch milliseconds (the web's `Date` fields `createdAt`, `purgeAfter`,
   `recordedAt` become numbers; their few readers adjust). **No opaque handle**: the held revisions are the web's state
   (`potSession.ts` holds and replaces them), not state a rule keeps across calls, so decision 5's exception does not
   apply. The cost is re-reading each body into Kotlin per `buildLedger`/`checkLedger` call; decision 9's timing bound
   decides whether that holds. **If it misses**, the named fallback is a `PotRevisions` handle (opaque, `markRaw`) that
   keeps the parsed bodies by revision id — decided here so a miss is a measured switch, not a redesign.

6. **Money as text moves with Expenses, and its vectors turn around** (ADR 0154 § Consequences named this move for it).
   `formatMoney` becomes `Shown.money`, `decimalOf` `Shown.number`'s digits, `minorUnitsOf`/`knownCurrencies` the
   core's `Currencies` table; the web's `Intl` path for money is deleted. Why: the web's locales are `en` and `de` only
   (`SUPPORTED_LOCALES`, `src/web/src/i18n/index.ts`), which `Shown` writes; and the digits a new Pot or entry is
   **sealed** with stop depending on the browser's ICU (ADR 0154 measured four currencies' digits move between ICU 77 and
   78: a browser on another ICU seals another `minorUnits` than the apps). The visible change: the currency picker offers
   the core's table (measured from `Intl` under ICU 78.3) instead of the browser's list. The `money` and `number`
   sections, and the digits `ShownVectorsTest.theDigitsAreTheWebs` checks, leave `src/mobile/words/format-vectors.json`
   for `src/mobile/words/money-vectors.json`, written by the core (`VEYQ_WRITE_VECTORS=1`, as `SearchVectorsTest`
   writes the search's), byte for byte today's cases at the turn; `format-vectors.mts` keeps writing the sections whose
   formatter stays in the web (size, dates, ranges, list, length, distance). One file, one writer.

7. **Twins deleted, assertions accounted for one by one** (ADR 0155 decision 2's test criterion, kept for every move).
   Each case of the deleted web tests — `domain/{money,format,codec,heads,ledger,memberParticipants}.test.ts`,
   `amounts.test.ts`, the `parseTable`/`crossRate` part of `exchangeRates.test.ts` — lands in one of: a vector case
   (anything the web and the apps must answer alike, observable through the façade: arithmetic, format acceptance and
   refusal paths, ledger results, the check's lists, money text, parsing, cross rates); a core common test
   (`ExpensesRulesTest`, the web's former unit cases, as `SearchRulesTest` is the search's); or a staying web test. The
   web runs the vectors through the package in `features/expenses/vectors.test.ts` (the search's `vectors.test.ts` is
   the model) and the package in `src/expenses.test.ts`. Core tests the move relies on that sit in `jvmTest` today
   (`ExpensesVectorsTest`, `LedgerRulesTest`) move to `commonTest` where they need no JVM API, so the JavaScript
   compilation compiles them (`compileTestKotlinJs`). Stryker: `src/web/stryker.mutate.json` names no Expenses file
   (`grep -c expenses src/web/stryker.mutate.json` → 0), so no measured mutation score is lost.

8. **Decision 8's boundary — what stays TypeScript because it has a flow, or calls one.** `potSession.ts` (its Kotlin
   twin `PotSession.kt` and `PotModel.kt` expose flows), the entry editor's state (`EntryEditor.kt` has coroutines),
   the create and settings sheets (`PotCreateSheet.kt`, `PotSettings.kt`), the door, the guest page, the offline queue
   (`PotDoor.kt`, `PotGuest.kt`, `QueuedExpenses.kt`), and the rates' `dayTable` (a `Mutex` and the device store).
   Locator: `for f in src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/expenses/*.kt; do echo "$f
   $(grep -cE 'kotlinx\.coroutines|StateFlow|Flow<|suspend ' $f)"; done`. A pure function whose Kotlin home is an
   object that also holds such state (`ExchangeRates`'s `lock`, `PotView`'s companion parsers, `Shown` if its reach pulls
   one) moves into a pure file of its own in phase 2, because an object's initializer runs every property and would drag
   the coroutines in — the slice check is the proof, not this list.

9. **The gate of this move** — every line must hold; a miss goes to the owner with its number (ADR 0155 decision 2's
   rule), a no-go reverts this CR's commits:
   - **size, net:** the eager set (`main` + the chunks `index.html` modulepreloads) and Expenses' chunks grow together by
     ≤ 25,600 B gz, the deleted TypeScript counted; Memories', Depot's and core's lazy chunks grow by ≤ 1,024 B gz each.
     Measured once, before (the tree at phase 4's first edit, its own build) and after, with the pilot's method (gzip
     level 9 per chunk, each chunk attributed by its source map), recorded in ADR 0155. **Enforced permanently** in the
     package's slice check: what `expenses` adds over `search` alone (both entries bundled together minus `search`
     bundled alone) ≤ 25,600 B gz — gross, the TypeScript not subtracted, so it can only be stricter than the bound;
     `@noble/*` is external there, which is why the web measurement is the authoritative one (decision 10's risk);
   - **delivery:** three clean builds byte-identical (`src/mobile/CLAUDE.md` § Prove your work's command); no `eval`,
     `new Function` or DOM sink; `verify:csp`, `verify:license-sources` green;
   - **behaviour:** the staying web tests pass with only their imports and the `Date`→number reads changed — any other
     change is listed in the retrospective with why (the pilot's two were ruled a pass by the owner when they read the
     deleted code's internals; the same reading applies, stated, not assumed); `buildLedger` and `checkLedger` over a
     10,000-entry Pot ≤ 1.5× the TypeScript's median (Node, the same machine, measured before the deletion), answers
     identical;
   - **tests:** decision 7's accounting complete; a mutated Kotlin rule (a +1 in `Ledger.settleUp`, half-even instead of
     half-away in `Money.roundHalfAwayFromZero`) turns `src/expenses.test.ts` and the web's `vectors.test.ts` red.

## Inventory (locators — run at refine, read as lists; re-run at implement)

| What | Locator (from the repo root) | At refine |
|---|---|---|
| The web's imports of the moved files | `for m in ledger format codec revisions heads money; do echo "$m $(grep -rlE "from '[^']*(domain/\|\./)${m}'" src/web/src \| wc -l)"; done`, then the same `grep -rl` per file for the list | 25 files import `ledger`, 28 `format`, 12 `codec`, 16 `revisions` (one in `core/export/importSubtree.midRun.test.ts`), 6 `heads`, 6 `money` (its outside users: `amounts.ts`, `exchangeRates.ts`, `EntryHistory.vue`) |
| Second angle: who reads the deleted symbols by name, wherever imported | `git grep -nwE "buildLedger\|checkLedger\|openServedRevisions\|sealRevision\|readRevisionBody\|formatMoney\|minorUnitsOf\|knownCurrencies\|crossRate\|parseTable" -- src/web src/admin-web tests` | `formatMoney` in 6 components and `src/web/src/test/formatVectors.ts`; `knownCurrencies` in 3 components, `exchangeRates.ts` and `formatVectors.ts` |
| The `Date` fields that become numbers | `git grep -nE "\.(createdAt\|purgeAfter\|recordedAt)\b" -- src/web/src/features/expenses` | — |
| Kotlin comments and docs citing a deleted file as the rule ("a port of …ts") | `git grep -lE "(money\|ledger\|format\|codec\|heads\|revisions\|amounts\|exchangeRates)\.ts" -- src/mobile docs site contracts scripts` | `Big`, `Codec`, `Currencies`, `ExchangeRates`, `Format`, `Ledger`, `LedgerCheck`, `Money`, `PotView` `.kt`; `docs/apps/expenses.md`, ADR 0123/0154/0155 (records: ADRs keep their history, `docs/apps/expenses.md` is updated) |
| Coverage refs to the deleted tests | `grep -rnE "expenses/domain/(money\|format\|codec\|heads\|ledger\|memberParticipants)\.test\|expenses/(amounts\|exchangeRates)\.test" docs/coverage` | `uc.expenses.{balances,record-entries,verify-ledger,exchange-rates,entry-history,manage-participants}`, `uc.spaces.rekey-after-rotation` |
| Kotlin twins' reach (coroutines / serialization / network per file) | decision 8's loop, plus `grep -c kotlinx.serialization` | pure: `Big`, `Codec`, `Currencies`, `Ledger`, `LedgerCheck`, `Money`; serialization: `Format` (9), `ExchangeRates` (9, and a `Mutex`) |
| The format vectors' writers and readers | `git grep -n "format-vectors" -- src scripts docs` | `format-vectors.mts`, `formatVectors.ts`/`.test.ts`, `ShownVectorsTest`, `Shown.kt`, `Currencies.kt`, `docs/crosscutting/native-apps.md`, `src/mobile/CLAUDE.md` |
| Published pages | `git grep -niE "same (suggestion\|code)\|currenc" -- site/src/content/docs/expenses site/src/content/docs/de/expenses` | `expenses/settle-up.mdx` ("Every device shows the same suggestion.") and its German twin |

## Phases

**Phase 1 — contracts: the vectors both sides answer before anything moves.**
`contracts/expenses/v1-vectors.json` gains sections for every rule decision 7 sends to a vector and that is not pinned
today (today: `conversion` 15, `rounding` 12, `pots` 3): the reader's acceptance and refusal paths (decision 3's parser
edges among them), the ledger check's `missing`/`altered`/`unreadable`, `entryProblem` and `splitBreaks`, money text and
the parsers per language, `crossRate`/`parseTable`. Their `expected` come from today's TypeScript; `ExpensesVectorsTest`
answers every section over the Kotlin. Both answering one file before the deletion is the proof the twins agree — a
disagreement is stopped and handed back with the case, never resolved by editing the expected value.
Done when: the web's `pnpm run check` and `./gradlew :core:jvmTest --tests '*ExpensesVectorsTest*'` green; a mutated TS
rule and a mutated Kotlin rule each turn their side red (one case named per section); `validate_*.py` green.

**Phase 2 — core: the seam, the pure homes, the turned money vectors.**
Decision 3's tree and codec with its JS and kotlinx actuals; `Format`, the rate table reader and the parsers onto it;
the pure functions moved out of state-holding objects (decision 8); `ExpensesVectorsTest` gains the write mode and
rewrites the file byte for byte; `ExpensesRulesTest` takes the web's unit cases; `money-vectors.json` written by the
core, `format-vectors.mts`/`.test.ts` stop writing `money`/`number`; the Kotlin comments stop calling themselves ports.
Done when: `./gradlew :core:compileKotlinJs :core:compileTestKotlinJs :core:jvmTest :core:macosArm64Test` green, the
apps' builds that link the core green, as `src/mobile/CLAUDE.md` § Prove your work names them (Apple ad hoc); `git diff contracts/expenses/v1-vectors.json` after the rewrite empty; the web's check green.

**Phase 3 — core-js: the façade, the entries, the checks.**
`ExpensesWeb.kt`/`Shapes.kt` (decision 5); decision 4's entry files and `package.json`'s `./expenses`; `src/expenses.test.ts`
(every vector section, the crypto vectors' `expense-entry` case opening, a revision refusing to open at another address,
a key passed as a view and zeroed by the caller); the slice check's `expenses` entry: its forbidden list adds
`hash-library-sha3`/`sponges-library-keccak` (the codec needs no X-Wing), its control is `expenses/Ledger.kt` in the map,
its increment bound (decision 9), and the `search`-alone delta printed and recorded (decision 4's measurement); a
type test that `@veyq/core-js/search` exports no Expenses name and the reverse.
Done when: `cd src/packages/core-js && pnpm run build && pnpm run check` green; three clean builds one digest; the bites:
decision 9's two Kotlin mutations red here, a kotlinx import reached from `Format` red in the slice check, a cross-entry
import red in the type test.

**Phase 4 — web: the callers switch, the twins go.**
Baseline first, before the first edit: the eager set and every chunk (decision 9's method) and the 10,000-entry timings.
Then the callers import the façade (through the surviving type-and-glue modules, which hold no rule: no arithmetic, no
validation branch, no seal); the deleted files go; `features/expenses/vectors.test.ts` added; coverage refs repointed in
the same commit as the tests they name (`changes/README.md` § Coverage).
Done when: `pnpm run check`, `pnpm run build`, `verify:csp`, `verify:license-sources` green; decision 9's gate table
filled; `validate_coverage.py` and `validate_web_core_imports_no_applications.py` green; **the browser pass** (one
throwaway account, the dev stack per `infra/README.md`, the UI in English and in German): create a Pot in JPY and one in
EUR, record an expense with items and a percent split, a refund, a transfer in another currency with the day's rate
filled, see balances, settle-up, the overview and an entry's history; a link guest's read-only view of the same Pot; a
reported entry opened in the moderator's review (`openRevisionWithKey`). Every amount and balance identical to the same
pass on the baseline build (screenshots only for the final proof).

**Phase 5 — docs.** ADR 0155: `Built` (decision 4's seam, decision 7's first application), § Consequences the gate's
table and decision 4's granularity finding; ADR 0154: the money formats turned (`Shown` the source,
`money-vectors.json`); `docs/apps/expenses.md` (where the rules live); `docs/crosscutting/native-apps.md` and
`src/mobile/CLAUDE.md` (the vectors' writers; Expenses beside the search in § Prove your work); `src/web/CLAUDE.md` if
it names Expenses' rules; `site/src/content/docs/expenses/settle-up.mdx` and `de/expenses/settle-up.mdx` (the browser
and the app compute it with the same code, as the search page says); inbox #1046.
Done when: every `scripts/validate_*.py` green (`validate_guard_gaps.py` only if a guard changed).

## Risks

- **`@noble/*` is external to the slice check.** A primitive the Kotlin `JsPrimitives` reaches that the web did not load
  eagerly before (ML-KEM, Argon2 behind an override kept alive) shows only in the web measurement. Phase 4's chunk
  attribution names every `@noble` module in the eager set before and after.
- **KT-68281's sort step** may meet an Expenses interface list it refuses (an interface with a default member).
  It fails the build loudly; the fix is in the Kotlin (the default moves) or the case is handed back — never a widened
  sort.
- **Two JSON parsers** (decision 3): a parser edge the vectors do not name can still differ. Phase 1's edge cases are
  the defence; a new edge found later is a vector case first.
- **Per-call re-reading** (decision 5) may miss the 1.5× bound on large Pots; the handle is the named fallback, `Big`
  over native `BigInt` on JavaScript the second.
- **Shared buffers.** Views instead of copies keep zeroing effective, and mean a Kotlin function that keeps a reference
  to its argument keeps the caller's bytes; the codec keeps none (its tests in phase 3 hold a zeroed key).
- **Users with Expenses switched off** download its rules eagerly — true of the TypeScript today; unchanged in kind.

## Questions for the owner

1. **What budget do the later Expenses moves get** (entry form, Pot meta, writes, re-key, receipts, import and export —
   decision 2)? ADR 0155 decision 7 gives "an application" 25 KB gz; this move is expected to use ~18 KB of it.
   *Recommendation:* count the 25 KB against this move alone, and gate each later move of an already-moved application
   as a slice is gated: ≤ R × the gzip of the TypeScript it deletes (R = 3.0). A fixed remainder (~7 KB) would force the
   biggest, most logic-heavy part of Expenses to split arbitrarily; the ratio scales with what is actually removed.

## Definition of done

Decision 9's gate holds and is recorded in ADR 0155; the deleted TypeScript is gone and decision 7's accounting is
complete; `@veyq/core-js`'s check, the web's check, `./gradlew :core:compileKotlinJs :core:compileTestKotlinJs
:core:jvmTest :core:macosArm64Test` and every `scripts/validate_*.py` green; the browser pass done.

## Retrospective

(Filled by the implementer.)
