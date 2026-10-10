# ADR 0030 — Contributor public share: a sealed anonymous share identity + a share-scoped upload path

- Status: Accepted
- Date: 2026-07-07

## Context

[ADR 0029](0029-public-viewer-share.md) built the first sharing slice — a **read-only READ** public share —
and **deferred** the contributor/uploader variant, naming its three dependencies: a **blind upload-capability**,
a **public-share presign mirror**, and **anonymous share identities**. This ADR decides those: how an
**anonymous visitor with no account** can *add* media to a shared Memory — not just view it — while the server
stays operator-blind.

The **crypto is already available**: a contributor who opens `/s/{shareId}#k=<K_share>` unwraps the Memory's
`K_mem` from the share wrap ([ADR 0012](0012-content-key-hierarchy.md), ADR 0029) exactly as a viewer does. With
`K_mem` in hand they can seal a new blob under a fresh `K_blob` (wrapped under `K_mem`) — the **same** operator-
blind upload the curator performs ([en.memory.media](../features/memory/enablers/en.memory.derivatives.md),
[ADR 0013](0013-blob-storage-presigned-direct-to-store.md)). What is **not yet decided** is the **server surface**:
the role, the mandatory contributor **display name** (and how it stays operator-blind), the **anonymous identity**
that owns a contributor's uploads, the **share-scoped upload endpoints**, and where contributed media lands.

The **display name is mandatory** — a product decision. A contributor names themselves on a pre-screen before
contributing. The name is not decoration: it is the **forward-compatible hook for buckets** (a later slice),
which group each contributor's uploads into a named per-contributor **bucket**, seeded from that display name.
Without it, attribution has nothing to key on and buckets would need a migration to add later. v1 does not build
the buckets UI, but it **captures the name into a sealed anonymous identity now** so buckets fall out of it later
without a migration.

## Decision

Adopt a **contributor public share** as an extension of the ADR 0029 share resource — a new role plus a sealed
anonymous identity and a share-scoped upload path. **v1 is deliberately narrow: role READ_WRITE only,
direct-append, personal scope.**

1. **A READ_WRITE role on the share.** The share record's plaintext `role` (READ in ADR 0029) gains
   **READ_WRITE** = *view + append-upload* (renamed 2026-10-06 from `CONTRIBUTOR` —
   [ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)'s three access modes). The curator opts in at create time. v1 builds **only** READ_WRITE;
   the pre-E2EE product's **MANAGER** (edit/delete) role is deferred; the **WRITE_ONLY** (write-only drop-box) is
   decided separately in [ADR 0031](0031-uploader-public-share.md) (it builds on this identity + upload path).

2. **A mandatory, sealed display name → an anonymous share identity.** Before contributing, the visitor must
   enter a **display name**. The client **seals it under `K_mem`** (which it holds) and creates an anonymous
   **share identity**: the server stores a `veyq_share_identity` row — an opaque `identityId`, the shared
   `shareId`, the **`K_mem`-sealed display name** (ciphertext), and the hash of an unguessable **contributor
   token** returned to the client. The server **never** sees the name, and serves it back only to the Memory's
   members (point 8). Naming yourself proves the visitor holds the link's key — a token derived from `K_share`,
   checked against a stored hash ([ADR 0076](0076-a-guest-write-proves-it-holds-the-links-key.md)) — so the share
   id alone names no guest. This is the **anonymous share identity**
   ADR 0029 named, and the **buckets hook**: name → sealed identity → (later) the per-contributor primary bucket.

3. **A share-scoped, unauthenticated upload path (the presign mirror + blind capability).** Three endpoints mirror
   the authed media control plane, gated **only** on share state — a parallel authz path, never a relaxed member
   filter: `POST /api/shares/{shareId}/blob-uploads` (open sessions + presign their first window of PUTs,
   [ADR 0013](0013-blob-storage-presigned-direct-to-store.md) §2), `POST /api/shares/{shareId}/blob-upload-windows`
   (each next window) and `POST /api/shares/{shareId}/media` (complete + attach). Each requires the share to
   **exist + be `enabled` + have role READ_WRITE**, and a **valid contributor token** (the blind upload-capability)
   — checked on every request, so a revoked link stops an upload in flight at its next window — and a session acts
   only for the identity that opened it. The contributor
   seals the blob's `K_blob`, its derivatives, and the listed metadata **under `K_mem`** — byte-for-byte the
   curator's upload, only the transport is share-gated.

4. **Direct-append to the shared Memory root.** Contributed media attaches to the **shared node** (`share.nodeId`)
   — contributors add to the **top Memory**, not an arbitrary subtree node. Each media row is stamped with the
   contributor's **`share_identity_id`**. It appears in the shared Memory **immediately** (direct-append — no
   review gate); the curator can delete it or revoke the link. Moderation (review-before-publish) is deferred.

5. **Attribution stored, shown to members.** Each contributed media is stamped with its `identityId`, and the
   identity keeps the sealed name. That is the hook the **buckets** grouping builds on
   ([ADR 0034](0034-media-uploader-buckets.md)): the Memory's members see each contributor's uploads under their
   name. A guest's page renders all contributed media inline in the one read-only gallery and is told neither
   the stamp nor the name (point 8).

6. **Abuse controls.** An unauthenticated upload endpoint is an abuse surface. Defense: the curator opt-in
   (role READ_WRITE), the per-identity **contributor token**, the share `enabled` gate (**revoke stops uploads**),
   the existing per-blob **byte budget** ([ADR 0013 §3](0013-blob-storage-presigned-direct-to-store.md)), the
   **per-link storage limit** ([ADR 0073](0073-storage-quota-belongs-to-whoever-pays-for-the-store.md)), and a
   **per-share rate limit** (in-memory, best-effort defense-in-depth, mirroring the auth rate limiter). Soft (L1)
   revocation stops *new* uploads + reads; already-uploaded media (like already-read content) is not retracted.

7. **Client-integrity trust tier — same honesty as the viewer.** The no-install contributor page is operator-
   served web code ([ADR 0026](0026-client-trust-tiers.md)), so it is **passive-operator-safe, not operator-proof
   against an active foreign operator** — surfaced by the same **footer link** to the security docs, not a
   per-page caveat. The T&S **decryptable-on-report** corollary ([security model](../crosscutting/security-model.md))
   extends to contributed media: a reported contributor share URL carries the fragment key, so a reported share
   is decryptable — and, with the database the operator holds, so are its guests' names (point 8). Intended, not
   a leak.

8. **A guest's name is for the Memory's members (owner's call, 2026-09-28).** No share link is served the name
   any guest gave; the Memory's members read it through their bucket list, as before.
   - **What a guest is served about an identity:** a WRITE_ONLY identity's `{identityId, wrappedIdentityKey}`,
     and only because a reader needs it to open that identity's media
     ([ADR 0031](0031-uploader-public-share.md) point 7) — so only that media names its identity. No READ_WRITE
     identity is served at all, not even the reading link's own, which a READ link keeps after a
     READ_WRITE→READ change. The served identity has **no name field**, so no guest-side code can be written
     to read one.
   - **The residual, accepted:** the names stay sealed as they are — a contributor's under `K_mem`, an uploader's
     under its own key, which a link opens through the drop private key. So a READ or READ_WRITE link **plus
     a database dump** reads every guest's name; in practice, that is an operator holding a link. That holder
     already decrypts every medium of the Memory, and a name is less than the photos it is attached to. A key
     only members hold — a name keypair whose private half sits under `K_SC` — would close it, and was rejected
     as more machinery than that difference is worth.
   - **The guest is told:** the naming form says that only the people the Memory belongs to see the name.

## Consequences

- **New server-visible surface, all opaque:** a `veyq_share_identity` row (opaque `identityId` + `K_mem`-sealed
  name + token hash + `shareId`), a `share_identity_id` stamp on `veyq_media`, and the two share-scoped upload
  endpoints. No name, no identity keypair, no account — the server never learns *who* contributed, only that an
  opaque identity did.
- **Direct-append means spam is briefly live.** Until the curator deletes it or revokes the link, an unwanted
  upload is visible to viewers — a **stated v1 limitation**; moderation (review-before-publish) is the mitigation
  and a deferred slice.
- **Attribution is self-asserted, not verified.** Anyone with the READ_WRITE link can create an identity with
  any name; the contributor token only binds *subsequent* uploads to *that* identity (prevents trivial cross-
  contributor spoofing), it does not authenticate the person. Operator-blind + accountless by design — stated.
- **Buckets forward-compatibility is bought now.** Because each contributor's uploads already carry a stable
  `share_identity_id` + a sealed name, the later buckets slice groups them without a data migration — exactly the
  pre-E2EE `MemoryShareIdentity → primary bucket` chain, adapted to operator-blind (the name is sealed, not
  cleartext).
- **Scope-rotation ≠ share-revoke** and the **personal-scope-only** cut carry over unchanged from ADR 0029
  (§6, Consequences): a personal scope has no member removal, so the governance + rotation interactions do not
  arise. Group/tenant contributor shares stay deferred.
- **Deferred, each its own slice:** the **buckets UI** (per-contributor grouping/display), **MANAGER** /
  contributor edit + delete, **password protection**, **moderation** (review-before-
  publish), and per-contributor **delete-your-own-upload**.
- **Amended 2026-10-11 (inbox #401 S5): the name is asked at the first contribution, not in front of the Memory.** A
  share view is the member view with functions removed ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)
  Consequences), so a READ_WRITE link opens on the Memory itself and its visitor names itself in the share page's one
  naming dialog when it first adds a photo — still mandatory before any contribution (point 2), never asked of a
  visitor who only looks. Point 2's identity, seal and buckets hook are unchanged.

## Alternatives considered

- **Cleartext display name (the pre-E2EE design source).** Rejected — it breaks operator-blindness. The name is
  **sealed under `K_mem`**; the server relays ciphertext, exactly like the Memory name.
- **No identity — seal the name onto each media.** Rejected: it loses the per-contributor grouping the buckets
  slice needs and gives no upload capability token. A single **share identity** per contributor-session is the
  forward-compatible hook (name → identity → bucket) and the natural home for the blind upload-capability.
- **Reuse the authed media endpoints with a relaxed membership gate.** Rejected — same reasoning as the viewer
  resolve (ADR 0029): a **parallel authz path keyed on share state**, never a relaxed tenant/scope filter, keeps
  the operator-blind guarantee auditable.
- **Contributions land in a pending/review area (moderation-first).** Deferred, not chosen for v1: the product
  decision is **direct-append** (a shared album everyone adds to); review-before-publish is a later moderation
  slice with its own pending state + curator review UI.
- **Uploader-only drop-box (WRITE_ONLY) in this slice.** Split out: it needs a `canView` gate + a write-only
  surface with its own trust model; decided in [ADR 0031](0031-uploader-public-share.md). This slice ships the
  single READ_WRITE role (view + add).
