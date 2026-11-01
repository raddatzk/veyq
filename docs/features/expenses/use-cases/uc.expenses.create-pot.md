# UC: Create a Pot, set it up, delete it

- **ID:** uc.expenses.create-pot
- **Primary actor:** A **member** of a space who may create nodes in a folder (or at the space's root).
- **Goal:** Start a Pot for a trip, a flat or an evening, with the currency everyone's share is counted in and the
  people whose money it is.
- **Preconditions:** The space exists; Expenses is not switched off for the instance or the tenant
  ([uc.platform.switch-application-off](../../platform/use-cases/uc.platform.switch-application-off.md)).
- **Postconditions (success):** An `EXPENSES_POT` node in the folder, with its own key under the folder's
  ([ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)); its sealed
  `node-meta` holds the format version, the base currency and the Pot's own categories; each named participant is a
  record of the Pot at its first revision ([ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
  decisions 2 and 3). The server saw a node of kind `EXPENSES_POT` and the number of records.

## Main flow

1. The member chooses **New → Pot** in a folder (core's create menu, which lists every application's create entry).
2. The dialog asks for a **name**, the **base currency** (preset from the reader's locale) and the **participants**,
   in one field: typing suggests the space's members whose display name holds the text (case and accents aside), and
   last the text itself as a name; each one added is a chip that can be taken out again before creating. Text still in the field when the Pot is
   created is taken too: the member whose whole name it is (case and accents aside, and only one), else a name. The rules
   are those of [uc.expenses.manage-participants](uc.expenses.manage-participants.md): a member at most once, its
   record holding the member's id and their display name sealed at adding; a name at most once (case aside), never a
   blank one. Where the member list cannot be read, the dialog takes names only.
3. The client creates the node through core's create with its sealed name and meta, then appends one participant
   record per person, in the order added, in one write.
4. The Pot opens: its participants, no entries, a zero balance each, and an empty overview.
5. **Settings** (a member's): the Pot's own categories are added and renamed beside the default set
   ([docs/apps/expenses.md](../../../apps/expenses.md) § The Pot); the base currency is changed while no entry exists.
   Each save writes the whole meta with the revision it read
   ([ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md)).
6. **Delete:** the member deletes the Pot like any node; it goes to the space's trash with every entry and receipt in
   it, and a restore brings it back whole ([ADR 0061](../../../decisions/0061-node-deletion-reversible-node-trash.md)).

## Alternate / exception flows

- **E0 — Expenses is switched off:** no Pot is offered or created; existing Pots are read-only — they open, show their
  balances and download, and take no write.
- **E1 — the participants' append fails after the node was created:** the Pot opens with no participants and offers to
  add them (step 3 of [uc.expenses.manage-participants](uc.expenses.manage-participants.md)); nothing is lost.
- **E2 — the base currency once an entry exists:** shown, not editable — every entry carries its rate to it.
- **E3 — a stale settings save:** the meta moved since it was read; the server refuses with the current revision, and
  the dialog reloads it and asks again.
- **E4 — a guest:** no create; Expenses declares no guest-creatable kind (ADR 0108 decision 2).

## Quality notes

- The default categories are ids the client translates; a Pot's own are names in its sealed meta. Neither reaches
  the server in the clear.
- The meta holds no participant: a participant is a record with history, because a guest adds them
  (ADR 0108 decision 2).

## User impact

You can start a shared expense list for a trip or a household in a few taps, and nobody but the people in it can read
what it holds.

## Related

- **Feature:** [expenses](../_index.md); the domain [docs/apps/expenses.md](../../../apps/expenses.md).
- **Use-cases:** [uc.expenses.manage-participants](uc.expenses.manage-participants.md),
  [uc.expenses.share-pot](uc.expenses.share-pot.md).
- **Decisions:** [ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
  decisions 1 and 2.
- End-user view (docs site): [Expenses](../../../../site/src/content/docs/expenses/index.mdx)
