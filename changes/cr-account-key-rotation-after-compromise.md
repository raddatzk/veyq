---
id: cr-account-key-rotation-after-compromise
title: Rotate the Master Key and identity after a compromise, and re-key every scope
status: planned
satisfies: [uc.account.rotate-keys]
adrs: ["0060", "0028", "0059", "0058", "0025", "0024", "0054"]
invariants:
  - Operator-blind E2EE — the rotation must add no server-visible class beyond a replaced directory value, rewritten ciphertext wraps, epoch indices and queued jobs
  - The blind server never adjudicates a key change — re-admission is a human decision on a client, never a server-side accept
  - API-first — the rotation verb is specified in `contracts/` before either surface is touched
  - Self-hostability — untouched; this CR adds no hosted dependency and no central service, so no phase addresses it
  - Product language (Memory / Chapter) — untouched; this CR adds no content-tier copy, only account + space-level copy, so no phase addresses it
---

## Goal

Give an account holder whose key material has been handed to someone else — the phished device-link grant of
[ADR 0059](../docs/decisions/0059-device-linking.md) — a way to **end** that compromise. Rotation replaces the
Master Key and the identity keypair **together**, re-keys every scope so the old identity reads nothing new,
and is re-admitted to shared scopes only by a human who checks out-of-band. It does not undo anything: what
was already read stays read, and no surface may imply otherwise.

## Context & constraints

The durable truth this builds on, all of it already written — **do not restate it here**:

- [ADR 0060](../docs/decisions/0060-key-rotation-after-compromise.md) — the decision: one mode, the
  cold-credential gate, forward-secrecy-not-repair, the rotator does not re-admit themselves, every shared
  scope rotates including the tenant, and the decline-clearing that Decision 2 of ADR 0028 already promised.
- [uc.account.rotate-keys](../docs/features/account/use-cases/uc.account.rotate-keys.md) — the flow, its seven
  exception paths and the bilingual copy the wiki derives from.
- [ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md) +
  [en.account.identity-pinning](../docs/features/account/enablers/en.account.identity-pinning.md) — the change
  response that re-admits a rotated key, and the gap in its accept path.
- [ADR 0024](../docs/decisions/0024-scope-key-rotation.md) /
  [ADR 0025](../docs/decisions/0025-forward-secrecy-policy-per-scope.md) §2 /
  [en.spaces.scope-key-rotation](../docs/features/spaces/enablers/en.spaces.scope-key-rotation.md) — the epoch
  machinery this drives and the tenant re-key it is the first caller of.
- [en.account.key-model](../docs/features/account/enablers/en.account.key-model.md) — what is replaced, what is
  not (the Secret Key), and why the Emergency Kit survives.

**The shape of the work, stated once so no phase re-derives it: the machinery is built and the trigger is
not.** The epoch chain, `POST /api/scopes/{scopeId}/epochs`, the stale-epoch reconcile, the `SCOPE_ROTATION`
job, the TOFU gate at all three foreign-wrap sites, the decline seam and the changed-key review all exist and
are covered under their own use-cases. What is missing is the account-side operation, its gate, and two seams
that were never exercised because a changed key could until now only be an attack.

**Two things this CR demands that have no producer today**, both measured, both real work rather than wiring:

- The gate's input does not exist. `web/src/app/vault.ts` holds the opened Master Key and identity private key
  and **no record of which wrap opened them**, so "refuse unless this was a cold unlock" cannot be evaluated.
  Locator: `grep -n "setUnlocked" -r web/src` and read `web/src/app/vault.ts`.
- The job row cannot express the exclusion. `TasksStore.originateScopeRotation(scopeId, maxAttempts)` takes no
  principal, so nothing records that the rotator must not claim their own rotation.
  Locator: `grep -rn "originateScopeRotation\|fun claim" backend/src/main/kotlin/com/veyq/app/features/tasks/`.
  A candidate shape that needs **no** schema change is to write a `veyq_job_decline` row for the rotator at
  origination — the per-worker exclusion mechanism already exists and is already how a worker is kept off a
  job. Confirm it against the claim query before adopting it:
  `grep -n "job_decline\|JobDeclineEntity" -r backend/src/main/kotlin`.

**Constraints, stated as intent rather than as a prohibition:** the epoch mechanism, the grant-delivery
reconcile predicate and the pairing ceremony keep their current semantics — this CR adds callers and one
exclusion rule, it does not re-specify how rotation, delivery or pairing work. Where a detail must change to
make the above true, that is expected; a blanket "do not touch `TasksStore`" would collide with the exclusion
this CR requires.

**Every terminal and non-terminal pairing state, because the rotation invalidates an envelope's contents and
the cleanup must not be specified against only the state that prompted the thought.** `DevicePairingEntity`
carries `PENDING`, `ANSWERED`, `SEALED`, `COLLECTED`, `SUPERSEDED`, `EXPIRED`
(`grep -n "const val" backend/src/main/kotlin/com/veyq/app/features/account/pairing/Entities.kt`):
- **`PENDING`, `ANSWERED`, `SEALED`** — live, and their envelope carries (or will carry) the **old** Master Key
  and a delegated refresh token. A rotation must supersede them, or a later collect hands a device a dead key
  and a live session.
- **`COLLECTED`** — spent. That device is covered by the wrap deletion and the session revoke; nothing extra.
- **`SUPERSEDED`, `EXPIRED`** — unspent terminal; `PairingPurge` already revokes their delegated family. Nothing
  to add, stated so the next reader does not have to re-check.

## Phases

- [ ] **1 contracts** — the rotation verb on `account.openapi.yaml` (a single transactional write: the new
      identity public key, `wrappedIdentityPrivateKey`, the rewritten `PASSWORD`/`RECOVERY` wraps, the deletion
      of every other wrap, and `recoveryMasterKeyTag`), plus whatever the exclusion rule needs on the tasks
      spec. **No `mode`, `scope` or `identityOnly` discriminator anywhere in the request** — ADR 0060 Decision 1
      is that there is one mode, and a flag is how it comes back. ·
      Done when: `npm run generate:api` is clean and
      `for f in scripts/validate_*.py; do python3 "$f"; done` is green.
- [ ] **2 backend** — the rotation endpoint and its transaction; origination of a `SCOPE_ROTATION` job per
      shared scope; the rule that the rotator cannot claim their own; and the **deletion of the declining
      worker's exclusion when a changed key is accepted** (ADR 0028's *"the wrap then proceeds"*, which does not
      hold today). · Done when: `./gradlew check` green, including a slice test that the rotator's own claim is
      refused and one that an accepted key change makes a previously-declined job claimable again.
- [ ] **3 web** — the entry surface and its pre-confirmation copy; **recording which wrap opened the vault** and
      the gate that reads it; the mint / re-wrap / re-seal sequence; the self-served personal re-key; the
      per-scope result status; and the accept path re-driving delivery. The use-case's E1 and E2 are
      degraded-access flows, so one locator must find the **gate**, not only the insertion point:
      `grep -n "requiresUnlock" web/src/router.ts`. · Done when: `npm run check` green.
- [ ] **4 prose supersession** — three claims are **superseded, not merely edited**, and each has more copies
      than an inventory keyed on a symbol will find. Before writing the site list, run
      `grep -rn "rotation that does not exist\|no master-key or identity rotation\|rotation is the live obligation" docs/ contracts/ backend/ web/ scripts/ wiki/`
      and `grep -rniE "forward secrecy|not reversible|unrecoverable" wiki/`. The durable docs **and the two
      inbox rows that carried it** (#12, #111) were corrected with this CR's refine — that first grep is the
      one that found them, after a narrower fragment sweep had missed both, which is why it is written into the
      phase rather than trusted to have been complete. What this phase owns is **`wiki/{en,de}`**, which carries no rotation copy at all
      today (`grep -rniE "rotat|kompromitt" wiki/` → empty) while `uc.account.rotate-keys` now ships the
      bilingual section it is derived from. · Done when: the grep above returns only the corrected sentences,
      and the wiki says what the buttons do.
- [ ] **5 coverage** — `docs/coverage/features/account/use-cases/uc.account.rotate-keys.yaml` moves off
      `status: planned`, every acceptance criterion gains a test ref or an explicit gap with a reason, and the
      `uc.spaces.verify-identity` coverage note is corrected where the decline-clearing lands. ·
      Done when: `python3 scripts/validate_coverage.py` green.

## Definition of done

Tests green on `backend` and `web`; `docs/coverage/` updated for `uc.account.rotate-keys` with
`python3 scripts/validate_coverage.py` green; the whole guard glob green over the **committed** tree
(`for f in scripts/validate_*.py; do python3 "$f"; done`).

**The structural invariant this rests on becomes a permanent guard.** ADR 0060 Decision 1 says there is one
mode, and the shortcut that would quietly undo it is a request field — a `mode`, an `identityOnly`, a
`scopes` subset — which would let identity-only rotation return as a flag while every test stayed green. So
phase 1 adds `scripts/validate_rotation_shape.py` in the family of the existing schema-shape guards
(`validate_device_shapes.py`, `validate_session_family_shape.py`): the rotation request schema's property set
is pinned, and carries no discriminator. Its docstring owes the three fields
([`README.md`](README.md) § trust-chain guards): what it asserts, the defect that bought it — *no incident;
built with this CR because ADR 0060 Decision 1 is a negative that nothing else can hold* — and its limit.
Run its predicate against the **untouched** tree before writing that sentence, then bite it by
snapshot-and-restore, asserting the named guard goes red and that the injection **landed** (new text present
**and** bytes differ from the snapshot) before believing any run.

## Retrospective

<!-- Filled at implement time: what went wrong in planning, in execution, AND in process/collaboration —
     both directions — with the root cause and one concrete "next time". -->
