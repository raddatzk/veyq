# Application: Inventory (concept)

> **Concept, not built and not on the V1 line** (inbox #326). It is shaped on today's platform, with Expenses
> ([ADR 0108](../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)) as its template; an ADR
> takes its decisions over when it is refined, and this page keeps the domain. The questions still open are at the end.

Inventory (*Inventar*) keeps track of the things the people of a space own — climbing gear, camping kit, board
games — of who has them now, and of who reserved them, with the platform's operator-blindness: the server
never learns what anyone owns, who lent it to whom, or when it is due back.

## The domain

- **One pool per space.** Each space has one **pool** (*Pool*), created the first time someone opens Inventory in it:
  a personal space's pool is one's own stuff, a group's a crew's, a tenant's a household's or a club's. Every member
  of the space sees the whole pool and adds their own items to it. It is **no open pool**: an item is bound to its
  owner and its space, and only its owner writes it.
- **An item belongs to the member who added it.** Its **owner** (*Eigentümer*) is that member, for good, and the only
  one who writes the item itself — its name, tags, location, fields, notes and pictures. What every member of the space
  writes about it are **loan entries** (*Ausleih-Einträge*) of their own: that they took it, that they reserve it, that
  they gave it back. Its **holder** (*Besitzer*) is who has it now — the owner, unless the latest loan entry says
  otherwise. A holder is a **member** of the space or a **name** (someone outside it), the same two kinds as a Pot's
  participants (ADR 0108 decision 2). Every loan entry stays in the item's history.
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
- **A loan is the borrower's entry.** A member who wants an item writes an entry of their own, from a day and
  optionally until a day, for themselves or for a name — a person outside the space borrows through a member. The owner
  lends their own item the same way. Passing an item from one borrower to the next is the next one's entry. **The
  return:** the holder notes it, with what the owner should know, such as a fall; where an item must be inspected after
  use, the owner adds a note on its state. A loan opens nothing to the borrower: a member already sees the pool, a name
  is a person veyq does not know.
- **Whether a loan waits for the owner is open** — decided later (owner 2026-11-01), leaning towards confirming.
  **Confirmed:** the entry is a request, and the borrower is the holder only once the owner's own entry says yes; a
  return holds once the owner confirms it came back. **Noted:** the entry holds at once, and the owner, who sees on
  opening the pool what is new on their items ("Anna has your rope since yesterday"), may cancel or correct it with an
  entry of their own. Either way the records are the same — a borrower's entry and the owner's entries about it, each
  written only by its writer — and only what a client counts as holding differs, so the choice moves no part of the
  platform below. A third shape is both, by a switch per item ("only on request", for what is dear or must be
  inspected).
- **Reservations** are loans asked for or noted ahead, for a span of days. Two that hold for one item may not overlap:
  the client checks before writing. Confirmed, the owner says yes over a view that shows the other; noted, where two
  members reserve at once over views that did not yet see each other, the one the server took first holds — by the
  server's time, the same on every client — and the later one shows its writer as overlapping, to move or drop.
- **An owner who leaves, their items go.** An item is bound to its owner and its space: when its owner leaves the
  space, is removed from it or is erased, their items are gone from the pool, with their fields, notes, pictures and
  history. Leaving of their own accord, the owner is asked first: whether to keep a copy of their items in their
  personal space's pool, and — where one is lent out — that it is still with its holder. A copy is a new item there,
  its history along, a loan in it kept with the holder's name. The leaving member's loan entries on others' items stay,
  as their entries in a Pot do; an item of another's they still hold stays held by a former member until its owner
  notes it back.
- **No links.** A pool cannot be shared by a link: a link guest is anonymous and names itself, which is not who should
  hold a club's rope. A person outside the space asks a member.
- **QR labels.** A printable label per item that holds only a URL with the item's id on the tenant's domain — never a
  key. Scanned by a member who is signed in, it opens the item; to anyone else it shows the sign-in.

**The server holds to it**, though it reads no record: every revision's writer is operator-visible already, so it
takes an item's next revision only from the writer of its first — its owner — and a loan entry's only from whoever
wrote the entry. What it cannot check is what an entry says: which item it is about, and the owner's answer to a
borrower's, are sealed, and the clients read them. With no push, a new entry shows when the owner opens the pool.

**Not in the first cut:** packing lists (a list of items to tick off for a trip), a finder card (a label a stranger
can read to reach the owner — it would need a public link, which Inventory otherwise has none of), reminders by
notification (below), a field set per tag, handing an item to another owner (sold or given away: the new owner adds
it again), and import from a spreadsheet.

## On the platform

What it takes from the platform as it stands:

| Need | Today |
|---|---|
| The pool, its key, trash, moderation, the application switch | A node kind (`INVENTORY`) with `NodeKindRules` — Expenses' `ExpensesNodeKindRules` is the template — at most one per space, at its root; no share roles, so no link. |
| Items, loans, reservations and notes as records with a history | ADR 0108's shape in Inventory's own table: one row per revision, the server takes `n + 1` only over `n` (`409 stale_revision`), tombstones, history kept, each revision under its own key wrapped under the pool's, bodies padded to size classes. |
| A dropped or reordered revision is caught | ADR 0108 decision 5's graph of seen revisions, no signature (a seal under the pool's key already proves a key holder wrote it). |
| Pictures per item | **New core piece:** a record with attachments, which ADR 0108 left until a third application needed it. Expenses' receipt table (`veyq_expenses_receipt`, the fourth `veyq_blob` arm) is where it is drawn from, so Inventory does not add a fifth arm of its own. |
| Only the owner writes an item; a loan entry only its writer | **New, Inventory's own:** a writer check per record — revision `n + 1` only from the writer of revision 1 — over what the operator sees already. |
| Two reservations at once (where loans are only noted) | The server's time of each record's first revision, which every client reads alike: the first holds. No lock and no server-side overlap check — the spans are sealed. |
| An owner's items go when they do | The writer check above names them: when a member leaves the space, the server drops the items whose first revision they wrote, in the same step. A copy to their personal pool is their own client's, before they leave, as any write to their own space. |
| Search and filter by tag, field, location, holder | In the client, over the decrypted list — a few thousand items fit in memory, as a Pot's entries do. |
| "Due", "due back", "new on my items" (and, confirmed, "asked of me") | Shown when the pool opens, computed on the device. |

What it does **not** need, unlike the earlier concept: a content key wrapped for one person (a loan is noted, not
granted), a signed log (the graph of seen revisions), and a push service.

## What the operator sees

The node `kind` `INVENTORY` in each space that uses it; the number of items, their revisions — each one's time, size
class and writer, and with the first revision's writer **whose item it is** — tombstones and erasures; the number and
sizes of the pictures. From the times and writers of writes: **when** the pool is used and **who** notes loans in it,
and with that the rhythm of loans and returns, but not which item, to whom, or until when. No item name, tag, field, location, holder, date or note
is readable by it. That the owner is visible is the price of the owner alone writing their items: the writer of every
revision is visible in a Pot today already.

## Open questions

- **Does a loan wait for the owner's yes?** Above, § The domain: confirmed, noted, or a switch per item. The owner
  leans towards confirming and decides later (2026-11-01); the records are the same either way.
- **Reminders beyond opening the pool** — a local notification on a phone after a sync, as Expenses' #550 — wait for
  the native clients (#438).
