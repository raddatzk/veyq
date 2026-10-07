---
id: cr-files-application
title: Files, the second application — platform prerequisites, the skeleton, files for members, shares, the drop box
status: planned          # planned → in-progress → done (then harvest what binds, and delete the file)
satisfies: []            # use-case ids this fulfills (trust-chain link); Files' use cases are written with F1
adrs: ["0072", "0077", "0079", "0080"]
invariants: [operator-blind E2EE everywhere, API-first, self-hostable by default]
---

## Goal

Build **Files** ([docs/apps/files.md](../docs/apps/files.md)) as the platform's second application: a file is a node
kind holding ONE sealed blob (replaced, not versioned), directories are core `FOLDER`s, web only; folder shares carry
over (read, contributor), and a drop box takes uploads from guests. It proves the split of
[ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md): an application is added
without touching core except for the platform prerequisites below, which every later application needs too.

## Context & constraints

- Inventories of 2026-10-07 (read-only, from the session that closed `cr-node-key-hierarchy`): the scaffolding checklist,
  the blob/attachment design, the sharing and product questions. Every claim below was read at the cited file; what was
  not read is marked UNVERIFIED in the phase it matters to.
- Owner decisions, 2026-10-07:
  1. **Attachments are a core table** `veyq_node_attachment(node_id FK, slot, blob_id FK, share_identity_id?)` (inbox #327/
     #345), not a table per application ([ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md)
     Consequences rule that out); the cover slot may move onto it later.
  2. **Module name `files`**: `:apps:files`, package `me.raddatz.veyq.files`, node kind `FILE` (not `storage`).
  3. **`BlobRole` opens to a token** like `NodeKind` (`^[A-Z][A-Z0-9_]{0,15}$`) with a role of Files' own; the wire format
     of the field stays. A stored-format change before the first release is allowed ([ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md),
     `release: none`).
  4. **No per-file size cap at first**: the quota only; an operator-configurable cap is an inbox row.
  5. **No preview at first**; later a derivative through an attachment slot.
- Findings that shape the order: `FOLDER` has no `ShareGuestWrites`, so a guest of a folder share creates the `FILE` node
  with `createShareNode` and the upload targets that node; Files does **not** need inbox #343 (link as a credential): every
  per-kind extension is a seam with one implementation per kind, and core releases file blobs through the attachment table
  itself; a skeleton module needs no `GuestIdentityUsage` of its own (Memories provides one) until guests upload files (F2).
- Slice discipline as in the earlier CRs: contracts → backend → web, guard glob before and after, bite tests with real
  failing levers, MariaDB races for gate and transaction changes, e2e only from the main checkout.

## Phases

### Platform prerequisites
- [x] P1 slug function per node kind (inbox #350) — `KindDescriptor.slug` (optional), `slugTokenFor` takes the kind, a
      file-name folding `fileNameSlug` (NFC + simple lowercasing, punctuation kept: `Foto.JPG` = `foto.jpg`,
      `Day1.md` ≠ `Day1 md`) beside today's `slugify` (folders keep it); every caller passes the kind (create, rename and
      placement, restore, guest tree); conformance vectors for BOTH foldings in `contracts/crypto/vectors/v1.json` ·
      Done when: `npm run check` green, bite tests (a kind with its own folding gets its own token; folding changed ->
      the vector test red), `validate_*` green. Must land before the first stored `FILE` (the token freezes the folding).
      **Measured (2026-10-07):** `registry.slugFor(kind, name)` (the kind's `slug`, else `slugify`, also for an
      unregistered kind); `slugTokenFor(entryKey, slot, kind, name)` at all five call sites (`tree.ts` create, placement
      with `node.kind`, restore with the trash row's kind; `guestTree.ts` create and placement with the served
      `ShareNode.kind`); the guest pre-check folds each sibling by its OWN kind. `fileNameSlug` is not yet registered
      by any kind (F0 registers `FILE`). `name_folding` in `v1.json`: 13 `slugify` + 15 `fileNameSlug` cases, from an
      independent Python implementation, equal to the TS functions. `npm run check` 1817 → 1861 tests, packages/crypto
      341 unchanged, 53/53 `validate_*` before and after. Bites, each red then green: `toLowerCase` dropped → 11 vector
      cases red, NFC dropped → 1, `ß` folding dropped → 1; `FOLDER_KIND` instead of the node's kind in create, placement,
      restore → that wire test red each; the guest pre-check folded with `FOLDER` → 3 red, with the new node's kind for
      the sibling → 2 red. Found, not changed: a decomposed `ü` (`u` + U+0308) slugifies to `u`, not `ue` (the German
      table only sees composed letters), pinned as built in the vectors; a member's dialog has no sibling pre-check.
- [x] P2 contract owners from directories (inbox #344) — `buildSrc` `SpecSets.kt` derives the owner from the directory
      (top level = core, `contracts/api/<app>/` = application `<app>`, `_common` shared; `requireNoStray` keeps failing for
      anything else), `scripts/contract_specs.py` and rule (d) of `scripts/validate_backend_module_graph.py` derive the same
      way in the SAME commit, `validate_web_core_imports_no_applications.py` derives application clients from any
      `contracts/api/<app>/` pair · Done when: a throwaway second application directory in a scratch tree is accepted and
      generates `me.raddatz.veyq.<app>.generated`, a stray directory without a module is refused, the guard bites.
      **Measured 2026-10-07:** a module opts in with `veyqOpenApi { generateOwnSpecs() }` (owner from its project path,
      replacing `owner("…")`); a throwaway `contracts/api/demo/` + `:apps:demo` generated `DemoApi`/`DemoThing` in
      `me.raddatz.veyq.demo.generated.{api,model}`; without the include `gradlew help` refused it ("`demo/` is no
      application — the settings include no `:apps:demo`"), included without the opt-in it refused it too, and so did a
      spec two levels deep and a `Bad/` directory; with the pre-#344 owner list put back the demo was refused, green
      again without it. Core's and Memories' generated trees byte-identical before/after (594 files), the web client
      unchanged. Guard-gap cases 62–64 (rejected) and 65 (a module without its directory still passes, as before) bite:
      rule (d) not reading the spec directories turns 62/63 red, tolerating a literal owner list turns 64 red. Note for
      F0: the module is `files`, so its specs go in `contracts/api/files/` (the directory name is the owner).
- [ ] P3 attachments in core (inbox #327/#345) — planned 2026-10-07 from four plan inventories; owner decisions: the slot of
      Files' blob equals its role token `FILE_CONTENT` (one token space); `expectedBlobId` travels in the completion
      `payloads` (Files' own contract, no core wire change); the attachment bytes count as a core term in `linkUsage`;
      the server refuses a guest upload with the role `COVER` explicitly (the enum used to say it).
  - [x] P3-S1 crypto — `BlobRole` in `packages/crypto/src/seal.ts` becomes a token (anchored regex `^[A-Z][A-Z0-9_]{0,15}$`),
        no new seal purpose, a new role value `FILE_CONTENT` only (the role is encoded `lp(utf8)`: the bytes of the four
        existing roles stay identical; ADR 0072 asks for suite v2 only on a field change); a new vector block (content key →
        node key → `blob-key` with `FILE_CONTENT`) from an independent Python implementation; conformance rejection cases
        (`album`, empty, 17 × `A`, `1X`, `ORIGINAL\n`); the commit records the exit code of `npm run mutation -- --force`
        (web/CLAUDE.md) · Done when: `cd packages/crypto && npm run check`, web `npm run check`, bite tests (an unanchored
        regex or a closed list → red).
        **Measured 2026-10-07:** `BlobRole` is `string` in `seal.ts` checked by `/^[A-Z][A-Z0-9_]{0,15}$/` (equal to `NodeKind`;
        a `$` without the `m` flag matches only at the input's end, so `ORIGINAL\n` fails); no new seal purpose; the new block
        `file_blob` in `v1.json` (content key → file node's `content-key` → `blob-key` with `FILE_CONTENT`, owner = the node id)
        from an independent Python script that first reproduced an existing `blob-key` and `content-key` vector byte for byte;
        `seal.sym`/`seal.asym` are JSON-identical; crypto 341 → 369 tests, web 1861 unchanged, 53/53 `validate_*`, the mutation
        gate exit 0 at 100.00 % (466 mutants: 459 killed, 2 timeout, 5 ignored). Bites: the unanchored regex 7 red, the closed list
        11, `lp` dropped 13. `export type BlobRole` in `media.ts` points at the generated (still closed) contract enum, so no
        cast. Owed to S2: `mediaDeriver.ts:17` (`Extract<BlobRole, …>` of the GENERATED type becomes `never` with the contract
        token), the descriptions in `blob.openapi.yaml` ~:363-366 and the sharing copy ('exactly these four'), and ADR 0079 §9
        (it says a new kind adds seal purposes and asks HKDF with the application id: say once that random node keys under the
        parent's key and `role` + `ownerId` satisfy it: P5).

  - [x] P3-S2 contract + references — `BlobRole` token in `_common.openapi.yaml` (as `NodeKind`), `$ref` from `blob.openapi.yaml`
        and `sharing.openapi.yaml` (its second copy, without `COVER`), `@Pattern` on every request property, the two tuples
        of `validate_contracts_common` go, Memories' roles become its own constants (`MemoryBlobRole` in `features/memory`,
        Kotlin constants in `apps/memories`; `mediaDeriver.ts` `Extract<BlobRole, …>`), the server refuses a guest `COVER` ·
        Done when: guards, `./gradlew check`, `npm run check`, a bite test per guard.
        **Measured 2026-10-07:** `_common`'s `BlobRole` (`^[A-Z][A-Z0-9_]{0,15}$`, `maxLength: 16`, visibility `structure`)
        generates a `String`; both request properties carry `@Size(max = 16) @Pattern`; the web client has no `BlobRole`
        left. Core names no role (it compares, `UploadSessionEntity.boundTo`; its `cover_blob_id` is a column, not the
        role): `validate_kernel_imports_no_slices` reads Memories' four roles as it reads its kinds (guard-gap case 66).
        Memories: `MemoryBlobRoles` (Kotlin), `MemoryBlobRole` (`features/memory/blobRole.ts`); the neutral transport in
        `media.ts` takes `role: string`, `mediaDeriver`'s tiers are a literal union. The guest start asks the new
        `ShareGuestWrites.admitsGuestRole` past the gate and refuses the whole request `400 invalid_request` (the
        contract's existing 400 code; the enum used to give a 400 too): Memories admits ORIGINAL/THUMBNAIL/PREVIEW. The
        completion now refuses a derivative in a role outside `MemoryBlobRoles.DERIVATIVES` (`upload_mismatch`): a session
        started as ORIGINAL and declared as a derivative of kind ORIGINAL passed the binding check alone (the media
        contract's closed `MediaDerivativeKind` refused it at the wire only). A member's start takes any token; a session
        in a role Memories names nothing for is never attached and expires as any unfinished one. Bites: `@Pattern` off the
        sharing property → `validate_request_bounds` red; `COVER` in `MemoryBlobRoles.GUEST` → the guest test red (200);
        the derivative check off → the seam test red; a `"COVER"` literal in `core/blob/Entities.kt` → the kernel guard
        red; the roles off the kernel guard's list → case 66 red. B0 and P4-0 snapshots untouched.
  - [x] P3-S3 schema + guards — `veyq_node_attachment(id, scope_id, node_id, slot, blob_id, share_identity_id, created_at)`,
        UNIQUE (node_id, slot) and (blob_id), foreign keys to scope, node, blob and share identity WITHOUT `CASCADE` (a cascade
        would delete the row without `deleteBlobRow`: a silent orphan, there is no sweep), baseline edit + pin line; the
        fourth arm of `validate_blob_reference_arity` (`blob_id` on its own line), a core `AttachmentNodeSubtreeEraser` and
        `AttachmentScopeEraser` (phase < 500), `SharedSubtreeContributor` and a core `GuestIdentityUsage` over attachments
        (document that an application without its own usage no longer fails closed) · Done when: a purge slice test (attachment
        and blob gone, nothing orphaned), the guard bites (list edits back → three keys red; contributor removed → live twin
        red).
        **Measured 2026-10-07:** the table is in core's baseline after `veyq_share_identity` (it references it), constraints
        `ux_attachment_node_slot`, `ux_attachment_blob`, `fk_attachment_{scope,node,blob,share_identity}` (none cascades),
        indexes on `scope_id` and `share_identity_id`; pin line re-hashed in the same commit (`release: none`). **The owner
        recreates the dev database** (`docker compose -f infra/docker-compose.yml down -v`): a changed baseline fails Flyway's
        checksum on every database that ran the old one. Everything lives in `core/blob` beside `BlobEntity`
        (`NodeAttachmentEntity`, `@DynamicUpdate` — `DynamicUpdateEverywhereTest` asked for it). `AttachmentNodeSubtreeEraser`
        (phase 200) reads the guest ids, bulk-deletes the rows, then frees each blob through `deleteBlobRow`;
        `AttachmentScopeEraser` (phase 200) deletes rows only (`ScopePurge` records the blobs); the contributor authorizes the
        attachment blobs of core's live node ids (an attachment has no trash of its own); `AttachmentGuestIdentityUsage`
        counts `share_identity_id`. With core's usage the `@All` set is never empty, so `GuestIdentityUsage.unreferenced`'s
        fail-closed branch no longer occurs at runtime — its KDoc says every application whose rows name a guest identity
        must implement it (Memories does; its `share_identity_id` has no FK, so a missing usage there would be silent).
        `CoreAloneSliceTest` now pins core's own attachment beans in four seams; the Memories order tables carry phase 200.
        Guards: blob arms 3 → 4 (2 → 3 of them FK-backed, all 4 in the live twin), node-keyed entities 4 → 5, scope closure
        13 → 14 tables, `validate_store_reclaim_arity` does not react (no `BlobStore` call added); 53/53 green before and
        after. Bites: the three list edits back → entity, schema and backstop red "unexpected reference arm"; the contributor
        file removed → live twin red; the node eraser removed → node-subtree guard red; the scope eraser removed → scope
        guard red. Guard-gap cases 67–69 (rejected: those three forgeries) and 70–71 (open: the scope eraser moved past the
        blob rows, and the node eraser keeping the blobs, both green there and held only by the slice test), 64 → 69 cases.
        `NodeAttachmentSliceTest` (core alone, 7 tests) — each red with its lever, green without: `deleteBlobRow` dropped →
        the subtree purge leaves the blob row; the guest set not returned → the dead link's guest survives; the scope eraser at
        650 → `FK_ATTACHMENT_BLOB` refuses the purge; the contributor empty → the guest's 200 is a 404; the usage always
        false → the identity delete fails on `fk_attachment_share_identity`; `ON DELETE CASCADE` on the node FK → the node
        delete is no longer refused; `ux_attachment_blob` dropped → the second attachment of one blob is accepted.
        `./gradlew check` green (core 849, memories 361 with 2 skipped, root 84). On MariaDB 11.4 (the pinned image):
        `run_backend_races_on_mariadb.py` green (43 race filters, both projects — the baseline with the new DDL migrates
        there), and with `--tests` `NodeAttachmentSliceTest` 7/7, `CoreAloneSliceTest` 8/8 and both eraser-order tables
        green — the FK refusals come from the MariaDB driver there.
  - [ ] P3-S4 `AttachmentStore.attach` — one transaction: `admitted()`, scope lock, consume the session as the first write,
        liveness and kind by QUERY (not `findById`: the stale-context trap), `persistBlob` and an explicit flush, a CAS
        `UPDATE … blob_id = expected` (an insert only into an empty slot; the loser gets `conflict`), `deleteBlobRow(old)`;
        replace on a trashed node refused · Done when: a race test on H2 and MariaDB (model `NodeCoverSliceTest`
        `whileUncommitted`), levers: scope lock removed, CAS predicate removed.
  - [ ] P3-S5 quota — attachment bytes as a core term of `linkUsage`/`StorageQuota.linkFits` · Done when: a byte-limit test at
        the exact boundary.
- [ ] P4 web re-cut — planned 2026-10-07; two strands. Owner decision: the toolbar (trash, grid/list) stays in Memories'
      section until Files needs it (the visible change P4e is deferred). Every e2e suite is API-level, so only golden and
      characterisation tests prove 'no user-visible change'.
  - [x] B0 characterisation test of the upload pipeline — `randomBytes`/`randomUUID` and `fetch` stubbed; per frame method, path,
        body and SHA-256 for `addMedia`, `uploadCoverBlob` and `addSharePhoto`; plus the `vi.mock` sabotage of web/CLAUDE.md ·
        Done when: it goes red for a changed `PUMP_IN_FLIGHT` or `PUT_CONTENT_TYPE`.
        **Measured 2026-10-07:** `features/memory/uploadWire.characterisation.test.ts` pins `crypto.getRandomValues` (a seeded
        stream: every `K_blob`, nonce, seal), `crypto.randomUUID` (a counter) and `Date.now`, reset per scenario; a fake
        server at `fetch` signs 3 PUTs per window and answers each PUT after a timer; the real `media.ts`, `share.ts`,
        generated clients, `api/client.ts` and `withRefresh` run (only `exifr` is mocked, so the EXIF tier and the derived
        pixels are not pinned). Recorded per request in order: method, path, headers (`content-type` by value, the rest
        `<present>`), the canonical JSON body, a PUT body's length + SHA-256; plus the max in-flight PUTs and the result,
        into `__snapshots__/uploadWire.*.json`. Each scenario runs twice and must record the same. Pinned: `addMedia`
        one-chunk image 5 requests / 3 PUTs / max 1; `addMedia` 7-chunk original 13 / 9 / max 4 (2 windows);
        `uploadCoverBlob` 3-chunk cover 5 / 3 / max 3; `addSharePhoto` (below the root, with a share password) 5-chunk
        original 10 / 7 / max 4 (1 window), no `authorization`. Red for `PUMP_IN_FLIGHT` 4→3, `PUT_CONTENT_TYPE`,
        `CHUNK_SIZE` halved, blobs pumped last-first, no window lookahead, the original's `blob-key` wrap sealed under
        another role; green restored. A deliberate wire change updates it with `npx vitest run -u <the file>` and the
        snapshot diff is part of that change; B1 must leave the files untouched. `vi.mock` sabotage (each export made to
        throw): `addMedia`+`uploadCoverBlob` — the 8 suites that mock `media` green, `media.test.ts` and the
        characterisation red; `blobApi` — its 5 mockers green, the characterisation red; `withRefresh` — its 14 mockers
        green, `media.test.ts` and the characterisation red. B1 repeats it on the new `core/blob` paths.
  - [x] P4-0 golden test of the Memories level view — really mounted (fixtures of `MemoryBrowse.test.ts`), serialising tag, class, text,
        aria-label, disabled; root and folder, grid/list/map, readonly; `toMatchFileSnapshot` · Done when: a changed class
        goes red. Measured: `web/src/views/MemoryHomeView.golden.test.ts` mounts `MemoryHomeView` over the real registry,
        `MemoryLevelHost`, `MemoryBrowse`, real crypto and catalog; 8 snapshots in `__snapshots__/MemoryHomeView.golden/`
        (root grid/list/map, folder shareable/unshareable/readonly/readonly-unshareable, empty folder), serialised by
        `src/test/domGolden.ts` (own test). Levers, each red then restored green: a renamed `memory-folder-overview-card`
        (5 red, a one-line diff), the share button's `v-if` dropped (1), `memory.folderCreate` → `memory.create` (5), the
        two sections swapped (7). It pins the DOM, not layout or computed CSS. Snapshots are `.txt`, which
        `validate_stylesheet_anchors` does not read, so they keep no dead rule alive for P4d. Update only on purpose:
        `npx vitest run -u src/views/MemoryHomeView.golden.test.ts`, then review the snapshot diff.
  - [x] B1 move the neutral blob transport to `web/src/core/blob/{types,transport}.ts` (`planBlob`, `reserveAndPump`,
        `authedReserve`/`authedWindow`, `windowPuts`, `openBlob*`, `presignAllChunks`, `makeChunkProvider`; `pumpBlob`,
        `sealFrameAt`, `putChunk`, `getChunk` private); `features/memory/media.ts` re-exports; `uploadMediaVia` is split
        (planning and pumping to core; derivatives, metadata sealing and complete stay) · Done when: B0 unchanged and green,
        the core-import guard bites on an import from `features/memory`.
        **Measured 2026-10-07:** moved verbatim (bodies unchanged, `memoryKey` params named `key`): `core/blob/types.ts`
        holds `BlobRole` (a plain re-use of the generated blob client's type, as `media.ts` had it), `BlobRef`, `BlobPlan`,
        `BlobDeclare`, `ChunkPut`, `ReservedUpload`, `WindowFn`; `core/blob/transport.ts` holds `blobKeyContext`,
        `sealFrameAt`, `WINDOW_LOOKAHEAD`, `pumpBlob`, `PRESIGN_REFRESH_MS`, the chunk PUT/GET helpers and their status
        parsers (all private, as before) and exports `CHUNK_SIZE`, `PUMP_IN_FLIGHT`, `planBlob`, `throwIfAborted`,
        `reserveAndPump`, `authedReserve`/`authedWindow` (were the private `mediaReserve`/`mediaWindow`, same signatures;
        nothing outside imported them, so no aliases), `windowPuts`, `openBlobFromUrls`, `presignAllChunks`, `openBlob`,
        `makeChunkProvider`. It imports `@veyq/crypto`, `shared/httpError`, `api/client` (`blobApi`), `app/sessionRefresh`
        and types from `generated/blob` and `sw/protocol` only. `media.ts` keeps its own `BlobRole` line untouched (P3-S2's),
        the meta/EXIF tiers, `CompleteDecl`, `MediaUploadTransport`, `mediaApiTransport`, `addMedia`, `uploadCoverBlob`,
        `openGrid/Lightbox/Original`, `chunkProvider`, and re-exports `CHUNK_SIZE`, `PUMP_IN_FLIGHT`, `windowPuts`,
        `openBlobFromUrls`, `openBlob`, `makeChunkProvider` and the five types; no importer changed. The `uploadMediaVia`
        split is only that its `planBlob`/`reserveAndPump` calls are core's now; the order is the same. Tests: the six
        `makeChunkProvider` cases moved unchanged to `core/blob/transport.test.ts`; the five pump cases and the read-back
        ones stay in `media.test.ts`, since they drive `addMedia`/`listMedia`/`uploadCoverBlob`; the core test adds
        `planBlob`, `reserveAndPump` over `authedReserve`/`authedWindow` (windows, in-flight bound, progress, round trip
        through `openBlob`), the pre-abort and `windowPuts`. `npm run check` 199 files / 1879 tests → 200 / 1884; 53
        validators green before and after, the core-import guard reading 182 → 184 core files; B0 and P4-0 snapshots
        untouched. Levers, each red then restored green: an import of `features/memory/media` in `transport.ts` (guard
        red), `PUMP_IN_FLIGHT` 4→3 in core (B0: 2 of 4 red), planning the blobs before `sealMetadata` in `uploadMediaVia`
        (B0: 3 of 4 red). `vi.mock` sabotage on the new paths: `blobApi` throwing — its 6 mockers (the new core test is one)
        green, B0 red (3 of 4; the share upload does not use it); `withRefresh` — its 14 mockers green, `media.test.ts`
        (47), B0 (3) and the core test (1) red; `reserveAndPump` + `makeChunkProvider` throwing in core — nobody mocks
        `core/blob/transport`, the 8 `media` mockers green, the core test (8), `media.test.ts` (31), `sealBinding.test.ts`
        (2, through the re-export) and B0 (4) red; `addMedia` + `uploadCoverBlob` — the 8 `media` mockers green,
        `media.test.ts` (32) and B0 (3) red, the core test green. `sw/range.ts`'s `PLAINTEXT_CHUNK_SIZE` stays a literal:
        importing `CHUNK_SIZE` would pull `@veyq/crypto` and the API client into the service-worker bundle, which that
        file keeps out on purpose; `core/blob/transport.test.ts` asserts `CHUNK_SIZE === PLAINTEXT_CHUNK_SIZE` (and
        `range.test.ts` already pins it to the crypto export).
  - [x] P4a list slots — `addSlotItem`/`slotItems` for `topbarTray` and `sidebarPins` (ordered, write-once per (slot, id)) ·
        Done when: `registry.test.ts`, `AppShell.slots.test.ts`, `applications.test.ts` updated, a two-entry order case, P4-0
        unchanged.
        **Measured 2026-10-07:** `registry.ts` gains `ListSlotName = 'topbarTray' | 'sidebarPins'`, `SlotItem {id, component}`,
        `addSlotItem(slot, id, component, order?)` (a duplicate id in the same list throws naming both; `order` defaults to
        the registration index; the sort is the stable `Array.sort`, so a tie keeps registration order) and
        `slotItems(slot): readonly SlotItem[]` (a sorted copy). `SlotName` narrows to `levelView | nodeMutationDialog |
        shareDialog`; `claimSlot` also refuses a list slot at runtime, `addSlotItem` a single one. `AppTopbar`/`AppSidebar`
        render `<component :is v-for :key="id">` in the same place, no wrapper; Memories adds `memories.upload-status` and
        `memories.pinned`. `npm run check` 200 files / 1884 tests → 200 / 1896; 53 validators green before and after
        (identical); B0 and P4-0 snapshots untouched. The one-item DOM is pinned by hand-written expectations
        (`AppShell.slots.test.ts`), shown equal to the old templates by running them (their lookup adapted to
        `slotItems(…)[0]`) against the new tests: those passed, only the two-item cases went red. Levers, each red then
        restored green: duplicate check dropped (1 red), `order` ignored (3), `AppTopbar` rendering only the first item
        (1), `claimSlot`'s list-slot guard dropped (1), `topbarTray` back in `SlotName` (typecheck red: an unused
        `@ts-expect-error`). Not verified in a browser.
  - [x] B2 the upload queue with a job runner in `web/src/core/blob/queue.ts` (`UploadJob<R> = {name, sizeBytes, run({onProgress,
        signal})}`; six states, one automatic retry, never for `quota_exceeded`); Memories' `enqueueUploads` is a thin adapter ·
        Done when: the queue cases split into generic and Memories ones, `quota_exceeded` never retries, an abort gives
        `cancelled`.
        **Measured 2026-10-07:** `core/blob/queue.ts` exports `UploadStatus` (the six states unchanged), `UploadJobContext
        {onProgress(fraction), signal}`, `UploadJob<R, X> {name, sizeBytes, run(ctx): Promise<R>, extra?: X}`, `UploadItem<R, X>`
        (`X` & id, name, sizeBytes, status, progress, error?, attempts, controller, `result?: R`), `UploadBatch<R, X, B>` (`B` &
        id, label, `key?`, `files`, seq, finishedAt, acknowledged, onComplete?, `onItemDone?`), `enqueue(jobs, {label, key?,
        onComplete?, onItemDone?, extra?}): string | null`, `QUOTA_EXCEEDED`, `isRetryable`, `cancelFile`, `cancelBatch`,
        `cancelAll`, `retryFile`, `removeFile`, `acknowledgeBatch`, `clearUploads`, `useUploadQueue`; the runner, retry,
        abort and progress rules are moved verbatim. `features/memory/uploadQueue.ts` keeps `UploadTarget`, `batchKey`,
        `UploadFn`, the picker helpers and `enqueueUploads(target, selections, opts)` (same signature): one job per file whose
        `run` is `target.upload ?? addMedia(...)`, `extra` {mediaType, file} on the item and {target} on the batch, `key:
        batchKey(target)`, and an `onItemDone` that sets `file.mediaId = file.result?.mediaId` and then calls the caller's
        `onFileDone`; it re-exports the core names, so no importer changed. Decisions (each reversible): (1) progress stays
        a fraction 0..1, not `bytesDone` as the plan sketched — `addMedia` reports a fraction of its chunks (derivatives
        included), and bytes would divide by a zero `sizeBytes`; (2) the core batch keeps the array name `files` and the
        functions their names (`cancelFile`, `retryFile`, `removeFile`), because the brief asked for today's names and every
        consumer and the hand-built batches in `MemoryDetail.optimistic`/`ShareView.order` read `batch.files`; (3) the
        application's own fields ride as `extra` copied onto the item/batch, so Memories' `UploadFile`/`UploadBatch` keep
        their shape (`file`, `mediaType`, `mediaId`, `target`) and `optimisticUpload`, `useOptimisticTiles` and the status
        menu are untouched; the batch's stored hook is `onItemDone` (Memories' type lost the `onFileDone` field nobody read);
        (4) the job lives in a module `Map` by item id, not on the reactive item; (5) `mediaId` is set in the settle hook,
        after `status = 'done'` in the same synchronous step, where it was set before — no watcher runs in between (no
        `flush: 'sync'` watcher reads the queue); (6) Memories' `useUploadQueue` keeps only batches carrying a `target`, so
        another application's batches never reach `batchKey`; `activeFileCount` stays core's (all batches) — moot until B3
        moves the menu. Tests: 15 cases in `uploadQueue.test.ts` → 12 there (addMedia wiring, File unread, mediaId, onFileDone
        order, the share transport's quota and settle, the item's file/mediaType, the abort signal handed to addMedia, empty
        pick, the 3 picker helpers) + 16 in `core/blob/queue.test.ts` over a fake runner (states, progress flip, order across
        batches with max one in flight, onItemDone before onComplete, retry, quota never retried, retryFile, abort/AbortError →
        cancelled, cancelFile/Batch/All, remove, acknowledge, clear, `extra`). Coverage yaml rows `global-uploads-tray`,
        `progress-and-cancel`, `transient-retry`, `delete-own-in-session`, `the-member-is-told-there-is-no-space` name the
        core test. `npm run check` (fresh `generate:api`) 200 files / 1896 tests → 201 / 1909; 53 validators green before and
        after, the core-import guard 184 → 185 core files; B0 and P4-0 snapshots untouched. Levers, each red then restored
        green: retry also for `quota_exceeded` (3 red: the core quota case, Memories' share-quota case, the status menu's
        no-space case); an abort leaving the status as it was (6 red: 4 core cancel cases, Memories' signal case, the status
        menu's cancel); `import … from '../../features/memory/uploadQueue'` in `queue.ts` (guard red); the adapter returning
        only `{blobId}` (3 Memories cases red). `vi.mock` sabotage: `enqueue` + `useUploadQueue` throwing in core — nobody
        mocks `core/blob/queue`; the three suites that replace `features/memory/uploadQueue`'s queue functions
        (`MemoryDetail.optimistic`, `ShareView.order`, `ShareView.picker`) green; red: the core test (16), `uploadQueue.test.ts`
        (9), `UploadStatusMenu` (4), `AppTopbar` (2), `MemoryDetail` (30), `MemoryShareBody` (7), `MemoryShareBody.embedded`
        (7, its partial mock keeps the real `useUploadQueue`), `ShareView.folder` (1). Not verified in a browser.
  - [ ] B3 the status menu to core and the tray becomes a list (`register.ts` loses its tray claim; needs P4a).
  - [x] P4b a core level view `core/node/LevelView.vue` — overview card, application sections ordered by `order`, the folder
        section, a fallback section for a kind nobody sections ('not supported', never an empty area) · Done when: P4-0
        unchanged, `LevelView.test.ts`.
        **Measured 2026-10-08:** `registry.ts` gains `KindDescriptor.section?: KindSection {order, component}`,
        `LevelSectionProps {folder: TreeNode | null; nodes: readonly TreeNode[]; epochs: EpochKeys | null; readonly:
        boolean}`, `LevelSectionEmits {openNode: [node]; create: [mode: string]; openTrash: []}`, `LevelSection {component,
        order, kinds}` and `sections(): readonly LevelSection[]`; `LevelViewProps`/`LevelViewEmits` and `'levelView'` in
        `SlotName` are gone. Rule: a section is keyed by its component's identity — rendered once, handed the children of
        every kind that names it (kinds in registration order, each bucket in `childrenOf`'s order); sorted by `order`, a
        tie in the order each section was first named; a kind naming an existing section's component with another `order`
        makes `registerKind` throw and registers nothing (a re-registered kind throws before its section is added).
        `core/node/LevelView.vue` (props `folder, level, epochs, readonly, shareable`; emits `openNode, create, renameFolder,
        deleteFolder, openTrash, share` — the old slot contract) renders the overview card, the sections, one fallback
        section per unsectioned kind, then the Folders section; the overview card and the Folders section are
        `MemoryBrowse.vue`'s markup moved verbatim with their `memory-*` classes and `memory.*` keys (P4d renames them, a
        comment says so). `features/memory/components/MemoriesSection.vue` is the Memories half (cards, date sort, covers,
        favourites — the heart's toggle moved in from `MemoryLevelHost` —, the create card, `SpaceMap`, the toolbar with
        the trash and `ViewModeToggle`), registered as `section: {order: 10}` on MEMORY and CHAPTER; `MemoryBrowse.vue` and
        `MemoryLevelHost.vue` are deleted; `MemoryHomeView` renders `LevelView` where the slot was (its unsupported branch
        is now `isOpenable` only). Files registers no section: a FILE child shows in the fallback.
        `npm run check` (fresh `generate:api`) 202 files / 1915 tests → 203 / 1941 (`LevelView.test.ts` 17 new: order,
        shared-once, empty section, props/events, fallback for a registered and an unregistered kind, folders, map/list,
        read-only, the overview cases moved from `MemoryBrowse.test.ts`; that file is `MemoriesSection.test.ts` now, 10
        cases; `registry.test.ts` +7; `applications.test.ts` +1; `MemoryHomeView.test.ts` drives the real `LevelView` over
        a stubbed `MemoriesSection`, incl. a FILE child in the fallback over the real Memories + Files registry;
        `MemoryHomeView.registry.test.ts`'s unclaimed-`levelView` case is now "no section registered: folders walkable,
        unsectioned kinds in the fallback"); 53/53 validators green before and after, the core-import guard 185 → 186 core
        files (Memories 64 → 63), stylesheet anchors 636 → 639. The golden test's control case now asserts
        `registry.sections()`; the 8 P4-0 snapshots and the B0 snapshots are byte-identical (no `-u` run).
        `AppShell.slots.test.ts` needed no change (it never touched `levelView`). Levers, each red then restored green:
        fallback filtered out (4 red: LevelView 2, MemoryHomeView FILE, registry host); `sections()` unsorted (3 red);
        a shared section rendered once per kind (9 red: the LevelView once-case and 8 of 8 golden snapshot cases); an
        import of `features/memory/kinds` in `LevelView.vue` (guard red on `import`); the sections block after the Folders
        section (golden 7 red, the map scenario has no folders); the overview share `v-if` dropped (golden 1 red).
        **Decisions taken (owner, please review; each reversible):** (1) `create` still carries the create MODE the
        `nodeMutationDialog` understands (`create-memory`, `create-folder`), not a kind — P4c re-cuts the dialogs per kind
        and changes the payload with them. (2) A section renders even with no child of its kinds (Memories' empty state
        and create card are in the golden). (3) The view-mode seam: core reads the shared `useViewMode` for the Folders
        grid's list variant and hides the Folders section in map mode, as `MemoryBrowse` did — no event between section
        and core; P4e may move grid/list into a core toolbar. (4) The fallback: one section per kind, sorted by the kind
        token, after the application sections and before the Folders; heading = the raw kind token (no kind has a label
        yet; F1 replaces FILE's), a count, rows as non-clickable `div.veyq-card.veyq-node-card.veyq-node-card-unsupported`
        (opening one would land on the same unsupported state) with the kind's icon (`pi pi-question-circle` for an
        unregistered kind), the name and `home.unsupportedKind`; shown in map mode too. Three new `veyq-*` rules in
        `ui.css`, tokens only. (5) CHAPTER names the section too, although the backend places a CHAPTER only in a MEMORY:
        an impossible chapter at a folder level would show as a Memories card rather than "unsupported". (6) The trash
        stays in Memories' section toolbar (the owner's P4 decision); core forwards `openTrash`. (7) `sections()` is read
        once per mount, as the host reads its slots.
        **Not verified:** no browser — the layout of the new fallback rows, the visual order and the keyboard (tab) order
        across sections are unverified; the golden pins the DOM only.
  - [ ] P4c dialogs per kind — a core `FolderNameDialog` (create folder, rename anything), `createDialog` and `shareDialog` per
        kind; the `nodeMutationDialog` slot goes (`levelView` went in P4b) · Done when: `MemoryHomeView*.test.ts` updated.
  - [ ] P4d CSS and i18n neutral — the `memory-folder-*`/`memory-create-card` rules move to `veyq-*` twins (dark tokens), the dead
        rules are deleted (`validate_stylesheet_anchors`), the generic `memory.*` keys move to a core namespace (en + de) ·
        Done when: the golden test is updated deliberately (class names only).
- [x] P5 ADR 0079 §9 says key derivation takes the application id (HKDF); random node keys under the parent's key satisfy it:
      say so once, in place · Done when: `validate_doc_citations` green. Done 2026-10-07 with P3-S2: §9 no longer says a new
      kind adds seal purposes and names the random node key + `ownerId`/`role` in `blob-key` as meeting both rules.

### Files
- [x] F0 skeleton `:apps:files` — settings, root build, module build (only `:core`), `META-INF/beans.xml`, `FilesNodeKindRules`
      (`FILE` only under the root or a `FOLDER`, no children), two `Dockerfile` COPY lines, an assertion in
      `ModuleCompositionTest` · Done when: `validate_backend_module_graph` green with two applications, `./gradlew check`
      green. UNVERIFIED: the `veyq.files` config prefix against core's `veyq` mapping; Flyway finding migrations of every
      module jar at run time (not needed before F1).
      **Measured 2026-10-07:** the module is added in `settings.gradle.kts`, the root's `dependencies`, the `Dockerfile`
      (build file beside Memories' in `backend-build-base`, `src` beside Memories' in `backend-build-jvm`), and named in
      `Composition.kt`, `backend/CLAUDE.md`, `web/CLAUDE.md`, `docs/apps/{README,files}.md`; the other hits of
      `apps/memories` (infra, the chart, CI, `run_backend_races_on_mariadb.py`, `backend_tree.py`, `MainOutputs`,
      `validate_doc_citations.py`) derive the modules and need no line. `apps/files/build.gradle.kts` = `veyq.module` +
      `io.quarkus` (for its own `@QuarkusTest`, as Memories), `:core` + `testFixtures(:core)`; no `veyq.openapi` — the build
      does not demand it while `contracts/api/files/` is absent (its check runs only for an existing directory). Code:
      `me.raddatz.veyq.files.FilesNodeKinds` + `FilesNodeKindRules` in one file (Memories' shape), `parentAllows` also checks
      the kind itself. The fast-jar's `lib/main` holds `me.raddatz.veyq.{core,files,memories}.jar`; the Files jar holds the
      two classes and `META-INF/beans.xml`. `./gradlew check`: core 849, memories 361 (2 skipped), files 8 (new), root
      84 → 85, green; `run_backend_races_on_mariadb.py` green (43 filters, `:core` + `:apps:memories`; Files has no race
      class), and with `--tests` `FilesNodeSliceTest` (4/4, own database `veyq_apps_files`, core's baseline only) and
      `ModuleCompositionTest` green on MariaDB 11.4. Tests: `FilesNodeKindRulesTest` (plain JUnit: the matrix, FILE under
      FILE/MEMORY/CHAPTER refused, no rule of core or Files admits FILE as a parent); `FilesNodeSliceTest` (core + Files, no
      Memories: the partition, create at root and in a folder 201, a FILE or FOLDER under a FILE 422 `illegal_structure`,
      a share on a FILE 422 `illegal_scope` beside a FOLDER share 201, rename/move folder→folder→root 204 with the
      placement version counted, a move into a FILE 422 and unchanged); `ModuleCompositionTest` gains the Files anchor in
      the seam union and `theRulesOfCoreAndEveryApplicationPartitionTheKinds` (FOLDER/MEMORY/CHAPTER/FILE exactly one rule
      each, nothing under a FILE) — the only place both applications run, so the cross-application partition lives in the
      root, not in the module. Web: `features/files/{kinds,register}.ts`, `registerKind({FILE, 'pi pi-file', shareable:
      false, slug: fileNameSlug})`, `registerApplications()` calls it after Memories; `npm run check` 200 files / 1896 tests
      → 201 / 1902 (5 in `register.test.ts`, 1 in `applications.test.ts`), B0 and P4-0 snapshots untouched.
      `validate_web_core_imports_no_applications` now reads `APPLICATIONS` (owner → web directory: `memories` →
      `features/memory`, `files` → `features/files`), clients derived per owner, a new `application` rule (no application
      imports another or its clients), `FILE`/`NodeKind.File` in the kind rule; core files read 184 before and after.
      53/53 `validate_*` before and after; guard-gap cases 69 → 75 (72–76 new, 77 below).
      **Decisions taken (owner, please review):** (1) no `FileDetailHost`: `KindDescriptor.detail` is optional and an
      opened FILE already renders the host's explicit `home.unsupportedKind` state, so no placeholder component or i18n
      key. (2) No runtime test that "core's beans do not depend on files": no compilable lever can make it red (core has
      no edge to Files; Gradle refuses the cycle, rule (b) the edge) — it would be a test that cannot fail. (3)
      `ModuleCompositionTest` names `FILE` and `me.raddatz.veyq.files.FilesNodeKindRules` as strings, not imports, so a root
      build that drops the module fails at run time (2 of 4 red), not at compile. (4) **Rule (a) tightened**: every
      included module must ship (the root reaches it over a runtime edge) — with the root's Files line dropped, every
      guard was green and only the composition test caught it; case 77 pins it, and the demo/other forgeries of cases 50
      and 62–65 now compose their module into the root. (5) The module's tests boot core + Files with its own
      `src/test/resources` (Memories' keys minus the share-password bound it does not need).
      **Bites, each red then green:** root line dropped → `ModuleCompositionTest` 2/4 red and rule (a) red;
      `FilesNodeKindRules.handles` also `FOLDER` → files 6/8 red, root 2/4 red; a `memories` import in `FilesNodeKindRules.kt`
      → `:apps:files:compileKotlin` red (`Unresolved reference 'memories'`), the guard stays green on it (it reads build
      files) — with `implementation(project(":apps:memories"))` in Files' build the import compiles and rule (b) is red;
      `'FILE'` + an import of `features/files` in a `core/node` file → web guard red on `kind` and `import`, the old guard
      green; `features/memory` re-exporting `features/files` → red on `application`, old guard green; `features/files`
      importing `features/memory` → red on `application` (the old guard was red too, but treating Files as core).
      **Level view, unchanged:** `browse.ts` puts a FILE child into `level.byKind['FILE']`, and Memories' `MemoryLevelHost`
      reads only `MEMORY` — a FILE in a folder is silently not shown, and a folder holding only files shows Memories'
      empty state. P4b's fallback section is what fixes it; F0 changes nothing there.
      **Not verified:** the Docker image build, a cold-home verification-metadata run (the module resolves what Memories'
      identical plugin set does; the warm build verified clean), Flyway at run time (no migration), the `veyq.files`
      config prefix (none added).
- [ ] F1 a member uploads and downloads a file — `UploadCompletion` for `FILE`, `registerKind`, the file list in the folder level
      view, the download decrypted in the browser, delete into the trash · Done when: an e2e round trip with identical bytes.
- [ ] F2 a folder share with files — read, then contributor: a guest creates the `FILE` node (`createShareNode`) and uploads
      into it; a file in the trash answers 404; Files' `GuestIdentityUsage` · Done when: e2e, race tests.
- [ ] F3 the drop box ([ADR 0080](../docs/decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
      decision 11) — kind `FILES_DROPBOX`, `identity_id` unique per (drop box, identity), members only delete or move out,
      `ShareRoots` {`FILES_DROPBOX` → `WRITE_ONLY`}, an own operation to create the identity folder (a `WRITE_ONLY` guest
      holds no key of the drop box) · Done when: two guests get two folders, a second upload of one identity lands in the
      same folder, revoking the link keeps the content.

## Definition of done

All boxes ticked with their proofs, `./gradlew check`, `npm run check` (web, admin-web), the guard glob, MariaDB races and
the e2e suites; `docs/apps/files.md` is the application's description (no longer a concept), `docs/coverage/` has Files'
use cases; what the work taught is harvested into ADRs, rules and comments, then this file is deleted.

## Retrospective
(Filled at close.)
