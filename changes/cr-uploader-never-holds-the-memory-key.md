---
id: cr-uploader-never-holds-the-memory-key
title: An uploader link seals to the Memory's drop key — the guest never holds K_mem
status: planned
satisfies: [uc.sharing.share-uploader]
adrs: ["0031", "0036", "0012", "0072", "0030", "0037"]
invariants: [operator-blind E2EE, API-first]
---

## Goal

An UPLOADER share resolves `symWrap(K_share, K_mem)` like every other share, and the guest's page unwraps and
holds `K_mem`. So "write-only" exists only as the server's refusal to serve content. A leaked uploader link plus
a database and store dump reads the whole shared Memory. Through `sealed_share_key` it also reads every sibling
share's `K_share` and, from those, their passwords: working viewer links.

[ADR 0031](../docs/decisions/0031-uploader-public-share.md) point 7 makes write-only cryptographic:
- **The Memory has a drop keypair.** Its private half is sealed under `K_mem`, its public half under `K_share`.
- **Each uploader identity seals under a key of its own,** and that key is wrapped to the drop public key.
- **A media's key path is fixed by its origin.**

The guest ends up holding `K_share` and its own identity key, nothing more.

## Context & constraints

- **The rule:** ADR 0031 point 7 and its two rejected alternatives. Also
  [ADR 0036](../docs/decisions/0036-editable-share-note-recoverable-share-key.md) (why an UPLOADER share has no
  wrap to keep) and [ADR 0012](../docs/decisions/0012-content-key-hierarchy.md).
- **What the spike measured** (`main` @ `a494436`, 2026-09-24):
  - The uploader page reads nothing back. Its own-view is local previews, and the resolve returns no media,
    nodes or identities.
  - The server checks nothing about any `K_mem`-sealed field.
  - The uploader's seals are the blob keys (`sealBlob`), media meta and EXIF (`sealMetadata`), and the
    identity's display name (`createContributorIdentity`).
  - The member-side opens bottom out in five unwrap sites
    (`grep -rn "KBLOB_CTX\|MEDIA_META_CTX\|MEDIA_EXIF_CTX\|share-identity-name" web/src`), which about twenty
    call sites reach
    (`grep -rnE 'unsealMeta\(|loadMediaExif\(|openBlobFromUrls\(|makeChunkProvider\(|openBucketName\(' web/src`).
  - **Trap:** `share.ts` detects a locked share by `!share.wrap`, so an UPLOADER share without a wrap would read
    as locked.
- **Size.** An `asymWrap` of a 32-byte key is about 1.2 KB (≈1.6 k base64 characters). The identity key's wrap
  is the only asymmetric field per identity, and every other field keeps its size. Size the new fields; do not
  widen the existing caps.
- **A password-protected uploader share** withholds the drop public key exactly where it withholds `wrap` today
  ([ADR 0037](../docs/decisions/0037-password-protected-shares.md)).
- **Order:**
  - **After** `cr-seal-binds-its-object`. The new purposes join the typed union with a vector each, and
    `createShareIdentity` takes a client-minted id there, which the identity key's binding needs.
  - **After** `cr-request-bounds-enforced`, so the new fields land under its guard.
  - Independent of the other #161 CRs.
- **A stored-format change, before V1.** Nothing is migrated. The owner's development instance needs a reset;
  tell them, and do not wipe their database yourself.

## Decisions

- **The drop keypair** is a hybrid (X25519 + ML-KEM-768) keypair of the shape `asymWrap` already targets.
  - The owner's client mints it with the Memory's first UPLOADER share.
  - The private half is sealed under `K_mem` in a **dedicated node field**, bound to (scopeId, nodeId). It is
    not inside node-meta: node-meta is a whole-blob read-modify-write, and date or location edits would then
    carry the key. The field is served wherever the node is served, including a viewer or contributor share's
    nodes, so every reader of the Memory can open uploads.
  - The public half is sealed under `K_share` on each UPLOADER share. The resolve serves it **instead of**
    `wrap`.
- **The identity key.** The guest's client mints a random `K_up` per identity. `createShareIdentity` carries
  `asymWrap(dropPublic, K_up)`, bound to (scopeId, nodeId, identityId). Everything that identity seals keeps its
  purpose and binding and changes only its key, to `K_up`: blob keys, media meta, EXIF, the display name.
- **The reader resolves the key by origin, in one place.** A media whose identity carries a wrapped `K_up` opens
  through the drop key; every other media opens through `K_mem`. The call sites ask one resolver; they do not
  each branch.
- **A member's edit of an uploaded media's metadata re-seals under `K_up`,** so no media changes path.
- **Unchanged:**
  - VIEWER and CONTRIBUTOR shares. A contributor's uploads stay under `K_mem`, which it legitimately holds.
  - The role rule of `updateShare` (UPLOADER never flips).
  - The own-view and delete-own of ADR 0031 points 2–4.
- **Purposes.** The union gains the drop private key (under `K_mem`), the drop public key (under `K_share`, a
  purpose-only key by ADR 0072 Decision 2) and the identity-key wrap (asymmetric). Each gets a vector. The
  implementer names them and states the names in the union.

## Phases

- [ ] 1 contracts (crypto) — the three purposes and their vectors.
      · Done when: `npm run check` in `packages/crypto` green, all `scripts/validate_*.py` green.
- [ ] 2 contracts (api) — the node field and its reads, the share's sealed drop public key on create and resolve
      (with no `wrap` for UPLOADER), and the wrapped identity key on `createShareIdentity` and `ShareIdentity`.
      Locator:
      `grep -n 'PublicShare:\|CreateShareRequest:\|CreateShareIdentityRequest:\|ShareIdentity:\|ShareNode:\|wrappedMeta' contracts/api/sharing.openapi.yaml contracts/api/content.openapi.yaml`.
      · Done when: `npm run generate:api` + `npm run check` (web) green. The backend is red until phase 3,
      which is expected.
- [ ] 3 backend — store and serve the three fields. The UPLOADER resolve carries the drop key and no wrap.
      Locator: `grep -rlE '"wrap"|\.wrap\b|\bwrap =|sealedDisplayName|sealed_display_name' backend/src/main/kotlin/com/veyq/app/features/sharing backend/src/test`
      (the node field adds `content`'s node reads, found by the phase-2 locator).

      **Bite:** `ShareUploaderSliceTest` asserts today that the UPLOADER resolve returns the wrap. It flips to
      "returns the drop key and no `wrap`". Control, in the same class: a VIEWER resolve still returns the wrap.
      · Done when: `./gradlew check` green.
- [ ] 4 web — the drop keypair at share creation, the guest's identity key and seals, the key resolver, the
      locked-share detection.
      Locators: the two spike greps above, and `grep -rnE 'resolveShare|addSharePhoto|createContributorIdentity|rootMemoryKey|share\.wrap' web/src`.

      **Bites — live defects on the untouched tree. Write them first and see them red:**
      - Resolving an UPLOADER share yields `K_mem` today (`rootMemoryKey`). After the fix the resolved session
        holds no Memory key. Assert on the type or return, not on a variable name.
      - A blob key an uploader sealed **opens under `K_mem`** today. After the fix it does not (anchor: the AEAD
        refusal on `K_mem`). Control: it opens through the drop key and `K_up`.
      - The curator's gallery and a viewer link's page both open the guest's upload. This is a positive, and it
        is the proof that the key path reaches every reader.

      · Done when: `npm run check` (web) green.
- [ ] 5 e2e — in the `run_web_e2e.py` harness, a guest uploads through an UPLOADER link, and the curator's
      client then opens that media. Assert that the resolve the guest received carried no wrap. Extend
      `shareContributor.e2e.test.ts`'s setup if it fits; otherwise add a suite.
      · Done when: `python3 scripts/run_web_e2e.py` green, and its log shows the suite executed rather than
      skipped.
- [ ] 6 docs + coverage:
      - `uc.sharing.share-uploader` gains *"The guest's client never holds the Memory's key: its uploads are
        sealed under its own identity key, wrapped to the Memory's drop key"*, covered by phases 4 and 5.
      - Delete ADR 0031 § What is still open. Flip the row in
        [security-model § What holds today](../docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve).
      - The promise this CR makes true is the use-case's own: its summary and its EN+DE *What this means for
        you* section, which the wiki is assembled from, say a guest cannot see what is already in the Memory
        (`grep -n -i "cannot see\|nicht sehen" docs/features/sharing/use-cases/uc.sharing.share-uploader.md`).
        Check that it now reads true without an edit.
      - Tell the owner their development instance needs a reset.

      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- Every phase done. Baseline before the first edit:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`.
- The two negative web bites were seen red on the untouched tree, and the e2e ran rather than skipped.
- No UPLOADER code path unwraps `K_mem`:
  `grep -rn "symUnwrap(.*SHARE_CTX\|'share-key'" web/src --exclude-dir=generated` shows the unwrap guarded to
  VIEWER and CONTRIBUTOR.

**Not proven:** anything about an operator that serves the guest a tampered page. The no-install page is
operator-served, and passive-safe only ([ADR 0026](../docs/decisions/0026-client-trust-tiers.md)).

## Retrospective
