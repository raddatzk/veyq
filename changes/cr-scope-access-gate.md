---
id: cr-scope-access-gate
title: One scope-access gate on every scope-addressed operation
status: planned
satisfies: [uc.spaces.scope-access]
adrs: ["0042", "0066", "0007", "0021", "0014", "0032", "0036", "0029"]
invariants: [operator-blind E2EE, API-first, self-hostable by default]
---

## Goal

Today every backend handler decides for itself who may act on a space, and they disagree. A proposed member who
never accepted (`pendingConsent`) passes every membership check and can upload, trash, purge and re-activate
shares. A group's deletion grace blocks node writes only; media, buckets, favorites, shares, roster, icon-clear,
rotation and job work ignore it, and the one state has three wire codes. A suspended tenant is refused only on
its own domain: its scopes, shares and jobs stay reachable through any other tenant's domain, because the space
switcher is host-independent. A member removed from the tenant still passes the tenant-belonging check, and can
restore their own soft-deleted spaces. Any key-holder can complete or fail a job someone else holds.

After this CR, every scope-, tenant-, share- and job-addressed operation calls **one** gate first,
`ScopeAccess.check(caller, scope, READ | WRITE)`, which answers in a fixed order. `OperatorBlindProbeTest`
derives the operation set from the contract, so an operation nobody classified fails the build. The rule, and
what every caller is told, is [uc.spaces.scope-access](../docs/features/spaces/use-cases/uc.spaces.scope-access.md);
this file is only the work order.

## Context & constraints

- **The rule:** [uc.spaces.scope-access](../docs/features/spaces/use-cases/uc.spaces.scope-access.md) (gate
  order, A1–A8). **The ADRs it rests on**, all already edited to the decided state:
  - [ADR 0042](../docs/decisions/0042-http-failure-projection.md): the order, and the derived four-persona guard.
  - [ADR 0066](../docs/decisions/0066-tenant-suspension.md): the gate enforces suspension; the `Host` filter
    stays for the tenant's own domain.
  - [ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md): belonging keys on the root scope, and
    the scope and tenant rows are shared query-only entities.
  - [ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md) §6: only the lease holder acts on a
    job.
  - [ADR 0032](../docs/decisions/0032-capability-discovery-version-gating.md): re-activation is capability-gated.
  - [ADR 0036](../docs/decisions/0036-editable-share-note-recoverable-share-key.md) and
    [ADR 0029](../docs/decisions/0029-public-viewer-share.md): share curation needs a current member.
- **Neighbour use-cases, already edited:** delete-group A2–A6/E2, suspend-tenant step 3/6/A3,
  consent-group-add A3a, remove-tenant-member step 5, switch-spaces A3, share-shared-scope A2/A3, share-note,
  en.spaces.tenant-scope E1a. Do not re-edit them here unless the code disagrees; if it does, stop and ask
  which side is right.
- **Invariants at risk.**
  - *Operator-blind:* the gate reads membership rows, `deletedAt` and `status` only — no key, no name.
  - *API-first:* the contract changes first; no hand-written DTOs.
  - *Self-hostable:* nothing new to deploy.
  - **Not** changed: `TenantSuspensionFilter`'s mechanism (pre-matching, worker-pool hop; its KDoc explains
    both). Only its KDoc changes, because it no longer is the enforcement.
- **Land it alone.** The gate touches almost every handler in every backend feature. Start it only when no other
  backend-heavy CR is in flight.
  - **#156 lands first.** Its own work order says so (`cr-request-tenant-and-tenant-bound-recovery` §
    Context, *Neighbours*): it changes the signatures of the `ScopesHandler` methods this CR gates, and
    Host-resolved handlers then take a `tenantId` instead of a host. Re-run D10's locator after it.
  - Rows **#152** (the admin API surface, `TenantsResource`) and **#173** land either wholly before this CR or
    after it. If #173 already moved `TenantEntity` into `shared/tenant`, phase 2 moves only
    `ScopeEntity`.
  - This CR **takes #173's `TenantEntity` half** (phase 2), so #173 keeps only the `SessionService` interface
    and `ImageCropper`. Narrow that row when phase 2 lands.
  - **#154** adds a presign-renewal media operation. Whichever of the two lands second makes it call the gate
    and classifies it in the probe.
- **Substrate:** every locator below was run on `main` at `7c1e310` (2026-09-23, clean). Do not rebuild the
  inventory, but spot-check the row the whole CR leans on: that `isScopeMember` counts proposals
  (`sed -n 16,18p backend/src/main/kotlin/com/veyq/app/shared/membership/MembershipStore.kt` shows no
  `pendingConsent` predicate).
- **Before phase 1:** make one throwaway commit to confirm the commit path works, and run the trust-chain
  baseline (`for f in scripts/validate_*.py; do python3 "$f" >/dev/null || echo "RED $f"; done`), so that any
  later red is this CR's.

## Decisions

Each decision names the line that settles it.

1. **The gate.** `ScopeAccess` in `shared/membership` exposes `check(userId, scopeId, mode): Access`, with
   `Access` = `Ok | NotMember | Suspended | Deleting`. Order:
   1. accepted membership (`pendingConsent = false`), else `NotMember`;
   2. tenant `SUSPENDED` → `Suspended`;
   3. for `WRITE` only: an accepted row on the tenant root (`scopeId == tenantId`), else `NotMember`;
   4. for `WRITE` only: the scope's `deletedAt` set → `Deleting`.

   Status is read on every call, never cached (suspend-tenant A3: *"the gate reads the same row the flip
   wrote"*). Settled by uc.spaces.scope-access § Main flow and the code block in ADR 0042.
2. **Outcome shape: one variant per operation, no exception path.**
   - Each gated operation's sealed `…Outcome` gains `Suspended` (delegating to `Failures.tenantSuspended`) and,
     for a write, `Deleting` (delegating to `Failures.scopeDeleting`). `NotMember` maps to the operation's
     existing 404 variant.
   - Resources do not change: they already match `is HttpFailure → httpError`
     (`backend/src/main/kotlin/com/veyq/app/shared/http/HttpFailure.kt`, the KDoc on `HttpFailure`).
   - A small mapping helper is fine. A thrown access exception is not: ADR 0042 § Consequences, *"Rejected —
     full collapse to `Either`"*, keeps compile-time exhaustiveness.
3. **Belonging has one definition.** Tenant-addressed operations call the gate on the root scope.
   - Deleted outright rather than renamed: `MembershipStore.isMember(userId, tenantId)`, `ScopesStore.isMember`,
     `TenantStore.hasMembership`, the five scope-sense `isMember` store wrappers, and `isScopeMember` as an
     access check.
   - Two non-gate predicates stay, each under a name that says what it counts.
     - *Holds a row, proposal included*: the add's idempotency, consent-group-add A2 (`ScopesHandler`
       `AlreadyMember`).
     - *Target belongs to the tenant*: an accepted root row (`AddGroupMember` / `ResolveCandidate`
       `MemberNotFound`).
   - `my-tenants` keeps listing every tenant with any accepted row: a removed member's grace-window read must
     stay reachable (remove-tenant-member step 5), and the switcher finds a tenant only through that list.
4. **One code for "being deleted": `scope_deleting`.**
   - `scope_pending_deletion` (renameGroup, setScopeIcon) and `scope_already_deleted` (deleteGroup) are
     retired.
   - The web reads neither (`grep -rn 'scope_already_deleted\|scope_pending_deletion' web/src` returns empty),
     and `SpaceTrashDialog.vue:65` treats `scope_deleting` as a plain failure.
   - `clearScopeIcon` loses its grace exemption (`ScopesHandler.kt:233-234`).
5. **Restore** calls `check(WRITE)`.
   - `Deleting` is its precondition and proceeds; `Ok` → `409 not_deleted`.
   - It gains the GROUP-kind check `deleteGroup` already has (`409 not_group`).
   - A former tenant member stops at step 3 (`NotMember`). Settled by delete-group A2/A3 and scope-access A1/A2.
6. **Guest share routes** (`SharingHandler.activeShare` and every `@PermitAll` op, `getShareTerms` included)
   additionally require the share's scope to be live and its tenant not suspended; otherwise they return the
   same `share_not_found`.
   - Nothing is written onto the share, so it serves again after restore or reactivation.
   - **Invite-token routes** (`previewTenantInvite`, `acceptTenantInvite` on its MEMBER branch,
     `registerAndAcceptTenantInvite`) on a suspended tenant answer as a not-live invite.
   - Settled by scope-access A4.
7. **Share curation.**
   - `createShare`, `revokeShare`, `updateShare` and `reactivateShare` call `check(WRITE)` on the share's (or
     node's) scope. Revoke and update keep their `ownerAccount` check after the gate.
   - Reactivate adds `capabilities.isActive(capabilityFor(share.role))` → `422 capability_disabled`, the check
     `create` already runs (`SharingHandler.create`, its first line).
   - `listShares?nodeId=` calls `check(READ)`. `listShares` without `nodeId` drops rows whose scope fails
     `READ`, so a former creator's and a suspended tenant's shares leave "my shares". That list is
     account-addressed and carries sealed names of scopes the caller may no longer read.
8. **Tasks.** Claim eligibility becomes the granted scopes that pass `WRITE`. `enqueue`, `heartbeat`,
   `complete`, `fail` and `decline` call `check(WRITE)` on the job's scope.
   - `complete`: an already-`DONE` job stays a no-op success, checked first. Otherwise the caller must hold the
     lease, else `409 not_lease_holder`.
   - `fail`: must hold the lease, and the job must be `LEASED`.
   - Both web workers already claim first: `grantDeliveryWorker.ts:57,63` and `scopeRotationWorker.ts:54,61`
     (`grep -rn 'completeTask\|claimTask' web/src/features/tasks`).
   - Jobs in a suspended tenant or a deleting scope wait. Nothing sweeps them, and the purge erases them with
     the scope.
9. **`my-tenants`:** `MyTenant` gains a required `status` carrying the tenant lifecycle's value. A suspended
   tenant comes with `wrappedGrant` and `sealedIcon` both `null`. Before choosing between a cross-file `$ref`
   to `TenantStatus` and a local enum, run `grep -rln 'TenantStatus\|MyTenant' scripts/`: a guard anchors on
   whichever shape exists (§ *before reshaping a symbol* in `changes/README.md`).
10. **Host-resolved operations.**
    - `createPersonalScope` and `createGroup` call `check(WRITE)` on the root of the Host's tenant.
    - `listMyScopes`, `listMyGroupInvitations`, `getMyProfile` and `updateMyProfile` read only the caller's own
      rows on the Host's own tenant, so the `Host` filter is a correct suspension gate for exactly these.
      They go on the probe's exclusion list with that reason.
11. **Entities move to `shared`, query-only, behaviour-preserving.**
    - `ScopeEntity` moves to `shared/scope/`; `ScopeEpochEntity` stays in spaces.
    - `TenantEntity` moves to `shared/tenant/`.
    - All mutations stay in the feature stores (the `MembershipEntity` precedent, ADR 0007).
    - Two guards anchor on them. `scripts/validate_guard_gaps.py` holds `SCOPE_ENTITY` =
      `…/features/spaces/scope/Entities.kt` with anchor `class ScopeEntity : PanacheEntityBase {`: move the path
      and keep the anchor. `scripts/validate_mutation_trigger_paths.py` names `spaces.scope.ScopeEntity` in its
      docstring: fix the name.
    - `validate_tenant_ingress_triggers.py` reads `TenantStore`/`TenantHandler`, which stay, so it is
      unaffected (measured: `grep -n TENANT_DIR scripts/validate_tenant_ingress_triggers.py`).
12. **The web.**
    - The switcher renders `MyTenant.status`: a suspended tenant shows its initial, is marked offline, and is
      not enterable.
    - A `403 tenant_suspended` for a tenant other than the domain's own leaves the space for the domain
      tenant's home and refreshes the list.
    - On the suspended tenant's **own** domain, the app routes to a full-page card notice. Its exemplar is
      `web/src/views/NotSetUpView.vue` with its route and guard (`web/src/router.ts:29,62`), and like it the
      notice names no address.
    - EN+DE strings follow [neutral-first inclusive language](../docs/crosscutting/cc.inclusive-language.md).
    - No new UI component
      ([styleguide § Reuse first](../docs/crosscutting/ui-web-styleguide.md)).

## Inventory — every kind, with the command that lists its members

| Kind | Fate | Locator |
|---|---|---|
| First-guard membership calls (content, favorites, item-favorites, media, buckets, `/api/scopes/{scopeId}/…`, `enqueueTask`) | replaced by `check` | `grep -rn 'isMember(\|isScopeMember(' backend/src/main/kotlin` |
| Tenant-sense belonging calls | gate on root, or the named target predicate (D3) | `grep -rn 'isMember(.*tenantId\|hasMembership(' backend/src/main/kotlin` |
| Share curation and guest gates | D6, D7 | `grep -n 'ownerAccount ==\|activeShare(\|fun reactivate\|capabilities.isActive' backend/src/main/kotlin/com/veyq/app/features/sharing/*.kt` |
| Invite-token routes | D6 | `grep -n '@PermitAll\|isLive' backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantsResource.kt backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantAcceptHandler.kt` |
| Job gates | D8 | `grep -n 'grantedScopeIds(\|leaseHolder = ?' backend/src/main/kotlin/com/veyq/app/features/tasks/TasksStore.kt` |
| Host-resolved operations | D10 | `grep -rn 'activeHost()\|tenantFor(\|find("domain"\|RequestTenant' backend/src/main/kotlin/com/veyq/app/features` |
| Deletion checks and codes (retired or unified) | D4 | `grep -rn 'scope_pending_deletion\|scope_already_deleted\|isPendingDeletion\|PendingDeletion\|AlreadyDeleted\|deletedAt(scopeId)' backend/src contracts web/src` |
| Entity users (moved imports) | D11 | Two angles; the difference is same-package users: `grep -rl 'import com.veyq.app.features.spaces.scope.ScopeEntity\|import com.veyq.app.features.admin.tenant.TenantEntity' backend/src` against `grep -rlw 'ScopeEntity\|TenantEntity' backend/src` |
| Tests asserting the retired semantics | updated to the new answer | `grep -rn 'not_key_holder\|scope_pending_deletion\|scope_already_deleted\|hasMembership\|pendingConsent' backend/src/test` (`MemberRemoveSliceTest`'s private `hasMembership` is **scope**-sense; rename it) |
| Scheduled jobs acting on scopes | **spared**: no caller, no gate | `grep -rn '@Scheduled' backend/src/main/kotlin` |
| Server-Owner tenant routes | **spared**: `@RolesAllowed(SERVER_OWNER)` + lifecycle guards; on the probe's exclusion list | `grep -n 'RolesAllowed' backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantsResource.kt` |
| Account-wide operations (`rotateAccountKeys`, `clearDeclinesForAcceptedKey`) | **spared**: they address no scope; rotation jobs they originate simply wait where claims are refused | `grep -rn 'originateScopeRotationExcluding\|clearDeclinesForTarget' backend/src/main/kotlin` |
| Web: tenant list and suspension handling | D12 | `grep -rn 'listMyTenants\|tenantUnavailable\|isNoTenant\|tenant_suspended' web/src` |

## Phases

- [ ] **1 contracts.**
  - `MyTenant.status` (D9), plus the `wrappedGrant`/`sealedIcon` descriptions: both are null while suspended.
  - Every gated operation declares `403 tenant_suspended`; every write declares `409 scope_deleting`. Use a
    shared response in `_common` if one fits; if a guard objects, stop and ask rather than inline 60 copies.
  - Retire `scope_pending_deletion` and `scope_already_deleted`.
  - `completeTask`/`failTask` declare `409 not_lease_holder`. Delete *"a lease is not required (a cheap
    idempotent job needs none)"*.
  - Correct `failTask`'s *"`attempts` is incremented"*: the claim increments it (`TasksStore.fail` has the
    comment).
  - `reactivateShare` declares `422 capability_disabled`.
  - `contracts/tasks/tags/v1.json` `"lease": false` and `contracts/platform/capabilities.json` (reactivation
    is gated too).
  - Description sites that these decisions falsify. **Edited**, not appended to:
    `grep -rn 'request boundary\|lease is not required\|lease-less\|consent is still pending\|holds no grant for it' contracts/`,
    plus phase 6's *suspension* and *lease* keys run over `contracts/`.
  - Done when: `npm run generate:api` and `npm run check` are green in `web/` and `admin-web/`, and the
    trust-chain glob is green. The backend does **not** compile yet: `MyTenant` gained a required property on
    an existing tag, so this red is expected (`changes/README.md` § Phases only above real size).
- [ ] **2 backend: entities into `shared`** (D11). Behaviour-preserving, its own commit.
  - Move the entities, retarget the imports and both guard anchors.
  - Narrow inbox row #173 to what is left of it.
  - Done when: `./gradlew check` is green and `python3 scripts/validate_guard_gaps.py` exits 0.
- [ ] **3 backend: the guard, red first.** The defects are live, so the order is guard → red → fix → green
  (`changes/README.md` § *Where the defect is still live*).
  - `OperatorBlindProbeTest` takes the operation set by reflection over the generated
    `com.veyq.app.generated.api` interfaces: the method name is the operationId, and the class and method
    `@Path` plus the verb annotation give the route.
  - It fails when an operation is neither in its probe map nor in its reasoned exclusion map, or is in both.
  - The exclusion map holds these, each with its reason:
    - public, token-gated operations;
    - Server-Owner routes;
    - account-wide operations;
    - D10's self-scoped Host-resolved operations;
    - operations whose refusal is a **filter** rather than an answer: `claimTask`, `listMyTenants`,
      `listShares` without `nodeId`. Each of these gets its own dedicated test.
  - Each probed operation runs against four personas:
    1. **Stranger:** real id vs invented id, identical `(status, code)`, and the status is the membership
       404.
    2. **Unaccepted proposal:** the same answer as the stranger.
    3. **Accepted member of a SUSPENDED tenant:** `403 tenant_suspended`, **sent on a Host no tenant claims**,
       so a green cannot come from `TenantSuspensionFilter`.
    4. **Accepted member of a deleting GROUP:** a write gets `409 scope_deleting`; a read is not refused by
       the gate.
  - Controls. Every negative owes a positive control on the same predicate, so each persona has one: the
    same call by an accepted member of a live group in an ACTIVE tenant is not refused by the gate. Anchor on
    the specific observable (`status` + `code`), never on "not 2xx".
  - READ/WRITE is derived from the verb, `GET` = READ. A named exception map covers the rest: POST-reads
    (`resolveGroupMember`, `presignBlobDownloads`) and `restoreGroup`, whose `Deleting` is its precondition.
  - Fixtures: a probe the gate lets through may mutate, so each probe runs on its own fixture. `restoreGroup`
    is the obvious case.
  - Dedicated tests:
    - claim does not hand out a job in a suspended tenant or a deleting scope (control: the same job in a live
      scope is handed out);
    - `my-tenants` shows the suspended status with neither grant nor icon;
    - guest share and invite routes answer as a revoked link or a not-live invite;
    - `listShares` without `nodeId` drops unreadable rows;
    - `complete`/`fail` by a non-holder → `409 not_lease_holder`, and a done job stays a no-op;
    - `reactivateShare` with the capability off → `422`;
    - a former tenant member reads their own grace space but cannot restore it.
  - Done when: run against the **untouched** handlers, the suite is red, and the retrospective names the ops
    it reddened per persona. An empty red list means the probe is broken, not that the code is right.
- [ ] **4 backend: the gate and every site** (D1–D10). Wire `ScopeAccess` into every kind in the inventory,
  plus the side items:
  - restore's kind check;
  - reactivate's capability check;
  - the lease holder on complete and fail;
  - deleting `NodesStore.isPendingDeletion` and every predicate D3 retires.

  Rewrite the KDoc and comments that claim the `Host` filter is the enforcement: **narrowed** to "the tenant's
  own domain, sign-in included". Sites: `grep -rn 'request-boundary gate\|request boundary\|TenantSuspensionFilter\|refuses the request before matching' backend/src`.
  Among them are `TenantSuspensionFilter`'s own KDoc (its mechanism paragraphs stay), `Failures.kt`
  (`tenantSuspended`, `notKeyHolder`, `scopeNotFound`), `TenantHandler`, the admin `Entities.kt`, and
  `MediaPresignDomainSliceTest.kt:105-107`.
  - Bite tests. Snapshot and restore every file; never `git checkout`:
    - **B1:** delete one operation's entry from the probe map → red, naming that operationId as unclassified.
    - **B2:** drop `pendingConsent = false` from the gate → the proposal persona goes red across the probe
      map, while its control stays green.
    - **B3:** drop the suspension branch from the gate → the suspended persona goes red although
      `TenantSuspensionFilter` is untouched. This is the bite that proves the Host is not what was green.
    - **B4:** switch one write handler (`MediaHandler.start`) to `check(READ)` → red, naming
      `startBlobUploads`.
    - **B5:** move `findNode` ahead of the gate in `MediaHandler.listMedia` → the stranger persona goes red:
      the real id and the invented id answer differently.
    - **B6:** drop the lease-holder predicate from `TasksStore.complete` → its dedicated test goes red.
  - Done when: `./gradlew check` is green, the phase-3 suite is green, and each bite went red and then green
    again, with its output quoted in the retrospective.
- [ ] **5 web** (D12).
  - **Measure first:** load the app on a suspended tenant's own domain and confirm the shell arrives while
    `/api` answers `403 tenant_suspended`. `cr-app-shell-served-by-backend` expects exactly that and marks it
    *not measured*. If the shell itself is refused, the card cannot render: stop and ask.
  - The switcher and home render `status`.
  - `403 tenant_suspended` handling:
    - for another tenant: leave the space and refresh the list;
    - on the own domain: route to the card notice.
  - The `tenantUnavailable` awaiting-access marker stays distinct from "offline": they are two different
    reasons (en.spaces.tenant-scope E1 vs E1a).
  - Done when: `npm run check` is green in `web/`, with unit tests for both 403 branches and for the switcher
    row. Each 403 test asserts by `code`, with a `404` control, because the remedy differs.
- [ ] **6 prose echo sweep.** Every remaining site that calls the `Host` filter the lockout, or deletion "nodes
  only", or completion lease-free. **Narrow** it where the filter's own-domain role stays true; **delete** it
  where it restates uc.spaces.scope-access. One key per retired claim; the four sets do not overlap:
  - *suspension:* `grep -rni 'request-boundary gate\|request boundary\|suspended.*at the boun' docs/ contracts/ web/src admin-web/src backend/src charts/ infra/ wiki/`
  - *lease:* `grep -rni 'no lease\|lease-free\|lease is not required\|"lease": false\|lease-less' docs/ contracts/`
  - *hand-kept probe rows:* `grep -rn 'add a row' docs/ backend/src/test`
  - *grace covers some writes:* `grep -rni 'server-rejected during the grace\|read-only during grace' web/src backend/src`

  Most hits belong to phases 1 and 4. Measured at refine, the ones left for this phase are
  `docs/crosscutting/developer-styleguide.md` (the "add a row" line), `admin-web/src/features/tenants/tenants.ts:47`,
  `charts/veyq/values.yaml:127`, `infra/README.md:303`, `web/src/features/memory/components/MemoryBrowse.vue:30`
  and `web/src/views/MemoryHomeView.vue:37` — a floor, not the set. Re-run the four keys here: phases 1–5 wrote
  prose too. Done when: every hit says the narrowed thing, and the trust-chain glob is green.
- [ ] **7 coverage.**
  - Replace both `gaps` in `docs/coverage/features/spaces/use-cases/uc.spaces.scope-access.yaml` with refs, and
    drop `status: planned`.
  - Extend `uc.spaces.delete-group.yaml`'s `grace-read-only-viewable` text beyond node writes, pointing at
    scope-access.
  - Done when: `python3 scripts/validate_coverage.py` is green.

## Definition of done

- `./gradlew check` is green in `backend/`, and `npm run check` is green in `web/` and `admin-web/`.
- The trust-chain glob is green against the phase-0 baseline.
- **The guard is permanent**: the derived four-persona `OperatorBlindProbeTest`. An operation added later
  without the gate, or without a classification, is red the day it lands. It replaces the hand-kept 39-row list
  and its two misses (`restoreNode`, `listTrashedNodes`).
- `uc.spaces.scope-access` is covered on both surfaces.
- Inbox **#157** is deleted, and **#173** is narrowed. Whatever this CR taught moves to where it binds, then
  this file is deleted.

## Retrospective

To be filled at implement time. It must list the phase-3 red set per persona and every bite's red → green.
