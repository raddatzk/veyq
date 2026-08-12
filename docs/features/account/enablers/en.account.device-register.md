# Enabler: The sealed device register — a stable device id, its label, and the family join

- **ID:** en.account.device-register
- **Type:** **Enabler** — the client-side substrate that lets
  [ADR 0004](../../../decisions/0004-session-tokens.md)'s deliberately **unnamed** session families be shown
  with names, without giving the operator anything to correlate
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
- **Provides:** a **stable, client-local device id**, a per-device **display label** the user can rename, and
  the **device↔session-family join** — all inside **one Master-Key-sealed blob** the server stores and relays
  but cannot read.
- **Serves (use-cases):** [uc.account.sessions](../use-cases/uc.account.sessions.md) (the sessions view labels
  its rows from this register; the set and the revoke never depend on it).
- **Depends on:** [en.account.key-model](en.account.key-model.md) — the Master Key that seals the register, so
  the account must be **unlocked** for any of this to run.
- **Postconditions:** after an unlock that follows a login, the account's register knows this device (id +
  label) and knows which session family this login created. The server saw one opaque ciphertext and a
  monotonic counter — never a device name, never a device↔family link.

## Why it exists at all — and why nothing else could carry it

A session family is the auth layer's unit; it is created by a **login**, and at login there is no symmetric
key of any kind, because auth ≠ unlock ([en.account.key-model](en.account.key-model.md)). So a name can never
be attached during the call that creates the thing it names. That alone forces the name off the family.

The stronger reason is linkability. An earlier design stored a client-encrypted name **on the family**; a name
blob re-sent unchanged across logins lets a blind operator **re-link** a device's re-logins, re-joining
families that are otherwise unlinkable. That design therefore carried a standing obligation to re-encrypt with
a fresh nonce every single login. Keeping the label in a per-account register **removes the mechanism instead
of guarding it**: with no per-family ciphertext there is nothing to correlate. This is why a per-family name
field must not come back, however convenient it looks — the rule is stated at the schema in
`contracts/api/auth.openapi.yaml` and enforced by a guard (below).

**And a device outlives its logins.** Naming the device rather than each family is one write instead of one
per login, and the name survives the family it was written during.

## What it is — one sealed blob, the identity-pins shape

A single **register blob**, sealed under the Master Key (`symWrap(masterKey, blob, "device-register")`) and
synced exactly like the pin store — the server stores + relays **one opaque ciphertext** plus a cleartext
monotonic `version`, and never interprets either
([en.account.identity-pinning](en.account.identity-pinning.md) is the same shape, and the shape is deliberately
copied rather than re-invented). The plaintext is two maps:

```
{
  devices:  { <deviceId>: { label, lastSeenAt } },
  families: { <familyId>: <deviceId> }
}
```

- **`deviceId`** — an opaque random id **minted and kept by the client**, never derived from anything the
  server issued. It must not be the `wrapId`: switching trust level, and even re-enrolling at the *same*
  level, mints a brand-new server wrap id ([ADR 0005 §9](../../../decisions/0005-unlock-methods-and-device-trust.md)),
  so a `wrapId` is a handle to a wrap and never an identity for a device.
- **`label`** — the display name. Seeded best-effort, renamed by the user (below).
- **`lastSeenAt`** — when this device last reconciled the register. It exists for one job only: bounding the
  register's growth (below). It is **not** shown to the user — the sessions view shows the *server's*
  timestamps, which are the authoritative ones.
- **`families`** — the join that lets a session row find its device. This is what makes a name render at all.

**The `version` lives on the wire, not in the plaintext** — it is the optimistic-concurrency counter the
server compares, so it must be readable by a server that cannot open the blob.

**Two maps, not one, because they decay at completely different rates.** A family dies at every logout,
expiry and revoke; a device persists across all of them. Folding the join into the device entry would tie the
device's lifetime to its most recent family — which is exactly the bug the register exists to avoid.

## The two-layer rule this register lives under

The register is an **annotation layer over a server-authoritative set**, never the set itself. The server
decides which families exist; the register only says what to call them. Three consequences, and every rule
below is a restatement of one of them:

- **Anything the register does not know still renders, and is still revocable** — a pre-feature device, a
  write that never landed, a device that has not logged in since the feature shipped. Silence in the register
  must never remove a row from the list, or a lost device could hide from the very screen that exists to find
  it.
- **A stale annotation is harmless.** It names nothing, because nothing renders it; it is removed the next
  time a client reconciles.
- **Reading the register needs an unlock; the security action does not.** A locked client shows the complete
  list with dates in place of names, and every revoke still works. The names are the cosmetic half and are
  gated as such — see [uc.account.sessions](../use-cases/uc.account.sessions.md) § Two layers, one list.

## The device id — provenance and lifetime

The id is minted lazily on first need, as an opaque random value, and stored in **client-local, non-secret
browser storage** — deliberately **not** in any of the IndexedDB stores that hold key material. Two
requirements decide that placement, and both are about what already wipes those stores:

- A boot with no live session drops both local convenience tiers, and an instance setup drops them wholesale.
  If the device id lived alongside them, **every session expiry would mint a new device**, and the register
  would fill with one entry per expiry while every real device kept losing its name.
- The id is not key material and authorises nothing. It is an opaque label-key that only ever leaves the
  client **sealed inside the register**. Storing it beside the non-secret client preferences is therefore the
  honest classification, not a downgrade.

**A private window has no device id that outlives it** — its whole storage partition is discarded when the
last such window closes. So each private session is genuinely a **new device** to the register, and that is
correct rather than a defect: it *is* a different, disposable environment. The cost is register growth, and
the growth bound below is what pays for it.

**Tier-consistent ([ADR 0026](../../../decisions/0026-client-trust-tiers.md)).** A malicious operator serving
the client could read the device id — but at that point it can read the Master Key, so this adds no new class
of exposure. The register's guarantee is against a **blind** operator, which is the one this enabler is for.

## The best-effort label, and renaming

A browser cannot read a machine name. The initial label is therefore a **browser + platform guess** derived on
the device it names — never a device identity, and never something the server supplies. Because the label
lives in a user-owned register rather than on a per-login family, the user can **rename it**, so two
identically-guessed laptops are a starting point rather than a dead end.

Renaming happens **where the devices are seen side by side** — the sessions view — because that is where a
wrong or duplicate name actually costs something. The copy must be explicit that a **device** is being
renamed, not a session: the two are one-to-many, and a rename reaches every session of that device at once.

**A label is written by the device it names.** So a device that has not unlocked since this feature shipped
has no entry and renders unnamed until it next signs in — see the two-layer rule above. That is the honest
cost of never letting the server hold a name.

## Sync & merge — union is safe here, and exactly why

The blob carries a monotonic **`version`**; a write is optimistic-concurrency, and a write whose version
moved is refused so the client re-reads, merges and retries. The merge is a **union**, the same as the pin
store's — and the reason it is safe here is worth stating precisely, because the obvious objection is
correct in general: with devices, **deletion is real**, and a union-merge would ordinarily resurrect a
removed one.

It does not, because **every removal in this register is *derived*, never a user's intent.** The server's
family list is the authority; a client that prunes is only recomputing what the server already decided. So a
resurrected row is self-healing — it names nothing, and the next reconcile removes it again.

That safety holds only under one discipline, and it is the load-bearing rule of this whole enabler:

> **A removal whose truth is EXTERNAL is applied as a frozen id-delta. A removal whose truth is INTERNAL is
> recomputed on the merged state.**

- **Pruning a family row** is external: its truth is the server list, snapshotted at the moment it was read.
  So the prune is expressed as *"remove exactly these ids"* — the ids this client saw and the server did not
  list. Never as *"keep only what my list had"*: a family created by **another device between our read and our
  write** is absent from our list without ever having been dropped, and the second formulation deletes it.
- **Evicting a device entry** is internal: its truth is the register's own contents. So eviction is
  **recomputed inside the retry**, against the freshly merged map, never frozen from the pre-merge one.

A same-device label conflict resolves to the **later write** — a rename is a deliberate act, and the person
who renamed last meant it.

**Therefore this register offers no manual "forget this entry".** That would be the one genuinely
*user-intended* deletion, and union-merge would resurrect it from any concurrent device. Adding it later
requires tombstones, which is a real design step and not a small one — so it is deliberately absent rather
than accidentally missing.

### Growth is bounded by eviction, not by immediate deletion

Family rows are pruned as soon as a client sees the authoritative list. **Device entries are kept**, because a
device whose sessions have all expired is still a device the user named — dropping it there would silently
revert a rename every time a laptop sat unused. Growth is instead bounded by keeping only the **N
most-recently-seen** devices and evicting the rest, with N set comfortably above the per-method wrap ceiling
so a real device is never evicted by a limit meant for a different thing. A wrong N costs a **label**, never a
row and never a revoke — which is why an approximate bound is the right instrument here.

## Write moments — two, both idempotent, both best-effort

There is no write queue and no retry ledger, because the surface that displays the register is also the
surface that repairs it.

1. **After an unlock that followed a login** — ensure the device id exists, upsert this device (keeping any
   existing label, refreshing `lastSeenAt`), and record the current family against it. Purely additive, so
   union-merge is trivially correct. **Note it is the unlock, not the login:** the blob is Master-Key-sealed,
   and at login there is no Master Key.
2. **When the sessions view opens unlocked** — it holds the authoritative list, including the server-set
   marker for the caller's own family. So it can add a current family the first write missed, apply the
   external prune, recompute eviction, and write only if something changed.

A failed write is therefore never an error the user must see: the affected rows render unnamed, and the next
open of the sessions view repairs them. That is the same degradation
[uc.account.sessions](../use-cases/uc.account.sessions.md) § E4 already accepts, reached by design rather than
by accident.

## Quality notes

- **Operator-blind.** One Master-Key-sealed ciphertext and one integer. The server learns no device name, no
  device count (it already knew the *family* count), and above all **no device↔family link** — which is the
  one it must not learn, because it would newly cluster N anonymous families into per-device groups, exactly
  what ADR 0004's unnamed families exist to prevent.
- **The join is client-side because nothing server-side needs it.** All wraps are served in one response and
  **no endpoint reads or uses a single wrap**, so unwrapping is already client-only. A server-side link would
  be a new fact about the account, not a convenience.
- **This is a naming layer, not an access-control one.** Nothing here gates anything. A register that is
  missing, stale, unreadable or empty degrades to unnamed rows and changes no permission, no revoke and no
  key.
- **The register does not cover wraps — yet.** "Cut this device off completely" (its session **and** its
  convenience wrap) needs a device↔**wrap** join, which is a separate increment. Until it exists, the
  register can name a device but cannot fully retire one, and reclaiming a wrap whose device is gone has no
  handle at all: the wrap table carries **no timestamps**, so there is not even an age heuristic to fall back
  on.

> **No end-user section.** As an enabler this has no standalone "what this means for you" story — its value
> reaches the user through [uc.account.sessions](../use-cases/uc.account.sessions.md), whose bilingual section
> carries the wiki text.

## Related

- Decisions: [ADR 0004](../../../decisions/0004-session-tokens.md) (session families, and why a name may not
  live on one), [ADR 0005](../../../decisions/0005-unlock-methods-and-device-trust.md) (one convenience wrap
  per device, and why its id is not a device identity),
  [ADR 0026](../../../decisions/0026-client-trust-tiers.md) (what a served client can be trusted with)
- Concepts: [en.account.key-model](en.account.key-model.md) (the Master Key that seals the register; auth ≠
  unlock), [en.account.identity-pinning](en.account.identity-pinning.md) (the sealed-synced-blob shape this
  copies, down to the optimistic-concurrency retry)
- Use-cases: [uc.account.sessions](../use-cases/uc.account.sessions.md) (the surface that reads, writes and
  repairs the register), [uc.account.trust-device](../use-cases/uc.account.trust-device.md) +
  [uc.account.remember-this-device](../use-cases/uc.account.remember-this-device.md) (the wraps a device
  carries, which this register deliberately does not yet join)
