---
id: cr-platform-contract-split
title: "DRAFT — the contracts say whose they are: core's specs speak the platform, Memories' guest-media contract leaves core"
status: planned
satisfies: [uc.sharing.share-contributor, uc.sharing.share-uploader, uc.sharing.share-public-viewer]
adrs: ["0079", "0072", "0077", "0031", "0030", "0012", "0013", "0044"]
invariants: [operator-blind E2EE, API-first (contracts are the source of truth), self-hostable by default, crypto wire and stored formats unchanged]
---

> **DRAFT, not implementable yet.** Written 2026-10-05 from five read-only inventories plus a re-verification pass
> (§ What was measured). Per [`docs/ai/README.md`](../docs/ai/README.md) § *Refined state comes before the CR*, a CR
> may not invent a concept: the paragraphs in **Appendix A** must first be decided with the owner and written into
> [ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Consequences. Until then
> this file is the **plan and the question list**, not a work order. Implementation waits for an explicit go.

## Goal

Every spec under `contracts/api/` describes what its **owner** does, and an operation, schema or parameter whose
behaviour is an application's lives in that application's directory. Concretely: core's specs stop describing the
generic node tree, blobs and shares in Memories' words; Memories' guest-media contract (the two
`/api/shares/{shareId}/media` operations, their request/response schemas, and the media rows a share resolve serves)
moves to `contracts/api/memories/`, and with it the media-shaped types core still carries for it. What core **stores
and decodes** — the closed `NodeKind`, `BlobRole`, the Memories columns on `veyq_node` — is a second, wire-and-schema
part (Part 2), drafted here but cut as its own CR once its ADR questions are answered.

## Context & constraints

- [ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Decisions 2, 3, 6, 7, 9 and
  its Consequences list of "what core still knows of Memories"; [`docs/apps/memories.md`](../docs/apps/memories.md),
  [`docs/apps/README.md`](../docs/apps/README.md).
- [`backend/CLAUDE.md`](../backend/CLAUDE.md) § Conventions: specs by owner directory, `_common` is core's and ONE
  generated type; **a description-only spec edit needs no backend phase**; **one tag is one generated interface with ONE
  root `@Path`**; migrations: baselines may change until the release, only together with their pin line.
- [`web/CLAUDE.md`](../web/CLAUDE.md): `generate:api` before `check`; **an absence asserted through the generated client
  proves nothing** (read `…Raw(…)` + `.raw.json()`); `vi.mock` resolves by path (a move disarms mocks).
- No release: `scripts/applied_migrations.sha256:16` reads `release: none` [M]. Wire and baselines may change — each
  change explicit and proven.
- Not up for debate: operator-blind; crypto stored formats. Seal purposes bind the purpose string and object ids,
  never a JSON field name or a spec file ([ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md);
  `packages/crypto/src/seal.ts:43-58` [M]). Moving a field between schemas changes no ciphertext byte; renaming a
  purpose, adding a binding field or merging two plaintexts would be a suite `v2` (ADR 0079 Decision 9).
- Parallel ideas this plan must not pre-empt: a Gradle `platform-api` module (under discussion, not decided) and inbox
  **#335** (split `ScopeAccessWorld`). See Owner decision 8.

## What was measured

Substrate: worktree of `main` at `2f302bbd`, clean (`git status --short` empty), 2026-10-05. The inventories behind
this draft read the main checkout at the same commit. **[M]** = read at the cited line in this session; **[I]** =
inferred. Inventory claims not listed below are carried as the inventories stated them and are re-checked by the
phase that uses them (§ *a handed-over table has a reader*, `changes/README.md`).

### Re-verified (the claims the slices lean on)

| # | Claim | Evidence (re-read this session) | Verdict |
|---|---|---|---|
| V1 | `NodeKind` is the closed `[FOLDER, MEMORY, CHAPTER]` in `_common`, also pinned by the enum-family guard | `contracts/api/_common.openapi.yaml:161-168`; `scripts/validate_contracts_common.py:57` | confirmed |
| V2 | Share creation already refuses by seam, not by `MEMORY` | `backend/core/.../core/sharing/SharingHandler.kt:279` (`guestWrites.none { it.supports(node.kind) }` → `IllegalScope`); the comment above it (:277-278) still says "only a MEMORY node" | confirmed — only text names `MEMORY` |
| V3 | The drop private key is written by core only, set-once | `core/sharing/SharingStore.kt:83-91` (conditional `update … where sealedDropPrivateKey is null` → `DROP_KEY_EXISTS` / `DROP_KEY_MISSING`) | confirmed |
| V4 | The drop key may not join the node-meta plaintext | `packages/crypto/src/seal.ts:55-58` ("a node field of its own, never inside node-meta") | confirmed — rules out folding it into a generic payload |
| V5 | Core runs the node-meta CAS | `core/content/NodesStore.kt:179-188` (bulk `update … metaVersion = ?4 and deletedAt is null`, stale read after) | confirmed |
| V6 | The guest gate is private to `SharingHandler`; the seam has `supports/start/window/complete/deleteOwn` | `core/sharing/ShareGuestWrites.kt:23-44`; gate steps inline in `SharingHandler.completeUploads`/`deleteMedia` (`activeShare`, `passwordCheck` :755-761, `canUpload`, `findIdentity`, `rateLimiter.allowUpload`, `writesFor` :734-737) | confirmed — **what is missing for the move is a callable gate, not a seam method** |
| V7 | `CompleteItem` stays media-shaped in core *only because* core's share contract serves the guest completion | `core/blob/UploadCompletion.kt:11-19` (KDoc says exactly this) | confirmed — moving the two ops lets it leave core |
| V8 | `PublicShare.media` is required and media-shaped; core builds it from a seam | `contracts/api/sharing.openapi.yaml:1311,1381-1384`; `core/sharing/SharedSubtreeContributor.kt:4-26,38-44` (`ShareMediaRow`, `ShareDerivativeRow`, `rows`) | confirmed |
| V9 | `commitCoverBlob` bypasses `UploadCompletion` and writes core's `veyq_node.cover_blob_id` | `apps/memories/.../media/MediaHandler.kt:287-304`; `media/MediaStore.kt:373-383` | confirmed |
| V10 | `cover_byte_size` is written and never read by main code | `grep -rn "coverByteSize\|cover_byte_size" backend \| grep -v /test/` → entity mapping, two Memories writes, baseline; only `NodeCoverSliceTest` reads it | **new**: write-only column |
| V11 | `veyq.media.trash.*` is declared by core and read only by Memories | `core/config/VeyqConfig.kt:331-340`; only reader `memories/media/MediaTrashPurge.kt:29`; values in core's `application.properties:102-103` | confirmed |
| V12 | Core web imports Memories' generated client | `web/src/shared/image/mediaDeriver.ts:8` (`MediaDerivativeKind` from `api/generated/media`); `mediaDeriver` is also used by core's `features/spaces` (space icon); `web/src/api/client.ts` builds `mediaApi/bucketsApi/favoritesApi/itemFavoritesApi/ownItemFavoritesApi` and is their only non-Memories user | confirmed |
| V13 | `SpecSets.kt` hard-codes two owner sets; module-graph rule (d) reads literal `SpecSet(…)` calls | `backend/buildSrc/.../SpecSets.kt:29-35`; `scripts/validate_backend_module_graph.py:138-139,371-381` (no literal → "(d) declares no SpecSet" red) | confirmed |
| V14 | Probe test pins both generated packages | `backend/src/test/.../OperatorBlindProbeTest.kt:417,446` | confirmed |
| V15 | Guards anchored on spec keys | `scripts/contract_specs.py:29-33` (`APPLICATIONS`, `OPAQUE_LOCATION_CANARY`); `validate_maxlength_storage.py:60-71`; `validate_guard_gaps.py:290-291,524-536` | confirmed |
| V16 | Core specs carry Memories only in prose | `instance.openapi.yaml:91`, `tenants.openapi.yaml:578`, `scopes.openapi.yaml:554`, `tasks.openapi.yaml:136,627`, `contracts/platform/capabilities.json:8` | confirmed (spot-checked 6 of the inventory's sites) |

### Where the inventories were wrong or imprecise

- **`MediaDerivativeKind` vs. `BlobRole` drift is already guarded** (inventory B §Q4 said "would surface only at
  runtime"). `validate_contracts_common.py:56-69` holds the `derivative-kind` family `(PREVIEW, THUMBNAIL)` and declares
  `BlobRole` and the share role list as the only intentional variants: adding a value to either enum is red today.
  The "dedupe `MediaDerivativeKind`" slice therefore buys nothing and is dropped [M].
- **The web guard's blind spot is not the `api/generated` exemption.** `is_exempt` (`validate_web_core_imports_no_applications.py:57-63`)
  exempts files *inside* `generated/` from being read; the miss is `reaches_application` (:66-70), which knows only
  `features/memory` as the application, so an import *of* `api/generated/media` is never judged [M].
- **The web generator has 11 runs, not 12** (inventory E): `sed -n 15p web/package.json | grep -o '\-i [^ ]*' | wc -l` → 11 [M].
- **`BlobRole` is closed in the crypto package's encoder, not only its type**: `seal.ts:136` (`ROLES`) and `:172-176`
  refuse any other role before sealing [M]. Opening the contract enum alone therefore admits nothing a client could seal.
- **ADR 0079 states the `kind` question twice, differently** — Consequences bullet 1: *"the tree's `kind` is a column the
  platform must keep open to values it does not interpret"*; the Consequences paragraph on open prerequisites: *"The node
  `kind` open to values core does not interpret is not decided yet."* Owner decision 1 resolves which holds [M].

## Target layout of `contracts/api/`

"Text" = description/summary/example only (no generated delta beyond Kotlin KDoc, `backend/CLAUDE.md`). Anchors are
the inventories' (A = content/_common/sharing, B = blob/tasks/scopes/Memories, C = account/auth/instance/setup/tenants).

| Spec | After Part 1 | After Part 2 (with the recommended decisions) |
|---|---|---|
| `_common.openapi.yaml` (core) | Unchanged shape. Text: `NodeKind` description names the owner per value (FOLDER core, MEMORY/CHAPTER Memories); `StaleSealedMeta` comment drops operation names (A §2). New: `components.parameters` `ShareId`, `SharePassword`, `ContributorToken`, shared by `sharing` and `memories/share-media` (see Slice 1.3) | `NodeKind` becomes an open bounded token (Owner decision 1); visibility marker `enum` → `structure` |
| `account`, `auth`, `setup` (core) | Unchanged (C: no hit beyond noise) | Unchanged |
| `instance` (core) | Text :91, :112, :168-172 ("photo"/"media" → uploads/blob transfer) | — |
| `tenants` (core) | Text :578, :1089, :1198-1199 | — |
| `scopes` (core) | Text :554 ("with its item favorites" → "with the rows applications keep for that membership") | — |
| `tasks` (core) | Text :627 (`e.g. a mediaId` → neutral); example tag :136 → a tag from `contracts/tasks/tags/v1.json` | — |
| `blob` (core) | All three operations stay. Text :5-19, :35-42, :227, :259, :362 (no `completeMedia`/`commitCoverBlob`/`K_mem` in core's words; `BlobRole` says its values are Memories' and why it is closed) | `BlobRole` unchanged unless Owner decision 5 opens it |
| `content` (core) | All six operations stay. Text throughout (A §1: "Memory tree", the K_mem paragraphs, "no folder inside a Memory" → "a kind's placement is its application's rule", trash texts naming media/buckets/photos). Example `createNode` → `FOLDER`. Fields unchanged | `kind` per decision 1. `wrappedMeta`/`metaVersion`/`sealedDropPrivateKey` per decisions 2–3 (recommended: stay, already described generically in Part 1) |
| `sharing` (core) | **Keeps** every curator op, `resolveShare` (shell), `getShareTerms`, `resolveShareBlob`, `createShareIdentity`, `startShareBlobUploads`, `presignShareBlobUploadWindows`. **Loses** `completeShareMedia`, `deleteShareMedia`, parameter `MediaId`, schemas `ShareMediaBatchRequest`, `ShareMediaRequestItem`, `ShareMediaDerivativeUpload`, `ShareMediaBatchResponse`, `ShareMediaResultItem` (Slice 1.3) and **`PublicShare.media`**, `ShareMedia`, `ShareMediaDerivative` (Slice 1.4). Text: A §3 rows 9–12, 15, 18–19 | `PublicShare.rootKind` (#329) with decision 1 |
| **`memories/share-media.openapi.yaml`** (new, Memories) | Tag `share-media`, generated root `/api/shares/{shareId}/media`: `completeShareMedia` (POST), `deleteShareMedia` (DELETE `/{mediaId}`), **`listShareMedia`** (GET, replaces `PublicShare.media`), with the schemas above; security: none (guest), each op its own rate-limit posture | — |
| `memories/media.openapi.yaml`, `memories/favorites.openapi.yaml` | Unchanged | Decision 1: declares `MemoriesNodeKind [MEMORY, CHAPTER]` for its own generated constant |

Path decision for `memories/share-media`: the root `/api/shares/{shareId}/media` is longer than `sharing`'s `/api/shares`,
so RESTEasy resolves those two paths to the Memories class and every other `/api/shares/…` path to core's. The
precedent is live today: tag `item-favorites` (root `/api/scopes/{scopeId}/nodes/{nodeId}/item-favorites`,
`memories/favorites.openapi.yaml:41`) sits below `content`'s root `/api/scopes/{scopeId}` [M paths; I resolution — no
generated sources in this tree]. The hazard `backend/CLAUDE.md` records is the opposite case (a tag whose root
*widens*), so the slice proves it with a route test over **every** `/api/shares` URL, not only the moved ones.

## The two cuts, side by side

| | **Cut M — ownership on the wire, storage unchanged** (recommended now) | **Cut X — maximal: nothing Memories-shaped stored or decoded by core** |
|---|---|---|
| Contains | Part 1: text, web hygiene + guard, guest-media ops and media list into `memories/share-media`, `UploadCompletion` + `CompleteItem` out of core; then Part 2 items 2.1 (NodeKind + #329), 2.2 (revisions + `cover_byte_size`), 2.4 (config) | Cut M **plus** node meta into a Memories table with its own endpoints (decision 2b), the drop key out of `veyq_node` (decision 3), `BlobRole` opened (5), cover into a Memories table now (6c) |
| Wire impact | `PublicShare.media` removed + one new GET; guest-media ops change spec/package, not path or bytes; `NodeKind` becomes an open string; `rootKind` added | All of M, plus `NodeSummary`/`ShareNode`/`CreateNodeRequest`/`UpdateNodeRequest` lose `wrappedMeta`/`metaVersion`; `createNode` no longer arms meta atomically; new Memories meta endpoints |
| Schema | `veyq_node` loses `cover_byte_size`, `media_revision`, `delete_revision`; both baselines + pin lines change | Also loses `wrapped_meta`, `meta_version`, `sealed_drop_private_key`, `cover_blob_id`; new Memories tables with erasers |
| New seams | A callable `ShareGuestGate` (core service) | Also a share-creation hook (drop key set-once inside core's transaction), a guest-resolve contribution of node payloads, node-create with an application payload |
| What stays in core | The node payload slot (`wrappedMeta`/`metaVersion`, described as the kind's sealed, versioned payload — ADR 0079 Decision 7), the uploader drop key (uploader shares are core, Decision 2), `wrappedContentKey` (core opens it, `web/src/core/node/tree.ts`), `cover_blob_id` until the attachment table (#327), closed `BlobRole` | `wrappedContentKey` only |
| Effort [I] | Part 1 ≈ 3–4 sessions; Part 2 (M items) ≈ 3 sessions | M + ≈ 4–6 sessions, two of them on the share-creation seam and its race proof |

**Plainly:** the owner asked for "very concrete" moves, and the honest finding is that the biggest-looking Memories
fields on core's wire (`wrappedMeta`, `metaVersion`, `sealedDropPrivateKey`, `wrappedContentKey`) are **platform
mechanisms whose plaintext is Memories'**: core stores, compare-and-sets, chains or set-once-writes them and never opens
them, and Memories' backend never touches node meta at all (inventory D §2 [M]). Moving them to Memories buys a cleaner
`veyq_node` at the price of new seams, a lost atomic create, and a second list request on every browse — and the next
application would want exactly the same slot back. Cut M moves everything whose **behaviour** is Memories' and
re-describes, rather than moves, what is a mechanism. That needs the ADR to say so (Appendix A.2–A.3), because
Decision 6's sentence "does not widen `veyq_node` with columns of its own" reads, today, as forbidding it.

## Owner decisions

Each lists options, a recommendation, and what it binds. Q-numbers are stable for the conversation.

1. **`NodeKind`: open or keep closed?** *(ADR 0079 contradicts itself, § What was measured.)*
   - (a) keep the closed enum; text only. Core keeps decoding Memories' kinds; every new application edits `_common`.
   - (b) **open it**: `_common` `NodeKind` = `type: string`, `pattern: ^[A-Z][A-Z0-9_]{0,15}$` (fits `veyq_node.kind
     VARCHAR(16)`, `core_baseline.sql:191`), visibility `structure`; each application declares its kinds in its own spec;
     an unknown kind is refused by the fail-closed seams (`NodeKindRules` → `illegal_structure`, `ShareGuestWrites` →
     `illegal_scope`), not by JSON decoding. Lands **together with #329** (`PublicShare.rootKind` takes the open type and
     `DROP_SHARE` retires before a kind could be spelled `drop`); #328's slug claim is unaffected.
   - Recommendation **(b)**, in Part 2.1. Binds: ADR 0079 Consequences (replace "not decided yet").
2. **Node `wrappedMeta` + `metaVersion`: where?**
   - (0) **stay in core, described as the node's sealed application payload** (format = the kind's application's,
     versioned per Decision 7, CAS per ADR 0077); no wire or schema change.
   - (a) rename to a generic name (e.g. `appPayload`/`payloadVersion`): wire + core baseline + pin; ≈ 8 core main files
     (inventory D §6a), `x-veyq-concurrency` field, ~20 Memories web files. Buys a name only.
   - (b) a Memories table + endpoints (inventory D §6b): loses the atomic create and the meta-in-`listNodes` that browse
     sorting relies on (`docs/features/memory/enablers/en.memory.node-meta.md` § *Materialize, don't recompute*), needs
     new erasers (`validate_node_subtree_erasure.py`, `validate_scope_erasure_arity.py`), probes and postures.
   - Recommendation **(0)**. Binds: Appendix A.2.
3. **`sealedDropPrivateKey`: whose?** Uploader shares are core (ADR 0079 Decision 2) and core alone writes the key (V3).
   - (0) **stay on `veyq_node`, described as the share root's drop private key** (sealed under the root's content key;
     Memories its first user). No change.
   - (1) move to a core table (`veyq_share_drop_key`), no wire change — removes the cross-writer hazard
     `DynamicUpdateEverywhereTest` records (inbox #224) but is not part of this split.
   - (2) Memories-owned: needs a share-creation seam holding set-once atomicity and the `ShareCreateRaceSliceTest` race;
     high cost for a field core's own uploader mechanism needs.
   - Recommendation **(0)**; (1) as an inbox row if the owner wants `veyq_node` slimmer. Binds: Appendix A.3.
4. **Guest-media operations and `PublicShare.media`.**
   - (a) **move both** (Slices 1.3 + 1.4): a core `ShareGuestGate` service, Memories' own resource and spec, `listShareMedia`
     replacing `PublicShare.media` (one more request when a viewer opens a share; a correct password token never spends
     the attempt budget, `SharingHandler.kt:759`).
   - (b) move only the two write ops; keep `media` in the resolve (core keeps `ShareMediaRow` and `SharedSubtreeContributor.rows`).
   - (c) keep both; text only.
   - Recommendation **(a)**: it is the one place where core's contract carries Memories *behaviour*, ADR 0079 already
     names it, and (a) also lets `CompleteItem`/`UploadCompletion` leave core (V7). Binds: Appendix A.4.
5. **`BlobRole`.**
   - (a) **keep closed in core's `blob` spec, text says the values are Memories'**; open it with the next blob-owning
     application (#327), where the new role is an ADR 0072 stored-format decision anyway (V-correction: the crypto
     encoder refuses unknown roles).
   - (b) open now to a bounded token in core with the values documented by Memories. Admits nothing a client can seal.
   - Recommendation **(a)**.
6. **The Memories columns on `veyq_node`** (`cover_blob_id`, `cover_byte_size`, `media_revision`, `delete_revision`).
   - (a) **drop `cover_byte_size`** (write-only, V10) and **move the two revision counters** into a Memories table keyed
     by `node_id`; keep `cover_blob_id` until the `veyq_node_attachment` table ADR 0079 schedules with #327.
   - (b) all four out now (cover into the Memories table; `commitCoverBlob` then writes Memories' own row and
     `NodeSubtreePurge`'s cover phase moves into Memories' eraser).
   - (c) leave all four (ADR's current timing).
   - Recommendation **(a)** — it removes what is Memories-only without pre-empting the attachment design.
7. **`veyq.media.*` configuration.** (a) **move `veyq.media.trash.*` into a Memories `@ConfigMapping`** (keys unchanged,
   so no operator-visible change); keep `veyq.media.upload.*` as named (core reads it) or (b) rename the upload keys to
   `veyq.blob.upload.*` — an operator-visible config change that must be answered on compose **and** Helm. Recommendation
   **(a)** only.
8. **Ordering against the `platform-api` idea and #335.** Part 1 adds a Memories spec that `$ref`s `_common`; its models
   map to core's generated package today (`SpecSets.kt:12-15`). If a `platform-api` module later owns `_common`'s
   generated models, every Memories import of them is re-pointed once — mechanical, and the same for the existing two
   specs. Recommendation: **do not wait**; but put nothing *new* into `_common` beyond the three share parameters until
   that is decided. #335 touches `ScopeAccessWorld` and the probe classifications that Slice 1.3/1.4 edit: run them
   **sequentially, not in one wave** (either order).
9. **`SpecSets` owner derivation from sub-directories** (the inventory's proposal). Recommendation: **not now** — with
   one application the hard-coded pair fails loudly (`requireNoStray`), and a derived set would need rule (d) of
   `validate_backend_module_graph.py` re-written in the same commit (V13). Pain before tool: do it with the second
   application. If the owner wants it now, it is Slice 1.0 below.

## Part 1 — phases

Order is contracts → backend → web inside each slice; slices are independently provable and land in this order. Every
slice starts with `git status --short` (a dirty shared spec is stop-and-confirm) and records the baseline exit codes
**before its first edit**: `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`,
`(cd backend && ./gradlew check)`, `(cd web && npm run generate:api && npm run check)`.

- [ ] **1.0 (only if decision 9 = now) `SpecSets` owners from directories.** `SpecSets.kt` derives one set per
  sub-directory of `contracts/api/` (package `me.raddatz.veyq.<dir>.generated`); in the **same commit**
  `validate_backend_module_graph.py` rule (d) derives package roots the same way instead of reading `SpecSet(` literals.
  Green on the untouched tree first. Bite: forge `contracts/api/forged/x.openapi.yaml` → the build refuses a set no
  module generates; forge a second `SpecSet` package in two modules → (d) red. Done when: both guards and
  `./gradlew check` green with no spec moved.

- [ ] **1.1 Text pass (contracts + comments).** Needs Appendix A.1–A.3 in the ADR. Sites: the "Text" cells of the
  target table, `contracts/platform/capabilities.json:8`, and core comments naming Memories kinds
  (`grep -rn 'MEMORY\|CHAPTER\|K_mem' backend/core/src/main` — comments only today, inventory D §3). Locator for the
  prose (two keys, because one key misses "photo"/"cover"): `grep -niE 'memor|chapter|k_mem' contracts/api/*.openapi.yaml contracts/platform/capabilities.json`
  and `grep -niE 'photo|media|cover|bucket|favorite' contracts/api/*.openapi.yaml` (expected noise: "in memory", the
  S3 *store bucket*, "recovery", public `share`s — inventory C Part 1). Use-case ids cited in core summaries
  (`uc.memory.delete-node`) **stay** — the node trash's use-case is filed under `memory` (`docs/apps/memories.md`
  says so); re-filing it is an inbox row, not this CR.
  Proof that only text moved — a comparing locator, run against the slice's base hash `<base>`:
  ```
  python3 - <<'EOF'
  import subprocess, yaml, pathlib, sys
  PROSE = {"description", "summary", "example", "examples", "x-veyq-rationale"}
  def strip(n):
      if isinstance(n, dict): return {k: strip(v) for k, v in n.items() if k not in PROSE}
      if isinstance(n, list): return [strip(v) for v in n]
      return n
  bad = []
  for p in sorted(pathlib.Path("contracts/api").rglob("*.openapi.yaml")):
      old = subprocess.run(["git", "show", f"<base>:{p}"], capture_output=True, text=True).stdout
      if old and strip(yaml.safe_load(old)) != strip(yaml.safe_load(p.read_text())): bad.append(str(p))
  print("shape changed:", bad or "none"); sys.exit(1 if bad else 0)
  EOF
  ```
  One-shot is enough here (the next spec edit is a shape edit by definition; a permanent "nothing but prose changed"
  guard has no meaning). No backend phase, no web phase (`backend/CLAUDE.md`: description-only). Docs: none beyond the
  ADR paragraphs. Done when: the script prints `none`, the guard glob is green, `validate_field_visibility.py` and
  `validate_write_concurrency.py` included.

- [ ] **1.2 Web: core stops importing Memories' generated clients; the guard learns it.** Order guard → red → fix →
  green (the defect is live, V12):
  1. Extend `scripts/validate_web_core_imports_no_applications.py`: the application also owns every generated client
     whose `generate:api` run reads a spec under `contracts/api/<application>/` — **derived from `web/package.json`'s
     `-i`/`-o` pairs**, never listed, so `share-media` (Slice 1.3) is covered the day its line lands. Exempt
     `src/applications.ts` as today; **no** exemption for `api/client.ts`. Run it: red on `shared/image/mediaDeriver.ts`
     and `api/client.ts` — exactly those two (V12 locator: `grep -rln "generated/media\|generated/favorites" web/src | grep -v features/memory | grep -v '\.test\.ts$'`).
     Positive control: a core import of `api/generated/blob` stays green. Docstring: the three fields, and its new limit
     (a client generated outside `generate:api` is not seen).
  2. `mediaDeriver.ts` types its tiers from core's generated `BlobRole` (`Extract<BlobRole, 'THUMBNAIL' | 'PREVIEW'>`),
     because `features/spaces` (core) uses it too — moving it into `features/memory` is not an option.
  3. The five Memories factories leave `api/client.ts` for a Memories module (e.g. `features/memory/api.ts`) built on an
     exported core `configuration()` helper. Readers re-pointed first: `grep -rlnE "\b(mediaApi|bucketsApi|favoritesApi|itemFavoritesApi|ownItemFavoritesApi)\b" web/src`;
     every `vi.mock` of `api/client` in a Memories test that stubs one of them must follow (`grep -rln "vi.mock(.*api/client" web/src/features/memory`)
     — and is proven to bite by making the moved real factory throw (`web/CLAUDE.md`).
  4. `web/stryker.mutate.json`: neither file is in scope today (`grep -n "client.ts\|mediaDeriver" web/stryker.mutate.json` → none) [M].
  Done when: the guard is green, `npm run generate:api && npm run check` green, a `validate_guard_gaps.py` case is added
  only if the new limit is documented as a gap.

- [ ] **1.3 Guest-media operations move to `memories/share-media.openapi.yaml`.** Wire bytes unchanged (same paths,
  methods, bodies, codes); spec file, tag, generated package and implementing module change. Needs Appendix A.4.
  1. **backend, first and on its own (green, no spec change):** extract the guest gate from `SharingHandler` into a
     public core service (`ShareGuestGate`: active share → password → role capability → identity token → per-share rate
     limit, returning scope, root node id, root kind, identity id, or the same refusal outcomes). `SharingHandler`'s two
     media methods call it. Proof: every existing guest slice test unchanged and green, plus
     `python3 scripts/run_backend_races_on_mariadb.py` (a gate moves; `admitted()` must still run inside the attach's
     transaction under the share-row lock, `SharingHandler.kt` comment above `val admitted`).
  2. **contracts:** new spec with tag `share-media`; the two ops and five schemas move; `MediaId` moves; `ShareId`,
     `SharePassword` and the contributor-token header become `_common` `components.parameters` referenced by both specs
     (if the generator refuses an external parameter `$ref`, duplicate them and say so in both files). `web/package.json`
     gains its `-i ../contracts/api/memories/share-media.openapi.yaml -o src/api/generated/share-media` line in this commit.
     Predicted reds: `:core` no longer compiles `SharingResource`'s two overrides (removed in step 3); `OperatorBlindProbeTest`
     red until the two ops are classified under the Memories package; web red at the two imports.
  3. **backend:** Memories' `ShareMediaResource` (`@PermitAll` per operation — never a class default; `@RateLimit(key = SHARE)`
     on complete, the documented exemption on delete, copied from `SharingResource.kt:368-399`) calls `ShareGuestGate`,
     checks the root kind is one Memories `supports`, then `UploadCompletions`/its own delete. Core loses
     `SharingHandler.completeUploads`/`deleteMedia`, `ShareCompleteItem`, and `ShareGuestWrites.complete`/`deleteOwn`.
     Readers to re-point first: `grep -rn "completeShareMedia\|deleteShareMedia\|/media\b" backend/*/src/test backend/src/test`
     (routes, not only names — `changes/README.md` § *greps its route*), `RateLimitPostureSweepTest`,
     `AdminChannelPostureSweepTest`, `ShareGuestWritesPartitionSliceTest`.
  4. **Route proof:** one slice test in `:apps:memories` that calls **every** `/api/shares/…` route of both specs and
     asserts each reaches its handler (status/body anchor, not "not 404" alone), with a control that a core route still
     answers after the Memories class registered. This is the root-`@Path` hazard's bite.
  5. **web:** Memories' share code imports from `generated/share-media`; `scripts/run_web_e2e.py` (records HTTP calls
     against `contract_specs.specs()`, recursive) needs no edit [M C]. Run the e2e suites from the worktree only as the
     machine notes say (worktree orchestrator, `VEYQ_INFRA_DIR=<main checkout>/infra`, `VEYQ_JAR=<worktree jar>`).
  Docs: ADR 0079 Consequences (remove "the guest completion item is media-shaped" once 1.5 lands), `docs/apps/memories.md`
  (Contracts line), `uc.sharing.share-contributor`/`share-uploader` only where they name the spec file. Coverage: refs
  that move module/package are re-pointed (`python3 scripts/validate_coverage.py`). No baseline change.
  Done when: `./gradlew check`, MariaDB race run, `npm run generate:api && npm run check`, the e2e share suites, the
  guard glob — all green; `grep -n "ShareMedia\|completeShareMedia\|deleteShareMedia\|MediaId" contracts/api/sharing.openapi.yaml` empty.

- [ ] **1.4 `PublicShare.media` becomes `listShareMedia`.** Wire change (a removed required field + a new operation).
  1. contracts: `GET /api/shares/{shareId}/media` in `memories/share-media` (view gate: password, `canView` — an
     UPLOADER link answers as today's resolve does for it: no media), response `ShareMedia[]` + `ShareMediaDerivative`
     (moved); `PublicShare` loses `media` from `properties` and `required`. Locked-response text (`sharing.openapi.yaml`
     `PublicShare` description) loses `media`.
  2. backend: `ShareGuestGate` gains the view gate; Memories serves the rows (its `MediaSharedSubtreeContributor`
     logic, with its **own** live predicate — `ShareTrashedMediaSliceTest` bites the listing and the blob GET separately
     and must keep doing so); core's `SharedSubtreeContributor` keeps only `authorizedBlobIds`; `ShareMediaRow`,
     `ShareDerivativeRow` and `rows` leave core. Probe classification for the new op.
  3. web: `share.ts` (`for (const m of share.media)`, :767) reads the new call; `resolvePublicShare` unchanged (it reads no media).
  Proof of the removal: a raw read in a share e2e suite and in a backend slice test that `resolveShare`'s JSON has **no
  `media` key** (`…Raw` + `.raw.json()`), each with a positive control on the same response (`nodes` present). Docs:
  `uc.sharing.share-public-viewer` / `share-contributor` where they describe the resolve body; ADR 0029/0030 only if they
  name the field (`grep -n "PublicShare\|\.media" docs/decisions/0029-*.md docs/decisions/0030-*.md`); security-model
  unchanged (no new operator-visible fact: the server already sees the resolve) — say so in the PR text, and re-read
  `wiki/en/is-my-data-safe.md` + `wiki/de/sind-meine-daten-sicher.md` if `uc.sharing.*` is edited.
  Done when: as 1.3, plus the two raw-JSON absence tests.

- [ ] **1.5 `UploadCompletion` and `CompleteItem` leave core.** After 1.3 core constructs no `CompleteItem`
  (V7). Locator: `grep -rn "UploadCompletion\|CompleteItem\|DerivativeDeclare" backend/core/src backend/apps backend/src`.
  The dispatcher and seam move into `:apps:memories` (or the seam is deleted and Memories calls its implementation
  directly — decide at implement by whether a second Memories path still dispatches by kind). `CoreAloneSliceTest` and
  `ModuleCompositionTest` drop the seam; `validate_kernel_imports_no_slices.py:23,204` and ADR 0079's "nine interfaces"
  sentence are edited. Done when: `./gradlew check` green and `grep -rn "mediaId\|wrappedExif" backend/core/src/main` names no DTO.

## Part 2 — follow-on CR (draft; split out into its own file once decisions 1, 2, 3, 5, 6, 7 are answered)

```yaml
# planned front matter of the follow-on — not validated while it sits in this block
id: cr-platform-node-kind-and-columns
satisfies: [en.memory.tree, uc.sharing.share-uploader]
adrs: ["0079", "0072", "0031", "0061"]
```

- **2.1 `NodeKind` opens, with #329 (decision 1b).** contracts: `_common` `NodeKind` → bounded token, visibility
  `structure`; `PublicShare.rootKind` (#329); Memories declares `MemoriesNodeKind`. Guards re-pointed first (green on the
  closed enum): `validate_contracts_common.py:57` family `node-kind` (drops out — an open token is no family),
  `validate_field_visibility.py` (`enum` → `structure`), `validate_kernel_imports_no_slices.py` `APPLICATION_KINDS` and
  `validate_web_core_imports_no_applications.py` `KIND` (both keep biting on literals; the web forgery in
  `validate_guard_gaps.py:849` imports a generated constant that will no longer exist — re-anchor it on a literal),
  guard-gaps case 49 (`:782-787`, same). backend: `NodeKind.decode` sites (`ContentResource.kt:106,117`,
  `SharingResource.kt:271-272`) take the string; a test that an unknown kind is refused by the seam with its code
  (`illegal_structure` on create, `illegal_scope` on share), with a `FOLDER` control. web: a core `FOLDER` constant
  replaces `NodeKind.Folder` (`grep -rln "NodeKind\.Folder" web/src | grep -v features/memory` → registry, tree, browse,
  DeleteNodeDialog, MemoryHomeView [M]); Memories uses its own constant; `ShareView` dispatches on `rootKind`;
  `DROP_SHARE` retires. #328 (node URLs) is unaffected but should not run in the same wave (both edit `registry.ts`).
- **2.2 `veyq_node` sheds Memories-only columns (decision 6a).** Core baseline drops `cover_byte_size`, `media_revision`,
  `delete_revision`; Memories baseline gains `veyq_memory_node(node_id PK, scope_id FK → veyq_scope ON DELETE CASCADE,
  media_revision, delete_revision)`; both pin lines change in the same commit; every database recreated. Needs a
  `NodeSubtreeEraser` (`validate_node_subtree_erasure.py`), scope erasure by cascade or eraser
  (`validate_scope_erasure_arity.py`), `@DynamicUpdate` (`DynamicUpdateEverywhereTest`), and a race-safe first insert:
  the counters are bumped by every attach/trash (`MediaStore.kt:175-177`), and today they ride the node row lock
  (`MediaStore.kt:461`) — the new lock order is a hypothesis to be re-derived from the first red
  (`run_backend_races_on_mariadb.py`). `NodeCoverSliceTest` loses its `coverByteSize` assertions.
- **2.3 Cover (only if decision 6b).** Cover id into the Memories row; `commitCoverBlob` writes it; `NodeSubtreePurge`'s
  cover phase and `SharingStore.subtreeBlobIds`' cover read move behind `NodeSubtreeEraser`/`authorizedBlobIds`;
  `validate_blob_reference_arity.py` re-pointed from `veyq_node.cover_blob_id` before the move.
- **2.4 `veyq.media.trash.*` into Memories (decision 7a).** Measure first whether a second `@ConfigMapping` under the
  `veyq` root and the keys left in core's `application.properties` boot (unknown-key validation) [I — not measured];
  `@WithDefault` keeps the defaults; compose/Helm unchanged because the keys are.
- **2.5 Node payload / drop key** only if decisions 2 or 3 pick a move — then this part is re-refined with inventory D §6
  as its locator list; it is not planned here.

## Risks and what is not proven

- **Route resolution** for `memories/share-media` under `sharing`'s root is inferred from a precedent, not from
  generated code (none in this tree). Slice 1.3 step 4 is the proof; until it runs, a regression would look like 404/405
  on guest uploads only.
- **Gate extraction** (1.3.1) moves the order of checks; the outcome codes are pinned by existing guest slice tests, the
  lock/transaction placement of `admitted()` only by the MariaDB race run.
- **A second request on share open** (1.4) changes viewer latency and the e2e choreography; the password budget is
  unaffected (V6, `SharingHandler.kt:759`). Not measured.
- **Shared `components.parameters` in `_common`** is new to this repo; openapi-generator resolving them across files is
  [I]. Fallback named in 1.3.
- **Config split** (2.4) and **counter lock order** (2.2) are [I] until measured.
- **Not covered by this plan:** re-filing `en.memory.tree` / node-trash use-cases under a platform feature id;
  `features/memory` rename and the single-claim web slots; the capability-tag registry's missing owner field
  (inventory B #9); `BlobRef` as a shared read-back shape (wait for a second blob reader).

**Size, honestly:** Part 1 is one coherent CR (≈ 3–4 sessions, one design-bearing step: the gate service). Part 2 is a
second CR of similar size whose shape depends on four owner answers; writing it as phases of this one would freeze
decisions not yet taken. Cut X (decisions 2b/3(2)) would add a third CR roughly as large as the first two together.

## Definition of done (Part 1)

- `contracts/api/sharing.openapi.yaml` carries no media operation, parameter or schema, and `PublicShare` no `media`;
  `contracts/api/memories/share-media.openapi.yaml` carries them.
- No core spec description names a Memories operation, kind, key or plaintext (the 1.1 locators return only the
  documented noise).
- `validate_web_core_imports_no_applications.py` judges imports of application-generated clients and is green; core
  imports none.
- Core holds no media-shaped DTO (`CompleteItem`, `ShareCompleteItem`, `ShareMediaRow`, `ShareDerivativeRow` gone) and
  ADR 0079 Consequences no longer lists the guest completion item.
- `./gradlew check`, `scripts/run_backend_races_on_mariadb.py`, `npm run generate:api && npm run check` (web and
  admin-web), the e2e share suites, and `for f in scripts/validate_*.py; do python3 "$f"; done` green **on the committed
  tree** (no remote: a local run is the strongest proof available). `docs/coverage/` re-pointed for the three
  `satisfies:` use-cases, `python3 scripts/validate_coverage.py` green.
- What the work taught moves to `backend/CLAUDE.md` (a cross-spec route under one root, if it needs a rule) and ADR 0079.

## Appendix A — paragraphs for ADR 0079 Consequences (to be decided before implementation)

Each paragraph encodes the owner decision named in brackets; wording is a draft for the refine conversation.

**A.1 [all] Contracts by owner.** "A core spec describes what core does. Where a value's meaning belongs to an
application — a node's sealed payload, a blob role, a kind — its description says whose it is and names no application's
plaintext, operation or key. An operation whose behaviour is an application's is in that application's directory, even
when its path sits under a core route (`/api/shares/{shareId}/media` is Memories')."

**A.2 [decision 2 = 0] The node payload slot is core's.** "Core gives every node one sealed, versioned payload
(`wrappedMeta`, `metaVersion`): core stores it, compare-and-sets it ([ADR 0077](../docs/decisions/0077-a-write-carries-the-version-it-read.md))
and lists it with the tree, and never opens it; its plaintext format is the node kind's application's, versioned per
Decision 7. It is a platform column, not an application widening `veyq_node` (Decision 6): Memories' date, place and
cover live inside it. A second application uses the same slot for its own kinds."

**A.3 [decision 3 = 0] The uploader drop key is the share mechanism's.** "An UPLOADER share's drop key pair belongs to
the uploader-share mechanism, which is core's (Decision 2): `sealedDropPrivateKey` is the share root's, set once by core's
share creation and sealed under the root's content key ([ADR 0031](../docs/decisions/0031-uploader-public-share.md) point 7, Memories its
first user). It stays its own field and never joins the node payload — a payload edit must never carry it."

**A.4 [decision 4 = a] Application guest operations.** "Core owns the public share and its guest gate — active share,
password, the role's capability, the guest identity's token, the per-share rate limit — and exposes the gate as a service
an application calls before its own guest read or write. Core's share contract keeps only kind-neutral guest operations
(resolve without application rows, terms, blob GET, identity, upload start and windows); an application serves its rows
and its writes on its own route under the share, and checks that the share's root kind is its own."

**A.5 [decision 1 = b] Node kinds are open.** Replaces "The node `kind` open to values core does not interpret is not
decided yet": "The node `kind` is an open, bounded token on the wire. Core names `FOLDER` only; each application declares
its kinds in its own contract; a kind no application claims is refused by the fail-closed seams, never by decoding.
`PublicShare` carries the root's kind (inbox #329)."

**A.6 [decision 6 = a] Memories-only node columns.** "`veyq_node` carries no column only one application reads or
writes: the media revision counters live in Memories' own table keyed by `node_id`. The cover blob reference stays on
`veyq_node` until the generic node-attachment table (#327) replaces it."

## Retrospective

Written at close.
