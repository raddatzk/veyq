# ADR 0030 — Contributor public share: a sealed anonymous share identity + a share-scoped upload path

- Status: Accepted
- Date: 2026-07-07

## Context

[ADR 0029](0029-public-viewer-share.md) built the first sharing slice — a **read-only VIEWER** public share —
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
anonymous identity and a share-scoped upload path. **v1 is deliberately narrow: role CONTRIBUTOR only,
direct-append, personal scope.**

1. **A CONTRIBUTOR role on the share.** The share record's plaintext `role` (VIEWER in ADR 0029) gains
   **CONTRIBUTOR** = *view + append-upload*. The curator opts in at create time. v1 builds **only** CONTRIBUTOR;
   the pre-E2EE product's **MANAGER** (edit/delete) role is deferred; the **UPLOADER** (write-only drop-box) is
   decided separately in [ADR 0031](0031-uploader-public-share.md) (it builds on this identity + upload path).

2. **A mandatory, sealed display name → an anonymous share identity.** Before contributing, the visitor must
   enter a **display name**. The client **seals it under `K_mem`** (which it holds) and creates an anonymous
   **share identity**: the server stores a `veyq_share_identity` row — an opaque `identityId`, the shared
   `shareId`, the **`K_mem`-sealed display name** (ciphertext), and the hash of an unguessable **contributor
   token** returned to the client. The server **never** sees the name. This is the **anonymous share identity**
   ADR 0029 named, and the **buckets hook**: name → sealed identity → (later) the per-contributor primary bucket.

3. **A share-scoped, unauthenticated upload path (the presign mirror + blind capability).** Two endpoints mirror
   the authed media control plane, gated **only** on share state — a parallel authz path, never a relaxed member
   filter: `POST /api/shares/{shareId}/blob-uploads` (presign one PUT per chunk, [ADR 0013](0013-blob-storage-presigned-direct-to-store.md))
   and `POST /api/shares/{shareId}/media` (complete + attach). Both require the share to **exist + be `enabled` +
   have role CONTRIBUTOR**, and a **valid contributor token** (the blind upload-capability). The contributor
   seals the blob's `K_blob`, its derivatives, and the listed metadata **under `K_mem`** — byte-for-byte the
   curator's upload, only the transport is share-gated.

4. **Direct-append to the shared Memory root.** Contributed media attaches to the **shared node** (`share.nodeId`)
   — contributors add to the **top Memory**, not an arbitrary subtree node. Each media row is stamped with the
   contributor's **`share_identity_id`**. It appears in the shared Memory **immediately** (direct-append — no
   review gate); the curator can delete it or revoke the link. Moderation (review-before-publish) is deferred.

5. **Attribution stored, grouping deferred.** The anonymous resolve ([ADR 0029](0029-public-viewer-share.md))
   returns each media's `identityId` and each share identity's sealed name. **v1 renders all contributed media
   inline** in the one read-only gallery (a viewer sees the combined result). The **buckets UI** — grouping each
   contributor's uploads under their name — is a later slice; the `identityId` link + the sealed name are the
   hook it builds on, present from v1.

6. **Abuse controls.** An unauthenticated upload endpoint is an abuse surface. Defense: the curator opt-in
   (role CONTRIBUTOR), the per-identity **contributor token**, the share `enabled` gate (**revoke stops uploads**),
   the existing per-blob **byte budget** ([ADR 0013 §3](0013-blob-storage-presigned-direct-to-store.md)), and a
   **per-share rate limit** (in-memory, best-effort defense-in-depth, mirroring the auth rate limiter). Soft (L1)
   revocation stops *new* uploads + reads; already-uploaded media (like already-read content) is not retracted.

7. **Client-integrity trust tier — same honesty as the viewer.** The no-install contributor page is operator-
   served web code ([ADR 0026](0026-client-trust-tiers.md)), so it is **passive-operator-safe, not operator-proof
   against an active foreign operator** — surfaced by the same **footer link** to the security docs, not a
   per-page caveat. The T&S **decryptable-on-report** corollary ([security model](../crosscutting/security-model.md))
   extends to the contributor's sealed name + contributed media: a reported contributor share URL carries the
   fragment key, so a reported share is decryptable. Intended, not a leak.

## Consequences

- **New server-visible surface, all opaque:** a `veyq_share_identity` row (opaque `identityId` + `K_mem`-sealed
  name + token hash + `shareId`), a `share_identity_id` stamp on `veyq_media`, and the two share-scoped upload
  endpoints. No name, no identity keypair, no account — the server never learns *who* contributed, only that an
  opaque identity did.
- **Direct-append means spam is briefly live.** Until the curator deletes it or revokes the link, an unwanted
  upload is visible to viewers — a **stated v1 limitation**; moderation (review-before-publish) is the mitigation
  and a deferred slice.
- **Attribution is self-asserted, not verified.** Anyone with the CONTRIBUTOR link can create an identity with
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
- **Uploader-only drop-box (UPLOADER) in this slice.** Split out: it needs a `canView` gate + a write-only
  surface with its own trust model; decided in [ADR 0031](0031-uploader-public-share.md). This slice ships the
  single CONTRIBUTOR role (view + add).
