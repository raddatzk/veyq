# Application: Inventory (concept)

> **Concept, not built and not on the V1 line** (inbox #326). It is shaped on today's platform, with Expenses
> ([ADR 0108](../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)) as its template; an ADR
> takes its decisions over when it is refined, and this page keeps the domain. The questions still open are at the end.

Inventory (*Inventar*) keeps track of things a person or a group owns — climbing gear, camping kit, board games — and
of who has them and who reserved them, with the platform's operator-blindness: the server never learns what anyone
owns, who lent it to whom, or when it is due back.

## The domain

- **An Inventory is a node** of its own kind (`INVENTORY`), like a Pot: at a space's root or in a folder, as many as
  the space wants ("Climbing gear", "Games"), in the node trash, with its own key
  ([ADR 0080](../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)). Who sees an
  Inventory is who is in the space: a personal space is one's own stuff, a group a crew, a tenant a household or a club.
- **An item** is one thing: a name, its tags, where it is (a location, typed — "cellar, box 3"), notes and pictures.
  A location only describes; it is no folder and no boundary.
- **Tags, managed by the Inventory's members.** No fixed categories: whoever may write adds, renames and removes the
  Inventory's tags, and an item carries any number of them. The tag list is the Inventory's meta, written whole
  ([ADR 0077](../decisions/0077-a-write-carries-the-version-it-read.md)); an item names its tags by id, so a rename
  reaches every item at once.
- **Notes and pictures per item.** A note is dated and kept: the item's notes are its history (a fall, an inspection,
  a repair, a missing piece). Pictures are images attached to the item.
- **Lending is noted, not granted.** An item says who has it and since when, optionally until when: a **member** of the
  space or a **name** (someone outside it), the same two kinds as a Pot's participants (ADR 0108 decision 2). Noting a
  loan opens nothing to the borrower: a member already sees the Inventory, a name is a person veyq does not know.
  Returning it ends the loan; the loans stay in the item's history.
- **Reservations.** A member reserves an item for a span of days, for themselves or for a name — a guest's wish goes
  through a member, as everything a person outside the space wants does. Two reservations of one item may not overlap:
  the client checks it before writing, and the record's compare-and-swap below makes a second write over a stale view
  fail instead of double-booking.
- **No links.** An Inventory cannot be shared by a link: a link guest is anonymous and names itself, which is not who
  should hold a club's rope. A person outside the space asks a member.
- **QR labels.** A printable label per item that holds only a URL with the item's id on the tenant's domain — never a
  key. Scanned by a member who is signed in, it opens the item; to anyone else it shows the sign-in.

**Not in the first cut:** packing lists (a list of items to tick off for a trip), a finder card (a label a stranger
can read to reach the owner — it would need a public link, which Inventory otherwise has none of), reminders by
notification (below), and import from a spreadsheet.

## On the platform

What it takes from the platform as it stands:

| Need | Today |
|---|---|
| The Inventory, its key, trash, moderation, the application switch | A node kind with `NodeKindRules` — Expenses' `ExpensesNodeKindRules` is the template; no share roles, so no link. |
| Items, loans, reservations and notes as records with a history | ADR 0108's shape in Inventory's own table: one row per revision, the server takes `n + 1` only over `n` (`409 stale_revision`), tombstones, history kept, each revision under its own key wrapped under the Inventory's, bodies padded to size classes. |
| A dropped or reordered revision is caught | ADR 0108 decision 5's graph of seen revisions, no signature (a seal under the Inventory's key already proves a key holder wrote it). |
| Pictures per item | **The one new core piece:** a record with attachments, which ADR 0108 left until a third application needed it. Expenses' receipt table (`veyq_expenses_receipt`, the fourth `veyq_blob` arm) is where it is drawn from, so Inventory does not add a fifth arm of its own. |
| Search and filter by tag, location, who has it | In the client, over the decrypted list — a few thousand items fit in memory, as a Pot's entries do. |
| "Due back", "reserved by me" | Shown when the Inventory opens, computed on the device. |

What it does **not** need, unlike the earlier concept: a content key wrapped for one person (a loan is noted, not
granted), a signed log (the graph of seen revisions), and a push service.

## What the operator sees

The node `kind` `INVENTORY` and the Inventory's place in the tree, as for any node; the number of items, their
revisions — each one's time, size class and writer — tombstones and erasures; the number and sizes of the pictures.
From the times of writes: **when** the Inventory is used, and with that the rhythm of loans and reservations, but not
which item, by whom or to whom. No item name, tag, location, borrower, date or note is readable by it.

## Open questions

- **Dates an item can carry.** Tags replace categories, so a climbing harness's manufacture date, its end of service
  and its next inspection have no category to live in. The recommendation: every item may carry two optional dates —
  *use until* and *next check* — that every item type needs alike, and the Inventory shows what is due when it opens.
- **Reminders beyond opening the Inventory** — a local notification on a phone after a sync, as Expenses' #550 — wait
  for the native clients (#438).
- **Who may write.** The recommendation: every member who may write in the space, as in a Pot; a reader of the space
  reads the Inventory and reserves nothing.
