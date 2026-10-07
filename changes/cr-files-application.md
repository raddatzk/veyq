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
- [ ] P2 contract owners from directories (inbox #344) — `buildSrc` `SpecSets.kt` derives the owner from the directory
      (top level = core, `contracts/api/<app>/` = application `<app>`, `_common` shared; `requireNoStray` keeps failing for
      anything else), `scripts/contract_specs.py` and rule (d) of `scripts/validate_backend_module_graph.py` derive the same
      way in the SAME commit, `validate_web_core_imports_no_applications.py` derives application clients from any
      `contracts/api/<app>/` pair · Done when: a throwaway second application directory in a scratch tree is accepted and
      generates `me.raddatz.veyq.<app>.generated`, a stray directory without a module is refused, the guard bites.
- [ ] P3 attachments in core (inbox #327/#345) — contract + migration for `veyq_node_attachment`, `BlobRole` as a token
      (contract, `packages/crypto/src/seal.ts`, vectors, web re-export), a core completion helper (replace by compare in the
      `UPDATE`, free the old blob with `deleteBlobRow` in the same transaction), a core `NodeSubtreeEraser` and
      `SharedSubtreeContributor` for attachments, a generic `UploadAdmission` over the attachment row (per-link byte limit
      through `share_identity_id`) · Done when: `validate_blob_reference_arity` has the fourth arm and bites without it,
      the erasure guards stay green, a concurrent-replace race test (MariaDB), conformance both directions.
- [ ] P4 web: the folder level view and the upload queue move to core — the level view belongs to core and applications
      claim per kind (row, detail, mutation dialog, share dialog); `topbarTray` and `sidebarPins` become lists; the blob
      transport (`planBlob`, `sealFrameAt`, the window PUTs, the chunk functions of `features/memory/media.ts`) moves to
      `web/src/core/blob` · Done when: Memories' e2e suites stay green, `validate_web_core_imports_no_applications` green.
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
