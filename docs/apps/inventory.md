# Application: Inventory (concept)

> **Concept, not built and not on the V1 line** (inbox #326). It is shaped on today's platform, with Expenses
> ([ADR 0108](../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)) as its template; an ADR
> takes its decisions over when it is refined, and this page keeps the domain. The questions still open are at the end.

Inventory (*Inventar*) keeps track of the things the people of a space own — climbing gear, camping kit, board
games — of who has them now, and of who asked to borrow them, with the platform's operator-blindness: the server
never learns what anyone owns, who lent it to whom, or when it is due back.

## The domain

- **One pool per space.** Each space has one **pool** (*Pool*), created the first time someone opens Inventory in it:
  a personal space's pool is one's own stuff, a group's a crew's, a tenant's a household's or a club's. Every member
  of the space sees the whole pool and adds their own items to it. It is **no open pool**: an item is bound to its
  owner and its space, and only its owner writes it.
- **An item belongs to the member who added it.** Its **owner** (*Eigentümer*) is that member, for good, and the only
  one who writes it — its name, tags, location, fields, notes, pictures and who holds it. Everyone else writes only
  **requests** about it: to borrow it, to reserve it, and to report it returned. Its
  **holder** (*Besitzer*) is who has it now — the owner, unless it is lent. A holder is a **member** of the space or a
  **name** (someone outside it), the same two kinds as a Pot's participants (ADR 0108 decision 2). Every change of
  holder stays in the item's history.
- **What an item carries:** a name, its tags, where it is (a location, typed — "cellar, box 3"), its fields, notes and
  pictures. A location only describes; it is no folder and no boundary.
- **Fields, placed freely.** The owner adds a field to an item: a label of their choosing and one of the field
  types Inventory offers — text, number, yes/no, date, and a **due date**, a date the pool shows to its owner as due
  ("use until", "next inspection"). The types are Inventory's, versioned in its entry format; the labels are the
  owner's. Labels already used in the pool are offered again, so "next inspection" stays one field across items and
  filters by it; nothing forces a field on an item, and no category groups them.
- **Tags, the owner's.** No fixed categories: an owner gives an item any number of tags, and the tags already used in
  the pool are offered again, as field labels are, so a crew's "rope" stays one tag to filter by. There is no tag list
  of the pool's that everyone writes: a tag is a word on an item.
- **Notes and pictures per item.** A note is dated and kept: the item's notes are its history (a fall, an inspection,
  a repair, a missing piece). Pictures are images attached to the item.
- **A loan is asked for.** A member asks the owner to borrow an item, in a request of their own, from a day and optionally until a day, for
  themselves or for a name — a person outside the space asks through a member. The owner confirms or declines; on a
  confirmation the borrower becomes the holder from that day. The owner lends their own item to someone directly.
  Passing an item from one borrower to the next is a new request to the owner. **The return:** the holder reports
  it — with what the owner should know, such as a fall — and the owner confirms it came back, with a note on its state
  where it must be inspected after use; then the owner is the holder again. A loan opens nothing to the borrower: a member already sees the pool, a name is a person veyq does
  not know.
- **Reservations** are loans asked for ahead, for a span of days. Two confirmed spans of one item may not overlap: the
  client checks before writing, and the record's compare-and-swap below makes a second write over a stale view fail
  instead of double-booking.
- **An owner who leaves takes their items along.** When a member leaves the space, their items leave the pool and go
  to the pool of their personal space, with their fields, notes, pictures and history; an item lent out at that
  moment goes along still lent, its holder kept as the name it had. Items of others that the leaving member holds
  stay in the pool, held by a former member, until their owner confirms them back. An account erased takes its items
  with it.
- **No links.** A pool cannot be shared by a link: a link guest is anonymous and names itself, which is not who should
  hold a club's rope. A person outside the space asks a member.
- **QR labels.** A printable label per item that holds only a URL with the item's id on the tenant's domain — never a
  key. Scanned by a member who is signed in, it opens the item; to anyone else it shows the sign-in.

**The server holds to it**, though it reads no record: every revision's writer is operator-visible already, so it
takes an item's next revision only from the writer of its first — its owner — and a request's only from whoever wrote
the request. What it cannot check is what a request says: which item it is about, and the owner's answer to it, are
sealed, and the owner's client reads them. With no push, an open request shows when its owner opens the pool.

**Not in the first cut:** packing lists (a list of items to tick off for a trip), a finder card (a label a stranger
can read to reach the owner — it would need a public link, which Inventory otherwise has none of), reminders by
notification (below), a field set per tag, and import from a spreadsheet.

## On the platform

What it takes from the platform as it stands:

| Need | Today |
|---|---|
| The pool, its key, trash, moderation, the application switch | A node kind (`INVENTORY`) with `NodeKindRules` — Expenses' `ExpensesNodeKindRules` is the template — at most one per space, at its root; no share roles, so no link. |
| Items, loans, reservations and notes as records with a history | ADR 0108's shape in Inventory's own table: one row per revision, the server takes `n + 1` only over `n` (`409 stale_revision`), tombstones, history kept, each revision under its own key wrapped under the pool's, bodies padded to size classes. |
| A dropped or reordered revision is caught | ADR 0108 decision 5's graph of seen revisions, no signature (a seal under the pool's key already proves a key holder wrote it). |
| Pictures per item | **New core piece:** a record with attachments, which ADR 0108 left until a third application needed it. Expenses' receipt table (`veyq_expenses_receipt`, the fourth `veyq_blob` arm) is where it is drawn from, so Inventory does not add a fifth arm of its own. |
| Only the owner writes an item; others write requests | **New, Inventory's own:** a writer check per record — revision `n + 1` only from the writer of revision 1 — over what the operator sees already. |
| An owner takes their items along | **New, Inventory's own:** the server knows an item's owner already — the writer of its first revision is operator-visible, as every revision's writer is — so it can hand a member who leaves their own items and nothing else. Their client re-seals them into their personal pool, and the server then drops them from the space's. An erased account's items go with it. |
| Search and filter by tag, field, location, holder | In the client, over the decrypted list — a few thousand items fit in memory, as a Pot's entries do. |
| "Due", "due back", "asked of me" | Shown when the pool opens, computed on the device. |

What it does **not** need, unlike the earlier concept: a content key wrapped for one person (a loan is noted, not
granted), a signed log (the graph of seen revisions), and a push service.

## What the operator sees

The node `kind` `INVENTORY` in each space that uses it; the number of items, their revisions — each one's time, size
class and writer, and with the first revision's writer **whose item it is** — tombstones and erasures; the number and
sizes of the pictures. From the times of writes: **when** the pool is used, and with that the rhythm of requests,
loans and returns, but not which item, to whom, or until when. No item name, tag, field, location, holder, date or note
is readable by it. That the owner is visible is the price of taking one's items along: the writer of every revision
is visible in a Pot today already.

## Open questions

- **A member removed rather than leaving.** The recommendation: they take their items along as well — removing
  someone does not keep their things. Their client fetches them at its next sign-in, within a window; after it, the
  items are gone from the space's pool all the same.
- **Bound to the space, or taken along?** An item is bound to its owner and its space. When its owner leaves, does it
  go with them to their personal pool, as written above, or is it gone with them from the space and nowhere else?
- **An item changing owner** (sold or given to another member): no transfer, since the owner is fixed to who added it;
  the new owner adds it again. The recommendation is to keep it so in the first cut.
- **Reminders beyond opening the pool** — a local notification on a phone after a sync, as Expenses' #550 — wait for
  the native clients (#438).
