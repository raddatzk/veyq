# ADR 0101 — An account is erased on request, a tenant can be deleted while active, and only what the law keeps stays

- Status: Accepted
- Built: partly (not built: decision 9's hand-over of the moderation key (inbox #812))
- Date: 2026-10-18
- Model: [cc.privacy.retention](../crosscutting/cc.privacy.retention.md)

That an account and an active tenant can be erased is not a choice: GDPR Art. 17(1) and 12(2) ask for it, and where a
legal duty and what veyq decided or built disagree, the duty decides (`CLAUDE.md`). The shape is this ADR's; the legal
research behind it is the [legal register](../crosscutting/legal-register.md) L10 — research, not legal advice.

## Context

- **Nothing erased an account**: no code path and no contract operation deleted a user. Twelve tables reference the
  user row and none with `ON DELETE`, so a bare row delete fails; a further set of columns carries a user id with no
  foreign key on purpose — editors, uploaders, bucket owners (`user:{id}`), the moderation tables' actors ("a member
  who left stays named").
- **Removing a member erases little.** A tenant owner's removal puts the member's personal scope and solo groups into
  the scope trash, hands a sole-owned shared group on, cuts the shared ones, switches their links off and clears the
  picture; the tenant profile outlives it and the terms acceptance rows stay.
- **An active tenant could not be deleted.** Revoking a tenant deleted a `PENDING` one only, its invites and its
  row; suspension "preserves everything" ([ADR 0066](0066-tenant-suspension.md)).
- **A tenant had one owner in practice.** The model allows several owners at every level ([ADR
  0007](0007-membership-role-grant-relation.md)), but an owner invite existed only while the tenant was `PENDING`, a
  member invite is `MEMBER` only, and a role change was for groups only, so the last owner of an active tenant could not
  hand it on, and a removal refuses the last owner. Decision 9 adds the tenant's "make owner" and the Server Owner's
  owner invite to a member.
- **The law** ([legal register](../crosscutting/legal-register.md) L10): erasure "without undue delay" (Art. 17(1)); the
  controller facilitates it (Art. 12(2)) and acts within one month (Art. 12(3)); erasure does not reach what is needed
  for a legal obligation or for legal claims (Art. 17(3)(b), (e)), and a hold is a restriction of processing (Art. 18,
  ADR 0093 § 4). The Server Owner is the controller veyq builds for; whether a tenant owner is a joint controller is
  open (ADR 0093 Still open 6).

## Decision

### 1. Two doors, one erasure

A member erases their own account in the settings, proving their password the way a password change does: a signature
with the account's auth key over the account and a login challenge minted for its address, spent by the request — the
password, not the Secret Key, since erasing mints no credential. The Server Owner erases an account by its address on
the admin channel — admin-web and `veyq-admin account erase` — for a request that reached them another way (a mail, a
letter, a person who lost their password). Both doors start the same erasure.

### 2. At the request, the account stops at once

In one transaction: every session ends, sign-in is refused, every pending invite, pairing, recovery and address change
of the account is dropped, and in each tenant the member removal runs as it does today (§ Context: own scopes into the
scope trash, shared ones cut, a sole-owned group handed on, links off). A moderator leaves the roster as a removal does
([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md): grants deleted, a new
epoch). The account address receives one mail, through the instance mail where it is set up: that erasure was requested,
when it completes and who can stop it — a stolen session that erases an account is then seen by its owner.

### 3. Erased after a window of its own, by one purge

The account row and every row keyed on it go after a window of **14 days** from the request, by the purge that
erases its own scopes with it — a window of the erasure's own, no longer the scope trash's 30 days. Until then only
the Server Owner can stop it, for a mistaken or coerced request; the person cannot sign in to do so. The request is
confirmed at once, with its date, which answers Art. 12(3)'s month; the erasure itself owes Art. 17(1)'s "without
undue delay", and a window for a mistaken or coerced request is bounded short for that reason. The 30 days were
rejected: they are not inside every calendar month, and longer than a safeguard needs.

### 4. What goes

The `veyq_user` row (address, keys, wraps) and the address wherever it was copied (the invites sent to it, a login
challenge, a tenant's record of whom its owner invite named — which follows an address change, so it never keeps an
address the account gave up), master key wraps, session families and their tokens, identity pins,
recovery, device register and pairings, address confirmations and changes, the tenant profile in every tenant,
every membership, the favorites (core's and Memories'), the moderator row and its grants, and the terms acceptance
rows except as decision 6 keeps them. The browser that erased clears its own records for the address (session vault,
remembered and trusted device, trust marks); another device finds its wraps gone at its next sign-in.

### 5. What stays with the scope, naming no account

Content the person put into a scope they did not own alone — uploads, nodes, buckets, attachments — belongs to that
scope, as a guest's photos belong to the Memory ([security model](../crosscutting/security-model.md) § Deletion &
erasure). Its attribution keeps the opaque id, which the client shows as a former member; the person's sealed name goes
with their profile. Their links in shared scopes are switched off, as at a removal, and the scope's members may delete
or re-issue them. The confirmation tells the person, before they erase, to delete what they want gone from shared scopes
while they are still a member.

### 6. The terms acceptance history goes, unless a case needs it

Deleted with the account (ADR 0035 decision 4 names the table for this), **except** while a moderation record still
kept names the account — a measure on it in force, a case about its content open or inside its log retention (31
December of the third year after it closes, ADR 0093 § 11), or a Server Owner's step on no case (ADR 0093 § 7) inside
its own (31 December of the third year after its lift): then its rows stay under the opaque id until that record's
date, as the evidence of which terms a breach was measured against (Art. 17(3)(e)). No new period. The erasure asks the
question its later sweep of kept rows asks, on the safe side: an account erased the day after its case closed is
named by that case's records as long as one erased the day before, and a dispute over a kept record needs the terms it
was measured against. A step on a tenant (taken offline or deleted over content) names the tenant's owners at the time
of the step — the recipients of its statement — and is then a measure on each of them, again on the safe side: it
targets the tenant id and often answers no case, so without this a tenant deleted over its content would lose its
owners' rows at its purge while the step's record stays to its date. A tenant's erasure (decision 10) keeps them by the
same rule, asked when it starts: the evidence is the account's, and erasing the space it was given in does not settle
the case.

### 7. What survives, and is said to survive

Holds keep their snapshot and its attribution to their own ends (ADR 0093 § 4; Art. 17(3)(b), (e), Art. 18); the
moderation log, the reports the person made, the measures, statements, objections and orders that name the account
stay to their case's log retention date, the reporter's sealed contact six months after the decision was told
(ADR 0093 § 11). A sign-in block on the account stays as the record it is, and the purge ends it — lifted by no one,
so it does not stand forever on nothing and hold its case's records with it (GDPR Art. 5(1)(e)); a tenant's purge
ends the records over content on the tenant alike (decision 10), and every other eraser of what a measure stands on — the
link's delete, a node's or a medium's purge, a guest identity's drop, the purge of the scopes the account owned —
ends the measures on it the same way (ADR 0093 § 6, `MeasureTargets`). The erase dialog, the mail of decision 2 and
the instance privacy notice ([ADR 0035](0035-server-terms-of-service.md) decision 8) say this, and that backups keep
an erased account at most B days (decision 8).

### 8. Backups

An erasure enters the erasure ledger in the transaction that completes it, and a restore re-applies it before the
instance serves ([ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) decision 8).

### 9. A refusal, and the last owner's choice

- **The Server Owner's own account** is refused: erasing it leaves nobody on the admin
  channel, and moving the server role to another account is not built (inbox #43).
- **The last owner of a tenant** with other members chooses at the self-service door: **delete the tenant
  along** (decision 10, its window) **or name a new owner** from its members first; the erasure proceeds after either. A
  tenant with no other member is deleted with the account (decision 10), and the dialog names it. **The Server Owner's door does not refuse**: an erasure cannot wait on a third person
  (Art. 12(2)). The tenant then has no owner; admin-web shows it so, and the Server Owner invites a new owner into the
  active tenant (an owner invite, as setup mints one) or deletes it. The owner invite goes to **one of the tenant's
  members**: the operator can admit nobody into a tenant's key ([ADR 0020](0020-grant-distribution-invite-wrap.md) move
  3), so an owner it brought in from outside would wait forever for a key no member could vouch for; the member accepts
  it and becomes an owner, nothing is born and no key moves.
- **The last holder of the moderation key** is not refused: an erasure cannot wait on a third person (Art. 12(2)). At
  the self-service door the erasure takes one step first — the person's own client hands the key to another moderator
  or to the Server Owner ([ADR 0099](0099-moderators-share-one-instance-moderation-key-and-review-without-a-vault.md)
  § 5), so every report and hold sealed to it, the very records decision 7 keeps, stays readable. At the Server
  Owner's door, where the person's client is not there to hand it over, the erasure proceeds after a warning that what
  only this key opens becomes unreadable; admin-web shows an instance whose key one person alone holds. Refusing until
  another moderator held the key was rejected: it made the erasure wait on someone else.

### 10. A tenant is deleted while active

The Server Owner deletes an `ACTIVE` or `SUSPENDED` tenant in admin-web and with `veyq-admin tenant delete`. At once it
is offline exactly as a suspension takes it (ADR 0066: every request refused, ingress withdrawn, links dead); after the
same 30 days, which the Server Owner can still stop, one purge erases every scope (its blobs onto the reclaim list), the
tenant profiles, the terms rows (except as decision 6 keeps them), the mail settings, the invites, the capability
switches and quota, and the tenant row. Holds and the moderation records survive: their tables hang on cases, not
tenants. Members keep their accounts, also one the deletion leaves in no tenant: nothing erases an account its person
did not ask to erase; they are told by mail and may erase it themselves. Deleting a tenant over its content is a
termination of the service for its owners (DSA Art. 17(1)(c)) and carries their statement of reasons (ADR 0093 § 7): a
step of its own, as a suspension's is, which a stop lifts and the purge ends.

### 11. The guard

Like the scope's erasure guard: the foreign-key closure below `veyq_user` and below
`veyq_tenant`, read from the migrations, each table erased by the eraser or cascading; and every column that carries a
user id without a foreign key classified in the eraser's code as erased or kept, with the reason for keeping.

## Consequences

- The privacy notice, the Use pages (account, space owners) and the Run pages (moderation, tenants) say what goes and
  what stays.
- **Limit:** an opaque id stays in content attribution and in kept moderation records; with the account row gone it
  names someone only through those records, which the law keeps.
- **A stop restores the account, not its memberships.** The member removal runs at the request (decision 2), so a
  stopped erasure gives back an account that can sign in, keeps its own scopes in the scope trash until their retention
  ends, and needs a new invite to the shared scopes and tenants it was cut from; a tenant deleted along is stopped on its
  own.
- **The erasure ledger** (decision 8): the account's and the tenant's erasure write their line before the row goes,
  and a start after a restore erases them again, mailing nobody
  ([cc.privacy.retention](../crosscutting/cc.privacy.retention.md) § Backups and the erasure ledger). The statement of
  reasons a deletion over content carries (decision 10) is the Server Owner's of ADR 0093 § 7.

## Rejected alternatives

- **Erasing at once, with no window.** A coerced or mistaken request would be final.
- **Pseudonymising the account instead of deleting it.** A row kept without its address serves no purpose a
  controller could name.
- **Nulling the ids in others' content.** It would cut a hold's attribution (ADR 0093 § 8) and turn "a former member"
  into nobody, for an id that names no one once the row is gone.
- **Erasing the person's uploads in shared scopes.** They are the scope's; the other members would lose their
  memories to someone else's request, and the server cannot read what is in them.
- **Refusing the last owner at the Server Owner's door too.** The erasure would depend on another person's act.
- **Keeping every acceptance row as evidence for the case log's period.** Most erased accounts are named by no case;
  a period without a purpose is what Art. 5(1)(e) forbids.
