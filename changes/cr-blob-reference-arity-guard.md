---
id: cr-blob-reference-arity-guard
title: Pin the blob-reference arity as a guard, and correct the GC sentence it was written for
status: planned
satisfies: ["en.memory.media"]
adrs: ["0013"]
invariants:
  - "No reclaim predicate over veyq_blob may enumerate fewer reference arms than exist — the arm it misses is user content it destroys, and only two of three arms have a foreign key to refuse."
---

## Goal

Make the number of places that reference a `veyq_blob` row a **checked** fact instead of a remembered one, and
correct the three documents that promise an unreferenced-blob sweep for work it cannot do.

This CR was refined as the unreferenced-blob sweep itself (`docs/inbox.md` #26 item (3), the second half of
ADR 0013 §3's GC sentence). Refinement measured the set that sweep would act on and found it **empty by
construction**: every blob row is created inside the same transaction as its reference, and no delete path
removes a reference while leaving the blob. The sweep would ship a job whose Definition of done is *"finds
nothing"*, carrying its entire blast radius on the one arm the database does not protect. What is worth
building today is the structural guard that any such sweep would need as a precondition — and which already
protects a **live** twin of the same predicate, `SharingStore.subtreeBlobIds`. The sweep stays an inbox row,
pointed at this guard.

## Context & constraints

- [ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md) §3 — the GC sentence being
  corrected.
- [`en.memory.media`](../docs/features/memory/enablers/en.memory.media.md) E3 — the same claim, one altitude
  down.
- [`contracts/api/media.openapi.yaml`](../contracts/api/media.openapi.yaml) `deleteCoverBlob` — the third
  copy, in a different context and wrong for a different reason.
- `changes/README.md` § *promote one-shot proofs to permanent guards*, § *run a new guard's predicate against
  the UNTOUCHED tree*, § *what a guard's module docstring must carry*.
- **Not touched by this CR:** the two leaks refinement surfaced (below). Each is its own work order.

### The measurements this CR rests on

Measured 2026-08-17 against `main` @ `3a2827f`, clean tree. **Do not rebuild this inventory — but spot-check
the row the argument leans on hardest**, which is *the delete-site table*: re-run its locator and confirm no
sixth site appeared.

**The three arms, from four independent keys** — the inbox row's two, plus two this refinement added because
both of its keys were on the *name* `blob_id` and would miss an arm under another column name:

| Key | Command | What it answers |
|---|---|---|
| entity | `grep -rnoE '@Column\([^)]*"[a-z_]*blob_id"' --include='*.kt' backend/src/main/kotlin` | the Kotlin declaration sites |
| schema | `grep -rniE '^\s+[a-z_]*blob_id\s+VARCHAR\|ADD COLUMN [a-z_]*blob_id' backend/src/main/resources/db/migration/*.sql` | the SQL declaration sites |
| backstop | `grep -rniE 'references +veyq_blob\b' backend/src/main/resources/db/migration/*.sql` | which arms a foreign key refuses to orphan |
| live twin | `sed -n '/fun subtreeBlobIds/,/^    }/p' backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt` | which arms the one predicate already running in production enumerates |

**Run 2026-08-17 against `main` @ `021bc66`, clean tree** — the hardening pass ran all four keys rather than
inheriting them, because a guard that is red on arrival makes every later bite unreadable (`changes/README.md`
§ *run a new guard's predicate against the UNTOUCHED tree*):

```
entity   -> 3   content/Entities.kt:55 (cover_blob_id), media/Entities.kt:104, media/Entities.kt:168
schema   -> 3   V13__media_derivatives.sql:13, V11__media.sql:45, V19__node_cover.sql:9
backstop -> 2   V13 fk_derivative_blob, V11 fk_media_blob
live twin-> 3/3 ids.add(m.blobId) | MediaDerivativeEntity | coverBlobId, all present in the function body
```

The **gap between the schema key and the backstop key is the finding, and it names the arm**:
`veyq_node.cover_blob_id` (`V19__node_cover.sql`, a plain nullable column) has no foreign key, where
`fk_media_blob` (V11) and `fk_derivative_blob` (V13) do. The database refuses to orphan two of three arms and
silently permits the third — which is exactly the arm a wrong predicate destroys irreversibly.

**Why no sweep — the delete-site table.** Blob rows are created only by `MediaStore.persistBlob`, reached only
from `attach` and `commitCover`, both `@Transactional` and both writing the reference in the same transaction.
Locator for the delete sites (this is the row to spot-check):

```
grep -rn --include='*.kt' -E '(MediaEntity|MediaDerivativeEntity|BlobEntity|NodeEntity|ScopeEntity)\.delete' backend/src/main/kotlin
grep -rn --include='*.kt' -iE 'delete from|executeUpdate|createNativeQuery' backend/src/main/kotlin   # expect: no hits — the entity API is the whole set
```

| Site | Deletes | Leaves behind |
|---|---|---|
| `ScopePurge.eraseScope` | objects, derivatives, media, blobs, sessions, nodes, scope | — |
| `NodesStore.deleteSubtree` | cover blobs + node rows | **the nodes' media rows** |
| `MediaStore.purgeMediaRows` | derivative → media → blob | — |
| `MediaStore.deleteBlobRow` | the blob, when the cover reference is cleared | — |

No path removes a reference and leaves the blob. **The unreferenced-blob set is empty on today's code.**

**Spot-check run 2026-08-17 @ `021bc66`:** the table survives — 10 call sites grouping into exactly the four
functions above, **no sixth site**, and the raw-SQL locator returns 0. One angle was added, because a
delete-keyed locator structurally cannot see the way the *FK-less* arm would become unreferenced — an UPDATE
that nulls it. `grep -rn --include='*.kt' 'coverBlobId' backend/src/main/kotlin` finds both writers
(`MediaStore.commitCover:352-354`, `MediaStore.clearCover:372-373`); each calls `deleteBlobRow` on the old
value in the same `@Transactional` before reassigning, so the update path orphans nothing either.

### Settled decisions, each with the line that settles it

- **No grace period is needed against the writer.** `MediaStore.kt:112` (`attach`) and `MediaStore.kt:351`
  (`commitCover`) are both `@Transactional` and persist blob + reference together, so no commit boundary ever
  exposes an unreferenced row. *(The residual window is on the reader's side — a sweep that reads the
  reference sets before `veyq_blob` can see a row whose reference committed in between. That is a read-ordering
  constraint for the future sweep, recorded on the inbox row, not a retention window.)*
- **The two purges do not disagree on `canPresign`.** `UploadSessionPurge.kt:43` and `MediaTrashPurge.kt:35`
  both delete unconditionally; only `MediaHandler.kt:289` (the budget purge) gates on it. Where a future CR
  names two exemplars, **`UploadSessionPurge` wins** — it carries the reason in its own source. `MediaHandler`
  is the exception, not a second template. *(This corrects the framing in `changes/README.md` § a seventh tell,
  which names the trash purge and the budget purge as the disagreeing pair; the pair is right, but the trash
  purge is not one of the two scheduled purges' halves that conflict.)*
- **The naive locators are not fit to be the guard.** Bitten 2026-08-17 on a throwaway copy: a fourth arm
  injected as `@Column(length = 36, name="banner_blob_id")` plus a lowercase `references veyq_blob (id)` left
  the inbox row's two locators reporting **3 and 2, unchanged**. The hardened keys above went to 4 / 4 / 3.
  **Re-run 2026-08-17 @ `021bc66`** on a fresh copy — reproduced exactly (naive `3` / `2`; hardened `4` / `4` /
  `3`), so the lever is wired to all three keys and the CR is not carrying a remembered bite.
  The tree carries a live lowercase `references veyq_membership`, so the case-insensitive spelling is not
  hypothetical.
- **No web phase.** `web/src/api/generated/` is gitignored (`git check-ignore -v` → `web/.gitignore:4`) and
  rebuilt by `npm run prepare`, so the contract description edit produces no tracked web diff.

## Phases

- [x] **1 contracts** — `media.openapi.yaml` `deleteCoverBlob`: the final clause *"the deferred
      unreferenced-blob sweep (ADR 0013 §3) is the crash-safety backstop"* is **replaced, not deleted**. The
      residual risk it names is real but misattributed: every cover path is transactional, so a crash leaves no
      unreferenced *row*; what it can leave is an orphaned store *object* (the post-commit `blobStore.delete`,
      whose `runCatching {}` at `BlobStore.kt:121` swallows failures), which no predicate over `veyq_blob` can
      see. State that, and name no job that does not exist. ·
      **Done when:** `python3 scripts/validate_contracts_common.py` green and
      `grep -rn 'unreferenced' contracts/` returns nothing.

- [x] **2 scripts** — add `scripts/validate_blob_reference_arity.py`, asserting **all four** hardened keys above
      with the **named site set**, not only the counts (a list can look wrong; a number cannot). The FK key is
      asserted at 2 deliberately: an arm gaining a foreign key is a real state change that should turn this red
      and be recorded, not land silently. The **live-twin key is what carries the front-matter invariant**: the
      three declaration keys pin how many arms *exist*, and only this one pins that the one predicate already
      running over them *enumerates* all three — asserted as the three accessor spellings inside
      `subtreeBlobIds`' own function body, so the guard stays a buildless grep. (Python 3.9 on the dev host:
      a new guard using `X | None` needs `from __future__ import annotations`, as `validate_guard_gaps.py`
      already does.) Docstring carries the three required fields, and its **limit** field states what is left
      open — (a) it counts *declaration sites*, so a column not named `*blob_id` escapes the first two keys,
      as does a `@Column(` whose name literal sits on a later line (none exist today: `grep -rn '@Column($'`
      → 0), and (b) the live-twin key reads **text in one named function**, so it sees neither a *second*
      consumer written later nor an arm that is named but unreachable. ·
      **Done when:** `python3 scripts/validate_blob_reference_arity.py` green on the untouched tree **before**
      any injection, the arity injection from § settled decisions turns it red naming the fourth site, and
      deleting the `coverBlobId` arm from `subtreeBlobIds` turns it red naming that function — two bites,
      because the guard now makes two claims that fail independently.

- [x] **3 scripts** — promote that injection into `scripts/validate_guard_gaps.py` as a rejected-case pin, so
      the guard's bite is re-run on every trust-chain run rather than remembered from this CR. The harness
      already has the exact shape (copy tree → forge → run → discard); the injection is the one bitten above.
      **Lever checked against the mechanism** (`changes/README.md` § *first check the lever is wired*): a forgery
      is a function over the copied root and may write any number of files — case `10`
      (`forge_scoped_policy_gains_unlisted_dependency`) already writes two — so the `.kt` + `.sql` arity injection
      fits with no harness change, as `expect_pass=False`. Its `check_case` runs the target guard on the
      *un-forged* copy first, so both ways of removing the arity check turn this red: a neutered check fails the
      `expect_pass` comparison, a deleted file fails the un-forged pre-run. Its `_IGNORE` list excludes neither
      `backend/src/main` nor `db/migration`, so the copy carries everything the new predicate reads. ·
      **Done when:** `python3 scripts/validate_guard_gaps.py` green, and removing the new guard's arity check
      turns it red.

- [ ] **4 docs** — supersede the claim at every site the fragment sweep found. The sweep is
      `git grep -niI 'unreferenced'` over the **whole tracked tree** — not an enumerated directory list, which
      drifts, and which here also drags in 14 `web/node_modules` hits that bury the six real ones. **Do not
      "sharpen" it to `unreferenced-blob`:** `en.memory.media.md` wraps the compound across a newline, so the
      more distinctive fragment silently drops one of the two sites this phase exists to fix (6 hits / 4 files
      instead of 9 / 7). Run 2026-08-17 @ `021bc66`, CR excluded — 9 hits in 7 files, of which **two are
      homonyms** and stay untouched (`docs/decisions/0038-…md:56` is about an unreferenced generated clone;
      `web/src/app/devAutoUnlock.ts` about an unreferenced function). The rest, with the shape of each
      correction named:
      - `docs/decisions/0013-…md:76` — the clause `+ the unreferenced-blob sweep` is **deleted**. The sentence
        is about abandoned uploads; an abandoned upload never reaches `persistBlob`, so `UploadSessionPurge`
        alone satisfies it. Edit in place, no addendum.
      - `docs/features/memory/enablers/en.memory.media.md:98` (E3) — the same clause **deleted**, same reason.
      - `backend/…/UploadSessionPurge.kt:12` — **unchanged**, deliberately. It records *why* the two halves are
        split, which is still true and is the reason this CR did not fold them together.
      - `docs/inbox.md` — the sweep returns **three** hits, not one. Only #26 item (3) is **rewritten**; the
        other two are the rows for the two leaks below, which already describe this sweep correctly (one of
        them already names this CR as what puts it out of scope) and are left **unchanged**. Item (3) becomes
        what is actually left: the sweep is deferred because
        its set is empty by construction; it names the guard as its precondition, the reader-side read-ordering
        constraint, and the two leaks below as separate rows. ·
      **Done when:** `for f in scripts/validate_*.py; do python3 "$f"; done` green over the **committed** tree.

## Definition of done

All four phases green, and — since there is no remote and **no CI run has ever executed** — "green" means the
full glob run locally over the **committed** tree: `for f in scripts/validate_*.py; do python3 "$f"; done`,
which is also the strongest form the per-phase done-whens above are measured against. Baseline for that run,
2026-08-17 @ `021bc66`: **26 guards, all `rc=0`** — so a red after this CR is this CR's.

The front-matter invariant is enforced by `scripts/validate_blob_reference_arity.py` rather than by a sentence,
and the CR states how far that reaches instead of implying it is total: the three declaration keys pin the arm
*count and identity*, the live-twin key pins that the one predicate running over them today enumerates all
three, and a **future** consumer is covered by neither — that residue is the guard's documented limit (b), not
an unstated gap. Bitten both ways 2026-08-17: dropping the cover arm from `subtreeBlobIds` leaves the three
declaration keys at an unchanged `3/3/2`, which is exactly why the fourth key is in the guard and not only in
the table above. No coverage change:
`satisfies:` names an enabler, and `docs/coverage/` models use-cases only (`find docs/coverage -name 'en.*'`
→ empty).

**Explicitly out of scope — two leaks refinement measured, each its own work order.** Neither is reachable by
this CR's predicate, and naming them here is what keeps them from being read as covered:
1. **Orphaned media rows.** `NodesStore.deleteSubtree` says in its own comment that it *"leaves each node's own
   media rows untouched"*. After a node delete those rows point at a deleted `node_id`; every read filters on
   `scopeId + nodeId`, so they are unreachable, and they are not soft-deleted, so `MediaTrashPurge`
   (`deletedAt is not null`) never sees them. Their blobs stay **referenced** — the foreign key holds and any
   unreferenced-blob predicate correctly skips them. Only a whole-space delete reclaims them. This is the leak
   that actually holds bytes.
2. **Orphaned store objects.** Every store delete is best-effort and post-commit, and `BlobStore.kt:121` wraps
   it in `runCatching {}` with no handler, so a failure is silent. The object then survives with **no row at
   all** — invisible to any database predicate. Finding it needs a store listing diffed against
   `veyq_blob.storage_key ∪ veyq_upload_session.storage_key`: a different tool, and the only one of the three
   with no foreign key backstop anywhere.

## Retrospective

*(filled at implementation close — planning, execution, and process/collaboration, both directions, ending in
one concrete "next time")*
