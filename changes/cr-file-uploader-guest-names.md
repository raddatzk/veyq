---
id: cr-file-uploader-guest-names
title: A file's page names the guest who uploaded it - the sealed name rides the member's listing
status: planned
satisfies: [uc.files.view-file]
adrs: ["0083", "0030", "0079"]
invariants: [operator-blind E2EE everywhere]
---

## Goal

Where the file page says "Uploaded by a guest" today, a member sees the name the guest gave itself. The member's file
listing carries, next to `uploaderKey` `share:<identityId>`, that identity's sealed display name and its share id; the
web opens it under the nearest ancestor key the member holds. A drop-box guest is named by its identity folder. A guest
of a deleted link stays named. Nothing reaches a share path. Decided by the coordinator on the owner's behalf, 2026-10-14
(inbox #431).

## Context & constraints

- [uc.files.view-file](../docs/features/files/use-cases/uc.files.view-file.md) step 0 owns the behaviour; inbox #190 / ADR
  0030 point 8: no guest reads any guest's name (the residual a link plus a dump accepts stays as it is).
- **Measured (grounding 2026-10-14):** `veyq_share_identity.sealed_display_name` is written by `createIdentity` and read
  only by Memories (`MediaStore.primarySealedNameFor`). Its seal binds `{purpose:'share-identity-name', scopeId, shareId,
  identityId}` (`web/src/core/sharing/guestIdentity.ts`) - opening needs the share id. A READ_WRITE link's name is sealed
  under the share root's content key (members open it through the key chain; Memories tries ancestors nearest-first,
  `features/memory/mediaBuckets.ts`); a WRITE_ONLY (drop box) guest's under its own `K_up` - but Files already names that
  guest by its identity folder (`features/files/dropBox.ts`).
- **Built by cr-file-uploader:** the member-only `FileListItem.uploaderKey`, the guest's separate `ShareFileListItem`
  (no uploader), `features/files/uploader.ts` `uploaderOf`, the "Uploaded by" line in `FileDetail.vue`.
- ADR 0079: Files may not import Memories - the ancestor-key opening moves into core next to `identityNameContext`.
- **Invariant:** operator-blind - the server serves a ciphertext it already stores to a member it already serves; no new
  plaintext, no new relation.

## Inventory (commands)

- `grep -rn "sealedDisplayName\|primarySealedNameFor" backend --include=*.kt`; `grep -n "veyq_share_identity" -A8 backend/core/src/main/resources/db/migration/V202610041200__core_baseline.sql`
- `grep -n "FileListItem\|ShareFileListItem\|uploaderKey" contracts/api/files/files.openapi.yaml`
- `grep -rn "identityNameContext\|openBucketName" web/src`; `web/src/features/files/uploader.ts`, `components/FileDetail.vue` and their tests
- Tests: `backend/apps/files/src/test/kotlin/me/raddatz/veyq/files/share/ShareFilesSliceTest.kt`, `FilesContentSliceTest`;
  Memories' `ShareGuestNamesSliceTest` (the raw-JSON absence pattern); web `FileDetail.uploader.test.ts`, `FileDetail.guest.test.ts`.

## Decisions

1. **Contract:** the member's `FileListItem` gains `uploaderName` (`{ sealedName, shareId }`, nullable), present only
   for a `share:` uploader whose identity row exists; `x-veyq-visibility` as the sealed name's class (opaque). The guest's
   `ShareFileListItem` does not change.
2. **Backend:** Files' listing reads core's identity row for each `share:` key (one query per listing, not per file) and
   fills it; a deleted link's identity is still served (its row survives while an attachment names it).
3. **Web:** a core function opens an identity name under the nearest ancestor key the session holds (moved out of
   Memories' bucket code; Memories then calls core's - behaviour unchanged, its tests stay green); `uploaderOf` names the
   guest with it; inside a drop box the identity folder's name is used; anything that does not open stays "a guest".
4. **Tests that bind it:** the member listing carries the sealed name and share id for a guest's upload and none for a
   member's; the share listing's raw JSON carries neither (bite: inject the field into the share shape); a deleted link's
   guest is still named; the web names the guest, falls back to "a guest" when the name does not open, and a guest
   session shows nothing (guest-mode mount).

## Phases

- [x] 1 contracts: Decision 1. Done when the trust-chain guards are green.
- [x] 2 backend: Decisions 2 and 4 (backend half). Done when `./gradlew check` is green; bitten.
- [ ] 3 web: Decisions 3 and 4 (web half). Done when `npm run check` is green; bitten; a real browser showed a guest's name
  on a file page (the main session clicks it if this runs in a worktree).
  - _Open (implementer, 2026-10-14):_ `npm run check` green and every new web test bitten; the browser pass is NOT done -
    a worktree subagent cannot serve its tree to the browser pane. The main session runs it after the merge (steps in the
    implementer's report: a READ_WRITE folder link, a guest uploading into it, a member opening the file), then ticks this.
- [x] 4 docs: uc.files.view-file step 0 (built), its coverage, `docs/apps/files.md`, ADR 0083's uploader decision, both
  wikis' files page, inbox #431 done. Done when every `scripts/validate_*.py` is green, run on a detached worktree.

## Definition of done

- `./gradlew check` and `npm run check` green; every `scripts/validate_*.py` green on a detached worktree; the browser run
  recorded; coverage updated.

## Retrospective

- **Planning - "present only for a share: uploader whose identity row exists" is a condition that cannot fail.**
  `fk_attachment_share_identity` keeps the identity row while an attachment names it (that is also why a deleted link's
  guest stays named), so the field is present for every `share:` uploader. Harmless; built as written, and the store's
  KDoc names the foreign key instead of promising a branch no row reaches.
- **Planning - the core reader's shape was left to the build, rightly.** "Opens an identity name under the nearest ancestor
  key the session holds" fits two callers that hold their keys differently (Memories: an eager `[node, ...ancestors]` list;
  Files: a `TreeSource` walked upward). Built as `openIdentityName(scope, share, identity, sealed, keys: Iterable)`, so
  Files hands a lazy generator that opens one ancestor key at a time and stops at the first that opens. Memories' existing
  ancestor test was bitten through the moved path (`[memoryKey]` only: red), which is the proof "behaviour unchanged" needs.
- **Execution - a seal context refuses a non-canonical id.** The guest-mode test first sealed under the fixture identity
  `id-1` and threw `SealContextInvalid`: every seal binding an identity needs a canonical lowercase UUID, so a test that
  seals a name mints one.
- **Execution - the guest-mode negative got a stronger form.** Besides "the guest's listing has no field", the guest page
  is mounted on a link listing that DOES carry an uploader key and a name sealed under the file's key, which that guest
  holds: had the guest source read it, the name would have opened. Bitten by giving the guest source the door.
- **Process.** `docs/apps/files.md` was kept to one edited block (the brief: a shared file): the uploader bullet carries
  `uploaderName`; the `listFiles` API line above it still lists `{nodeId, fileBlob|null, uploaderKey|null}` - the contract
  is the source of truth, but a merge may want to add the field there. The worktree sandbox refused heredoc Python that
  quoted Kotlin and a `$VAR` path; scripts written with the editor and called by literal path ran. The new listing read is
  a plain `id in ?1` with no lock, so the MariaDB race run was not repeated. The phase-3 browser pass is open (above).
