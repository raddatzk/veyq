# ADR 0034 — Media uploader buckets: an `owner_key`-keyed bucket table (operator-blind)

- Status: Accepted
- Built: yes
- Date: 2026-07-09
- Model: [en.memory.buckets](../features/memory/enablers/en.memory.buckets.md)

## Context

**Buckets** are a per-uploader container *within one Memory* holding the media that uploader contributed — an
**attribution axis** ("who contributed this?"), orthogonal to Chapters (the owner's curation axis). Each uploader gets
one automatic **primary bucket** (its name is the uploader's) plus optional one-level **sub-buckets**; it surfaces as a
Memory tab ("Contributions" / "Beiträge"). The uploader is a **member** *or* an **anonymous share identity**, so the
axis groups members and public contributors alike. [ADR 0030](0030-contributor-public-share.md) already stamped each
contributed medium with an opaque identity and a sealed name. The product direction is the full organizer —
grouping **and** sub-bucket management — not read-only grouping.

## Decision

Adopt an **`owner_key`-keyed bucket table**, operator-blind. The table, the lifecycle and the gates as built are the
model, [en.memory.buckets](../features/memory/enablers/en.memory.buckets.md); the experience is
[uc.memory.buckets](../features/memory/use-cases/uc.memory.buckets.md).

1. **A bucket is a per-uploader container within one Memory** — the attribution axis, a filter and organization view
   over the same media, **not** a node, a permalink or a Memory/Chapter/Folder. "Bucket" is an internal noun; users
   see the "Beiträge" tab.

2. **A real bucket table, not a derived group-by.** A row per materialized bucket, with one primary per
   `(node, owner_key)`; each medium carries its bucket (null = its owner's primary) and its uploader's `owner_key`,
   copied on attach so grouping and move checks never join the bucket.

3. **One opaque `owner_key`** identifies the uploader: `user:{userId}` for a member, `share:{shareIdentityId}` for a
   guest (reusing ADR 0030's identity as the grouping key). It is operator-visible, like membership and the identity
   stamp, so the server can scope grouping and moves by one opaque key without knowing identity semantics; it is
   **not** the authorization gate (point 6).

4. **Virtual-until-upload primary; sealed names.** A primary materializes lazily on its uploader's first upload or
   first sub-bucket, so an uploader with no media has no row. Its derived name is sealed like any bucket name (the
   view is uniform and blind) and cannot be renamed. Sub-bucket names are sealed user text, so the server enforces
   **no name uniqueness**; the only uniqueness is the content-blind one primary per `(node, owner_key)`, held by the
   **node row's lock**, not by a unique key: under the lock a second first upload lands in the winner's primary, where
   with a key the loser's whole attach — a guest's or a member's upload — would fail and roll back.

5. **One-level nesting.** A sub-bucket sits directly under a primary and inherits its owner; no deeper nesting, no
   reparenting.

6. **Reparent-on-delete + member-gated sub-bucket authorization** (both deliberate reversals of the pre-E2EE code).
   Deleting a sub-bucket moves its media back to the owner's primary — media are **never lost**; primaries are not
   deletable. Creating, renaming and deleting a sub-bucket and moving media are gated to **scope membership**: any
   member may curate any owner's sub-buckets, never a primary, and a move stays within one owner. The gate is
   membership, not `owner_key == caller`, so a curator can tidy a contributor's uploads; no reader may infer an
   owner-only guarantee.

## Consequences

- **Operator-visible surface, all opaque:** `owner_key`s, the bucket structure (node, parentage, `is_primary`, counts)
  and each medium's bucket and uploader key — coarse metadata consistent with membership and ADR 0030's stamp,
  recorded in the [security model](../crosscutting/security-model.md). Sealed names and all content stay blind. A
  personal space with no share is wholly confidential: its only `owner_key` is the account owner's.
- **ADR 0030's forward-compatibility is spent as intended:** contributor grouping falls out of
  `share:{shareIdentityId}` with no migration, and the contributor's sealed name becomes its primary's.
- **One redundant column, deliberately:** a medium's ADR 0030 identity stamp overlaps its `owner_key` for guests; it
  stays rather than churn the sharing side.

## Alternatives considered

- **Derived primary buckets, no table** (a per-media uploader id grouped at read time). Rejected: asymmetric
  (contributors already have a materialized identity, members would be a bare group-by), and it cannot carry
  sub-buckets without adding the table later anyway.
- **The pre-E2EE two-column `uploader_user_id` / `share_identity_id` XOR.** Rejected — the XOR was enforced only in
  application code at many call sites; one opaque `owner_key` is cleaner.
- **Materialize every primary eagerly.** Rejected — lazy find-or-create avoids empty rows and their lifecycle churn.
- **Cascade-delete a bucket's media** (the pre-E2EE code). Rejected — reparent-to-primary never loses media;
  destructive delete belongs to the explicit media delete path, not to tidying a folder.
- **Cleartext names + server-side name uniqueness** (the pre-E2EE code). Rejected — breaks operator-blindness.
- **A unique key for the one primary.** Rejected (point 4): the loser's attach would fail instead of landing.
- **A fully sealed `owner_key`** (hide attribution from the operator). Rejected — inconsistent with the already-visible
  identity stamp, and it would force client-side-only grouping and authorization; it stays a possible escalation.
