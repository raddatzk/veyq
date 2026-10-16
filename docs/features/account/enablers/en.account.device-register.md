# Enabler: The sealed device register — a stable device id, its label, and the family + wrap joins

- **ID:** en.account.device-register
- **Type:** **Enabler** — the client-side substrate that lets
  [ADR 0004](../../../decisions/0004-session-tokens.md)'s deliberately **unnamed** session families be shown
  with names, without giving the operator anything to correlate
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
- **Provides:** a **stable, client-local device id**, a per-device **display label** the user can rename, the
  **device↔session-family join**, and the **device↔wrap join** that lets one device retire another's
  convenience wrap — all inside **one Master-Key-sealed blob** the server stores and relays but cannot read.
- **Serves (use-cases):** [uc.account.sessions](../use-cases/uc.account.sessions.md) (the sessions view labels
  its rows from this register; the set and the revoke never depend on it).
- **Depends on:** [en.account.key-model](en.account.key-model.md) — the Master Key that seals the register, so
  the account must be **unlocked** for any of this to run.
- **Postconditions:** after an unlock that follows a login, the account's register knows this device (id +
  label), which session family this login created, and which convenience wrap this device holds. The server saw
  one opaque ciphertext and a monotonic counter — never a device name, never a device↔family or device↔wrap
  link.

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
monotonic `revision`, and never interprets either
([en.account.identity-pinning](en.account.identity-pinning.md) is the same shape, and the shape is deliberately
copied rather than re-invented). The plaintext is three maps:

```
{
  devices:  { <deviceId>: { label, lastSeenAt } },
  families: { <familyId>: <deviceId> },
  wraps:    { <wrapId>:   <deviceId> }
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
- **`wraps`** — the join that lets one device retire another's convenience wrap. A wrap id is known **only to
  the device that minted it** (it comes back from the add-wrap call and is kept in that device's local trust or
  remember record), so no other client could ever derive this mapping; the register is the only place it can be
  shared, and it is shared sealed.

**The `revision` lives on the wire, not in the plaintext** — it is the optimistic-concurrency counter the
server compares, so it must be readable by a server that cannot open the blob.

**Three maps, not one, because they decay at completely different rates.** A family dies at every logout,
expiry and revoke; a wrap dies at every re-enrolment, including re-enrolling at the *same* tier
([ADR 0005 §9](../../../decisions/0005-unlock-methods-and-device-trust.md)); a device persists across all of
them. Folding either join into the device entry would tie the device's lifetime to its most recent family or
wrap — which is exactly the bug the register exists to avoid. It also keeps a device entry **single-writer**:
only the device it names ever writes it, so a cross-device act like retiring a wrap never has to rewrite
somebody else's entry.

## The two-layer rule this register lives under

The register is an **annotation layer over server-authoritative sets**, never the sets themselves. The server
decides which families and which wraps exist; the register only says what to call them and which device they
belong to. Three consequences, and every rule below is a restatement of one of them:

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

The blob carries a monotonic **`revision`**; a write is optimistic-concurrency, and a write whose revision
moved is refused so the client re-reads, merges and retries. The merge is a **union**, the same as the pin
store's — and the reason it is safe here is worth stating precisely, because the obvious objection is
correct in general: with devices, **deletion is real**, and a union-merge would ordinarily resurrect a
removed one.

It does not, because **every removal in this register is *derived*, never a user's intent.** The server's
family and wrap lists are the authority; a client that prunes is only recomputing what the server already
decided. So a resurrected row is self-healing — it names nothing, and the next reconcile removes it again.

That safety holds only under one discipline, and it is the load-bearing rule of this whole enabler:

> **A removal whose truth is EXTERNAL is applied as a frozen id-delta. A removal whose truth is INTERNAL is
> recomputed on the merged state.**

- **Pruning a family row** is external: its truth is the server list, snapshotted at the moment it was read.
  So the prune is expressed as *"remove exactly these ids"* — the ids this client saw and the server did not
  list. Never as *"keep only what my list had"*: a family created by **another device between our read and our
  write** is absent from our list without ever having been dropped, and the second formulation deletes it.
- **Pruning a wrap row** is external in exactly the same way, against the served key material rather than the
  session list, and takes the identical frozen-id-delta shape for the identical reason — a wrap another device
  minted between our read and our write must survive.
- **Evicting a device entry** is internal: its truth is the register's own contents. So eviction is
  **recomputed inside the retry**, against the freshly merged map, never frozen from the pre-merge one.

A same-device label conflict resolves to the **later write** — a rename is a deliberate act, and the person
who renamed last meant it.

### A user-intended removal, and the only shape of it that is safe here

"Cut this device off completely" is a genuine **intent** to remove something, which is what the rule above says
union-merge cannot carry. It is nonetheless safe, and the reason is a sharpening of the same rule rather than an
exception to it:

> A user-intended removal is safe under union-merge **iff the intent is executed against the server-authoritative
> set first, and the register edit only follows from re-reading that set.**

Retiring another device's wrap qualifies: the intent *is* the `DELETE` on the wrap endpoint, and the join row's
disappearance is derived from the next read of key material — so a concurrent device that resurrects the row has
resurrected an annotation naming a wrap that no longer exists, and the following reconcile drops it again. Two
disciplines fall out, and the second is easy to get wrong:

- **Never prune a join row from your own DELETE's success — only from a re-read of the server's list.** Pruning
  optimistically would make the removal *internal* again (its truth would be "I asked", not "the server no longer
  lists it"), and it would destroy the handle a half-succeeded escalation has to be retried from.
- **The join is never on an unlock path.** A device finds its own wrap by the id in its *local* record, never
  through the register — so pruning a join row can never make a client lose access, and a blind operator that
  withholds a wrap devalues an annotation rather than destroying key material. That is what keeps this
  compatible with anti-withholding
  ([uc.account.trust-device](../use-cases/uc.account.trust-device.md) E1) instead of colliding with it.

**Therefore this register still offers no manual "forget this entry".** That would be the removal with *no*
server act behind it — nothing makes it true but the edit itself, so union-merge resurrects it from any
concurrent device and nothing ever heals it. That one needs tombstones, which is a real design step and not a
small one, so it stays deliberately absent rather than accidentally missing. The distinction is the whole rule:
not *intended vs. derived*, but **whether the intent was made true somewhere the register can re-read.**

### Growth is bounded by eviction, not by immediate deletion

Family and wrap rows are pruned as soon as a client sees the authoritative list they belong to, and evicting a
device takes its dependent family **and wrap** rows with it. **Device entries are kept**, because a
device whose sessions have all expired is still a device the user named — dropping it there would silently
revert a rename every time a laptop sat unused. Growth is instead bounded by keeping only the **N
most-recently-seen** devices and evicting the rest, with N set comfortably above the per-method wrap ceiling
so a real device is never evicted by a limit meant for a different thing. A wrong N costs a **label**, never a
row and never a revoke — which is why an approximate bound is the right instrument here.

## Write moments — three, all idempotent, all best-effort

There is no write queue and no retry ledger, because the surface that displays the register is also the
surface that repairs it.

1. **After an unlock** — ensure the device id exists, upsert this device (keeping any existing label,
   refreshing `lastSeenAt`), record the current family against it, and record the wrap id this device holds in
   its local trust or remember record. All purely additive, so union-merge is trivially correct. **Note it is
   the unlock, not the login:** the blob is Master-Key-sealed, and at login there is no Master Key.
2. **Right after enrolling a convenience wrap** — the *only* moment the new wrap id exists anywhere, since
   every enrolment mints a fresh one. Without this write the register would trail a session behind, and the
   device that most needs naming — one signed into exactly once, on someone else's machine — would be the one
   it could not name. The wrap modules stay ignorant of the register: enrolment **returns** its new id and the
   caller records it.
3. **When the sessions view opens unlocked** — it holds the authoritative family list, including the server-set
   marker for the caller's own family, and fetches the account's key material for the authoritative wrap list.
   So it can add a current family the first write missed, apply both external prunes, recompute eviction, and
   write only if something changed.

Moment 1 is what makes the wrap join self-healing: a device re-asserts its own row from local state on every
unlock, so a row lost to a prune, a failed write, or an operator that briefly withheld the wrap comes back
without anyone noticing it was gone.

A failed write is therefore never an error the user must see: the affected rows render unnamed, the escalation
that depends on them is simply not offered, and the next unlock or open of the sessions view repairs them. That
is the same degradation [uc.account.sessions](../use-cases/uc.account.sessions.md) § E4 already accepts, reached
by design rather than by accident.

## Quality notes

- **Operator-blind.** One Master-Key-sealed ciphertext and one integer. The server learns no device name, no
  device count (it already knew the *family* and *wrap* counts), and above all **no device↔family and no
  device↔wrap link** — which are the ones it must not learn, because either would newly cluster N anonymous
  families or wraps into per-device groups, exactly what ADR 0004's unnamed families exist to prevent. This is
  also the shortcut the wrap join invites and must not take: a cleartext wrap-id list on the register's wire
  shape, or a `deviceId` on the wrap itself, would hand the operator that clustering while everything kept
  working. Both wire shapes therefore owe a guard rather than this paragraph — a prose rule about a field nobody
  adds on purpose is not a defence. `scripts/validate_session_family_shape.py` is the built precedent, one
  schema over.
- **The join is client-side because nothing server-side needs it.** All wraps are served in one response and
  **no endpoint reads or uses a single wrap**, so unwrapping is already client-only. A server-side link would
  be a new fact about the account, not a convenience.
- **This is a naming layer, not an access-control one.** Nothing here gates anything. A register that is
  missing, stale, unreadable or empty degrades to unnamed rows and a missing escalation, and changes no
  permission, no revoke and no key. **Degrading the view is not reading it as empty:** a register served older
  than one this device has held, missing after it held one, or one that does not open is refused
  ([ADR 0028](../../../decisions/0028-identity-authenticity-tofu-verification.md) Decision 7) — no write re-seals
  over it, so it does not repair itself on the next unlock, and the sessions view names the refusal and links
  to the way back: the user starts it over, empty, through the settings hub's confirmed *Start a list over* —
  the pin store's mechanism ([en.account.identity-pinning](en.account.identity-pinning.md) § Sync & merge), which no
  server response reaches. In particular **no unlock path reads it**: a device finds its own wrap by
  the id in its local record, so the register can never be what stands between someone and their vault — which
  it could not be anyway, since reading it requires the vault to be open already.
- **What the wrap join still cannot reach: a wrap orphaned before it shipped.** Those have no register entry,
  and the wrap table carries **no timestamps**, so no age heuristic exists and none may be invented — the
  server's list cannot distinguish an orphan from a device that has not signed in yet. The only honest handle
  is defined by exclusion of what the client can verify locally
  ([ADR 0058](../../../decisions/0058-revoke-never-takes-key-material.md) §5), and it lives in
  [uc.account.sessions](../use-cases/uc.account.sessions.md), not here.

> **No end-user section.** As an enabler this has no standalone "what this means for you" story — its value
> reaches the user through [uc.account.sessions](../use-cases/uc.account.sessions.md), whose bilingual section
> carries the wiki text.

## Related

- Decisions: [ADR 0004](../../../decisions/0004-session-tokens.md) (session families, and why a name may not
  live on one), [ADR 0005](../../../decisions/0005-unlock-methods-and-device-trust.md) (one convenience wrap
  per device, and why its id is not a device identity),
  [ADR 0026](../../../decisions/0026-client-trust-tiers.md) (what a served client can be trusted with),
  [ADR 0058](../../../decisions/0058-revoke-never-takes-key-material.md) (what the wrap join is *for*: a revoke
  never takes key material, and removing a wrap is a named escalation)
- Concepts: [en.account.key-model](en.account.key-model.md) (the Master Key that seals the register; auth ≠
  unlock), [en.account.identity-pinning](en.account.identity-pinning.md) (the sealed-synced-blob shape this
  copies, down to the optimistic-concurrency retry)
- Use-cases: [uc.account.sessions](../use-cases/uc.account.sessions.md) (the surface that reads, writes and
  repairs the register), [uc.account.trust-device](../use-cases/uc.account.trust-device.md) +
  [uc.account.remember-this-device](../use-cases/uc.account.remember-this-device.md) (the wraps a device
  carries, whose ids this register joins to their device)
- End-user view (docs site): [Start a list over](../../../../site/src/content/docs/help/start-a-list-over.mdx)
