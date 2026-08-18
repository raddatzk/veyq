---
id: cr-store-object-reclaim-list
title: Store-object reclaim becomes a durable work list with a single deleter
status: planned
satisfies: [uc.memory.delete-media, uc.memory.delete-node, en.memory.media]
adrs: ["0062", "0013", "0056", "0061"]
invariants:
  - Operator-blind E2EE — a reclaim row must describe nothing about the scope, node or media it came from; after a group erasure it is the only surviving trace and must not be a size histogram of the erased group (ADR 0062 Decision 6).
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
3. **`ScopePurge` stops deleting objects first.** ADR 0062 Decision 4. The *objects-first* rationale in its own
   docstring (`ScopePurge.kt:70-76`) goes with it.
4. **The port returns the keys it could not delete** ([ADR 0062](../docs/decisions/0062-store-object-reclaim-is-a-durable-work-list.md)
   Decision 2), and `S3BlobStore` stops swallowing (`BlobStore.kt:121-125`). Three implementations move:
   `S3BlobStore`, `InMemoryBlobStore` (empty — it deletes nothing and fails at nothing), and the test-scope
   `RecordingBlobStore`. **Without this the CR is inert and still green:** `RecordingBlobStore.delete` throws
   on a marked key (`RecordingBlobStore.kt:66-69`) while `S3BlobStore.delete` cannot fail at all, so a sweep
   built on the current signature would pass every bite-test below and clear reclaim rows in production
   regardless of what the store did.
5. **The sweep does not gate on `canPresign`.** `UploadSessionPurge.kt:43-45` states the reason in its own
   source; the gate at `MediaHandler.kt:289` belongs to a path that will no longer delete.

## Inventory — locators, run at refine against `main` @ `3dcd28e`

Spot-check one row before the first edit; the argument leans hardest on the two-angle comparison below.

**Angle 1 — where keys are handed to the store.** Read as a list: one hit is a KDoc mention
(`MediaStore.kt:262`), not a call.

```bash
grep -rn --include='*.kt' 'blobStore.delete' backend/src/main/kotlin
```

**Angle 2 — where keys are computed and carried across a transaction boundary.**

```bash
grep -rn --include='*.kt' 'reclaimKeys\|purgeMediaRows\|chunkKey(' backend/src/main/kotlin
```

**The comparison was run, and the disagreement is the finding — it names the sites.** Angle 1 returns nine
calls, all in handlers and scheduled jobs. Angle 2 returns those *plus* the places the work actually
is, and Decision 1 puts the insert in every one of them: `MediaStore.purgeMediaRows` and its four callers
(`MediaStore.kt:265/281/292/308`), the two cover paths (`:357/:377`), `deleteBlobRow` (`:387`),
`SharingStore.deleteContributedMedia` (`:213-219`), and `NodeSubtreePurge.eraseExpired` (`:63-84`) — none of
which Angle 1 can see. It also surfaces a **data class that carries reclaim keys across the boundary**,
`CoverCommitRows(val ref, val reclaimKeys)` at `MediaStore.kt:61`, which Decision 1 dissolves: a shape change,
not a call rewire. **An implementer working from Angle 1 alone would rewire nine call sites and leave the
transaction-side untouched, which is where this CR's guarantee lives.**

Angle 2's false positives are the read paths — `chunkKey` used for presign or `stat`
(`MediaHandler.kt:206/277/406`, `SharingHandler.kt:362`, and the helper itself at `BlobStore.kt:51`). Leave
them alone.

**Prose that asserts the behaviour being replaced.**

```bash
grep -rn --exclude-dir=node_modules -i 'best-effort' docs/ changes/ infra/ scripts/ wiki/ contracts/ backend/src/main | grep -i 'store\|object\|blob\|delet\|reclaim\|purge'
```

Run here; it named these doc-side sites — **a floor, not the set**: ADR 0056 § Context 2, ADR 0061 Decision 7,
`docs/features/memory/use-cases/uc.memory.delete-node.md:101`, the coverage criterion **text** at
`docs/coverage/features/memory/use-cases/uc.memory.delete-node.yaml:79`, `contracts/api/media.openapi.yaml:473`,
`infra/README.md:202`, `scripts/instance_backup.py:44`, and `BlobStore.kt:46`'s own port KDoc — plus roughly a
dozen KDoc/comment sites under `backend/src/main`. **Re-run it at phase 4**: phases 2 and 3 author new prose
(a migration comment, a class docstring, a guard docstring) into the same corpus.

Two sites the sweep found are stale for an unrelated reason and are **already corrected** (this session, before
the CR): three places claimed the upload-session GC "was never built", which `UploadSessionEntity`'s KDoc
(`Entities.kt:14-20`) has contradicted since it shipped. Do not re-fix them.

**Guards anchored on anything reshaped here.**

```bash
grep -rln 'blobStore\|veyq_blob\|storage_key\|BlobStore' scripts/
```

Run here: `validate_blob_reference_arity.py` is the only guard at risk, and it stays green — its live-twin key
reads `SharingStore.subtreeBlobIds`, which this CR does not touch (Decision 1 changes
`SharingStore.deleteContributedMedia`, a different function in the same file). **Confirm that before reshaping,
not after.** Its own documented limit — *"a new reclaim predicate elsewhere gets no protection from this guard
at all"* — is exactly what phase 3's guard covers from the other side.

**Trust-chain baseline.** Run before the first edit; this CR adds one guard, so a count taken afterwards would
be confirmed by the work it grades.

```bash
for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done
```

## Phases

- [ ] **1 contracts** — correct the `deleteCover` description at `contracts/api/media.openapi.yaml:473`, whose
      last clause states the orphan outcome as a property of the API, and sweep the spec for siblings.
      · Done when: `npm run generate:api` shows a **description-only** diff (no model or operation change) and
      `./gradlew check` + `npm run check` are green.
- [ ] **2 backend** — `V48__store_reclaim.sql` (`storage_key`, `chunk_count`, `created_at`, `attempts`,
      `next_attempt_at`, `abandoned_at`; no scope, no media, no node — invariant 1), `StoreReclaimEntity` +
      store + `StoreReclaimSweep` in `shared/blobstore/`, the config block under `veyq.store-reclaim.*`
      (`@ConfigMapping` on `VeyqConfig`, defaults mirrored into `application.properties` — the `Interval`
      convention already carries `sweep.every`), and every site from the inventory rewired per Decisions 1–5.
      · Done when: `./gradlew check` green **and** the fault-injection test below passes.
- [ ] **3 guard** — `scripts/validate_store_reclaim_arity.py`: exactly one call site of `blobStore.delete`
      outside `shared/blobstore/` itself, and it is in `StoreReclaimSweep`. This is ADR 0062 Decision 3's
      completeness proof, and it is the decision every future reclaim path will be tempted to bypass.
      Register its bite as a **case in `scripts/validate_guard_gaps.py`**, so the injection below becomes a
      permanent assertion rather than a one-shot run — that guard's `CASES` list is the only place its tally
      lives, and it does not require an entry per guard, which is precisely why one has to be chosen.
      · Done when: green on the untouched phase-2 tree, bite-proven red (below), and `validate_guard_gaps.py`
      green with the new case.
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

**Planning — the CR was strong on the thing that would have gone wrong silently, and blind to the test tree.**

- **It cited a test its own decision disarms.** § Context named `ScopesSliceTest`'s poisoned-scope case as proof
  the refusing-store seam already exists and concluded *"This CR does not need a new seam."* Decision 3/4 removes
  `blobStore.delete` from `ScopePurge.eraseScope` — the only thing that case can poison, as its own docstring
  states outright. The cited proof and the decision voiding it sat four paragraphs apart. It cost a red on a
  correct tree and the `RefusingStoreReclaimStore` the CR had declared unnecessary. **Harvested** as a new rule
  in [`changes/README.md`](README.md) § *a test the CR CITES is a test the CR can disarm* — the general form,
  because the lever had already moved once before (FK gap → store delete → reclaim insert) while the property
  never moved at all.
- **The inventory never greps the test tree, against this repo's own standing rule.** § the locator already says
  *"the inventory greps the **test** trees too"*. Both angles here read `backend/src/main/kotlin` only. The real
  blast radius was four tests reddened by the timing change, one compile break on a changed return type
  (`MediaTrashSliceTest:190`), one control quietly turned vacuous, and one dead lever — every one found at
  implement time, by a subagent, in five minutes. The same five minutes were available at refine.
- **`max-attempts` is a decision, not a keystroke, and the CR handed it over as one.** The config block is
  named (`veyq.store-reclaim.*`) but every value is left open. Three are genuinely keystrokes; the attempt
  ceiling is not — it sets how long a dead store takes to become countable, which is the ADR's whole point.
  Chosen here as 5 attempts / `PT15M` doubling ≈ 8h, and written into `VeyqConfig` with that reasoning attached.

**Execution — one guard shipped green over the defect it exists to catch, and only the second bite found it.**

- **The predicate matched an unqualified `: BlobStore` only.** A bite forged as a fully-qualified declaration
  under a renamed receiver ran **green** over a tree carrying a live bypass. This is § *run a new guard's
  predicate against the untouched tree* meeting its opposite: the guard was green on arrival *and* green on the
  break, and only the second, deliberately-awkward injection told them apart. The first bite (the obvious one)
  would have shipped it. Fixed, and pinned as case 13 of `validate_guard_gaps.py` so the shape is a permanent
  assertion rather than a run I once did.
- **I asserted a global tally inside a slice test.** `store.purgeTrashedBefore(...) == 1` over a JVM-shared H2
  where every other class leaves trashed rows. `RecordingBlobStore`'s own KDoc states the rule — *assert on
  containment of the keys you seeded, never on size* — three lines from the code I was editing.
- **One unreproduced `StaleStateException` in `settle`, hardened rather than explained.** Three re-runs were
  clean and I did not find the cause. The fix (a bulk delete by id instead of `deleteById`) is right on its own
  merits — two instances of a self-hosted deployment run this sweep against one database — but it is a
  hardening, not a diagnosis, and it is recorded as one in that method's KDoc.

**Process — the brief did the CR's hardest job for it, which is the finding.**

- The implement brief's *"die gefährlichste Stelle, und sie sieht grün aus"* named the inert-and-green failure
  of Decision 2 precisely, and the pre-first-edit baseline instruction meant every later red was attributable.
  Both were right, and both were the **brief** compensating for the CR: the CR states Decision 4's danger too,
  but nothing in it makes an implementer measure before touching anything.
- **Next time:** at refine, run the inventory's locators against `backend/src/test` as an explicit third angle
  whenever a CR changes a signature, a return type, or the *timing* of an observable — and list per hit which
  of the three the test depends on (the old return, the old timing, or an injection at the old failure point).
  Those three are different repairs, and only the third can silently become a tautology instead of a red.
