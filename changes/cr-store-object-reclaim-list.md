---
id: cr-store-object-reclaim-list
title: Store-object reclaim becomes a durable work list with a single deleter
status: planned
satisfies: [uc.memory.delete-media, uc.memory.delete-node, en.memory.media]
adrs: ["0062", "0013", "0056", "0061"]
invariants:
  - Operator-blind E2EE — a reclaim row must describe nothing about the scope, node or media it came from; after a group erasure it is the only surviving trace and must not be a size histogram of the erased group (ADR 0062 Decision 5).
  - Self-hostable by default — the sweep must work against any S3-compatible store and must not gate on `canPresign`, or the `%test` store makes its own half unobservable (the reason `UploadSessionPurge.kt:43-45` gives for not gating).
  - API-first — the one contract description that asserts the current behaviour is corrected in phase 1, before the backend contradicts it.
---

## Goal

Nine call sites hand object keys to `BlobStore.delete`, which swallows every per-key failure in a bare
`runCatching` and returns `Unit`. The row that held the `storage_key` is already gone, so a failed delete leaves
bytes nothing can find and nothing counts. This CR makes the keys survive the transaction that deletes the row:
they go onto a durable reclaim list, and exactly one component — `StoreReclaimSweep` — drains it. What remains
after a permanently unreachable store is then a row you can count, which is the whole point.

## Context & constraints

- [ADR 0062](../docs/decisions/0062-store-object-reclaim-is-a-durable-work-list.md) owns every decision here,
  including the four rejected alternatives and the naming (not *job*, not *purge*, not *outbox*).
- [ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md) §3 owns declare-then-verify;
  [ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md) § Context 2 and
  [ADR 0061](../docs/decisions/0061-node-deletion-reversible-node-trash.md) Decision 7 each assert the
  behaviour this CR replaces — both are corrected in phase 4, not rewritten in passing.
- [en.memory.media](../docs/features/memory/enablers/en.memory.media.md) owns the media enabler's exception
  flows; it has no clause for *the store refuses the delete* and gains one.
- **The one seam that decides testability:** `InMemoryBlobStore.delete` is a no-op and says so in its own KDoc —
  a purge that dropped its store half would pass the whole hermetic suite. Assertions go through the test-scope
  `RecordingBlobStore` (`@Alternative @Priority(1)`), whose `failOnKeyMarker` already injects a refusing store
  and is already used this way at `ScopesSliceTest` (the poisoned-scope isolation case). This CR does not need
  a new seam.
- **Not in scope, deliberately:** a bucket-versus-database reconcile. ADR 0062 § Consequences states why — the
  stock it would measure is zero, and it only acquires a meaning *after* this CR. It stays `docs/inbox.md` #130.

## Decisions this CR implements (each with the pointer that settles it)

1. **The insert is the store method's, not the caller's.** The guarantee is "same transaction as the row
   delete", and the transaction belongs to the store method (`MediaStore.kt:279/290/306/353/375`,
   `SharingStore.kt:208` each carry `@Transactional`; neither handler nor any `*Resource*` does — verified by
   `grep -rln 'Transactional' backend/src/main/kotlin --include='*Resource*.kt'` returning nothing). So the
   methods that today *return* `reclaimKeys` for the caller to delete instead *record* them and return nothing.
   `MediaStore.purgeMediaRows`'s KDoc (`MediaStore.kt:258-263`) hands that ownership to the caller today and is
   the single sentence that changes shape.
2. **Two paths own their own transaction and insert directly.** `ScopePurge.eraseScope` (per-scope
   `QuarkusTransaction`, `ScopePurge.kt:55-62`) and `MediaHandler`'s over-budget purge (`MediaHandler.kt:288`,
   which deliberately leaves its session rows standing — so it is the one site that cannot lose its keys today
   and must keep that property).
3. **`ScopePurge` stops deleting objects first.** ADR 0062 Decision 3. The *objects-first* rationale in its own
   docstring (`ScopePurge.kt:70-76`) goes with it.
4. **The sweep does not gate on `canPresign`.** `UploadSessionPurge.kt:43-45` states the reason in its own
   source; the gate at `MediaHandler.kt:289` belongs to a path that will no longer delete.

## Inventory — locators, run at refine against `main` @ `3dcd28e`

Spot-check one row before the first edit; the argument leans hardest on the *call sites* row.

| What | Locator (re-run it) |
|---|---|
| Every site handing keys to the store | `grep -rn --include='*.kt' 'blobStore.delete' backend/src/main/kotlin` — **read as a list**: one hit is a KDoc mention (`MediaStore.kt:262`), not a call |
| **Second angle, keyed on the keys rather than the call** | `grep -rn --include='*.kt' 'reclaimKeys\|purgeMediaRows\|chunkKey(' backend/src/main/kotlin` — this finds the paths that *compute* keys, which is the set that must write reclaim rows. **The disagreement between the two lists is the finding**, and it is the whole risk of this CR: a path that computes keys and does not hand them over today is a path that will silently write none |
| Prose that asserts the behaviour being replaced | `grep -rn --exclude-dir=node_modules -i 'best-effort' docs/ changes/ infra/ scripts/ wiki/ contracts/ backend/src/main \| grep -i 'store\|object\|blob\|delet\|reclaim\|purge'` |
| Guards anchored on anything reshaped here | `grep -rln 'blobStore\|veyq_blob\|storage_key\|BlobStore' scripts/` — run **before** reshaping, not after |
| Trust-chain baseline | `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 \|\| echo "RED $f"; done` — run before the first edit; this CR adds one guard, so a count taken afterwards would be confirmed by the work it grades |

**The prose sweep was run here and named these doc-side sites — treat it as a floor, not the set.** ADR 0056
§ Context 2, ADR 0061 Decision 7, `docs/features/memory/use-cases/uc.memory.delete-node.md:101`, the coverage
criterion **text** at `docs/coverage/features/memory/use-cases/uc.memory.delete-node.yaml:79`,
`contracts/api/media.openapi.yaml:473`, `infra/README.md:202`, `scripts/instance_backup.py:44`, and
`BlobStore.kt:46`'s own port KDoc — plus roughly a dozen KDoc/comment sites under `backend/src/main`. **Re-run
the sweep at phase 4**, because phases 2 and 3 author new prose (a migration comment, a class docstring, a guard
docstring) into the same corpus.

Two sites the sweep found that are **stale for an unrelated reason and are already corrected** (this session,
before the CR): three places claimed the upload-session GC "was never built", which `UploadSessionEntity`'s KDoc
(`Entities.kt:14-20`) has contradicted since it shipped. Do not re-fix them.

## Phases

- [ ] **1 contracts** — correct the `deleteCover` description at `contracts/api/media.openapi.yaml:473`, whose
      last clause states the orphan outcome as a property of the API, and sweep the spec for siblings.
      · Done when: `npm run generate:api` shows a **description-only** diff (no model or operation change) and
      `./gradlew check` + `npm run check` are green.
- [ ] **2 backend** — `V48__store_reclaim.sql` (`storage_key`, `chunk_count`, `created_at`, `attempts`,
      `next_attempt_at`, `abandoned_at`; no scope, no media, no node — invariant 1), `StoreReclaimEntity` +
      store + `StoreReclaimSweep` in `shared/blobstore/`, the config block under `veyq.store-reclaim.*`
      (`@ConfigMapping` on `VeyqConfig`, defaults mirrored into `application.properties` — the `Interval`
      convention already carries `sweep.every`), and every site from the inventory rewired per Decisions 1–4.
      · Done when: `./gradlew check` green **and** the fault-injection test below passes.
- [ ] **3 guard** — `scripts/validate_store_reclaim_arity.py`: exactly one call site of `blobStore.delete`
      outside `shared/blobstore/` itself, and it is in `StoreReclaimSweep`. This is ADR 0062 Decision 2's
      completeness proof, and it is the decision every future reclaim path will be tempted to bypass.
      · Done when: green on the untouched phase-2 tree **and** bite-proven red (below).
- [ ] **4 docs + coverage** — ADR 0056 § Context 2, ADR 0061 Decision 7, `uc.memory.delete-node.md`,
      `en.memory.media.md` (a new **E6 — the store refuses the delete**), `infra/README.md` § *What `verify`
      tells you* (the tolerance survives, on ADR 0062's new reason: reclaim *pending*, not deletion
      *unreliable*), `scripts/instance_backup.py`'s docstring, `BlobStore.kt`'s port KDoc, and the coverage
      YAMLs. **Say per site whether the sentence is edited, narrowed or deleted.**
      · Done when: `python3 scripts/validate_coverage.py` green and the phase-4 re-run of the prose sweep
      returns no surviving assertion of the replaced behaviour.

## Bite-tests — how each is made red, and with which lever

- **The guard (phase 3), by injection.** Copy the tree to a scratch dir, add one `blobStore.delete(...)` line to
  a purge class there, run the guard: it must go **red and name that file**. Restore by deleting the scratch
  copy — **never `git checkout`**. Run the predicate against the *untouched* phase-2 tree first: a guard that is
  red on arrival makes every later bite unreadable. Its corpus is `backend/src/main/kotlin`, so the guard's own
  source is not in what it scans and must not be excluded from anything.
- **The behaviour (phase 2), by injection through the existing seam.** Arm `RecordingBlobStore.failOnKeyMarker`
  on one key, run the sweep, and assert the reclaim row **survives with `attempts` incremented and a later
  `next_attempt_at`**; disarm, run again, assert the row is gone and the key was handed over.
  **Positive control on the same predicate, in the same test:** an unarmed key in the same tick is reclaimed and
  its row deleted — otherwise "the sweep held the failure" is indistinguishable from "the sweep did not run".
  Restore the marker in a `finally`; it is a shared singleton across the test JVM.
- **The give-up state (phase 2).** Drive `attempts` past `max-attempts` and assert `abandoned_at` is set and
  logged once — the row that makes the leak countable. Negative control: a row below the threshold is not
  abandoned.

## Definition of done

`./gradlew check` green; every guard green — `for f in scripts/validate_*.py; do python3 "$f"; done` — over the
**committed** tree (the glob sees files, not commits: an untracked guard is unchecked in CI);
`python3 scripts/validate_coverage.py` green with `uc.memory.delete-media` carrying the new criterion and
`uc.memory.delete-node`'s corrected one; and the phase-3 guard bite-proven red-then-green. The structural
invariant is not left as a one-shot grep — it ships as that guard.

## Retrospective

_(filled at the end of the implement session — planning, execution, and the process/collaboration side, both
directions, ending with one concrete "next time".)_
