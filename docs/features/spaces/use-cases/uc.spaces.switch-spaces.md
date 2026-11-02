# UC: List & switch spaces

- **ID:** uc.spaces.switch-spaces
- **Primary actor:** Member (authenticated + unlocked)
- **Goal:** move between the **tenants** they belong to and the **spaces** within the active tenant — the shared
  **Tenant** space, their **Personal** space, and their **groups** — from the shell.
- **Preconditions:** A live session + an unlocked vault; the member belongs to ≥1 tenant.
- **Postconditions (success):** The shell shows the chosen tenant/space; the active scope key is the one for the
  selected space, and the tree + favorites have been re-entered for it.

## Main flow

1. Under the sidebar's **"Bereiche/Spaces"** header the **active tenant** is shown as a single row — its avatar,
   name and domain — from `GET /api/scopes/my-tenants`, each tenant reached at its own domain
   ([ADR 0002](../../../decisions/0002-personal-scope-per-tenant.md)). A **chevron** on that row opens an
   **overview of all the member's tenants** to switch between them; the chevron appears only when the member
   belongs to **more than one** tenant.
2. **Each avatar is decrypted on device.** `my-tenants` is **account**-scoped, not Host-scoped, so it carries per
   tenant — beside the cleartext name + domain — the caller's **own** `wrappedGrant` for that tenant's root scope
   and its `sealedIcon` + `iconEpoch` ([en.spaces.tenant-scope](../enablers/en.spaces.tenant-scope.md), which owns
   that read path and the reasoning for it). For each row the client opens the grant, opens the icon's value key from its
   wrap with the scope key it carries (a rotation re-wraps it and leaves the icon, ADR 0115), decrypts the icon, and **zeroes that scope key immediately** — the same
   transient open-decrypt-zero the group-name read already does. The result is the row's avatar; where no icon is
   set, or no grant is held (A2), the tenant's **initial** stands
   ([uc.spaces.set-space-icon](uc.spaces.set-space-icon.md)).
3. That **same active-tenant row is the entry into the shared Tenant space**
   ([en.spaces.tenant-scope](../enablers/en.spaces.tenant-scope.md), labelled with the **tenant's name**) — its
   body opens the token-less tenant home, so the tenant *switcher* and the Tenant-*space* entry are **one row**,
   not two. Below it the sidebar lists the remaining spaces in the active tenant: the **Personal** space
   ([en.spaces.personal-scope](../enablers/en.spaces.personal-scope.md)) and the member's **groups**
   ([en.spaces.group-scope](../enablers/en.spaces.group-scope.md), as they land).
4. Selecting a **different tenant** from the overview navigates to that tenant's domain (a full navigation —
   each tenant is its own origin).
5. Selecting a **space** switches the in-shell content position via the URL `#fragment`, resolved entirely
   client-side ([ADR 0015](../../../decisions/0015-operator-blind-readable-urls.md)). The grammar
   ([ADR 0019](../../../decisions/0019-token-less-tenant-home.md)):

   | Space | Root | A child |
   |---|---|---|
   | **Tenant** (shared, the default home) | `/` *(token-less — no bare `#`)* | `/#urlaube` |
   | **Personal** | `/#~me` | `/#~me/urlaube` |
   | **Group** (later) | `/#@<id>` | `/#@<id>/urlaube` |

   The token-less default is the **Tenant** space — the shared commons is home; Personal rides the explicit
   `~me` prefix. The folder/Memory slugs stay in the fragment (operator-blind unchanged); only the leading
   token distinguishes the scope.
6. **Re-entering a scope** (a leading-token change, or first entry) opens that scope's key from its grant
   ([en.spaces.tenant-scope](../enablers/en.spaces.tenant-scope.md) /
   [en.spaces.personal-scope](../enablers/en.spaces.personal-scope.md)) and **resets the per-scope state** —
   the Memory **tree** and **favorites** are cleared and reloaded for the newly active scope, so no stale tree
   or favorites bleed across scopes. Navigating *within* a scope (segment change only) does not re-enter.

## Alternate / exception flows

- A1 — **only one tenant / no groups:** the active-tenant row is shown **without the overview chevron** (there
  is nothing to switch to) and still enters the Tenant space, with Personal below; the groups section is hidden
  until a group exists.
- A2 — **no openable tenant grant → Personal is home.** If the member's `TENANT` grant does not open — it is
  behind the scope's epoch or refused, until grant-delivery brings it current
  ([en.spaces.scope-key-rotation](../enablers/en.spaces.scope-key-rotation.md) E2), so transient; a new member
  joins holding one ([uc.spaces.add-member](uc.spaces.add-member.md)) — the token-less default is **not** openable,
  so the shell **falls back to Personal** (`~me`) as the effective home and surfaces the Tenant entry as
  *Waiting for the current key* ([ADR 0019](../../../decisions/0019-token-less-tenant-home.md),
  [en.spaces.tenant-scope](../enablers/en.spaces.tenant-scope.md)). No empty shared room is shown, and that
  tenant's row keeps its **initial** — without the grant its icon cannot be opened either.
- A3 — **a suspended tenant** ([uc.admin.suspend-tenant](../../admin/use-cases/uc.admin.suspend-tenant.md)):
  `my-tenants` still lists it, with its status and **without** grant or icon, so the overview shows it marked
  offline — its initial, not enterable — rather than letting it silently vanish. A `403 tenant_suspended` that
  arrives while the member is inside one of its spaces leaves that space for the tenant of the domain the app is
  open on, and the overview then shows the suspended one offline; on the suspended tenant's own domain the app
  shows a full-page "temporarily offline" notice instead ([uc.spaces.scope-access](uc.spaces.scope-access.md)
  A7).
- E1 — **a space can't be opened** (grant unwrap fails): that space is surfaced as unavailable; the others
  remain switchable.

## Quality notes

- **Operator-blind.** The tenant list returns **membership** metadata — tenant name + domain + the caller's role
  + the tenant's status, all sanctioned operational cleartext — plus, per tenant, **ciphertext only the caller can open**: their own
  `wrappedGrant` and the tenant's `sealedIcon`. Handing those over discloses nothing the server does not already
  hold; the reasoning, and the trust-tier cost it *does* carry, live once in
  [en.spaces.tenant-scope](../enablers/en.spaces.tenant-scope.md). **Group names are sealed** the same way and
  decrypted on device ([ADR 0014](../../../decisions/0014-group-scopes-and-governance.md)). Switching a space is
  **client-side** navigation; the server is not asked to resolve a path.
- **Auth ≠ content.** Switching shows a space only if its grant opens (the identity-unwrapped scope key); a
  session alone reveals nothing.

## User impact

Everything you can reach sits in one sidebar, so you move between your shared spaces, your personal space and your
groups in one click.

## Related

- Feature: [Spaces (scopes)](../_index.md)
- Enablers: [en.spaces.tenant-scope](../enablers/en.spaces.tenant-scope.md) (the shared space, opened — and the
  owner of the account-scoped `my-tenants` read this switcher renders from),
  [en.spaces.personal-scope](../enablers/en.spaces.personal-scope.md),
  [en.spaces.group-scope](../enablers/en.spaces.group-scope.md)
- Use-cases: [uc.spaces.set-space-icon](uc.spaces.set-space-icon.md) (the sealed avatar each row shows, and its
  initial fallback), [en.spaces.scope-key-rotation](../enablers/en.spaces.scope-key-rotation.md) (grant-delivery — why A2 is transient)
- Decisions: [ADR 0002](../../../decisions/0002-personal-scope-per-tenant.md) (tenant-bound scopes),
  [ADR 0014](../../../decisions/0014-group-scopes-and-governance.md) (groups, sealed names),
  [ADR 0015](../../../decisions/0015-operator-blind-readable-urls.md) (fragment routing),
  [ADR 0019](../../../decisions/0019-token-less-tenant-home.md) (tenant is the token-less default home)
- End-user view (docs site): [Your spaces](../../../../site/src/content/docs/spaces/index.mdx); the addresses:
  [Views, tabs and favorites](../../../../site/src/content/docs/spaces/views-and-favorites.mdx)
