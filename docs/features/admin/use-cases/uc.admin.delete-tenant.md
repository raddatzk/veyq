# UC: Delete a tenant

- **ID:** uc.admin.delete-tenant
- **Primary actor:** **Server Owner**, in admin-web or `veyq-admin tenant delete`
- **Secondary actor:** the tenant's members (they lose the tenant, keep their accounts); a last owner erasing their
  account ([uc.account.erase-account](../../account/use-cases/uc.account.erase-account.md) A3)
- **Goal:** Remove an `ACTIVE` or `SUSPENDED` tenant and everything in it
  ([ADR 0101](../../../decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md)
  decision 10).
- **Preconditions:** The actor is the Server Owner; the tenant is `ACTIVE` or `SUSPENDED` (a `PENDING` one is revoked
  as today, [uc.admin.create-tenant](uc.admin.create-tenant.md)).
- **Postconditions (success):** The tenant is offline at once; after the scope trash's retention every scope, profile,
  terms row, mail setting, invite, switch and the tenant row are erased, its blobs are on the reclaim list, the
  erasure is in the erasure ledger, and holds and moderation records remain.

## Main flow

1. The Server Owner deletes the tenant and confirms by typing its domain.
2. The server takes it offline as a suspension does — every request refused, ingress withdrawn, links dead — and marks
   it pending deletion until the retention ends.
3. Each member whose account would be left in no tenant, and holds no server role, is mailed that their account goes
   with the tenant.
4. Where the deletion is over the tenant's content, the Server Owner enters the statement of reasons with it, and the
   tenant's owners receive it by mail (ADR 0093 § 7).
5. When the retention ends, one purge erases every scope of the tenant through the scope erasure, then the tenant's
   remaining rows and the tenant, erases the accounts step 3 named, and writes the erasures into the ledger.

## Alternate / exception flows

- **A1 — Stopped:** before the purge the Server Owner stops the deletion; the tenant returns `SUSPENDED`, as it was
  taken offline, and is reactivated as a suspended one is.
- **A2 — A tenant with no owner** (an owner's account erased at the Server Owner's door): admin-web lists it as
  ownerless with the two ways on, an owner invite or this deletion.
- **A3 — Restored from a backup:** the ledger re-applies the deletion before the instance serves.

## Quality notes

- Nothing is decrypted to delete; the bytes go through the store reclaim, the one deleter of objects (ADR 0062).
- Holds and the moderation log hang on cases, not tenants, so the deletion cannot end them early.

## User impact

A Server Owner can close a space for good, not only switch it off, and what was in it is erased.

## Related

[ADR 0101](../../../decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md),
[ADR 0066](../../../decisions/0066-tenant-suspension.md), [uc.admin.suspend-tenant](uc.admin.suspend-tenant.md),
[uc.account.erase-account](../../account/use-cases/uc.account.erase-account.md).
