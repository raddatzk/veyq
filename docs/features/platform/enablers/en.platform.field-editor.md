# Enabler: The last editor of every sealed field

- **ID:** en.platform.field-editor
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
  A **model** enabler: it states what is stored and when, not a flow.
- **Status:** **built** — decided in [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 8, built by cr-sealed-fields-record-their-last-editor (2026-10-16). The ref is core's `PrincipalKey`
  (`core/identity`), the one spelling of `user:{id}` / `share:{identityId}` that Memories' owner key and an
  attachment's uploader use too.
- **Provides:** for every sealed field group — **name, meta, note, icon** — the opaque reference of whoever last
  wrote its ciphertext: `user:{id}` for a member, `share:{identityId}` for a link's guest. The server knows it and
  checks with it; no client is served it.
- **Serves (use-cases):** [uc.depot.share-folder-files](../../depot/use-cases/uc.depot.share-folder-files.md) E5 and
  [uc.depot.drop-box](../../depot/use-cases/uc.depot.drop-box.md) E9 — a guest takes back only a placeholder its own
  identity named ([ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  decision 6). Next, the moderation hold (ADR 0093 § 4), which freezes a reported revision with its editor.
- **Depends on:** [en.memory.tree](../../memory/enablers/en.memory.tree.md) (the node model whose seals it attributes);
  [uc.sharing.share-contributor](../../sharing/use-cases/uc.sharing.share-contributor.md) (the share identity a guest
  writes as, from its contributor token).

## What is attributed

| Field group | Object and sealed column | Who writes it |
|---|---|---|
| name | node `encrypted_name` | a member (create, rename, move, restore); a `READ_WRITE` guest (create, rename, move); a `WRITE_ONLY` guest's identity folder |
| meta | node `wrapped_meta` | a member (meta, cover) |
| name | share `sealed_name` (the share root's name) and `sealed_share_name` (the private label) | a member |
| note | share `sealed_note` | a member |
| name, icon | group scope `encrypted_name`, `sealed_icon` | a member |
| name | Memories bucket `sealed_name` | a member; a primary bucket's copies its guest identity's name, so that identity is its editor |
| meta | Memories medium `wrapped_meta` | the uploader (with the upload), then a member |

**Attributed by the row itself, no column:** a share identity's `sealed_display_name` (only that identity writes it,
at creation), a member's profile name and avatar (only that member writes their own), a medium's `wrapped_exif`
(written once, with the upload: its uploader, `veyq_media.created_by` / `share_identity_id`). Key material
(`sealed_share_key`, `sealed_password`, the drop keys, a membership's name key) is no field group.

## How it is stored

Per field group a column pair on the row it attributes: `<group>_editor_user_id` (a member, no foreign key) and, where
a guest can write the field, `<group>_editor_identity_id` (a guest identity) — on `veyq_node` (`name_editor_*`,
`meta_editor_*`, the identity a foreign key onto `veyq_share_identity`; the baseline checks exactly one for the name and
exactly one while there is a meta), `veyq_bucket` (`name_editor_*`) and `veyq_media` (`meta_editor_*`, no foreign key,
as Memories' `share_identity_id`). A share's (`name_`, `note_`, `share_name_editor_user_id`) and a scope's
(`name_`, `icon_editor_user_id`) take only the member column: no guest writes them. `NodesStore`'s four writers take the
editor as a required parameter, so a new node writer states it or does not compile.

## Rules

1. **The editor is the authenticated caller of the write**, never a field of the request: a member's session
   principal, a guest's identity from its contributor token.
2. **Every write of the ciphertext records its caller** — a new text, a re-seal by a move (a placement re-seals the
   name under the new parent's key, ADR 0080 decision 2) and a restore's re-seal alike. The server cannot tell a
   re-seal from a new text, so "last editor" means the last writer of the ciphertext (owner 2026-10-16).
3. **Clearing a field clears its editor:** a removed meta, a cleared icon, a deleted share's tombstone. A field
   without a value has no editor; a node's name always has one.
4. **The reference outlives its writer as the uploader's does.** A member's id is no foreign key: a member who left
   stays named. A guest's identity is kept while it is the last editor of anything, as while it names an upload.
5. **Only the last editor**, no history (ADR 0093 § Rejected alternatives): the hold freezes what was reported.
6. **Served to no client** (owner 2026-10-16). Whether members see "last changed by" is a product question of its own
   (inbox #383's idea of marking a contributor's file).

## What the server learns

Per field group, which member or which opaque guest identity wrote it last — the class
[ADR 0034](../../../decisions/0034-media-uploader-buckets.md) and
[ADR 0083](../../../decisions/0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md) decision 8
already disclose for uploads, for the same reason (a sealed attribution could name anyone). Never a name, never
what was written.

## Acceptance criteria

An enabler carries no coverage file (`validate_coverage.py` reads use-cases only), so each criterion names the slice
tests that verify it here. Every one runs in CI's backend job.

- **field-editor-per-writer** — each writer of the table above stores its caller as the field's editor, a member as
  `user:{id}` and a guest as `share:{identityId}`: `NodeFieldEditorCoreSliceTest` (a node's create, rename, move,
  meta and cover, member and guest), `DropBoxSliceTest.anIdentityFolderAndItsFilesNameTheirGuestAsTheNamesEditorUntilAMemberMovesItOut`
  (the identity folder), `ShareFieldEditorCoreSliceTest` (a share's create, edit, re-seal of the name, re-issue),
  `ScopesSliceTest.aGroupsNameAndIconRecordTheMemberWhoWroteThemAndAClearedIconHasNone` (a group's name and icon),
  `MediaFieldEditorSliceTest` (a medium's meta, a primary's copied name, a sub-bucket's create and rename).
- **field-editor-from-the-caller** — no request carries an editor; a guest's write records its token's identity:
  `NodeFieldEditorCoreSliceTest.theEditorIsTheTokensIdentityNeverAnotherGuestOfTheLink`, and every test above, which
  sends no editor.
- **field-editor-rewrite** — a move, a restore and a re-seal record their caller:
  `NodeFieldEditorCoreSliceTest.aMembersRenameAndMoveTakeTheNameOverFromTheGuest`,
  `NodeFieldEditorCoreSliceTest.aGuestsRenameAndMoveTakeTheNameOverFromTheMember`,
  `NodeFieldEditorCoreSliceTest.aRestoreThatReSealsTheNameNamesItsCallerOneThatDoesNotKeepsTheEditor`,
  `ShareFieldEditorCoreSliceTest.aMembersReSealOfTheNameTakesTheNameOver`.
- **field-editor-cleared-with-the-field** — a removed meta, a cleared icon, a tombstoned share hold no editor:
  `MediaFieldEditorSliceTest.aMediumsMetaIsItsUploadersThenTheEditingMembersAndAClearedOneHasNone`,
  `ScopesSliceTest.aGroupsNameAndIconRecordTheMemberWhoWroteThemAndAClearedIconHasNone`,
  `ShareFieldEditorCoreSliceTest.aReissueNamesItsCallerOnTheNewLinkAndTheTombstonesHoldNoEditor`,
  `ShareFieldEditorCoreSliceTest.anEditNamesItsCallerForEachFieldItSetsAndClearsTheEditorOfOneItClears`.
- **field-editor-keeps-the-identity** — a guest identity that is the last editor of a name is not dropped, and is
  dropped once nothing names it: `NodeFieldEditorCoreSliceTest.anIdentityThatLastNamedANodeOutlivesItsLinkUntilTheNodeIsGone`.

The take-back it serves is the two Depot use-cases' criteria (`a-guest-takes-back-only-its-own-placeholder`,
`a-refused-file-leaves-nothing-behind`).

_No user-impact section: an enabler's value reaches the user through the use-cases it serves — here the narrowed
take-back in the two Depot use-cases._

## Related

- [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 8 (the decision), § 4 (the hold that copies it), § 10 (the guest-write record, whose triggers are a guest's
  attributed writes).
- [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) decision 6
  (the take-back it narrows).
