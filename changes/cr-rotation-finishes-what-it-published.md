---
id: cr-rotation-finishes-what-it-published
title: A key rotation keeps the old identity, sealed under the new Master Key, until every space it re-keys alone holds the new one
status: planned
satisfies: ["uc.account.rotate-keys"]
adrs: ["0060", "0072", "0028", "0024", "0109", "0026"]
invariants:
  - the server stores and serves only ciphertext and scope ids; the retired identities are sealed under the NEW Master Key, which no holder of the old key material has
  - a retired identity opens a pending scope's own grant and nothing else - it never enters the vault, never signs, and is never served or opened as the account's identity (its seal purpose is its own, ADR 0072)
  - the vault holds the PUBLISHED identity from the moment the publish is known to have committed; nothing after the publish depends on this tab staying open
  - a scope the publish left to the caller is finished from any device the account unlocks - the Emergency Kit path included - and only by a new epoch granted to and signed by the published identity
  - a failure the server did not answer with a declared refusal is an UNKNOWN outcome; the client never says "nothing was changed" without having read that the old identity is still published
  - a retry after a committed publish finishes it and never mints a second set of keys (uc.account.rotate-keys E7)
  - the retired identities are dropped in the same transaction that finishes the last pending scope
---

## Goal

Inbox #556. Today a rotation can leave a space the account was alone in, the personal space among them, under a key
that no device can open again. The publish overwrites the only server-held copy of the old identity, and the re-key of
those spaces runs afterwards with the old identity from this tab's memory. Anything that stops it there (a lost answer,
a closed tab, a re-key that fails, a space in another tenant) strands that space for good.

This CR makes the rotation **finishable**. The publish also stores the old identity, sealed under the new Master Key,
and the server keeps a list of the spaces the caller must still re-key alone. Every device that unlocks the account
finishes them, with the retired identity opening the old grant and the published identity receiving the new one. When
the last one is done, the server drops the retired identity in the same transaction. A lost answer is detected and
finished, never re-minted. That makes the use case's E7 ("finishes rather than re-mints") true for the first time.

**Size: three phases plus docs** (contracts and crypto, backend, web, docs). It is a CR, not a fix, because it adds a
stored ciphertext to the account and a new seal purpose, and because six decisions are the owner's (§ Open questions).

## Context & constraints

Measured at refine on `worktree-agent-ad7f3527cb5fb33c2` at `5a6c23679`, nothing changed. Every pointer was read in
this refine; a claim held from memory is marked *(remembered)*. A throwaway spike test (§ The defect, measured) was run
and deleted; it is not committed.

### What the code does today

- **The publish overwrites the only server-held copy of the old identity.** `RotationStore.applyRotation` sets
  `user.wrappedIdentityPrivateKey` to the new identity under `MK'` and rewrites the `PASSWORD` and `RECOVERY` wraps to
  open `MK'` (`src/backend/core/src/main/kotlin/me/raddatz/veyq/core/account/rotation/RotationStore.kt:53-59`). It
  deletes every other wrap (`:61`). Nothing stores the old identity, under any key.
- **The old identity is generated, not derived.** `rotateKeys` mints with `v1.generateIdentity()` and a random `MK'`
  (`src/web/src/features/account/rotation/rotateKeys.ts:130-131`). The old one cannot be re-derived from the Secret Key,
  the password or the old Master Key.
- **The sole-member re-key runs after the publish, with the vault's identity.** The publish loop is `:184-208`; the
  re-key of each `soleMemberScopeIds` entry is `:226-229`; the vault swap to the new identity is `:237`, after it.
  `rekeyAlone` calls `commitRotation` (`:321-334`), which opens the scope through `openById(scopeId, 'open')`
  (`src/web/src/features/spaces/scopes.ts:613`). That reads the scope row from `listMyScopes()` and opens the grant with
  `identityPrivate()`, the vault's (`scopes.ts:331-340`). So the old private key is used from the vault, and from
  nowhere else.
- **A grant to the old identity cannot be opened by the new one.** The seal is to the old identity's public key, and
  `verifyOwnGrant` checks the signature over a recipient that includes the identity bundle's digest, under the key from
  the vault (`src/web/src/features/spaces/grantTrust.ts:66-72`). With the new identity the check answers
  `bad_signature` (measured, § The defect, way 3).
- **A `failed` outcome does not stop the vault swap.** `setUnlocked(newMasterKey.slice(), identity.priv, 'cold')` runs
  whatever the outcomes were (`rotateKeys.ts:237`). `ScopeOutcome`'s doc says of `failed`: "the scope is unchanged and
  the old identity still reads it" (`:63-65`). That is true for whoever **else** holds the old identity, the attacker
  included. It is false for the account holder: no device of theirs holds it any more.
- **The panel promises a retry that does not exist.** `rotation.statusHint.failed` is "This space still has its old
  lock. Try again." (`src/web/src/i18n/index.ts:312`, DE `:2290`), and the Use page says the same
  (`site/src/content/docs/account/replace-your-keys.mdx:59`). No button re-runs a sole-member re-key, and a second
  rotation is refused (way 1).
- **E7 describes a window that no longer exists, and misses the one that does.** E7 says "interrupted between steps 4
  and 5 … an unpublished identity" (`docs/features/account/use-cases/uc.account.rotate-keys.md:145-147`). Steps 4 and 5
  are one transaction now (`RotationHandler.kt:162-210`). The real window is between step 5 and step 6.

### The defect, measured — four ways, not three

A throwaway spike (`spike556.test.ts`, the harness of `rotateKeys.test.ts:1-224` plus four cases; run with
`npx vitest run`, 4 passed, then deleted) asserted today's behaviour. Each case passed, which confirms each defect:

1. **The answer to a committed publish is lost.** The fake server committed (its `PASSWORD` floor became the posted
   wrap) and then rejected with a `FetchError`-shaped error. `rotateKeys` rejected with `FetchError`, not a
   `RotationRefused`, so the panel shows `rotation.errorFailed`: "Nothing was changed — please try again"
   (`KeyRotationPanel.vue:138-150`, `i18n/index.ts:300`). `rotateScope` and `setUnlocked` were never called. The retry
   was refused as **`credentialRejected`** ("That password or Secret Key didn't match"), because the floor now opens
   `MK'` and the vault holds `MK` (`src/web/src/features/account/coldCredential.ts:62-70`). The identity the server now
   holds is the new one. The personal grant is wrapped to the old one.
2. **The tab closes after the publish.** The posted request carries exactly the seven fields
   `identityPublicKey, wrappedIdentityPrivateKey, passwordWrap, recoveryWrap, secretKeyProofSignature, identityPins,
   deviceRegister` (measured), and `wrappedIdentityPrivateKey` opens under `MK'` to the **new** identity. Nothing the
   account keeps leads back to the old identity once the tab's memory is gone.
3. **A sole-member re-key fails.** With `listScopeMembers` rejecting, the outcome is `failed`, `rotateScope` is never
   called, `setUnlocked` receives the new identity, and `verifyOwnGrant` refuses the personal grant for it with
   `bad_signature`.
4. **A space in another tenant fails every time (found in this refine).** The server's split counts the caller's
   memberships across **every** tenant (`MembershipStore.memberCountsByScopeFor`,
   `src/backend/core/src/main/kotlin/me/raddatz/veyq/core/membership/MembershipStore.kt:75-82`; the split at
   `RotationHandler.kt:203-204`). The client opens a scope only from `listMyScopes()`, which is the **active tenant's**
   rows (`ScopesResource.kt:92-93` reads `activeTenantId()`; contract `scopes.openapi.yaml:89-90`). A personal space
   exists once per member **per tenant** (`ScopesStore.kt:139-143`), and an account can belong to several tenants
   (`listMyTenants`, `scopes.openapi.yaml:838-846`). So every sole-member space outside the active tenant meets
   `cannot open scope to rotate` (`scopes.ts:614`), reads `failed`, and is stranded by the vault swap. Measured in the
   spike: `[{personal, secured}, {other tenant's scope, failed}]`, `rotateScope` called once. This one is not a race. It
   happens to every multi-tenant account on every rotation.

### Is there any path that recovers a stranded space today? No.

Looked for, each read:
- **Another device's vault.** Every other session family is revoked in the publish (`RotationHandler.kt:207`), and every
  device wrap is deleted (`RotationStore.kt:61`). A device that signs in again opens `MK'` and the new identity.
- **The session vault.** It caches only the Master Key, sealed under a per-browser key
  (`src/web/src/features/account/rememberMe/sessionVault.ts:17-19`, `:52-64`). After a rotation the cached `MK` no
  longer opens the served `wrappedIdentityPrivateKey`, so `restoreVaultFromCache` drops the cache and falls to the
  floor (`src/web/src/features/account/unlock/unlock.ts:162-183`). The floor opens `MK'`.
- **The scope-rotation worker.** It resumes only node re-keys of scopes already at their new epoch (`resumeRekeys`,
  `src/web/src/features/tasks/scopeRotationWorker.ts:111-117`) and claims queued jobs for **other** owners. No code
  mints a sole-member epoch except `rekeyAlone` (`git grep -n "rekeyAlone\|soleMember" -- src/web/src` finds only
  `rotateKeys.ts`).
- **The Emergency Kit.** It opens the `RECOVERY` wrap, which now holds `MK'` (`RotationStore.kt:55-56`). Recovery keeps
  the Master Key (`en.account.key-model.md:68`: "only a rotation replaces it").
- **Pairing envelopes.** Superseded in the publish (`RotationHandler.kt:191`). *(Remembered, not re-read:)* their
  plaintext would be the old Master Key and identity, readable only by the device they were sealed to.

So a stranded space is lost, apart from an operator restoring a database backup, which is outside the product.

### The shared (queued) spaces are not stranded

The covering rotation is minted by another owner, who re-grants every member except the rotator's changed key and
chain-links the new epoch to the old one (`commitRotation`, `scopes.ts:606-661`). The new identity is re-admitted by
grant delivery once a human accepts it (uc step 7, ADR 0060 Decision 4). It never needs the old identity. *Stalled*
spaces (E4) are a known, stated state, not this defect.

### What else depends on the old Master Key after the publish

`resealBestEffort` re-seals the node pins and re-tags the item marks with the old Master Key and each scope's `E_0`
(`rotateKeys.ts:213-222`, `:282-295`), and `carryMarks` moves this device's trust marks (`:233`). A closed tab loses
these. By ADR 0068 an unreadable pin set reads as empty, and an item mark whose tag does not verify reads as someone
else's (`rotateKeys.ts:291-293`). This device's marks restart from nothing under `MK'`, which is what every other device
already does after a rotation. None of these strands a space. They stay best-effort (Q2).

### Where the client could finish from, and what is Host-bound

- `ScopeAccess.check` gates a scope by membership, tenant status and deletion, **not** by the request's Host
  (`src/backend/core/src/main/kotlin/me/raddatz/veyq/core/membership/ScopeAccess.kt:43-52`). So `rotateScope` and
  `listScopeMembers` work for a scope in another tenant.
- Only the opener is Host-bound: `listMyScopes` (above), and so `openById` and `rekeyScope`, which opens through
  `openScopeById` (`src/web/src/core/node/rekey/rekeyScope.ts:29`, `:65`).
- `rotateScope` refuses a suspended tenant and a group in its deletion grace (`ScopesHandler.kt:549-550`). A space there
  cannot be finished until that changes.

### What every unlock passes through

Every vault opening goes through `openVault` (`unlock.ts:41-52`; callers `:121`, `:146`, `:173`, `:204`), apart from
change-password (`changePassword.ts:98`) and the rotation itself. The scope-rotation worker polls while the vault is
unlocked, runs once immediately on start and every 45 s (`src/web/src/features/tasks/scopeRotationPolling.ts:10-25`).

## Options

Each is judged on the brief's six questions. The attacker of ADR 0060 holds the old Master Key and the old identity
(a phished device link), and not the Secret Key.

### (a) Keep the old identity, sealed under `MK'`, until every sole-member space holds the new one — **recommended**

The publish carries `retiredIdentities`: the old identity (and any still retired from an earlier rotation) sealed under
`MK'` with a purpose of its own. The server stores it and, in the same transaction, a pending row per sole-member scope.
Any unlocked device finishes the pending scopes: it opens the old grant with the retired identity, mints the next epoch,
chain-links it, and grants it to the published identity, signed by it. A commit on a pending scope deletes its row, and
the last one nulls the retired blob, in the same transaction.

- **Operator-blind:** one more ciphertext, and a list of scope ids the server already derives from memberships. No key.
- **ADR 0060's attacker:** gains nothing. The blob is under `MK'`, which they lack. A *later* compromise of `MK'` yields
  the retired identity too, but only while a scope is still pending, and `MK'` already yields the new identity, which
  reads every finished scope back to its first epoch. What the old identity adds there is the old grants of shared
  scopes not yet covered, and only until the blob is dropped (normally seconds after the publish).
- **Contract:** a required request field, a read of the pending scopes, a new seal purpose and its vector.
- **Migration:** a new column or table. No account rotated before this has a retired identity, so a space stranded
  before cannot be recovered (stated, § Not in this CR). There is no release yet (`src/backend/CLAUDE.md:215-219`).
- **Idempotent:** a scope finished by another device answers `409 stale_epoch` and is gone from the pending read.
- **Lost answer:** detected (decision 7), then finished.
- **Covers all four ways,** a suspended tenant and a deleting group included: they stay pending until they can be
  finished.

### (b) Re-key the sole-member spaces inside the publish

The publish carries each sole-member scope's rotation (epoch, chain-link, the new identity's grant, admissions), and
the server applies them in the same transaction, refusing unless the set equals its own split.

- **Operator-blind, attacker:** fine. It also closes the window in which someone holding the password **and** the old
  identity could commit their own epoch on a sole-member scope (Q5). It keeps no key.
- **Why not:** a sole-member scope that cannot be rotated at publish time has two outcomes, both bad. Either the whole
  rotation is refused (a suspended tenant, a group in deletion grace, a grant this device refuses below its epoch
  floor, `scopes.ts:289`), which blocks the remedy for a compromise on something the user may not control. Or the
  scope is left out, and it is stranded exactly as today. It also needs a cross-tenant read before the publish (way 4),
  moves `ScopesStore.rotate` into the account transaction with a lock order shared with the queued originations
  (`RotationHandler.kt:201-205`), and still needs the lost-answer detection. (a) handles all of that with less.
- It can be added later on top of (a), as hardening of the happy path, if Q5 says the window matters.

### (c) A client-side resume record

The old identity sealed under a non-extractable per-browser key in IndexedDB, as the session vault does.

- Helps only this browser on this device: not another device, not the Emergency Kit, not a cleared profile. It puts
  the old private key into local storage. It does nothing for way 4. Rejected.

## Decisions this CR takes

1. **Option (a)** (Q1).
2. **`retiredIdentities` is a required field of `RotateAccountKeysRequest`.** Its plaintext is the list of every
   identity still retired on the account: this rotation's old identity, plus each one the current retired blob holds
   (opened under the old Master Key). A second rotation while scopes are pending would otherwise strand them. Required
   and always sent, even with nothing pending: a rotation without it is refused at the boundary, and
   `validate_rotation_shape.py` pins it (an eighth field). It is not a mode and not a subset: the server cannot read
   it, and every sole-member scope becomes pending regardless.
3. **It is not bound into `secretKeyProofSignature`.** ADR 0060 Decision 1's reasoning for the two documents holds: the
   only writer is this request, which already needs the proof, and a malicious server can replace a stored blob anyway,
   which is a denial of service it can do today. Binding it would change the conformance-pinned statement
   (`secret_key_proof` vector) for no defended case.
4. **A seal purpose of its own: `retired-identities`, purpose only** (under the account's own Master Key, ADR 0072
   Decision 2). It must differ from `identity-priv` so that a server can never serve a retired identity as the
   account's identity: a vault that adopted the old identity would grant new epochs to the key the attacker holds. The
   encoding is a count and the serialized private bundles, each length-prefixed, as the suite's other lists are
   *(remembered: the shape, to be read against `v1.ts` at implement time)*. A vector in `contracts/crypto/vectors/v1.json`,
   generated by an independent implementation per that file's note, and a conformance case that a `retired-identities`
   blob does not open as `identity-priv` and the reverse.
5. **The server keeps the pending set and drops the blob itself.**
   - The publish's transaction writes one pending row per sole-member scope (`scopeId`, the epoch at the rotation) and
     the blob. A second rotation keeps existing rows and adds new ones.
   - A `rotateScope` commit on a pending scope deletes its row in `ScopesStore.rotate`'s transaction. When none is left,
     the blob is nulled in the same transaction. A scope that is purged, or a membership that ends, takes its row with
     it. The account purge deletes both (`AccountPurge.kt:159` deletes by explicit list).
   - No client call is needed to "finish": a finish call could be lost the same way the publish's answer was.
6. **A pending read, Host-independent.** One operation answers the blob and, per pending scope, what `toOpenedScope`
   reads from a `ScopeSummary` (id, kind, tenant, current and granted epoch, the caller's own grant and its signature
   fields). It is Host-independent like `listMyTenants`, so the finish covers every tenant from wherever the user is
   (Q4). Nothing is added to `AccountKeyMaterial`, which several flows serve.
7. **The publish's failures are split by whether the server answered.**
   - A **declared refusal** means nothing was written: `409 stale_revision` (retried, as today), `413` (inbox #553's
     `requestTooLarge`), `403 secret_key_proof_failed`, `409 rotation_unavailable`, `400`, `401`.
   - **Anything else** (a `FetchError`, a `5xx`, a proxy's `502`/`504`) is an **unknown outcome**. The client re-reads
     the account's published identity:
     - the minted one: the publish committed. The client continues with the keys it still holds, reading the pending
       set from the server instead of the lost answer;
     - the old one: nothing was published, and the copy says so (`rotation.errorFailed` stays true here);
     - the re-read fails too: the client locks the vault and says it could not confirm the outcome and to unlock again.
       Whatever the server holds, the next unlock opens it and finishes what is pending (Q3).
8. **A retry never re-mints a committed rotation.** The cold-credential gate tells a floor that opened to *another*
   Master Key apart from one that did not open. Change-password keeps reading both as `credentialRejected`. For the
   rotation, a floor that opens to `MK''` while a retired blob opens under `MK''` to a list holding the vault's
   identity is this account's committed rotation: the typed password and Secret Key are a cold unlock, so the client
   re-opens the vault from them and finishes. It mints nothing and posts no second rotation. This also covers the race
   in which the re-read of decision 7 saw the old identity and the request committed after it.
9. **The vault swaps to the published identity as soon as the publish is known to have committed**, before the
   re-seals and the re-keys. The finish of the sole-member scopes takes the retired identities explicitly. They are
   never handed to `setUnlocked`, never sign, and are zeroed after use. `commitRotation` gains a way to open a scope's
   grant from a given row with a given identity, instead of `listMyScopes` and the vault. Its other three callers keep
   today's path (`git grep -n "commitRotation(" -- src/web/src`).
10. **One finish, three callers.** `finishRotation` re-keys every pending scope (members read, refused if not exactly
    one, as today), then runs the node re-key (ADR 0109) for the ones it secured. It is called:
    - by `rotateKeys` right after the publish (or after decision 7 found it committed);
    - as the first step of `runScopeRotationWorker`, before `resumeRekeys`, so every unlocked device finishes, the
      Emergency Kit's included. It runs on start and on every poll (`scopeRotationPolling.ts:22-25`). A finished scope
      refreshes the active space;
    - by the retry of decision 8.
    A scope it cannot finish now (offline, a suspended tenant, a group in deletion grace) stays pending and is tried on
    the next pass. A finished scope in another tenant may still read *re-keying* until that tenant is opened, because
    `rekeyScope` opens through the active tenant (`rekeyScope.ts:65`). That is stated, not fixed (§ Not in this CR).
11. **The best-effort re-seals stay best-effort, and identities only are retired** (Q2). The node pins, the item marks'
    tags and this device's marks need the old Master Key, which is not retained. A closed tab loses them as ADR 0068
    already allows. E7 names that.
12. **What the panel says.**
    - `ScopeOutcome` keeps `secured` and `rekeying`. `failed` becomes **`pending`**: the new epoch is not in yet, the old
      identity (and whoever else holds it) still reads what is added there, and veyq finishes it from any device that
      unlocks the account. The doc comment says exactly that.
    - `rotation.status.pending` and `rotation.statusHint.pending` replace the `failed` pair, with no "Try again": the
      finish is automatic. A new `rotation.errorUnknownOutcome` covers decision 7's third case. EN and DE proposed in Q6.
    - `rotation.errorFailed` is shown only where nothing can have been written: before the publish, or after the
      re-read found the old identity.
13. **The durable docs move with it** (Phase 4, text proposed in § Proposed durable text):
    - ADR 0060: an amendment in § Consequences, the request's field list in Decision 1, and alternatives (b) and (c).
    - The use case: Postconditions, steps 4, 6 and 9, and E7 rewritten.
    - `security-model.md` § Key-directory trust, the rotation bullet (`:592-599`).
    - `en.account.key-model.md`: the Master Key row (`:68`) and § Rotation after a compromise (`:137-150`).
    - The coverage YAML gains `interrupted-rotation-finishes`.
    - The site: the Use page EN and DE (`replace-your-keys.mdx:56-59` and its DE twin `:61`), and the Security page's
      rotation paragraph (`site/src/content/docs/security/key-hierarchy.md:195-205`).

### Not in this CR

- **A server-side check that a pending scope's new grant is signed by the published identity** (Q5). Without it,
  someone holding the password **and** the old identity can sign in after the rotation and commit an epoch of their own
  on a pending scope before the finish does. That denies the holder the scope. It is today's window too (milliseconds
  in the happy path, forever in the stranded cases), so (a) is never worse than today.
- **A pending scope that gained a member.** `rekeyAlone` refuses it today (`rotateKeys.ts:323-324`) and the finish keeps
  that refusal: Decision 4 does not let the rotator drive a shared scope's re-grant. Such a scope stays pending and the
  retired identity with it. It can only arise if the rotator's own old identity admitted someone between the publish
  and the finish (Q6 names it as a residual).
- **A cross-tenant node re-key** (decision 10's last point).
- **Recovering a space stranded before this CR.** No copy of its identity exists anywhere.

## Ordering with `cr-rotation-refused-as-too-large` (#553)

Both edit the publish loop's `catch` (`rotateKeys.ts:204-207`), the panel's `onError` (`KeyRotationPanel.vue:138-150`),
the rotation copy beside `errorFailed`, the use case's exception flows, its coverage YAML and the Use page in EN and DE.

- **#553 lands first.** It is decided, small and ready. It adds the `413` branch, `RotationRefusal` `'requestTooLarge'`
  and E10. This CR's decision 7 lists `413` among the declared refusals and leaves that branch as it is.
- **This CR then restates #553's two controls.** #553's `httpError(500)` and `FetchError` cases assert "rethrown as they
  are, not `requestTooLarge`". After decision 7 they are not rethrown as they are: they are an unknown outcome and are
  re-read. The new assertion keeps #553's point (never `requestTooLarge`) and adds where they now go. #553's Q4 kept
  that control for exactly this hand-over.
- **E numbering:** #553 adds E10. This CR rewrites E7 in place and adds no number.
- **Do not run the two web phases in parallel.** They change the same `catch` and the same panel arm.

## Inventory

Every locator was run at refine.

| What | Where | Locator |
|---|---|---|
| The request, its result and the pending read | `contracts/api/account.openapi.yaml` | `grep -n "RotateAccountKeysRequest:\|RotateAccountKeysResult:\|operationId: rotateAccountKeys" contracts/api/account.openapi.yaml` |
| The shape guard | `scripts/validate_rotation_shape.py` | `grep -n "REQUEST_PROPS" scripts/validate_rotation_shape.py` |
| Every builder of the request (each reddens on a new required field) | backend tests, web | `git grep -l "rotateAccountKeysRequest\|RotateAccountKeysRequest" -- . ':!**/generated/**'` (finds the contract, the guard, `AccountRotationResource.kt`, `CredentialResetRaceSliceTest`, `IdentityPinsSliceTest`, `RotationSliceTest`, `rotateKeys.ts`, `rotateKeys.test.ts`, and a prose line in `uc.account.change-password.md`) |
| The seal purpose, its vector and conformance | `src/packages/crypto/src/seal.ts`, `contracts/crypto/vectors/v1.json` | `grep -n "'identity-priv'" src/packages/crypto/src/seal.ts` |
| The publish's transaction | `RotationHandler.kt`, `RotationStore.kt` | `grep -n "applyRotation\|originateScopeRotationExcluding" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/account/rotation/*.kt` |
| The commit that deletes a pending row | `ScopesStore.rotate` | `grep -n "fun rotate(" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/spaces/scope/ScopesStore.kt` |
| The account purge | `AccountPurge.kt` | `git grep -n "MasterKeyWrapEntity.delete" -- src/backend/core/src/main` |
| A new operation's classification | `OperatorBlindProbeTest` | `git grep -n "class OperatorBlindProbeTest" -- src/backend` |
| The migration and its pin | `src/backend/core/src/main/resources/db/migration/`, `scripts/applied_migrations.sha256` | `ls src/backend/core/src/main/resources/db/migration/ \| tail -1` |
| The client flow | `rotateKeys.ts` | `grep -n "setUnlocked\|rekeyAlone\|resealBestEffort\|isCodedError" src/web/src/features/account/rotation/rotateKeys.ts` |
| The opener the finish needs | `scopes.ts` | `grep -n "async function openById\|export async function commitRotation" src/web/src/features/spaces/scopes.ts` |
| Every `commitRotation` caller (they keep today's path) | web | `git grep -n "commitRotation(" -- src/web/src` |
| The gate's two refusals | `coldCredential.ts` | `grep -n "credentialRejected" src/web/src/features/account/coldCredential.ts` |
| The worker pass | `scopeRotationWorker.ts` | `grep -n "resumeRekeys" src/web/src/features/tasks/scopeRotationWorker.ts` |
| Every reader of the outcome type | web | `git grep -n "ScopeOutcome\|RotationResult\|'failed'" -- src/web/src/features/account/rotation` |
| The copy, EN and DE | `src/web/src/i18n/index.ts` | `grep -n "failed: 'This space still has its old lock\|failed: 'Dieser Bereich hat noch sein altes Schloss\|errorFailed: 'Your keys\|errorFailed: 'Deine Schlüssel" src/web/src/i18n/index.ts` |
| The durable docs | ADR 0060, the use case, `security-model.md`, `en.account.key-model.md`, the coverage YAML | `git grep -n -i "finishes rather than re-mints\|old identity still reads\|Try again" -- docs src/web/src/features/account/rotation` |
| The site pages | Use EN and DE, Security | `git grep -n -i "old lock\|altes Schloss\|Rotation after a compromise" -- site/src/content/docs` |

Second angle on the readers of the old behaviour: `git grep -n -i "try again\|Versuch es noch einmal" -- site/src/content/docs/account site/src/content/docs/de/account`
finds the two `Old lock` lines per language, of which only the first (the sole-member one, `:59` / `:61`) changes. The
shared spaces' *Start it again* is the re-queue of E3 and stays.

## Phases

### Phase 1 — contracts and crypto

- The `retired-identities` purpose in `seal.ts`, its encoding in `v1.ts`, its vector and the separation case (decision 4).
- `RotateAccountKeysRequest.retiredIdentities` (required), the pending read (decision 6), and the result's description
  saying the sole-member scopes stay pending until re-keyed.
- `validate_rotation_shape.py`: the eighth property, required, and its docstring on why it is not a mode.
  **Bite:** drop it from `required` in a snapshot of the spec, the guard goes red; restore from the snapshot.
- The new operation's line in `OperatorBlindProbeTest` (a probe, or an exclusion with its reason).

**Done when:** `npm run generate:api`; `npm run check` in `src/packages/crypto`; the trust-chain guards green. Expected
red, stated and not stubbed (`changes/README.md` § Phases): `src/web/` at the request literal in `rotateKeys.ts`
(`rotateAccountKeysRequest: {`, missing `retiredIdentities`); `./gradlew check` at `AccountRotationResource` if the
pending read joins the existing `account-rotation` tag, and at the test builders of the request. Run each check once to
see the red it names.

### Phase 2 — backend

Decisions 2, 5 and 6. A migration (a nullable ciphertext for the blob, a table for the pending rows, both deleted with
the account and the scope), the writes in the publish's transaction, the row's deletion and the blob's nulling in
`ScopesStore.rotate`, the pending read, the purge.

**Reproducing tests first, red against the handler as Phase 1 left it** (it accepts the field and ignores it), in
`RotationSliceTest`, following `aSoleMemberScopeIsLeftForTheCallerAndQueuesNothing` (`:171`) and its seeds:
- `aRotationKeepsTheOldIdentitySealedUntilItsSoleScopesAreRekeyed`: after the publish the pending read serves the blob
  and the personal scope; a `rotateScope` by the caller on it empties the set **and** nulls the blob. Control on the
  same predicate: with two pending scopes, committing one leaves the blob served.
- `aSecondRotationWhilePendingKeepsTheFirstsScopes`: the first rotation's pending row survives the second.
- `aCommitOnAnotherScopeRemovesNoPendingRow` and `aStaleCommitRemovesNoPendingRow` (a `409 stale_epoch` leaves it).
- `aRolledBackRotationLeavesNothingPending`: a `stale_revision` rollback (`aStalePinStoreRevisionRollsTheWholeRotationBack`,
  `:290`) writes no blob and no row.
- The purge: an erased account leaves no row and no blob.

**Bite** each by removing its write (the row's delete, the blob's nulling, the carry-over); each named test goes red.

**Done when:** `./gradlew check` green.

### Phase 3 — web

Decisions 7 to 12.

**Reproducing tests first, red against the untouched `rotateKeys.ts`** (the regenerated client aside), in
`rotateKeys.test.ts`, a new `describe('a rotation interrupted after the publish finishes (E7, inbox #556)')`, built on the
harness the spike used (`fakeServer`, `heldAt0`, `storedFloor`, `rotateKeys.test.ts:153-224`). The fake gains a
committed state (floor, published identity, blob, pending rows) and the pending read:
- **Way 1:** the publish commits, then rejects with a `FetchError`-shaped error. The call ends with the personal scope
  `secured`, `rotateScope` called with a grant that opens under the vault's new identity, and `rotateAccountKeys` called
  once. Red today: it rejects. A second case: the vault still on `MK`, the server committed, the user presses again. No
  second `rotateAccountKeys`, the vault ends on the published identity, the scope is `secured`. Red today:
  `credentialRejected`.
- **Way 2:** the request's `retiredIdentities` opens under `MK'` (recovered from `passwordWrap` with the password and
  the Secret Key, as the spike did) to a list holding `OLD_ID`. Then a fresh run of the worker pass, with a vault opened
  from `MK'` alone, re-keys the personal scope: the new grant opens under the new identity and its link opens to `e0`.
  Red today: the field does not exist.
- **Way 3:** `listScopeMembers` rejects once. The outcome is `pending`, `setUnlocked` already holds the new identity,
  and the next worker pass secures the scope. Red today: `failed`, and nothing retries.
- **Way 4:** a pending scope absent from `listMyScopes` (another tenant) is finished from its pending row. Red today:
  `failed`.
- **Unknown outcome:** the re-read fails too. The vault is locked, and the copy is `errorUnknownOutcome`, never
  `errorFailed`. Control: a `FetchError` with the re-read showing the old identity shows `errorFailed`, and the vault
  stays on `MK`.
- **#553's controls restated:** `httpError(500)` and the `FetchError` are never `requestTooLarge`, and both are re-read.

**Bite tests for the fix**, each with its lever:
- The retired identity never reaches the vault and never signs. Assert, as `rotateKeys.test.ts:450-451` does, that the
  finish's grant verifies under the new key and not under `OLD_ID`'s, and that no `setUnlocked` call carries `OLD_ID`.
  Lever: pass the retired identity as the rotator. Red.
- A pending scope with a second member is refused and stays pending (the existing `:480-501` case, moved onto the
  finish). Lever: drop the member-count check. Red.
- A second rotation while pending carries the first's retired identity. Lever: seal only this rotation's old identity.
  Red.
- Decision 8 fires only on "the floor opened to another Master Key **and** the blob holds the vault's identity". Control:
  a wrong Secret Key is still `credentialRejected`, and so is a floor that opens to a Master Key whose blob does not hold
  the vault's identity. Lever: drop the identity check. The second control goes red.
- `KeyRotationPanel.test.ts`: the `pending` row's copy, and `errorUnknownOutcome` for its error.

Snapshot and restore every lever, never `git checkout` (`changes/README.md` § Bite-proof a guard by snapshot-and-restore).

**Done when:**
- `npm run check` in `src/web/` is green;
- **browser pass** (headless system Chrome per the owner's machine notes, the owner's German UI, a worktree-built
  backend, never a second `quarkusDev`):
  - **way 1 live:** a Playwright route on `POST /api/account/rotation` that sends the request (`route.fetch()`) and then
    aborts it. The panel shows the result, not "Es wurde nichts geändert";
  - **way 2 live:** the same route, then the page closed before the finish; a fresh context signs in with the password
    and Secret Key, and the personal space opens with its content;
  - if this phase cannot run a browser, it says so, and the pass stays open here.

### Phase 4 — the durable docs and the site

Decision 13, with the text of § Proposed durable text. The coverage YAML's new criterion lists Phase 2's and Phase 3's
tests.

**Done when:** the trust-chain guards (`scripts/validate_*.py`, without `validate_guard_gaps.py` unless a guard changed,
which Phase 1's did: run it once there), and the site builds.

**Not run here:** the release-candidate e2e and the fuzzer (owner rule 2026-10-19: once, at V1's end). A leg in
`src/web/src/features/tasks/scopeRotation.e2e.test.ts` (it already calls `rotateKeys`, `:163`) that abandons the
rotation after the publish and finishes it from a fresh sign-in is written in Phase 3 and runs in that pass.

## Proposed durable text

**ADR 0060, § Consequences, a new bullet** (and Decision 1's field list gains "the identities this rotation retires,
sealed under the new Master Key"):

> **The old identity is retired, not discarded, until the rotator's own spaces hold the new one.** A space the rotator
> is alone in is re-keyed by the rotator, with the old identity opening its current grant. That runs after the publish,
> so the publish also stores every retired identity, sealed under the new Master Key with a purpose of its own, and the
> server keeps the list of those spaces. Any device that unlocks the account finishes them, and the server drops the
> retired identities in the transaction that finishes the last one. The old key's holder gains nothing: the blob opens
> only under the new Master Key. A later compromise of the new Master Key yields the retired identities only while a
> space is still pending. Discarding the old identity at the publish stranded such a space whenever the re-key did not
> run on that tab (inbox #556).

**ADR 0060, Alternatives rejected:**

> - **(j) Re-keying the sole-member spaces inside the publish.** It keeps no key, but a space that cannot be rotated at
>   that moment (a suspended tenant, a group in deletion, a grant the device refuses) would either block the rotation
>   or be stranded.
> - **(k) A resume record in the browser.** It helps one browser on one device, and puts the old private key in local
>   storage.

**The use case, E7, replacing today's text:**

> **E7 — the rotation is interrupted after the publish.** The answer is lost, the tab closes, a space's re-key fails,
> or a space is in another tenant. The server holds the new keys, every retired identity sealed under `MK'`, and the
> list of spaces the holder must still re-key alone. Any device that unlocks the account, through the Emergency Kit
> too, finishes them, and they read *pending* until then. A retry finishes rather than re-mints: the password and
> Secret Key that open the floor to the new Master Key are a cold unlock. A failure the server did not answer is
> reported as unknown, and the holder is asked to unlock again, never told that nothing changed. What needs the old
> Master Key (the Memory pins, the item marks' tags) stays best-effort, as step 4 says.

**The use case, step 6, appended:** "Until it is done the space reads *pending*, and every device that unlocks the
account finishes it (E7)." **Step 9:** *secured*, *re-keying* or *pending*.

**`security-model.md` § Key-directory trust, the rotation bullet, appended:**

> Until every space the rotator is alone in holds the new identity, the server keeps the old identity sealed under the
> new Master Key, and drops it in the transaction that finishes the last one.

**`en.account.key-model.md`:** the Master Key row's "Opens `wrappedIdentityPrivateKey`" gains "and, while a rotation is
unfinished, the identities it retired"; § Rotation after a compromise gains the ADR bullet's first two sentences.

**Site, Use (EN), the `Old lock` line under the sole-member list becomes:**

> - **Not yet.** The new lock isn't in yet. veyq fits it the next time any of your devices opens veyq. Until then, the
>   old key reads what is added here.

DE: **Noch nicht.** Das neue Schloss ist noch nicht drin. veyq baut es ein, sobald eines deiner Geräte veyq das
nächste Mal öffnet. Bis dahin liest der alte Schlüssel, was hier dazukommt.

**Site, Security (`key-hierarchy.md` § Rotation after a compromise), one sentence:** "The old identity is kept, sealed
under the new Master Key, until every space the account re-keys alone holds the new one."

## Definition of done

- A rotation whose answer is lost, whose tab closes, whose sole-member re-key fails, or whose sole-member space is in
  another tenant ends with that space at a new epoch granted to the published identity, finished by the next device
  that unlocks the account. Each way has a test that was red on the untouched code.
- A retry after a committed publish posts no second rotation.
- No copy says "nothing was changed" where the client did not read the old identity still published.
- The retired identities are gone from the server once nothing is pending, and nothing ever opens them as the account's
  identity.
- E7, the ADR amendment, the security model, the key model, the coverage YAML and the site say so.
- This CR is deleted, after its Retrospective's findings have moved to where they apply.

## Open questions (owner)

**Q1 — Which design: (a) keep the old identity under `MK'`, (b) re-key inside the publish, or (c) a browser resume
record?**
- **Recommendation: (a).**
- **Reason:** it is the only one that covers all four ways, including a space that cannot be rotated at publish time
  and a space in another tenant, without refusing the rotation. It keeps the server blind, and ADR 0060's attacker gains
  nothing, because the blob opens only under `MK'`. (b) is worth adding later only if Q5's window matters.

**Q2 — Retire the identities only, or the old Master Key too?**
- **Recommendation: identities only.**
- **Reason:** the old Master Key would let the finish also repair the Memory pins, the item marks' tags and this
  device's marks after a closed tab. ADR 0068 already lets each of them degrade, and none of them strands a space.
  Retaining less key material is the smaller claim to defend.

**Q3 — An unconfirmed outcome: re-read and continue (with lock as the fallback), or always lock and ask for a fresh
unlock?**
- **Recommendation: re-read and continue, lock only when the re-read fails too.**
- **Reason:** in the common case (the answer lost, the network back) the user sees the result at once, with no second
  prompt. Locking is the right answer only where the client cannot know. Decision 8 covers the remaining race either
  way.

**Q4 — Finish every tenant's pending spaces from wherever the user is, or each tenant when it is opened?**
- **Recommendation: from wherever the user is (a Host-independent pending read).**
- **Reason:** `rotateScope` and the member read are not Host-bound (`ScopeAccess.kt:43-52`). Waiting for a visit leaves a
  personal space of another tenant open to the old identity for as long as the user stays away. Only the node re-key
  waits for a visit.

**Q5 — Should the server also refuse a commit on a pending space unless its new grant is signed by the published
identity?**
- **Recommendation: no, as a separate inbox row.**
- **Reason:** it closes the window in which someone holding the password **and** the old identity commits their own
  epoch first. That window exists today (and forever in the stranded cases), so this CR is never worse than today. The
  check needs the grant statement's encoding in Kotlin, pinned to the same vector as the client's (`identityVerify` is
  Ed25519, `v1.ts:614-620`). That is a conformance obligation of its own.

**Q6 — The copy and one residual.**
- **Copy, proposed:**
  - EN `rotation.status.pending`: "Not yet". `rotation.statusHint.pending`: "The new lock isn't in yet. veyq fits it the
    next time any of your devices opens veyq. Until then, the old key reads what is added here."
  - EN `rotation.errorUnknownOutcome`: "We couldn't confirm whether your keys were replaced. Unlock again: if they
    were, veyq finishes what's left; if not, nothing was changed."
  - DE `rotation.status.pending`: "Noch nicht". `rotation.statusHint.pending`: "Das neue Schloss ist noch nicht drin. veyq
    baut es ein, sobald eines deiner Geräte veyq das nächste Mal öffnet. Bis dahin liest der alte Schlüssel, was hier
    dazukommt."
  - DE `rotation.errorUnknownOutcome`: "Wir konnten nicht prüfen, ob deine Schlüssel ersetzt wurden. Entsperre veyq
    noch einmal: Wurden sie ersetzt, macht veyq den Rest fertig; wenn nicht, wurde nichts geändert."
- **Residual, proposed as stated rather than fixed:** a pending space that gained a member stays pending, and the
  retired identity with it (§ Not in this CR).
- **Recommendation: take the copy, and state the residual in E7.**
- **Reason:** the copy follows the panel's existing lock wording (`i18n/index.ts:302-313`) and drops the "Try again"
  that no button carries out. The residual needs the rotator's own old identity to admit someone inside the finish's
  window, which a fix would cost a hook in membership acceptance to close.

## Retrospective

*(filled at implement time)*
