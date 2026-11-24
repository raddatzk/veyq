---
id: cr-live-journal
title: A journal per Memory and Chapter — a sealed Yjs log whose rules are the Kotlin core's, drawn by the web first, written by members and READ_WRITE guests on the poll, with member-made snapshots and restore
status: planned
satisfies: ["uc.memory.journal", "en.moderation.guest-write-record"]
adrs: ["0133", "0155", "0154", "0153", "0134", "0093", "0033", "0109", "0082", "0125", "0088", "0073", "0061", "0072"]
invariants:
  - "the server stores and serves only sealed journal bytes; no plaintext, embed id or Yjs client id leaves a client unsealed (ADR 0133 decision 3)"
  - "only a member writes a snapshot revision or reads an earlier one; the share route offers neither (ADR 0133 decisions 4, 7, 8)"
  - "the server takes snapshot n + 1 only over n, and no update under a K_jrev minted below the scope's epoch (ADR 0133 decisions 4, 11)"
  - "a journal update bumps the journal's own list of the node's items revision — never the gallery's, never the scope tree's (ADR 0133 decision 3)"
  - "a guest's journal updates leave one guest-write record row per writing session, and every update keeps its own server time (ADR 0093 § 10)"
  - "a link guest is served each contributor's alias, never a name or a member or identity id, and the journal's alias key is never served (ADR 0133 decision 9)"
  - "every journal rule a client runs is the Kotlin core's, drawn by the web through @veyq/core-js/memories; the web holds no second implementation of it (ADR 0155 decision 1, ADR 0133 decision 2)"
  - "the journal's Kotlin lands in the journal's lazy chunk: the eager set grows by at most 1 KB gz (ADR 0155 decisions 2 and 7)"
---

> **Status: planned, deferred by the owner 2026-11-21** — the journal waits behind the web's move onto the core
> ([ADR 0155](../docs/decisions/0155-the-web-draws-over-the-kotlin-core-application-by-application-after-a-measured-pilot.md)
> decision 7: the core's split, built 2026-11-22 ([ADR 0156](../docs/decisions/0156-the-kotlin-core-is-split-into-a-platform-module-and-one-module-per-application.md)),
> then Expenses' move, ADR 0155 § Expenses' move, built 2026-11-24); no date set.

## Goal

Build [ADR 0133](../docs/decisions/0133-a-memorys-journal-is-a-sealed-crdt-log-whose-snapshots-only-members-write.md)
on [ADR 0033](../docs/decisions/0033-live-media-updates-revision-polling.md)'s poll, **core first**
([ADR 0155](../docs/decisions/0155-the-web-draws-over-the-kotlin-core-application-by-application-after-a-measured-pilot.md)):
each Memory and Chapter gets a **Journal** — markdown in a CodeMirror source editor with live preview, embeds as cards —
that members and `READ_WRITE` guests write at the same time (each sees the others' edits within the poll's 2 s while the
editor is open), `READ` guests read, members' clients compact into snapshot revisions under fresh keys, members list
and restore earlier ones for the trash retention, a guest leaves one evidence row per writing session
([ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ 10), and export, import and search carry the text. Every rule a client runs is written once in the Kotlin core
(`memories/journal/`) and drawn by the web through `@veyq/core-js/memories`; the web keeps Yjs (the engine), CodeMirror
(the editor), its Vue UI, sealing through `@veyq/crypto` and the poll. The apps draw the same core in inbox #855 (V2
block I, after this CR). Sub-second delivery and "who is here" are `cr-live-stream`'s; this CR's poll stays the
fallback.

## Context & constraints

- **Re-grounded 2026-11-21** against ADR 0155 (the web draws over the core; owner 2026-11-20: the core holds all
  functionality, every client only draws), [ADR 0154](../docs/decisions/0154-every-app-draws-the-cores-plans-through-a-core-ui-its-applications-plug-into-as-the-webs-do.md)
  (gates, plans, notices with their lifetimes, formats from the core),
  [ADR 0153](../docs/decisions/0153-the-apps-queue-a-persons-own-additions-made-offline-sealed-to-the-device-and-send-them-through-the-online-write-path-expenses-joins-the-store.md)
  (the apps' outbox) and the V2 order (`docs/inbox.md` § V2, block I: this CR first, then #855). What changed was
  proposed as an amendment of ADR 0133, accepted by the owner on 2026-11-21 and folded into its decisions: the rules in
  the core and the engine a port (decision 2), the snapshot check normalised through the checker's engine (decision 4),
  the pseudonym lists in the words catalog (decision 9), an unsent update in the apps an offline write (decision 11).
- **Where the model lives — decided: (b), the engine per platform, everything around it in the core.**
  - *(a) the engine in Kotlin too* is rejected for lack of an engine: no maintained Kotlin engine reads Yjs update v1 on
    the core's targets (measured 2026-11-21, ADR 0133 § Context's list: `gh api repos/y-crdt/ykt` → last push
    2023-01-17, README "currently not active"; `gh api repos/hwisu/yks` → JVM 21 only, one contributor, created
    2026-07-05, GitHub Packages behind a token; `gh api repos/y-crdt/y-octo/readme` → Kotlin/Swift bindings "coming
    soon"). `yrs` (MIT, v0.28.2 of 2026-11-19, `gh api repos/y-crdt/y-crdt/releases`) reaches the web only as `ywasm`,
    against the CSP (ADR 0155's Alternatives) and without `y-codemirror.next`, which binds a Yjs `Y.Text`. A port of
    our own would be a third CRDT implementation whose divergence corrupts journals for good.
  - *(c) as planned, TypeScript now, ported later* is rejected by ADR 0155 decision 1: the journal has no TypeScript yet,
    so core first costs no deletion, and TypeScript now is the twin that decision deletes.
  - *(b)* keeps the engine a port of four byte functions (merge, state of base + updates, text of a state, the edit
    from a state to a text) — the form ADR 0155's search façade already takes for the browser's collator
    (`src/mobile/core/src/jsMain/kotlin/me/raddatz/veyq/mobile/search/web/SearchWeb.kt:114`, `compareNames`), so no
    Yjs object crosses the façade — and Yrs through `yffi` behind the same port in #855.
- **Size (ADR 0155 decision 7, the flows gate of its decision 8).** Remembered estimate, to be measured in phase 5: the
  search façade's 1,185 Kotlin lines (`wc -l` over `search/{Fold,Query,Results,Model}.kt`, `session/LocalDays.kt`,
  `jsMain/…/search/web/*.kt`) are 8,754 B gz of own code (ADR 0155 § Consequences); the journal's rules are of that
  order. They count as Memories' own code, ≤ 25 KB gz, in the journal's lazy chunk. The risk is **granularity**: the
  package compiles one module for every entry (`src/packages/core-js/package.json:9-11`: `./search` →
  `./dist/veyq-mobile-core.mjs`), and a module a bundler cannot split lands where its first eager importer is — the
  search's chunk. Measured since on Expenses' parked move: one module put Expenses into the search's entry (ADR 0155
  § Consequences), so the core splits first into a platform module and one module per application, each its own
  JavaScript file ([ADR 0156](../docs/decisions/0156-the-kotlin-core-is-split-into-a-platform-module-and-one-module-per-application.md), built; its measurement: the platform code a façade needs must leave the file every
  entry imports); the journal's Kotlin lives in `:apps:memories`. The façade crosses no flow: step
  functions over an opaque handle; the web's 1 s tick, the poll and the jitter's timer stay the web's
  (`createLivePoller`), the core decides what each tick does.
- **ADR 0133's library facts** (Yjs 13.6.33 update v1 read by `@y/y` 14 rc.29; order-independent
  `encodeStateAsUpdate`; a withheld delete-only update unnoticed by Yjs) hold for the pinned version; measured again
  2026-11-21 only from the registry: `yjs` `latest` is still 13.6.33, `@y/y` `beta` is now 14.0.0-rc.30
  (`curl -s https://registry.npmjs.org/yjs`, `…/@y%2Fy`, `dist-tags`). Re-run the probes in phase 4 if the pinned Yjs
  differs, plus the fixed point ADR 0133 decision 4's normalised check needs (encode → apply → encode, the same bytes).
- **Measured 2026-11-21 on `claude/windows-cr-f6c73c` (`8b760b0f3`), the two facts the design leans on, spot-checked
  by reading the code — both still hold:**
  - Core's items revision is one row per node: `veyq_node_items_revision` keyed by `node_id` alone
    (`src/backend/core/src/main/resources/db/migration/V202611070831__core_baseline.sql:325-330`); `bump(scopeId,
    nodeId, deleted)` takes no list (`src/backend/core/src/main/kotlin/me/raddatz/veyq/core/items/NodeItemsRevisions.kt:105`);
    no later core migration touches it (`git grep -n items_revision -- 'src/backend/**/db/migration/*.sql'` → only the
    baselines). Its callers: Expenses' `PotRevisionStore`, `PotNodeRekeyParticipant`, `ExpensesErasures`,
    `PotModeration` and Memories' media (locator below).
  - The guest-write record writes one row per write: `GuestWriteRecords.record` persists a fresh row each call
    (`src/backend/core/src/main/kotlin/me/raddatz/veyq/core/moderation/guestwrite/GuestWriteRecords.kt:39-53`), the
    table has no last-time or count column (`V202611070831__core_baseline.sql:1108-1120`); writers in Memories', Depot's
    and Expenses' handlers (locator below); `GuestWriteRecordedTest` at
    `src/backend/src/test/kotlin/me/raddatz/veyq/core/moderation/guestwrite/GuestWriteRecordedTest.kt`.
- Measured 2026-11-13 and not re-read today (re-read before the phase that uses each): Memories' schema has a baseline
  (`apps/memories/.../V202611070832__memories_baseline.sql`, still its only migration: `ls
  src/backend/apps/memories/src/main/resources/db/migration/`), so tables and the list key arrive in **new**
  migrations; member requests carry a bearer header (`src/web/src/api/client.ts`), the share API is token-less;
  `src/web/src/shared/markdown.ts` is the one renderer and the Trusted Types sanitizer (ADR 0117), without embeds; the
  live loop is `core/items/itemsLive.ts` over `shared/state/livePoller.ts`; Expenses' `potSession.ts` is the nearest
  user of sealed revisions.
- `src/web/package.json` holds no CodeMirror and no Yjs (`grep -c -E 'yjs|codemirror' src/web/package.json` → 0) and
  depends on `@veyq/core-js` (`:33`). npm (2026-11-21): `yjs` 13.6.33, `y-codemirror.next` 0.3.6 (peer `yjs ^13.5.6`,
  `@codemirror/state`/`view` `^6`), `@codemirror/view` 6.43.15, `@codemirror/state` 6.7.6, `@codemirror/lang-markdown`
  6.5.2 — all MIT, none deprecated. CI's `pnpm_install_no_deprecated.py` gates them.
- **Not measured at refine (no build on this machine while another session builds):** the exit codes of `./gradlew
  check` (backend), the core's `./gradlew :core:jvmTest` (`src/mobile`), `@veyq/core-js`'s and the web's `pnpm run
  check`. Each phase records its gate's exit code on the tip before its first edit (changes/README § A done-when that
  names a gate).
- Out: the apps' drawing (#855, which also adds the journal to ADR 0153's queue as ADR 0133 decision 11 says), a Memory's view
  of its Chapters' journals (#853; nothing here may assume one journal per Memory only), live cursors (#854).

## Decisions (technical, settled here)

1. **Contract — Memories' journal, two new spec files on new tags:** `contracts/api/memories/journal.openapi.yaml`
   (member) and `share-journal.openapi.yaml` (link guest).
   - `getJournal` (`GET …/scopes/{scopeId}/nodes/{nodeId}/journal`) — ADR 0033's conditional read (`$ref` the shared
     `ItemsIfNoneMatch`, `ItemsSince`, `ItemsLimit`, `ItemsETag`, `ItemsNotModified`) with a `knownRevision`
     parameter: the current snapshot revision's row (number, epoch, wrapped `K_jrev`, folded position, writer, time,
     sealed state and projection) only when `knownRevision` differs; the predecessor's wrapped `K_jrev` while its
     tail is served; one page of updates past the cursor (`position`, update id, snapshot revision, writer, time,
     sealed envelope); the contributors (writer ids with their ordinals); `journalRevision`, `more`, `cursor`. No
     `deleteRevision` or id set: updates are never removed from a served tail.
   - `appendJournalUpdates` (`POST …/journal/updates`) — the snapshot revision and epoch the writer sealed under, up
     to 16 updates (`updateId`, sealed envelope), the positions answered. Refusals: `409 stale_revision` (replaced),
     ADR 0109's `409 stale_epoch` / `node_rekey_pending`, `409 journal_tail_full`, `413` past the cap, `451`,
     the switch's refusal.
   - `writeJournalSnapshot` (`POST …/journal/snapshots`) — member only: `continues` (n), the new revision's wrapped
     key and epoch, folded position, the two seals. `409 stale_revision` unless `continues` is current.
   - `listJournalRevisions`, `getJournalRevision` — member only: the kept revisions (number, time, writer, size
     class) and one revision's seals, wrapped key and its updates (paged), for the history, the restore and the
     replay past a snapshot that does not open.
   - `rekeyJournalRevisions` — the node re-key's re-wrap of every kept `K_jrev` (Expenses' revision re-wrap's shape,
     ADR 0109 decision 3), refusing any change of a body.
   - The share file: `getShareJournal` (a `READ` or `READ_WRITE` guest; ADR 0033 decision 7's digest tag; every writer
     as its contributor ordinal, no member or identity id anywhere; the contributors as `{ ordinal, alias }`, the alias
     base64 of 8 bytes (ADR 0133 decision 9), and `selfOrdinal` for a `READ_WRITE` guest who wrote) and
     `appendShareJournalUpdates` (`READ_WRITE` only).
   - Classify every new operation in `OperatorBlindProbeTest`; state `ModuleCompositionTest`'s reds, never stub.
   - The export schema `contracts/export/memories/content.schema.json` gains a node's `journal` (markdown, embed
     tokens rewritten to exported paths).
   - The core's generated client (`src/mobile`, from `contracts/api`) gains the operations with the web's: the apps
     call none of them before #855, but the generation is one.
2. **Seal purposes (crypto vectors, `contracts/crypto/vectors/v1.json`, additive):** the wrap `journal-revision-key`
   (`K_jrev` under `K_node`, bound to scope, node, revision — `expense-entry-key`'s shape) and field purposes
   `journal-update` (bound to node, revision, update id), `journal-state` and `journal-projection` (bound to node,
   revision). Added to **both suites' tables** in the same phase — `src/packages/crypto/src/seal.ts`'s `SEAL_PURPOSES` /
   `FIELD_PURPOSES` and the core's `crypto/Contexts.kt` (where `expense-entry-key` stands at `:38`) — checked so no
   purpose sits in two, the vectors from an independent implementation as the note requires. The envelope's plaintext
   is **not** a crypto vector: it is the core's codec (decision 9), sealed as opaque bytes.
3. **Core (backend): a list key on the items revision.** `veyq_node_items_revision` gains `list` (primary key
   `(node_id, list)`; existing rows are the default list) in a new core migration; `bump`/`read` take the list with the
   default for today's callers; `NodeItemsRevisionBumpsTest` and the lock-order tests extend to the journal's writers.
   Erasure still deletes every row of the node.
4. **Memories' tables** (new migration): `veyq_journal_revision`, `veyq_journal_update` (indexed `(node_id, position,
   id)`), `veyq_journal_contributor` (node, writer, ordinal; written with a writer's first update) and
   `veyq_journal_alias_key` (node, 32 random bytes from the server's CSPRNG, minted in the transaction of the first
   contributor row, never served, deleted with the journal) as ADR 0133 decision 3; a writer's alias is the first 8
   bytes of `HMAC-SHA-256(key, kind ‖ writer id)` (kind `m` for a member, `g` for a link identity), computed when
   served; an update's position is the journal list's bump, stamped in the writer's transaction (ADR 0033 decision
   3). Both carry their padded sizes; Memories' storage-usage term sums them (ADR 0073 § 4's per-application term).
5. **Numbers:** update ≤ 64 KiB sealed, classes 256 B / 1 KiB / 4 KiB / 16 KiB / 64 KiB; state ≤ 4 MiB, projection ≤
   1 MiB, powers of two from 4 KiB; tail cap 5,000 updates or 4 MiB, whichever first; a writer's rate 4 updates/s with
   a burst of 20 (core's token bucket, ADR 0052); compaction past 500 tail updates, on editor close after writing, and
   first after a rotation, with 0–5 s jitter. Constants live once each: the caps in the contract, the server's
   behaviour in one backend Kotlin file, **every client's behaviour in one core file**
   (`memories/journal/JournalNumbers.kt`) — the web has no copy.
6. **Retention:** a structural purge (ADR 0021) deletes a superseded revision and its updates once the node trash
   retention has passed since its replacement, never the current one or its predecessor, never one with a guest's
   update before the guest-write record's N, never one a hold pins.
7. **Guest-write session rows (backend core):** `GuestWriteRecords` gains a session write — find the identity's open
   row for the journal with the same address, port and `Origin` whose last time is under 15 minutes old, under a lock,
   and extend it (last time, count, expiry); else insert. New nullable columns (`last_observed_at`, `write_count`) in a
   core migration; every existing row reads as a session of one. The purge, the objection's erasure, the hold copy,
   the Art. 18 export (`ModerationExportGuestWrite` gains first/last/count) and admin-web's and `veyq-admin`'s views
   follow. `GuestWriteRecordedTest` lists `appendShareJournalUpdates`.
8. **Platform hooks in Memories (backend):** the node re-key participant re-wraps `K_jrev` (as
   `PotNodeRekeyParticipant`); the erasures and subtree eraser delete the journal; the reportable-items seam gains the
   journal as an `ITEM` (its keys: the current and the predecessor's `K_jrev`); the hold copies the reported revisions
   and updates; a content block refuses append, snapshot and restore with `451`; `memories.journal` and Memories off
   refuse writes, reads stay.
9. **The journal's model — the Kotlin core, `src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/memories/journal/`**
   (ADR 0133 decision 2). No coroutines, serialization or Ktor in anything below (ADR 0155 decision 4; the package's
   slice check reads the shipped bundle):
   - `JournalEngine` — the port: `merge(updates)`, `state(base?, updates)`, `text(state)`, `editTo(state, text)`, bytes
     in and out. The core's tests run over a played engine; the engine-bound vectors over Yjs in phase 5 (and Yrs in
     #855).
   - `JournalEnvelope` — the plaintext codec: `v`, Yjs client id, sequence, update, padded to its class (decision 5).
   - `JournalLog` — the state a client keeps for one open journal: apply in the server's order; the client-id pin to
     the server-attested writer (a guest's client: the contributor ordinal); the per-client-id sequence check, its
     *incomplete* state and the held updates; an undecodable update skipped and marked; a snapshot that does not open
     skipped for the newest that does with the kept log replayed. In: opened envelopes with position, update id and
     writer; out: the update bytes to apply in order, and what the UI must say.
   - `JournalWriter` — local updates buffered, the flush (at most one envelope a second, merged through the engine;
     one over the cap answered as *too large*, so the web's editor splits a paste above it into consecutive inserts),
     the re-seal set after `409 stale_revision`; an update the server refuses for good ends writing under that client
     id (the editor turns to the read view; ADR 0133 decision 11).
   - `JournalCompaction` — when (the three triggers of decision 5, the jitter drawn from a source the caller hands),
     what the snapshot folds (state, projection, folded position, `continues`), and the check before compacting over a
     predecessor and of every snapshot a reader holds the predecessor of, **normalised through the reader's engine**
     (ADR 0133 decision 4); a lost race (`409`) drops the snapshot.
   - `JournalRestore` — the edit from the current state to a kept revision's projection, then a compaction.
   - `JournalRights` — a gate (ADR 0154 decision 1): from the viewer (a member; a `READ` or `READ_WRITE` guest), the
     switch and a block, whether it edits, compacts, reads the history and restores, or only reads. A `WRITE_ONLY`
     link has no journal (ADR 0133 decision 6).
   - `JournalSaid` — the journal's notices, each with its `NoticeKind` lifetime (ADR 0154 decision 4): incomplete,
     snapshot mismatch (naming the writer), an update that did not decode, a restore done (with Undo for the window),
     the tail full, a refusal (`451`, the switch), offline-ish transport failure.
   - `JournalPlans` — ADR 0154 decision 2's plans: the editor's tools in order (heading, list, embed with its kinds
     media / chapter / map / track) and the history's actions (preview, Restore, Undo by restoring the one before) as
     enums, each drawn whole and in order.
   - `JournalPeople` — the contributors' rows: for a member, each writer's kind and id (the client names members and a
     guest's chosen name, as everywhere); for a guest, the pseudonym (ADR 0133 decision 9) composed by the core — animal
     from alias bytes 0-1, adjective from bytes 2-3, colour index from byte 4, the German adjective agreeing with the
     animal's gender, equal names numbered in ordinal order with a writer's suffix kept, the guest's own row "You" (de
     "Du") — over the catalog's lists (decision 13).
   - `contracts/memories/journal/v1-vectors.json`, **written by the core's** `JournalVectorsTest` (ADR 0155 § Consequences:
     the core's output is the one source): the envelope's bytes with padding, scripted logs (order, pin, gap, skip,
     replay), the flush's bounds, the compaction decisions, the rights table, the pseudonyms in EN and DE with
     numbering. The engine-bound cases (an honest and a forged snapshot, the fixed point, a restore's edit) carry bytes
     a pinned Yjs made, generated once by a script in `src/packages/core-js` and run over each engine.
10. **The web's façade — `src/mobile/core/src/jsMain/kotlin/me/raddatz/veyq/mobile/memories/journal/web/`,
    `@veyq/core-js/memories`** (ADR 0155 decision 5): `JournalWeb.kt` and `Shapes.kt` as the search's — plain values,
    external interfaces in the `.d.ts`, no `any`; one opaque handle per open journal (`JournalHandle`, held `markRaw`);
    the engine handed in once as four functions over `Uint8Array`. The web's direction guard reads
    `@veyq/core-js/memories` as Memories' (`features/memories` only). The package's slice check gains the entry, and
    its check runs the journal vectors through the shipped module over the lockfile's `yjs` (a devDependency pinned to
    the web's exact version).
11. **The web — `src/web/src/features/memories/journal/`, lazy-loaded with its dependencies on first open:** the Yjs
    engine adapter (the four functions); sealing and opening the core's envelopes, states and projections with
    `@veyq/crypto`'s new purposes; the poll at 2 s while an editor is open and visible and ADR 0033's cadence otherwise,
    the 1 s tick and the jitter's timer on `itemsLive.ts`/`createLivePoller`, each tick asking the façade what to do;
    `Y.Doc` applying what the façade orders. No journal rule in TypeScript: a test of the web asserts only drawing and
    wiring. Yjs pinned to an exact version.
12. **Rendering:** `shared/markdown.ts` gets a block-token hook (`::name:args`); Memories registers `media`,
    `chapter`, `map`, `track` (core never knows them, ADR 0079). Cards in the editor are CodeMirror widgets over the
    same renderers; a placeholder for what does not open; maps under the one consent. The token grammar (parse a token
    line into its kind and ids, rewrite ids for export) is a rule: the core's (`memories/journal/JournalTokens.kt`, in
    the vectors), drawn by the renderer hook.
13. **Words:** the pseudonym lists (64 adjectives × 64 animals, EN and DE, each German animal with its gender and each
    German adjective a regular stem; friendly, nothing a reader could take as a judgement of a person) are `core_text`
    keys of `src/mobile/words/words.json` (ADR 0133 decision 9; `generate_words.py` writes `CoreText.kt`; a test holds both
    lists to 64 distinct words per language); the façade takes the language. The web's other journal words (the tab,
    the tools, the history, the notices, the banners) are the web's i18n in EN and DE; the same keys and texts join
    `words.json` with #855, so the CR lists them in its phase 7 commit message as the table #855 copies. Product words
    (owner 2026-11-21, [ADR 0086](../docs/decisions/0086-one-word-per-meaning.md)'s table): **Journal** (de *Tagebuch*)
    for the tab and the text, **History** (de *Verlauf*) for the kept snapshot revisions.
14. **UI** (reuse first, `ui-web-styleguide.md` § Reuse first): a **Journal** tab on a Memory's and a Chapter's detail
    (beside Chapters/Map); the editor drawing `JournalPlans`' tools; the read view where `JournalRights` says read; a
    contributors popover drawing `JournalPeople`' rows (a member's page: names as elsewhere; a guest's: the core's
    pseudonyms); a **History** panel for members drawing the history's plan (list, preview, Restore with a
    confirmation, Undo); the notices from `JournalSaid` at their lifetimes; a guest's editor shows
    `GuestWriteRecordNotice`. Times and sizes in the history through the web's formatters (`shared/formatBytes.ts`, the
    date styles), which ADR 0154 decision 5's vectors already hold to the core's. **The apps' Journal entry on a
    Memory's page** (an ADR 0154 plan entry every app must draw) is #855's: added now, it would force every app to draw
    a page that does not exist; the web's tab is the web's own until Memories' page moves (ADR 0155 decision 7).
15. **Export/import/search:** the exporter merges and writes `journal` per node with tokens rewritten by the core's
    `JournalTokens`; the importer writes a first snapshot; search registers the journal as a document kind over the
    projection (ADR 0125 stage 8b), through the core's search as the other kinds are.

## Inventory (locators — run, read as lists)

| What | Command |
|---|---|
| Items-revision callers and tests | `git grep -n -e "items\.bump" -e NodeItemsRevisions -e veyq_node_items_revision -- src/backend` |
| Guest-write record writers, readers, export, views | `git grep -ln -e GuestWriteRecord -e guest_write_record -e ModerationExportGuestWrite -- src/backend src/admin-web src/web tests` |
| Node re-key participants (shape to copy) | `git grep -ln NodeRekeyParticipant -- src/backend` |
| Reportable items and the hold's contributors | `git grep -ln -e ReportableItems -e HoldContributor -- src/backend` |
| Seal purpose tables, both suites, and vectors | `git grep -n -e "SEAL_PURPOSES" -e "FIELD_PURPOSES" -- src/packages/crypto/src/seal.ts`, `git grep -n "expense-entry-key" -- src/mobile/core/src/commonMain` and `contracts/crypto/vectors/v1.json` |
| The search façade (the shape to copy) and the package's entries and slice check | `ls src/mobile/core/src/jsMain/kotlin/me/raddatz/veyq/mobile/search/web/`, `cat src/packages/core-js/package.json`, `src/packages/core-js/src/slice.test.ts` |
| The web's users of `@veyq/core-js` and its direction guard | `git grep -ln "@veyq/core-js/" -- src/web/src src/web/eslint.config.* scripts` |
| Notice kinds and core text | `git grep -n "enum class NoticeKind" -- src/mobile/core`, `git grep -n core_text -- src/mobile/words/generate_words.py` |
| The renderer and its users | `git grep -ln "shared/markdown" -- src/web/src` |
| The live loop and its users | `git grep -ln -e createLivePoller -e itemsPollTick -- src/web/src` |
| Memory/Chapter detail tabs (where Journal goes) | `git grep -n -e "Map" -e "Chapters" -- src/web/src/features/memories/components/MemoryDetail*.vue` |
| Export/import and search document kinds | `git grep -ln -e content.schema.json -e exportImport -- src/web/src contracts src/mobile/core` and `git grep -ln "documentKind\|DocumentKind" -- src/web/src src/mobile/core` |
| Operator-blind probe and composition test | `git grep -ln -e OperatorBlindProbeTest -e ModuleCompositionTest -- src/backend` |
| The capability's planned rows (to flip) | `git grep -n "memories.journal" -- docs site src` |
| e2e specs reaching the Memory detail's tabs by label | `git grep -ln -e "'Map'" -e "Chapters" -- tests/e2e` |
| Site pages to touch | `git grep -ln -e uc.memory -e en.moderation.guest-write-record -e "security-model" -- site/src/content/docs` |

## Phases

Order: contracts → backend → core → core-js → web (ADR 0155 § Consequences), then docs.

**Phase 1 — contracts and crypto vectors.** Decisions 1, 2. Done when: `pnpm run generate:api` (web) and the
core's generated client build, the contracts guards green; one use of each new schema written in each generated
language before the commit, and the generated models diffed against a copy taken before (changes/README § Phases);
`src/packages/crypto` `pnpm run check` green with the new vectors and `pnpm run mutation --force`'s exit code in the
commit message; the core's suite over the same vectors (`./gradlew :core:jvmTest :core:macosArm64Test` in `src/mobile`,
the rule for a change under `crypto/`) and `@veyq/core-js`'s conformance (`pnpm run build && pnpm run check`) green;
the predicted backend reds named (`ModuleCompositionTest`, `OperatorBlindProbeTest` until classified — classify them
here).

**Phase 2 — backend, core.** Decisions 3, 7. Record `./gradlew check`'s exit code on the tip before the first edit.
Tests: two lists on one node bump independently and a media poll stays `304` across journal bumps (positive control:
a media attach moves it); a session row extends within 15 minutes and splits on a new address, a new port, a new
`Origin` and after the gap (each split with its control), the purge counts from the last time, an objection erases
sessions, a hold copies them, the export shows first/last/count. Done when: `./gradlew check` green and
`validate_coverage.py` green.

**Phase 3 — backend, Memories.** Decisions 1, 4–6, 8. Tests: the compare-and-set (`n + 1` over `n`, a second over the
same `n` refused); updates for `n` taken until `n + 1` exists and refused after; the tail of `n` served with `n + 1`
and the predecessor's key served with it; a guest's snapshot and history reads refused (with a member's control); an
update under a revision minted below the scope epoch refused and taken after a compaction; the tail cap, the update
and snapshot caps, the rate; the retention purge (current and predecessor kept, a guest-holding revision kept to N,
a held one kept); re-key re-wraps and refuses a body change; erasure and trash; the switch and `451`; the report's
keys and the hold copy; the storage term; a `READ` guest's digest tag `304`s while nothing it sees moved; the alias:
the same writer's alias equal across reads and a restart, different in another journal, different for two writers, a
member's and a guest's never equal to a plain hash of their id, no id in the guest's response (with the member's
response carrying the ids as the control). Every negative with its positive control. Done when: `./gradlew check`
green.

**Phase 4 — core, the journal's model.** Decisions 5 (the client's numbers), 9, 12's token grammar, 13's lists. Record
`./gradlew :core:jvmTest`'s exit code first (`src/mobile`). Tests over a played engine: two logs converge through
envelopes in server order; a reused client id from another writer is dropped (and the same id from its owner kept); a
sequence gap is reported and the later updates held, and released when the gap fills; an undecodable update skipped;
a snapshot that does not open skipped and the kept log replayed; the flush bounds updates to one a second and answers
*too large* above the cap; the re-seal set after `409`; a refusal for good ends the client id; compaction's triggers,
jitter and drop on `409`; the check names a forged snapshot and passes an honest one written by a second played
engine that encodes the same state in other bytes (ADR 0133 decision 4's case); the rights table; the pseudonyms (the same
alias the same name, EN and DE with the German adjective agreeing for each gender, both lists 64 distinct words in
each language, animal and adjective from their own alias bytes, equal names numbered in ordinal order with the earlier
writer's suffix kept when a later one appends, the guest's own row "You"); the token grammar and its export rewrite.
`JournalVectorsTest` writes the vectors and fails when the file differs. Done when: `./gradlew :core:compileKotlinJs
:core:compileTestKotlinJs :core:jvmTest` green and each rule's bite recorded (one mutated rule → its vector red).

**Phase 5 — core-js, Memories' façade and the size gate.** Decision 10. Record `@veyq/core-js`'s and the web's `pnpm
run check` exit codes and the web build's eager set and chunk sizes on the tip first (the pilot's method: `main` and
the chunks `index.html` modulepreloads, gzip level 9, attributed by source map). The engine-bound fixture generated
over the pinned Yjs, and ADR 0133 decision 4's fixed point measured (encode → apply → encode) — a Yjs that is no fixed point
stops the phase and goes back to refine. Tests: the journal vectors through the shipped module over Yjs, a dropped
gap rule turning them red; the slice check names no coroutines, serialization, Ktor or refusal file in the entry.
Done when: the package's `pnpm run build && pnpm run check` green; **the eager set grows by at most 1 KB gz** and the
façade's own code is at most 25 KB gz (ADR 0155 decision 7) — measured with a throwaway import of the entry from a
lazy chunk; two clean builds byte-identical. If the one-module output puts the journal's Kotlin in the eager set and
Expenses' move has not settled granularity, the phase measures Kotlin/JS's per-file output against the same gates; a
miss goes to the owner with its number, as ADR 0155 decision 2 says.

**Phase 6 — web, the journal wired.** Decision 11, the dependencies. Unit tests (drawing and wiring only): the
adapter's four functions over Yjs; a served page opened, handed to the façade and applied in its order; local edits
handed in and the flushed envelope sealed under the current `K_jrev`; a `409 stale_revision` re-sealed; the compaction
the façade orders sealed and posted, dropped on `409`; the poll's cadence while open and visible. Done when: `pnpm run
check` (web) green.

**Phase 7 — web, the editor and its embeds.** Decisions 12, 13, 14 (without History). Unit tests per renderer token
and placeholder; the editor binds the doc and draws every tool of the plan in its order; the read view where the gate
says read; the guest's editor shows the notice; the contributors popover draws the core's rows (names for a member,
pseudonyms for a guest); each `JournalSaid` drawn at its lifetime. A lane L1 spec in `tests/e2e`: a member and a
`READ_WRITE` guest in two browser contexts type into one journal and both see both texts; a `READ` guest sees the
text. Done when: `pnpm run check` green, the spec green once, and a **browser pass** on the dev instance
(`veyq.localhost`): two profiles edit one Memory's journal at once, embed a photo and a track, the guest's notice is
shown, a Chapter's journal through the Chapter's link shows a parent photo as a placeholder. The commit message lists
the journal's EN/DE words as #855's table.

**Phase 8 — web, history, restore, export, import, search.** Decisions 14 (History), 15. Unit tests: the history
draws the plan over the kept revisions; a restore writes one update and a snapshot, and restoring the one before
undoes it; a guest has no History; export writes `journal` with rewritten tokens and import starts a fresh journal;
search finds a journal by its projection and not a guest. Done when: `pnpm run check` green, the e2e export/import spec
extended and green, and a browser pass: restore an earlier state while a second profile types, and undo it.

**Phase 9 — docs, site, coverage.** `uc.memory.journal`'s criteria from its gaps into refs beside their tests
(except `live-and-present`, `cr-live-stream`'s), the coverage file to `built` only if every criterion has a ref;
ADR 0133's `Built` line, ADR 0093's
`Built` line, ADR 0155's `Built` line (Memories' façade, the journal's slice); `en.moderation.guest-write-record` (the
session row from decided to built); `docs/apps/journal.md` (the rules in the core, the engine a port, the apps through
#855); the security model's append-only statement; the capability register's `memories.journal` from planned (docs
and `site/.../run/capabilities.mdx`, `security/roadmap.md`); inbox #855's row (the engine port in the core through
`yffi`, the outbox of ADR 0133 decision 11, the plan entry of decision 14). Site: a new Use page *Write a journal together*
(EN+DE, beside the Memories pages, at their depth); Security pages that list what the server sees and the known
limitations (the freshness residual, the kept history's residual); Run: the journal's retention and the storage term,
where a Run page states retention. Done when: the trust-chain guards (`scripts/validate_*.py`, `/usr/bin/python3`) and
the site's own check green.

## Owner answers (2026-11-21: "all as recommended, the journal later")

1. **ADR 0133's amendment (the rules in the core, the engine a port) — accepted**, folded into ADR 0133's decisions 2,
   4, 9 and 11 and its Context; the ADR stays Accepted.
2. **The German word for Journal is *Tagebuch*** — in ADR 0086's table; the UI strings arrive with phase 7 (decision 13's
   words).
3. **History (*Verlauf*) names a journal's earlier snapshot revisions too** — ADR 0086's row reads "earlier contents
   of a file or of a journal".
4. **The journal comes later** — deferred behind the web's move onto the core (ADR 0155 decision 7's
   core split and Expenses' move); see the status note at the top.

## Definition of done

The nine phases' done-whens; `git grep -n "memories.journal" -- docs site` shows no "planned"; the CR's lessons moved
to where they bind and this file deleted.

## Retrospective

_(filled by the implementer)_
