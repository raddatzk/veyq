---
id: cr-trust-anchors-survive-rotation
title: An invite carries its owner's head and grant, an owner's securing re-signs what it rooted, and the rotation body stays bounded
status: in-progress
satisfies: ["uc.spaces.add-member", "uc.spaces.manage-member-invites", "uc.account.rotate-keys"]
adrs: ["0111", "0104", "0028", "0099", "0024"]
invariants:
  - operator-blind E2EE - no path lets the server substitute a key, a head, a join record, an endorsement or a verification that a client then trusts
  - no plaintext key and no link secret reaches the server
  - goal 3 of ADR 0111 - nothing here blocks anybody's write into a shared space
  - a request the rotation sends has an upper bound under the default body limit, derived from the contract's caps
---

## Goal

ADR 0111 left six trust risks open (inbox #565, #568, #569, #570, #571, #572). This CR measures each on `main`
(76babd6da), closes the three that a client can close with a key the operator lacks, and states the other three as
named residuals with their reasons. Closed: **an invite carries its own anchor** — the inviting owner's key-chain head
inside the link and the owner's own grant beside whatever a rotation re-wrapped, so a newcomer checks the tenant key it
receives against both (#565 and #568, merged); and **an owner's securing re-signs what it rooted** — its open invites
and an endorsement of every join record it signed, staged before the publish and applied in it, so a retired owner key
roots nothing after the rotation (#571, and the second half of #568). Residuals: the rotation body's bound (#569,
measured and turned into a guard), the moderator's unanchored facts wrap (#570) and the verification row's address
(#572).

## Measured on `main` (76babd6da), per row

Every claim below names its command or test. Probes were scratch tests under `src/web/src/features/tenant/`, deleted
before this CR was committed; their results are quoted.

| Row | Measured | Who gains what | Decision |
|---|---|---|---|
| **#565** | Probe: `openMemberInvite` given a grant at epoch 1 wrapped to the invite key with a 32-byte key of the probe's choosing, signed by a freshly generated identity under an unknown user id, **returns that key as the tenant key** (vitest, green on main). Nothing on the newcomer's path compares the re-wrapped epoch with the owner's: the owner's grant is overwritten by the rotation (`TenantStore` writes `inviteGrants` into the invite's one grant slot) and `memberInviteAnchored` is not even called for a new account (`TenantJoinView.vue` `finishRegistration`). | **The operator alone**: after any tenant rotation carried an open invite, it serves the newcomer a tenant key it chose under a granter it fabricated; the newcomer self-grants it, seals its join record, name and picture under it, and posts under it. | **Fix** (Part A). |
| **#568** | Probe: after the owner rotated (`signerKeyChainHead` replaced by another head), a grant **signed by the owner's retired key** carrying a key of the probe's choosing is opened by a new account (green on main). `anchorIdentity(…, { firstUse: 'served' })` pins the served signer key at the served head without checking that the key is the one at that head (`identityPins.ts`), so the refusal the contract (`ServedMemberInvite.signerKeyChainHead`) and ADR 0111 § What stays open describe ("the invitee refuses one whose signer is not the key at the served head") **does not exist**; a pinned owner is refused only because the chain read 404s for a non-co-member. Rotators declare such an invite pending (`inviteVouched` anchors the stored retired key at the current head: anomaly). | **The operator with a thief of the owner's old device**: after the owner secured the account, it plants a tenant key for every newcomer of the owner's open invites — the forward secrecy the owner's securing bought does not reach them. Honest server: the invites keep working behind, against what the ceremony tells the owner (`rotation.factInvites`). | **Fix**, merged with #565 (Part A). |
| **#571** | `tenantTrust.test.ts` "a record its owner signed with a key they have since retired still anchors" (green on main): `loadTenantView` accepts a record whose signer key is any key on the owner's chain at or after their join, with nothing telling a record signed before the rotation from one signed after. | **The operator with a thief of an owner's old device**: mints an invite key with the retired key, a join record for a key of its own and a membership row; every co-member anchors it, so every later rotation re-grants it the tenant's new epochs — the owner's securing ends nothing tenant-wide. | **Fix** (Part B). |
| **#569** | `scratchpad/bound.py` (JSON of `RotateAccountKeysRequest` with every field at its contract cap and the real 296-character pin entry): **10,101,120 bytes** against Quarkus' default 10,485,760 — 384,640 bytes (3.7 %) of margin. At the contract's own `IdentityPinEntry.entry` cap (512) it is 15,501,120. `IdentityPinsSliceTest.aWriteOf25000EntriesLandsAndTheStoreHoldsNoMore` claims to bound the rotation but its `ROTATION_OVERHEAD` omits seven fields secure-the-account added, among them `unreadableScopeIds` (up to 390,000 bytes). The pin store's own cap is enforced at every write (`422 pin_store_full`). | Nobody: no list in the publish is uncapped, and a real body fits. The ceiling is ADR 0104's 25,000 contacts per account. | **Residual**, with the bound made exact and guarded (Part C). |
| **#570** | `factsForMembers` (`src/web/src/features/moderation/cases.ts`) wraps `K_facts` to the served `StatementRecipient.identityPublicKey`; the contract says "pin-check it before sealing" (`moderation.openapi.yaml`). The moderation surface has no Master Key and so no pin store (ADR 0099 § 6), and the recipients may share no tenant with the moderator, so there is no join record and no pinned head to anchor them by: any check would be a first use the operator serves. | **An active operator** that substitutes a recipient's key reads the statement's facts text (and can re-wrap it to the genuine key afterwards, so nobody notices). It already reads the measure's kind, target, legal ground and provision in the clear. | **Residual**: the contract's sentence and the code comment are corrected; no anchor exists to check against (Part D). |
| **#572** | `veyq_verification` (`V202610281253__core_verification.sql`): primary key (tenant, verifier, subject), `created_at`; `MemberVerification` serves both ids and `createdAt`. | **The operator** learns which member verified which, in which tenant, and when — never what was compared. | **Residual (decided, for the owner)**: the verifier cannot be hidden, because the server authenticates every write; an opaque subject id hides little, because a compare is mutual and the two writes arrive seconds apart; and it would cost the one-row-per-pair replace and the removal's delete by member. |

## Decisions

### Part A — an invite carries its anchor (#565, #568)

1. **The link carries the owner's key-chain head, not the fingerprint.** `invite-link` payload v2:
   `0x02 ‖ lp(seed) ‖ lp(signerHead) ‖ lp(utf8(signerUserId))`, the head 32 bytes. A head commits the identity **and**
   the proof key at that step, so walking the owner's chain from it accepts a later key only through steps signed by
   the Secret-Key proof key — which a thief of a device never holds. A v1 payload is refused (`InviteLinkInvalid`
   `version`); veyq has no release (`release: none`), so no v1 link exists outside a dev database. Vector
   `#invite_link` regenerated.
2. **The invite keeps the owner's own grant as its anchor.** `veyq_tenant_invite` gains `anchor_wrapped_grant`,
   `anchor_epoch`, `anchor_grant_signature`: written at mint and reissue (equal to the grant), replaced when a scope
   rotation carries the invite **and the rotator is the invite's signer** (the carried grant then is the owner's), and
   nulled by every statement that ends the invite. Any other rotation writes only the carried grant, as today.
3. **The preview serves what the check needs.** `ServedMemberInvite` gains the signer's whole key chain (its steps),
   the anchor grant, and the tenant scope's epoch links for every epoch in `(anchorEpoch, grantedEpoch]`. It loses
   `signerKeyChainHead` (the chain carries it). `granterKeyChainHead` stays (a pinned foreign granter is still judged
   against its chain). The preview is public (it is read before an account exists), so it serves the invite's signer's
   chain to the holder of the invite token — public keys and signatures, as `getKeyChain` serves co-members.
4. **The newcomer's check** (`checkInvite` in `@veyq/crypto/invite`, then `openMemberInvite`), each refusal
   `InviteCheckFailed` with its own reason:
   - the key the seed derives is the served invite key (`key_mismatch`); the seal's signer id is the served one
     (`signer_mismatch`);
   - the served chain is the signer's, verifies, and **passes through the link's head**; its newest key is the served
     `signerIdentityPublicKey` byte for byte (`signer_chain_mismatch`). So an owner who secured the account after
     signing is accepted at the new key, reached by the doubly signed step, and a retired key is never accepted;
   - the `invite-key` signature verifies under that newest key (`bad_signature`), and so does the anchor grant's
     `grant` signature (`anchor_invalid`);
   - the anchor grant opens to `E_a`. If `grantedEpoch == anchorEpoch` the tenant key is `E_a` and the carried grant
     must be the anchor byte for byte; if `grantedEpoch > anchorEpoch` the carried grant's signature verifies under
     the served granter key, it opens to `E_g`, and peeling the served links from `E_g` down to `anchorEpoch` yields
     exactly `E_a` (`epoch_unlinked`); `grantedEpoch < anchorEpoch` is refused. The operator holds no `E_a`, so it
     cannot make a link that ends there.
5. **The inviting owner is pinned from the walked chain.** For an existing account, `memberInviteAnchored` judges the
   inviting owner against its pin with the served chain (`readChain`: a pinned head must lie on it and carry the pinned
   key) and pins the walked head with its real index on first use. `anchorIdentity`'s `firstUse: 'served'` option goes
   (no caller is left). A pinned foreign granter is still judged as today; an unpinned one is not pinned (the epoch
   link, not its key, vouches for the grant).
6. **An owner's securing re-signs its open invites, through the stage.** `GET /api/account/rotation/plan/invites`
   lists every ACTIVE, unexpired member invite the caller signed, in every tenant (tenant, invite id, invite public
   key, expiry, anchor grant and epoch). The client signs, with the **new** identity, a fresh `invite-key` statement
   (same fields) and the anchor grant's `grant` statement, and stages them
   (`PUT /api/account/rotation/stage/{stageId}/invites`, at most 256, each verified under the stage's identity: `422
   invite_resign_invalid`; an invite the caller did not sign, that is not open, or whose anchor epoch moved: `409
   rotation_plan_changed`). The publish, under the tenant scope locks it already takes, requires every open member
   invite the caller signed to be staged at its current anchor epoch (`409 rotation_plan_changed` otherwise) and writes
   `invite_key_signature`, `signer_identity_public_key` (the new identity) and `anchor_grant_signature` — and, where the
   carried grant is the anchor (same epoch, granter = caller), its `grant_signature` and `granter_identity_public_key`
   too. Nothing to declare: re-signing needs no key a device may lack. So an owner's open invites survive the owner's
   securing, and the rotation's own tenant commit then carries them (the rotator is their signer: the anchor moves to
   the new epoch).
7. **An accept states the signer key it checked.** `MemberJoin` gains `signerIdentityDigest` (SHA-256 of the signer
   bundle the newcomer's join record names); the server refuses it unless it is the invite's stored signer key, under
   the tenant scope lock (`409 invite_changed`). This closes the race in which a newcomer opened the invite before the
   owner's publish and accepts after it, with a join record naming a key the publish just retired and no endorsement
   (Part B). The client re-reads the preview and re-runs the checks with the invite key and the link facts it kept
   (not secret: the head and the signer id), once, without asking for the code again.
8. **The ceremony stops warning.** `rotation.factInvites` (EN+DE), its icon row in `KeyRotationPanel.vue` and
   `signedOpenInvites` in `rotationEntry.ts` go: the invites survive.

### Part B — a retired owner key roots nothing after the rotation (#571)

9. **An endorsement.** Statement `join-endorsement` over `tenantId`, `userId` (the member), `signerUserId` (the owner)
   and `recordDigest` = SHA-256 of the member's **sealed** join record as stored (the bytes the server serves as
   `sealedJoinRecord`), signed by the owner's identity (`signJoinEndorsement`, `joinEndorsementVerifies`, vector
   `#join_endorsement`). Bound to the sealed bytes, so it endorses that one record and no other the thief writes; the
   server can verify it at stage without opening anything. Unsealed: it tells the server only that the owner invited the
   member, which it recorded at the accept.
10. **The rule a reader applies** (`loadTenantView`): a join record whose `signerIdentityPublicKey` is the signer's
    **served current** key is judged as today. One whose key is another key of the signer's chain (at or after the
    signer's join, `anchorChainKey`) counts only when the signer's served key is anchored in the tenant
    (`anchorIdentity` with the view) **and** the member's `joinEndorsement` verifies under it. Anything else is
    `unanchored`. So a record signed by a retired key counts only with the owner's endorsement by its current key —
    the counterpart of decision 13's re-signed verifications, here atomic.
11. **The server records who rooted a join.** `veyq_tenant_profile` gains `join_signer_user_id` (the invite's signer,
    written at accept) and `join_endorsement` (served as `ScopeMember.joinEndorsement`, nullable). Both go with the
    profile (removal, erasure, purge).
12. **An owner's securing endorses through the stage.** `GET /api/account/rotation/plan/join-records` lists every
    profile whose `join_signer_user_id` is the caller and that holds a join record (tenant, member, sealed record).
    The client opens each with the tenant's epochs (the plan opener), and endorses it with the **new** identity only
    when the record verifies, names the caller as signer, and either names the caller's outgoing key or carries an
    endorsement that verifies under it; any other it **declares** (`endorsement: null` — a record it cannot open, or one
    a retired key signed without endorsement, which is exactly what must not be carried). Staged by
    `PUT /api/account/rotation/stage/{stageId}/join-endorsements` (at most 256; a non-null one verified under the
    stage's identity over the stored sealed bytes: `422 join_endorsement_invalid`; a profile the caller did not root:
    `409 rotation_plan_changed`). The publish requires every such profile to be staged (endorsed or declared) — `409
    rotation_plan_changed` otherwise, which also catches an accept between staging and publish — and writes
    `join_endorsement` (null for a declared one).
13. **The stage digest is not widened.** `stagedGrantsDigest` binds the self-grants into the Secret Key's proof
    because a self-grant hands a scope key to the stage's identity. A re-signed invite and an endorsement hand nobody a
    key; each carries the new identity's own signature, verified at upload, and the publish installs only that identity.
    What the server could do with them — withhold one — the completeness check answers on an honest server and a
    reader's check answers on a malicious one.

### Part C — the rotation body's bound (#569, residual)

14. **No stage for the pin store.** The publish is bounded: every list in it has a contract cap, and the worst case
    fits. Staging the 25,000 entries would only matter for a higher cap, which nothing asks for.
15. **The bound is exact and guarded.** `IdentityPinEntry.entry` is exactly 296 characters (`minLength` = `maxLength`:
    a 192-byte padded entry sealed is 221 bytes). `scripts/validate_rotation_shape.py` computes the largest JSON body
    `RotateAccountKeysRequest` admits from the contract's own caps (every string's `maxLength`, a uuid's 36, every
    array's `maxItems`, an int64's 19 digits, `$ref`s resolved) and fails when it exceeds the default body limit
    (10,485,760, `quarkus.http.limits.max-body-size` unset) — and when any string or array in it has no cap, which is a
    body with no bound. `IdentityPinsSliceTest`'s hand-kept `ROTATION_OVERHEAD` goes; the test keeps proving that the
    store holds 25,000 and no more.

### Part D — the moderator's facts wrap (#570, residual)

16. The contract's `StatementRecipient.identityPublicKey` says the key is wrapped to unchecked, why (no pin store on
    the moderation surface, no shared tenant), and what an active operator reads by substituting it. The comment on
    `factsForMembers` drops its false claim that a substitution wins the operator nothing.

### Part E — the verification row's address (#572, residual)

17. Accepted as metadata, for the reasons in the table. Stated in ADR 0111 § Consequences (already there),
    security-model's residual list, and nowhere as open.

## Inventory

Locators (run them; do not trust a count frozen here):

- Invite link: `grep -rn "signerFingerprintHash\|encodeInviteLinkPayload\|decodeInviteLinkPayload\|sealInviteLink\|openInviteLink" src contracts tests --include='*.ts' --include='*.kt' --include='*.json'`
- Served invite: `grep -rn "ServedMemberInvite\|signerKeyChainHead\|servedMemberInvite" src contracts tests | grep -v '/generated/'`
- Invite grant writes: `grep -rn "wrapped_grant\|wrappedGrant\|inviteGrants" src/backend/core/src/main | grep -i invite`
- The served-first-use pin: `grep -rn "firstUse: 'served'\|firstUse?: 'chain' | 'served'" src/web/src`
- The ceremony warning: `grep -rn "factInvites\|signedOpenInvites" src/web/src site/src`
- Join records: `grep -rn "sealedJoinRecord\|sealed_join_record\|joinRecordVerifies" src contracts`
- Rotation stage: `grep -rn "RotationStage\|rotation/stage\|stagedGrantsDigest" src contracts scripts`
- Moderation facts: `grep -rn "factsForMembers\|StatementRecipient" src contracts`
- Pin entry size: `grep -rn "ROTATION_OVERHEAD\|IdentityPinEntry\|entryOf(" src contracts`

| Surface | Files (found by the locators) |
|---|---|
| crypto | `src/packages/crypto/src/invite.ts` (link v2, `checkInvite` with the chain, the endorsement), `seal.ts` (`join-endorsement` statement purpose), `contracts/crypto/vectors/v1.json` and the generator that writes it |
| contracts | `tenants.openapi.yaml` (`ServedMemberInvite`, `MemberJoin`/accept requests' `signerIdentityDigest`, `409 invite_changed`), `scopes.openapi.yaml` (`ScopeMember.joinEndorsement`), `account.openapi.yaml` (two plan reads, two stage writes, publish semantics, `IdentityPinEntry.entry`), `moderation.openapi.yaml` (`StatementRecipient`) |
| backend | a migration (`veyq_tenant_invite` anchor columns; `veyq_tenant_profile.join_signer_user_id`, `join_endorsement`; `veyq_rotation_stage_invite`, `veyq_rotation_stage_endorsement`) and its pin line; `TenantStore`/`TenantsResource`/`TenantAcceptHandler`/`TenantHandler` (mint, preview, accept); the scope rotation's invite carry; `RotationStageStore`/`RotationHandler`/`RotationStore`/`AccountRotationResource`; the Kotlin statement encoder for `join-endorsement` |
| web | `features/spaces/memberInvite.ts` (mint seals the head), `features/tenant/tenantInvite.ts`, `views/TenantJoinView.vue`, `features/account/identityPins.ts`, `features/account/tenantTrust.ts`, `features/account/rotation/{rotateKeys,rotationEntry}.ts`, `KeyRotationPanel.vue`, `i18n/index.ts`, `features/moderation/cases.ts`, test fixtures `test/memberInvite.ts`, `test/memberJoin.ts` |
| scripts | `validate_rotation_shape.py` (the bound) |
| docs | ADR 0111 (decisions 1–3, 6, 12, 15; § Consequences; § What stays open), `keys-grants-and-trust.md`, `security-model.md`, `uc.spaces.add-member`, `uc.spaces.manage-member-invites`, `uc.account.rotate-keys`, `en.account.identity-pinning`, `en.spaces.member-identity`, their coverage files; site: `security/{key-hierarchy,protocols,known-limitations,threat-model,what-the-server-sees}`, `account/replace-your-keys` (EN+DE), `space-owners/who-gets-into-your-space` (EN+DE) — the docs phase greps `site/` for `stop working`, `fingerprint`, `retired` |

## Phases

- [ ] **1 crypto + contracts** — link v2 and `checkInvite` with the chain walk and the epoch-link check's pure half;
      the `join-endorsement` statement; the vectors; every contract change above; the Kotlin `join-endorsement`
      encoder (it is the vectors' second reader). Tests first: crypto unit tests for each refusal reason of decision 4,
      each with its control. · Done when: the crypto package's check is green; `validate_*` green; the backend and web
      fail to compile only where the new operations and fields are unimplemented (the accepted contracts-phase red).
- [ ] **2 backend** — the migration; anchor grant on mint/reissue/carry/end; the preview's chain, anchor and links;
      the accept's `join_signer_user_id` and `signerIdentityDigest` check; `joinEndorsement` served; the two plan
      reads, the two stage writes, the publish's completeness and application. Tests first: slice tests per refusal and
      its control (`invite_resign_invalid`, `join_endorsement_invalid`, `rotation_plan_changed` for an unstaged invite,
      an unstaged join record and an accept between stage and publish, `invite_changed`), and that a non-signer
      rotation leaves the anchor. The lock change (publish now writes invites and profiles under the scope locks it
      takes) runs `scripts/run_backend_races_on_mariadb.py`. · Done when: `./gradlew check` green; races green.
- [ ] **3 web** — mint seals the head; `openMemberInvite` and `memberInviteAnchored` per decisions 4–5; the accept's
      digest and its one silent retry; `loadTenantView` per decision 10; `rotateKeys` stages invites and endorsements
      and re-stages their delta on `409 rotation_plan_changed`; the warning goes; the moderation comment. Tests first:
      the two probes above, turned into regressions that must now refuse (operator key under a fabricated granter:
      `epoch_unlinked`; retired-key grant after the owner rotated: `signer_chain_mismatch`), each with a control; the
      #571 case as "a retired-key record without an endorsement is unanchored, with one by the current key it is
      joined, with one by the retired key it is not". · Done when: `npm run check` green; a browser pass (owner
      invites, owner secures the account, newcomer accepts with the code and is a member holding the current epoch).
- [ ] **4 bound** — decision 15. Bite: the guard red on an uncapped string and on a cap that pushes the body over.
      · Done when: the guard green; `validate_guard_gaps.py --force` green.
- [ ] **5 docs** — ADR 0111 in place, the concern doc's "Decided, not built", security-model rows and residuals,
      use cases, coverage, site. Inbox: #565, #568, #569, #570, #571, #572 deleted. · Done when: every `validate_*`
      green; the site's check green.

## Definition of done

`./gradlew check` (src/backend), `npm run check` (src/web), the crypto package's and the site's checks, every
`scripts/validate_*.py`, the MariaDB races, and a browser pass of the invite across an owner's securing — all green.
Each load-bearing check above has a bite test (break it, see red, restore). `docs/coverage/` covers each new criterion.
The CR is deleted after its retrospective's lessons are moved where they bind.

## Retrospective

(filled at the end)
