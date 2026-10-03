# Application: Inventory (concept)

> **Concept only.** Nothing here is built, refined into use-cases or planned. It records the owner's idea so the
> platform decisions ([ADR 0079](../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)) can be
> checked against a second, harder application than Memories.

Self-hosted gear management (camping, climbing, board games) with full operator-blindness: the server never
learns what anyone owns, who lent it to whom, or when.

## Domain

- **Item** — one thing. **Location** (box, shelf, cellar) and **Collection** (packing list, set) only
  **organize**; neither is ever a security boundary.
- **Scopes:** `PERSONAL` is your own stuff, `GROUP` a climbing crew, `TENANT` a shared household or club stock.
  One inventory per scope, created on demand.
- **Category-specific fields:** a core item schema plus category attributes with their own schema, each
  versioned. Climbing PPE: manufacture date, maximum service life, fall log, inspection intervals. Games:
  completeness. Camping: condition and maintenance.

## Keys: ownership versus custody

Each item has its own content key `K_item`, wrapped under the scope key. Making an item visible to a group, or
lending it to a person, is **one more wrap** of `K_item` for that scope or identity; the item does not move.
Returning it is a **revocation with a new epoch for `K_item`**. Old blobs (photos) are not re-encrypted: only new
versions use the new key, and what the borrower already read cannot be retracted (the same limit as
[revocation](../crosscutting/security-model.md#revocation--two-layers)).

## Behaviour that lives in the client

- **Reservations and lending:** the conflict check runs in the client; consistency comes from a
  compare-and-swap on **one reservation record per item**. The server only sees version changes.
- **Search, filter, packing lists:** a local index built after decryption.
- **Reminders** (inspection due, service life ending): local notifications on the device after a sync. The server
  may at most send a **content-free wake-up push**.
- **QR labels:** only the item id as a URL on the tenant's domain, never a key. An optional **finder share**
  with its own key carries contact information and nothing else.

## What the operator sees (for the security model)

The platform's node `kind` and per-kind rows, timestamps and sizes, plus: the **lending graph** (who received
access to which item), **activity patterns** from writes to reservation records, and **blob sizes** (mitigated
by padding to fixed size classes).

## Core primitives it needs that do not exist today

| Primitive | Today |
|---|---|
| A content key wrapped for a single identity | Keys are wrapped per scope, per share and per member of a scope. None per item to one identity. |
| Compare-and-swap on a record | [ADR 0077](../decisions/0077-a-write-carries-the-version-it-read.md) covers two sealed blobs only (`veyq_node.meta_version`, `veyq_media.meta_version`). |
| Append-only signed logs (fall log, lending history) | None. Only grants carry a signature ([ADR 0028](../decisions/0028-identity-authenticity-tofu-verification.md)). |
| Content-free wake-up push | None. |

## Open questions

- Is one `K_item` per item affordable for a household with a few thousand items (wraps, epochs, rotation work)?
- Does a lent item show in the borrower's own scope, or only in a "lent to me" view?
- Does the fall log need a signature per entry, or is the item's key holder set enough trust?
