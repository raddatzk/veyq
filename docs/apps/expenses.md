# Application: Expenses (concept)

> **Concept only.** Nothing here is built, refined or planned. An alternative to Splid-style shared expense
> tracking, as a second application on the platform ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)).

## Idea

- An **expense group** is a node (its own kind). Its **participants are anonymous named people inside the sealed
  record**, not accounts: the server never learns who owes whom.
- **Entries are append-only and sealed** (an expense, a settlement). A correction is a new entry that refers to
  the one it replaces.
- **Guests contribute through a contributor share link**, without an account or a signature
  ([ADR 0030](../decisions/0030-contributor-public-share.md)).
- **Invite-only visibility** is a platform fact, not an application feature: create a `GROUP` scope that holds
  the ledger, and only its members can open it.
- Several expense groups per scope; balances are computed in the client.
- **Entry format is versioned from the first entry.** Signatures and a hash chain are deferred; because the format
  is versioned they can be added without rewriting what exists.

## Known gap

The server can **drop or reorder entries**, because a seal binds an entry to its object, not to its neighbours
([ADR 0072](../decisions/0072-a-seal-binds-its-object.md)). It is closed only if a sequence number or hash chain is
bound into each entry's associated data
([security-model](../crosscutting/security-model.md#applications-are-compiled-in--never-loaded)).

## Operator-visible, beyond the platform's `kind`

Entry count and timestamps of the ledger (when people spend), entry sizes, the contributor links that wrote to it.

## Open questions

- Sequence or hash chain in the associated data from day one, or deferred behind the versioned format?
- A guest cannot sign, so who may a hash chain trust to extend it: the server's order, or the members' clients?
- Can a participant later be linked to an account without revealing the ledger to the server?
- Currency and exchange rates: client-side only, entered by hand?
