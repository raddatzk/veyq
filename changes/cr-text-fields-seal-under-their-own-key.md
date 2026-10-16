---
id: cr-text-fields-seal-under-their-own-key
title: A text or icon field seals under a key of its own — K_field per write, so a report can hand over one field
status: planned
satisfies: ["en.memory.tree", "en.memory.node-meta", "en.memory.buckets", "en.spaces.member-identity", "uc.sharing.share-note", "uc.spaces.set-profile-picture"]
adrs: ["0093", "0072", "0017", "0080", "0084", "0036", "0012"]
invariants:
  - "Operator-blind E2EE: K_field is derived on the client and never sent; the server stores only the field envelope (salt, nonce, ciphertext), which opens nothing without the key it was sealed under"
  - "ADR 0072 Decision 6, the structure is the guard: a field purpose can be sealed only through the field seal; the plain seal refuses it by type and at runtime"
  - "One copy of key-material logic: the derivation lives in @veyq/crypto and nowhere else (CLAUDE.md, packages/crypto)"
  - "Constant-length sealed avatars stay constant-length: the new length is exact, the same for every picture"
---

## Goal

[ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 9,
built. Today a text field is sealed straight under the key of its object or its parent: a node's name under the
parent's `K_node`, a share's note under `K_share`, a member's name under `K_tenant`. A report that must let the
moderator read **one** field would have to hand over that key, and with it the subtree, the siblings or the whole
tenant's names (ADR 0093 § Rejected alternatives: "Handing over `K_share` or a parent key for a text field"). After this
CR every listed field seals under `K_field = HKDF(K_parent, salt, "veyq/v1/field/<purpose>")`, with a fresh salt per
write stored in the field's own envelope. A client that can read a field can compute that field's `K_field`, and that
key opens that one revision of that one field and nothing else. The report itself (§§ 1–7, 10, 11) is not built here.
This CR gives a later report CR the key it hands over, plus the moderator-side open, pinned by vectors.

## Context & constraints

- **The decision:** ADR 0093 § 9 (the derivation, the per-write salt, the purpose list, "a stored-format change with a
  reset, not a suite v2"). The stored format's rules come from [ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md):
  Decision 1 (the AAD binds purpose and object), Decision 2 (where the key is unique, the key is the binding),
  Decision 6 (the typed seal is the only entry point; one vector per purpose, compared in both directions), and
  § Consequences ("every development instance is reset"). The labels follow
  [ADR 0017](../docs/decisions/0017-crypto-primitives-and-post-quantum-strategy.md) (`veyq/v1/…`, one vector per purpose).
- **Settled by § 9, not reopened here** (each with the line that settles it):
  - Derivation, not a random key plus a wrap: "`K_field = HKDF(K_parent, salt, "veyq/v1/field/<purpose>")`" (ADR 0093 § 9).
    No wrap row exists, so nothing new is stored beyond the salt.
  - One key per purpose (= field), not per field group. § 9 lists `share-name` and `share-memory-name` apart, although
    both are the field-editor's "name" group ([en.platform.field-editor](../docs/features/platform/enablers/en.platform.field-editor.md)
    § What is attributed). The editor group is about attribution; the key is about what a report opens.
  - One key per write: "the salt drawn fresh **per write**". So "rotation on re-share" needs no mechanism.
    - A re-issue mints a new `K_share` and re-seals the note, name and label (`remintShare`, `web/src/core/sharing/manageShares.ts`), each with a fresh salt.
    - A move re-seals the name under the destination's key (`sendPlacement`, `web/src/core/node/tree.ts`), again with a fresh salt.
    - A `K_field` handed to a moderator opens only the held revision. It reveals nothing about `K_parent` (HKDF is one-way) and nothing about the next revision.
  - Icons count: `member-avatar` is on § 9's list. `space-icon` and `group-name` are kept as they are. See Open 4: they
    are not quite "this way" today.
- **What "K_parent" means per field.** It is the key the field is sealed under today, which for `node-meta` is the
  node's **own** key, not its parent's. Each is measured in the sealing wrapper (locator L1):

  | Purpose | Column (wire) | K_parent today | Wrapper |
  |---|---|---|---|
  | `node-name` | `veyq_node.encrypted_name` (`encryptedName`) | the entry key: parent `K_node`; `K_SC` at the node's epoch for a scope root; the guest's `K_up` for an identity folder | `web/src/core/node/tree.ts` `sealName`/`openName` |
  | `node-meta` | `veyq_node.wrapped_meta` (`wrappedMeta`) | the node's own `K_node` | `web/src/core/node/nodeMeta.ts` |
  | `bucket-name` | `veyq_bucket.sealed_name` | the Memory's/chapter's `K_node`, or `K_up` (`mediaKey`) | `web/src/features/memory/mediaBuckets.ts` |
  | `share-identity-name` | `veyq_share_identity.sealed_display_name` | the share root's `K_node`, or `K_up` | `web/src/core/sharing/guestIdentity.ts`, plus two direct calls (`features/memory/share.ts`, `features/depot/dropBox.ts`) |
  | `share-memory-name`, `share-note`, `share-name` | `veyq_share.sealed_name`, `sealed_note`, `sealed_share_name` | `K_share` | `manageShares.ts`, `resolvePublicShare.ts` |
  | `member-name`, `member-avatar` | `veyq_tenant_profile.sealed_name`, `sealed_avatar` | `K_tenant` (the current write key; no epoch is bound) | `features/account/profile/memberName.ts`, `memberAvatar.ts`, plus `web/scripts/bootstrap-user.mts` |
  | `media-meta`, `media-exif` (only if Open 3 says yes) | `veyq_media.wrapped_meta`, `wrapped_exif` | `mediaKey`: `K_node` or `K_up` | `features/memory/media.ts` |

- **A cross-column copy the server makes.** `primarySealedNameFor` (`backend/apps/memories/.../media/MediaStore.kt`)
  copies a guest identity's `sealed_display_name` verbatim into its primary bucket's `sealed_name`. The client opens it
  as `share-identity-name` (`openIdentityName` from `mediaBuckets.ts`). With the salt inside the envelope (Open 1), the
  copy carries its salt and nothing changes on the server. With a sibling salt column, this copy is one more writer
  that must move it.
- **admin-web** reads and writes none of these fields (locator L4). It needs no phase.
- **Export and import** carry plaintext (`web/src/core/export/exportSubtree.ts`, `features/memory/exportImport.ts`) and
  re-seal through the create paths, so the manifest (`contracts/export/veyq-subtree.schema.json`) does not change.
- **Migration: none, a reset.** `release: none` (`scripts/applied_migrations.sha256`, `grep -n '^release:'`) and
  `backend/CLAUDE.md` § Migrations: before the first release no user data exists to carry.
  - Every existing ciphertext of these purposes stops opening, exactly as ADR 0072's binding did. Every dev instance is recreated (`docker compose -f infra/docker-compose.yml down -v`, then the reseed).
  - **Neither a re-seal on first write nor a one-time client re-seal is built.** Both would be a dual reader of the old format that is dead code the day V1 ships, and a reader that still opens the old format is the very key-sharing the CR removes.
  - Under Open 1's recommendation no migration file and no baseline line changes either. The columns stay `TEXT`.
- **The upgrade proof.** `ci.yml`'s `upgrade-proof` is `if: false` until a release. A local
  `python3 scripts/run_web_e2e.py --upgrade` across the web phase's commit is **expected red** at the reader
  (`features/instanceBackup/upgrade.b-read.e2e.test.ts` opens names and meta the predecessor sealed). That red is the
  format break `release: none` permits, not a defect. Do not run it as this CR's gate. The phase that runs it says so
  in its commit message.
- **Size.**
  - The envelope grows by the salt: today it is `1 + 12 + pt + 16`; with a 16-byte salt it is `1 + 16 + 12 + pt + 16`.
  - **Every** cap is re-checked against its writer's plaintext bound (locator L6).
  - The tight caps are `maxLength: 512` (bucket name, share label, guest display name, media meta) and 4096 (node name, share root name).
  - `sealedAvatar` is an **exact** length today: `21884 = 4·⌈(1+12+16384+16)/3⌉`. It becomes `4·⌈(1+S+12+16384+16)/3⌉` (21908 for S = 16, 21928 for S = 32).
  - That number is shared today with the tenant icon (ADR 0084 § 2, "the very number of the tenant icon's"). It stops being shared if `space-icon` stays as it is (Open 4).
  - `scripts/validate_guard_gaps.py` anchors on `maxLength: 21884` in `scopes.openapi.yaml` (the **icon**), so that anchor must stay put. Only `account` and `tenants` move.
- **Mutation gate.** `packages/crypto/stryker.mutate.json` covers `seal.ts`, `primitives.ts` and `v1.ts`.
  `web/stryker.mutate.json` covers `src/core/node/nodeMeta.ts`. A commit touching either records
  `npm run mutation -- --force`'s exit code in its message (`web/CLAUDE.md`, the Crypto bullet).
- **Parallel work.** No live CR touches `packages/crypto`, `contracts/crypto` or these wrappers
  (`grep -lE 'seal\.ts|contracts/crypto|0093' changes/cr-*.md`). `cr-stack-e2e` edits `e2e/` and `run_stack_e2e.py`, not
  `web/src/**/*.e2e.test.ts`. If that changes before the go, re-run the grep.

### Inventory — the locators

Run from the repo root. Each is a list to read, not a number. Implementers spot-check the row the design leans on
hardest: L1 (every sealing site goes through a wrapper that can be switched in one place).

The pipes below belong to the commands themselves (ERE alternation, shell pipes). They are not table separators.

- **L1:** every seal and open of a field purpose, keyed on its literal:
  `grep -rnE "purpose: '(node-name|node-meta|bucket-name|share-note|share-name|share-memory-name|share-identity-name|member-name|member-avatar|media-meta|media-exif)'" web/src web/scripts admin-web/src packages/crypto/src`
- **L1b:** the same, keyed on the call. This is the second angle. It finds the direct `v1.seal(identityNameContext(…))`
  in `share.ts` and `dropBox.ts`, which L1 reaches only through a context helper. Read every hit whose context is an
  L1 purpose:
  `grep -rnE "v1\.(seal|open)\(" web/src web/scripts --include='*.ts' --include='*.vue' --include='*.mts' | grep -v '\.test\.ts'`
- **L2:** the tests that build or open these ciphertexts. Run L1 with `-l --include='*.test.ts'`, plus
  `grep -rlE "\b(sealName|openName|sealNodeMeta|unsealNodeMeta|sealNote|sealShareName|sealRootName|createBucket|openBucketName|createGuestIdentity|openIdentityName|sealAvatar|openAvatar)\(" web/src --include='*.test.ts'`
- **L3:** the vectors and their readers:
  `grep -nE '"(node-name|node-meta|bucket-name|share-[a-z-]+|member-[a-z]+|media-meta|media-exif)"|directory_entry' contracts/crypto/vectors/v1.json`
  and `grep -rlE "v1\.json|vectors\.seal|directory_entry" packages web/src backend --include='*.ts' --include='*.kt'`.
  Measured at refine: no Kotlin test reads `seal` or `directory_entry`.
- **L4:** admin-web reads none of them, so this is expected to return nothing:
  `grep -rnE "v1\.(seal|open)\b|sealedName|encryptedName|sealedAvatar|wrappedMeta|sealedNote|sealedShareName|sealedDisplayName" admin-web/src`
- **L5:** contract prose that names a field's key or envelope:
  `git grep -nE "under .?K_(node|share|tenant|parent)|0x02 \|\| nonce|0x02 ‖" -- contracts/api`
- **L6:** every cap on a field carrier:
  `python3 -c "import yaml,glob;[print(f,s,p,v.get('maxLength')) for f in glob.glob('contracts/api/**/*.yaml',recursive=True) for s,d in (yaml.safe_load(open(f)).get('components',{}).get('schemas',{}) or {}).items() for p,v in (d.get('properties') or {}).items() if p in ('encryptedName','wrappedMeta','wrappedExif','sealedName','sealedNote','sealedShareName','sealedDisplayName','sealedAvatar')]"`.
  `sealedName` and `encryptedName` also name group and member carriers, so read the schema column.
  The exact-length pins: `git grep -n "21884\|16413" -- . ':!*.lock'`.
  Each writer's plaintext bound is read in the L1 wrapper that seals it.
- **L7:** server-side copies of a field ciphertext between columns. The expected hit is the primary bucket's name:
  `grep -rnE "sealedDisplayName|sealed_display_name" backend --include='*.kt' | grep /main/`
- **L8:** durable prose that states a field's key, across docs, ADRs, the site and coverage:
  `git grep -nE "under .?K_(node|share|tenant|parent|up)\b|under (its|the) parent'?s? key|sealed under|AAD share-note|21884" -- docs site/src/content/docs web/CLAUDE.md contracts/crypto`
- **L9:** guards that anchor on anything this CR moves. The expected hit is only the icon cap anchor in
  `validate_guard_gaps.py`:
  `grep -nE "21884|SEAL_PURPOSES|'node-name'|v1\.json|encrypted_name|sealed_note" scripts/*.py`

## Decisions (refine)

1. **A field seal is its own typed entry point.** `packages/crypto/src/seal.ts` gains `FIELD_PURPOSES`, a third table
   beside `SEAL_PURPOSES` and `SEAL_TO_PURPOSES`, with the same binding fields per purpose as today. The field
   purposes leave `SEAL_PURPOSES`, so `seal({ purpose: 'node-name' }, …)` is a type error and a runtime
   `SealContextInvalid` ("unknown purpose"). The exports:
   - `sealField(context, parentKey, plaintext)` draws the salt and the nonce, derives `K_field` and returns the envelope.
   - `openField(context, parentKey, envelope)` is the member and guest read.
   - `fieldKeyOf(context, parentKey, envelope)` returns `K_field`. This is what a reporter's client will hand over.
   - `openFieldWith(context, fieldKey, envelope)` is the moderator's read.
   - The HKDF `info` is exactly `utf8("veyq/v1/field/" + purpose)`, as § 9 says. It binds no object: the AAD does that (Decision 1 of ADR 0072).
2. **The envelope** (under Open 1's recommendation): `0x03 ‖ salt(S) ‖ nonce(12) ‖ aead`, AAD = `0x03 ‖ 'v1' ‖
   context_bytes`. A new type byte, so a field envelope can never open as a plain seal (`0x02`) or a wrap (`0x01`), and
   `primitives.ts`' existing type-in-AAD defence (`wrapAad`) covers it. The salt is not in the AAD: a changed salt
   derives another key and the tag fails.
3. **Vectors.** `contracts/crypto/vectors/v1.json` gains `field_seal`. It has one entry per `FIELD_PURPOSES` key:
   `context`, `context_bytes`, `parent_key`, `salt`, `info`, `field_key`, `nonce`, `plaintext`, `envelope`.
   - The field purposes' entries leave `seal.sym`. The conformance test compares `FIELD_PURPOSES` with `field_seal` in both directions, the way `SEAL_PURPOSES` is compared today.
   - It also asserts that no purpose sits in two tables.
   - `directory_entry`'s sealed name and its note ("a node's directory entry lives under its PARENT's content key") are re-derived and reworded.
   - `group_name_key` and `space_icon_key` stay, unless Open 4 says otherwise.
4. **No server change beyond the generated caps.** The server stores the envelope as the opaque base64 it already is.
   Nothing on the server derives, checks or parses a salt, and no Kotlin test reads `seal` vectors (L3). The backend
   phase is the regenerated `@Size` and the tests that pin an exact length.
5. **`satisfies:`** names the existing docs whose stated keying this changes, with acceptance criteria to re-prove:
   - the node, meta and bucket enablers and the member-identity enabler;
   - share-note, whose criterion `curator-sets-note-at-creation` says "seals it under K_share (AAD share-note)";
   - set-profile-picture, whose sealed length changes.
   - The property itself ("one field, one revision, one key") gets a model enabler in the docs phase, `en.platform.field-key`, the sibling of `en.platform.field-editor` (§ 8's model). It does not exist yet, so it is not cited above.

## Open for the owner

1. **Where the salt lives.**
   - **(a) Inside the envelope** (Decision 2): no contract field, no column, and no Kotlin change. The server's
     primary-bucket copy (L7) and a future hold (§ 4 copies "sealed fields") carry it for free. The cost is a few
     caps (L6).
   - **(b) A sibling field and column per carrier** (`…Salt`, precedent `veyq_share.pw_salt`): about 13 columns,
     every request and response schema that carries a field (L6 lists them), `OperatorBlindProbeTest`'s bodies, the
     copy in `primarySealedNameFor`, and later every hold table.
   - § 9's "stored beside the ciphertext" reads as either.
   - **Recommendation: (a).** The salt is part of the ciphertext's own format, as the nonce is. (b) adds 13 places
     to forget it, among them a server copy that would silently pair one field's ciphertext with another's salt.
2. **Salt length S.**
   - 16 bytes: a collision across one parent key's writes is negligible, and it costs the 512-caps 16 bytes of
     plaintext.
   - 32 bytes: the hash length HKDF's RFC suggests for a salt.
   - **Recommendation: 16.** The salt only has to make each write's key distinct under a secret `K_parent`. It is
     not a password salt, and 32 bytes would cost the tight caps twice as much for no attack it stops.
3. **`media-meta` and `media-exif` are not on § 9's list, and a medium report cannot open them.**
   - § 1 hands over "the `K_blob` of every blob … no `K_node`".
   - § 9 says "a medium's `blob-meta` is covered by its `K_blob`".
   - But a Memories medium's `wrapped_meta` (date, place, as the gallery reads it) and `wrapped_exif` are sealed under the Memory's `K_node` (`media.ts`, purposes `media-meta`/`media-exif`), not under `K_blob`.
   - So the moderator would see the picture without its date or place, and handing over `K_node` is exactly what § 1 forbids.
   - Options:
     - (a) Add both to `FIELD_PURPOSES` here. The report CR then decides which keys a medium report carries.
     - (b) Add only `media-meta` (member-editable, attributed by § 8); EXIF stays unreportable.
     - (c) Leave both, and § 1 says the moderator sees no metadata.
   - **Recommendation: (a).** Each is one more row and one more vector, and choosing what to hand over belongs to the report CR, which cannot choose a key that does not exist. This edits ADR 0093 § 9's list. The docs phase makes that edit.
4. **`group-name` and `space-icon` "already work this way" — not quite.**
   - Both derive with an **empty** salt per (scope, epoch): `K_name = HKDF(E_e, "", "veyq/v1/group-name-key")` and `K_icon = HKDF(E_e, "", "veyq/v1/space-icon-key")` (`v1.json` `group_name_key`, `space_icon_key`; `web/src/features/spaces/inviteName.ts`, `spaceIcon.ts`).
   - A rename within one epoch reuses the key, so the key opens every revision of that epoch, not one revision.
   - A pending invitee is already handed `K_name` (`sealTo` `group-name-key`).
   - Options:
     - (a) Keep both as they are, and correct § 9's sentence to "per epoch, not per write".
     - (b) Move both to the field seal. That breaks the invitee hand-over (a rename in the same epoch would need a fresh wrap per pending invitee) and moves the icon's 21884 anchor.
   - **Recommendation: (a).** Only the scope's own members can read either, a group name is not on § 1's list of reportable things, and a stored earlier revision exists only under a hold.
5. **The share fields' AAD binds no object.**
   - `share-memory-name`, `share-note` and `share-name` are purpose-only (`seal.ts`: `[]`), because `K_share` is unique to one share (ADR 0072 Decision 2).
   - § 9 says the moderator verifies by the AAD that "binds the object".
   - Under a per-write `K_field` the key is still unique to one share's one revision, so Decision 2 still holds.
   - Options:
     - (a) Keep them purpose-only, and word § 9 as "whose AAD binds the object, or whose key is unique to it".
     - (b) Bind `shareId` too, since every opener addresses a share by its id. This is a stored-format change, but one already being made.
   - **Recommendation: (a).** Binding adds nothing: the reporter's `K_field` must open the **held** ciphertext of the reported share, which the server picks by id, so a key from another share cannot open it.

Each answer changes the plan only where it says: Open 1(b) adds a backend phase (columns, schemas, the copy,
the probe bodies) and turns the reset into a baseline edit with its pin line; Open 3 changes the row count of
`FIELD_PURPOSES` and `field_seal`; Open 4(b) adds `group-name`/`space-icon` to both and the invitee re-wrap to the web
phase.

## Phases

- [ ] **1 contracts — the field seal in the crypto suite, and the caps.**
  - `FIELD_PURPOSES` and the four functions go in `packages/crypto/src/seal.ts` (Decision 1); the `0x03` envelope goes in `primitives.ts` (Decision 2); the vectors go in `v1.json` (Decision 3), generated by a script run once and not kept.
  - The conformance test gains the new table's two-way comparison, the no-purpose-in-two-tables assertion, and the `field_seal` byte checks.
  - The OpenAPI files get:
    - the raised exact `sealedAvatar` length in `account` and `tenants`, with the `@Size` beside each;
    - any 512/4096 cap L6 shows too small;
    - every description L5 lists, reworded to the field envelope and its key.
  - Then `npm run generate:api` (web, admin-web) and the backend's generated interfaces.
  - **The web is predicted red** at the wrapper modules L1 lists: a field purpose no longer type-checks in `v1.seal`. Run `npm run check` in `web/` once and name the first typed line it fails on, in the commit message.
  - **Bites:**
    - **B1 (live defect): "one field's key opens one field".**
      - Write the test against today's API first. Hand over "the key that opens node A's name", which today is the parent `K_node`, and assert it does **not** open sibling B's name, nor A's `node-meta`. That is red on the untouched tree.
      - Then build `fieldKeyOf` and point the test at it. Green.
      - The same test, with its own positive control, asserts that A's `K_field` opens A's name.
    - **B2 (live defect): "one revision".** Seal A's name twice with the same plaintext. The first envelope's `K_field` must not open the second. With today's `seal` both open under one key, so it is red.
    - **B3 (injection): the structure is the guard.** Re-add `'node-name'` to `SEAL_PURPOSES` on a detached worktree (`git worktree add --detach <dir> HEAD`). The no-purpose-in-two-tables assertion must go red, and `tsc` must accept `v1.seal({purpose:'node-name'})` again. Restore by deleting the worktree.
    - **B4 (injection): envelope confusion.** Change the field envelope's type byte to `0x02` in `primitives.ts`. The test "a field envelope does not open through `open`, nor a seal through `openField`" must go red.
    - **B5 (injection): a fresh salt per write.** Make `sealField` draw a constant salt. B2 must go red. This is the lever that proves B2 tests the salt, not the nonce.
    - **B6 (injection): the vectors pin the bytes.** Flip one byte of one `field_seal` entry's `salt`. Conformance must go red on that entry's `field_key`.
  - **Done when:**
    - `cd packages/crypto && npm run check` is green.
    - `npm run mutation -- --force` in `packages/crypto` runs, and its exit code is in the commit message.
    - `npm run generate:api` runs in `web/` and `admin-web/`.
    - `admin-web`'s `npm run check` is green; it reads no field (L4).
    - The contracts guards (`scripts/validate_*.py` over `contracts/`) are green.
    - `cd backend && ./gradlew check` is red only where an exact avatar length is pinned (`MemberAvatarSliceTest`), as predicted. Name the line.
- [ ] **2 backend — the generated caps, and the tests that pin a length.**
  - `MemberAvatarSliceTest`'s arithmetic and `AVATAR_LEN` move to the new formula, as do any 512/4096 fixtures phase 1 had to raise (L6).
  - No production Kotlin changes: L7's copy moves the envelope whole.
  - **Bite B7 (live defect):** a `sealedAvatar` of the old exact length plus one character is refused 400. A body of the new length is accepted (the control). This is red until the cap moved.
  - **Done when:** `cd backend && ./gradlew check` is green, and `python3 scripts/validate_maxlength_storage.py` and `validate_request_bounds.py` are green.
- [ ] **3 web — every field wrapper seals and opens through the field seal.**
  - These modules switch to `sealField`/`openField`; L1 and L1b give the list:
    - `tree.ts` `sealName`/`openName`
    - `nodeMeta.ts`
    - `mediaBuckets.ts`
    - `guestIdentity.ts`, plus the direct calls in `features/memory/share.ts` and `features/depot/dropBox.ts`
    - `manageShares.ts` and `resolvePublicShare.ts`
    - `memberName.ts`, `memberAvatar.ts` and `web/scripts/bootstrap-user.mts`
    - `media.ts`, if Open 3 (a) or (b) applies
  - Each keeps its signature, so its callers do not move. `AVATAR_SEALED_B64_LEN` takes the phase 1 length.
  - Test fixtures that hand-build a ciphertext with `v1.seal` (L2) move to the wrapper or to `sealField`.
  - The web mutation scope's `nodeMeta.ts` reruns: `npm run mutation -- --force` in `web/`, with its exit code in the commit message.
  - **Bites:**
    - **B8 (live defect, wire).** In `tree.wire.test.ts` (or its neighbour, whichever builds a create request), the `encryptedName` a create sends must start with the `0x03` type byte, and it must not open with `v1.open` under the parent key. Red on the untouched tree.
    - **B9 (already live, re-proven).** The guest's display name, copied by the server onto its primary bucket, still opens through `openBucketName` → `openIdentityName`. Name the test **case** in `media.buckets.test.ts` or `useUploaderNames.test.ts` that drives this path. If none exists, write it: the server's verbatim copy is exactly what a sibling salt would have broken.
  - **Done when:**
    - `cd web && npm run check` is green.
    - The e2e suites run once against a live stack through the worktree's own orchestrator (`python3 scripts/run_web_e2e.py`, pinned to the main checkout's infra when started from a worktree) and are green, apart from the `upgrade` group's reader, which is expected red across this commit (§ Context, the upgrade proof). Say so in the message.
    - A real-browser pass on a fresh instance: create a folder, rename and move it; set a Memory date; create a sub-bucket; create a share with a note and label; open it as a guest; set your profile name and picture. Each value shows after a reload. **The dev instance is reset first.** Every old ciphertext of these purposes no longer opens, and that is the expected effect, not a bug.
- [ ] **4 docs — the stated keying, the ADRs, the site, coverage.**
  - L8 is the site list; re-run it at this phase, because phases 1–3 wrote prose too. The phase **edits** each sentence that names a field's key to the field key over that key. It does not add a sentence beside it. Among the sites:
    - `docs/crosscutting/security-model.md`: the key model and the T&S section. It gains the field-key layer.
    - The enablers: `en.memory.tree`, `en.memory.node-meta`, `en.memory.buckets`, `en.memory.media` (if Open 3), `en.spaces.member-identity`.
    - The sharing, memory, spaces and depot use-cases L8 lists.
    - `docs/apps/memories.md` and `docs/apps/depot.md`.
    - ADR 0072 Decision 2's list, which gains "a field under its own `K_field`".
    - ADR 0080 Decision 2's "the `node-name` seal … take[s] the parent's content key".
    - ADR 0036 and ADR 0084, where the 21884 is "the very number of the tenant icon's". That stops being true under Open 4(a).
    - ADR 0030/0031/0034's names under `K_node`.
    - `web/CLAUDE.md`'s `member-avatar` line.
  - ADR 0093 itself:
    - the Status line: § 9 built;
    - § 9's list, per Open 3;
    - its group-name sentence, per Open 4;
    - its "AAD binds the object", per Open 5.
  - **New:** `docs/features/platform/enablers/en.platform.field-key.md`, a model enabler like `en.platform.field-editor`. It covers what is sealed under what, the envelope, what a `K_field` opens, and what the server learns: a 16-byte random salt per write, and nothing about the key. Its acceptance criteria name the phase 1 and 3 tests (B1, B2, B3, B8). Inbox #462's row says § 9 is built.
  - Coverage:
    - `uc.sharing.share-note.yaml`'s `curator-sets-note-at-creation` text, plus any criterion L8 finds in `docs/coverage/`.
    - The set-profile-picture criteria that name the sealed length.
  - The site (`git grep -ln "<each doc path above>" -- site/src/content/docs`, plus L8 over `site/`):
    - `security/key-hierarchy.md` (the `K_share` row; "every node's key and name sits under its parent's key"; the directory entry);
    - `security/cryptography.md` (the HKDF row; "What a seal binds");
    - `security/known-limitations.md`;
    - `security/protocols.mdx` (the sequence diagrams' "seals the node's name under `K_share`/`K_node`");
    - `security/what-the-server-sees.md`, which gains the salt.
    - No Use page names a key. The CR touches none, and the phase says so after re-checking with L8 over `site/src/content/docs/use`.
  - **Done when:**
    - `for f in scripts/validate_*.py; do python3 "$f" || echo "RED $f"; done` prints no `RED` (`validate_guard_gaps.py` only with `--force` if `scripts/` changed).
    - `python3 scripts/validate_coverage.py` is green.
    - `cd site && npm run check` is green.
    - L8 re-run lists no sentence that still puts one of the field purposes directly under its parent key.

## Definition of done

- Every field purpose seals only through `sealField`, and `seal` refuses it by type and at runtime (B3). That is the
  permanent guard: the typed tables plus the conformance test's two-way and disjointness assertions. No grep guard is
  added, because the structure is the guard (ADR 0072 Decision 6).
- `K_field` of one revision of one field opens that revision and nothing else (B1, B2), and the bytes are pinned by
  `field_seal` vectors (B6).
- The server stores and serves the envelope as opaque base64. No contract field and no column carries a key, and
  under Open 1(a) no salt either.
- `packages/crypto`, `web/`, `admin-web/`, `backend/` and `site/` checks are green; both mutation gates have run with
  their exit codes recorded; the trust-chain guards are green; the docs in phase 4 say what was built.
- Implementation waits for the owner's go, and for the answers to § Open for the owner.

## Retrospective

_Filled at implementation._

---

Implementation waits for the owner's go.
