---
id: cr-web-share-links-on-the-core
title: The share-link rules move onto the Kotlin core for the web — `@veyq/core-js/sharing`, the TypeScript twin deleted, an edited link's last day counted from its creation in every app
status: in-progress
satisfies: ["uc.sharing.keep-a-received-link", "uc.sharing.browse-a-received-share-in-place", "uc.sharing.share-public-viewer", "uc.admin.capability-requirements"]
adrs: ["0155", "0156", "0150", "0121", "0120", "0088", "0046"]
invariants:
  - "operator-blind: the rules decide what a pasted text is, which day a picker offers and whether a share is shown in place; no key leaves the link it came in, no request is added, nothing new reaches the server"
  - "code a façade reaches carries no kotlinx.serialization, coroutines, Ktor or NotOnTheWeb refusal (ADR 0155 decision 4), held by the package's slice check"
  - "a Long never crosses the façade: an instant is a Double of epoch milliseconds, a day an Int of epoch days"
  - "the apps' enums (ReceivedLink.Problem, ShareInstance.Refusal) keep their constants: Swift and Android switch over them exhaustively"
---

## Goal

ADR 0155 decision 7.2's second platform slice. Three rule groups are written twice today — TypeScript in the web and
Kotlin in `:core` — and answered differently in one place:

- **a received link** — its check and normalization, its host, its report link and the share page's `&report`
  parameter (web `core/bookmark/bookmarkLink.ts`; core `sharing/ReceivedLink`), both held to
  `contracts/platform/share-links/cases.v1.json`;
- **a link's last end day** under a tenant's day limit (web `core/sharing/linkRules.ts` `lastExpiryDay`; core
  `session/LinkForm`'s `lastDay`) — **where they differ: inbox #1057.** The web counts from when the link was made, as the
  server does; the core counts from today, so an app editing an older link offers an end the save then refuses;
- **an instance's embeddability** — whether a bookmark's page shows a share in place (web `core/sharing/shareInstance.ts`
  `embeddability`, `olderMajorsInWindow`) and whether an app opens a link's instance at all (core `sharing/ShareInstance`,
  `session/ClientWindow`), which read the same answer of `/api/setup`.

After this CR the web draws all three through `@veyq/core-js/sharing`, its TypeScript rule bodies are deleted, and the
apps' link form counts an edited link's last day from its creation through the same function.

## Ground (measured on `b8c2228de`, this worktree, before the first edit)

- The deleted TypeScript, measured the node tree's way (each export bundled alone with esbuild, minified, every other
  import external, gzip level 9; `links-scratch/measure-ts.mjs`): `bookmarkLink.ts`' exports + `lastExpiryDay` +
  `embeddability` + `olderMajorsInWindow` = **2,678 B min / 1,253 B gz** (ADR 0155's row: 1,251). Without
  `olderMajorsInWindow` and `MAX_LINK_LENGTH` 1,193; `bookmarkLink.ts` alone 795.
- The running budget before the slice (`src/packages/core-js/src/slice.test.ts`): 27,943 + 3.14 × 1,326 = 32,106 B gz;
  the platform module with every entry measured 32,101. This slice's allowance at R = 3.0: **3 × 1,253 = 3,759 B gz**, so
  the budget after it is 35,865.
- The eager set (`main` + `index.html`'s modulepreloads, gzip -9 each; `links-scratch/eager.py` over a `pnpm run build`
  under Node 22.23.3): **634,769 B gz**, the node tree's after-number exactly.
- The bookmark's sealed meta stays TypeScript: decision 4's JSON seam is not in `:core` (`json/JsonTree.kt` exists only
  on the parked `claude/p2-impl`, `git show --stat c5793db14`; no `expect` JSON seam under `src/mobile/core/src`).
- `LinkForm.start` is called only by `composition/ShareSheet.kt` (three sites); Swift, Android and the bridge read the
  form, never start it — so one change in the core fixes #1057 for all four apps.
- The web callers: per keystroke `BookmarkCreateDialog.vue`'s `parseShareLink`; per list `bookmarkMeta.ts`' `keptLink`
  (each bookmark's meta) and `bookmarkRows.ts`' `hostOf` (each row), `guestStops.ts`' `lastExpiryDay` (each stopped
  link's row); once per page `openInApp.ts`, `ShareView.vue`'s `asksForReport`/`withoutReport`, `BookmarkEmbed.vue`'s
  `embeddability`, the three share dialogs' `lastExpiryDay`.

## Plan

1. **Contracts.** `cases.v1.json` gains `lastDay` (the last end day by maxDays, creation instant, now and zone; one
   edited link made days before now — the case #1057's code fails) and `embed` (a setup answer as read, the majors
   spoken, this instance's switch; the app's answer and the page's), and parse cases where the two parsers met no case
   yet. The core's `ReceivedLinkTest` grows into `ShareLinkVectorsTest`, which answers every section; the web's tests
   read the new sections. Done when: both sides answer every case, and the edit case is red on the core before phase 2.
2. **Core** (`:core`). `sharing/LinkDays.lastEndDay` (the rule), `LinkForm.start` taking `now` and counting an edit from
   the link's `createdAt` (a day past the last no longer throws in `withEndDay`), `ShareSheet` passing `now`;
   `ShareInstance.refusal`/`embedding` over what was read, `decide` adapting the API model; `ReceivedLink.asksForReport`,
   `withoutReport`, `hostOf`. Done when: `:core` jvmTest + macosArm64Test, `:composition:jvmTest`, `:bridge:jvmTest`,
   `:android:assembleDebug`, the XCFramework and the macOS ad-hoc build, Linux and Windows `run.py test` are green.
3. **The façade** — `jsMain/…/sharing/web/` (entry `./sharing`), its vectors through the shipped module
   (`src/packages/core-js/src/sharing.test.ts`), the slice row in `slice.test.ts`, `entries.typetest.ts`. **Gate:** own
   code ≤ 3,759 B gz, the eager set measured; time ≤ 2.5× the TypeScript and ≤ 100 ms on the keystroke and list paths.
   A miss stops here.
4. **Web.** `bookmarkLink.ts`, `linkRules.ts` and `shareInstance.ts` keep their exports' signatures and hold no rule;
   the TypeScript bodies and their unit cases leave, each case accounted for; the browser pass.
5. **Docs and harvest.** ADR 0155's row and § "The share links' slice" with this log; ADR 0150, the use cases'
   coverage, `src/web/CLAUDE.md`, the inbox row #1057 deleted; this CR deleted.

## Decision log

- **D1 — The slice's set.** *Q:* which exports leave? *Options:* `bookmarkLink.ts` only (795 B gz); the ADR row's three
  groups; the groups plus the stateful callers (`linkRules()`, `readShareInstance`, `EMBED_MAJORS`). *Choice:* the three
  groups as ADR 0155's row names them, with `olderMajorsInWindow` (the core's `ClientWindow` holds its twin) — 1,253 B gz;
  `linkRules()` (a capability read), `readShareInstance` (a fetch) and `EMBED_MAJORS` (the list of the web embed's own
  adapters, held by the web's VERSION tripwire) stay, being no rule but the browser's state. *Evidence:* measured,
  `measure-ts.mjs` above. *Cost:* the budget is R × 1,253, not × 1,193.
- **D2 — The bookmark's meta stays out.** *Q:* does the sealed meta join? *Choice:* no — the JSON seam is only on the
  parked branch (measured, Ground). *Cost:* `bookmarkMeta.ts` keeps its codec in TypeScript until Expenses lands the seam.
- **D3 — The entry is `@veyq/core-js/sharing`.** The façade's package is `me.raddatz.veyq.mobile.sharing.web`;
  `:composition:jsPackage` names an entry by that directory, and the web's direction guard reads an entry named for a core
  area (`core/sharing`) as core's. Remembered from ADR 0155 § The node tree's slice: the entry's code is the module file,
  only its types its own `.d.mts`.
- **D4 — #1057's rule.** *Q:* where does the last day come from? *Options:* the caller hands `LinkForm` a last day; the
  form counts it from the edited link's `createdAt` and the `now` it is given. *Choice:* the form — `LinkDays.lastEndDay
  (maxDays, madeAt) = dayOf(madeAt + maxDays × 24 h) − 1`, the web's arithmetic: a picked day is sent as its end
  (`LocalDays.endOf`), which must not pass the server's `createdAt + maxDays`. `start` takes `now` in place of `today`
  (today is `LocalDays.dayOf(now)`), so a caller cannot hand an edit a today-counted day. A link already past its last day
  keeps that day as the end (the web's picker caps the same way); `withEndDay` no longer throws on the empty range it
  would now meet. *Cost:* `LinkForm.start`'s signature (one caller, `ShareSheet`).
- **D5 — The zone.** The rule reads the device's zone through `LocalDays` on every target, as the form already did. A
  vector names its zone; the JVM test sets the default time zone per case (`WallClock.jvm.kt` reads
  `TimeZone.getDefault()`), the package's test sets `process.env.TZ` (Node re-reads it). *Cost:* none on the apps.
- **D6 — One embeddability rule, two answers.** The app's `Refusal` enum keeps its three constants (Swift and Android
  switch over it); `ShareInstance.refusal(major, capabilities, speaks)` is the reading both use, `embedding(on, …)` adds
  the page's two checks (this instance's switch first, `.embeddable` last) and answers in the web's words. The vectors
  carry both answers per case.
