---
id: cr-guest-write-proves-the-link-key
title: A guest who names themself on a link proves they hold its key — a share id alone writes nothing
status: planned
satisfies: [uc.sharing.share-contributor, uc.sharing.share-uploader]
adrs: ["0029", "0030", "0031", "0037", "0072", "0073"]
invariants: [operator-blind E2EE, API-first]
---

## Open for the owner

**Third round answered by the owner, 2026-10-01: every recommendation added since the second answer is taken too** (questions added by the third refine wave and by implementation hand-backs). Rows those questions proposed exist in `docs/inbox.md` (#233–#243).

Nothing below is decided. Each question carries a recommendation; the CR is written for the recommendations.
**Order:** this CR lands **after** `cr-reissue-share-link` (planned, owner-approved), because it adds a field to
that CR's re-issue request. It is independent of `cr-tenant-removal-share-links`; with `cr-share-link-delete`
either order works (Decision 7). Every claim about those CRs is *planned*; the implementer re-reads each one
against the merged tree first ([changes/README.md](README.md) § *A sibling CR still in flight is cited as PLANNED*).

**What the refine measured** (inbox #227: *"a share id stored raw lets a database copy add uploads"*):

- **The row is right, and the share id is the only bearer secret stored raw.** Every other capability the
  server checks is kept as a hash: login sessions, tenant invites, recovery tokens, pairing handles, contributor
  tokens and share-password verifiers (Inventory row 1). The share id is the primary key in clear (row 2).
- **Exactly one guest call is opened by the id alone: `createShareIdentity`.** It mints a fresh
  `contributorToken`; every later guest write (start, window, complete, delete-own) needs that token, whose hash
  only is stored (row 3). A password-protected link is already safe: its gate checks `SHA-256(token)` against a
  stored verifier (row 4). So the hole is: **a copy of the database + the live instance → name yourself on every
  enabled, password-less CONTRIBUTOR/UPLOADER link → upload.**
- **What that buys the holder is junk and spent quota, never a read.** Without `K_share` they cannot seal under
  `K_mem` (CONTRIBUTOR) nor open the link's sealed drop public key (UPLOADER), so what they upload opens for
  nobody (row 16). It is bounded per link by the tenant's per-link limit and identity rate limit (rows 5–6), but it fills
  the Memory's guest buckets with undecryptable items and can spend the tenant quota. Integrity and availability,
  not confidentiality.
- **Hashing the lookup id — the row's suggestion — costs much and closes less** (question 1):
  - The raw id is not only a lookup key. Members need it back: the manage list returns it to rebuild the link, and
    two ADR 0072 seal contexts bind it — the recovery copy of `K_share` and every guest's sealed name, which the
    bucket list serves with the raw `shareId` so members can open it (row 7). A hashed id would need a third,
    `K_SC`-sealed copy of the id on every share and on both read models, and a share without a recovery copy
    could never get one.
  - It also travels in the clear in every guest request path (`/s/{id}`, `/api/shares/{id}/…`, row 8), so any
    reverse-proxy access log the operator enables holds it raw. The bundled stack enables none (row 9); a
    Kubernetes ingress controller is the operator's own choice and is outside the repo.
- **A key-derived write proof closes both, and changes no lookup:** the client derives a write token from
  `K_share`, the server keeps only its hash, and `createShareIdentity` checks it — the password gate's shape,
  with the key in place of the password. That makes ADR 0029 §2's *"one without the other is inert"* true for
  writes, where today it is not.

1. **Which fix?** Options:
   (a) **hash the lookup id** (`id = SHA-256(shareId)`; the path keeps the raw id; members get it back through a
   new `K_SC`-sealed copy);
   (b) **a write proof derived from `K_share`**: `writeToken = HKDF(K_share, "veyq/v1/share-write:" + shareId)`,
   sent as `X-Share-Write` on `createShareIdentity`; the server stores `writeVerifier = SHA-256(writeToken)` at
   create and compares in constant time;
   (c) **accept and document** — bounded by the per-link limit, nothing readable.
   **Recommendation: (b).** It closes the database copy *and* an access log, touches one guest call, one column
   and two request schemas, and leaves the lookup, the listing, the bucket list, the seal contexts, the re-issue's
   and the delete's id logic exactly as they are. (a) changes the id model across contracts, backend and web for a
   smaller result. (c) leaves the one raw bearer column in a schema that hashes every other.
2. **Which guest calls need the proof?** (a) `createShareIdentity` only; (b) every guest call, the resolve and
   blob reads included. **Recommendation: (a).** It is the only call the id alone opens for writing (row 3). A read
   by an id holder yields ciphertext a database copy already holds, plus blob ciphertext under `K_mem` — nothing
   readable, so the gate goes where the harm is. (b) stays a cheap later step if a read-side reason appears.
3. **What does a missing or wrong proof answer?** **Recommendation: the non-enumerable `404 share_not_found`,**
   as a password-protected link answers a write without its token (row 4). A real guest always holds the key, so
   no guest ever sees it; a distinct code would only confirm the link exists to someone without the key.
4. **Shares minted before this lands** have no verifier. **Recommendation: the column is nullable and a null
   verifier refuses every new guest identity (fail closed);** nothing has shipped (row 10), and an existing guest's
   token keeps working because only naming oneself is gated. A curator re-issues such a link to take new guests.

## Goal

Naming yourself on a CONTRIBUTOR or UPLOADER link requires proof that you hold the link's key. A database copy,
an access log or a leaked id without the `#k=` fragment can no longer add a guest, so it can no longer upload.
The server learns nothing new: it stores a hash of a key-derived token and sees that token, never `K_share`.

## Context & constraints

- **The capability's two halves:** [ADR 0029](../docs/decisions/0029-public-viewer-share.md) §1–§2 — the id is
  *"half the capability"*, the fragment key the other half; today the server checks only the first.
- **The write paths:** [ADR 0030](../docs/decisions/0030-contributor-public-share.md) (contributor identities and
  their token), [ADR 0031](../docs/decisions/0031-uploader-public-share.md) point 7 (the uploader's own key wrapped
  to the drop key).
- **The verifier precedent:** [ADR 0037](../docs/decisions/0037-password-protected-shares.md) — the client derives
  a token, the server stores `SHA-256(token)` and compares; cr-0164 put that gate in front of the write path.
- **Binding:** [ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md) — derived values bind the object
  they belong to, hence the share id in the HKDF info.
- **The bound that exists today:** [ADR 0073](../docs/decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md)
  §4 (per-link limit) and the per-share identity rate limit.
- **Operator-blind, explicitly:** `writeToken` is an HKDF output under a label of its own; it reveals nothing about
  `K_share` and opens no seal. An *active* operator could record it — and gains only the ability to write, which
  it has anyway (it is the server). This CR is a layer-1 control against a database copy and an access-log
  holder, not against the operator ([security-model](../docs/crosscutting/security-model.md) § What holds today on a client the operator does not serve:
  *"a check the server runs never closes a row here"*).

### Inventory — measured on `main` @ `7a34c57`, 2026-10-01

Every row was read or run at refine; the command re-derives it.

| # | Fact | Locator |
|---|---|---|
| 1 | Every other bearer secret is stored hashed: sessions, invites, recovery tokens, pairing handles, contributor tokens, password verifiers. | `grep -rn -i "token_hash\|handle_hash\|pw_verifier" backend/src/main/resources/db/migration/*.sql \| grep -i "varchar\|text"` |
| 2 | The share id is the primary key in clear, and the identity table's foreign key. | `grep -n "id             VARCHAR(64)" backend/src/main/resources/db/migration/V31__share.sql`; `grep -n "fk_share_identity_share" backend/src/main/resources/db/migration/V32__contributor_share.sql` |
| 3 | `createIdentity` gates on active share, password, role and rate limit — nothing proves the key; every other guest write looks the identity up by its token hash. | `grep -n "fun createIdentity" -A22 backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt`; `grep -n "store.findIdentity(shareId, contributorToken)" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt`; `grep -n "fun findIdentity" -A3 backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt` |
| 4 | The password gate: `SHA-256(token)` against `pwVerifier`, constant-time; a missing token on a write answers `404`. | `grep -n "private fun passwordCheck" -A14 backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt`; `grep -n "PwCheck.MISSING -> return CreateIdentityOutcome.NotFound" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt` |
| 5 | The identity and upload bounds are in-memory, per share id. | `grep -n "fun allowIdentity\|fun allowUpload\|TokenBucketCounter()" backend/src/main/kotlin/com/veyq/app/features/sharing/ShareRateLimiter.kt` |
| 6 | The per-link limit is a tenant setting. | `grep -n "fun setShareLinkLimit" backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantStore.kt` |
| 7 | Members get the raw id back, and two seal contexts bind it: the manage list, the recovery copy, the guest-name seal, and the bucket list's `shareId`. | `grep -n "shareKeyRecoveryContext = \|identityNameContext = " web/src/features/memory/share.ts`; `grep -n "carries that identity's \`shareId\`" contracts/api/media.openapi.yaml` |
| 8 | The raw id rides every guest request path and the page route. | `grep -n "^  /api/shares/{shareId}" contracts/api/sharing.openapi.yaml`; `grep -n "path: '/s/:shareId'" web/src/router.ts` |
| 9 | No access log is enabled in the backend, the bundled Traefik, or the chart; nothing in the sharing feature logs. | `grep -rn -i "accesslog\|access-log" backend/src/main/resources infra charts` (no hits); `grep -rn "Log\.\|LOG\.\|logger" backend/src/main/kotlin/com/veyq/app/features/sharing` (no hits) |
| 10 | Pre-V1 migrations assume an instance reset (nothing has shipped). | `grep -n "before V1" backend/src/main/resources/db/migration/V57__uploader_drop_key.sql` |
| 11 | A share's role can be changed after mint (so a VIEWER share needs a verifier too). | `grep -n "a flipped VIEWER link" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt`; `grep -n "fun updateShare" -A2 backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt` |
| 12 | HKDF-SHA256 derivations with a `veyq/v1/…` label live in the crypto package, pinned by vectors. | `grep -n "slug-token\|media-dedup" packages/crypto/src/v1.ts`; `grep -n "slugToken" packages/crypto/src/v1.conformance.test.ts` |
| 13 | The guest's one identity call site holds the fragment key. | `grep -n "createContributorIdentity(shareId" web/src/views/ShareView.vue`; `grep -n "const fragmentKey" web/src/views/ShareView.vue` |
| 14 | Blast radius in tests: backend tests that post `/identities` directly (23 call sites in 11 files at refine) and that create shares. | `grep -rc '/identities"' backend/src/test/kotlin \| grep -v ":0"`; `grep -rc '"/api/shares"' backend/src/test/kotlin \| grep -v ":0"` |
| 15 | The field-visibility guard does not see headers; an opaque `contentEncoding: base64` body field needs no marker. | `grep -n "X-Share-Password" scripts/validate_field_visibility.py`; `grep -n "pwVerifier:" -A6 contracts/api/sharing.openapi.yaml` |
| 16 | Without `K_share` a guest can neither unwrap `K_mem` nor open the link's drop public key — so a forged guest's uploads open for nobody. | `grep -n "openDropPublicKey(kShare\|v1.open(SHARE_KEY, kShare" web/src/features/memory/share.ts` |

*Remembered, not checked:* that a member's client shows an undecryptable guest upload as a broken item rather
than failing the gallery. B1 does not depend on it; the docs phase words the residual without it.

## Decisions (for the recommendations)

1. **Crypto** — `v1.shareWriteToken(kShare, shareId) = HKDF-SHA256(kShare, salt = ∅, info = "veyq/v1/share-write:"
   + shareId, 32)`, beside `slugToken`, with a vector in `contracts/crypto/vectors/v1.json` and a case in
   `v1.conformance.test.ts`.
2. **Schema** (next free migration; re-run `ls backend/src/main/resources/db/migration | sort -V | tail -1`):
   `veyq_share.write_verifier TEXT NULL`.
3. **Create** — `CreateShareRequest.writeVerifier` (required, `contentEncoding: base64`, `maxLength: 64`, the
   `pwVerifier` shape) for **every** role (row 11). `ReissueShareRequest` (planned in `cr-reissue-share-link`) gets
   the same field for the new `K_share`. `updateShare` never takes it: `K_share` does not change.
4. **The gate** — `createShareIdentity` takes `X-Share-Write` (header parameter `ShareWrite`, the `SharePassword`
   shape). `SharingHandler.createIdentity` checks it **after** `activeShare` and the password gate, **before** the
   role check: a null verifier, a missing header, a malformed or a wrong token → `CreateIdentityOutcome.NotFound`.
   Constant-time compare (`MessageDigest.isEqual`), as `passwordCheck` does. No attempt limiter: the token has 256
   bits.
5. **Web** — `createShare` (and `reissueShare`) posts `writeVerifier = b64(SHA-256(shareWriteToken(kShare,
   shareId)))`; `createContributorIdentity` takes the fragment key and sends `xShareWrite`.
6. **Docs-in-code** — the `V31__share.sql` id comment is not edited (migrations are immutable); the new
   migration's header says why the id stays raw and what gates the write.
7. **With `cr-share-link-delete`:** its tombstone clears `write_verifier` with the ciphertext columns. Whichever CR
   lands second adds that line.

## Phases

1. **Phase 0 — the decision on record.** A new ADR (next free number at implement time: `ls docs/decisions | sort
   | tail -3`; sibling CRs also write ADRs), *"A guest write proves it holds the link's key"*: question 1's weighing,
   including why the id stays raw. ADR 0029 §2 and ADR 0030 point to it. · Done when: all `scripts/validate_*.py`
   green.
2. **Phase 1 — crypto + contracts.** Decision 1 (vector + function), Decision 3's fields, Decision 4's header and
   its paragraph on `createShareIdentity` (the `404` row names a missing or wrong proof). · `createShareRequest`
   gains a required field, so `web/` goes red at its call sites until Phase 3 and the backend's generated DTO
   until Phase 2 (changes/README.md § phases). · Done when: `npm run check` in `packages/crypto`, `npm run
   generate:api` and the contract guards green.
3. **Phase 2 — backend.** Decisions 2, 3 (store the field), 4. Every test helper that creates a share sends a
   verifier and every test that names a guest sends the matching token (row 14) — through one test helper
   (`ShareWriteProof`), not 23 copies. · Done when: `./gradlew check` green, including B1–B3.
4. **Phase 3 — web.** Decision 5. · Done when: `npm run check` green in `web/`, including W1.
5. **Phase 4 — docs.** `uc.sharing.share-contributor` and `uc.sharing.share-uploader`: the naming step says the
   client proves the key, with the coverage YAML; security-model § Sharing & share-boundary Trust & Safety: a line that a link's id alone opens no
   write, and § Threat model's dump row is unchanged (it is about reads); inbox #227 → done. · Done when: `python3
   scripts/validate_coverage.py` and all `scripts/validate_*.py` green.

## Definition of done

- **Baseline before the first edit** — run it, don't trust this line:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done` printed nothing on
  `7a34c57` at refine.
- **B1 — a database copy names no guest (red-first, live today).** In a new `ShareWriteProofSliceTest`:
  - **Setup:** a CONTRIBUTOR share S without a password. Read S's row as a database copy would.
  - **Action:** `POST /api/shares/S/identities` with a valid body and **no** `X-Share-Write`.
  - **Red today:** `201`. **The assertion expected to fail first** is the `404` status.
  - **After:** `404 share_not_found`, and `veyq_share_identity` has no row for S. Presenting the stored
    `writeVerifier` itself as the token → `404` (the copy holds the hash, not the token). A token derived for
    another share id → `404` (the binding).
  - **Positive control:** the same request with the right token → `201`, then an upload through the returned
    `contributorToken` succeeds as today.
  - **Bite:** in a snapshot copy, skip Decision 4's check → B1 red, the control green. Restore.
- **B2 — the gate survives a role flip, and holds for UPLOADER.** A share minted VIEWER is flipped to CONTRIBUTOR:
  no token → `404`, right token → `201`. An UPLOADER share behaves like B1. **Bite:** store the verifier only for
  CONTRIBUTOR/UPLOADER at create → the flip case goes red.
- **B3 — a null verifier fails closed.** A row with `write_verifier` null (set by SQL in the test) refuses every
  token with `404`, while an identity created before the null keeps uploading with its `contributorToken`.
  **Bite:** treat null as "no gate" → B3 red.
- **B4 — create requires the field.** `createShare` without `writeVerifier` → `400`, nothing stored.
- **C1** — the vector in `v1.json` matches `v1.shareWriteToken`; a different share id gives a different token.
  `v1.ts` is already in the crypto mutation scope (ADR 0045); a flipped label must be a killed mutant, not a green
  test — confirm with `grep -n '"src/v1.ts"' packages/crypto/stryker.mutate.json`.
- **W1** — `share.test.ts`, through the real `createShare` and `createContributorIdentity`, capturing the requests:
  `SHA-256(xShareWrite) == writeVerifier`; the verifier is not derivable from the share id alone (a fixture with the
  same id and another `K_share` differs). **Bite:** derive from the share id instead of `K_share` → red.
- Existing assertions carry over per test (row 14); `SharePasswordWriteGateTest` keeps passing with the token
  added: the password gate still answers first.

**Not proven:** anything against an active operator (it is the server); that no deployment logs request paths
(the proof makes such a log harmless for writes, which is the point); reads by an id holder stay possible and
yield ciphertext only (question 2).
