# UC: Erase an account

- **ID:** uc.account.erase-account
- **Primary actor:** A **member**, erasing their own account in src/web/; or the **Server Owner**, erasing one on a request
  that reached them another way, in admin-web or `veyq-admin`
- **Secondary actor:** the other members of the person's scopes and tenants (they keep what is the scope's)
- **Goal:** Have an account and everything kept about it erased, apart from what the law keeps
  ([ADR 0101](../../../decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md); GDPR
  Art. 17).
- **Preconditions:** The account exists and is not the Server Owner's. For the self-service door: the member is signed in
  and proves their password.
- **Postconditions (success):** The account can no longer sign in from the request on; after the erasure's own window
  (14 days, `veyq.account.erasure.window`) its row and every row keyed on it are gone, its own scopes are purged with
  it, and the erasure is in the erasure ledger; what a moderation case or a hold keeps stays, under an opaque id, to its own end.

## Main flow

1. **The request.** The member opens the settings' account section, chooses to erase the account, reads what goes and
   what stays (decision 7 of the ADR), deletes from shared scopes whatever they want gone first, and confirms with their
   password (a signature with the auth key over the account and a login challenge, spent by the request). Or the Server Owner enters the account's address in admin-web (or `veyq-admin account erase --address`)
   and confirms by typing it again. Where the account is the last holder of the moderation key, A8 adds one step at
   either door.
2. **The account stops.** In one transaction the server ends every session, refuses sign-in, drops pending invites,
   pairings, recoveries and address changes, runs the member removal in every tenant (own scopes into the scope trash,
   shared scopes cut, a sole-owned group handed on, links in shared scopes switched off), removes the account from the
   moderator roster if it is on it (after A8's delivery, where it is the last holder of the key), and records the erasure as pending, due the erasure's window after the request
   (14 days by default; ADR 0101 decision 3, a safeguard for a mistaken or coerced request). The due date is stored
   with the request: a window changed later moves no pending erasure.
3. **The person is told.** One mail to the account address, through the instance mail where it is set up: erasure
   requested, the date it completes, and that the Server Owner can stop it until then. The browser that erased clears
   its own records for the address.
4. **The purge.** When the window ends, the purge erases the account with its own scopes — then, though the scope trash
   alone would keep them longer: its row, keys and
   wraps, sessions, pins, recovery, device register and pairings, profiles, memberships, favorites, moderator grants and
   terms acceptance rows (unless step 5 keeps them), and writes the erasure into the erasure ledger.
5. **What stays.** Content in scopes the person did not own alone keeps its opaque attribution and shows as a former
   member's; a hold keeps its snapshot to its end; the moderation records naming the account stay to their case's log
   retention date, and with them the terms acceptance rows while a case names the account. A measure on the account
   still in force (a sign-in block, a reporter's bar) ends with the purge, so its records run to their date; a Server
   Owner's block on no case keeps the terms rows to its own.

## Alternate / exception flows

- **A1 — Wrong password** at the self-service door: refused, nothing changes.
- **A2 — The Server Owner's own account:** refused (`server_owner_account`).
- **A3 — The last owner of a tenant with other members**, at the self-service door: the dialog offers a choice per such
  tenant — **delete the tenant along** ([uc.admin.delete-tenant](../../admin/use-cases/uc.admin.delete-tenant.md), its
  window: the scope trash's retention, not the account's) **or name a new owner** from its members — and the erasure proceeds after either, in the request's one
  transaction. A request that leaves such a tenant without a choice is refused (`last_owner_choice_required`), nothing
  changed. A tenant with no other member is deleted with the account, and the dialog names it.
- **A4 — The Server Owner's door for a last owner:** not refused; the tenant is left with no owner, and admin-web shows
  it so, to invite a new owner or delete the tenant.
- **A5 — Stopped:** the Server Owner stops a pending erasure before the purge; the account can sign in again, and its
  own scopes are still in the scope trash, restorable by it as before. The shared scopes and tenants it was cut from
  need a new invite, and a tenant deleted along stays pending deletion until the Server Owner stops that too.
- **A6 — No instance mail:** the erasure runs all the same; admin-web already warns that mail is missing.
- **A7 — Restored from a backup:** the instance re-applies the erasure ledger before it serves, and the account is
  erased again, mailing nobody (ADR 0056 decision 8); a backup itself is kept at most B days.
- **A8 — The last holder of the moderation key** (the only accepted moderator holding one of its live epochs) is not
  refused: an erasure may not wait on a third person (ADR 0101 decision 9, ADR 0099 § 5). A step-down or a removal
  that would leave no holder stays refused ([uc.moderation.appoint-moderators](../../moderation/use-cases/uc.moderation.appoint-moderators.md) A4).
  - **At the self-service door** the preview names the Server Owner (their identity beside the head of their key
    chain, which the server serves to an accepted moderator) and the live epochs the person holds that the Server
    Owner misses; the dialog says the key goes to the Server Owner. Before the request the person's client anchors that
    identity (ADR 0028 Decision 2), opens its grants with the vault the settings hold, checks each against its
    published bundle and seals each epoch to the Server Owner (`moderation-grant` [epoch]); the grants travel in the
    request. In its one transaction the server stores them as a delivery, makes the Server Owner an accepted moderator
    holding them — reappointed by the delivery, with no acceptance — and then takes the person off the roster as a
    step-down does (the epoch is due for rotation). A request that does not carry every such epoch, or names one the
    person does not hold, is refused (`moderation_delivery_required`), one sealed to an identity that is no longer the
    Server Owner's too (`identity_changed`), nothing written — so a client that skips the step cannot lose the key. A
    Server Owner whose key the client cannot anchor is not sealed to; nothing is sent, and the dialog says the Server
    Owner can erase the account at their door instead.
  - **At the Server Owner's door** the person's client is not there to hand the key over: the request is refused
    (`moderation_last_holder`, nothing written) until it confirms the loss (`confirmModerationKeyLoss`), which admin-web
    sends after a warning the Server Owner accepts and `veyq-admin account erase` with `--lose-moderation-key`, its
    refusal printing the warning. The key then goes with the account, and with it what only it opens; the roster has no
    holder and a rotation is due. admin-web's moderation panel shows an instance whose key one person alone holds.

## Quality notes

- **Operator-blind** is unaffected: what goes is ciphertext and metadata; nothing is decrypted to erase it.
- **The erasure is total or pending, never partial:** the purge erases the account's rows in one transaction, and a
  guard derived from the migrations holds that every table keyed on an account is erased or cascades.

## User impact

You can leave an instance for good: your account and what is kept about you are erased, and you are told what has to
stay and why.

## Related

[ADR 0101](../../../decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md),
[ADR 0056](../../../decisions/0056-instance-durable-state-and-cold-logical-backup.md) decision 8 (the ledger),
[ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§§ 4, 11 (what moderation keeps), [uc.admin.delete-tenant](../../admin/use-cases/uc.admin.delete-tenant.md). Published:
[Erase your account](../../../../site/src/content/docs/account/erase-your-account.mdx),
[First-run setup and the admin console](../../../../site/src/content/docs/run/first-run.mdx) § Accounts.
