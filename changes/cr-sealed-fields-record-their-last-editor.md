---
id: cr-sealed-fields-record-their-last-editor
title: Every sealed field records its last editor, and a guest takes back only a placeholder it named
status: planned
satisfies: ["uc.depot.share-folder-files", "uc.depot.drop-box"]
adrs: ["0093", "0080", "0034", "0083", "0092", "0094"]
invariants:
  - "Operator-blind: the server records only an opaque `user:{id}` / `share:{identityId}` per field group — the class ADR 0034 and ADR 0083 already disclose; no plaintext, no new key, no client-supplied editor"
  - "API-first: no wire shape changes in this CR (decision D5); if the owner exposes the editor, a contracts phase comes first and the DTO is generated"
  - "ADR 0080 decision 6: a guest's take-back erases only where nobody could lose anything"
---

## Goal

Build [ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ 8: for every sealed field group — **name, meta, note, icon** — the server stores who last wrote its ciphertext, as
an opaque `user:{id}` or `share:{identityId}`, taken from the authenticated caller, never from the request. Then use
it where it already has a reader: [ADR 0080](../docs/decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
decision 6's take-back (`abandonShareNode`) erases a placeholder only if its name was last written by the calling
guest's identity, so a guest no longer erases a member's empty file or folder. The hold that copies these columns
(§ 4), the per-field keys (§ 9) and the guest-write record (§ 10) are **not** in this CR (§ Not in this cut).

Implementation waits for the owner's go on this CR, and on the open decisions below.

## Context & constraints

- **Refined state:** ADR 0093 § 8 (the decision), ADR 0080 decision 6 and its "Decided with the owner" paragraph
  (the rule this tightens: "Until a node stores who last wrote its name, that rule stands"), ADR 0034 and ADR 0083
  decision 8 (the attribution class already disclosed). Use-cases: `uc.depot.share-folder-files` E5 and its quality
  note on deletes, `uc.depot.drop-box` E9. Inbox #462 (the moderation line, V1), #383 (the idea of marking a
  contributor's file as theirs, parked on "nodes store no authorship").
- **Measured 2026-10-16 on this worktree** (branch `claude/objective-davinci-5a9755`, clean, at main's `b2842097`;
  a read-only sweep plus re-reads of the load-bearing lines):
  - **One choke point for node seals.** `NodesStore` (`backend/core/.../core/content/NodesStore.kt`) is the only
    writer of `veyq_node.encrypted_name` / `wrapped_meta`, in four functions: `create`, `createIdentityFolder`,
    `applyUpdate` (placement writes the name, a meta write the meta) and `restoreSubtree` (an A1 retry re-seals the
    name). None takes a principal today. Member callers know it as `identity.principal.name` (`ContentResource`,
    `NodeCoverStore`, `NodesHandler.restore`); guest callers as `ShareWriteGate.Admitted.identityId` from the
    contributor token (`ShareNodesHandler.create` / `update`, `IdentityFolders.ensure`). Guests write no node meta.
  - **The other sealed field groups** (core baseline `V202610041200__core_baseline.sql`, Memories baseline):
    `veyq_share.sealed_name` (the share root's name, `setShareSealedName`), `sealed_note`, `sealed_share_name`
    (written by `SharingStore.create` / `reissue` / `updateShare` / `setSealedName`, members only);
    `veyq_scope.encrypted_name`, `sealed_icon` (`ScopesStore.createGroup` / `renameGroup` / `setIcon` / `clearIcon`);
    `veyq_bucket.sealed_name` (`MediaStore.ensurePrimaryBucket` copies the guest identity's sealed name,
    `createSubBucket`, `renameBucket`); `veyq_media.wrapped_meta` (`MediaStore.attach`, then a member's `setMeta`).
    **Self-attributed, no column needed:** `veyq_share_identity.sealed_display_name` (only its identity writes it,
    at creation) and `veyq_tenant_profile.sealed_name` / `sealed_avatar` (only that member writes their own).
    Key material (`sealed_share_key`, `sealed_password`, `sealed_drop_*`, `sealed_name_key`) is no field group.
  - **What exists:** `veyq_node_attachment.uploader_user_id` / `share_identity_id` with `ck_attachment_uploader`
    (exactly one; the user id deliberately no foreign key, "a member who left stays named"), the pattern to mirror;
    `veyq_media.created_by` / `share_identity_id` / `owner_key`. Three spellings of one ref: Memories'
    `OwnerKey` (`backend/apps/memories/.../media/OwnerKey.kt`, `format()`/`parse()`), core's inline
    `NodeAttachmentEntity.uploaderKey`, Depot's `SHARE_KEY` const (`FilesStore.kt`). **`veyq_node.share_identity_id`
    is not an editor** — it marks an identity folder's owner (ADR 0080 decision 11, `ux_node_parent_identity`) and is
    cleared on a move; do not reuse it.
  - **`abandonShareNode` today** (`ShareNodesHandler.abandon` + `isPlaceholder`): reach, boundary, no identity
    folder for `READ_WRITE`, no child, no upload session opened by someone else, then `GuestPlaceholders.isEmpty`
    (core `FolderGuestPlaceholders`, Depot `DepotGuestPlaceholders`). No authorship check. **Measured finding:** the
    erase (`NodeSubtreePurge.erase`) tombstones every link on the erased node (`sharing.tombstoneAllOn`), and nothing
    in `isPlaceholder` looks for one — so today a `READ_WRITE` guest can end a member's other link that is rooted on
    an empty folder in its reach (decision D3).
  - **Web decides nothing.** `abandonShareNodeAsGuest` (`web/src/core/sharing/guestTree.ts`) is called only for
    nodes the guest's own job created (`shareUpload.ts`, `uploadFolder.ts` `takeBackEmptyFolders`) and **tolerates
    `not_a_placeholder` silently**. So an editor recorded wrongly on the guest path would not fail any screen: the
    refused upload's empty file would just stay. The positive control must therefore run the real guest path
    (create through `createShareNode`, then abandon), never a seeded node.
  - **Schema policy:** `release: none` — a column change is edited into the baseline in place together with its line
    in `scripts/applied_migrations.sha256` (as `7efcd29c` and `120f5a58` did); every dev database is recreated, so
    there are **no existing rows** to backfill. A new table would be a new V file above `202610161210`.
- **The guest-identity foreign key** has a cost: `dropDeadIdentities` drops an identity nothing references, read
  through `GuestIdentityUsage`. An editor column that references `veyq_share_identity` must be counted there, or the
  drop fails on the key (and, worse, if it were `ON DELETE SET NULL`, a moderator would lose who wrote a name the
  moment the identity's last upload went). Counting it means an identity lives while it is the last editor of
  anything — what ADR 0093 needs and what `uc.depot.share-folder-files` A8 already says for files.
- **Legal: a ship gate, not a refine blocker.** ADR 0093 § Open legal questions must be answered by someone qualified
  before the moderation line ships. This CR adds no personal data beyond the opaque class ADR 0034/0083 disclose and
  makes an erasure stricter, so it may be built and merged before that review; it does not make V1 shippable.

### Open decisions (owner, before go)

- **D1 — Which objects in this cut.** *Recommended: every non-self-attributed field group* listed above (node
  name/meta, share name/note/share name, scope name/icon, bucket name, media meta), as phases 1–3. ADR 0093 § 8 is
  one property ("every sealed field"), the hold CR then only copies columns, and each writer gets its slice test.
  The cost: outside the node name, the columns have no reader until the hold exists. *Alternative:* node name/meta
  only now (the one with a reader), the rest with the hold CR.
- **D2 — A guest's move writes the name.** A placement re-seals the name under the new parent's key (ADR 0080
  decision 2), and the server cannot tell a re-seal from a new text, so a `READ_WRITE` guest who **moves** a member's
  empty folder becomes its name's last editor — and could then take it back. *Recommended: accept it*, as ADR 0093 § 8
  reads ("its name was last written by that guest's identity"): what it can erase is a name with nothing behind it,
  which the same guest could already rename away (ADR 0080's own residual), and D3 removes the one case where someone
  loses more. State the residual in ADR 0080. *Alternative:* a creator column on `veyq_node`, written once, and the
  take-back asks "created by this identity" — exact for "the node its upload created", but a second attribution
  concept ADR 0093 does not have, so an ADR amendment.
- **D3 — No take-back of a link's root.** *Recommended: in this CR* — `isPlaceholder` also refuses a node that
  carries a live link (`not_a_placeholder`). A guest's own placeholder never carries one (only members create
  links), so nothing the feature needs is lost, and "erased only where nobody could lose anything" holds again.
- **D4 — One core ref type.** *Recommended:* a core sealed type (`User(id)` / `Share(identityId)`, `format()` /
  `parse()`, the `user:{id}` / `share:{identityId}` spelling of ADR 0034) that the editor columns use, and that
  `OwnerKey`, `NodeAttachmentEntity.uploaderKey` and Depot's `SHARE_KEY` move onto — four spellings of one class
  otherwise. Memories may import it (an application uses core); core never imports `OwnerKey`.
- **D5 — The editor is not served.** *Recommended: no wire field in this CR.* Whether members see "last changed by"
  is the product question ADR 0093 leaves open (and #383's idea); the server's own check needs no client. So no
  contracts phase — only descriptions change, in the commit that changes the behaviour.
- **D6 — Where the property's criteria live.** "Each write records its writer" is a property with many triggers, so
  by [`changes/README.md`](README.md) § Coverage it wants a home of its own rather than rows under the Depot
  use-cases (which keep only the take-back). *Recommended:* the owner names it before go — an enabler under
  `docs/features/platform/` (e.g. `en.platform.field-editor`) with a `planned` coverage file, written in the refine,
  not invented in a phase.
- **D7 — Not decided here: a guest deleting its own content.** ADR 0080 decision 6 says a guest's delete of a node
  that holds something "comes with stored authorship". This CR stores who wrote a *name*, not who owns a subtree's
  content; whether a guest may then delete "its own" file or folder (by which editor: name, attachment, all?) is a
  refine of its own. *Recommended:* an inbox row, out of this CR.

### Not in this cut

- **§ 10, the guest-write record** — a separate CR. It depends on [ADR 0094](../docs/decisions/0094-a-clients-address-is-the-hop-the-network-vouches-for.md)'s
  resolver (accepted, not built: no `X-Forwarded-For` handling exists beyond Quarkus's own) and on
  [ADR 0092](../docs/decisions/0092-logs-are-operational-and-name-no-person.md) (status *Proposed*, its § 2 fixes
  the retention setting); it stores addresses, so it changes `CLAUDE.md`'s invariant on what the server keeps, the
  security model and the terms — the joint 0092/0093 docs CR inbox #462 names. Its triggers are exactly this CR's
  guest editor writes plus the upload commit and identity creation, so it rides the call sites this CR threads the
  editor through, and comes after it.
- **§ 4 the hold, § 9 per-field keys, the report share, admin-web's queue** — the moderation CRs. § 9 re-seals the
  same columns under `K_field`; it is independent of the editor columns and may come in either order.

### Inventory — the locators

| What | Command |
|---|---|
| Sealed-column writers (main code) | `grep -rnE '(encryptedName\|wrappedMeta\|sealedIcon\|sealedName\|sealedNote\|sealedShareName\|sealedDisplayName\|sealedAvatar) *=[^=]' backend --include='*.kt' \| grep -v /test/` |
| The four node writers and their callers | `grep -rnE 'fun (create\|createIdentityFolder\|applyUpdate\|restoreSubtree)\(' backend/core/src/main/kotlin/me/raddatz/veyq/core/content/NodesStore.kt`, then `grep -rnE '\.(create\|createIdentityFolder\|applyUpdate\|restoreSubtree)\(' backend --include='*.kt'` |
| Ref spellings to unify (D4) | `grep -rnE '"user:\|"share:\|OwnerKey\|uploaderKey\|SHARE_KEY' backend --include='*.kt'` |
| Identity-usage counters | `grep -rn 'GuestIdentityUsage' backend --include='*.kt'` |
| The take-back and its tests | `grep -rn 'abandon' backend --include='*.kt' -l`; `grep -rn 'abandonShareNode' web/src` |
| Text that says "no authorship" (code, contracts, docs, web) | `grep -rnE 'authorship\|any guest in reach\|who created the node is not recorded' contracts docs web/src backend site/src/content/docs` |
| Fixture blast radius | `grep -rln 'seedNode(' backend --include='*.kt'` (≈100 files: `TestFixtures.seedNode` gains the editor as a **defaulted** parameter, a fixture member; only the take-back tests pass a guest) |
| Schema guards a column meets | `validate_migration_versions.py` (the pin line), `validate_scope_erasure_arity.py`, `validate_node_subtree_erasure.py`, `EntitySchemaValidationTest` (`:core`, `:apps:memories`) |
| Published pages | `git grep -ln "security-model.md\|0034-media-uploader-buckets" -- site/src/content/docs` — at least `site/src/content/docs/security/what-the-server-sees.md` (the attribution bullet) |

## Phases

- [ ] **1 backend (core) — node editors.** The core ref type (D4). On `veyq_node`, per field group a pair
  `<group>_editor_user_id` (no FK, as the attachment's) and `<group>_editor_identity_id` (FK to
  `veyq_share_identity`), at most one set; the name pair exactly one (a node always has a name), the meta pair set
  iff `wrapped_meta` is. Edited into the core baseline with its pin line. `NodesStore`'s four writers take the editor
  as a required parameter (no default: every caller states it), and every caller passes it from the authenticated
  principal. `GuestIdentityUsage` counts the identity columns. The `OwnerKey` / `uploaderKey` / `SHARE_KEY` move
  (D4) is in this phase if decided. · Done when: a slice test per writer path — member create, rename, move, meta
  (cover), restore A1; guest create, rename, move, identity folder — reads the stored editor and asserts it, each with
  a control of the other principal kind; `dropDeadIdentities` keeps an identity that is the last editor of a name and
  drops it once that node is gone (positive and negative); `./gradlew check` green.
- [ ] **2 backend (core) — the take-back asks the editor.** `isPlaceholder` adds: the name's last editor is the
  calling identity, and (D3) no live link on the node. The descriptions in `contracts/api/sharing.openapi.yaml`
  (`updateShareNode`, `abandonShareNode`, its `409`), `contracts/api/depot/files.openapi.yaml` (the tag text, the
  `uploaderKey` note) and `contracts/api/memories/share-media.openapi.yaml` say the new rule; the KDocs
  (`ShareNodesHandler`, `GuestPlaceholders`, `FolderGuestPlaceholders`) and the test comments that cite "no
  authorship" follow. · Done when: the flipped controls in `ShareNodeAbandonCoreSliceTest` and `ShareFilesSliceTest`
  now assert a member's empty folder / file is `409 not_a_placeholder`, beside the positive control — the guest's own
  placeholder made **through `createShareNode`** is erased; new cases, each failing exactly one check: a member renamed
  the guest's placeholder → kept; another identity's placeholder → kept; (D2 as decided) a guest moved a member's
  empty folder → the decided outcome; an empty folder carrying a link → kept, link live. `DropBoxSliceTest` and
  `ShareNodeAbandonRaceSliceTest` green unchanged. Bitten: phase 1's editor write on `ShareNodesHandler.create`
  injected to record a member → the positive control goes red. `./gradlew check` and `npm run generate:api` +
  `npm run check` (web, admin-web: descriptions only, expected green) green.
- [ ] **3 backend — the other field groups** (D1 as decided). Core: `veyq_share` (name, note, share name; the
  tombstone nulls the editors with the seals), `veyq_scope` (name, icon; `clearIcon` nulls the icon editor). Memories:
  `veyq_bucket` name (the primary bucket's copied name names the identity whose name it copies), `veyq_media` meta
  (`attach` the uploader, `setMeta` the member). Baselines edited with their pins. · Done when: a slice test per writer
  reads the editor (create, update, re-seal, clear, tombstone/reissue), `./gradlew check` green.
- [ ] **4 docs + coverage.** ADR 0080 decision 6 and its "Decided with the owner" paragraph: the rule as built, D2's
  residual, D3; ADR 0093 status line: § 8 built (and which objects, per D1). `docs/apps/depot.md` (§ Guests' role
  table, § abandon), `uc.depot.share-folder-files` (E5, the deletes quality note), `uc.depot.drop-box` (E9),
  `uc.depot.share-file` (the "no authorship" clause), `docs/crosscutting/security-model.md` (what the server sees: the
  last editor per field group), `site/src/content/docs/security/what-the-server-sees.md` (same bullet); the web
  comments that cite "no authorship" (`web/src/core/access.ts`, `features/depot/source.ts`, `rights.ts`,
  `components/FileDetail.vue`) say what is now true. Coverage: the take-back criteria under the two Depot use-cases
  and the property's under D6's home. Inbox #462 row: § 8 built; a row for D7. · Done when:
  `python3 scripts/validate_coverage.py` and the `trust-chain` job's guards green, `npm run check` in `site/` and
  `web/` green; the locator for "no authorship" returns only text that is still true.

No web phase: nothing the client sends or shows changes (D5). **Browser pass:** the take-back is invisible when it
fails (web tolerates `not_a_placeholder`), so phase 2 re-walks `uc.depot.share-folder-files` E5 as it was walked on
2026-10-16 — a `READ_WRITE` folder link at a small limit, a refused folder upload — and confirms the guest's listing,
the member's tree and the database hold none of the made nodes; then one member-made empty folder in the same
subtree survives a hand-crafted `abandonShareNode`.

## Definition of done

Every sealed field group of D1's scope stores its last editor from the authenticated caller, with a slice test per
writer; `abandonShareNode` erases only the calling identity's own named placeholder and never a link's root, pinned by
positive and negative controls on the real guest path and bitten as phase 2 says; `./gradlew check` green; contracts
descriptions, ADR 0080, the Depot use-cases, `docs/apps/depot.md`, the security model and the Security page
`what-the-server-sees` say what is built; `docs/coverage/` updated for both `satisfies:` use-cases and D6's home with
`python3 scripts/validate_coverage.py` green; the `trust-chain` job green; `npm run check` green in `site/` and
`web/`. No permanent guard is added: the property is pinned per writer by slice tests, and `NodesStore`'s required
editor parameter makes a new node writer that forgets it fail to compile — a new writer elsewhere would bypass
`NodesStore`, which `TreeRevisionBumpsTest` already reads the sources for.

## Retrospective

(filled at implementation)
