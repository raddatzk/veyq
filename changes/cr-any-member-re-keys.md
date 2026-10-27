---
id: cr-any-member-re-keys
title: Any member re-keys a space's nodes after a rotation, and a member's own write pulls its path forward
status: planned
satisfies: ["uc.spaces.rekey-after-rotation"]
adrs: ["0111", "0109", "0080", "0075", "0024"]
invariants:
  - a re-key is still a write path onto the node - only a caller who may write there runs it; a drop box, its drop key and every guest identity folder below it are re-keyed by an owner only (ADR 0111 decision 11, ADR 0080 Decision 11)
  - nothing seals under an epoch below the scope's current one - a member's write waits for its path's re-key, it is never written under the old key, and a guest never re-keys
  - two devices re-keying the same node at once leave one commit and no lost write (the node row lock and `node.epoch >= current → Stale`, RekeyStore.kt:137)
  - the server learns nothing new - who re-keyed is a session it already sees
---

## Goal

[ADR 0111](../docs/decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
decision 11. After a scope rotates, [ADR 0109](../docs/decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md)
re-keys every node below it and holds a write into a node not yet re-keyed (`409 node_rekey_pending`). Today only an
**owner** may run that re-key, so after any rotation a member cannot write into an existing folder or Memory until an
owner has been online long enough. This CR lets **any member holding the current epoch** re-key, eagerly in the
background on every member's device, and makes a member's own write that meets `node_rekey_pending` re-key its path and
retry, in one place. A guest still waits, and a drop box with its guests' identity folders stays an owner's.

It is the first CR of ADR 0111's build and stands alone: it changes who runs a re-key for **every** rotation cause (a
group removal, a removal cascade, an account rotation), and `changes/cr-secure-the-account.md` depends on it, because
under that CR any member who secures their account commits every shared epoch.

**Size:** three phases (contracts, backend, web) and a docs phase.

## Context & constraints

Refined 2026-10-27 against `main` at `8cd444bbc`, measured by a delegated read that cited every pointer below.

- **The gate.** `RekeyAccess.check` (`src/backend/core/src/main/kotlin/me/raddatz/veyq/core/node/rekey/RekeyAccess.kt:38-52`)
  checks, in order: `ScopeAccess` (`NoScope`, `Suspended`, `Deleting`), the membership row, **`role != OWNER` →
  `not_scope_owner`** (:47, the failure at :22), `tasks.isRotationExcluded` → `rotation_excluded` (:48), the caller's
  grant behind the current epoch → `grant_behind` (:50). Its rationale KDoc :27-34 is owner-only. Its one caller is
  `RekeyHandler.refusal()` (:55-56), behind `listRekeyNodes`, `getNodeRekeyParts`, `stageNodeRekey`, `commitNodeRekey`
  (`contracts/api/content.openapi.yaml:537, 575, 611, 661`) and the application participants (`listPotRekey`/
  `stagePotRekey`, `expenses.openapi.yaml:275, 316`; `listMediaRekey`/`stageMediaRekey`, `media.openapi.yaml:184, 225`).
  The contract's `not_scope_owner` texts: `content.openapi.yaml:563-567, 603, 645, 723`; `expenses.openapi.yaml:306,
  351`; `media.openapi.yaml:215, 257`.
- **The tests that pin it:** owner-only only in `RekeyCoreSliceTest.kt:51` (a MEMBER with a current grant gets `403
  not_scope_owner`, asserted :71); `PotRekeySliceTest.kt:52` and `MediaNodeRekeySliceTest.kt:70` pin `grant_behind`.
- **`rekeying` is served to owners only**: `ScopesStore.listMine` (`ScopesStore.kt:258`, `if (m.role == OWNER)
  ScopeRekeying.lagsBelow(...) else null`), contract `ScopeSummary.rekeying` (`scopes.openapi.yaml:1881-1889`,
  "Owner-only"), pinned by `RekeyCoreSliceTest.kt:293` (owner side only).
- **The web re-key** `rekeyScope` (`src/web/src/core/node/rekey/rekeyScope.ts:64`) opens through `openScopeById`, has no
  role check of its own, re-lists and re-keys the frontier each round (:82). **`resumeRekeys`**
  (`features/tasks/scopeRotationWorker.ts:111-116`) runs it on every scope whose row says `rekeying === true` — so the
  owner filter is the server's field. Polling: every 45 s while unlocked and on becoming visible
  (`scopeRotationPolling.ts:11, 22-25`), started at `app/activeSpace.ts:170`.
- **Concurrency is already safe.** A commit locks the scope and the node and refuses `node.epoch >= current` as `Stale`
  (`RekeyStore.kt:129-137`); a child whose parent lags is `Pending` (:146); `rekeyScope.ts:52` re-lists on either.
- **The drop box is visible to the server.** `NodeEntity.sealed_drop_private_key` (`core/node/Entities.kt:73-77`) marks a
  drop box root of any kind (Depot also has `DEPOT_DROPBOX`); `share_identity_id` (:79-89) marks a guest's identity
  folder, and `NodesStore.identityFolderOwner` (`content/NodesStore.kt:246-255`) walks to it. The re-key has no
  permission special case for either today, only shape (`RekeyStore.kt:154-155, 182, 210-215`; web
  `rekeyScope.ts:145, 159, 177, 197-201`; test `RekeyCoreSliceTest.kt:250`).
- **`node_rekey_pending` and the client.** The verdict is `NodeEpochGate.kt:49`, the failure `Failures.kt:82`. It is
  raised on node create (`NodesHandler.kt:222`), placement/rename (`NodesStore.kt:381`), restore (:527), cover
  (`NodeCoverStore.kt:42`), share create/reissue/identity (`SharingHandler.kt:368, 637, 763`), depot completion
  (`DepotUploadCompletion.kt:86`), pot write (`PotRevisionStore.kt:188`), receipt (`PotReceiptStore.kt:94`), banner
  (`MemoryBannerStore.kt:112`), media meta and buckets (`MediaHandler.kt:257, 335, 358`), and on guest writes
  (`ShareNodesHandler.kt:265, 370`, `ShareMediaHandler.kt:252`). The client's only handling is
  `src/web/src/shared/rekeyRetry.ts`: `retryWhileRekeying` (:70) waits `[2000, 5000, 10000]` ms (:41) and re-runs, and
  `writeAtNodeEpoch` (:87) adds one `stale_epoch` re-read. It is opt-in per call site (`core/node/tree.ts:278, 466`,
  `nodeMetaWrite.ts:66`, `core/sharing/guestTree.ts:156, 223`, `guestIdentity.ts:100`, `manageShares.ts:242, 347`,
  `features/expenses/potSession.ts:276, 285, 308`). Nothing re-keys on a write today.
- **Every accepted member may write** (`ScopeAccess.kt:8`, `AccessMode { READ, WRITE }`; roles only OWNER and MEMBER,
  `MembershipEntity.kt:112-113`), so a member's re-key gains a member no power it lacks (ADR 0111 decision 11).
- **Account rotation's exclusion** (`rotation_excluded`, :48) stays here: `cr-secure-the-account` removes it with the
  exclusion machinery. Until it lands, a rotator excluded from a scope is refused a re-key there as today.

## Decisions

1. **Any member holding the current epoch may re-key a node** — `RekeyAccess`'s `role != OWNER` refusal becomes a
   refusal **only** where the node is a drop box root (`sealed_drop_private_key` set), a guest identity folder, or below
   one (`identityFolderOwner`), for every operation the gate guards, the participants' included. The refusal there stays
   `403 not_scope_owner`. `grant_behind` and `rotation_excluded` stay as they are.
   - **The listing** (`listRekeyNodes`, the participants' lists) serves a member every lagging node, drop-box ones
     included, marked so the client skips them (`ownerOnly: true` on `RekeyNode`) — a member's frontier must know a
     drop box is not its to re-key, or it re-lists forever.
2. **`rekeying` is served to every member** (`ScopesStore.kt:258` loses its owner filter; the contract's "Owner-only"
   text and `RekeyCoreSliceTest.kt:293`'s KDoc change). So every member's `resumeRekeys` continues an unfinished re-key
   in the background — spreading over all members' devices what today lands on an owner's.
3. **A member's write pulls its path forward, in one place.** `retryWhileRekeying` gains an option `pull: {scopeId,
   nodeId}`. On the first `node_rekey_pending` it lists the scope's lagging nodes, re-keys the target and its lagging
   ancestors top-down through the same per-node commit `rekeyScope` uses (factored out of it, not copied), and retries
   at once; a `403 not_scope_owner` from that re-key (a drop box) falls back to today's waits. `writeAtNodeEpoch` passes
   it through. Every **member** write path that can receive the 409 calls through the helper with `pull`; the guest
   paths (`guestTree.ts`, `guestIdentity.ts`, `features/depot/shareUpload.ts`, `dropBox.ts`, Memories' share upload)
   call it without, and keep waiting. A member write path that does not use the helper today is wrapped (the Inventory
   pairs each backend site with its web caller).
4. **A guest waits**, with today's copy (`share.folder.rekeyPending`), until a member's or an owner's device has
   reached its node. Writing under the old key instead would let the excluded party read it (ADR 0111 decision 11).
5. **The drop box stays an owner's** (ADR 0111 decision 11, last bullet): a member's write into a drop box node waits
   like a guest's, and only an owner's worker re-keys it. Members place nothing in its identity folders (ADR 0080
   Decision 11), so this costs a member only a write into the drop box root itself.

### Not in this CR

- Who commits a shared epoch after an account rotation (`changes/cr-secure-the-account.md`).
- A lazy re-key: still rejected (ADR 0109; ADR 0111 decision 11).

## Inventory

| What | Where | Locator |
|---|---|---|
| The gate and its rationale | `RekeyAccess.kt` | `grep -n "NotOwner\|not_scope_owner\|role != \|isRotationExcluded" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/node/rekey/RekeyAccess.kt` |
| Every operation behind it | `RekeyHandler.kt`, participants | `grep -rn "\.refusal(\|RekeyAccess" src/backend --include='*.kt'` |
| Contract texts naming the owner | content, expenses, media specs | `grep -n "not_scope_owner" contracts/api/content.openapi.yaml contracts/api/expenses/expenses.openapi.yaml contracts/api/memories/media.openapi.yaml` |
| The listing schema | `RekeyNode` | `grep -n "^    RekeyNode:\|sealedDropPrivateKey:\|shareIdentityId:" contracts/api/content.openapi.yaml` |
| `rekeying` served | `ScopesStore.kt`, `scopes.openapi.yaml` | `grep -rn "rekeying" src/backend/core/src/main contracts/api/scopes.openapi.yaml` |
| Drop box and identity folder markers | `Entities.kt`, `NodesStore.kt` | `grep -n "sealed_drop_private_key\|share_identity_id\|fun identityFolderOwner" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/node/Entities.kt src/backend/core/src/main/kotlin/me/raddatz/veyq/core/content/NodesStore.kt` |
| Every 409 site (backend) | backend | `grep -rn "nodeRekeyPending\|RekeyOutcome.Pending\|Verdict.PENDING" src/backend --include='*.kt'` |
| Every web caller of the retry helper, and every 409 reader | web | `grep -rn "retryWhileRekeying\|writeAtNodeEpoch\|isRekeyPending\|node_rekey_pending" src/web/src --include='*.ts' --include='*.vue'` |
| The re-key's per-node commit to factor out | `rekeyScope.ts` | `grep -n "commitNodeRekey\|stageNodeRekey\|function rekeyNode\|export async function rekeyScope" src/web/src/core/node/rekey/rekeyScope.ts` |
| Tests asserting owner-only (test trees too) | backend + web tests, e2e | `grep -rln "not_scope_owner" src/backend/core/src/test src/backend/apps; grep -rln "rekeying" src/web/src --include='*.test.ts' tests/e2e` |
| Docs and site that say "an owner re-keys" | `docs/`, `site/` | `git grep -n -i "owner's worker\|any owner holding\|an owner re-keys\|until an owner" -- docs site/src/content/docs` |

## Phases

### Phase 1: contracts

`RekeyNode.ownerOnly`; the `not_scope_owner` texts narrowed to the drop box; `ScopeSummary.rekeying` described for
every member. **Done when:** `npm run generate:api` runs and the trust-chain guards are green; expected red: none on a
new optional response field beyond the fixtures that build `RekeyNode` (run `./gradlew check` and `npm run check` once
to see it, `changes/README.md` § A new required response field).

### Phase 2: backend

Decisions 1, 2, 5. **Reproducing tests first, red against today's gate**, in `RekeyCoreSliceTest`:
- `aMemberWithTheCurrentGrantReKeysANode` — red today (`:51`'s case inverted; that test's member leg becomes the
  control below).
- **Controls on the same predicate:** a member is refused `not_scope_owner` on a drop box root, on a guest identity
  folder, and on a node below one; an owner re-keys each of them.
- `aMemberWithAGrantBehindIsRefused` (`grant_behind`, unchanged).
- The participants: one member leg each in `PotRekeySliceTest` and `MediaNodeRekeySliceTest`.
- `aMembersScopeRowReadsRekeyingWhileANodeLags` (`:293`'s member side, red today).
- `twoMembersReKeyingTheSameNodeLeaveOneCommit` — the second commit answers `Stale` and the node holds the first's key.
**Bite:** restore `role != OWNER` → the member test red; drop the drop-box check → its control red.
**Done when:** `./gradlew check` is green.

### Phase 3: web

Decisions 3, 4, 5. Tests in `rekeyRetry.test.ts` and the call sites' tests:
- **A member's write pulls:** a fake server answers the write `node_rekey_pending` once, lists the target and its
  parent lagging; the helper re-keys parent then target, then the write succeeds on the second attempt with **no wait**
  (fake timers show zero elapsed). Red today: it waits and re-runs and the write fails again.
- **Controls:** a guest call site (no `pull`) waits as today; a drop-box target (`ownerOnly`) falls back to the waits;
  a `stale_epoch` after the pull is re-read once, as `writeAtNodeEpoch` does today.
- `resumeRekeys` on a member's device re-keys a scope whose row reads `rekeying`.
**Bite:** drop the pull → the "no wait" case red; pull for a guest → the guest control red.
**Done when:** `npm run check` in `src/web/` is green, and the **browser pass** ran (headless system Chrome, the owner's
German UI, a throwaway two-member tenant, no second `quarkusDev`): owner A removes a third member from a group, B (a
plain member) adds a photo to an existing Memory of that group right away and it lands; a guest link's upload into an
existing folder waits until B's or A's device re-keyed it. If no browser can run, say so; the pass stays open here.

### Phase 4: docs

ADR 0109 point 7 and § Consequences "Writing waits" rewritten as built; ADR 0080 Decision 11 gains the owner-only
re-key of a drop box; ADR 0111 § Status (decision 11 built); `uc.spaces.rekey-after-rotation` (primary actor: any
member; the pull; the guest and drop-box waits) and its coverage YAML; `en.spaces.scope-key-rotation` where it names the
owner; site pages the Inventory's last row finds. **Done when:** the trust-chain guards are green and `npm run check` in
`site/` is green.

## Definition of done

- A member writes into an existing node right after a rotation, delayed by its path's re-key and never refused; a guest
  waits until a member's device reached its node; only an owner re-keys a drop box and its identity folders.
- Every member's device continues an unfinished re-key.
- The tests above were red first; the ADRs, use case, coverage and site say so.
- This CR is deleted, after its Retrospective's findings have moved to where they apply.

## Retrospective

*(filled at implement time)*
