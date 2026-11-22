---
id: cr-web-rights-and-plans-on-the-core
title: A node header's and a level row's plans and core's favourite, cover and bookmark rights move onto the Kotlin core for the web — `@veyq/core-js/navigation`, the TypeScript twins deleted
status: in-progress
satisfies: ["uc.platform.node-cover", "uc.platform.copy-node", "uc.moderation.report", "uc.sharing.keep-a-received-link", "en.memory.favorites"]
adrs: ["0155", "0154", "0110", "0120", "0068", "0046"]
invariants:
  - "operator-blind: the rules decide which actions a header and a row offer and in which group; no request is added, nothing new reaches the server, no key is read"
  - "code a façade reaches carries no kotlinx.serialization, coroutines, Ktor or NotOnTheWeb refusal (ADR 0155 decision 4), held by the package's slice check"
  - "no Long, Regex or HashMap on the façade's path; plain values cross, never a Kotlin class"
  - "every plan enum keeps its constants: the four apps switch over HeaderAction exhaustively (ADR 0154 decision 2), so no entry is added or removed here"
---

## Goal

ADR 0155 decision 7.2's third platform slice: **the rights and ADR 0154's plans**. The web composes a node header's and
a level row's actions with TypeScript of its own (`core/node/nodeHeader.ts`' `placeCoreActions`, `orderHeaderActions`,
`partitionHeaderActions`, `isMenuOnly`; `core/node/levelRow.ts`' `splitRowActions`, `ROW_MENU_LEAD`;
`core/node/standardRowActions.ts`' `withStandardRowActions`), and answers core's favourite, cover and bookmark rights with
it (`core/favorites/rights.ts`, `core/node/cover/rights.ts`, `core/bookmark/rights.ts`), while the four apps draw the same
plans from the core's `NodeHeader.plan` and `LevelRow.plan` (ADR 0154 decision 2). After this CR the web draws both plans
through `@veyq/core-js/navigation`, its components keeping only what a browser does — the measured fit of a header's
buttons (ADR 0110), the row's inline buttons, the labels, icons and handlers — and the TypeScript rule bodies are deleted.

## Ground (measured on `36516924f`, this worktree, before the first edit)

- **What the core gives the apps** (`src/mobile/core/…/navigation/`): `HeaderAction` (17 entries, each with its `Slot`),
  `NodeHeader.plan(rights, order, maxButtons, own)` → `buttons | menu | last`, `NodeHeader.rights(…)` (the apps' reading of
  their doors), `LevelRow.plan(rights, own)` → `menu | last`, `LevelPlan`, and each kind's order in `AppKinds.headerOrder`.
  `PlansAreTheWebsTest` (`:composition:jvmTest`) reads the web's sources for each kind's `header.order`, core's merge order
  from `coreNodeActions.ts` and the Settings order from `sections.ts`.
- **The web's twins** (the map of every plan surface: an Explore pass over `src/web/src`, its findings below and in the
  decision log): the header's merge, order and split in `NodeHeader.vue` line 120 over `nodeHeader.ts`; the row's in
  `LevelRowItem.vue` line 76 over `levelRow.ts`; core's actions built in `coreNodeActions.ts` (favourite before, copy beside
  the move, download and report after — the same merge order as `NodeHeader.core`); the six `header.order` arrays equal to
  `AppKinds.headerOrder` (the test above). Level tools, the sign-in's entries, the drawer, the share dialog's link row and a
  tile's mark are inline in `.vue` templates; the Settings sections in `features/settings/sections.ts` (a composable).
- **The running budget and the eager set before the slice** (`src/packages/core-js/src/slice.test.ts`; `rights-scratch/eager.py`
  over a `pnpm run build` under Node 22.23.3): the platform module with every entry **35,392 B gz** against its budget
  35,865; the eager set **637,425 B gz** — the share links' after-numbers exactly.
- **The TypeScript the slice deletes**, measured the node tree's way (each export bundled alone with esbuild, minified,
  every other import external, gzip level 9; `rights-scratch/measure-ts.mjs`): the plans' exports of `nodeHeader.ts`,
  `levelRow.ts` and `standardRowActions.ts` 2,500 B min / 1,012 B gz; `favoriteRights`, `coverRights`, `bookmarkRights`
  with `canCreateBookmark` 930 / 459. ADR 0155's row: 1,388 B gz. The exact set is decision D1's, re-measured at the gate.

## Plan

1. **This CR.**
2. **Contracts.** `contracts/platform/plans/v1-vectors.json`: each kind's header order (`kinds`), header plans
   (`header`: rights, order, own → buttons, menu, last), row plans (`row`: rights, own → menu, last) and core's rights
   (`rights`: favourite, cover, bookmark), written by the core (`PlanVectorsTest`, `:composition:jvmTest`, `VEYQ_WRITE_VECTORS=1`),
   read by the web's tests through its current TypeScript first (so the file is the web's answer before anything moves).
   Done when: both sides answer every case.
3. **Core** (`:core`): the differences D3–D5 settle; the rights functions the web's three files answer. Done when:
   `:core` jvmTest + macosArm64Test, `:composition:jvmTest`, `:bridge:jvmTest`, `:android:assembleDebug`, the XCFramework
   and the macOS ad-hoc build, Linux `run.py build`, Windows `run.py test` are green.
4. **The façade** — `jsMain/…/navigation/web/` (entry `./navigation`), its vectors through the shipped module
   (`src/packages/core-js/src/navigation.test.ts`), its row in `slice.test.ts`, `entries.typetest.ts`. **Gate:** own code
   ≤ 3.0 × the deleted TypeScript's gzip and the eager set measured; each hot rule ≤ 2.5× the TypeScript and ≤ 100 ms. A
   miss stops here.
5. **Web.** One adapter (`core/node/plans.ts`) draws each plan whole; the TypeScript bodies and their unit cases leave,
   each case accounted for; the browser pass against a baseline build.
6. **Docs and harvest.** ADR 0155's row and § "The rights and plans' slice" with this log; ADR 0154, `src/web/CLAUDE.md`,
   `src/mobile/CLAUDE.md`; this CR deleted.

## Decision log

Each entry: the question, the options, the choice and why, the evidence (measured with its command, or remembered), the
cost. The refine's entries first.

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
  web's TypeScript answered all 200, after it the core's (measured, `plans.vectors.test.ts`, `PlanVectorsTest`). The apps do
  not call `CoreRights` yet: their `NodeHeader.rights` takes the same answers from their doors (favoriteKnown, coverOffered).
  *Cost:* until the apps read through it too, the three rules live once in the core but are read by the web alone.
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
