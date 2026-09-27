---
id: cr-cold-credential-proves-the-secret-key
title: Rotation, password change and recovery prove the Secret Key to the server
status: done
satisfies: [uc.account.rotate-keys, uc.account.change-password, uc.account.recover-access]
adrs: ["0060", "0054", "0017", "0003"]
invariants: [operator-blind E2EE, API-first]
---

## Goal

`rotateAccountKeys` accepts any session, and `changePassword` checks only the password: the Secret-Key half of
both is client-side, and the attacker runs their own client. A session needs only the password, and a
device-link holder is handed one with no password at all. So someone with a phished or cracked password
(`changePassword`, then `rotateAccountKeys`) and a device-link holder (`rotateAccountKeys` directly) can each
install an identity of their own on the account. The next scope
rotation then wraps every shared scope key to that identity for each member who never pinned the old one.
That is inbox #159's "a session is enough to get the plaintext", reached through a second door, and it is
ADR 0060's gate failing against the very attacker it names. This CR gives every account a **Secret-Key proof
key**: an Ed25519 key derived from the Secret Key alone, whose public half is registered at birth. The three
flows that mint a cold credential must carry its signature, and the server refuses them without it. The
signature replaces `recoveryAuthTag`, a hash of the Master Key that a linked device can compute and a database
dump contains.

## Context & constraints

- **The rule:** [ADR 0060](../docs/decisions/0060-key-rotation-after-compromise.md) Decision 2 (the server
  half of the gate, and alternatives (h)/(i)) and
  [ADR 0054](../docs/decisions/0054-password-recovery-two-factor-release.md) Decision 1 and its § Context
  fact 3 (why the tag was the wrong proof).
- **A second asserting stack.** The backend now rebuilds the signed statement's bytes itself, so the vector
  for those bytes is asserted by **both** `packages/crypto` and a backend unit test that reads
  `../contracts/crypto/vectors/v1.json`. `CapabilityTagRegistryTest` already reads a contracts JSON that way.
  See [ADR 0017](../docs/decisions/0017-crypto-primitives-and-post-quantum-strategy.md) rule 4, which the
  refine corrected accordingly.
- **The client's refusal stays.** ADR 0060's live proof, the password + Secret Key re-opening the `PASSWORD`
  wrap, is untouched. This CR adds the server's half and does not replace the client's.
- **A guard anchors on the field being removed.** `scripts/validate_rotation_shape.py` pins
  `RotateAccountKeysRequest`'s exact property set, `recoveryMasterKeyTag` included. Its docstring also says
  the cold-credential gate *"is client-side by construction … and has no contract surface"*, and this CR makes
  that false. `scripts/run_web_e2e.py` names the field too (`grep -n 'recoveryMasterKeyTag' scripts/*.py`).
- **Order:** independent of `cr-seal-binds-its-object` and `cr-admission-is-a-key-holder-act`, and it may land
  first. The new fields arrive under `scripts/validate_request_bounds.py` (`cr-request-bounds-enforced`, done). It is also inbox #156's C8 side item: it replaced a planned "store the tag hashed" CR that was
  withdrawn before it started. It overlaps `cr-request-tenant-and-tenant-bound-recovery` in text only
  (`RecoveryHandler`, `AccountStore`, `RecoverySliceTest`), so either order works and whichever lands second
  rebases. #156 closes when both are done. It also rewrites `PasswordChangeStore.applyChange` and
  `RecoveryStore.applyReset`, whose wrap-delete predicate `cr-wrap-methods-survivor-whitelist` has already
  replaced with the survivor whitelist — keep `method not in WrapMethod.SURVIVORS` as it is (no store spells a
  method literal; `scripts/validate_wrap_method_vocabulary.py` reds one). Both this CR and `cr-upload-windowed-presign` add a Flyway migration, and at planning time each would take `V50`
  (the highest was `V49__item_favorites.sql`); whichever merges second renumbers its file.
- **Every existing account lacks the proof key.** Nothing is migrated. Before V1 the owner resets the
  development instance; tell them, and do not wipe their database yourself.

## Decisions

- **The key.** `seed = HKDF-SHA256(ikm = secretKey, salt = "", info = "veyq/v1/secret-key-proof")`, and from
  the seed an Ed25519 keypair. It lives in `@veyq/crypto` next to `deriveAuthKeypair`, with a vector. The
  public key is registered by every account birth. `buildAccount` in `packages/crypto/src/accountBirth.ts` is
  the shared birth, so web and admin-web inherit it.
- **The statements.** Each is domain-separated per flow and length-prefixed with the same `lp` encoding the
  vectors already use. Each signs what the flow **installs** plus the state it **replaces**, so replaying it
  after a later change fails:
  - rotation: userId, digest of the current identity public bundle, digest of the new one, and digests of the
    two rewritten wraps;
  - change-password: userId, the current `authPublicKey`, the new one, and a digest of the new `PASSWORD` wrap;
  - recovery completion: a digest of the recovery token (single-use and bound to the account), the new
    `authPublicKey`, and a digest of the new `PASSWORD` wrap.

  The exact field list is the vector's to define, and phase 1 writes it first.
- **The server verifies against the stored proof key** and refuses with a distinct code (`secret_key_proof_failed`,
  `403`) before writing anything. The code stays distinct from `attestation_failed`, because "your password
  was wrong" and "your Emergency Kit was wrong" are different things to tell a user.
- **`recoveryAuthTag` / `recoveryMasterKeyTag` / `recovery_master_key_tag` are deleted**, not kept beside the
  signature: two proofs for one gate is duplication, and the weaker one is the bug. Rotation stops re-deriving
  the tag. The proof key survives rotation unchanged, because the Secret Key does.
- **Not in scope:** re-issuing an Emergency Kit (a new Secret Key). That capability does not exist
  (ADR 0054 § Consequences). When it is built, it registers a new proof key under a signature of the old one.

## Phases

- [x] 1 contracts (crypto) — the derivation, the three statement encodings, sign/verify helpers, and vectors
      for the key and for each statement.
      · Done when: `npm run check` in `packages/crypto` green, all `scripts/validate_*.py` green.
- [x] 2 contracts (api) — `AccountRegistration` swaps `recoveryMasterKeyTag` for `secretKeyProofPublicKey`. It
      is in `setup` and `tenants`, and every other copy is found by
      `grep -rn 'recoveryMasterKeyTag\|recoveryAuthTag' contracts/api`. `RotateAccountKeysRequest` swaps it for
      `secretKeyProofSignature`; `changePassword` gains `secretKeyProofSignature`; `completePasswordRecovery`
      swaps `recoveryAuthTag` for it. The operation descriptions name the new refusal. Update
      `scripts/validate_rotation_shape.py`: its set, and the docstring sentence that called the gate
      contract-less. Bite the updated guard by injection (drop the new field → red).
      · Done when: `npm run generate:api` + `npm run check` (web **and** admin-web) green, all
      `scripts/validate_*.py` green. The backend will not compile until phase 3, which is expected.
- [x] 3 backend — a Flyway migration adds the proof-key column and drops `recovery_master_key_tag`;
      registration stores the key; the three handlers verify. The statement builder is pinned to the vector by
      a unit test that reads the contracts file.
      Locator: `grep -rlE 'recoveryMasterKeyTag|recoveryAuthTag|recovery_master_key_tag|RecoveryMasterKeyTag' backend/src`
      (main **and** test; the slice tests that register accounts all carry the field).

      **Bites — live defects on the untouched tree. Write them first and see them red:**
      - `rotateAccountKeys` from a session that holds a valid auth key and **no** Secret Key, with a
        well-formed request carrying a fresh identity → refused, and the identity is unchanged. Today it is
        applied.
      - `changePassword` with a valid password attestation but a proof signature made from a different Secret
        Key → refused. Today there is nothing to refuse it with.
      - `completePasswordRecovery` with a valid token and a signature from a different Secret Key → refused.
        Control: the right key → applied.

      Each negative has its positive control on the same request, and anchors on `secret_key_proof_failed`, not
      on "it failed".
      · Done when: `./gradlew check` green.
- [x] 4 web + admin-web — birth registers the key; rotation, change-password and recovery sign. The Secret Key
      is already in hand on each of those screens (ADR 0060 Decision 2: *"every path through the screen
      supplies them anyway"*).
      Locator: `grep -rlE 'recoveryMasterKeyTag|recoveryAuthTag' web/src admin-web/src packages/crypto/src`.
      · Done when: `npm run check` (web, admin-web, packages/crypto) green, and `python3 scripts/run_web_e2e.py`
      green. Its recovery and rotation suites cross the new fields end to end.
- [x] 5 docs + coverage
      - The three use-cases' coverage files each gain the criterion *"refused without a signature of the
        account's Secret-Key proof key"*, covered by the phase-3 bites.
      - `uc.account.recover-access.yaml`'s criterion that names `recoveryAuthTag` is **edited** to name the
        signature, not duplicated.
      - Sweep: `grep -rn 'recoveryAuthTag\|recoveryMasterKeyTag\|recovery_master_key_tag' docs wiki` returns
        nothing.
      - Flip the row "installs an identity of its own on an account" in
        [security-model § What holds today](../docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve).

      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- Every phase is done. Baseline before the first edit:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`.
- The rotation bite was seen red on the untouched tree. It is the whole #159 second door.
- `grep -rnE 'recoveryAuthTag|recoveryMasterKeyTag|recovery_master_key_tag' --exclude-dir=node_modules --exclude-dir=generated .`
  returns only migration history (`V1__setup.sql`, `V44__account_recovery.sql`, and the new drop).
- The statement vector is asserted by both stacks.

**Not proven:** an attacker who holds the Secret Key *and* a session can still rotate. That is the account
holder by every factor veyq has, and it is the case rotation exists for.

## Retrospective

- **Planning — the docs phase told me to flip a row the change cannot flip.** security-model's table is about an
  *active operator*, and a server-side signature check binds every caller but the operator, who writes the
  directory itself. ADR 0060 § Consequences already said so. Root cause: the flip was specified against the
  attacker the CR was thinking of (a device-link holder, a password phisher), not against the table's subject.
  Harvested into security-model's table intro (a server-run check never closes a row there); the sibling
  `cr-admission-is-a-key-holder-act`, which relied on this CR closing "#159's other row", was corrected. The owner
  confirmed keeping the row at *Can* with the reason stated.
- **Planning — two Done-whens could not hold as written.** Phase 2 promised web and admin-web green after
  `generate:api`; a typed client turns a removed or newly required request field into a compile error, so they are
  red until phase 4 (harvested into `changes/README.md` beside the backend version of the same rule). Phase 4's
  e2e was to run through the main checkout's orchestrator, but that runs `main`'s `web/`, which this CR changes, so
  it would have tested the old client against the new jar. The run instead used the worktree's orchestrator with
  the main checkout's compose file. The Decisions also named `userId` in the rotation statement, and the web client
  cannot know it; it was dropped, with the reason at the line (`v1.ts`).
- **Execution — the locator keyed on the field being removed, so it missed a fixture that never had it.**
  `SetupRequestSanitizationTest` builds a `CreateServerOwnerRequest` without the tag and went red on the new
  required field in the first full run. `changes/README.md` already says to key a narrowing's locator on the input's
  *producer*. The rule existed and the refine did not apply it. Nothing new to write; the second angle
  (`masterKeyWraps` without `secretKeyProofPublicKey`) found no further site.
- **Next time:** when a CR edits a table or a claim, the refine reads that table's subject line and asks whether
  the change binds that subject.

