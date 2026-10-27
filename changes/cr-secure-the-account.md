---
id: cr-secure-the-account
title: Securing the account replaces the identity, the Master Key and the Secret Key, and keeps every space readable from the moment it publishes
status: planned
satisfies: ["uc.account.rotate-keys"]
adrs: ["0060", "0054", "0028", "0109", "0075", "0024", "0026", "0003", "0104"]
invariants:
  - the server stores and serves only ciphertext, scope ids, epoch indices and public keys; no retired identity, Master Key or Secret Key is kept anywhere, on the server or in the browser
  - from the moment the publish commits, every scope the publish counted as the rotator's alone holds a grant of its CURRENT epoch to the published identity - no state after it can lose access
  - a self-grant in the publish is accepted only if it verifies under the identity published in the same request, and the set of self-grants equals the server's own split, or nothing is written
  - the publish is gated on the CURRENT Secret Key (its proof key's signature), and re-registers the proof key only under that signature and a possession signature of the new one
  - the publish is not sent before the new Emergency Kit was saved and confirmed
  - the PASSWORD and RECOVERY wraps the publish writes open under the NEW Secret Key only
  - the new epoch of a re-granted scope (step 3) is minted, granted and signed by the PUBLISHED identity only, from any device the account unlocks
  - a publish failure the server did not answer with a declared refusal is an UNKNOWN outcome; the client never says "nothing was changed" without having read that the old identity is still published
  - a retry after a committed publish finishes it and never mints a second set of keys (uc.account.rotate-keys E7)
  - shared scopes keep today's behaviour here (a queued rotation by another owner, the rotator excluded, ADR 0060 Decision 4); the tenant's switch is cr-rotated-member-re-grants-itself
---

## Goal

Inbox #556 and #559, one ceremony. The user-facing name is **"Secure your account" / "Konto absichern"**.

**#556.** Today a rotation can leave a space the account is alone in, the personal space among them, under a key no
device can open again. The publish overwrites the only server-held copy of the old identity, and the re-key of those
spaces runs afterwards with the old identity from this tab's memory. A lost answer, a closed tab, a re-key that fails,
or a space in another tenant strands that space for good (four ways, measured, § The defect).

**#559.** Someone holding the password **and** the Secret Key is indistinguishable from the member, and no operation
replaces the Secret Key: a rotation rewrites both cold wraps under the same one (ADR 0060 Consequences).

This CR reorders the rotation so that **access moves first and security follows**:
1. The client mints the new Master Key, the new identity and the new Secret Key.
2. **One publish, one transaction.** It carries everything the account rewrite carries today, plus:
   - the re-registered Secret-Key proof key and the new auth key;
   - for every scope the server counts as the rotator's alone, **across all tenants**, the scope's **current** epoch
     key granted to the new identity and signed by it.

   From that moment on, no state can lose access.
3. Each such scope gets a new epoch, granted to the new identity. Any unlocked device of the account can do it.
4. Every node of the scope is re-keyed (ADR 0109, as built).

No retired identity is kept anywhere.

**Size: five phases** (contracts and crypto, backend, web mechanism, web ceremony, durable docs and site). It is one CR
and not two, because the Secret Key and the rotation share one request, one proof statement, one transaction and one
screen. Splitting them would mean shaping `RotateAccountKeysRequest` twice and the guard that pins it twice. The tenant
switch for **shared** scopes is split out (`changes/cr-rotated-member-re-grants-itself.md`), because it is genuinely
independent:
- it is a setting with its own contract, storage, enforcement and two UIs;
- it adds members to the self-grant set and changes nothing in the mechanism;
- its design rests on a finding that contradicts the owner's decision (that CR's Q1).

**This CR lands first. That CR builds on its publish.**

## Context & constraints

Re-refined 2026-10-27 after the owner rejected the former design (a) (a retired identity sealed under the new Master
Key) and decided the one below. The worktree `worktree-agent-a0be6f96549d75e39` was fast-forwarded to `main` at
`c75f0616c`, and nothing was changed. It was then fast-forwarded again to `5b845ec5c` (#553 merged), and every pointer
into `rotateKeys.ts`, `KeyRotationPanel.vue`, their tests and `i18n/index.ts` was re-measured there. Every pointer was read in this refine or measured by a delegated read that cited
it; a claim held from memory is marked *(remembered)*. The four ways were confirmed by the former refine's throwaway
spike (`spike556.test.ts`, run and deleted, not committed), and the former CR's grounding is kept here.

### The defect, measured: four ways

1. **The answer to a committed publish is lost.** The fake server committed and then rejected with a `FetchError`-shaped
   error. `rotateKeys` rejected with `FetchError`, not a `RotationRefused`, so the panel showed `rotation.errorFailed`:
   "Nothing was changed — please try again" (`src/web/src/features/account/rotation/KeyRotationPanel.vue:138-153`,
   `src/web/src/i18n/index.ts:300`). The retry was refused as `credentialRejected`, because the floor now opens `MK'`
   while the vault holds `MK` (`src/web/src/features/account/coldCredential.ts:62-70`).
2. **The tab closes after the publish.** The request carries exactly seven fields (`contracts/api/account.openapi.yaml:1747-1787`).
   Nothing the account keeps leads back to the old identity once the tab's memory is gone.
3. **A sole-member re-key fails.** `rekeyAlone` answers `failed` (`rotateKeys.ts:327-340`). `setUnlocked` receives the
   new identity regardless (`rotateKeys.ts:243`), and `verifyOwnGrant` then refuses the personal grant as
   `bad_signature` (`src/web/src/features/spaces/grantTrust.ts:70-72`).
4. **A space in another tenant fails on every rotation.** The server's split spans every tenant
   (`MembershipStore.memberCountsByScopeFor`, `src/backend/core/src/main/kotlin/me/raddatz/veyq/core/membership/MembershipStore.kt:75-82`;
   the split at `RotationHandler.kt:203-204`). The client opens a scope only through `listMyScopes()`, which serves the
   active tenant's rows (`ScopesResource.kt:92-93`; `scopes.ts:331-336`). So `commitRotation` throws
   `cannot open scope to rotate` (`scopes.ts:613-614`) for every sole-member space outside the active tenant.

**No path recovers a stranded space today.** The former refine read every candidate:
- other devices: their wraps are deleted and their sessions revoked (`RotationStore.kt:61`, `RotationHandler.kt:207`);
- the session vault: it holds only the Master Key (`rememberMe/sessionVault.ts:17-19`);
- the scope-rotation worker: it resumes only node re-keys (`scopeRotationWorker.ts:111-117`);
- the Emergency Kit: it opens the rewritten `RECOVERY` wrap;
- pairing envelopes: superseded (`RotationHandler.kt:191`).

### What the code does today, beyond the four ways

- **The publish is one transaction**, in this order (`RotationHandler.kt:162-210`):
  1. the account row lock (`:163`);
  2. the live-session check (`:164`);
  3. the Secret-Key proof, over a statement the server rebuilds (`:165-174`);
  4. `applyRotation` (`:176-179`);
  5. the two documents (`:186-189`);
  6. the pairing supersede (`:191`);
  7. the moderation hook (`:193-196`);
  8. the split, with one `originateScopeRotationExcluding` per shared scope in ascending scope order, each under its
     scope's row lock (`:203-205`);
  9. the revocation of every other session family (`:207`).
- **No existing endpoint can replace the rotator's grant at the current epoch.** `rotateScope` writes only grants at
  the next epoch (`ScopesStore.kt:449-457`). Grant delivery writes only for a membership that is behind
  (`TasksStore.kt:270-275`, `:647-674`). So the self-grant needs a writer of its own. That is a **sixth grant writer**,
  and `scripts/validate_grant_writer_arity.py` holds the writers to five (`EXPECTED_WRITERS`, `:48`). Each writer owes a
  leg in `GrantWriterSnapshotSliceTest`.
- **The server never verifies a grant signature today.** The contract says the server "stores and relays and never
  verifies" (`contracts/api/scopes.openapi.yaml:1299-1303`), and ADR 0028 Decision 6 gives the reason: "A blind server
  does not adjudicate a crypto claim". The statement is pinned by the vector `grant_signature`
  (`contracts/crypto/vectors/v1.json:1103-1131`, with a PERSONAL case and a GROUP case). The identity public bundle is
  `lp(kem) ‖ lp(sign)` (`src/packages/crypto/src/v1.ts:793-796`). The JDK's Ed25519 is already wrapped
  (`core/crypto/Ed25519Verifier.kt:22`).
- **A grant written by a granter whose key a recipient's pin reads `changed` is refused by that recipient**
  (`grantTrust.ts:82`, `granter_key_changed`). Every grant row carries the granter's key as of the write
  (`ScopesStore.kt:442`). This is why a rotator never signs grants for **other** members in this CR, and it is the
  finding behind the sibling CR's Q1.
- **The Secret-Key machinery.**
  - The proof key is `hkdf(secretKey, 'veyq/v1/secret-key-proof')` (`v1.ts:282-287`). Its doc says "the Secret Key
    never changes in these flows" (`:283`).
  - Its public half is written once, at birth (`identity/AccountStore.kt:52,65`), is served by no GET, and has no
    re-registration path (`VeyqUser.kt:43-48`).
  - The rotation statement is `veyq/v1/secret-key-proof/rotation`, over the current and new identity, the wrapped
    identity and the two wrap digests (`v1.ts:302-316`; `core/crypto/SecretKeyProof.kt:35-50`; vector
    `v1.json:963-1020`).
  - The 2SKD KEK is `kekPasswordSecret` (`v1.ts:207-212`), and the recovery KEK is `kekRecovery(secretKey, salt)`
    (`v1.ts:214-215`).
  - The Emergency Kit carries `{email, secretKey}` only (`src/packages/crypto/src/emergencyKit.ts:12`, `:22-24`).
  - The reusable "save and confirm" step is `EmergencyKitSave.vue`. Ready means downloaded **and** confirmed
    (`src/web/src/features/account/emergencyKit/EmergencyKitSave.vue:14-15`, `:23-25`, `:48-51`). Account birth gates
    on it (`src/web/src/views/TenantJoinView.vue:351`, `:355`), and so does the change-email re-print
    (`ConfirmEmailChangeView.vue:139`).
  - No device caches the Secret Key: `LoginView.vue:240` clears it, and the device wraps never use it
    (`rememberMe/deviceKeyWrap.ts:44`). So no cache needs updating. *Machine hint, dev only:* `.run/.env`'s
    `VEYQ_SECRET_KEY` (`src/web/scripts/bootstrap-user.mts:12-22`) goes stale after a dev account is secured.
- **Change-password already needs the Secret Key**, so it stays a separate offer:
  - it proves the cold credential (`src/web/src/features/account/changePassword/changePassword.ts:50-51`);
  - it signs `passwordChangeProofStatement` with the proof key (`:70-80`);
  - the server verifies that proof (`PasswordChangeHandler.kt:93-100`);
  - the use case says so (`uc.account.change-password.md:12-17`).

  The auth keypair is deterministic from `(password, authSalt)` (`changePassword.ts:59-61`). So a rotation that keeps
  the password derives the same auth public key.
- **A pending email change outlives a rotation today.** Its Secret-Key proof is checked when it is **requested**
  (`EmailChangeHandler.kt:138-141`). The confirm checks only the token, a live session and address uniqueness
  (`:186-206`), and then writes the address and the stored `authPublicKey` (`:202-203`). So someone holding the
  password and the **old** Secret Key could request a change before the user secures the account and confirm it after,
  as long as the password still signs in. Recovery is not affected: its completion verifies against the proof key
  stored **at completion** (`RecoveryHandler.kt:165-166`).
- **Cross-tenant reads.**
  - `listMyTenants` and `listRotationScopeStates` are Host-independent (`ScopesResource.kt:229-242`;
    `AccountRotationResource.kt:78-79`).
  - Every scope-addressed operation (`rotateScope`, `listScopeMembers`, the task claim) checks access through
    `ScopeAccess`, never by Host (`ScopeAccess.kt:43-53`).
  - **No Host-independent read serves the caller's own PERSONAL or GROUP grant row.** `ScopeMember` carries none
    (`scopes.openapi.yaml:1546-1573`), and `listMyTenants` carries only the tenant root's grant.
  - The entry screen's list of sole-owner spaces is active-tenant only as well (`soleOwnerSpaces.ts:19`).
- **The node re-key is Host-bound.** `rekeyScope` opens through `openScopeById` (`src/web/src/core/node/rekey/rekeyScope.ts:29`,
  `:65`), and `resumeRekeys` walks `listMyScopes()` (`scopeRotationWorker.ts:111-117`).
- **Links.**
  - `listShares` without `nodeId` is account-wide: "every share the authenticated caller created in a space they may
    still read" (`contracts/api/sharing.openapi.yaml:137-138`). Each row carries `reissueRequired`
    (`ShareSummary`, `:1815`, `:1897-1905`). No web code calls the account-wide form today; the only caller passes a
    `nodeId` (`src/web/src/core/sharing/manageShares.ts:448-449`).
  - A re-key leaves a link marked (`ReissuePolicy.kt:19-23`; `RekeyCoreSliceTest.kt:101-106`;
    `rekeyScope.test.ts:208-211`).
  - The renew UI exists per node, in each kind's share dialog:
    - Memories: `features/memories/components/ShareDialog.vue:292-298`, through `ShareCreateDialog`'s `reissue-from`;
    - folders and files: `core/sharing/FolderShareDialog.vue:665-673`, through `remintShare`;
    - a drop box has no renew; it tells the user to make a new link (`DropBoxShareDialog.vue:215`).
  - Core reaches a kind's share dialog through the registry's `shareDialog` slot (`src/web/src/core/applications/registry.ts:17-21`).

### Is the race for the account new? No, and the ADR text says so

Securing the account is gated on the password and the **current** Secret Key, and it replaces the Secret Key. So
whoever holds both and secures the account first owns it. That is not a new power, as shown below.

Today, someone holding the password and the Secret Key can already do all of the following:
- move the sign-in address to a mailbox of their own. The request needs both secrets, the old address is only told,
  and the confirm needs nothing from the old address (`uc.account.change-email.md:58-59`; `EmailChangeHandler.kt:181-206`);
- change the password;
- and thereby take recovery away, because recovery mails go to the new address (ADR 0054).

So the decided gate, password plus the current Secret Key, adds no power over what that holder already has. What this
CR adds is the first remedy for the **member** who still holds both and acts first.

## Decisions this CR takes (owner 2026-10-27, written in as decided)

1. **The order: access first, security after.**
   - **Step 1, mint.** The client runs the cold proof (password + current Secret Key, `proveColdCredential`, as today),
     then mints `MK'`, the new identity and the new Secret Key, all in memory.
   - **Step 2, the publish.** It is one request and one transaction, carrying:
     - everything the account rewrite carries today;
     - the new auth public key (decision 7);
     - the re-registered Secret-Key proof key (decision 6);
     - `selfGrants`: one per scope the server counts as the rotator's alone, across every tenant. Each grants the
       scope's **current** epoch key `E_n` to the new identity and is signed by it.

     **The client opens those grants with the old identity before the publish**, while it still holds it. It reads them
     from the plan (decision 3), not from `listMyScopes`.
   - **Step 3, the new epoch.** Each re-granted scope gets `E_{n+1}`. It is chain-linked (`commitRotation`'s
     `wrappedPrev`, `scopes.ts:621`) and granted to the published identity. It runs right after the publish and is
     resumable by **any** unlocked device of the account, because it needs only the published identity (decision 8).
   - **Step 4, the nodes.** Each node of the scope is re-keyed top-down (`rekeyScope`, ADR 0109, as built).
   - **Shared scopes keep today's path.** For each of them the publish originates a `SCOPE_ROTATION` job that excludes
     the rotator (`TasksStore.kt:431-441`). Self-grants for shared scopes are the sibling CR.
   - **No retired identity is kept anywhere.** `E_n` is enough: every older epoch of the scope is reachable from it
     through the chain links, so nothing that the old identity opened is lost.
2. **The server refuses the publish unless the self-grants match its own split.**
   - Under each scope's row lock, the server recomputes the sole-member set. It requires
     `selfGrants ∪ unreadableScopeIds` to equal that set exactly, and each self-grant's `epoch` to equal the scope's
     current epoch.
   - Otherwise it answers **`409 rotation_plan_changed`**, and nothing is written. The client re-reads the plan,
     re-opens and re-signs with the same new keys, and resubmits. That counts against `MAX_PUBLISH_ATTEMPTS`
     (`rotateKeys.ts:36`), like `stale_revision` today.
   - A membership change or an epoch commit between the read and the publish is the only cause.
   - **The lock order.** The account lock comes first, as today. Then one ascending pass over all the publish's scopes,
     sole and shared together, takes each scope's row lock. In that pass a sole scope gets its self-grant, and a shared
     one gets its origination. This replaces the shared-only loop (`RotationHandler.kt:203-205`). The order stays
     scope → membership → job (`ScopeEntity.kt:104-116`).
3. **A plan read, Host-independent: `getAccountRotationPlan`** (`GET /api/account/rotation/plan`, tag
   `account-rotation`).
   - It answers the server's split across every tenant. Per scope it gives: `scopeId`, `kind`, `tenantId`,
     `currentEpoch`, `regrant` (true for a scope the publish must re-grant), `callerIsOwner`, `otherOwners` (whether
     another owner exists, for E4), and `blocked` (`tenant_suspended`, `scope_deleting` or absent).
   - Where `regrant` is true it also gives the caller's own grant row: what `toOpenedScope` reads, that is
     `wrappedGrant`, `grantedEpoch`, `granterUserId`, `grantSignature` and `granterIdentityPublicKey`
     (`scopes.ts:284-304`).
   - It serves the entry screen (the blocked spaces, the E4 spaces of every tenant, replacing `soleOwnerSpaces.ts`'s
     active-tenant read), the publish's self-grants, and step 3's opener after the publish.
   - Operator-blind: it holds only rows the server already serves the caller, through `listMyScopes` per tenant.
4. **A grant this device cannot open is left out, and declared.**
   - If `verifyOwnGrant` or the epoch floor refuses a sole scope's grant, the client cannot re-grant it. The causes
     are `GrantRefused`, `AwaitingCurrentKey` and a grant behind the current epoch (`scopes.ts:288-289`). The client
     names such a scope in `unreadableScopeIds`.
   - Leaving it out strands nothing a correct server would have let this account read. The refusals are
     device-independent, except the epoch floor, and a grant below the floor is a rollback this device already refuses.
   - The server keeps its grant untouched, writes it no row, and treats it as covered for the set equality.
   - **How it is reported:** the entry screen lists it under the blocked spaces before the button, with the reason
     "this device can't open it — it is already closed to you". The result screen repeats it with the same words.
5. **The server verifies each self-grant against the identity published in the same request** (the former Q5, decided
   yes).
   - It uses a Kotlin `GrantSignature` beside `SecretKeyProof`. It builds the `grant_signature` statement (`scopeId`,
     the scope's own `kind`, `epoch`, the caller's `userId`, `sha256` of the new identity public key, `sha256` of the
     `wrappedGrant`), takes the `sign` half from the new identity bundle, and checks with `Ed25519Verifier`.
   - A conformance test pins it to `v1.json`'s `grant_signature` vector, both cases.
   - A self-grant that does not verify is **`422 self_grant_invalid`**, and nothing is written.
   - **It is not bound into `secretKeyProofSignature`.** The new identity's public key is in the proof's statement, and
     only its private half can sign a self-grant the server accepts, so the binding is transitive.
   - **The ADR 0028 Decision 6 amendment.** This is the one place the server checks a grant signature. It adjudicates
     no trust here: the key it checks against is the one this same request publishes under the Secret Key's proof.
6. **The new Secret Key is part of this build (#559).**
   - The client mints a fresh 32-byte Secret Key `SK'`. The `PASSWORD` wrap becomes `kekPasswordSecret(SK', password')`
     and the `RECOVERY` wrap becomes `kekRecovery(SK', salt)`, each with a fresh salt, both over `MK'`. ADR 0054
     recovery is the Secret Key alone plus address control, so the recovery wrap moves too.
   - The publish carries `secretKeyProofPublicKey`, the new proof public key derived from `SK'`.
   - The rotation proof statement gains three fields: the current and the new auth public key (decision 7), and the
     new proof public key.
     - It is still signed by the **current** proof key: that signature is the gate and the authorization to move it.
     - It is also signed by the **new** proof key, as `secretKeyProofPossession`. Without that, a client bug could
       register a key nobody holds, and the account could then never recover, change its password or be secured again.
     - The domain stays `veyq/v1/secret-key-proof/rotation`. There is no release (`scripts/applied_migrations.sha256`
       `release: none`, `src/backend/CLAUDE.md:216-219`), so the statement is replaced, not versioned. Its vector is
       regenerated by an independent implementation, per `v1.json`'s note.
   - The server verifies both signatures and then stores the new proof public key in the same transaction.
   - From that moment, change-password, recovery, change-email and the next rotation verify against the new key. They
     already read the stored key per request (`PasswordChangeHandler.kt:93-100`, `RecoveryHandler.kt:165-166`,
     `EmailChangeHandler.kt:138-141`).
   - **The publish voids every open email-change request of the account**, in the same transaction. Its proof was
     checked against the old key when it was requested, and its confirm checks none (§ Context).
   - **The new Emergency Kit is saved before the publish.** The ceremony shows `EmergencyKitSave` with the account's
     email and `SK'`, and the publish button stays disabled until it reports ready (downloaded **and** confirmed), as
     account birth does (`TenantJoinView.vue:351-355`). A panicked click-through cannot lock anyone out, because the
     download and the confirmation come first.
   - `uc.account.rotate-keys`' precondition stands: the gate is the password plus the **current** Secret Key, whatever
     wrap opened the vault.
7. **Optionally a new password, in the same step.** The ceremony offers "Also choose a new password", **pre-selected**,
   not forced.
   - The publish always carries `authPublicKey` and `attestationSignature`: the old auth key signs the new one, as
     change-password does (`changePassword.ts:59-61`). The server verifies it as `PasswordChangeHandler.kt:88-91`
     does, and writes `auth_public_key`.
   - If the user keeps the password, the derived auth key is the same key and the attestation still verifies. So both
     fields are always present and required, with no mode by omission (ADR 0060 Decision 1).
   - "Change password" stays its own offer in Settings → Security.
8. **Step 3 is server-tracked and finished by any device.**
   - **The pending row.** For each self-granted scope, the publish writes one row of a new table
     `veyq_rotation_regrant (user_id, scope_id, epoch_at_rotation, created_at)`. Its foreign keys cascade on delete
     from `veyq_user` and `veyq_scope`, so the two erasure guards hold. A second rotation replaces the account's rows.
   - **The state.** `listRotationScopeStates` serves these rows too, next to the queued ones (`kind: regranted`). Each
     row's state is derived from rows the server holds, first match wins:
     - `blocked` (with `reason`) while the tenant is suspended or the scope is being deleted;
     - `waiting` while the scope's current epoch equals `epoch_at_rotation`;
     - `rekeying` while a node lags, by the same predicate the queued state uses;
     - `secured` otherwise.

     A regranted scope has no job, so no attempts and no `failed`. It is retried on every pass until done.
   - **`finishRotation()`** runs first in `runScopeRotationWorker`, before `resumeRekeys`:
     1. it reads the account's `waiting` regranted rows and the plan's grant rows;
     2. it opens each scope's grant with the vault's identity (the published one), Host-independently;
     3. it commits `E_{n+1}` through `commitRotation` with that opener;
     4. it runs `rekeyScope` where the scope is in the active tenant.

     It runs on start and every 45 s while unlocked (`scopeRotationPolling.ts:10-25`), so every device finishes,
     including one opened from the Emergency Kit. `rotateKeys` calls the same function right after the publish.
   - **The opener.** `commitRotation` gains an injected opener (a row plus the vault's identity) for this one caller.
     Its other callers keep `openById` (`git grep -n "commitRotation(" -- src/web/src`).
   - **The members.** `commitRotation` re-grants whoever is a member at that moment. In a scope the server counted
     sole at the publish, any other member was admitted afterwards by the account's new identity, so there is nobody
     to exclude. The former `members.length !== 1` refusal (`rotateKeys.ts:330`) goes, and with it the former CR's
     residual. That member's client pins the new key on first use. One residual stays: someone holding the password
     can sign in after the publish and act as owner (§ Not in this CR).
   - **A scope in another tenant.** Its epoch commits from anywhere. Its node re-key waits until that tenant is opened,
     because `rekeyScope` is Host-bound (§ Context), and it reads `rekeying` until then. Nobody adds to a scope the
     account is alone in without opening its tenant, and adding there waits while it is `rekeying`
     (`rotation.statusHint.rekeying`, `i18n/index.ts:313`).
9. **A publish failure the server did not answer with a declared refusal is an unknown outcome** (the former Q3, kept).
   - **Declared refusals, where nothing was written:**
     - `400`;
     - `401`;
     - `403 secret_key_proof_failed`;
     - `409 rotation_unavailable`;
     - `409 stale_revision` and `409 rotation_plan_changed`, which are retried;
     - `413`, #553's `requestTooLarge`, mapped by status at `rotateKeys.ts:211` and left as it is;
     - `422`.
   - **Anything else is unknown:** a `FetchError`, a `5xx`, or a proxy's `502` or `504`. Today these reach the final
     `throw e` of #553's `catch` (`rotateKeys.ts:212`), which becomes this re-read. #553's measurement saw one
     `502` through Traefik for a `413` (its phase-1 commit `5e97a451d`). The client re-reads `getAccountKeyMaterial`
     and tries the served `wrappedIdentityPrivateKey` (`account.openapi.yaml:1361-1383`):
     - it opens under `MK'` to the minted identity: the publish committed. The vault swaps, step 3 runs, and nothing is
       re-minted;
     - it opens under `MK` to the old identity: nothing changed, and `rotation.errorFailed` is true;
     - the re-read fails, or neither key opens it: the client **locks the vault** and shows
       `rotation.errorUnknownOutcome`. Whatever the server holds, the next unlock opens it, and the worker finishes what
       is waiting.
10. **A retry whose typed credential opens the floor to another Master Key finishes; it does not re-mint.**
    - `proveColdCredential` keeps two refusals apart:
      - the floor does not open (`credentialRejected`, as today);
      - the floor opens to a Master Key other than the vault's (a new `vaultStale`), split from today's
        `coldCredential.ts:66-69`.
    - On `vaultStale`, the panel checks that the key opens the served `wrappedIdentityPrivateKey`. It then re-opens the
      vault from the typed credential through `openVault`: it is a cold unlock (`unlock.ts:41-52`). It shows the
      current states from `listRotationScopeStates`, and the worker finishes.
    - Nothing is minted and nothing is posted. This also covers the race in which decision 9's re-read saw the old
      identity and the request committed afterwards.
    - Change-password keeps mapping `vaultStale` to its `credentialRejected` copy (§ Not in this CR).
11. **The vault swaps to the published identity as soon as the publish is known to have committed.** That is before the
    best-effort re-seals and before step 3.
    - The re-seals that need the old Master Key stay best-effort, and a closed tab loses them as ADR 0068 allows: the
      Memory pins, the item marks' tags, and this device's marks (`rotateKeys.ts:217-239`, `:288-301`).
    - The old identity is zeroed in the `finally`.
12. **Share links.** After the re-key a link keeps working (ADR 0109 point 4). One the thief may know closes only by a
    re-issue (ADR 0075).
    - The result screen reads `listShares()` without `nodeId` and lists the caller's own links with
      `reissueRequired`, grouped by space.
    - Each row's **Renew** opens that node's own share dialog through the registry's `shareDialog` slot
      (`registry.ts:17-21`). That reaches Memories' renew (`ShareDialog.vue:292-298`) and folders' and files' renew
      (`FolderShareDialog.vue:665-673`) without core importing an application.
    - A drop-box link reads as its dialog does: create a new link (`DropBoxShareDialog.vue:215`).
    - A link in a space of another tenant says to open that tenant, because the dialog opens through the active tenant.
13. **The ceremony and its words** (decision 5 of the owner, text proposed in Q1).
    - **Entry.** Plain words before any field, with no Master Key, identity or Secret-Key internals:
      - all your devices are signed out;
      - you get a new Emergency Kit to save, and the old one stops working;
      - a new password is offered;
      - in a shared space, someone there confirms it's you before you see what is added afterwards (today's behaviour;
        the sibling CR changes this line per tenant);
      - what was already seen stays seen;
      - links made before stay open until renewed;
      - which spaces are blocked, and why, from the plan: suspended, being deleted, unreadable on this device, and the
        E4 spaces where you are the only owner.
    - **The steps:**
      1. the gate (password + current Secret Key);
      2. "Also choose a new password" (pre-selected);
      3. the new Emergency Kit (`EmergencyKitSave`, ready gates the next button);
      4. the confirm dialog;
      5. the publish;
      6. the result.
    - The minted keys live only in the panel's memory and are zeroed when it is left. A kit saved and then abandoned
      before the publish is useless, so the kit step says to keep the old kit until the result screen says the account
      is secured.
    - **The result screen:**
      - per space, the state: `waiting`, `rekeying`, `secured` or `blocked` for re-granted spaces, and today's five for
        queued ones;
      - the links to renew;
      - "Your old Emergency Kit no longer works. You can destroy it."
    - The settings rail entry and the panel's title become "Secure your account" / "Konto absichern". The Use page
      keeps its URL (`/account/replace-your-keys/`), so no link breaks, and takes the new title.
    - Every text that says the Secret Key or the kit cannot be replaced is corrected (Phase 5 inventory). That includes
      the line printed into every kit PDF (`kit.pdfKeepSafe`, `i18n/index.ts:625`, DE `:2579`).
14. **The durable docs move with it** (Phase 5, text in § Proposed durable text):
    - ADR 0060: Decision 1's "one mode" now includes the Secret Key; Decision 2's "It never changes in these flows";
      the Consequences "The Emergency Kit stays valid" and "The Secret-Key proof key survives unchanged"; a new
      Consequences bullet on the order; Alternatives (j), (k) and (l).
    - ADR 0054 Consequences.
    - ADR 0028 Decision 6, the one server-side check.
    - ADR 0109 point 7: "the rotator's own device" becomes "any device of the account".
    - `uc.account.rotate-keys`: the goal, the postconditions, steps 1–4 and 6, step 9's states, E7 rewritten, and E10
      for the new kit.
    - `en.account.key-model`: the Secret Key row, the proof-key row, § Rotation after a compromise (`:146`) and
      § Recovery (`:168-169`).
    - `security-model.md` § Key-directory trust, the rotation bullet.
    - `ui-web-styleguide.md:273`.
    - The coverage YAML.
    - The site: Use EN and DE, Security.
    - Each code comment that says the Secret Key never changes.

### Not in this CR

- **Self-grants for shared scopes, and the tenant's switch:** `changes/cr-rotated-member-re-grants-itself.md`.
- **#560** (contacts accept a changed key by a Secret-Key-proof signature), **#561** (a first contact anchored in the
  invitation), and first-contact TOFU in general.
- **A password holder acting after the publish.** Someone holding the password, without the Secret Key, can sign in
  after the publish and commit an epoch of their own on a scope the account owns. That denies the account the scope; it
  discloses nothing new. It is today's residual (ADR 0060 Decision 2: a session is all `rotateScope` asks for).
  Securing the account with a **new** password, which is pre-selected, closes it.
- **The node re-key of a scope in another tenant from wherever the user is** (decision 8). It waits for a visit.
- **A finding for a new inbox row: a queued rotation job of another tenant is claimed and failed.** The global worker
  claims it (`scopeRotationWorker.ts:143-147`), cannot open the scope outside the active tenant (`scopes.ts:613-614`),
  and fails it, spending an attempt (`:81-83`). An owner whose browser sits on another tenant can thereby dead-letter
  a rotation. This exists today and is independent of this CR. Suggested next step: decline rather than fail when the
  scope is not openable here, or claim per tenant.
- **Change-password meeting a stale vault** (decision 10's split) keeps its copy. That is the same family of defect,
  for the next row if it bites.
- **Recovering a space stranded before this CR.** No copy of its identity exists.

## Builds on #553 (done, merged as `5b845ec5c`)

#553's CR is done and deleted. What it left, which this CR builds on:
- **`RotationRefused('requestTooLarge')`** (`rotateKeys.ts:52`). The publish `catch` maps a `413` by status alone
  (`isHttpStatus(e, HttpStatus.contentTooLarge)`, `:211`). Everything that is not a `409 stale_revision` or a `413` is
  rethrown (`:212`), the `FetchError` and the `500` included.
- **The panel's arm** (`KeyRotationPanel.vue:148-149`, `rotation.errorTooLarge`, `i18n/index.ts:301`, DE `:2281`) and
  its tests, the panel's first error arms (`KeyRotationPanel.test.ts:232`).
- **E10** in the use case (`uc.account.rotate-keys.md:165-170`).
- **A browser reads the `413`.** #553's phase 1 measured that headless Chrome reads it mid-body: 59 of 60 requests, no
  fetch rejected, one `502` through Traefik (`5e97a451d`; the measurement's note,
  `rotationAtPinCap.measure.e2e.test.ts`).
- **A recipe for a throwaway instance with no shared container** (`changes/README.md:1695`). This CR's browser pass
  uses it.

What this CR does to it:
- **Decision 9 sits on #553's `catch`.** The `413` branch stays as it is, a declared refusal. The final rethrow
  (`:212`) becomes the unknown-outcome re-read, so a `FetchError`, a `5xx` and a proxy's `502`, the one #553 saw
  included, are re-read and never reported as "nothing was changed" unread.
- **#553's two controls are restated, not dropped.** `control: %s is rethrown as it is, never as too large`
  (`rotateKeys.test.ts:688-701`) keeps its point (never `requestTooLarge`) and asserts where the failure goes now: the
  re-read. #553's Q4 kept that control for exactly this hand-over.
- **E numbering.** E10 is #553's. This CR rewrites E7 in place and adds **E11** (the new Emergency Kit).

## Inventory

Every locator was run at refine.

| What | Where | Locator |
|---|---|---|
| The request, its result, the states, the plan | `contracts/api/account.openapi.yaml` | `grep -n "RotateAccountKeysRequest:\|RotateAccountKeysResult:\|RotationScopeState:\|operationId: rotateAccountKeys\|operationId: listRotationScopeStates" contracts/api/account.openapi.yaml` |
| The prose that says the Secret Key survives, in the contract | same file | `grep -n "Secret Key is unchanged\|survives the rotation\|no \`authPublicKey\`" contracts/api/account.openapi.yaml` |
| The shape guard | `scripts/validate_rotation_shape.py` | `grep -n "REQUEST_PROPS\|RotatedWrap" scripts/validate_rotation_shape.py` |
| Every builder of the request or reader of the result | backend tests, web | `git grep -ln "rotateAccountKeysRequest\|RotateAccountKeysRequest\|soleMemberScopeIds" -- . ':!**/generated/**'` |
| The proof statement, its vector, both implementations | `v1.ts`, `SecretKeyProof.kt`, `v1.json`, `SecretKeyProofTest.kt`, `v1.conformance.test.ts` | `git grep -n "rotationProofStatement\|rotationStatement\|\"rotation\"" -- src/packages/crypto/src src/backend/core/src contracts/crypto` |
| The grant statement, for the Kotlin verifier | `v1.ts`, `v1.json` | `grep -n "export const grantStatement" src/packages/crypto/src/v1.ts; grep -n '"grant_signature"' contracts/crypto/vectors/v1.json` |
| The publish's transaction | `RotationHandler.kt`, `RotationStore.kt` | `grep -n "applyRotation\|originateScopeRotationExcluding\|memberCountsByScopeFor" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/account/rotation/*.kt` |
| Grant writers and their guard | `ScopesStore.kt`, `TasksStore.kt`, guard, snapshot test | `grep -n "EXPECTED_WRITERS" -A8 scripts/validate_grant_writer_arity.py` |
| The proof key's column, its only writer | `VeyqUser.kt`, `AccountStore.kt` | `git grep -n "secretKeyProofPublicKey =" -- src/backend/core/src/main` |
| Open email changes to void | `EmailChangeHandler.kt`, its store | `grep -n "fun spend\|fun findByTokenHash\|class .*EmailChange.*Entity" -r src/backend/core/src/main/kotlin/me/raddatz/veyq/core/account/email/` |
| The state derivation | `CompromiseRotationPolicy.kt`, `TasksStore.compromiseRotations` | `git grep -n "fun state\|fun compromiseRotations" -- src/backend/core/src/main` |
| Migration and pin; erasure guards | `db/migration/`, `scripts/applied_migrations.sha256` | `ls src/backend/core/src/main/resources/db/migration/ \| tail -1` |
| A new operation's classification | `OperatorBlindProbeTest` | `git grep -n "class OperatorBlindProbeTest" -- src/backend` |
| The client flow | `rotateKeys.ts` | `grep -n "setUnlocked\|rekeyAlone\|resealBestEffort\|isCodedError\|MAX_PUBLISH_ATTEMPTS" src/web/src/features/account/rotation/rotateKeys.ts` |
| The opener the finish replaces | `scopes.ts` | `grep -n "async function openById\|export async function commitRotation\|function toOpenedScope" src/web/src/features/spaces/scopes.ts` |
| Every `commitRotation` caller (they keep `openById`) | web | `git grep -n "commitRotation(" -- src/web/src` |
| The gate's refusals | `coldCredential.ts` and its readers | `git grep -n "credentialRejected\|ColdCredentialRefused" -- src/web/src` |
| The worker pass | `scopeRotationWorker.ts` | `grep -n "resumeRekeys\|export async function runScopeRotationWorker" src/web/src/features/tasks/scopeRotationWorker.ts` |
| The entry list that becomes the plan | `soleOwnerSpaces.ts` and its readers | `git grep -n "soleOwnerSpaces" -- src/web/src` |
| The kit step | `EmergencyKitSave.vue` and its users | `git grep -n "EmergencyKitSave" -- src/web/src` |
| Every reader of the outcome type | web | `git grep -n "ScopeOutcome\|RotationResult\|'failed'" -- src/web/src/features/account/rotation` |
| The links read | `manageShares.ts` | `grep -n "listShares" src/web/src/core/sharing/manageShares.ts` |
| The copy, EN and DE: rotation and every "cannot be replaced" | `src/web/src/i18n/index.ts`, `src/admin-web/src/i18n/index.ts` | `git grep -n "rotation\.\|cannot be replaced\|can't be replaced\|nicht ersetzt\|nicht ersetzen\|nobody can replace\|niemand kann ihn ersetzen\|stay as they are\|bleiben, wie sie sind" -- src/web/src/i18n/index.ts src/admin-web/src/i18n/index.ts` |
| The settings rail label | `SettingsHubView.vue` | `grep -n "KeyRotationPanel\|'security'" src/web/src/views/SettingsHubView.vue` |
| The durable docs that say the Secret Key is never rotated | `docs/` | `git grep -n -i "secret key itself is not rotated\|emergency kit stays valid\|never changes in these flows\|survives unchanged\|does not exist yet\|cannot be replaced" -- docs` |
| The site pages | Use EN and DE, Security | `git grep -n -i "replace your keys\|Schlüssel ersetzen\|stay as they are\|bleiben, wie sie sind\|cannot make you a new\|kein neues Notfall-Kit\|only time you get a new one\|Ein neues bekommst du nur\|No operation rotates it\|does not exist yet" -- site/src/content/docs` |
| Code comments that say the Secret Key never changes | crypto, backend, web | `git grep -n -i "secret key never changes\|never changed by rotation\|secret key does not change\|secret key is unchanged\|Secret Key does not change" -- src contracts` |

**Second angle**, on the Secret Key's "never". `git grep -n -i "kit stays valid\|old kit keeps working\|kit keeps working\|Kit funktioniert weiter" -- docs site src` finds:
- the change-email and forgot-password lines (`site/src/content/docs/account/change-email.mdx:41`,
  `account/forgot-password.mdx:22`, and their DE twins), which **stay true**: neither flow replaces the Secret Key;
- the rotation lines, which change.

Each hit is assigned in Phase 5: kept with that reason, or rewritten.

## Phases

### Phase 1: contracts and crypto

Decisions 2, 3, 5, 6, 7 and 8 in the contract; the statement and its vector.

- **`RotateAccountKeysRequest` gains six required properties:**
  - `selfGrants`: an array of `{scopeId, epoch, wrappedGrant, grantSignature}`;
  - `unreadableScopeIds`;
  - `authPublicKey`;
  - `attestationSignature`;
  - `secretKeyProofPublicKey`;
  - `secretKeyProofPossession`.

  Each new property carries its `x-veyq-visibility` and its bound with the `x-field-extra-annotation`
  (`validate_field_visibility.py`, `validate_request_bounds.py`). The arrays are bounded by a `maxItems` the backend
  enforces.
- The descriptions that say the Secret Key survives are rewritten (Inventory row 2).
- `409 rotation_plan_changed` and `422 self_grant_invalid` are declared.
- `RotateAccountKeysResult.soleMemberScopeIds` becomes `regrantedScopeIds`.
- `getAccountRotationPlan` is a new operation.
- `RotationScopeState` gains `kind` (`regranted` or `queued`), the state `blocked` and its `reason`.
- `validate_rotation_shape.py`: the new property set, each required, and a docstring line per field on why it is not a
  mode. **Bite:** drop `secretKeyProofPossession` from `required` in a snapshot of the spec. The guard goes red.
  Restore from the snapshot.
- The rotation statement in `v1.ts` gains `currentAuthPublicKey`, `authPublicKey` and `secretKeyProofPublicKey`. The
  vector `secret_key_proof.rotation` is regenerated by an independent implementation, per `v1.json:3`. The proof-key
  derivation's doc (`v1.ts:283`) loses "never changes".
- `OperatorBlindProbeTest` gets the plan's line: a probe, or an exclusion with its reason.

**Done when:**
- `npm run generate:api` runs;
- `npm run check` in `src/packages/crypto` is green, its conformance case included;
- the trust-chain guards are green. `validate_guard_gaps.py` runs once, because a guard changed.

Expected red, stated and not stubbed (`changes/README.md` § Phases):
- `src/web/` at the request literal in `rotateKeys.ts` and at `soleMemberScopeIds`;
- `./gradlew check` at `AccountRotationResource` (a new operation on the existing `account-rotation` tag) and at the
  test builders of the request.

Run each check once to see the red it names.

### Phase 2: backend

Decisions 2, 3, 5, 6 (the server half), 7 and 8.

- **Migration:** `veyq_rotation_regrant`, with cascading foreign keys, and its pin line.
- **`GrantSignature.kt`** and its conformance test against `grant_signature`, both cases.
- **The publish:**
  - the new statement and the possession check;
  - the attestation, as `PasswordChangeHandler.kt:88-91` checks it;
  - one ascending pass over the scopes: the self-grant written on the caller's membership row (`wrapped_grant`,
    `granted_epoch`, `granter_user_id` = the caller, `grant_signature`, `granter_identity_public_key` = the new key),
    or the origination, as before;
  - the set equality;
  - the regrant rows;
  - `auth_public_key` and `secret_key_proof_public_key` written;
  - open email changes voided.
- **The sixth grant writer:** `EXPECTED_WRITERS` and a leg in `GrantWriterSnapshotSliceTest`.
- **The plan read.**
- **The states read:** the regranted rows and `blocked`.
- **Comments:** `VeyqUser.kt:43-46` and `RotationStore.kt:29-30` lose "never changed".

**Reproducing tests first, red against the handler as Phase 1 left it** (it accepts the new fields and ignores them),
in `RotationSliceTest`. They follow `aSoleMemberScopeIsLeftForTheCallerAndQueuesNothing` (`:171`) and its seeds:
- **Ways 2 and 3, the server half:**
  - **`aSoleMemberScopeIsRegrantedToTheNewIdentityInThePublish`.** After the publish, the caller's personal grant is at
    the current epoch, signed by the caller, carrying the new key, and it verifies under the new identity (the
    `grant_signature` statement, in the test).
  - **Control on the same predicate.** A shared scope's grant to the caller is untouched.
- **Way 4, the server half:**
  - **`aSoleScopeInAnotherTenantIsRegrantedToo`.** It is seeded with a second tenant.
  - The plan, read on tenant A's Host, lists tenant B's personal scope with its grant row.
- **The refusals.** Each answers its code, writes nothing, and is checked by re-reading the identity and the grant.
  Each has a passing control that differs in that one input only:
  - **`aPublishMissingASoleScopeIsRefused`:** `409 rotation_plan_changed`;
  - **`aSelfGrantForAScopeThatIsNotSoleIsRefused`:** `409 rotation_plan_changed`;
  - **`aSelfGrantAtAStaleEpochIsRefused`:** `409 rotation_plan_changed`;
  - **`aSelfGrantSignedByTheOldIdentityIsRefused`:** `422 self_grant_invalid`;
  - **`aPossessionSignatureByAnotherKeyIsRefused`:** `403 secret_key_proof_failed`;
  - **`aWrongAttestationIsRefused`:** its declared code.
- **An unreadable scope.** **`anUnreadableScopeKeepsItsGrantAndGetsNoRow`.**
- **The new Secret Key binds at once.** **`theNextCredentialFlowVerifiesUnderTheNewProofKey`.**
  - A change-password signed by the **old** proof key after the publish is `403`.
  - Signed by the new one, it is `200`.
  - Recovery completion likewise.
- **`anOpenEmailChangeDoesNotSurviveThePublish`.** A change requested before the publish, then confirmed after it,
  answers `Invalid`, and the address is unchanged. Its control: a change requested **after** the publish confirms.
- **`aRolledBackRotationLeavesNothing`.** A `stale_revision` rollback (`aStalePinStoreRevisionRollsTheWholeRotationBack`,
  `:290`) writes no self-grant, no row, no proof key and no auth key.
- **The states.**
  - `waiting` after the publish.
  - `rekeying`, then `secured`, after a `rotateScope` by the caller and the node re-key.
  - `blocked`, with its reason, for a suspended tenant.
- **The purge.** An erased account leaves no row.

**Bite** each, with its lever:
- drop the set equality: the missing-scope test goes red;
- skip the signature check: the old-identity test goes red;
- keep the old proof key: the change-password test goes red;
- skip the void: the email-change test goes red;
- write the row outside the pass: the rollback test goes red.

**Done when:** `./gradlew check` is green, and the guards that read the backend (`validate_grant_writer_arity.py`, the
two erasure-arity guards, `validate_migration_versions.py`, `validate_time_columns.py`) are green.

### Phase 3: web, the mechanism

Decisions 1, 2, 4, 8, 9, 10 and 11. It starts from #553's code on `main` (`5b845ec5c`).

**Reproducing tests first, red against the untouched `rotateKeys.ts`** (the regenerated client aside), in
`rotateKeys.test.ts`. They go in a new `describe('securing the account loses no space (inbox #556)')` on the harness
the spike used (`fakeServer`, `heldAt0`, `storedFloor`, `rotateKeys.test.ts:114-227`; #553 gave `fakeServer` a
`rotateFails` option, `:165`, `:193`). The fake gains:
- a committed state (floor, published identity, proof key, grants);
- the plan;
- the states read;
- the set check.

The cases:
- **Way 1, the lost answer.** The publish commits, then rejects with a `FetchError`-shaped error.
  - The call ends with the vault on the minted identity.
  - The personal scope is `secured` or `rekeying`, at a new epoch whose grant opens under the new identity.
  - `rotateAccountKeys` was called once.
  - *Red today:* it rejects.
- **Way 1, the retry.** The vault is still on `MK`, the server committed, the user presses again, typing the new
  Secret Key.
  - No second `rotateAccountKeys`.
  - The vault ends on the published identity.
  - *Red today:* `credentialRejected`.
- **Way 2, the closed tab.** The posted request's `selfGrants` holds the personal scope's `E_n`. It opens under the new
  identity and verifies under its key.
  - Then a fresh worker pass, with a vault opened from `MK'` alone, commits `E_{n+1}`. Its link opens to `E_n`.
  - *Red today:* the field does not exist, and the only grant is to the old identity.
- **Way 3, step 3 fails.** `listScopeMembers` rejects once.
  - The state is `waiting`, and the vault holds the new identity.
  - The personal grant at `E_n` **opens** under it. That is the point: no space is stranded.
  - The next worker pass secures it.
  - *Red today:* `failed`, then `bad_signature`.
- **Way 4, another tenant.** A sole scope absent from `listMyScopes` but in the plan is self-granted and its epoch is
  committed. *Red today:* `failed`.
- **The unknown outcome.**
  - The re-read fails too: the vault is locked, and the error is `errorUnknownOutcome`, never `errorFailed`.
  - **Control.** A `FetchError` whose re-read shows the old identity is `errorFailed`, and the vault stays on `MK`.
- **#553's controls, restated.** `control: %s is rethrown as it is, never as too large` (`rotateKeys.test.ts:688-701`)
  becomes: `httpError(500)` and the `FetchError` are never `requestTooLarge`, and both are re-read. The `413` case
  above it (`:676-686`) stays as it is.
- **`rotation_plan_changed`.** It re-reads the plan and resubmits with the same identity and the same new Secret Key.
  Its control: the call count is bounded by `MAX_PUBLISH_ATTEMPTS`.

**Bite tests for the fix**, each with its lever:
- **The old identity never reaches the vault and never signs a self-grant.** Assert that each self-grant verifies under
  the new key and not under `OLD_ID`'s (as `rotateKeys.test.ts:453-454` does), and that no `setUnlocked` call carries
  `OLD_ID`. Lever: sign with the old identity. Red.
- **An unreadable grant is declared, not dropped silently.** Lever: omit it from both lists. The server fake's set check
  refuses.
- **`vaultStale` fires only on "opened to another Master Key that opens the served identity".**
  - Control: a wrong Secret Key is still `credentialRejected`.
  - Second control: a floor that opens to a key that does not open the served identity is `credentialRejected` too.
  - Lever: drop the identity check. The second control goes red.
- **The finish re-grants a member who joined after the publish.** Lever: restore the `!== 1` refusal. Red.

Snapshot and restore every lever, never `git checkout` (`changes/README.md` § Bite-proof a guard by snapshot-and-restore).

**Done when:** `npm run check` in `src/web/` is green.

### Phase 4: web, the ceremony

Decisions 6 (the client half), 7, 12 and 13.

- The panel's steps: gate, password, kit, confirm, publish, result.
- The new Secret Key and the wraps under it.
- The proof key, the possession signature and the auth attestation.
- The result's link list.
- The entry screen from the plan, replacing `soleOwnerSpaces.ts`.
- The copy in EN and DE, as decided in Q1.

The tests:
- **`rotateKeys.test.ts`:**
  - the posted `passwordWrap` opens with the password and the **new** Secret Key, and not with the old one;
  - `recoveryWrap` opens under `kekRecovery(SK')`;
  - `secretKeyProofPublicKey` is `deriveSecretKeyProofKeypair(SK')`'s;
  - both signatures verify over the server-rebuilt statement;
  - with "keep my password", `authPublicKey` equals today's.
  - Bite: wrap the recovery under the old Secret Key. The `kekRecovery(SK')` assertion goes red.
- **`KeyRotationPanel.test.ts`**, beside #553's error arms (`describe('KeyRotationPanel — what a failed rotation
  says')`, `:232`):
  - the publish button stays disabled until `EmergencyKitSave` reports ready;
  - the password option is pre-selected;
  - the blocked list renders the plan's reasons;
  - the result lists a `reissueRequired` link with **Renew**, and not one without the flag;
  - each error arm maps to its copy, `errorUnknownOutcome` included.
  - Bite: enable the button on download alone. Red.

**Done when:**
- `npm run check` in `src/web/` is green;
- **browser pass.** It uses headless system Chrome per the owner's machine notes, the owner's German UI, a worktree-built
  backend, never a second `quarkusDev`, and a **throwaway** account on a throwaway instance (#553's recipe,
  `changes/README.md:1695`; securing the dev seed's account would leave its Secret Key in `.run/.env` stale):
  - **the ceremony end to end.** The kit downloads, then confirm, then publish, then sign out. Signing in with the
    password and the **new** kit opens the personal space with its content. The **old** kit is refused;
  - **way 1 live.** A Playwright route on `POST /api/account/rotation` sends the request (`route.fetch()`) and then
    aborts it. The panel shows the result, not "Es wurde nichts geändert";
  - **way 2 live.** The same route, then the page closed before step 3. A fresh context signs in with the password and
    the new kit, the personal space opens with its content, and it reads secured after the worker pass;
  - **a link to renew** is listed, and **Renew** opens its dialog;
  - if this phase cannot run a browser, it says so, and the pass stays open here.

### Phase 5: the durable docs and the site

Decision 14, with the text of § Proposed durable text.
- The coverage YAML's criteria list Phase 2's, 3's and 4's tests. The criterion at
  `uc.account.rotate-keys.yaml:49` ("the Emergency Kit stays valid") is rewritten.
- The site pages and every "cannot be replaced" hit of the Inventory, each assigned and either rewritten or kept with
  its reason. Use pages in EN and DE together:
  - `account/replace-your-keys.mdx`;
  - `getting-started/emergency-kit.mdx:29-34`;
  - `help/lost-emergency-kit.mdx:8`, which stays true for a **lost** kit and gains the case of a kit someone else may
    hold;
  - `security/key-hierarchy.md:134`, `:139`;
  - `security/protocols.mdx:569`, `:610`.
- `ui-web-styleguide.md:273`.

**Done when:**
- the trust-chain guards are green (`scripts/validate_*.py`, without `validate_guard_gaps.py`, since no guard changed
  here);
- `npm run check` in `site/` is green.

**Not run here:** the release-candidate e2e and the fuzzer (owner rule 2026-10-19: once, at V1's end).
`src/web/src/features/tasks/scopeRotation.e2e.test.ts` already calls `rotateKeys` (`:163`). Phase 3 gives it a leg that
abandons the ceremony after the publish and finishes it from a fresh sign-in, and that leg runs in that pass.

## Proposed durable text

**ADR 0060, Decision 1**, the first sentence becomes:
> **One mode: the Master Key, the identity keypair and the Secret Key rotate together, always.**

Its field list gains:
- the self-grants and the declared unreadable scopes;
- the new auth public key and its attestation;
- the new Secret-Key proof key, signed by the current one and by itself.

**ADR 0060, Decision 2**, "It never changes in these flows" becomes:
> **It changes only when the account is secured.** The publish registers the new proof key under a signature of the
> current one, and a signature of the new one proves it is held. Change-password, recovery and a change of the sign-in
> address leave it as it is.

**ADR 0060, § Consequences.** Replace "The Emergency Kit stays valid" with:
> **Securing the account replaces the Emergency Kit.** The Secret Key is rotated with the rest, because someone who
> holds it and the password is otherwise indistinguishable from the member (inbox #559). The new kit is saved and
> confirmed before anything is published, so the ceremony that needs the old kit cannot leave the member with none.
> Whoever holds the password and the current Secret Key and secures the account first owns it. That is not a new
> power: the same holder can already move the sign-in address and change the password (ADR 0074 Decision 8). The
> publish voids every open address change, whose proof was taken under the old key.

Add:
> **Access moves first, security after.** In one transaction, the publish re-grants each scope the rotator is alone in,
> at its current epoch, to the new identity, signed by it. The server verifies that signature under the identity the
> same request publishes, and refuses unless the set equals its own split, across every tenant. Then a new epoch, and
> the node re-key, follow from any device of the account. No retired identity is kept, and no state after the publish
> can lose access. Before this, the re-key of such a scope needed the old identity after the publish, and a lost
> answer, a closed tab, a failed re-key or a space in another tenant stranded it (inbox #556).

**ADR 0060, Alternatives rejected:**
> - **(j) Keeping the old identity, sealed under the new Master Key, until every such space is re-keyed.** It would
>   have made a stranded space finishable, but it keeps key material the server stores for no other reason. That
>   needs a seal purpose whose only job is never to be opened as the identity, and a later compromise of the new
>   Master Key yields the old identity too. Re-granting the current epoch in the publish gives the same finishability
>   and keeps nothing.
> - **(k) Minting the new epoch inside the publish.** A space that cannot be rotated at that moment (a suspended
>   tenant, a group in deletion) would block the remedy or be stranded. It also moves the scope rotation into the
>   account's transaction.
> - **(l) A resume record in the browser.** It helps one browser on one device, and it puts the old private key in
>   local storage.

**ADR 0054, § Consequences**, the first bullet's last sentence becomes:
> Re-issuing a kit means rotating the Secret Key and re-wrapping. It is part of securing the account (ADR 0060), not
> of a reset: a reset keeps the Secret Key, so the kit it was opened with stays valid.

**ADR 0028, Decision 6**, the last bullet gains:
> One exception: an account rotation's publish verifies the self-grants it carries, under the identity the same request
> publishes (ADR 0060). That is a consistency check of the request against itself, under the Secret Key's proof. It is
> not a trust judgment about a key.

**ADR 0109, point 7**: "the rotator's own device for the personal scope" becomes "any device of the rotator's account,
for a scope the rotator is alone in".

**The use case.**
- **Goal:** "Replace the Master Key, the identity keypair and the Secret Key together".
- **Postconditions:**
  - the `PASSWORD` and `RECOVERY` wraps open under the **new** Secret Key, and the old Emergency Kit stops working;
  - the proof key is re-registered;
  - each self-served scope holds its current epoch for the new identity from the publish on, and then a new epoch.
- **Step 3** mints the Secret Key too.
- **Step 4** carries the self-grants and the kit-first rule.
- **Step 6** becomes the new epoch from any device.
- **Step 9** gains *waiting* and *blocked* for self-served scopes.
- **E7**, replacing today's:
  > **E7: the ceremony is interrupted after the publish.** The answer is lost, the tab closes, a space's new epoch
  > fails, or a space is in another tenant. Each space the holder is alone in already holds its current key for the
  > new identity, so nothing is lost. The new epoch follows from any device that unlocks the account, the Emergency
  > Kit's included, and the space reads *waiting* until then. A failure the server did not answer is reported as
  > unknown, and the holder is asked to unlock again, never told that nothing changed. A retry finishes rather than
  > re-mints: a password and Secret Key that open the floor to another Master Key are a cold unlock of the account as
  > published. What needs the old Master Key (the Memory pins, the item marks' tags) stays best-effort, as step 4 says.
- **E11** (#553 added E10):
  > **E11: the new Emergency Kit.** It is shown before the publish, and the publish waits until it is downloaded and
  > confirmed. Until the result screen says the account is secured, the old kit is the one that works.

**`en.account.key-model`:**
- the Secret Key row gains "replaced only when the account is secured";
- the proof-key row's "Stable while the Secret Key is" stays true;
- § Rotation after a compromise: "The Secret Key itself is not rotated, so the Emergency Kit stays valid" becomes "The
  Secret Key is rotated too, and the new Emergency Kit is saved before anything is published";
- § Recovery's "the Emergency Kit stays valid" stays, because it is about a reset.

**`security-model.md` § Key-directory trust, the rotation bullet, appended:**
> The publish re-grants every scope the rotator is alone in to the new identity at its current epoch, and the server
> checks those grants against the identity it publishes, so no space depends on the old identity after the publish.

**`ui-web-styleguide.md:273`:**
> A Secret Key, a recovery code or an Emergency Kit cannot be recovered once lost, so the text says so plainly. A kit
> someone else may hold is replaced by securing the account.

**Site, Use (EN):** the title "Secure your account", and the entry facts of Q1. In "What gets replaced": "Your
password stays unless you choose a new one; your Secret Key and Emergency Kit are replaced." The result states of
decision 8. The links list. DE in step.

**Site, Security (`key-hierarchy.md:134`):** the Secret Key row's "When it rotates" becomes "When the account is
secured; a new Emergency Kit is saved first". `:139` becomes "When the account is secured".

## Definition of done

- Securing an account whose answer is lost, whose tab closes, whose new epoch fails, or which has a space in another
  tenant leaves that space readable by the published identity from the publish on. The next device that unlocks the
  account then secures it. Each way has a test that was red on the untouched code.
- After the ceremony:
  - the old Secret Key opens nothing;
  - a flow signed by the old proof key is refused;
  - an address change requested before it does not confirm.
- A retry after a committed publish posts no second rotation.
- No copy says "nothing was changed" where the client did not read the old identity still published.
- The server holds no retired key material.
- `validate_rotation_shape.py` and `validate_grant_writer_arity.py` pin the new shape.
- The ADRs, the use case, the key model, the security model, the styleguide, the coverage YAML and the site
  (EN and DE) say so.
- This CR is deleted, after its Retrospective's findings have moved to where they apply.

## Open questions (owner)

**Q1: The copy. The owner decides the wording; proposed:**

| Key | EN | DE |
|---|---|---|
| `title`, `action` | Secure your account | Konto absichern |
| `sub` | For when someone else may have your password, your Secret Key, or a device that was signed in to your account. | Für den Fall, dass jemand anderes dein Passwort, deinen Secret Key oder ein Gerät haben könnte, das in deinem Konto angemeldet war. |
| `factSignsOut` | All your devices are signed out. Each signs in again with your password and your new Emergency Kit, or you add it from this one with “Add a device”. | Alle deine Geräte werden abgemeldet. Jedes meldet sich mit deinem Passwort und deinem neuen Notfall-Kit wieder an, oder du fügst es von diesem aus über „Gerät hinzufügen“ hinzu. |
| `factNewKit` | You get a new Emergency Kit. You save it before anything changes, and your old kit stops working once your account is secured. | Du bekommst ein neues Notfall-Kit. Du speicherst es, bevor sich etwas ändert, und dein altes Kit funktioniert nicht mehr, sobald dein Konto abgesichert ist. |
| `factPassword` | You can choose a new password at the same time. We recommend it. | Du kannst dabei ein neues Passwort wählen. Wir empfehlen es. |
| `factSharedSpaces` | In spaces you share with others, someone there confirms it’s you before you see what is added after this. | In Bereichen, die du mit anderen teilst, bestätigt dort jemand, dass du es bist, bevor du siehst, was danach dazukommt. |
| `factNotRepair` | What someone else already saw stays seen. Only what is added afterwards is closed to them. | Was jemand anderes schon gesehen hat, bleibt gesehen. Nur was danach dazukommt, ist für ihn zu. |
| `factLinks` | Links you made before stay open until you renew them. You’ll see a list at the end. | Links, die du vorher erstellt hast, bleiben offen, bis du sie erneuerst. Am Ende siehst du eine Liste. |
| `blockedTitle` | These spaces can’t get their new lock yet: | Diese Bereiche bekommen ihr neues Schloss noch nicht: |
| `blocked.unreadable` | This device can’t open it, so it is already closed to you. | Dieses Gerät kann ihn nicht öffnen, er ist also schon für dich zu. |
| `blocked.tenant_suspended` | It is paused. It gets its new lock once it is back. | Er ist pausiert. Er bekommt sein neues Schloss, sobald er zurück ist. |
| `blocked.scope_deleting` | It is being deleted. | Er wird gerade gelöscht. |
| `soleOwner` (kept) | You are the only owner of these spaces. Make someone else an owner first, or they keep the old lock: | (today's, `:2267`) |
| `newPassword` | Also choose a new password | Auch ein neues Passwort wählen |
| `kitKeepOld` | Keep your old kit until this page says your account is secured. | Bewahr dein altes Kit auf, bis diese Seite sagt, dass dein Konto abgesichert ist. |
| `doneOldKit` | Your account is secured. Your old Emergency Kit no longer works, so you can destroy it. | Dein Konto ist abgesichert. Dein altes Notfall-Kit funktioniert nicht mehr, du kannst es vernichten. |
| `status.waiting` / hint | Not yet / The new lock goes in the next time one of your devices opens veyq. What is here stays yours. | Noch nicht / Das neue Schloss wird eingebaut, sobald eines deiner Geräte veyq das nächste Mal öffnet. Was hier ist, bleibt deins. |
| `errorUnknownOutcome` | We couldn’t confirm whether your account was secured. Unlock again with your new Emergency Kit. If it is refused, nothing was changed and your old kit still works. | Wir konnten nicht prüfen, ob dein Konto abgesichert wurde. Entsperre veyq noch einmal mit deinem neuen Notfall-Kit. Wird es abgelehnt, wurde nichts geändert, und dein altes Kit funktioniert weiter. |
| `linksTitle` / `renew` | Links to renew / Renew | Links zum Erneuern / Erneuern |
| `kit.pdfKeepSafe` | Keep this safe. If someone else may have it, secure your account to get a new one. | Bewahr es sicher auf. Wenn es jemand anderes haben könnte, sichere dein Konto ab, um ein neues zu bekommen. |

- **Recommendation: take these.**
- **Reason:**
  - they keep the panel's lock wording (`i18n/index.ts:304-322`) and its existing E4 line;
  - they drop the "Try again" that no button carries out (`:314`);
  - they name no key but the Emergency Kit, which the user holds in their hand;
  - a suspended tenant reads "paused", as the share dialog already says of a space (`share.spaceUnavailable`,
    `i18n/index.ts:1859`, DE `:3746`). ADR 0086 has no row for the tenant, so the copy avoids the word.

## Retrospective

*(filled at implement time)*
