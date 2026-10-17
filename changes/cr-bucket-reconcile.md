---
id: cr-bucket-reconcile
title: A bounded, resumable sweep lists the bucket, counts the objects no row claims, and reclaims them after a grace on an exclusive bucket
status: done
satisfies: [uc.admin.configure-object-store]
adrs: ["0062", "0056", "0089", "0092", "0093", "0067"]
invariants:
  - operator-blind E2EE — the sweep reads opaque keys, sizes and the store's timestamps; it opens nothing, attributes nothing to a scope (ADR 0062 Decision 6), and no key reaches a log
  - self-hostable by default — one S3 operation every S3-compatible store has (ListObjectsV2), on both deployment targets, no new dependency
  - ADR 0062 Decision 3 — StoreReclaimSweep stays the only deleter of store objects; the reconcile only puts keys on its list
---

## Goal

Inbox #134: nothing compares the bucket against the database. The owner decided on 2026-10-17 ("134 ja") to build the
reconcile ADR 0062 parked. This CR builds a backend sweep that lists the object store page by page — bounded per tick,
resumable from a cursor in the database — and counts the objects that **no row claims**: no `veyq_blob`, no
`veyq_upload_session`, no `veyq_store_reclaim` row names their prefix and chunk index, and they are older than a safe
age read off the store's own clock. The Server Owner sees the count (objects, bytes, when the last full pass ended,
what is not veyq's), next to the reclaim list's pending and abandoned counts, in admin-web's **Object store** page and
in `veyq-admin`. **An orphan is deleted only after a grace period, on a bucket the Server Owner confirmed is this
instance's alone, unless paused** (O1, answered 2026-10-17 — § Decided with the owner): every orphan key keeps a
first-seen clock, a key that stayed an orphan in every pass for the grace goes onto the existing reclaim list, and any
restore or rollback restarts every clock.

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
  gains A7 (phase 0), written from O1's answer.
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
  (run at refine: `OK … StoreReclaimSweep.kt:83`). Under O1's answer it stays this CR's proof that the reconcile
  deletes nothing (it only records keys on the reclaim list), with no new guard.
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
   (`started_at`, `cursor`, page counter, running counts) and the last completed pass (`completed_at`, objects, orphan
   objects, orphan bytes, not-veyq's objects), plus the last failure (`failed_at`, a reason enum: `STORE_UNREACHABLE`,
   `LIST_DENIED`, `NO_STORE`, `NO_STORE_CLOCK`, `DATABASE`), and the owner's switches of decisions 11 and 14. Created on
   first write, no seed. It and `veyq_store_orphan` (decision 12) join `NOT_DATA` in `scripts/instance_backup.py`
   (`grep -n '^NOT_DATA = ' -A8`), or a fresh instance's first pass would make A5 refuse a restore onto it. No key in
   the state row but the cursor, which is the store's own name for a position.
6. **Bounded and resumable:** `@Scheduled(every = "{veyq.store-reconcile.every}", concurrentExecution = SKIP)`; a tick
   lists at most `veyq.store-reconcile.pages-per-tick` pages from the cursor; a pass that reaches `truncated = false`
   is published as the last completed pass, and a new pass starts no sooner than `veyq.store-reconcile.period` after
   the previous one started. Defaults are set at implement by the tick's duration measured on a 100 000-object Garage
   listing (the expected household order), not guessed here.
7. **What it must never count as an orphan, and never act on:** a key a live upload may still PUT (its session row
   claims it; the age is the second layer); the probe; anything not veyq-shaped; any page classified while the
   database or the listing failed — **a failure ends the tick, keeps the cursor, records the reason, and publishes
   nothing and enqueues nothing**: the last completed pass stays what the console shows, with the failure beside it.
   A store setting that moves (ADR 0089) restarts the pass from the beginning (decision 16). *Acting* on an orphan is
   only decision 15's enqueue, under decisions 11–14.
8. **One log line per completed pass, at INFO, counts only.** A failure logs once at the transition into it (the
   reclaim sweep's rule, `StoreReclaimSweep.run`), never per tick and never a key.
9. **The read and the switches are the Server Owner's:** `GET /api/instance/store/reconcile`, `x-veyq-channel:
   admin`, `403 not_server_owner` otherwise — the last completed pass, the pass in progress (started, objects so far),
   the last failure, the candidates and the verdict (decision 14), the exclusive confirmation, the pause, the last
   clock restart, and the reclaim list's `pending` and `abandoned` counts (`SELECT count(*)` both — what operations.md
   tells the operator to type today). `PUT`/`DELETE …/reconcile/exclusive-bucket` confirm and withdraw (decision 11,
   `409 no_store` while no store is usable), `PUT`/`DELETE …/reconcile/pause` pause and resume; each answers the read.
   Their own tag (`instance-store-reconcile`, its resource owns the subtree) so the parallel admin CR's `instance`
   tag stays untouched. No key, no scope. `veyq-admin store reconcile` prints and switches the same through the same
   facade (`admin-web/src/cli/commands.ts`, the "mirrors what admin-web holds" rule in its header).
10. **Rejected — the operator tool on rclone** (ADR 0062's cheaper arm: `instance_backup.py`'s listing plus one
    database snapshot). It needs no port, but it runs only when someone runs it, cannot put a number in the console,
    and would read the database and the listing at two moments with nothing to bracket them but the operator's
    timing. The backend sweep pays one port operation and is periodic.

## Decided with the owner

**O1 — answered 2026-10-17 ("ja"): an orphan is deleted after a grace period, under three conditions** — neither
(a) report only nor (c) blind automatic reclaim. The re-read reasons (i)–(iii) below are what each condition answers;
they stay the reason the rejected arm (c) stays rejected.

- (i) a store of the operator's own need not be a dedicated bucket, and two veyq instances sharing one would each see
  the other's objects as veyq-shaped orphans;
- (ii) after a restore of the *wrong* dump the database is the wrong side, and the orphans are the data a restore of
  the right dump needs;
- (iii) a future table that keeps a key alive and is missing from the claim set turns a wrong count into lost media
  (guard G1 holds the claim set to the migrations).

11. **Exclusive bucket — answers (i).** The Server Owner confirms once, in admin-web's panel or with
    `veyq-admin store reconcile --confirm-exclusive-bucket`, that the bucket in use holds only this instance's objects.
    Without that confirmation the reconcile **only reports**. The confirmation is stored with its time and with the
    digest of the store it was given for (decision 16); a store setting that changes (ADR 0089 — a new setting put in
    use, or one a restore brings) no longer matches the digest, and the confirmation is void and cleared. It can be
    withdrawn (`--withdraw-exclusive-bucket`).
12. **Grace from first seen — answers (ii) and the race no read can close.** `veyq_store_orphan` holds one row per
    orphan **storage key** (the prefix: no row claims it at all) with its first-seen instant, read off the store's
    clock like the safe age, and the pass that last saw it. A completed pass drops every row it did not see, so a
    row that survives was an orphan in **every** pass since it was first seen; a claim row that reappears (a restore)
    makes its key explained, so the next completed pass drops it, and the enqueue re-checks the claim set anyway.
    A key is **due** once `storeTime − firstSeen ≥ veyq.store-reconcile.grace` (default `P30D`). The table holds
    opaque keys and counts — no scope, as `veyq_store_reclaim` (ADR 0062 Decision 6). It joins `NOT_DATA` with the
    state row: a fresh instance's first pass, or the rollback's one start on an emptied database, writes rows there.
13. **A restore or a rollback restarts every clock — detected twice.**
    - **The restore writes the reset.** Every restore and rollback veyq documents ends in `instance_backup.py
      restore` (`cmd_restore`: `veyqctl restore`, `veyqctl rollback` with the capture's release's tool, the
      cluster's gate restore through its record), which, after the dump is loaded, **empties** both reconcile
      tables: every first-seen clock restarts, and the exclusive confirmation and a pause are gone with the state row,
      so nothing is deleted until the Server Owner confirms the bucket again. Clearing the confirmation too is a
      choice on the safe side: a restore is exactly the moment of (ii), when the Server Owner alone knows whether the
      dump was the right one. Why the tool and not something the app reads: the app is stopped for every restore, and
      the dump it loads carries both tables as they were at the capture — no row inside it can say it was loaded
      again — while the engine's own trace (a table's `CREATE_TIME`) is MariaDB's only, absent on H2, and survives a
      volume copy.
    - **The app's backstop: a clock counts only observed time.** When two consecutive listed pages are further apart
      on the store's clock than `veyq.store-reconcile.max-gap` (default `P1D`), every clock restarts. It catches a
      database brought back by anything else (a hand-loaded dump, a database-only volume copy) once it is older than
      the gap, and plain downtime too, which only delays a deletion. The documented volume-copy fast path copies the
      store's volumes together with the database, so its clocks come back with the very objects they describe.
    The console shows when the clocks last restarted and why (`RESTORE` is the empty state the tool leaves, `GAP`,
    `STORE_CHANGED`).
14. **Visible, not silent.** The read carries the candidates — keys, objects, bytes, the oldest and newest first-seen,
    how many are due, and the first deletion date when deleting is armed — and a verdict the console renders totally:
    `ARMED`, `NOT_CONFIRMED`, `PAUSED`, `NO_STORE`. **Pause** stops the enqueueing and keeps every count; **resume**
    lets the next completed pass enqueue what is due. `veyq-admin store reconcile [--pause | --resume]` mirrors it.
    What a pause does not reach is said where it is clicked: keys already handed to the reclaim list are the reclaim
    sweep's (ADR 0062), within that list's not-before.
15. **Deletion goes through the reclaim list; the reconcile never deletes.** In the transaction that completes a
    pass: drop the rows the pass did not see; select the due rows; re-run the claim query on their keys; record each
    still-unclaimed key with `StoreReclaimStore.record(key, highestIndexSeen + 1)` (so `StoreReclaimSweep`, its
    not-before and its one call site delete) and delete its orphan row. `validate_store_reclaim_arity.py` stays the
    proof that the reconcile deletes nothing. A key **any** claim row names is never a candidate: an object past its
    claimed `chunk_count` (B6) is counted as an orphan, never enqueued, because a reclaim row deletes `0 … n−1` and
    would take the claimed chunks with it. A key with an unclaimed object younger than the safe age is not a
    candidate in that pass (its clock restarts), because an unexplained young object is a writer the age bound has not
    yet ruled out.
16. **The store a pass lists is recorded by digest** — SHA-256 over the resolved store's type, endpoint, bucket,
    region and access key (no secret). A pass, the clocks and the confirmation each hold the digest they were made
    under; a different current digest restarts the pass from the first key, empties the orphan rows
    (`STORE_CHANGED`) and voids the confirmation. This is decision 7's *"the pass records the setting it lists"*.
17. **Two backend processes cannot double-count.** Every page's write is a compare-and-set on the pass's page
    counter in the state row; a tick that lost the race writes nothing and ends. No lock is held across a store call.

## Phases

Baseline before the first edit: `for f in scripts/validate_*.py; do [ "$f" = scripts/validate_guard_gaps.py ] &&
continue; python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done` and `(cd backend && ./gradlew check)` exit code on
`main`, recorded in the retrospective — a red afterwards is this CR's only against that list.

- [x] **0 refine completion** (after O1) — ADR 0062's reconcile bullet amended to what O1 and decisions 2–4 and
  11–17 settle (decided, not yet built: the grace on an exclusive bucket, the enqueue through the reclaim list, the
  `stat` in place of the read-before, the age on the store's clock, the restore's reset), its rejected *"scheduled
  sweep that deletes"* arm kept with the re-read reasons (i)–(iii) as what the three conditions answer;
  uc.admin.configure-object-store gains **A7 — Objects no row claims**. Its coverage criterion
  `orphans-are-reclaimed-only-after-the-grace-on-an-exclusive-bucket` lands with phase 2's refs: the file is built,
  and a built file's criterion without a ref is red (`docs/coverage/README.md` § Rules). · Done when: `python3
  scripts/validate_changes.py` and `validate_coverage.py` green.
- [x] **1 contracts** — the five operations of decision 9 in `contracts/api/instance.openapi.yaml` under the new tag
  with their schema, examples for *never ran*, *in progress*, *completed with candidates, armed*, *failed:
  LIST_DENIED*. · Done when: the contract validators green (`python3 scripts/validate_*.py`); a new tag compiles, so the
  backend is red only where a new operation is seen on day one (`OperatorBlindProbeTest`, the admin-channel posture
  sweep and gate test — `changes/README.md` § Phases only above real size) until phase 2.
- [x] **2 backend** — decisions 1–9 and 11–17: the port operation and its three implementations (+ the test-scope
  `RecordingBlobStore` gains a scripted listing), the migration (`V<stamp>__core_store_reconcile.sql`: both tables, and
  an index on `storage_key` in each claim table, which the per-page `IN (…)` reads), the sweep
  (`core/blobstore/StoreReconcile.kt`), config (`veyq.store-reconcile.*` in `VeyqConfig` + `application.properties`),
  the resource, `NOT_DATA` and the restore's reset in `scripts/instance_backup.py` (decision 13) with its self-test
  case; guard **G1** `scripts/validate_store_reconcile_claims.py`: every table a migration gives a `storage_key` column
  is named by the claim query (derived from the migrations, never listed in the guard). Slice tests on the scripted
  store, one per bite:
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
  - **B13 exclusive bucket** — an orphan past the grace on an unconfirmed bucket → reported, nothing recorded on the
    reclaim list; the same after confirming → recorded (control); confirmed, then the store digest changes → the
    confirmation is void and nothing is recorded. Lever: skip the confirmation check → red.
  - **B14 the grace** — first seen `grace − 1s` before the completing page's store time → not recorded; at `grace` →
    recorded. Lever: measure the grace from the object's `lastModified` instead of first seen → red.
  - **B15 a claim reappears** — a candidate whose key gains a claim row → its orphan row is gone after the next pass and
    nothing is recorded; a claim inserted after the key's page was listed and before the completing page → not
    recorded (the enqueue's re-check). Lever: drop the re-check → red.
  - **B16 a restore restarts the clocks** — `instance_backup_selftest.py`: a restore empties both reconcile tables
    (lever: remove the reset → red); backend: two pages further apart than `max-gap` → every clock restarts and nothing
    past the old first-seen is recorded (lever: skip the gap check → red).
  - **B17 pause** — paused → counts published, nothing recorded; resumed → the next completed pass records it.
    Lever: ignore the pause → red.
  - **B18 a claimed key is never enqueued** — an object past its claimed `chunk_count` is counted and never recorded
    (lever: track every orphan's key → the claimed key reaches the reclaim list, red).
  - **B19 two processes** — a page written by another tick between this tick's read and its write → this tick writes
    nothing (lever: drop the compare-and-set → the page is counted twice, red).
  - **G1 bite** — a scratch migration adding `storage_key` to a new table → G1 red naming it; G1 joins
    `validate_guard_gaps.py`'s cases only if it changes a guard there (`changes/README.md` § trust-chain guards).
  - **Opt-in S3 e2e** (`VEYQ_S3_E2E=1`, beside `BlobBatchE2ETest`): `S3BlobStore.list` against a real Garage — pages
    by `startAfter`, `storeTime` from `Date`, `truncated` — the one un-mocked test that runs before the mapping is
    trusted (`changes/README.md` § Where a mapping depends on a library's output).
  · Done when: `(cd backend && ./gradlew check)` green (own build, never `quarkusDev`); the sweep holds no lock
  across a store call, but its compare-and-set is a row write two processes can race, so B19's class runs in
  `scripts/run_backend_races_on_mariadb.py` too; `validate_store_reclaim_arity.py` still reports exactly one site;
  `instance_backup_selftest.py` green; **live proof** on a throwaway Compose project of this CR's own (never the owner's
  dev stack, never `run_web_e2e.py`): capture, upload two files, roll back in place with the check only, run a pass →
  orphans = those files' chunk count and bytes, and the restore left no clock; unconfirmed → only reported; with a
  short test grace, confirmed → recorded on the reclaim list and reclaimed from the store; a pass on the untouched
  instance → 0.
- [x] **3 admin-web** — `features/instance/StoreReconcilePanel.vue` (its own component, shown by `StorePanel.vue`
  while a store is in use), a total render map over the failure enum and the verdict (as `StoreProvisioningPanel.vue`
  does for its states), the candidates with their deletion date, the exclusive-bucket confirmation (a confirm dialog
  that says what it arms) and withdraw, pause and resume; EN + DE strings; the `store reconcile` CLI command with
  `--confirm-exclusive-bucket`, `--withdraw-exclusive-bucket`, `--pause`, `--resume`; unit tests per state. · Done
  when: `npm run check` in `admin-web/` green and the panel clicked in a browser against the live proof's instance
  (candidates shown, confirmed, paused, then a failure shown after stopping its Garage).
- [x] **4 docs** — ADR 0062 status of the bullet (built), ADR 0056 § Context 2's last sentence about counting;
  `site/src/content/docs/run/operations.md` (the Background jobs table row; the console replaces the SQL, which stays
  as the database-side form); `run/upgrade.mdx`'s rollback paragraph and `run/backup-and-restore.md`'s tolerated
  orphans point at the count and say a restore restarts the clocks and disarms the deletion; the confirm, the grace,
  the pause and the deletion date on `run/operations.md`; `run/storage-and-email.md` says an own store's key needs to list the bucket for the count
  (`LIST_DENIED` otherwise); coverage criterion flipped to built. · Done when: every `scripts/validate_*.py` green and
  `npm run check` in `site/` green.

## Definition of done

The phases' Done-whens; every `scripts/validate_*.py` green, plus `validate_guard_gaps.py --force` because G1 is a new
guard; `docs/coverage/` updated for uc.admin.configure-object-store with `validate_coverage.py` green; the live proof
recorded with its commands and outputs in the retrospective; the docs site's Run pages updated; inbox #134 marked done
with what was built and O1's answer. Then the harvest: what binds moves to ADR 0062 and the code, and this file is deleted.

## Retrospective

**Baseline** (measured before the first code edit, on this worktree at `6e3b8dcc` plus step 0's docs): every
`scripts/validate_*.py` but `validate_guard_gaps.py` green. `./gradlew check` was not run on the untouched tree —
its first run is the CR's (green, 5 m 28 s); a red there would have been unattributable. Afterwards: `./gradlew check`
green, `npm run check` green in `admin-web/` (324 tests) and `site/`, the glob green on the committed tree,
`validate_guard_gaps.py --force` green (80 cases), `instance_backup_selftest.py` green, and
`run_backend_races_on_mariadb.py --tests '*StoreReconcileSliceTest' --tests '*InstanceStoreReconcileSliceTest' --tests
'*EntitySchemaValidationTest'` green on the pinned MariaDB 11.4 (17 + 3 + both schema validations: the migration, the
compare-and-set and the entities on the production engine).

**Bites, each pulled once** (snapshot-and-restore, `--no-watch-fs`, `compileKotlin` re-ran each time; batched only across
disjoint code paths and tests — run 3's two extra reds were blob-claim tests the blob-branch lever also hits): B1/B2/B3
(each claim table dropped from `CLAIM_TABLES`), B4 (probe case removed), B5 (pattern widened to `[^/]+/\d+`), B6
(`chunk_count > index` dropped), B7 (age off `Instant.now()`), B8 (stat skipped), B9 (cursor reset on failure), B10
(claim failure swallowed), B11 (`S3BlobStore` falling back to this process's clock — `S3BlobStoreListTest`), B13
(confirmation dropped from `armed`, both halves), B14 (grace zero), B15 (re-check dropped), B16 (gap rule off; the tool's
reset removed → `instance_backup_selftest.py` red), B17 (pause dropped from `armed`), B18 (a claimed key as candidate —
red only with B15's re-check held off too, the defence in depth), B19 (compare-and-set ignored), G1 (a scratch migration
with `CREATE TABLE … storage_key` and `ALTER TABLE … ADD COLUMN storage_key` → both named; a listed table without the
column → named). admin-web: the deletion date shown while unarmed, and confirming without the dialog — both red.

**Live proof** (throwaway Compose project `veyqrc` — MariaDB + Garage of the dev pins on their own names, ports and
network, the worktree's fast-jar on `:28080/:28090`, clocks shortened by environment: safe age 2 min, grace 3 min,
period 20 s, tick 10 s; torn down with `down -v`): a fresh instance read `NO_STORE`, logged once; the restore rehearsal's
part A (`restore.a-capture.e2e.test.ts`) birthed it and stored one 2-chunk blob → first pass 3 objects (2 chunks + probe),
0 orphans; bucket confirmed via `veyq-admin store reconcile --confirm-exclusive-bucket`; app stopped, `instance_backup.py
capture` (the dump carried the confirmation); app started, two files uploaded through the real client (a temporary,
never-committed suite) → 3-chunk + 1-chunk blobs; past the safe age, armed: 7 objects, 0 orphans, nothing enqueued.
Rollback in place, as `veyqctl rollback` does it: stop, `empty-database`, one start (it wrote a `NO_STORE` reconcile row
— the A5 gate accepted it as `NOT_DATA`), stop, `restore` with the check only → *"emptied the bucket reconcile's state"*,
SQL: 0 reconcile rows, 0 orphan rows, 1 blob row. Started: the first pass counted **4 orphan objects, 921 712 bytes, 2
candidate keys** — exactly the two uploads — verdict `NOT_CONFIRMED`, clocks from that pass. Past the 3-min grace,
unconfirmed: still only reported, no reclaim row. Paused by CLI, confirmed **in the browser** (admin-web dev server
against the instance, the dialog naming the grace and the counts) → `PAUSED`, a pass later still nothing enqueued;
**Resume** clicked → the next pass logged *"2 put on the reclaim list"* (chunk counts 3 and 1, not-before +2 min); the
reclaim sweep deleted them at 19:37:38; Garage then held the capture's 2 chunks and the probe only; next pass 7→3
objects, 0 orphans. Every log line counts only. Then Garage stopped → `STORE_UNREACHABLE` logged once and shown in the
panel beside the last completed pass. The opt-in `S3BlobStoreListE2ETest` ran green against that Garage. **The defaults**
(CR decision 6): 100 000 one-byte veyq-shaped objects, each claimed by a reclaim row, listed in one tick: **2.5 s for the
whole pass** (~25 ms per 1000-key page with its claim query), so 10 pages a tick and a tick a minute keep a tick near a
quarter second and a 100 000-object pass at ten minutes; the defaults stand. Not measured: a pass over 100 000
*unclaimed* objects, which stats each one (a rollback that leaves that many is the case; 10 000 HEADs a tick).

**What went wrong, and the fix:**
- **Planning — the owner's answer reversed the CR, and the brief, not the CR, carried it.** Step 0 rewrote decisions,
  bites and phases in one edit, which is the rule (§ An owner's answer that reverses a decision…). One choice went past
  the owner's words on the safe side and needs their nod: a restore **also withdraws the exclusive-bucket confirmation**
  (the tool empties the state row), where the owner said "confirms once". Reason: a restore is the moment of (ii).
- **Execution — two defects only the live proof found.** The UI said "0 days" for a three-minute test grace (every unit
  test used the 30-day default); and Garage v2.0.0 refuses the AWS SDK's default checksummed `putObject` (the opt-in test's
  setup — veyq itself writes only through presigned PUTs). And a contract property named `keys` compiled to
  `propertyKeys` in the Kotlin model; found at the phase-2 compile, so phase 2's commit carries the contract rename.
  Next time: a contracts phase compiles one use of each new model before it commits.
- **Process — the scratchpad is shared with parallel agents.** A neighbour's `bite.py` overwrote mine mid-session (it
  was no longer needed). Scratch files take a CR-specific name or directory.
- **Next time:** a UI that renders a configured duration is unit-tested with the shortest value its live proof's
  config uses, not only with the default.
