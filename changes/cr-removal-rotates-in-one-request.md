---
id: cr-removal-rotates-in-one-request
title: Removing a group member and rotating its key is one request, and no client writes under a stale epoch
status: planned
satisfies: [uc.spaces.manage-group-members]
adrs: ["0024", "0025", "0020", "0072"]
invariants: [operator-blind E2EE, API-first]
---

## Goal

Removing a group member takes three calls today: `GroupMembersPanel` deletes the membership, reads the member
list, and rotates to whatever that list contains. That causes three defects:
- A server that re-lists the removed member gets the new epoch wrapped to their genuine key. Their pin matches,
  and after `cr-admission-is-a-key-holder-act` their admission tag verifies under the old epoch too.
- A tab closed between the calls leaves the group unrotated.
- A member whose grant is stale (skipped for a changed key, or awaiting re-grant) keeps writing new content under
  the old epoch, which the removed member holds, and the server accepts any epoch on a node create.

This CR makes the removal and its rotation one request, which excludes the removed member by the remover's own
choice. It also stops a client writing while it knows its epoch is stale
([ADR 0024](../docs/decisions/0024-scope-key-rotation.md) Decision 4 and Decision 6's layer-1 half).

## Context & constraints

- **The rule:** ADR 0024 Decisions 4 and 6 and § Guarantee. Decision 6's per-device floor is
  `cr-trust-state-never-goes-backwards`, not this CR.
- **Queued rotations are deliberately untouched.** `scopeRotationWorker.ts` completes rotations another act
  queued: a tenant-member removal that cuts a co-shared group, and an account rotation. It has no knowledge of
  its own to exclude by, so it keeps reading the server's list (ADR 0024 § Guarantee). The owner-only gate on
  `rotateScope` against the any-grant-holder claim of those jobs is inbox #180, not this CR.
- **The standalone member DELETE stays, for one purpose.** Its other caller is rescinding a pending consent
  proposal (`GroupMembersPanel.vue`, `rescind`), which was never keyed, so nothing is rotated.
- **Order:**
  - **After** `cr-seal-binds-its-object`. `rotateScope` states its new epoch there, and the server refuses any
    epoch but `current + 1`. The rotator must state its *own* held epoch + 1, never the server's current.
    That closes three things: inbox #177's race; the chain link a rotator with a stale grant corrupts; and a
    delivery worker labelling a stale key as current.
  - **After** `cr-admission-is-a-key-holder-act`. It re-tags every open admission in the same request and the
    same function (`rotateGroupForRemoval`).
  - **Before** `cr-trust-state-never-goes-backwards`, which extends the same write-epoch check with the floor.
- **Measured against** `main` @ `a494436` on 2026-09-24. The three-call removal is `GroupMembersPanel.vue`
  `removeMember`. `ScopeSummary` already serves `currentEpoch`, and no web code reads it
  (`grep -rn 'currentEpoch' web/src --exclude-dir=generated`). Node create accepts any `epoch`; rename and
  set-icon already check `== currentEpoch`
  (`grep -n 'currentEpoch' backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesHandler.kt`).

## Decisions

- **One request.** The rotation request gains the member it removes.
  - When the request names one, the server deletes that membership and commits the epoch in **one
    transaction**.
  - It refuses a request whose grants include that member (layer 1).
  - The owner gate and the last-owner guard are unchanged.
  - The member DELETE refuses a *granted* member of a group; only a pending proposal is deleted without a
    rotation. The implementer picks the status and code and states them in the contract.
- **Excluded by the remover's own choice.** The client builds the re-grant set from the member list it read
  **before** the removal (the list the owner picked from), minus the member it removes. It also drops any row
  with that user id from any list it reads, so a duplicate row cannot slip past.
- **No write under a known-stale epoch (layer 1):**
  - **Client.** A scope whose `grantedEpoch < currentEpoch` reads as *awaiting the current key*: nothing is
    written into it until the re-grant arrives. Reuse the existing awaiting-access presentation; no new UI.
  - **Server.** An epoch-tagged write whose epoch is not current is refused (`409 stale_epoch`), extending the
    check rename and set-icon already make. Every such write is found by
    `grep -n 'epoch' contracts/api/content.openapi.yaml contracts/api/scopes.openapi.yaml`, read as a list.
- **The failure copy becomes true.** A failed removal-rotation leaves the member in place, so "removing failed"
  now means what it says.

## Phases

- [ ] 1 contracts (api) — the removal field on the rotation request, the refusals, and `stale_epoch` on the
      epoch-tagged writes. Before editing, run `grep -n 'RotateScopeRequest' scripts/*.py` (empty at refine) and
      update any guard that has come to pin that request's shape since.
      · Done when: `npm run generate:api` + `npm run check` (web) green, all `scripts/validate_*.py` green. The
      backend is red until phase 2, which is expected.
- [ ] 2 backend — the transactional remove-and-rotate, the grants refusal, the member-DELETE rule, the
      stale-epoch refusal.
      Locators: `grep -rnE 'fun (rotate|removeMember|removeMembership|createNode)\b' backend/src/main/kotlin/com/veyq/app/features`
      and, for the tests that remove or rotate,
      `grep -rlE 'removeMember|rotateScope|/epochs' backend/src/test`.

      **Bites (write them first):**
      - A removal-rotation whose grants include the removed member → refused, and neither the membership nor
        the epoch has changed. Control: without it → the member is gone and the epoch is `n+1`.
      - Atomicity: a removal-rotation that fails after validation leaves the membership in place. Name the
        lever the test uses to make the commit fail, and bite it.
      - A node create at epoch `n` while current is `n+1` → `409 stale_epoch`. Control: `n+1` → `201`.
      - The member DELETE on a granted group member → refused. Control: on a pending proposal → deleted.
      · Done when: `./gradlew check` green.
- [ ] 3 web — the panel's removal becomes one call, the exclusion, and the stale-grant write gate.
      Locator: `grep -rnE 'rotateGroupForRemoval|removeGroupMember|grantedEpoch|writeKey\(' web/src` (tests
      included).

      **Bites — live defects on the untouched tree. Write them first and see them red:**
      - The server re-lists the removed member after the removal. Today `rotateGroupForRemoval` wraps the new
        epoch to them. After the fix the request's grants do not name them. Control: every remaining member
        is named.
      - A member with `grantedEpoch < currentEpoch` creates a node. Today it is sealed under the old epoch.
        After the fix the scope reads as awaiting and nothing is written. Control: equal epochs → created.

      Every negative anchors on its specific refusal, not on "it threw".
      · Done when: `npm run check` (web) green, and `python3 scripts/run_web_e2e.py` green.
      `scopeRotation.e2e.test.ts` crosses the rotation end to end.
- [ ] 4 docs + coverage — `uc.spaces.manage-group-members` gains *"A removal and its rotation are one request;
      the new epoch is never granted to the removed member, whatever member list the server returns"* and
      *"A member whose grant is older than the scope's current epoch writes nothing until it is re-granted"*.
      Its main flow's "remaining members" step is edited to say where the set comes from. Delete the part of
      ADR 0024 § What is still open that this closes. Flip the rows in
      [security-model § What holds today](../docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve).
      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- Every phase done. Baseline before the first edit:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`.
- Both web bites were seen red on the untouched tree.
- `grep -rn 'removeGroupMember' web/src --exclude-dir=generated` shows only the consent rescind.

**Not proven:** anything about the queued rotations, or a rotation withheld from members who never saw it
(ADR 0024 § Guarantee). No test pretends otherwise.

## Retrospective
