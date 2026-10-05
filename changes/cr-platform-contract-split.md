---
id: cr-platform-contract-split
title: "DRAFT — the contracts say whose they are: neutral core specs, three access modes, open node kinds, Memories' guest media out of core"
status: planned
satisfies: [uc.sharing.share-contributor, uc.sharing.share-uploader, uc.sharing.share-public-viewer]
adrs: ["0079", "0072", "0077", "0029", "0030", "0031", "0012", "0013", "0044"]
invariants: [operator-blind E2EE, API-first (contracts are the source of truth), self-hostable by default, crypto wire and stored formats unchanged]
---

## Summary for the owner

**Goal.** Core's contracts and code describe the platform in the platform's words, and whatever only Memories
means moves to Memories. After this CR a second application could add its own guest operations, kinds and texts
without editing a single core spec or core main file.

**What moves.**
- The share roles get neutral wire names: `VIEWER`/`CONTRIBUTOR`/`UPLOADER` become `READ`/`READ_WRITE`/`WRITE_ONLY`.
  The screens keep their labels.
- The node kind becomes an open token. Core knows only `FOLDER`, and Memories brings `MEMORY` and `CHAPTER`.
  `PublicShare` then also carries `rootKind` (inbox #329).
- Memories' guest media goes to Memories: uploading and deleting a guest's photos, and the list of photos plus
  uploader keys a viewer sees. Core's `PublicShare` then carries no application data at all.
- The core web no longer imports Memories' generated clients.
- The completion item core hands to an application becomes generic (`ownerId` in place of `mediaId`).
- Core's specs are re-worded neutrally: tree, tags, examples, roles, drop key, blob roles, and the instance,
  tenant, scope and task texts.

**What deliberately stays in core.** Link management and consent. The resolve, which returns the link plus the
shared nodes and their keys. The guest blob transport (start, windows, download). Guest identities. The drop key
of a `WRITE_ONLY` link. The node's sealed payload slot (`wrappedMeta`/`metaVersion`) and its content key. The
`UploadCompletion` seam. The closed `BlobRole`. This is an **intermediate state** (D9), not the end state.

**Slice order** (each slice runs contracts → backend → web and is proven on its own; the estimates are honest,
not optimistic):

| # | Slice | Est. sessions |
|---|---|---|
| 0 | Docs first: ADR 0079 paragraphs, platform definition, use-case sentences (Appendix A) | 0.5 |
| 1 | Roles renamed to `READ`/`READ_WRITE`/`WRITE_ONLY` (mechanical) | 1 |
| 2 | Text pass over core specs, with a script that proves only prose changed | 0.5–1 |
| 3 | Core web stops importing Memories clients; the guard learns to see it | 0.5–1 |
| 4 | Node kind opens to a token | 1–1.5 |
| 5 | `rootKind` on `PublicShare` (#329) | 0.5 |
| 6 | Guest media moves to Memories (gate service, write operations, media+identities read) | 3–4 |
| 7 | `CompleteItem` becomes generic | 0.5 |
| | **Part 1 total** | **≈ 8–10** |

**Deferred (each its own CR later, only recorded here).** The Memories columns on `veyq_node`, the
`veyq.media.*` config prefix, the `ScopeAccessWorld` split (#335, after slice 6), attachments as a core spec
(#327, with the second application), links as a credential, and deriving `SpecSets` owners from directories.
Application-specific roles stay an extension point (a policy seam) and are not built.

**Two points need your look** (marked CONFLICT below): at D1, your "roles in the transport become opaque"
against D10's "do not open `BlobRole`", and at D1, "through the normal Memories API" against D6. Nothing is
implemented before slice 0 has landed and you give an explicit go.

---

> **DRAFT, not implementable yet.** The owner's decisions are taken (2026-10-06, § Owner decisions). Per
> [`docs/ai/README.md`](../docs/ai/README.md) § *Refined state comes before the CR*, Slice 0 (the ADR 0079
> paragraphs and use-case sentences of Appendix A) must land first. It is refinement work and may run in a refine
> session. Implementation then waits for an explicit go.

## Goal

Every spec under `contracts/api/` describes what its **owner** does. An operation, schema or field whose
behaviour belongs to an application lives in that application's directory. Core's wire speaks in access modes
and open kinds, never in Memories' words. Concretely:
- the share roles are renamed to neutral access modes;
- the node kind opens to a bounded token, and `PublicShare` gains `rootKind`;
- Memories' guest-media contract leaves core: the two `/api/shares/{shareId}/media` writes, their schemas, and
  the media rows plus uploader identities the resolve serves today;
- core's completion item becomes generic;
- core's prose is neutral.

Storage-level moves (the `veyq_node` columns, config, attachments) are Part 2.

## Context & constraints

- [ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md): its Decisions,
  and its Consequences paragraph "What core still knows of Memories", which this CR rewrites point by point
  (§ Before/after). See also [`docs/apps/README.md`](../docs/apps/README.md) and [`docs/apps/memories.md`](../docs/apps/memories.md).
- [`backend/CLAUDE.md`](../backend/CLAUDE.md):
  - specs are split by owner directory, `_common` is core's and is ONE generated type;
  - a description-only spec edit needs no backend phase;
  - one tag is one generated interface with ONE root `@Path`;
  - a baseline may change until the release, but only together with its pin line.
- [`web/CLAUDE.md`](../web/CLAUDE.md):
  - run `generate:api` before `check`;
  - an absence asserted through the generated client proves nothing (read `…Raw(…)` + `.raw.json()`);
  - `vi.mock` resolves by path, so a move disarms mocks.
- No release yet: `scripts/applied_migrations.sha256:16` reads `release: none` [M]. Wire and baselines may change,
  each change explicit and proven. The owner recreates his dev DB.
- Not up for debate: operator-blind; crypto stored formats. Seal purposes bind the purpose string and object ids,
  never a JSON field name, a spec file or a share role (V26; [ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md)).
  Renaming a purpose would be a suite `v2` (ADR 0079 Decision 9).
- Process: at most five agents in parallel. #335 runs **sequentially** after slice 6, not in the same wave,
  because both edit the probe classifications and `ScopeAccessWorld`'s users.

## Owner decisions (all DECIDED 2026-10-06)

The decisions D1–D11 replace the draft's earlier question numbers:

| Earlier question(s) | Now |
|---|---|
| 4 + 10 | D1 |
| 1 + 12 | D5 |
| 2 | D4 |
| 3 | D3 |
| 5 | D10 |
| 11 | D8 |
| 13 | D9 |
| 6, 7, 8, 9, 14 | D11 |

The owner's reasoning is paraphrased. [M] = re-read in this session (§ What was measured).

**D1 — Sharing split: link mechanics stay core, application data leaves the resolve.**
- **Stays core.**
  - Link management: create, list, revoke, reactivate, reissue, update and delete, with password, expiry, note
    and name.
  - Consent: `getShareTerms`.
  - `resolveShare`, whose body shrinks to the link data plus `nodes` (structure and content keys). The nodes stay
    because the viewer derives every descendant's key through the `wrappedContentKey` chain, and every
    application needs the shared subtree.
  - The guest **transport** of bytes: `startShareBlobUploads`, `presignShareBlobUploadWindows`,
    `resolveShareBlob`. Blob mechanics are already core for members.
  - The guest **identity**: `createShareIdentity`, core's table and the `GuestIdentityUsage` seam
    (`core/sharing/GuestIdentityUsage.kt`, implemented by `memories/media/MediaGuestIdentityUsage.kt` [M]). Its
    sealed fields stay opaque.
- **Moves to Memories' spec.**
  - `completeShareMedia`, `deleteShareMedia` and the `ShareMedia*` schemas.
  - `PublicShare.media` and `PublicShare.identities`, which become **one Memories read operation**.
  - Afterwards `PublicShare` carries no application data. A second application attaches its data the same way,
    with its own operation.
- **Privacy travels with the data.** ADR 0030 point 8 holds: contributor identity ids are never served, only
  identities with a `wrappedIdentityKey`. The rule moves into the Memories operation in the same commit, and the
  view gate no longer needs to return uploader identity ids.
- **Same slice:** the `identities` derivation (`SharingStore.uploaderKeys`, :397-402, reads `subtree.media` over
  ALL shares, V19) is re-pointed in that slice. `ShareGuestNamesSliceTest` moves along and bites through a
  raw-JSON read.
- **Path detail is decided at implementation.** The proposal is `GET /api/shares/{shareId}/media`, the root the
  two writes already share, so Memories' resource keeps one root `@Path`.
- *Rejected:* keeping `media` in the resolve and moving only the writes. Core would keep a media-shaped row type
  and the derivation it needs.

> **CONFLICT (D1 vs D10 — blob roles in the guest transport).** D1 says "roles in the transport become
> open/opaque". D10 says `BlobRole` stays closed until #327, and the guest-transport items keep `role` as the
> blob-role string they have today. The fact: `ShareBlobUploadRequestItem.role` is a closed
> `[ORIGINAL, THUMBNAIL, PREVIEW]` (`sharing.openapi.yaml:1642-1646` [M]). It is declared an intentional variant
> in `validate_contracts_common.py` (the comment above the tuple, :68-69 [M]). The crypto encoder refuses any other
> role anyway (`seal.ts:136,172-179` [M]).
> **This plan follows D10:** the enum stays closed, and slice 2 only re-describes it as "the blob's role, closed
> with `BlobRole`". The owner confirms, or says D1 meant more than text.

> **CONFLICT (D1's suggestion "through the normal Memories API" vs D6).** Memories' member read
> `listNodeMedia` is `bearerAuth` (`memories/media.openapi.yaml:118` [M]). A guest on the anonymous `/s/` page has
> no session, and "a link is a credential to member operations" is D6's *later* CR. So until D6 lands, the guest
> read is its own Memories operation under the share. It **reuses** the member list's schemas where they fit:
> `UploaderKey {identityId, wrappedIdentityKey}` (`memories/media.openapi.yaml:1001-1011` [M]) is already exactly
> the shape `PublicShare.identities` serves. Memories also already computes it from its own rows for members
> (`MediaStore.uploaderKeys(rows)`, `memories/media/MediaStore.kt:434-439` [M], V30). Read this way, the
> suggestion holds for the data and the code, not for the route.

**D2 — Three access modes, renamed; "write" means what the application says.**
- **What changes.** Core keeps exactly three access modes. The wire values `VIEWER`/`CONTRIBUTOR`/`UPLOADER`
  are renamed to `READ`/`READ_WRITE`/`WRITE_ONLY`, as its own mechanical slice (slice 1) **before** the
  guest-media move.
- **Why now.** We are pre-release (`release: none`); the baseline and its pin line change together; the DB column
  `veyq_share.role VARCHAR(16)` fits (V27); the owner recreates his dev DB.
- **What stays.**
  - The UI labels, which are independent of the wire value (V29).
  - The capability ids `sharing.public-links` / `sharing.uploader-links`: they couple to the role by symbol,
    not by wire string (V28). Only their descriptions change.
  - The crypto: the role is bound in no seal or AAD (V26).
- **What "write" means is the application's**, through the existing `ShareGuestWrites` seam.
- **Extension point, recorded and not built:** application-specific roles open later through a **policy seam**,
  where core asks the application "is this action allowed for this role right now". There is no registry of
  roles.
- *Not renamed (out of scope, a separate decision if ever):* the header `X-Contributor-Token`, the field
  `contributorToken`, the error code `share_not_contributable`, the use-case ids `uc.sharing.share-contributor`/
  `share-uploader`, the i18n keys `share.roleViewer`…, and `ShareGuestWrites`.

**D3 — The drop key stays core.** It is the mechanism of a `WRITE_ONLY` link:
- the public key sits in the share row, sealed under `K_share`;
- the private key sits on the node, sealed under `K_mem`;
- it is set once (409 `drop_key_exists`/`drop_key_missing`, `sharing.openapi.yaml:91-92` [M]);
- core writes it atomically inside `createShare` (`SharingStore.kt:83-91` [M], V3).

The text becomes "drop key of the shared root node of a `WRITE_ONLY` link". The names `sealedDropPublicKey`/
`sealedDropPrivateKey` and the seal purposes `drop-private-key`/`drop-public-key` stay, because seal ids are a
stored format (ADR 0079 Decision 9). *Option for Part 2 only:* move the private key out of `veyq_node` into a small
table `veyq_node_drop_key`.

**D4 — `wrappedMeta`/`metaVersion`/`wrappedContentKey` stay core as generic mechanism.** They are the node's
versioned sealed payload slot and the content key of a shareable node; the text is generic and nothing moves.
- *Rejected:* a Memories table with endpoints. It would lose the atomic create and the meta in `listNodes` that
  browse sorting relies on, and would need new erasers, probes and postures. The next application would want the
  same slot back.
- *Rejected:* a generic rename (`appPayload`). It buys a name for a wire + baseline + ~20-file edit.

**D5 — `NodeKind` opens as a bounded token (option B).**
- **The token.** `_common` `NodeKind` becomes `type: string`, `pattern: ^[A-Z][A-Z0-9_]{0,15}$`,
  `x-veyq-visibility: structure`. The wire values stay the strings `FOLDER`/`MEMORY`/`CHAPTER`.
- **Who knows which kind.** Core knows only `FOLDER`, and applications bring their kinds and rules:
  - backend: the `NodeKindRules` seam (`handles(kind)`/`parentAllows(kind, ParentSlot)`), which fails closed when
    no rule claims a kind (`core/node/NodeKindRules.kt:22-35` [M]);
  - web: the registry's `registerKind`.
- **What is removed.**
  - The backend decode sites (V32).
  - The generated enum in the core web files (V32). Core types the kind as `string` plus a `FOLDER` constant, and
    the registry is keyed by string. `ShareBodyKey` and `DROP_SHARE` stay separate symbols; the lowercase `'drop'`
    can never match the uppercase pattern.
  - The enum-family pin at `validate_contracts_common.py:57` [M].
- **No crypto or DB change.** `veyq_node.kind` is `VARCHAR(16)` with no CHECK.
- **Order:** the kind opens FIRST (slice 4), then #329 (slice 5). `rootKind` is a string from birth and nothing
  is edited twice.
- **Consequence, stated:** the web loses compile-time exhaustiveness over kinds. The registry's fail-closed
  behaviour and its tests carry that guarantee instead.
- *Rejected:* keeping the closed enum. Every new application would have to edit `_common`, and core would keep
  decoding Memories' kinds.

**D6 — Link-as-credential: a later direction, not Part 1.** The owner agrees with the reasons against doing it
now:
- privacy filtering per handler, against one dedicated guest path;
- the rate-limit and anonymity posture of every member operation that admits a link;
- blob authorization by subtree;
- a second identity type in auth, which needs its own ADR.

The direction is recorded: "a link is another credential accepted by core's gate; each application operation
declares who may call it: member only, or link with read/write mode". It belongs to a separate CR: reads first
(media list, blob download), then writes. That CR then deletes the guest mirrors this CR moves. It is not designed
here.

**D7 — Attachments are platform infrastructure; the platform definition is written down.**
- **What the owner floated:** a "Files application providing media up/download that Memories depends on". It
  breaks ADR 0079's layer rule (an application → core only, never another application; the module-graph guard
  enforces it), and it mixes infrastructure with product.
- **Decided direction:**
  - Generic **attachments** (a blob, its role, sealed metadata and a version, on a node) are core infrastructure =
    inbox #327.
  - They are formalised as a core spec **when the second application needs them**. The paper test: expenses
    receipts, inventory photos, storage files.
  - A future Files/Storage *product* application sits on core attachments and has no edge to Memories.
- **The owner's platform definition** goes into ADR 0079 and `docs/apps/README.md` (Appendix A.2).
- **Stated honestly:**
  - today attachment completion is application work (`UploadCompletion`);
  - Memories columns still sit in `veyq_node`;
  - "every application needs attachments" is an assumption the second application tests.

**D8 — `UploadCompletion` stays core; `CompleteItem` becomes generic (former question 11, option 1).** Memories is
one implementation of the seam.
- **The new item:** `ownerId`, `nodeId`, `originalUploadId`, `derivatives`, and the application's sealed payloads
  as opaque values.
- **Small, and it follows slice 6.** After slice 6, `mediaId` is no longer a name on any core wire, so the KDoc
  premise at `UploadCompletion.kt:14-17` [M] ("core's own share contract … builds these … renaming `mediaId` is a
  wire change") lapses (V33).
- **Pins:** `CoreAloneSliceTest` :89, :94 [M], `UploadCompletionPartitionSliceTest`,
  `validate_kernel_imports_no_slices.py` :23, :204 [M]. `docs/apps/files.md:30` and `docs/apps/memories.md:43` [M]
  keep describing the seam as core's, which stays true.
- *Rejected:*
  - (2) moving the seam into Memories, which optimises for one application and drops one of ADR 0079's nine seams;
  - (3) the status quo, whose premise is gone.

**D9 — Cut M is "until further notice, an intermediate state", not the end state.** Only the larger cut leaves
`veyq_node` free of columns only Memories fills today: the node-meta pair (in practice written by Memories' web
code), the drop key, and the cover and revision columns. The platform-slot text (A.3, A.4) claims that a second
application uses the same slots, and that claim is re-examined when one does.

**D10 — `BlobRole` stays closed until #327.** The crypto encoder is closed too (`seal.ts:136` `ROLES`,
`:172-179` [M]); only the text changes (slice 2). The guest-transport items keep `role` as today's blob-role
string, and this CR does not open it. See the CONFLICT note at D1.

**D11 — Deferred to Part 2, each its own CR (recorded, not designed).** Listed in § Part 2.

## What was measured

Substrate: worktree branch `worktree-agent-a698ab9d335b4aaab` at `feb0f363` (a clean descendant of `main` @
`2f302bbd`, the CR's own two commits on top), 2026-10-05 / -06.
- **[M]** = read at the cited line in this session; **[I]** = inferred.
- V1–V25 come from the first draft's re-verification pass and still hold on this substrate where the slices lean
  on them. V26–V35 were measured for the owner's decisions.
- Inventory claims not listed here are re-checked by the phase that uses them (`changes/README.md` § *a
  handed-over table has a reader*).

| # | Claim | Evidence | Verdict |
|---|---|---|---|
| V3 | The drop private key is written by core only, set-once | `core/sharing/SharingStore.kt:83-91` (conditional update `… sealedDropPrivateKey is null` → `DROP_KEY_EXISTS`/`DROP_KEY_MISSING`) | confirmed |
| V4 | The drop key may not join the node-meta plaintext | `packages/crypto/src/seal.ts:56-58` | confirmed |
| V5 | Core runs the node-meta CAS | `core/content/NodesStore.kt:179-188` | confirmed |
| V6 | The guest gate is private to `SharingHandler`; the seam has `supports/start/window/complete/deleteOwn` | `core/sharing/ShareGuestWrites.kt:25-43`; gate steps inline in `SharingHandler.completeUploads`/`deleteMedia` (`activeShare`, `passwordCheck` :755-761, `canUpload`, `findIdentity`, `rateLimiter.allowUpload`, `writesFor` :734-737) | confirmed — what the move needs is a callable gate |
| V8 | `PublicShare.media` is required and media-shaped; core builds it from a seam | `sharing.openapi.yaml:1311` (`required: [role, passwordRequired, nodes, media]`), `:1381-1390`; `core/sharing/SharedSubtreeContributor.kt` (`ShareMediaRow`, `ShareDerivativeRow`, `rows`) | confirmed |
| V10 | `cover_byte_size` is written and never read by main code | `grep -rn "coverByteSize\|cover_byte_size" backend \| grep -v /test/` | write-only (Part 2) |
| V12 | Core web imports Memories' generated client | `web/src/shared/image/mediaDeriver.ts:8`; `mediaDeriver` is also used by core's `features/spaces`; `web/src/api/client.ts` builds `mediaApi/bucketsApi/favoritesApi/itemFavoritesApi/ownItemFavoritesApi` | confirmed |
| V17 | The write gate's re-check is a closure core builds and the application runs inside its attach transaction | `SharingHandler.kt:680`; `SharingStore.kt:234-238` (`PESSIMISTIC_WRITE` + `refresh`); `memories/media/MediaStore.kt:124-126` | confirmed |
| V18 | Complete spends the per-share upload budget, delete does not | `SharingHandler.kt:666`; `deleteMedia` :691-704; `SharingResource.kt:370` `@RateLimit(key = SHARE)` vs `:392` `@RateLimitExempt` | a single unconditional gate would change delete's posture |
| V19 | The served identities are the uploader identities **of any share** whose media lie in the live subtree | `SharingStore.kt:397-402`; `SharingHandler.kt:540-545` (nulls every other `identityId`); `ShareGuestNamesSliceTest.kt:58-83` (uploader on its own UPLOADER link, a VIEWER link served exactly that identity) | not a per-share set |
| V20 | The subtree's live predicate is core's, the media rows' is the contributor's | `SharingStore.kt:405-429`; twin `subtreeBlobIds` :444-449; `ShareDeletedNodeSliceTest` bites each | confirmed |
| V21 | Every core class is public | `grep -rn "^internal " backend/core/src/main` → 0 | an application *could* call `SharingStore` |
| V22 | `OperatorBlindProbeTest` classifies by operationId over both generated packages | `OperatorBlindProbeTest.kt:224-225`, `:277`, `:417,446` | a moved op stays green; a new op is red until classified |
| V23 | The kind guards are text guards | `validate_kernel_imports_no_slices.py:111-115`; `validate_web_core_imports_no_applications.py:54`; forgeries `validate_guard_gaps.py:776-787`, `:847-850` | confirmed |
| V24 | `resolveShare` is rate-limit exempt by recorded decision | `SharingResource.kt:195-201`; `docs/crosscutting/cc.security.rate-limit.md` § Unbounded by decision | confirmed |
| V25 | Core's `VeyqConfig.Media` holds `trash()` (read only by Memories) and `upload()` (read by core) | `core/config/VeyqConfig.kt:24,35,331-340`; `memories/media/MediaTrashPurge.kt:29` | confirmed (Part 2) |
| V26 | **The share role is bound in no seal and no AAD** | `seal.ts:43-90` (`SEAL_PURPOSES`, `SEAL_TO_PURPOSES`): the only `role` field kind is the blob role (`blob-key` :48, `ROLES` :136, `:172-179`); `grep -rnE "VIEWER\|CONTRIBUTOR\|UPLOADER" packages/crypto` → comments only (`seal.ts:76,88`, `v1.ts:535-536`); `grep -rlE … contracts` lists no crypto vector file | rename is wire + storage + text only |
| V27 | Share role storage | `veyq_share.role VARCHAR(16) NOT NULL`, no default, no CHECK (`core_baseline.sql:321`); the baseline's only other mention is the comment :314; no Memories migration names a role; the longest new value has 10 characters | fits; the baseline hash changes through the comment |
| V28 | Capability ids couple to the role by symbol, not wire string | `SharingHandler.kt:725` (`role == ShareEntity.UPLOADER`); role words only in texts: `CapabilityIds.kt:12,15`, `capabilities.json` descriptions | ids stay |
| V29 | UI labels are independent of the wire value; no client state persists a role | `ShareDialog.vue:83-87` (constant → i18n key); the files touching `localStorage`/IndexedDB and a role name are `resolvePublicShare.ts`, `MemoryShareBody.vue`, `ShareView.vue`, and what they store carries no role (`ContributorSession = {identityId, contributorToken, uploaderKey?}`, `share.ts:517`; terms per tenant) | confirmed |
| V30 | Memories already derives uploader wraps from its own rows, for members | `memories/media/MediaStore.kt:434-439` (`uploaderKeys(rows)`, reads core's `ShareIdentityEntity` directly), served as `uploaderKeys` by `listNodeMedia` (`memories/media.openapi.yaml:1001-1011`); `listNodeMedia` is `bearerAuth` (:118) | the guest read can reuse logic + schema, not the route |
| V31 | The resolve's nodes carry `wrappedMeta` and `sealedDropPrivateKey` | `SharingStore.kt:416-425` (`ShareNodeRow`) | stay with D3/D4 |
| V32 | `NodeKind` use sites | backend main: request `ContentResource.kt:50` (`createNodeRequest.kind.value`), responses `:106,:117`, `SharingResource.kt:272` — `grep -rlE "\bNodeKind\b" backend \| grep src/main` → those 2 files; web: `grep -rlE "\bNodeKind\b" web/src \| grep -v /generated/ \| grep -v features/memory` → **18** core files, 17 under `features/memory` | D5's "two decode sites" are **three lines in two files plus the request site** |
| V33 | After slice 6 no core wire names `mediaId` and core builds no `CompleteItem` | `sharing.openapi.yaml` `mediaId` sites :676-691, :717-735, :775, :1449-1457, :1800-1876 all belong to the moving ops/schemas; `grep -rn "CompleteItem(" backend/*/src/main backend/apps/*/src/main` → core `SharingHandler.kt:668` (leaves with slice 6) + Memories `MediaResource.kt:55` | D8's premise holds after slice 6 |
| V34 | Role-name scale (slice 1 locator, below) | contracts **5** (`sharing`, `content`, `tenants`, `memories/media` + `capabilities.json`); backend **54** (`apps` 31, `core` 20 of which 11 main, root 3); web **30**; docs **34** (ADRs 11, features 10, coverage 9, crosscutting 3, inbox 1); `packages/crypto` 2 (comments); `wiki/` 0; `admin-web/` 0; `scripts/` 1 false positive (`validate_wire_log_matcher.py:435`, a token constant named `CONTRIBUTOR`) | The brief's "web 48 / backend ~56" came from another locator (a case-insensitive `viewer\|contributor\|uploader` over `web/src` gives 59). **The command binds, not either number.** |
| V35 | The guest-transport blob role is closed | `ShareBlobUploadRequestItem.role` `[ORIGINAL, THUMBNAIL, PREVIEW]` (`sharing.openapi.yaml:1642-1646`); intentional variant in `validate_contracts_common.py` (:68-69) | D10 holds; CONFLICT note at D1 |

**Where the first draft's inventories were wrong** (kept because a later slice could repeat the mistake):
- **The `MediaDerivativeKind`/`BlobRole` drift is already guarded** by `validate_contracts_common.py:56-69`.
- **The web guard's blind spot is `reaches_application`** (:66-70), not the `api/generated` exemption.
- **The web generator has 11 runs.**
- **`BlobRole` is closed in the crypto encoder too.**
- **ADR 0079 states the `kind` question twice, differently.** D5 resolves it, and slice 0 rewrites both sentences.

## Target layout of `contracts/api/`

"Text" = description/summary/example only (no generated delta beyond Kotlin KDoc).

| Spec | After Part 1 |
|---|---|
| `_common` (core) | `NodeKind` → bounded token, visibility `structure` (slice 4). `StaleSealedMeta` comment without operation names (slice 2). **Nothing new**: share parameters are duplicated into `memories/share-media`, not lifted into `_common` |
| `account`, `auth`, `setup` | unchanged |
| `instance`, `tenants`, `scopes`, `tasks` | text (slice 2): `instance` :91, :112, :168-172; `tenants` :578, :1086, :1089, :1133, :1198-1199, :2090, :2104 (role words → slice 1); `scopes` :554; `tasks` :627 and example tag :136 → a tag from `contracts/tasks/tags/v1.json` |
| `blob` | all three operations stay; text: no `completeMedia`/`commitCoverBlob`/`K_mem` in core's words; `BlobRole` says its values are today Memories' and why it is closed (D10) |
| `content` | all six operations stay; text: tree, "a kind's placement is its application's rule", generic payload slot and content key (D4), drop key (D3), trash texts; example `createNode` → `FOLDER`; `kind` per slice 4 |
| `sharing` | **keeps** the curator operations, `resolveShare` (link + `nodes` + `rootKind`), `getShareTerms`, `resolveShareBlob`, `createShareIdentity`, `startShareBlobUploads`, `presignShareBlobUploadWindows`. **Loses** `completeShareMedia`, `deleteShareMedia`, parameter `MediaId`, `ShareMediaBatchRequest`, `ShareMediaRequestItem`, `ShareMediaDerivativeUpload`, `ShareMediaBatchResponse`, `ShareMediaResultItem`, `ShareMedia`, `ShareMediaDerivative`, and `PublicShare.media` + `PublicShare.identities`. `ShareRole` = `[READ, READ_WRITE, WRITE_ONLY]` |
| **`memories/share-media`** (new) | tag `share-media`, root `/api/shares/{shareId}/media`: `completeShareMedia` (POST), `deleteShareMedia` (DELETE `/{mediaId}`), the media+identities read (GET, proposed `listShareMedia`); its own copies of `ShareId`, `SharePassword`, `MediaId`; the inline `X-Contributor-Token` header moves with `deleteShareMedia`; security none (guest), each operation its own rate-limit posture |
| `memories/media`, `memories/favorites` | role words (slice 1); otherwise unchanged |

**Route resolution under `sharing`'s root.** The Memories root `/api/shares/{shareId}/media` is longer than
`/api/shares`, so RESTEasy should route those paths to the Memories class. The live precedent is `item-favorites`
(`memories/favorites.openapi.yaml:41`) below `content`'s root [M paths; I resolution]. The hazard
`backend/CLAUDE.md` records is the opposite case, a root that *widens*. Slice 6.2 proves it with a route test over
**every** `/api/shares` URL.

## What core still knows about Memories — before / after this CR

This table is ADR 0079 Consequences' "What core still knows of Memories" paragraph, point by point. Slice 0 adds
the decisions. Each later slice rewrites its row of the paragraph to the code state when it lands, so the ADR never
claims more than is built.

| Point today | After Part 1 | Next |
|---|---|---|
| `kind` is the closed `FOLDER`/`MEMORY`/`CHAPTER` enum core's generated API decodes | **Gone** (slice 4): an open token; core names `FOLDER` only; `rootKind` on `PublicShare` (slice 5) | — |
| `veyq_node` carries `cover_blob_id`, `cover_byte_size`, `media_revision`, `delete_revision` | **Unchanged** | Part 2 (P2.1); `cover_blob_id` with #327 |
| `ShareGuestWrites.supports` gates share *creation*; core alone refuses every share | **Unchanged in kind, smaller**: the seam loses `complete`/`deleteOwn` (slice 6) and keeps `supports`/`start`/`window` | not planned; it is the fail-closed rule ADR 0079 wants |
| The guest completion item is media-shaped because core's share contract serves `/media` | **Gone**: the operations are Memories' (slice 6); the item is generic (slice 7) | — |
| `BlobRole` is closed in core's `blob` spec | **Unchanged**, re-described (slice 2, D10) | #327 |
| Upload retention/purge keys are named `veyq.media.*` | **Unchanged** | Part 2 (P2.3) |
| *(new row)* Share roles named in Memories' terms | **Gone** (slice 1): `READ`/`READ_WRITE`/`WRITE_ONLY` | application roles via a policy seam (D2), not planned |
| *(new row)* `PublicShare` carries Memories' rows (`media`, `identities`) | **Gone** (slice 6) | — |
| *(new row)* Core web imports Memories' generated clients | **Gone** (slice 3), guarded | — |
| *(new row)* Node payload slot, content key, drop key on `veyq_node`, written in practice by Memories | **Stays**, re-described as platform mechanism (D3, D4); **intermediate state** (D9) | re-examined with the second application; drop-key table option P2.2 |

## Part 1 — slices

Every slice:
- starts with `git status --short` (a dirty shared spec means stop and confirm);
- records the baseline exit codes **before its first edit**:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`,
  `(cd backend && ./gradlew check)`, `(cd web && npm run generate:api && npm run check)`;
- re-points the readers of what it moves **first**, on the layout it is about to leave;
- runs contracts → backend → web;
- ends green on its own, as its own commit(s).

- [ ] **0 — Docs first (refinement; no code).** Writes the target into the durable state:
  - Appendix A.1–A.8 into ADR 0079 Consequences;
  - A.2 into `docs/apps/README.md`;
  - A.9's use-case and coverage sentences.

  Use-case text keeps **today's** role wire names, because slice 1's locator sweeps them along. That way no doc
  names a wire value before it exists. Both `kind` sentences of ADR 0079 are rewritten to A.7 (the "not decided
  yet" sentence goes). Done when: `python3 scripts/validate_changes.py`, `validate_doc_links.py`,
  `validate_doc_citations.py`, `validate_coverage.py` and the whole guard glob are green. **≈ 0.5 session.**

- [ ] **1 — Roles renamed `READ`/`READ_WRITE`/`WRITE_ONLY` (D2).** Mechanical; no behaviour change.

  Locator, run from the repo root (V34 gives its 2026-10-05 output):
  `grep -rlE "\b(VIEWER|CONTRIBUTOR|UPLOADER)\b|ShareRole\.(Viewer|Contributor|Uploader)" contracts backend web/src admin-web/src packages/crypto/src docs wiki scripts changes | grep -v /generated/`.
  A second angle for the mixed-case symbols the generator and the code derive:
  `grep -rnE "ShareRole\.|ShareEntity\.(VIEWER|CONTRIBUTOR|UPLOADER)|canView|canUpload" backend web/src | grep -v /generated/`.

  1. **contracts:**
     - `ShareRole` enum `[READ, READ_WRITE, WRITE_ONLY]` (`sharing.openapi.yaml:806-815`; example `READ`);
     - every prose naming the values in `sharing`, `content`, `tenants`, `memories/media`;
     - the two `capabilities.json` descriptions; the ids stay (V28).

     Predicted reds: backend compiles (the role is a string in core, `ShareEntity` constants), but the tests
     posting `"VIEWER"` go red at their 400s. Web is red at every `ShareRole.Viewer|Contributor|Uploader`, and the
     generator names the new members `Read`/`ReadWrite`/`WriteOnly` [I — confirm from the first `generate:api`].
  2. **backend:**
     - the `ShareEntity` constants (`core/sharing/Entities.kt:138-142`) and `ShareAccessPolicy` (:17-23);
     - every raw role string in tests;
     - the KDoc/comment words;
     - the core baseline comment :314, so the baseline hash changes: the pin line in
       `scripts/applied_migrations.sha256` is re-derived in the **same commit** (`release: none`);
     - every database is recreated.
  3. **web:**
     - every generated-constant use;
     - `ShareDialog.vue`'s `roleLabel` keeps its i18n keys (labels unchanged, V29);
     - e2e fixtures and the test models.
  4. **docs:** the locator's docs hits (ADRs 0029/0030/0031 and the others, the features, coverage, crosscutting,
     inbox). Inbox and `changes/` rows that cite the old values as history may keep them, marked as old.

  **Bite / proof.**
  - A backend slice test POSTs `createShare` with `"role":"VIEWER"`: the 400 is anchored on status and code. Its
    positive control: `"READ"` → 201.
  - `ShareGuestNamesSliceTest`'s raw `.body("role", equalTo("VIEWER"))` (:106) becomes `"READ"`, the raw pin of
    the wire value.
  - The locator returns only the allowed list: crypto comments are reworded, and the
    `validate_wire_log_matcher.py` false positive stays.

  Done when: `./gradlew check`, `npm run generate:api && npm run check` (web **and** admin-web), the e2e share
  suites (`scripts/run_web_e2e.py`; from a worktree pinned as the machine notes say) and the guard glob are green.
  **≈ 1 session.**

- [ ] **2 — Text pass over core specs and core comments (D3, D4, D10, A.1).**
  - **Sites:**
    - the "Text" cells of the target table;
    - the role prose (the access modes; "write" is the application's, via `ShareGuestWrites`);
    - the drop key ("of the shared root node of a `WRITE_ONLY` link");
    - `wrappedMeta`/`metaVersion` ("the node's versioned sealed payload; its format is the kind's
      application's") and `wrappedContentKey` ("the content key of a shareable node");
    - `BlobRole` and `ShareBlobUploadRequestItem.role` (closed, why);
    - the `capabilities.json` descriptions;
    - core main comments naming Memories kinds (`grep -rn 'MEMORY\|CHAPTER\|K_mem' backend/core/src/main`).
  - **Prose locators (two keys, because one misses "photo"/"cover"):**
    `grep -niE 'memor|chapter|k_mem' contracts/api/*.openapi.yaml contracts/platform/capabilities.json` and
    `grep -niE 'photo|media|cover|bucket|favorite' contracts/api/*.openapi.yaml`.
    - Expected noise: "in memory", the S3 *store bucket*, "recovery", public `share`s.
    - The `media`-named operations still in `sharing` until slice 6 are worded neutrally there and leave with it.
  - **What stays:** use-case ids cited in core summaries (`uc.memory.delete-node`). Re-filing them is an inbox row.

  Proof that only prose moved — run once against the slice's base `<base>`; JSON covered too:
  ```
  python3 - <<'EOF'
  import subprocess, yaml, json, pathlib, sys
  PROSE = {"description", "summary", "example", "examples", "x-veyq-rationale", "note"}
  def strip(n):
      if isinstance(n, dict): return {k: strip(v) for k, v in n.items() if k not in PROSE}
      if isinstance(n, list): return [strip(v) for v in n]
      return n
  files = sorted(pathlib.Path("contracts/api").rglob("*.openapi.yaml")) + [pathlib.Path("contracts/platform/capabilities.json")]
  bad = []
  for p in files:
      old = subprocess.run(["git", "show", f"<base>:{p}"], capture_output=True, text=True).stdout
      load = json.loads if p.suffix == ".json" else yaml.safe_load
      if old and strip(load(old)) != strip(load(p.read_text())): bad.append(str(p))
  print("shape changed:", bad or "none"); sys.exit(1 if bad else 0)
  EOF
  ```
  A one-shot proof is enough: a permanent "only prose changed" guard has no meaning. No backend and no web phase.
  Done when: the script prints `none` and the guard glob is green (incl. `validate_field_visibility.py`,
  `validate_write_concurrency.py`). **≈ 0.5–1 session.**

- [ ] **3 — Core web stops importing Memories' generated clients; the guard learns it.** The defect is live (V12),
  so the order is guard → red → fix → green.
  1. **The guard.** Extend `scripts/validate_web_core_imports_no_applications.py`: an application also owns every
     generated client whose `generate:api` run reads a spec under `contracts/api/<application>/`.
     - The pairs are **derived from `web/package.json`'s `-i`/`-o` pairs**, never listed, so `share-media`
       (slice 6) is covered the day its line lands.
     - There is no exemption for `api/client.ts`.
     - Red on exactly `shared/image/mediaDeriver.ts` and `api/client.ts`. Locator:
       `grep -rln "generated/media\|generated/favorites" web/src | grep -v features/memory | grep -v '\.test\.ts$'`.
     - Positive control: a core import of `api/generated/blob` stays green.
     - The docstring states the new limit: a client generated outside `generate:api` is not seen.
  2. **`mediaDeriver.ts`** types its tiers from core's `BlobRole` (`Extract<BlobRole, 'THUMBNAIL' | 'PREVIEW'>`).
     Core's `features/spaces` uses it too, so it cannot move into `features/memory`.
  3. **The five Memories factories** leave `api/client.ts` for a Memories module (e.g. `features/memory/api.ts`),
     built on an exported core `configuration()` helper.
     - The module has both shapes core has today: a bearer factory and a **token-less** one, as `publicSharesApi()`
       (`client.ts:173-175`) sits beside `sharesApi()`. Slice 6 adds `publicShareMediaApi()` there; it has no
       bearer, because the anonymous `/s/` page sends no `Authorization` header (`client.ts:166-167`).
     - Readers to re-point first:
       `grep -rlnE "\b(mediaApi|bucketsApi|favoritesApi|itemFavoritesApi|ownItemFavoritesApi)\b" web/src`.
     - Every `vi.mock` of `api/client` in a Memories test that stubs one of them follows
       (`grep -rln "vi.mock(.*api/client" web/src/features/memory`). Each is bitten by making the moved real
       factory throw.
  4. `web/stryker.mutate.json` holds neither file today [M].

  Done when: the guard is green, `npm run generate:api && npm run check` is green, and a `validate_guard_gaps.py`
  case exists only if the new limit is documented as a gap. **≈ 0.5–1 session.**

- [ ] **4 — `NodeKind` opens to a token (D5).**
  - **Readers and guards re-pointed first, green on the closed enum:**
    - `validate_contracts_common.py:57` drops the `node-kind` family (an open token is no family);
    - `validate_field_visibility.py` (`enum` → `structure`);
    - `validate_kernel_imports_no_slices.py` `APPLICATION_KINDS` and `validate_web_core_imports_no_applications.py`
      `KIND` keep biting on literals. The guard-gap forgeries (`validate_guard_gaps.py:776-787`, `:847-850`) stay
      REJECTED without an edit (V23).
  1. **contracts:** `_common` `NodeKind` → `type: string`, `pattern: ^[A-Z][A-Z0-9_]{0,15}$`,
     `x-veyq-visibility: structure`. The description says core names `FOLDER` and applications bring their kinds;
     the comment at `_common:162-163` goes. No Memories spec enum (*rejected*: a schema only to generate a constant
     — Memories keeps `MemoriesNodeKinds`, `memories/node/MemoryNodeKindRules.kt:9`, and a web constant).
     Predicted reds: the backend at the V32 sites, the web at every `NodeKind.*` constant.
  2. **backend:**
     - the request site (`ContentResource.kt:50`, `.kind.value` → the string) and the three response `decode`s
       (V32) take the string;
     - the tests: a malformed kind (`memory`, 17 characters) → 400 by the pattern; a well-formed unknown kind
       (`EXPENSE`) → refused by the seam with its code (`illegal_structure` on create, `illegal_scope` on share);
       a `FOLDER` control;
     - whether the generator emits the `pattern` as bean validation is [I]. If it does not, the 400 test says so,
       and the seam's refusal is the only gate. The CR then records that instead of claiming the pattern.
  3. **web:**
     - core types the kind as `string`, with a `FOLDER` constant in core (locator V32);
     - the registry (`core/applications/registry.ts:38-56,173-182`) is keyed by string;
     - `features/memory` uses its own `MEMORY`/`CHAPTER` constants;
     - `ShareBodyKey`/`DROP_SHARE` stay separate symbols (:111-112);
     - the registry tests carry the lost exhaustiveness: an unregistered kind → `{ supported: false }`, with a
       `FOLDER` control;
     - check `web/stryker.mutate.json` for `registry.ts` and keep its score.
  - #328 (node URLs) must not run in the same wave: both edit `registry.ts`.

  Done when: `./gradlew check`, `npm run generate:api && npm run check`, the e2e suites and the guard glob are green.
  **≈ 1–1.5 sessions.**

- [ ] **5 — `PublicShare.rootKind` (#329).**
  1. **contracts:** `rootKind` (`$ref NodeKind`), omitted on a locked response, served for every unlocked role,
     `WRITE_ONLY` included. That hands a `WRITE_ONLY` guest one structural fact, the root's kind; the server
     already holds it, and the owner accepted it in #329.
  2. **backend:** the resolve fills it from the root node.
     - Raw-JSON test: present for `READ` and `WRITE_ONLY`, absent when locked.
     - Its positive control: `role` present on the locked response.
  3. **web:**
     - `resolvePublicShare.ts:126` reads `share.rootKind` instead of deriving it from `nodes`;
     - `ShareView` dispatches on it;
     - `DROP_SHARE` retires (`registry.ts:111-112`, `features/memory/register.ts:32`, the comments at
       `resolvePublicShare.ts:13-14`, `registry.ts:25`).

  Docs: inbox #329 closed; `uc.sharing.share-uploader` step 3's resolve shape names `rootKind` (drafted in A.9).
  **≈ 0.5 session.**

- [ ] **6 — Guest media moves to Memories (D1).** Three steps, each its own green commit. Needs A.6.
  1. **backend only, no spec change — `ShareGuestGate`.** Extract the gate from `SharingHandler` into a public
     core service. The steps keep today's order: active share → password → the mode's capability → identity
     token → the per-share upload budget **only where the caller asks** (V18). Each refusal is today's outcome, so
     the status mapping does not move.
     - **write gate** `writeGate(shareId, pwToken, contributorToken, spendUploadBudget)` →
       `Admitted(scopeId, rootNodeId, rootKind, identityId, admitted: () -> Boolean)` or
       `NotFound | PasswordIncorrect | RateLimited | NotContributable`.
       - `admitted` is today's closure `{ store.admitsGuests(shareId) }` (V17), handed out **unevaluated**: the
         application calls it first inside its attach transaction, and evaluating it in the gate would reopen the
         race `GuestWriteRaceSliceTest` pins.
       - `rootKind` is read as `writesFor` reads it (`SharingHandler.kt:734-737`); a gone root is
         `NotContributable`.
       - Complete passes `spendUploadBudget = true`, delete passes `false`.
     - **view gate** `viewGate(shareId, pwToken)` → `Viewable(scopeId, rootNodeId, rootKind, liveNodeIds)`,
       `WriteOnly` (`ShareAccessPolicy.canView` false), or `NotFound | Locked | PasswordIncorrect | RateLimited`.
       - `liveNodeIds` is core's live subtree (V20).
       - The gate carries **no identity set** (D1).
     - **Visibility:** the gate is the only core sharing surface Memories newly calls. The later
       `internal`/`core.api` cut can then hide `SharingStore`. Memories' existing direct read of
       `ShareIdentityEntity` (V30) is today's state and is listed for that cut, not changed here.
     - **Proof:** every guest slice test unchanged and green, plus `python3 scripts/run_backend_races_on_mariadb.py`
       (`admitted()` still runs inside the attach transaction under the share-row lock).
     - **Bite:** a new slice test, a delete after an exhausted upload budget still answers 204. Lever: pass `true`
       on delete → red.
  2. **the two writes move — the wire bytes do not.**
     - **contracts:**
       - new `memories/share-media.openapi.yaml` (tag `share-media`); the two operations and five schemas move;
       - `MediaId` moves; `ShareId`/`SharePassword` are **duplicated**, identical by `validate_contracts_common.py`
         rule 2 (`shared_parameters` :90-96, check :215-217). Bite: change one copy's `ShareId` `pattern` → red;
       - the inline `X-Contributor-Token` header moves with `deleteShareMedia`;
       - `web/package.json` gains its `-i … -o src/api/generated/share-media` line, so slice 3's guard covers it
         at once.
       - **Not** red: `OperatorBlindProbeTest` (V22: it classifies by operationId).
     - **backend:**
       - Memories' `ShareMediaResource`: `@PermitAll` per operation; `@RateLimit(key = SHARE)` on complete and the
         documented exemption on delete, copied from `SharingResource.kt:368-399`;
       - it calls `writeGate`, checks the root kind is one Memories `supports`, builds its items with
         `nodeId = rootNodeId` (server-set), and passes `admitted` through `UploadCompletions`, or runs its own
         delete;
       - core loses `SharingHandler.completeUploads`/`deleteMedia`, `ShareCompleteItem` and
         `ShareGuestWrites.complete`/`deleteOwn`;
       - readers first: `grep -rn "completeShareMedia\|deleteShareMedia\|/media\b" backend/*/src/test backend/apps/*/src/test backend/src/test`
         (routes, not only names), `RateLimitPostureSweepTest`, `AdminChannelPostureSweepTest`,
         `ShareGuestWritesPartitionSliceTest`.
     - **route proof:** one slice test in `:apps:memories` calls **every** `/api/shares/…` route of both specs and
       asserts each reaches its handler (a status/body anchor, not "not 404"), with a core-route control.
     - **web:**
       - Memories' share code calls `publicShareMediaApi()`;
       - readers: `grep -rln "completeShareMedia\|deleteShareMedia" web/src` (`features/memory/share.ts` :594,
         :638; `share.test.ts`; `shareUploader.test.ts`; `shareContributor.e2e.test.ts`);
       - each `vi.mock('../../api/client', …)` gains the new factory and is bitten by making the real one throw.
  3. **the media+identities read; `PublicShare` loses `media` and `identities` (wire change).**
     - **contracts:**
       - the GET on the share-media root returns `{ media: ShareMedia[], identities: UploaderKey[] }` (`ShareMedia`
         and `ShareMediaDerivative` move);
       - `UploaderKey` is reused in shape from `memories/media` (V30). Referenced across Memories' two specs or
         duplicated: decided at implementation, held identical by rule 2 if duplicated;
       - `PublicShare` loses `media` (from `properties` and `required`) and `identities`, and its locked-response
         text loses both;
       - the new operation joins `OperatorBlindProbeTest`'s public/token-gated exclusion (:224-225), the one probe
         edit (V22).
     - **backend:**
       - Memories serves its rows over the view gate's `liveNodeIds`, with its **own** live predicate
         (`ShareTrashedMediaSliceTest` keeps biting the listing and the blob GET separately);
       - the wraps come from Memories' `uploaderKeys(rows)` (V30) or a core lookup, and **every `identityId` not in
         that map is nulled in the same commit** (ADR 0030 point 8; today `SharingHandler.kt:540-545`);
       - `WriteOnly` → empty media and identities, as today;
       - core deletes `SharingStore.uploaderKeys`, `SubtreeData.media`, `ShareMediaRow`, `ShareDerivativeRow` and
         `SharedSubtreeContributor.rows`; `SharedSubtreeContributor` keeps `authorizedBlobIds`;
       - **before reshaping it, grep `scripts/`:** `validate_blob_reference_arity.py:120-121` (`CONTRIBUTOR_IMPL`,
         `CONTRIBUTOR_FN`) and `validate_guard_gaps.py:310` (`MEDIA_SHARE_CONTRIBUTOR`) stay green as long as the
         interface name, its declaration and `authorizedBlobIds` stay;
       - **rate limit:** `@PermitAll` + `@RateLimitExempt` with `resolveShare`'s justification (V24).
         `cc.security.rate-limit.md` § Unbounded by decision turns "the three anonymous guest reads" into four.
         `RateLimitPostureSweepTest` sees the Memories class without an edit.
     - **pin moves along:** `ShareGuestNamesSliceTest` (already in `:apps:memories`) re-points its `media`/
       `identities` assertions (:58-117) to the new read, through RestAssured paths on the raw body as today. It
       adds a raw assertion that the resolve body has **no `media` and no `identities` key**, with `nodes` as the
       positive control on the same response. The members' bucket-list control (:120-128) is unchanged.
     - **bite levers:**
       - serve `identityId` unfiltered → `onlyAnUploadersMediaNamesItsIdentity` red;
       - serve CONTRIBUTOR identities → `aContributorLinkIsServedNoneOfItsOwnGuests` red;
       - put `media` back on the resolve → the absence assertion red.
     - **web:**
       - `share.ts:765-767` reads the new call (`publicShareMediaApi()`, no bearer);
       - `resolvePublicShare` reads no media;
       - a raw e2e assertion that the resolve JSON has no `media`/`identities`, with a `nodes` control.

  - **Docs:** A.9's sentences are already written by slice 0; `docs/apps/memories.md` Contracts line; ADR 0079's
    row (§ Before/after); security-model unchanged (no new operator-visible fact; the server already sees the
    resolve), stated in the commit text; re-read `wiki/en/is-my-data-safe.md` + `wiki/de/sind-meine-daten-sicher.md`
    if `uc.sharing.*` changes meaning.
  - **Coverage:** refs re-pointed (`python3 scripts/validate_coverage.py`). No baseline change.
  - **Done when:** `./gradlew check`, the MariaDB race run, `npm run generate:api && npm run check`, the e2e share
    suites and the guard glob are green, and
    `grep -n "ShareMedia\|completeShareMedia\|deleteShareMedia\|MediaId\|identities" contracts/api/sharing.openapi.yaml`
    names nothing but prose that names no Memories operation.

  **≈ 3–4 sessions** (6.1 ≈ 1 with the race run, 6.2 ≈ 1, 6.3 ≈ 1.5).

- [ ] **7 — `CompleteItem` becomes generic (D8).**
  - **Re-verify first:** after slice 6, is `mediaId` absent from every core wire and is `MediaResource.kt:55`
    (plus Memories' share resource) the only constructor (V33 locators)? Only then does the KDoc premise at
    `UploadCompletion.kt:14-17` lapse.
  - **The new item:** `CompleteItem(ownerId, nodeId, originalUploadId, derivatives, payloads)`, with
    `CompleteItemResult(blobId, ownerId, error)`. `payloads` holds the application's sealed values, opaque to core
    (Memories: `wrappedMeta`, `wrappedExif`), with no seal and no wire change. Memories maps its DTOs.
  - **Locator:** `grep -rn "CompleteItem\|CompleteItemResult\|DerivativeDeclare\|mediaId" backend/core/src backend/apps backend/src`.
  - **Pins:**
    - `CoreAloneSliceTest` (:89 `completions`, :94 `dispatcher`) and `UploadCompletionPartitionSliceTest` stay
      green, edited only where they build an item;
    - `validate_kernel_imports_no_slices.py:23,204` stays true unedited (the seam stays);
    - `docs/apps/files.md:30` and `memories.md:43` stay true;
    - ADR 0079 Consequences drops point 4.
  - **Done when:** `./gradlew check` is green and `grep -rn "mediaId\|wrappedExif" backend/core/src/main` names no
    field. **≈ 0.5 session.**

## Part 2 — deferred (D11; each its own CR, recorded not designed)

- **P2.1 `veyq_node` columns.** Drop `cover_byte_size` (write-only, V10). Move `media_revision`/`delete_revision`
  into a Memories table. To decide then:
  - **foreign keys:** today nothing references `veyq_node` by FK (`validate_node_subtree_erasure.py` docstring);
    the guards see a table only by its `node_id` column and its `REFERENCES` edges;
  - **lock order:** today the counters ride the node row lock (`memories/media/MediaStore.kt:175-177,461`); the
    order is written down before the first MariaDB race run.

  `cover_blob_id` goes with #327.
- **P2.2 Drop private key table** `veyq_node_drop_key` (option from D3).
- **P2.3 `veyq.media.*` config.**
  - Memories declares `@ConfigMapping(prefix = "veyq.media")` with `trash()`, and core's `Media` loses `trash()`
    (V25). That prefix is preferred over a second `veyq` mapping, whose root would overlap core's whole tree.
  - Key names are unchanged, so compose and Helm are unchanged.
  - The boot proof and a misspelt-key bite run first [I].
- **P2.4 `ScopeAccessWorld` split (#335):** sequentially after slice 6, never in the same wave.
- **P2.5 Attachments (#327) as a core spec, with the second application** (D7): a blob, its role, sealed metadata
  and a version on a node. `BlobRole` opens there, together with a seal purpose for non-Memories blobs (an ADR 0072
  stored-format decision).
- **P2.6 Link as a credential** (D6): reads first, then writes; it deletes the guest mirrors slice 6 moved.
- **P2.7 `SpecSets` owners derived from directories, with the second application.** Rule (d) of
  `validate_backend_module_graph.py` (:138-139, :371-381) changes in the same commit.
- **Not planned at all:** application-specific roles. They come through a policy seam if ever (D2).

## Risks and what is not proven

- **Route resolution** for `memories/share-media` under `sharing`'s root is inferred from a precedent. Slice 6.2's
  route test is the proof; a regression would show as 404/405 on guest uploads only.
- **Gate extraction** (6.1): the outcome codes are pinned by the guest slice tests. The placement of `admitted()`
  is pinned only by the MariaDB race run. Delete's budget posture is pinned by the test 6.1 adds.
- **A second request when a share opens** (6.3) changes viewer latency and the e2e choreography. The password
  budget is unaffected (a correct token never spends it, `SharingHandler.kt:759`). Not measured.
- **The lost web exhaustiveness** (slice 4) is carried by the registry's fail-closed behaviour and its tests, not
  by the compiler.
- **The pattern as a 400** (slice 4) depends on the generator emitting bean validation [I].
- **The role rename** (slice 1) breaks every stored share on an existing database. That is accepted (no release;
  dev DBs are recreated), and it is the reason the slice must land before any release.
- **Duplicated share parameters** (6.2): drift between the copies is caught by rule 2. Not seen: a copy's
  `description`, and the inline header, which exists once after the move.
- **Not covered:** re-filing `en.memory.tree`/node-trash use-cases under a platform id; the `features/memory`
  rename and the single-claim web slots; the capability catalog's missing owner field; `BlobRef` as a shared shape.

## Size, honestly

Part 1 is **≈ 8–10 sessions** (summary table). The first draft's 5–6 covered slices 2, 3, 6 and 7 only. Those four
are still ≈ 5–6. Slices 0, 1, 4 and 5 add ≈ 3–3.5.

The yardstick is the repo's own: cr-0160 guessed "~8 cross-slice imports" and paid "+2 same-package users **and 22
test files**" (`docs/ai/README.md:199-201`). The test trees measured for slice 6, with the locators it carries [M,
first draft]:
- **14** backend test files for the writes;
- **16** for the read;
- **17** web files across both.

Slice 1's scale is V34 (≈ 125 files, almost all mechanical). Slice 4's is V32 (35 web files, 2 backend main
files).

**Sparring note.** `changes/README.md` says *genuinely independent work = separate CRs, not phases*.
- Slices 1 (rename) and 4+5 (kinds) are independent of slice 6 except for ordering. They are candidates to be cut
  into their own CR files when implementation starts, which keeps each work order readable.
- The order above holds either way.
- The owner decides at go.

## Definition of done (Part 1)

- `ShareRole` is `[READ, READ_WRITE, WRITE_ONLY]` on the wire, in storage and in the docs. Slice 1's locator
  returns only the documented allowances.
- `NodeKind` is an open bounded token; core's hand-written code names `FOLDER` only; `PublicShare.rootKind`
  exists; `DROP_SHARE` is gone.
- `contracts/api/sharing.openapi.yaml` carries no media operation, parameter or schema, and `PublicShare` carries
  no `media` and no `identities`. `contracts/api/memories/share-media.openapi.yaml` carries them, with the privacy
  rule of ADR 0030 point 8 pinned by `ShareGuestNamesSliceTest` through raw JSON.
- No core spec description names a Memories operation, kind, key or plaintext (slice 2's locators return only the
  documented noise).
- `validate_web_core_imports_no_applications.py` judges imports of application-generated clients and is green.
- Core holds no media-shaped DTO: `ShareCompleteItem`, `ShareMediaRow` and `ShareDerivativeRow` are gone, and
  `CompleteItem` is generic.
- ADR 0079 Consequences matches § Before/after row by row. No row is silently dropped, and none is claimed done
  that Part 1 did not do.
- **The second-application paper test is checkable.** An `expenses` application with guest contributions would
  add `contracts/api/expenses/share-entries.openapi.yaml` (root `/api/shares/{shareId}/entries`), declare its kind
  through `NodeKindRules` and the registry, implement `ShareGuestWrites.supports`, and call
  `ShareGuestGate.writeGate`/`viewGate`. It would do so **with no edit to `sharing.openapi.yaml`, `_common`, or any
  `backend/core` main file**. The close states that sentence against the merged tree, with
  `git grep -n "media\|Media" contracts/api/sharing.openapi.yaml` naming no Memories operation and the gate's
  signature naming no Memories type.
- `./gradlew check`, `scripts/run_backend_races_on_mariadb.py`, `npm run generate:api && npm run check` (web and
  admin-web), the e2e share suites and `for f in scripts/validate_*.py; do python3 "$f"; done` are green **on the
  committed tree**. `docs/coverage/` is re-pointed for the three `satisfies:` use-cases, with
  `validate_coverage.py` green.
- What the work taught moves to `backend/CLAUDE.md` (a cross-spec route under one root, if it needs a rule) and
  ADR 0079.

## Appendix A — drafted text for Slice 0 (ADR 0079 Consequences, `docs/apps/README.md`, use-cases)

Wording is a draft for slice 0. Brackets name the decision each paragraph encodes.

**A.1 [all] Contracts by owner.** "A core spec describes what core does. Where a value's meaning belongs to an
application — a node's sealed payload, a blob role, a kind — its description says whose it is and names no
application's plaintext, operation or key. An operation whose behaviour is an application's lives in that
application's directory, even when its path sits under a core route (`/api/shares/{shareId}/media` is Memories')."

**A.2 [D7] What the platform is** (for ADR 0079 and `docs/apps/README.md`). "veyq is a platform for sealed,
shareable objects. The core provides:
- **who**: tenants, central users, groups and personal scopes, memberships, invitations, keys, devices;
- **what**: nodes as a tree inside a scope, with parents, order, trash and a content key per node; and
  *attachments*, sealed files on a node that can be uploaded and downloaded, with size and storage quota;
- **with whom**: links with `READ`, `READ_WRITE` or `WRITE_ONLY` access, with password, expiry and consent;
- **in the background**: tasks, rate limits, the admin channel and operator-blindness.

An application supplies the **meaning**: which node kinds exist, what the plaintext means, which rules apply to
structure, attachments and guests, and the user interface."

Followed by the honest state: "Attachments are the target, not yet the code: today what a verified upload becomes
is application work (`UploadCompletion`), and Memories' cover and revision columns still sit on `veyq_node`.
Generic attachments are formalised as a core spec (inbox #327) when the second application needs them; that
'every application needs attachments' is an assumption the second application tests. A Files or Storage product
is an application on core's attachments, never a dependency of another application (Decision: an application
depends on core only)."

**A.3 [D4, D9] The node's payload slot and content key are core's.** "Core gives every node one sealed, versioned
payload (`wrappedMeta`, `metaVersion`): core stores it, compare-and-sets it
([ADR 0077](../docs/decisions/0077-a-write-carries-the-version-it-read.md)) and lists it with the tree, and never
opens it. Its plaintext format is the node kind's application's, versioned per Decision 7. `wrappedContentKey` is
the content key of a shareable node, chained so a share's viewer derives every descendant's key. These are platform
columns, not an application widening `veyq_node`. That only one application fills them today makes this an
intermediate state, re-examined when a second application does."

**A.4 [D3] The drop key is the `WRITE_ONLY` link's mechanism.** "A `WRITE_ONLY` link's drop key pair belongs to
core's link mechanism:
- the public half is sealed under the link's `K_share` on the share row;
- the private half is the shared root node's, sealed under the root's content key, set once by core inside the
  share creation (409 `drop_key_exists`/`drop_key_missing`);
- it stays a field of its own and never joins the node payload.

The seal purposes `drop-public-key`/`drop-private-key` are a stored format and keep their names."

**A.5 [D2] Three access modes.** "A link grants one of three access modes: `READ`, `READ_WRITE`, `WRITE_ONLY`.
Core enforces the mode (who may read the shared subtree, who may add through the guest transport). What 'write'
means on a node is the application's, through `ShareGuestWrites`. The modes are bound in no seal. Application-specific
roles, if ever needed, come through a policy seam where core asks the application whether an action is allowed for
a mode — never through a registry of roles." (ADRs 0029/0030/0031 then name the modes in place of
`VIEWER`/`CONTRIBUTOR`/`UPLOADER`, in slice 1.)

**A.6 [D1] Application guest operations.** "Core owns the public link and its guest gate: active link, password,
the mode's capability, the guest identity's token, and the per-link upload budget where the operation spends one.
Core exposes the gate as a service an application calls before its own guest read or write. Core's share contract
keeps only kind-neutral guest operations: resolve (the link plus the shared nodes and their keys, no application
data), terms, blob transport and download, guest identity. An application serves its rows and its writes on its own
route under the share, checks that the share's root kind is its own, and carries the privacy rules of its rows with
them (for Memories: [ADR 0030](../docs/decisions/0030-contributor-public-share.md) point 8)." Later direction, one
sentence (D6): "A link may later become a credential core's gate accepts for application operations that declare
it; that is a separate decision."

**A.7 [D5] Node kinds are open.** This replaces both of ADR 0079's `kind` sentences: "The node `kind` is an open,
bounded token on the wire (`^[A-Z][A-Z0-9_]{0,15}$`). Core names `FOLDER` only; each application brings its kinds
and their rules (`NodeKindRules`, the web registry). A kind no application claims is refused by the fail-closed
seams, never by decoding. `PublicShare` carries the root's kind (inbox #329)."

**A.8 [D8] The completion seam stays core.** In the nine-seams sentence: "`UploadCompletion` (what a verified
upload becomes on a node of the kind; its item is generic — an `ownerId`, derivatives and the application's sealed
payloads)".

**A.9 [D1] The use-case and coverage sentences** (exact current text [M, 2026-10-05]; drafts keep today's role
words, which slice 1 renames).
- `uc.sharing.share-public-viewer.md:26-27` (step 3).
  - Today: *"The client `GET`s `/api/shares/{shareId}` (**no auth**) → the record **plus the sealed subtree** (the
    Memory node + its chapters + media metadata, all sealed under `K_mem`)."*
  - Draft: *"… → the record **plus the sealed subtree** (the Memory node + its chapters, sealed under `K_mem`),
    then `GET`s `/api/shares/{shareId}/media` → the Memory's media metadata and the wrapped keys of the uploader
    identities whose media it holds. Both pass the same password gate."*
  - The sequence diagram (:50-51) gains the second request.
- `uc.sharing.share-uploader.md:31-32` (step 3).
  - Today: *"`{ role: UPLOADER, sealedDropPublicKey, nodeId, sealedName, media: [] }`"*.
  - Draft: *"`{ role: UPLOADER, rootKind, sealedDropPublicKey, nodeId, sealedName }`; an UPLOADER link's media
    read answers an empty list"*.
  - Likewise :19, :63 (the diagram), :89-90 and :110-111 (`media: []` → the media read's empty list). Step 5
    (:45-46) keeps its path.
- `uc.sharing.share-contributor.md:51` keeps its path. :121-122 becomes: *"Neither a link's resolve nor its media
  read serves a contributor identity, not even its own guests' — … — and no contributed media in the read names
  one"*.
- Coverage rows (`docs/coverage/features/sharing/use-cases/`):
  - `uc.sharing.share-public-viewer.yaml:21` splits into the resolve and the media read;
  - `uc.sharing.share-uploader.yaml:41` (*"The anonymous resolve returns media=[], nodes=[], identities=[]"*) and
    :70 name the media read;
  - `uc.sharing.share-contributor.yaml:63` names the media read.

## Retrospective

Written at close.
