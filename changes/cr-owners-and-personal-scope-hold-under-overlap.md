---
id: cr-owners-and-personal-scope-hold-under-overlap
title: A group keeps an owner and a member keeps one personal scope when writes overlap
status: in-progress
satisfies: [uc.spaces.manage-group-members, uc.spaces.remove-tenant-member]
adrs: ["0007", "0014", "0002"]
invariants: [operator-blind E2EE everywhere]
---

## Goal
Two rules the docs state hold only for writes that do not overlap. A group must keep at least one OWNER (ADR 0007,
ADR 0014 §4), and a member has one PERSONAL scope per tenant (ADR 0002). Both are checked in a read before the
write, so two overlapping writes both pass the check. This CR moves each check to where the write happens
(developer-styleguide §12). The last-owner check moves under the scope row's lock, and the personal-scope rule
becomes a unique key on the scope row. Inbox #272 and #278.

## Context & constraints
- [uc.spaces.manage-group-members](../docs/features/spaces/use-cases/uc.spaces.manage-group-members.md) A1,
  [uc.spaces.remove-tenant-member](../docs/features/spaces/use-cases/uc.spaces.remove-tenant-member.md) step 4 (the
  orphan rule), [en.spaces.personal-scope](../docs/features/spaces/enablers/en.spaces.personal-scope.md) E1.
- Styleguide §12: a compare-and-set or a lock, checks under the write's lock; the insert half
  (`createOrConflict`) already handles the personal-create loser once an index exists.
- No contract change: `409 last_owner`, `403 not_scope_owner` and `409 personal_scope_exists` already exist
  (`grep -n 'last_owner\|personal_scope_exists' contracts/api/scopes.openapi.yaml`).
- **Production runs MariaDB at REPEATABLE READ.** The test H2 runs READ COMMITTED. On InnoDB the first plain
  read of a transaction fixes its snapshot, so a check read *after* a lock still sees the state from before the
  lock, unless the lock is the transaction's first read. H2 cannot show this. So every fix here takes its lock as
  its transaction's first statement, and says so at the line.

## Measured inventory (main @ 4d9ac60, this worktree)
| What | Locator | Finding |
|---|---|---|
| Last-owner decisions | `grep -rn 'ownerCount\|isLastOwner' backend/src/main` | Three readers. `ScopesHandler.setMemberRole` checks outside any transaction and then writes in `ScopesStore.setRole`. `ScopesStore.rotate` checks under the scope lock and is already sound. `TenantHandler.removeMember` reads counts in `membershipsInTenant` *before* it locks the scopes it cuts. |
| Who can be OWNER | `grep -rn 'MembershipEntity.OWNER' backend/src/main` | A tenant root has one OWNER, set at activation (`TenantStore`), and `setMemberRole` is GROUP-only. Two tenant owners removing each other therefore cannot happen, and no test is written for it. |
| Existing locks | `grep -rn 'PESSIMISTIC_WRITE\|lockRow\|lockScope' backend/src/main` | `ScopeEntity.lockRow` is the scope row's lock, shared by rotations, admissions and every removal. |
| Personal-scope create sites | `grep -rn 'kind = ScopeEntity.PERSONAL' backend/src/main` | One site, `ScopesStore.createPersonal`. Test fixtures seed PERSONAL rows directly and get a null key, which the key allows. |
| Uniqueness today | `grep -n 'veyq_scope' backend/src/main/resources/db/migration/*.sql` | Only the primary key. Nothing covers (tenant, owner, PERSONAL). |

Red first, against the untouched tree. Each test is a live-defect test whose lever is `whileUncommitted`: the
first write's commit is held back and the second write runs inside that window, so the order is forced. Each ran
5 rounds and failed in round 0 on the assertion named here:
- `OwnerRoleRaceSliceTest.twoOwnersDemotingEachOtherLeaveOneOwner`: the second demote answered `Ok`, where
  `NotOwner` was expected. Both owners were demoted.
- `OwnerRoleRaceSliceTest.twoOwnersStepDownTogetherLeaveOneOwner`: `Ok`, where `LastOwner` was expected.
- `OwnerRoleRaceSliceTest.aTenantRemovalOverlappingADemoteLeavesTheGroupAnOwner`: the group's owners were `[]`.
  The removal cut the other owner as one of two, while a co-owner's demote was in flight. The #272 row did not
  name this case. The measurement found it, and it is the same invariant.
- `ScopeCreateRaceSliceTest.overlappingPersonalCreatesUnderTwoIdsCreateOneScope`: `Created`, where
  `AlreadyExists` was expected.

None of these was closed by a landed CR. `cr-managed-writes-flush-only-what-they-change` put the rename and icon
epoch gates and the re-activation under a lock, and left role changes alone. `cr-create-race-losers-answer-their-
conflict` fixed losers whose index already existed, and this rule had no index.

## Decisions (owner's standing answer: "all recommendations")
1. **The role change decides under the scope lock.** `ScopesStore.changeRole` opens its own transaction and takes
   `ScopeEntity.lockRow` as its first statement. It then re-reads the caller's ownership, the target's role and the
   owner count, asks `MembershipPolicy`, and writes. This is the same lock a removal-rotation takes, so a demote and
   a removal of the other owner serialise too. Before the lock the handler keeps only the access gate and the kind
   check, so the order stays access → kind → owner → member → last-owner. Each loser answers what a replay of its
   request gets after the winner commits. *Rejected:* a guarded `UPDATE … where (select count(*) …) > 1`, because
   MariaDB refuses a subquery on the table being updated (error 1093).
2. **The tenant removal locks before it reads.** The target's scope ids are read first, outside the decision's
   transaction. That transaction then locks those scope rows in ascending id order as its first statements, and
   only after that runs the gate, the owner check, the per-scope counts, the plan and the cascade. The existing
   per-step lock stays, because it covers a scope the target joined after the id read. Residual: that late-joined
   scope's counts come from the snapshot, which needs three overlapping events.
3. **One personal scope per member and tenant is a unique key.** A migration adds `veyq_scope.personal_owner`
   (VARCHAR(36), null except on PERSONAL) and `ux_scope_personal_owner (tenant_id, personal_owner)`. Its number
   is the next free one at the final rebase. Both engines allow many NULLs in a unique index. The backfill copies
   each PERSONAL scope's OWNER. If an instance already holds duplicates, one scope per pair keeps the key
   (`MIN(id)`) and the rest get null. The scopes themselves are untouched, and without this step the index would
   refuse to build and the server would not start. `personalScopeExists` reads the key, so the replay question is
   exactly the index's predicate: a collision on the key is always explained, and anything else stays a 500.
   *Rejected:* a lock on the caller's root membership. It guards only the paths that take it, and the index guards
   every insert.
4. **The REPEATABLE READ rule is harvested to styleguide §12**: a check under a lock is fresh only if the lock is
   its transaction's first read. `SharingHandler.reactivate` reads `liveScopeOf` before its lock. It was read and
   not measured, so it goes to the inbox and is not fixed here.

## Phases
- [x] 0 measure: the four red tests above.
- [ ] 1 backend: decisions 1–3 + the migration · Done when: the four tests green, `./gradlew check` green.
- [ ] 2 docs + coverage: the use-case/enabler sentences, coverage refs, styleguide §12 · Done when:
      `for f in scripts/validate_*.py; do python3 "$f"; done` green.

## Definition of done
- The four red-first tests are green, and each keeps a positive control. A demote with a co-owner present lands,
  and another member's personal create lands. Each bite is the live defect recorded above. Re-bite after the fix
  by injection: put the role checks back before the lock (lever: `ScopesHandler.setMemberRole` calls a
  pre-lock `isScopeOwner`/`ownerCount` path), and drop the unique index from a scratch copy of the migration
  (lever: remove the `CREATE UNIQUE INDEX` line). Each must turn its test red again.
- Coverage: `last-owner-guard` (manage-group-members) and `orphan-transfer` (remove-tenant-member) cite the new
  tests. `python3 scripts/validate_coverage.py` green.
- The migration's backfill and dedupe have run once against H2 in MariaDB mode with seeded duplicates (a one-shot
  proof; no CI test runs migrations against stale data). MariaDB itself is not measured, and the CR says so.

## Retrospective
_(filled at close)_
