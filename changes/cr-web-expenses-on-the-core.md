---
id: cr-web-expenses-on-the-core
title: The pilot — the web's Expenses reads, checks and seals its revisions through the Kotlin core's façade, its TypeScript twin deleted, and a measured go or no-go
status: planned
satisfies: []
adrs: ["0155", "0108", "0079", "0026", "0065"]
invariants:
  - "a move is a move: every Pot the web shows, writes and settles reads the same before and after (ADR 0155 decision 2, behaviour)"
  - "code a façade reaches uses no kotlinx.serialization, coroutines or Ktor, and no NotOnTheWeb actual (ADR 0155 decision 4)"
  - "the apps read and write Expenses exactly as before: the JSON seam's JVM and native side is kotlinx.serialization"
  - "the gate's criteria are ADR 0155 decision 2's, unchanged by what the phases find; a miss is a no-go"
---

## Goal

ADR 0155 decisions 2, 4, 5 and 6, the pilot's P1. After this CR the web's Expenses calls `@veyq/core-js/expenses` for
its arithmetic, ledger, ledger check, revision format and revision seal — the Kotlin core's `expenses/{Big,Money,
Currencies,Ledger,LedgerCheck,Format,Codec}` with the suite's `V1` over `@noble/*` —, the TypeScript twins of those
rules and their tests are gone, and a measured go or no-go is reported to the owner before anything else moves.

## Context & constraints

- **Read first:** ADR 0155 (decision 2 is the gate, word for word); ADR 0108 (a Pot, its sealed revisions);
  `contracts/expenses/entry.v1.schema.json` (every integer bounded to a safe one) and `v1-vectors.json`;
  `docs/apps/expenses.md`; the core's `expenses/` files above and their tests (`ExpensesVectorsTest`, `LedgerRulesTest`,
  `BigTest`, `PotWritesTest`); the web's `src/web/src/features/expenses/domain/`; `src/web/CLAUDE.md`;
  `changes/README.md` § A test migration is accounted for per assertion; `cr-core-js-target` (built before this).
- **Order:** after `cr-core-js-target`, and after the change that loads the web's applications lazily (in flight
  2026-11-20): the gate measures the Expenses chunk, which does not exist before it. Until that change lands this CR
  does not start.
- **A move is a move.** The web keeps its Vue components, its types for the shapes it draws, `amounts.ts` (its money
  display, i18n rendering), its `Intl` minor units (a façade takes `minorUnits` as the TypeScript does), the entry form,
  the exchange rates, receipts, the Pot session and its API calls. A rule change found on the way is filed, not made.
- **Out of scope:** any other application; a screen model or flow (ADR 0155 decision 8); the entry form, the Pot
  session and the exchange rates, whose Kotlin twins reach Ktor, coroutines or the words (the measured slice left them
  out for that reason).

## Measured at refine (2026-11-20)

Branch `claude/kjs-refine` at `ff410c6c9`; `W=src/web/src/features/expenses`, `K=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/expenses`.

| # | What | Where / command |
|---|---|---|
| E1 | The TypeScript twins: `domain/money.ts`, `ledger.ts`, `format.ts`, `codec.ts`, `heads.ts`, `revisions.ts` (~1,000 lines without tests); their Kotlin twins `Big`, `Money`, `Ledger`, `LedgerCheck`, `Format`, `Codec` (`Revisions` in it: `byRecord`, `currentHeads`, `headsToName`). Each export decides by its own twin: one with none stays TypeScript. | `grep -n "^export" $W/domain/{money,ledger,format,codec,heads,revisions}.ts`; `grep -n "^fun \|^object \|^class \|    fun " $K/{Money,Ledger,LedgerCheck,Format,Codec}.kt` |
| E2 | Their importers, tests included (45 files on `ff410c6c9`). | `grep -rlE "domain/(money\|ledger\|format\|codec\|heads\|revisions)['\"]\|\./(money\|ledger\|format\|codec\|heads\|revisions)['\"]" src/web/src` |
| E3 | Of the slice only `Format.kt` reaches kotlinx.serialization (`JsonPrimitive` and friends); `Codec.kt` reaches the suite only. | `grep -l "import kotlinx\|import io.ktor" $K/{Big,Money,Currencies,Ledger,LedgerCheck,Format,Codec}.kt` |
| E4 | The format bounds every amount and revision to a safe integer, and both readers accept a whole number in any JSON spelling (`1.0`, `1e3`) and refuse anything above `2^53 - 1`; so `JSON.parse`'s numbers decide every case as the JVM does, with no reviver source. | `grep -n "9007199254740991" contracts/expenses/entry.v1.schema.json`; `sed -n 95,105p $W/domain/format.ts`; `sed -n 125,135p $K/Format.kt` |
| E5 | The vectors: 15 conversion, 12 rounding, 3 Pots (invalid entries among them). | `/usr/bin/python3 -c "import json;d=json.load(open('contracts/expenses/v1-vectors.json'));print({k:len(v) for k,v in d.items() if isinstance(v,list)})"` |
| E6 | Coverage: 16 refs to the twins' tests under six use cases (five of Expenses', `uc.spaces.rekey-after-rotation`). | `grep -rn "expenses/domain/\(money\|ledger\|format\|codec\|heads\|revisions\)" docs/coverage` |
| E7 | Stryker mutates no Expenses file. | `grep -c expenses src/web/stryker.mutate.json` |
| E8 | The app image's frontend stage installs the workspace from the copied members (crypto, web) and builds the web with no JDK. | `sed -n '/AS frontend-build/,/AS backend-build-base/p' src/backend/Dockerfile`; `scripts/validate_pnpm_installs_gated.py` holds its COPY |
| E9 | The web's direction guard reads a non-relative specifier only by an application's directory or client path. | `grep -n "non-relative" scripts/validate_web_core_imports_no_applications.py` |
| E10 | The site says `@veyq/crypto`'s typed seal is the only way the web client writes a seal. | `grep -n "@veyq/crypto" site/src/content/docs/security/cryptography.md` |
| E11 | The web's built licence file is checked for every source it ships. | `grep -n "license-sources" src/web/package.json` |

## Decisions

- **W1 — the JSON seam.** Common code gains one JSON seam (an `expect` over a small tree: object, array, string,
  number, boolean, null; parse and compact stringify), `Format.kt` reads and writes through it, and nothing else in
  the slice changes. The JVM and native actual is kotlinx.serialization (today's reading, byte for byte on the write);
  the JavaScript actual is `JSON.parse`/`JSON.stringify` (E4: no reviver source). No test pins
  `RevisionFormat.encode`'s bytes today (`grep -rln "RevisionFormat\." src/mobile/core/src/commonTest`), so phase 2
  first adds one: each body kind's encoded bytes, captured on the JVM before the edit, held on every target — the
  package's check holds the JavaScript encoder to the same bytes.
- **W2 — the façade.** `jsMain/…/expenses/web/ExpensesWeb.kt`, `@JsExport`, plain values only (ADR 0155 decision 5):
  the format's JSON shapes in, `bigint` for amounts and fractions (`{ num, den }`), `Uint8Array` for keys and bodies,
  and the same errors the TypeScript threw where a caller tells them apart (`RevisionFormatInvalid`,
  `RevisionUnreadable`, by name). Its surface is what E2's callers call, no more. The package exports it as
  `@veyq/core-js/expenses`; the shipped module's exports are the façades alone.
- **W3 — the slice check.** The package's build ends with a check over the shipped module's source map: no source under
  kotlinx-serialization, kotlinx-coroutines, ktor or `NotOnTheWeb.kt`; and its size printed (raw, minified, gz) beside
  ADR 0155's budget, red above it. It runs in the package's `pnpm run check` and so in CI.
- **W4 — the web's side.** E2's files import from `@veyq/core-js/expenses`; E1's exports with a twin are deleted; the
  types the components draw stay in `domain/format.ts` and `ledger.ts`. The web's dev server and `pnpm run check`
  build the package first (one documented command for a fresh worktree); the launcher's `web:run` does the same.
- **W5 — the tests.** Each deleted test's assertions are accounted for, one by one, in a table in this CR: the core
  test that holds it (named, existing or added in phase 2), or the web's new `expenses.core.test.ts`, which runs
  `contracts/expenses/v1-vectors.json` whole through the package and keeps the web-only cases (an error's name, a
  `bigint` crossing). E6's refs move to the web tests that now hold them.
- **W6 — the guard.** `validate_web_core_imports_no_applications.py` reads `@veyq/core-js/<owner>` as that owner's,
  derived from its `OWNERS` like `features/<owner>`; core importing it, or another application, is red.
- **W7 — delivery.** The app image gains a stage that builds the package on the core's JDK (pinned by digest, as every
  stage) and copies `dist/` into the frontend stage before the web's build; `validate_pnpm_installs_gated.py` learns
  the new member and COPY. The built licence file carries the Kotlin standard library's notice.

## Phases

- [ ] **1 the baseline** — on the tree with lazily loaded applications, before any edit: the Expenses chunk and the main
  chunk (raw, min, gz, `vite build` into scratch, the measuring session's method), `pnpm run check`'s time in `src/web`
  cold and warm, the `frontend` job's time over its last three runs, E2's list. Written into § Measured at implement.
  · Done when: the numbers are there, and the Expenses chunk is a chunk of its own (else stop: the order above).
- [ ] **2 core** — W1; core tests added where W5's accounting needs one. · Done when: `:core:jvmTest
  :core:macosArm64Test`, `:core:compileKotlinJs` and `:bridge:jvmTest` green; `ExpensesVectorsTest` and `PotWritesTest`
  unchanged; the encode test added before the seam and green after it. **Bite:** the seam's stringify reordering two
  keys → the encode test red.
- [ ] **3 the façade** — W2, W3. · Done when: the package's `pnpm run check` green with the vectors through the façade.
  **Bites:** `Json.encodeToString` planted in `Money.kt` → the slice check red; a `deviceFiles` call planted in the
  façade → red; settle-up's remainder handed +1 in `Ledger.kt` → the vectors red; the JavaScript stringify reordering
  two keys → the package's encode case red.
- [ ] **4 web** — W4, W5, W6, W7; E6's refs; the site's E10 sentence (both suites export only typed seals, and the
  web's Expenses seals through the Kotlin one). · Done when: `pnpm run generate:api` then `pnpm run check` in
  `src/web` green; `src/admin-web` and `tests/e2e` checks green; the app image builds; every guard but guard_gaps
  green; the browser pass of § Passes run. **Bites:** a core file importing `@veyq/core-js/expenses` → the guard red;
  the +1 settle-up mutation of phase 3 → the web's `expenses.core.test.ts` red.
- [ ] **5 the gate** — ADR 0155 decision 2, each criterion measured against phase 1 and written into a table here: size
  (the two chunks), build (the `frontend` job over three runs, its timeout), development (a `Ledger.kt` edit to the
  reloaded dev page, timed warm three times; a thrown façade error's stack frame naming the `.kt` line in Chrome's
  devtools; `grep -n "any" dist/*.d.ts` over the façade), tests (W5's table complete), delivery (two clean builds'
  `shasum -a 256`; `grep -E "eval\(|new Function|innerHTML|outerHTML|insertAdjacentHTML|document\.write"` over
  `dist/`; `verify:csp`, `verify:license-sources`), behaviour (§ Passes). · Done when: the table is complete and the
  report is with the owner; nothing after this phase starts before the owner's go or no-go.
- [ ] **6 harvest** — on a go: ADR 0155's `Built:` (decisions 2, 4–6); the root `CLAUDE.md` (a moved application's
  phase order: contracts → backend → core → clients), `docs/ai/README.md` § The pipeline where it names the order,
  `src/web/CLAUDE.md` § Prove your work (the package's build before the web's check), `docs/apps/expenses.md` (where
  the rules run), `native-apps.md` (the web draws Expenses from the core), and inbox rows for P2. On a no-go: phases 2–4
  and `cr-core-js-target` reverted by their commits' hashes, ADR 0155 edited to the no-go with the table. · Done when:
  every guard but guard_gaps green; the site's `pnpm run check` green.

## Passes

A throwaway's Pot (`throwaway:account`), on the dev stack, Chrome, before phase 2 and after phase 4, same steps: open the
Pot; add an expense with two payers and an item split by amounts, a refund and a transfer; edit one entry and restore an
earlier revision; read the overview's balances and settle-up; screenshot the overview before and after, compared.
Then the app (Android emulator or the Linux app) opens the same Pot and shows the same balances to the cent. Both
throwaways are erased after.

## Owner questions

- **Q1 — the budget.** ADR 0155 decision 2 sets the first application's ceiling at 50 KB gz (measured 35 KB gz for this
  slice, 6 KB in TypeScript) and decision 7 every next one at 25 KB gz (the slice's own code measured ~20 KB). Both
  are load time for every person who opens a Pot on a slow line. **Recommendation:** keep both as written.
- **Q2 — the go.** The gate's criteria decide; phase 5 reports and stops. **Recommendation:** a full pass is the go
  without a further question; a single miss comes to you with its number rather than reverting unasked.

## Definition of done

Phases 1–6 checked; every bite seen red and green; the gate's table complete and the owner's answer recorded; the
passes run; `git diff --stat` over `src/backend/src`, `src/admin-web/` and `contracts/` empty.

## Retrospective

_(filled at the end of the implementation)_
