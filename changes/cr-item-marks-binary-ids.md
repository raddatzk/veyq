---
id: cr-item-marks-binary-ids
title: Item marks as 16-byte ids in the one sealed blob, a cap a 10,000-photo Memory fits, and a heart that says when it did not save
status: in-progress
satisfies: ["uc.memory.gallery"]
adrs: ["0068", "0072", "0077"]
invariants:
  - operator-blind - still one sealed blob per (member, scope, node); the server learns nothing per mark (ADR 0068 § 4)
  - the self-tag statement is unchanged; only the plaintext's layout changes, and a vector pins it
---

## Goal

Inbox #500, measured (a vitest through `saveItemFavorites` / `saveFavorites` with the real seal and random UUIDs): a
mark costs 52 base64 characters, so one member's marks in one Memory stop at **1,257** — 12.6 % of the 10,000-photo
Memory `cr-gallery-virtualization` targets — and the 1,258th is a `400` the heart answers by springing back without a
word. Owner decision (2026-10-19, option b): keep ADR 0068 § 4's shape (one blob per member and Memory, no per-mark
rows), write each media id as its **16 raw bytes** instead of a JSON string, and raise the contract cap to **262,144**
(the column is already `MEDIUMTEXT`). Node pins keep their shape and cap (1,259 per scope: unreachable, the sidebar lists
every pin). **No silent revert** on either heart: a write that does not land shows a message (EN+DE).

## Decisions

1. **Plaintext** (`packages/crypto`, `encodeItemMarks` / `decodeItemMarks`): `tag (32 bytes) ‖ id₁ ‖ … ‖ idₙ`, each id
   its 16 RFC 4122 bytes, distinct and ascending. The decoder refuses any other shape (short, ragged, unsorted,
   repeated); the client reads a refused plaintext as a blob that does not open (an empty set, nobody's own — ADR 0068
   § 9's degradation). No version byte: the resource is the versioning axis (ADR 0068 § 1). The self-tag statement and
   its vectors are unchanged; `seal.sym.item-favorites` gets the new plaintext, and both `item_mark_self` cases gain
   theirs, from an independent Python implementation that first re-derives the old blob and both tags.
2. **The cap**: `maxLength: 262144` on the three item-favorites `ciphertext` fields (`@Size` beside it). That holds
   12,284 marks (262,144 base64 → 196,608 bytes − 29 seal − 32 tag, / 16); a toggle at 1,258 marks uploads about 27 KB.
3. **Over the cap, the client refuses before the write** (`ItemMarksTooMany`), so the limit message is the client's, and
   a server `413`/`400` reads as the generic failure.
4. **The message**: the two stores revert and rethrow; the components that own a toast say it —
   `MemoryDetail.vue` for a mark (limit vs. failure), every host of the pin heart (`favoriteActions.ts` takes the
   failure callback) for a pin.
5. **Not live, no legacy path**: a memories migration deletes every `veyq_scope_item_favorites` row. A dev instance loses
   every member's marks; nothing else.

## Phases

Phases 1-4 are built (2026-10-19); what each proved is in its commit. **Phase 5 stays open for the V1 heavy stage.**

- [x] **1 crypto + contracts** — the codec and its vectors; the cap. Done when: `packages/crypto` `npm run check`, the
  mutation gate `npm run mutation -- --force` at 100 %, `generate:api`, the contracts guards.
- [x] **2 backend** — the migration; `ItemFavoritesSliceTest` pins the new cap (262,144 stored, one more refused).
  Done when: `./gradlew check`.
- [x] **3 web** — encoding through the codec, the refusal, the two messages; tests: 1,258 and 12,000 marks round-trip,
  12,285 refused before any write with the message, no media id's bytes in the clear part of the blob, pin failure
  says so. Done when: `npm run check`.
- [x] **4 docs** — ADR 0068 §§ 1, 4 (measured), the enabler, the use-case's E8, the coverage criteria, the two Use pages
  (EN+DE), inbox #500 (`built` while phase 5 is open).
- [ ] **5 browser pass** (V1 heavy stage) — mark past the limit in a real browser and read the message; a failed pin
  write likewise.

## Definition of done

Every phase's Done-when; the browser pass at the V1 heavy stage; inbox #500 deleted then.

## Retrospective

- **Planning — the first brief prescribed a design an accepted ADR had already rejected.** #491's per-entry shape was
  named as the way to lift the cap; ADR 0068 § 4 rejects exactly that for item marks (a stable per-mark row the server
  can label by timing). Found only by reading the ADR before building, and settled by the owner in one round trip.
  Root cause: the inbox row pointed at #491's fix, not at the ADR that owns the marks. Next time: a row that proposes a
  sibling's design names the ADR that governs its own object.
- **Execution — two reds a phase-local run did not show.** The codec's first mutation run had 3 survivors (a `typeof`
  that the regex already covers for a number but not for an object whose text is a UUID, and a guard the comparison
  made redundant); and the heart's new `.catch` turned every `toggleFavorite: vi.fn()` mock into a crash in four
  component tests that click it. The second is the general one: a call site that starts using a function's returned
  promise reddens every mock of that function that returns `undefined` — grep the mocks when a `void f()` becomes
  `f().catch(…)`.
- **Process — a shared machine again.** Load 18-60 throughout; the crypto mutation run had to be kept apart from the
  web suite, which imports `@veyq/crypto` from the source Stryker mutates in place.
