---
id: cr-web-search-on-the-core
title: The pilot — the web's search folds, ranks and orders through the Kotlin core's façade, its TypeScript twin deleted, and a measured go or no-go
status: planned
satisfies: []
adrs: ["0155", "0125", "0146", "0079", "0026", "0065"]
invariants:
  - "a move is a move: every search the web answers lists the same results in the same order before and after (ADR 0155 decision 2, behaviour)"
  - "code a façade reaches uses no kotlinx.serialization, coroutines or Ktor, and no NotOnTheWeb actual (ADR 0155 decision 4)"
  - "the apps search exactly as before: the core's one new parameter defaults to today's order"
  - "the gate's criteria are ADR 0155 decision 2's, unchanged by what the phases find"
---

## Goal

ADR 0155 decisions 2, 4, 5 and 6, the pilot's P1 — a slice of **core** logic, not an application (owner 2026-11-20:
the Kotlin runtime's floor lands in the entry chunk with the first core logic anyway, so it is paid once there, first;
each application then adds only its own code). After this CR the web's search calls `@veyq/core-js/search` for its
folding, its chips, its matching and ranking and its result order — the Kotlin core's `search/{Fold,Query,Results,Model}`
with `session/LocalDays` —, the TypeScript twins of those rules and their tests are gone, and a measured go or no-go
decides before anything else moves. Expenses is the first application after a go (P2); it gets its CR then.

## Context & constraints

- **Read first:** ADR 0155 (decision 2 is the gate, word for word); ADR 0125 (the search) and ADR 0146 (its vectors and
  the core's port); `contracts/platform/search/v1-vectors.json`; the core's `search/` files above and their tests
  (`SearchVectorsTest`, `SearchModelTest`); the web's `src/web/src/core/search/` and `src/web/src/shared/text/fold.ts`;
  `src/web/CLAUDE.md`; `changes/README.md` § A test migration is accounted for per assertion; `cr-core-js-target`
  (built before this).
- **Order:** after `cr-core-js-target`. The lazy applications it once waited for have landed (ADR 0079 § Consequences,
  2026-11-20); the gate measures the eager set they left.
- **A move is a move.** The web keeps its Vue components (`SearchField.vue`), its `SearchDocument` type for what it
  draws (the `open` closure and the icon stay the web's, beside the core's document), the index and its generations
  (`searchIndex.ts`, `documents.ts`: IndexedDB, sealed metadata, workers), the contributions, the people and settings
  documents, Memories' facets and media documents (an application's, they stay), and its `Intl.Collator` for the name
  order (S3). A rule change found on the way is filed, not made.
- **Out of scope:** any application; a screen model or flow (ADR 0155 decision 8; the core's `SearchModel.kt` is one);
  the JSON seam (no rule of this slice reads JSON — Expenses brings it in P2); `Documents.kt`, which reaches coroutines
  and kotlinx.serialization.

## Measured at refine (2026-11-20)

Tree `3a05be7fe` (the lazy applications merged). Builds into scratch only: `npx vite build --sourcemap --outDir <scratch>`
in `src/web` (12.7 s), each chunk attributed by its source map (bytes between two mappings to the earlier one's source,
`source-map-js`), gzip level 9. `K=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`, `W=src/web/src`.

| # | What | Where / command |
|---|---|---|
| S1 | **The eager set:** `main` 1,051,910 B / 300,411 gz; `main` with the chunks `index.html` modulepreloads 606 KB gz (ADR 0079's number). | the build above; `grep -o 'modulepreload[^>]*' <scratch>/index.html` |
| S2 | **The slice's TypeScript is eager and split across two of those chunks:** `query.ts` 5,536 B and `results.ts` 905 B in `main`; `model.ts` 681 B and `fold.ts` 330 B in `activeSpace` (modulepreloaded). Together 7,452 B min, 3.1 KB gz bundled alone (2,613 + 516). | the source-map attribution over `main-*.js` and `activeSpace-*.js` |
| S3 | **Every candidate weighed** (eager, a Kotlin twin, held by vectors, pure): search — all four (S2, `v1-vectors.json`); share-link parsing (`bookmarkLink.ts`, `cases.v1`) — all four but 1,432 B, too small to measure a ratio by; the subtree export (`zip.ts` 10,694 + `manifest.ts` 3,826 + `archiveName.ts` 923 B in `main`, `import-cases`) — streams and a serializer behind it, and a wrong byte loses data; the account rotation (`rotateKeys.ts` 11,863 B) — no vectors, key material. Expenses' domain is eager too (`ledger.ts` 5,511, `format.ts` 4,307, `money.ts` 1,189, `codec.ts` 960, `revisions.ts` 487 B in `main`, reached through its `contentSchema.ts` and `levelRows.ts`) but is an application's. | the attribution over every modulepreloaded chunk, intersected with the web files the core names (`grep -rhoE "[A-Za-z0-9_]+\.ts\b" $K \| sort -u`, 161 names) |
| S4 | **A variant-B probe of the slice** (Kotlin 2.4.21, IR, ES modules, es2015, no experimental flag; `Fold`, `Query`, `Results`, `Model`, `LocalDays` copied unchanged, `nfd` over `String.prototype.normalize`, `localWallClock` over `Date`, a sketch façade of plain values with an opaque corpus and matcher): bundled and minified 78,497 B / **27.7 KB gz**; of the minified bytes the standard library 53,036 (the floor: Expenses' probe measured 52,415) and the slice with its façade 25,231 — by share of the gzip ~18.7 KB floor, **~8.9 KB own**. Own code 3.4× its TypeScript minified (Expenses' probe: 3.7×). Two builds byte-identical. | `compileProductionLibraryKotlinJs` in a scratch Gradle project; `esbuild --bundle --minify --format=esm --metafile`; `gzip -9c \| wc -c` |
| S5 | **The probe passes the vectors' pure cases:** 72 checks (`fold` 10 with its map, `tails` 7, `wordPrefix` 9, `nameParts` 11, `match` 25 over `corpus`); `fold` without `ß → ss` turns 5 red. `facets` and `medium` run Memories' functions and stay the web's. | `node vec.mjs contracts/platform/search/v1-vectors.json` over the probe's bundle |
| S6 | **Speed through the façade:** a 10,000-document corpus made once in 26.8 ms; a query with the matcher cleared each time, median 1.0 ms, p90 1.9 ms (Node, one run). | the probe's `perf.mjs` |
| S7 | The web's twins: `core/search/query.ts`, `results.ts`, the rules in `model.ts` (`searchDocument`, `yearsOf`, `monthsOf`), `shared/text/fold.ts`; their importers, tests included, 21 files. | `grep -rlE "search/(query\|results\|model)['\"]\|\./(query\|results\|model)['\"]\|text/fold['\"]" $W` |
| S8 | The name order differs: the web orders equal ranks by `Intl.Collator(locale, { numeric: true, sensitivity: 'base' })` over `name`, the core by `NameOrder` over the folded name, its residual named (punctuation, a letter a language collates apart). | `sed -n 76,81p $W/core/search/results.ts`; `sed -n 75,90p $K/search/Results.kt` |
| S9 | The matcher keeps state across calls (a corpus generation, a history of 64 queries): the core's `Query.Matcher` over `SearchDocuments`, the web's `createMatcher` over a list's identity. | `sed -n 290,330p $K/search/Query.kt`; `sed -n 340,395p $W/core/search/query.ts` |
| S10 | The tests: `query.test.ts` 51 cases, `results.test.ts` 12, `fold.test.ts` 6, `vectors.test.ts` (the vectors' write mode, `VEYQ_WRITE_VECTORS=1`, records them from the web's functions). Coverage: 7 refs in `uc.platform.search.yaml`. Stryker mutates no search file. | `grep -c "it(" …`; `grep -n "core/search/\|text/fold" docs/coverage/features/platform/use-cases/uc.platform.search.yaml`; `src/web/stryker.mutate.json` |
| S11 | The app image's frontend stage installs the workspace from the copied members and builds the web with no JDK. | `sed -n '/AS frontend-build/,/AS backend-build-base/p' src/backend/Dockerfile`; `scripts/validate_pnpm_installs_gated.py` |
| S12 | The web's direction guard reads a non-relative specifier only by an application's directory or client path. | `grep -n "non-relative" scripts/validate_web_core_imports_no_applications.py` |
| S13 | The web's built licence file is checked for every source it ships. | `grep -n "license-sources" src/web/package.json` |

**Chosen: the search** — the one candidate that is eager, held by vectors on both sides, pure, and big enough to fix a
ratio by (S3); a wrong answer lists a wrong result and changes no data. Its cost: the floor it pays once (~19 KB gz)
plus ~9 KB gz of its own, against ~3 KB gz of TypeScript deleted (S2, S4).

## Decisions

- **S1 — the façade.** `jsMain/…/search/web/SearchWeb.kt`, `@JsExport`, exported as `@veyq/core-js/search`: `fold`,
  `foldMap`, `matchedText`, `wordPrefix`, `tailsOf`, `nameParts`, `matchedRanges`, `reasonKeyword`, `chipKey`,
  `suggestChips`, `yearsOf`, `monthsOf`, and the search itself — what S7's callers call, no more. Values cross plain
  (ADR 0155 decision 5): a document as the web's shape without `open` and `icon` (an `opensItself` flag for the one,
  the web's own array for the other), a chip as its tagged object, results as positions in the corpus the web passed.
  Each shape is an external interface, never `dynamic` at a signature (the probe's sketch used `dynamic`, which the
  `.d.ts` prints as `any`).
- **S2 — two opaque handles.** The corpus generation (the core's `SearchDocuments`) and the matcher keep state across
  calls (S9); each crosses as an opaque handle: no member in its `.d.ts`, held by the web `markRaw`, read only by the
  façade, dropped with the generation. This is ADR 0155 decision 5's one exception, written there.
- **S3 — the name order stays the browser's.** The core's `Results` gains one parameter, the order of two documents'
  names at an equal rank and place, defaulting to `NameOrder` over the folded name (the apps unchanged); the façade
  passes the web's `Intl.Collator.compare` over `name`. The vectors' `collator` cases hold it.
- **S4 — the slice check.** The package's build ends with a check over the shipped module's source map: no source under
  kotlinx-serialization, kotlinx-coroutines, ktor or `NotOnTheWeb.kt`; and its size printed (raw, minified, gz, the
  standard library's and the façades' share from the bundler's metafile) beside ADR 0155's budget, red above it. It
  runs in the package's `pnpm run check` and so in CI.
- **S5 — the web's side.** S7's files import from `@veyq/core-js/search`; `query.ts` and `results.ts` keep only the
  web's types and the adapter that turns a `SearchDocument` list into a corpus handle (once per generation) and positions
  back into documents; `fold.ts` is deleted. The web's dev server and `pnpm run check` build the package first (one
  documented command for a fresh worktree); the launcher's `web:run` does the same.
- **S6 — the tests.** Each deleted test's assertions are accounted for, one by one, in a table in this CR: the core
  test that holds it (named, existing or added in phase 2), or the web's `vectors.test.ts`, which runs
  `v1-vectors.json`'s pure cases through the package and keeps the web-only cases (a handle dropped with its
  generation, the collator crossing, Memories' `facets` and `medium`). The vectors' write mode moves to the core's
  `SearchVectorsTest`: the core's output is the one source (ADR 0155 § Consequences). S10's coverage refs move to the
  tests that now hold them.
- **S7 — the guard.** `validate_web_core_imports_no_applications.py` reads `@veyq/core-js/<entry>` as core's when the
  entry is a core area (`search`) and as that application's when it is one of the guard's `OWNERS`; an application
  importing core's entry stays allowed, core importing an application's entry is red.
- **S8 — delivery.** The app image gains a stage that builds the package on the core's JDK (pinned by digest, as every
  stage) and copies `dist/` into the frontend stage before the web's build; `validate_pnpm_installs_gated.py` learns
  the new member and COPY. The built licence file carries the Kotlin standard library's notice.

## Phases

- [x] **1 the baseline** — before any edit: the eager set (`main` and its modulepreloads) and every lazy chunk (raw,
  min, gz, S1's method), the slice's share of them (S2's method), `pnpm run check`'s time in `src/web` cold and warm,
  the `frontend` job's time over its last three runs, the search's speed over S6's corpus through the TypeScript
  (median and p90 of the same query list), S7's list. Written into § Measured at implement. · Done when: the numbers
  are there. **Built** 2026-11-20: B1–B6.
- [x] **2 core** — S3; core tests added where S6's accounting needs one; the vectors' write mode in
  `SearchVectorsTest`. · Done when: `:core:jvmTest :core:macosArm64Test`, `:core:compileKotlinJs` and `:bridge:jvmTest`
  green; `SearchVectorsTest` and `SearchModelTest` unchanged but for the write mode. **Bite:** the default order's
  name comparison reversed → `SearchVectorsTest` red.
  **Built** 2026-11-20: `Results.searchResults(…, names)` (default `BY_NAME`, NameOrder over the folded names); the write
  mode in `SearchVectorsTest` (`VEYQ_WRITE_VECTORS=1`), written as `JSON.stringify(v, null, 2)` — a rewrite from today's
  rules left `git diff contracts/` empty; `SearchRulesTest` (19 cases, S6's table). Two changes the CR did not plan, both
  for the speed criterion and rule-neutral: the scan's place and alias loops indexed, and the name test `String.holds`
  (`expect`; `includes` on JavaScript, `contains` elsewhere) — Kotlin/JS's `contains` was a quarter of the query time.
  Checks: `:core:jvmTest :core:macosArm64Test :core:compileKotlinJs :core:compileTestKotlinJs :core:jsPackage
  :bridge:jvmTest` green (`--warning-mode all`). Bites: the order reversed → `SearchVectorsTest.match` red; `holds` as
  `startsWith` on the JVM plus the matcher's remembered answer removed → 8 of 19 `SearchRulesTest` cases red.
- [x] **3 the façade** — S1, S2, S4. · Done when: the package's `pnpm run check` green with the vectors' pure cases
  through the façade, and the `.d.ts` of both handles without a member. **Bites:** `Json.encodeToString` planted in
  `Query.kt` → the slice check red; a `deviceFiles` call planted in the façade → red; `ß → ss` dropped in `Fold.kt` →
  the vectors red; the façade ignoring the passed collator → the `collator` cases red.
  **Built** 2026-11-20: `jsMain/…/search/web/{SearchWeb,Shapes}.kt`; every shape an `@JsExport external interface`
  (exportable, so the `.d.mts` declares it — a plain external interface was referenced but undeclared); the handles
  `SearchCorpus`, `SearchMatcher` (`private constructor()` only); `exports["./search"]`; `sideEffects: false` (the module
  imports Ktor's client for its engine's eager registration, which no façade reaches); `src/search.test.ts` (the pure
  sections, the matcher under both contributions' nodes, the collator crossing with `sv` against `de`) and
  `src/slice.test.ts` (esbuild over the entry, its sources through the chained maps; printed: 78,916 B minified, 27,734 B
  gz, the standard library 52,384 B, the core's own 26,219 B). Deviations: the façade exports `textRank`, `satisfies` and
  `searchRules` (Memories' gallery filter and the settings test call them) and not `matchedRanges`/`monthsOf` (no caller).
  The vectors' `collator` cases agree with NameOrder by design, so the collator bite is the package's `sv` case. Bites,
  each red then green: `Json.encodeToString` in `Query.matchedText` → slice check red (2 sources); `deviceFiles` read in
  `fold` → red (`NotOnTheWeb.kt`); `ß → ss` dropped → 4 vector cases red; `Results.BY_NAME` for the passed comparison →
  the collator case red.
- [x] **4 web** — S5, S6, S7, S8; S10's refs. · Done when: `pnpm run generate:api` then `pnpm run check` in `src/web`
  green; `src/admin-web` and `tests/e2e` checks green; the app image builds; every guard but guard_gaps green; the
  browser pass of § Passes run. **Bites:** a core file importing an application's `@veyq/core-js` entry → the guard
  red; the `ß` mutation of phase 3 → the web's `vectors.test.ts` red.
  **Built** 2026-11-20: S5–S8 as decided, S6's table below; `pnpm run core` (the package's build) before `check` and
  `dev`; the dev server keeps Gradle watching the core on its development link (`:core:jsPackageDev`, unplanned: the
  production link made a reload 16–18 s, the criterion is 15; Ktor's Node-only `import('ws')`, kept by the development
  link, answered with an empty module in dev); the image's stage builds on the backend's JDK image (one image, one tag:
  `validate_image_pins.py`), copying `scripts/repo_layout.py` and `validate_web_contrast.py` for the theme generator; the
  licence file's `kotlin-stdlib` entry (version from `dist/kotlin-stdlib.version`, Apache-2.0 text in
  `src/packages/core-js/notices/`). Two staying web tests changed beyond their imports, both where they reached into the
  moved TypeScript: `SearchField.test.ts`' matcher-memory case counted reads of `foldedName` (now: the field clears the
  core's matcher when its list closes, counted on `clearMatcher`; the memory itself is `SearchRulesTest`'s) and
  `searchIndex.test.ts`' query ran `matchAll` (now `searchResults`). `validate_pnpm_installs_gated.py` needed no change
  (it reads the lockfile's COPY, not the members). Checks: `generate:api` then `check` in `src/web` green (7,274 tests);
  `src/admin-web`, `tests/e2e`, `src/packages/core-js` green; the app image built (`docker build -f src/backend/Dockerfile`,
  493 s); every guard green, guard_gaps run (a validator changed). Bites: `import '@veyq/core-js/expenses'` in
  `core/search/results.ts` → the guard red; `ß → ss` dropped → the web's `vectors.test.ts` 4 cases red; the
  `kotlinStdlibNotice()` line dropped → `verify:license-sources` red. Browser pass: § Passes below.
- [x] **5 the gate** — ADR 0155 decision 2, each criterion measured against phase 1 and written into a table here: size
  (the eager set, `main` alone, every lazy chunk; the façade's own share and its ratio to S2's deleted TypeScript),
  build (the `frontend` job over three runs, its timeout), development (a `Query.kt` edit to the reloaded dev page,
  timed warm three times; a thrown façade error's stack frame naming the `.kt` line in Chrome's devtools;
  `grep -n "any" dist/*.d.ts` over the façade), tests (S6's table complete), delivery (two clean builds'
  `shasum -a 256`; `grep -E "eval\(|new Function|innerHTML|outerHTML|insertAdjacentHTML|document\.write"` over
  `dist/`; `verify:csp`, `verify:license-sources`), behaviour (S6's speed against phase 1's; § Passes). · Done when:
  the table is complete; a full pass is the go (Q2), a miss is with the owner with its number, and nothing after this
  phase starts before that answer.
  **Measured** 2026-11-20: the table under § Measured at implement — a miss (reproducibility), so phase 6 waits.
- [ ] **6 harvest** — on a go: ADR 0155's `Built:` (decisions 2, 4–6) and decision 7's ratio (the measured one,
  rounded up to the next half); the root `CLAUDE.md` (a moved slice's phase order: contracts → backend → core →
  clients), `docs/ai/README.md` § The pipeline where it names the order, `src/web/CLAUDE.md` § Prove your work (the
  package's build before the web's check), `native-apps.md` (the web draws its search from the core), the site's
  pages that name where the search runs, and the inbox row for P2 (Expenses first). On a no-go: phases 2–4 and
  `cr-core-js-target` reverted by their commits' hashes, ADR 0155 edited to the no-go with the table. · Done when:
  every guard but guard_gaps green; the site's `pnpm run check` green.

## Passes

On the dev stack, Chrome, a throwaway (`throwaway:account`) with two spaces, a Memory with a place, participants and
photos, a Pot and a file; before phase 2 and after phase 4, same steps: type `g`, `ge`, `geb` and back to `g`; a name
with an accent and one with `ß` typed both ways; a year, a month, a place, a person, a kind and an *In:* chip; "show
more"; a person and a settings section; the open Memory's photos. Each result list read as text (the field's options,
in order, with their bold parts) and compared before and after; one screenshot of each as the final proof. Then the app
(the Linux app or the Android emulator) searches the same account and lists the same results for three of the
queries. The throwaway is erased after.

## Measured at implement

2026-11-20, branch `claude/kjs-p1` (from `claude/windows-cr-f6c73c` at `421982845`), macOS arm64, Kotlin 2.4.21, under
other agents' load (load averages named where a time is reported). The scratch scripts: `chunks.cjs` (every chunk,
`gzip -9`), `attr.cjs` (S2's source-map attribution), `perf.mjs` (the web's own API, `searchDocument` →
`createMatcher` → `searchResults`, over S6's 10,000-document corpus, the matcher cleared before each of 200 queries),
`devloop.mjs` (headless Chrome on the worktree's dev server: a `Query.kt` edit to the page's `load`).

| # | Phase 1, before any edit | Number | Command |
|---|---|---|---|
| B1 | The eager set: `main` + `index.html`'s 28 modulepreloads | 29 chunks, 2,148,024 B / **606,761 gz**; `main` 1,051,910 / 300,411 gz | `vite build --sourcemap --outDir <s>/dist-before`; `node chunks.cjs` |
| B2 | Every lazy chunk | 58 chunks, 3,645,022 B / 1,124,940 gz (each in `chunks-before.json`) | the same |
| B3 | The slice's share | `query.ts` 5,536 + `results.ts` 905 B in `main`, `model.ts` 681 + `fold.ts` 330 B in `activeSpace`: 7,452 B min, **3,129 B gz** alone (2,613 + 516) | `attr.cjs` per chunk |
| B4 | `pnpm run check` in `src/web` | cold 330 s (red: 2 timeouts, load 40), warm 296 s (red: 1 timeout, load 94) | `rm -rf node_modules/.vite* dist; time pnpm run check`, twice |
| B5 | The `frontend` job | CI, its last three runs before core-js joined it: 9.53, 10.27, 10.22 min (2026-11-16/18). Locally: red in 12.5 min (timeouts, load 29) | `gh run view <id> --json jobs`; `run_pipeline.py --job frontend` |
| B6 | The search's speed (TypeScript) | corpus 8–11.5 ms; query median **0.42–0.46 ms**, p90 0.72–0.84 ms (six runs) | `node perf.mjs ts-before.mjs` (esbuild of the three files) |
| B7 | S7's list | 21 files, as the refine counted | S7's grep |

### S6 — every deleted assertion's holder

69 cases (`query.test.ts` 51, `results.test.ts` 12, `fold.test.ts` 6). **V** = a vector case of `v1-vectors.json`
(run by `SearchVectorsTest`, the package's `search.test.ts` and the web's `vectors.test.ts`); **R** = a
`SearchRulesTest` case; **W** = a staying web test.

| Deleted case(s) | Holder |
|---|---|
| query 1–5, 7, 8 (year, person, tail, place, space, kind, In: suggestions) | V `suggest` #0–6, #9, #10, #17 |
| query 6 (one letter, a chip already chosen) | V `suggest` #7, #8 |
| query 9, 11, 12, 14, 16, 17 (In:, space, photo year, person, kind chips; no-space document) | V `match` #15, #16, #18, #20–22 |
| query 10 (In: by id across spaces) | R `anInChipAsksTheNodesIdAndItsSpace` |
| query 13 (year span edges) | V `match` #15 + R `aYearChipHoldsBothEndsOfASpan` |
| query 15 (place chip folded, whole) | V `match` #19 + R `aPlaceChipIsTheWholeFoldedPlace` |
| query 18 (every chip) | R `everyChipMustHold` |
| query 19, 22 (the rank ladder, rank then name) | V `matcher` #0, #1; `match` #3, #14 |
| query 20, 21 (fold and several words: their ranks) | V `match` #9–11 + R `theRanksOfFoldedAndSeveralWordMatches` |
| query 23, 28 (a settings word ranks as a place, from three characters; a name first) | V `match` #6, `matcher` #0 + R `aSettingsWordRanksAsAPlaceAndANameFirst` |
| query 24 (an alias at its best rank) | V `match` #12, `matcher` #0 + R `anAliasCountsAtItsBestRank` |
| query 25 (an empty query) | V `match` #24 |
| query 26, 45 (one character: word starts, places; the matcher over "rk") | V `match` #4, #5, `matcher` #0 + R `oneCharacterMatchesWordStartsOnlyPlacesToo` |
| query 27 (a character outside the BMP) | V `match` #13 + R `aCharacterOutsideTheBasicPlaneIsOne` |
| query 29, 30 (a short settings word typed whole; the reason word) | V `match` #5–7 + R `aShortSettingsWordTypedWholeAndTheReason`; W `SearchField.test.ts` (the drawn reason) |
| query 31, 39–43 (the bold parts) | V `nameParts` #0–10 |
| query 32–35 (month chips) | V `suggest` #11–15; W `SearchField.test.ts` ("may 2024") |
| query 36, 38 (month span edges, no month without a date) | V `match` #17 + R `aMonthChipHoldsBothEndsOfASpanAcrossAYear` |
| query 37 (a photo's local month at its last minute) | R `aPhotosMonthIsItsLocalOne` |
| query 44 (the matcher equals a scan along a typed sequence) | V `matcher` #0, #1 (with the field's remembering matcher, in all three runners) |
| query 46 (narrowing never asks a dropped document again) | R `theMatcherNarrowsOnlyWhatItRemembered` |
| query 47 (a new list, another chip, an empty query) | V `matcher` #1 + R `theMatcherScansAgainForANewGenerationAndAnswersNothingForNothing` |
| query 48 (the same query again is the last answer) | R `theSameQueryAgainIsTheRememberedAnswer` |
| query 49–51 (reads counted: Backspace, a new generation, the history's bound) | R `theSameQueryAgainIsTheRememberedAnswer`, `theMatcherForgetsAtANewGenerationAndAtClear`, `theMatcherRemembersAtMostItsHistory` (identity, where TypeScript counted property reads); W `SearchField.test.ts` (the field clears the matcher as its list closes) |
| results 1–5 (one list: rank, place, name) | V `match` #0–3, #5, #14 |
| results 6–8 (a chip over people and settings) | V `match` #15, #18, #20; W `SearchField.test.ts` (Kind: Person) |
| results 9 (where a node lies, a cycle) | W `documents.test.ts` (moved: `documents.ts` stays the web's) |
| results 10–12 (`firstRanked`, its input untouched, a page and the exact total) | V `match` #23 + R `theFirstNAreAFullSortsFirstN` |
| fold 1–6 (case, accents, `ß`, composed and decomposed, the fold map) | V `fold` #0–9 + R `foldReadsTheCapitalSharpSAndCapitals` (`ẞ`) |

Stryker's manifest (`src/web/stryker.mutate.json`) named no search file before or after.

### The gate (phase 5) — ADR 0155 decision 2, against B1–B7

| Criterion | Number | Command | |
|---|---|---|---|
| size: the eager set | **+24,860 B gz** (606,761 → 631,621; ≤ 40,960): the Kotlin lands in `activeSpace` (+26,838 gz), `main` −1,974 gz | `chunks.cjs` over `dist-before`/`dist-after` | pass |
| size: every lazy chunk | largest growth **+14 B gz** (`MemoryDetailHost`; ≤ 1,024) | the same | pass |
| size: the façade's own share | 25,541 B min, **8,754 B gz** alone (the standard library 51,479 B min, 18,218 gz); the adapter left in `main` 1,096 B | `attr.cjs` over `activeSpace`/`main` | — |
| size: its ratio to the deleted TypeScript (B3) | **2.80** gz (8,754 / 3,129), 3.43 minified → decision 7's R rounds up to **3.0** | the same | — |
| build: the `frontend` job | after **5.7 min** green (core-js and the web's `pnpm run core` in it); before, the same host the same hour, 8.9 min red (one `FileViewer.pdf` timeout, load ~22); CI's last three 9.5–10.3 min; budget 15 | `run_pipeline.py --job frontend --rev 436d16ab0`, then `--rev 9d2f77c57` | pass (local; no CI run, nothing pushed) |
| development: an edit to the dev page | **11.1, 11.3, 11.3** s warm (12.8 s the first), a `Query.kt` constant to the reloaded page reading it, load 6–11 | `devloop.mjs` (headless Chrome, `VITE_PORT=8097`, its watch) | pass |
| development: a stack frame names the `.kt` line | `chipOf` → `SearchWeb.kt:159` (`throw IllegalArgumentException(…)`), `chipKey` → `SearchWeb.kt:65`, through the map the dev server serves (what devtools reads; not looked at in devtools' UI) | `stackmap.mjs` | pass |
| development: no `any` in a façade signature | none; the `.d.mts` holds `any` twice outside the façade — Kotlin's `KtSingleton` helper and Ktor's `initHook` getter, the compiled module's own exports | `grep -n any dist/veyq-mobile-core.d.mts` | pass |
| tests: every deleted assertion accounted | S6's table, 69 of 69 | above | pass |
| tests: the vectors against the shipped JavaScript in `check`, a Kotlin mutation red | the package's `search.test.ts` and the web's `vectors.test.ts`; `ß → ss` dropped → 4 red in each | the bites of phases 3 and 4 | pass |
| tests: Stryker | names no search file before or after | `grep search src/web/stryker.mutate.json` | pass |
| delivery: two clean builds byte-identical | **no: 3 fresh-process clean builds, 3 digests** (`2fdb5daa…`, `71a592f9…`, `78f377f4…`; two warm-daemon ones `efef2cb5…`, `9a71c50a…`). Only `kotlin-kotlin-stdlib.mjs` differs (and its conformance copy): the super-interfaces' order in `initMetadataForClass` (`[KtList, Collection]` against `[Collection, KtList]`) — P0's M4 nondeterminism, which `kotlin.incremental.js.ir=false` did not remove here | `rm -rf dist; ./gradlew --no-daemon --rerun-tasks -Pkotlin.compiler.execution.strategy=in-process :core:jsPackage; find dist -type f \| sort \| xargs shasum -a 256 \| shasum -a 256`, three times | **miss** |
| delivery: no `eval`, `new Function` or DOM sink | 0 hits | `grep -cE "eval\(\|new Function\|innerHTML\|outerHTML\|insertAdjacentHTML\|document\.write"` over the shipped module and the standard library | pass |
| delivery: `verify:csp`, `verify:license-sources` with the standard library's notice | green in `pnpm run check`; the notice's bite red | the web's check | pass |
| behaviour: the remaining web search tests pass with only their imports changed | they pass (7,274 tests), but **two changed beyond their imports**: `SearchField.test.ts`' matcher-memory case (it counted property reads of `foldedName` on the web's documents; the core reads its own copies) and one line of `searchIndex.test.ts` (it queried through the deleted `matchAll`) | `git diff 9d2f77c57 -- src/web/src/core/search/*.test.ts` | **miss, as worded** (both reached into the moved TypeScript; no search behaviour they assert changed) |
| behaviour: every query of the pass the same before and after | **identical**: 17 queries and their 64 listed lines, bold parts marked, the TypeScript search (the coordinator's Vite, 8081) against this branch's (8097), one throwaway | `search.mjs 8081`, `search.mjs 8097`, `diff` | pass |
| behaviour: an app lists the same | **not run** (no Linux app or emulator in this session) | — | open |
| behaviour: a 10,000-document query ≤ 1.5× the TypeScript | median **0.523–0.530 ms** against 0.432–0.446 ms: **1.19–1.23×**; the answers identical | `perf.mjs`, three alternating runs each | pass |

**Verdict: no go yet — one miss measured (delivery: reproducibility), one miss as worded (behaviour: two tests edited
beyond their imports), and the app's comparison not run.** With the owner (Q2: a miss comes back with its number); phase 6
waits for that answer, and nothing is reverted unasked.

## Owner questions

- **Q2 — the go.** The gate's criteria decide; phase 5 reports and stops. **Recommendation:** so — a full pass is the go
  without a further question; a single miss comes to you with its number rather than reverting unasked.

**Answered (owner 2026-11-20): Q2 as recommended.**

## Definition of done

Phases 1–6 checked; every bite seen red and green; the gate's table complete and the go recorded (or the owner's answer
to a miss); the passes run; `git diff --stat` over `src/backend/src`, `src/admin-web/` and `contracts/` empty.

## Retrospective

_(filled at the end of the implementation)_
