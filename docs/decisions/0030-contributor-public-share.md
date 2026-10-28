# ADR 0030 — Contributor public share: a sealed anonymous share identity + a share-scoped upload path

- Status: Accepted
- Built: yes
- Date: 2026-07-07
- Model: [en.sharing.public-link](../features/sharing/enablers/en.sharing.public-link.md)
- Replaced in part: point 4 by ADR 0080 (built)

## Context

[ADR 0029](0029-public-viewer-share.md) built the **read-only** public share. This ADR decides how an **anonymous
visitor with no account** can *add* media to a shared node while the server stays operator-blind. The crypto is
already there: a contributor opens the root's key from the link exactly as a viewer does and seals new blobs under it,
byte for byte the curator's upload ([ADR 0013](0013-blob-storage-presigned-direct-to-store.md)). What is open is the
server surface: the role, a mandatory display name and how it stays blind, the anonymous identity that owns a
contributor's uploads, the share-scoped upload endpoints, and where contributed media lands.

The **display name is mandatory** — a product decision. It is the hook for attribution: each contributor's uploads
group under their name ([ADR 0034](0034-media-uploader-buckets.md)), and without a name captured from the start that
grouping would need a migration.

## Decision

Adopt a **contributor public share** as an extension of the ADR 0029 share resource — a new role, a sealed anonymous
identity and a share-scoped upload path. How each is built is the model,
[en.sharing.public-link](../features/sharing/enablers/en.sharing.public-link.md).

1. **A `READ_WRITE` role on the share** = view + add (one of
   [ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)'s three access modes). The curator opts in
   at create. A manager role (edit and delete others' uploads) is not built; `WRITE_ONLY` is
   [ADR 0031](0031-uploader-public-share.md).

2. **A mandatory, sealed display name → an anonymous share identity.** The visitor names itself at its first
   contribution, never in front of the content; a visitor who only looks is never asked. The client seals the name
   under the root's key and creates a **share identity**: the server stores an opaque id, the sealed name and the
   hash of an unguessable **contributor token** it returns once. The server never sees the name. Naming yourself
   proves the visitor holds the link's key ([ADR 0076](0076-a-guest-write-proves-it-holds-the-links-key.md)), so the
   share id alone names no guest.

3. **A share-scoped, unauthenticated upload path.** Endpoints that mirror the member media control plane, gated
   **only** on share state — a parallel authorization path, never a relaxed member filter. Each needs a live link
   whose role uploads and a valid contributor token, checked on every request, so a switched-off link stops an upload
   in flight at its next window; a session acts only for the identity that opened it.

4. **Contributed media lands where the guest names, inside the subtree.** A guest's upload names an optional target
   node below the root, checked inside the live subtree; the target node's kind decides what a contributor may do
   there ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) decision 6). Each medium
   is stamped with the contributor's identity and appears **immediately** (direct-append, no review gate); the curator
   can delete it or switch off the link.

5. **Attribution stored, shown to members.** The identity stamp on each medium and the identity's sealed name are
   what the per-uploader grouping builds on ([ADR 0034](0034-media-uploader-buckets.md)). A guest's page shows all
   contributed media in one gallery and is told neither the stamp nor the name (point 8).

6. **Abuse controls.** An unauthenticated upload endpoint is an abuse surface. Defense: the curator's opt-in, the
   per-identity contributor token, the share's `enabled` gate (**switching off stops uploads**), the per-blob byte
   budget ([ADR 0013 §3](0013-blob-storage-presigned-direct-to-store.md)), the **per-link storage limit**
   ([ADR 0073](0073-storage-quota-belongs-to-whoever-pays-for-the-store.md)) and a per-share rate limit. Already
   uploaded media is not retracted by a switch-off.

7. **Client-integrity trust tier — same honesty as the viewer.** The no-install contributor page is operator-served
   web code ([ADR 0026](0026-client-trust-tiers.md)), surfaced by the same footer link. A reported contributor link
   carries the fragment key, so it is decryptable on report — and, with the database, so are its guests' names
   (point 8).

8. **A guest's name is for the members.** No share link is served the name any guest gave; members read it through
   their bucket list.
   - **What a guest is served about an identity:** a `WRITE_ONLY` identity's `{identityId, wrappedIdentityKey}`, and
     only because a reader needs it to open that identity's media ([ADR 0031](0031-uploader-public-share.md) point 7).
     No `READ_WRITE` identity is served at all, not even the reading link's own. The served identity has **no name
     field**, so no guest-side code can be written to read one.
   - **The residual, accepted:** a contributor's name is sealed under the root's key, an uploader's under its own key,
     which a link opens through the drop private key. So a `READ` or `READ_WRITE` link **plus a database dump** reads
     every guest's name; that holder already decrypts every medium, and a name is less than the photos it is attached
     to. A key only members hold — a name keypair whose private half sits under `K_SC` — would close it, and was
     rejected as more machinery than that difference is worth.
   - **The guest is told:** the naming form says that only the people the content belongs to see the name.

## Consequences

- **New server-visible surface, all opaque:** the identity row (opaque id, sealed name, token hash, share id) and an
  identity stamp on each contributed medium. The server never learns *who* contributed, only that an opaque identity
  did.
- **Direct-append means spam is briefly live.** Until the curator deletes it or switches off the link, an unwanted
  upload is visible to viewers; review-before-publish would be the mitigation and is not built.
- **Attribution is self-asserted, not verified.** Anyone with the link can create an identity with any name; the
  contributor token binds *subsequent* uploads to *that* identity, it does not authenticate the person.
- **Per-uploader grouping needed no migration**, because each contribution carried a stable identity and a sealed
  name from the start.

## Alternatives considered

- **Cleartext display name (the pre-E2EE design).** Rejected — it breaks operator-blindness.
- **No identity — seal the name onto each medium.** Rejected: it loses per-contributor grouping and gives no upload
  capability token. One identity per contributor is the hook (name → identity → bucket) and the home of the token.
- **Reuse the member media endpoints with a relaxed membership gate.** Rejected — a parallel authorization path keyed
  on share state keeps the operator-blind guarantee auditable.
- **Contributions land in a review area first.** Not chosen: the product decision is direct-append (a shared album
  everyone adds to); review-before-publish would need its own pending state and review UI.
- **The write-only drop-box in this decision.** Split out: it needs a `canView` gate and its own trust model —
  [ADR 0031](0031-uploader-public-share.md).
