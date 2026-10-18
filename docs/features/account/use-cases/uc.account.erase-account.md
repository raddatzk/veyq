# UC: Erase an account

- **ID:** uc.account.erase-account
- **Primary actor:** A **member**, erasing their own account in web/; or the **Server Owner**, erasing one on a request
  that reached them another way, in admin-web or `veyq-admin`
- **Secondary actor:** the other members of the person's scopes and tenants (they keep what is the scope's)
- **Goal:** Have an account and everything kept about it erased, apart from what the law keeps
  ([ADR 0101](../../../decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md); GDPR
  Art. 17).
- **Preconditions:** The account exists and is not the Server Owner's. For the self-service door: the member is signed in
  and proves their password.
- **Postconditions (success):** The account can no longer sign in from the request on; after the scope trash's
  retention its row and every row keyed on it are gone, its own scopes are purged, and the erasure is in the erasure
  ledger; what a moderation case or a hold keeps stays, under an opaque id, to its own end.

## Main flow

1. **The request.** The member opens the settings' account section, chooses to erase the account, reads what goes and
   what stays (decision 7 of the ADR), deletes from shared scopes whatever they want gone first, and confirms with their
   password. Or the Server Owner enters the account's address in admin-web (or `veyq-admin account erase --address`)
   and confirms by typing it again.
2. **The account stops.** In one transaction the server ends every session, refuses sign-in, drops pending invites,
   pairings, recoveries and address changes, runs the member removal in every tenant (own scopes into the scope trash,
   shared scopes cut, a sole-owned group handed on, links in shared scopes switched off), removes the account from the
   moderator roster if it is on it, and records the erasure as pending until the scope trash's retention ends.
3. **The person is told.** One mail to the account address, through the instance mail where it is set up: erasure
   requested, the date it completes, and that the Server Owner can stop it until then. The browser that erased clears
   its own records for the address.
4. **The purge.** When the retention ends, the purge that erases the own scopes erases the account: its row, keys and
   wraps, sessions, pins, recovery, device register and pairings, profiles, memberships, favorites, moderator grants and
   terms acceptance rows (unless step 5 keeps them), and writes the erasure into the erasure ledger.
5. **What stays.** Content in scopes the person did not own alone keeps its opaque attribution and shows as a former
   member's; a hold keeps its snapshot to its end; the moderation records naming the account stay to their case's log
   retention date, and with them the terms acceptance rows while a case names the account.

## Alternate / exception flows

- **A1 — Wrong password** at the self-service door: refused, nothing changes.
- **A2 — The Server Owner's own account:** refused (`server_owner_account`).
- **A3 — The last owner of a tenant with other members**, at the self-service door: the dialog offers a choice per such
  tenant — **delete the tenant along** ([uc.admin.delete-tenant](../../admin/use-cases/uc.admin.delete-tenant.md), its
  window) **or name a new owner** from its members — and the erasure proceeds after either, in the request's one
  transaction. A request that leaves such a tenant without a choice is refused (`last_owner_choice_required`), nothing
  changed. A tenant with no other member is deleted with the account, and the dialog names it.
- **A4 — The Server Owner's door for a last owner:** not refused; the tenant is left with no owner, and admin-web shows
  it so, to invite a new owner or delete the tenant.
- **A5 — Stopped:** the Server Owner stops a pending erasure before the purge; the account can sign in again, and its
  own scopes are still in the scope trash, restorable by it as before. The shared scopes and tenants it was cut from
  need a new invite, and a tenant deleted along stays pending deletion until the Server Owner stops that too.
- **A6 — No instance mail:** the erasure runs all the same; admin-web already warns that mail is missing.
- **A7 — Restored from a backup:** the instance re-applies the ledger before it serves, and the account is erased again.

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
§§ 4, 11 (what moderation keeps), [uc.admin.delete-tenant](../../admin/use-cases/uc.admin.delete-tenant.md).
