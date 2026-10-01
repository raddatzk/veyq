---
id: cr-grant-verifies-under-its-signing-key
title: A grant is verified under the key its granter signed it with, so an account rotation does not lock out everyone the rotator granted
status: planned
satisfies: [uc.account.rotate-keys, uc.spaces.switch-spaces]
adrs: ["0028", "0060", "0024"]
invariants: [operator-blind E2EE, API-first]
---

## Open for the owner

**Fourth round answered by the owner, 2026-10-01: every recommendation added since the third answer is taken too** (incl. the granter-lock questions Q1–Q3 and the rotation CR's Q8/Q9).

**Opened 2026-10-01 by the inbox #233 refine.** Nothing here has a go yet. The CR is written for the
recommendation in each question, and phase 0 records the answers before any code changes.

1. **Which key does a grant row serve: the granter's key as of the grant, or the current one?**
   **Recommendation: the key as of the grant (D1).** The server stores a snapshot of the granter's identity public
   bundle with every grant it writes, and serves that snapshot.
   - **It changes no trust decision on the client.** The client already takes the key from the row, verifies the
     signature under it, and then runs the TOFU gate on that same key (§ Inventory 4). The pin is the trust
     anchor; the served key never was. An operator can serve any key today, and still can afterwards.
   - **It removes the lock for every recipient who has not accepted the rotator's new key.** Their pin still
     holds the old key, and the old key is what signed their grant (§ Inventory 6, cases 1 and 5).
   - **Its lifetime is bounded by the covering rotation.** That rotation replaces every remaining member's grant
     with one the committing owner signs, so after it lands no grant at the scope's current epoch is signed with
     the retired key. A member the rotation skipped keeps a grant at the old epoch only. The rotator's own grant
     is bound to their old identity, and their client verifies it against the new one, so it refuses it whoever
     signed it (`grep -n "identityPublicKeyB64: self.pubB64" web/src/features/spaces/grantTrust.ts`).
   - **The alternatives** are in § Options: an identity-history table does the same with a second table; the
     rotator re-signing its grants couples the covering rotation to the human re-admission review (and launders
     what the server says the rotator signed); a recipient self-endorsing its grant freezes a possibly planted key
     past the out-of-band check meant to catch it.
2. **What happens when a recipient accepts the rotator's new key before the covering rotation reached them?**
   Under D1 their pin moves to the new key, the snapshot is the old one, and the grant is refused as
   `granter_key_changed` (§ Inventory 6, case 6). If that recipient was the owner who would have rotated, the job
   fails on them. uc.account.rotate-keys' own sequence diagram draws this order (*"alt accepted out-of-band"*).
   **Recommendation: drain first, then fail closed (D3).** Accepting a changed key runs one pass of the
   scope-rotation worker before the pin moves. That pass already runs on every app entry and every 45 s
   (§ Inventory 8), so an owner who is online has almost always covered the scope before they can reach the
   accept button. The pass only closes the remaining window. Whatever it cannot cover stays refused, and the
   retired key is never trusted again.
   - **Not taken: keep the replaced key as trusted for verifying old grants.** That key is the compromised one.
     With an active operator, the thief could keep planting grants under it after the human has explicitly moved
     on, which is the ADR 0028 Decision 6 attack with its detection removed.
   - **Not taken: re-sign (self-endorse) the recipient's grants at accept time.** If the key being replaced was
     itself an operator substitution, the endorsement launders the plant at the very moment a safety-number check
     would have exposed it.
3. **Does this CR land before `cr-queued-rotation-owners-only-no-leased-join`?** **Recommendation: either order;
   prefer this one first.** They share no decision. They touch two of the same files (the grant-delivery branch
   of `TasksStore.complete`, and a new migration each), so the second to land re-derives its migration number
   and rebases one `update` statement. Landing this one first removes the deterministic cause of the dead-letter
   that the other CR's D9 makes visible, so that CR's B8/B9 `failed` legs are then reached only through their
   own forced failures (which is how they are written already).

## Goal

[ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md) Decision 6 has every grant signed by
its granter, and the recipient verifies it before the key inside is used. The grant row serves the granter's
**current** identity key. An account rotation ([ADR 0060](../docs/decisions/0060-key-rotation-after-compromise.md))
replaces that key, so from that moment every grant the rotator ever signed fails verification as `bad_signature`.

That is inbox #233. The first two links are run (§ Inventory 5 and 6); the worker's failure path is read (7):
- every member whose current grant the rotator signed reads the scope as *awaiting access*;
- an **owner** among them cannot open the scope, so the queued rotation it is the eligible worker for throws
  `cannot open scope to rotate`, and the worker fails the job;
- where the rotator signed every owner's grant (they committed the last rotation, or admitted every other owner
  and nothing has rotated since), every claim fails and the job dead-letters. The scope freezes for those
  members, and the old identity keeps the current epoch, which is the harm the rotation was for.

ADR 0028 calls the lockout deliberate *"until the rotation's re-grant reaches the recipient"*. In the topology
above no re-grant can be produced, and accepting the rotator's new key does not help either: the signature check
fails before the pin is consulted (§ Inventory 6, case 3).

After this CR:
- a grant verifies under the key its granter held when the server wrote it, whatever the granter did since;
- the recipient's pin still decides whether that key is trusted, exactly as today;
- an account rotation therefore no longer locks out anyone the rotator granted, and the owners it granted can
  complete the covering rotation;
- accepting a contact's changed key first runs the rotation worker, so an online owner covers the scope before
  their pin moves away from the key that signed their grant.

## Context & constraints

- **The promise:** ADR 0028 Decision 6 and its § What is still open paragraph, which names the lock as
  deliberate. This CR reverses that consequence and amends the paragraph (phase 0).
- **The trust anchor is the recipient's pin** (ADR 0028 Decisions 1–2). The server's served key is input to the
  gate, never a verdict. Nothing here moves trust to the server.
- **Operator-blind:** the snapshot is a public key the server already stores and serves (in `veyq_user`), copied
  onto the grant row it already writes. No new class of metadata.
- **The covering rotation** is `cr-queued-rotation-owners-only-no-leased-join` (planned): owners-only claims,
  the rotator excluded at the commit, and D9's per-scope state. That CR's § Inventory for #225 measured this lock
  as the deterministic dead-letter cause, read and not run; this CR runs it.

### Inventory: measured on `main` @ `857d711`, 2026-10-01

`B=backend/src/main/kotlin/com/veyq/app`. Items 5 and 6 were **run**; the rest is read, each with its locator.

| # | Claim | Locator |
|---|---|---|
| 1 | Five store paths write a grant, and each records the authenticated caller as granter. Two are foreign grants (a rotation's re-grants, a grant-delivery completion); three are self-grants (personal and group birth, the tenant's cold birth). | `git grep -n "granterUserId = " -- 'backend/src/main/**/*Store.kt'` → 5 (`ScopesStore` ×3, `TenantStore` ×1, `TasksStore` ×1 inside the JPQL string) |
| 2 | Two reads serve a grant, both through one function that looks up the granter's **current** key. A granter whose account row is gone yields no granter at all, which the client refuses as `unsigned`. | `grep -n "granterOf(" $B/features/spaces/scope/ScopesStore.kt` → `listMine`, `myTenants` and the definition; `grep -n "CURRENT identity public key" $B/features/spaces/scope/ScopesStore.kt` |
| 3 | An account rotation overwrites the key and keeps no history of the old one. | `grep -n "user.identityPublicKey = identityPublicKey" $B/features/account/rotation/RotationStore.kt`; `git grep -n -i "identity.\?history" -- backend/src/main contracts` prints nothing |
| 4 | The client verifies a shared grant under the key the row serves, then runs the TOFU gate on that same key. The signature check comes first, so a key mismatch is `bad_signature` before any pin is read. Self-granted grants ignore the served key and verify under the vault's own key. | `grep -n -e "granterSign, scope, to, wrappedGrant" -e "pinSerially(row.granterUserId" -e "self.pub.sign" web/src/features/spaces/grantTrust.ts` → lines 71, 82, 83 |
| 5 | **Run (backend):** after the rotator R rotates, a co-owner's grant row that R signed is served with R's **new** key beside the unchanged signature R made with the **old** one, on both reads, and that co-owner is the claimant of the queued rotation. | Throwaway `@Test` in `RotationSliceTest` (seed R and O2 as owners of a tenant scope, O2's grant `granterUserId = R`; read `/api/scopes` and `/api/scopes/my-tenants` as O2; rotate R through `/api/account/rotation`; read again; O2 claims `scope-rotation`). `./gradlew test --tests '…RotationSliceTest'` → 13 tests, 0 failures, the throwaway green with `granterIdentityPublicKey` = old before and new after on both reads. Reverted; B1 below is its permanent form |
| 6 | **Run (web, real crypto):** a group grant sealed to the caller and signed by R's old key. **(1)** Served with R's old key, pin `match`: opens. **(2)** Served with R's new key, pin `changed`: `null`. **(3)** Served with R's new key, pin `match` (the caller **accepted** R's new key): `null`, and the pin is never consulted. **(4)** Same row: `commitRotation` throws `cannot open scope to rotate`. **(5)** Served with the old key again (the D1 shape), pin `match`: opens. **(6)** Served with the old key, pin `changed` (the caller accepted the new key before the covering rotation): `null`. | Throwaway `granterLockThrowaway.test.ts` beside `scopes.test.ts`, with that file's mocks for the API client, the vault and `checkAndPin`. `npx vitest run src/features/spaces/granterLockThrowaway.test.ts` → 6 passed. Run with `node_modules` and `web/src/api/generated` symlinked from the main checkout (a refine worktree has neither). Deleted after the run; W1 and W2 below are its permanent form |
| 7 | The worker turns that throw into a failed attempt; five fail the job. | `grep -n "else await failJob(job.jobId, 'rotate-failed')" web/src/features/tasks/scopeRotationWorker.ts`; `grep -n "^veyq.tasks.max-attempts" backend/src/main/resources/application.properties` |
| 8 | An unlocked client drains every queued rotation it may claim on entry, then every 45 s and on tab focus. | `grep -n -e "POLL_INTERVAL_MS = " -e "immediate: true" web/src/features/tasks/scopeRotationPolling.ts` |
| 9 | Accepting a changed key overwrites the pin unconditionally. It is reached from two surfaces. | `grep -n "export async function acceptKeyChange" -A5 web/src/features/account/identityPins.ts`; `grep -rln "acceptKeyChange(" web/src --include='*.vue'` → `ChangedKeyReviewDialog.vue`, `GroupMembersPanel.vue` |
| 10 | The rotation's re-grant skips a member whose key reads `changed`, so the covering rotation never needs the rotator's new key accepted. | `grep -n "checkAndPin(m.userId, m.identityPublicKey)" web/src/features/spaces/scopes.ts` |
| 11 | The docs that state the current-key behaviour. | `grep -n "the granter's current key" docs/decisions/0028-identity-authenticity-tofu-verification.md`; `grep -n "granter's current identity public bundle" contracts/api/scopes.openapi.yaml` → 2 (`ScopeSummary`, `MyTenant`); `grep -n "alt accepted out-of-band" docs/features/account/use-cases/uc.account.rotate-keys.md` |
| 12 | The bundle a snapshot stores is the serialized identity public bundle: X25519 (32 B), ML-KEM-768 (1184 B), Ed25519 (32 B), each length-prefixed, so about 1.7 KB of base64 per grant row. The wire field already caps it at 8192. | `grep -n "export const serializeIdentityPublic" -A1 packages/crypto/src/v1.ts`; `grep -n "ml_kem768" packages/crypto/src/v1.ts`; the `maxLength` under `granterIdentityPublicKey:` in `contracts/api/scopes.openapi.yaml` |
| 13 | No path deletes an account today, so D1's change for a deleted granter (the snapshot keeps serving) has no live caller. | `git grep -n "VeyqUser.delete" -- backend/src/main` prints nothing |
| 14 | The existing coverage for Decision 6. | `grep -n "a-grant-opens-only-if-its-granter-signed-it" -A10 docs/coverage/features/spaces/use-cases/uc.spaces.switch-spaces.yaml` |

## Options

- **(a) Snapshot the granter's key on the grant row, and serve it. Recommended (D1, Q1).** One nullable column on
  `veyq_membership`, written by the five store writers from the granter's row in the same transaction, served by
  the two readers. No client change for verification.
- **(b) An identity-key history table**, and serve the key that was current when the grant was written. It needs
  the history table, a write in the rotation, and a written-at column on the grant to look up against. That is
  (a) with two more moving parts and the same answer.
- **(c) The rotator's client re-signs its outstanding grants under the new key** during the rotation, after
  verifying each under its own old key. The recipient's pin still holds the old key, so every re-signed grant reads
  `granter_key_changed` until a human accepts the new key. The covering rotation would then wait for the
  re-admission review, which ADR 0060 Decision 4 keeps separate. It also vouches, under the new key, for whatever
  the old key signed, the thief's grants included.
- **(d) The recipient self-endorses a grant once it verified it** (re-signs it under its own key, a self-grant).
  It survives every future granter change, which is the defect: when a later out-of-band check exposes the
  granter key the recipient pinned as an operator substitution, the plant is already endorsed.
- **(e) The client verifies under its pinned key when it has one, and under the served key only at first use.**
  Client-only, no migration. It misses the recipient who has no pin of the rotator yet (a member R delivered to who
  has not opened the scope since), and it turns a contact's legitimate key change into `bad_signature` instead of
  the `granter_key_changed` notice.

## Decisions (for the recommendations)

- **D1: every grant row carries the granter's identity public bundle as it was when the server wrote the grant,
  and the reads serve that.**
  - `veyq_membership` gains `granter_identity_public_key TEXT NULL` and `MembershipEntity` its field.
  - **Write:** each of the five writers (§ Inventory 1) sets it from the caller's `VeyqUser.identityPublicKey`,
    read in the same transaction. The invariant is: non-null exactly when `granterUserId` is. The three self-grant
    writers keep it too, although the client ignores the served key for a self-grant, so that no reader needs a
    special case.
  - **Read:** `granterOf` serves the snapshot instead of looking the granter up. The `VeyqUser` lookup goes.
  - **No lock against the granter's own rotation.** If a grant write and the granter's account rotation
    interleave, the snapshot and the signature can disagree. That fails closed (`bad_signature`), which is today's
    behaviour for that grant, and a rotator is outside the scope from that moment anyway (ADR 0060 Decision 6).
  - **Backfill:** the migration fills existing rows from the granter's current key
    (`update veyq_membership m set granter_identity_public_key = (select u.identity_public_key from veyq_user u
    where u.id = m.granter_user_id) where m.granter_user_id is not null`). That is right for every grant whose
    granter has not rotated since. For one whose granter already rotated it is as wrong as today, and nothing can
    do better, since no history exists (§ Inventory 3).
- **D2: the client's verification is unchanged.** `verifyOwnGrant` keeps verifying under the served key and then
  pinning or checking it. Stated because it is the property Q1 rests on: the server's choice of key decides nothing
  the pin does not also decide.
  - **A consequence, stated:** a recipient with no pin of the rotator yet, whose grant the rotator signed with the
    old key, now opens it and pins the **old** key on first use. Their client then reads the rotator as `changed`
    against the directory, which is the truth of the history. Today that recipient cannot open the grant at all.
- **D3 (Q2): accepting a changed key first runs one scope-rotation worker pass, then moves the pin.**
  - The pass is `runScopeRotationWorker()`, awaited, with its result ignored. It is called at the one place both
    accept surfaces go through (§ Inventory 9). `identityPins` itself stays free of the tasks feature, so the
    wrapper lives in `features/spaces`.
  - Whatever the pass could not cover stays refused after the pin moves. No retired key is trusted again.
  - The accept dialog's copy says, in plain words, that if a space still awaits its re-key, accepting now can leave
    the caller waiting for another member to complete it. EN and DE.
- **Unchanged, stated:**
  - The grant signature's content, its verification, and the PERSONAL rule.
  - What an active operator can do: it chooses the served key today and afterwards; the pin decides in both.
  - The rotator's own grants stay refused on their own client (each is bound to the old identity, and the client
    verifies against the new one), which is ADR 0060 Decision 6 and `cr-queued-rotation-owners-only-no-leased-join` D7's honest-client premise.

## Phases

- [ ] 0 decision (docs only, after the owner answers):
      - **ADR 0028 § What is still open:** the *"granter's current key"* sentence and its two *"deliberate"*
        consequences become: the rows serve the key as of the grant; a granter's rotation leaves their grants
        verifiable under the recipient's existing pin until the covering rotation replaces them; accepting the new
        key before that refuses them (D3 names the drain). A pointer here.
      - **uc.account.rotate-keys:** a new exception for the accept-before-cover order (D3), and the sequence
        diagram's *"alt accepted out-of-band"* branch says the re-grant then follows by grant-delivery after the
        covering rotation, not inside it (§ Inventory 10 is why).
      · Done when: all `scripts/validate_*.py` green.
- [ ] 1 contracts (descriptions only): `granterIdentityPublicKey` on `ScopeSummary` and `MyTenant`
      (§ Inventory 11) says *"the granter's identity public bundle as it was when the grant was written"*, and
      why: the TOFU gate decides whether it is trusted. No shape changes, so no backend or web delta.
      · Done when: the contract guards are green, and `npm run generate:api` then `npm run check` in `web/`.
- [ ] 2 backend:
      - The migration (the next `V<n>`; re-derive with `ls backend/src/main/resources/db/migration | sort -V |
        tail -1`, it was `V58` at refine, and the sibling CR adds one too), with D1's backfill. Keep its SQL to the
        H2 ∩ MariaDB subset (backend/CLAUDE.md § Migrations).
      - `MembershipEntity.granterIdentityPublicKey`, the five writers, `granterOf`, and the KDocs that say
        *"CURRENT identity public key"* (`MembershipEntity`, `ScopesStore.granterOf`).
      - Write B1 and B2 first and record each red.
      · Done when: `./gradlew check` green.
- [ ] 3 web:
      - D3's wrapper and both accept surfaces calling it; the accept copy in EN and DE.
      - Write W1 and W2 first; record W2's red. W1 is a regression pin (see its note).
      · Done when: `npm run check` green in `web/`.
- [ ] 4 docs + coverage:
      - uc.account.rotate-keys gains a criterion `grants-outlive-their-granters-rotation` carrying B1, W1 and W2.
      - uc.spaces.switch-spaces `a-grant-opens-only-if-its-granter-signed-it` gains B2 (the arity of the writers
        is part of what makes the served key the signing one).
      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- **Baseline.** Measured green at refine on `857d711` plus this refine's own docs (this CR, the sibling CR's
  edits, three inbox cells): `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f";
  done` printed nothing. The implementer runs it again before the first edit; a red from that run is not this CR's.
- **B1: a grant the rotator signed is served with the key that signed it (D1).** A backend slice test in
  `RotationSliceTest`, a **live defect** (§ Inventory 5 is its red, run at refine).
  - **Setup:** rotator R and owner O2 on a tenant scope; O2's grant written by R **through a real grant path**
    (R commits a rotation of that scope re-granting O2, or R completes O2's grant-delivery), not seeded, so B1
    also covers a writer.
  - **Action:** R rotates through `/api/account/rotation`.
  - **Assert:** O2's `/api/scopes` and `/api/scopes/my-tenants` rows both serve R's **old** key and the unchanged
    signature. **Red today:** the new key.
  - **Positive control, same run:** a grant R writes **after** rotating (for instance a self-served personal
    re-key) is served with the new key. That separates "the snapshot" from "the key never updates".
  - **Lever:** make `granterOf` read the current key again. Both reads go red.
- **B2: every grant writer records the snapshot.** A backend slice test per writer, or one parameterized test, a
  **live defect** (the column does not exist today).
  - For each of the five writers (§ Inventory 1), after a real write the row's snapshot equals the granter's key
    at that moment.
  - **Arity:** the test names the five writers and asserts the count against
    `git grep -n "granterUserId = " -- 'backend/src/main/**/*Store.kt'`, so a sixth writer added later without the
    snapshot fails it. If the implementer finds the count better held by a `scripts/` guard, it goes there instead
    (changes/README.md § promote one-shot proofs) and the test keeps the per-writer assertions.
  - **Lever:** drop the snapshot from the grant-delivery writer. That writer's leg goes red.
- **W1: the composition, with real crypto (D1, D2).** A web unit test beside `scopes.test.ts`, shaped as
  § Inventory 6.
  - Cases (1), (5) and (6) as assertions: old key served with a matching pin opens; the same row with a `changed`
    pin does not.
  - Case (3) as the guard on Q1's premise: served the new key, the signature fails before the pin is read
    (`checkAndPin` not called).
  - **Note:** the client does not change, so W1 is green on both trees. It pins the contract B1's served value
    must meet, with real signatures; B1 is the red. Together they are the chain: the server serves the signing
    key, and the client opens what it is served.
- **W2: accepting a changed key drains the rotation worker first (D3).** A web unit test, a **live defect**.
  - **Assert:** `runScopeRotationWorker` resolves before the pin store is written, and a worker error does not
    stop the accept.
  - **Red today:** the worker is not called.
  - **Lever:** call it after the pin write. The ordering assertion goes red.
- **E1 (if the e2e harness can drive an account rotation): the lock is gone end to end.** Extend
  `scopeRotation.e2e.test.ts` (live, real crypto, `python3 scripts/run_web_e2e.py` from the **main checkout**): B
  delivers C's grant in a group they both own, B rotates their account keys, and C's `runScopeRotationWorker()`
  completes the queued rotation (epoch advances, C re-granted, B not). **Red today:** C's worker fails the job.
  If the harness cannot drive `rotateKeys` (cold credential), record why and rely on B1 + W1; do not stub the
  rotation.
- **Existing tests carry over per assertion.** `ScopesSliceTest.aGrantIsServedWithItsGranterSignatureAndKey`
  keeps its assertions (a self-grant's snapshot is the owner's own key). Every `granterIdentityPublicKey` fixture
  in `web/src` keeps its meaning (`grep -rn "granterIdentityPublicKey" web/src --include='*.test.ts'`).
- **Invariants.** Operator-blind: a public key the server already holds, copied onto a row it already writes.
  API-first: no shape change.

**Not proven:**
- **That an online owner always covers before accepting.** D3 closes the window for jobs the accepting client may
  claim at that moment. A job leased to someone else at that moment is not covered by the pass.
- **Anything against an active operator.** It chooses the served key before and after; the pin decides.
- **Grants whose granter rotated before this CR's migration.** The backfill cannot know their signing key.

## Proposed inbox rows

None. The refine considered one (nothing lists a member's grants across tenants, so no client pass can enumerate
"every grant this contact signed for me") and dropped it: D3 is claim-driven and does not need that listing, and
no other planned work does.

## Retrospective

(filled when done)
