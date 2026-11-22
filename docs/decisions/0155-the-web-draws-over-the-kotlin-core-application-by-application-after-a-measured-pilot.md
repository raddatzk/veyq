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
  rule); its third (2026-11-22: a node header's and a level row's plans and core's favourite, cover and bookmark rights,
  `@veyq/core-js/navigation`, § The rights and plans' slice — 1.90×); its fourth (2026-11-22: a content sniff, a download name
  and an archive name, `@veyq/core-js/files`, § The file facts' slice — 1.93×; inbox #1059 decided per app). Decision 7.3 and 8 are not built: Expenses' move is parked, half built on
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
         (1,388 B gz): the structure every client draws, after the tree's kind facts exist to build them on — **built**,
         § The rights and plans' slice (923 B gz deleted: the standard builders stay);
      4. **the file facts** — a content sniff, a download name, an archive name (2,566 B gz): lazy today (Depot's
         viewer), and the SVG rule is a security rule — **built**, § The file facts' slice (2,539 B gz deleted; measured
         eager, not lazy: every part sat in the eager set already);
      5. **the media headers** — EXIF, video and image headers (4,780 B gz): lazy, and no vectors yet;
      6. **the export archive** — the ZIP, its CRC and manifest (5,726 B gz): lazy, streamed, and a wrong byte loses data;
      7. **the music order and fades** (859 B gz).

      Not slices: the **formats** move with the slice or application that owns each (money with Expenses) — alone they
      miss R by a measured factor (ADR 0154 § Consequences: 7.1×); **notices** wait until the web and the apps read one
      word catalog (their words are generated for the web since 2026-11-22, ADR 0136 § The web's words); the **slug folding** (`shared/naming.ts`) stays beside the Kotlin as decision 9's crypto does, held by
      `v1.json#name_folding`; the **URL fragment rules** are the router's (decision 1). Every platform façade lands in
      `:core`'s one JavaScript file, which a bundler cannot split, so a rule that is lazy in TypeScript turns eager when
      it moves — the reason slices 4 to 6 come last, each asking first whether a module of its own is cheaper (slice 4 measured
      its TypeScript eager already, so the question did not arise: § The file facts' slice, D2) — and the
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
    before the slice — the server's question, not this slice's (inbox #1067: since 2026-11-22 the list is the link domain's tenant's, ADR 0088 decision 3; the same day's re-run of
    the pass, switched off and reloaded, showed the fallback line naming the host, ADR 0121 decision 3 § `Cache-Control`).

- **The rights and plans' slice** (decision 7.2's third, built 2026-11-22, branch `claude/rights-slice`). A node header's plan
  and a level row's menu (`NodeHeader.plan`, `LevelRow.plan`, ADR 0154 decision 2) and core's favourite, cover and bookmark
  rights (`navigation/CoreRights`, new) are the platform's `navigation/` (`:core`); the web draws them through
  `@veyq/core-js/navigation` — `src/web/src/core/node/plans.ts` hands the core what the page's and core's actions offer and
  draws exactly the plan it returns, `favorites/rights.ts`, `cover/rights.ts` and `bookmark/rights.ts` read the session into
  the rules. `contracts/platform/plans/v1-vectors.json` holds both sides with each kind's header order (`PlanVectorsTest`, the
  package's `navigation.test.ts`, the web's `plans.vectors.test.ts`). Its gate (each number against `36516924f`):

  | Criterion | Measured | Bound |
  |---|---|---|
  | size: the platform module, every entry | 35,392 → 37,145 B gz; own code 1,753 = 1.90× the 923 B gz deleted (2,145 B minified) | 35,865 + 3.0 × 923 = 38,634 |
  | size: the eager set | +1,863 B gz (637,425 → 639,288: `activeSpace` +1,699, `main` +163) | 2,769 at R |
  | size: any lazy chunk | +2 B gz at most | ≤ 1,024 |
  | time (Node 22, median of 15, 10,000 each) | a level's row menus 1.78× (22.0 ms), Memory headers 2.12× (34.4 ms), `favoriteRights` 1.10×, `coverRights` 1.31×, `bookmarkRights` 1.03×; the answers identical | ≤ 2.5× and ≤ 100 ms |
  | delivery | three `--rerun-tasks` builds one digest; `verify:csp`, `verify:license-sources` green | identical |
  | tests | the deleted cases each a vector case or a case of `plans.test.ts`, `plans.whole.test.ts` or a staying test; a reversed `LevelRow.LEAD` turns the package's and the web's vectors red, the pre-fix banner slot `PlanVectorsTest` | — |
  | behaviour | the web's tests pass, the header's and row's component tests moved onto real entries (a fake id now throws); the vectors' web-expressible cases are the TypeScript's answers byte for byte (phase 2). **The browser pass is owed**: the dev backend on 8080 answered every request with Quarkus' "Error restarting" page from before the façade's first build to the harvest (last at 15:11, 2026-11-22), and this slice's rules forbid touching it; a baseline build of `24a2ad5f9` (the tree before the web's wiring) was prepared for the comparison | identical |

  The slice's decision log, kept by its implementer (question, choice and why, evidence, cost; the refine's entries first):
  - **D1 — The slice's set.** *Q:* which TypeScript leaves? *Options:* (a) the plans alone (the header's and the row's merge,
    order and split, 1,012 B gz); (b) the plans and core's favourite, cover and bookmark rights (ADR 0155's row, 1,388 B gz);
    (c) (b) and `access.ts`' `nodeRights`/`folderRights` (834 B gz more), the base every application's rights function
    narrows. *Choice:* (b), the row as ADR 0155 names it. `nodeRights` and `folderRights` are the applications' base, read
    by every application's `rights.ts` and by the guest's role matrix, which the apps answer through their doors
    (`NodeHeader.rights`' inputs) and not with one matrix; moving them asks first what the apps' twin is (a design of its own,
    follow-up). The copy, download and report gates (`canCopy`, `canDownload`, `canReport`, 525 B gz) read the registry's
    hooks and an active review: the browser's readings, as the apps' doors are theirs. *Evidence:* measured,
    `node rights-scratch/measure-ts.mjs` (Ground). *Cost:* the rights stay half TypeScript until the applications' base moves.
  - **D2 — The entry is `@veyq/core-js/navigation`.** The plans live in `:core`'s `navigation/`; `:composition:jsPackage`
    names an entry by the directory above `web/`, and the web's direction guard reads every entry that names no application
    as core's (`validate_web_core_imports_no_applications.py`, `CORE_JS_ENTRY`). Remembered from § The node tree's slice: the
    entry's code is the module file, only its types its own `.d.mts`.
  - **D3 — Where a web plan and an app plan differ, and what settles each** (the Explore map; ADR 0154 § Consequences: "each
    such change … follows the web unless the owner decides otherwise", and the owner's answers of 2026-11-20 recorded there):
    - *A Memory's banner:* the web's is `menuOnly` — the menu's last group, with move, copy, report and delete; the core's
      `BANNER` sits in `Slot.MENU`, the first group, though its KDoc says "as the web's `menuOnly`". Settled by the rule: the
      web's. `HeaderAction.BANNER` moves to `Slot.LAST`; on the four apps *Add banner* moves behind the menu's separator.
    - *A header's rename, cover, "Available offline" and send:* the web renames in place and opens the cover through its
      leading visual (ADR 0110 decision 11), has no offline store and no system share sheet; named as platform affordances in
      `HeaderAction`'s KDoc and `PlansAreTheWebsTest`. The web hands the plan no such right; no change.
    - *A row's share:* the apps' row menu offers Share (`LevelRow.plan` over `NodeHeader.standard`), the web's row does not
      (its row defaults are rename, move and delete, `standardRowActions.ts`). Not named by any answer, and either way removes
      or adds a function a person sees: **an owner question**. Until answered each client keeps what it has — the web hands
      its row no share right (it has no share door on a row), the apps unchanged.
    - *A kept link's row:* the web's row offers the share's report, the apps' row does not (`ShellModel.rowPlan` hands no
      `own`). The rule says the web's; the apps' row is native drawing beside #1066's work, so a follow-up (inbox).
    - *Not this slice's surfaces, each a difference found:* New's order (web Folder, Memory, Upload files, Upload folder, Pot,
      Bookmark; core Folder, Pot, Upload files, Upload folder, Memory, Bookmark — settled by the rule, the web's, but a level's
      New is no rights or header plan: follow-up); a READ_WRITE guest's New (web also *Upload folder*, core `GuestLevel.creates`
      folder and files — a product question, below); the level's view switch (web only where more than one view, core unless
      the map tab); the Settings sections (web gates profile on a tenant, core gates appearance and maps; the web's tenant
      `storage` and the apps' offline `storage` share an id); the sign-in (web has a locked, a forgot-password and a sign-out
      entry, no Cancel or *Open a link*); a guest's own photo's delete on a tile (web only). Each stays as it is here and is
      listed in the report.
  - **D4 — A kept link's two reports take the core's ids.** The web names the name's report `report-name` and the share's
    `report` where a link reads (and the name's `report` where none does); the core names them `report` and `report-shared`
    (ADR 0086: one word per meaning). The web takes the core's: `report` is always the name's, `report-shared` the share's.
    *Cost:* the menu entries' class hooks (`veyq-*-action-report-name`) change; no stylesheet reads them (grep).
  - **D5 — The kinds' header orders stay the registry's data, pinned by vectors.** *Options:* the order joins the tree
    slice's `KindFacts` (the core then plans from the kind); or the web hands its descriptor's `header.order` to the plan, as
    today, and both registries are held to the vectors' `kinds`. *Choice:* the second — `:composition` is no JavaScript entry,
    so the web's façade would rebuild the same table from the same registry; one table pinned both ways is what the tree's
    kinds already are. `PlansAreTheWebsTest`'s two source readings (header orders, core's merge order) give way to the
    vectors, written by the core; its Settings reading stays. *Cost:* none on the apps.
  - **D6 — The web draws a plan whole, by construction and by a check.** The adapter hands the core the ids the page's and
    core's actions offer (the rights) and draws exactly the ids the plan returns: an id the core does not know, or an offered
    action the plan leaves out, throws (an entry of the web's own is the defect ADR 0154 decision 2 names). What stays the
    web's: how many of a header's `buttons` fit (ADR 0110, measured), and which row entries are drawn inline — at most two
    marked `first` (the heart, a file's download), taken from the plan's menu in its order, the rest of the menu after them:
    "how many fit inline is the platform's" (ADR 0154 decision 2).
  - **D7 — The vectors' writer moved from the web to the core.** Phase 2 recorded every case the web's TypeScript can express
    (a header with no rename, cover, offline or send and every button fitting; a row with no share or offline) with the
    web's test's write mode; the three app-only header cases and two row cases were left `null`. Phase 3's `PlanVectorsTest`
    (`:composition`, common: the JVM and `macosArm64`) rewrote the file: byte for byte the same but the five `null`s filled,
    each read against its rule (measured: `diff` of the two writes). The web test's write mode left with phase 5. *Cost:* none.
  - **D8 — Core's rights are three functions over plain inputs** (`navigation/CoreRights.kt`). *Options:* `NodeHeader.rights`
    (the apps' reading of their doors) widened with the web's inputs; a `Viewer`/`Target` model of the web's `Access`; three
    functions over booleans the caller reads. *Choice:* the third — the web's `Access` is a browser session (a guest's link,
    a pending deletion, the switch of an application) the apps read through their doors, so the rule is the conjunction and
    the reading stays each platform's. The vectors enumerate every combination (8, 128 and 64 cases); before phase 3 the
    web's TypeScript answered all 200, after it the core's (measured, `plans.vectors.test.ts`, `PlanVectorsTest`). Since 2026-11-22 the
    apps read through it too (`NodeHeader.rights`, ADR 0154 § As built: the plans aligned, P11).
  - **D9 — The web hands the plan presence, not flags.** *Q:* how does the web say what a node grants? *Options:* a rights
    object computed beside the actions; the actions the page and core's action builders already gate. *Choice:* the actions —
    each standard or core action's presence is its `NodeHeader.Rights` flag, every other one the page's own entry, in its
    order; the adapter (`core/node/plans.ts`) draws the plan's ids with the web's own action objects. One answer per action
    (its builder's rights function), no second flag list to keep equal to it. The `placement` and `menuOnly` fields leave the
    web's action model: where an action sits is the plan's. `withStandardRowActions` stays as the union of a row's defaults
    and the application's own (an own action of a default's id replaces it), its order no longer meaningful.
  - **D10 — `HeaderAction` is no longer `@Serializable`; its serial names stay.** The façade's first build put
    `kotlinx-serialization-core` into the module (the slice check red for all four entries; 43,721 B gz): an `@Serializable`
    enum's companion holds a lazy `createAnnotatedEnumSerializer`, built with the entries, so reaching any entry reaches the
    library. *Options:* keep it and give up the slice; a hand-written serializer; drop the annotation. *Choice:* drop it —
    kotlinx.serialization serializes an enum at its use site (the bridge's `HeaderShown.PlanAction`) with its `@SerialName`s
    on every target. *Evidence:* `:bridge:generateBindings` wrote `bridge.rs`, `Bridge.g.cs` and the samples byte-identical
    before and after (`diff -r`); `PlanVectorsTest` reads the names through a use-site carrier on the JVM, `macosArm64` and
    Windows (`serializer<HeaderAction>()` found none on Kotlin/Native, measured); the structure guard and Apple's
    `generate_plans.py` read `@SerialName` from the source as before. *Cost:* `HeaderAction.serializer()` is gone (no caller).
  - **D11 — The plans' rules written as loops over the granted entries.** With serialization gone the module measured
    38,773 B gz, own code 3,381 = 3.66× (`NodeHeader` 6.8 KB unminified: `Pair`s, a `Placement` class, `withIndex` +
    `associate` (a `LinkedHashMap`), `toSet`, `sortedBy`, the ten-field data class `Rights` and the `Plan` data classes on the
    path; attributed by diffing an unminified bundle with and without the façade). `NodeHeader.planInto(granted, …)` and
    `LevelRow.planInto` now take the entries a `Rights` grants (`NodeHeader.granted`: each right one entry of its name,
    `RIGHTS`) and write three lists; `plan(rights, …)` wraps them for the apps, its signature and answers unchanged
    (`NodeHeaderTest`, the vectors). The façade reads the shape's flags by the entries' ids, so no `Rights` or `Plan` is built
    on its path. *Evidence:* 37,145 B gz, own code **1,753 B gz = 1.90×** the 923 deleted (measured, `slice.test.ts`). *Cost:*
    none on the apps' behaviour; `Placement` is gone (nothing outside `NodeHeader` used it).
  - **D12 — The gate** (phase 4; Node 22.23.3, macOS arm64, Kotlin 2.4.21):
    - size: the deleted TypeScript re-measured on the sources at `068051ab6` (the functions bundled alone, every other import
      external, gzip -9; `rights-scratch/measure-ts.mjs`): **2,145 B min / 923 B gz** (`nodeHeader.ts`' five, `levelRow.ts`'
      three, the three rights functions; `withStandardRowActions` stays, simplified, so it is not counted). ADR 0155's row had
      estimated 1,388 with the standard builders, which stay. The platform module with every entry **35,392 → 37,145 B gz**,
      own code 1,753 = **1.90×** (R = 3.0 allows 2,769); the running budget 38,634;
    - time (`rights-scratch/bench.ts`, median of 15 after 3 warm-ups, the answers compared first): a level's 10,000 row menus
      1.78× (12.3 → 22.0 ms), 10,000 Memory headers 2.12× (16.2 → 34.4 ms), `favoriteRights` 1.10×, `coverRights` 1.31×,
      `bookmarkRights` 1.03× over 10,000 nodes — each within 2.5× and 100 ms;
    - bites: `LevelRow.LEAD` reversed turns the package's `navigation.test.ts` (five row cases) and the web's
      `plans.vectors.test.ts` red; an unknown id read as `SHARE` turns both packages' unknown-entry tests red; the running
      budget at a deleted 300 B gz red; `plans.ts` without its whole-plan check turns `plans.whole.test.ts` red; the
      pre-fix banner slot turned `PlanVectorsTest` red (phase 3). Each restored with `/bin/cp -f`;
    - delivery: three `:composition:jsPackage --rerun-tasks` builds, one digest of `dist/` (`3b2e311b…`).

  - **D13 — The web's guard is the adapter's own check, no new validator.** *Q:* is a static web guard cheap here (the web's
    `validate_mobile_structure.py`)? *Options:* a TypeScript reader for rules (b)–(c) over `src/web/src`; the adapter's
    runtime check exercised by every component test. *Choice:* the check — the web reaches the plans through one file, which
    throws on an entry the core does not know (the façade) and on an offered action the plan leaves out or an entry no action
    offers (`plans.ts`), so every mounted header and row is a test of it; a static reader would read names only (the apps'
    guard's own blind spots) and would have to except the registry's `header.order` arrays, the one place two serial names
    stand together legitimately (data held by the vectors). *Evidence:* `plans.whole.test.ts` bitten (the check removed: red).
    *Cost:* a component that filters the plan's answer after the adapter is not seen by a static rule; none does.
  - **D14 — Left, each named** (since settled or named anew, [ADR 0154](0154-every-app-draws-the-cores-plans-through-a-core-ui-its-applications-plug-into-as-the-webs-do.md) § As built: the plans aligned): a row's Share (the apps offer it, the web does not; the owner's question, each client keeps
    its own until answered); a kept link's share report on the apps' rows (`ShellModel.rowPlan` hands no `own`; the web's,
    by ADR 0154's rule); New's order (the web's Folder, Memory, Upload files, Upload folder, Pot, Bookmark against the core's
    Folder, Pot, Upload files, Upload folder, Memory, Bookmark — ADR 0154's rule says the web's; a level's plan, not this
    slice's); a READ_WRITE guest's *Upload folder* (the web offers it, `GuestLevel.creates` does not; the owner's question);
    the view switch's gate, the Settings sections (profile's and appearance's gates, the two `storage` ids), the sign-in's
    entries and a guest's tile delete — each a difference measured in the slice's map, none moved; `access.ts`' `nodeRights`
    and `folderRights` (the applications' base, their own slice); the apps reading `CoreRights` (they answer the same three
    rules through their doors today).

- **The file facts' slice** (decision 7.2's fourth, built 2026-11-22, branch `claude/files-slice`). What a decrypted file's
  first bytes are (`blob/Sniff`: image, container, PDF, text and its encoding, an SVG's markup, the claims that narrow), the
  name a download is saved under (`blob/DownloadName`) and the safe name of a path segment inside a download's ZIP
  (`export/ArchiveName`) are the platform's alone; the web draws them through `@veyq/core-js/files`
  (`jsMain/…/files/web/`) behind its unchanged `core/blob/sniff.ts`, `core/blob/meta.ts`' `downloadName`/`typeExtension` and
  `core/export/archiveName.ts`, which hold no rule. `contracts/platform/files/v1-vectors.json` holds every side
  (`FileFactsVectorsTest`, the writer; the package's `files.test.ts`; the web's `files.vectors.test.ts`). **Inbox #1059** is
  decided per app, security first (D9). Its gate (each number against `c4306dcce`):

  | Criterion | Measured | Bound |
  |---|---|---|
  | size: the platform module, every entry | 37,145 → 42,049 B gz; own code 4,904 = 1.93× the 2,539 B gz deleted | 38,634 + 3.0 × 2,539 = 46,251 |
  | size: the eager set | +2,024 B gz (639,288 → 641,312: the Kotlin now in the `review` chunk, `activeSpace` and `main` lose the TypeScript) | 7,617 at R |
  | size: any lazy chunk | +109 B gz at most (`media`) | ≤ 1,024 |
  | time (Node 22, median of 15 after 3 warm-ups, the answers compared first) | opening a file (`loadFileView`'s questions of a 256 KiB first frame) 0.71–1.02× for a text or an SVG, ≤ 1.92× for the rest (≤ 0.02 ms); `archiveName` over a directory of 10,000 ≤ 0.93× (9.2 ms); `isArchivePath` ≤ 0.78×; `downloadName` × 10,000 ≤ 2.24× (1 ms); `claimsText` ≤ 1.94×; the answers identical | ≤ 2.5× and ≤ 100 ms |
  | delivery | three `--rerun-tasks` builds from a removed `dist/`, one digest (`4d43a730…`); `verify:csp`, `verify:license-sources` green | identical |
  | tests | the deleted `sniff.test.ts` and `archiveName.test.ts` cases each a vector case (152 heads, 24 claims, 23 names, 43 download names, 53 directories, 40 segments and paths); `SVG_SKIPS = 17` turns `FileFactsVectorsTest`, the package's `files.test.ts` and the web's `files.vectors.test.ts` red, a renamed `audio/mpeg` row and a dropped `¹` the core's | — |
  | behaviour | the web's tests pass, their imports unchanged; the browser pass (one throwaway, headless Chrome, this worktree's Vite over the dev backend): five SVGs uploaded and opened — see D9 and D11 | identical but D11 |

  The slice's decision log, kept by its implementer (question, options, choice and why, evidence, cost):
  - **D1 — The slice's set.** *Q:* what TypeScript leaves? *Options:* (a) ADR 0155's row — `sniff.ts`, `downloadName` with
    `MIME_TO_EXT`, `archiveName.ts`; (b) (a) and Depot's `view.ts` decision (`loadFileView`). *Choice:* (a). `view.ts` is
    Depot's (an application's slice), and it streams and collects chunks — the browser's transport, which ADR 0155 decision 1
    keeps. Memories' `sniffMediaType` (the type an upload seals) stays Memories' and keeps its three byte readers (`asciiAt`,
    `hasAscii`, `readEbmlDocType`, 298 B gz) in `features/memories/download.ts`, not counted as deleted. *Evidence:* measured,
    `npx -y node@22 scratchpad/ff/measure-ts.mjs <web src>` (each set bundled alone, every other import external, minified,
    gzip -9): the sniff's eight exports 4,136 B min / 1,746 B gz, `downloadName` + `MIME_TO_EXT` 627 / 405,
    `archiveName.ts`' exports 1,070 / 648; **together 5,815 B min / 2,539 B gz** (ADR 0155's row said 2,566). *Cost:* none.
  - **D2 — Lazy or eager: the central question, answered by a measurement.** *Q:* the platform module is eager; does moving
    this slice turn lazy code eager, and would a second, lazily loaded Kotlin module be cheaper? *Evidence:* measured on the
    web's production build at `c4306dcce` (`vite build` under Node 22, then `/usr/bin/python3 scratchpad/ff/eager.py dist`:
    `index.html`'s entry and modulepreloads, each gzip -9; markers located by grep in the minified chunks): the eager set is
    **639,288 B gz** (ADR 0155's last recorded number); the sniff (`theora`, `x-ndjson`, `utf-16le`, `avis`) sits in
    `activeSpace` — eager, pulled there by `core/people/avatarCache.ts` (`sniffImage`) and Expenses' `receiptRules.ts`; the type
    → extension table in `activeSpace`; `archiveName`'s rules (`con|prn|aux`) in `main`. **Nothing of the slice is lazy
    today** — ADR 0155's premise was the Depot viewer's import, but the module lands where its first eager importer puts it.
    *Choice:* the platform module, as every slice before; no second module. A lazily loaded module would pay ADR 0156's
    per-module overhead to keep lazy what is eager already. *Cost:* none beyond R; ADR 0155's note on slices 4–6 corrected for
    this one (slices 5 and 6 measure their own).
  - **D3 — The entry is `@veyq/core-js/files`.** `jsMain/…/files/web/`, a core area's name (no application is `files`), so the
    web's direction guard reads it as core's; its code the module file, its types `dist/files.d.mts` (remembered from the earlier
    slices' entries).
  - **D4 — Bytes cross without a copy.** The web holds a `Uint8Array`; Kotlin/JS's `ByteArray` is an `Int8Array`. The façade
    takes the `Uint8Array` and reads it through an `Int8Array` view of the same buffer — no copy of a 256 KiB first chunk.
  - **D5 — The taken names cross as the web's own `Set`.** `archiveName(name, taken)` adds to the caller's set, which the web's
    exporter and folder upload keep across a directory. *Options:* an opaque handle per directory; an external interface
    (`has`, `add`) a `Set<string>` satisfies. *Choice:* the external interface — the callers keep their sets, no handle to
    track; the core's `ArchiveName.of` takes its own small `Taken` interface, a `MutableSet` adapter on the apps.
  - **D6 — Where the core and the web answered apart, the web's answer won** (ADR 0154's rule; each a vector case now). The
    core's `Sniff` had no SVG rule (#1059's half: added); read a Matroska file by `"matroska"` anywhere in its first 64 bytes
    where the web reads the EBML `DocType` element in the first KiB (`ebmlDocType`, ported: a DocType past byte 64 is Matroska,
    a `matroska` that is not the DocType is WebM); and said only whether a head is text, not in which encoding (`textEncoding`).
    `DownloadName`'s table lacked the six audio rows the web added for music tracks (ADR 0135), so an app's track download got
    no extension. And the core's `extensionOf`, `HAS_EXTENSION` and `trim` read the JVM's white space (`\s` ASCII on the JVM,
    `isWhitespace` elsewhere): JavaScript's set now (`JsText`), so `a.b c` has no extension on any client. *Evidence:*
    measured, the vectors recorded from the web (phase 2) answered by the core (`:core:jvmTest --tests '*FileFactsVectorsTest*'`:
    10 tests, 0 failures) and rewritten by it byte for byte (`VEYQ_WRITE_VECTORS=1 …`, `cmp` against phase 2's file). *Cost:* an
    app user sees a Matroska/WebM file read as the web reads it, and a track saved as `.mp3`.
  - **D7 — No `Regex`, `HashMap` or `Pair` on the path.** `Sniff`'s sets became `when`s, its UTF-8 reading one loop without a
    `Pair` per byte, `extensionOf`/`hasExtension`/the SVG prolog/`ArchiveName`'s forbidden, reserved and trailing rules hand loops
    (ADR 0155's traps). The apps' API stays (`Sniff.isText`, `DownloadName.of`, `ArchiveName.of(name, MutableSet)`).
  - **D8 — The bites (phase 3).** Each planted in the core, `:core:jvmTest --tests '*FileFactsVectorsTest*'` red (exit 1), restored
    with `/bin/cp -f`: `SVG_SKIPS = 17` (the seventeen-comments case), the `audio/mpeg` row renamed (the track case), `¹`
    dropped from the device names (`COM¹`).
  - **D9 — Inbox #1059: an SVG, per app, security first.**
    *Threat model.* An SVG in a space is a peer's bytes (a member, a link guest uploading into a drop box), opened on the
    viewer's device where the keys are. Must-not: (1) **script execution** — `<script>`, `on*` attributes, `javascript:`
    links, a `foreignObject`'s HTML: on the web an XSS beside the keys (operator-blind E2EE broken), in an app code run in its
    process; (2) **external fetches** — `<image href>`/`xlink:href`, `<use href>`, `<feImage>`, CSS `@import`/`url()`, a
    `foreignObject`'s frame or image, XInclude: a beacon telling a third party the viewer's address and when the file was
    opened; (3) **XXE** — an external entity (`SYSTEM "file:…"` or `"http:…"`) expanding a local file into the picture or
    fetching. Bounded, not excluded: a renderer's denial of service (huge sizes, deep filters; the 64 MiB cap bounds the bytes,
    not the work) and a decoder's memory-safety bug (the residual every image decoder carries). Not a threat: a drawing that
    looks like UI — it stays inside the picture's frame. **The sniff is a gate, not a sanitizer**: it says the markup is an SVG
    (any doctype skipped, `<!DOCTYPE html><svg>` too — the web's rule, a vector case); safety is the renderer's mode.
    *Options per app:* draw it only through a renderer that is static by construction or a browser's image mode, proven on that
    platform by a hostile-file test; else show the text (fail closed, today's state).
    *Choice:* **web** — unchanged: an `<img>` from a blob URL (the SVG-as-image mode: no script, no resource loads) under the
    app's CSP (`script-src 'self'`, `img-src data: blob:`), never inline (remembered from the specifications; the browser pass
    checks the hostile files below). **Linux** — draws it: GTK decodes through gdk-pixbuf and glycin, whose SVG loader is
    librsvg in a sandbox of its own (no network, no file system); librsvg runs no script and resolves no URL but `data:` for
    bytes with no base file. Proven by `file_page.rs`' `an_svg_draws_with_nothing_in_it_run_or_fetched` in the Fedora 44
    container (`run.py build`'s SANDBOX_SKIPS): nine hostile files naming a loopback listener, no connection; an external
    file entity does not expand where the internal control does. Turned on by the bridge's Linux start
    (`platformDrawsSvgAsImage`, `FileView.drawSvgAsImage`), for a member's and a guest's file page alike. **Windows,
    Android, iOS/macOS** — show it as its text, named in `docs/apps/depot.md` as the platform's difference: Android has no
    SVG decoder (a WebView's image mode would be a new browser surface, AndroidSVG a dependency whose maintenance is in doubt)
    and no device this run may use; Apple has no public decoder of arbitrary SVG data (a `WKWebView` image mode the
    candidate) and builds only here; Windows' candidates (WinUI's `SvgImageSource` over Direct2D, or the hardened WebView2 of
    `PdfWeb.cs` in image mode) need a hostile-file test on the VM that this slice did not write. *Cost:* an SVG looks
    different on four clients; each follow-up is a renderer plus its proof.
  - **D10 — The gate** (phase 4, Node 22.23.3, macOS arm64, Kotlin 2.4.21; the machine at a load of ~30 from parallel work, so
    the times carry its noise):
    - size: the platform module with every entry **37,145 → 42,049 B gz** (base measured on a worktree of `c4306dcce`, the
      package's `slice.test.ts`); own code **4,904 B gz = 1.93×** the 2,539 deleted (budget 38,634 + 3.0 × 2,539 = 46,251); the
      eager set **639,288 → 641,312 B gz, +2,024** (`scratchpad/ff/eager.py` over `vite build`'s `dist`: `index.html`'s entry and
      modulepreloads, gzip -9; the bound R × 2,539 = 7,617) — less than the own code, since the deleted TypeScript was eager too;
      no lazy chunk grows by more than 109 B gz (`media`);
    - time (`scratchpad/ff/bench/bench.ts`: the deleted TypeScript from git against the shipped module, median of 15 after 3
      warm-ups, the answers compared first, three runs): opening a file — what `loadFileView` asks of a 256 KiB first frame —
      0.71–0.91× (text), 0.90–1.02× (SVG), 1.89–1.92× (random binary, 0.02 ms), 1.36–1.81× (PDF, 0.003 ms), the JPEG below the
      timer; `archiveName` over a directory of 10,000 0.78–0.93× (≤ 9.2 ms); `isArchivePath` over 10,000 0.69–0.78×;
      `downloadName` × 10,000 1.81–2.24× (≤ 1 ms); `claimsText` × 10,000 1.62–1.94× — each within 2.5× and 100 ms;
    - delivery: three `--no-daemon --rerun-tasks :composition:jsPackage` builds from a removed `dist/`, one digest
      (`4d43a730…`); the web's `verify:csp` and `verify:license-sources` green in its check;
    - bites: `SVG_SKIPS = 17` red in the core's, the package's and the web's vector tests; the slice's row at 900 B gz deleted
      turns the running budget red; restored with `/bin/cp -f`.
  - **D11 — The web's SVG: a blob URL opened in a tab ran its script — shown as a PNG now.** *Q:* does the web's SVG drawing
    hold the threat model? *Evidence:* measured, the browser pass (headless system Chrome through Playwright, a throwaway on
    this worktree's Vite at `tw-…localhost:8197` over the dev backend; the store's presigned PUTs, whose CORS names only origin
    8081, sent through Playwright's `route.fetch` with the page's origin added — the pass's one workaround, the listener's
    origin never routed): five SVGs (benign; `<script>` and `onload`; `<image href>`, `xlink:href`, `<use>`, CSS `@import` and
    `url()`, a `javascript:` link; a `foreignObject` frame, image and script; an external `http` and a `file` entity), each naming
    a loopback listener. In the page's `<img>` nothing ran and nothing was requested. But the `<img>`'s blob URL opened in a tab
    of its own ran the SVG's script — `PWNED-script`, `PWNED-onload`, `PWNED-foreign` in the console, the tab's title rewritten
    — on the app's origin: the page's CSP (`script-src 'self'`, a response header) was not inherited by the blob document.
    `depot.md`'s note that it is was remembered and wrong. *Options:* a `data:` URL (an opaque origin when opened, but the
    script still runs and may fetch); serving the SVG under another type (no image then); drawing it once into pixels.
    *Choice:* pixels — `features/depot/svgRaster.ts` decodes the SVG off the DOM in image mode, draws it onto a canvas (its own
    size × the screen's ratio ≤ 2, at most 4096 px) and the page shows the PNG; the SVG's URL is revoked once drawn. Re-run:
    four SVGs shown as PNGs, each tab opening a PNG, no listener hit, no off-origin request, no `PWNED`; the `foreignObject`
    one taints the canvas and is "cannot be shown" with the download. `FileViewer.test.ts` holds it (bitten: the SVG's blob
    shown again → two red). *Cost:* an SVG is a raster at a fixed resolution (zoom shows pixels — lifted by D14); one with a
    `foreignObject` no longer shows.
  - **D12 — Left:** a renderer and its hostile-file proof for Windows, Android and Apple (#1059's next step); the installed
    Flatpak's glycin sandbox was not exercised (the test runs in the Fedora container, where glycin's loaders run as in
    `run.py run`; the GNOME 51 runtime carries the same loaders, remembered from the manifest); the browser pass ran against
    Vite's dev build and headless Chrome only (Firefox and Safari not run; the PNG holds there by construction).
  - **D13 — A lone surrogate is no vector case.** *Evidence:* measured, Windows' `run.py test`: `archiveName("lone \ud800
    surrogate")` kept the surrogate on the JVM, `macosArm64` and JavaScript but became U+FFFD on Windows (and so on Linux): the
    bridged targets' `crypto/nfc` normalizes through utf8proc over UTF-8, which cannot carry a lone surrogate. *Choice:* the two
    lone-surrogate cases leave the vectors; `nfc` is not changed here (it folds the name tokens, `v1.json#name_folding`, a crypto
    change of its own). A name of decrypted UTF-8 cannot hold one; a browser's picked file name can. *Cost:* such a name's ZIP
    segment differs between the desktops and the other clients (follow-up: `nfc` on the bridged targets keeping lone
    surrogates, under the crypto suite's vectors).
  - **D14 — The web's SVG stays sharp when zoomed: painted again at the zoom's scale, onto canvases** (owner 2026-11-22,
    branch `claude/svg-zoom`). *Q:* D11's PNG is drawn once (≤ 2 × its size, ≤ 4096 px): zoomed, it shows pixels. How does a
    zoom stay sharp without giving up any of D9's must-nots? *Options:* (a) re-encode a PNG at each zoom and swap the
    `<img>` (a PNG encode per step; a 16× PNG of a large picture is the canvas the cap forbids); (b) paint the decoded image
    onto in-page `<canvas>` elements at the zoom's scale, only the part in view; (c) a sandboxed, opaque-origin rendering
    (an `<iframe sandbox>` of the SVG — not measured: the brief rules out an `<iframe>`/`<object>` of the SVG and a CSP
    change, the policy's `frame-src` admits none, and an opaque origin stops the keys' theft but not a beacon — D9 must-not
    2 — unless the frame carries a policy of its own; remembered, not built). *Choice:* (b). `svgRaster.ts`' `decodeSvg`
    decodes the SVG in image mode off the DOM (as D11), revokes its URL **before** it returns, and reads one pixel back from
    a 1 × 1 probe draw — a tainted canvas (`foreignObject`) throws, so the SVG is refused before anything is shown, as D11's
    `toBlob` refused it; every later paint reads a pixel back too and empties its canvas if it throws. `SvgView.vue` paints
    the decoded image onto a **base** canvas (the whole picture at the size that fits, never larger than its own, × the
    screen's ratio) and, zoomed (×1 to ×16 in nine steps, labelled buttons as the PDF's), a **tile** canvas holding only
    the part in view at the zoom's actual scale: zoom × `devicePixelRatio` × a pinch zoom's `visualViewport.scale`,
    repainted 150 ms after the last zoom, scroll or resize (a stale tile is stretched with the zoom meanwhile, the base
    under it). A canvas is a drawing, not a URL: the page holds no `<img>`, no blob URL, nothing a tab could open but the
    canvas' own pixels. **The cap** (`planRaster`): no painted canvas holds more than 4096 × 4096 = 16,777,216 pixels
    (64 MiB RGBA; iOS Safari's canvas area limit) or 8192 px on a side — past it the ratio is lowered, the cap wins over
    sharpness; since the tile is the view's size, not the zoom's, a 16× zoom of a 100,000-unit SVG holds no more than the
    view. *Evidence:* measured. A probe (headless system Chrome, `page.evaluate`): a decoded SVG drawn after its URL was
    revoked still draws (`naturalWidth` kept), a 10-unit edge drawn at 100× has 0 grey pixels (vectors, not a stretched
    bitmap), a draw of a 1,000,000-unit picture offset into a 1,000 px canvas takes 13 ms, a `foreignObject` SVG makes
    `getImageData` throw `SecurityError`, a `viewBox`-only SVG decodes as 300 × 150. The pass (headless system Chrome
    through Playwright, a throwaway on this worktree's Vite at `tw-…localhost:8213` over the dev backend; the store's
    presigned URLs name origin 8081 and its CORS only that origin, so `/blobs/` on 8081 was passed by Playwright's
    `route.fetch` straight to the store at 127.0.0.1:19000 with the signed Host kept and the page's origin added — the
    pass's one workaround, the listener never routed): six SVGs uploaded and opened — benign; `<script>` and `onload`;
    `<image href>`, `xlink:href`, `<use>`, CSS `@import` and `url()`, `<feImage>`, a `javascript:` link; a
    `foreignObject` frame, image and script; an external `http` and a `file` entity; a 100,000 × 100,000 one — each
    naming a loopback listener. At 1×, 4× and 16× (dpr 2, 1280 × 900): no `PWNED`, the title unchanged, 0 listener hits,
    0 off-origin requests, no `<img>`/`<object>`/`<iframe>` in the viewer and no blob or `.svg` URL in the DOM; the 200 ×
    100 SVGs' base 400 × 200 px for 200 × 100 CSS (×2), the tile at 4× 1600 × 800 px for 800 × 400 CSS (×2, the picture at
    4×), at 16× 1872 × 1260 px for the 936 × 630 CSS view (×2, the picture laid out at 3200 × 1600) — the device ratio
    kept at every zoom, 0 grey pixels across the black/white edge in view; the `foreignObject` one "cannot be shown" with
    the download. The cap, dpr 3, 2560 × 1600, full screen, the huge SVG: base 4095 × 4095 px for 1456 CSS (ratio 2.81 < 3,
    16,769,025 px), the tile at 4× and 16× 5397 × 3108 px for 2528 × 1456 CSS (16,773,876 px, ratio 2.13) — both under
    16,777,216. The web's tests: `svgRaster.test.ts` (the cap over six regions, the URL revoked before the picture is
    handed out and on a failed decode, the taint gate, a paint's draw arguments) and `FileViewer.svg.test.ts` (a zoom
    repaints the view at zoom × ratio, debounced, within the cap at 16× and a ratio of 8; fit frees the tile; a refused
    paint is "cannot be shown"; unmount empties both canvases). Bitten, each restored with `/bin/cp -f`: the cap removed
    from `planRaster` (2 red), the tile painted at the fitted scale (2 red), the taint read-back dropped (1 red), the URL
    kept unrevoked (2 red), the debounce at 0 ms (1 red). *Cost:* two canvases instead of one `<img>` (the base ≤ the fitted
    view × ratio², the tile ≤ the cap); a repaint per zoom step or scroll pause, and a one-pixel read-back per paint (a GPU
    read); a stale tile shows the stretched base for 150 ms after a scroll; the right-click menu offers a canvas' "Save
    image as" (its pixels, a PNG) where it offered the `<img>`. Left: a pinch zoom and a browser zoom repaint through
    `visualViewport` and a `resolution` media query — not exercised by the pass (headless Chrome changes neither live);
    Firefox and Safari not run (their image mode and canvas taint are remembered, not measured here).

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
