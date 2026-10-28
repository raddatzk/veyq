---
id: cr-trust-records-in-the-tenant
title: The join record anchors a person per tenant, a verification is a signed statement every member there reads, and the compare shows the key-chain head
status: planned
satisfies: ["uc.spaces.verify-identity", "uc.spaces.enforce-verified-identities", "en.account.identity-pinning"]
adrs: ["0111", "0028", "0104", "0023", "0060", "0026", "0020"]
invariants:
  - a key is anchored in a tenant only if the key chain reaches it from the keys the person's join record names, and the join record verifies owner -> invite -> newcomer; a root owner and a person without a join record are trusted on first use, nothing else
  - nobody can write a verification in another's name - each is signed by its verifier and written only into the verifier's own records
  - a verification signed by a retired key counts only if the list its successor signed in that tenant names it (ADR 0111 decision 13)
  - every record here is sealed under the tenant; the operator reads none of it and learns only that records exist
  - display only, one gate - nothing here blocks anybody's write; an unanchored key is not wrapped to
---

## Goal

[ADR 0111](../docs/decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
decisions 12–17 (the parts not built; decisions 6–10 and 16's counter are, 2026-10-28) and inbox #560's compare half and #561's
second half. After the two steps before it, every newcomer writes a join record (ADR 0111 decisions 1–5, built 2026-10-28) and
every rotation is a doubly signed key-chain step whose head each account pins (built 2026-10-28, ADR 0028 Decision 2), but the anchor
is still the pin taken on first use, and *verified* is still a private mark in the verifier's pin store. This CR:

- makes the **join record the anchor**, per tenant: a key counts when the chain reaches it from the keys the join record
  names, rooted owner → invite → newcomer;
- turns *verified* into a **signed verification** of a person's chain head, written into the verifier's own records in
  the tenant and read by every member there — *verified by …* counts for everyone in that tenant;
- has every rotation **close the outgoing key's verifications** per tenant (decision 13);
- shows the **chain head in the safety-number compare** (decision 14);
- shows *new*, *not verified*, *verified by …* and *new identity, signed by the previous key* (decision 15).

**Size:** four phases (crypto and contracts, backend, web, docs and site). **Depends on** both CRs above (built).

## Context & constraints

Refined 2026-10-27 against `main` at `8cd444bbc`. What it builds on is built (2026-10-28); the sentences that rested on
it were rewritten to the merged tree, and its line pointers are re-measured before the first edit.

- **Today's pin store** (ADR 0104; `src/web/src/features/account/identityPins.ts`): one entry per contact per
  **account**, `{u, f, h, s, t}` — user id, fingerprint, the pinned key-chain head, `pinned|verified`, first seen — sealed under the Master Key (purpose
  `identity-pin`, bound to `pinId`, :45, 187-218), under a sealed head with a set digest (`identityPinSetDigest`,
  `v1.ts:536-546`) and the device marks of ADR 0028 Decision 7 (`trustMarks.ts`). Contract tag `account-identity-pins`
  (`account.openapi.yaml:325-457`).
- **Today's verified mark**: `markVerified` (`identityPins.ts:533-548`), called only from `PersonSafetyNumber.vue:107`;
  shown by `foldTrust`/`trustStates` (:559-585) in `MemberTrustBadge.vue` and the profile header
  (`PersonSafetyNumber.vue:166-174`). Private to the verifier.
- **The safety number**: `fingerprintHash = SHA-256("veyq/v1/fingerprint" ‖ lp(kem) ‖ lp(sign))` (`v1.ts:762-764`),
  rendered as 6 groups of 5 digits (:746-755); QR payload `veyqid:1:<b64 digest>` (`safetyCode.ts:24-26`, compare
  :48-52); vectors `fingerprint` (`v1.json:956-962`). UI `core/people/MySafetyNumber.vue`, `PersonSafetyNumber.vue`,
  `SafetyDigits.vue`, mounted from `PersonProfile.vue:120-121`; copy `safety.*` (`i18n/index.ts:1489-1530`, DE :3422).
- **The tenant profile** is one row per member and tenant (`veyq_tenant_profile`, baseline :349-363), written only by
  its member, and `sealed_join_record`, written once at an invite's accept and served on `ScopeMember.sealedJoinRecord`
  in the tenant scope (null for the tenant's first owner). Name and picture are sealed under `K_tenant` bound to
  `{tenantId, userId}` (`memberName.ts:11-25`); the join record under the tenant epoch its invite's grant carried, as
  the field `member-join-record` bound to the same pair (`openJoinRecord`, `src/packages/crypto/src/invite.ts`), so a
  reader opens it with that epoch's key from its chain.
- **Identity is per account, not per tenant** (`veyq_user.identity_public_key`; ADR 0023 covers name and avatar only), so
  a person in two tenants has one chain and one join record per tenant.
- **Owners are the server's word**: `ScopeMember.role` on the tenant scope (`scopes.openapi.yaml:1595`).
- **No release** (`scripts/applied_migrations.sha256`, `release: none`): no existing verified marks or pins need to
  migrate; every dev database is recreated.
- **A planned use case collides with ADR 0111's "never a block"**: `uc.spaces.require-verified-identities` (strict mode,
  `status: planned, not buildable`, its A1). This CR leaves it as it is and says so in its § Alternate flows (phase 4).

## Decisions

The three items of ADR 0111 § What stays open are decided here *(assistant, for owner review — the owner's instruction
of 2026-10-27 was to decide rather than block)*; the rest follows the ADR.

1. **The anchor, per tenant** (ADR 0111 decisions 12, 15).
   - **Verifying a join record:** its two signatures (invite key, newcomer's identity) over its statement; the
     invite-key signature by `signerIdentityPublicKey`; the signer listed as an owner of the tenant and itself anchored
     — by its own join record, or, having none, as a root owner (below). A join record that does not verify is ignored
     and shown as an anomaly.
   - **A person is anchored in a tenant** when the key chain reaches the served head from the identity and proof key
     their join record names, and the served head is not behind the head this account pinned (the pin store stays the
     counter, ADR 0111 decision 16).
   - **No join record — owners:** a person the server lists as an owner of the tenant and who has no join record is a
     **root** and trusted on first use (the pin), as ADR 0111 decision 15 trusts an unknown person. A newcomer pinned
     their inviting owner from the link (ADR 0111 decision 1: the owner's fingerprint inside the link's seal, checked
     by `checkInvite` before the accept), so for them the root is anchored by the link.
   - **No join record — anyone else: an anomaly** *(decides ADR 0111 § What stays open, the membership the server
     made)*. Since 2026-10-28 every member joins with one, and there is no release, so a non-owner membership without
     one can only be one the server made — which today every wrap still reaches as a first use, since nothing reads
     the record yet. Not wrapped to, declared pending in a rotation, shown as
     an anomaly — never a block on anyone's write. Alternatives: trust it on first use (keeps the operator's door open
     for exactly the case the join record exists to close); treat owners the same (would make every root owner an
     anomaly to everyone they did not invite).
   - **Owner roles stay the server's word** *(decides item 2: accepted as a stated residual)*. A former owner, or a
     member the server presents as owner, can with the operator's help root a key of their choosing. Closing it needs
     signed roles and removals — the membership protocol ADR 0020 declined; nothing in this CR waits for it.
   - **A pin not on the anchored chain is replaced**, once, with the profile note *"The key you saw before wasn't
     theirs. Their invitation confirms this one."* *(assistant, for owner review)*: an owner-rooted join record is
     stronger than a first-use pin, and this is the case of an operator who substituted a key at first contact before
     this CR — the residual of the pinned-head anchor: a contact pinned at first use under a key the operator
     substituted sees the real person as an anomaly, while other members, whose pins are right, keep granting them.
   - **Per tenant**: the built wrap gate (`anchorIdentity`, ADR 0028 Decision 2) asks "anchored in **this scope's** tenant";
     the pin store stays per account.
2. **Verifications** (ADR 0111 decision 12).
   - **Statement** (purpose `verification`): `tenantId`, verifier user id, subject user id, subject head, verifier
     head; signed by the verifier's current identity.
   - **Written into the verifier's own records** *(confirms ADR 0111's detail: only a person writes their own tenant
     records, as `updateMyProfile` today)*: a table `veyq_verification (tenant_id, verifier_user_id, subject_user_id,
     sealed_statement, created_at)`, one row per (tenant, verifier, subject), replaced by a newer one; sealed under the
     tenant name key, purpose `verification`, bound to `{tenantId, verifierUserId}`. Written by `putMyVerification`,
     read by every tenant member through `listVerifications(tenantId, subjectUserId?)`.
   - **It counts** when it verifies, names the subject's **current** head (a rotation moves the head, so every old
     verification stops counting by itself — that is "a rotation drops *verified*"), and its signing key is the
     verifier's current key or a retired one the closing list names (decision 3).
   - **The personal mark goes**: the pin entry loses `state`; `markVerified` writes a verification instead; the
     profile and badges read *verified by …* from the tenant's records.
3. **The closing list** (ADR 0111 decision 13). `RotateAccountKeysRequest` gains
   `closingLists`: per tenant the account is in, the list of the outgoing key's verifications there (their row ids and
   statement hashes), signed by the **new** identity and the **new** Secret-Key proof key, naming the chain step's hash,
   sealed under that tenant's name key. Stored in `veyq_verification_closing (tenant_id, user_id, step_index,
   sealed_list)`. A tenant the publish could not reach (`blocked`) gets its list from `finishRotation` later. The
   server checks only presence per reachable tenant (it cannot open them); `validate_rotation_shape.py` gains the field.
4. **The compare shows the head** (ADR 0111 decision 14). The safety number stays the identity's 30 digits; beside it a
   **chain code** of 10 digits (two groups of 5) from the head hash, compared the same way; the QR payload becomes
   `veyqid:2:<fingerprint digest>:<head digest>`, and a `veyqid:1` scan is read as "update the other app" *(assistant,
   for owner review)*. A matching compare writes the verification of decision 2. Vectors: `fingerprint` gains the chain
   code cases.
5. **What a user sees** (ADR 0111 decision 15), per tenant: *new* while the pin's `firstSeenAt` is under 14 days old
   *(assistant, for owner review)*; *not verified*; *verified by …* (the names of the verifiers of the current head);
   *new identity, signed by the previous key* (built: `trust.rotated` on the profile); the anomaly. None of them blocks.
6. **Key Transparency stays later** (ADR 0111 decision 17): nothing to build; the chain and its head in the compare are
   the commitment a later log needs.

### Not in this CR

- ADR 0111 decision 18 (signing join records for members from before this model) → `changes/cr-join-records-for-existing-members.md`.
- An overview "people I share with" (ADR 0111 § Consequences) → inbox #562.
- Strict mode (`uc.spaces.require-verified-identities`), which stays planned and not buildable.
- Signed roles and removals (decision 1's residual).

## Inventory

| What | Where | Locator |
|---|---|---|
| The pin entry and every reader of `state`/`verified` | web | `git grep -n "PinState\|pinVerified\|markVerified\|'verified'\|foldTrust\|trustStates" -- src/web/src` |
| The safety number and its QR | crypto, web | `git grep -n "fingerprintDigits\|safetyCodePayload\|parseSafetyCode\|compareScan\|veyqid:" -- src/packages/crypto/src src/web/src` |
| The vectors | `v1.json` | `grep -n '"fingerprint"\|"identity_pin"' contracts/crypto/vectors/v1.json` |
| The profile and badge UI and its copy | web | `git grep -n "MemberTrustBadge\|PersonSafetyNumber\|MySafetyNumber\|safety\.\(state\|badge\|changed\)" -- src/web/src` |
| The join record as written at accept | contracts, crypto, web | `git grep -n "sealedJoinRecord\|join-record" -- contracts src` |
| The anchor function as written by the rotation CR | web | `git grep -n "anomaly\|keyChainHead\|getKeyChain" -- src/web/src` |
| The rotation request it extends | contracts, guard | `grep -n "RotateAccountKeysRequest:" contracts/api/account.openapi.yaml; grep -n "REQUEST_PROPS" scripts/validate_rotation_shape.py` |
| Tests asserting a personal verified mark (test trees too) | web, e2e | `grep -rln "markVerified\|pinVerified\|stateVerified" src/web/src tests/e2e` |
| Docs and site describing *verified* as private | `docs/`, `site/` | `git grep -n -i "verified\b.*private\|only you see\|nur du siehst\|safety number\|Sicherheitsnummer" -- docs/features docs/crosscutting site/src/content/docs` |

## Phases

### Phase 1: crypto and contracts

`@veyq/crypto`: the join-record verifier (owner → invite → newcomer), the verification and closing-list statements, the
chain code; vectors `verification`, `closing_list`, the `fingerprint` chain-code cases, each from an independent
implementation. Contracts: `putMyVerification`, `listVerifications`, `closingLists` on the rotation request; probes in
`OperatorBlindProbeTest`. **Done when:** `npm run generate:api` runs, crypto's check and the trust-chain guards are
green (`validate_guard_gaps.py` once, `validate_rotation_shape.py` changed). Expected red stated as in the siblings;
phases 1–3 merge together.

### Phase 2: backend

The two tables (cascading from `veyq_user` and the tenant), the operations, the closing-list presence check in the
publish. Tests first, each refusal with a control: a member writes and every member reads a verification, an outsider
gets `404`; a verification can only be written as the caller; the publish without a list for a reachable tenant is
refused and with one commits; an erased account leaves no row. **Done when:** `./gradlew check` and the erasure and
migration guards are green.

### Phase 3: web

The anchor per tenant at every wrap and accept site (`anchorIdentity`/`anchorChainKey` in `identityPins.ts`, now fed the join record); the
pin replaced off-chain (decision 1); verifications written and read; the closing list built in the rotation and in
`finishRotation`; the compare with the chain code; the displays.
Tests, each negative failing exactly its own check, each with a positive control:
- a join record whose invite-key signature is by a non-owner → not anchored; by an anchored owner → anchored;
- a non-owner without a join record → anomaly; an owner without one → first use;
- the chain from the join record reaches the served head → anchored; a head behind the pin → anomaly;
- a verification of the current head counts; of an old head does not; signed by a retired key counts only if the
  successor's closing list names it (**the thief case** of ADR 0111 decision 13: a statement signed by the old key after
  the rotation, not on the list, does not count);
- the compare: equal heads match, a withheld step (heads differ) does not.
Bite: drop the closing-list check → the thief case red; accept a non-owner signer → its negative red.
**Done when:** `npm run check` in `src/web/` is green, and the **browser pass** ran (headless system Chrome, German UI, a
throwaway three-member tenant): A and B compare (number and chain code), C sees "verified by A" on B's profile; B
secures the account, and C's view drops to "not verified" with "new identity, signed by the previous key".

### Phase 4: docs and site

ADR 0028 Decisions 1–3 and 5–6 and § What is still open rewritten as built; ADR 0104 decision 2 (the entry without
`state`); ADR 0111 § Status and § What stays open (the three items decided here, with their reasons); ADR 0023 (the
records in the tenant); `security-model.md` § Key-directory trust; `uc.spaces.verify-identity`,
`uc.spaces.enforce-verified-identities` (verified is shared in the tenant), `uc.spaces.require-verified-identities`
§ Alternate flows (ADR 0111 never blocks a write; A1 stands); `en.account.identity-pinning`; coverage YAMLs; site Use EN
and DE (comparing safety numbers, what "verified by" means), Security (trust model). **Done when:** the trust-chain
guards and `npm run check` in `site/` are green.

## Definition of done

- A member's key is anchored per tenant by an owner-rooted join record and the key chain; a non-owner without one is an
  anomaly; a root owner is trusted on first use.
- *Verified by …* counts for every member of the tenant, drops by itself on a rotation, and a retired key cannot add a
  verification after the rotation.
- The compare shows the chain head; a withheld step shows as a mismatch.
- The ADRs, use cases, coverage and site (EN and DE) say so. This CR is deleted after its Retrospective has moved.

## Retrospective

*(filled at implement time)*
