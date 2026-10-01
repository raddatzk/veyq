---
id: cr-tenant-removal-share-links
title: A tenant-member removal marks every link a departed member could copy, and can turn the tenant's links off
status: planned
satisfies: [uc.sharing.share-shared-scope, uc.spaces.remove-tenant-member, uc.spaces.manage-group-members]
adrs: ["0025", "0029", "0024", "0060", "0036"]
invariants: [operator-blind E2EE, API-first]
---

## Open for the owner

**Third refine wave, 2026-10-01 — open, not covered by the answer below.** Inbox #228 (*a standalone "turn off
every link in this space" for scope owners*) is folded in here, because this CR already owns the predicate that
decides whether a turned-off link may come back. Questions 6–8 and § *Folded: inbox #228* below carry it; the
go on questions 1–5 stands for the rest of the CR whatever the answer.

6. **Fold #228 into this CR?** **Recommendation: yes.** A standalone switch-off is `revokeLinks` without a removal,
   and it is only worth building together with what makes it stick — which is this CR's `ReissuePolicy`. As its own
   CR it would amend that function before it exists.
7. **What makes a bulk switch-off stick?** The removal counter (question 1) does not: Decision 4 judges a share
   **without** a recovery copy by its owner's membership alone, so after a switch-off with nobody removed, **any**
   member re-activates every such link with one click (the planned re-activation adopts it). That argument —
   *"no one else could have rebuilt it"* — is about a departed member; a switch-off says *"a link may have
   leaked"*, and a link without a copy leaks like any other. Options: (a) a second, ungated counter; (b) **a per-row
   mark `reissue_only`** set on every share a bulk switch-off turns off. **Recommendation: (b), set by all three bulk
   paths** — the standalone switch-off, the group removal's `revokeLinks` (planned in `cr-reissue-share-link`
   Decision 3) and the tenant removal's `revokeLinks` (Decision 6) — so "turn off every link" means one thing: they
   come back only by re-issue. The bulk path writes each row anyway, so a mark is exact and needs no new event
   model; shares minted afterwards are untouched by construction.
8. **Who, and where?** **Recommendation: any owner of the scope** (`isScopeOwner` — group owners, tenant owners,
   the personal scope's owner; one gate), through one operation; the web offers it in the group panel (beside
   *delete group*) and in the tenant members panel, behind a confirm that names the effect. Not in the personal
   space: no one else can revive a link there, so it adds nothing a per-link revoke lacks.

**Answered by the owner, 2026-10-01: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists in `docs/inbox.md`.

Nothing below is decided. Each question carries a recommendation; the CR is written for the recommendations.
**Order:** this CR lands **after** `cr-reissue-share-link` (planned, owner-approved). It extends that CR's
re-activation refusal and its "turn off every link" box; every claim below about that CR is *planned in
`cr-reissue-share-link`*, and the implementer re-reads each one against the merged tree before the first edit
([changes/README.md](README.md) § *A sibling CR still in flight is cited as PLANNED*).

**What the refine measured changes the row's premise** (inbox #210). The row says *"no rotation happens on a tenant
scope, so its shares carry no epoch signal"*. Half right:

- **The tenant epoch does move — on the wrong event.** A tenant-member removal never rotates (Inventory row 3), but
  every member's **account key rotation** queues a rotation of every shared scope they belong to, the tenant
  included (row 4). So `cr-reissue-share-link`'s epoch predicate *does* fire on a tenant — only never on a removal.
- **A removed tenant member's reach is the same as a removed group member's:** every enabled link on every
  tenant-scope Memory, rebuildable from its recovery copy (rows 1–2, the argument `cr-reissue-share-link` measured
  for groups). The use case's plain-language section tells tenant owners the opposite: removal *"cuts them off from
  everything shared straight away"* (row 10).
- **The live hole, and it survives `cr-reissue-share-link`:** member M is removed from the tenant; co-member C's
  link S (which M could copy) is still re-activatable by any member after a revoke, because C is still a member and
  the tenant epoch did not move. And if a tenant "turn off every link" were added *without* a removal signal, any
  member could re-activate every link it turned off, one click each. **The signal is what makes the switch-off
  stick** — it is load-bearing, not a hint.
- **The same gap reaches co-shared groups for a while.** A tenant removal cuts M from every group they co-shared,
  but that group's rotation is *queued* to another key-holder (row 3); until a device runs it, the group's epoch has
  not moved either.

1. **What carries the removal signal?** Options: (a) a per-scope **removal counter**, bumped in the transaction of
   every removal of an accepted member, and copied onto each share when it is minted; (b) rotate the tenant on every
   removal — rejected by ADR 0025 (`O(N)` on the primary scope); (c) compare `share.createdAt` with a scope
   `lastRemovalAt` — no share column, but two backend replicas stamp with their own clocks, so a share made seconds
   around a removal is misfiled. **Recommendation: (a).** Exact, one int per row, and the operator already sees
   every membership delete.
2. **Does the counter replace the epoch half of `cr-reissue-share-link`'s predicate, or join it?** **Recommendation:
   join it (OR).** The epoch half's "false positive" — an account key rotation (ADR 0060) — is the *compromise* case:
   a stolen device could list and rebuild every link as well as a departed member could. So a link made before
   either event needs re-issuing. The hint reads *"made before someone left this space or replaced their keys"*.
3. **Where is the predicate computed?** `cr-reissue-share-link` Decision 5 computes the hint in the client from the
   epoch. The counter is not on the wire, so the client can no longer compute it. **Recommendation: one
   server-computed boolean `reissueRequired` on `ShareSummary`, from the same function the re-activation refusal
   calls** — so the badge and the refusal cannot disagree. This supersedes that CR's Decision 5 (and reshapes its
   W2 onto the field). The server can know every input (changes/README § *Before adding a read field*): owner
   membership, the copy's epoch, the two counters.
4. **What does the tenant "turn off every link" reach?** **Recommendation: the TENANT scope's shares only,** as a
   `revokeLinks=true` query flag on `removeTenantMember` (a `DELETE` carries no body), in the removal's own
   transaction, box unchecked by default — the tenant twin of `cr-reissue-share-link` question 1(b). Not the
   co-shared groups: the tenant owner need not be a member of them and cannot see them; there the counter marks
   every link at once and the group's owners re-issue. A standalone *"turn off every link in this space"* for a scope
   owner, outside any removal, is a **proposed inbox row**.
5. **Existing rows.** **Recommendation: backfill 0 on both sides.** Pre-V1 migrations already assume an instance
   reset (row 11), so there is no history to reconstruct.

## Goal

After a tenant member is removed, every link they could have copied — on the tenant scope and on each group the
removal cut them from — is marked "re-issue", cannot be re-activated, and can, in the same act, be turned off on the
tenant scope. One predicate decides the mark and the refusal, for every scope kind.

## Context & constraints

- **Per-scope policy:** [ADR 0025](../docs/decisions/0025-forward-secrecy-policy-per-scope.md) — the tenant revokes
  by ACL, never rotates on removal. This CR does not change that; it adds a counter, not a key event.
- **The share governance it extends:** [ADR 0029](../docs/decisions/0029-public-viewer-share.md) §6 (removal
  soft-revokes the removed member's own shares; the tenant *"relies on this deactivation as its **only** forward
  layer for a public link"*), and the ADR `cr-reissue-share-link` Phase 0 writes (planned).
- **The compromise case:** [ADR 0060](../docs/decisions/0060-key-rotation-after-compromise.md) — why the epoch half
  stays (question 2).
- **Honest-server only**, as in `cr-reissue-share-link`: a dump holder already holds `K_SC` and `K_mem`
  ([ADR 0012](../docs/decisions/0012-content-key-hierarchy.md)); the counter, like `enabled`, is layer 1.

### Inventory — measured on `main` @ `64c1f65`, 2026-09-28

Every row was read or run at refine; the command re-derives it.

| # | Fact | Locator |
|---|---|---|
| 1 | Any member lists every share of a Memory, the tenant scope included; the gate is membership only. | `grep -n "fun listForNode" -A8 backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt` |
| 2 | A tenant-scope share can carry a recovery copy: create checks the copy's epoch against the scope's, for any scope kind. | `grep -n "sealedShareKeyEpoch != scopes.currentEpoch" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt`; the tenant scope id is the tenant id: `grep -n "TENANT scope id == tenantId" backend/src/test/kotlin/com/veyq/app/features/sharing/ShareSharedScopeSliceTest.kt` |
| 3 | Tenant removal: `cutSharedAccess` deletes the membership and soft-revokes the target's own shares per scope; only a co-shared GROUP queues a rotation (`rotateAfter`), the TENANT does not. | `grep -n "private fun cutSharedAccess" -A5 backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantHandler.kt` |
| 4 | An account key rotation queues a rotation of **every** scope with more than one member — no kind filter, so the tenant epoch moves on it. | `grep -n "partition { (counts\[it\]" -A1 backend/src/main/kotlin/com/veyq/app/features/account/rotation/RotationHandler.kt`; `grep -n "fun memberCountsByScopeFor" -A7 backend/src/main/kotlin/com/veyq/app/shared/membership/MembershipStore.kt` |
| 5 | Re-activation checks the caller's access and the capability only. | `grep -n "fun reactivate" -A10 backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt` |
| 6 | Every path that removes an accepted member: the removal-rotation (`ScopesStore.rotate`) and the tenant cascade. Pending-proposal deletes are not removals (never keyed, no grant to open a recovery copy). | `grep -rn "removeMembership(\|deactivateSharesByOwnerInScope(\|deletePendingMembership(" backend/src/main/kotlin` |
| 7 | `removeTenantMember` is a `DELETE` with path parameters only. | `grep -n "operationId: removeTenantMember" -A22 contracts/api/tenants.openapi.yaml` |
| 8 | The tenant panel removes on a plain yes/no confirm; `ConfirmRequest` has no option field. | `grep -n "async function remove" -A12 web/src/features/spaces/TenantMembersPanel.vue`; `grep -n "export interface ConfirmRequest" -A11 web/src/shared/state/useConfirm.ts` |
| 9 | The existing tenant-cascade share test (the control for "without the flag"). | `grep -n "fun removingTenantMemberDeactivatesTheirTenantScopeShares" backend/src/test/kotlin/com/veyq/app/features/sharing/ShareSharedScopeSliceTest.kt` |
| 10 | The tenant-removal copy that overstates the cut (the use case's EN/DE *What this means for you*; no wiki page carries the sentence). | `grep -rn "cuts them off from everything shared" docs wiki` |
| 11 | Pre-V1 migrations assume an instance reset. | `grep -n "before V1" backend/src/main/resources/db/migration/V57__uploader_drop_key.sql` |
| 12a | `ShareSharedScopeSliceTest` creates shares without a recovery copy; the copy-carrying create lives in `ShareKeyRecoveryEpochSliceTest`, whose helper B1 borrows (or `cr-reissue-share-link` B1 has already lifted). | `grep -rln "sealedShareKeyEpoch" backend/src/test/kotlin/com/veyq/app/features/sharing` |
| 12 | Next free migration number (re-run at implement: a sibling may take it). | `ls backend/src/main/resources/db/migration \| sort -V \| tail -1` → `V58__…` |

*Remembered, not checked:* that the scope rotation job cannot run inside a backend slice test (it is executed by a
client device, ADR 0021), so a queued group rotation stays queued in B2. The implementer confirms it before relying
on it: `grep -rn "SCOPE_ROTATION" backend/src/main/kotlin | grep -v "originate"`.

## Decisions (for the recommendations)

1. **Schema** (next free migration): `veyq_scope.member_removals INT NOT NULL DEFAULT 0`;
   `veyq_share.member_removals_at_mint INT NOT NULL DEFAULT 0`.
2. **The bump** — in the same transaction as the membership delete, on each of row 6's two removal paths:
   `ScopesStore.rotate`'s removal branch, and `TenantHandler.cutSharedAccess` for **every** scope it cuts (TENANT and
   each co-shared GROUP). One store method, `ScopesStore.recordRemoval(scopeId)`, so a third removal path has one
   call to make.
3. **The stamp** — `SharingStore.create` (and `reissue`, planned in `cr-reissue-share-link`) copies the scope's
   current `member_removals` onto the new row inside its transaction.
4. **The predicate** — one pure function, `ReissuePolicy.required(ownerIsMember, copyEpoch, currentEpoch,
   removalsAtMint, removals)`:
   `!ownerIsMember || (copyEpoch != null && (copyEpoch < currentEpoch || removalsAtMint < removals))`.
   A share with no recovery copy is judged by its owner's membership alone: no other member could rebuild its link.
   Re-activation calls it (`409 reissue_required`); `listShares` calls it per row for `reissueRequired`.
5. **`reissueRequired: boolean`** on `ShareSummary` (required). The web hint reads it; the client-side epoch compare
   planned in `cr-reissue-share-link` Decision 5 is not built (or is removed, if it landed).
6. **`revokeLinks`** — optional boolean query parameter on `removeTenantMember`. With `true`, the removal's
   transaction also sets `enabled = false` on every enabled share of the TENANT scope. Nothing is written if the
   removal refuses (`last_owner`, `member_not_found`, `not_tenant_owner`).
7. **Web** — `TenantMembersPanel` asks through the confirm-with-option `cr-reissue-share-link` Phase 3 builds for the
   group panel (planned; if it landed as a one-off in `GroupMembersPanel`, lift it into `useConfirm` rather than
   copy it). `ShareDialog` shows the hint from `reissueRequired`, and no re-activate button where it is true.

## Phases

1. **Phase 0 — the decision on record.** Amend the ADR `cr-reissue-share-link` wrote: the predicate gains the
   removal counter, the hint moves server-side, the tenant gets `revokeLinks`. Point ADR 0025's *Consequences* and
   ADR 0029 §6's tenant sentence at it. · Done when: all `scripts/validate_*.py` green.
2. **Phase 1 — contracts.** `ShareSummary.reissueRequired`; the `revokeLinks` query parameter and its paragraph on
   `removeTenantMember`; `reactivateShare`'s `409 reissue_required` description names the counter half.
   · Backend red until Phase 2 (a new required field on an existing schema); web gains a field.
   · Done when: `npm run generate:api` and the contract guards are green.
3. **Phase 2 — backend.** Migration, Decisions 1–4 and 6. · Done when: `./gradlew check` green, including B1–B4.
4. **Phase 3 — web.** Decisions 5 and 7; i18n EN + DE. · Done when: `npm run check` green in `web/`, including W1–W2.
5. **Phase 4 — docs.** `uc.spaces.remove-tenant-member` (new alternative flow: *turn off every link*; the
   postcondition names the links a member keeps until re-issued) and its coverage YAML; `uc.sharing.share-shared-scope`
   (the hint's two causes); security-model § What stays open (the tenant row); row 10's
   sentence and its DE twin; the wiki pair `wiki/en/is-my-data-safe.md` + `wiki/de/sind-meine-daten-sicher.md` if
   `cr-reissue-share-link`'s paragraph there does not already say it for every space. Inbox #210
   → done. · Done when: `python3 scripts/validate_coverage.py` and all `scripts/validate_*.py` green.

## Definition of done

- **Baseline before the first edit** — run it, don't trust this line:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done` printed nothing on
  `64c1f65` at refine.
- **B1 — a co-member's tenant link is not revived after a removal (red-first; live today, and still live after
  `cr-reissue-share-link`).** In `ShareSharedScopeSliceTest`:
  - **Setup:** tenant owner O, members M and C. C creates tenant-scope share S **with** a recovery copy at the
    tenant's current epoch. O removes M (`DELETE /api/tenants/{t}/members/{M}`). C revokes S.
  - **Action:** C `POST /api/shares/S/reactivate`.
  - **Red today:** `204`, and S resolves `200`. **The assertion expected to fail first** is the `409` status.
  - **After:** `409` with code `reissue_required`; S still `404`.
  - **Positive control:** C's share S2 minted *after* the removal, revoked and re-activated → `204`, then `200`.
  - **Null-copy control:** C's share S3 with **no** recovery copy, minted before the removal, revoked and
    re-activated → `204` (no one but C could rebuild it).
  - **Bite, per lever:** drop the bump in `cutSharedAccess` → B1 red, S2 green. Drop the `copyEpoch != null` gate
    from the counter half → S3 red, B1 green.
- **B2 — a co-shared group is marked before its queued rotation runs.** M co-shares group G with C; C's G-share
  minted before the tenant removal; the queued rotation has not run (G's epoch unchanged — asserted, not assumed).
  Revoke, re-activate → `409 reissue_required`. **Red today and after `cr-reissue-share-link`:** `204`.
  **Bite:** bump only the TENANT scope in `cutSharedAccess` → B2 red, B1 green.
- **B3 — `revokeLinks` on the tenant.** Removal with `?revokeLinks=true` → C's and O's tenant-scope shares resolve
  `404`; C's G-share still `200` (TENANT only). The existing
  `removingTenantMemberDeactivatesTheirTenantScopeShares` is the control without the flag. A refused removal with
  the flag (`409 last_owner`) leaves every share `200`. **Bite:** apply the flag to every scope in the cascade → the
  G-share assertion goes red.
- **B4 — the predicate as a unit.** `ReissuePolicyTest` covers each disjunct alone and the null-copy case; the class
  joins `backend/pitest.scope.json` (ADR 0045 §2) so a flipped `<` is a surviving mutant, not a green test.
- **W1** — `ShareDialog.test.ts`: `reissueRequired: true` shows the hint and hides re-activate; **control:** `false`
  on a disabled share keeps re-activate. **Bite:** read the old epoch compare instead of the field → the counter-only
  fixture (current epoch, `reissueRequired: true`) goes red.
- **W2** — `TenantMembersPanel` sends `revokeLinks=true` only when the box is checked; **control:** unchecked sends no
  parameter (asserted on the captured request).
- Existing assertions carry over per test (row 9 keeps passing unchanged).

**Not proven:** anything against an active operator (layer 1, as in `cr-reissue-share-link`); that a tenant owner
reads the hint. A dump holder still reads every tenant Memory under `K_SC` until an explicit re-key (ADR 0025's
accepted residual).

## Folded: inbox #228 — a standalone "turn off every link in this space" (third refine wave, 2026-10-01)

Written for questions 6–8's recommendations. Until the owner answers them this section has **no go**; questions
1–5 and everything above keep theirs. If the owner declines the fold, this section moves out unchanged into its own
CR, landing after this one.

### Inventory — measured on `main` @ `7a34c57`, 2026-10-01

| # | Fact | Locator |
|---|---|---|
| F1 | The only bulk switch-off in the code is the removal cascade's, filtered by owner; nothing turns off every share of a scope. | `grep -rn "enabled = false where" backend/src/main/kotlin` |
| F2 | One gate covers every scope owner: `isScopeOwner` reads the OWNER membership, and the tenant-owner check is the same call on the tenant scope id. | `grep -n "fun isScopeOwner\|fun isTenantOwner" -A1 backend/src/main/kotlin/com/veyq/app/shared/membership/MembershipStore.kt` |
| F3 | The owner-only group actions (rename, delete) sit in the group panel; the backend pattern is access check, kind check, `isScopeOwner`. | `grep -n "deleteGroup\|renameGroup" web/src/features/spaces/GroupMembersPanel.vue`; `grep -n "fun deleteGroup" -A6 backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesHandler.kt` |
| F4 | *Planned, not code:* Decision 4's predicate lets any member re-activate a share without a recovery copy whose owner is still a member — the gap question 7 names. | Decision 4 above; `cr-reissue-share-link` Decision 2 |
| F5 | A count in a response body is the `measure` visibility class. | `grep -n '"measure"' scripts/validate_field_visibility.py` |

### Decisions

8. **Schema** — in this CR's migration: `veyq_share.reissue_only BOOLEAN NOT NULL DEFAULT FALSE`.
9. **One store body for every bulk switch-off** — `SharingStore.turnOffAllInScope(scopeId): Int` sets `enabled =
   false, reissue_only = true` on every enabled share of the scope and returns the count. The group removal's
   `revokeLinks` (planned, `cr-reissue-share-link` Decision 3) and Decision 6's tenant flag call it instead of an
   update of their own.
10. **The predicate** — Decision 4 gains a first disjunct: `ReissuePolicy.required(reissueOnly, ownerIsMember,
    copyEpoch, currentEpoch, removalsAtMint, removals) = reissueOnly || <Decision 4 as written>`. Re-activation and
    `reissueRequired` follow; nothing else changes. A re-issue mints a new row, so the mark never travels.
11. **`POST /api/scopes/{scopeId}/shares/revoke`** (`revokeScopeShares`, `sharing` tag, in
    `sharing.openapi.yaml`) → `200 { revoked: <int> }` (`x-veyq-visibility: measure`). Gate: scope WRITE access,
    then `isScopeOwner`, else `403 not_scope_owner`; unknown scope → `404`. Any scope kind. Idempotent: a second
    call answers `revoked: 0`. No body.
12. **Web** — `share.ts` gains `revokeScopeShares`; `GroupMembersPanel` and `TenantMembersPanel` offer *turn off
    every link in this space* to owners, behind a confirm whose copy says the links come back only by re-issue; the
    result names the count. i18n EN + DE. `ShareDialog` needs nothing new: the row shows the hint because
    `reissueRequired` is true.

### Phases (merged into the CR's phases above)

- **Phase 0** adds: the ADR amendment names the mark and the standalone operation.
- **Phase 1** adds: `revokeScopeShares` and its response schema.
- **Phase 2** adds: Decisions 8–11.
- **Phase 3** adds: Decision 12.
- **Phase 4** adds: `uc.spaces.manage-group-members` and `uc.spaces.remove-tenant-member` — an alternative flow
  *turn off every link in this space*, with the coverage YAML; inbox #228 → done.

### Definition of done (added)

- **B5 — a standalone switch-off sticks (red-first: the operation does not exist today, so the call is refused
  before any share changes).** In `ShareSharedScopeSliceTest`:
  - **Setup:** group G, owner O, member C. C's share S1 **without** a recovery copy and C's share S2 **with** one
    at the current epoch, both enabled; nobody is removed.
  - **Action:** O `POST /api/scopes/G/shares/revoke` → `200 { revoked: 2 }`; S1 and S2 resolve `404`. O
    re-activates S1 → `409 reissue_required`; C re-activates their own S2 → `409` too.
  - **Positive control:** C's share S3 minted after the switch-off, revoked and re-activated → `204`, then `200`.
  - **Negative with its control:** C (not an owner) calls the operation → `403 not_scope_owner`, every share still
    `200`; the same call by O → `200`.
  - **Bite:** drop the `reissueOnly` disjunct → both re-activates answer `204` (red), S3 unchanged.
- **B6 — the removal paths set the mark too.** After a tenant removal with `?revokeLinks=true`, a co-member's
  null-copy tenant share → re-activate `409`. **Bite:** let Decision 6 keep an `enabled = false` update of its own →
  red. The same case for the group removal's `revokeLinks`.
- **B4** gains the `reissueOnly` disjunct as a case of its own.
- **W3** — the group panel offers the action to an owner only, sends the request on confirm and shows the count;
  **control:** a non-owner sees no action.

**Not proven (added):** anything against an active operator (layer 1); that a switch-off reaches a link already
saved on a guest's device (it stops the server serving it, nothing more).
