---
id: cr-web-node-tree-on-the-core
title: The node tree's rules move onto the Kotlin core for the web — level order, equal names numbered, a name's sibling collision, the move plan — `@veyq/core-js/tree`, the TypeScript deleted
status: planned
satisfies: ["en.platform.node-tree", "uc.memory.browse", "uc.memory.move-node"]
adrs: ["0155", "0154", "0136", "0080", "0079", "0046"]
invariants:
  - "operator-blind: nothing about what is sealed, under which purpose, or what the server sees changes; no key, no sealed byte and no slug token crosses the façade — the tree facts it takes are ids, kinds, names, parents, creation instants and the identity-folder flag, never `TreeSource.keyOf`"
  - "the token a name is HMAC'd into is unchanged: `shared/naming.ts` stays the web's folding for the token, `crypto/Naming.kt` the core's, both held by contracts/crypto/vectors/v1.json#name_folding (decision 3)"
  - "code the façade reaches carries no kotlinx.serialization, coroutines, Ktor or NotOnTheWeb refusal (ADR 0155 decision 4), held by the package's slice check; no `Long` crosses the façade or runs on its hot path"
  - "the platform never names an application: the rules take each kind's facts from their caller (decision 4), and `validate_mobile_module_graph.py` (the split's guard) holds that `:core` depends on nothing"
  - "a web user sees the same level order, the same numbered names, the same collision answer and the same move places as before, except the cases decision 5 names; an app user sees the web's answers where they differed (decision 1)"
---

## Goal

[ADR 0155](../docs/decisions/0155-the-web-draws-over-the-kotlin-core-application-by-application-after-a-measured-pilot.md)
decision 7.2's first platform slice (the order and its reasons: the ADR's decision 7.2 and this CR's decision log, D1).
The node tree's pure rules — the order a level lists its nodes in, the numbering of siblings that carry one name
(`Anna (2)`), whether a new or changed name collides with a sibling, and the move's plan (where a selection may go, what
it leaves out, the places offered, the trail) — exist twice today: TypeScript in `src/web/src/core/node/` and a Kotlin
port spread over `navigation/Home.kt`, `session/NodeMove.kt`, `session/NodeRename.kt`, `session/NodeCreate.kt` and
Depot's `depot/DropBoxes.kt`. The two disagree: the app sorts a level by `name.lowercase()` (no number order, accents
apart, no tie-break), numbers equal names only on a drop box's page, and plans a move for one node of a member only.
After this CR the rules have one home in the platform module `:core` (`me.raddatz.veyq.mobile.tree`), the web draws
them through `@veyq/core-js/tree`, the apps call the same functions, and the TypeScript is deleted. It is also where the
platform's façades learn to share `:core`'s one JavaScript file (decision 9) and where the slice check becomes the
platform module's running budget (decision 10).

## Context & constraints

- **The substrate** (named before every number below): branch `claude/platform-slices-refine` at `d089bffd2`,
  2026-11-21, the pilot merged, the core's split **not** landed (it is being built on `claude/core-split` in parallel:
  [ADR 0156](../docs/decisions/0156-the-kotlin-core-is-split-into-a-platform-module-and-one-module-per-application.md), built 2026-11-22). Nothing was built for this
  refinement and no Gradle task ran; every size is measured on sources with esbuild (command below) or remembered from
  ADR 0155.
- **Blocked until the split lands** (decision 11): this CR assumes the split's layout — `:core` the platform at
  `src/mobile/core`, `:apps:<a>`, `:composition` producing the JavaScript package with one file per module, `AppKinds`
  the platform's registry filled by `:composition` (the split CR's decisions 1, 2 and 5) — and its phase 4's
  per-module numbers, which are this CR's size baseline.
- **The TypeScript the move deletes** (measured: `node measure.mjs <src/web/src> '<slice json>'` — a 30-line esbuild
  script under the scratchpad that re-exports the named functions, bundles only the slice's own files with every other
  import external, `--minify`, ESM, then `gzip -9`; `esbuild@0.28.2` from the workspace's pnpm store):
  `levelList.ts` (all), `location.ts`' `compareByCreation`/`compareById`/`displayNamesOf`,
  `siblingCollision.ts`' `collidingSibling`, `move.ts`' `moveHereBlock`/`moveHereBlockOf`/`planMove`/`moveTop`/
  `moveStart`/`moveTrail`/`movePlaces`/`endsIdentity` — **3,086 B min / 1,326 B gz** (the order part 430 B gz, the move
  and collision part 1,030 B gz). So decision 7.2's bound: the eager set grows by at most **3 × 1,326 = 3,978 B gz**.
- **What it will cost** (remembered, not built): the pilot's façade code was 2.80× its deleted TypeScript; at that
  ratio this slice's own Kotlin is ~3.7 KB gz, the net growth ~2.4 KB — inside, with no margin for scope creep. A small
  slice pays the façade's fixed part (its shapes, the handle, the entry) over few deleted bytes, which is why phase 3
  measures the package before the web is touched.
- **What is eager** (remembered, to be measured in phase 4's baseline): the rules' callers are core's level, move
  dialogs, name dialogs and drag (`LevelView.vue`, `MoveNodesDialog.vue`, `MoveBrowser.vue`, `nodeDrag.ts`,
  `nodeDialogs.ts`, `CopyNodesDialog.vue`, `FolderShareDialog.vue`, `BookmarkCreateDialog.vue`), reached from the
  space page; the search's Kotlin already lands in `activeSpace`, which `index.html` modulepreloads.
- **The kinds the rules need.** The collision compares token inputs (`registry.tokenInputFor`: each kind's folding —
  `fileNameSlug` for Depot's `FILE`, `slugify` for every other — behind its application's route slug), and the move asks
  each kind's placement (`registry.placementAllows`, held to `contracts/platform/kinds/*.json` by the web's
  `kindsContract.test.ts`). The core answers both through `AppKinds.tokenInput` and `AppKinds.placement`, which after
  the split are the registry `:composition` fills — and `:composition` is never a JavaScript entry (the split's decision
  5), so on the web nobody installs it. Decision 4 is the seam.
- **The Kotlin twins today** (read): `navigation/Home.kt` `level` (folders first, then `name.lowercase()`), `NodeRef`
  carries no creation instant (`navigation/Library.kt`); `session/NodeMove.kt` (`start`, `trail`, `places`, `hereBlock`,
  `inIdentityFolder`, `endsIdentity` over one moved `NodeRef`, member only, beside the `suspend fun move` that writes);
  `session/NodeRename.kt` and `NodeCreate.kt` (the collision inline over `tokenInput`); `depot/DropBoxes.kt`
  (`compareByCreation` over ISO strings, `displayNames` — a platform rule inside an application's package).
- **No vectors pin these rules today**: `contracts/crypto/vectors/v1.json#name_folding` pins the folding only.

## Decisions (technical, settled here; each one's why and cost is in § Decision log)

1. **The phase order is contracts → core → core-js → web** (ADR 0155 § Consequences). Nothing of the backend or
   `contracts/api` changes. Where the vectors written from today's web disagree with the Kotlin, the web's answer is the
   product's and the Kotlin follows (D13); the app's visible changes that follow — a level in the web's order, a guest's
   identity folders numbered — are the alignment this slice exists for.

2. **The scope**: moved and their TypeScript deleted — `levelList.ts` whole; `compareByCreation`, `compareById` and
   `displayNamesOf` out of `location.ts`; `collidingSibling` out of `siblingCollision.ts` (its `nameTakenMessage` stays: a
   sentence from the web's catalog); `move.ts`' plan (`moveHereBlock`, `moveHereBlockOf`, `planMove`, `moveTop`,
   `moveStart`, `moveTrail`, `movePlaces`, `endsIdentity`, the private `withinMoved`). **Stays TypeScript**: `naming.ts`
   (decision 3), `location.ts`' URL rules (`segmentsOf`, `resolveSlugPath`, `slugPathOf`, `parseHash`, `buildHash` — the
   router's, ADR 0155 decision 1; the apps have no fragment paths, D4), `nodeName.ts`' writes and `renameSelectionEnd`
   (a caret, the dialog's), `move.ts`' `nodeMover` and `moveRefusalOf` (network), `selection.ts` (Vue state).

3. **The token's folding stays two implementations.** `shared/naming.ts` is what the web HMACs into a stored slug token;
   ADR 0155 decision 9 keeps the crypto suite TypeScript beside the Kotlin, two implementations answering one vector
   set, and `naming.ts` is held by exactly that set (`v1.json#name_folding`) and by the web's Stryker gate
   (`src/web/stryker.mutate.json` names it). The Kotlin collision folds with `crypto/Naming.kt`; the two agree by the
   vectors, which both sides already run. `Naming.fnv1a` stops going through `Long` (`hash.toUInt().toString(36)`, the
   same digits): it is reached from the façade, and a `Long` on JavaScript is emulated (ADR 0155 § Consequences).

4. **The kinds' facts are data the caller hands in, not the registry on JavaScript.** The platform declares
   `KindFacts` in `me.raddatz.veyq.mobile.tree`: per kind its route slug (or none), its folding (`NAME` = `slugify`,
   `FILE_NAME` = `fileNameSlug`; an enum — a folding is frozen into stored tokens, so a new one is a platform change with
   its vectors, never an application's lambda) and its placement (the parents it takes, whether the top). On the apps,
   the split's `AppKinds` registry answers it from each application's contribution; on the web, the façade builds it
   once from the web's registry (`registry.kinds()`, `routeOf`, each descriptor's `slug`, `placementAllows`), and the
   web keeps that table as an opaque handle (`kindFacts(...)`, held `markRaw` beside the registry). The rules never call
   `AppKinds`; `AppKinds.tokenInput` and `AppKinds.placement` become thin readers of `KindFacts`, so the app and the web
   fold and place by one function.

5. **A level's order is one rule in common code, with no platform collator.** Folders first, then every other node mixed
   by name; names compared case- and accent-insensitively (NFKD, combining marks dropped, lowercase, `ß` as `ss`), runs of
   digits as numbers (leading zeros ignored), the remaining characters in the order of the web's collator's groups
   (white space, punctuation, symbols, digits, letters), then by code point; equal names by creation, then by id
   (`compareByCreation`), a guest's by id (`compareById`). The web's `Intl.Collator(locale, { numeric: true, sensitivity:
   'base' })` is replaced by it: the vectors (phase 1) are written from that collator under Node 22 (ICU 78.3) for English
   and German over a named corpus, and every case the rule answers otherwise is either fixed in the rule or listed in
   phase 1's commit as a visible change with why (D5). Language-free: the web's two languages sort alike at base strength.

6. **The tree crosses once per generation, as an opaque handle; answers cross as ids.** `treeFacts({ rootId, nodes })`
   takes the plain facts of every node a session reads (`id`, `parentId`, `kind`, `name`, `createdAt` as epoch
   milliseconds or null for a guest's, `identity`) and returns a handle indexed by id and parent. The member's
   `treeStore` and the guest's tree context rebuild it when they replace their node list (a `WeakMap` from the list to
   the handle), as the search's corpus is rebuilt per generation (ADR 0155 decision 5's exception: state a rule keeps
   across calls). `orderLevel` and `displayNames` take the level's own entries per call (a level, not the tree).
   Results are ids, the block and left-out reasons as the web's string unions, and labels by id in a `Map`; the web maps
   ids back to its own `TreeNode`s, so no Kotlin object reaches Vue.

7. **No `Long` on the façade or its hot path.** A creation instant is epoch milliseconds in a `Double` (exact below
   2^53) in the rules' common types; the apps convert the served ISO instant once where they build the facts (D9).

8. **The pure rules get a platform home**: `src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/tree/`
   (`KindFacts`, `TreeFacts`, `LevelOrder`, `DisplayNames`, `SiblingNames`, `MovePlan`), free of coroutines and
   serialization. `Home.level`, `NodeMove`'s pure part, `NodeRename`'s and `NodeCreate`'s collision and
   `DropBoxes.compareByCreation`/`displayNames` call them, and their own copies go. `NodeMove`'s `suspend fun move` stays
   where it is: an object whose initializer the façade reaches must hold no coroutine state (the parked Expenses CR's
   decision 8; the slice check is the proof). `NodeRef` gains `createdAt` (epoch ms, `Double?`) so the app's level can
   tie-break and number as the web does.

9. **The façade and its entry.** `src/mobile/core/src/jsMain/kotlin/me/raddatz/veyq/mobile/tree/web/TreeWeb.kt` and
   `Shapes.kt`, as the search's: `kindFacts`, `treeFacts`, `orderLevel`, `displayNames`, `collidingSibling`,
   `moveHereBlock`, `moveHereBlockOf`, `planMove`, `moveStart`, `moveTrail`, `movePlaces`, `endsIdentity` (`moveTop` is
   the handle's root, read by the web). The entry is `@veyq/core-js/tree`, core's by the web's direction guard (an entry
   named for no application, ADR 0155 decision 5). Both core entries live in `:core`'s one module file
   (`veyq-mobile-core.mjs`), so `package.json` maps each to a **generated entry file** that re-exports only its façade's
   `@JsExport` declarations (derived from the sources, never listed by hand — the mechanism the parked Expenses branch
   built in `cbf54e21e`, taken over here since this slice comes first); a name from the other entry does not type-check.

10. **The size gate is the platform module's running budget.** Every platform façade lands in the same module file, and a
    bundler cannot drop one façade's code from it (ADR 0155 § Consequences), so `slice.test.ts`'s per-entry 40 KB becomes
    one bound on the platform module bundled with all its entries: the size the split's phase 4 recorded for the search
    alone, plus R × each moved slice's deleted TypeScript (a table in the test, one row per slice with its CR and
    numbers). The web measurement stays the authority (ADR 0155 decision 7.2): the eager set grows by ≤ 3,978 B gz, the
    deleted TypeScript counted, no lazy chunk by > 1,024 B gz; recorded in ADR 0155.

11. **What waits for the split, and what does not.** Blocked: phase 2 onward needs `:core` as the platform module with
    `AppKinds` as the registry (the split's decision 2 — `KindFacts` is read from that registry, not beside it) and the
    JavaScript package produced per module by `:composition` (its decision 5); the size baseline is its phase 4's search
    number. Not blocked: phase 1 (the vectors, answered by today's TypeScript and today's Kotlin through a test that
    calls the current functions) may run before the split, as long as it touches no file under `src/mobile` that the
    split moves — its Kotlin test lands with phase 2.

12. **The gate of this slice** — every line must hold; a miss goes to the owner with its number (ADR 0155 decision 2's
    rule), a no-go reverts this CR's commits:
    - **size:** decision 10's two bounds, measured with the pilot's method (gzip 9 per chunk, each chunk attributed by
      its source map), before (the tree at phase 4's first edit, its own build) and after;
    - **time:** over a 10,000-node corpus (one folder of 10,000 siblings, the volume harness's wide shape; and a
      10,000-node tree six levels deep for the ancestry walks), `treeFacts`, `orderLevel`, `displayNames`,
      `collidingSibling`, `movePlaces` and `planMove` of a 1,000-node selection each ≤ **2.5×** the TypeScript's median
      **and** ≤ **100 ms** (ADR 0155 decision 7.3's bound, applied to a slice's hot rules — D12), Node 22, median of 15
      after 3 warm-ups, answers identical;
    - **delivery:** three clean builds byte-identical; no `eval`, `new Function` or DOM sink; `verify:csp` and
      `verify:license-sources` green;
    - **behaviour:** the staying web tests pass with only their imports changed (any other change listed in the
      retrospective with why); the browser pass of phase 4 lists the same rows, labels and places before and after;
    - **tests:** every assertion of a deleted TypeScript test accounted for (the parked Expenses CR's decision 7: a vector case,
      a core common test, or a staying web test); a mutated Kotlin rule (digits compared as text in `LevelOrder`; `self`
      not excluded in `SiblingNames`) turns `src/tree.test.ts` in the package and the web's `vectors.test.ts` red.

## Inventory (locators — run at refine, read as lists; re-run at implement)

| What | Locator (from the repo root) | At refine |
|---|---|---|
| The web's callers of the moved functions | `git grep -lwE "orderLevel\|compareByName\|compareByCreation\|compareById\|displayNamesOf\|collidingSibling\|moveHereBlock\|moveHereBlockOf\|planMove\|moveTop\|moveStart\|moveTrail\|movePlaces\|endsIdentity" -- src/web/src ':!*.test.ts'` | 12 non-test files import `levelList`, `siblingCollision` or `move` by path (`grep -rln "levelList'\|siblingCollision'\|/move'"`) |
| Second angle: the tests that read them | the same `git grep` restricted to `'*.test.ts'` | `levelList.test.ts`, `location.test.ts`, `siblingCollision.test.ts`, `move.test.ts`, `FolderNameDialog.collision.test.ts`, the move dialogs' tests — the account of decision 12 per assertion |
| The Kotlin twins and their callers | `git grep -nwE "level\|places\|hereBlock\|trail\|start\|endsIdentity\|inIdentityFolder\|compareByCreation\|displayNames\|tokenInput\|placement" -- src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/{navigation/Home.kt,session/NodeMove.kt,session/NodeRename.kt,session/NodeCreate.kt,session/FolderCreate.kt,depot/DropBoxes.kt,AppKinds.kt}` (paths after the split: the application files under `src/mobile/apps/`) | the twins in § Context |
| Kotlin tests of the twins | `git grep -lE "Home\.level\|NodeMove\.\|DropBoxes\.\|NodeRename\." -- 'src/mobile/**/*Test*.kt'` | `HomeTest`, `NodeMoveTest`, `MoveSheetTest`, the drop box's and rename's tests |
| What a façade object would drag in | `for f in <the files of the row above>; do echo "$f $(grep -cE 'kotlinx\.coroutines\|StateFlow\|Flow<\|suspend \|kotlinx\.serialization' $f)"; done` | `NodeMove.kt` has a `suspend fun move`; `Home.kt` imports kotlinx once |
| `Long` on the reach | `grep -nE "\bLong\b\|toLong\(\)" src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/crypto/Naming.kt` plus the new `tree/` files | `Naming.fnv1a` |
| Coverage refs to the touched tests | `grep -rnE "core/node/(levelList\|location\|siblingCollision\|move)\.test" docs/coverage` | `uc.memory.move-node`, `uc.memory.browse`, `uc.memory.participants` |
| Docs that name the TypeScript as the rule's home | `git grep -lE "levelList\.ts\|siblingCollision\.ts\|displayNamesOf\|compareByName\|move\.ts" -- docs src/mobile site contracts` | `en.platform.node-tree.md`, `docs/apps/depot.md`, ADR 0123 (a record, unchanged), the Kotlin twins' comments |
| Published pages about a level's order or numbering | `git grep -niE "sorted\|alphabetical\|order\|\(2\)" -- site/src/content/docs/spaces site/src/content/docs/de/spaces` | read at implement; a page that states the order gets the sentence the browser and the apps now share |
| The package's entries and checks | `cat src/packages/core-js/package.json`; `src/packages/core-js/src/slice.test.ts` (`ENTRIES`, `BUDGET_GZ`); `scripts/validate_web_core_imports_no_applications.py` (`CORE_JS_ENTRY`) | one entry, `./search`; the 40 KB bound per entry |

## Phases

**Phase 1 — contracts: the vectors both sides answer before anything moves.**
`contracts/platform/tree/v1-vectors.json` (its schema beside it) with sections `order` (en and de: the named corpus —
case, accents, `ß`, `Foto 2`/`Foto 10`/`Foto 02`, punctuation and symbols leading, emoji, CJK, empty-folding names,
equal names tied by creation and by id), `displayNames`, `collision` (a kind table, siblings, kind, name, self → the
colliding id or null; the route groups of ADR 0080 decision 16), `moveBlock` (self, inside, current, identity folder,
kind, a guest's non-folder), `moveBlockOf`, `planMove` (current and name-taken left out), `movePlaces`, `moveTrail`,
`moveStart`. Their `expected` come from today's TypeScript (a writer script under `src/web/scripts/`, as
`format-vectors.mts` is; the order section under Node 22, the ICU major named in the file). The web answers every section
(`src/web/src/core/node/tree.vectors.test.ts`). Where today's Kotlin disagrees — it will on order and on a selection's
move — the case is listed in the commit, not edited (decision 1).
Done when: the web's `pnpm run check` green; a mutated TS rule turns its section red (one case named per section);
`validate_*.py` green.

**Phase 2 — core: the platform home, the apps on it, the vectors turned.** (After the split.)
`tree/` (decision 8) over `KindFacts` (decision 4); `AppKinds.tokenInput`/`placement` read it; `Home.level`,
`NodeMove`'s pure part, `NodeRename`/`NodeCreate`'s collision and `DropBoxes`' two rules call it and lose their copies;
`NodeRef.createdAt`; `Naming.fnv1a` off `Long`; `TreeVectorsTest` in `commonTest` answers every section and gains the
write mode (`VEYQ_WRITE_VECTORS=1`, as `SearchVectorsTest`), rewriting the file byte for byte; the web's unit cases
that are no vector land in `TreeRulesTest`; the writer script of phase 1 is deleted (one file, one writer).
Done when: from `src/mobile`, the split CR's phase 1 list for the modules it touches (`:core` and `:apps:depot` jvm,
macosArm64 and JS compilations and tests, `:bridge:jvmTest`, `:android:assembleDebug`, the Apple app ad hoc,
`linux/run.py test`, `windows/run.py test`); `git diff contracts/platform/tree/` after the rewrite empty;
`VeyqCore.h` and the bridge's bindings diffed — only `NodeRef`'s new field and the `tree` package's names added.

**Phase 3 — core-js: the façade, its entry, the checks.**
`TreeWeb.kt`/`Shapes.kt` (decision 9); the generated entry files and `package.json`'s `./tree`; `src/tree.test.ts`
(every vector section through the shipped module, a handle rebuilt per generation answering the new tree, no key in any
shape); `slice.test.ts`: the platform module's running budget (decision 10), the forbidden list unchanged, its control
`tree/LevelOrder.kt` in the map; a type test that `@veyq/core-js/tree` exports no search name and the reverse.
**A size probe before the web is touched**: the platform module bundled with both entries against the search alone;
past 4 × 1,326 B gz of own code the slice cannot meet decision 12's net bound, and goes to the owner here, not after
phase 4.
Done when: `cd src/packages/core-js && pnpm run build && pnpm run check` green; three clean builds one digest; the bites
of decision 12's two Kotlin mutations red here, a `kotlinx.coroutines` import reached from `tree/` red in the slice
check, a cross-entry import red in the type test.

**Phase 4 — web: the callers switch, the twins go.**
Baseline first, before the first edit: the eager set and every chunk (decision 12's method) and the timings of decision
12 over the TypeScript. Then the registry builds the kind handle, `treeStore` and the guest tree context the tree handle
(decision 6); the callers import the façade; the moved functions and `levelList.ts` go; `core/node/vectors.test.ts` runs
the vectors through the package (the phase 1 test, its import changed); coverage refs repointed in the same commit as
the tests they name.
Done when: `pnpm run check`, `pnpm run build`, `verify:csp`, `verify:license-sources` green; decision 12's table filled;
`validate_coverage.py` and `validate_web_core_imports_no_applications.py` green; **the browser pass** (one throwaway
account, the dev stack per `infra/README.md`, the UI in English and in German): a folder holding `Foto 2`, `Foto 10`,
`Äpfel`, `apfel`, `_Notiz`, a Memory, a file and a Pot mixed by name; a drop box with two guests both named `Anna`
(`Anna`, `Anna (2)` on its page, the level, the breadcrumb, the guest's own folder page); a rename and a new folder
refused inline for a taken name, a file `a.b` beside a folder `a.b` accepted; a move of a selection with one node
name-taken (left out, said), the move dialog's blocked places (itself, inside, a guest's folder, a kind the place does
not take); a contributor link guest moving a file. Every row, label and refusal identical to the same pass on the
baseline build (screenshots only for the final proof). **And the apps**: the same space on the Android emulator or the
Mac lists the folder in the web's order (skipped and said if no device is reachable).

**Phase 5 — docs.** ADR 0155 (`Built`: decision 7.2's first slice; § Consequences the gate's table and the platform
module's new baseline); `docs/features/platform/enablers/en.platform.node-tree.md` (where the tree's rules live, the
vectors); `src/mobile/CLAUDE.md` and `src/web/CLAUDE.md` where they list the core-js entries or the vectors' writers;
`docs/crosscutting/native-apps.md` if it lists the turned vectors; a Use page that states the order (inventory row);
inbox #1046 (the slice built) and #1062 (the rule in the platform, the apps' views to follow).
Done when: every `scripts/validate_*.py` green (`validate_guard_gaps.py` only if a guard changed); `pnpm run check` in
`site/` green if a page changed.

## Risks

- **The slice is small, so its fixed façade cost weighs.** 1,326 B gz of TypeScript buys 3,978 B gz of growth. Phase 3's
  probe finds a miss before the web is rewired; the fallback is not a looser bound but the owner's call with the number,
  and the cheapest cut named with it (the move plan and collision alone, 1,030 B gz, if the order's common rule is what
  costs).
- **The order's common rule differs from ICU somewhere the corpus did not look** (scripts beyond Latin, symbols). The
  web's two languages and the corpus bound it; a case found later is a vector case first, then a rule change.
- **Per-module output keeps another module's code** (the split CR's first risk): the platform baseline is the split's
  measured number, not the pilot's 28,189 B gz; this CR records which.
- **KT-68281's sort step** may meet an interface list in `tree/` it refuses; it fails loudly, and the fix is in the Kotlin.
- **A handle held across a tree replacement** answers the old tree: the `WeakMap` keyed by the node list is the one
  rebuild path, and `src/tree.test.ts` holds a handle rebuilt per generation.
- **`SearchField.test.ts` went red on the parked branch** from a module hop between the web and the search's façade; a
  second entry over the same module file may reproduce it. Phase 3 runs the web's check before phase 4 starts.

## Decision log

Each entry: the question, the options, the choice and why, the evidence (measured with its command, or remembered),
and what it costs.

- **D1 — Which platform slice goes first, and in which order the rest follow.** Options: the formats of ADR 0154 first
  (decision 7.2's list order), the share-link rules, the file facts (sniff, download name, archive name), the node tree's
  rules. Choice: the node tree's rules, then share links, rights and plans, file facts, media headers, the export archive,
  the audio order (ADR 0155 decision 7.2 holds the list with a reason each). Why: the tree is eager today, every
  application builds on its kinds' placement and naming, it gives one home to three rules the app and the web answer
  differently (the order, which the apps take over at once through `Home.level`; the numbering and a selection's or a
  guest's move, which the apps' views then draw — inbox #1062, #1063), it needs no JSON seam (Expenses builds that) and no key material, and it
  forces the two mechanisms every later slice reuses (decision 9's shared module file, decision 10's running budget) on
  the smallest real case. Evidence: measured, the slices' deleted TypeScript by `measure.mjs` (§ Context) — tree 1,326 B
  gz, share links 1,251, rights and plans 1,388, file facts 2,566, media headers 4,780, export archive 5,726, audio 859,
  formats 587; the divergences read in the sources (§ Context). Cost: the first slice is one of the smallest, so its
  fixed façade cost weighs most (Risks).
- **D2 — Are ADR 0154's formats and notices a slice of their own?** Options: move them first as decision 7.2 listed;
  move each with the slice or application that owns it; never. Choice: with their owner (money with Expenses, a Memory's
  date range with Memories, sizes and days with the first slice that draws them); notices after the word catalogs have
  one source. Why: a format slice alone misses R: `Shown` behind a façade is 4,967 B gz of own code against 703 B gz of
  TypeScript, 7.1× where R allows 3.0, and it would replace the browser's `Intl` for every format at once. A notice is a
  word from a catalog the web does not share with the apps (inbox #1060). Evidence: measured in ADR 0154 § Consequences
  (its command there); the catalogs read (`scripts/validate_mobile_words.py` reads no web source). Cost: the format
  vectors keep running web → core until each owner moves.
- **D3 — Does `shared/naming.ts` move?** Options: move and delete it; keep it as the token's folding. Choice: keep.
  Why: it decides the bytes HMAC'd into stored slug tokens, held by the crypto vectors that ADR 0155 decision 9 keeps two
  implementations for, and it is in the web's Stryker gate (a measured mutation score would be lost). Evidence: measured,
  `grep -n naming src/web/stryker.mutate.json` → lines 7 and 15. Cost: ~240 B gz of TypeScript not deleted (the slice
  with it 1,569 B gz against 1,326 without; `naming.ts` alone 335 B gz — `measure.mjs`).
- **D4 — Do the URL segment rules move?** Options: with the tree; stay. Choice: stay. Why: they are the router's
  (ADR 0155 decision 1), the apps open nodes by id and have no fragment paths, so moving adds Kotlin no app runs.
  Evidence: remembered from the inventory (no Kotlin twin of `segmentsOf`/`resolveSlugPath`; `sharing/DeepLink.kt` is a
  different rule). Cost: `location.ts` keeps importing `compareByCreation`, now from the façade.
- **D5 — Which order rule: the browser's collator or a common one?** Options: an `expect` collator (`Intl` on JavaScript,
  ICU4J on Android, `localizedStandardCompare` on Apple, a fallback on Linux and Windows); one rule in common code. Choice:
  one rule in common code. Why: only a rule every platform runs can be pinned by one vector file; four collators would
  differ where no test looks, which is the divergence this slice removes. Evidence: measured in the sources — the app
  sorts by `name.lowercase()` (`navigation/Home.kt:27`), the web by `Intl.Collator` (`levelList.ts:13`). Cost: the web
  gives up the browser's locale data for this one order; a name in a script outside the corpus sorts by code point after
  folding; the vectors depend on the ICU of the Node that writes them once (ADR 0154's lesson), and then on the core.
- **D6 — How does a platform rule learn an application's kind?** Options: the split's `AppKinds` registry on JavaScript
  too (a JavaScript `:composition` entry installing it); a callback per kind from the web; the facts as data. Choice: data
  (`KindFacts`), from the registry on the apps and from the web's registry on the web. Why: `:composition` is no entry
  (the split's decision 5) and an entry for it would put every application's code into the eager platform file; a
  callback per comparison crosses the boundary per sibling; a folding is an enum because it is frozen into stored tokens.
  Evidence: read, `AppKinds.tokenInput` (`AppKinds.kt:41`) and `registry.tokenInputFor` (`registry.ts:942`) — two
  foldings only. Cost: a new folding is a platform change with vectors, never an application's own function.
- **D7 — Per-call nodes or a tree handle?** Options: the whole tree converted per call; a handle per tree generation.
  Choice: a handle for the move and collision rules, per-call entries for a level's order and numbering. Why: the move
  rules walk ancestry by id over the whole tree, and Expenses measured per-call re-reading as its dominant cost (306 →
  260 ms once the revisions were read once). Evidence: remembered, ADR 0155 § Consequences. Cost: the web must rebuild
  the handle when it replaces its node list (one `WeakMap`), a stale handle being the risk named.
- **D8 — What crosses back: nodes or ids?** Choice: ids, reasons as the web's string unions. Why: Vue never proxies a
  Kotlin object (ADR 0155 decision 5) and the web keeps its `TreeNode` type. Cost: one id → node lookup per answer in the web.
- **D9 — How is a creation instant carried?** Options: `Long` epoch ms; ISO string; `Double` epoch ms. Choice: `Double`.
  Why: a `Long` is emulated on JavaScript (`buildLedger` 38 ms → 25.7 s over `Long` limbs); an ISO string compares right
  only if every writer pads alike. Evidence: remembered, ADR 0155 § Consequences. Cost: the apps parse the served instant
  once per node when they build the facts.
- **D10 — How do two core façades share `:core`'s one file?** Options: one entry `@veyq/core-js/core` for every platform
  façade; each entry mapped straight to the module (each exposing the other's names); a generated entry file per façade.
  Choice: the generated entry files. Why: ADR 0155 decision 5's one entry per area with a typed surface of its own, and
  the mechanism exists on the parked branch (`cbf54e21e`). Evidence: remembered from the parked CR (its decision 4).
  Cost: a generator step in `jsPackage` and the byte-identical check over the generated files.
- **D11 — What does the package's size check bound now?** Options: the 40 KB per entry; a bound per entry grown per
  slice; one running bound on the platform module. Choice: the running bound. Why: the bundler cannot drop one façade's
  code from a shared module, so a per-entry bound measures the same file twice; the running bound adds exactly decision
  7.2's R × deleted per slice. Evidence: measured in ADR 0155 § Consequences (28,189 → 59,587 B gz with a second façade
  in the module). Cost: the search's own 40 KB stops being checked alone; the web's eager-set measurement remains the
  authority.
- **D12 — What time bound does a platform slice meet?** Options: none (decision 7.2 sets size only); the pilot's 1.5×;
  decision 7.3's 2.5× and 100 ms. Choice: 7.3's. Why: the level order runs per render and the collision per keystroke,
  so a hot rule needs a bound, and one bound for every move after the pilot is simpler to hold than one per kind of move.
  Evidence: the owner's bound of 2026-11-21 (ADR 0155 decision 7.3). Cost: a 100 ms ceiling is generous for a per-keystroke
  rule; the 2.5× against the TypeScript is the binding one there.
- **D13 — Who wins where the twins disagree?** Choice: the web, as the parked Expenses CR settled its three. Why: the
  web's rules are the documented product (inbox #407 for the order, #393 for the numbering, #389 for the collision) and
  the app's lowercase sort and single-node move are ports that stopped short. Cost: the apps change visibly (order,
  numbering), which is the alignment asked for.
- **D14 — Wait for the split or build on today's core?** Choice: wait, except phase 1. Why: `AppKinds`, `Home`, `NodeMove`
  and `DropBoxes` are files the split moves or re-homes right now; building beside it would conflict, and `KindFacts`
  must be read from the split's registry rather than grow a second one. Cost: the slice starts after the split's phase 4.

*Implementation (2026-11-22, branch `claude/node-tree` from `961f96c1f`, the split merged; each entry as above).*

- **D15 — Re-grounding phases 2–3 against the merged split.** Read: ADR 0156 and its log, the merged tree. What changed
  against this CR: the platform's module file is `kotlin_veyq_mobile_core.mjs` (not `veyq-mobile-core.mjs`) and the
  package's one `.d.mts` is `veyq-mobile-composition.d.mts`, both written by `:composition:jsPackage` (not `:core`'s); the
  parked branch's entry writer (`cbf54e21e`) read `:core`'s façades and re-exported from the one module, so it is rebuilt in
  `:composition`, reading every core module's `jsMain/**/web/` and re-exporting from that module's file. `AppKinds` is the
  registry and each application answers through `ApplicationKinds`; `NodeCreate.kt` holds no collision (the inventory was
  wrong): the app's twins are `NodeRename`, `NodeMove.move`, Memories' `MemoryCreate.check` and Depot's
  `DepotFiles.collision`, the last two folding through `AppKinds.tokenInput` (so through `KindFacts` once it reads it).
  Evidence: measured, `git grep -n "tokenInput(it.kind, it.name)" -- src/mobile`. Cost: none.
- **D16 — Phase 1's writer is the web test's write mode, not a script under `src/web/scripts/`.** The kind table is read
  from the web's registry, which only a DOM test environment fills (`src/test/setup.ts` registers the applications in a
  `beforeAll` behind `typeof window`); a `tsx` script would have to load every application's Vue modules. Deleted in phase 2
  as planned (one file, one writer: the core's `TreeVectorsTest`). Evidence: read. Cost: none after phase 2.
- **D17 — The kind table is written sorted by kind.** The web registers core, Memories, Depot, Expenses; the apps install
  Depot, Expenses, Memories (`AppComposition.applications`): sorted, both sides compare and the core writes one order.
  Evidence: measured, the core's rewrite left `git diff contracts/platform/tree/` empty. Cost: none.
- **D18 — The order's common rule is a sort key.** Each name becomes one string whose UTF-16 order is the rule's (white
  space, then a table of ICU 78's punctuation, symbols and currency signs in its primary order — 115 characters, the quotes
  it reads as one folded first —, an unlisted one after its group by code unit, a run of ASCII digits as its length without
  leading zeros then its digits, a letter by its code unit after NFKD, combining marks dropped, lower case, and `ß`, `æ`,
  `œ`, `ø`, `ł`, `đ`, `ð` read as the collator reads them); equal keys fall to creation, then id. One key per name per call,
  then native string comparison, so a 10,000-name level folds 10,000 times, not per comparison. Evidence: measured, a
  JavaScript prototype of the key against `Intl.Collator('en'|'de', { numeric: true, sensitivity: 'base' })` under Node
  22.23.3 (ICU 78.3): 0 of 25,600 ordered pairs of the corpus' 160 names differ, 0 of 90,000 pairs of 300 random names over a
  60-character pool (`npx -y node@22 proto.mjs`, scratch); the Kotlin answers every `order` case (`TreeVectorsTest`, JVM).
  Cost: the table in the module; the residual named in `LevelOrder`'s doc (a script or digit the table does not reach, a
  dotless `ı`). No visible change on the corpus (D5's list is empty).
- **D19 — A place the tree does not read takes nothing.** Against D13: the web's TypeScript asked an unread place as the
  scope root, the app refused it (`NodeMoveTest` holds the app's). The browser never lists an unread place, the server
  decides anyway, and failing closed is the registry's own answer for an unknown kind; so `MovePlan.hereBlock` refuses
  (`kind`). No vector case: phase 1's TypeScript would answer otherwise and each commit's web check stays green;
  `TreeRulesTest.aPlaceTheTreeDoesNotReadTakesNothing` holds it. Cost: none visible.
- **D20 — The applications' kind facts are data.** `ApplicationKinds.tokenInput`/`placement` are replaced by `kindFacts`
  (each kind's route, folding, placement); `AppKinds.facts` is built at install (core's two kinds and every part's, by kind),
  `AppKinds.tokenInput` and `placement` read it, and the install refuses a part whose facts are not its kinds;
  `AppCompositionTest` asks one facts entry per kind. Evidence: measured, `./gradlew :core:jvmTest :apps:*:jvmTest
  :composition:jvmTest` green, `TreeVectorsTest.kinds` (the apps' table equals the file's). Cost: the header's
  `ApplicationKinds` protocol loses two members and gains one property (the header diff, phase 2).
- **D21 — `NodeMove` keeps its API and adapts.** Its `PlacementAllows`, `Block` and `Place` stay (Swift, Android, the bridge
  and `MoveSheet`'s door read them); `start`, `trail`, `places`, `hereBlock`, `inIdentityFolder` call `MovePlan` over a
  `TreeFacts` of the given `NodeRef`s, so `MovePlan` takes the placement as a function (the door's or `KindFacts`'). Cost: a
  `TreeFacts` built per call on the apps (a list's size, the apps' trees).
- **D22 — `Naming.slugify` as loops, `fnv1a` without `Long`.** The web's collision folds every sibling's name through
  `slugify`: the per-call `Regex` and the `Map<Char, String>` became loops with the same output (held by
  `v1.json#name_folding` on both sides); `UInt.toString(radix)` goes through `Long`, so the unsigned value is printed in base
  36 through a `Double` (exact below 2^53). Evidence: measured, the crypto vectors green on the JVM. Cost: none.
- **D23 — Two forms of the collision rule, one comparison.** `SiblingNames.colliding` over a `TreeFacts` and the
  `KindFacts` (the web: each sibling's input folded once per tree generation and kind table, so a keystroke compares strings),
  and over a list the caller holds with the registry's `tokenInput` (the apps' `NodeRename`, `NodeMove.move`: null for a kind
  the build does not know, which meets nothing, as before). Cost: a second entry point, one rule.
- **D24 — The entry files, rebuilt in `:composition:jsPackage`.** Each core module's `jsMain/**/web/*.kt` is a façade; its
  `@JsExport` declarations (read from the sources, the parked writer's regular expression) become `dist/<entry>.mjs`
  (D29: no longer written) and `dist/<entry>.d.mts` (every name, types too, `from
  './veyq-mobile-composition.mjs'`: TypeScript refuses an `import` from a `.d.mts` path, TS2846, and resolves the `.mjs`
  to the composition's one declaration file). `package.json` maps `./search` and `./tree` to them; `entries.typetest.ts`
  refuses `orderLevel` through `./search` and `fold` through `./tree`; `conformance.test.ts` names the three declaration
  files. Evidence: measured, `pnpm run build && pnpm run check` in `src/packages/core-js` (all but the budget green). Cost:
  the generator's 20 lines.
- **D29 — An entry's code is the module file, its types a file of its own.** With D24's re-exporting `dist/search.mjs` the
  web's `SearchField.test.ts` went red (1 of 61: `vi.mock('@veyq/core-js/search', …)` with one export wrapped no longer
  reached the binding the test imports) — the parked Expenses branch's red, reproduced, and the CR's named risk. Measured:
  with `default` mapped straight to `kotlin_veyq_mobile_core.mjs` again (types still `dist/search.d.mts`) 61 of 61 pass. So
  the generator writes the declaration file only; `package.json` maps each entry's code to its module file. What it costs:
  at runtime an entry carries its module's other façade's names too (they always did: one module file); the separation is
  the types' and the direction guard's, which read the entry. Evidence: `pnpm exec vitest run
  src/core/search/SearchField.test.ts` in `src/web`, before and after.
- **D25 — The façade's surface.** The CR's list, plus `compareByCreation` (the web's `segmentsOf` and the drop box's page
  sort by it) and minus `compareById` (its one web caller numbered a guest's names, which `displayNames(…, byId)` does).
  Handles: `KindFactsHandle`, `TreeFactsHandle` (no member in their `.d.ts`). Moved nodes cross as the web's own objects
  (`MovedShape`: id, kind, parent, name), answers as ids. A place the session browses from (`top`) is a parameter, not part of
  the tree handle, so a source whose root differs (a guest's) needs no second handle. Cost: none.
- **D26 — Squeezing the platform module (the size probe).** First build: the platform module with both entries 33,404 B gz
  against the running budget's 31,921 (27,943 + 3 × 1,326). Measured per change (`size.sh`: the module bundled, minified,
  gzip 9, Node 22.23.3), each behaviour-neutral (the vectors green on the JVM and through the package after each): the
  collator's table a `val`, not a `const` (inlined four times) and its last code-unit-ordered characters and the currency
  signs between `¥` and `₧` left to the by-code-unit rule; a category by its two-letter `code` rather than enum entries; the
  move's blocks and reasons and the kinds' foldings as string constants rather than enums (no enum machinery, the words the
  web already spells); the façade writing plain arrays and objects (no `Pair`, no `map`); one list sort for the order and the
  numbering (no `Array.sortWith`, no `plus`, no `toList`, no library `indexOf`); a moved node a `TreeFacts.Node` (no class of
  its own); a node's token input cached on the node. 33,404 → 32,101 B gz (own code 37,094 B minified against the search
  alone's 26,271; the standard library +460). Cost: `MovePlan`'s and `KindFacts`' constants are strings, not enums (the
  apps' `NodeMove.Block` stays an enum over them).
- **D27 — The time gate, measured.** Corpus (`bench.*.test.ts`, scratch, never committed; Node 22.23.3, median of 15 after 3
  warm-ups, answers compared by digest): one folder of 10,000 siblings of every kind, and 10,000 folders six levels deep;
  the TypeScript in the phase-1 tree (`claude/node-tree` at `4c4743d16`), the façade through the web's adapter in this one.
  `orderLevel` 16.8 ms against 11.1 (1.51×), `displayNames` 5.17 against 2.64 (1.96×; a first form, sorting every sibling
  by creation and counting names in a `HashMap`, was 2.87× — one sort by the exact name, equal names by creation, replaced
  it), `collidingSibling` 0.10 against 2.78 per keystroke on a held generation (a new generation's first: 9.85, 3.5×, once
  per tree read), `movePlaces` 26.8 against 73.4, `planMove` of 1,000 into the wide folder 9.2 against 1,843; `treeFacts`
  over 20,000 nodes 1.6 ms (no TypeScript twin: the TypeScript held no index). Answers identical but `displayNames`' entry
  order (a map; its labels equal). Reading: decision 12's 2.5× binds a rule per call as the web calls it (a keystroke on a
  held generation); a generation's build is held to 100 ms only. Cost: none.
- **D28 — The size gate misses; stopped before the web's rewiring.** The CR's two bounds read one ratio two ways: phase 3's
  probe allows 4 × 1,326 = 5,304 B gz of own code (decision 12's net eager-set bound, the deleted TypeScript counted);
  decision 10's running budget in the package allows 3 × 1,326 = 3,978 (gross, nothing deleted there). Measured: own code
  4,158 B gz (32,101 − 27,943), 3.14× the deleted TypeScript — inside the probe, **180 B gz over the running budget**. Per the
  owner's mandate the miss goes to the owner with its numbers; the gate is not loosened. The web's rewiring (built, unverified,
  uncommitted on this branch) is parked on `claude/node-tree-p4-wip`. The owner's options, priced: (a) take 3.14× for this
  slice (the eager-set bound is expected to hold: own growth minus the 1,326 deleted is ~2,830 plus the adapter); (b) give up
  the collator's table (196 B gz, measured by removing it from the bundle) — names led by punctuation or a symbol outside
  ASCII then sort by code unit, a visible change, and still ~10 B gz over without a further cut; (c) the CR's cheapest cut,
  the move plan and the collision alone, the order and the numbering staying TypeScript (the order is LevelOrder's 2.1 KB
  minified of the 10.8 KB). Cost: the slice waits for the owner.

## Questions for the owner

Open (2026-11-22, D28): **the platform module's running budget misses by 180 B gz** (32,101 against 31,921; the slice's
own code 3.14× its deleted TypeScript against R = 3.0), while phase 3's probe (4×) and the time gate (D27) pass. Phases 1–3
are committed; the web's rewiring is parked, unverified, on `claude/node-tree-p4-wip`. Which of D28's options (a) take
3.14× for this slice, (b) drop the collator's table (a visible change for names led by non-ASCII punctuation, and still a
few bytes over), (c) the move plan and collision alone — or another bound?

## Definition of done

Decision 12's gate holds and is recorded in ADR 0155; the moved TypeScript is gone and its assertions accounted for;
the apps and the web answer `contracts/platform/tree/v1-vectors.json` from one Kotlin home, written by the core; the
browser pass done (and the app pass, or said why not); `@veyq/core-js`'s check, the web's check, the core's and the
apps' builds of phase 2 and every `scripts/validate_*.py` green.

## Retrospective

(Filled by the implementer.)
