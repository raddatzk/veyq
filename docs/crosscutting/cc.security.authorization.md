# Concern: Authorization

- **ID:** cc.security.authorization
- **Type:** **Cross-cutting concern** — a horizontal mechanism that gates every feature; it has no standalone
  end-user goal (not a use-case) and is not a feature substrate (not an enabler). It is recorded here because the
  rules were only ever readable **as code**: before this doc, no document named the membership role vocabulary and
  the share role vocabulary together (`comm -12` of the two file lists over `docs/` → **0** files in common), so
  the model could not be reconstructed from the docs at all — the gap a 2026-07-14 doc-vs-code review found.
- **Status:** the model is settled and enforced ([ADR 0007](../decisions/0007-membership-role-grant-relation.md) ·
  [ADR 0008](../decisions/0008-centralized-http-auth.md) · [ADR 0042](../decisions/0042-http-failure-projection.md)).
  This doc ships **no new mechanism and no new guard** — it is the map. It explains what those ADRs bind and names,
  in one place, every dimension a request is gated on.

## Protects against

Two distinct classes. Conflating them is the mistake this doc exists to prevent:

- **Unauthorized action** — a caller doing what they may not do: writing to a scope they do not own, administering
  a tenant they merely belong to.
- **Unauthorized *knowledge*** — a caller learning something across the membership boundary **without acting at
  all**: that a scope id exists, who a tenant's members are. Because the server is operator-blind, existence and
  membership are themselves confidential — so an authorization answer that is *correct* can still leak (rule 2
  below, and [ADR 0042](../decisions/0042-http-failure-projection.md)).

Content **readability** is on neither list. Authorization never grants it: the server cannot read content at all,
and a management role is a hollow title without the client-side key ([security-model](security-model.md)).

## Mechanism — two layers, by design

Every request passes **layer 1**; scope-addressed requests also pass **layer 2**
([ADR 0008](../decisions/0008-centralized-http-auth.md), *Two authz layers, by design*).

**Layer 1 — the coarse HTTP gate, declared at the resource boundary.** A standard annotation on the `…Resource`:
`@PermitAll` · `@Authenticated` · `@RolesAllowed(SERVER_OWNER)`. It answers only *is there a valid session, and
which **server** role does it carry* — never anything scope-specific. It is **provably complete**:
`quarkus.security.jaxrs.deny-unannotated-endpoints=true` (`backend/src/main/resources/application.properties:15`)
denies any endpoint without an annotation, so a new endpoint **fails closed**, and `DefaultDenyTest` pins that
with a deliberately unannotated probe. This layer has never been the site of a defect.

**In front of layer 1, for the Server-Owner operations alone: the admin channel.** Reachability, not a role — an
operation marked `x-veyq-channel: admin` answers only on the backend's admin listener, which nothing but the admin
container is pointed at, and 404s on the public port; the Server-Owner role behind it is still required
([uc.admin.admin-channel](../features/admin/use-cases/uc.admin.admin-channel.md),
[ADR 0065](../decisions/0065-admin-ui-cli-separate-service.md)).

**Layer 2 — the fine, fachlich guard, inside the `…Handler`.** Everything scope-specific: membership, the scope
key, the scope role, the share role, the share password. It is **never** in the `…Resource` — resources hold zero
guard calls — and it does not throw: a refusal is a sealed-outcome variant carrying its own `code` and status
bucket, rendered by the one writer in `core/http/` ([ADR 0042](../decisions/0042-http-failure-projection.md)).

## The gate dimensions

Eight dimensions, three role vocabularies, three non-role axes. **The same string means different things on
different dimensions** — the single most common source of confusion here, and the reason this table exists:

| # | Dimension | Answers | Vocabulary | Enforced by |
|---|---|---|---|---|
| 1 | **Session** | is there a valid session? | — | annotation (layer 1) |
| 2 | **Server role** | may they administer the *server*? | `SERVER_OWNER` · `MEMBER` | annotation (layer 1) — the **only** declarative one |
| 3 | **Membership** | do they belong to this scope / tenant? | — (an *accepted* row exists, or not; a tenant = its root scope) | `ScopeAccess` |
| 4 | **Grant** | do they hold this scope's key? | — (`wrappedGrant is not null`) | `holdsGrant` |
| 5 | **Scope role** | may they *manage* this scope? | `OWNER` · `MEMBER` | `isScopeOwner` · `isTenantOwner` · `ownerCount` · `memberRole` |
| 6 | **Share role** | what may a link-holder do? | `VIEWER` · `CONTRIBUTOR` · `UPLOADER` | `canView` · `canUpload` |
| 7 | **Share password** | do they know the share's password? | — | `passwordCheck` |
| 8 | **Space state** | is the tenant suspended, is the scope being deleted? | `ACTIVE` · `SUSPENDED` · `deletedAt` | `ScopeAccess` (after #3) |

**`MEMBER` means three different things:** an ordinary server user (#2), an ordinary scope member (#5), and the
role a tenant invite confers. **`OWNER` means two** (#5, and the invite's). The server's owner value is
`SERVER_OWNER` — a **distinct string on a distinct axis**, not `OWNER`. Dimensions #6/#7 are **disjoint from every
other**: a share guest has no account, so no membership, grant or scope role can ever apply to them.

The values themselves are never copied here — read them at their definition sites:
`core/identity/ServerRoles.kt` (#2) · `core/membership/MembershipEntity.kt` (#5) ·
`core/sharing/Entities.kt` (#6). Guards #3–#5 all live in the one query-only leaf
`core/membership/MembershipStore.kt` ([ADR 0007](../decisions/0007-membership-role-grant-relation.md)); #6/#7
are private to `core/sharing/SharingHandler.kt`.

> This table lists **dimensions**, not operations — deliberately. It changes only when the security model does,
> which is exactly when someone edits this doc. A table of *operations* would churn with every endpoint; see
> *Known limits*.

## Which dimensions apply — the rule, not a table

Six rules decide it, and they compose. Applied to a new endpoint they give the answer a per-operation table would
have given — and unlike a table they cannot go stale.

1. **Every operation carries a layer-1 annotation.** Not a convention — default-deny makes it mandatory. For the
   exact set: `grep -rn '@PermitAll\|@Authenticated\|@RolesAllowed' backend/src/main/kotlin`.

2. **Scope-addressed → membership first, always.** Wherever the caller names a `scopeId` they could have guessed:

   > **The membership boundary is the first guard on every scope-addressed operation.** Capability checks
   > (grant-holder, owner) run *after* it and may answer 403 — a member legitimately knows the scope exists.
   > — [ADR 0042](../decisions/0042-http-failure-projection.md)

   The **order is the security property**. Probing existence — or scope *kind* — before the boundary lets a
   non-member distinguish "exists, forbidden" (403) from "no such scope" (404) and enumerate scope ids.
   Membership-gated absence is
   therefore **404**, uniform for strangers, bogus ids and unaccepted proposals; the residual **403** means
   precisely "member, but not a key-holder" (`not_key_holder`). The boundary is **one** call, `ScopeAccess.check`,
   which answers #8 only after #3 — suspension (403) and, for a write, deletion (409) — so a non-member never
   learns either ([uc.spaces.scope-access](../features/spaces/use-cases/uc.spaces.scope-access.md)).

3. **Role checks key on `scopeId`, never on `tenantId` alone.**

   > `tenantId` groups; belonging and every role check key on `scopeId`.
   > — [ADR 0007](../decisions/0007-membership-role-grant-relation.md)

   Every scope birth mints its creator an `OWNER` row, so a `tenantId`-only owner check silently degrades to a
   belonging check — it answers **true for every tenant member**. And a `tenantId`-only *belonging* check is wrong
   too: it counts a removed member's kept rows and a proposed member's unaccepted one. That was a real privilege escalation. Tenant
   authority keys on the tenant
   **root scope**: `isTenantOwner(u, t) = isScopeOwner(u, t)`.

4. **Role gates management; grant gates content** ([ADR 0007](../decisions/0007-membership-role-grant-relation.md)).
   An `OWNER` without a grant may administer the scope and still cannot read a byte of it. So #5 never substitutes
   for #4: the member-directory gate is `holdsGrant`, not `isScopeOwner`.

5. **Self-scoped → no layer-2 guard, by construction.** Account key-material, wraps, identity pins, profile, "my
   scopes", "my invitations": these key off the session's own principal and never off a request-supplied id, so
   there is nothing to authorize. An empty layer 2 here is **correct**, not a hole.

6. **Token-bearing public operations are the deliberate exception to rule 2.** The `@PermitAll` share-guest
   operations and the tenant invite `preview`/`register`/`accept` are reached by possessing an **unguessable
   token** — that token *is* the credential. Their callers already know the resource exists, so a **403 is correct
   there** and rule 2 must **not** be applied ([ADR 0042](../decisions/0042-http-failure-projection.md)). They are
   gated on #6 + #7 instead, and bounded by [cc.security.rate-limit](cc.security.rate-limit.md). #8 still applies
   to them — as the answer of a revoked token, since their holders are not members.

## Known limits (honest — what this doc deliberately does not do)

- **No per-operation matrix.** The authoritative operation set is `contracts/api/*.openapi.yaml` (every operation
  carries a unique `operationId`); the authoritative gate is the annotation plus the handler's guard. A
  **hand-kept** matrix drifts — the sibling concern records the precedent
  ([cc.security.rate-limit](cc.security.rate-limit.md): cr-0044 deleted a phantom row). A **generated** matrix
  would not drift and would also be worthless as a guard: derived from the code, it can never *disagree* with the
  code, so it can never fail. On the day the rule-3 privilege-escalation fix shipped, a generated matrix would
  have printed `isTenantOwner` and been green — the defect was *inside* the predicate.
- **Layer 2 is not greppable, and must not be guarded by a grep.** No resource holds a guard call; every guard is
  at least one hop down in a handler, and for a few operations (share `revoke`/`update`, the task lifecycle) it is
  fused into a store **query predicate** (`takeIf { it.ownerAccount == … }`), where no call-site scan can see it.
  Guard *order* — the actual property in rule 2 — is not greppable at all, and is pinned by a test on purpose
  ([ADR 0042](../decisions/0042-http-failure-projection.md): "*a grep is explicitly not the right guard here*").
- **The tenant-invite role constants are a second definition site.** `core/admin/tenant/Entities.kt` mirrors
  `OWNER`/`MEMBER` for the role an invite confers, and nothing holds the two copies equal:
  `scripts/validate_scope_model_vocabulary.py` pins `MembershipEntity` against the contract enum, not this copy.
- **Authorization is not in the contract, and that is a decision.**
  [ADR 0008](../decisions/0008-centralized-http-auth.md) rejected declaring authz in the OpenAPI spec ("*it would
  leak the backend role string into the published contract*"). The spec's `security:` block therefore states only
  *bearer or not* — and even that is **not authoritative**: `logout` is `security: bearerAuth` in
  `contracts/api/auth.openapi.yaml` but `@PermitAll` in code. That is deliberate (lazy authentication keeps logout
  a lenient 204 no-op for any token, [ADR 0008](../decisions/0008-centralized-http-auth.md)) and unguarded. **Read
  the annotation, not the spec.** The one per-operation marker the specs do carry, `x-veyq-channel: admin`, is
  not an exception: it names which door answers, never a role, and the backend derives its channel set from it —
  while the sweep below reads the *annotation* to hold that set to the Server-Owner gate.

## Verified by

Nothing is verified by this doc. It points at the guards that already exist:

- `DefaultDenyTest` — an unannotated probe endpoint 401s. Pins layer 1's completeness: a future endpoint added
  without an annotation fails closed.
- `OperatorBlindProbeTest` — fires every scope-, tenant-, share- and job-addressed operation as a *non-member*
  against both a real id and an invented one and asserts the two answers are identical in status and `code`,
  then as an unaccepted proposal, a member of a suspended tenant and a member of a deleting group. This is rule
  2's enforcement, and the reason it is a test rather than a static check: order is not greppable. Its
  operation **set** is derived from the contract — every operation is probed or named on a reasoned exclusion
  list — while its **assertions** stay behavioural, which is what keeps it from being the generated matrix the
  limits above reject.
- `AdminChannelPostureSweepTest` — every operation whose effective gate is `@RolesAllowed(SERVER_OWNER)` carries
  `x-veyq-channel: admin`, and every marker names a resource method that exists, so a Server-Owner operation added
  without it fails the build instead of answering on the public origin. `AdminChannelGateSliceTest` proves the
  refusal itself.
- `SessionTokenIdentityProviderTest` — the token → `SecurityIdentity` resolution (principal + server role) that
  layer 1 rests on.
- Per-feature slice tests carry the layer-2 cases (owner-only, key-holder-only, share role, share password); the
  share-password *bound* belongs to [cc.security.rate-limit](cc.security.rate-limit.md).

**Out of scope for any test:** whether the *policy* is the right one. That a tenant owner may do X at all is a
product judgement, recorded in the ADRs — not something a test asserts.

## Related

- Concept: [security-model](security-model.md) (threat model; why a role never confers readability),
  [scopes-and-tenancy](scopes-and-tenancy.md) (what a scope *is* — this doc gates it),
  [cc.security.rate-limit](cc.security.rate-limit.md) (the bound axis: how *often* a gate may be attempted)
- Decisions: [ADR 0007](../decisions/0007-membership-role-grant-relation.md) (the relation, role-vs-grant, the
  `scopeId` keying rule), [ADR 0008](../decisions/0008-centralized-http-auth.md) (the two layers, default-deny,
  authz-not-in-the-spec), [ADR 0042](../decisions/0042-http-failure-projection.md) (guard ordering + the failure
  projection), [ADR 0014](../decisions/0014-group-scopes-and-governance.md) (group governance: `OWNER`/`MEMBER`
  only), [ADR 0020](../decisions/0020-grant-distribution-invite-wrap.md) (how a grant is distributed),
  [ADR 0023](../decisions/0023-per-tenant-member-identity.md) (owner-email visibility is a role rule),
  [ADR 0037](../decisions/0037-password-protected-shares.md) (share passwords),
  [ADR 0029](../decisions/0029-public-viewer-share.md) ·
  [ADR 0030](../decisions/0030-contributor-public-share.md) ·
  [ADR 0031](../decisions/0031-uploader-public-share.md) (the three share roles)
- Code: `backend/.../shared/membership/MembershipStore.kt` (guards #3–#5),
  `backend/.../shared/identity/ServerRoles.kt` (#2), `backend/.../shared/auth/` (the authentication front door),
  `backend/.../features/sharing/SharingHandler.kt` (#6/#7)
