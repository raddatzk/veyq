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

- [ ] **1 the baseline** — before any edit: the eager set (`main` and its modulepreloads) and every lazy chunk (raw,
  min, gz, S1's method), the slice's share of them (S2's method), `pnpm run check`'s time in `src/web` cold and warm,
  the `frontend` job's time over its last three runs, the search's speed over S6's corpus through the TypeScript
  (median and p90 of the same query list), S7's list. Written into § Measured at implement. · Done when: the numbers
  are there.
- [ ] **2 core** — S3; core tests added where S6's accounting needs one; the vectors' write mode in
  `SearchVectorsTest`. · Done when: `:core:jvmTest :core:macosArm64Test`, `:core:compileKotlinJs` and `:bridge:jvmTest`
  green; `SearchVectorsTest` and `SearchModelTest` unchanged but for the write mode. **Bite:** the default order's
  name comparison reversed → `SearchVectorsTest` red.
- [ ] **3 the façade** — S1, S2, S4. · Done when: the package's `pnpm run check` green with the vectors' pure cases
  through the façade, and the `.d.ts` of both handles without a member. **Bites:** `Json.encodeToString` planted in
  `Query.kt` → the slice check red; a `deviceFiles` call planted in the façade → red; `ß → ss` dropped in `Fold.kt` →
  the vectors red; the façade ignoring the passed collator → the `collator` cases red.
- [ ] **4 web** — S5, S6, S7, S8; S10's refs. · Done when: `pnpm run generate:api` then `pnpm run check` in `src/web`
  green; `src/admin-web` and `tests/e2e` checks green; the app image builds; every guard but guard_gaps green; the
  browser pass of § Passes run. **Bites:** a core file importing an application's `@veyq/core-js` entry → the guard
  red; the `ß` mutation of phase 3 → the web's `vectors.test.ts` red.
- [ ] **5 the gate** — ADR 0155 decision 2, each criterion measured against phase 1 and written into a table here: size
  (the eager set, `main` alone, every lazy chunk; the façade's own share and its ratio to S2's deleted TypeScript),
  build (the `frontend` job over three runs, its timeout), development (a `Query.kt` edit to the reloaded dev page,
  timed warm three times; a thrown façade error's stack frame naming the `.kt` line in Chrome's devtools;
  `grep -n "any" dist/*.d.ts` over the façade), tests (S6's table complete), delivery (two clean builds'
  `shasum -a 256`; `grep -E "eval\(|new Function|innerHTML|outerHTML|insertAdjacentHTML|document\.write"` over
  `dist/`; `verify:csp`, `verify:license-sources`), behaviour (S6's speed against phase 1's; § Passes). · Done when:
  the table is complete; a full pass is the go (Q2), a miss is with the owner with its number, and nothing after this
  phase starts before that answer.
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

## Owner questions

- **Q2 — the go.** The gate's criteria decide; phase 5 reports and stops. **Recommendation:** so — a full pass is the go
  without a further question; a single miss comes to you with its number rather than reverting unasked.

## Definition of done

Phases 1–6 checked; every bite seen red and green; the gate's table complete and the go recorded (or the owner's answer
to a miss); the passes run; `git diff --stat` over `src/backend/src`, `src/admin-web/` and `contracts/` empty.

## Retrospective

_(filled at the end of the implementation)_
