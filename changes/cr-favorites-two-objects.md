---
id: cr-favorites-two-objects
title: Favorites split into a private Memory pin and a shared per-item mark
status: planned
satisfies: [uc.memory.gallery]
adrs: ["0068", "0014", "0012", "0024"]
invariants:
  - Operator-blind E2EE — the server stores only ciphertext + opaque metadata
  - A member's Memory pins are confidential from their co-members
  - Product language: Memory / Chapter (never Album / Explorer)
---

## Goal

Favorites become **two objects with two audiences**
([ADR 0068](../docs/decisions/0068-favorites-two-confidentiality-levels.md)). The **Memory pin** turns private:
it re-seals under the member's **master key**, closing a live leak where a group's shared `K_SC` let every
co-member decrypt another member's pins. The new **item mark** is deliberately shared: a member marks individual
media, the gallery filters to *everyone's favorites* or *only mine*, and each tile's heart shows an **aggregate
count** — never who marked it. Item marks are stored **per node**, because the server has already observed the
node id, so that partition costs no metadata the blob design was built to withhold.

## Context & constraints

- Governing decision: [ADR 0068](../docs/decisions/0068-favorites-two-confidentiality-levels.md) — the two
  objects, the two seals, the rejected alternatives (larger cap, per-item blinded rows, keyed-hash buckets), and
  the measured ceiling. **Do not re-derive any of it here.**
- Refined state: [en.memory.favorites](../docs/features/memory/enablers/en.memory.favorites.md) (both objects),
  [uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md) (flow 7, E4, E5, and the copy rule).
- Discharges [ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md) §7.
- Contract: `contracts/api/content.openapi.yaml`. Crypto vectors: `contracts/crypto/vectors/v1.json`.
- **There is no production data.** No migration of favorites data, and — deliberately — **no read path for the
  old `K_SC`-sealed pin blob** (ADR 0068 §8). A one-shot re-seal fallback is the thing being refused, not an
  oversight.
- **Aggregate-only is presentation, not confidentiality.** Every member of a group can decrypt every other
  member's item-mark blob; the client receives them because an operator-blind server cannot aggregate for us. No
  string added by this CR — UI or `wiki/` — may imply otherwise. *"Only mine"* names a filter, never a promise.
- `git status` before the first edit of phase 1 (§ *`git status` before the first edit of a contracts-phase
  CR*). Parallel sessions are live: stage by **explicit path**, never `git add -A`, never a relative ref.

### Locators — run these, do not trust a tally

```bash
# every site that seals/unseals the pin set, and every test that will need the new key
rg -n "loadFavorites|saveFavorites|favoritesStore|'favorites'" web/src packages/crypto/src
# the E_0 stable-key rationale that must MOVE to the item side, not be deleted
rg -n -B4 "keyAt\(epochs, 0\)" web/src/app/favoritesStore.ts
# the cr-0089 post-rotation zeroed-key trap — re-read it before touching scope-switch reset
rg -n -B6 "favorites" web/src/app/activeSpace.ts
# node-scoped cleanup precedent: how media rows are reclaimed on node purge (this table follows it)
rg -rn "veyq_media|MediaEntity.delete" backend/src/main/kotlin --glob '*Purge*.kt' --glob '*Node*.kt'
# next Flyway version
ls backend/src/main/resources/db/migration | sed 's/__.*//;s/V//' | sort -n | tail -1
```

## Phases

- [ ] **1 contracts** — add `GET`/`PUT /api/scopes/{scopeId}/nodes/{nodeId}/item-favorites`
      (`ItemFavoritesEntry {memberId, ciphertext}` array on read; `PutItemFavoritesRequest {ciphertext}` on
      write — **no `memberId` on the write body**, which is what makes "you can only write your own" a shape
      rather than a check). Re-describe `Favorites` / `PutFavoritesRequest` as master-key sealed, context
      `favorites`. Add the `item-favorites` context to `contracts/crypto/vectors/v1.json` (`wrap` group) and —
      since neither context is pinned today — a `sym_favorites` vector beside it; **generate** both from the
      real primitives, never hand-write a vector. Add the two new capped base64 fields to
      `MEDIUMTEXT_BACKED` in `scripts/validate_maxlength_storage.py`.
      · **Done when:** `npm run generate:api` + `cd web && npm run check` green, and every
      `scripts/validate_*.py` green. `./gradlew check` **will not compile** in this phase — the generated
      `ItemFavoritesApi` has no implementation until phase 2. That is correct; do **not** add a stub to buy a
      green tick.

- [ ] **2 backend** — `V49__item_favorites.sql`: `veyq_scope_item_favorites` with PK
      `id VARCHAR(110)` = `"$userId|$scopeId|$nodeId"`, `user_id`/`scope_id`/`node_id VARCHAR(36)`,
      **`ciphertext MEDIUMTEXT NOT NULL`**, `updated_at`, `CONSTRAINT fk_item_favorites_scope … REFERENCES
      veyq_scope (id)` and an index on `(scope_id, node_id)`. Follows `veyq_media`: FK to the **scope**, a
      plain indexed `node_id` — node rows are reclaimed by code, because ADR 0061 made node deletion a
      *soft* delete and a cascade would fire at the wrong moment. Then the slice
      (`Resource`/`Handler`/`Store`/`Entity`) mirroring `features/content/favorites/`, member-gated with
      `NoScope → scopeNotFound`; register both verbs in `OperatorBlindProbeTest`; `@RateLimitExempt` as the
      existing favorites resource carries. Reclaim: `ScopePurge.eraseScope`, the node hard-purge, and **grant
      revocation** (a removed member's rows would otherwise keep counting into the aggregate forever).
      · **Done when:** `cd backend && ./gradlew check` green and every `scripts/validate_*.py` green.

- [ ] **3 web** — `favorites.ts` seals the pin set under the **master key** (from `app/vault`, the
      `identityPins.ts` idiom), context unchanged. New `itemFavorites.ts` + store: load every member's blob for
      the open node, `symUnwrap` each under `K_SC@E_0`, expose *union* / *mine* / *per-item count*. Gallery:
      the header filter (hidden in a personal scope), the per-tile count on the existing heart, the
      filter-empty state. i18n **EN + DE** for every new string, honouring the copy rule above.
      · **Done when:** `cd web && npm run check` green.

- [ ] **4 coverage** — `docs/coverage/features/memory/use-cases/uc.memory.gallery.yaml` gains rows for flow 7,
      E4 and E5 (today the YAML is deliberately behind the use-case — the "doc ahead" case, which is why it is
      a phase and not a follow-up).
      · **Done when:** `python3 scripts/validate_coverage.py` green.

## Bite tests

Each names its **kind** and its **lever** (§ *the CR says PER bite-test how it will be bitten*).

| # | phase | what it pins | kind | lever that reddens it |
| --- | --- | --- | --- | --- |
| 1 | 3 | **A member's pins do not open with `K_SC`** | **live defect** | re-seal `favorites.ts` under `symWrap(scopeKey, …)` |
| 2 | 1 | `favorites` and `item-favorites` blobs do not open under each other's context | injection | give both call sites the same context string |
| 3 | 1 | the new capped fields are storage-checked | **live defect, guard is the bite** | remove the `MEDIUMTEXT_BACKED` entries |
| 4 | 2 | scope erasure can complete with the new table | **live defect, guard is the bite** | remove the delete from `ScopePurge.eraseScope` |
| 5 | 2 | `GET` returns **every** member's entry | live defect | filter `GET` to the caller |
| 6 | 2 | a non-member gets `404` on both verbs | live defect | drop the `isMember` short-circuit |
| 7 | 2 | a purged node's rows are reclaimed | injection | remove the node-purge delete call |
| 8 | 3 | in a personal scope the filter is **absent**, not disabled | injection | render it with `:disabled` |
| 9 | 3 | no `memberId` reaches the rendered grid | injection | bind `memberId` into a tile attribute |
| 10 | 3 | the tile count aggregates **all** members | live defect | count only the caller's set |

**Bite 1 is the one this CR exists for, and it has a trap that makes the obvious version unbiteable.**
`loadFavorites` **swallows** a failed unwrap and returns the empty set — and `favorites.test.ts` asserts that as
*intended* behaviour. So a bite phrased as *"loading with the wrong key yields nothing"* is green today, green
after the fix, and green again if the fix is reverted: it can never redden. Assert on the **raw**
`v1.symUnwrap(scopeKey, blob, 'favorites')` **throwing**, against a blob produced by the production
`saveFavorites` path. That assertion is red on the untouched tree (today it succeeds and returns the pins), so
run it **before** the fix and record the red (§ *where the defect is still live, order it guard → red → fix →
green*). It reddens on the `symUnwrap` assertion, not on any setup line.

**Bites 3 and 4 are free, and both are ordered guard-first.** Adding the new base64 fields makes
`validate_maxlength_storage.py` red (cap 65 536 > `TEXT_MAX_B64` 65 532, allowlist entry absent); creating the
table with its scope FK makes `validate_scope_erasure_arity.py` red (a new table in the closure that
`eraseScope` does not name). Run each guard **before** writing its fix — the same run is the discovery, the
bite and the fix-list.

**What bite 3 does *not* prove, so nobody quotes it for more than it says.** `column_is_mediumtext` matches a
column **name across all migrations**, not per table — and `ciphertext` is already MEDIUMTEXT from V41 (the
`veyq_scope_favorites` column). So once the allowlist entry exists the guard passes **even if the new table's
`ciphertext` is plain `TEXT`**. The width on the new table is therefore carried by the Definition of done's
grep, not by the guard.

### Existing tests whose lever this CR moves

Named per § *a test the CR CITES as evidence is a test the CR can disarm* — each keeps its property and loses
its key, so each needs the new key, **not** deletion or weakening:

- `web/src/features/memory/favorites.test.ts` — all three cases seal with `K_SC` today. The empty-on-unreadable
  case stays (the behaviour is unchanged); its key changes.
- `web/src/app/favoritesStore.test.ts`, `web/src/app/activeSpace.test.ts` — same.
- `web/src/features/memory/components/MemoryCard.test.ts` — unaffected: the pin heart's rendering does not change.

**One comment must move rather than die.** `favoritesStore.ts` carries the cr-0089 rationale for sealing under
the **base epoch `E_0`** (so a rotation cannot strand the set). That reason stops applying to pins — the master
key does not rotate with scope epochs — and starts applying to **item marks**, which do seal under `K_SC@E_0`.
Move it to the item store with its reasoning intact; deleting it discards a harvested trap.

## Definition of done

- `cd backend && ./gradlew check` green; `cd web && npm run check` green.
- Every guard green, run as CI runs them: `for f in scripts/validate_*.py; do python3 "$f"; done`.
- `docs/coverage/` updated for `uc.memory.gallery`, `python3 scripts/validate_coverage.py` green.
- The new column's width is proven directly, since bite 3 cannot:
  `rg -n "ciphertext\s+MEDIUMTEXT" backend/src/main/resources/db/migration/V49__item_favorites.sql` → 1 hit.
- Every new UI string exists in **both** EN and DE, and none of them claims a mark is private.
- Completeness of the two structural invariants rests on permanent guards
  (`validate_scope_erasure_arity.py`, `validate_maxlength_storage.py`), not on a prose tally — no new guard is
  promoted by this CR, because both invariants already have one.

## Retrospective

_(filled at close — planning, execution, and process/collaboration, both directions, ending in one concrete
"next time")_
