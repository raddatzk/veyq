---
id: cr-file-uploader
title: A file's page names who uploaded its current content - recorded on the attachment, shown to members only
status: in-progress
satisfies: [uc.files.view-file]
adrs: ["0083", "0034", "0079"]
invariants: [operator-blind E2EE everywhere]
---

## Goal

The file page's header names the uploader of the file's current content, for members: a member by name with a link to
their profile (a former member as such), a guest's upload as "a guest". The server records a member's upload as a user id
on the attachment, beside the guest identity it already records. A share guest sees no uploader and its listing carries
no such field. Decided by the coordinator on the owner's behalf, 2026-10-14 (inbox #415).

## Context & constraints

- [ADR 0083](../docs/decisions/0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md) Addendum
  (2026-10-14) owns the decisions; decision 9 is the display-name rule; [uc.files.view-file](../docs/features/files/use-cases/uc.files.view-file.md)
  step 0 the flow; the security model's inventory already lists the new row.
- **What the server holds today** (measured): `veyq_node_attachment.share_identity_id` (the guest identity that uploaded
  the current blob, moved by a replace in `AttachmentStore`); a member uploader exists only in `veyq_upload_session.opener_user_id`,
  deleted when the upload completes. `FileBlobRef.ownerId` is the file node, not a person.
- **The guest-leak trap:** the share's file listing reuses the member list item (`FilesResource.toRef`,
  `ShareFilesResource`); a field added to the shared shape reaches guests (inbox #190). The uploader goes on a
  member-only shape.
- **Memories' reference:** `useUploaderNames.ts` (names a `user:` key through `core/people`, a former member greyed),
  `PersonAvatar`, `personHash`. Files reaches only core, never Memories (ADR 0079).
- Contract-first: `contracts/api/files/files.openapi.yaml` changes before backend and web. The baseline migration is
  still editable (`release: none`): a column added to the core baseline changes it together with its pin line, and every
  dev database is recreated (`backend/CLAUDE.md` § Migrations).
- **Invariant:** operator-blind. The new record is metadata of the class ADR 0034 accepted (who uploaded which blob),
  never a name or content.

## Inventory (commands)

- Backend: `grep -n "share_identity_id\|opener_user_id" backend/core/src/main/resources/db/migration/V202610041200__core_baseline.sql`;
  `grep -rn "fun attach\|swapExpected\|shareIdentityId" backend/core/src/main/kotlin/me/raddatz/veyq/core/blob/`;
  `grep -rn "withBlobs\|toRef" backend/apps/files/src/main/kotlin`; erasure: `grep -rln "node_attachment" scripts backend/core/src/main/kotlin`
  (incl. `scripts/validate_scope_erasure_arity.py`).
- Contract: `grep -n "FileListItem\|FileBlobRef\|ShareFileListing" contracts/api/files/files.openapi.yaml`.
- Web: `grep -rn "useUploaderNames\|PersonAvatar\|personHash" web/src --include='*.ts' --include='*.vue' -l`;
  `web/src/features/files/components/FileDetail.vue` and its tests (`FileDetail*.test.ts`).
- Tests: `backend/apps/files/src/test/kotlin/me/raddatz/veyq/files/` (`FilesContentSliceTest`, `share/ShareFilesSliceTest`),
  `ShareGuestNamesSliceTest` (the model for "the guest response carries no name"), the operator-blind probes
  (`grep -rln "OperatorBlindProbe" backend`).

## Decisions

1. **Storage.** `veyq_node_attachment` gains a nullable member user id; exactly one of it and `share_identity_id` is set
   (a check constraint where the H2 ∩ MariaDB subset allows it, else enforced in the store and tested). Written on attach
   and on a replace (the replacer becomes the uploader), from the upload's opener. Existing rows (dev only) may hold
   neither: shown as no uploader.
2. **Contract.** The member's file listing / file read gains `uploaderKey` (`user:<id>` | `share:<identityId>`, the
   shape Memories' `owner_key` uses, `x-veyq-visibility` as structure), on a member-only schema; the share listing's
   schema does not gain it. If today both use one schema, split it so the share side cannot grow the field by accident.
3. **Web.** `FileDetail`'s header shows "Uploaded by <name>" through core's people directory (avatar + name, a link to
   the profile; "former member" greyed); a `share:` key reads "a guest"; a guest session shows nothing (its source has no
   uploader). No row meta.
4. **Tests that bind it:** a member upload records the member, a replace by another member moves it, a guest upload
   records the identity and no user; the share listing's JSON has no uploader field (read raw, not through the generated
   client - `web/CLAUDE.md` on absences); the operator-blind probes stay green.

## Phases

- [x] 1 contracts: Decision 2.
  - Done when the trust-chain guards are green.
- [x] 2 backend: Decisions 1 and 4 (backend half).
  - Done when `./gradlew check` is green; bitten (the uploader not written, the field leaking to the share listing).
- [ ] 3 web: Decision 3 and the web tests (member sees name/link/former, guest key reads "a guest", a guest session shows
  nothing - a guest-mode mount through `guardedClients`).
  - Done when `npm run check` is green and a real browser showed the uploader on a file page on the dev instance.
  - _Open (implementer, 2026-10-14):_ `npm run check` green and every web test bitten; the browser pass is NOT done - a
    worktree subagent cannot serve its tree to the browser pane. The main session runs it after the merge (the baseline
    changed: recreate the dev database first), then ticks this box.
- [ ] 4 docs: ADR 0083 Addendum and the security model's "not yet built" removed; `docs/apps/files.md`; the use case and
  its coverage; both wikis' files page; inbox #415 done.
  - Done when every `scripts/validate_*.py` is green, run on a detached worktree.

## Definition of done

- `./gradlew check` and `npm run check` are green.
- Every `scripts/validate_*.py` is green, run on a detached worktree.
- The browser run is recorded; coverage updated.

## Retrospective

_(filled at implementation)_
