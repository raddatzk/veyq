# ADR 0108 — Expenses: a Pot is a node, its entries are sealed revisions

- Status: Accepted
- Built: yes
- Date: 2026-10-23
- Model: [Application: Expenses](../apps/expenses.md)

## Context

Expenses is the third application on the platform ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)):
shared expense tracking in the shape of Splid — people put in what they paid, the client says who owes whom. Its
domain (entry kinds, splits, balances, overviews) is [docs/apps/expenses.md](../apps/expenses.md); this record holds
what it asks of the platform, because three things in it are new there:

- **A live list of sealed records with no blob.** The one list a view keeps live through core's revisioned-list
  primitive is Memories' media, whose rows require a blob (`veyq_media.blob_id NOT NULL`); the blobless sealed rows
  that exist (a Memory's buckets, a member's favorites) are no such list. The nearest small sealed payload, a node's `node-meta`, is one per node and capped
  at 8192 base64 characters (`content.openapi.yaml`).
- **A guest that writes a record, not a blob.** Every guest write today is an upload, or taking one back
  (`ShareGuestWrites`), and a guest deletes no node that holds anything
  ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) decision 6).
- **A ledger the server can thin out.** A seal binds an entry to its own object, not to its neighbours
  ([ADR 0072](0072-a-seal-binds-its-object.md)), so the server can serve the list with an entry missing and every
  balance comes out wrong without anyone noticing ([security model](../crosscutting/security-model.md#applications-are-compiled-in--never-loaded)).

## Decision

**1. Names.** The application is **Expenses** (*Ausgaben*), code id `expenses`. What a person creates in a folder is
a **Pot** (*Kasse*), node kind `EXPENSES_POT` (the application's prefix, as `DEPOT_DROPBOX`). Never "group" — that is
the `GROUP` scope ([ADR 0086](0086-one-word-per-meaning.md)) — and never "tab", which names a UI tab throughout the
client and its docs ("Trash tab"). Rejected: *Ledger* (an accountant's word), *Kitty* (unknown outside British
English), *Tab*.

**2. A Pot is one node; its participants are records in it, its settings its sealed meta.** It lives in any folder beside Memories and
Depot files, carries its own key like every node ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)),
and is a share root with `READ` and `READ_WRITE` — never `WRITE_ONLY`, which reads nothing and so has no balance to
write against. Only a member creates one: Expenses declares no guest-creatable kind
([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) decision 6).
Deleted, it goes to the space's node trash like every node ([ADR 0061](0061-node-deletion-reversible-node-trash.md));
its deleted entries never do, they stay inside it (decision 6). The Pot's `node-meta` holds only its base currency and
its own categories, written by a member whole with the revision it read
([ADR 0077](0077-a-write-carries-the-version-it-read.md)). Its **participants are records in its list** (decision 3),
not meta: a guest adds them (decision 6), and meta is written whole with no history, so a guest who could write it could
rewrite the list without a trace. A participant is **a member or a name**. A member is added by choosing them from
the space's members, as a Memory's participants are
([ADR 0083](0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md)): the record holds their member id
and, sealed with it, their display name at the time they were added. A person without an account is a name, as before:
the friends of a trip often have none. Whoever may write adds either kind, except that a link guest adds names only —
it cannot see the space's members — which is the member's view with one function fewer, not a guest's own. A member
reads a member participant by their current display name and picture; a link guest reads the name sealed at adding,
since it has no access to the members (the residual: whoever holds the link sees that name, and a later rename does not
reach it — accepted because a guest who settles a Pot must know whom it owes, where a Memory's guest needs no
participants at all). A participant never changes kind: no later linking of a name to an account, so nobody can turn
someone else's participant into their own account and receive what another owes. An erased account leaves its
participant and its entries standing, shown as a former member. Who *wrote* a revision is a separate fact and stays
the platform's (decision 6). Rejected: **names only**, which the first version built — it leaves a member's payment
details (inbox #551) and a reminder (inbox #782) nobody to reach; **linking a name to an account later** — it lets a
writer link someone else's participant to their own account.

**3. An entry — an expense, a refund, a transfer or a participant — is a record with revisions, in Expenses' own
table — not a node, and never overwritten.** The server cannot tell the four apart. The client
mints an entry's id ([ADR 0072](0072-a-seal-binds-its-object.md) decision 3). Every change is a new **revision** row
of that entry: the server takes revision `n + 1` only where `n` is the entry's latest and answers anything else
`409 stale_revision`, so two concurrent edits never overwrite each other silently. A delete is a revision too, with a
server-visible tombstone flag (the server needs it for decision 6's purge). Revision rows stay as long as the Pot does:
the history of every entry, with who wrote each revision, is what makes decision 5 safe. The one exception is a deleted
participant (decision 6): a member writes a sealed **erasure** revision on its record, and the server then erases that
record's earlier revisions, so its name and every earlier spelling of it are gone from storage; the erasure is a line of
the erasure ledger, so a restored backup erases them again ([ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md)
decision 8, GDPR Art. 17). Entries name a
participant by its id only, so none of them changes. The list is published with
core's revisioned-list primitive ([docs/apps](../apps/README.md) § How an application publishes a revisioned list).

**4. Each revision has a key of its own, `K_rev`, and is sealed under a new field purpose, `expense-entry`,** with a
field key derived from `K_rev` ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ 9's envelope, `K_rev` in the place of the parent key); `K_rev` is random and wrapped under the Pot's `K_node` by a
second purpose, `expense-entry-key`, bound to (scope, node, entry, revision) — the shape of a `K_blob` under its node.
Both vectors go into `contracts/crypto/vectors/v1.json` (additive, ADR 0072 Consequences). So a re-key after a rotation
([ADR 0109](0109-a-scope-rotation-re-keys-every-node-below-it.md)) re-wraps each `K_rev` and never re-encrypts a body
(that ADR's decision 3, applied to revisions): every body and its digest (decision 5) stay byte-identical, and the server
refuses a re-key that changes one, so not even the owner who re-keys can rewrite the history. Rejected: a field key
derived from `K_node` directly, with the digest taken over the plaintext — one purpose less, but every re-key rewrites
every body, and its owner could rewrite the history undetected. Its plaintext is padded to fixed size classes, so an itemized restaurant bill does not stand out from a
single taxi ride, and the contract caps a revision at 16 KiB.

**5. Each revision names the revisions its writer had seen.** Its plaintext carries the current **heads** of the Pot as
its writer read them (entry id, revision, SHA-256 of that revision's body ciphertext, which no re-key changes); the revisions form a graph in which
two concurrent writes share a parent and the next write names both — the shape of git's commits, not a sequence.
On every read the client checks that each named revision is present and matches its digest, and says so where one is
missing. That catches the server dropping any revision somebody wrote after; it cannot catch the newest revisions
being held back, which looks exactly like a stale read — the platform's freshness limit
([ADR 0072](0072-a-seal-binds-its-object.md) decision 5, inbox #161), stated, not closed. No signature is needed: the
seal under the Pot's key already proves a revision came from a key holder, and the server cannot forge a reference.
A named revision may be missing only where a sealed erasure of its record covers it (decision 3); the server cannot
write one, so it cannot use one to hide anything.

**6. Members and `READ_WRITE` guests create, edit and delete entries alike** — Expenses' interpretation of
`READ_WRITE` for its kind (ADR 0080 decision 6), and a share stays the member's view with functions removed. A guest
may change and delete another person's entry, unlike a Memory's media, because in a ledger forbidding it protects
nothing: whoever may write can cancel any entry with a counter-entry. What protects is **that nothing is lost**, the reason
ADR 0080 decision 6 refuses a guest's node delete: an edit or a delete is a revision, every revision stays
readable with its writer, a deleted entry is shown in the Pot's own deleted list and restorable for the platform's
trash retention ([ADR 0061](0061-node-deletion-reversible-node-trash.md)), and no guest action erases anything at once.
**Participants** follow the same shape: whoever may write adds and renames one, and the rest is a
member's. A member **deactivates** a settled participant (entries keep the name; new entries and the overviews no longer
offer it; a member activates it again), **deletes** a settled one (entries show it as one merged "deleted people", and
its name is erased, decision 3), or **removes** one that no revision of any entry, live or deleted, names (it is
erased and appears nowhere). The server cannot see which of the reversible steps a revision is, so those rules are the
client's, and a revision that breaks them is visible in the history like any other. An erasure is the one write that
removes something, so it is a server-visible operation the server takes from a member only.
There is no extra permission; the server knows each revision's writer (a member's user id or a link identity, the
pair core's attachments record), so "only one's own entries" could be enforced later without the server learning more.
A member reads the history with each writer's name; a guest reads every revision and its time but no writer's name,
because a link is served no name: no guest's ([ADR 0030](0030-contributor-public-share.md) point 8) and no member's
([ADR 0083](0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md) decisions 7 and 8 — a Memory's
uploader and Depot's guest file listing stay unnamed for a guest the same way).
A guest's revision and receipt are guest writes like any other: behind core's guest gate, in the guest-write record
([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 10), and reportable
with a hold copy. A report of an entry reaches the whole entry — every revision's `K_rev` and every receipt's `K_blob`
— and a receipt can also be reported on its own; both are an `ITEM`. One report share carries a bounded number of keys
(256, the report codec's), and an entry's share carries all of them: an entry with more revisions and
receipts than that is not reported as an item at all — the dialog offers its Pot instead, a `NODE` whose key reaches
every revision and receipt in it, as it offers the enclosing folder above 100 items (ADR 0093 § 1). So no report of an
entry leaves part of it out; the hold copies every revision row either way. A moderator's content block of the Pot (or a
node above it), or of one entry, refuses every revision of what it reaches but a tombstone, and every receipt for it,
from a member and a guest alike (`451 blocked_by_operator`) — ADR 0093 § 6's "deleted, never replaced or
restored": a tombstone is the record's deletion, and an erasure is a removal, so neither is refused.

**7. Receipts are images or PDFs of at most 10 MiB, up to 100 per entry, in Expenses' own table.** Core's
attachments hold one blob per named slot of a node (`ux_attachment_node_slot`), so a receipt is a row of Expenses'
(receipt, entry, blob, writer), filled through core's `UploadCompletion` as Memories' media are; its `K_blob` is wrapped
under the Pot's key and bound to the blob, the node and the role as Depot's is, so it needs no new blob purpose. The
server enforces the size, which it sees; only the client can enforce the type, which is sealed — an honest client
offers anything else for download and never renders it. No previews are made. A revision that drops a receipt names it
to the server, and a receipt nothing holds any more, or whose entry is deleted, is erased after the trash retention.
The size cap is the role's, a seam core's upload start asks of every application, never a size core names for one; the
retention is core's node trash retention, with no key of Expenses' own; a link's storage usage sums every
application's term, so a folder link holding a Memory and a Pot holds one limit for both, never one per application
([ADR 0073](0073-storage-quota-belongs-to-whoever-pays-for-the-store.md) § 4); and the PDF renderer is core's, since an
application may not import another ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)). The
mechanics are [docs/apps/expenses.md](../apps/expenses.md) § Receipts.

**8. Exchange rates are fetched automatically, straight from the client — the second third-party request without
opt-in.** Where a Pot uses a currency other than its base, a device fetches **the whole day's table** of an
exchange-rate provider once per day, one table for every Pot on that device: the provider learns the client's address, the `Origin` (the instance's domain)
and a date — no currency, no amount, no veyq identity. That is the breached-password check's justification
([security model](../crosscutting/security-model.md) § third-party services): what crosses carries no content, so
consent per space ([ADR 0016](0016-geocoding-provider-neutrality.md)) would guard nothing. Never through the backend,
which would hand the operator the date of a trip. The rate an entry used is sealed into it and can be typed by hand,
so a later table never moves an old balance and a device without the provider still works. The provider is
configurable, and the operator and a tenant owner switch the fetch off as `expenses.exchange-rates`
([ADR 0088](0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)). A new instance starts with
**Frankfurter** (open source and self-hostable, serving the ECB's reference rates), as the breached-password check starts
with HIBP; a mirror can replace it. **Only the ECB's reference rates:** the seeded address is Frankfurter's ECB
endpoint, the ECB's own euro reference rates, never a table that blends many sources and carries non-ISO rows. The
ECB's terms let its rates be reused with the ECB cited as the source, so the editor says a filled rate is computed from
the ECB's reference rates of the rows' date, and the privacy notice names Frankfurter as the service the browser
contacts and the rates as the ECB's. Whatever address the Server Owner sets must serve the same table (a mirror, a
self-hosted Frankfurter). A currency the table lacks is typed, never offered from another source. **The tenant's
switch reaches a link guest too:** a guest's client holds only the instance's manifest, so its Pot read carries the
table's address exactly while the entry is active for the Pot's tenant, and a member's client reads its tenant's set —
neither fetches where that tenant's privacy notice names no fetch. The endpoint and the fetch are
[docs/apps/expenses.md](../apps/expenses.md) § Exchange rates.

**9. Everything that reads amounts runs in the client**: balances, the settle-up suggestion, the overviews. The entry
format is versioned from its first revision (ADR 0079 decision 7), and its rounding rule is part of the version, so
every device computes the same balance from the same entries. An expense's (a refund's) amount is the sum of its payers'
(receivers') amounts, sealed beside them and never typed. Its allocation is **one shape**: items, each an exact amount
with **its own split**, and the **rest** — the amount less the items — with a split of its own, sealed while the rest is
above zero (a new entry has no items: its whole amount is the rest, split equally among the Pot's active participants,
each named in the split). A split is equal, by whole shares, or by percent in basis points that add up to exactly 10000
(100.00 %): integers, so no percent is ever a rounded decimal. **The rounding rule:** the total is converted once into
the base currency, then allocated to the parts — each item in its order, then the rest — by their amounts, and each
part's base amount among its split's people by their weights (1, the shares, the basis points); every step is the
largest remainder in minor units, ties by join order. In the base currency each part gets exactly its own amount, so an
item's shares always add up to the item. The schema states the rule and `contracts/expenses/v1-vectors.json` pins it.

## Consequences

- **What the operator sees, beyond the node's kind:** per Pot the number of entries, of revisions per entry and of
  receipts per entry; every revision's time, size class and writer (a member or a link identity); which entries are
  deleted; which records were erased; receipt sizes. Not who owes whom, no amount, no participant, no category, no currency.
- **New on the platform:** the first blobless record list (decision 3), the first guest write that is a record
  (decision 6), two seal purposes (decision 4), the second record type with its own blobs after media (decision 7). A
  generic "record with attachments" primitive in core waits for a third concrete user (Inventory is a concept).
- **The instance privacy notice** (GDPR Art. 13) names the rate provider
  in a passage of its own (`exchange-rates`, beside the breached-password check's, switched by the entry), and the
  security model lists the fetch beside the breached-password check; the mechanics are in
  [docs/apps/expenses.md](../apps/expenses.md) § Exchange rates.
- **Out of the first cut** ([docs/apps/expenses.md](../apps/expenses.md) § Out of the first cut): each is additive
  under the versioned format.

**Alternatives rejected.**

- **An entry as a node** (a child kind of the Pot). Core would give it a key, trash and attachments for free, but every
  entry would be tree structure, and a guest creating one is an application node a guest creates (refused, decision 2).
- **An entry overwritten in place** with a compare-and-set, as a medium's meta. Simplest, and it lets anyone with a link
  rewrite an amount with no trace — the one thing decision 6 rests on.
- **The whole Pot as one sealed document.** Every write would conflict with every other, and it outgrows `node-meta`'s
  cap within a holiday.
- **No graph, added later behind the versioned format.** Possible, but every entry written before it stays unverifiable
  for the Pot's life; now it costs about fifty bytes per revision.
- **A numbered sequence instead of a graph.** Two people adding at once both write number 17.
- **Rates proxied through the backend** (fewer CSP origins): hands the operator when people spend abroad.
- **A split mode per entry** (equally, by shares, by exact amounts, or line items whose tip followed each person's items
  or was shared equally), rounded once over each person's summed weights. Four shapes answered one question, the typed
  total had to match its payers by hand, and one bill could not split the wine by shares and the rest equally; each mode
  is one case of items plus a rest (exact amounts are one item per person). Rounding once kept a person within a cent
  of their exact share over the whole entry, but an item's shares no longer added up to the item a person can read on
  the bill; per part, a person on several parts can be a cent off per part instead.
