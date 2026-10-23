# Application: Expenses

> **Refined, not built, out of V1.** Shared expense tracking in the shape of Splid, as the third application on the
> platform. What it asks of the platform — storage, seals, guests, receipts, exchange rates, what the operator sees — is
> [ADR 0108](../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md); this page is the domain.
> No use-case exists yet: the first CR writes them.

Expenses (*Ausgaben*) keeps **Pots** (*Kassen*): a trip, a shared flat, a club evening. People put in what they paid,
and anyone in the Pot sees at any time who owes whom.

## The Pot

- **Participants** are only names in this Pot, linked to no account — a link guest has no stable identity to link
  to. Renaming is always possible; removing only while no entry names the person, else the participant is hidden.
- **Categories**: a fixed default set with stable ids, translated in the client (food, accommodation, transport,
  shopping, leisure, other), plus the Pot's own.
- **Base currency**: chosen when the Pot is created, fixed from its first entry on, because every entry carries its
  rate to it.
- **Who am I** is each viewer's own choice, kept on the device; it marks "you" in balances and never leaves the client.

## Entries

Every entry has a title, a date, a currency with its rate to the base currency, and any number of receipts (images or
PDFs). Three kinds:

| Kind | Who | Amounts | Category |
|---|---|---|---|
| **Expense** | one or more **payers**, each with the amount they paid | allocated to **beneficiaries** (below) | one per expense |
| **Refund** (an income: a credit for a delayed flight) | one or more **receivers**, each with the amount received | allocated to beneficiaries like an expense; may name the expense it refunds | one, counted negatively |
| **Transfer** (settling up) | from one participant to another | one amount | none |

**Allocation** of an expense or a refund is one of:

- **A split** among beneficiaries: equally, by shares, or by exact amounts.
- **Line items**: each with a label, an amount and the participants it belongs to (a bottle of wine for three is one
  item with three people). The difference between the total and the sum of the items — tip, service — is spread
  **in proportion to each person's items** (the default) or **equally among the people on the bill**, chosen per entry
  and sealed with it.

Amounts are integers in the currency's minor unit, never floating point. Where an amount does not divide evenly, the
format's rounding rule decides which participant gets the remaining cent, deterministically, so every device computes
the same balance.

**Editing and deleting** are open to everyone who may write (ADR 0108 decision 6). Each change keeps the earlier
version: an entry shows that it was edited, and its history what changed and when — and, for a member, who (the
writing member or link guest, recorded by the platform, not a participant). A deleted entry moves to the
Pot's own list of deleted entries, restorable during the trash retention; after it, its receipts are erased and the
entry stays a line in the history.

**Exchange rates** fill in on their own where the instance allows it, and can always be typed (ADR 0108 decision 8).
An entry keeps the rate it was saved with; a later rate never changes it.

## What the client computes

- **Balances**: per participant, what they paid (and received as refunds) against their share of every expense and
  refund, transfers included.
- **Settle up**: the fewest transfers that bring every balance to zero, computed deterministically so every device
  suggests the same ones; recording one is a transfer entry.
- **Overviews**: total cost per category, per person (what they paid and what their share was, side by side — either
  alone misleads), and category by person. Refunds count against their category; transfers are not costs.

## Rights

A share is the member's view with functions removed. On a Pot:

| | member | `READ` guest | `READ_WRITE` guest |
|---|---|---|---|
| see entries, history, balances, overviews; download receipts | ✓ | ✓ | ✓ |
| create, edit, delete and restore entries; add and remove receipts | ✓ | ✗ | ✓ |
| participants, categories, base currency (the Pot's meta) | ✓ | ✗ | ✗ |
| create a Pot | ✓ | ✗ | ✗ |

A `WRITE_ONLY` link on a Pot does not exist. Whether a contributor may add a participant (a friend joining a trip half
way) is open below.

## Download and import

A Pot downloads ([ADR 0082](../decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md))
as a JSON file of its participants and entries, which an import reads back, a CSV of the entries for a spreadsheet,
and its receipts as files beside them. History is not exported; an import starts each entry at its current revision.

## Out of the first cut

Previews of receipts, offline use, push notices, a category per line item, and rates by entry date (an entry entered
late takes the day's rate unless typed).

## Open questions

- May a `READ_WRITE` guest add a participant? The Pot's meta is a member's today, but a link-only group has no member
  on the trip.
- The default category set and its icons; the rounding rule; the settle-up algorithm (greedy largest-first is the
  usual choice and deterministic once ties are ordered).
