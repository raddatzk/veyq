# UC: An entry in a foreign currency gets its rate

- **ID:** uc.expenses.exchange-rates
- **Primary actor:** Whoever writes an entry in a currency other than the Pot's base — a **member** or a
  **`READ_WRITE` guest** — and their **device**.
- **Secondary actor:** The **exchange-rate provider** the instance names.
- **Goal:** A foreign-currency entry gets a fair rate without anyone looking it up, and keeps it.
- **Preconditions:** The entry's currency differs from the Pot's base.
- **Postconditions (success):** The entry is saved with a rate to the base currency, sealed into it; a later rate
  never changes it ([ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
  decision 8). The provider learned the device's address, the instance's `Origin` and a date — no currency, amount or
  veyq identity.

## Main flow

1. The editor needs a rate (step 4 of [uc.expenses.record-entries](uc.expenses.record-entries.md)).
2. If the device holds no table for today, it fetches **the whole day's table** from the provider the instance names
   — straight from the browser, never through the backend — once per day for every Pot on the device, and keeps it on
   the device.
3. The rate from the table fills in; the actor keeps it or types another.
4. On save the rate is sealed into the entry as a decimal string.

## Alternate / exception flows

- **E1 — the fetch is switched off** (`expenses.exchange-rates`, by the Server Owner or the tenant owner, [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)),
  **or the instance names no provider:** no request is sent; the editor asks for the rate.
- **E2 — the provider cannot be reached, or its table lacks the currency:** the editor asks for the rate; saving does
  not wait for the provider.
- **E3 — an entry entered late:** takes today's rate unless typed (rates by entry date are out of the first cut).
- **E4 — an old entry is opened:** its sealed rate is shown; no fetch.

## Quality notes

- The second third-party request without opt-in, after the breached-password check, for the same reason: what
  crosses carries no content ([security-model](../../../crosscutting/security-model.md) § third-party services).
  Proxying it through the backend is refused — it would hand the operator the date of a trip.
- The privacy notice names the provider and the security model lists the fetch once it runs, not before.

## User impact

Spending abroad is counted at a real exchange rate without you looking one up, and an old entry never changes when the
rate does.

## Related

- **Feature:** [expenses](../_index.md).
- **Use-cases:** [uc.expenses.record-entries](uc.expenses.record-entries.md).
- **Decisions:** [ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
  decision 8, [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
  decision 7 (a provider's address is the Server Owner's).
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md).
