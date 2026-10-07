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
  - [ ] P3-S1 crypto — `BlobRole` in `packages/crypto/src/seal.ts` becomes a token (anchored regex `^[A-Z][A-Z0-9_]{0,15}$`),
        no new seal purpose, a new role value `FILE_CONTENT` only (the role is encoded `lp(utf8)`: the bytes of the four
        existing roles stay identical; ADR 0072 asks for suite v2 only on a field change); a new vector block (content key →
        node key → `blob-key` with `FILE_CONTENT`) from an independent Python implementation; conformance rejection cases
        (`album`, empty, 17 × `A`, `1X`, `ORIGINAL\n`); the commit records the exit code of `npm run mutation -- --force`
        (web/CLAUDE.md) · Done when: `cd packages/crypto && npm run check`, web `npm run check`, bite tests (an unanchored
        regex or a closed list → red).
  - [ ] P3-S2 contract + references — `BlobRole` token in `_common.openapi.yaml` (as `NodeKind`), `$ref` from `blob.openapi.yaml`
        and `sharing.openapi.yaml` (its second copy, without `COVER`), `@Pattern` on every request property, the two tuples
        of `validate_contracts_common` go, Memories' roles become its own constants (`MemoryBlobRole` in `features/memory`,
        Kotlin constants in `apps/memories`; `mediaDeriver.ts` `Extract<BlobRole, …>`), the server refuses a guest `COVER` ·
        Done when: guards, `./gradlew check`, `npm run check`, a bite test per guard.
  - [ ] P3-S3 schema + guards — `veyq_node_attachment(id, scope_id, node_id, slot, blob_id, share_identity_id, created_at)`,
        UNIQUE (node_id, slot) and (blob_id), foreign keys to scope, node, blob and share identity WITHOUT `CASCADE` (a cascade
        would delete the row without `deleteBlobRow`: a silent orphan, there is no sweep), baseline edit + pin line; the
        fourth arm of `validate_blob_reference_arity` (`blob_id` on its own line), a core `AttachmentNodeSubtreeEraser` and
        `AttachmentScopeEraser` (phase < 500), `SharedSubtreeContributor` and a core `GuestIdentityUsage` over attachments
        (document that an application without its own usage no longer fails closed) · Done when: a purge slice test (attachment
        and blob gone, nothing orphaned), the guard bites (list edits back → three keys red; contributor removed → live twin
        red).
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
  - [ ] B0 characterisation test of the upload pipeline — `randomBytes`/`randomUUID` and `fetch` stubbed; per frame method, path,
        body and SHA-256 for `addMedia`, `uploadCoverBlob` and `addSharePhoto`; plus the `vi.mock` sabotage of web/CLAUDE.md ·
        Done when: it goes red for a changed `PUMP_IN_FLIGHT` or `PUT_CONTENT_TYPE`.
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
  - [ ] B1 move the neutral blob transport to `web/src/core/blob/{types,transport}.ts` (`planBlob`, `reserveAndPump`,
        `authedReserve`/`authedWindow`, `windowPuts`, `openBlob*`, `presignAllChunks`, `makeChunkProvider`; `pumpBlob`,
        `sealFrameAt`, `putChunk`, `getChunk` private); `features/memory/media.ts` re-exports; `uploadMediaVia` is split
        (planning and pumping to core; derivatives, metadata sealing and complete stay) · Done when: B0 unchanged and green,
        the core-import guard bites on an import from `features/memory`.
  - [ ] P4a list slots — `addSlotItem`/`slotItems` for `topbarTray` and `sidebarPins` (ordered, write-once per (slot, id)) ·
        Done when: `registry.test.ts`, `AppShell.slots.test.ts`, `applications.test.ts` updated, a two-entry order case, P4-0
        unchanged.
  - [ ] B2 the upload queue with a job runner in `web/src/core/blob/queue.ts` (`UploadJob<R> = {name, sizeBytes, run({onProgress,
        signal})}`; six states, one automatic retry, never for `quota_exceeded`); Memories' `enqueueUploads` is a thin adapter ·
        Done when: the queue cases split into generic and Memories ones, `quota_exceeded` never retries, an abort gives
        `cancelled`.
  - [ ] B3 the status menu to core and the tray becomes a list (`register.ts` loses its tray claim; needs P4a).
  - [ ] P4b a core level view `core/node/LevelView.vue` — overview card, application sections ordered by `order`, the folder
        section, a fallback section for a kind nobody sections ('not supported', never an empty area) · Done when: P4-0
        unchanged, `LevelView.test.ts`.
  - [ ] P4c dialogs per kind — a core `FolderNameDialog` (create folder, rename anything), `createDialog` and `shareDialog` per
        kind; `nodeMutationDialog` and `levelView` slots go · Done when: `MemoryHomeView*.test.ts` updated.
  - [ ] P4d CSS and i18n neutral — the `memory-folder-*`/`memory-create-card` rules move to `veyq-*` twins (dark tokens), the dead
        rules are deleted (`validate_stylesheet_anchors`), the generic `memory.*` keys move to a core namespace (en + de) ·
        Done when: the golden test is updated deliberately (class names only).
- [ ] P5 ADR 0079 §9 says key derivation takes the application id (HKDF); random node keys under the parent's key satisfy it:
      say so once, in place · Done when: `validate_doc_citations` green.

### Files
- [ ] F0 skeleton `:apps:files` — settings, root build, module build (only `:core`), `META-INF/beans.xml`, `FilesNodeKindRules`
      (`FILE` only under the root or a `FOLDER`, no children), two `Dockerfile` COPY lines, an assertion in
      `ModuleCompositionTest` · Done when: `validate_backend_module_graph` green with two applications, `./gradlew check`
      green. UNVERIFIED: the `veyq.files` config prefix against core's `veyq` mapping; Flyway finding migrations of every
      module jar at run time (not needed before F1).
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
