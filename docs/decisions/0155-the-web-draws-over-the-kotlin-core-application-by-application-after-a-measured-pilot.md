# ADR 0155 — The web draws over the Kotlin core, application by application, after a measured pilot
- Status: Accepted
- Built: partly — decision 3 (2026-11-20: `:core`'s `js()` target over `@noble/*`, the refusals in `jsMain`'s two
  `NotOnTheWeb.kt`); decision 6 (`@veyq/core-js` with its conformance in CI's `frontend` job, and the web's half: its
  build, dev server, check and the app image's stage build the package first); the pilot (2026-11-21): decision 2's
  gate passed (§ Consequences), decision 4 but its JSON seam, which comes with Expenses (the slice check in the
  package's `pnpm run check`), decision 5 for the search (`@veyq/core-js/search`, two opaque handles, the web's
  direction guard reading the entry); decision 7's ratio written; decision 7.1 (2026-11-22: the core's split into a
  platform module and one module per application, [ADR 0156](0156-the-kotlin-core-is-split-into-a-platform-module-and-one-module-per-application.md)). Decision 7.2, 7.3 and 8 are not built: Expenses' move is
  parked, half built on branch `claude/p2-impl`, and the split's measurement (§ Consequences) missed the search's
  budget — the owner's question (inbox #1046)
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
   2. **The platform core moves to the web slice by slice**: the core's formats, notices and plans of ADR 0154, the node
      tree's rules, and what is next after them. Each slice grows the eager set by at most **R × the gzip of the
      TypeScript it deletes**, R the pilot's measured ratio of its façade's own code to its deleted TypeScript, rounded
      up to the next half: **R = 3.0** (the pilot's 8,754 B gz of own code against 3,129 B gz deleted, 2.80; the probe
      had estimated ~2.9).
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
    the search alone, handed to the owner** (decision 2's rule); the split stays for its direction, and the miss decides
    how Expenses resumes: what a façade needs of the platform out of the module every entry imports, a declaration file
    per entry (one for every module today, so the entries share their types), a conformance compilation per application
    (Expenses' decode runner has none). [ADR 0156](0156-the-kotlin-core-is-split-into-a-platform-module-and-one-module-per-application.md) § Consequences holds the whole run.
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
