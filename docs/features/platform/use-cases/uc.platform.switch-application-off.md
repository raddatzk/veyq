# UC: An application switched off is read-only

- **ID:** uc.platform.switch-application-off
- **Primary actor:** Every **member** and **guest** of a tenant where an application is switched off.
- **Secondary actor:** The **Server Owner** (for the instance, [uc.admin.switch-capabilities](../../admin/use-cases/uc.admin.switch-capabilities.md))
  or the **tenant owner** (for the tenant, [uc.admin.tenant-switch-capabilities](../../admin/use-cases/uc.admin.tenant-switch-capabilities.md)),
  who switched it.
- **Goal:** An instance or a tenant can stop offering an application — Memories, Depot, Expenses — without trapping
  anyone's data in it ([ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)
  decision 8, [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
  decision 5).
- **Preconditions:** The application's entry (`memories`, `depot`, `expenses`) is switched off for the instance, or for
  the tenant.
- **Postconditions (success):** Nothing of the application is written in the tenant while it is off; everything of it
  that exists opens, downloads and can be deleted as before; switched on again, everything is as it was. Its child
  entries (`memories.places`, `memories.map`, `expenses.exchange-rates`) are off with it. Left off for the window, its
  content is erased on a day its members are told
  ([uc.platform.erase-switched-off-application](uc.platform.erase-switched-off-application.md), planned).

## Main flow

1. The switch takes effect without a restart; the application's id is missing from every client's effective set.
2. Every member of the tenant sees one banner at the top of veyq: the application is switched off here, and what is
   there can still be opened and downloaded, so there is time to save it (owner 2026-10-24). The create menu offers
   none of the application's kinds, and the application's nodes and records show with every write function removed.
3. A member opens, reads and downloads what exists; a guest does the same through a link that exists, which keeps
   being served (owner 2026-10-24). A guest's page is not told the switch: the link's resolve carries no tenant
   setting, so a guest's write is refused like any other write (step 4, E6).
4. Every write of the application is refused `422 capability_disabled` by the server: a node of its kinds created,
   renamed, moved, given a cover, copied or imported; its records and blobs written; a link rooted on one of its nodes
   created, reactivated or given a write role; a guest's write through any link.
5. A member deletes a node of the application to the trash and restores it from there, as always; restored, it is
   read-only like every node of the application — an application off means its nodes are off (owner 2026-10-23).
6. Switched on again, every write is accepted and nothing was lost.

## Alternate / exception flows

- **E1 — a rotation's re-key reaches the application's nodes:** it runs; the re-key is the security of what exists
  ([ADR 0109](../../../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md)).
- **E2 — an erasure** (an account's, a tenant's, the trash's purge) and **a moderation measure:** they run.
- **E3 — off for the instance, on for the tenant:** off; a tenant only narrows what the instance allows (ADR 0088
  decision 2).
- **E4 — a write already in flight** (an upload started before the switch): its completion is refused; nothing of it is
  kept.
- **E5 — a member's own marks** (a heart on a medium, Memories' item marks, [ADR 0068](../../../decisions/0068-favorites-two-confidentiality-levels.md)):
  taken, because they are the member's own blob, not the application's content, and a Master-Key rotation re-tags
  them through the same write, which the server cannot tell from a new mark (owner 2026-10-23).
- **E6 — a client that does not know the id** (older, or a native app), and **a guest's page**, which is not told it:
  it hides nothing, and the server refuses its writes all the same. The web client reads the refusal: an upload that
  meets it fails at once, with no retry, and says the application is switched off here.

## Quality notes

- The switch reaches what exists, unlike every other feature switch, because an application off that still took
  writes into its existing nodes would not be off; it stops at reading, because a switch that hid what people stored
  would hold their data hostage (owner 2026-10-23).
- The server sees what it refuses by: the node's kind, the application's operations, the share's root kind.
- Off is a window, not an end: switched on again, nothing was lost. What follows a long window is
  [uc.platform.erase-switched-off-application](uc.platform.erase-switched-off-application.md): the application's
  content in the tenant is erased on a day the banner and a mail name, unless it is switched on before (owner
  2026-10-24 and 2026-10-25). It is planned, not built; until it is, nothing is erased.

## User impact

The people who run an instance or a tenant can stop offering one of its applications, and nobody loses access to what
they had already put into it.

## Related

- **Use-cases:** [uc.platform.erase-switched-off-application](uc.platform.erase-switched-off-application.md) (what
  follows a long window, planned), [uc.admin.switch-capabilities](../../admin/use-cases/uc.admin.switch-capabilities.md),
  [uc.admin.tenant-switch-capabilities](../../admin/use-cases/uc.admin.tenant-switch-capabilities.md),
  [uc.platform.capability-discovery](uc.platform.capability-discovery.md).
- **Decisions:** [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) decision 8,
  [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decisions 2
  and 5.
- **On the docs site:** [What your space offers](../../../../site/src/content/docs/space-owners/capabilities.mdx) (the
  tenant owner's switch, EN and DE), [Applications](../../../../site/src/content/docs/applications/index.mdx) (what a
  member sees, EN and DE) and [Capabilities](../../../../site/src/content/docs/run/capabilities.mdx) (the Server Owner's).
