---
id: cr-item-mark-self-tag
title: Which of a node's item marks is mine is proven by my client, not told by the server
status: planned
satisfies: [uc.memory.gallery, uc.account.rotate-keys]
adrs: ["0068", "0072", "0060", "0044"]
invariants: [operator-blind E2EE, "ADR 0068 §6 — no member id on the wire, nothing a co-member can attribute", "ADR 0072 — a seal binds its object; freshness not claimed"]
---

# Which of a node's item marks is mine is proven by my client, not told by the server

Refined 2026-09-28 from inbox #187 against `main` `faf9ef4`. Every claim below is `measured` (with the command
that re-runs it) or `remembered`.

## Open for the owner

**Answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`).

**Q1 — How does an account-key rotation carry the caller's own marks over to the new key?** The self-tag key
derives from the Master Key (Decision 2), and a rotation replaces the Master Key
(`grep -n "newMasterKey = v1.randomBytes(32)" web/src/features/account/rotation/rotateKeys.ts` → 1; the only other
minting site is account birth, `grep -rn "randomBytes(32)" packages/crypto/src/accountBirth.ts` → `masterKey`,
`secretKey`). Without a re-tag, after every rotation each Memory shows *only mine* empty and the caller's old marks
count as "someone's", and the **first toggle on each Memory replaces the old set** — silent loss, on every node,
guaranteed rather than only on failure. The client cannot enumerate the nodes it has marks on today. Options:

- **(A) — recommended.** A new read, `GET /api/item-favorites`, returns the caller's own rows across every scope
  they are an accepted member of: `[{ scopeId, nodeId, ciphertext }]`. The server already knows exactly these rows
  (`userId` is in the row key), so it learns nothing; the caller learns only its own. The rotation re-tags each row
  whose tag verifies under the **old** key, best-effort, as a fourth rider of `resealBestEffort`. One read plus one
  PUT per marked Memory. Costs a contracts + backend phase.
- **(B)** No new endpoint: the rotation walks `listNodes` for every scope and `GET`s item favorites for every
  Memory. Client-only, but it scales with the whole library (one request per Memory, marked or not), at the one
  moment the user is already under stress (post-compromise), inside a ceremony that awaits it.
- **(C)** No re-tag: accept that a rotation empties *only mine* everywhere and the next toggle per Memory drops
  the old set. Not recommended — it is the "silent degradation" ADR 0068 § Consequences refused for the pin blob.

The CR below is written for **(A)**; under (B) phase 2 disappears and phase 3c walks the tree instead; under (C)
phase 2 and 3c disappear and the rotate-keys use case names the loss.

**Q2 — Fold "the server can replay a member's earlier sets as extra entries" into this CR?** Same root (an entry
is bound to (scope, node) and to nothing that says *whose*): the server can serve one member's blob twice, or any
earlier version of it it kept, as additional "members" — the per-tile count and the *everyone's* filter inflate
(`measured`: `grep -n "reduce((n, m) => n + (m.ids.has(mediaId)" web/src/app/itemFavoritesStore.ts` → the count is
one per entry, no de-duplication). **Recommended: no — file it as its own inbox row** (proposed wording in the
refine report). The fix needs a per-(member, node) pseudonym that co-members can see and link across versions,
which is a change to ADR 0068's privacy levels that deserves its own decision, and even then it only caps a replay
at one count per member — a stale set still reads as current (ADR 0072 §5's freshness class). This CR's tag is
content-bound and unlinkable (Decision 3), so it neither closes nor forecloses that fix.

## Goal

An item mark (ADR 0068's shared, member-attributed-to-nobody object) is sealed under `K_SC@E_0` and bound to
(scope, node) — not to the member who made it, because the read deliberately names no member (ADR 0068 §6, ADR
0072 §3). The read tells the client which entry is its own through a server-set boolean, `self`, which nothing
checks. So a malicious server can relabel another member's marks as the caller's. After this CR, the caller's
client **proves** which entry is its own: its plaintext carries a tag only the marking member's own client can
compute — an HMAC under a key derived from their Master Key, over the scope, the node and the marked set — and an
entry is *mine* only when the server flags it `self` **and** the tag verifies. Nothing new is visible to the
operator (the tag is inside the seal) or attributable by a co-member (they cannot compute or link it).

## What a relabel does today — measured

Measured by reading the code path, with the lines that carry each step:

1. **The flag is taken as given.** `measured` (`grep -n "own: e.self" web/src/features/memory/itemFavorites.ts` →
   2, lines 54 and 58): `loadItemFavorites` copies the wire's `self` into `own`, for a blob that opened and for one
   that did not. The seal cannot object: `measured` (`grep -n "'item-favorites':" packages/crypto/src/seal.ts` →
   `[['scopeId', 'id'], ['nodeId', 'id']]`) — any member's blob for this node opens in any entry.
2. **What the user sees.** The server sets `self: true` on member B's entry and `false` on the caller's. The tile
   hearts fill for B's items (`isMarkedByMe` → `mineOf(...)` = the first `own` entry,
   `grep -n "marks.find((m) => m.own)" web/src/app/itemFavoritesStore.ts`), *only mine* shows B's marks
   (`grep -n "markFilter.value === 'mine'" web/src/features/memory/components/MemoryDetail.vue`), and the caller's
   real marks look like someone else's. The per-tile **count does not move** — it counts entries, not owners
   (`grep -n "reduce((n, m) => n + (m.ids.has(mediaId)" web/src/app/itemFavoritesStore.ts`) — so nothing on screen
   looks wrong except that the hearts are not the ones the user set.
3. **One click makes it durable, and launders it through the victim's own client.** `toggleItemFavorite` starts
   from `mineOf(before)` — B's set — adds or removes the clicked item, and PUTs that as the caller's blob
   (`grep -n "new Set(mineOf(before))\|filter((m) => !m.own)" web/src/app/itemFavoritesStore.ts` → 2). The server
   stores it under the caller's row (the row is keyed by the token's user, below). Result: the caller's real set
   on this Memory is **gone**, and B's marks now sit in two rows — every member's count for B's items is +1,
   persistently, written by the caller's genuine client. Before the click the relabel was a display lie; after it
   the lie is stored data.
4. **An honest server never produces the lie.** `measured`
   (`grep -n "own = it.userId == callerId" backend/src/main/kotlin/com/veyq/app/features/content/itemfavorites/ItemFavoritesStore.kt`
   → 1; `grep -n "fun key" backend/src/main/kotlin/com/veyq/app/features/content/itemfavorites/ItemFavoritesEntity.kt`
   → `"$userId|$scopeId|$nodeId"`, the table's `PRIMARY KEY (id)` in `V49__item_favorites.sql`): one row per
   (member, scope, node), so at most one entry is `self`, and it is the caller's.

**Confidentiality is untouched** — every member opens every entry anyway (ADR 0072 §3). This is integrity of *which
marks are mine*, and, through step 3, integrity of every member's aggregate.

## Inventory — measured

| # | What | Locator (re-run it) | Result at `faf9ef4` |
|---|---|---|---|
| I1 | The read's entry shape | `grep -n -A3 "^    ItemFavoritesEntry:" contracts/api/content.openapi.yaml` | `required: [self, ciphertext]`; `self` is `x-veyq-visibility: flag` |
| I2 | The seal purpose | `grep -n "'item-favorites':" packages/crypto/src/seal.ts` | binds scope + node, no member |
| I3 | The vector | `grep -n '"item-favorites": {' contracts/crypto/vectors/v1.json` | 1; plaintext is a bare JSON id array |
| I4 | Where `own` is set | `grep -n "own: e.self" web/src/features/memory/itemFavorites.ts` | 2 |
| I5 | Who consumes `own` | `grep -rn "\.own\b\|isMarkedByMe\|mineOf" web/src --include=*.ts --include=*.vue \| grep -v test` | `itemFavoritesStore.ts` (mineOf, toggle), `MemoryDetail.vue` (filter, tile props) |
| I6 | Direct test of `itemFavorites.ts` | `grep -rl "features/memory/itemFavorites'" web/src` | only `itemFavoritesStore.ts` + its test, which **mocks** it — the seal/open path has **no** test of its own |
| I7 | Backend `self` computation | `grep -n "own = it.userId == callerId" backend/src/main/kotlin/com/veyq/app/features/content/itemfavorites/ItemFavoritesStore.kt` | 1 |
| I8 | Backend tests that pin `self` | `grep -n "self" backend/src/test/kotlin/com/veyq/app/features/content/itemfavorites/ItemFavoritesSliceTest.kt` | lines 67, 89, 94, 118, 175 — unchanged by this CR (the flag stays) |
| I9 | Response order | `grep -n "ItemFavoritesEntity.list(" backend/src/main/kotlin/com/veyq/app/features/content/itemfavorites/ItemFavoritesStore.kt` | no `ORDER BY`. `remembered/inferred`: the index scan likely returns rows in a stable order, so array position may already act as a per-node pseudonym for co-members — not relied on by this CR either way |
| I10 | Master-Key re-seal riders | `grep -n "await reseal" web/src/features/account/rotation/rotateKeys.ts` | 3 in `resealBestEffort` (pins, register, Memory favorites); called before `rekeyAlone` and before `setUnlocked`, so the old identity still opens grants there |
| I11 | Statement MAC precedent | `grep -n "^export const STATEMENT_PURPOSES\|export function admissionTag" packages/crypto/src/seal.ts packages/crypto/src/v1.ts` | `admission-tag` = `HMAC(HKDF(key, "", "veyq/v1/…"), encodeStatement(…))`, vector `#admission_tag` |
| I12 | Statement vectors checked both ways? | `grep -c "STATEMENT_PURPOSES" packages/crypto/src/v1.conformance.test.ts` | 0 — only seal purposes are compared with the vector set both ways; a new statement's vector is pinned only by its own conformance case |
| I13 | Docs and comments that state the gap | `grep -rn "relabel" docs/decisions docs/features docs/crosscutting web/src/features/memory/itemFavorites.ts packages/crypto/src/seal.ts` | ADR 0072:55, `en.memory.favorites.md`:119, `security-model.md`:369, `itemFavorites.ts`:23, `seal.ts`:61 |
| I14 | ADR 0068 drift | `grep -n "memberId, ciphertext\|bare id arrays" docs/decisions/0068-favorites-two-confidentiality-levels.md` | §5 still says `[{ memberId, ciphertext }]` — the contract has carried `self` since `cr-favorites-two-objects`; §1 says both plaintexts stay bare id arrays |
| I15 | Size ceiling | the script under *Decision 5* | bare array 1 259 ids (reproduces ADR 0068's table); `{tag, ids}` 1 257 |
| I16 | Rotation use case | `grep -n "re-sealed" docs/features/account/use-cases/uc.account.rotate-keys.md` | lines 18, 39, 161 name the Master-Key riders |
| I17 | Endpoint probe registry | `grep -n "ItemFavorites" backend/src/test/kotlin/com/veyq/app/shared/http/OperatorBlindProbeTest.kt` | 2 probes (get, put) — a new endpoint joins it |

Gate baselines on this tree (`measured` at refine, recorded here so a done-when promises only what it can):
see *Baselines* at the end.

## Decisions

**1. The server keeps saying which row is mine; the client proves the row's content is mine. Both are required.**
`own := entry.self === true && tagVerifies(entry)`. Why both, not the tag alone (which would let the wire drop
`self`, per `changes/README.md` § *"Which one is mine?" is a flag*): every co-member **opens** the caller's blob,
so they can copy it — tag and all — into their own row. With the tag alone, that copy would verify as the
caller's own, and after the caller's next toggle the copy is a stale set the caller's client could not tell from
its real one: a co-member, with no server help, could pin the caller's view of their own marks. The honest
server's `self` separates the copy (it sits in the co-member's row). Against the server, the tag is what holds:
it can move a genuine entry, not make one. The two fail in different places, so neither is removable.

**2. The tag key is `K_self = HKDF-SHA256(masterKey, salt = "", info = "veyq/v1/item-mark-self", 32)`.**
It must be a secret only the marking member holds — anything a co-member also holds (`K_SC`, a user id, the scope
and node ids) would let them compute every member's tag by trial over the member list, which is exactly the
attribution ADR 0068 §6 forbids. The Master Key is the one member secret every device of that member has
(`measured`: the pairing envelope carries it, `grep -n "masterKey: b64std(grant.masterKey)"
web/src/features/account/pairing/pairing.ts` → 1; recovery opens and re-wraps the same key,
`grep -n "purpose: 'master-key'" web/src/features/account/recovery/recovery.ts` → 2; only a rotation replaces it — Q1). The operator cannot choose it
(`changes/README.md` § *a device-local defence is filed under a key the server cannot name*).

**3. The tag binds the content: `tag = HMAC-SHA256(K_self, encodeStatement({ purpose: 'item-mark-self', scopeId,
nodeId, marks }))`**, with `marks` = SHA-256 over `lp(utf8(id))…` of the marked ids, de-duplicated and sorted
ascending (a `digest` field, the kind `grant` already uses). A new row in `STATEMENT_PURPOSES`, beside
`admission-tag`. Why content-bound, not a static per-node value: a static tag is visible to every co-member (it
is inside `K_SC`'s plaintext) and so is a copyable credential — a co-member colluding with the server could paste
it onto a blob of **their** choosing and have it shown as the caller's own. Content-bound, the colluders can only
replay a set the caller really once had, which is ADR 0072 §5's freshness class, not forgery. It is also
**unlinkable**: two versions of one member's set carry unrelated tags, and nothing crosses nodes — so a co-member
learns nothing the unnamed set did not already tell them.

**4. The tag lives inside the seal.** The plaintext becomes `{"tag":"<b64std, 32 bytes>","ids":[…]}` — no wire,
schema, column or visibility change; the operator sees a fixed +61-byte growth that carries no information.
Rejected: a `selfTag` wire field beside the ciphertext (a MAC over the ciphertext bytes). Equally sound, and it
would let a rotation re-tag without opening grants — but it is a contract field on two schemas, a new column and a
migration, and it hands the operator one more opaque value per row. Inside the seal is the smaller surface.

**5. The ceiling moves from 1 259 to 1 257 ids per Memory.** `measured` — re-run:
`python3 -c 'import json,uuid,math;f=lambda p:4*math.ceil((29+len(p))/3)<=65536;t="A"*44;n=1
while f(json.dumps({"tag":t,"ids":[str(uuid.uuid4()) for _ in range(n)]},separators=(",",":")).encode()):n+=1
print(n-1)'` → 1257 (the bare-array form of the same script → 1259, ADR 0068's number). The contract's 65 536-char
cap is unchanged.

**6. Everything that is not provably mine counts as someone else's — a degradation, never a refusal.** An entry
flagged `self` whose tag fails, an entry whose plaintext has no or a malformed tag, and an entry whose tag verifies
but is not flagged `self` all count toward the aggregate as another member's set; the caller's own set is then
empty. If more than one entry is flagged `self` and verifies, the first is mine and the rest count as others (an
honest server sends at most one, I7; more is a replay of the caller's own genuine sets — freshness, ADR 0072 §5).
Why not refuse (e.g. "marks unavailable", writes disabled): every such outcome is **availability-equivalent** to a
power the operator already has — it can delete the caller's row outright, and the next toggle then starts from
empty just the same. A refusal would add a UI state and would strand the one honest producer of an unverifiable
own entry, a rotation whose re-tag did not land (`changes/README.md` § *turning a degradation into a refusal*).
What the fix guarantees is the integrity direction only: **another member's marks are never shown as mine, and a
toggle never writes another member's set into my row.** No UI string changes; *only mine* stays a filter, not a
promise (ADR 0068 § Consequences).

**7. A rotation re-tags, and the old key is what licenses a re-tag (Q1 (A)).** The re-tag rider reads the
caller's own rows, opens each with that scope's `E_0`, re-seals only those whose tag verifies under the **old**
`K_self`, under the new one, and PUTs. It never re-tags an entry it cannot verify — the listing is the server's
and is a hint, not a proof, so a server that slips B's blob into it gains nothing. Best-effort like the three
riders it joins: a failure leaves that Memory in Decision 6's degraded state.

**8. ADR 0068 and ADR 0072 are edited in place** (phase 0; `docs/decisions/README.md`: current state, no
addenda). ADR 0072 §3's item-favorites paragraph stops calling the gap accepted and points to ADR 0068; ADR 0068
gains the self-tag as a decision of its own (it owns the object), corrects §5's wire shape (I14), and narrows §1's
"bare id arrays" to the Memory pin.

## Invariants — how each is held

- **Operator-blind.** The tag is inside the `K_SC` seal; the size delta is constant. The new read (Q1 (A)) returns
  to the caller only rows the server already attributes to the caller. No new field in any schema but the new
  endpoint's response, whose three fields are two ids and an opaque base64 ciphertext
  (`scripts/validate_field_visibility.py` classifies them).
- **ADR 0068 §6 — nothing attributable.** No member id is added anywhere. A co-member can neither compute a tag
  (Decision 2) nor link two tags (Decision 3). Bitten below (B4).
- **ADR 0072 — binding, not freshness.** The seal context is unchanged (`item-favorites` keeps scope + node); the
  tag is a statement, not a seal purpose, so `SEAL_PURPOSES` and its both-ways vector check do not move. Replay of
  the caller's own older set stays open and is named as such (Decision 6, Q2).

## Phases

- [ ] **0 docs — the decision first.** ADR 0068: a new decision "which entry is mine is proven, not told"
  (Decisions 1–3, 6 here, with the rejected alternatives: tag alone without `self`, static tag, wire-side tag,
  refusal instead of degradation), §1's plaintext sentence narrowed to the pin, §5's `[{ memberId, ciphertext }]`
  corrected to `[{ self, ciphertext }]`, and the Consequences line on the rotation rider. ADR 0072 §3: the
  item-favorites paragraph rewritten to "bound to (scope, node); *whose* is a self-tag inside the plaintext (ADR
  0068)". · Done when: `grep -n "relabel\|#187" docs/decisions/0072-a-seal-binds-its-object.md` → 0 and
  `grep -n "memberId, ciphertext" docs/decisions/0068-favorites-two-confidentiality-levels.md` → 0; the guard glob
  green.
- [ ] **1 contracts.** (a) `contracts/crypto/vectors/v1.json`: the `item-favorites` seal vector's plaintext becomes
  the `{tag, ids}` form; a new `item_mark_self` section (master key, derived `K_self`, statement bytes, tag) —
  two cases, one id and several unsorted ids with a duplicate, so the canonicalisation is pinned.
  (b) `content.openapi.yaml`: `ItemFavoritesEntry.self` and both `ciphertext` descriptions say what the flag is
  now (the server's claim of *which row*, checked by the client against the tag) and what the plaintext holds.
  (c) Q1 (A): `GET /api/item-favorites` (`operationId: listOwnItemFavorites`, tag `item-favorites`), 200 →
  array of `OwnItemFavoritesEntry { scopeId, nodeId, ciphertext }`, only rows of scopes the caller is an accepted
  member of; 401; 403 tenant-suspended. · Done when: `npm run check` in `packages/crypto` green with the new
  conformance case; the guard glob green (field visibility, maxlength-storage, ADR citations).
- [ ] **2 backend** (Q1 (A) only). `ItemFavoritesStore.listOwn(userId)` joined against accepted membership (a row
  of a scope the caller has left must not come back); `ItemFavoritesResource` method; `OperatorBlindProbeTest`
  entry; slice tests: returns the caller's rows across two scopes, never a co-member's, never a left scope's; 401.
  · Done when: `./gradlew check` green (backend/CLAUDE.md).
- [ ] **3a crypto package.** `itemMarkSelfKey(masterKey)`, `itemMarkSelfTag(key, {scopeId, nodeId, ids})`,
  `itemMarkSelfTagVerifies(...)` in `v1.ts` beside `admissionTag`, reusing `macEquals`; the statement row in
  `seal.ts`. · Done when: `packages/crypto` `npm run check` green; bites B5 red-then-green.
- [ ] **3b web — the read and the write.** `itemFavorites.ts`: `saveItemFavorites` seals `{tag, ids}`;
  `loadItemFavorites` computes `own` per Decisions 1 and 6 (`K_self` derived from the vault's Master Key per call
  and zeroed; no Master Key ⇒ nothing is mine **and no write happens** — `onToggleMark` returns early, as it does
today without a scope key, because a toggle from an empty own set would replace the real one). The
  store's contract (`MemberMarks.own`) is unchanged, so `itemFavoritesStore.ts` and `MemoryDetail.vue` should not
  need edits — if they do, that is a finding. Rewrite the file's header and the `seal.ts:58-62` comment (I13).
  A new `web/src/features/memory/itemFavorites.test.ts` (I6: the path has no test today). · Done when: `npm run
  check` in `web/` green; B1–B4 red first on the untouched tree where marked, then green.
- [ ] **3c web — the rotation rider.** `retagItemMarksUnderNewMasterKey(oldMk, newMk)` in `itemFavorites.ts`,
  called from `resealBestEffort` (`rotateKeys.ts`) with its own `try`; opens each scope's `E_0` through the same
  grant path the vault still holds at that step (I10). · Done when: B6 and B7 red-then-green; `web` `npm run check`.
- [ ] **4 docs + coverage.** `en.memory.favorites.md` (the two-seals bullet: the gap sentence becomes the
  self-tag, and the ceiling 1 257), `security-model.md` § what stays open (remove the item-favorites relabel
  clause; keep `E_0`; name own-set replay as freshness), `uc.account.rotate-keys.md` (the fourth rider at the three
  I16 lines), `uc.memory.gallery.md` only if its text claims anything about *mine* being server-told (grep
  `self`), and the coverage YAMLs `docs/coverage/features/memory/use-cases/uc.memory.gallery.yaml` (a new item
  `my-marks-are-proven-mine`, refs to B1–B5) and `docs/coverage/features/account/use-cases/uc.account.rotate-keys.yaml`
  (the rider, B6–B7). The inbox row closes. · Done when: `python3 scripts/validate_coverage.py` and the whole
  glob green; `grep -rn "relabel" docs/features docs/crosscutting docs/decisions web/src/features/memory packages/crypto/src`
  → 0 hits that describe this gap as open.

## Definition of done

Bite tests — each with its method and its lever (`changes/README.md` § *the CR says PER bite-test how it will be
bitten*). **Order: B1 and B2 are live defects — write them against the untouched `loadItemFavorites` /
`toggleItemFavorite` and record the red before any 3b edit.** The red is taken through seams the untouched tree
has: B1 calls `loadItemFavorites(scopeId, nodeId, scopeKey)` with a mocked `getItemFavorites` (typed response,
not `as unknown as` — inbox #195). The co-member's blob is produced by the module's **own** `saveItemFavorites`,
run as that member (their Master Key in the vault on the fixed tree) and captured from the mocked PUT — so the
fixture is a genuine blob of whichever format the tree under test writes, and the same test body is the red on the
untouched tree and the green on the fixed one. If the fixed signature adds a parameter, re-point then.

| # | Asserts | Method | Lever after the fix |
|---|---|---|---|
| B1 | A `self: true` entry holding **another member's** genuinely sealed set is not *mine*; the caller's real entry flagged `false` is not *mine* either; the count is unchanged | **live defect** (red today: `own` = `self`) | replace the verification with `own: e.self` in `itemFavorites.ts` → red |
| B2 | After that relabelled load, a toggle PUTs a blob that opens to **only** the clicked id — never B's set plus it | **live defect** (red today: laundering, step 3) | same lever as B1, observed at the PUT body |
| B3 | A co-member's entry carrying a **copy of the caller's tag** over a different id set does not verify; the caller's own honest entry does (positive control — tells "held" from "nothing ran") | injection | drop `marks` from the statement (a static per-node tag) → the copy verifies → red |
| B4 | Nobody without the caller's Master Key computes the caller's tag: a test that holds `K_SC`, the scope and node ids, the ids, and every member's user id derives tags every way those allow and none equals the caller's | injection | derive `K_self` from `K_SC` (or from the user id) instead of the Master Key → red |
| B5 | `item_mark_self` vectors reproduce byte for byte, including the duplicate/unsorted case | injection | change the info string, or skip the sort → conformance red |
| B6 | After `rotateKeys`, an entry tagged under the old Master Key verifies as *mine* under the new one | live defect of the new flow (red until 3c) | remove the rider call from `resealBestEffort` → red |
| B7 | The rider does not re-tag a listed row whose tag fails the **old** key (a server-slipped co-member blob stays not-mine) | injection | remove the old-key check in the rider → red |
| B8 | (Q1 (A)) `listOwnItemFavorites` never returns a co-member's row or a left scope's row | live defect of the new endpoint | return `ItemFavoritesEntity.listAll()` unfiltered → red |

Also: `packages/crypto` mutation (`npx stryker run stryker.config.mjs`) has **no survivor in the three new
functions** — the gate as a whole is red on untouched `v1.ts` (inbox #184), so this CR promises the new code only.
The guard glob green; `web` and `packages/crypto` `npm run check` green; `./gradlew check` green if phase 2 ran.
The implement run records B1/B2's red output (test name + failing assertion) in the retrospective.

**Every existing item-mark blob stops opening as *mine*** (no tag) — before V1 there is no data to carry; a
development instance's marks read as someone else's until re-toggled, or reset it (ADR 0072's precedent).

## Baselines (measured at refine, `faf9ef4`)

- `packages/crypto`: `npm run check` → exit 0, 277 tests, 6 files (`measured`, after `npm ci` at the root).
- `web`: `npm run check` → exit 1 in one run: 1 of 1 365 failed, `src/i18n/i18n.test.ts > uses every key it
  carries`, a **5 000 ms timeout** (6 329 ms) while nine sibling refines loaded this machine; re-run alone
  (`npx vitest run src/i18n/i18n.test.ts`) → pass in 2.05 s. Not this CR's; if it recurs on an idle machine it is
  an inbox row, not a phase.
- Guard glob (`for f in scripts/validate_*.py; do python3 "$f"; done`): green on this CR's commit (recorded in the
  refine report).
- `./gradlew check`, the `packages/crypto` mutation run: **not run** at refine (no Docker in the refine sandbox;
  mutation is red on untouched `v1.ts`, inbox #184). Phase 2 records `./gradlew check` on `main` before its first edit.

## Retrospective

(written at implement close)
