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
  and an archive name, `@veyq/core-js/files`, § The file facts' slice — 1.93×; inbox #1059 decided per app); its fifth (2026-11-23: an image's size, a
  video's size and capture time and a photo's tags, `@veyq/core-js/media`, § The media headers' slice — 2.73×; the ExifReader
  library gone); its sixth (2026-11-23: the export archive's ZIP, its length and the manifest's check, `@veyq/core-js/export`,
  § The export archive's slice — 1.90×); its seventh and last (2026-11-23: the music order and fades, `@veyq/core-js/audio`,
  § The music order and fades' slice — 1.56×), so decision 7.2's list is built. Decision 7.3's first application
  (2026-11-24: Expenses' rules, `@veyq/core-js/expenses` from `:apps:expenses`' own module file, decision 4's JSON seam
  with it, § Expenses' move); the owner (2026-11-22) counts the platform code its façade needs against the platform
  module's running budget and retired the search-alone budget. Its second (2026-11-24: Depot's rules, `@veyq/core-js/depot`
  from `:apps:depot`'s own module file, a lazy chunk, § Depot's move). Its third (2026-11-24: Memories' pure rules,
  `@veyq/core-js/memories` from `:apps:memories`' own module file, never in the eager set, § Memories' move). Decision 8 is
  not built; its measurement was taken with Memories' move (§ Memories' move, D7: the gallery's model as it is would put
  ~285 KB gz on every first load), and the owner decided 2026-11-25 that it crosses (inbox #1323)
- Date: 2026-11-20
- Amended: 2026-11-25 — **no size number gates a move any more** (the owner: "Wir gehen mit Kotlin, egal ob es ein
  bisschen teurer wird"). Dropped as gates that day: decision 7.2's R = 3.0 and the per-slice ratios, 7.3's 25 KB gz
  per application, decision 10's guest-path ceilings (Depot's 704 KiB, Memories' proposed 816 KiB, never set) and its
  R rule for later moves, decision 4's ban on kotlinx.serialization, coroutines and Ktor, decision 8's gate of its own,
  and 7.3's time ratio. Each is now **measured and reported with every move**; the numbers recorded below stay as they
  were measured, against the bounds that held then. What still gates: identical answers, 100 ms per user action
  (decision 7.3), no refusal reachable from a façade (decision 4)
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
   codecs and its screen models (decision 8). A moved application's feature is built in the core and
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

4. **What a façade carries is reported by the build, and declared per entry** (variant B until 2026-11-25, when the
   owner dropped the size gates; the ban below was a size device). Until then code reachable from a façade used no
   kotlinx.serialization, no coroutines and no Ktor. From then on a façade may reach them **once the move that needs
   them is built**: each entry of `src/packages/core-js/src/slice.test.ts` declares which of the three it carries, and
   the check fails where an entry carries one it does not declare or declares one it no longer carries — so the move that
   brings one in widens that entry's row in the same commit, and an accidental reach (Memories' D7: a model reaching the
   session pulled in all three) is seen in review, not discovered in a bundle. That is a declaration, not a bound: the
   bytes it costs are printed beside it, never compared. Why a declaration and not a report alone: a report nobody reads
   lets a 285 KB reach land silently, and the declaration costs one line per move. **What stays a ban:** a `NotOnTheWeb`
   refusal reachable from a façade — it throws on the web, a correctness defect, not a cost. JSON goes through a platform
   seam in common code: on the JVM and native targets kotlinx.serialization behind it, so the apps read and write exactly
   as today; on JavaScript `JSON.parse` and `JSON.stringify` — the seam stays the default for a pure rule (it is smaller
   and needs no serializer), and a façade that reaches kotlinx.serialization does so because a model it draws needs it.
   The formats bound every integer to a safe one (`entry.v1.schema.json`'s amounts and revisions), so `JSON.parse`'s
   numbers are exact and no reviver source text is needed; a format that ever needs a larger number carries it as a
   string, as `rate` already is. The shipped module's source map lists the files it carries, through the compiled
   modules' own maps to the Kotlin files; the check reads the libraries from it.

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
   2. **The platform core moves to the web slice by slice.** Each slice measures and reports what it grows the eager set
      by against the gzip of the TypeScript it deletes — its ratio —, and each of its hot rules meets 7.3's time rule.
      Until 2026-11-25 that ratio was a gate: at most **R = 3.0**, the pilot's measured ratio of its façade's own code to
      its deleted TypeScript rounded up to the next half (8,754 B gz of own code against 3,129 B gz deleted, 2.80; the
      probe had estimated ~2.9), a slice the owner granted more carrying its own (the node tree's 3.14×). The owner
      dropped it that day with every other size gate (the header's amendment): every slice of the list below was built
      under it, and its ratio stays recorded in its section. The order (2026-11-21; the deleted
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
      5. **the media headers** — EXIF, video and image headers (4,780 B gz): lazy, and no vectors yet — **built**, § The media
         headers' slice (4,809 B gz deleted; its two header readers measured eager, the EXIF reader and its library lazy);
      6. **the export archive** — the ZIP, its CRC and manifest (5,726 B gz): lazy, streamed, and a wrong byte loses data —
         **built**, § The export archive's slice (5,608 B gz deleted; measured eager, not lazy; the digest worker keeps its
         CRC-32);
      7. **the music order and fades** (859 B gz) — **built**, § The music order and fades' slice (864 B gz deleted; measured
         eager, its rules in `main`; the last of this list, which is built).

      Not slices: the **formats** move with the slice or application that owns each (money with Expenses) — alone they
      miss R by a measured factor (ADR 0154 § Consequences: 7.1×); **notices** waited until the web and the apps read one
      word catalog — released 2026-11-23: the web registers the notices' words from `words.json` and says them there
      (ADR 0136 § The notices' move), so the notices slice (the web's toasts raised by the core's `*Notices.kt` tables)
      is open, and adds the words it raises to `web.json`'s `read`; the **slug folding** (`shared/naming.ts`) stays beside the Kotlin as decision 9's crypto does, held by
      `v1.json#name_folding`; the **URL fragment rules** are the router's (decision 1). Every platform façade lands in
      `:core`'s one JavaScript file, which a bundler cannot split, so a rule that is lazy in TypeScript turns eager when
      it moves — the reason slices 4 to 6 come last, each asking first whether a module of its own is cheaper (slice 4 measured
      its TypeScript eager already, so the question did not arise: § The file facts' slice, D2; slice 5 turned ~10 KB gz eager
      and took a 45 KB gz lazy library out: § The media headers' slice, D2; slice 6 measured its TypeScript eager already, and
      a module of its own would not have spared the one lazy user, a worker, the standard library's floor: § The export
      archive's slice, D2; slice 7 measured its TypeScript eager already, and a module of its own would have paid that floor
      for 864 B gz of rules: § The music order and fades' slice, D2) — and the
      package's size check became one running bound on that file: the split's measured search alone plus R × each moved
      slice's deleted TypeScript (80,094 B gz after the seventh slice). Platform code an application's façade needs (Expenses'
      JSON seam, money's formats) counted against that running bound, not against the search, and there is no separate
      budget for the search alone: the platform module grows by design with every slice (the owner, 2026-11-22). **Since
      2026-11-25 the running bound is no gate:** `src/packages/core-js/src/slice.test.ts` prints the platform module's size
      with every entry bundled, and its growth over the search alone against the TypeScript the slices deleted (each
      slice's on its row) — the report a move cites.
   3. **Then application by application, Expenses first.** Each move: its façade, its TypeScript twin deleted, its own
      size report and time check. The floor is paid, so a size report counts only a move's own code:
      - **size, reported:** what an application grows the eager set and its own chunks by, and that the entries that do
        not use it grow by nothing; where its rules are eager today, as Expenses' are, the eager set carries them. Until
        2026-11-25 this was a gate of at most **25 KB gz** (Expenses' own code measured ~20 KB, its suite's `V1` among it);
        Expenses, Depot and Memories moved under it. Since then a move measures and reports it, never stops on it;
      - **time:** each rule a move names as its hot path runs **at most 100 ms** absolute over the move's named corpus,
        which is what one user action processes at the real use's upper end, **the answers identical** — both gates. Its
        ratio to the TypeScript's median is measured and reported beside it; until 2026-11-25 it was a gate too, at most
        **2.5×** (the owner, 2026-11-21, replacing 1.5× for an application move; the search's 1.5× of decision 2 was the
        pilot's and was met). Why the 100 ms stays when the ratio goes (decided 2026-11-25): the ratio is a cost relative
        to a TypeScript that is deleted with the move — the owner's "egal ob teurer" —, while 100 ms is what a person
        perceives as an immediate answer, the same for any language and the bound the web's search already answers to
        (ADR 0125, ADR 0146). It binds the experience, not the cost, so a miss is fixed in the core (as Depot's D3 did)
        or goes to the owner with its number; it is not a reason to keep TypeScript. Identical answers are correctness:
        two clients answering apart is the defect decision 1 exists to remove. For Expenses the hot paths are
        `buildLedger` and `checkLedger` over the 10,010-revision Pot.

8. **Screen models with flows cross to the web** (the owner, 2026-11-25, inbox #1323: cross). Moving a model that
   exposes a flow (ADR 0136 decision 2) brings coroutines into the web once: variant A's floor measured ~75 KB gz above
   B's. Until 2026-11-25 that crossing was a gate of its own, decided by a measurement of its own; the owner decided it
   that day — the models cross, and the measurement stays the record and the report each such move repeats (decision 4's
   declaration names the libraries it brings). The web observes a model as Swift and the bridge do, through its
   `watch(listener)` and the `Watch` it returns (`blob/UploadQueue.kt`), behind one composable that replaces a
   `shallowRef` per state and cancels the watch when its scope ends; never Vue's deep reactivity over a Kotlin object.
   **Measured 2026-11-24** (§ Memories' move, D7): coroutines alone add 4,680 B gz to the platform's module file and
   15,239 B gz to the application's own code; Memories' gallery model as it is reaches the session, the generated API
   client and with them Ktor and kotlinx.serialization, which then ship in the platform's module file with every entry —
   +284,612 B gz on every first load. How the first model crosses — `GalleryModel` as it is, or reading through its
   `Door` first so the session stays out of the platform's file — is that move's technical design, not this decision's.

9. **The crypto suite and admin-web stay TypeScript.** `@veyq/crypto` and the Kotlin suite are two implementations
   answering to one set of vectors; that is a check worth more than the bytes it costs. A Kotlin `V1` runs in the web
   only where a moved application's codec needs it, over the same `@noble/*` primitives and the same vectors. admin-web
   holds no domain logic to move ([ADR 0065](0065-admin-ui-cli-separate-service.md)).

10. **What the size report watches: the first load of a link guest, not a member's cached return** (the owner,
    2026-11-24; a gate until 2026-11-25, a report since). A member's browser holds the built chunks in its cache between
    visits, so a lazy chunk only members load costs them once per release. Three costs stay that a cache does not
    remove, and they are why a move keeps measuring and reporting the guest path though no number gates it any more:
    every release renames the hashed chunks it changes, and the platform module changes with almost every release, so
    each member pays its download again then; the engine parses and compiles every loaded script on every start, cached
    or not, which a weak phone feels; and a link guest is a first visitor — opened on a phone, on a mobile network, with
    nothing cached. Hence:
    - **The guest path is measured and reported with every move:** what a link guest's first load fetches — the eager
      set (decision 2's `main` and `index.html`'s modulepreloads; the platform module sits in it, every entry importing
      it) plus the chunks the share view of the guest's kind loads (§ Depot's move, D8 names the method), per kind the
      move touches. A move that grows it says by how much and why; making it smaller is an optimisation of its own
      (inbox #1310), never a condition of a move. **The platform module has no budget of its own:** it sits on every guest
      path; the package's slice check reports its size with every move.
      *History:* the guest path had a ceiling per kind from 2026-11-24 to 2026-11-25. **Set by the owner 2026-11-24, from
      Depot's move:** a Depot link guest's first load at most **704 KiB gz (720,896 B)** — the single-file link measured
      702,621 B gz, the folder link 688,070, the drop box 691,778; every other application's ceiling was to be set at its
      own next move, since one number does not fit (a Memory's page measured 811,620 B gz the same day). **Proposed for
      Memories, never set** (§ Memories' move, D8): at most **816 KiB gz (835,584 B)** — a Memory's page measured
      814,770 B gz after the move, its share body 718,798; the larger plus Depot's relative headroom (2.6%). Both were
      dropped with every other size gate on 2026-11-25 (the header's amendment; inbox #1324 closed with it).
    - **A chunk only members load** is measured and recorded with every move, as it was before.
    - **Time binds everywhere:** 7.3's time rule (100 ms per user action, identical answers) is about running, not
      loading, and holds for every rule moved. A move's time corpus is the move's to name where the real use is bounded
      (Depot's view decision over 1,000 openings, accepted 2026-11-24).
    - **Later moves of an application already moved** (inbox #1270, the owner 2026-11-24) report their ratio to the
      TypeScript they delete and their growth of the guest path as a slice does. Until 2026-11-25 they were gated as a
      slice, at most R = 3.0 on the guest path; that rule went with R.

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
    `AppKinds.tokenInput`); a guest moves nothing in the apps (inbox #1063).
  - *The numbered name in the apps* (inbox #1062, built 2026-11-22, branch `claude/sibling-names-1062`): the core's plans
    hand it on **beside** the name — `NodeRef.label` set by `Home.level`, `FolderRef.label` by `asFolder` and `Shell.at`
    (and read anew by `Shell.relabelled` on each of `ShellModel`'s rebuilds: an older sibling restored renumbers the path),
    `GuestLevel.Row.label` and `GuestLevel.title` in node-id order (a guest is served no creation instant), the bridge's
    `ReceivedRow.displayName` and `ReceivedPage.title`; each null where the name is the label, so a `copy(name = …)`
    never carries a stale one, and a constructor property, so the bridge's generated Rust and C# carry it. The name stays
    what a rename prefills, a move and a collision check read, a report names and an AutomationId keys. Android, Apple,
    Linux and Windows draw it on a folder's row and tile, Home's title and a guest's rows and title (no app draws a
    breadcrumb of Home's path). **Only folders are numbered, everywhere:** the web's level numbered folders among folders,
    but its breadcrumb (`browse.ts displayNameOf`) and a guest's `nameOf` among every sibling, so a folder `Rom` beside a
    Memory `Rom` — legal, a name is unique among its application's siblings only — read `Rom (2)` in the breadcrumb and
    `Rom` in the list it was picked from; both now number among the sibling folders (`browse.test.ts`). Equal names of one
    class arise among folders alone (a drop box's guests'). The web already draws `displayNamesOf` from the core, so
    decision 7.2's swap needs nothing more. Owed: the four apps' click pass over a drop box with two `Anna`s (member and
    guest).
- **The share links' slice** (decision 7.2's second, built 2026-11-22, branch `claude/share-links-slice`). A received
  link's check, its host, its report link and the share page's `&report` (`ReceivedLink`), a link's last end day under a
  tenant's limit (`LinkDays`, new) and an instance's embeddability (`ShareInstance.refusal` for the apps' open,
  `embedding` for the web page, one reading) are the platform's `sharing/` (`:core`), with `session/ClientWindow`'s window;
  the web draws them through `@veyq/core-js/sharing` behind its unchanged `bookmarkLink.ts`, `linkRules.ts`' `lastExpiryDay`
  and `shareInstance.ts`' `embeddability`, which hold no rule. `contracts/platform/share-links/cases.v1.json` holds every
  side (`ShareLinkVectorsTest`, the package's `sharing.test.ts`, the web's three tests). **Inbox #1057** — the apps
  counted an edited link's last end day from today, the server from the link's creation — is fixed for all four apps in
  `LinkForm.start`, which now takes `now` and counts from the edited link's `createdAt`. The bookmark's sealed meta stays
  TypeScript: decision 4's JSON seam was then only on the parked `claude/p2-impl` (built since, § Expenses' move; inbox #1271). Its gate (each number against `b8c2228de`):

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
  - **D12 — Left:** a renderer and its hostile-file proof for Windows, Android and Apple (#1059's next step — built, D15); the installed
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
  - **D15 — The apps draw an SVG as the web does: a web view's image mode (Android, Apple, Windows), glycin redrawn per
    zoom (Linux), a `foreignObject` refused by the core** (branch `claude/svg-apps`, D12's follow-up). *Q:* which renderer
    per app gives D9's must-nots, D14's refusal, a pixel cap and a sharp zoom? *Options and choices, per platform:*
    **Android** — no platform API decodes an SVG (`ImageDecoder` and `VectorDrawable` take none); AndroidSVG's last release
    is 1.4 of 2019-05-28 (Maven Central's metadata, read 2026-11-22), so the no-abandoned-dependency rule excludes it and
    Coil's SVG module, which wraps it. Chosen: the system `WebView` (the platform's Chromium, updated by Play) in image mode:
    script off, `blockNetworkLoads`, file and content access off, Safe Browsing off, the metrics opt-out in the manifest,
    the page and the picture served from memory by `shouldInterceptRequest` and every other request answered 403, no
    navigation, no long-press; its zoom is its own (pinch, `zoomBy` from the buttons). **Apple** — macOS's `NSImage(data:)`
    draws an SVG through CoreSVG (measured: twelve hostile files, no listener hit) but iOS has no public decoder of arbitrary
    SVG data, so one path for both: `WKWebView` in image mode — `allowsContentJavaScript` off, a non-persistent store, a
    content rule list blocking every load but its own scheme, a scheme handler serving the page and the picture from
    memory, no navigation but its page, no window, no link preview. Measured on macOS 26.7.3 (a probe over the same page and
    handler): ten hostile files naming a loopback listener, no connection, only `view.html` and `picture.svg` asked; the
    app's zoom script ran with the page's script off and laid the 200 px picture out at 1600 px at ×8. **Windows** —
    Direct2D's SVG (WinUI's `SvgImageSource`, Win2D) draws no `<text>` and rasterizes only the whole picture; chosen:
    WebView2 on the PDF viewer's measured browser (`PdfWeb`: no resolver, no proxy, nothing of Edge's own on the wire,
    InPrivate, a folder deleted after it), plus the page's script off, no context menu, no browser zoom (it would refit the
    picture), every request but the page and the picture refused (`SvgWeb.cs`); `SvgWebTest` (in the logged-on session,
    as `PdfWebTest`) shows nine hostile files in the core's own page and asserts no listener hit, no request but those two,
    the title unchanged, and the benign one at 200 px and 1600 px at ×8; its bite is `VEYQ_SVG_BARE=1` (the SVG inline,
    script on). Measured: `run.py build` green on the VM (the app, the bridge's 6 tests, `Veyq.Tests` outside the session);
    `SvgWebTest` itself not yet run — the session's scheduled task for the run's suffix is the owner's to register
    (`run.py register`), so its result is owed (inbox #1110). **Linux** — glycin's sandboxed librsvg kept (D9); zoom added: each step drawn again by the loader at the
    laid-out size × the screen's scale (`PixbufLoader::set_size`, librsvg renders the vectors at it), within the core's cap
    (`File.SvgRaster` → `SvgView.plan`, the web's `planRaster`); `file_page.rs`' `a_zoomed_svg_is_drawn_at_its_size_not_enlarged`
    holds the sharpness (a ×10 edge between two pixels, none partly covered) — written, not yet run: `run.py build` stopped
    at the Flatpak's poppler module, the Docker VM's disk full (inbox #1110). **The refusal** — the apps have no canvas taint:
    the core refuses an SVG holding a `foreignObject` by its markup, the whole file, before any renderer sees it
    (`SvgView.refused` in `FileView.open`): any case or prefix; character references undone to a fixed point, since an
    internal entity's value `&#60;foreign&#79;bject` becomes markup where it is used and `&#38;#…;` one round later; refused
    too what the scan cannot read (invalid UTF-8, a declared encoding other than UTF-8/ASCII — UTF-7 would hide the tag —,
    references nested past eight rounds). Internal entities alone are drawn (Illustrator declares them). Linux, whose
    librsvg never drew one, now refuses it too, as the web. **The zoom and the cap** — the web's nine steps
    (`SvgView.ZOOM_STEPS`); in a web view the step is the app's own script (`SvgView.zoomScript`: CSS `zoom` on the
    `<img>`, centre kept), which WebKit and WebView2 run beside the page's script turned off; the engine lays the picture out
    again and paints its vectors at that size into the tiles in view only, so no bitmap of the picture exists past the view —
    the engine's tiling is the cap there, `SvgView.plan`'s 4096² where an app rasterizes itself (Linux). *Evidence:*
    `SvgViewTest` (the refusal's spellings, entities and encodings; `FileView.open` handing a refused SVG's bytes to
    nothing; the steps; the cap; the page holding no script and the SVG only as an `<img>`), bitten (the unescape removed
    and the open's refusal dropped: three red, restored with `/bin/cp -f`). *Cost:* a web view per SVG on three apps (a
    browser process; on Windows a user-data folder, InPrivate, deleted after); Android steps by the WebView's zoom, pinch
    included, not exactly the web's steps. Linux draws only the part in view at each step (inbox #1112): gdk-pixbuf draws
    an SVG only whole, so the part is cut by the SVG's own root (`file_page.rs`' `svg_clipped`: its `viewBox` set to the
    part in the SVG's units, its size to the part's pixels) and drawn by the same sandboxed loader, the ×1 picture stretched
    beneath while a scroll's part is drawn — the cap holds per view, as the web's tile.
    *Left:* Android's hostile-file proof on a device and a committed Apple one (the macOS probe is the session's, no Apple
    test target exists; inbox #1110); iOS's first run (CoreSimulator is out of date here) and every app's click pass
    (#1111); Linux's pass of a far zoom scrolled (#1112).

- **The media headers' slice** (decision 7.2's fifth, built 2026-11-23, branch `claude/media-headers-slice`). An image's pixel
  size from its header (`media/ImageHeader`, new in the core), a video's display size and capture time from its container
  (`media/VideoHeader`, Matroska's size new) and a photo's EXIF, XMP and IPTC tags (`media/ExifReader`, its XMP scanned by
  `media/Xmp`) are the platform's alone; the web draws them through `@veyq/core-js/media` (`jsMain/…/media/web/`) behind its
  unchanged `shared/image/imageHeader.ts`, `videoHeader.ts` and `exifTags.ts`, which hold no rule, and the ExifReader library
  they called is gone (D1). `contracts/platform/media/v1-vectors.json` holds every side — 50 image, 65 video and 104 tag
  cases, each case's bytes as parts (hex, a fill, a repeat) and what its reads may reach (`bounds`) — (`MediaVectorsTest`, the
  writer; the package's `media.test.ts`; the web's `media.vectors.test.ts`). Its gate (each number against `3f355d862`):

  | Criterion | Measured | Bound |
  |---|---|---|
  | size: the platform module, every entry | 42,049 → 55,190 B gz; own code 13,141 = 2.73× the 4,809 B gz deleted | 46,251 + 3.0 × 4,809 = 60,678 |
  | size: the eager set | +9,694 B gz (641,702 → 651,396: the Kotlin in the `review` chunk +12,966, `main` −3,252 for the two header readers it no longer carries) | 14,427 at R |
  | size: any lazy chunk | +9 B gz at most (`itemFavorites`, `people`); the `exif-reader` chunk −45,226 (gone), `media` −1,255 | ≤ 1,024 |
  | time (Node 22, a pick's corpus at once, median of 15 after 3 warm-ups, three runs) | `imageSizeFromHeader` over 50 phone JPEGs 1.25–1.69× (≤ 0.97 ms); `readExifTags` `date` over them 0.82–1.19×, `all` 0.36–0.51× (the TypeScript without its XMP parse under Node, so its share is low); `date` over 50 HEICs 0.55–0.61×; `videoSizeFromHeader` over 20 MP4s with the `moov` last 1.09–1.14×, `videoCaptureTimeFromHeader` 1.19–1.21×, over 20 WebMs 0.82–1.02×; every answer identical | ≤ 2.5× and ≤ 100 ms |
  | delivery | three `--no-daemon --rerun-tasks :composition:jsPackage` builds from a removed `dist/`, one digest (`7d52fd8a…`); `verify:csp`, `verify:license-sources` green (51 packages, each with its source) | identical |
  | tests | the deleted `imageHeader.test.ts`, `videoHeader.test.ts` and `exifTags.test.ts` each case a vector case (the library-shape cases of `tagsFrom` as files that carry those shapes); bitten — orientation 5 not swapped (1 red in the core's, the package's and the web's vector tests), the first Exif `APP1` read (D6), an XMP prefix not read by its local name (D7), the slice's row at 2,000 B gz deleted (the running budget red) | — |
  | behaviour | the web's tests pass with their imports unchanged (591 files, 7,838 tests, every suite mocking `readExifTags` as it was); `readExif.fixture.test.ts` seals the same tiers from the fixtures over the core's reader. The browser pass of a pick (photos and videos: tile sizes, capture times, the panel) is owed (inbox #1140) | identical |

  The slice's decision log, kept by its implementer (question, options, choice and why, evidence, cost):
  - **D1 — The slice's set: the three readers and the library behind one of them.** *Q:* what TypeScript leaves, and does the
    ExifReader library stay? *Options:* (a) `imageHeader.ts` and `videoHeader.ts` only — the readers the core lacked a twin
    of in part; (b) (a) and `exifTags.ts`' own code, the library kept and its shapes handed to Kotlin; (c) (a), `exifTags.ts`
    and the library, the core's `ExifReader` the one reader. *Choice:* (c). The core already read every tag the web names for
    the apps (ADR 0123), held to the web's fixtures by `MediaMetadataFixtureTest`; keeping the library would keep two
    readers and hand Kotlin a library's object shapes; and decision 1 keeps only what a browser does, which a TIFF walk is not.
    *Evidence:* measured, `node scratchpad/mh/measure-ts.mjs src/web/src` (each set bundled alone with esbuild, every other
    import external, minified, gzip -9): `imageHeader.ts` 2,004 B min / 1,021 B gz, `videoHeader.ts` 6,141 / 2,496,
    `exifTags.ts` 3,914 / 1,678; **together 12,097 B min / 4,809 B gz** (ADR 0155's row said 4,780). The library is not
    counted as deleted (it is not TypeScript the web wrote), though its 45,226 B gz chunk left. *Cost:* the readers' answers
    are now the core's where the library read more — what it did that the core does not is D8's residual.
  - **D2 — Lazy or eager: measured, a mix.** *Q:* the platform module is eager; what turns eager? *Evidence:* measured on the
    production build at `3f355d862` (`vite build` under Node 22, `scratchpad/mh/eager.py dist`: `index.html`'s entry and
    modulepreloads, each gzip -9; the rules located by grep in the minified chunks): the two header readers sat in `main`
    (eager); `exifTags.ts` in the lazy `media` chunk and the library in its own lazy `exif-reader` chunk (45,226 B gz), loaded
    by the first photo picked (`useOptimisticTiles`' date probe). *Choice:* the platform module, as every slice before, no
    second module: the eager set grows by 9,694 B gz (inside R × 4,809 = 14,427) and every pick stops loading 46 KB gz of
    lazy code. *Cost:* a visitor who never picks a photo carries ~10 KB gz more.
  - **D3 — The entry is `@veyq/core-js/media`.** `jsMain/…/media/web/`, a core area's name, read by the web's direction guard
    as core's; its code the module file, its types `dist/media.d.mts` (remembered from the earlier slices' entries).
  - **D4 — The reads stay the browser's, awaited without kotlinx.coroutines.** *Q:* the readers walk a file in windows whose
    places depend on what they read (a `moov` behind the media data, a JPEG's segments), and a `Blob`'s slice is a promise.
    *Options:* (a) the web reads a fixed head and hands it over (a `moov` at the end out of reach); (b) a resumable walk that
    answers "need bytes at X" and is run again (every reader rewritten twice); (c) the core's readers stay `suspend` and the
    façade hands them the web's `read(from, length): Promise<Uint8Array>` (`MediaFile`), awaited by `suspendCoroutine` and
    answered as a `Promise` built with `startCoroutine` — the standard library's coroutine intrinsics, not kotlinx.coroutines
    (decision 4 bans the library, whose floor was variant A's). *Choice:* (c). *Evidence:* the slice check's forbidden list
    stays empty for every entry (`pnpm run check` in `src/packages/core-js`); the time row. *Cost:* each `suspend` function
    is a state-machine class in the module.
  - **D5 — No `Long`, `Regex`, `HashMap` or `Pair` on the path; the apps keep their API.** Offsets, sizes and instants are
    `Double`s (`MediaBytes`, exact to 2^53; `localWallClockMs`, the `expect` the four platforms answer, `localWallClock` its
    `Long` for the apps); the apps' `ByteSource` adapts through `media()`, `ExifTags`' dates keep `Long` getters over their
    `…Ms` fields, `VideoHeader.size`/`captureTime(ByteSource, Long)` stay. The XMP is scanned by hand (`Xmp`), the dates and
    `Number()`'s reading of a rating parsed by hand, the IFDs dispatched by `when`, a HEIF's item found without a map. A
    signed rational is read as a signed `Int` — a `Double` of an unsigned value saturates in `toInt()` (measured: the
    fixtures' −0.7 read as 214,748,364.7 until fixed).
  - **D6 — Where the library and the core answered apart, the web's answer won** (ADR 0154's rule; each a vector case now,
    the phase-1 file recorded from the web, `media.vectors.write.test.ts` under happy-dom, deleted when the core took over):
    a `DateTimeOriginal` in IFD0 is read (the library merges IFD0 and the Exif IFD by tag); of two Exif `APP1` segments the
    last is read; a coordinate needs its reference; an empty ASCII tag is none; a WebM's and a Matroska file's size is read
    (the apps answered null: `MediaUpload` now sizes them); Apple's creation date rolls a month 00 or 13 over the year as
    `Date.UTC` does. *Evidence:* measured, `:core:jvmTest --tests '*MediaVectorsTest*'` green and the file rewritten by the
    core byte for byte (`VEYQ_WRITE_VECTORS=1`), but the twelve cases the library **rejected** (bytes of no image, eleven
    near misses of a magic number): the core answers none — the callers catch both the same (`media.ts`). *Cost:* an app's
    sealed metadata of such a file changes as described; nothing the web sealed changes.
  - **D7 — XMP by local name, without an XML parser.** *Q:* the library parsed XMP with the browser's `DOMParser` through
    `parseInertXml` (ADR 0117) and keyed a property by its local name, whatever its namespace; the core matched literal
    prefixes by regular expression. *Choice:* the library's reading, scanned by hand (`Xmp`): an attribute or an element by
    local name, the last occurrence winning, an `rdf:Alt`/`Bag`/`Seq`'s `x-default` item else its first, a structure or a
    CDATA section no value, a packet with unbalanced tags, a broken attribute or an unknown entity none at all (the parser
    refused it). *Evidence:* the vectors' XMP cases (a foreign prefix, a default namespace, an attribute and an element of one
    name, a malformed packet), each recorded from the library. *Cost:* a well-formedness check of our own, not the XML
    specification's; the XMP no longer reaches a DOM parser (one Trusted Types door fewer used: `parseInertXml` keeps the GPX
    track, `cc.security.csp.md`).
  - **D8 — Left: what the library read that the core does not.** A PNG's compressed XMP `iTXt` (and `zTXt` raw profiles), a
    HEIC's XMP item, a JPEG's extended XMP, an IPTC character set other than UTF-8/Latin-1: none is a vector case, none was
    read by any fixture; filed as inbox #1141. A value whose bytes are not valid UTF-8 in Apple's creation date fell back to
    the `mvhd` time in the core where the web's fatal decoder rejected the read — kept, the caller's outcome the same.
  - **D9 — A refused read rejects; a damaged block keeps what was read.** The web's readers rejected for a read the browser
    refused and answered none for a malformed header; the core's `ExifReader` swallowed both. Now a refusal anywhere rejects
    (`ExifReader.read` notes a read's failure and rethrows it), damage keeps what was read — the package's `media.test.ts`
    holds a refusal at the head and inside a JPEG's walk.
  - **D10 — The gate** (phase 5, Node 22.23.4, macOS arm64, Kotlin 2.4.21; the machine at a load of ~9 from other
    applications, no other agent): the size rows by the package's `slice.test.ts` (`pnpm run check` in `src/packages/core-js`)
    and `scratchpad/mh/eager.py` over `vite build`'s `dist` at `3f355d862` and after; the time rows by
    `scratchpad/mh/bench/bench.mjs` (the three deleted files from git over ExifReader 4.54.0, installed offline from the
    store, against the web's adapters over the shipped module, both bundled by esbuild; the answers compared first, the
    TypeScript's XMP tags left out of the comparison since Node has no `DOMParser`); delivery by
    `scratchpad/mh/repro.py` (three `--no-daemon --rerun-tasks :composition:jsPackage` builds from a removed `dist/`).

  D8's residual, inbox #1141 (built 2026-11-23, branch `claude/media-xmp-1141`; the trigger: the slice's browser pass saw a
  `sips` HEIC's title and rating missing). The core's `ExifReader` now also reads a HEIC's or an AVIF's **XMP item**, a
  PNG's **compressed XMP `iTXt`** and its **raw EXIF and XMP profiles** (`tEXt`, `zTXt`, `iTXt`), and a JPEG's **extended
  XMP**; three fixtures (`exif-sample-xmp.heic`, `-compressed.png`, `-extended.jpg`, `scripts/make_exif_fixture.py`) and
  37 tag cases (`… (#1141)` in `v1-vectors.json`, 141 in all) hold it, and `ExifReaderDamageTest` cuts each fixture at
  every length and flips bytes in it a thousand times — the reader answers every time. Its gate, against `855f0aa72`:

  | Criterion | Measured | Bound |
  |---|---|---|
  | size: the platform module, every entry | 67,178 → 68,783 B gz (+1,605) | the running budget, 80,094 |
  | size: the eager set | +1,508 B gz (659,325 → 660,833: the Kotlin in the `review` chunk +1,347, `main` +169 for the inflater); the slice's +9,694 with it +11,202 | 14,427 at R (the slice's) |
  | size: any lazy chunk | +10 B gz at most (`media`) | ≤ 1,024 |
  | time (Node 22, the slice's bench, its old reader now over happy-dom's `DOMParser` so its XMP is compared; median of 15 after 3 warm-ups, three runs) | `all` over 50 HEICs with an XMP item 0.33–0.40× (≤ 2.6 ms), `xmpDate` over them 0.42–0.49×; `all` over 50 PNGs with a compressed raw EXIF profile and XMP 0.40–0.42× (≤ 11.4 ms); `all` over 50 JPEGs with extended XMP 0.28–0.29×; the slice's rules unchanged (`imageSizeFromHeader` 1.36–1.41×, `date` over JPEGs 0.94–1.04×, the videos 0.83–1.24×) | ≤ 2.5× and ≤ 100 ms |
  | answers | the HEIC: identical to the library's; the PNG: the library read the raw EXIF profile, not the compressed XMP; the JPEG: the library lost the standard packet's `CreatorTool` beside the extended one (D11); each new fixture's XMP as `exiftool` 13.44 reads it, and a `sips` HEIC's (`exif-sample.jpg` converted) and an `exiftool`-split extended JPEG's through the built package | — |
  | tests | bitten (D14) | — |

  - **D11 — What the library read that the core did not, measured rather than remembered.** *Q:* D8 listed four forms from
    the library's documentation. *Evidence:* the bench's old reader (ExifReader 4.54.0, the deleted `exifTags.ts`) over the
    three fixtures under happy-dom's `DOMParser`: it read a HEIC's XMP item (its answer equals the core's now); a PNG's raw
    EXIF profile in a `zTXt` (its EXIF tags present), but **not** its compressed XMP `iTXt` (its finder skips the
    compression flag and parses the deflated bytes as XML: no XMP tag); a JPEG's extended XMP, its chunks concatenated in
    file order under any GUID, and on the fixture without the standard packet's `CreatorTool` (`exiftool` reads it). The
    IPTC character set: the web asked the library for `iptc: ['Object Name']` alone, so the `1:90` record that names the
    set was never parsed — the library's answers were the core's (UTF-8, else Latin-1); nothing was lost. *Choice:* the
    HEIC's item, the raw EXIF profile and the extended XMP restore what the library read; the compressed XMP `iTXt` and the
    raw XMP profile are read too though the library did not — the PNG specification's own form, and the inflate is there.
    The extended XMP is read as Adobe's XMP specification (part 3, 1.1.3.1) writes it, not as the library did: only the
    chunks under the GUID the standard packet's `xmpNote:HasExtendedXMP` names, placed by their offsets, all of the full
    length they state or none; the standard packet must scan, and the extended one's values win where it has one (the
    later part of one document). A HEIF item in the `meta`'s `idat` (construction 1) is read, the Exif item's too. *Left*
    (inbox #1185): an XMP packet in a TIFF's tag 700 (the library read it, remembered from its source, `exif-reader.js`'s
    application notes — no fixture); a PNG's raw IPTC profile; an IPTC character set other than UTF-8/Latin-1 (nothing a
    user had); a HEIF item with a `content_encoding`. *Cost:* a JPEG whose extended chunks the library joined under a
    wrong GUID or out of order now reads its standard packet alone.
  - **D12 — The web's inflate is the browser's, handed in.** *Q:* the core inflates by `blob/inflate`, an `expect` whose
    JavaScript `actual` is `NotOnTheWeb`'s refusal, which no façade may reach (the slice check). *Options:* (a) an inflate in
    common Kotlin (RFC 1951 by hand, every platform's); (b) the platforms' zlib, the web's being `DecompressionStream`,
    handed to the reader. *Choice:* (b): `ExifReader.read(file, inflater)`, the apps' and the tests' overload passing
    `blob/inflate`, the façade's `exifTags(file, scope, inflater)` the web's `MediaInflater` (`shared/image/mediaFile.ts`'s
    `mediaInflater`: the stream read a chunk at a time, given up past the cap); decision 1 keeps a browser's primitive the
    web's, and no inflate of our own is to be held to RFC 1951. *Evidence:* the slice check's forbidden list stays empty for
    `@veyq/core-js/media`; the vectors' bad, truncated and 6 MiB streams answer alike on the JVM, natively, under Node's
    zlib (the package) and under `DecompressionStream` (the web). *Cost:* a third parameter; a zlib stream followed by junk
    is refused by `DecompressionStream` and accepted by zlib — no vector case carries one.
  - **D13 — What a read may inflate and join.** A compressed text is inflated only where its keyword is one read
    (`XML:com.adobe.xmp`, `Raw profile type exif`/`xmp`, any case); one read inflates at most `SLICED_MAX` (4 MiB) in all,
    the rest of the cap handed to each inflate; a JPEG's walk keeps at most 1,024 extended chunks, their packet at most
    4 MiB; a HEIF item's extents at most 4 MiB, read by their extents alone (the vectors' item 4 MB into a file: ≤ 256 KiB
    read). A raw profile's stated length is not trusted (its digits are counted, as the library did), an odd count none.
  - **D14 — The bites** (five mutations at once, one per rule, restored with `/bin/cp -f`): the XMP item's type not
    matched, a compressed `iTXt` refused, the raw EXIF profile's keyword misspelt, the GUID not checked, the standard
    packet's value winning the merge — the same 14 cases red in the core's `MediaVectorsTest`, the package's
    `media.test.ts` and the web's `media.vectors.test.ts` (6, 2, 2, 1 and 3 of them), and the web's
    `readExif.fixture.test.ts` red for the HEIC and the PNG fixture.

  D11's residual, inbox #1185 (built 2026-11-24, branch `claude/media-1185`; the trigger: what the row named, measured
  against the writers a user has — macOS's `sips`, exiftool 13.44, ImageMagick 7 — rather than remembered). The core's
  `ExifReader` now reads a TIFF block's own **XMP** (IFD0's tag 700, `XMLPacket`) and **IPTC** (tag 33723, `IPTC-NAA`)
  where the container carries none of its own, walks a **bare TIFF by its IFDs' offsets** past its first MiB (macOS and
  ImageMagick write IFD0 behind the pixels), and reads a PNG's **raw IPTC profile** (`Raw profile type iptc`) in
  exiftool's and ImageMagick's forms; two fixtures (`exif-sample-xmp.tif`, `exif-sample-iptc.png`,
  `scripts/make_exif_fixture.py`) and 24 tag cases (`… (#1185)`, 165 in all) hold it, and `ExifReaderDamageTest` cuts and
  flips both fixtures as it does #1141's three. Its gate, against `7bb824054`:

  | Criterion | Measured | Bound |
  |---|---|---|
  | size: the platform module, every entry | 69,251 → 70,175 B gz (+924) | the running budget, 80,094 |
  | size: the eager set | +864 B gz (661,319 → 662,183: the Kotlin in the `review` chunk +852, `main` +13); the slice's +9,694 and #1141's +1,508 with it +12,066 | 14,427 at R (the slice's) |
  | size: any lazy chunk | +5 B gz at most (`PersonMemories`) | ≤ 1,024 |
  | time (Node 22, #1141's bench with three corpora more, its old reader over happy-dom's `DOMParser`; median of 15 after 3 warm-ups, three runs at a load of 9–13 from other agents) | `all` over 50 TIFFs with IFD0, its XMP and its IPTC behind 4 MB of pixels 1.10× (≤ 8.6 ms; the old reader read nothing of them), `date` over them 1.51–1.67× (≤ 9.6 ms); `all` over 50 TIFFs with IFD0 and tag 700 at the head 0.66–0.68× (answers identical); `all` over 50 PNGs with a raw IPTC profile behind 3 MB of `IDAT` 0.54–0.57× (≤ 8.9 ms); the slice's rules unchanged (`imageSizeFromHeader` 1.34–1.41×, `date` over JPEGs 0.99–1.36×, `all` 0.31–0.32×, `date` over HEICs 0.63–0.64×) | ≤ 2.5× and ≤ 100 ms |
  | answers | the writers' own files through the built package (D15): a 4.5 MB `sips` TIFF its title, rating, IPTC object name and EXIF (before: nothing), ImageMagick's and exiftool's 4.5 MB TIFFs their XMP (before: nothing), the small ones their XMP and IPTC (before: a `sips` one its EXIF alone), the three PNGs their IPTC object name; every new fixture as `exiftool` 13.44 reads it | — |
  | tests | bitten (D20) | — |

  - **D15 — What the library read, measured.** *Q:* the row said the library read a TIFF's tag 700 ("remembered from its
    source") and a PNG's raw IPTC profile. *Evidence:* the slice's old reader (ExifReader 4.54.0, the deleted
    `exifTags.ts`, under happy-dom's `DOMParser`) and the core at `7bb824054`, through the built package, over each
    writer's file (`scratchpad/m1185/probe-old.mjs`, `probe.mjs`): the library read tag 700 and 33723 of a TIFF within its
    first MiB — a small `sips`, exiftool or ImageMagick TIFF — and **nothing** of a camera-sized one from `sips` or
    ImageMagick (the web handed it the first MiB, and IFD0 lies behind the pixels: 4,500,450 of 4,505,644 bytes); and **no
    PNG raw IPTC profile in either writer's form** — ImageMagick's is named `iptc` but holds the datasets bare, which the
    library's resource walk refuses, exiftool's holds Photoshop's resources but is named `IPTC profile`, which its header
    check refuses: the row's "the library read it" was wrong. The core read none of these, of a small `sips` TIFF its
    EXIF alone. *Choice:* tag 700 and 33723 restore what the library read; the walk by offsets and the PNG's IPTC profile
    read what neither did, the forms every TIFF macOS exports and every PNG exiftool tags carry.
  - **D16 — A bare TIFF walked by its offsets.** *Q:* IFD0, its values and the sub-IFDs may lie anywhere in the file.
    *Options:* (a) read more of the head (no bound holds a file whose IFD0 is last); (b) a walk over a `suspend` reader
    (every TIFF read twice over, the in-memory blocks of a JPEG or a HEIF too); (c) the walk unchanged over the windows it
    holds, noting each range it misses, run again once they are read. *Choice:* (c): the head stays the first
    `TIFF_PREFIX` (1 MiB); each miss is read as a window of `WINDOW` (128 KiB) (or the value's length) from where it is wanted, at most 64
    windows and `SLICED_MAX` (4 MiB) bytes beyond the head, in at most 8 rounds; each round reads what the last did and more, so
    the last one's tags stand. IFD offsets are `Double`s now (a TIFF past 2 GiB). *Evidence:* the vectors' IFD0 behind 2 MB
    of pixels, an Exif IFD and a tag 700 behind them (each ≤ 1 MiB + 2 windows read); the probe's 4.5 MB `sips` TIFF read
    in 1,053,782 bytes. *Cost:* a walk run up to 8 times (an IFD is ≤ 12 KB), a TIFF whose tags lie in more than 64
    places reads what the first 64 hold.
  - **D17 — A TIFF block's own XMP and IPTC give way to the container's**, as the library gave a carried block way: tag
    700 and 33723 are read from IFD0 alone (the Exif IFD's are not the image's), in any TIFF block — a bare TIFF, a JPEG's,
    a PNG's, a WebP's or a HEIF's Exif — and applied once the container is read, unless it holds an XMP block of its own
    (a JPEG's XMP segment, even one that does not scan, as the library's did; a PNG's XMP `iTXt` or raw XMP profile; a
    WebP's `XMP `; a HEIF's XMP item) or an IPTC block (a JPEG's `APP13`, a PNG's raw IPTC profile). Tag 700 as `BYTE` or
    `UNDEFINED` (the library's number arrays), not `ASCII`; tag 33723 also as `LONG`, its bytes as they lie (exiftool
    writes it so; the library cut each `LONG` to a byte). A value up to `SLICED_MAX` (4 MiB), not the 64 KiB of an ordinary tag: a
    Lightroom packet with its develop history passes that.
  - **D18 — A PNG's raw IPTC profile.** The keyword in any case, as the other raw profiles; the profile's name line any
    line of at most 64 bytes (`iptc`, ImageMagick's; `IPTC profile`, exiftool's); its bytes Photoshop's resources (an
    `8BIM` first: exiftool) or the datasets bare (ImageMagick). ImageMagick's `Raw profile type 8bim` beside it is not
    read: it writes the `iptc` one whenever it has IPTC.
  - **D19 — Left, with no writer seen** (inbox #1240): an IPTC character set other than UTF-8 or Latin-1 — `sips` writes
    `1:90` as UTF-8 (ESC % G), exiftool names UTF-8 or nothing, and a set the record does not name is read as before
    (UTF-8, else Latin-1); and a HEIF item with a `content_encoding` — libheif's metadata compression is an option its
    encoder leaves off, Apple's and Android's HEICs carry the XMP plain (#1141's `sips` HEIC), and such an item's bytes
    do not scan as XMP, so it reads as none rather than as something wrong.
  - **D20 — The bites** (each mutation alone on the JVM, then all at once through the package and the web, restored with
    `/bin/cp -f`): tag 700 not read (B1: 8 vector cases red and the damage test's fixture check), the walk run once
    over the head alone (B2: 3), the carried XMP read over the container's own (B3: 3), tag 33723 refused as `LONG` (B4:
    1), the raw IPTC profile's keyword misspelt (B5: 4 and the fixture check), the name line held to 4 bytes (B6: 2 and
    the fixture check), the resources read as bare datasets (B7: 2 and the fixture check), a carried value held to 64 KiB
    (B8: 1) — in the core's `MediaVectorsTest`; all eight at once (B1 hiding B3's three) 13 cases red in the package's
    `media.test.ts` and the web's `media.vectors.test.ts` alike, and the web's `readExif.fixture.test.ts` red for both new
    fixtures.

- **The export archive's slice** (decision 7.2's sixth, built 2026-11-23, branch `claude/export-archive-slice`). A download's
  store-only ZIP — written (`export/ZipWriter`), its exact length before the first byte (`ZipWriter.length`), read and
  distrusted (`export/ZipReader`), the CRC-32 both run (`export/Crc32`) — and the manifest's check and payload walk
  (`export/ManifestRules`, new, over `json/JsonAccess`) are the platform's alone; the web draws them through
  `@veyq/core-js/export` (`jsMain/…/export/web/`) behind its `core/export/zip.ts` and `manifest.ts`, which hold no rule — the
  sink's close and abort, the `Blob`'s slices, an entry as a lazy `Blob`, the types, the errors the web throws and the
  build's release major stay. `contracts/platform/export/v1-vectors.json` holds every side — 7 CRC, 9 DOS-stamp, 42
  written archives byte for byte (hex or SHA-256, the sink's write count, each file's CRC), 8 lengths at the real 4 GiB and
  65,535-entry limits, 43 reads of written and patched archives (entries, each stream's chunk lengths, what opening may
  read) and their refusals, 79 manifests and 7 payload walks — (`ArchiveVectorsTest`, the writer; the package's
  `export.test.ts`; the web's `archive.vectors.test.ts`). Its gate (each number against `7a75535d2`):

  | Criterion | Measured | Bound |
  |---|---|---|
  | size: the platform module, every entry | 55,190 → 65,831 B gz; own code 10,641 = 1.90× the 5,608 B gz deleted (2.47× the 4,316 net of the adapters' 1,292) | 60,678 + 3.0 × 5,608 = 77,502 |
  | size: the eager set | +6,090 B gz (651,396 → 657,486: the Kotlin in the `review` chunk +10,043, `main` −3,978 for the ZIP and the manifest it no longer carries) | 16,824 at R |
  | size: any lazy chunk | +69 B gz at most (`MemoryDetailHost`); the digest worker's chunk unchanged | ≤ 1,024 |
  | time (Node 22, a download of 100 folders, 1,000 files of 16 KiB and 8 of 2 MiB in 256 KiB frames — 33.3 MB written —, a 10,101-node manifest; median of 15 after 3 warm-ups, three runs) | writing the archive 0.88–0.91× (≤ 71.4 ms); `zipLength` of its plan 1.74–1.96× (≤ 0.32 ms); opening it 1.45–1.50× (≤ 15.9 ms); every entry read back, its CRC checked, 0.91–0.94× (≤ 89.9 ms); `parseManifest` 1.17–1.20× (≤ 20.4 ms); `collectPayloads` over every node 1.64–1.75× (≤ 1.85 ms); the archives byte-identical, every answer identical | ≤ 2.5× and ≤ 100 ms |
  | delivery | three `--no-daemon --rerun-tasks :composition:jsPackage` builds from a removed `dist/`, one digest (`261ccbe0…`); `verify:csp`, `verify:license-sources` green (51 packages, each with its source) | identical |
  | tests | the web's `zip.test.ts` stays, through the adapters, and each of its cases is a vector case too but two (the ZIP64 limit at 20,000 bytes, the length above the payloads' sum); bitten — a CRC finished one bit off, a directory offset one byte off, an entry buffered whole before its first write: each red in the core's, the package's and the web's vector tests (D8) | — |
  | behaviour | the web's tests pass with their imports unchanged (592 files, 7,929 tests; `importCases.test.ts` over the Python-written import cases among them); a download and its import in a browser, before and after, are owed (inbox #1150) | identical |

  The slice's decision log, kept by its implementer (question, options, choice and why, evidence, cost):
  - **D1 — The slice's set: the writer, the reader, the length and the manifest's check; the CRC-32 twice.** *Q:* what
    TypeScript leaves? *Choice:* `zip.ts`' writer, reader and `zipLength`, `manifest.ts`' check, payload walk, version-1 path
    rule, `applicationOf` and facts; `crc32.ts` stays as the digest worker's own (D2). *Evidence:* measured,
    `node scratchpad/ea/measure-ts.mjs <worktree> scratchpad/ea/old` (each set's exports bundled alone with esbuild, every
    other import external, minified, gzip -9; the old files from git): `zip.ts` 10,776 B min / 4,013 B gz, `manifest.ts`
    4,097 / 1,805, **together 14,939 B min / 5,608 B gz**; `crc32.ts` 379 / 265 (ADR 0155's row of 5,726 counted it — 5,739
    measured with it); the adapters 2,846 / 1,292, so 4,316 B gz net. *Cost:* two CRC-32s, held to one set of cases.
  - **D2 — Lazy or eager, and a module of its own?** *Q:* the ADR's standing question for slices 4–6. *Evidence:* measured
    on the production build at `7a75535d2` (`vite build` under Node 22, `scratchpad/ea/eager.py`; the rules located by their
    messages in the minified chunks): the writer, the reader and the manifest's check all sat in `main` — eager, reached by
    `LevelView`'s static import of `importSubtree`'s `isDownloadArchive` —; the CRC also in the lazy `digest.worker` chunk
    (3,363 B gz), which sums an import payload's CRC beside its SHA-256 (inbox #557). For the page a module of its own buys
    nothing. For the worker: one function of the platform module bundled alone costs 50,198 B gz
    (`scratchpad/ea/one.mjs`, `foldArchiveName` from `@veyq/core-js/files`), one export of the standard library's file
    alone 17,551 B gz — a module of its own pays that floor unless it imports nothing of the standard library, at the price
    of a Gradle module, the module graph's guard and a package entry, for 26 lines. *Choice:* the platform module for the
    page; the worker keeps `crc32.ts` as its own primitive beside its SHA-256 (decision 1 keeps the workers and the raw
    primitives the web's), held to the vectors' `crc32` cases (`archive.vectors.test.ts` runs them over it and over the
    core). *Cost:* a CRC-32 in two languages (265 B gz); inbox #1152 names when to look again.
  - **D3 — The entry is `@veyq/core-js/export`.** `jsMain/…/export/web/`, the core area's name, read by the web's direction
    guard as core's; its code the module file, its types `dist/export.d.mts`. The writer, the reader and an entry's read cross
    as opaque handles (`ZipWriterHandle`, `ZipReaderHandle`, `ZipReadingHandle`), as the tree's facts do.
  - **D4 — Streaming over the web's promises, pulled, without kotlinx.coroutines.** *Q:* the web's sink awaits each write
    (back-pressure from the download route or a file), its chunks arrive by an async iterator, an entry is read by `Blob`
    slices. *Choice:* the writer's sink suspends, a file's chunks are pulled (`ZipChunksShape.next`), an entry's read is pulled
    too (`ZipReader.reading(name).next()`, the CRC checked after the last chunk, as the web's generator did) — each the web's
    promise awaited by `suspendCoroutine`, answered with `startCoroutine` (media D4's intrinsics). The apps' API moved with
    it: `ZipWriter`'s sink suspends (`NodeExport` wraps its own), `addDirectory` and `finish` suspend, `ZipFailure` left for
    `ZipReader.ZipError` and its new `SIZE_MISMATCH`. *Evidence:* every write case's sink takes as many writes as the web's
    did (one per record and per chunk); the bite of D8. *Cost:* a promise per chunk each way — measured, writing 0.88–0.91×.
  - **D5 — The JSON seam's read-only form.** *Q:* the manifest's check walks an untrusted document of up to 200,000 nodes,
    `JSON.parse`'s on the web, kotlinx's in the apps; the parked Expenses move built a tree (`JsonValue`) every value is copied
    into. *Options:* (a) that tree, (b) a read-only view asked of the platform's own value (`json/JsonAccess`:
    `KotlinxJsonAccess`, `JsJsonAccess`). *Choice:* (b) — no copy of a large document to check it, and the walk hands back
    the web's own objects (`manifestPayloads`, held by identity in the package's test). *Evidence:* `parseManifest` 1.17–1.20×.
    *Cost:* two forms of the seam once the parked tree lands — the tree for a format read and written, the view for a
    check; inbox #1151.
  - **D6 — No `Long` on the hot path, no `Regex`.** The CRC-32 runs on an `Int` (the old `LongArray` table ran an emulated
    `Long` per byte on JavaScript), its `Long` forms kept for the apps; offsets and sizes stay `Long` (a few operations per
    record: writing measured 0.88–0.91×); the manifest's character classes are hand-written; the DOS stamp is arithmetic
    (`kotlin.time.Instant`'s ISO text is no longer pulled into the module).
  - **D7 — Where the core and the web answered apart, the web's answer won** (ADR 0154's rule; each a vector case): a name's
    lone surrogate is written as U+FFFD (the JVM's encoder wrote `?`; `ZipWriter.utf8` is every platform's now); a declared
    size is checked before the path (`ZipWriter.declaredSize`, the web's order); a JSON number is read as JavaScript reads
    it, an infinite one too (the apps' `Manifest.number` dropped it before the check, so a reference with such a size was no
    reference). One the other way, unreachable after the check: `applicationOf` of a type without a dot — the web dropped its
    last character, the core keeps it whole. *Evidence:* `:core:jvmTest --tests '*ArchiveVectorsTest*'` green and the file
    rewritten by the core byte for byte (`VEYQ_WRITE_VECTORS=1`).
  - **D8 — A wrong byte is caught everywhere, bitten:** a CRC finished one bit off (`Crc32.finish` `xor 1`), a directory
    record's offset one byte off, and an entry buffered whole before its one write — each turned the core's
    `ArchiveVectorsTest` red (its write and read sections; the CRC's too for the first), the package's `export.test.ts` (34,
    34 and 5 of 92) and the web's `archive.vectors.test.ts` (34, 34 and 5 of 91); each restored with `/bin/cp -f` from a
    copy, the tree clean after.
  - **D9 — The gate** (Node 22.23.4, macOS arm64, Kotlin 2.4.21; the machine at a load of ~9–12 from other applications, no
    other agent): the size rows by the package's `slice.test.ts` (`pnpm run check` in `src/packages/core-js`) and
    `scratchpad/ea/eager.py` over `vite build`'s `dist` at `7a75535d2` and after; the time rows by `scratchpad/ea/bench/bench.mjs`
    (the deleted files from git against the web's adapters over the shipped module, both bundled by esbuild; the archives
    and every answer compared first); delivery by `scratchpad/ea/repro.py`.

- **The music order and fades' slice** (decision 7.2's seventh and last, built 2026-11-23, branch `claude/music-fades-slice`).
  The order a music queue plays in (`audio/PlayOrder`: shuffled and endless, a round never starting with the track that
  ended the one before, previous restarting after 3 s, a dropped track leaving every round, the platform's unbiased draw
  `randomIntOf`) and where and how long a crossfade runs (`audio/Fades`: 5 s, 1 s on a press, a third of a short track, what
  is left after a seek) were the core's already, ports of the web's (ADR 0142 decision 1); they are now every client's one
  rule, the web drawing them through `@veyq/core-js/audio` (`jsMain/…/audio/web/`) from its player, and `order.ts` and
  `fades.ts` are gone with their tests. `contracts/platform/audio/v1-vectors.json` holds every side — 10 draws from scripted
  words, 21 queues under scripted randomness (790 draws, each with the `n` it must be asked with; 1,012 steps of `current`,
  `next`, `peek`, `previous`, `drop`, `size`) and 14 spans' fades — (`MusicVectorsTest`, the writer; the package's
  `audio.test.ts`; the web's `music.vectors.test.ts`). Its gate (each number against `31947d000`):

  | Criterion | Measured | Bound |
  |---|---|---|
  | size: the platform module, every entry | 65,831 → 67,178 B gz; own code 1,347 = 1.56× the 864 B gz deleted | 77,502 + 3.0 × 864 = 80,094 |
  | size: the eager set | +557 B gz (657,486 → 658,043: the Kotlin in the `review` chunk +1,212, `main` −576 for the order and the fades it no longer carries) | 2,592 at R |
  | size: any lazy chunk | +24 B gz at most (`MemoryDetailHost`) | ≤ 1,024 |
  | time (Node 22, the player's calls, median of 15 after 3 warm-ups, three runs) | a queue of 1,000 tracks started and played through three rounds, peeking before each step, 0.82–0.89× (≤ 0.29 ms); of 10,000 tracks 0.75–0.77× (≤ 2.8 ms; 4.2× before the optimisation round, D6); 1,000 tracks dropped one by one 1.26–1.31× (≤ 10.5 ms); the four fades over 10,000 spans 1.02–1.05× (≤ 0.21 ms); every answer identical | ≤ 2.5× and ≤ 100 ms |
  | delivery | three `--no-daemon --rerun-tasks :composition:jsPackage` builds from a removed `dist/`, one digest (`c4bc8abe…`); `verify:csp`, `verify:license-sources` green (51 packages, each with its source) | identical |
  | tests | the deleted `order.test.ts` and `fades.test.ts` each assertion a vector case (Fisher–Yates over always-0 and always-(n−1) draws, a round's permutation and its edge over 60 seeded rounds of 2–5 tracks, a start track, a single track, previous at 2 s and 4 s, peek, drop and the empty queue; the song, the short and the tiny track and the seek), the 1,000-round edge also over the platform's own randomness in the web's test, the core's `OrderTest` and `FadesTest` kept (the latter now the apps' `Long` clock); bitten — a third read as a quarter, a round's swap partner drawn without its `1 +` (D7) | — |
  | behaviour | the web's tests pass with the player's and the bar's unchanged (591 files, 7,961 tests); a browser pass of a Memory's music (a queue started, next and previous, a crossfade, a seek into the fade) and the apps' music passes are owed (inbox #1160) | identical |

  The slice's decision log, kept by its implementer (question, options, choice and why, evidence, cost):
  - **D1 — The slice's set: the two rule files whole; the player stays.** *Q:* what TypeScript leaves? *Choice:* `order.ts`
    (`PlayOrder`, `nextRound`, `shuffled`, `cryptoRandomInt`, the 3 s constant) and `fades.ts` (the four fades, the two
    constants), with no adapter left behind: the player asks the façade for each step and fade. `player.ts` stays — its
    decks, timers and gain ramps are what a browser does, and the core's twin of it (`audio/MusicPlayer`) is a model with
    flows, decision 8's gate (inbox #1161). *Evidence:* measured, `node scratchpad/mf/measure-ts.mjs <worktree>
    scratchpad/mf/old` (each set's exports bundled alone with esbuild, every other import external, minified, gzip -9; the
    old files from `31947d000`): `order.ts` 1,572 B min / 711 B gz, `fades.ts` 342 / 228, **together 1,905 B min / 864 B gz**
    (ADR 0155's row said 859).
  - **D2 — Lazy or eager, and a module of its own — or left where it is?** *Q:* the standing question, sharpest for the
    smallest slice. *Evidence:* measured on the production build at `31947d000` (`vite build` under Node 22,
    `scratchpad/mf/eager.py`; the player located by its MediaSession action names in the minified chunks): the player, the
    order and the fades sat in `main` — eager, the bar is the shell's. A module of its own would pay the standard library's
    floor for 864 B gz of rules (17,551 B gz for one export of its file alone, remembered from § The export archive's slice
    D2). Left in TypeScript, the eager set would have stayed 557 B gz smaller (measured: +557 after). *Choice:* the platform
    module, inside R: two implementations of this rule had already answered apart (D4), which is what decision 1 removes,
    and the cost is the smallest any slice measured. *Cost:* 557 B gz eager.
  - **D3 — The entry is `@veyq/core-js/audio`.** `jsMain/…/audio/web/`, the core area's name, read by the web's direction
    guard as core's; its code the module file, its types `dist/audio.d.mts`. The order is state kept across calls, so it
    crosses as an opaque handle (`PlayOrderHandle`), read by `playOrderCurrent`, `…Next`, `…Peek`, `…Previous` (a
    `{ restart, id }`), `…Drop`, `…Size`; the randomness stays the caller's `(n) => number` — the player's `env.random`, by
    default the façade's `cryptoRandomInt` (`randomIntOf` over noble's `randomBytes`, built per call so the module keeps no
    top-level initializer). A span is the web's own `{ startMs, endMs }` (`SpanShape`), an unknown end `Infinity`.
  - **D4 — Where the core and the web answered apart, the web's answer won** (ADR 0154's rule; each a vector case): the
    fades run on `Double`s — a third of a span is not rounded (a 7,000 ms track fades over 2,333.33 ms, the core said 2,333),
    a file's duration in seconds times 1000 is fractional, an unknown end is infinite (the core said `Long.MAX_VALUE − 5000`
    for where it fades out); `previous` reads a fractional position (3,000.5 ms is more than 3 s: the core's `Long`
    truncated it to 3,000 and went back); the draw reads a word's four bytes little-endian, as the web's `Uint32Array` does
    (the core read them big-endian: equally uniform, but not one rule). The apps' player keeps its `Long` clock through
    `Span`, whose four functions answer the `Double` rule truncated toward zero, an infinite answer `Span.UNKNOWN_END`
    (`FadesTest`). *Evidence:* `:core:jvmTest --tests '*MusicVectorsTest*'` green and the file rewritten by the core byte for
    byte (`VEYQ_WRITE_VECTORS=1`). *Cost:* an app's fade-out point of a short track can move by a millisecond; its draws
    read another word from the same bytes.
  - **D5 — The randomness is part of the contract.** *Q:* the order is random; what can a vector hold? *Choice:* a case
    scripts every draw as `[n, value]`, and a client fails the case where it asks another `n`, makes a draw the case does
    not list or leaves one unmade — so Fisher–Yates' call order (from the end, `random(i + 1)`) and the edge swap's
    `1 + random(n − 1)` are held, not only the outcome's properties; the draw itself is held by scripted words through
    `crypto.getRandomValues` (the package's and the web's tests spy on it). The draw's domain is a queue's length: `n` up to
    2^31 − 1 (a Kotlin `Int`), where the web's took any number up to 2^32.
  - **D6 — The optimisation round: a round read forward.** *Evidence:* the first build took each track with
    `removeAt(0)`, which on JavaScript moves the whole round every step — 10,000 tracks through three rounds 4.21–4.26× the
    web's `shift()` (14.4 against 3.4 ms; inside 100 ms, outside 2.5×). *Choice:* the round is read from a head index and
    rebuilt only by a drop, by a loop: `subList` pulled 364 B gz of the standard library's list views (measured, the slice
    check). After: 0.75–0.77×. No `Long` and no `Regex` on the path (the draw on `Double`s, exact below 2^53).
  - **D7 — A wrong rule is caught everywhere, bitten:** a short track's fade a quarter instead of a third (`Fades.kt`
    `length / 4`) turned the core's `MusicVectorsTest` `fades` red (7 cases), the package's `audio.test.ts` (7 of 46) and the
    web's `music.vectors.test.ts` (7 of 46); a round's swap partner drawn without its `1 +` (a round may start with the
    track that ended the last) the core's `order` red (10 cases), the package's (10 of 46) and the web's (11 of 46: the
    1,000-round test over the platform's own randomness too). Each restored with `/bin/cp -f` from a copy, the tree clean after.
  - **D8 — The gate** (Node 22.23.4, macOS arm64, Kotlin 2.4.21; the machine at a load of ~5–6 from other applications, no
    other agent): the size rows by the package's `slice.test.ts` (`pnpm run check` in `src/packages/core-js`) and
    `scratchpad/mf/eager.py` over `vite build`'s `dist` at `31947d000` and after; the time rows by
    `scratchpad/mf/bench/bench.mjs` (the deleted files from `31947d000` against the shipped module, both bundled by esbuild,
    the same seeded randomness on both sides; every answer compared first); delivery by `scratchpad/mf/repro.py`.
  - **Decision 7.2's list is built.** Seven slices moved the platform's rules the web and the apps both answered — the node
    tree, the share links, the rights and plans, the file facts, the media headers, the export archive, the music order and
    fades —, the platform module from the search's 27,943 to 67,178 B gz against a running budget of 80,094. What the list names as no slice
    (the formats, the notices, the slug folding, the URL fragment rules) stays as it says; next is decision 7.3, Expenses
    first, whose move is parked below.

- **Expenses' move** (decision 7.3's first application, built 2026-11-24, branch `claude/expenses-core-resume`,
  the work order cr-web-expenses-on-the-core, deleted at harvest). The web's Expenses rules — the entry
  format's reader and writer, the revision codec, the held revisions and the ledger check, the ledger, money as text and
  as typed, the currency digits, the day's rate table and cross rate — are `:apps:expenses`' (`Format`, `Codec`,
  `Revisions`, `LedgerCheck`, `Ledger`, `Money`, `Big`, `Amounts`, `Currencies`, `ExchangeRateTable`), and the web draws them
  through `@veyq/core-js/expenses` (`apps/expenses/src/jsMain/…/expenses/web/`), its code the application's own module file
  (`kotlin_veyq_mobile_apps_expenses.mjs`), which imports the platform's. `src/web/src/features/expenses/domain/` keeps the
  types the web draws by and hands the core what it asks — one `PotRevisions` handle per held list (`revisions.ts`), the
  core's refusals as the web's error classes; `money.ts` and `amounts.ts` are gone. What stays TypeScript, with a Kotlin
  twin, is the next Expenses move: the entry form, the Pot's meta, the writes, the re-key, the receipts' rules, import and
  export. Its gate (each number against `1a30aed00`, the tree before the move, its own build):

  | Criterion | Measured | Bound |
  |---|---|---|
  | size: Expenses over every platform entry (the package's `slice.test.ts`, gross, `@noble/*` external) | +20,838 B gz | ≤ 25,600 |
  | size: the platform module, every entry | 79,637 B gz (70,173 before: +9,464 of platform code Expenses' façade needs — the suite's `V1`, `Contexts`, `Pad`, money's `Shown`, the JSON tree — and of the standard library it keeps) | the running budget, 80,094 (the owner, 2026-11-22) |
  | size: the web's eager set and Expenses' chunks together (`vite build`, gzip -9 per chunk) | +25,409 B gz all in (eager +26,520: `main` +16,107, `review` +10,533; Expenses' lazy chunks −1,111), the deleted TypeScript counted; of it ~9.5 KB the platform's growth above | ≤ 25,600 for Expenses alone; met all in |
  | size: any other lazy chunk | +17 B gz at most (`media`) | ≤ 1,024 |
  | time: `buildLedger`, the 10,010-revision Pot (Node 22.23.4, median of 15 after 3 warm-ups) | 89.4–89.7 ms against the TypeScript's 42.3–42.5 (2.11×); the handle's one read of a new list 44–47 ms apart; the machine at a load of ~30 from other agents' builds, two runs | ≤ 2.5× and ≤ 100 ms |
  | time: `checkLedger`, the same | 5.3–5.9 ms against 4.9–5.3 (1.11×) | ≤ 2.5× and ≤ 100 ms |
  | answers | identical (one digest over balances, settle-up, the category overview and the check) | identical |
  | behaviour | the web's tests pass with their imports changed; the twins' tests of the ledger, the heads, the codec, the member participants and the amounts stay and pass through the glue unchanged but one line (codec's padding case reads the plaintext's length without the deleted writer) | identical |
  | delivery | three clean builds (`dist/` removed, `./gradlew --no-daemon --rerun-tasks :composition:jsPackage`), one digest over `dist/`'s 225 files: `e8feaecc…562372ce`; `verify:csp`, `verify:license-sources` green | identical |
  | tests | a +1 in `Ledger.settleUp` turns 18 cases of the package's `expenses.test.ts` and 15 of the web's `vectors.test.ts` red (the `ledger`, `settleUp` and Pot sections); half-even in `Money.roundHalfAwayFromZero` 3 and 3 (`conversion` #2, #5, #8) — the web's run gained the `conversion` and `rounding` sections for it: without them the half-even bite passed the web's vectors (measured first) | each red |

  The move's decision log, kept by its implementer (question, choice and why, evidence, cost):
  - *Replayed, not rebuilt.* The parked commits (`claude/p2-impl`: the vectors, the JSON seam and the pure homes, the
    façade, `Big` over `BigInt`, the timing round) were cherry-picked onto the split, `core/…/expenses` landing in
    `:apps:expenses` (git's directory renames did most), the façade in its `jsMain`, the decode runner in its own
    `jsConformance` compilation (`dist/conformance/expenses/`, 21 decode cases green through it).
  - *Where the platform code the façade needs goes: the platform.* The JSON tree (`json/JsonValue`, `JsonText`) and
    money's `Shown` are platform code every application may use; the owner's answer (2026-11-22) counts them against the
    running budget. Moving them into Expenses' module would charge them to its 25 KB and copy the platform's seam into an
    application. Measured first at 84,028 B gz, **over** the running budget: whole-program elimination keeps in the
    platform's and the standard library's files whatever any façade reaches, and a bundler drops nothing of either.
  - *The squeeze, behaviour-neutral:* a pattern runs on the platform's own engine (`text/Pattern`: `RegExp` on JavaScript,
    `Regex` elsewhere) in `Contexts`, the JSON tree's number text, `Shown`, the format, `Money`, `Amounts` and the rate
    table — Kotlin's `Regex` was ~10 KB minified of the standard library; the wire base64 is written by hand
    (`crypto/b64`, `B64Test` against the standard library's for every length) — `kotlin.io.encoding.Base64` was ~9 KB;
    `Shown.money`'s letter test is a `\p{L}` pattern, not `Char.isLetter`'s tables. 84,028 → 79,637 B gz.
  - *Times cross as the web's own `Date`*, not epoch milliseconds (the refine's decision 5): `createdAt`, `purgeAfter` in,
    `recordedAt` out. Every staying test and component reads them unchanged; the cost is one `Date` per ledger entry.
  - *One name per façade.* The composition's one declaration file merged Expenses' `EntryShape` into the tree's
    (TypeScript merges two interfaces of one name): `:composition:jsPackage` now refuses a name two façades export, and
    Expenses' shapes are `LedgerEntryShape` and `JsonValueShape`.
  - *The glue files stay as glue.* `format.ts`, `ledger.ts`, `revisions.ts`, `heads.ts`, `codec.ts` keep their names and
    types and hold no rule (no arithmetic, no validation branch, no seal), so the callers' imports did not move; the
    package is imported directly where a caller used `amounts.ts`.
  - *The JSON seam's two forms* (inbox #1151): the tree is what a format reads and writes (Expenses' revision body, the rate
    table), the read-only view (`JsonAccess`) what checks a large untrusted document in place (the export manifest). Both
    stay; decision 4's seam is both.
  - *What the TypeScript twins' tests became* (decision 2's criterion): the reader's, writer's and patterns' cases
    (`format.test.ts`) are the vectors' `read`, `decode` and `encode` sections and `ExpensesRulesTest`'s schema pins; the
    arithmetic's (`money.test.ts`) the vectors' `conversion` and `rounding`, `BigTest`, `LedgerRulesTest`; the rest stay as
    web tests over the glue. The vectors run through the package (`src/expenses.test.ts`) and through the web's glue
    (`features/expenses/vectors.test.ts`).
  - *Visible change* (the refine's decision 6): the currency picker offers the core's table (from `Intl` under ICU 78.3)
    and a new Pot's or entry's digits stop depending on the browser's ICU.
  - *Not done here:* the browser pass of the refine's phase 4 (two builds over one backend, en and de) — the tests above
    hold the behaviour; the pass is owed.

- **Depot's move** (decision 7.3's second application, built 2026-11-24, branch `claude/depot-web-core`, the work order
  cr-web-depot-on-the-core, deleted at harvest). What the web's Depot decided beside a Kotlin twin the apps already ran — what
  an opened file is (`view.ts`' decision: the bytes decide, the claim narrows, the caps, a text's frames and cut), a
  picked directory's plan (`uploadFolder.ts`' `planFolderUpload`: safe names, slug-numbered per directory, the top among the
  level's siblings) and an SVG's canvas and zoom steps (`svgRaster.ts`' `planRaster` and caps, `SvgView.vue`'s steps) — is
  `:apps:depot`'s (`FileView.decided`, `FolderUpload.planned`, `SvgView`), drawn through `@veyq/core-js/depot`
  (`apps/depot/src/jsMain/…/depot/web/`), its code Depot's own module file (`kotlin_veyq_mobile_apps_depot.mjs`), a lazy
  chunk: the file's page and the first folder upload load it, never a level alone. `contracts/depot/v1-vectors.json` holds
  every side (43 openings, 12 plans with the folding's tokens each may ask, 14 rasters, the zoom steps): `DepotVectorsTest`
  writes it, the package's `depot.test.ts` and the web's `features/depot/vectors.test.ts` answer it. What stays TypeScript,
  and why: everything that awaits I/O or keeps state (the reading and decoding, `createFolders`, `takeBackEmptyFolders`, the
  uploads, the drop box, the rows, the viewer — decision 8), and three rules with no whole Kotlin twin to draw — the rights
  matrix (inbox #1300), the uploader's naming (#1301), the export content checks over variant A's tree (#1302). Its gate
  (each number against `96a40323e`, the tree before the move, its own build):

  | Criterion | Measured | Bound |
  |---|---|---|
  | size: Depot over every platform entry (the package's `slice.test.ts`, gross, `@noble/*` external) | +3,249 B gz (its module's own 7,587 B minified) | ≤ 25,600 |
  | size: the platform module, every entry | 79,768 B gz (79,637 before: +131 — the standard library's collection members the plan reaches first, 871 B minified, and `ArchiveName.of`'s set adapter) | the running budget, 80,094 |
  | size: the web's eager set and Depot's chunks together (`vite build`, gzip -9 per chunk) | +3,460 B gz all in: eager −627 (`main` −3,392 with the folder plan gone from it, the rest rechunked), Depot's module +3,280 and `uploadFolder` +883 lazy, `FileDetail` −115 | ≤ 25,600 |
  | size: any other lazy chunk | +54 B gz at most (`MemoryDetailHost`) | ≤ 1,024 |
  | size: a link guest's first load (decision 10: the eager set plus the static closure of the chunk the share view mounts; `scratchpad` `paths.py` over both builds) | a single file's link 700,081 → 702,621 (+2,540: Depot's module); a folder's 688,697 → 688,070 (−627, the eager set alone); a drop box's 692,384 → 691,778 (−606) | the owner's number, decision 10 |
  | size: a chunk only members load (Depot's share dialogs, a drop box's page) | 4,995 → 5,011 B gz (+16) | measured, not gated (decision 10) |
  | time: `planFolderUpload`, a directory of 10,000 files (100 directories, case clashes, hostile names, slug clashes, 20 numbered siblings; Node 22.23.4, median of 15 after 3 warm-ups, the glue as it ships against the deleted TypeScript) | 10.5–10.8 ms against the TypeScript's 7.1–7.4 (1.46–1.49×), three runs at a load of ~5 from other applications | ≤ 2.5× and ≤ 100 ms |
  | time: the view's decision, the first frames of 1,000 openings (100 each of a PNG, a JPEG, an SVG, an MP4, an MP3, a PDF, a binary and three 256 KiB texts) | 57.7–58.0 ms against 57.5–58.3 (0.99–1.00×; the 256 KiB texts' validation, the platform's sniff, is most of it) | ≤ 2.5× and ≤ 100 ms |
  | answers | identical (the plan's 369,753 B of JSON; 10,000 decisions) | identical |
  | behaviour | the web's tests pass (591 files, 8,313 tests) with the twins' tests (`view.test.ts`, `uploadFolder.test.ts`, `svgRaster.test.ts`, `levelRows*.test.ts`) unchanged but one import line each in the two rows tests (D2) | identical |
  | delivery | three `--no-daemon --rerun-tasks :composition:jsPackage` builds from a removed `dist/`, one digest over its 226 files (`c47ab942…9332f695`); the web's `pnpm run check` with `verify:csp` and `verify:license-sources` green | identical |
  | tests | the slug check dropped from `FolderUpload`'s namer turned the core's `folderPlan` red (3 cases), the package's `depot.test.ts` (3 of 80) and the web's (5: 3 vectors, 2 of `uploadFolder.test.ts`); a text of exactly 1 MiB read as truncated (`>=` for `>`) the core's `view` (1), the package's (1 of 80) and the web's (2: the vector, `view.test.ts`' own); each restored from git, the tree clean after | each red |

  The move's decision log, kept by its implementer (question, choice and why, evidence, cost):
  - **D1 — The set: three rules with a twin, nothing without one.** Decision 1 deletes a twin; a rule the apps do not run
    whole is built in the core first, which is its own work (#1300–#1302). The TypeScript sets measured (each set's
    exports bundled alone with esbuild, every other import external, minified, gzip -9; `scratchpad` `measure-ts.mjs`):
    1,847 B gz before, 1,473 B gz of glue after — 374 B gz net. The move is small in bytes; its point is one rule.
  - **D2 — Depot's module is a lazy chunk.** `levelRows.ts` (eager, the shell's rows) imported `uploadFolder.ts`; a static
    import of the façade there would have made every visitor load Depot's module. It imports `./uploadFolder` when a folder
    is picked or dropped (`await import`). *Cost:* the two rows tests that drive a folder upload import it up front.
  - **D3 — Each sniff read once** (the optimisation round). The first façade asked the text's encoding again after
    `decide` had validated it: 2.00× over 10,000 openings (1,136 against 567 ms). `FileView.decided` returns the decision
    with the stream and the encoding its own sniff found; `decide`, the apps' call, is its first part. After: 1.00×.
  - **D4 — A too-large decision carries the sniffed type**, so the web says which cap a file passed; the apps read the kind.
  - **D5 — Where the core and the web answered apart, the web's answer won** (ADR 0154's rule): a path that names no file
    counts toward neither the plan's files nor its bytes (the core counted every entry), a vector case.
  - **D6 — The tokens are part of a plan's case.** A plan asks each kind's folding; a case lists every (kind, name, token)
    it may ask, written by the core from the apps' real foldings and checked against them, and a client fails the case
    where it asks one not listed — so the order of the questions is held, not only the names.
  - **D7 — `planRaster` is no hot path:** it runs once per paint, 150 ms after the last zoom, scroll or resize. Measured:
    100,000 rasters 4.4 ms against the TypeScript's 0.15 (~44 ns each, an object across the boundary).
  - **D8 — Decision 10's guest path, measured for the first time** (the owner sets its number): the eager set plus the
    static imports of the chunk a link's share view mounts — `FileDetail` for a single file's link, `DropBoxShareBody` for a
    drop box's; a folder's link draws core's level and Depot's rows, both eager. For the record, the other kinds' at the
    same build: a Memory's share body 715,671, a Memory's page 811,620, a Pot's page 718,724 B gz.

- **Memories' move** (decision 7.3's third application, built 2026-11-24, branch `claude/memories-web-core`, the work order
  cr-web-memories-on-the-core, deleted at harvest). The web's Memories rules that were pure and had a whole Kotlin twin on
  variant B — the justified grid's geometry and a medium's display aspect (`mediaGridLayout.ts`), a Memory's medoid
  (`computeMedoid`, now `medoid.ts`), the participants' merge (`mergeParticipants`, now `participantsWrite.ts` with the
  write), the MP3 and ADTS frame headers, the first frame chain and a raw ADTS file's count (`music/frames.ts`), a WAV's
  header, samples and ends (`music/wav.ts`) — are `:apps:memories`' (`MediaGridLayout`, `MemoryLocation.medoid`,
  `Participants.merge`, `music/Frames`, `music/Wav`), drawn through `@veyq/core-js/memories`
  (`apps/memories/src/jsMain/…/memories/web/`), its code Memories' own module file (`kotlin_veyq_mobile_apps_memories.mjs`),
  which the web's bundler inlines into the lazy `media` chunk a Memory's page and share body load: never in the eager set.
  `contracts/memories/v1-vectors.json` holds every side (20 aspects, 15 grids, 10 medoid sets, 8 merges, 35 frame headers
  and chains, 8 count steps and 6 ends, 21 WAV headers, 7 sample sets, 6 end lengths): `MemoriesVectorsTest` writes it, the
  package's `memories.test.ts` and the web's `features/memories/vectors.test.ts` answer it. What stays TypeScript, and why:
  the sealed meta's readers (`normalizeLocation`, `normalizeDate`, `normalizeParticipants`, `boundPlaceName`) — their twins
  read kotlinx's tree (variant A) and the web reads them eagerly (inbox #1320); the rules whose twin answers apart or takes
  another shape (the gallery filter, the rights, the GPX track, the track label and facts, the audio facts, the music
  queue, the space map's clusters, the stored match, the banner; inbox #1321); `isGalleryMedium` (D4); and every model
  with a flow or I/O (decision 8, its gate measured below, D7). Its gate (each number against `b53b952f0`, the tree before
  the move, its own build; the machine at a load of 4–7 from other agents):

  | Criterion | Measured | Bound |
  |---|---|---|
  | size: Memories over every platform entry (the package's `slice.test.ts`, gross, `@noble/*` external) | +4,249 B gz (its module's own 10,572 B minified) | ≤ 25,600 |
  | size: the platform module, every entry | 79,874 B gz (79,768 before: +106 — `Float.fromBits`' companion and `floatFromBits`, `roundToLong`, an `IntArray.getOrNull`) | reported (decision 10); the slice check still holds the running budget, 80,094 |
  | size: the deleted TypeScript (each set's exports bundled alone, every other import external, gzip -9) | 2,512 B gz before (grid 613, medoid 385, merge 242, frames 765, wav 969), 1,010 B gz of glue after — 1,502 net | — |
  | size: the stop rule (the guest path grows by at most the deleted TypeScript × 3.0 plus the façade's floor) | +3,150 B gz at most (a Memory's page) | 7,536 + the floor |
  | size: the web's eager set (`vite build`, gzip -9 per chunk) | 688,070 → 688,246 (+176: `main` −215 with the medoid and the merge gone from it, `review` +302 the platform's growth, the rest rechunked) | — |
  | size: a link guest's first load (decision 10, D8 of Depot's move) | a Memory's share body 715,671 → 718,798 (+3,127); a Memory's page 811,620 → 814,770 (+3,150); a Depot file 702,621 → 702,776 (+155, inside its 720,896); a folder 688,246, a drop box 691,948, a Pot's page 718,924 (the eager set's +176 each) | Memories': proposed 816 KiB, decision 10 |
  | size: the chunks Memories loads | `media` 8,448 → 11,730 (+3,282: Memories' module inlined), `useOptimisticTiles` −351, `MemoryDetailHost` −25, `source` +35 | — |
  | size: a chunk only members load (`PersonMemories`, `MemoryNodeDialog`, `SpaceMap`, `exportImport`) | +3, +4, +1, −44 B gz | measured, not gated (decision 10) |
  | time: the grid, a 10,000-photo gallery laid out at 360, 1,280 and 2,560 px (Node 22.23.4, median of 15 after 3 warm-ups, the glue as it ships against the deleted TypeScript) | 2.65–2.75 ms against 1.21–1.33 (2.07–2.19×), two runs | ≤ 2.5× and ≤ 100 ms |
  | time: the medoid of a trip's 500 points | 3.60–3.63 ms against 3.33–3.47 (1.05–1.08×) | ≤ 2.5× and ≤ 100 ms |
  | time: a WAV end's samples, 30 s of 48 kHz stereo | 16 bit 12.52–12.56 ms against 7.44–7.49 (1.67–1.69×); 24 bit 11.45–11.47 against 9.08–9.14 (1.26×) | ≤ 2.5× and ≤ 100 ms |
  | time: the first frame chain over a 4 MiB tail, the chain at its end | MP3 11.96–11.97 ms against 7.15–7.26 (1.65–1.67×); ADTS 20.80–20.89 against 20.49–21.51 (0.97–1.02×) | ≤ 2.5× and ≤ 100 ms |
  | time: a 10-minute raw ADTS file counted | 1.00–1.09 ms against 0.73–0.74 (1.36–1.49×) | ≤ 2.5× and ≤ 100 ms |
  | answers | identical (a digest over each corpus' answers) | identical |
  | behaviour | the web's tests pass (592 files, 8,435 tests) with the twins' tests unchanged but their import lines (`memoryLocation.test.ts`, `memoryLocation.e2e.test.ts`, `memoryParticipants.test.ts`: the medoid and the write in their new files) | identical |
  | delivery | three `--no-daemon --rerun-tasks :composition:jsPackage` builds from a removed `dist/`, one digest over its 227 files (`117d728e…6a1022b`); the web's `pnpm run check` with `verify:csp` and `verify:license-sources` green | identical |
  | tests | one bite per hot rule, each red on all three sides and restored: `>` for `>=` closing a grid row — the core's `grid` (2 cases), the package (2), the web (2); `<=` for `<` in the medoid — 1, 1, 2 (`memoryLocation.test.ts`' tie too); a 16-bit sample over 32,767 — 2, 2, 2; a chain of two read as three — 4, 4, 7 (`frames.test.ts` too); the count reading past a cut slice — 2, 2, 1 (the web's whole-file case through `adtsDurationMs`, added for it: without it the bite passed the web, measured first) | each red |

  The move's decision log, kept by its implementer (question, choice and why, evidence, cost):
  - **D1 — The set: five rules with a whole twin on variant B, nothing else.** Decision 1 deletes a twin; the meta's readers
    take kotlinx's tree and are eager, the other rules have no whole twin — each is its own work (#1320, #1321). The move is
    small in bytes (1,502 B gz net); its point is one rule each.
  - **D2 — Memories' module stays off the eager set.** `memoryLocation.ts` and `memoryParticipants.ts` are eager (the level's
    rows, the search's facets; the medoid measured in `main`), so the medoid's glue is a file of its own (`medoid.ts`, the
    place's composable its one caller) and the participants' write moved with its merge into `participantsWrite.ts` (a
    Memory's page its one caller). A dynamic import in the write was tried first: one more await before the PATCH, and a
    test's pending write landed on the next test's mock (measured), so the write moved instead. *Cost:* three test files'
    import lines.
  - **D3 — The pure parts are `internal`.** The façade lives in the module's own `jsMain`, so what it needs —
    `MediaGridLayout.justifyInto`/`displayAspectOf`, `medoidAt`, `Frames.adtsCount`/`adtsEnd`/`adtsMs`, `Wav.endBytes` — is
    `internal`, and the apps' public functions run them; no public Kotlin signature changed, so the Apple framework's header
    is unchanged and no Xcode build was owed.
  - **D4 — `isGalleryMedium` stays:** a type test on one object per item; a crossing per item for three comparisons.
  - **D5 — Where the core and the web answered apart, the web's answer won** (ADR 0154's rule): the medoid's radians are
    the web's `(d * π) / 180`, not `d * (π / 180)` — an ulp apart, enough to flip a near tie.
  - **D6 — The squeeze, behaviour-neutral.** The first façade grew the platform by +462 B gz: the grid's `Layout` (its lazy
    rows, the standard library's sublists) and the medoid's `List<LatLng>` (the companion's world view ships core's `LatLng`
    with every entry). The façade places tiles without a `Layout` (`justifyInto`) and the medoid reads two arrays at file
    level (`medoidAt`): +106. `Float.fromBits` (a float WAV's samples) stays — its companion is the remainder.
  - **D7 — Decision 8's gate, measured, not crossed** (a throwaway probe façade, never committed; every platform entry
    bundled with and without Memories' as the slice check bundles them, gzip -9). *The candidate:* `GalleryModel`, the page
    every Memory's guest and member opens (the share body and the page both draw it), its TypeScript twin the largest of the
    application (`useMediaGalleryLive`, `useOptimisticTiles`, `useMediaTrash`, `useThumbnailPipeline`, `optimisticUpload`,
    `useUploaderNames`, `mediaRevisionPolling`, `itemFavoritesStore`: 1,411 lines, 6,603 B gz bundled, and much of
    `MemoryDetail.vue`). *Measured:* the coroutines floor alone (a `StateFlow`, a launched watch, a `Promise` action) — the
    platform 79,874 → 84,554 B gz (+4,680, every first load), Memories +4,249 → +19,488 (+15,239, the coroutines library in
    its lazy code); the gallery's model behind a door the web writes (every call a `Promise`), watched, its three reads —
    the platform **364,486 (+284,612)**, Memories +26,120 over it; every action the page calls — 381,625 (+301,751) and
    +36,746. *Why so much:* the model reaches 450 platform classes — `TenantSession`, the generated API client
    (`ContentApi`, `ScopesApi`, …), the rekey, the outbox, the offline pages — and with them Ktor and kotlinx.serialization,
    which then ship in the platform's one module file with every entry; a bundler drops none of it. *What it would buy:*
    the 6.6 KB gz above deleted and one gallery for every client. *Answer:* not crossed; the owner question (inbox #1323)
    was whether to cut the model from the session first (its reads only through its `Door`) and measure again — answered
    2026-11-25: cross (decision 8); how is the gallery move's own design.
  - **D8 — Memories' guest-path ceiling, proposed:** 816 KiB gz (835,584 B), a Memory's page's 814,770 plus Depot's relative
    headroom (decision 10; inbox #1324). Never set: the owner dropped every ceiling on 2026-11-25.

- **The operator-served web is unchanged in kind** ([ADR 0026](0026-client-trust-tiers.md)): still same-origin,
  content-hashed chunks, no new CSP source, no Subresource Integrity in use before or after; the Kotlin output is one
  more reviewed, compiled-in module ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 5).
  The web's build inputs grow by the Kotlin compiler and its libraries, pinned by `src/mobile/gradle/verification-metadata.xml`;
  a byte-identical rebuild — what the attested tier will need — is a criterion of the pilot, not an assumption.
- **The apps pay only the JSON seam**, and only once Expenses moves: its format reads JSON through it; the apps'
  behaviour is held by the tests that hold it today. The search adds one parameter to the core's `Results`, defaulting
  to today's order, so the web keeps its browser collator. What of coroutines, serialization and Ktor a façade reaches is declared per entry and the build holds the declaration (decision 4).
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
  decision 8 took that cost once a flow crosses (2026-11-25).
- **The status quo with more vector sets.** Each shared rule held by vectors still costs two implementations and their
  tests; the vectors catch a divergence, never the second implementation's cost.
- **Committing the compiled JavaScript.** Reviewable as a file, but it drifts from the Kotlin that claims to produce it,
  and nothing would prove the two equal on every commit.
- **Kotlin's own npm/Yarn tooling.** A second package manager and a second lockfile beside pnpm
  ([ADR 0116](0116-the-javascript-packages-are-installed-by-pnpm.md)), and a Node distribution fetched from a
  repository `src/mobile/settings.gradle.kts` does not declare (it sets `FAIL_ON_PROJECT_REPOS`).
- **Moving the crypto suite and admin-web too.** Decision 9.
- **Keeping the size gates after the pilot** (held 2026-11-21 to 2026-11-25). They kept the web's growth in view, but every
  move spent its effort squeezing bytes (Memories' D6, Expenses' platform placement) and the screen models, the larger half
  of what the web holds twice, could not cross at all (decision 8's +284,612 B gz). The owner chose the direction over the
  bytes (2026-11-25); the reports keep the growth in view without stopping a move.
