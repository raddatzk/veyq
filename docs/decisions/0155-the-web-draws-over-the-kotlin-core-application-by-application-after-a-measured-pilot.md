# ADR 0155 — The web draws over the Kotlin core, application by application, after a measured pilot
- Status: Accepted
- Built: partly — decision 3 (2026-11-20: `:core`'s `js()` target over `@noble/*`, the refusals in `jsMain`'s two
  `NotOnTheWeb.kt`); decision 6 (`@veyq/core-js` with its conformance in CI's `frontend` job, and the web's half: its
  build, dev server, check and the app image's stage build the package first); the pilot (2026-11-21): decision 2's
  gate passed (§ Consequences), decision 4 but its JSON seam, which comes with Expenses (the slice check in the
  package's `pnpm run check`), decision 5 for the search (`@veyq/core-js/search`, two opaque handles, the web's
  direction guard reading the entry); decision 7's ratio written; decision 7.1 (2026-11-22: the core's split into a
  platform module and one module per application, [ADR 0156](0156-the-kotlin-core-is-split-into-a-platform-module-and-one-module-per-application.md)). Decision 7.2's first slice (2026-11-22: the node tree's rules,
  `@veyq/core-js/tree`, § The node tree's slice — accepted by the owner at 3.14×; its eager set 67 B gz over that
  allowance, accepted by the owner 2026-11-22 as chunk overhead); its second (2026-11-22: the share-link rules,
  `@veyq/core-js/sharing`, § The share links' slice — 2.63×, inside R; inbox #1057 fixed for every app through the same
  rule). Decision 7.3 and 8 are not built: Expenses' move is parked, half built on
  branch `claude/p2-impl`; the owner (2026-11-22) counts the platform code its façade needs against the platform module's
  running budget and retired the search-alone budget (§ Consequences)
- Date: 2026-11-20
- Model: [native-apps](../crosscutting/native-apps.md)

## Context

The owner's goal (2026-11-20, "ja" to the path and to the pilot, and the pilot starting with core logic): **the core holds all functionality, for the web as for the apps;
every client only draws.** Today the order is the other way: a feature is built in the web's TypeScript, then ported
into the Kotlin core ([ADR 0123](0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md),
[ADR 0136](0136-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md)) and twinned test for test.
[ADR 0154](0154-every-app-draws-the-cores-plans-through-a-core-ui-its-applications-plug-into-as-the-webs-do.md) made
the four apps draw one core; the web is the fifth client and the only one with logic of its own.

Measured before the decision (2026-11-20):

- **The logic exists twice.** The core's common code names ~200 of the web's source files as the rule it ports, ~33k
  lines of TypeScript among them (`grep -rhoE "[A-Za-z0-9_]+\.(ts|vue)\b" src/mobile/core/src/commonMain/kotlin | sort -u`,
  each found under `src/web/src`, test files left out).
- **Kotlin/JS can carry it.** A Kotlin/JS build (IR, ES modules, es2015) of the core's crypto passed all of
  `contracts/crypto/vectors/v1.json` under Node (remembered from the measuring session: its primitives were Node's, not
  noble's). The Expenses slice (`expenses/{Big,Money,Currencies,Ledger,LedgerCheck,Format,Codec}` with the suite's
  `V1`, `Contexts`, `Pad`) as an ES module, bundled, minified, gzipped: **variant A** (kotlinx.serialization and
  coroutines) 97 KB; **variant B** (JSON through `JSON.parse`/`stringify`, no coroutines, no Ktor) 35 KB, of which
  ~20 KB is the Kotlin standard library's floor paid once and ~20 KB the slice's own code; the same logic in
  TypeScript is 6 KB. Both passed `contracts/expenses/v1-vectors.json`, and a +1 settle-up mutation failed both.
  B without the experimental `-Xes-long-as-bigint` flag: 36 KB.
- **The applications now load lazily** ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)
  § Consequences): `main` 300 KB gz, with its modulepreloads 606 KB gz. What stays eager is core's, and some of an
  application's: Expenses' rules (`ledger.ts`, `format.ts`, `money.ts`, `codec.ts`, `revisions.ts`, 12.5 KB minified)
  sit in `main`, reached through its `contentSchema.ts` and `levelRows.ts`.
- **The floor is paid once, in the entry.** Whatever slice moves first brings the Kotlin standard library's floor
  (~20 KB gz) into the eager set, since core logic runs there. A variant-B probe of the core's search
  (`search/{Fold,Query,Results,Model}`, `session/LocalDays`, a façade of plain values): 78 KB minified / 27.7 KB gz
  bundled, the standard library 53 KB of it (Expenses' probe: 52 KB), the slice ~25 KB minified (~9 KB gz) against its
  TypeScript's 7.5 KB (3.1 KB gz) — 3.4× minified, Expenses' 3.7×. It passed the vectors' 72 pure cases, and a
  dropped `ß → ss` turned 5 red; a 10,000-document corpus is made in 27 ms and searched in 1 ms (median, Node). Each
  chunk was attributed by its source map (`source-map-js`, gzip level 9). The candidates weighed beside the search,
  each eager in the build: share-link parsing (1.4 KB, too small to fix a ratio by), the subtree export (streams and a
  serializer behind it, and a wrong byte loses data), the account rotation (no vectors, key material), and Expenses'
  rules (an application's).

## Decision

1. **Core first, every client draws.** The Kotlin core becomes the one home of the web's logic too: its rules, formats,
   codecs and — once decision 8 allows — its screen models. A moved application's feature is built in the core and
   drawn by the web and the apps; its TypeScript twin is deleted, not kept. What the web keeps is what only a browser
   does: the Vue UI and router, the service worker, the workers, IndexedDB, the streaming transport, PDF and WebP,
   the i18n rendering, and the raw primitives (`@noble/*`). These never move.

2. **A pilot decides, by criteria fixed before it runs.** A slice of core logic goes first, not an application (the
   owner, 2026-11-20): the floor lands in the eager set with the first core logic anyway, so it is paid once there and
   measured alone, and every application after adds only its own code. The slice is the search — eager, pure, held by
   `contracts/platform/search/v1-vectors.json` on both sides, and a wrong answer changes no data —, as variant B. Its
   gate is a phase of its own in the pilot's CR, and **every criterion must hold** for a go:
   - **size:** the eager set — `main` and the chunks `index.html` modulepreloads, since the bundler decides which of
     them a module lands in — grows by at most **40 KB gz**, the TypeScript it deletes counted; no lazy chunk grows by
     more than 1 KB gz. The probe measured 27.7 KB gz before ~3 KB of TypeScript leaves; the ceiling leaves room for
     the real façade's own code to reach twice the probe's (~19 KB floor + ~18 KB);
   - **build:** the CI `frontend` job grows by at most 3 minutes and stays inside its `timeout-minutes: 15`;
   - **development:** a changed Kotlin rule reaches the running dev page with no manual step in at most 15 s warm;
     a stack frame in the browser's devtools names the `.kt` line; every façade signature is typed (no `any` in its
     `.d.ts`);
   - **tests:** every assertion of a deleted TypeScript test is accounted for, one by one, in a core test or the web's
     test of the package; the search vectors run against the shipped JavaScript in `pnpm run check`, and a rule
     mutated in Kotlin turns that test red; the web's Stryker manifest names no search file today, so nothing it
     measured is lost;
   - **delivery:** two clean builds of the package are byte-identical; it contains no `eval` or `new Function` and
     no DOM sink; `verify:csp` and `verify:license-sources` pass with the Kotlin standard library's notice;
   - **behaviour:** the web's remaining search tests pass with only their imports changed; every query of the CR's
     pass lists the same results in the same order before and after, and the web and an app list the same; a query
     over a 10,000-document corpus is at most 1.5× the TypeScript's time (median), measured the same way.

   A full pass is the go. A miss goes to the owner with its number (the owner's answer, 2026-11-20); a no-go reverts
   the pilot's commits and the `js()` target, and this ADR is edited to record the numbers and that the web stays
   TypeScript.

3. **The core gets a `js()` target** — IR, ES modules, es2015, TypeScript definitions; no experimental compiler flag
   (B measured 36 KB without `-Xes-long-as-bigint`; a `Long` never crosses a façade). Its JavaScript primitives are
   the web's own `@noble/*`, imported by the compiled module and resolved from the pnpm lockfile, so one
   implementation of each primitive ships and the suite's conformance runs over it. An `expect` the web has no form
   of answers with a named refusal in one `jsMain` file, and nothing a façade reaches may reach it. The core's whole
   common code compiles for JavaScript from then on, as for every other target.

4. **What the web consumes is variant B, held by the build.** Code reachable from a façade uses no
   kotlinx.serialization, no coroutines and no Ktor. JSON goes through a platform seam in common code: on the JVM and
   native targets kotlinx.serialization behind it, so the apps read and write exactly as today; on JavaScript
   `JSON.parse` and `JSON.stringify`. The formats bound every integer to a safe one (`entry.v1.schema.json`'s
   amounts and revisions), so `JSON.parse`'s numbers are exact and no reviver source text is needed; a format that
   ever needs a larger number carries it as a string, as `rate` already is. The shipped module's source map lists
   the files it carries; a kotlinx.serialization, coroutines, Ktor or refusal file among them fails the web's check.

5. **The boundary is a façade per application, of plain values.** Each application exports one façade from `jsMain`
   (`me.raddatz.veyq.mobile.<application>.web`); common code carries no `@JsExport`, so the apps see nothing new. A
   façade takes and returns JavaScript values — the format's JSON shapes, `bigint`, `Uint8Array`, strings —, never a
   Kotlin class, so the web keeps its own types for the shapes it draws and Vue never proxies a Kotlin object. The one
   exception is state a rule keeps across calls (the search's corpus generation and its matcher): it crosses as an
   opaque handle — no member in its `.d.ts`, held `markRaw`, read only by the façade. The
   package exposes one entry per application, and the web's direction guard reads `@veyq/core-js/<owner>` as that
   owner's, as it reads `features/<owner>`, and an entry named for a core area as core's.

6. **pnpm and Gradle meet at one directory.** Gradle compiles; pnpm installs, bundles and tests. The compiled module,
   its definitions and source maps land in `src/packages/core-js/dist/` (never committed: a build output in git drifts
   from the sources it claims), the one new workspace package (`@veyq/core-js`), which proves itself with its own
   `pnpm run check` — the suite's conformance and each façade's vectors under Node over the lockfile's `@noble/*`.
   The conformance is a binary of its own compilation, never an export of the shipped module. Kotlin's own Node, Yarn
   and npm tooling is never run: no task downloads a Node distribution or writes a `kotlin-js-store/`. The web's
   build, its dev server, the CI `frontend` job and the app image's frontend stage each run the package's build first;
   building the web from source then needs the JDK the core builds with, as the backend already does.

7. **Then, in this order: the core splits, the platform moves slice by slice, the applications follow** (after a go;
   the owner's order, 2026-11-21). Each step is a CR of its own.
   1. **The Kotlin core splits into a platform module and one module per application**, as the backend's `:core` and
      `:apps:<application>` (ADR 0079): an application may use the platform, never the reverse and never another
      application, and a guard holds the direction from the build files. One compiled JavaScript module cannot carry a
      second façade without making the first one's users pay for it (§ Consequences, measured), so the split comes
      before any second entry, and with it each module is its own JavaScript file.
   2. **The platform core moves to the web slice by slice.** Each slice grows the eager set by at most **R × the gzip
      of the TypeScript it deletes**, R the pilot's measured ratio of its façade's own code to its deleted TypeScript,
      rounded up to the next half: **R = 3.0** (the pilot's 8,754 B gz of own code against 3,129 B gz deleted, 2.80; the
      probe had estimated ~2.9), and each of its hot rules meets 7.3's time bound. The order (2026-11-21; the deleted
      TypeScript's min+gz measured on sources, each slice's exports bundled alone with every other import external):
      1. **the node tree's rules** — a level's order, equal names numbered, a name's sibling collision, the move's plan
         (1,326 B gz): eager, every application builds on them, the app and the web answer them differently today, no
         key material and no JSON — **built**, § The node tree's slice;
      2. **the share-link rules** — a received link's parsing and report link, a link's last end day, an instance's
         embeddability (1,251 B gz): pinned by `contracts/platform/share-links/cases.v1.json` on both sides already; the
         bookmark's sealed meta joins once Expenses has built decision 4's JSON seam — **built**, § The share links' slice;
      3. **the rights and ADR 0154's plans** — a header's and a row's actions, the favourite, cover and bookmark rights
         (1,388 B gz): the structure every client draws, after the tree's kind facts exist to build them on;
      4. **the file facts** — a content sniff, a download name, an archive name (2,566 B gz): lazy today (Depot's
         viewer), and the SVG rule is a security rule;
      5. **the media headers** — EXIF, video and image headers (4,780 B gz): lazy, and no vectors yet;
      6. **the export archive** — the ZIP, its CRC and manifest (5,726 B gz): lazy, streamed, and a wrong byte loses data;
      7. **the music order and fades** (859 B gz).

      Not slices: the **formats** move with the slice or application that owns each (money with Expenses) — alone they
      miss R by a measured factor (ADR 0154 § Consequences: 7.1×); **notices** wait until the web and the apps read one
      word catalog; the **slug folding** (`shared/naming.ts`) stays beside the Kotlin as decision 9's crypto does, held by
      `v1.json#name_folding`; the **URL fragment rules** are the router's (decision 1). Every platform façade lands in
      `:core`'s one JavaScript file, which a bundler cannot split, so a rule that is lazy in TypeScript turns eager when
      it moves — the reason slices 4 to 6 come last, each asking first whether a module of its own is cheaper — and the
      package's size check becomes one running bound on that file: the split's measured search alone plus R × each moved
      slice's deleted TypeScript (a slice the owner granted another ratio carries it, named with the owner's date, on its row
      of `src/packages/core-js/src/slice.test.ts`; R stays the rule). Platform code an application's façade needs (Expenses'
      JSON seam, money's formats) counts against that running bound, not against the search, and there is no separate
      budget for the search alone: the platform module grows by design with every slice (the owner, 2026-11-22).
   3. **Then application by application, Expenses first.** Each move: its façade, its TypeScript twin deleted, its own
      size check and time check. The floor is paid, so a size check counts only a move's own code:
      - **size:** an application grows the eager set and its own chunks together by at most **25 KB gz** (Expenses'
        own code measured ~20 KB, its suite's `V1` among it), and the entries that do not use it grow by nothing;
        where its rules are eager today, as Expenses' are, the eager set carries them;
      - **time:** each rule a move names as its hot path runs at most **2.5×** the TypeScript's median **and at most
        100 ms** absolute over the move's named corpus, the answers identical (the owner, 2026-11-21, replacing 1.5×
        for an application move; the search's 1.5× of decision 2 was the pilot's and was met). For Expenses the hot
        paths are `buildLedger` and `checkLedger` over the 10,010-revision Pot.

8. **A screen model with flows is a gate of its own.** Moving a model that exposes a flow (ADR 0136 decision 2) brings
   coroutines into the web once: variant A's floor measured ~75 KB gz above B's. That crossing is decided by a
   measurement of its own. The web then observes a model as Swift and the bridge do, through its `watch(listener)`
   and the `Watch` it returns (`blob/UploadQueue.kt`), behind one composable that replaces a `shallowRef` per state
   and cancels the watch when its scope ends; never Vue's deep reactivity over a Kotlin object.

9. **The crypto suite and admin-web stay TypeScript.** `@veyq/crypto` and the Kotlin suite are two implementations
   answering to one set of vectors; that is a check worth more than the bytes it costs. A Kotlin `V1` runs in the web
   only where a moved application's codec needs it, over the same `@noble/*` primitives and the same vectors. admin-web
   holds no domain logic to move ([ADR 0065](0065-admin-ui-cli-separate-service.md)).

## Consequences

- **The pilot's gate** (2026-11-20/21, branch `claude/kjs-p1`, macOS arm64, Kotlin 2.4.21; each number against the
  same tree before the first edit) — a full pass, so the go:

  | Criterion | Measured | Bound |
  |---|---|---|
  | size: the eager set | +24,860 B gz (606,761 → 631,621): the Kotlin lands in `activeSpace` (+26,838), `main` −1,974 | ≤ 40,960 |
  | size: any lazy chunk | +14 B gz at most (`MemoryDetailHost`) | ≤ 1,024 |
  | size: the façade's own code | 8,754 B gz (25,541 min) beside the standard library's 18,218 gz; 2.80× the 3,129 B gz of TypeScript deleted | R (decision 7) |
  | build: the `frontend` job | 5.7 min with the package and the web's `pnpm run core` in it (local, `run_pipeline.py --job frontend`); CI's last three before it 9.5–10.3 | +3 min, inside 15 |
  | development: a `Query.kt` edit to the reloaded dev page | 11.1, 11.3, 11.3 s warm (the development link, `:core:jsPackageDev`; the production link took 16–18) | ≤ 15 s |
  | development: a stack frame, the `.d.ts` | `SearchWeb.kt:159` through the dev server's map; no `any` in a façade signature | — |
  | tests | the 69 deleted TypeScript cases each held by a vector case, a `SearchRulesTest` case or a staying web test; the vectors run through the shipped module in the package's and the web's check, `ß → ss` dropped → 4 red in each; Stryker names no search file | — |
  | delivery: byte-identical builds | 5 clean builds in fresh processes and 2 on a warm daemon, one digest (`26813b07…`), after the fix below | identical |
  | delivery: no `eval`, `new Function`, DOM sink; `verify:csp`, `verify:license-sources` | 0 hits; green, the standard library's notice in the licence file | — |
  | behaviour: the staying web tests | pass; two changed beyond their imports where they counted the deleted TypeScript's internals (`SearchField.test.ts` its `foldedName` reads, `searchIndex.test.ts` its `matchAll`) — a pass by the owner's ruling (2026-11-21): no search behaviour they assert changed | imports only |
  | behaviour: the pass | 17 queries, their 64 listed lines with bold parts, identical before and after (one throwaway, Chrome) | identical |
  | behaviour: 10,000 documents | median 0.523–0.530 ms against the TypeScript's 0.432–0.446: 1.19–1.23×, the answers identical | ≤ 1.5× |

  Not run: an app listing the same results for the pass's queries (no Linux app or emulator in that session); the apps
  run the same `search/` against the same vectors. Explained for users (the browser and the app decide by the same
  code): [Search all your spaces](../../site/src/content/docs/spaces/search.mdx).
- **Byte-identical needs a step of the build until Kotlin 2.5** (KT-68281). The production link's dead-code
  elimination replaces an interface it removed by that interface's surviving supertypes, gathered in a `HashSet` of IR
  symbols, so a class's `initMetadataForClass` lists them in identity-hash order: `AbstractMutableList`'s
  `[KtList, Collection]` in one compiler process, `[Collection, KtList]` in the next. No compiler flag, execution strategy
  (Kotlin 2.4 removed `out-of-process`) or `-Xir-keep` holds it (keeping `MutableList` pulled in 25 KB of the
  collections' JavaScript views and still differed); the fix is upstream from 2.5.0 (a `LinkedHashSet`). Until then
  `:core:jsPackage` sorts such a list by name, only where order cannot change what runs — no listed interface, nor a
  super-interface of one, carries a default member — and fails on a Kotlin that carries the fix, so the step leaves
  with the upgrade (`src/mobile/core/build.gradle.kts`, `sortFreeInterfaceLists`). The incremental JavaScript caches
  stay off for a release build (`src/mobile/gradle.properties`): with them, fresh processes differed further.
- **One compiled module cannot hold a second façade** (decision 7.1's reason). Expenses' move, built through its core
  and package phases on branch `claude/p2-impl` (2026-11-21, Kotlin 2.4.21, esbuild bundling as the web does,
  `@noble/*` external, gzip level 9 — the package's `src/slice.test.ts` there, `pnpm run build && pnpm run check` in
  `src/packages/core-js`), measured each way the façades can share the core:
  - **one module, one entry file per façade** (measured, `7b8af11cf`): the bundler cannot drop one façade's code from
    the module, so the search's entry alone grows from 28,189 to 59,587 B gz against its 40 KB budget — every search
    user pays for Expenses' rules, eagerly;
  - **per-file granularity** (measured in the same session on a throwaway build, not committed): the search's entry
    30,563 B gz, inside; Expenses 37,021 B gz gross, ~30.8 KB net of its deleted TypeScript's 6,194 B gz — over its
    25 KB, from the per-file module overhead; and per-file writes an `external interface` shape into the
    `.export.d.mts` only where it shares a file with the façade;
  - **one binary per façade** (remembered, not built): each carries its own standard library, the pilot's 18,218 B gz,
    once per application;
  - **per-module granularity over real Gradle modules** — the platform's module and each application's its own
    JavaScript file ([ADR 0156](0156-the-kotlin-core-is-split-into-a-platform-module-and-one-module-per-application.md), built 2026-11-22) — measured with the method above, the parked commits replayed onto
    `:apps:expenses` on a throwaway branch from the split (`ecdc90588`, deleted after): the search's entry alone
    **43,091 B gz, over its 40,960** (27,943 on the split before Expenses), Expenses gross 61,619, both together 63,600,
    so **Expenses' net 14,315 B gz** (63,600 − 43,091 − 6,194), within its 25,600; its `.d.mts` shapes all present; time
    `buildLedger` 2.07× the TypeScript at the medians (79.2–81.6 ms against 37.5–40.2), `checkLedger` 1.20× (4.94–5.06
    against 4.18–4.37), the answers identical — within decision 7.3. The search pays for the platform code Expenses'
    façade uses (the JSON seam and the shared formats the parked move placed in the platform: the platform's own code in
    the search's bundle 26,271 → 45,253 B minified), since a bundler drops nothing of a Kotlin module file. **A miss on
    the search alone, handed to the owner** (decision 2's rule) — answered 2026-11-22: such platform code counts against the
    platform module's running budget (decision 7.2), and the search-alone budget is retired. The split stays for its
    direction, and the run decides
    how Expenses resumes: what a façade needs of the platform out of the module every entry imports, a declaration file
    per entry (one for every module today, so the entries share their types), a conformance compilation per application
    (Expenses' decode runner has none). [ADR 0156](0156-the-kotlin-core-is-split-into-a-platform-module-and-one-module-per-application.md) § Consequences holds the whole run.
- **The node tree's slice** (decision 7.2's first, built 2026-11-22, branch `claude/node-tree`). A level's order, the
  numbering of equal sibling names, a name's sibling collision and the move's plan are the platform's `tree/` (`:core`:
  `KindFacts`, `TreeFacts`, `LevelOrder`, `DisplayNames`, `SiblingNames`, `MovePlan`), the apps' `Home.level`, `NodeMove`,
  `NodeRename` and a drop box's page call them, and the web draws them through `@veyq/core-js/tree`
  (`src/web/src/core/node/treeRules.ts` holds the two handles and maps ids back); `contracts/platform/tree/v1-vectors.json`
  holds both, written by the core's `TreeVectorsTest`. Its gate (each number against `4c4743d16`, the tree before the slice):

  | Criterion | Measured | Bound |
  |---|---|---|
  | size: the platform module, every entry | 32,101 B gz (the search alone 27,943; own code 4,158 = 3.14× the 1,326 B gz deleted) | 27,943 + 3.14 × 1,326 = 32,106 (the owner's allowance, 2026-11-22; R = 3.0 would be 31,921) |
  | size: the eager set | +4,231 B gz (630,538 → 634,769: `activeSpace` +4,116, a new chunk `treeRules` +748, `main` −635) | 3,978 at R, 4,164 at 3.14 — **67 over**, accepted by the owner 2026-11-22 (the adapter's own chunk, not Kotlin code) |
  | size: any lazy chunk | +20 B gz at most (`jspdf.es.min`) | ≤ 1,024 |
  | time (10,000 nodes, Node 22, median of 15) | `orderLevel` 1.51×, `displayNames` 1.96×, `collidingSibling` 0.04× on a held generation (3.5× on a generation's first call, 9.9 ms), `movePlaces` 0.36×, `planMove` of 1,000 0.005×; `treeFacts` 1.6 ms | ≤ 2.5× and ≤ 100 ms |
  | delivery | three clean builds one digest; no `eval`, `new Function` or DOM sink; `verify:csp`, `verify:license-sources` green | identical |
  | tests | the deleted TypeScript's cases each a vector case, a `TreeRulesTest` case or a staying web test; digits compared as text in `LevelOrder` and `self` not excluded in `SiblingNames` turn the package's `tree.test.ts` and the web's `tree.vectors.test.ts` red | — |
  | behaviour | the web's tests pass, their imports changed, the eight hand-built `TreeSource` fakes given `facts()`; the browser pass (two builds over one backend, en and de) lists the same rows, refusals, move places and a selection's outcome — the upload-bound rows (a file `a.b`, a drop box's two `Anna`s, a link guest's move) owed | identical |

  The slice's decision log, kept by its implementer (question, choice and why, evidence, cost; the refine's entries first):
  - *The order of the slices:* the node tree first — eager, the base every application builds on, answered differently by
    the app and the web, no key and no JSON, and it forces the shared module file and the running budget on the smallest
    case. Formats move with their owner (alone 7.1× R); notices after one word catalog; the slug folding (`naming.ts`) and
    the URL rules stay TypeScript (crypto vectors and Stryker hold the one, the router owns the other).
  - *One order rule in common code, no platform collator* (four collators would differ where no test looks). It is a sort
    key per name: white space, a table of ICU 78's punctuation, symbols and currency signs in its primary order (an unlisted
    one after its group by code unit), digit runs as their length without leading zeros then their digits, letters by code
    unit after NFKD, marks dropped, lower case, `ß æ œ ø ł đ ð` read as the collator reads them; equal keys by creation,
    then id. A prototype matched `Intl.Collator('en'|'de', { numeric: true, sensitivity: 'base' })` under ICU 78.3 on 0 of
    25,600 corpus pairs and 0 of 90,000 random ones; the core answers every `order` case, so no visible change on the
    corpus. Residual: a script or digit the table does not reach, a dotless `ı`.
  - *The kinds' facts are data the caller hands in* (`KindFacts`: route, folding, placement) — `:composition` is no
    JavaScript entry, and a folding is frozen into stored tokens. The applications contribute `kindFacts` in place of their
    `tokenInput`/`placement` answers; `AppKinds.facts` is built at install, the table sorted by kind (the web and the apps
    register in different orders) and held to the vectors' `kinds` on both sides.
  - *The tree crosses once per generation as an opaque handle, answers as ids*; a creation instant is a `Double` of epoch
    milliseconds (a `Long` is emulated on JavaScript); a place's top (a guest's link root) is a parameter, not the handle's.
  - *A place the tree does not read takes nothing* — the one case where the app's answer won over the web's: the browser
    never lists one, and failing closed is the registry's rule (`TreeRulesTest`).
  - *`NodeMove` keeps its API* (Swift, Android and the bridge read it) and adapts `MovePlan`, which takes the placement as a
    function; the apps' collision checks call `SiblingNames` over the registry's `tokenInput`. `Naming.slugify` runs as
    loops (no per-call `Regex`, no `Map`), `fnv1a`'s base 36 through a `Double` (`UInt.toString(radix)` goes through `Long`).
  - *Phase 1's writer was the web test's write mode* (the kind table needs the registry, which only a DOM test fills),
    deleted in phase 2: the core writes the file, byte for byte.
  - *The entries:* `:composition:jsPackage` writes `dist/<entry>.d.mts` from each façade's `@JsExport` declarations;
    `package.json` maps an entry's types there and its code to the module file. A re-exporting `.mjs` per entry broke a
    test's `vi.mock` of one export (`SearchField.test.ts`, the parked Expenses branch's red, reproduced and measured fixed),
    so entries differ by their types only — the web's direction guard reads them.
  - *Squeezing the module* (33,404 → 32,101 B gz, each step behaviour-neutral): the collator's table a `val`, not an inlined
    `const`, and trimmed to what code-unit order does not give; categories by their two-letter code; blocks, reasons and
    foldings string constants, not enums; the façade writing plain arrays and objects; one list sort for order and numbering;
    a moved node a `TreeFacts.Node`; token inputs cached on the node. The numbering by one sort of the exact name took
    `displayNames` from 2.87× to 1.96× the TypeScript.
  - *The gate's two readings:* the refine's probe allowed 4× own code (the eager set net of the deleted TypeScript), its
    running budget 3× gross; the slice landed at 3.14× and went to the owner, who accepted it for this slice on 2026-11-22
    (an explicit allowance on its row; R stays the rule) and ruled that platform code an application's façade needs counts
    against the running budget, the search-alone budget retired. The eager set then measured 67 B gz above the allowance,
    the adapter's own chunk being the difference; the owner accepted it 2026-11-22 as chunk overhead, the eager set recorded at +4,231 B gz.
  - *The web's side:* `treeRules.ts` keeps the deleted functions' signatures where it could (`orderLevel` lost its locale,
    `displayNamesOf` takes an order, `endsIdentity` the tree); `TreeSource` gained `facts()`; the time gate binds a rule as
    the web calls it (a keystroke on a held generation), a generation's build only the 100 ms.
  - *Left:* Depot's `DepotFiles.collision` and Memories' `MemoryCreate.check` keep their own loops (over the same
    `AppKinds.tokenInput`); the apps' level and header plans do not hand the numbered name on yet (inbox #1062); a guest
    moves nothing in the apps (inbox #1063).
- **The share links' slice** (decision 7.2's second, built 2026-11-22, branch `claude/share-links-slice`). A received
  link's check, its host, its report link and the share page's `&report` (`ReceivedLink`), a link's last end day under a
  tenant's limit (`LinkDays`, new) and an instance's embeddability (`ShareInstance.refusal` for the apps' open,
  `embedding` for the web page, one reading) are the platform's `sharing/` (`:core`), with `session/ClientWindow`'s window;
  the web draws them through `@veyq/core-js/sharing` behind its unchanged `bookmarkLink.ts`, `linkRules.ts`' `lastExpiryDay`
  and `shareInstance.ts`' `embeddability`, which hold no rule. `contracts/platform/share-links/cases.v1.json` holds every
  side (`ShareLinkVectorsTest`, the package's `sharing.test.ts`, the web's three tests). **Inbox #1057** — the apps
  counted an edited link's last end day from today, the server from the link's creation — is fixed for all four apps in
  `LinkForm.start`, which now takes `now` and counts from the edited link's `createdAt`. The bookmark's sealed meta stays
  TypeScript: decision 4's JSON seam is only on the parked `claude/p2-impl`. Its gate (each number against `b8c2228de`):

  | Criterion | Measured | Bound |
  |---|---|---|
  | size: the platform module, every entry | 32,101 → 35,392 B gz; own code 3,291 = 2.63× the 1,253 B gz deleted (8,753 B minified, 3.27× the 2,678) | 32,106 + 3.0 × 1,253 = 35,865 |
  | size: the eager set | +2,656 B gz (634,769 → 637,425: `activeSpace` +3,156, `main` −493) | 3,759 at R |
  | size: any lazy chunk | +5 B gz at most (`browser`) | ≤ 1,024 |
  | time (Node 22, median of 15) | `parseShareLink` (a keystroke) 1.41×, `keptLink` + `hostOf` (a list of 10,000) 1.17×, `lastExpiryDay` (a list of 10,000 stopped links) 0.30×, `embeddability` (once a page) 2.05×; each whole batch ≤ 26 ms, the answers identical (20,038 compared) | ≤ 2.5× and ≤ 100 ms |
  | delivery | three `--rerun-tasks` builds one digest; no `eval`, `new Function` or DOM sink; `verify:csp`, `verify:license-sources` green | identical |
  | tests | the deleted bodies' unit cases stay as the adapters' tests over the shipped module; the pre-fix `today + maxDays − 1` turns the #1057 vector red in `ShareLinkVectorsTest` and `LinkFormTest`; a `withoutReport` keeping empty parameters and a `lastEndDay` without its `− 1` turn the package's `sharing.test.ts` red | — |
  | behaviour | the browser pass (one throwaway, headless Chrome, a 7-day rule): the new and the edit form offer today … today + 6, a pasted link's eight cases answered as the vectors say, the bookmark row and page name the host, the page embeds the share, `&report` opens the share page's report dialog and leaves its address | identical but the three cases below |

  The slice's decision log, kept by its implementer:
  - *The set:* the three groups of the row, with `olderMajorsInWindow` (1,253 B gz). `linkRules()` (a capability read),
    `readShareInstance` (a fetch) and `EMBED_MAJORS` (the web embed's own adapters, under the web's VERSION tripwire) stay
    the browser's. `MAX_LINK_LENGTH` left the web; its one reader reads the vectors' `maxLinkLength`.
  - *The entry* is `@veyq/core-js/sharing` (`jsMain/…/sharing/web/`), a core area's name, so the web's direction guard reads
    it as core's; its code the module file, its types `dist/sharing.d.mts`.
  - *#1057's rule:* `LinkDays.lastEndDay(maxDays, madeAt) = dayOf(madeAt + maxDays × 24 h) − 1` — a picked day is sent as
    its end, which must not pass the server's `createdAt + maxDays`. `LinkForm.start` takes `now`, never a day, so no caller
    can hand an edit a today-counted last day; a link past its lifetime keeps its last day as the end (the web's picker caps
    the same way), and `withEndDay` no longer meets the empty range it would now find. The vectors name a zone per case (the
    JVM test sets the default zone, the package's test `process.env.TZ`), across both ends of daylight saving time.
  - *Where the two parsers read apart,* the core's answer won (fail closed, one rule), measured by running both over
    candidate inputs: a key whose last character sets bits past the 256th (`noKey`; `atob` read it), an empty user info
    `https://:@host` (`credentials`), and a host in Unicode (`notAUrl`; the punycode form still reads). A member who pastes
    such a text on the web is refused now; no link veyq builds is one of them. Ten more cases both already agreed on joined.
  - *One embeddability reading:* the apps' `Refusal` enum keeps its three constants (Swift and Android switch over it
    exhaustively); the page's answer adds this instance's switch first and `.embeddable` last, in the web's words.
  - *No `Regex` and none of the library's general string search on a façade's path.* The façade's first build measured
    37,357 B gz (own code 4.19×): a Kotlin `Regex` (~9 KB minified with its wrapper), `split`'s delimited-ranges sequence,
    the ignore-case paths of `indexOf`/`lastIndexOf`/`startsWith`/`endsWith` and `trim`'s Unicode table, attributed by the
    source map and an unminified diff of the bundle. `ReceivedLink` reads its shapes with helpers of its own. The same build
    found a defect only JavaScript had: the IPv6 host's `^\[[0-9a-f:.]+]$` is no valid expression in the `u` mode Kotlin/JS
    compiles a `Regex` to — the web's first parse threw, while the JVM and native read it. `trim` is now JavaScript's own set
    of white space, for every client.
  - *A `Long` on a list path, again:* through `LocalDays` (epoch ms as an emulated `Long`) the guest-stop rows'
    `lastExpiryDay` measured 19.8× the TypeScript. `LinkDays` takes the instant as a `Double` and the local-day reading as a
    parameter — `LocalDays` for the apps, the engine's `Date` behind the façade (0.30×): one formula, one reading per platform.
  - *Left:* an instance's tenant switch for `.embeddable` does not reach `/api/setup`'s capability list (measured in the
    pass: switched off, the list still named it and the page embedded), so the page decides by the instance's answer, as
    before the slice — the server's question, not this slice's (inbox #1067: since 2026-11-22 the list is the link domain's tenant's, ADR 0088 decision 3).

- **Expenses' move is parked, not merged** ([cr-web-expenses-on-the-core](../../changes/cr-web-expenses-on-the-core.md)),
  on branch `claude/p2-impl`: the vectors (`c76d3212f`), the JSON seam and Expenses' pure homes (`c5793db14`), the
  façade and its entry (`cbf54e21e`), `Big` over the engine's `BigInt` (`243e89185`), one optimisation round
  (`7b8af11cf`). It is rebased onto `:apps:expenses` and reused, not rebuilt — replayed there once for the measurement
above, which says what changes first. Its time at
  the park (Node 22.23.3, median of 15 after 3 warm-ups, the 10,010-revision Pot; the bench script, `bench.mts`, is not committed — the
  numbers are the commit message's of `7b8af11cf` and the measuring session's):
  `buildLedger` 80.4–82.9 ms against the TypeScript's 38–41 (2.07–2.12×), `checkLedger` 4.9–5.0 against 4.1–4.6
  (1.15–1.19×), the answers identical — inside decision 7.3's time bound. **The trap that binds every later move: a
  Kotlin `Long` is emulated on JavaScript.** `Big`'s limbs over `Long` took `buildLedger` from 38 ms to 25.7 s; over
  the engine's `BigInt` (an `expect` class, the limbs kept for the JVM and native) 306 ms; then a revision handle
  read once (260), the engine's own `Map` and `Set` for string keys (165), a ledger entry handed back as the web's own
  body object (126), whole weights without the fractions' gcd and each rate read once (103), the remainder's cents by
  selection instead of a sort (87), lazy reader views and a UUID checked by its characters (81). A hot path's Kotlin
  for the web is measured on JavaScript, never assumed from the JVM.

- **The operator-served web is unchanged in kind** ([ADR 0026](0026-client-trust-tiers.md)): still same-origin,
  content-hashed chunks, no new CSP source, no Subresource Integrity in use before or after; the Kotlin output is one
  more reviewed, compiled-in module ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 5).
  The web's build inputs grow by the Kotlin compiler and its libraries, pinned by `src/mobile/gradle/verification-metadata.xml`;
  a byte-identical rebuild — what the attested tier will need — is a criterion of the pilot, not an assumption.
- **The apps pay only the JSON seam**, and only once Expenses moves: its format reads JSON through it; the apps'
  behaviour is held by the tests that hold it today. The search adds one parameter to the core's `Results`, defaulting
  to today's order, so the web keeps its browser collator. Core code a façade reaches stays free of coroutines, serialization and Ktor; the build says so.
- **After the go**, a CR touching a moved slice or application orders its phases contracts → backend → core → clients,
  and ADR 0154's vectors "from the web's own formatters" turn around for what moved: the core's output is the one
  source (the search's vectors are written by `SearchVectorsTest`). The root `CLAUDE.md`, `docs/ai/README.md` and
  `src/web/CLAUDE.md` say so.
- **CI's `frontend` job checks the package** with crypto, web and admin-web: published in the stage map of
  [How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md).
- **The pnpm workspace holds a second package.** ADR 0065's "one package" was a rule against general code sharing;
  `@veyq/core-js` is no shared UI, router or state but the core's logic, compiled.

## Alternatives considered

- **The whole web onto the core now.** No measurement says the size, build and development costs hold beyond one slice;
  a full move would be undone only at a large cost. The pilot buys the numbers first.
- **Kotlin/Wasm.** Every crypto call would copy its bytes across the Wasm boundary, and the noble primitives would have
  to be called back through it; the web's `'wasm-unsafe-eval'` is granted for the WebP encoder alone (cc.security.csp).
- **An application first (Expenses).** Measured and planned first: 35 KB gz for its slice. But its floor would have
  been paid in an application's budget while the entry, where core logic runs, still carried none; leading with core
  logic pays the floor once where it lands anyway and lets every application's check count its own code (the owner,
  2026-11-20).
- **Expenses on the one module, before the split.** Built that way first: its façade made every search user pay
  for it (59,587 B gz in the search's entry), and per-file granularity missed Expenses' own 25 KB (§ Consequences).
  The split also gives the core the direction the backend and the web already hold, which no granularity setting does.
- **Variant A for the pilot.** 97 KB gz against 35 KB, for coroutines and a serializer the pure rules do not need;
  decision 8 keeps that cost for the day a flow must cross.
- **The status quo with more vector sets.** Each shared rule held by vectors still costs two implementations and their
  tests; the vectors catch a divergence, never the second implementation's cost.
- **Committing the compiled JavaScript.** Reviewable as a file, but it drifts from the Kotlin that claims to produce it,
  and nothing would prove the two equal on every commit.
- **Kotlin's own npm/Yarn tooling.** A second package manager and a second lockfile beside pnpm
  ([ADR 0116](0116-the-javascript-packages-are-installed-by-pnpm.md)), and a Node distribution fetched from a
  repository `src/mobile/settings.gradle.kts` does not declare (it sets `FAIL_ON_PROJECT_REPOS`).
- **Moving the crypto suite and admin-web too.** Decision 9.
