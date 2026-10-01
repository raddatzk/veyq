---
id: cr-identity-codec-refuses-malformed-fields
title: The identity codec refuses a field of the wrong length, and anything after the last one
status: in-progress
satisfies: []
adrs: ["0028", "0045"]
invariants: [operator-blind E2EE]
---

## Goal

`deserializeIdentityPublic` and `deserializeIdentityPrivate` (`packages/crypto/src/v1.ts`) read three
length-prefixed fields and check none of them: a field of any length is accepted, a length prefix that runs past
the end is silently clamped by `slice`, and trailing bytes are ignored. The public decoder reads the identity
bundles the **server** serves for other members (grant, invite-name, safety number, granter-signature check,
pairing answer), so an operator can hand a client a non-canonical bundle and the client takes it. Inbox #240. Both
decoders move onto the bounded reader the drop-key codecs already use (`readFixedLp`), so every v1 length-prefixed
codec has exactly one encoding per value.

## Context & constraints

- The bundle format: [ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md) (what is
  pinned is the full public bundle), [en.account.identity-pinning](../docs/features/account/enablers/en.account.identity-pinning.md).
- The mutation gate grades this package: [ADR 0045](../docs/decisions/0045-mutation-testing-trust-chain-unit-layer.md);
  a refusal is pinned on its own **message**, never a bare `.toThrow()` (ADR 0045, the `decodeKdfParams` paragraph).
- Owner's standing answer for this wave: every recommendation below is taken (2026-10-01), so the Decisions are
  decided, not proposed.

## Inventory (measured against `main` 8fadccf, 2026-10-01)

**Every length-prefixed read in the package** — keyed on the read:

```sh
grep -nE 'getUint32|readFixedLp\(' packages/crypto/src/*.ts | grep -v '\.test\.ts'
```

**Every decoder in the package** — keyed on the name, the second angle:

```sh
grep -nE 'export (function|const) (deserialize|decode|open|sym|asym|kem|parse)' packages/crypto/src/*.ts
```

The two agree. Classified:

| Decoder | Shape | Verdict |
|---|---|---|
| `deserializeIdentityPublic` | `lp(kemX 32) ‖ lp(kemM 1184) ‖ lp(sign 32)`, unbounded | **fix** — served by the server |
| `deserializeIdentityPrivate` | `lp(kemX 32) ‖ lp(mlkemSeed 64) ‖ lp(sign 32)`, unbounded | **fix** — only reached after an AEAD open under the Master Key, so not attacker-shaped; fixed for one rule per codec |
| `deserializeDropKey`, `deserializeHybridPublic` | `readFixedLp` | already bounded, no change |
| `decodeKdfParams` | fixed 16-byte header + salt | not length-prefixed; already bounded and message-pinned |
| `open`, `openAs` (`seal.ts`) over `symUnwrap`, `asymUnwrap`, `kemDecap`; `openFrame` | fixed offsets | not length-prefixed; a wrong length fails the AEAD/KEM in `@noble` (ADR 0045, tamper vectors) |
| `parseKit` (`emergencyKit.ts`) | text | its own validation, mutation-graded |

**The callers** (they are where a new refusal lands — § *turning a degradation into a refusal*):

```sh
grep -rn 'deserializeIdentityPublic\|deserializeIdentityPrivate' web/src admin-web/src | grep -v '\.test\.ts'
```

- `grantTrust.ts` (granter's `sign`), `pairing.ts` `readAnswer` — catch and map to their existing refusal.
- `grant.ts`, `inviteName.ts`, `identityPins.safetyNumber` — do not catch. A short buffer already throws there
  today (a `DataView` `RangeError`), so the fix adds no new throw path; it widens which inputs reach the existing
  one. `ChangedKeyReviewDialog.vue` calls `safetyNumber` in a watcher with no catch — pre-existing, filed as an
  inbox row, not fixed here.
- `unlock.ts` — the private decoder, after the Master-Key AEAD open.
- **Honest writers of a non-canonical bundle: none.** Every producer is `serializeIdentityPublic`/`Private` over a
  generated or re-derived identity (`grep -rln 'serializeIdentity' web/src admin-web/src packages/crypto/src`),
  whose field sizes are fixed by `@noble`. So no stored bundle is stranded by the refusal.
- `pairing.ts` `readAnswer` re-serializes the decoded key and compares bytes, to make "the key sealed to is byte for
  byte the key the MAC covered". With a canonical decoder that check can no longer fire.

## Decisions

1. Both identity decoders read through `readFixedLp` with their fixed sizes; refusals are
   `identity public key: truncated|malformed|trailing bytes` and `identity private key: …`.
2. **No conformance vector.** A refusal has no output to record; ADR 0045 already decided against
   tamper-rejection vectors, and the drop-key codec's refusals are not vectored either.
3. **`pairing.ts` drops its re-serialization check** — the decoder now owns canonicality, and two copies of one rule
   are one rule and one copy that drifts. The property stays pinned **at pairing's own layer** by a new case (T2).
4. Callers are not changed; the dialog's uncaught throw is an inbox row.

## Phase (single)

- [ ] crypto + web — decoders, tests T1–T2, pairing deletion · Done when: `packages/crypto` `npm run check` and
  `npm run mutation -- --force` green; `web/` and `admin-web/` `npm run generate:api && npm run check` green.

## Bite tests

- **T1 — live defect** (`packages/crypto/src/identity.test.ts`): the drop-key mangle table plus two identity
  shapes (an overlong length on the last field — today clamped to a valid 32-byte `sign`; a self-consistent wrong
  length on the middle field), run against both decoders and pinned on the full message. Expected first red: the
  first public-decoder `toThrow(regex)` of each row — "cut inside the first length prefix" reddens on the message
  (`RangeError` today), "trailing bytes" and the overlong/wrong-length rows on *no throw at all*. The old
  "cut inside a length prefix" case with a bare `.toThrow()` is deleted; T1 supersedes it.
- **T2 — injection** (`web/src/features/account/pairing/pairing.test.ts`): an answer whose inner key carries one
  trailing byte, **MACed correctly over those bytes**, is `answer_unreadable`. Green on the untouched tree (the
  re-serialization refuses it) and green after (the decoder refuses it). **Lever:** on the untouched decoder,
  delete pairing's re-serialization check → T2 red, which proves the case reaches the property and is not refused
  by the outer `readPair`.
- **Mutation** — the new literals and the `sizes` arrays are graded by `npm run mutation -- --force` at the
  package's `break`; a survivor gets a kill test.

## Definition of done

T1 red then green, T2 bitten by its lever, the gates above green, the guard glob green, inbox #240 done.

## Retrospective
