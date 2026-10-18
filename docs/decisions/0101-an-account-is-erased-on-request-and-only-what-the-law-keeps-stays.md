# ADR 0101 — An account is erased on request, a tenant can be deleted while active, and only what the law keeps stays

- Status: Accepted 2026-10-18 for its duty, **decided, not built**. That an account and an active tenant can be erased is
  not a choice: GDPR Art. 17(1) and 12(2) ask for it, and where a legal duty and what veyq decided or built disagree,
  the duty decides (owner rule, `CLAUDE.md`). The shape is this ADR's; the decisions marked **(Q)** carry the
  recommendation of an owner question in `cr-account-erasure` and change with the owner's answer.
- Date: 2026-10-18

Provenance: **(M)** measured in this worktree with the command or line named; **(A)** the legal audit of 2026-10-18
(research, not legal advice; the GDPR articles it cites were read in their primary text).

## Context

- **Nothing erases an account.** `AccountEmailResource.kt:34-35` says so ("no path deletes one"), inbox #316 measured it
  (`grep -rnE 'VeyqUser\.(delete|deleteById)' backend/*/src/main backend/apps/*/src/main` → nothing), and no contract
  operation deletes a user (`grep -n operationId contracts/api/account.openapi.yaml`; the one delete is a device wrap)
  (M). Twelve tables reference `veyq_user` and none with `ON DELETE`
  (`grep -rn 'REFERENCES veyq_user' backend/core/src/main/resources/db/migration`), so a bare row delete fails; a
  further set of columns carries a user id with no foreign key on purpose — editors, uploaders, bucket owners
  (`user:{id}`), the moderation tables' actors (M, the baselines' own comments, e.g. "a member who left stays named").
- **Removing a member erases little.** A tenant owner's removal (`RemovalPolicy.plan`, `RemovalPolicy.kt:50-66`) puts
  the member's personal scope and solo groups into the scope trash, hands a sole-owned shared group on, cuts the
  shared ones, switches their links off and clears the picture; the tenant profile "outlives it"
  (`TenantHandler.kt:497`) and the terms acceptance rows stay (M).
- **An active tenant cannot be deleted.** `revokeTenant` deletes a `PENDING` tenant only
  (`contracts/api/tenants.openapi.yaml:705`, `TenantHandler.kt:243-252`, `TenantStore.deleteTenant` touches invites and
  the row); suspension "preserves everything" ([ADR 0066](0066-tenant-suspension.md)) (M).
- **A tenant has one owner in practice.** An owner invite exists only while the tenant is `PENDING`, a member invite
  is `MEMBER` only (`inviteTenantMember`), and a role change is for groups only (`ScopesHandler.kt:497`), so the last
  owner of an active tenant cannot hand it on (M). `RemovalPolicy` refuses the last owner (`last_owner`).
- **The law** (A): erasure "without undue delay" (Art. 17(1)); the controller facilitates it (Art. 12(2)) and acts
  within one month (Art. 12(3)); erasure does not reach what is needed for a legal obligation or for legal claims
  (Art. 17(3)(b), (e)), and a hold is a restriction of processing (Art. 18, ADR 0093 § 4). The Server Owner is the
  controller veyq builds for; whether a tenant owner is a joint controller is open (ADR 0093 Still open 6).

## Decision

### 1. Two doors, one erasure

A member erases their own account in the settings, proving their password the way a password change does. The Server
Owner erases an account by its address on the admin channel — admin-web and `veyq-admin account erase` — for a request
that reached them another way (a mail, a letter, a person who lost their password). Both doors start the same erasure.

### 2. At the request, the account stops at once

In one transaction: every session ends (`SessionService.revokeAllForUser`), sign-in is refused, every pending
invite, pairing, recovery and address change of the account is dropped, and in each tenant the member removal runs as
it does today (§ Context: own scopes into the scope trash, shared ones cut, a sole-owned group handed on, links off).
A moderator leaves the roster as a removal does ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md):
grants deleted, a new epoch). The account address receives one mail, through the instance mail where it is set up:
that erasure was requested, when it completes and who can stop it — a stolen session that erases an account is then
seen by its owner.

### 3. Erased after the scope trash's retention, by one purge (Q)

The account row and every row keyed on it go when its own scopes go: after `veyq.scope.delete.retention` (`P30D`,
`application.properties:99`), by the purge that already erases those scopes. Until then only the Server Owner can stop
it, for a mistaken or coerced request; the person cannot sign in to do so. Thirty days stays inside Art. 12(3)'s month.

### 4. What goes

The `veyq_user` row (address, keys, wraps), master key wraps, session families and their tokens, identity pins,
recovery, device register and pairings, address confirmations and changes, the tenant profile in every tenant,
every membership, the favorites (core's and Memories'), the moderator row and its grants, and the terms acceptance
rows except as decision 6 keeps them. The browser that erased clears its own records for the address (session vault,
remembered and trusted device, trust marks); another device finds its wraps gone at its next sign-in.

### 5. What stays with the scope, naming no account (Q)

Content the person put into a scope they did not own alone — uploads, nodes, buckets, attachments — belongs to that
scope, as a guest's photos belong to the Memory ([security model](../crosscutting/security-model.md) § Deletion &
erasure). Its attribution keeps the opaque id, which the client shows as a former member
(`web/src/core/people/directory.ts:132`); the person's sealed name goes with their profile. Their links in shared
scopes are switched off, as at a removal, and the scope's members may delete or re-issue them. The confirmation tells
the person, before they erase, to delete what they want gone from shared scopes while they are still a member.

### 6. The terms acceptance history goes, unless a case needs it

Deleted with the account (ADR 0035 decision 4 names the table for this), **except** while a moderation case names the
account — a measure on it in force, or a case about its content not closed: then its rows stay under the opaque id
until that case's log retention date (31 December of the third year after it closes, ADR 0093 § 11), as the evidence of
which terms a breach was measured against (Art. 17(3)(e)). No new period: the case's.

### 7. What survives, and is said to survive

Holds keep their snapshot and its attribution to their own ends (ADR 0093 § 4; Art. 17(3)(b), (e), Art. 18); the
moderation log, the reports the person made, the measures, statements, objections and orders that name the account
stay to their case's log retention date, the reporter's sealed contact six months after the decision was told
(ADR 0093 § 11). A sign-in block on the account stays as the record it is. The erase dialog, the mail of decision 2 and
the instance privacy notice ([ADR 0035](0035-server-terms-of-service.md) decision 8) say this, and that backups keep
an erased account at most B days (decision 8).

### 8. Backups

An erasure enters the erasure ledger in the transaction that completes it, and a restore re-applies it before the
instance serves ([ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) decision 8).

### 9. Two refusals, and the last owner (Q)

- **The Server Owner's own account** is refused (`server_owner_account`): erasing it leaves nobody on the admin
  channel, and moving the server role to another account is not built (inbox #43).
- **The last owner of a tenant** with other members is refused at the self-service door until another member is an
  owner, which a tenant owner can now make one; a tenant with no other member is deleted with the account (decision
  10), and the dialog names it. **The Server Owner's door does not refuse**: an erasure cannot wait on a third person
  (Art. 12(2)). The tenant then has no owner; admin-web shows it so, and the Server Owner invites a new owner into the
  active tenant (an owner invite, as setup mints one) or deletes it.

### 10. A tenant is deleted while active

The Server Owner deletes an `ACTIVE` or `SUSPENDED` tenant in admin-web and with `veyq-admin tenant delete`. At once it
is offline exactly as a suspension takes it (ADR 0066: every request refused, ingress withdrawn, links dead); after the
same `P30D` the Server Owner can still stop (Q), one purge erases every scope through `ScopePurge.eraseScope` (its blobs
onto the reclaim list), the tenant profiles, the terms rows (their cascade), the mail settings, the invites, the
capability switches and quota, and the tenant row. Holds and the moderation records survive: their tables hang on
cases, not tenants (`grep -n REFERENCES backend/core/src/main/resources/db/migration/V202610172000__core_moderation_holds.sql`).
Members keep their accounts; an account left in no tenant and holding no server role is erased with the tenant, and
told so by mail at the deletion (Q). Deleting a tenant over its content is a termination of the service for its owners
(DSA Art. 17(1)(c)) and carries their statement of reasons (ADR 0093 § 7).

### 11. The guard

Like the scope's (`scripts/validate_scope_erasure_arity.py`): the foreign-key closure below `veyq_user` and below
`veyq_tenant`, read from the migrations, each table erased by the eraser or cascading; and every column that carries a
user id without a foreign key classified in the eraser's code as erased or kept, with the reason for keeping.

## Consequences

- New: two erasure operations (self and admin channel), a tenant deletion for active tenants, an owner's "make owner"
  for a tenant member, an owner invite into an active tenant, a pending-erasure state on the account and the tenant,
  one eraser per account and per tenant with its guard, the erasure mail, and admin-web's view of an ownerless tenant.
- The privacy notice, the Use pages (account, space owners) and the Run pages (moderation, tenants) say what goes and
  what stays.
- **Limit:** an opaque id stays in content attribution and in kept moderation records; with the account row gone it
  names someone only through those records, which the law keeps.

## Rejected alternatives

- **Erasing at once, with no window.** A coerced or mistaken request would be final, and the own scopes go through
  the scope trash's 30 days anyway.
- **Pseudonymising the account instead of deleting it.** A row kept without its address serves no purpose a
  controller could name.
- **Nulling the ids in others' content.** It would cut a hold's attribution (ADR 0093 § 8) and turn "a former member"
  into nobody, for an id that names no one once the row is gone.
- **Erasing the person's uploads in shared scopes.** They are the scope's; the other members would lose their
  memories to someone else's request, and the server cannot read what is in them.
- **Refusing the last owner at the Server Owner's door too.** The erasure would depend on another person's act.
- **Keeping every acceptance row as evidence for the case log's period.** Most erased accounts are named by no case;
  a period without a purpose is what Art. 5(1)(e) forbids.
