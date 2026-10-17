---
id: cr-bucket-reconcile
title: A bounded, resumable sweep lists the bucket and counts the objects no row claims
status: planned
satisfies: [uc.admin.configure-object-store]
adrs: ["0062", "0056", "0089", "0092", "0093", "0067"]
invariants:
  - operator-blind E2EE — the sweep reads opaque keys, sizes and the store's timestamps; it opens nothing, attributes nothing to a scope (ADR 0062 Decision 6), and no key reaches a log
  - self-hostable by default — one S3 operation every S3-compatible store has (ListObjectsV2), on both deployment targets, no new dependency
  - ADR 0062 Decision 3 — StoreReclaimSweep stays the only deleter of store objects
---

## Goal

Inbox #134: nothing compares the bucket against the database. The owner decided on 2026-10-17 ("134 ja") to build the
reconcile ADR 0062 parked. This CR builds a backend sweep that lists the object store page by page — bounded per tick,
resumable from a cursor in the database — and counts the objects that **no row claims**: no `veyq_blob`, no
`veyq_upload_session`, no `veyq_store_reclaim` row names their prefix and chunk index, and they are older than a safe
age read off the store's own clock. The Server Owner sees the count (objects, bytes, when the last full pass ended,
what is not veyq's), next to the reclaim list's pending and abandoned counts, in admin-web's **Object store** page and
in `veyq-admin`. **Whether an orphan is ever deleted is open (O1)**; the CR is written for the recommendation —
report only — and states what each other answer changes.

## Why this is no longer a reconcile that can only find nothing

ADR 0062 parked the reconcile because, after `cr-reclaim-waits-out-the-presign-ttl`, its only producer was a writer
outside veyq, so against a stock of zero its only honest Definition of done was *"finds nothing"* (inbox #134, owner
2026-09-28). **That premise expired on 2026-10-17**, re-derived at refine against the tree:

- `veyqctl rollback` is **in place** (ADR 0056 §7, owner 2026-10-17; ADR 0096): it empties the database and restores
  the pre-upgrade capture *with the check only*, and *"the store is never written; objects uploaded under the failed
  release stay as orphans with no reclaim row"* — measured: `grep -n 'stay as orphans' scripts/veyqctl.py
  site/src/content/docs/run/upgrade.mdx` → `veyqctl.py:124`, `:1504`, `upgrade.mdx:252`.
- A check-only restore of an older capture onto the live store tolerates *"an object the store holds and the index
  does not … (an upload after the capture)"* (`grep -n 'upload after the capture' scripts/instance_backup.py` →
  `:1013`, `:1780`) — and those are exactly objects no row claims once the older dump is loaded.

So veyq itself now produces orphans, by a documented procedure, and the reconcile has a Definition of done other than
*"finds nothing"*: **after an in-place rollback it counts exactly the uploads made under the failed release** — the
live proof of phase 2.

## Context & constraints

- Decisions this builds on: [ADR 0062](../docs/decisions/0062-store-object-reclaim-is-a-durable-work-list.md)
  § Consequences, the reconcile bullet (the three tables; a `storage_key` appears twice; the read-order rules) and the
  rejected *"scheduled sweep that lists the bucket and deletes"* arm; [ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md)
  § Context 2 (an orphan is a reclaim pending) and §7 (in-place rollback); [ADR 0089](../docs/decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
  decision 5 (the store follows the setting at runtime — `SwitchingBlobStore`); ADR 0092's spirit (logs are
  operational: counts, never keys); [ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 4 (hold pins).
- Use-case: [uc.admin.configure-object-store](../docs/features/admin/use-cases/uc.admin.configure-object-store.md) —
  gains an alternate flow (phase 4), written when O1 is answered.
- **Objects veyq writes, and what names them** (measured at refine, this worktree at `c1e3ffee`):
  - a chunk is `<storage_key>/<index>` (`grep -n 'fun chunkKey' backend/core/src/main/kotlin/me/raddatz/veyq/core/blobstore/BlobStore.kt`);
    `storage_key` is a server-minted `UUID.randomUUID()` (`grep -n 'val storageKey = UUID' backend/core/src/main/kotlin/me/raddatz/veyq/core/blob/BlobUploadHandler.kt` → `:212`);
    a window is signed only for indices `< chunkCount` (`grep -n 'fun presignWindow' -A1 …/BlobUploadHandler.kt` → `fromChunk until minOf(fromChunk + window, chunkCount)`).
  - the store check's probe is `veyq-check/probe`, *"overwritten by every check and never deleted"*
    (`grep -n 'PROBE_KEY' backend/core/src/main/kotlin/me/raddatz/veyq/core/blobstore/StoreCheck.kt` → `:192`).
  - an object pool (`scripts/instance_backup.py --objects-to s3:<bucket>[/<prefix>]`) may be pointed at any bucket;
    nothing refuses the store's own (`grep -n 'same bucket' scripts/instance_backup.py` → empty), and its ledger is
    `veyq-pool/captures` (`grep -n '^LEDGER' scripts/instance_backup.py`).
  - a store of the operator's own is not required to be a dedicated bucket
    (`grep -n -i 'dedicated\|own bucket' site/src/content/docs/run/storage-and-email.md` → empty).
- **Tables that carry a storage key** — `grep -ln storage_key backend/core/src/main/resources/db/migration/*.sql
  backend/apps/*/src/main/resources/db/migration/*.sql` → only the core baseline, three columns
  (`veyq_upload_session` :295, `veyq_blob` :317, `veyq_store_reclaim` :574). `veyq_store_reclaim` has no unique index on
  it (ADR 0062: a committed blob keeps its session's key). **`cr-report-and-hold` adds `veyq_hold_pin`** (its decision
  4: *"Pins are rows of `veyq_hold_pin` (storage key, hold, `expires_at`)"*), planned, not built
  (`grep -rn hold_pin backend scripts` → empty). Its pinned keys keep their reclaim row (the sweep's skip is in `due`,
  its decision 5), so a pinned object is already explained — but the claim set names the table anyway, held by guard
  G1 below, so that the second CR to land cannot forget it (as with the backup index, `grep -n 'def blob_index' -A10
  scripts/instance_backup.py`).
- **The claim chain is continuous, which is what makes an after-read sufficient.** A key is minted inside
  `createSession`'s transaction before any URL is signed (`BlobUploadStore.createSession`, `@Transactional`); session →
  blob is one transaction (`persistBlob` joins the caller's, which deletes the session); every row drop records its
  key in the same transaction (`StoreReclaimStore.record` is `MANDATORY`); a reclaim row is settled only after the
  store reported all its keys deleted (`StoreReclaimSweep.run`: `handOver` before `settle`), and is not due before
  `veyq.blob.upload.retention` (`grep -n 'nextAttemptAt = now.plus(notBefore)' …/StoreReclaim.kt`), so no PUT signed
  earlier can re-create an object after its row settled. Hence: **an object that is present after a read in which no
  row claims it is an orphan** — its chain ended (settled, so its objects were deleted and cannot return) or never
  began (not veyq's PUT).
- **The listing, measured on Garage v2.0.0** (a throwaway `dxflrs/garage:v2.0.0` on `127.0.0.1:29400`, removed after;
  signed with `curl --aws-sigv4`): `GET /blobs?list-type=2&max-keys=2` → `200`, header `date: Sat, 17 Oct 2026
  17:34:32 GMT`, `<IsTruncated>true</IsTruncated>`, a `NextContinuationToken`, and per object `<Key>`, `<Size>`,
  `<LastModified>2026-10-17T17:34:24.035Z</LastModified>`; `…&start-after=11111111-…/1` → the remaining two keys,
  `veyq-check/probe` among them (it sorts after every UUID). So `start-after` resumes a listing from a stored key, and
  the response's `Date` and each `LastModified` come from **one clock, the store's**.
- The backend has no metrics endpoint (`grep -n '## No metrics' -A3 site/src/content/docs/run/operations.md`); the
  reclaim list's counts are SQL only today (`operations.md:77`). The chart runs one app replica
  (`grep -n 'replicas:' charts/veyq/templates/app-deployment.yaml` → `1`).
- `validate_store_reclaim_arity.py` already proves *"exactly 1 BlobStore delete call site … StoreReclaimSweep.kt"*
  (run at refine: `OK … StoreReclaimSweep.kt:83`). Under the recommended O1 it is this CR's proof that the reconcile
  deletes nothing, with no new guard.
- Parallel work: `cr-instance-contact-and-provider-identification` edits admin-web's console and the backend admin
  surface, and likely `contracts/api/instance.openapi.yaml`. This CR adds its own path to that file and its own panel
  component; in `StorePanel.vue` it adds one import and one tag (`changes/README.md` § Two CRs that write the same page).

## Decisions settled at refine

1. **A port operation, read-only: `BlobStore.list(startAfter: String?, maxKeys: Int): StoreListing?`** —
   `StoreListing(objects: List<StoredObject(key, size, lastModified)>, storeTime: Instant, truncated: Boolean)`, null
   when the store cannot list (`InMemoryBlobStore`, `UnconfiguredBlobStore`). `S3BlobStore` calls ListObjectsV2 with
   `startAfter` (a key, so the cursor survives a restart; a continuation token is opaque and may expire) and reads
   `storeTime` from the response's `Date` header (`sdkHttpResponse().firstMatchingHeader("Date")`); a response without
   one **throws**, so the page is never classified against another clock. `SwitchingBlobStore` delegates. No `delete`
   anywhere in the sweep.
2. **The safe age is `veyq.blob.upload.retention`, measured on the store's clock** — `storeTime − lastModified ≥
   retention`. Re-derived rather than taken from the brief's *"presign TTL + upload-session lifetime + clock skew"*:
   the presign TTL plus one chunk still streaming **is** `retention` (`application.properties:120-126`, the bound the
   reclaim not-before already uses — one number, one meaning); an upload session's lifetime is **unbounded** (signed
   window by window, `UploadSessionPurge`'s docstring), so no age can cover a live upload — its session row does, and
   the age is a second layer, not the primary; clock skew disappears because both instants are the store's. No new
   knob.
3. **A key is classified before it is judged.** Exactly `veyq-check/probe` → *probe*, ignored. Matching
   `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(0|[1-9][0-9]*)$` → *veyq-shaped*. Anything else →
   *not veyq's*, counted separately and never an orphan (a pool in the same bucket, a shared bucket's other tenant, a
   hand-copied file).
4. **Per page, in this order:** list (≤ 1000 keys) → one claim query for the page's veyq-shaped prefixes: a single
   `SELECT storage_key, chunk_count … UNION ALL …` statement over the claim tables with `storage_key IN (…)` (one
   statement is one consistent read, which is ADR 0062's *one snapshot* rule) → a veyq-shaped object is **explained**
   when some claim row has its prefix and `chunk_count > index` → an unexplained one older than the safe age is a
   **candidate** → `stat` each candidate, and only one still present is an **orphan** (counted with the listed size).
   The `stat` replaces ADR 0062's *read before*: a row deleted and drained during the listing deleted its object first,
   so the object is absent at the `stat`; the after-read catches an upload that began during the listing (the
   continuity argument above). Phase 4 amends ADR 0062's bullet to this.
5. **State: one row, `veyq_store_reconcile` (id 1), written per page in its own transaction** — the in-progress pass
   (`started_at`, `cursor`, running counts) and the last completed pass (`completed_at`, objects, orphan objects,
   orphan bytes, not-veyq's objects), plus the last failure (`failed_at`, a reason enum: `STORE_UNREACHABLE`,
   `LIST_DENIED`, `NO_STORE`, `DATABASE`). Created on first write, no seed. It joins `NOT_DATA` in
   `scripts/instance_backup.py` (`grep -n '^NOT_DATA = ' -A8`), or a fresh instance's first pass would make A5 refuse a
   restore onto it. Counts only — no key but the cursor, which is the store's own name for a position.
6. **Bounded and resumable:** `@Scheduled(every = "{veyq.store-reconcile.every}", concurrentExecution = SKIP)`; a tick
   lists at most `veyq.store-reconcile.pages-per-tick` pages from the cursor; a pass that reaches `truncated = false`
   is published as the last completed pass, and a new pass starts no sooner than `veyq.store-reconcile.period` after
   the previous one started. Defaults are set at implement by the tick's duration measured on a 100 000-object Garage
   listing (the expected household order), not guessed here.
7. **What it must never count as an orphan, and never act on:** a key a live upload may still PUT (its session row
   claims it; the age is the second layer); the probe; anything not veyq-shaped; any page classified while the
   database or the listing failed — **a failure ends the tick, keeps the cursor, records the reason, and publishes
   nothing**: the last completed pass stays what the console shows, with the failure beside it. A store setting that
   moves (ADR 0089) restarts the pass from the beginning (the pass records the setting it lists; locator at implement:
   `grep -n 'fun current' backend/core/src/main/kotlin/me/raddatz/veyq/core/blobstore/StoreSetting.kt`).
8. **One log line per completed pass, at INFO, counts only.** A failure logs once at the transition into it (the
   reclaim sweep's rule, `StoreReclaimSweep.run`), never per tick and never a key.
9. **The read is the Server Owner's:** `GET /api/instance/store/reconcile`, `x-veyq-channel: admin`, `403
   not_server_owner` otherwise — the last completed pass, the pass in progress (started, objects so far), the last
   failure, and the reclaim list's `pending` and `abandoned` counts (`SELECT count(*)` both — what operations.md
   tells the operator to type today). No key, no scope. `veyq-admin store reconcile` prints the same through the same
   facade (`admin-web/src/cli/commands.ts`, the "mirrors what admin-web holds" rule in its header).
10. **Rejected — the operator tool on rclone** (ADR 0062's cheaper arm: `instance_backup.py`'s listing plus one
    database snapshot). It needs no port, but it runs only when someone runs it, cannot put a number in the console,
    and would read the database and the listing at two moments with nothing to bracket them but the operator's
    timing. The backend sweep pays one port operation and is periodic.

## Open for the owner

**O1 — Does the reconcile ever delete an orphan?** The parking reasons, re-read against the tree:

- *"Against a stock of zero it buys nothing"* — **no longer true**: an in-place rollback leaves every upload of the
  failed release behind, and those bytes are unreadable by anyone (their wrapped keys were in the emptied database).
- *"A delete path that buys nothing is one more thing that can be wrong about user content"* — **still true, and
  sharper than in ADR 0062**: (i) a store of the operator's own need not be a dedicated bucket, and two veyq instances
  sharing one (a staging instance) would each see the other's objects as veyq-shaped orphans; (ii) after a restore of
  the *wrong* dump the database is the wrong side, and the orphans are the data a restore of the right dump needs;
  (iii) a future table that keeps a key alive and is missing from the claim set turns a wrong count into lost media.

Options:

- **(a) Report only (recommended).** This CR as written. Nothing deletes; `validate_store_reclaim_arity.py` stays the
  proof. The operations page says what a non-zero count means (after a rollback: that release's uploads; otherwise: a
  writer other than this instance, or a bug — report it) and how to free the bytes by hand if the operator is sure.
- **(b) Report, plus a reclaim the Server Owner confirms.** Adds an action — console and `veyq-admin store reconcile
  --reclaim` — that re-runs the classification for the last pass's orphans and puts the still-unclaimed,
  still-present ones on `veyq_store_reclaim` (so the existing sweep, its not-before and its one call site do the
  deleting). Costs: a table of orphan prefixes (opaque, no scope), a contract `POST`, a confirm dialog, and bites for
  (i)–(iii) at the enqueue step. Decides nothing for the operator: they know whether the bucket is shared and which
  dump is right.
- **(c) Automatic reclaim** of every orphan past the safe age. Rejected by (i)–(iii): it turns each into silent,
  irreversible loss on someone's media, with no human between the count and the delete.

**Recommendation: (a) now, (b) as its own CR once a real instance shows a count.** (a) is the whole of (b)'s
classification, so nothing built here is thrown away, and (b) can then be designed against an actual rollback residue
instead of an imagined one. If the owner answers (b) now, phases 1–3 each gain the action named above and phase 2 the
orphan table; the bites below stay and B13–B15 are added for (i)–(iii).

## Phases

Baseline before the first edit: `for f in scripts/validate_*.py; do [ "$f" = scripts/validate_guard_gaps.py ] &&
continue; python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done` and `(cd backend && ./gradlew check)` exit code on
`main`, recorded in the retrospective — a red afterwards is this CR's only against that list.

- [ ] **0 refine completion** (after O1) — ADR 0062's reconcile bullet amended to what O1 and decisions 2–4 settle
  (built; report only or (b); the `stat` in place of the read-before; the age on the store's clock), its rejected
  *"scheduled sweep that deletes"* arm kept with the re-read reasons (i)–(iii); uc.admin.configure-object-store gains
  **A7 — Objects no row claims** and its coverage file the criterion `orphans-are-counted-never-deleted` (or (b)'s
  wording) marked decided, not built. · Done when: `python3 scripts/validate_changes.py` and `validate_coverage.py` green.
- [ ] **1 contracts** — `GET /api/instance/store/reconcile` in `contracts/api/instance.openapi.yaml` with its schema
  (decision 9), examples for *never ran*, *in progress*, *completed with orphans*, *failed: LIST_DENIED*. · Done when:
  the contract validators green (`python3 scripts/validate_*.py`); the backend's generated interface is red until
  phase 2, and admin-web's client until phase 3 (`changes/README.md` § Phases only above real size).
- [ ] **2 backend** — decisions 1–9: the port operation and its three implementations (+ the test-scope
  `RecordingBlobStore` gains a scripted listing), the migration (`V<stamp>__core_store_reconcile.sql`), the sweep
  (`core/blobstore/StoreReconcile.kt`), config (`veyq.store-reconcile.*` in `VeyqConfig` + `application.properties`),
  the resource, `NOT_DATA`; guard **G1** `scripts/validate_store_reconcile_claims.py`: every table a migration gives a
  `storage_key` column is named by the claim query (derived from the migrations, never listed in the guard). Slice
  tests on the scripted store, one per bite:
  - **B1 live upload** — an open session with a PUT chunk older than the safe age → 0 orphans; drop the session branch
    from the claim query → 1 (red).
  - **B2 pending reclaim** — a blob purged (its reclaim row pending) → 0; drop the reclaim branch → red.
  - **B3 committed blob** → 0; drop the blob branch → red.
  - **B4 the probe** — `veyq-check/probe` → neither orphan nor not-veyq's; delete the probe case → not-veyq's = 1 (red).
  - **B5 not veyq's** — `veyq-pool/captures`, `x/0`, `<uuid>/01` → not-veyq's = 3, orphans 0; widen the pattern to
    `[^/]+/\d+` → orphans > 0 (red).
  - **B6 index past the count** — `<claimed key>/<chunk_count>` → 1 orphan; drop `chunk_count > index` → 0 (red).
  - **B7 the safe age** — an unclaimed veyq-shaped object with `storeTime − lastModified = retention − 1s` → 0, at
    `retention` → 1; read the age off `Instant.now()` with the store's clock set an hour ahead → red.
  - **B8 drained during the listing** — the listing returns a key whose `stat` answers null → 0; skip the `stat` → 1 (red).
  - **B9 resumable** — page 2 of 3 throws: no completed pass published, cursor after page 1, failure `STORE_UNREACHABLE`;
    the next tick resumes and the completed pass carries each object once; reset the cursor on failure → the totals
    double-count page 1 (red).
  - **B10 database fails** — the claim query throws (an injected `@Alternative`) → that page counts nothing, no pass
    completes, failure `DATABASE`; swallow the exception and classify the page as unclaimed → orphans > 0 (red).
  - **B11 no `Date` header** — the scripted listing omits it → the page is not classified; fall back to the backend
    clock → red.
  - **B12 store unusable** — `UnconfiguredBlobStore` → `NO_STORE`, nothing counted; `LIST_DENIED` on an S3 `403`.
  - **G1 bite** — a scratch migration adding `storage_key` to a new table → G1 red naming it; G1 joins
    `validate_guard_gaps.py`'s cases only if it changes a guard there (`changes/README.md` § trust-chain guards).
  - **Opt-in S3 e2e** (`VEYQ_S3_E2E=1`, beside `BlobBatchE2ETest`): `S3BlobStore.list` against a real Garage — pages
    by `startAfter`, `storeTime` from `Date`, `truncated` — the one un-mocked test that runs before the mapping is
    trusted (`changes/README.md` § Where a mapping depends on a library's output).
  · Done when: `(cd backend && ./gradlew check)` green (own build, never `quarkusDev`); the sweep takes no lock, so
  `scripts/run_backend_races_on_mariadb.py` is not required (if implement adds one, it runs); `validate_store_reclaim_arity.py`
  still reports exactly one site; `instance_backup_selftest.py` green; **live proof** on a throwaway Compose project of
  this CR's own (never the owner's dev stack, never `run_web_e2e.py`): capture, upload two files, restore the capture in
  place with the check only, run a pass → orphans = those files' chunk count and bytes; a pass on the untouched
  instance → 0.
- [ ] **3 admin-web** — `features/instance/StoreReconcilePanel.vue` (its own component, shown by `StorePanel.vue`
  while a store is in use), a total render map over the failure enum (as `StoreProvisioningPanel.vue` does for its
  states), EN + DE strings, the `store reconcile` CLI command; unit tests per state. · Done when: `npm run check` in
  `admin-web/` green and the panel clicked in a browser against the live proof's instance (orphans shown, then a
  failure shown after stopping its Garage).
- [ ] **4 docs** — ADR 0062 status of the bullet (built), ADR 0056 § Context 2's last sentence about counting;
  `site/src/content/docs/run/operations.md` (the Background jobs table row; the console replaces the SQL, which stays
  as the database-side form); `run/upgrade.mdx`'s rollback paragraph and `run/backup-and-restore.md`'s tolerated
  orphans point at the count; `run/storage-and-email.md` says an own store's key needs to list the bucket for the count
  (`LIST_DENIED` otherwise); coverage criterion flipped to built. · Done when: every `scripts/validate_*.py` green and
  `npm run check` in `site/` green.

## Definition of done

The phases' Done-whens; every `scripts/validate_*.py` green, plus `validate_guard_gaps.py --force` because G1 is a new
guard; `docs/coverage/` updated for uc.admin.configure-object-store with `validate_coverage.py` green; the live proof
recorded with its commands and outputs in the retrospective; the docs site's Run pages updated; inbox #134 marked done
with what was built and O1's answer. Then the harvest: what binds moves to ADR 0062 and the code, and this file is deleted.

## Retrospective

(filled at close)
