# ADR 0034 — Media uploader buckets: an `owner_key`-keyed bucket table (operator-blind)

- Status: Accepted
- Date: 2026-07-09

## Context

**Buckets** are a per-uploader container *within a single Memory* holding the media that uploader contributed —
an **attribution axis** ("who contributed this?"), **orthogonal to Chapters** (the owner's curation axis). Each
uploader gets one automatic **primary bucket** (name = their display name) plus optional user-created, one-level
**sub-buckets**; the whole thing surfaces as a Memory tab ("Contributions" / "Beiträge"). The bucket owner is a
**member** *or* an **anonymous share identity**, so the axis groups **internal members and public contributors**
alike.

The **data hook already exists**: [ADR 0030](0030-contributor-public-share.md) captured each contributed medium's
uploader as an opaque `share_identity_id` + a `K_mem`-sealed display name, and **deferred the buckets UI** to a
later slice. This ADR decides that slice — widened from contributors to **every** uploader.

The refine (with the user) settled the shape: a **real bucket table** keyed by a single opaque **`owner_key`** —
not a minimal "derive from a per-media stamp" model — because that is symmetric across members and contributors
and carries **sub-buckets** without a second data model. V1 is the **finishing package**: the **full data layer +
backend** and the **"Beiträge" tab** as the full **organizer** — per-uploader grouping **and** sub-bucket
management (create/rename/delete folders, move media in). The product direction, refined with the user, is the
full organizer, not read-only grouping.

## Decision

Adopt an **`owner_key`-keyed bucket table**, operator-blind, built on the key hierarchy. The data model lives in [en.memory.buckets](../features/memory/enablers/en.memory.buckets.md);
the experience in [uc.memory.buckets](../features/memory/use-cases/uc.memory.buckets.md).

1. **A bucket = a per-uploader container within one Memory** — the **attribution axis**, **orthogonal to
   Chapters**, a filter/organization view over the same media (**not** a node, permalink, or
   Memory/Chapter/Folder). Internal noun **"bucket"** (a sanctioned third noun, distinct from the Memory/Chapter
   product language); user-facing surface = the **"Beiträge" tab**.

2. **A real `veyq_bucket` table, not a derived group-by.** A row per materialized bucket:
   `id, node_id, owner_key, parent_bucket_id? (null = primary), sealed_name? + suite_id, is_primary`, with
   **one primary per `(node_id, owner_key)`** — an invariant the schema does **not** currently carry (below).
   `veyq_media` gains **`bucket_id`** (nullable FK,
   `ON DELETE SET NULL`, `null` = the owner's primary) and **`created_by`** (the `owner_key`, copied onto the
   medium on attach — denormalized so grouping/move-authz never joins the bucket). Full field list:
   [en.memory.buckets](../features/memory/enablers/en.memory.buckets.md).

3. **One opaque `owner_key` string** identifies the uploader: `user:{userId}` (member) /
   `share:{shareIdentityId}` (contributor, **reusing the cr-0101 identity** as the grouping key). It is
   **operator-visible** (like membership + the existing `share_identity_id`), lets the server **scope grouping
   and moves** by that one opaque key without knowing identity semantics (it is **not** the authz gate — see §6),
   and **collapses** the fragile pre-E2EE two-column `uploader_user_id`/`share_identity_id` XOR into one field. The shipped `media.share_identity_id`
   (cr-0101) is **left untouched** — mildly redundant with `created_by` for contributors, cleaned when the
   sharing surface is next worked, **not** in this CR.

4. **Virtual-until-upload primary; names sealed under `K_mem`.** A primary bucket materializes **lazily** (a
   find-or-create on `(node, owner_key)` on the first upload / first sub-bucket); uploaders with no media have no
   row. Its **derived** name (member = username, contributor = the identity's sealed name) is itself **sealed
   under `K_mem`** so the view is uniform + operator-blind, and it is **not renameable**. Sub-bucket names are
   **`K_mem`-sealed** user text; because they are ciphertext there is **no server-side name uniqueness** — the
   only uniqueness this model wants is the content-blind **one primary per `(node, owner_key)`**.

   **That uniqueness is not enforced today, and the find-or-create is best-effort.** Two requests that resolve
   the same missing primary concurrently each create one. The intended `UNIQUE (node_id, owner_key) WHERE
   is_primary` is a **partial** index, which the H2 ∩ MariaDB common subset does not have — but the portable
   form does: a marker column carrying `owner_key` **only** when `is_primary` (else `NULL`, and NULLs do not
   collide), plus a plain `UNIQUE (node_id, marker)`. That is the same shape `veyq_node` already uses for slug
   uniqueness (`ux_node_parent_slug`, V10), where the store's `slugTaken` pre-check exists for the friendly
   error and the **index** is the guarantee. Buckets have the pre-check and not the index — **due when buckets
   are next touched**, with a dedup of any primaries that already doubled
   ([architecture §11](../architecture/README.md)).

5. **One-level nesting.** Sub-buckets sit **one level** under a primary (the parent must be a primary), **owner
   inherited** from the parent. No arbitrary depth, **no reparenting**.

6. **Reparent-on-delete + member-gated sub-bucket authz (both deliberate reversals of the pre-E2EE code).**
   Deleting a sub-bucket sets its media **`bucket_id = null`** (they fall back to the owner's primary — media are
   **never lost**, reversing the pre-E2EE hard-delete); **primaries are not deletable**. Create / rename / delete
   a **sub-bucket** and **move media** are **server-gated to scope membership** — **any member** may curate **any
   owner-root's** sub-buckets (its own *or* a contributor's), **never a primary**, and a move stays **within one
   owner-root** (never across owners). **Delete-media** and **edit capture time** stay **any key-holder**
   (unchanged from the media data layer). *(cr-0123 relaxed the authz from the original owner-only gate —
   `owner_key == caller` — to member, so a curator can tidy a contributor's uploads; a reader must not infer the
   stronger owner-only guarantee, which the code does not give.)*

7. **V1 scope = full data layer + backend + the "Beiträge" organizer.** Build the table + `owner_key` +
   `bucket_id`/`created_by` + the lazy primary + the sub-bucket **endpoints** (list, create, rename, delete-with-
   reparent, move) + sub-bucket authz (owner-gated at cr-0113, **member-gated since cr-0123** — §6); and the web **"Beiträge" tab** as the full **organizer** — a hierarchy
   picker (your primary + sub-buckets), create/rename/delete sub-buckets, move media between your own buckets, a
   **virtual own-primary** that materializes on first use — **always available**. **Deferred:** uploading directly
   into a *selected* sub-bucket (the `bucketId` is plumbed to `attach`; the web upload flow still targets the
   primary — move covers it), a **camera/source filter** + a **capture-time-shift** tool (the EXIF metadata isn't
   surfaced in the fresh client), and group/tenant **self-identification** in the organizer UI — now a pure UI
   nicety, not an authz need: cr-0123 relaxed the server gate to **member**, so there is no own-bucket gate to
   self-identify against.

## Consequences

- **Operator-visible surface, all opaque:** `owner_key`s, the **bucket structure** (node, parentage,
  `is_primary`, per-bucket counts), and `bucket_id`/`created_by` on media — coarse operational metadata,
  consistent with membership + the cr-0101 contributor stamp; recorded in the
  [security-model](../crosscutting/security-model.md). **Sealed names and all content stay blind.** A personal
  space **with no share** is wholly confidential — its only `owner_key` is the account owner's.
- **ADR 0030's forward-compat is spent as intended.** Contributor grouping falls out of `share:{shareIdentityId}`
  with **no migration** on the sharing half; the sealed contributor name becomes the primary's sealed name.
- **We diverge deliberately from how buckets worked before E2EE.** Cascade-delete → **reparent-to-primary**; no
  bucket ownership → **`owner_key`-structured, member-gated sub-bucket authz** (cr-0123); cleartext names + server-side uniqueness → **`K_mem`-sealed names +
  primary-only uniqueness**. These are behavior/blindness changes, not just crypto.
- **One redundant column, deliberately.** `media.share_identity_id` (cr-0101) overlaps `created_by` for
  contributors; kept to avoid churning the shipped sharing feature in this CR, flagged for cleanup when sharing is
  next touched.
- **Migration adds** the `veyq_bucket` table + `media.bucket_id` + `media.created_by`; existing media backfill to
  `created_by = user:{owner}` (or their `share:{id}`) and `bucket_id = null` (→ their primary, lazy). That list-time
  backfill was deleted before V1 (inbox #264): every medium since is born with both, and V1 carries no older rows.

## Alternatives considered

- **Derived primary buckets, no table** (a per-media `created_by_user_id` grouped at read time — the first-cut
  V1). **Rejected in refine:** it is **asymmetric** (contributors already have a materialized identity row,
  members would be a bare group-by), and it does **not** carry sub-buckets without introducing the table later
  anyway. A single `owner_key` + one table is the symmetric, sound foundation.
- **The pre-E2EE two-column `uploader_user_id`/`share_identity_id` XOR.** Rejected — the XOR was enforced only in
  app code at many call sites; **one opaque `owner_key`** is cleaner and authorization-friendly.
- **Materialize every primary eagerly.** Rejected — **virtual-until-upload** (lazy find-or-create) avoids empty
  rows + create/delete lifecycle churn; a primary appears exactly when its uploader has media.
- **Cascade-delete a bucket's media** (the pre-E2EE code). Rejected — **reparent-to-primary** never loses media;
  destructive delete belongs to the explicit media delete/trash path (cr-0110/0111), not to tidying a folder.
- **Cleartext names + server-side name uniqueness** (the pre-E2EE code). Rejected — breaks operator-blindness;
  names are **`K_mem`-sealed**, sub-bucket names carry **no** uniqueness (ciphertext), only the content-blind
  primary uniqueness remains.
- **Fully-sealed `created_by`** (hide attribution from the operator). Rejected — inconsistent with the already-
  visible `share_identity_id`, and it would force client-side-only grouping/authz. `owner_key` is operator-
  visible coarse metadata; the stricter variant stays a future escalation path.
