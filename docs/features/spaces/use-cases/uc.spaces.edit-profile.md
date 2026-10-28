# UC: Edit your profile in a space

- **ID:** uc.spaces.edit-profile
- **Primary actor:** A member of a tenant space, editing their own identity there.
- **Goal:** Change the handle other members find you by, and the real name they see, in one tenant space,
  without the operator ever reading the name.
- **Preconditions:** Signed in and unlocked, with a tenant space active (the *Your profile* rubric of the settings
  hub is offered only there). Setting or removing the real name additionally needs the member's tenant grant
  (`K_tenant`); the username does not.
- **Postconditions (success):** The member's per-tenant profile row carries the new `username` (plaintext,
  unique in this tenant) and a real-name blob sealed under a field key derived from `K_tenant` at the address (this tenant's scope, the
  member's user id). Every member of the tenant sees the change in the directory; nothing changes in any other
  tenant.

## Main flow

1. The member opens *Settings → Your profile* while a tenant space is active.
2. The client loads the member's profile in that tenant: the username, the sealed-name blob and the account-global
   consent preference.
3. The client opens `K_tenant` from the member's own tenant grant and unseals the name on the device, at the
   address (tenant scope, own user id), under whichever held tenant epoch it was saved under.
4. The member edits the username, the first name and/or the last name.
5. On save, the client seals the first and last name under a field key derived from `K_tenant` at that address. The name may be empty,
   and an empty name is sealed like any other. The client sends the username (trimmed, lower-cased), the sealed
   blob and the consent preference.
6. The server re-checks that the username is free in this tenant, stores the handle and the blob verbatim, and
   returns the profile. The panel confirms the save.

## Alternate / exception flows

- **E1 — username taken in this tenant:** `409 username_taken`. The panel says the name is taken and nothing is
  saved: not the handle, the name or the preference. The same handle may be free in another tenant.
- **E2 — the tenant key does not open here** (a member *waiting for the current key*, whose grant is behind or
  refused): there is no `K_tenant` on the device, so the name fields are disabled and a hint says why. The username
  alone can still be saved. Once the current grant lands, the name can be set here ([en.spaces.member-identity](../enablers/en.spaces.member-identity.md) E2).
- **A1 — remove your real name:** empty both name fields and save. An empty name is sealed, and the directory
  shows only `@username` from then on.
- **A2 — the tenant rotated since the name was saved** (a member secured their account, or one was removed): the name
  still opens under the older epoch, in the directory of every member and in this form, and the member is not asked to
  finish a profile they finished. On entering the tenant the member's own client seals it again under the current epoch
  ([ADR 0023](../../../decisions/0023-per-tenant-member-identity.md) § The epoch of a sealed name).
- **E3 — the profile does not load:** the panel shows a load error instead of the form. A refused session ends
  the way every refused session ends ([uc.account.sign-out](../../account/use-cases/uc.account.sign-out.md) E1).

## Quality notes

- **Operator-blind where it can be.** The username is plaintext by design: the server enforces its uniqueness and
  serves it in the directory ([ADR 0023](../../../decisions/0023-per-tenant-member-identity.md)). The real name
  is ciphertext the server stores verbatim and never opens.
- **A name is bound to its row.** It is sealed at (tenant scope, user id)
  ([ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md)), so a server that serves one member's blob on another
  member's row makes it fail to open. It cannot pass it off as that member's name.
- **Per tenant, by construction.** A handle or name changed here changes nothing in another tenant. There is no
  global handle to correlate across tenants.
- **The consent checkbox** on the same panel is [uc.spaces.consent-group-add](uc.spaces.consent-group-add.md)
  step 1. It is account-global, not per-tenant.

## User impact

You choose how the members of each shared space find and recognise you, by a username and an optional real name that
they can read and the server cannot.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- Enabler: [en.spaces.member-identity](../enablers/en.spaces.member-identity.md) (the per-tenant username and
  sealed name this edits)
- Decisions: [ADR 0023](../../../decisions/0023-per-tenant-member-identity.md) (per-tenant identity),
  [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md) (the seal address),
  [ADR 0022](../../../decisions/0022-settings-information-architecture.md) (the settings hub the rubric lives in)
- Related use-cases: [add-member](uc.spaces.add-member.md) (where the username is first chosen, and the directory
  that shows it), [consent-group-add](uc.spaces.consent-group-add.md) (the preference on the same panel)
- End-user view (docs site): [Your profile](../../../../site/src/content/docs/account/your-profile.mdx)
