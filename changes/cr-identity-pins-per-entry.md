---
id: cr-identity-pins-per-entry
title: The identity-pin store as one sealed entry per pin under a sealed head — no ceiling at 444 members
status: in-progress
satisfies: ["uc.spaces.verify-identity", "uc.spaces.enforce-verified-identities", "uc.account.rotate-keys", "uc.spaces.add-member"]
adrs: ["0104", "0028", "0072", "0060", "0101", "0023", "0085", "0077", "0026"]
invariants:
  - operator-blind - the server sees opaque keyed ids, one sealed entry each and a sealed head; never a userId, a key, a state or the trust graph
  - ADR 0028 Decision 7 holds per entry - a withheld, added, rolled-back or swapped entry is refused as a rolled-back store is today
  - no wrap to an unpinned key - the pin is written before the wrap, as today
  - every write of entries and the head is one transaction, compare-and-set on the head's revision
---

## Goal

Inbox #491, measured by `cr-volume-measurement-local`: a key-holder's pins live in one sealed blob capped at 1 MiB, a pin
costs 2,361 bytes of it, and the 445th member a key-holder admits is refused — `400 invalid_request`, swallowed by the
grant-delivery poller, the member *awaiting access* for good. The owner decided (2026-10-19): **store pins individually**
instead of raising the cap. [ADR 0104](../docs/decisions/0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md)
(proposed) is the shape: one sealed row per pin under a keyed id, and the existing store row as a **head** whose sealed
digest of the whole set keeps ADR 0028 Decision 7's rollback refusal intact. Each pin holds the contact's
`fingerprintHash` instead of the bundle (Q1), so an entry is about 300 bytes. Existing stores move on their owner's next
unlock.

## Context & constraints

- State: [ADR 0104](../docs/decisions/0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md),
  [ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md) (Decisions 1, 2, 6, 7 — what a pin
  protects and how), [en.account.identity-pinning](../docs/features/account/enablers/en.account.identity-pinning.md)
  (the store as built), [ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md) (a seal binds its object),
  [ADR 0060](../docs/decisions/0060-key-rotation-after-compromise.md) (the store re-sealed inside the rotation's
  transaction), [ADR 0101](../docs/decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md)
  § 11 (the erasure arity guard), [ADR 0077](../docs/decisions/0077-a-write-carries-the-version-it-read.md) (the
  five-attempt write loop).
- **A claim the code already breaks** (found at refine): the enabler's *"the server never learns … nor the count"*, the
  contract's *"no `userId`, key, or count"* and the site's *What the server sees* — the blob is not padded, its length is
  29 + 1,771 × n bytes before base64 (M, below), so the count was always visible. ADR 0104 decision 5 corrects it rather
  than defending it; phase 6 rewrites every place that makes the claim.
- **Unaffected:** the device register (same pattern, bounded by a person's devices: `maxLength 65532`); the trust marks'
  shape (`TrustMarks.pins` stays one integer — the head's revision) and the pairing envelope that carries them; strict
  mode (ADR 0028 Decision 4, unbuilt).
- **Not this CR:** the poller's `catch(() => {})` that hid #491 (`grantDeliveryPolling.ts`) — after this CR a pin write
  no longer fails on size, but any other failure stays silent; an inbox row is filed by phase 6 if the owner wants one
  (it is a one-line observation, not a decision).
- **Sibling CRs:** `cr-volume-measurement-cax` phase 2's member axis can reach 1,000 once this lands (that CR says so).
  No overlap in files with `cr-gallery-list-paging` / `cr-gallery-virtualization` or the #488 work.

### Measured substrate (worktree at `6fb7c8dd`, clean)

| Fact the plan leans on | Locator | At refine |
|---|---|---|
| The two operations, the cap, the revision CAS, `409 stale_revision` | `grep -n 'operationId: getIdentityPins\|operationId: putIdentityPins\|maxLength: 1048576\|x-veyq-concurrency' contracts/api/account.openapi.yaml` | 2 ops, 2 caps |
| The rotation carries the whole store (`RotateAccountKeysRequest.identityPins` → `IdentityPinsUpdate`), pinned by a guard | `grep -n 'identityPins' contracts/api/account.openapi.yaml`; `grep -n 'DOCUMENT_REFS' scripts/validate_rotation_shape.py` | required, one blob |
| Table and store: `veyq_identity_pins(account_id PK, sealed_blob MEDIUMTEXT, revision)`, CAS update, insert at revision 1 | `grep -n -A7 'CREATE TABLE veyq_identity_pins' backend/core/src/main/resources/db/migration/V202610041200__core_baseline.sql`; `grep -n 'fun get\|fun put\|revision = ?' backend/core/src/main/kotlin/me/raddatz/veyq/core/account/identitypins/IdentityPinsStore.kt` | as listed |
| The rotation writes the pins in its transaction | `grep -n 'identityPins' backend/core/src/main/kotlin/me/raddatz/veyq/core/account/rotation/RotationHandler.kt` | `put … is Stale` → rollback |
| The erasure deletes the store; the arity guard reads every table in `veyq_user`'s FK closure | `grep -n 'IdentityPinsEntity' backend/core/src/main/kotlin/me/raddatz/veyq/core/account/erasure/AccountPurge.kt`; `sed -n 1,25p scripts/validate_account_erasure_arity.py` | one delete |
| `MEDIUMTEXT` columns are allow-listed; a `TEXT` column takes ≤ 65,532 | `grep -n 'IdentityPins' scripts/validate_maxlength_storage.py` | 2 entries |
| Backup: the dump is the whole database; no per-table registry for user data | `grep -n 'NOT_DATA\|DUMP_SCHEMA_ONLY\|RECONCILE_TABLES' scripts/instance_backup.py` | nothing to add |
| The seal purposes and their vectors; `identity-pins: [['revision','uint']]`; `digest` is a field kind | `grep -n "'identity-pins'\|type FieldKind" packages/crypto/src/seal.ts`; `grep -n 'identity-pins' contracts/crypto/vectors/v1.json` | one purpose, one vector |
| Vectors come from an independent implementation outside the tree, checked by the TS conformance test | `grep -n 'independent' contracts/crypto/vectors/v1.json`; `grep -n 'every purpose in the union has a vector\|names a live purpose' packages/crypto/src/v1.conformance.test.ts` | out of tree |
| `fingerprintHash` = SHA-256(`veyq/v1/fingerprint` ‖ bundle), 32 bytes; the digits derive from it alone | `grep -n -A3 'export function fingerprintHash\|export function fingerprint(' packages/crypto/src/v1.ts` | as stated |
| Every read of a pin's key in the client is an equality check or a safety number | `grep -rn 'pin?\?\.key\|pin\.key\|prior\.key\|existing\.key' web/src \| grep -v '\.test\.'` | 5 sites |
| The pin's plaintext, its seal, the write loop, the readers | `grep -n 'interface Pin\|pinsContext\|MAX_WRITE_ATTEMPTS\|export async function\|export const pinStoreDocument' web/src/features/account/identityPins.ts` | — |
| `checkAndPin`'s callers (the four foreign-wrap surfaces, the granter pin, moderation) and `markVerified`'s | `grep -rn 'checkAndPin\|markVerified\|trustStates\|getPin(' web/src --include='*.ts' --include='*.vue' \| grep -v '\.test\.'` | — |
| The marks: one integer per document, refusals named | `grep -n 'TrustMarks\|openAccountDocument\|trust_state_' web/src/features/account/trustMarks.ts` | — |
| The rotation's reseal and the reset both use `pinStoreDocument` | `grep -rn 'pinStoreDocument' web/src \| grep -v '\.test\.'` | `rotateKeys.ts`, `trustReset.ts` |
| A pin's cost: 2,361 bytes; 444 pins seal to 1,048,484 base64 characters | python3 over `serializeIdentityPublic` (4 + 1,216 + 4 + 32 = 1,256 bytes) in `{"<uuid>":{"key":…,"state":"pinned","firstSeenAt":…}}` + 29 bytes of seal, then base64 | matches #491's reading exactly |
| The request body limit the one big write meets | `grep -rn 'max-body-size' backend/src/main/resources backend/core/src/main/resources` → none; Quarkus 3.36 `ServerLimitsConfig.maxBodySize` default `10240K` | 10 MiB |
| Where the site states what the pin store holds and hides | `grep -rln 'identity pin\|pin store\|identity-pins' site/src/content/docs` | 6 Security pages |

### Other contract caps on stores that grow with members or items

The measurement's lesson (changes/README § A CR that grows a count) applied to the whole contracts tree, by
`grep -rnE 'maxItems: [0-9]+|maxLength: [0-9]{5,}' contracts/api`, each hit read with its enclosing schema. Only these grow with a
count; each is an estimate (R) until its owner measures it, and none blocks this CR:

| Store | Cap | Grows with | Where it would bite |
|---|---|---|---|
| `item-favorites` ciphertext (one member's marked-media-id set per Memory) | `maxLength: 65536` | marks one member sets in one Memory | at roughly 1,200 marks in one Memory (a JSON uuid ≈ 39 bytes, base64 around the seal) |
| `favorites` ciphertext (one member's pinned nodes per scope) | `maxLength: 65536` | nodes one member pins | roughly 1,200 pins per scope; user-chosen, unlikely |
| `ModerationStatementRecipients.accounts` | `maxItems: 1000` | accounts a measure's statement reaches | a measure on content a tenant of over 1,000 members reaches — the response would break its own schema |
| `ModerationCaseExport.uploaders` | `maxItems: 1000`, where `ModerationTargets.uploaders` allows 10,000 | uploaders of a reported item | an export of a case the targets list already allowed past 1,000 |
| `RotateAccountKeysRequest.identityPins` (after this CR) | the entries' `maxItems` and the 10 MiB body | pins | decision 6 below sets it |
| Every unpaged list (`listNodeMedia`, `listShareMedia`, a scope's member list) | none on the response | items, members | no cap to hit — the cost is size; the media lists are `cr-gallery-list-paging`'s, the member list (one query per member, no ETag) is noted in `cr-volume-measurement-cax` |

## Decisions this CR takes

ADR 0104 decisions 1–7 are the design; what follows is how this CR builds them.

1. **Contract (`account-identity-pins` tag):**
   - `getIdentityPins` stays: the head (`blob`, `revision`). Its `blob` keeps `maxLength: 1048576` while a version-1
     store can still be served (ADR 0104 decision 7), then shrinks with the legacy path.
   - `listIdentityPinEntries` — `GET /api/account/identity-pins/entries?after=<pinId>&limit=` → `{ entries: [{ pinId,
     entry }], more }`, keyset on `pinId`, `limit` 1–1000, `maxItems: 1000`.
   - `writeIdentityPins` replaces `putIdentityPins` — one body for every write: `{ revision, head, put: [{ pinId,
     entry }], delete: [pinId], replaceAll }` (`replaceAll` deletes every other entry: the reset and the rotation),
     `x-veyq-concurrency` on `revision`, `409 stale_revision` as today. `entry` is `TEXT`-sized (constant length, well
     under 65,532), `put` `maxItems` = the ceiling decision 6 sets.
   - `RotateAccountKeysRequest.identityPins` becomes the same write body with `replaceAll: true`;
     `validate_rotation_shape.py`'s `DOCUMENT_REFS` follows.
   - `pinId` is `x-veyq-visibility: id` with a fixed base64 length (32 bytes); `OperatorBlindProbeTest` classifies the
     two new operations.
2. **Crypto (`packages/crypto`):** a seal purpose `identity-pin` bound to `[['pinId', 'digest']]`; a key label for
   `K_pinIndex` (HKDF from the Master Key) and `pinId = HMAC-SHA256(K_pinIndex, utf8(userId))`; the set digest of ADR
   0104 decision 4 as a pure function; `fingerprintDigits(hash)` split out of `fingerprint()` so a safety number renders
   from a stored hash. Each gets a vector **generated by an independent Python implementation outside the tree**, as
   `seal.note` in `v1.json` says the existing ones were (`cryptography` and `hashlib` in a scratch venv — the system
   Python is 3.9), and the conformance test's both-way purpose↔vector check covers the new purpose by construction.
   The head keeps `identity-pins` / `[revision]`; its plaintext is `{"version":2, "count", "digest"}`, the constant-size
   entry plaintext `{"userId","fingerprint","state","firstSeenAt"}` padded to one length.
3. **Backend:** a core table `veyq_identity_pin_entry(account_id, pin_id, sealed_entry TEXT)`, primary key `(account_id,
   pin_id)`, FK to `veyq_user`; no stored time (`EveryStoredTimeHasAReaderTest` sees none). `writeIdentityPins` applies
   deletes, puts and the head in one transaction, CAS on the head's revision (a first write at 0 inserts, as today,
   and two racing first writes answer `409`, not a key violation — the current store's PK race, `IdentityPinsStore.kt`,
   is fixed in passing). `AccountPurge.eraseAccount` deletes the entries before the head; the rotation applies its
   write in its transaction.
4. **Web (`identityPins.ts` and its callers):** the store reads the head under the marks (`openAccountDocument`, as
   today), lists the entries once per head revision and caches them for the session, verifies the digest and every
   entry's `pinId`; a mismatch with an unmoved head is `TrustStateRefused('rolled_back')`, with a moved head a re-read
   (inside the existing five attempts). A pin, an accept and a verify write one entry plus the head; a conflict re-lists
   and re-applies, `verified` winning as today. `checkAndPin`, `foldTrust`, `markVerified` compare
   `fingerprintHash(served)` with the entry; the two safety-number surfaces render from the stored hash.
   `pinStoreDocument`'s two users get the per-entry form: the rotation re-derives every `pinId` under the new Master Key
   and writes all entries with `replaceAll`; the reset writes an empty head with `replaceAll` one revision above.
5. **Migration (ADR 0104 decision 7):** opening a version-1 head converts it — every pin to an entry (its bundle hashed),
   one `writeIdentityPins` at the next revision with `replaceAll`. The marks need nothing: the new head's revision is
   above the old one. Conversion runs from the same read path every pin consumer uses, so the first trust read after
   the upgrade performs it; it is idempotent under the CAS.
6. **The ceiling** is the one write that carries every entry (rotation, migration, a later conversion): `put.maxItems`
   = 25,000, with phase 3 measuring the request at that count against the 10 MiB body limit (an entry ≈ 300–400 bytes
   on the wire). 25,000 is 56× today's wall; above it a rotation would need a staged write, which is not built.

## Open questions (owner)

**Decided (Q1–Q3).** Owner 2026-10-19: Q1 the fingerprint hash, Q2 the pin count named openly — both as recommended. **Q3 differs: no legacy read path.** veyq is not live, so no instance holds pins worth migrating: the old single-blob format is dropped, a dev instance is reset (the migration may simply clear the old rows), and ADR 0085's major rule does not apply yet. Implementation has the owner's go.

- **Q1: Does an entry pin the bundle or its `fingerprintHash`?** The hash keeps the security claim (ADR 0104 decision 3:
  every reader is an equality check or a safety number, both functions of the hash; equality of the hash is equality of
  the bundle up to a SHA-256 second preimage; it is the digest the QR compare already trusts). It costs ADR 0028
  Decision 1's sentence "the full identity public bundle is pinned" and a refactor of five call sites. The bundle keeps
  that sentence and costs about 2,360 instead of about 300 bytes an entry: the whole list a device reads at 1,000
  contacts is 2.4 MB instead of 300 KB, and the rotation's ceiling falls to about 4,000. **Recommendation:** the hash.
- **Q2: The count.** It was never hidden (the blob's length), and the grant rows let the server count anyway.
  (a) Say so and stop claiming otherwise; (b) pad with dummy entries to a size bucket, about 2× the rows, to hide what
  the grant rows already show. **Recommendation:** (a).
- **Q3: How long does the version-1 read path live?** Until the next major (ADR 0085), then removed with the 1 MiB cap;
  or for good. An account that never unlocks again keeps its version-1 row either way (the erasure removes it).
  **Recommendation:** the next major — one migration path, written once, deleted on a date the train sets.

## Phases

Phases 0-4 and 6 are built (2026-10-19, branch `worktree-agent-a8762061df59dffb9`); what each proved is in its commit.
**Phase 5 stays open: the rest of the live proof is to be proven live at the V1 heavy stage** (owner, 2026-10-19:
harness measurements, L1, web-e2e, api-fuzz and the MariaDB race suite wait for the end of V1).

- [x] **0 docs** — nothing to write: ADR 0104 existed, and coverage criteria cannot be written gapped (a criterion of a
  built use-case needs a ref, `validate_coverage.py`), so they landed with phases 3-4. `a-version-1-store-converts-on-unlock`
  was dropped with Q3.
- [x] **1 crypto** — the `identity-pin` purpose, the index key, the pin id, the set digest, `fingerprintDigits`; vectors
  from an independent Python implementation; both bites red; mutation 100 %.
- [x] **2 contracts** — three operations; pin ids are 64 hex characters, not base64 (the case-insensitive collation);
  case 82 re-anchored; `delete` became `remove` in phase 3 (the TS generator's reserved word).
- [x] **3 backend** — the entry table, the one-transaction write under the account lock, the 25,000 bound measured
  (9,600,083 bytes against 10,485,760), every listed bite red; `./gradlew check`, the MariaDB race run green.
- [x] **4 web** — the per-entry store, the four refusals bitten by skipping the digest; no version-1 conversion (Q3);
  `identityPins.ts` re-measured at 72.90 %.
- [ ] **5 live proof** — measured on a local production-recipe instance with `measure_volume.py` (the dev stack is the
  owner's and was not touched): the member seeder added 999 members, every one invited, accepted and granted through
  the product's flow by the owner's own grant-delivery worker; the owner's store then held 999 entries of 296 base64
  characters each under a head at revision 999 (148 characters), and the 445th and the 1,000th member's tenant grant
  were written (`wrapped_grant` set) — read off the instance's database before teardown. The profile stage was stopped
  at 722 of 999 on the owner's word, and the instance torn down. **Still owed at the V1 heavy stage:** the rotation on
  a 1,000-pin store (with the request sizes of the rotation and one pin write), the trust reset leaving an empty store
  on it, and the browser pass — the profile's safety number and QR compare, the changed-key review, the settings hub's
  *Start a list over*. A throwaway proof script for the rotation and the reset exists in the implementer's scratch,
  not in the tree. There is no version-1 store to convert any more (Q3).
- [x] **6 docs** — ADR 0104 accepted, ADR 0028 edited in place, the enabler, the security model, the use-cases, five
  Security pages (*Start here* states nothing the change moves); `npm run check` in `site/` green. Inbox #491 is
  `built`, not `done`, while phase 5 is open.

## Definition of done

Every phase's Done-when; a key-holder admits a 1,000th member on the dev stack; the four refusal cases bitten; inbox
#491 done; the CAX CR's member axis unblocked (its § Local pre-check says so).

## Retrospective

- **Planning — phase 0 asked for something the trust chain refuses.** It wanted gapped coverage criteria under built
  use-cases; `validate_coverage.py` rejects a criterion with no ref, and `changes/README.md` § Coverage already says so
  (cr-guest-write-record's phase 0 hit the same wall). Root cause: the refine wrote phase 0 from the usual shape, not
  from that rule. Next time: a CR that adds criteria to a built use-case writes them in the phase that brings their tests.
- **Execution — the contracts phase skipped a rule that was already written.** "A contracts phase that adds a schema
  writes one use of it in each generated language" was in the README; I did not, and met `entries` (Kotlin) and
  `delete` (TypeScript, a model that did not compile) a phase later, renaming the contract inside the backend phase.
  Also a bite I first trusted (`REQUIRES_NEW` as the rollback's lever) was red for a deadlock, not for the rollback; only
  reading the failing messages showed it. Both are now in `changes/README.md`.
- **Process — a shared machine shaped the run more than the code did.** Load averages of 20-170 from neighbours for
  most of the session, a neighbour's api-fuzz run on the same fixed `veyq_fuzz` database turning my run red (inbox
  #501), two L1/volume stacks taking ports 80/443 in turn, and the owner's scope for heavy checks narrowing three times
  mid-run — the third after the 1,000-member seeding had reached 999 members, so it was stopped there. What worked:
  one heavy step at a time, a background wait on load and ports rather than polling. **Next time:** a brief that names
  heavy stages states them as decided before the run starts, and the CR's live proof names the instance it runs on
  (the dev stack it named is the owner's and off limits to an implementer).
