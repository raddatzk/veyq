---
id: cr-live-journal
title: A journal per Memory and Chapter — a sealed Yjs log, written by members and READ_WRITE guests on the poll, with member-made snapshots and restore
status: planned
satisfies: ["uc.memory.journal", "en.moderation.guest-write-record"]
adrs: ["0133", "0093", "0033", "0109", "0082", "0125", "0088", "0073", "0061", "0072"]
invariants:
  - "the server stores and serves only sealed journal bytes; no plaintext, embed id or Yjs client id leaves a client unsealed (ADR 0133 decision 3)"
  - "only a member writes a snapshot revision or reads an earlier one; the share route offers neither (ADR 0133 decisions 4, 7, 8)"
  - "the server takes snapshot n + 1 only over n, and no update under a K_jrev minted below the scope's epoch (ADR 0133 decisions 4, 11)"
  - "a journal update bumps the journal's own list of the node's items revision — never the gallery's, never the scope tree's (ADR 0133 decision 3)"
  - "a guest's journal updates leave one guest-write record row per writing session, and every update keeps its own server time (ADR 0093 § 10)"
  - "a link guest is served each contributor's alias, never a name or a member or identity id, and the journal's alias key is never served (ADR 0133 decision 9)"
---

## Goal

Build [ADR 0133](../docs/decisions/0133-a-memorys-journal-is-a-sealed-crdt-log-whose-snapshots-only-members-write.md)
on [ADR 0033](../docs/decisions/0033-live-media-updates-revision-polling.md)'s poll: each Memory and Chapter gets a
**Journal** — markdown in a CodeMirror source editor with live preview, embeds as cards — that members and
`READ_WRITE` guests write at the same time (each sees the others' edits within the poll's 2 s while the editor is
open), `READ` guests read, members' clients compact into snapshot revisions under fresh keys, members list and
restore earlier ones for the trash retention, a guest leaves one evidence row per writing session
([ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ 10), and export, import and search carry the text. Sub-second delivery and "who is here" are `cr-live-stream`'s,
which moves this CR's journal onto core's stream; this CR's poll stays the fallback.

## Context & constraints

- ADR 0133 holds every decision and its rejected alternatives, and the measured library facts (Yjs 13.6.33 update v1
  read by `@y/y` 14 rc.29 both ways; order-independent `encodeStateAsUpdate`; a withheld delete-only update unnoticed by
  Yjs). Re-run them before phase 4 if the pinned Yjs differs from 13.6.33 (a ten-line probe each; ADR 0133 Context
  names what each asserts).
- Measured 2026-11-13 on `main` (`480955ec2`), the facts the design leans on — **spot-check the first two before
  phase 2**:
  - Core's items revision is one row per node: `veyq_node_items_revision(node_id, revision, delete_revision)`
    (`src/backend/CLAUDE.md` § items, `core/items/NodeItemsRevisions.kt`); its callers are Memories' media and
    Expenses' entries (locator below). ADR 0033 left the second list per node open.
  - The guest-write record writes one row per write (`core/moderation/guestwrite/GuestWriteRecords`), called from
    Memories', Depot's and Expenses' guest handlers; `GuestWriteRecordedTest` holds that every guest-write operation
    resolves the address or is exempt.
  - Memories' schema has a baseline (`apps/memories/.../V202611070832__memories_baseline.sql`); instances exist since
    V1, so tables and the list key arrive in **new** migrations, never a baseline edit.
  - Member requests carry a bearer `Authorization` header (`src/web/src/api/client.ts`); the share API is token-less.
  - `src/web/src/shared/markdown.ts` is the one renderer and the Trusted Types sanitizer (ADR 0117); it has no embeds.
  - The live loop is `core/items/itemsLive.ts` over `shared/state/livePoller.ts` (`createLivePoller`); Expenses'
    `potSession.ts` is the nearest user with sealed revisions.
  - `src/web/package.json` holds no CodeMirror and no Yjs; npm (2026-11-13): `yjs` 13.6.33, `y-codemirror.next`
    0.3.6 (peer `yjs ^13.5.6`, `@codemirror/state`/`view` `^6`), `@codemirror/view` 6.43.15, `@codemirror/lang-markdown`
    6.5.2 — all MIT, none deprecated. CI's `pnpm_install_no_deprecated.py` gates them.
- **Not measured at refine (a docs-only session):** the exit codes of `./gradlew check` and `pnpm run check` on
  `main`. Phase 2 and phase 4 record them before their first edit (changes/README § A done-when that names a gate).
- Native apps are out (inbox #855); a Memory's view of its Chapters' journals is out (inbox #853), and nothing here may
  assume one journal per Memory only.

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
2. **Seal purposes (crypto vectors, `contracts/crypto/vectors/v1.json`, additive):** the wrap `journal-revision-key`
   (`K_jrev` under `K_node`, bound to scope, node, revision — `expense-entry-key`'s shape) and field purposes
   `journal-update` (bound to node, revision, update id), `journal-state` and `journal-projection` (bound to node,
   revision). Checked against `seal.ts`'s `SEAL_PURPOSES` / `FIELD_PURPOSES`
   so no purpose sits in two; vectors from an independent implementation as the note requires. The update envelope's
   plaintext (`v`, Yjs client id, sequence, update bytes) is specified in the vectors' note, padded as ADR 0133
   decision 3 says.
3. **Core: a list key on the items revision.** `veyq_node_items_revision` gains `list` (primary key `(node_id,
   list)`; existing rows are the default list) in a new core migration; `bump`/`read` take the list with the default
   for today's callers; `NodeItemsRevisionBumpsTest` and the lock-order tests extend to the journal's writers. Erasure
   still deletes every row of the node.
4. **Memories' tables** (new migration): `veyq_journal_revision`, `veyq_journal_update` (indexed `(node_id, position,
   id)`), `veyq_journal_contributor` (node, writer, ordinal; written with a writer's first update) and
   `veyq_journal_alias_key` (node, 32 random bytes from the server's CSPRNG, minted in the transaction of the first
   contributor row, never served, deleted with the journal) as ADR 0133 decision 3; a writer's alias is the first 8
   bytes of `HMAC-SHA-256(key, kind ‖ writer id)` (kind `m` for a member, `g` for a link identity), computed when served; an update's position is the journal list's bump, stamped in the writer's
   transaction (ADR 0033 decision 3). Both carry their padded sizes; Memories' storage-usage term sums them
   (ADR 0073 § 4's per-application term).
5. **Numbers:** update ≤ 64 KiB sealed, classes 256 B / 1 KiB / 4 KiB / 16 KiB / 64 KiB; state ≤ 4 MiB, projection ≤
   1 MiB, powers of two from 4 KiB; tail cap 5,000 updates or 4 MiB, whichever first; a
   writer's rate 4 updates/s with a burst of 20 (core's token bucket, ADR 0052); compaction past 500 tail updates, on
   editor close after writing, and first after a rotation, with 0–5 s jitter. Constants live once each, in the
   contract (caps) and one Kotlin and one TypeScript file (behaviour).
6. **Retention:** a structural purge (ADR 0021) deletes a superseded revision and its updates once the node trash
   retention has passed since its replacement, never the current one or its predecessor, never one with a guest's
   update before the guest-write record's N, never one a hold pins.
7. **Guest-write session rows (core):** `GuestWriteRecords` gains a session write — find the identity's open row for
   the journal with the same address, port and `Origin` whose last time is under 15 minutes old, under a lock, and
   extend it (last time, count, expiry); else insert. New nullable columns (`last_observed_at`, `write_count`) in a
   core migration; every existing row reads as a session of one. The purge, the objection's erasure, the hold copy,
   the Art. 18 export (`ModerationExportGuestWrite` gains first/last/count) and admin-web's and `veyq-admin`'s views
   follow. `GuestWriteRecordedTest` lists `appendShareJournalUpdates`.
8. **Platform hooks in Memories:** the node re-key participant re-wraps `K_jrev` (as `PotNodeRekeyParticipant`); the
   erasures and subtree eraser delete the journal; the reportable-items seam gains the journal as an `ITEM` (its keys:
   the current and the predecessor's `K_jrev`); the hold copies the reported revisions and updates; a content block
   refuses append, snapshot and restore with `451`; `memories.journal` and Memories off refuse writes, reads stay.
9. **Web model** (`src/web/src/features/memories/journal/`, lazy-loaded with its dependencies on first open): open
   (snapshot + tail; skip an undecodable snapshot via `getJournalRevision` and replay), merge in server order, the
   writer-id pin, the per-client-id sequence check and its "incomplete" state, a 1 s flush tick, re-seal on `409
   stale_revision`, compaction (verify the predecessor byte for byte first; jitter; drop on `409`), the poll at 2 s
   while an editor is open and visible and ADR 0033's cadence otherwise, all on `itemsLive.ts`/`createLivePoller`.
   Yjs pinned to an exact version.
10. **Rendering:** `shared/markdown.ts` gets a block-token hook (`::name:args`); Memories registers `media`,
    `chapter`, `map`, `track` (core never knows them, ADR 0079). Cards in the editor are CodeMirror widgets over the
    same renderers; a placeholder for what does not open; maps under the one consent.
11. **UI** (reuse first, `ui-web-styleguide.md` § Reuse first): a **Journal** tab on a Memory's and a Chapter's detail
    (beside Chapters/Map); the editor with a small toolbar (headings, list, embed menu); a read view for `READ`
    guests and when the switch is off; a contributors popover — on a guest's page each other person a **pseudonym**
    (ADR 0133 decision 9): `features/memories/journal/journalPseudonym.ts` names an alias from a fixed list of 64
    animals in EN and DE (each German one with its gender, so "Anonymer Fuchs", "Anonyme Eule", "Anonymes Pferd"; the
    animal is the alias's first two bytes as an unsigned integer mod 64, the avatar colour its third byte mod the
    palette), numbers equal animals ("Anonymous Fox 2") in contributor-ordinal order and its own row "You"; a member's
    page shows names as before; a **History** panel for members (list, preview,
    Restore with a confirmation, Undo by restoring the one before); the incomplete/mismatch banners; a guest's
    editor shows `GuestWriteRecordNotice`. EN+DE in the i18n files.
12. **Export/import/search:** the exporter merges and writes `journal` per node with tokens rewritten; the importer
    writes a first snapshot; search registers the journal as a document kind over the projection (ADR 0125 stage 8b).

## Inventory (locators — run, read as lists)

| What | Command |
|---|---|
| Items-revision callers and tests | `git grep -n -e "items\.bump" -e NodeItemsRevisions -e veyq_node_items_revision -- src/backend` |
| Guest-write record writers, readers, export, views | `git grep -ln -e GuestWriteRecord -e guest_write_record -e ModerationExportGuestWrite -- src/backend src/admin-web src/web tests` |
| Node re-key participants (shape to copy) | `git grep -ln NodeRekeyParticipant -- src/backend` |
| Reportable items and the hold's contributors | `git grep -ln -e ReportableItems -e HoldContributor -- src/backend` |
| Seal purpose tables and vectors | `git grep -n -e "SEAL_PURPOSES" -e "FIELD_PURPOSES" -- src/packages/crypto/src/seal.ts` and `contracts/crypto/vectors/v1.json` |
| The renderer and its users | `git grep -ln "shared/markdown" -- src/web/src` |
| The live loop and its users | `git grep -ln -e createLivePoller -e itemsPollTick -- src/web/src` |
| Memory/Chapter detail tabs (where Journal goes) | `git grep -n -e "Map" -e "Chapters" -- src/web/src/features/memories/components/MemoryDetail*.vue` |
| Export/import and search document kinds | `git grep -ln -e content.schema.json -e exportImport -- src/web/src contracts` and `git grep -ln "documentKind\|DocumentKind" -- src/web/src` |
| Operator-blind probe and composition test | `git grep -ln -e OperatorBlindProbeTest -e ModuleCompositionTest -- src/backend` |
| The capability's planned rows (to flip) | `git grep -n "memories.journal" -- docs site src` |
| e2e specs reaching the Memory detail's tabs by label | `git grep -ln -e "'Map'" -e "Chapters" -- tests/e2e` |
| Site pages to touch | `git grep -ln -e uc.memory -e en.moderation.guest-write-record -e "security-model" -- site/src/content/docs` |

## Phases

**Phase 1 — contracts and crypto vectors.** Decisions 1, 2. Done when: `pnpm run generate:api` (web) and the
contracts guards green; one use of each new schema written in each generated language before the commit, and the
generated models diffed against a copy taken before (changes/README § Phases); `src/packages/crypto` `pnpm run
check` green with the new vectors and `pnpm run mutation --force`'s exit code in the commit message; the predicted
backend reds named (`ModuleCompositionTest`, `OperatorBlindProbeTest` until classified — classify them here).

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
response carrying the ids as the control). Every
negative with its positive control. Done when: `./gradlew check` green.

**Phase 4 — web, the journal model.** Decision 9, the seal purposes in `src/packages/crypto` (`seal.ts`), the
dependencies. Record `pnpm run check`'s exit code first. Unit tests: two docs converge through sealed updates in
server order; a reused client id from another writer is dropped (and the same id from its owner kept); a sequence gap
(a withheld delete-only update — the measured case) is reported and the later updates held; a re-seal after `409`; the
byte-for-byte check accepts an honest and names a forged snapshot; a snapshot that does not open is skipped and the
kept log replayed; compaction's jitter and drop on `409`; the flush tick bounds updates to one a second. Done when:
`pnpm run check` (web) green and `pnpm run mutation --force` for `src/packages/crypto` recorded.

**Phase 5 — web, the editor and its embeds.** Decisions 10, 11 (without History). Unit tests per renderer token and
placeholder; the editor binds the doc and flushes; the read view for `READ` and the switch off; the guest's editor
shows the notice; the contributors popover names for a member and pseudonyms for a guest (`journalPseudonym`: the
same alias the same name, EN and DE with the German adjective agreeing, equal animals numbered in ordinal order with
the earlier writer's suffix kept when a later one appends, the guest's own row "You", every list entry a distinct
animal). A lane L1 spec in `tests/e2e`:
a member and a `READ_WRITE` guest in two browser contexts type into one journal and both see both texts; a `READ`
guest sees the text. Done when: `pnpm run check` green, the spec green once, and a **browser pass** on the dev instance
(`veyq.localhost`): two profiles edit one Memory's journal at once, embed a photo and a track, the guest's notice is
shown, a Chapter's journal through the Chapter's link shows a parent photo as a placeholder.

**Phase 6 — web, history, restore, export, import, search.** Decisions 11 (History), 12. Unit tests: the history
lists kept revisions; a restore writes one update and a snapshot, and restoring the one before undoes it; a guest has
no History; export writes `journal` with rewritten tokens and import starts a fresh journal; search finds a journal
by its projection and not a guest. Done when: `pnpm run check` green, the e2e export/import spec extended and green,
and a browser pass: restore an earlier state while a second profile types, and undo it.

**Phase 7 — docs, site, coverage.** `uc.memory.journal`'s criteria from its gaps into refs beside their tests
(except `live-and-present`, `cr-live-stream`'s), the coverage file to `built` only if every criterion has a ref;
ADR 0133 and ADR 0093's `Built` lines; `en.moderation.guest-write-record` (the session row from decided to built);
`docs/apps/journal.md`; the security model's append-only statement; the capability register's `memories.journal`
from planned (docs and `site/.../run/capabilities.mdx`, `security/roadmap.md`). Site: a new Use page *Write a journal
together* (EN+DE, beside the Memories pages, at their depth); Security pages that list what the server sees and the
known limitations (the freshness residual, the kept history's residual); Run: the journal's retention and the
storage term, where a Run page states retention. Done when: the trust-chain guards (`scripts/validate_*.py`,
`/usr/bin/python3`) and the site's own check green.

## Definition of done

The seven phases' done-whens; `git grep -n "memories.journal" -- docs site` shows no "planned"; the CR's lessons
moved to where they bind and this file deleted.

## Retrospective

_(filled by the implementer)_
