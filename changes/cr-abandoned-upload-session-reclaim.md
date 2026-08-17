---
id: cr-abandoned-upload-session-reclaim
title: Reclaim abandoned upload sessions — the row and the bytes it staged
status: planned
satisfies: [uc.memory.upload]
adrs: ["0013"]
invariants:
  - Operator-blind E2EE — the sweep reads `created_at` and `storage_key` and deletes opaque objects; it opens nothing and needs no key. Untouched, but the phase that deletes bytes states it.
  - Self-hostability — the store is the operator's own; a GC that never runs is a bill they pay forever, so this is a self-hosting obligation rather than a tidiness one.
  - API-first — no wire change: a scheduled sweep has no contract surface, so this CR has no `contracts` phase.
  - Product language (Memory / Chapter) — untouched; no user-facing copy.
---

## Goal

An upload that is cancelled or dies mid-flight leaves a `veyq_upload_session` row **and** the chunks the client
already PUT straight into the object store. Nothing reclaims either. Build the sweep three documents already
describe in the present tense, so an abandoned upload stops being permanent.

## Context & constraints

**This is a *doc-ahead* gap** (`docs/ai/README.md` § 3): the decision exists, nobody built it. Three
independent copies agree on the behaviour and on its shape, so nothing here is being invented —

- [ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md):75 — *"Abandoned uploads are
  reclaimed by GC (stale OPEN sessions + the unreferenced-blob sweep)."*
- [en.memory.media](../docs/features/memory/enablers/en.memory.media.md):98 § E3 — same, as an exception flow.
- [uc.memory.upload](../docs/features/memory/use-cases/uc.memory.upload.md):39 § E2 — *"The abandoned upload
  session is reclaimed by the store-side GC."*

**Measured against `main` (2026-08-17), so no phase re-derives it:**

| Fact | Where |
|---|---|
| An OPEN session is a plain row — **no status column**; `complete` deletes it | `V11__media.sql:10-19`, `MediaStore.kt:152` + `:339` |
| Seven `@Scheduled` sweeps exist; **none** names `UploadSessionEntity` | `grep -rln "@Scheduled" backend/src/main/kotlin` |
| The unreferenced-blob half does not exist either | `grep -rn "unreferenced\|orphanBlob\|blobsWithoutMedia" backend/src/main/kotlin` → empty |
| The template: purge the rows in a tx, then delete the store keys **after** it commits | `MediaTrashPurge.kt:28-32` |
| The object key for a chunk | `BlobStore.chunkKey(storageKey, index)` = `"$storageKey/$index"`, `BlobStore.kt:51` |
| Deleting objects | `BlobStore.delete(keys: List<String>)`, `BlobStore.kt:47`; the `%test` store is a deliberate no-op, `:66` |
| Config shape for a sweep | `veyq.<area>.retention` + `veyq.<area>.purge.every`, ISO-8601 — `application.properties:40`, `:52`, `:58` |
| `uc.memory.upload` coverage carries **7 criteria and no `gaps:`**, none of them about the reclaim | `docs/coverage/features/memory/use-cases/uc.memory.upload.yaml` |

**Scoped to the session half. The unreferenced-blob sweep is a separate CR, and the reason is risk class, not
size.** This sweep deletes objects that **nothing can reach**: the session row is the only pointer to that
`storage_key`, so dropping both reclaims something already unreachable — a wrong predicate costs a stale row.
An unreferenced-*blob* sweep deletes objects the database might still reach through a path the predicate
missed, and blob references are **not** in one place: `MediaEntity.blobId`, `MediaDerivativeEntity.blobId`, and
node **covers** (`NodesStore.kt:140` deletes cover blobs by id, so a cover holds one too). A predicate that
enumerates two of those three destroys user content irreversibly. Same sentence in the docs, opposite blast
radius — they do not belong in one CR.

**`V11__media.sql:9` says *"Reclaimed by GC when abandoned (a later slice)"* and must be left exactly as it
is.** Flyway checksums applied migrations, so correcting that parenthesis fails startup on every instance that
already ran it — and `%dev`'s `repair-at-start=true` hides that in the one place it would be tested
([`backend/CLAUDE.md`](../backend/CLAUDE.md) § Migrations). The comment is a historical artifact; the live
statement of the rule is the sweep's own KDoc.

## The one decision left — the other dissolved when it was measured

**"How long can a session still be in flight?" is not a judgement call: it is bounded by construction at
600 seconds.** All of a session's presigned PUT URLs are signed **once, at `start`**
(`MediaHandler.kt:206` — the only `presignPut` call site in the slice), under
`veyq.blob-store.presign-ttl-seconds=600` (`application.properties:138`,
`BlobStore.kt:88`). There is **no re-presign path**: `media.openapi.yaml` carries `blob-uploads-batch` (start)
and `blob-downloads-batch` (read) and nothing that re-signs a live session. So a client past the TTL holds
only dead URLs and can never PUT another byte for that session, however good its connection.

Two consequences, and the second is the one that saves work:

1. **The window's floor is the presign TTL plus streaming slack** — S3 honours a request whose signature was
   valid when it *started*, so a chunk of a 2 GiB blob (`veyq.blob.max-bytes=2147483648`, `:139`) can still be
   arriving after the URL expires. **Recommended: `PT1H`** — six times the TTL, and still 720× tighter than the
   `P30D` every sibling uses. What is *not* acceptable is copying `P30D`: it stores a month of garbage to
   protect a window that closes in ten minutes.
2. **The heartbeat question is moot — drop it.** There is nothing to heartbeat: the session dies with its URLs
   regardless of connection speed, so a slow uploader is not helped by a longer window or a `touched_at`. What
   they would need is **re-signing**, which does not exist and is [ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)
   § Rejected's *"single most expensive add"* (resumable uploads). Not this CR, and not a schema change either.

**So one thing is open: is `PT1H` the number?** It is an operator-facing config key with a documented default,
so getting it wrong is cheap to correct — but it should be chosen, not inherited.

**And a second, found by asking where phase 1's assertion would come from: the store half is unobservable
today.** This CR's done-when says the sweep deletes *both* the row and the chunk keys — and nothing in the
hermetic tier can see the second. `InMemoryBlobStore.delete` is an empty no-op (`BlobStore.kt:66`), and the
precedent this CR mirrors does not assert it either: `MediaTrashSliceTest`'s own header says *"In-memory store
(metadata only)"* and it checks only that rows are hard-deleted, leaving `MediaTrashPurge.kt:31`'s
`blobStore.delete(reclaimKeys)` proven by nothing. So the invariant this CR calls load-bearing — *a row-only
delete is strictly worse than no GC* — is exactly the half no existing test covers. Two ways, and they are not
equivalent:

- **A recording `BlobStore` test double** that captures the keys it was handed. Costs a small test-scope bean;
  buys the assertion this CR needs **and** retro-fits one onto `MediaTrashPurge`, so the seam is shared rather
  than scaffolding for one job. **Recommended.**
- **Assert at the store layer** — call the purge's store method directly and check the reclaim keys it returns.
  Cheaper, and strictly weaker: it proves the keys were *computed*, never that they reached the store, which is
  the failure the invariant is about.

## Phases

- [ ] **1 backend** — `UploadSessionPurge`, mirroring `MediaTrashPurge`'s shape exactly: a `@Scheduled` job that
      asks the store layer for sessions older than the window, deletes their rows in one transaction, and — **after
      that transaction commits** — deletes `chunkKey(storage_key, 0 … chunk_count-1)` from the object store,
      best-effort. Order is load-bearing in that direction only: an object delete that fails leaves a reclaimable
      object and no pointer, whereas deleting objects first would leave a row pointing at nothing if the tx rolled
      back. **The key enumeration already exists — reuse it, do not re-derive it:** `MediaHandler.kt:286` is
      exactly `blobStore.delete(sessions.flatMap { s -> (0 until s.chunkCount).map { chunkKey(s.storageKey, it) } })`,
      written for the budget-exceeded purge (E2). The store delete must tolerate keys that were never uploaded —
      an abandoned session has, by definition, an unknown number of its chunks actually present, and that path
      already relies on the same tolerance. Config keys follow the sibling shape.
      · Done when: `./gradlew check` green, including a slice test that a session past the window loses **both**
      its row and its chunk keys, and one that a session inside the window loses neither.
- [ ] **2 coverage** — `docs/coverage/features/memory/use-cases/uc.memory.upload.yaml` gains the acceptance
      criterion this behaviour has never had (E2's *"reclaimed by the store-side GC"* is asserted by no test
      today), with a backend ref. Note in the file that the **web** half of E2 — cancel stops before `complete` —
      is a different claim already covered by `progress-and-cancel`.
      · Done when: `python3 scripts/validate_coverage.py` green.

## Definition of done

`./gradlew check` green; `docs/coverage/` updated for `uc.memory.upload`; the whole guard glob green over the
**committed** tree (`for f in scripts/validate_*.py; do python3 "$f"; done`).

**No new guard, and the reason is stated rather than assumed.** The invariant worth protecting is *"a sweep that
deletes a row carrying a `storage_key` also deletes the objects under it"* — a row-only delete is strictly worse
than no GC, because it orphans the bytes permanently. It is not promoted to a `scripts/` validator because the
proof is behavioural, not structural: phase 1's slice test asserts both halves through the recording double
decided above, which is a stronger check than any static sweep over `@Scheduled` classes could be, and there are
only three such purges to keep honest. If a fourth arrives, revisit — three is where the rule of three starts.
**Note what that leaves open:** until the double exists, `MediaTrashPurge`'s object delete is unasserted too, so
"three purges kept honest" is an aspiration on the day this CR starts and a fact on the day it lands.

## Retrospective

<!-- Filled at implement time: what went wrong in planning, in execution, AND in process/collaboration —
     both directions — with the root cause and one concrete "next time". -->
