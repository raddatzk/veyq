# Application: Expenses

> **The ledger is built, on the V1 line** (owner 2026-10-23; cr-expenses-ledger, 2026-10-25, its browser pass lane L1's
> `tests/e2e/tests/features/expenses-pot.spec.ts`, EN and DE). Shared expense tracking in the shape of Splid, as the third application on the platform: Pots,
> their people, entries with their history, the ledger check, balances, settle-up, overviews and the link are built, a rate
> is typed. **Not built yet:** receipts (`changes/cr-expenses-receipts.md`), rates fetched by the device
> (`changes/cr-expenses-exchange-rates.md`) and "Who am I" (inbox #541). Backend `:apps:expenses`
> (`me.raddatz.veyq.expenses`), contract `contracts/api/expenses/`, the entry format `contracts/expenses/`, web
> `src/web/src/features/expenses`. What it asks of the platform — storage, seals, guests, receipts, exchange rates, what
> the operator sees — is [ADR 0108](../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md); this
> page is the domain. Its flows are the use-cases under [features/expenses](../features/expenses/_index.md).

Expenses (*Ausgaben*) keeps **Pots** (*Kassen*): a trip, a shared flat, a club evening. People put in what they paid,
and anyone in the Pot sees at any time who owes whom.

## The Pot

- **Participants** are only names in this Pot, linked to no account — a link guest has no stable identity to link
  to. Whoever may write adds and renames one. The rest is a member's (ADR 0108 decisions 3 and 6):

  | Step | Only when | Entries show | New entries, overviews | Undone |
  |---|---|---|---|---|
  | **Deactivate** | balance settled | the name | not offered, not shown | a member activates it again |
  | **Delete** | balance settled | "deleted people" (all deleted participants as one) | not offered, not shown | never; the name is erased from storage |
  | **Remove** | no revision of any entry, live or deleted, names it | — | gone | never; erased from storage |

  A deactivated or deleted participant counts in the balances as before. Should an edit of an old entry unsettle one,
  it shows again in the balances and the settle-up — a deactivated one by name, a deleted one as "deleted people" — so
  the group sees what is open. In the data every deleted participant keeps its own id, which entries and the rounding
  order use; only the screen merges them.
- **Categories**: a default set with stable ids the client translates, plus the Pot's own (a member's to add):

  | id | de | en |
  |---|---|---|
  | `food` | Essen & Trinken | Food & drink |
  | `groceries` | Einkäufe | Groceries |
  | `accommodation` | Unterkunft | Accommodation |
  | `transport` | Transport | Transport |
  | `leisure` | Freizeit | Leisure |
  | `household` | Haushalt | Household |
  | `other` | Sonstiges | Other |
- **Base currency**: chosen when the Pot is created, fixed from its first entry on (live or deleted), because every
  entry carries its rate to it. It and the Pot's own categories are the `pot` object of the node's sealed meta,
  [`contracts/expenses/pot-meta.v1.schema.json`](../../contracts/expenses/pot-meta.v1.schema.json), written by a member
  whole with the revision it read. A Pot whose meta carries no readable `pot` shows no balance and takes no money entry
  until a member sets a base currency: a client never guesses a currency.
- **Who am I** is each viewer's own choice, kept on the device; it marks "you" in balances and never leaves the client.
  Not built yet (inbox #541).

## Entries

Every entry has a title, a date, a currency with its rate to the base currency, and any number of receipts (images or
PDFs; not built yet — the format lists them, empty until `cr-expenses-receipts`). Three kinds:

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

**Rounding** is part of the entry format, so every device computes the same balance. The format is
`contracts/expenses/entry.v1.schema.json`, and `contracts/expenses/v1-vectors.json` holds the cases every client must
compute alike:

- Amounts are integers in the currency's ISO 4217 minor unit (the yen has none, the dinar three); a rate is a decimal
  string, and all arithmetic is exact (`BigInt` fractions), never floating point.
- A foreign amount is converted **once**, its total into the base currency, rounded half away from zero to the minor
  unit; it is allocated after that, in the base currency.
- An allocation gives each person their exact share rounded down, then hands the cents left over one each to the
  people with the largest cut-off remainders, ties broken by the order the people joined the Pot (largest remainder):
  nobody is ever more than one cent off their share.
- An entry with line items is rounded **once**: each person's exact shares of the items and of the tip are summed
  first and allocated as one, so rounding never accumulates across items.
- An entry that does not add up (payers' amounts beside the total, exact amounts beside it, line items above it) is
  not saved; one that arrives anyway counts nowhere and is shown as invalid (owner 2026-10-24). So does an entry that
  names someone who is no participant of the Pot: it has no place in the join order the rounding breaks ties by.

**Reading a Pot.** Where the schema leaves a reader a choice, every client chooses alike:

- A record counts by its **latest** revision. One whose latest revision does not open counts nowhere — never an earlier
  one in its place, which would count what the server chose to serve.
- The ledger check ([uc.expenses.verify-ledger](../features/expenses/use-cases/uc.expenses.verify-ledger.md)) marks a
  named revision that is missing, swapped or does not open, and also a hole in a record's revisions `1..latest`, which the
  named-head check misses once the revision naming the dropped one is dropped too.
- A missing revision is covered only by a revision of the **same** record that opened to `kind: "erasure"`; the
  server's own erasure flag is never read for it.
- A writer refuses before sealing anything: a money entry that does not add up, two revisions of one record in a batch,
  an erasure through the append, a participant whose `joined` moved, and a deactivation or an erasure while the
  balance is not settled.

**Editing and deleting** are open to everyone who may write (ADR 0108 decision 6). Each change keeps the earlier
version: an entry shows that it was edited, and its history what changed and when — and, for a member, who (the
writing member or link guest, recorded by the platform, not a participant). A deleted entry moves to the
Pot's own list of deleted entries, restorable during the trash retention; after it, its receipts are erased and the
entry stays a line in the history.

**Exchange rates** fill in on their own where the instance allows it, and can always be typed (ADR 0108 decision 8).
An entry keeps the rate it was saved with; a later rate never changes it. Built today: the typed rate; the fetch comes
with `cr-expenses-exchange-rates`.

## What the client computes

- **Balances**: per participant, what they paid (and received as refunds) against their share of every expense and
  refund, transfers included.
- **Settle up**: transfers that bring every balance to zero, computed greedily — debtors and creditors each sorted by
  amount, largest first, ties by the order the people joined; the largest debtor pays the largest creditor the smaller
  of the two amounts, both chosen again after every transfer, until nothing is left. At most one transfer fewer than there are people, and the same on every
  device; not always the fewest possible (finding those is NP-hard in general), which at the size of a trip does not
  matter. Recording one is a transfer entry.
- **Overviews**: total cost per category, per person (what they paid and what their share was, side by side — either
  alone misleads), and category by person. Refunds count against their category; transfers are not costs.

## Rights

A share is the member's view with functions removed. On a Pot:

| | member | `READ` guest | `READ_WRITE` guest |
|---|---|---|---|
| see entries, history, balances, overviews; download receipts | ✓ | ✓ | ✓ |
| create, edit, delete and restore entries; add and remove receipts | ✓ | ✗ | ✓ |
| add and rename participants | ✓ | ✗ | ✓ |
| deactivate, activate, delete and remove participants | ✓ | ✗ | ✗ |
| own categories, base currency (the Pot's meta) | ✓ | ✗ | ✗ |
| create a Pot | ✓ | ✗ | ✗ |

A `WRITE_ONLY` link on a Pot does not exist. A deleted Pot goes to the space's trash like any node; a deleted entry
stays inside the Pot (§ Entries).

## Download and import

A Pot downloads ([ADR 0082](../decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md))
as a JSON file of its participants and entries, which an import reads back, a CSV of the entries for a spreadsheet,
and its receipts as files beside them. History is not exported; an import starts each entry at its current revision,
under new ids, a batch of at most 256 records after another, each on what the one before wrote. A deleted person comes
back as a deactivated one shown as "deleted people": its name is gone, and an erasure on import would need its balance
settled first; a member can delete it again. A copy is the download and the import without the file.

## Out of the first cut

Previews of receipts, offline use, push notices, a category per line item, and rates by entry date (an entry entered
late takes the day's rate unless typed).

## Related

- End-user view (docs site): [Expenses](../../site/src/content/docs/expenses/index.mdx)
