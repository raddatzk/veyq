---
id: cr-expenses-ledger
title: Expenses, the third application — Pots, people and sealed entry revisions, balances, the link
status: in-progress
satisfies: ["uc.expenses.create-pot", "uc.expenses.manage-participants", "uc.expenses.record-entries", "uc.expenses.entry-history", "uc.expenses.verify-ledger", "uc.expenses.balances", "uc.expenses.share-pot"]
adrs: ["0108", "0109", "0079", "0080", "0082", "0093", "0099", "0088", "0072", "0077", "0033", "0061"]
invariants:
  - operator-blind — the server sees per Pot the counts, times, size classes, writers, tombstones and erasures ADR 0108 § Consequences lists, and no amount, participant, category or currency
  - an entry revision is never overwritten — the server takes revision n+1 only on n, and only a member's erasure of a participant's record removes a revision
  - no guest action erases anything; a guest writes revisions only through the link's gate, each recorded as a guest write
  - a re-key re-wraps each revision's K_rev and changes no body (ADR 0108 decision 4)
  - core never calls an application; Expenses joins core through its seams (kind rules, subtree erasure, re-key participant, reportable items, hold snapshot), never the reverse, and imports no other application
  - a share view is the member view with functions removed — no guest-only screen (inbox #401)
  - every text follows docs/crosscutting/ui-web-styleguide.md § Copy
---

## Goal

Inbox #325, on the V1 line (owner 2026-10-23): build **Expenses**, a Splid-like shared expense tracker, as the third
application — the first of three CRs (owner 2026-10-23: split into the ledger, `cr-expenses-receipts` and
`cr-expenses-exchange-rates`, each landing alone). This one is a working Splid without receipts: a **Pot** is a node kind
(`EXPENSES_POT`); its participants and entries are sealed **revisions** in Expenses' own table, appended and never
overwritten, each with a key of its own and naming the heads it had seen so a reader notices a revision the server left
out; balances, settle-up and overviews run in the client; a foreign currency's rate is typed; a `READ` or `READ_WRITE`
link shows the member's view. [ADR 0108](../docs/decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
holds what it asks of the platform, [docs/apps/expenses.md](../docs/apps/expenses.md) the domain, the use-cases
under [docs/features/expenses](../docs/features/expenses/_index.md) the flows.

## Entry condition — the code starts after the re-key

**No phase of this CR edits code before `cr-scope-rotation-rekeys-nodes` has landed on `main`**, or at the earliest
once its participant seam is merged into `main` *and* its web phase (4) is in: the backend `NodeRekeyParticipant`
(`core/node/rekey/`), core's `rekey` tag (`listRekeyNodes`, `getNodeRekeyParts`, `stageNodeRekey`, `commitNodeRekey`),
the `node-epoch` mode of `validate_write_concurrency.py`, `scripts/validate_node_rekey_arity.py`, and the required
`KindDescriptor.rekeyContent`. Phases 2 and 3 of that CR redden `src/web/`'s check until its phase 4 lands, so an
Expenses web phase started between them would be red for a reason not its own. Check before the first edit:

```bash
test -f changes/cr-scope-rotation-rekeys-nodes.md && echo "re-key CR still open"; git grep -n "interface NodeRekeyParticipant" -- src/backend; git grep -n "rekeyContent" -- src/web/src/core/applications/registry.ts; ls scripts/validate_node_rekey_arity.py
```

At refine (2026-10-23) the seam existed **only uncommitted** in that CR's worktree
(`core/node/rekey/NodeRekeyParticipant.kt`: `part`, `commit(scopeId, nodeId, staged): Verdict` with
`APPLIED/MISSING/STALE`, core rolling back on anything but `APPLIED`), no participant existed, `rekeyContent` and the
guard did not exist. **And `cr-application-switches` has landed** (`test -f changes/cr-application-switches.md && echo "switch CR still
open"`); it landed 2026-10-24 and its seam is built: core's `ApplicationSwitch` (`requireWritable(application, scopeId)`
by name in each write handler, after its gate), `NodeKindRules.applicationOf(kind)`, `MemberBlobRoles.application()`, the
`ApplicationSwitchSweepTest` (red on an application's write operation that is neither refused off nor exempt with its
reason), the catalog guard's "every application has its own entry", and on the web `registry.registerApplication` and
`MemberAccess.applicationsOff` — `docs/apps/README.md` § Rules lists what a new application adds. Every sentence below that
leans on that CR's plan carries **"(planned in
cr-scope-rotation-rekeys-nodes)"**; the first implementer re-reads each against the merged tree
(`grep -n "planned in cr-scope-rotation" changes/cr-expenses-ledger.md`) and says what moved.

## Context & constraints

- **Substrate measured at refine:** worktree `expenses-use-cases-and-cr-d2c6d0` at `329b92cf` (= `main`), clean; the
  re-key branch `claude/scope-rotation-rekeys-nodes-341753` at `9242fae7` plus its uncommitted tree. Two Explore sweeps
  ran the commands quoted in § Inventory; their counts are theirs, measured, not recalled. No build ran at refine.
- **Decisions are the ADR's; read ADR 0108 before each phase.** The owner's answers of this refine (2026-10-23) are
  edited into it: a key per revision so a re-key changes no body (decision 4); a report reaches the whole entry
  (decision 6); Frankfurter as the default rate provider (decision 8, built by `cr-expenses-exchange-rates`); three CRs.
- **What the inventory corrected in ADR 0108:** blobless sealed rows already exist (`veyq_bucket.sealed_name`,
  `veyq_scope_item_favorites.ciphertext`); what is new is a blobless list *published live* and a guest write that is an
  application record. Its § Context now says so.
- **Expenses' own switch comes from `cr-application-switches`** (owner 2026-10-23): that CR lands first and gives every
  application an entry of the register which, off, makes it read-only. This CR adds `expenses` to it — the entry, its
  kind through the seam, its write operations under the sweep (the erasure is a removal and exempt) — and its criterion
  under `uc.platform.switch-application-off`. `expenses.exchange-rates` is the rates CR's.
- **What the two later CRs need from this one, so it is built here:** the editor's rate field (typed), the revision's
  list of receipts held (empty until receipts exist, in the v1 format already), and the Pot page's place for receipts.
- **"The platform's trash retention" is two keys today:** `veyq.node.trash.retention` (core) and
  `veyq.media.trash.retention` (Memories, `MemoriesMediaConfig.kt`). Expenses reads core's node retention — a deleted
  entry lives inside its node and follows the node's rule — and declares no key of its own.

## Decisions this CR takes

ADR 0108 decides the mechanism. These are the work order's shape decisions, each settled here unless § Open questions
lists it.

0. **A key per revision** (ADR 0108 decision 4, owner 2026-10-23). The body seals as an `expense-entry` field under a
   random `K_rev` (ADR 0093 § 9's envelope with `K_rev` as the parent key); `K_rev` is wrapped under the Pot's `K_node`
   by `expense-entry-key`, bound to (scope, node, entry, revision), in a column of its own. The re-key participant
   rewrites only that column, and refuses (`STALE`) a staged part that names a body; a hold copies both.
1. **One append operation, a batch.** Members' and guests' writes of revisions go through one append each (member
   scope path, guest share path), taking 1–256 revisions of distinct records atomically: each revision `n + 1` lands only
   on `n`, else the whole batch is `409 stale_revision` naming the stale records. A batch serves creation (the Pot's
   first participants), recording every settle-up transfer at once, an import and a copy. Declared
   `x-veyq-concurrency: { mode: revision }` on `revision` and, once the re-key CR is in, `node-epoch` on `nodeEpoch`.
2. **The list is the revisioned-list primitive over revision rows.** A record of the list is one revision (id: entry
   id + revision); its position is the bump's revision, stamped after `items.bump` in the writer's transaction
   ([docs/apps/README.md](../docs/apps/README.md) § How an application publishes a revisioned list, steps 1–5); an
   erasure is a removal (`deleted = true`). The full read pages through every revision of the Pot; the client keeps no
   copy across sessions (offline is out of the first cut).
3. **The writer is served to members only.** A member's read carries each revision's writer (`user:{id}` or
   `share:{identityId}`, the class ADR 0093 § 8 discloses, resolved to a name by the client as Memories' uploader is);
   the guest read's schema has no writer field at all, so leaving it out is not a filter that can be forgotten.
4. **The erasure is its own member-only operation**: the sealed erasure revision of one participant record, after
   which the server deletes that record's earlier revisions in the same transaction. Refused from a link. The server
   cannot check that the record is a participant or that it is settled (sealed); those are the client's rules.
5. **The revision's plaintext format is a contract.** `contracts/expenses/entry.v1.schema.json` (kinds, amounts in
   minor units, rate as a decimal string, allocation, heads, receipts held) and
   `contracts/expenses/v1-vectors.json` — entries with their expected per-person shares, balances and settle-up, the
   rounding rule's conformance table — so a second client (native, later) computes the same balance. The size classes
   are 512 B, 2 KiB, 8 KiB and 16 KiB of padded plaintext (`u32be(len) ‖ bytes ‖ zeros`, the shape of
   `features/spaces/spaceIcon.ts`'s `padTo`, moved into `@veyq/crypto` as the one padding helper both use).
6. **The share roots are `READ` and `READ_WRITE`.** `ExpensesNodeKindRules` declares them through `ShareRoots`, so
   core refuses a `WRITE_ONLY` link on a Pot; Expenses declares no `GuestCreatableKinds` and no `GuestNodeRules`, so a
   guest has no tree rights inside a Pot (the container default, `illegal_scope`).
7. **A report targets an entry as an `ITEM`** — all its revisions, the report share carrying every `K_rev` (ADR 0108
   decision 6; its receipts join in `cr-expenses-receipts`); no new `ModerationItemKind`. Expenses implements
   `ReportableItems`, a `HoldSnapshotContributor` of its own phase (between Media's 100 and Core's 200 is free),
   `UploaderContent`/`HeldUploaders`, and a `Case…Resource` serving the held rows; the web side an `ITEM` target with
   its report-keys function and a claimed item review, as `MemoryDetailHost.vue` and
   `registry.claimItemReview(MediumReview)` do.
8. **A Pot's row shows its base currency as its detail** (from its sealed meta, [docs/apps/README.md](../docs/apps/README.md)
   § How an application shows facts); no total, which would need every Pot's revisions read to draw a folder.

## Inventory

Each row hands over the command that finds the sites; run it at implement time. At-refine findings are the sweeps'.

| Area | What changes | Locator |
|---|---|---|
| New module | `src/backend/apps/expenses` (`me.raddatz.veyq.expenses.*`), as Depot | `find src/backend/apps/depot -type f \| sort` (33 files at refine: 14 main, 15 test); `python3 scripts/validate_backend_module_graph.py` names every edge it derives |
| Module wiring | `include`, root runtime edge, `beans.xml`, two Dockerfile `COPY` lines | `grep -n "apps:depot\|apps/depot" src/backend/settings.gradle.kts src/backend/build.gradle.kts infra/docker/Dockerfile` |
| Kind seams | `NodeKindRules` (`applicationOf` → `expenses`), `ShareRoots` | `grep -rn "NodeKindRules\|ShareRoots\|GuestCreatableKinds\|WriteOnlyGuestKinds" src/backend/apps/depot/src/main` |
| Composition test | kinds, covers, never-guest-creatable loop, upload owner map, module anchors | `grep -n "DEPOT_DROPBOX\|depot" src/backend/src/test/kotlin/me/raddatz/veyq/app/ModuleCompositionTest.kt` |
| Migrations | `V<ts>__expenses_*.sql` + pin line; tables carry `node_id` (else `validate_node_subtree_erasure.py` cannot see them) and `scope_id` per `validate_scope_erasure_arity.py` | `ls src/backend/*/src/main/resources/db/migration src/backend/apps/*/src/main/resources/db/migration; tail -3 scripts/applied_migrations.sha256` |
| Revisioned list | bump in every writer, guard learns Expenses' writers | `grep -n "MediaEntity\|bumping" src/backend/src/test/kotlin/me/raddatz/veyq/core/items/NodeItemsRevisionBumpsTest.kt` (Memories-shaped at refine: its `writes` regex names `MediaEntity` only) |
| Guest writes | gate, record per revision (`GuestWriteObject.ITEM`) | `grep -rn "guestWriteRecords.record(" src/backend --include='*.kt' \| grep -v /test/` (6 sites at refine) and `ShareFilesHandler.complete` as the template |
| Erasure | node subtree, scope, account (writer columns classified) | `python3 scripts/validate_node_subtree_erasure.py; python3 scripts/validate_scope_erasure_arity.py; python3 scripts/validate_account_erasure_arity.py` |
| Re-key | participant (`part = "expenses"`) over the `K_rev` wrap column, list + stage operations as Memories' (planned in cr-scope-rotation-rekeys-nodes) | `grep -n "operationId: \(list\|stage\)MediaRekey" -A3 contracts/api/memories/media.openapi.yaml`; `grep -rln "NodeRekeyParticipant" src/backend` |
| Moderation | `ReportableItems`, `HoldSnapshotContributor`, `UploaderContent`/`HeldUploaders`, case resource | `grep -rn ": ReportableItems\|: HoldSnapshotContributor\|: UploaderContent\|: HeldUploaders" src/backend --include='*.kt' \| grep -v /test/` |
| Probe | every new operation classified | `grep -n "listShareFiles\|completeShareFile" src/backend/src/test/kotlin/me/raddatz/veyq/core/http/OperatorBlindProbeTest.kt` |
| Contracts | `contracts/api/expenses/expenses.openapi.yaml` (new tag), fields `x-veyq-visibility`, writes `x-veyq-concurrency` | `ls contracts/api/depot`; guards `validate_field_visibility.py`, `validate_write_concurrency.py`, `validate_opaque_names_disjoint.py`, `validate_contracts_common.py`, `validate_id_contract_format.py` read it by themselves |
| Web client generation | hand-listed per spec (no guard checks it) | `grep -n "depot/files.openapi.yaml" src/web/package.json` |
| Crypto | `expense-entry` (the 12th `FIELD_PURPOSES` row, a `field_seal` vector) and `expense-entry-key` (a `SEAL_PURPOSES` row, a `seal.sym` vector); the padding helper | `grep -n "FIELD_PURPOSES" -A16 src/packages/crypto/src/seal.ts`; `grep -n '"field_seal"' contracts/crypto/vectors/v1.json`; `grep -rn "padTo(" src/web/src src/packages` (Kotlin verifies no `field_seal` vector: `git grep -n field_seal -- src/backend` empty at refine) |
| Web registry | `register.ts`, `applications.ts`, `applicationMessages.ts` | `grep -n "registerDepot\|addApplicationMessages" src/web/src/applications.ts src/web/src/applicationMessages.ts` |
| Web guard | `OWNERS`, the `KIND` regex and `I18N_NAMESPACES` are hand lists | `grep -n "OWNERS\|KIND\|I18N_NAMESPACES" scripts/validate_web_core_imports_no_applications.py` |
| Export / import | content `$defs` + `content.oneOf` + the catch-all's `not.enum` | `grep -n "depotDropBox\|oneOf\|not" contracts/export/veyq-subtree.schema.json`; `src/web/src/features/depot/exportImport.ts` as the template |
| Report (web) | `ITEM` target and item review | `grep -rn "kind: 'ITEM'\|claimItemReview" src/web/src --include='*.ts' --include='*.vue'` |
| Site | pages that speak for what changes | `git grep -ln -E "docs/apps/README.md\|docs/apps/expenses.md\|0108-expenses" -- site/src/content/docs` (at refine: `applications/index.mdx` EN+DE, "veyq has two") |

## Texts

Every text follows [`docs/crosscutting/ui-web-styleguide.md` § Copy](../docs/crosscutting/ui-web-styleguide.md#copy-short-and-it-says-what-happens):
what happens and what it costs, usually one sentence, no reassurance. The product words are ADR 0108's (*Pot* /
*Kasse*, never group or tab); the categories' EN/DE are docs/apps/expenses.md's table. A few that carry a decision, the
rest the implementer writes to the rule:

| Key | EN | DE |
|---|---|---|
| `expenses.ledger.missing` | Changes to these entries are missing, so the balances may be wrong: | Änderungen an diesen Einträgen fehlen, die Salden können also falsch sein: |
| `expenses.people.deleteConfirm` | Their name is erased. Entries show them as “deleted people”. This can’t be undone. | Der Name wird gelöscht. Einträge zeigen die Person als „gelöschte Personen“. Das lässt sich nicht rückgängig machen. |
| `expenses.entry.stale` | Someone else changed this entry. Here is their version; save again to put yours on top. | Jemand anderes hat diesen Eintrag geändert. Hier ist die neue Fassung; speichere erneut, um deine darüberzulegen. |

## Phases

Each phase names its browser pass; the instance for every pass is a database of its own beside `veyq` on the dev
stack (changes/README.md § A live proof that names a screen), seeded with two accounts in one tenant, since the new
migration and two writers are what the passes need. Two writers are two hosts, not two ports.

1. **Contracts and crypto.** Before the first edit record `./gradlew check` and `npm run check` (web, admin-web) exit
   codes on `main`, and the guard baseline on a detached worktree of `HEAD` (`for f in scripts/validate_*.py; do
   python3 "$f" >/dev/null 2>&1; echo "$? $f"; done`). The Expenses spec: list (the revisioned-list pieces from
   `_common`), append (decision 1), erasure (decision 4), the guest twins under the share path, the re-key list and
   stage (planned in cr-scope-rotation-rekeys-nodes); member and guest read schemas apart (decision 3); the 16 KiB cap on
   a revision's body; `409 stale_revision` with the stale records. Its entry in `src/web/package.json`'s `generate:api`.
   `expense-entry` and `expense-entry-key` in `seal.ts` with their vectors (decision 0); the padding helper into
   `@veyq/crypto`; `contracts/expenses/entry.v1.schema.json` and `v1-vectors.json` (decision 5); the export schema's
   Expenses content. **Done when:** `npm run generate:api` runs; `src/packages/crypto` `npm run check` green (the
   conformance test reads the vectors both ways); the contracts guards green; `OperatorBlindProbeTest` classifies every
   new operation; the predicted backend red is only `ModuleCompositionTest` (a generated API no resource implements) —
   run `./gradlew check` once to see it; web and admin-web unchanged and green.
2. **Backend.** ✅ **Built 2026-10-24**, landed in one merge with phase 1's contracts (§ Retrospective). The module and its wiring; kind rules (decision 6); the revision table with `node_id`, `scope_id`, entry
   id, revision, tombstone, body, `K_rev` wrap, writer, size class, time; append with per-record CAS under the record
   rows' locks, then `items.bump`, then the position (docs/apps/README.md step 2's lock order); the erasure; member and
   guest reads; the guest gate and the guest-write record per revision; the subtree and scope erasers and the writer
   column's account-erasure class; the re-key participant (decision 0, planned in cr-scope-rotation-rekeys-nodes);
   `ReportableItems` and the hold contributor; `NodeItemsRevisionBumpsTest` learns the writers; the `expenses` entry
   and every Expenses write asking `ApplicationSwitch.requireWritable` (built by cr-application-switches), under
   `ApplicationSwitchSweepTest`.
   **Done when:**
   `./gradlew check` green; every guard of the baseline still `0`; slice tests, each negative anchored on its status and
   code with a positive control on the same predicate: a stale append lands nothing of its batch (control: the fresh one
   lands all); a guest's erasure refused (control: a member's erases the record's earlier revisions and only those); a
   `WRITE_ONLY` link on a Pot refused (control: `READ_WRITE` created); a guest read has no writer (control: the member
   read does); a guest append writes one guest-write record per revision; a re-key part that names a body is refused
   and lands nothing (control: one with the wraps alone lands, bodies byte-identical); a reported entry's hold copies
   its revision rows and survives the erasure; with `expenses` off an append is `422 capability_disabled` (control: on,
   it lands) while the list reads and the Pot goes to the trash. Bites, by snapshot-and-restore on a detached worktree: drop the bump
   from the append → `NodeItemsRevisionBumpsTest` red; drop the table from the subtree eraser →
   `validate_node_subtree_erasure.py` red; drop the wrap column from the participant → `validate_node_rekey_arity.py`
   red.
3. **Web: the domain, no screen.** ✅ **Built 2026-10-25** (§ Retrospective), with the reads and writes against the API and the
   web wiring phase 1 handed over (the `generate:api` entry, the guard's three hand lists, a catalog of the default
   categories). In `features/expenses/domain/`: the format v1 codec (pad, seal under `K_rev`, wrap,
   open, validate against the schema), allocation and rounding with `BigInt` fractions, balances, settle-up, overviews,
   the heads and the ledger check (ADR 0108 decision 5, with the erasure rule). **Done when:** `npm run check` green;
   unit tests run `contracts/expenses/v1-vectors.json` through the real code; the ledger check is bitten through the
   real crypto (no mocked seal): a served list missing one revision marks the Pot (control: the full list does not); a
   missing revision covered by an erasure of its own record passes, one covered only by another record's does not; a
   revision moved to another entry does not open; a re-wrapped `K_rev` (a re-key) leaves every digest matching. Bite the
   rounding: replace one `BigInt` division by a float one → a vector red.
4. **Web: the Pot.** ✅ **Built 2026-10-25**, its browser pass not run (§ Retrospective). The registration (`KindDescriptor` with `createDialog`/`createEntry`, `list.rows` with decision 8's
   detail, `detail`, `shareDialog` offering `READ`/`READ_WRITE`, `contentSchema`/`exportContent`/`importContent`/
   `validateContent`, `rekeyContent` (planned in cr-scope-rotation-rekeys-nodes)); the
   Pot page on core's existing pieces — `VeyqTabs` for Entries / Balances / Overview, the entry editor (splits, line
   items, a typed rate), People, Settings, History, Deleted entries, the ledger notice; rights as a pure function over
   `Access` like `depotRights`; the live list on `itemsLive.ts`; reports (decision 7); the rest of the `expenses` i18n namespace (phase 3 holds the categories), EN
   and DE. Site: `applications/index.mdx` (EN+DE) names three applications; new Use pages `expenses/index.mdx`,
   `expenses/record-an-expense`, `expenses/settle-up`, `expenses/share-a-pot` in EN and DE, each with `sources:`
   back-linked from the use-cases. **Done when:** `npm run check` green; `site` `npm run check` green; a **browser pass**
   in EN and DE: a member creates a Pot with three people, records an equal split, a line-item bill with a tip, a
   foreign-currency expense with a typed rate and a transfer, and the balances and settle-up match the vectors'
   arithmetic; a second member on another host edits the same entry first and the first save shows
   `expenses.entry.stale`; a `READ_WRITE` link guest adds an entry and a participant, sees no writer names and no
   Settings, and the member sees the guest's name in the history; delete and restore an entry; deactivate and delete a
   settled participant; download the Pot and import it into another folder (balances equal); copy it; report an entry as
   a guest and open it as a moderator; rotate the space's key and see every balance unchanged and no ledger notice; the
   Pot at 375 px.
5. **Close.** ✅ **Built 2026-10-25** (§ Retrospective); only phase 4's browser pass is left (§ Left open). Coverage: the seven files from `planned` to refs; Expenses' criteria in `uc.platform.switch-application-off`, `uc.platform.export-import`,
   `uc.platform.copy-node`, `uc.moderation.report` and `uc.spaces.rekey-after-rotation`, each beside the test that
   answers it. `docs/apps/expenses.md` and `docs/apps/README.md`'s table row say what is built; ADR 0108's status line
   names the two CRs still open; inbox #325's row names them as its next step. **Done when:** every
   `scripts/validate_*.py` exits `0` (`validate_guard_gaps.py --force` too, since phase 2 or 3 touched guards); `site`
   `npm run check` green; the heavy suites (L1, web-e2e, fuzz) are left to the V1 end-stage, named as not run.

## Left open: the browser pass — then delete this CR

Everything else is built, measured and harvested (§ Retrospective, phase 5): the coverage, the docs, ADR 0108, the inbox
rows, the code traps as comments at their lines. What this file still holds is **phase 4's browser pass**, which that
phase's done-when names and which no implementing agent could run (no Docker; changes/README.md § A UI phase's
done-when names its browser pass). Whoever runs it moves what it found into the code, the docs or an inbox row — a
defect it finds is fixed or filed before this file goes — and then deletes this CR in the same commit; nothing else in
it binds.

**Instance.** A database of its own beside `veyq` on the dev stack (changes/README.md § A live proof that names a screen
names the instance state that screen needs): a copy of the branch's fast-jar, `-Dquarkus.profile=e2e`, its own config
secret, `VEYQ_BUNDLED_STORE_PUBLIC_ENDPOINT` set to the browsed origin, the bundled S3 store (never the in-memory one),
then the seeder. It holds one tenant with **two member accounts** (A and B) in one **group space**, and a **moderator**
of the instance (ADR 0099). Two writers are **two hosts**: A on the tenant's host, B on a second `*.localhost` name
served by a second Vite with `VEYQ_SMOKE_HOST` (cookies and storage of its own). Before believing the DOM: read the
console and the failed requests, and name the tree the dev server serves.

**Steps** — all in English, then all again in German (the language switch; every label the catalog's, no key shown raw):

1. A creates a Pot "Trip" in a folder: base currency EUR, people Anna, Ben, Cleo in that order. Green: the Pot opens on
   Entries; the folder's row shows "EUR" as its detail.
2. A types in the "trip" Pot of `contracts/expenses/v1-vectors.json`, its nine entries as the vector holds them: equal
   splits (Dinner, Groceries with two payers, Ferry in GBP at a typed rate `1.1725`), line items with the rest in
   proportion (Trattoria) and equally (Bar), shares (Museum, JPY 5000 at a typed rate `0.0061234`), exact amounts (Taxi), a
   refund (Delayed train) and a transfer (Cash, Ben → Anna). Green: Balances shows Anna −€6.59, Ben +€39.28, Cleo
   −€32.69; the settle-up suggests Cleo → Ben €32.69, then Anna → Ben €6.59 (the vector's `expected`); no entry is marked
   invalid; on Overview the paid and share columns each sum to the costs.
3. B, on the second host, opens "Dinner" and saves a change while A's editor is open on it; A saves after. Green: A's
   dialog shows "Someone else changed this entry. Here is their version; save again to put yours on top." with B's
   version; saving again puts A's on top; the history lists three versions with A's and B's names.
4. A shares the Pot `READ_WRITE`: the dialog offers viewing and viewing-and-adding, no upload-only link. In a private
   window the guest opens the link, names itself, adds an entry and a person. Green: no Settings tab, no deactivate or
   delete on People, no writer's name in any history; in A's history the guest's entry carries the name it gave.
5. A deletes "Taxi" and restores it from **Deleted entries**. Green: it leaves the balances and brings them back as they
   were; no ledger notice after the restore.
6. A records the settle-up, then deactivates Cleo, activates her again and deletes her after the confirm; a person no
   entry names is removed. Green: Cleo's entries show "deleted people"; the removed person appears nowhere; neither is
   offered for a new entry.
7. A downloads the Pot and imports the ZIP into another folder, then copies the Pot ("Copy to…"). Green: both new Pots
   show the original's balances and settle-up; the copy dialog names what does not come along (each entry's history and
   the deleted entries).
8. The guest reports an entry; the moderator opens the case. Green: the review shows the entry's revisions in plaintext.
9. The space's key is rotated (B removed from the group space, or a queued rotation) and its re-key finishes. Green:
   every balance unchanged, no ledger notice ("Changes to these entries are missing…"), and the Pot still opens for A and
   through the guest's link.
10. The Pot at **375 px** wide: Entries, Balances, the entry editor with line items, People. Green: no horizontal scroll,
    every action reachable.

**Green overall** means every step's "Green" held in both languages, with no console error and no failed request but the
one a step provokes (step 3's `409`); screenshots only as the final proof of steps 2, 3, 4 and 10. **Not driven here,
named:** browsers other than Chromium, a real phone, the heavy suites (L1, web-e2e, fuzz) and the MariaDB race run
(inbox #544), all the V1 heavy stage's.

## Size — what "mostly client work" means here

Measured at refine: Depot is 14 main Kotlin files and 15 test files in its module, and 63 web files, 5,421 lines without
tests and 13,796 with them. This CR alone is **about Depot's size on both sides**: in the web its domain (rounding,
balances, settle-up, the ledger check) and its own screens (an editor with splits and line items, balances, overviews,
history) have no core level view to lean on as Depot's file list does; in the backend it needs its own table, a
compare-and-set append, an erasure, a hold contributor and a re-key participant. The claim holds for the *logic*: every
rule about money runs in the client. It does not hold for the *plumbing*: the backend joins core's seams — kind rules,
guest writes and their record, the revisioned list, subtree, scope and account erasure, the re-key participant,
reportable items and hold — each with a guard or test that is red until it does, and three hand lists in tests and
guards. A remembered estimate, not a measurement: two thirds client, one third contracts and backend.

## Definition of done

- The seven use-cases this CR satisfies built and covered; `python3 scripts/validate_coverage.py` green.
- A revision is never overwritten, a re-key changes no body, only a member's erasure removes a revision; the ledger
  check is bitten through the real crypto.
- Every core seam the Pot joins has its guard green and bitten where this CR changed it (bump, subtree erasure, re-key
  arity).
- The site's Applications page and the Expenses Use pages (EN+DE) are written; `site` `npm run check` green.
- Inbox #325 names the two CRs that follow; ADR 0108 says what is built.

## Open questions (owner)

None open. Answered at refine (owner 2026-10-23), each edited into ADR 0108: a key per revision (decision 4), a report
reaches the whole entry (decision 6), Frankfurter as the default provider (decision 8), three CRs. Settled here and not
stopped: the Pot's own categories are added and renamed, never removed, in the first cut.

## Retrospective

_Filled by the implementer._

**Phase 1 (2026-10-24), built on `main` `01260a52`, not landed.** Entry checks re-run: both sibling CRs are gone from
`changes/`, `NodeRekeyParticipant`, `rekeyContent` and `validate_node_rekey_arity.py` exist; the one thing that moved
since the refine is the participant's extra `lockRows(scopeId, nodeId)` hook. Baselines on that commit: `./gradlew check`
0, `npm run check` 0 in web, admin-web and `src/packages/crypto`, all 66 `scripts/validate_*.py` 0.

- **The done-when's backend red was remembered, not measured, and it is wider.** A spec under `contracts/api/expenses/`
  with no `:apps:expenses` fails Gradle while it configures (`buildSrc` `SpecSets.requireNoStray`), so `./gradlew check`
  runs no task at all, `validate_backend_module_graph.py` rule (d) is red, and `OperatorBlindProbeTest` cannot classify
  operations no module generates. So phase 1 split in two: the seals, the pad, the entry format, its vectors and the
  export content (`61e56111`) are green alone; the spec and the guard change it needs (`17d70016`) merge with phase 2's
  module, as § A contract that makes a client-sent field required argues for a contract no backend can build. Next
  time: a contracts phase that opens an application's spec directory runs `./gradlew help` at refine.
- **The `generate:api` entry is a web phase's, not phase 1's.** It generates, and `npm run check` is green with it
  (measured), but `validate_web_core_imports_no_applications.py` derives every application's client from that script
  and is red on an owner its `OWNERS` lacks — and `OWNERS` needs `features/expenses/` and its i18n catalog to exist.
  So the entry, `OWNERS`, `KIND` and `I18N_NAMESPACES` land together, with the first web phase that holds the catalog.
- **Two things the phase list did not name and the contract needed:** `listCasePotRevisions` (decision 7's case
  resource implements a generated API, so its operation is the contract's), and `validate_write_concurrency.py` could
  declare a revision only at the top level, not a batch's per-item one (it learned `revisions.revision`, bite case 94).

**Phase 2 (2026-10-24), built on `main` `c6132ec7` plus phase 1's contracts branch, and landed with them in one merge
(branch `worktree-agent-a02db9923432245a5`).**
`:apps:expenses` (`b63ca326`, `e8decffa`): the kind rules, `veyq_expenses_revision`, the append, the erasure, both
reads, the guest gate and record, the erasers, the re-key participant, the ITEM report with its hold and case review.
Measured: `./gradlew check` 0; every quick `scripts/validate_*.py` 0; `validate_guard_gaps.py --force` 0; the three
planned bites red and restored (bump dropped from `append` → `NodeItemsRevisionBumpsTest`; delete dropped from
`PotNodeSubtreeEraser` → `validate_node_subtree_erasure.py`; wrap assignment dropped from the participant →
`validate_node_rekey_arity.py`), and six slice-test bites, each red on its own test (no compare-and-set, no ledger line,
`WRITE_ONLY` admitted, no switch, an `ITEM` hold copying nothing, the participant's field check gone). No MariaDB race
was written: the CR asks none, though the append's lock order is new (a candidate for the V1 end-stage).

- **The non-member's 404 is `scope_not_found`, a Pot of the wrong kind `node_not_found`** (Depot's pair). With one code
  for both, `OperatorBlindProbeTest`'s control on the world's Memory answers exactly as the stranger and proves nothing.
  The contract's 404 descriptions name both codes now.
- **A participant's erasure writes an erasure-ledger line** (`EXPENSES_RECORD`, id `entryId:revision`, re-applied by
  `ExpensesErasures`): a restored backup would bring the erased name back otherwise (ADR 0056 decision 8, GDPR Art. 17).
  The phase list did not name it; the legal duty decides. The measure guard exempts the erasure and its re-application
  with the reason (the entry stays an item); the re-key arity guard excludes `sealed_body` (under `K_rev`).
- **The re-key bite, re-framed:** the stage's wire has no body field, so the bite writes a stage row of another field
  through `NodeRekeyStage.put` — and `put` replaces every earlier value of the same item. Staged apart, it wiped that
  revision's wrap and the bitten participant answered `rekey_parts_missing`, not `applied`; staged in one put, the
  bitten commit lands (204) and the test is red on the field check alone. A commit moves the Pot's item revision once and
  gives every revision that position, so an open list re-reads the new wraps.
- **Without the compare-and-set a stale append is a `500`**, not a silent overwrite: `ux_expenses_revision_entry` is a
  backstop under the node lock, not the answer.
- **Open for later CRs, not built here:** a content block on an entry refuses nothing yet (no blob, and the list carries
  no block mark; `cr-expenses-receipts` gives it something to refuse); a moderator's block of the Pot's node does not
  refuse a guest's append (the contract names no `451`); the guest's first page digests the whole Pot per read (no tag
  cache, as `ShareMediaTags` has). The verification metadata was not regenerated from a cold Gradle home: the module's
  plugins and dependencies are Depot's, and a warm `check` is green — remembered, not measured.

**Phase 3 (2026-10-25), built on `main` `df96b676` (phases 1 and 2 in).** `features/expenses/domain/`: `format.ts` (the v1
types and a reader held to the schema, whose patterns `format.test.ts` reads from the schema file), `money.ts` (`BigInt`
fractions, the conversion, the largest-remainder allocation), `codec.ts` (pad, seal under `K_rev`, wrap, open, the re-key's
re-wrap), `revisions.ts`, `heads.ts` (the heads and the ledger check), `ledger.ts` (effects, the unbalanced-entry rule,
balances, settle-up, overviews), `categories.ts`, and `potApi.ts` — the reads and writes the phase list did not name and the
orchestrator's brief did: the member's paged conditional read on core's `readItemPages`, the guest's tagged read, the batch
append on both paths (`409 stale_revision` → `StaleRevisionError` with the stale records) and the member's erasure.
Measured: `npm run check` 0 in `src/web` (4867 tests); every quick `scripts/validate_*.py` 0, and
`validate_guard_gaps.py --force` 0 (91 cases; the web guard's hand lists changed); every vector case runs through
the real code (15 conversions, 10 roundings, both Pots' entries, balances and settle-up); `src/packages/crypto` and
`src/admin-web` untouched, so neither their check nor the mutation gate ran.

- **The web wiring landed here, not in phase 4.** The reads and writes need the generated client; its `generate:api` line
  makes `validate_web_core_imports_no_applications.py` red without `OWNERS`, and `OWNERS` needs a catalog. So the line,
  `OWNERS`, `KIND` (`EXPENSES_POT`) and `I18N_NAMESPACES` (`expenses`) came with a catalog of the seven default categories
  (`features/expenses/i18n.ts`, added by `applicationMessages.ts`), which `categoryLabel` reads — the catalog's reverse
  guard (`i18n.test.ts`) is red on a key no source uses, so the catalog holds nothing phase 4's screens alone would read.
  Guard bitten by hand: a core file naming `'EXPENSES_POT'`, one quoting `'expenses.category.food'`, one importing
  `features/expenses/kinds`, and an Expenses file importing Depot's each red; restored. Nothing registers the application
  yet (`applications.ts` untouched).
- **Reader rules the schema leaves open, decided here:** an entry naming someone who is no participant of the Pot is
  invalid (`unknown-participant`: it has no place in the join order the rounding breaks ties by); a record whose latest
  revision does not open counts nowhere — no fallback to an earlier one, which would count what the server chose to serve;
  an erasure covers a missing revision only as a revision that opened to `kind: "erasure"` (the server's `erasure` flag is
  not read); and the ledger check also marks a hole in a record's revisions `1..latest`, which the named-head check misses
  once the revision naming the dropped one is dropped too (bitten: without the rule, a test that drops both is green).
- **The write path refuses before sealing** (`PotWriteRefused`, nothing sent): every money problem `entryProblem` names,
  two revisions of one record in a batch, an erasure through the append, a participant whose `joined` moved, a
  deactivation or an erasure while the balance is not settled.
- **Bites, each red and restored** (a script applying one sabotage, running the suite, restoring): the conversion's
  rounding as a float division → the `9007199254740991` vector red; the allocation's division as a float one → **no vector
  red**, only `money.test.ts`' own exactness case past 2^53 (the vectors carry no rounding case past the float range — a
  candidate for a vector, a contracts change not made here); ties broken in reverse → 16 red; truncation for the half rule →
  17 red; payers' or items' sums unchecked → the invalid-entry cases and the write-path refusal red; no re-pick in the
  settle-up → the "settle-up" vector red; the equal tip ignored → "Bar" and the trip's balances red; any record's erasure
  covering → the "another record's erasure" case red; the server's flag covering → the forged-erasure case red; no digest
  compare → the swapped-body case red; no named-head check → three red; the wrap bound to another entry → 33 red; a re-key
  that draws a new `K_rev` → the re-key cases red; a write that names no heads → eight red; an unsettled erasure sent → red.
- **For phase 4:** amounts are `bigint` in minor units of the base currency; the Pot's base currency (`BaseCurrency`, with
  its minor-unit digits) comes from its sealed meta, whose shape no contract holds yet — phase 4 defines it. A member's
  write key comes from core's `nodeKeyForWrite` (the floor). `rewrapRevisionKey` and `revisionKeyOf` are the cores of
  `rekeyContent` and of the report's keys. `core/export/ImportSubtreeDialog.test.ts` and `importHardening.test.ts` use
  `expenses` as an UNKNOWN application id: once phase 4 registers Expenses they need another id.

**Phase 4 (2026-10-25), built on `main` `7019ea60` (phases 1–3 in).** The registration (`features/expenses/register.ts`:
the Pot kind, "New Pot", its rows with the base currency as detail, `PotDetail`, `PotShareDialog` on core's link manager
with `READ`/`READ_WRITE`, the content hooks, `rekeyContent`, the route slug `expenses`, the item review, the application
entry); the Pot page (`components/`: Entries with the deleted ones, the entry editor with splits, line items and a typed
rate, its history, Balances with the settle-up recorded one by one or as one batch, Overview, People, Settings, the ledger
notice); `rights.ts` over `Access`; `potSession.ts` (the reads on core's `readItemPages` and `ITEMS_POLL`, the writes, the
epoch retry, the stale re-read); `rekey.ts`; `exportImport.ts`; `report.ts` with `PotEntryReview.vue`; the `expenses`
catalog in EN and DE. Site: `applications/index.mdx` names three applications; `expenses/{index,record-an-expense,
settle-up,share-a-pot}.mdx` in EN and DE, each back-linked from the use-cases it names. Measured: `npm run check` 0 in
`src/web` (4925 tests); `site` `npm run check` 0; every quick `scripts/validate_*.py` 0 (`validate_guard_gaps.py` not run: no
guard changed); `src/packages/crypto` and `src/admin-web` untouched.

- **The Pot's meta, decided:** the `pot` sub-object of core's `node-meta` envelope, `{ v: 1, baseCurrency, baseMinorUnits,
  categories?: [{ id, name }] }` — `contracts/expenses/pot-meta.v1.schema.json`, its bounds read by `potMeta.test.ts`. No
  vector: nothing is computed from it, and the envelope's seal is `node-meta`'s, already pinned in `v1.json`. A member
  writes it through core's `writeNodeMeta` (the change re-runs on a `409`); the base currency is fixed once the Pot holds
  any money entry, live or deleted. A Pot whose meta carries no readable `pot` shows no balance and takes no money entry
  until a member sets one in Settings — a client never guesses a currency.
- **A report key cannot name a revision.** `@veyq/crypto`'s report codec takes a `ref` that is a uuid or null and at most
  32 keys per item, so each `K_rev` goes as role `ENTRY_REVISION` with a null ref, the review tries each key on each held
  revision (a seal binds its address), and an entry of more than 32 revisions hands over its newest 32. "A report reaches
  the whole entry" (ADR 0108 decision 6) holds only up to 32 revisions: a candidate for the report codec, not changed here.
- **`appendPotWrites` answers the revisions it sent** (`heldOfSent` opens them): an import of more than 256 records appends
  batch after batch, each on what the one before wrote, or its entries would name people the next batch does not know.
- **A deleted participant imports as a deactivated one named by the catalog's "deleted people"**: its name is gone, and an
  erasure on import would need the balance settled first. A member can delete it again.
- **Bites**, by a script applying one sabotage, running the suites and restoring (16 sabotages, 15 red): a `READ_WRITE` guest managing people, a `READ`
  guest writing, the switch ignored, a delete offered to an unsettled person, the Settings tab for everyone, writers named
  for a guest, no re-read on a stale save, the ledger notice always on, erased balances shown apart, a deactivated person
  offered, the re-key sending the old wrap, a report handing one key, an import keeping the old ids, a guest writing as a
  member, a row without its currency — each red; "erased people not merged" stayed green, an equivalent mutant (the ledger
  gives an erased participant no name, so `p.name === null` already says it).
- **Not run:** the phase's **browser pass** (EN/DE, two hosts, a guest, a moderator, a rotation, 375 px): it needs a fresh
  instance on the dev stack, which this phase's agent may not start (no Docker). L1 specs: the phase asks none. Left for the
  orchestrator.
- **For phase 5:** the coverage moves (the seven use-cases, and Expenses' criteria under `uc.platform.switch-application-off`,
  `uc.platform.export-import`, `uc.platform.copy-node`, `uc.moderation.report`, `uc.spaces.rekey-after-rotation`) can point at
  `rights.test.ts`, `components/PotDetail.test.ts`, `components/PotDetail.guest.test.ts`, `entryForm.test.ts`,
  `exportImport.test.ts`, `rekey.test.ts`, `report.test.ts`, `people.test.ts` and `register.test.ts`; the project website's
  `site/src/components/website/strings.ts` still calls Memories and Depot "the first two" and lists shared expenses as an
  idea; "Who am I" (docs/apps/expenses.md § The Pot) is not built.

**Phase 5 (2026-10-25), built on `main` `70e68e16` (phases 1–4 in), branch `worktree-agent-a992de1147afaed61`.** The seven
coverage files from `planned` to refs; Expenses' criteria under `uc.platform.switch-application-off`
(`expenses-off-is-read-only`), `uc.platform.export-import` (`expenses-content`), `uc.platform.copy-node`
(`a-pot-copies-its-live-entries`), `uc.moderation.report` (`an-expenses-entry-is-reported-whole`) and
`uc.spaces.rekey-after-rotation` (`pot-revisions-rewrapped-bodies-unchanged`), each beside its test; two rounding vectors
past 2^53; the website's three applications; ADR 0108's status line, decision 3's erasure-ledger line and decision 6's
known gap; docs/apps (the table row, a rule on wiring a new application, the reader rules and the Pot meta of phases 3
and 4), the feature index and two use-cases saying what is not built; inbox #540–#544 and #325's next step;
`cr-expenses-receipts` taking the content block and the retention. Measured on the branch: `npm run check` 0 in
`src/web`, `site` `npm run check` 0, `./gradlew check` 0, every quick `scripts/validate_*.py` 0
(`validate_coverage.py` among them); `validate_guard_gaps.py` not run, no guard changed.

- **Two defects the coverage work found, each red first, then fixed.** A restore wrote the deleted content back with the
  heads ITS writer had read: `sealPotWrites` spread the content after its own heads, so an opened body's `heads` won and
  the restored revision did not name the tombstone it lifts (the new restore case in `PotDetail.test.ts` red; fixed in
  `sealPotWrites`, the comment at the line). And the append's compare-and-set used what its row locks returned before the
  node lock, so a second append that waited on the first's rows did not see the rows the first inserted: on H2 both
  `PotAppendRaceSliceTest` cases answered `500` (the unique index) where a replay answers `409 stale_revision`. It now
  reads the records again under the node lock (`PotRevisionStore`'s lock-order note); the MariaDB run is #544's.
- **Two criteria could not move as written, and left the coverage with a comment naming where they come back.** "Who am I"
  is `uc.expenses.balances` step 2 and no phase of this CR named it, so it was not built (#541); "past the retention, no
  restore" needs the retention clock the receipts' purge brings (`cr-expenses-receipts` decision 6). A criterion without
  a test has no place in a built coverage file. Root cause: the criteria were first held against the tests at the close,
  not when the phases were cut. Next time: a refine that satisfies a use-case names, per criterion, the phase whose
  test answers it.
- **The rounding bite of phase 3 is closed in the vectors.** A float share division in `allocate` now turns two vectors red
  (`9007199254740991` by 7:11:13 and by 999999:1:1, computed with Python `Fraction` from the rule as written, beside a
  float simulation of that sabotage showing both differ); the sabotage was applied to `money.ts` (three tests red) and
  restored.
- **Process:** phase 4's done-when names a browser pass, and its brief and this one forbid Docker, so the CR could not
  close in the sessions that built it. Next time: a phase whose done-when needs an instance is planned for a session that
  may start one, or its brief names the pass as the orchestrator's from the start.
