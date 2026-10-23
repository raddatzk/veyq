---
id: cr-scope-rotation-rekeys-nodes
title: Every scope rotation re-keys every node below it, and the rotation's texts say what it does and costs
status: planned
satisfies: ["uc.spaces.rekey-after-rotation", "uc.account.rotate-keys", "uc.spaces.complete-queued-rotation"]
adrs: ["0109", "0024", "0080", "0060", "0075", "0012"]
invariants:
  - operator-blind — no key, name or plaintext reaches the server; the re-key adds rewritten ciphertext in rows it already holds and node epochs it already sees
  - no store object is written or read by a re-key; K_blob never changes
  - after a rotation's re-key no write lands under a node key from before it (layer 1, the server's two 409s; layer 2, the device floor)
  - a rotation reads secured only when no live or trashed node of the scope lags the current epoch, derived by the server from its rows
  - core never calls an application; each application re-seals its own fields through a registry hook (web) and a seam (backend), fail closed
  - no text on the panel or the site claims more than is built; texts say what happens and what it costs, never that it is secure (owner 2026-10-23)
---

## Goal

Inbox #518: a rotation moves `K_SC` only, so whoever is shut out keeps every node key and, with a dump, reads
everything later added inside a folder, Memory or chapter that existed before. That breaks every rotation's promise:
an account's after a compromise, a group removal's, an explicit re-key. The owner decided (2026-10-23) that **every
rotation re-keys every node below the scope key**. [ADR 0109](../docs/decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md)
holds the decision; [uc.spaces.rekey-after-rotation](../docs/features/spaces/use-cases/uc.spaces.rekey-after-rotation.md)
the flow. This CR builds it and rewrites the rotation's texts to the owner's copy direction (short, what happens and
what it costs, no reassurance), folding in the rest of inbox #519: the sole-owner list before the button, and the
real screenshot.

## Context & constraints

- **The false claims are live today.** The panel's `rotation.statusHint.secured` (*only your new keys open it*),
  `rotation.doneNotRepair` (*the old device is shut out*), `queuedHint.secured`, the site page's *Secured*, and the
  removal paragraph of `space-owners/who-gets-into-your-space` say more than is built. Phase 1 makes them true for
  today, before anything else is built; phase 5 writes the final wording.
- **The decisions are ADR 0109's; read it before each phase.** Points 1–8 are the work order's rules: what a
  node's commit re-seals (2), re-wrap not re-encrypt (3), shares (4), drop keys (5), the two refusals and the floor
  (6), who runs and resumes it (7), what the server learns and moderation (8).
- **Owner decisions of 2026-10-23** (each is in the ADR): every rotation re-keys, not only a compromise; links keep
  working, re-wrapped to the new key, and stay marked `reissueRequired` — no switch-off, no automatic re-issue; writes
  wait per node (`node_rekey_pending`), never the whole scope; #519's rest is folded in.
- **Defaults the owner was shown and did not stop** (same date): re-seal every field rather than a key chain per
  node; trashed nodes are re-keyed; a node's drop keypair is replaced and its guests' `K_up` re-sealed; moderation
  hold copies stay untouched.
- **ADR 0080 Decision 6 narrows** (ADR 0109 point 6): a guest's create below the current epoch, stored as sent so
  far, becomes `409 stale_epoch`. That changes a guest path the e2e and slice tests drive; the locator below finds
  them.

### Measured substrate (worktree `rotation-rekeys-nodes-4f5733` at `cb1e5f89`, clean)

- A rotation writes no node row: `grep -c "NodeEntity\|veyq_node\|wrappedContentKey" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/spaces/scope/ScopesStore.kt` → `0`.
- No in-place writer of a blob wrap exists: `grep -rn "wrapped_blob_key\s*=\|wrappedBlobKey = :" src/backend --include='*.kt' | grep -v /test/ | wc -l` → `0`; `wrapped_blob_key` is written only by inserts (`BlobUploadStore`).
- No bulk or re-key operation exists: `grep -n "operationId:" contracts/api/*.yaml contracts/api/*/*.yaml | grep -ciE "rekey|re-key|rewrap|batch|bulk"` → `0`.
- `sealed_drop_private_key` is set only `where … is null` (`SharingStore`); `updateNode` requires `deletedAt is null`;
  `listTrashedNodes` returns trash roots only.
- Request caps a re-key's batches must respect: `MediaBatchRequest.items` ≤ 256, blob upload/presign lists ≤ 256,
  media listing pages ≤ 1000 (`contracts/api/memories/media.openapi.yaml`, `contracts/api/blob.openapi.yaml`).
- Trust chain on this tree: every `scripts/validate_*.py` but `validate_guard_gaps.py` exits `0` after the refine's
  doc edits (measured 2026-10-23). `./gradlew check` and `npm run check` were **not** run at refine (no build in a
  refine); phase 2 records both on `main` before its first edit.

## Decisions this CR takes

ADR 0109 decides the mechanism. These are the work order's own shape decisions, each settled here:

1. **A node's re-key is one core operation, with parts.** Core owns the endpoint (scope + node, stating the node's
   placement and meta revisions and its epoch) and the transaction; its body carries core's fields (the node's own
   entry, each child's entry, meta, attachments' blob wraps, drop keypair, shares, guest identities) and an opaque
   per-application part. Fields too many for one request (a 1,000-photo node) go up in staged parts first, keyed by a
   re-key id, and the commit swaps them in one transaction; an abandoned stage expires. Each part respects the
   contracts' existing caps.
2. **Applications re-seal their own fields through a seam, fail closed.** Web: a required `KindDescriptor` hook,
   `rekeyContent` (shaped like `exportContent`), so a new kind without one does not compile. Backend: a
   `NodeRekeyParticipant` per application, called inside core's commit transaction (Memories: media meta, EXIF,
   derivative and banner blob wraps, bucket names; Depot: file content blob wraps). A **guard**,
   `scripts/validate_node_rekey_arity.py`, derives every column sealed under a node key from the entities (as
   `validate_node_subtree_erasure.py` derives `node_id` rows) and is red on one no participant or core body rewrites;
   the columns sealed under another key (`blob-meta` under `K_blob`, `share` fields under `K_share`, `item-favorites`
   under `E_0`, report keys) are its named exclusions, each with the reason.
3. **The node epoch is the write's freshness token.** Every write in the inventory states `nodeEpoch` (the epoch of
   the node whose key it sealed under, as read); the two refusals are ADR 0109 point 6's. Declared in
   `x-veyq-concurrency` like `placementRevision`, so `validate_write_concurrency.py` carries it.
4. **The re-key's state is derived, never stored.** `rekeying` = the scope's epoch moved and a node lags; the
   server derives it from `veyq_node.epoch` against the scope's current epoch (an index on `(scope_id, epoch)` keeps
   the probe cheap) and serves it on the rotation status (ADR 0060 Decision 6) and on an owner's scope row, which is
   what the scope-rotation worker reads to resume. No new task kind. A queued job's completion is refused while a
   node lags (`409 rekey_incomplete`).
5. **Who may commit a node's re-key:** an owner of the scope holding its current grant and not excluded by a live
   rotation job (the `rotateScope` gate), because the re-key re-wraps every share and needs every recovery copy.
6. **The device floor reaches node keys** in the one helper every write path seals through, not per caller.

## Inventory

Each row hands over the command that finds the sites; run it at implement time.

| Area | What changes | Locator |
|---|---|---|
| Write operations sealing under a node key | state `nodeEpoch`; both refusals | `grep -n -E "wrappedBlobKey\|encryptedName\|wrappedMeta\|wrappedExif\|sealedDisplayName\|wrappedContentKey\|sealedDropPrivateKey\|wrappedIdentityKey\|sealedDropPublicKey\|slugToken" contracts/api/*.yaml contracts/api/*/*.yaml` — at refine: `startBlobUploads`, `createNode`, `updateNode`, `setNodeCover`, `removeNodeCover`, `restoreNode`, `ensureShareDropFolder`, `completeMedia`, `createBucket`, `renameBucket`, `updateMediaMeta`, `setMemoryBanner`, `removeMemoryBanner`, `completeShareMedia`, `createShare`, `reissueShare`, `createShareIdentity`, `startShareBlobUploads`, `createShareNode`, `updateShareNode`, plus Depot's file writes (`grep -n "operationId" contracts/api/depot/*.yaml`) |
| Columns a re-key rewrites | in-place writers + the participant seam | `grep -rhno -E 'name = "(wrapped\|sealed\|encrypted)_[a-z_]+"' src/backend \| grep -v /test/` (second angle: `src/packages/crypto/src/seal.ts` purposes keyed by a node key) |
| Rotation callers | run the re-key after each epoch commit | `grep -rn "commitRotation(" src/web/src --include='*.ts' --include='*.vue'` (removal, scope-rotation worker, `rotateKeys` personal) |
| Guest create epoch rule (ADR 0080 D6 narrowed) | below current → `409 stale_epoch` | `grep -rn "sealableOffRoot\|stale_epoch" src/backend --include='*.kt'` and the tests: `grep -rln "createShareNode\|stale_epoch" src/backend/*/src/test src/web/src tests/e2e` |
| Trash | re-key lists and commits trashed descendants | `grep -rn "deletedAt is null\|deleted_at is null" src/backend --include='*.kt' \| grep -i node` |
| Status states | `rekeying` beside `waiting/stalled/secured/failed` | `grep -rn "stalled" contracts/api src/web/src/features/account/rotation src/web/src/features/spaces --include='*.yaml' --include='*.ts' --include='*.vue'` |
| Texts | interim (phase 1), final (phase 5) | `src/web/src/i18n/index.ts` blocks `rotation:` (EN and DE); `grep -rn "rotation\.\|newLock\|needsNewLock" src/web/src --include='*.vue' --include='*.ts' -l` |
| Site pages | per phase below | `git grep -ln -E "uc.account.rotate-keys\|uc.spaces.complete-queued-rotation\|uc.spaces.rekey-after-rotation\|0024-scope-key-rotation\|0080-every-node\|0075-a-link\|0109-a-scope" -- site/src/content/docs` — at refine: `account/replace-your-keys` (EN+DE), `space-owners/who-gets-into-your-space` (EN+DE), `sharing/manage-links` (EN+DE), `security/key-hierarchy`, `security/known-limitations`, `security/threat-model`, `security/protocols` |
| Docs stating "node keys never re-key" | rewritten in the phase that builds it | `grep -rn -i -E "never re-key\|stable and never re-keyed\|node keys? never" docs site/src` |

## Texts

The owner's copy direction (2026-10-23): short; say what happens and what it costs; never reassure that something
is secure. The lock metaphor stays (the sidebar's *Needs a new lock* and the owner pages use it).

### Phase 1 — true for today (before the re-key exists)

| Key | EN | DE |
|---|---|---|
| `status.secured` | New lock | Neues Schloss |
| `statusHint.secured` | New lock on the space itself. Folders and Memories that were already there keep their old keys, so the old key still reads what is added to them. | Neues Schloss für den Bereich selbst. Ordner und Erinnerungen, die schon da waren, behalten ihre alten Schlüssel – der alte Schlüssel liest also, was dort dazukommt. |
| `queuedHint.secured` | New lock on the space itself; folders and Memories already there keep their old keys. Someone here still has to confirm it’s you. | Neues Schloss für den Bereich selbst; Ordner und Erinnerungen, die schon da waren, behalten ihre alten Schlüssel. Jemand hier muss noch bestätigen, dass du es bist. |
| `doneNotRepair` | Your keys are replaced and every other device is signed out. Below, each space shows how far its new lock reaches. | Deine Schlüssel sind ersetzt, alle anderen Geräte sind abgemeldet. Unten steht bei jedem Bereich, wie weit sein neues Schloss reicht. |

Site, same phase: `account/replace-your-keys` EN+DE — the *Secured* bullet becomes the `statusHint.secured` text
above; `space-owners/who-gets-into-your-space` EN+DE — the removal paragraph adds that folders and Memories already
there keep their keys; `security/key-hierarchy` and `security/known-limitations` state the residual as today's
(*a rotation re-keys no node yet; what is added inside an existing folder or Memory seals under keys the excluded
party holds*), linking ADR 0109 as decided, not built.

### Phase 5 — final (the panel, `rotation.*`)

| Key | EN | DE |
|---|---|---|
| `sub` | For an emergency: someone else has your keys, for example through a device you approved that wasn’t yours. | Für den Notfall: Jemand anderes hat deine Schlüssel, etwa über ein fremdes Gerät, dem du den Zugang bestätigt hast. |
| `whatBody` | Your Master Key and your identity. Your password, Secret Key and Emergency Kit stay as they are. | Dein Master Key und deine Identität. Passwort, Secret Key und Notfall-Kit bleiben, wie sie sind. |
| `whyTitle` | Why | Warum |
| `whyBody` | Signing a device out ends its access to the server, not the keys it holds. New keys shut those out of everything added from now on. | Abmelden beendet den Zugang eines Geräts zum Server, nicht die Schlüssel, die es hat. Neue Schlüssel sperren diese für alles aus, was ab jetzt dazukommt. |
| `whenBody` | Only if a device that isn’t yours had your keys. For a lost device of your own, choose “Sign out and remove this device’s saved key” instead. | Nur wenn ein fremdes Gerät deine Schlüssel hatte. Für ein verlorenes eigenes Gerät wähle stattdessen „Abmelden und gespeicherten Schlüssel entfernen“. |
| `afterTitle` | What it costs | Was es kostet |
| `factIdentity` | Everyone sees that your key changed. Whoever had verified you compares the safety number again. | Alle sehen, dass sich dein Schlüssel geändert hat. Wer dich verifiziert hatte, vergleicht die Sicherheitsnummer neu. |
| `factSharedSpaces` | You are outside every shared space until someone there confirms your new key. | In jedem geteilten Bereich bist du draußen, bis jemand dort deinen neuen Schlüssel bestätigt. |
| `factSignsOut` | Every other device is signed out and needs your password and Secret Key again, or to be added from this one. | Alle anderen Geräte werden abgemeldet und brauchen wieder Passwort und Secret Key oder werden von diesem aus neu hinzugefügt. |
| `factInvites` | Open group invitations to you lapse. | Offene Gruppeneinladungen an dich verfallen. |
| `factNotRepair` | It can’t be undone. What is there now stays readable to the old key; only what is added afterwards is closed to it. | Es lässt sich nicht rückgängig machen. Was jetzt da ist, bleibt für den alten Schlüssel lesbar; nur was danach dazukommt, ist für ihn zu. |
| `factLinks` (new) | Links made before stay open to the old key until you renew them. | Vorher erstellte Links bleiben für den alten Schlüssel offen, bis du sie erneuerst. |
| `soleOwner` | You are the only owner of these spaces. Make someone else an owner first, or they keep the old lock: | In diesen Bereichen bist du die einzige besitzende Person. Mach vorher jemand anderen dazu, sonst behalten sie das alte Schloss: |
| `factNeedsKit` | You need your Emergency Kit. | Du brauchst dein Notfall-Kit. |
| `gateNotCold` | This device unlocked without your Secret Key, so enter it here. | Dieses Gerät wurde ohne deinen Secret Key entsperrt, gib ihn deshalb hier ein. |
| `confirmBody` | Every space gets a new lock and every other device is signed out. This can’t be undone. | Jeder Bereich bekommt ein neues Schloss, und alle anderen Geräte werden abgemeldet. Das lässt sich nicht rückgängig machen. |
| `doneNotRepair` | Your keys are replaced. Below, each space shows whether its new lock is in. | Deine Schlüssel sind ersetzt. Unten steht bei jedem Bereich, ob sein neues Schloss drin ist. |
| `status.secured` / `.rekeying` (new) / `.waiting` / `.stalled` / `.failed` | New lock / Fitting the new lock / Waiting for another owner / Nobody can do it / Old lock | Neues Schloss / Schloss wird eingebaut / Wartet auf eine andere besitzende Person / Niemand kann es tun / Altes Schloss |
| `statusHint.secured` | What is added here from now on is closed to the old key. Links made before stay open to it until you renew them. | Was hier ab jetzt dazukommt, ist für den alten Schlüssel zu. Vorher erstellte Links bleiben für ihn offen, bis du sie erneuerst. |
| `statusHint.rekeying` (new) | The new lock is going in. Adding things here waits until it’s done. | Das neue Schloss wird eingebaut. Hinzufügen wartet hier, bis das fertig ist. |
| `statusHint.failed` | This space still has its old lock. Try again. | Dieser Bereich hat noch sein altes Schloss. Versuch es noch einmal. |
| `queuedHint.waiting` | Another owner fits the new lock the next time they open veyq. Until then, the old key reads what is added here. | Eine andere besitzende Person baut das neue Schloss ein, sobald sie veyq das nächste Mal öffnet. Bis dahin liest der alte Schlüssel, was hier dazukommt. |
| `queuedHint.stalled` | No other owner is left who can fit the new lock. Make someone an owner here. Until then, the old key reads what is added here. | Keine andere besitzende Person kann das neue Schloss einbauen. Mach hier jemanden zur besitzenden Person. Bis dahin liest der alte Schlüssel, was hier dazukommt. |
| `queuedHint.rekeying` (new) | The new lock is going in. Someone here still has to confirm it’s you. | Das neue Schloss wird eingebaut. Jemand hier muss noch bestätigen, dass du es bist. |
| `queuedHint.secured` | New lock in. Someone here still has to confirm it’s you. | Neues Schloss drin. Jemand hier muss noch bestätigen, dass du es bist. |
| `queuedHint.failed` | Fitting the new lock kept failing, so the old key still reads what is added here. Start it again. | Das Einbauen ist wiederholt gescheitert, deshalb liest der alte Schlüssel noch, was hier dazukommt. Stoß es neu an. |
| `errorTrustState` | This device can’t open your list of trusted keys or your device names. Nothing was changed. Start that list over first, under “Start a list over”. | Dieses Gerät kann deine Liste vertrauter Schlüssel oder deine Gerätenamen nicht öffnen. Es wurde nichts geändert. Beginne die Liste zuerst neu, unter „Liste neu beginnen“. |

Keys not listed keep their text. `soleOwner` shows only with a non-empty list (decision below); with none, the line
is not shown.

**Site, `account/replace-your-keys` (EN+DE), final.** The intro's second sentence (*The page explains the same …
none of it is small print*) and the closing paragraph's reassurance (*which is exactly what veyq is meant to notice
… can never hand your spaces to someone else*) go; the page keeps its link to *Is my data safe?* as a plain
*More:* line. *What changes afterwards* becomes *What it costs*, its bullets the panel's `fact*` texts (with
`factLinks`, linking `sharing/manage-links`). The result list carries the five states with the `statusHint` /
`queuedHint` texts above. *Two things before you start* names the list of spaces where you are the only owner.

**Site, others, final.** `space-owners/who-gets-into-your-space` EN+DE: the removal's new lock reaches every folder
and Memory, adding there waits while it goes in, links made before stay open to the removed member until renewed.
`sharing/manage-links` EN+DE: *Renew* is what closes a link to someone who left or replaced their keys, since the
link keeps showing what is added. `security/key-hierarchy` (*Rotation touches one layer only* becomes the re-key and
its cost), `known-limitations` (the residual list of ADR 0109 § Consequences), `threat-model` (removed member and
link holder rows), `protocols` (the rotation section).

### The sole-owner list (#519)

Before the button, the panel lists every shared space — the tenant included — where the caller is the only owner,
by its name as this device can open it (`groupNameUnavailable` / `scopeNameUnavailable` where it cannot). The client
derives it from the scopes it already lists and their member lists, which it already reads for owners; if that turns
out to need a server field, that is a question back to the owner, not a decision for the implementer. UC E4's entry
screen promise is then met.

## Phases

1. **Say what holds today** (web texts, site, docs). The phase-1 texts above (EN+DE); the site pages named there;
   `uc.account.rotate-keys` (Postconditions, steps 6, 9, *User impact*) and `uc.sharing.share-shared-scope` step 3 /
   A1 state today's reach. **Done when:** `npm run check` in `src/web/` green; the site builds; every
   `scripts/validate_*.py` but `validate_guard_gaps.py` exits `0`; a browser pass shows the result screen of a rotation
   in a dev account with the new texts in EN and DE.
2. **Contracts.** First record `./gradlew check` and `npm run check` exit codes on `main`. The re-key operation with
   staged parts (decision 1); the listing it needs (every node of the scope, trashed descendants included, with epoch;
   per node its media, attachments, buckets, shares, guest identities — existing listings where they suffice);
   `nodeEpoch` on every write of the inventory with its `x-veyq-concurrency` declaration; `409 node_rekey_pending`,
   `409 rekey_incomplete`; the epoch served per node on member and guest reads; the `rekeying` state on the rotation
   status and the owner's scope row; the guest-create rule. No seal changes, so `contracts/crypto/vectors/v1.json`
   stays byte-identical. **Done when:** `npm run generate:api` runs, the contracts guards are green, the predicted
   client and server reds are named by their typed lines (changes/README.md § Phases), and new operations are
   classified in `OperatorBlindProbeTest`.
3. **Backend.** Core's commit transaction under the node row lock, the participant seam with Memories' and Depot's
   participants, the in-place writers, the trashed listing, both refusals in every write handler of the inventory
   (core, Memories, Depot, the guest handlers), the derived `rekeying` state and its index, the completion gate, the
   guest-create narrowing. `scripts/validate_node_rekey_arity.py`, bitten per changes/README.md (snapshot-and-restore:
   remove a participant's column write → red; restore → green; run its predicate on the untouched tree first).
   **Done when:** `./gradlew check` green; slice tests for ADR 0109 bite tests 2, 3, 7, 8 (each negative with a
   positive control on the same predicate, anchored on the status code); the new guard green and bitten.
4. **Web.** The re-key driver (list, top-down, staged parts, resumable from the derived state), run after each of
   the three `commitRotation` callers and by the scope-rotation worker for a scope reading `rekeying`; the
   `rekeyContent` hook for Memories' and Depot's kinds; `nodeEpoch` on every write and the retry on
   `node_rekey_pending` / re-read on `stale_epoch`, members and guests; the device floor in the sealing helper;
   `rekeying` on the panel and the owner's row. **Done when:** `npm run check` green; unit tests for ADR 0109 bite
   tests 1, 4, 5, 6 through the real crypto (no mocked seal); a browser pass: rotate a dev account that owns a group
   with a Memory of 20+ photos and a link, add a photo afterwards, and show it opens for a member and through the
   link, while the link shows *renew*.
5. **Texts, the sole-owner list, site, docs, coverage.** The phase-5 texts; the sole-owner list; the site pages
   final; the real `account-replace-keys` screenshot; `uc.account.rotate-keys`, `uc.spaces.complete-queued-rotation`,
   `uc.sharing.share-shared-scope`, `en.spaces.scope-key-rotation`, `docs/crosscutting/security-model.md` (the
   *What holds today* row this refine added flips to *Cannot*, except through a link until re-issued; § Revocation's
   *Not built yet* goes), ADR 0024 § Guarantee's and ADR 0080 Decision 5's *until … lands* sentences and ADR 0109's
   status line; the coverage of `uc.spaces.rekey-after-rotation` from `planned` to refs, and the new criteria under
   the other two use cases. **Done when:** `npm run check` green; every `scripts/validate_*.py` exits `0`
   (`validate_guard_gaps.py --force` too, since phase 3 added a guard); a browser pass of the panel before and after
   a rotation in EN and DE, with the sole-owner list showing for a space the account owns alone.

## Definition of done

- A rotation of any kind leaves no live or trashed node of the scope below its current epoch, and only then reads
  *New lock*; what is added afterwards opens with no key from before, except through a link from before.
- No store object moved; vectors unchanged.
- `scripts/validate_node_rekey_arity.py` guards the field set; `validate_write_concurrency.py` carries `nodeEpoch`.
- Panel and site say what happens and what it costs in EN and DE, and nothing more than is built.
- Inbox #518 and #519 rows deleted; ADR 0109's status line says built.

## Open questions (owner)

None open at refine. Handed back if met: a server field for the sole-owner list (§ The sole-owner list).

## Retrospective

_Filled by the implementer._
