# Application: Expenses

> **Built, on the V1 line** (its browser pass lane L1's `tests/e2e/tests/features/expenses/expenses-pot.spec.ts`,
> EN and DE). Shared expense tracking in the shape of Splid, as the third application on the platform: Pots, their
> people, entries with their history and receipts, the day's exchange rate, the ledger check, balances, settle-up,
> overviews, "Who am I" and the link are built. Backend `:apps:expenses`
> (`me.raddatz.veyq.expenses`), contract `contracts/api/expenses/`, the entry format `contracts/expenses/`, web
> `src/web/src/features/expenses`. What it asks of the platform — storage, seals, guests, receipts, exchange rates, what
> the operator sees — is [ADR 0108](../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md); this
> page is the domain. Its flows are the use-cases under [features/expenses](../features/expenses/_index.md).

Expenses (*Ausgaben*) keeps **Pots** (*Kassen*): a trip, a shared flat, a club evening. People put in what they paid,
and anyone in the Pot sees at any time who owes whom.

## The Pot

- **Participants** are a **member** of the space or a **name** (ADR 0108 decision 2). Whoever may write adds either:
  a name typed, or one of the space's current members not yet in the Pot, chosen from the member list a Memory's
  participants come from (`core/people/directory.ts`) — on **People**, and already in the dialog that creates the Pot,
  through the same picker rules (`people.ts`' `addableMembers` and `participantRecord`). A member participant's record holds the member's user id and,
  sealed with it, their display name when they were added (`member` in [the entry format](../../contracts/expenses/entry.v1.schema.json), optional and additive under `v: 1`: a Pot written before it reads as names). A link guest adds
  names only — it cannot see the space's members — which is the member's view with one function fewer. A member is a
  participant of a Pot once at most. **Reading one:** a member's view shows a member participant by their current
  display name and picture; once the space no longer lists the account (erased, or gone from the space) it shows as a
  **former member** by the name sealed at adding, its entries and balance standing. A link guest reads no member list:
  it shows the sealed name and the letter, never a picture, and a later rename does not reach it (the residual
  ADR 0108 decision 2 accepts). A participant **never changes kind**: no name is linked to an account later, and a
  member participant is not renamed (its name is the member's own). Whoever may write renames a name participant. The
  rest is a member's (ADR 0108 decisions 3 and 6):

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
- **Who am I** is each viewer's own choice, kept on the device (per Pot, per account or guest); it marks "you" in the
  balances and the overview and never leaves the client. Until a signed-in member has chosen on this device, their own
  member participant is who they are (`whoAmI.ts`' `ownParticipant`): a default that is read, never written, so a
  choice they make — "nobody" included, stored as a choice of its own — stays theirs. A link guest has no member
  identity and gets no default, nor does a member who is no participant of the Pot.

## Entries

Every entry has a title, a date, a currency with its rate to the base currency, and any number of receipts (images or
PDFs, § Receipts). Three kinds:

| Kind | Who | Amounts | Category |
|---|---|---|---|
| **Expense** | one or more **payers**, each with the amount they paid; the amount is their sum | allocated to **beneficiaries** (below) | one per expense |
| **Refund** (an income: a credit for a delayed flight) | one or more **receivers**, each with the amount received; the amount is their sum | allocated to beneficiaries like an expense; may name the expense it refunds | one, counted negatively |
| **Transfer** (settling up) | from one participant to another | one amount | none |

The amount of an expense or a refund is never typed: the editor shows the payers' (receivers') sum, and the body seals it.

**Allocation** of an expense or a refund is one shape, never a mode per entry:

- **Items**, each with a label, an exact amount and **its own split** (a bottle of wine for three is one item split
  equally among three).
- **The rest** — the amount less the items — with a split of its own. A new entry has no items, so its whole amount is
  the rest, split equally among the Pot's active participants, who are sealed by name in that split (a later participant
  is on no earlier entry). Items above the amount do not save; the editor says by how much. At a rest of zero no rest
  split is sealed.
- **A split** is **equal** (each person once), **by shares** (whole numbers), **by percent** (basis points, at most two
  decimals, adding up to exactly 100 %) or **by amount** (`amounts`: each person a fixed amount in the entry's currency,
  adding up to exactly the amount of the part it splits — the item's, or the rest's).

So the whole amount equally or by shares is the rest's split alone; exact amounts per person are the rest's split by
amount (or one item each, filling the amount); a bill with a tip is its items plus a rest.

**The editor** (`features/expenses/components/EntryEditor.vue`, owner 2026-11-03) shows a split that is everyone offered,
equally — a new entry's rest, a new item, or a split opened equal over everyone offered — as one line ("All 3 people,
equally") with **Adjust**. Adjusted, a split is **by shares**, **by percent** or **by amount**, its people one row each:
a person picked per row, as **Paid by** does, with their value, **Add another person**, a button per row that takes the
person off, and, by shares, **Split equally among all** (every active person at 1 share). There is no "equally" mode:
shares of 1 each are sealed as `equal` — the same weights, and the form an entry opened again shows as its one line when
it names everyone offered. Percents or amounts that do not make what they must, and items above the amount, are said in
red under their split as they are typed; what the save finds wrong is said in red at its field (the title, **Paid by**, a
split), and the message at the bottom keeps only what belongs to no field.

**Rounding** is part of the entry format, so every device computes the same balance. The format is
`contracts/expenses/entry.v1.schema.json`, and `contracts/expenses/v1-vectors.json` holds the cases every client must
compute alike:

- Amounts are integers in the currency's ISO 4217 minor unit (the yen has none, the dinar three); a rate is a decimal
  string, and all arithmetic is exact (`BigInt` fractions), never floating point.
- A foreign amount is converted **once**, its total into the base currency, rounded half away from zero to the minor
  unit; it is allocated after that, in the base currency.
- An allocation gives each person their exact share rounded down, then hands the cents left over one each to the
  people with the largest cut-off remainders, ties broken by the order the people joined the Pot (largest remainder):
  within one allocation nobody is more than one cent off their share.
- An entry is rounded **part by part**: its base total is first allocated to its parts — each item in its order, then
  the rest — by their amounts (in the base currency every part gets exactly its own amount), then each part among its
  split's people by their weights (1 each, the shares, the basis points, the amounts — so by amount in the base currency
  every person gets exactly their amount). A person's share is the sum of their parts,
  so each item's shares add up to that item exactly; a person on several parts can end up to one cent per part off
  their exact share, never more.
- An entry that does not add up (payers' amounts beside the total, items above it, a rest with no split, percents beside
  100 %, a split's amounts beside the amount of its part, a person named twice) is not saved; one that arrives anyway counts nowhere and is shown as invalid. So does an entry that
  names someone who is no participant of the Pot: it has no place in the join order the rounding breaks ties by.

**Reading a Pot.** Where the schema leaves a reader a choice, every client chooses alike:

- A participant's **kind** is its record's **first** revision's: whether it is a member, and which, is fixed for the
  record's life, and a later revision that adds, drops or changes `member` moves nothing — so neither a client that
  predates the field (it drops `member` like any unknown field, and reads the record as its name) nor a writer naming
  their own account can change whom a participant is. Everything else of the record is its latest revision's.
- A record counts by its **latest** revision. One whose latest revision does not open counts nowhere — never an earlier
  one in its place, which would count what the server chose to serve.
- The ledger check ([uc.expenses.verify-ledger](../features/expenses/use-cases/uc.expenses.verify-ledger.md)) marks a
  named revision that is missing, swapped or does not open, and also a hole in a record's revisions `1..latest`, which the
  named-head check misses once the revision naming the dropped one is dropped too.
- A missing revision is covered only by a revision of the **same** record that opened to `kind: "erasure"`; the
  server's own erasure flag is never read for it.
- A writer refuses before sealing anything: a money entry that does not add up, two revisions of one record in a batch,
  an erasure through the append, a participant whose `joined` moved or whose `member` is not its first revision's, a
  member added who already is a participant (an erased one names nobody any more), and a deactivation or an erasure
  while the balance is not settled.

**Editing and deleting** are open to everyone who may write (ADR 0108 decision 6). Each change keeps the earlier
version: an entry shows that it was edited, and its history what changed and when — and, for a member, who (the
writing member or link guest, recorded by the platform, not a participant). A deleted entry moves to the
Pot's own list of deleted entries, restorable during the trash retention; after it, its receipts are erased and the
entry stays a line in the history.

## Exchange rates

A rate fills in on its own where the instance and the space allow it, and can always be typed (ADR 0108 decision 8,
[uc.expenses.exchange-rates](../features/expenses/use-cases/uc.expenses.exchange-rates.md)). An entry keeps the rate it
was saved with; a later rate never changes it.

- **The table.** Picking a currency other than the Pot's base, in an editable entry, makes the device fetch the day's
  table of the European Central Bank's euro reference rates from the address the instance names — a new instance's is
  Frankfurter's ECB endpoint, `…/v2/providers/ecb/rates?date={date}` — with the device's date in it and nothing else
  (`src/web/src/features/expenses/exchangeRates.ts`). One table per date is kept in the device's local storage for
  every Pot; the attempt is recorded before the request goes, so a day whose fetch failed is not asked again.
- **The rate** from currency X to the base B is `rate(B) / rate(X)`, exact in fractions with the euro as `1`, rounded a
  half away from zero to the format's 12 decimal places. A row may be of an earlier day than asked (a weekend, a
  holiday); the editor says the rate is computed from the ECB's reference rates of that day, which also cites the ECB as
  the source its terms ask for. A currency the table lacks — or a row that is no ISO 4217 code the browser knows — is
  never offered: its rate is typed.
- **Never waits, never moves.** The fill only writes an empty rate of the currency just picked; a rate typed meanwhile,
  another currency or a closed editor drops it. A save seals what the field holds and never waits for the provider, and
  an entry opened with its saved rate is never refilled.
- **Who may fetch.** A member's client reads `expenses.exchange-rates` from its tenant's capability set — never the
  instance's manifest, which a tenant owner's switch does not narrow; a link guest's from its Pot read, which carries the
  table's address (`exchangeRateService`) exactly while the entry is active for the Pot's tenant. So no device fetches
  where the tenant's privacy notice names no fetch (`potSession.ts`' `rateService`).

## Receipts

A receipt is an image or a PDF of at most 10 MiB kept with an entry, up to 100 per entry (the format's `receipts`)
([uc.expenses.attach-receipts](../features/expenses/use-cases/uc.expenses.attach-receipts.md), ADR 0108 decision 7).

- **A blob of core's blob service** ([ADR 0112](../decisions/0112-an-applications-stored-blobs-are-managed-by-a-core-service.md),
  [Applications § Core's blob service](README.md#cores-blob-service)): a row of `veyq_application_blob` in the role
  `EXPENSES_RECEIPT` whose owner is the entry — receipt id (minted by the client), entry id as the owner, blob id, writer,
  node, scope, completion, kept and released times. Every platform duty over it is the service's (its completion and
  reads, the guest's, the link's usage, the share's blob predicate, report, block, hold, re-key, erasure, purge);
  Expenses implements the service's small contract (`ExpensesBlobOwners`, `receipt/ExpensesReceipts.kt`): the role with
  its count — 100 not released per entry — and a guest may add one, and at a completion whether the entry may hold it
  here (no other Pot's record) and where it stands (no revision yet, live, deleted). A receipt id and a record id share
  the reportable-item id space, so neither is taken by the other (`id_taken`). Expenses' dev data from before the move was
  discarded, not migrated (ADR 0112 decision 5; the reset: [infra/README.md](../../infra/README.md#expenses-receipts-moved-to-core-once-per-dev-instance)).
- **Upload** through core's transport in the role `EXPENSES_RECEIPT`, the owner the Pot's node: the `K_blob` is wrapped
  under the Pot's key bound to (blob, Pot, role), the file's type, name and time are sealed in the blob meta. The
  service's completion (`completeApplicationBlob`, member and guest alike) checks the session's binding
  (`UploadSessionEntity.boundTo`), since a blob of the service is no attachment (one blob per named slot). The cap is the
  role's: `MemberBlobRoles.maxBytes` (`ExpensesMemberBlobRoles`), asked by core's start for a member and a guest alike,
  10 MiB of plaintext as its sealed size. A guest's receipt goes through the link's gate, is recorded as a guest write,
  and counts against the link's limit — a link's usage is every application's term summed (`StorageQuota.linkUsage`,
  ADR 0073 § 4). The web's transport is core's (`core/blob/applicationBlobs.ts`); `receipts.ts` is Expenses' view of it.
- **Shown by its bytes, never by its claimed type** (`domain/receiptRules.ts`): a raster image as an image, a PDF through
  core's page renderer `src/web/src/shared/pdf/` (lifted out of Depot, which mounts the same one), anything else only
  offered for download. The picker takes only what claims to be an image or a PDF — only the client can keep the type.
- **Keep and release** are the entry's revisions (`PotRevisionStore`, in its own transaction): a revision that is no
  tombstone keeps the entry's receipts, a tombstone releases them with the entry, a later revision of a deleted entry (a
  restore) keeps them again.
- **Remove** is the entry's next revision: it lists what it keeps and names the dropped ones (`droppedReceipts`), which
  releases them by name, for good; a dropped receipt is never held again (a client uploads it anew). A revision names every
  receipt of its entry it does not keep that the edited revision held, that this writer uploaded, or that was completed
  before the edited revision (an orphan of a save that failed after its upload); another writer's newer upload is left
  alone.
- **Retention** is core's node trash retention, no key of Expenses' own. A receipt goes once it has passed since its
  release (a drop, or its entry's tombstone) or since its completion if its entry never kept it; a restore keeps the
  tombstone's releases again. The service's purge (`ApplicationBlobPurge`) reads only its own rows, judges under the
  locks every writer of a node's blobs takes, and reclaims the blobs through `veyq_store_reclaim`. A deleted entry's read
  carries its `purgeAfter`: until then it says until when it is restorable, after it no restore is offered.
- **Re-key**: each receipt's `K_blob` is re-wrapped as one of the Pot's core blobs — core's parts read lists it in its
  role, its stage takes it, the service's participant checks and writes it (ADR 0109 decision 3, ADR 0112); a receipt
  left out is `rekey_parts_missing`. Expenses' own re-key moves the revisions' `K_rev` only.
- **Report**: an entry's report carries its receipts' `K_blob`s beside its revisions' keys (all of them, up to the codec's 256 per share; an entry with more is reported through its Pot, ADR 0108 decision 6); a
  receipt is also an `ITEM` on its own, by its receipt id (the service's item); the service's hold copies the receipts
  an entry holds and pins each blob, and the case view opens each with the key the report handed, reading them through
  core's case read (`listCaseApplicationBlobs`).
- **Block**: a moderator's content block of a receipt, or of the entry that holds it, answers `451 blocked_by_operator`
  for its bytes, member and guest alike, and the listing marks it; the client lists it as blocked, with nothing to open.
  A block of the Pot or of an entry also refuses every new revision of it but a tombstone, and every new receipt for it,
  member and guest alike (ADR 0108 decision 6); the entry dialog says so.

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
| add a member of the space as a participant | ✓ | ✗ | ✗ |
| deactivate, activate, delete and remove participants | ✓ | ✗ | ✗ |
| own categories, base currency (the Pot's meta) | ✓ | ✗ | ✗ |
| create a Pot | ✓ | ✗ | ✗ |
| rename, move, delete the Pot | ✓ | ✗ | ✗ |
| share the Pot (its `READ` / `READ_WRITE` links) | ✓ | ✗ | ✗ |

A Pot's share, rename, move and delete are core's (`nodeRights`, `src/web/src/core/access.ts`), as for every kind:
Expenses switched off keeps only the delete; while the space is pending deletion nothing is written, but the share
dialog stays to see and copy the Pot's links and to revoke the caller's own live one - it mints and changes nothing
else, as the server refuses every other share write then (revoking only closes exposure, uc.spaces.scope-access A1).
A `WRITE_ONLY` link on a Pot does not exist. A deleted Pot goes to the space's trash like any node; a deleted entry
stays inside the Pot (§ Entries).

## Download and import

A Pot downloads ([ADR 0082](../decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md))
as a JSON file of its participants and entries, which an import reads back, a CSV of the entries for a spreadsheet,
and its receipts as files beside them, each under the name and type its sealed meta holds, opened on the downloading
device. History is not exported; an import starts each entry at its current revision, under new ids, a batch of at most
256 records after another, each on what the one before wrote, and uploads each receipt under a new id into the new Pot
before the batch whose revision lists it. A receipt the operator blocked is not read, so it is left out of its entry. A
deleted person comes back as a deactivated one shown as "deleted people": its name is gone, and an erasure on import
would need its balance settled first; a member can delete it again. A member participant downloads with its name sealed
at adding and, in a member's download, its user id beside the tenant the id belongs to (`memberId`, `tenantId`): an
import into that same tenant, and every copy, keeps the member; an import into another tenant or instance makes it the
name sealed with it, as a Memory's participants are never restored outside their space (ADR 0083). A link guest's
download names no tenant and carries no member id. A copy is the download and the import without the
file: it reads each receipt by frame and seals it again under a fresh key bound to the copy.

## Out of the first cut

Previews of receipts, offline use, push notices, a category per item, and rates by entry date (an entry entered
late takes the day's rate unless typed).

## Related

- End-user view (docs site): [Expenses](../../site/src/content/docs/expenses/index.mdx)
