# UC: Switch capabilities off for a tenant

- **ID:** uc.admin.tenant-switch-capabilities
- **Primary actor:** The **tenant owner**, signed in (a session; no unlocked vault needed, a switch is configuration)
- **Goal:** Narrow what the tenant offers, below what the Server Owner allows: no public links in this tenant, no
  drop boxes, no new groups. The tenant level of the capability register
  ([ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
  decisions 2 and 12). The tenant owner knows before switching what each entry is for and what switching it off does.
- **Preconditions:** A session as the tenant owner (an OWNER membership on the tenant's root scope); the tenant is
  ACTIVE.
- **Postconditions (success):** The tenant holds the set of entries it switched off. Every member's effective set and
  every gate acting in the tenant follow it without a restart. Nothing a member stored is deleted.

## Main flow

1. The tenant owner opens **Settings → Capabilities** under the shared space (beside Terms and Email).
2. They see the entries a tenant may switch, as a tree, each with its title, what it is for, and its state: **on**,
   **off**, **off through its parent**, or **off for the whole server** (the Server Owner switched it, or a parent, off).
   The words are the catalog's, served by the API in the app's language.
3. They switch an entry **off**. The panel first shows what that does to new and to existing data, in the entry's own
   words, and asks for confirmation.
4. The server stores the switch and answers with the entry's new state. From then on the entry is missing from every
   member's effective set (`GET /api/tenants/{tenantId}/capabilities`), and every gate acting on the tenant's content
   refuses with `422 capability_disabled`. A share switch also reaches the tenant's existing links at once: a guest
   gets the same `404` as for an unknown link (ADR 0088 decision 5), and the members' share lists mark every such link
   as switched off in this space (inbox #457).
5. Switching the entry back **on** restores it, as far as the Server Owner allows: the effective state is the AND of
   both levels along the path, so a tenant can only narrow, never widen.

## Alternate / exception flows

- **A1, an entry only the Server Owner switches:** remembered devices, unlocking without a password and adding a
  device from another one are offered on the sign-in screen, before the tenant is known to the client, so they are
  instance-only and not listed. Their id, and an unknown one, are refused (`404 capability_not_found`), nothing stored.
- **A2, the Server Owner has it off:** the tenant owner still sets the tenant's own switch; it applies once the Server
  Owner switches the entry back on.
- **E1, a member who is not the owner:** `403 not_tenant_owner` on the switches; the effective set stays readable.
- **E2, not a member of the tenant:** `404 tenant_not_found`, as every tenant-addressed read.
- **E3, a suspended tenant:** `403 tenant_suspended`.

## Quality notes

- **One source for the words** (ADR 0088 decision 13): the catalog's texts, the same the Server Owner reads.
- **Operator-blind.** A switch is tenant configuration, carries no member data, and the effective set is the same for
  every member of the tenant (ADR 0032 §3).

## User impact

The owner of a shared space decides what it offers, such as public links or groups, without anything stored being
deleted: switched back on, everything works as before.

## Related

- **Feature:** [admin](../_index.md)
- **Use-cases:** [uc.admin.switch-capabilities](uc.admin.switch-capabilities.md) (the instance level),
  [uc.platform.capability-discovery](../../platform/use-cases/uc.platform.capability-discovery.md) (the effective set),
  [uc.admin.tenant-terms](uc.admin.tenant-terms.md) and [uc.admin.tenant-mail-settings](uc.admin.tenant-mail-settings.md)
  (the settings beside it).
- **Decisions:** [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md),
  [ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md),
  [ADR 0065](../../../decisions/0065-admin-ui-cli-separate-service.md) (why this lives in `src/web/`).
- **On the docs site:** [What your space offers](../../../../site/src/content/docs/space-owners/capabilities.mdx) (the
  tenant owner's steps, EN and DE) and [Capabilities](../../../../site/src/content/docs/run/capabilities.mdx) (both
  levels, for the Server Owner).
