---
id: cr-seal-binds-its-object
title: Every seal binds the object it is stored as — one typed seal, client-minted ids, a vector per purpose
status: planned
satisfies: [en.account.key-model, uc.memory.upload]
adrs: ["0072", "0017", "0020", "0024", "0027"]
invariants: [operator-blind E2EE, API-first, self-hostable by default]
---

## Goal

Every AEAD seal in veyq authenticates only a static purpose string, so the blind server can serve any
ciphertext of that purpose for any object under the same key. The worst case is a confidentiality break: it
serves shared Memory S's `K_mem` wrap on private Memory X's row, and every upload into X lands under S's key.
This CR makes every seal bind the object it is stored as, per
[ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md). The mechanism is a typed `seal(context, …)` in
`@veyq/crypto` as the only way to seal, clients minting the ids of what they create, and one vector per
purpose. It is a stored-format change, and it is being made before V1 because afterwards it would be a suite
`v2` plus re-encryption.

## Context & constraints

- **The rule:** [ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md). Its Decisions 1–6 are this
  CR's specification. Decision 2 lists the unique-key seals that bind their purpose only, and Decision 4 is why
  parents are not bound.
- **The byte definition comes first** ([ADR 0017](../docs/decisions/0017-crypto-primitives-and-post-quantum-strategy.md)
  rule 4): a vector is added before the code that must match it.
- **Freshness is out of scope** (ADR 0072 Decision 5, inbox #161): this CR binds epochs and versions but does
  not make a client refuse an old one.
- **Every existing ciphertext stops opening.** Nothing is migrated. The owner's development instance has to be
  reset after phase 4. **Say so, and do not wipe their database yourself**: their IntelliJ backend runs against
  it.
- **Order:**
  - **After** `cr-inert-store-responses`, `cr-request-bounds-enforced`, `cr-upload-frame-size-contract` and
    `cr-upload-windowed-presign`. All four reshape the upload schemas and `media.ts` that this CR also changes,
    and they were refined earlier against the current tree. The new id fields also land under
    `scripts/validate_request_bounds.py`.
  - **After** `cr-scope-access-gate` (#157). It puts one gate in front of every scope-addressed handler,
    including the eight creates this CR changes, so building on its handlers avoids rebasing both.
  - **Before** `cr-admission-is-a-key-holder-act`, which signs over the bound grant and reuses this CR's
    canonical encoding.
  - Independent of `cr-cold-credential-proves-the-secret-key`.
- **Settles inbox #32** (ADR 0072 Decision 6).
- **Measured against `claude/happy-torvalds-321ddf` @ `5be8bfe` on 2026-09-23.** So spot-check one row — the
  purpose table's `content-key` row, since the goal leans on it hardest — rather than rebuilding the table.

## Decisions

- **The purposes and what each one binds.** This table is the union. Ids are the canonical lowercase UUID
  string; epochs and versions are unsigned integers. "Purpose only" means the key is unique to the object
  (ADR 0072 Decision 2).

  | Purpose | Sealed under | Binds |
  |---|---|---|
  | `node-name` | scope epoch key | scopeId, nodeId, epoch |
  | `content-key` | scope epoch key (top level) or the parent's `K_mem` (chapter) | scopeId, nodeId, epoch |
  | `node-meta` | the node's own `K_mem` | scopeId, nodeId |
  | `blob-key` | `K_mem` | scopeId, owner (mediaId, or nodeId for a cover), blobId, role (`ORIGINAL`/`THUMBNAIL`/`PREVIEW`/`COVER`) |
  | `media-meta`, `media-exif` | `K_mem` | scopeId, mediaId |
  | `bucket-name` | `K_mem` | scopeId, nodeId, bucketId |
  | `share-identity-name` | `K_mem` | scopeId, shareId, identityId |
  | `share-key-recovery` | `K_mem` | scopeId, nodeId, shareId |
  | `item-favorites` | `K_SC` at `E_0` | scopeId, nodeId, the marking member's userId |
  | `favorites` | Master Key | scopeId |
  | `member-name` | tenant scope key | scopeId, userId |
  | `scope-grant` (asym) | recipient identity | scopeId, epoch |
  | `scope-epoch` | `E_n` | scopeId, n |
  | `group-name` (new; was `node-name`) | `K_name` | scopeId, nameEpoch |
  | `group-name-key` (asym) | invitee identity | scopeId, nameEpoch |
  | `space-icon` | `K_icon` | scopeId, iconEpoch |
  | `identity-pins`, `device-register` | Master Key | version |
  | `share-key`, `share-memory-name` (new; was `node-name` under `K_share`), `share-note`, `share-name`, `share-password` | `K_share` | purpose only |
  | `master-key`, `identity-priv`, `pairing-grant` | KEK / Master Key / ephemeral key | purpose only |

  Two cases stay outside the union, with the reason in a comment at the line. Chunk frames keep
  `index ‖ total` under their per-blob key. The two WebCrypto seals gain their purpose as `additionalData`,
  because their key is a non-extractable `CryptoKey` that `@noble` cannot take.
- **An opener binds from the address, never from the envelope.** The ids come from the request it made (the
  route's `nodeId`, the row it looked up, the scope it opened), not from fields of the response that carry the
  ciphertext. Where a binding field is missing from a read schema, phase 2 adds it. A bucket row is the
  likely case: a contributor bucket's sealed name needs `shareId` + `identityId`, and `BucketSummary` has
  `ownerKey`. Check it with
  `python3 -c "import yaml;print(yaml.safe_load(open('contracts/api/media.openapi.yaml'))['components']['schemas']['BucketSummary'])"`.
- **Client-minted ids on eight creates:** `createNode`, `startBlobUploads` (the blob ids and the media id the
  blobs belong to), `completeMedia` (that same media id), `createBucket`, `createShare`,
  `createShareIdentity`, `createGroup`, `createPersonalScope`. The request carries a UUIDv4. If the id is taken
  the server answers `409`, with the same answer whether the taken id is in the caller's scope or another's,
  and never overwrites. A retry with the same id and the same body is idempotent where the handler can tell
  (the implementer decides per create and states it in the contract description). The tenant scope's id is
  already the tenant id, so it is known before its birth.
- **Server-minted ids that stay server-minted:** memberships, epoch rows, jobs, upload sessions and S3
  storage keys. No seal binds them. Classify every hit of
  `grep -rn 'randomUUID()' backend/src/main/kotlin/com/veyq/app/features/{content,media,sharing,spaces}`
  as one of the eight creates or one of these. A hit that is neither is a question back to refinement.
- **A grant states its epoch.** Today the delivery worker does not say which epoch it wrapped, and the server
  records the scope's current epoch as `grantedEpoch` (`grep -n 'grantedEpoch' backend/src/main/kotlin/com/veyq/app/features/tasks/TasksStore.kt`).
  With the epoch bound, a rotation between claim and completion would record an epoch the grant does not open
  under. So a grant-delivery completion (`completeTask`) **states the epoch it sealed**, and the server refuses
  one that is not the scope's current epoch (the job re-runs). A `rotateScope` request already states the epoch
  it creates, and the server refuses it unless that is `current + 1`. That is
  [`cr-rotation-states-its-epoch`](cr-rotation-states-its-epoch.md), which lands first. This CR binds that
  stated epoch into the link's and the grants' seals and adds no field of its own to `rotateScope`.
- **The package's public surface.** Split `packages/crypto/src/v1.ts`:
  - The primitives move to a module that `package.json` `exports` does not list: `symWrap`, `asymWrap`, their
    unwraps, `aeadSeal`/`aeadOpen`, `wrapAad`. The conformance test imports them by relative path.
  - `@veyq/crypto/v1` keeps everything that is not a seal: KDFs, identity, fingerprint, tokens, frames. It
    gains `seal`/`open`/`sealTo`/`openAs` over the union.
  - `journalSeal`/`journalOpen` are deleted: empty AAD and no caller (`grep -rn 'journalSeal\|journalOpen' web/src admin-web/src packages`).
  - The vectors `invite-temp-key` and `session-token` are deleted: no consumer.
  - `dedupToken` is not a seal and stays out of scope.

## Phases

- [ ] 1 contracts (crypto) — the union, its canonical encoding, the four functions, and **one vector per
      purpose** in `v1.json` (a new section; the old `wrap.*` contexts migrate into it). Plus the conformance
      test that compares the union's purposes with the vector set in **both** directions. The raw primitives
      stay exported in this phase, because web still calls them.
      **Bite, by injection** (snapshot-and-restore): a purpose added to the union without a vector turns the
      test red, and so does a vector with no purpose. Read which direction reddens.
      · Done when: `npm run check` in `packages/crypto` green, all `scripts/validate_*.py` green.
- [ ] 2 contracts (api) — the eight client-minted ids, the stated epoch on the grant-delivery completion, and any
      binding field a read schema lacks. `rotateScope` already states its epoch (`cr-rotation-states-its-epoch`).
      The ops are on existing tags, so the backend will not compile until phase 3; that is expected
      (`changes/README.md` § Phases).
      Locator: `grep -n 'operationId: \(createNode\|startBlobUploads\|completeMedia\|createBucket\|createShare\|createShareIdentity\|createGroup\|createPersonalScope\|completeTask\)' contracts/api/*.yaml`.
      · Done when: `npm run generate:api` + `npm run check` (web) green, all `scripts/validate_*.py` green.
- [ ] 3 backend — take the ids, refuse taken ones, store the stated epochs and refuse stale ones.
      **Bites, written before the fix:**
      - A create carrying an id that exists in **another** scope → `409`, and that object is unchanged. Control:
        a fresh id → `201`.
      - A grant-delivery completion stating an epoch below current → refused, no grant written. Control:
        current epoch → written.
      · Done when: `./gradlew check` green.
- [ ] 4 web — every seal and open through the union; ids minted at the create sites; openers bind from the
      address; the WebCrypto `additionalData`. Then remove the raw primitives from the package's `exports`.
      Locators:
      - Sites, first angle: `grep -rnE '\b(symWrap|symUnwrap|asymWrap|asymUnwrap|sealFrame|openFrame|aeadSeal|aeadOpen)\(' web/src admin-web/src packages/crypto/src | grep -v -e /generated/ -e packages/crypto/src/v1.ts`
        (tests included on purpose; `web/src/test/e2eBootstrap.ts` is one).
      - Sites, second angle (context strings, including the literals that bypass constants):
        `grep -rnoE "'(master-key|identity-priv|content-key|blob-key|node-name|node-meta|media-meta|media-exif|bucket-name|share-[a-z-]+|scope-grant|scope-epoch|group-name-key|space-icon|member-name|identity-pins|device-register|favorites|item-favorites|pairing-grant)'" web/src admin-web/src packages/crypto/src`.
        The two lists must describe the same sites. A disagreement is the finding.
      - WebCrypto: `grep -rn 'subtle\.\(encrypt\|decrypt\)(' web/src admin-web/src`.

      **Bites — live defects on the untouched tree. Write them first and see them red:**
      - **The upload chain.** The node list serves Memory S's `wrappedContentKey` on Memory X's row. Resolving
        X's key must refuse; today it returns `K_mem(S)`. Control: X's own wrap → X's key.
      - A thumbnail ref moved from media A to media B does not open under B. Control: under A it does.
      - Scope A's grant served in scope B's row does not open. Control: in A's row it does.
      - Node-meta sealed at one node, served for another → refused. Control: its own node → opens.

      Anchor every negative on the specific refusal, not on "it threw" (`changes/README.md` § port its
      defences).
      **Then the structural bite:** with the exports removed, `import { symWrap } from '@veyq/crypto/v1'` in a
      web file fails `npm run check`, checked by snapshot-and-restore.
      · Done when: `npm run check` in `packages/crypto`, `web` and `admin-web` green, and
      `python3 scripts/run_web_e2e.py` green, since the format changes end to end.
- [ ] 5 docs + coverage
      - `uc.memory.upload.yaml` gains one criterion: *"An upload into a Memory seals only under that Memory's
        own key; a server that serves another Memory's key wrap on its row makes the upload refuse, not land
        under the other key"*, covered by the upload-chain bite.
      - Sweep the durable docs for a sentence this CR made false:
        `grep -rn "symWrap\|asymWrap\|'node-name'\|context string" docs/ --include=*.md`.
      - Flip the row "serves a ciphertext as another object's" in
        [security-model § What holds today](../docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve).
      - Tell the owner their development instance needs a reset.

      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- Every phase is done. Baseline before the first edit:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`.
- The four web bites were seen red on the untouched tree, and the two phase-1 bites were seen red by
  injection.
- `package.json` `exports` of `packages/crypto` no longer reaches a raw wrap primitive, and the phase-4
  first-angle locator returns only `packages/crypto/src/` internals and the conformance test.
- Every vector's purpose is a live union member, and every member has a vector. The phase-1 test holds this;
  it is not a hand count.

**Not proven:** freshness. A server can still serve an old but authentic ciphertext (ADR 0072 Decision 5), and
this CR adds no test that pretends otherwise.

## Retrospective
