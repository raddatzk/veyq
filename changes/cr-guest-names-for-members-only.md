---
id: cr-guest-names-for-members-only
title: A guest's name opens for the Memory's members only — no share link reads it
status: planned
satisfies: [uc.memory.buckets, uc.sharing.share-contributor, uc.sharing.share-uploader]
adrs: ["0030", "0031", "0034", "0012", "0024", "0072", "0037"]
invariants: [operator-blind E2EE, API-first]
---

## Open for the owner

These are product calls. The CR is written for the recommendation in each, and no phase starts until they are
answered.

1. **A mark for guest uploads on a guest's page.** Today a guest's page shows no attribution at all (measured
   below). Should a viewer or contributor see an anonymous *"added by a guest"* mark on media a guest added?
   **Recommendation: no, keep today's page.** A mark needs a per-media origin signal, and the name is what you
   asked to hide. The origin (guest or member) is a second, smaller fact nobody has asked for.
2. **Does a CONTRIBUTOR see its own name?** Today it never does: the pre-screen takes the name, seals it, and
   the device keeps only the identity id, the token and an uploader's own key (`ContributorSession` has no name
   field). **Recommendation:
   no change.** If it is wanted later, it stays on the device and is never read back from the server.
3. **The pseudonymous link between a guest's uploads.** The resolve stamps every guest-added media with its
   `identityId`. A guest can therefore see that *these twelve photos came from one person*, without learning
   who. **Recommendation: serve `identityId` only on an uploader's media.** The key chain needs it there, and
   the link is inherent anyway, because one `K_up` opens all of that identity's media. Serve it as null on a
   contributor's media: on the guest side it only feeds the uploader key map, which finds nothing for a
   contributor (measured below).
4. **The name pre-screen says who will see the name.** Today it says nothing about that. **Recommendation:
   one sentence, EN and DE:** *"Only the people this Memory belongs to see your name."* It is the promise this
   CR makes true, and a guest decides what to type on the strength of it.
5. **Camera metadata in the original file.** A guest can download an original, and no code strips its EXIF
   (measured below). A camera's *Artist*/owner tag therefore reaches the guest. That is not the display name
   this CR is about. **Recommendation: a separate inbox row, not this CR.**

## Goal

A VIEWER or CONTRIBUTOR link holder can read the name every guest gave. That covers the link's own
contributors and every uploader whose media the link shows. The resolve serves those names, and the link
yields the keys they are sealed under. The owner's call of 2026-09-28 is that holders of these links must not
see uploader names, while the Memory's members keep seeing them
([ADR 0030](../docs/decisions/0030-contributor-public-share.md) point 8).

This CR takes the names out of reach in two independent layers:
- **Cryptographic.** A guest seals its name to the Memory's **name keypair**. Its private half is sealed under
  `K_SC`, which no share carries.
- **The response.** The resolve stops serving any name.

The server still sees only ciphertext, as before. This is about what a link holder can decrypt.

## Context & constraints

- **The rule:** ADR 0030 point 8. It supersedes, once built:
  - ADR 0030 point 2's *"seals it under `K_mem`"* and point 5's *"returns … each share identity's sealed name"*;
  - [ADR 0031](../docs/decisions/0031-uploader-public-share.md) point 7's *"the display name"* among what an
    uploader seals under `K_up`;
  - [ADR 0034](../docs/decisions/0034-media-uploader-buckets.md)'s `K_mem`-sealed contributor-primary name.
- **The tier:** [ADR 0012](../docs/decisions/0012-content-key-hierarchy.md) (`K_SC` stays with members, and a
  share wraps only `K_mem`) and [ADR 0024](../docs/decisions/0024-scope-key-rotation.md) (content is tagged
  with the epoch it was sealed under; members derive older epochs by walking the epoch chain).
- **The bindings:** [ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md). A new purpose joins the
  typed union with a vector.
- **A password-protected share** withholds the name public key wherever it withholds `wrap` and the drop key
  ([ADR 0037](../docs/decisions/0037-password-protected-shares.md)).
- **Order:** after `cr-uploader-never-holds-the-memory-key` (landed `67db84e`). Independent of every live CR
  (`ls changes/`).
  - *Remembered, not checked:* if `cr-trust-state-never-goes-backwards` lands first, the epoch the name private
    key is read at should pass its per-scope floor like any epoch-tagged seal. Re-check at implement time.
- **A stored-format change, before V1.** Nothing is migrated. Tell the owner their development instance needs a
  reset; do not wipe it yourself.

### Inventory — measured on `main` @ `9e90e0e`, 2026-09-28

What a guest can open today, per link role. Each row is *read, not run*; the commands re-run it.

| Link holder | Names it can open today | How |
|---|---|---|
| VIEWER | every UPLOADER guest whose media the link shows | The resolve serves the identity with its name sealed under `K_up`. The node's drop private key is under `K_mem`, which opens the identity's wrapped `K_up`. |
| VIEWER, once a CONTRIBUTOR link | also every guest who named itself on it | The role flip keeps the identities, and the resolve serves the link's own ones, sealed under `K_mem`. |
| CONTRIBUTOR | every other guest on the same link, its own name, and every UPLOADER as above | Same two paths. |
| any link | another CONTRIBUTOR link's guests: **not served**, but sealed under `K_mem` | So a link plus a database dump reads them. That is the ADR 0031 point 7 threat. |
| any link | the Memory's **members**: nothing | No guest response names a member. |

The commands behind it:

- **The resolve serves the identities, names included.** `grep -n "fun readerIdentities" -A 5 backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt`
  shows the link's own identities plus every identity with a wrapped key. `grep -n "sealedDisplayName = it" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingResource.kt`
  maps the name into the response. `resolve` hands both to every `canView` role
  (`grep -n "readerIdentities(shareId" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt`).
- **What the name is sealed under.** `grep -n "const nameKey" web/src/features/memory/share.ts` shows `K_up`
  for an uploader and `K_mem` for a contributor. Two tests pin the leak as intended behaviour, and both flip in
  this CR:
  - `web/src/features/memory/share.test.ts`, the test that opens `req.sealedDisplayName` under `kMem`;
  - `web/src/features/memory/shareUploader.test.ts`, which opens it under `kUp`.

  On the backend, `ShareContributorSliceTest` asserts the resolve serves
  `identities.find { … }.sealedDisplayName`.
- **The role flip keeps the identities.** `grep -rn "ShareIdentityEntity.delete" backend/src/main` finds only
  the scope purge, and `updateShare` only sets `share.role`
  (`grep -n "share.role = role" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt`).
- **Only the resolve carries anything about an identity.** The guest-reachable operations are the ones with no
  `bearerAuth` (`grep -n "operationId\|bearerAuth" contracts/api/sharing.openapi.yaml`). Of their response
  schemas, only `PublicShare.identities` carries an identity. `ShareTerms`, `ShareMediaBatchResponse`, the
  upload responses and `ShareBlobDownload` carry ids, URLs and terms only. `ShareMedia` has no member id.
  `grep -n "ownerKey\|userId\|username\|createdBy" contracts/api/sharing.openapi.yaml` finds nothing in a
  guest schema.
- **No guest-side code reads a name.** `grep -rn "identityNameContext\|share-identity-name" web/src --exclude-dir=generated | grep -v test`
  finds the seal site in `share.ts` and the member-side bucket open in `mediaBuckets.ts`, nothing else. The
  resolve's identities feed only the uploader key map (`grep -n "share.identities" web/src/features/memory/share.ts`),
  and the contributor coverage file already records that *"that contract field has no web reader"*.
  `ShareView.vue` never reads `identityId` (`grep -n "identityId" web/src/views/ShareView.vue` finds nothing).
- **Members read names in one place.** `grep -rn "openBucketName(" web/src --exclude-dir=generated | grep -v test`
  finds `MemoryBuckets.vue`. The ciphertext reaches it through the bucket list, where
  `MediaStore.primarySealedNameFor` copies the identity's sealed name onto the guest's primary bucket
  (`grep -n "primarySealedNameFor\|contributorIdentityOf" backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt`).
  A primary is never renamed (`grep -n "isPrimary) return RenameBucketOutcome" backend/src/main/kotlin/com/veyq/app/features/media/MediaHandler.kt`).
- **Size.** An `asymWrap`/`sealTo` is `0x01 ‖ ct(1120) ‖ nonce(12) ‖ aead`, so 1 149 bytes plus the plaintext
  (`sed -n 93,105p packages/crypto/src/primitives.ts`). A name sealed that way does not fit the 512-character
  caps of `ShareIdentity.sealedDisplayName`, `CreateShareIdentityRequest.sealedDisplayName` and
  `BucketSummary.sealedName`. It does fit 2 048, the cap `wrappedIdentityKey` already has. Size the new fields;
  do not widen the old ones.
- **Camera EXIF (owner question 5).** `grep -rln -i "stripExif\|strip.*metadata\|removeExif" web/src packages`
  finds nothing, so originals keep their tags. A guest's lightbox shows no EXIF panel, because it has no loader
  and is read-only (`grep -n "const exifOf" -A 1 web/src/features/memory/components/MediaLightbox.vue`).

## Decisions

- **Why the cryptographic layer, not only the response.** Dropping the field from the resolve stops a link
  holder who has only the link. But the name stays sealed under keys every VIEWER and CONTRIBUTOR link yields:
  `K_mem` directly, and `K_up` through the drop private key. So a link plus a dump, or any future response
  that carries the ciphertext, reads it again. ADR 0031 point 7 refused exactly that trade for content, and a
  guest's name gets the same treatment. The response layer stays as well, because a name a guest cannot open
  still has no reason to reach it.
- **The name keypair belongs to the Memory, as the drop keypair does.** A hybrid (X25519 + ML-KEM-768)
  keypair, minted by a member's client with the Memory's first share of **any** role. Every share then carries
  its public half, so the VIEWER↔CONTRIBUTOR flip stays a plaintext authorization change with no re-crypto, as
  cr-0119 made it.
  - **Private half:** sealed under `K_SC` at the scope's current epoch, bound to (scope, node, epoch). It is a
    node field of its own, never inside node-meta, and it is set once. A second creator racing the first seals
    to the stored key, read fresh, as `share.ts` does for the drop key. It is served on the member reads the
    reader needs: the node read, for share creation, and the bucket list, for the names. It is never served on
    `ShareNode`.
  - **Public half:** sealed under `K_share` on every share (purpose-only, like `drop-public-key`). The resolve
    serves it to CONTRIBUTOR and UPLOADER roles only.
  - **Epochs.** The private half is not re-sealed on rotation: members derive the old epoch from the chain,
    as for node names. A removed member who keeps that key and a dump reads later guests' names until the
    keypair is re-minted. That is the same class as `K_mem` not rotating on removal, and it is stated, not
    fixed, here.
- **The guest seals its name to the name public key,** bound to (scope, share, identity). This applies to a
  contributor and to an uploader alike. Nothing of the name is sealed under `K_mem` or `K_up` any more. The
  symmetric `share-identity-name` purpose is **retired**, not kept beside the new one, so no code path can seal
  a name the old way (see the guard in the DoD). The implementer names the new purposes and states the names
  in the union.
- **The resolve serves no name.** Its identity list is exactly the UPLOADER identities with media in the
  served subtree, each as `{identityId, wrappedIdentityKey}`. A link's own contributor identities are no
  longer served: nothing on the guest side reads them. Whether `ShareMedia.identityId` stays on a
  contributor's media is owner question 3.
- **Members read names through the bucket list,** now through the name private key:
  `K_SC(epoch)` → name private key → `openAs`. The copy onto the primary bucket carries the new ciphertext in a
  field sized for it; sub-bucket names keep `sealedName` and its cap. The implementer decides whether the
  copy stays or the bucket list joins the identity; the reader is the same either way.
- **Unchanged:** the uploader's `K_up` chain for media (ADR 0031 point 7); the contributor's uploads under
  `K_mem`; the own-view and delete-own; the mandatory name; the token.

## Phases

- [ ] 0 red — before any edit, on the untouched tree, write the two live-defect bites from the DoD (backend
      **B1**, web **B2**) and record their red: the failing assertion line of each goes into the Retrospective.
      Do not commit them red. Each is re-pointed at its seam in the phase that fixes it.
      · Done when: both reds are recorded, and the tree is unchanged apart from the two uncommitted tests.
- [ ] 1 contracts (crypto) — the name keypair's two seal purposes (the private half under `K_SC`, the public
      half under `K_share`), the name's `sealTo` purpose, and a vector for each. Retire `share-identity-name`.
      Locator: `grep -rn "share-identity-name\|drop-public-key\|drop-private-key" packages/crypto contracts/crypto`.
      · Done when: `npm run check` in `packages/crypto` green, all `scripts/validate_*.py` green.
- [ ] 2 contracts (api):
      - the node field (the sealed name private key and its epoch) on the member node read and the bucket list;
      - the sealed name public key on `CreateShareRequest` and, for CONTRIBUTOR and UPLOADER, on `PublicShare`,
        plus the optional private key on the first share;
      - the name field on `CreateShareIdentityRequest`, replacing `sealedDisplayName`;
      - `PublicShare.identities` without any name;
      - the bucket list's guest-name field.

      Sized fields, not wider caps; `validate_request_bounds.py` and `validate_maxlength_storage.py` apply.
      Locator: `grep -n "PublicShare:\|ShareIdentity:\|CreateShareIdentityRequest:\|CreateShareRequest:\|BucketSummary:\|BucketsResponse\|NodeSummary:\|sealedDropPrivateKey" contracts/api/*.yaml`.
      · Done when: `npm run generate:api` succeeds, the contracts guards are green, and `npm run check` in web
      and admin-web is red only at the request shapes this phase changed. That red is expected until phase 4
      (changes/README.md § Phases only above real size).
- [ ] 3 backend:
      - store and serve the fields;
      - set-once on the node for the private half;
      - `readerIdentities` returns uploader identities only, and the resolve maps no name;
      - withhold the public key in a locked response;
      - the bucket list serves the new name field.

      Migration: new columns, and `sealed_display_name` retired or renamed. No data is carried over.
      Locator: `grep -rlnE "sealedDisplayName|sealed_display_name|readerIdentities|primarySealedNameFor|sealedDropPrivateKey" backend/src`.
      It includes the tests: `ShareContributorSliceTest`, `ShareUploaderSliceTest`, `ShareIdentityBoundTest`,
      `ClientMintedIdSliceTest`, `SharePassword*`, `ShareLinkStorageLimitSliceTest`, `ShareUploadWindowSliceTest`,
      `ShareNoteSliceTest` and `TenantStorageQuotaSliceTest` post identities.

      Re-point **B1** here and see it green.
      · Done when: `./gradlew check` green.
- [ ] 4 web:
      - share creation mints or reads the name keypair (mirror the drop-key path in `share.ts`, including the
        race);
      - `createContributorIdentity` seals to the name public key for both roles;
      - `resolveShare` reads the public key for contributable roles;
      - `openBucketName` opens a guest primary's name through `K_SC` → the name private key, and
        `MemoryBuckets.vue` gets the scope key it now needs;
      - the owner's answers to questions 1–4.

      Locators:
      - `grep -rnE "createContributorIdentity|identityNameContext|openBucketName|bucketNameContext_|sealedDropPrivateKey|mintDrop|share\.identities" web/src --exclude-dir=generated`
      - `grep -n "defineProps\|memoryKey" web/src/features/memory/components/MemoryBuckets.vue`

      Re-point **B2** here and see it green, together with the positive control.
      · Done when: `npm run check` (web) green.
- [ ] 5 e2e — in the `run_web_e2e.py` harness:
      - `shareContributor.e2e.test.ts`: a second guest on the same CONTRIBUTOR link resolves, and the raw
        resolve JSON contains no name ciphertext of the first guest. The curator's buckets panel still opens
        the first guest's name, which is the positive.
      - `shareUploader.e2e.test.ts`: a VIEWER link of the same Memory resolves the uploader's media and carries
        no name. The curator still opens the uploader's name.

      Fix the comment at the top of `shareContributor.e2e.test.ts` that explains why the unseal avoids
      `PublicShare.identities`.
      · Done when: `python3 scripts/run_web_e2e.py` green, and its log shows both suites executed rather than
      skipped.
- [ ] 6 docs + coverage:
      - **The new criterion lands in `uc.memory.buckets`:** *"A guest's name — a contributor's or an uploader's
        — opens only for the Memory's members: it is sealed to the Memory's name key, whose private half is
        under `K_SC`; no share link is served it or holds a key that opens it."* Covered by B1, B2 and the two
        e2e suites. It lands there because the buckets panel is the one place a name is shown, and the property
        has two triggers (contributor and uploader naming). Hanging it under either sharing use case would put
        the same refs under two.
      - **Edited:**
        - `uc.sharing.share-contributor`: flow step 3, the sequence diagram's seal step, Quality notes, the
          EN+DE *What this means for you* (add that other visitors do not see the name), and its coverage
          criteria `sealed-name-anonymous-identity` and `direct-append-attribution`;
        - `uc.sharing.share-uploader`: step 3, and its *"The price: …"* sentence, which is **deleted**, plus its
          criterion `guest-never-holds-the-memory-key`;
        - `en.memory.buckets`, including its title;
        - ADR 0034's contributor-primary name;
        - ADR 0030 points 2, 5 and 7: rewrite them to the built state and drop point 8's *"Not built yet"*;
        - ADR 0031 point 7's list.
      - **Flip** the row *"reads the names a Memory's guests gave"* in
        [security-model § What holds today](../docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve)
        to **Cannot**, and correct its line on bucket and uploader names, which says they *stay sealed under
        `K_mem`*.
      - Sites: `grep -rnE "sealedDisplayName|share-identity-name|(contributor|uploader|identity)('s)? (sealed |display )?name|names stay sealed|seals it under .K_mem" docs contracts backend/src web/src packages scripts wiki`.
        Re-run it at this phase, because phases 1–4 write new prose.
      - Tell the owner their development instance needs a reset.

      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- Every phase done. Baseline before the first edit, measured green on `9e90e0e` at refine:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`.
- **B1 — the response layer, the crypto layer held off.** A backend slice test, a live defect: red on the
  untouched tree. The Memory has:
  - a CONTRIBUTOR link with two named guests;
  - a second CONTRIBUTOR link with a guest and a media;
  - an UPLOADER guest with a media;
  - a VIEWER link;
  - a VIEWER link that was a CONTRIBUTOR link with a guest.

  For each resolve, no served identity carries a name field. The bytes are opaque to this test, so it holds
  whatever the crypto does.
  - **Positive controls on the same predicate:** the VIEWER resolve's identity set is **exactly** the
    uploader's identity, with its `wrappedIdentityKey`. So an empty list cannot pass vacuously. The member
    bucket list still carries a name ciphertext for both guests.
  - **Anchor:** the absence of the field on a present identity, never an empty response.
- **B2 — the crypto layer, the response layer held off.** A web unit test, a live defect: red on the untouched
  tree. Take the name ciphertext as `createContributorIdentity` posts it, as if a response had served it. Then
  try every key a link yields: `K_mem`, `K_share`, and for an uploader `K_up` and the drop private key.
  - **Red today:** it opens under `K_mem` (contributor) and under `K_up` (uploader).
  - **After:** every attempt is `OpenRefused`, anchored on that type, not on "it threw".
  - **Control:** a member opens it through `K_SC(epoch)` → the name private key.
- **Set-once:** the name private key is set once on the node, mirroring
  `ShareUploaderSliceTest.theDropKeyIsSetOnceOnTheMemory`. **Locked:** a locked resolve carries no name public
  key, mirroring the existing password-gate tests.
- **The permanent guard is the type union, bitten.** Once the name's purpose exists only in
  `SEAL_TO_PURPOSES`, a symmetric `v1.seal` of a name under `K_mem` does not compile. Bite it by injection: add
  such a call to `share.ts` in a snapshot copy (changes/README.md § bite-proof a guard), and see
  `npm run check` in web red on `SealContext`. The conformance test's purposes↔vectors comparison runs in both
  directions, so a re-added `share-identity-name` without its vector is red too. No new `validate_*.py`: the
  compiler is a stronger proof than a grep.
- `grep -rn "sealedDisplayName" web/src backend/src contracts --exclude-dir=generated` finds nothing, or only
  a named retired-column migration.

**Not proven:** anything about an operator that serves a guest a tampered page. The no-install page is
operator-served, and passive-safe only ([ADR 0026](../docs/decisions/0026-client-trust-tiers.md)). A page the
operator rewrites can post the typed name elsewhere before it is sealed, and no seal prevents that.

## Retrospective
