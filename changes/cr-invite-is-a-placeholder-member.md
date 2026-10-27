---
id: cr-invite-is-a-placeholder-member
title: A tenant invite is a placeholder member holding an owner-signed invite key; the invitee grants itself and writes a join record
status: planned
satisfies: ["uc.spaces.add-member", "uc.spaces.manage-member-invites", "uc.spaces.add-group-member", "uc.spaces.consent-group-add"]
adrs: ["0111", "0020", "0023", "0024", "0025", "0028", "0055", "0006", "0014", "0026"]
invariants:
  - the server stores only the invite PUBLIC key, the owner's signature over it, and a grant sealed to it; the invite private key leaves the owner's client only inside the link, sealed under a code or password the server never sees
  - the invitee derives the invite public key from the private one and checks the owner's signature against the fingerprint INSIDE the seal - nothing it relies on comes from the server unchecked
  - every rotation of the tenant scope grants each open invite the new epoch or declares it pending; the server refuses a rotation that leaves one out silently
  - an accepted or revoked or expired invite holds no key and no grant on the server
  - a newcomer holds the tenant key from its accept on; no member ever waits for another member's device to be let in
  - the join record is sealed under the tenant, signed by the invite private key and by the new identity; the operator cannot write one that verifies
---

## Goal

[ADR 0111](../docs/decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
decisions 1–5 (inbox #561's first half). Today a tenant invite is a server-minted token mailed in a link; the newcomer
joins **grant-less**, waits in *awaiting access* until some member's device wraps the tenant key to it, and that wrap is
gated by an **admission tag** that binds no user and no key (ADR 0020 move 6). Under this CR:

- **Creating an invite** mints an **invite keypair** on the owner's client. The owner signs the invite public key with
  their identity; the server stores the public half, the signature and a **grant of the current epoch wrapped to the
  invite key**. The private half travels in the link, **sealed under a generated code** the owner passes on outside
  veyq, together with the owner's identity fingerprint.
- **Every rotation** of the tenant re-wraps to each open invite like to a member. Revoking an invite deletes its key and
  grant; it expires after a fixed time.
- **Accepting** opens the grant with the link and the code, checks the owner's signature against the fingerprint in the
  seal, **grants the epoch to the new identity**, and writes a **join record** (identity and Secret-Key proof public
  key, signed by the invite key and the new identity) into the newcomer's tenant profile.
- **A group needs no invite key**: the group owner wraps the group's epoch directly to the target's key when adding them.
- **Gone:** the admission tag, the grant-delivery for new members, and *awaiting access*.

It lands **before** `changes/cr-secure-the-account.md`, which needs every tenant member — not only an owner — able to
carry open invites through a rotation (that CR's § Order). It needs nothing of that CR; the grants it adds name their
recipient key from birth, so that CR's decision 6 finds them already shaped.

**Size:** five phases (crypto and contracts, backend, web owner side, web invitee side and groups, docs and site).

## Context & constraints

Refined 2026-10-27 against `main` at `8cd444bbc`; every pointer was read by a delegated read that cited it.

- **Tenant member invite today** (`contracts/api/tenants.openapi.yaml`): `inviteTenantMember` (l.342-431,
  `MemberInviteRequest` l.2592: `email`, `admissionId`, `admissionEpoch`, `admissionTag`; answers `MemberInviteState`,
  no secret — mail is the only channel, ADR 0055); `listMemberInvites` (l.435, owner-only, `TenantHandler.kt:526`;
  `PendingMemberInvite` l.2638 serves the admission triple for a rotation's re-tag); `reissueMemberInvite` (l.484, keeps
  the admission); `revokeMemberInvite` (l.543, soft). Redeem, keyed on `bootstrapSecret` (`InviteBootstrapSecret`
  l.2717, "admission token, not a key", matched by hash): `previewTenantInvite` (l.2012, `InvitePreview` l.3290),
  `acceptTenantInvite` (l.2064, `AcceptInviteRequest` l.3345 — grant and admission only for an OWNER invite, a MEMBER
  "joins grant-less"), `registerAndAcceptTenantInvite` (l.2196, `RegisterAndAcceptRequest` l.3489), and the handover
  `acceptTenantOwnership` (l.2135).
- **Storage:** `veyq_tenant_invite` (`V202610041200__core_baseline.sql:152-169`, times DATETIME(3) since
  `V202610192230…:96-97`): `id, tenant_id, owner_email, token_hash, status, expires_at, created_at, role,
  admission_id, admission_epoch, admission_tag`; entity `admin/tenant/Entities.kt:130-178`. Tokens: 32 random bytes,
  hex SHA-256 stored (`crypto/Tokens.kt:16-26`). TTL `veyq.tenant.invite.ttl=P7D` (`application.properties:81`), purge
  `TenantInvitePurge.kt:27-31`.
- **Creation** `TenantHandler.inviteMember` (`TenantHandler.kt:469-496`): scope access, owner, tenant ACTIVE,
  `already_member`, `mailer.canSend`, rate limit, token, `mintMemberInvite` (`TenantStore.kt:210-221`, supersedes the
  earlier invite to that email, refuses a stale epoch), send after commit. The mailer builds
  `https://$domain/tenant/accept#$token` (`SmtpTenantInviteMailer.kt:55`; same shape `src/admin-web/src/shared/inviteLink.ts:9-11`).
- **Accept** `TenantAcceptHandler.acceptOnce` (:160-205): MEMBER branch locks the tenant scope, consumes the invite,
  copies the admission, `joinAsMember` (`TenantStore.kt:183-194`, `wrappedGrant = null`), creates the profile. OWNER
  branch (:193-204) requires the tenant birth. `registerAndAcceptOnce` (:217-265) creates the account first.
- **Web:** `InviteMemberDialog.vue:35-59` (email only) → `members.ts:26-29` (`admissionFor` + `inviteTenantMember`;
  `admissionFor` `scopes.ts:691-702`). `TenantJoinView.vue`: reads and strips `#secret` (:98-122), previews, registers
  (`prepareRegistration` :125-145, kit phase :349-356, `finishRegistration` :168-193 — a MEMBER sends no birth) or signs
  in (`signInAndAccept` :196-227, `acceptDirect`/`sealAndAccept` :231-278); `done` (:416-422) says access "arrives
  automatically once a member is online" (`tenant.join.doneNoteMember`, `i18n/index.ts:602-603`). Helpers
  `features/tenant/tenantInvite.ts` (`bornTenantScope` :97-105 self-tags the owner's admission).
- **Admission tags** (`grant.ts:80-116`, `v1.ts:392-414`, `seal.ts:338-342`; stored on `veyq_membership.admission_*`
  and `veyq_tenant_invite.admission_*`; re-tagged in `commitRotation`, `scopes.ts:627-633, 652-654`, and in
  `ScopesStore.rotate` :416-417, 459-466; completeness `RotationPolicy.kt:88-124`, `AdmissionsIncomplete` :118-120; wire
  `droppedAdmissions` `scopes.openapi.yaml:1376-1380`; verified only by the client: `grantDeliveryWorker.ts:75-79`,
  `useMemberDirectory.ts:50-80`). The server never verifies a tag, only its epoch (`TenantStore.kt:213-215`).
- **Awaiting access:** `ScopeMember.hasGrant` (`scopes.openapi.yaml:1610-1613`, `ScopesStore.kt:643, 663`);
  `orAwaitingAccess` (`scopes.ts:262-269`); `activeSpace.tenantUnavailable` (`app/activeSpace.ts:107-132`);
  `members.statusAwaiting`, `members.awaitingAccess`, `members.badgeNotAdmitted` (`i18n/index.ts:117-120`, DE
  2098-2101), shown at `TenantMembersPanel.vue:386, 392-394`, `GroupMembersPanel.vue:510, 518`, `AppSidebar.vue:301-303, 488`.
- **Grant delivery** (`grantDeliveryWorker.ts:53-116`; `TasksStore.reconcileGrantDelivery` :646-674 — "behind" is
  grant-less **or** below the current epoch; `complete` :228-278; scheduled backstop `GrantDeliveryOriginator.kt:23`)
  serves two cases: a new member (grant-less) and a member a rotation skipped (behind). The second stays (ADR 0111
  decision 9, built by `cr-secure-the-account`).
- **Group add today** is grant-less: `resolveGroupMember` (`scopes.openapi.yaml:383`) serves the target's key,
  `addGroupMember` (l.281-378, `AddGroupMemberRequest` l.1186: username, admission triple, optional `sealedNameKey`)
  records a `pendingConsent` membership, "no key crosses the wire" (l.287); owner-only (`ScopesHandler.kt:400`). Consent:
  `acceptGroupInvitation` (l.924), `declineGroupInvitation` (l.945, `ScopesHandler.declineInvitation` :479). Web
  `GroupMembersPanel.vue:132-144` → `members.ts:87-103`.
- **The tenant profile** (ADR 0023) is one row of fixed columns, `veyq_tenant_profile (id, user_id, tenant_id, username,
  sealed_name, sealed_avatar, avatar_revision)` (baseline :349-363), written only by its member (`/api/account/profile`,
  `account.openapi.yaml:168-302`) and at join, read by every co-member through `listScopeMembers`. Fields are sealed with
  `sealField` under the tenant name key, bound to `{scopeId=tenantId, userId}` (`memberName.ts:11-25`).
- **Reusable crypto:** Argon2id (`ARGON` `v1.ts:90`, `encodeKdfParams` :96-114, `kekRecovery` :214 — a generated code to
  a KEK, the closest analogue; off-thread `argonWorker.ts:130`); an identity-shaped bundle from a 32-byte secret
  (`moderationBundle` `v1.ts:635-660`, `identityPublicFromPrivate` :708); typed signed statements (`identitySign`
  :609, `STATEMENT_PURPOSES` `seal.ts:336-376`); `sealGrant` works with any identity-shaped key (`grant.ts:57-72`);
  `fingerprintHash` (`v1.ts:762-764`).
- **The first owner** comes through an OWNER invite of the Server Owner (`createTenant`, admin-web
  `CreateTenantDialog.vue`; `TenantStore.createPendingTenant` :287) and births the tenant scope cold. Nobody vouches for
  them: they are the tenant's trust root (ADR 0111 decision 3). The handover (`inviteTenantOwner`, ADR 0101) flips an
  existing member's role.

## Decisions

Decisions marked *(assistant, for owner review)* were taken by the assistant on 2026-10-27 under the owner's
instruction to decide rather than block.

1. **The invite row is the placeholder member.** `veyq_tenant_invite` gains `invite_public_key`, `invite_key_signature`,
   `signer_user_id`, `signer_identity_public_key` (the signer's key as signed, so a signer who rotates later does not
   void the invite), and the grant columns a membership carries (`wrapped_grant`, `granted_epoch`, `grant_signature`,
   `granter_user_id`, `granter_identity_public_key`); its three `admission_*` columns go. A new table would duplicate the
   token, email, status and expiry it already has *(assistant, for owner review)*. There is no release, so no existing
   rows (every dev database is recreated).
2. **The invite keypair** is an identity-shaped bundle (KEM + sign) derived from a 32-byte seed (`inviteKeyFromSeed`,
   built like `moderationBundle`), so `sealGrant` wraps to it and it signs the join record.
3. **The owner signs the invite key**: a typed statement, purpose `invite-key`, over `tenantId`, `inviteId`,
   `sha256(invite bundle)` and `expiresAt`, signed with `identitySign`. Any tenant owner may invite (as today); several
   owners each root their invitees.
4. **The link is always sealed, under a generated code** *(assistant, for owner review — ADR 0111 decision 1 leaves the
   seal optional; mail is the only channel today and the operator mails the link, so an unsealed link would hand the
   operator the invite)*.
   - `https://<domain>/tenant/accept#<token>.<sealed>`: `token` is today's `bootstrapSecret` (preview, single use);
     `sealed` is the base64url of an AEAD seal, purpose `invite-link`, of `{seed, signerFingerprintHash,
     signerUserId}` under `Argon2id(code, salt)` with `ARGON`'s parameters, salt and parameters in the clear.
   - **The code** is 10 Crockford-base32 characters (50 bits), minted by the owner's client and shown once in the
     invite dialog with a copy button and "Send this code to them yourself, not by email". Against the operator, who
     holds the sealed link and can guess offline, 50 bits behind Argon2id at 32 MiB is the margin ADR 0111 decision 1
     asks for. The owner may type a **password** instead (the account password policy's length floor).
   - The owner's client sends `sealed` with the invite; the server appends it to the mailed link and keeps no copy
     (`commitThenSend`, then dropped).
5. **The invite holds a grant like a member.** `MemberInviteRequest` carries, beside `email`: `inviteId` (client
   UUIDv4), `invitePublicKey`, `inviteKeySignature`, `expiresAt` (the server checks it equals its own TTL, ±1 minute),
   `sealedLink`, and a grant of the tenant's current epoch to the invite key — `wrappedGrant`, `grantSignature` (the
   `grant` statement with `recipientUserId` = `inviteId`), `epoch`, `recipientIdentityDigest` (`sha256` of the invite
   bundle). The server checks the epoch is current under the tenant scope's row lock (`409 stale_epoch`, as today) and
   the digest against the key it stores (decision 6 of `cr-secure-the-account` holds from birth here). This is a new
   grant writer: `EXPECTED_WRITERS` gains it.
6. **Every rotation carries every open invite.**
   - **A member-readable list:** `listOpenInvites` (`GET /api/scopes/{tenantId}/open-invites`, tag `scopes`) serves
     every tenant member `{inviteId, invitePublicKey, inviteKeySignature, signerUserId, signerIdentityPublicKey,
     expiresAt}` — no email. `listMemberInvites` stays the owner's list (with email and delivery state).
   - `RotateScopeRequest` for a TENANT scope gains `inviteGrants` (`{inviteId, wrappedGrant, grantSignature,
     recipientIdentityDigest}`) and `pendingInviteIds`; `admissions` and `droppedAdmissions` go. `RotationPolicy`
     requires `inviteGrants ∪ pendingInviteIds` to equal the open invites under the scope's row lock, else
     **`409 rotation_incomplete`**, replacing `AdmissionsIncomplete`.
   - `commitRotation` wraps to an invite whose `inviteKeySignature` verifies under `signerIdentityPublicKey` and whose
     signer passes the client's pin check (`checkAndPin` today; `cr-secure-the-account`'s anchor function once it
     lands); otherwise it declares it pending.
   - **A pending invite** stays redeemable: its newcomer self-grants the epoch it can open, its membership is behind,
     and the narrow grant delivery (`reconcileGrantDelivery`, behind memberships) brings it current.
7. **Revoke, expire, reissue.** Revoking or expiring an invite nulls its key and grant columns in the same statement
   that ends it (`revokeInvite`, `expireOverdueInvites`). The tenant does not rotate (ADR 0025; ADR 0111 § Consequences,
   accepted). Reissue revokes the old invite and creates a new one (new key, new code), so `reissueMemberInvite` takes a
   full `MemberInviteRequest`.
8. **Accept: the invitee grants itself.**
   - **Preview** (`InvitePreview`) gains `inviteId`, `invitePublicKey`, `inviteKeySignature`, `signerUserId`,
     `signerIdentityPublicKey`, and the invite grant (`wrappedGrant`, `grantedEpoch`, `grantSignature`, the granter's
     key).
   - **The invitee's client**, before anything else: asks for the code (or password) and opens `sealed`; derives the
     invite bundle from `seed` and requires it equal to the served `invitePublicKey`; requires
     `fingerprintHash(signerIdentityPublicKey)` equal to `signerFingerprintHash` from the seal; verifies
     `inviteKeySignature`; pins the signer with that key (first use, now anchored by the link). A mismatch anywhere is
     a refusal with "This invite can't be checked — ask for a new one", and nothing is sent. A signer already pinned
     under a different key is the same refusal.
   - It opens the invite grant with the invite private key, verifies its signature as `verifyOwnGrant` does for a
     foreign granter, and derives the epoch keys.
   - **Account birth or unlock** as today; then it seals the current epoch key to its own identity — a self-grant with
     `recipientIdentityDigest` — and builds the join record (decision 9).
   - **`acceptTenantInvite` / `registerAndAcceptTenantInvite`** (MEMBER) carry `selfGrant` `{wrappedGrant,
     grantSignature, epoch, recipientIdentityDigest}`, `sealedJoinRecord`, and `inviteProof` (the invite key's signature
     over `{inviteId, userId, sha256(identity bundle)}`). The server verifies `inviteProof` under the stored invite
     public key (the operator could forge one, but an honest server refuses a token-only redeem), writes the MEMBER
     membership **with** the grant (granter = the newcomer), the profile with its join record, and consumes the invite,
     nulling its key and grant. A self-grant below the current epoch is accepted (a pending invite) and leaves the
     membership behind. Two new grant writers (accept, register-and-accept) share one store function: one entry in
     `EXPECTED_WRITERS`.
   - The newcomer enters the tenant at once; `done` says so.
9. **The join record** (ADR 0111 decisions 3 and 12).
   - Content: `tenantId`, `userId`, the identity public bundle, the Secret-Key proof public key (the newcomer's client
     derives it from its Secret Key; at birth it is the one it registers), `inviteId`, the invite public bundle,
     `inviteKeySignature`, `signerUserId`, `signerIdentityPublicKey`; signed — the statement purpose `join-record` — by
     the invite private key **and** by the new identity.
   - Sealed under the tenant name key with `sealField`, purpose `join-record`, bound to `{scopeId=tenantId, userId}`,
     stored in a new column `veyq_tenant_profile.sealed_join_record`, written only at accept, never by
     `updateMyProfile`, served on `ScopeMember` (tenant scope listing) as `sealedJoinRecord`.
   - **Nothing reads it to decide anything in this CR** — `changes/cr-trust-records-in-the-tenant.md` makes it the
     anchor. It is written now because the invite private key exists only at accept. This CR's proof is that it is
     written, opens under the tenant key, and both signatures verify.
   - **A root owner has none**: the first owner births the tenant through the Server Owner's OWNER invite, which keeps
     its shape (no invite key: there is no grant to hand over) minus the admission self-tag.
10. **A group needs no invite key** (ADR 0111 decision 4). `AddGroupMemberRequest` gains a grant of the group's
    current epoch to the target's key from `resolveGroupMember` — `wrappedGrant`, `grantSignature`, `epoch`,
    `recipientIdentityDigest` — gated on the client by the pin check exactly as the name key is today
    (`scopes.ts:409-410`; the anchor function once `cr-secure-the-account` lands; the join record once
    `cr-trust-records-in-the-tenant` does). The admission triple goes. The `pendingConsent` membership **holds** that
    grant; consenting changes nothing about keys. Group rotations grant pending-consent members like any member.
    **A decline of a group invitation whose grant was written queues the group's rotation**, as the removal cascade
    does (`originateScopeRotation`, `TenantHandler.kt:688`) *(assistant, for owner review: the target held the key, so
    the group rotates, as ADR 0111 decision 2 says of a revoked group invite)*. Another grant writer for
    `EXPECTED_WRITERS`.
11. **Gone** (ADR 0111 decision 5): the admission tag and everything that makes, stores, carries, serves or checks it
    (the Inventory's first row); `ScopeMember.hasGrant`, *awaiting access* and its copy and states
    (`orAwaitingAccess`, `tenantUnavailable`, `members.statusAwaiting`, `members.awaitingAccess`,
    `members.badgeNotAdmitted`); the grant-delivery case for a grant-less membership (a membership is now born with a
    grant). The delivery for a **behind** membership stays, with its TOFU gate, minus its admission gate
    (`grantDeliveryWorker.ts:75-79`).

### Not in this CR

- Using the join record as an anchor, and every display of it → `changes/cr-trust-records-in-the-tenant.md`.
- A key chain, grants to retired keys, member completeness in a rotation → `changes/cr-secure-the-account.md`.
- A copy-link channel for member invites (mail stays the only channel; ADR 0055).
- The admission-tag replay hypothesis (ADR 0111 § What stays open): it dies with the tag, untested; nothing claims it.

## Inventory

| What | Where | Locator |
|---|---|---|
| Every admission site (source, tests, e2e, crypto, contracts) | everywhere | `grep -ril admission src/web/src src/backend/core/src src/packages/crypto/src contracts tests/e2e \| grep -v "quota\|UploadAdmission\|IdentityFolders\|ShareGuest\|SharingHandler"` |
| The invite operations and schemas | `tenants.openapi.yaml` | `grep -n "operationId: inviteTenantMember\|operationId: listMemberInvites\|operationId: reissueMemberInvite\|operationId: revokeMemberInvite\|operationId: previewTenantInvite\|operationId: acceptTenantInvite\|operationId: registerAndAcceptTenantInvite\|MemberInviteRequest:\|InvitePreview:\|AcceptInviteRequest:\|RegisterAndAcceptRequest:" contracts/api/tenants.openapi.yaml` |
| Every builder of those requests (by their newest field) | backend tests, web, admin-web, e2e | `grep -rln "admissionTag\|bootstrapSecret" src tests --include='*.ts' --include='*.kt' --include='*.vue'` |
| The invite table, entity, store, purge, mailer | backend | `grep -rn "veyq_tenant_invite\|TenantInviteEntity\|fun mintMemberInvite\|fun joinAsMember\|fun carryInvites\|fun expireOverdueInvites\|fun revokeInvite\|tenant/accept#" src/backend/core/src/main` |
| Rotation completeness | `RotationPolicy.kt`, `ScopesStore.kt` | `grep -n "AdmissionsIncomplete\|droppedAdmissions\|retags\|carryInvites" -r src/backend/core/src/main/kotlin/me/raddatz/veyq/core/spaces/scope/` |
| Grant writers and their guard | guard, snapshot test | `grep -n "EXPECTED_WRITERS" -A8 scripts/validate_grant_writer_arity.py; grep -rn "class GrantWriterSnapshotSliceTest" src/backend` |
| Awaiting access, everywhere | contracts, web, i18n, site | `grep -rn "hasGrant\|awaitingAccess\|statusAwaiting\|badgeNotAdmitted\|tenantUnavailable\|orAwaitingAccess\|doneNoteMember" contracts/api src/web/src; grep -rn -i "awaiting access\|wartet auf Zugang\|once a member is online" site/src/content/docs` |
| The web invite and join flow | web | `grep -n "admissionFor\|inviteTenantMember\|previewInvite\|registerAndAccept\|acceptInvite\|bornTenantScope" -r src/web/src/features/spaces/members.ts src/web/src/features/tenant/tenantInvite.ts src/web/src/views/TenantJoinView.vue src/web/src/features/spaces/InviteMemberDialog.vue` |
| Group add and consent | contracts, backend, web | `grep -rn "addGroupMember\|AddGroupMemberRequest\|declineGroupInvitation\|fun declineInvitation" contracts/api/scopes.openapi.yaml src/backend/core/src/main src/web/src/features/spaces` |
| The tenant profile | migration, entity, store | `grep -rn "veyq_tenant_profile\|class TenantProfileEntity\|fun create(" src/backend/core/src/main/resources/db/migration src/backend/core/src/main/kotlin/me/raddatz/veyq/core/identity/` |
| Statement purposes and seal purposes | `seal.ts`, `v1.ts` | `grep -n "STATEMENT_PURPOSES\|'admission-tag'\|'member-name'" src/packages/crypto/src/seal.ts` |
| Durable docs and site naming admission or awaiting access | `docs/`, `site/` | `git grep -n -i "admission tag\|admission-tag\|awaiting access\|grant-less\|joins grant-less" -- docs site/src/content/docs` |

## Phases

### Phase 1: crypto and contracts

- `@veyq/crypto`: `inviteKeyFromSeed`; statement purposes `invite-key`, `join-record`, and the accept proof; seal
  purpose `invite-link`; the link codec (`encodeInviteLink`/`parseInviteLink`, code minting and normalising); the
  admission-tag functions removed. Vectors `invite_key` (seed → bundle), `invite_link` (code + salt → KEK, a sealed
  payload), `join_record` (statement bytes, both signatures), each from an independent implementation; `admission_tag`
  removed.
- Contracts per decisions 4–11; every new property with its visibility and bounds; `listOpenInvites` classified in
  `OperatorBlindProbeTest`.
**Done when:** `npm run generate:api` runs; `npm run check` in `src/packages/crypto` is green; the trust-chain guards
are green. Expected red, stated: `src/web/`, `src/admin-web/` and `tests/e2e` at every builder of a reshaped request
(the Inventory's third row); `./gradlew check` at the tenant and scope resources and the test builders. **Phases 1–4
merge to `main` together** (client-sent fields become required).

### Phase 2: backend

Decisions 1, 5–11 (server half). **Reproducing tests first**, red against the handler as phase 1 left it:
- `aMemberInviteStoresTheInviteKeyItsSignatureAndAGrant`; refusals with controls: a stale epoch (`409 stale_epoch`), a
  digest that is not the stored key's (`409 recipient_key_not_current`), an `expiresAt` off the TTL (`400`).
- `aRotationOfTheTenantMustCarryEveryOpenInvite` (`409 rotation_incomplete` with one left out; control: the same in
  `pendingInviteIds` commits).
- `listOpenInvitesServesEveryMemberAndNoEmail`; control: an outsider gets `404`.
- `acceptingWritesAGrantedMembershipAndTheJoinRecordAndConsumesTheKey` — after accept the membership holds the
  self-grant, the profile holds `sealed_join_record`, the invite row holds no key and no grant; a second accept with the
  same token is refused as today. Refusal with control: an `inviteProof` by another key.
- `revokingOrExpiringAnInviteDropsItsKeyAndGrant`.
- `aGroupAddCarriesTheGrant` and `aDeclinedGroupInvitationQueuesTheGroupsRotation` (control: an accepted one queues
  nothing).
- No admission column or field remains: `grep -rn admission` over the backend main tree finds only the quota and share
  senses (a one-shot proof here; see Definition of done).
**Bite:** drop the open-invite completeness → its test red; keep the key on consume → the consume assertion red.
**Done when:** `./gradlew check` and the backend-reading guards (grant-writer arity, migration versions, time columns,
the erasure-arity pair) are green.

### Phase 3: web, the owner's side

The invite dialog (email, the code shown once, the password alternative); creating the invite (mint, sign, grant, seal);
`commitRotation` wrapping to open invites or declaring them; the pending-invite list's reissue and revoke; admission
code removed from the owner's paths. Tests: the posted invite's grant opens with the seed and verifies under the
owner; the link's `sealed` opens with the code and not with another; a rotation posts one `inviteGrants` entry per open
invite whose signature verifies and declares the rest. Bite: skip the signature check in `commitRotation` → a forged
invite is wrapped to → red.
**Done when:** `npm run check` in `src/web/` is green.

### Phase 4: web, the invitee's side and groups

`TenantJoinView`: the code step, the checks of decision 8 (each refusal with "This invite can't be checked — ask for a
new one"), the self-grant, the join record, the new `done` copy; awaiting access removed everywhere; group add with the
grant. Tests: each check of decision 8 has a crafted negative that fails **exactly that check** (a seed whose bundle
differs; a signer key whose fingerprint differs from the seal's; a signature by another key) and a passing control;
the accept posts a self-grant that opens under the new identity and a join record that opens under the tenant key with
both signatures verifying. Bite: drop the fingerprint check → its negative red.
**Done when:** `npm run check` in `src/web/` and `src/admin-web/` is green, and the **browser pass** ran (headless system
Chrome, German UI, a throwaway instance): owner A invites B; B opens the mailed link, types the code, registers, and is
**in the tenant space at once**, seeing A's content, with no other device online; a wrong code is refused; A revokes a
second invite and its link then fails at preview; A adds B to a group and B, after consenting, opens it at once.

### Phase 5: docs and site

ADR 0020 (moves 3, 5, 6 rewritten as built, its rejected alternatives "now taken"), ADR 0024 Decision 4 (no tags) and
5, ADR 0023 (the join record column), ADR 0111 § Status (decisions 1–5 built), `security-model.md` (:598's mark and the
admission section), `uc.spaces.add-member`, `uc.spaces.manage-member-invites`, `uc.spaces.add-group-member`,
`uc.spaces.consent-group-add` and their coverage YAMLs, `en.spaces.tenant-scope`, `en.spaces.member-identity`; site Use
EN and DE (inviting someone, joining, the code), Security (the invite protocol). **Done when:** the trust-chain guards
and `npm run check` in `site/` are green.

## Definition of done

- A newcomer holds the tenant key from the accept on, with no member online; the server never held the invite private
  key or the code; every accept wrote a join record that verifies.
- No admission tag and no *awaiting access* remain; the one-shot `grep -rn admission` proof of phase 2 is promoted to a
  check in `scripts/` only if a later CR could plausibly bring the tag back — this CR records why a one-shot proof
  suffices (the tag's whole machinery is deleted, and nothing names it any more).
- Every rotation of a tenant carries every open invite or declares it.
- The ADRs, use cases, coverage and site (EN and DE) say so. This CR is deleted after its Retrospective has moved.

## Retrospective

*(filled at implement time)*
