---
id: cr-reissue-share-link
title: A share link a departed member could copy is re-issued, never revived — and a group removal can turn every link off
status: planned
satisfies: [uc.sharing.share-shared-scope]
adrs: ["0029", "0036", "0024", "0025", "0031", "0012", "0073"]
invariants: [operator-blind E2EE, API-first]
---

## Open for the owner

**Answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`).

Nothing below is decided. Each question carries a recommendation; the CR is written for the recommendations, and
Phase 0 records the answers in the ADR before any code changes.

**First, what the refine measured changes the row's premise** (inbox #196). The row says a removed member
*"who ever opened a share"* keeps its `K_share`, *"with a dump"*. Both halves understate it:

- **Reach is every link on the group, not the ones they opened.** Any member lists every share of a Memory, and
  the manage panel rebuilds each enabled share's full link from its recovery copy and offers a copy button — for
  co-members' shares too (Inventory rows 2–3). So a member removed from a group may hold every link that existed
  on the group's Memories while they belonged.
- **Using a link needs no dump.** A link is a bearer capability against an honest server, and `K_mem` never
  re-keys, so a copied link keeps showing photos added *after* the removal
  ([uc.sharing.share-shared-scope](../docs/features/sharing/use-cases/uc.sharing.share-shared-scope.md) main flow
  step 3). The dump branch the row names is the weaker half: a removed member with a dump already holds `K_mem`
  and reads the Memory's content whatever the link does (ADR 0012 *Rotation / revocation*). Priced by the
  refinement rule *"price a confidentiality layer against what the same attacker already reads"*
  ([docs/ai/README.md](../docs/ai/README.md) § Refinement checklist): what a re-issue buys is the
  **honest-server** cut, not dump resistance.
- **A live defect sits next to it.** Re-activating a share that was turned off because its creator was removed
  hands that creator's own link back to them, working again. The contract describes exactly this as the purpose
  of re-activation: *"its `#k=K_share` fragment is unchanged"* (Inventory row 5). No dump and no copying needed.

So the row is **not moot**: shares exist in group and tenant scopes (ADR 0029 §6, cr-0104), not only in personal
ones. ADR 0036's *"v1 = personal-scope shares"* and three sibling sentences are stale (Inventory row 9).

1. **Does a group removal offer or force re-issuing?** Options:
   (a) *offer only* — every share gets a **re-issue** action, and a share minted before the group key last
   changed carries a hint;
   (b) (a) **plus an opt-in** in the removal confirmation: *"also turn off every link on this group"*. It
   soft-revokes every enabled share of the scope in the removal's own transaction; each share's owner then
   re-issues the ones they still want;
   (c) *force* — every removal turns off every link of the group.
   **Recommendation: (b), with the box unchecked by default.** (c) breaks every guest's link on every removal,
   including amicable ones. (a) alone leaves the remover unable to act: revoke is owner-gated
   (`SharingStore.revoke`), so a group owner cannot cut a co-member's link today. The group-owner power in (b) is
   dominated by one they already hold — deleting the group turns off every link in it.
2. **Which links?** **Recommendation: every share of the scope minted before the removal** — in a group that is
   every link on every Memory, because every member could list and copy all of them (row 2). There is no narrower
   honest set: the server cannot know which links a member copied. The hint's predicate is the one the server can
   check: *the share's recovery copy is sealed below the scope's current epoch* (row 7). It has one false positive:
   an account key rotation (ADR 0060) also moves the epoch. The hint's wording says "made before the group's key
   last changed", not "before someone left".
3. **What happens to the old link's guests?** **Recommendation: the old link 404s exactly as a revoked one does,
   and the curator hands out the new one.** No redirect: a redirect from the old id would give the new id to
   exactly the person the re-issue is for.
4. **What does a re-issue keep?** **Recommendation:**
   - **Keep** the role, the expiry, the note, the private label and the password. The client reads them through
     the old recovery copy and re-seals them under the new `K_share`, with a fresh password salt. The re-issue
     dialog is the create dialog pre-filled, so the curator may change any of them except the role.
   - **Keep the password by default.** The removed member knowing it gains them nothing without the new share id,
     and with a dump they read the Memory anyway (above).
   - **Keep the Memory's drop key** for an UPLOADER link. It must not change: every upload so far is sealed to it
     (ADR 0031 point 7, row 6).
   - **Do not carry guest identities over.** Contributors' and uploaders' photos and names stay in the Memory,
     readable by members and by the new link (row 6). A returning guest names themself again on the new link and
     can no longer delete their own earlier uploads — the guest's session is stored per share id (row 6).
   - The per-link storage budget starts at zero (ADR 0073 §4 counts a link's usage through its identities). This
     is no new power: a member can already mint a fresh link.
5. **Re-activation.** **Recommendation: re-activation is refused with `409 reissue_required` when the share's
   owner is no longer a member of its scope, or its recovery copy is sealed below the scope's current epoch.** The
   UI offers re-issue there instead, which also adopts the share. A share without a recovery copy (null epoch) is
   judged only by its owner's membership: no member could have rebuilt its link.
6. **Tenant scopes.** A tenant-member removal does not rotate the tenant scope (ADR 0025), so the epoch hint never
   fires there, and the removal is a tenant-owner action outside the group panel. **Recommendation: out of this
   CR, except that re-issue works on tenant-scope shares like any other, and the re-activation refusal's
   owner-membership half covers a removed tenant member's own links.** A tenant-side "turn off every link" is a
   proposed inbox row.

## Goal

A curator can **re-issue** a share: one request creates a new share with a fresh id and a fresh `K_share`,
carrying the old one's settings, and turns the old one off in the same transaction. That is what cuts a departed
member off a link they could copy. A share whose link a departed member may hold can no longer be **re-activated**,
only re-issued. A group owner removing a member can, in the same act, turn off every link on the group.

## Context & constraints

- **The residual this answers:** [ADR 0036](../docs/decisions/0036-editable-share-note-recoverable-share-key.md)
  point 4 (*"cutting such a member off an existing link means re-issuing the link"*) and
  [security-model](../docs/crosscutting/security-model.md) § What stays open.
- **The removal governance it amends:** [ADR 0029](../docs/decisions/0029-public-viewer-share.md) §6. A removal
  soft-revokes the removed member's *own* shares; any remaining member may re-activate one, which transfers
  ownership. §7 says revocation is soft and that a link cannot be rotated away.
- **Rotation:** [ADR 0024](../docs/decisions/0024-scope-key-rotation.md) Decision 4 (a removal and its rotation are
  one request) and Decision 6 (no write under a stale epoch: the same `409 stale_epoch` shape re-issue reuses).
  The chain is backward-linked, so a member holding `E_n` opens every copy sealed at `≤ n` (Decision 1).
- **Per-scope policy:** [ADR 0025](../docs/decisions/0025-forward-secrecy-policy-per-scope.md) — groups rotate on
  removal, the tenant does not, personal has no removal.
- **Drop key:** [ADR 0031](../docs/decisions/0031-uploader-public-share.md) point 7.
- **Siblings that land after this CR** (refined 2026-09-28, planned): `cr-tenant-removal-share-links` (inbox #210)
  adds a per-scope removal counter to Decision 2's predicate and moves Decision 5's hint server-side;
  `cr-share-link-delete` (inbox #211) makes Decision 1's old row a tombstone instead of `enabled = false`, because a
  voluntary re-issue leaves the old link re-activatable by any member. Build this CR as written; those two amend it.
- **Precedent for "one request":** `cr-removal-rotates-in-one-request` (landed) closed a removal that could end
  without its rotation. Re-issue has the same shape: a create that lands without its revoke leaves the old link
  live while the curator believes it is cut.

### Inventory — measured on `main` @ `faf9ef4`, 2026-09-28

Every row was read or run at refine; the command re-derives it. *Remembered* marks the rows that were not.

| # | Fact | Locator |
|---|---|---|
| 1 | Any member of a personal, group or tenant scope may create a share of a MEMORY there. | `grep -n "Any member of the node's scope may share" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt` |
| 2 | Any member lists every share of a Memory, with each share's id, `sealedShareKey` and epoch; the gate is membership only. | `grep -n "fun listForNode" -A8 backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt`; test `ShareSharedScopeSliceTest.listForNodeShowsEveryMembersSharesWithMineFlag` |
| 3 | The manage panel rebuilds the full link of **every** listed share (not only `mine`) and shows a copy button for each enabled one. | `grep -n "recoverShareLink\|shareLinks.get" web/src/features/memory/components/ShareDialog.vue` |
| 4 | A removal soft-revokes only the removed member's own shares — in the removal-rotation's transaction, and in the tenant cascade. | `grep -rn "deactivateSharesByOwnerInScope" backend/src/main/kotlin` |
| 5 | Re-activation checks the caller's membership and the capability, **not** the share owner's membership or the copy's epoch; it transfers ownership. The contract states the old fragment keeps working. | `grep -n "fun reactivate" -A10 backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt`; `grep -n "fragment is unchanged" contracts/api/sharing.openapi.yaml` |
| 6a | Guest identities hang off a share id; a guest's token is looked up per share. | `grep -n "fun findIdentity" -A3 backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt` |
| 6b | The guest's session is stored in the browser per share id. | `grep -n "sessionKey = " web/src/views/ShareView.vue` |
| 6c | Uploaders' keys are served for media of **any** share's identity, and members' bucket names read the identity by id with no `enabled` filter — so uploads survive the old share's revocation. | `grep -n "of any share" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt`; `grep -n "fun primarySealedNameFor" -A4 backend/src/main/kotlin/com/veyq/app/features/media/MediaStore.kt` |
| 6d | The drop private key is set on the Memory once; a create without it succeeds only if the Memory already has one. | `grep -n "DROP_KEY_EXISTS\|DROP_KEY_MISSING" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt` |
| 7 | The recovery copy's epoch is stored and must equal the scope's current epoch on create; the client holds `epochs.current`. | `grep -n "sealedShareKeyEpoch != scopes.currentEpoch" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt`; `grep -n "current: number" web/src/features/spaces/epochs.ts` |
| 8 | Revoke and update are owner-gated; there is no group-owner override. | `grep -n "takeIf { it.ownerAccount == ownerAccount }" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingStore.kt` |
| 9 | Stale "personal-scope only" wording, contradicting ADR 0029 §6. | `grep -rn -i "v1 = personal-scope\|personal\*\* only in v1\|one personal-scope Memory" docs backend/src web/src contracts` |
| 10 | The residual's current wording ("each share they opened", "with a dump"). | `grep -rn -i "share they opened\|who ever opened" docs` |
| 11 | The group removal is one call from the members panel; it has no confirmation step that carries options. | `grep -n "rotateGroupForRemoval" web/src/features/spaces/GroupMembersPanel.vue`; `grep -n "removesUserId" contracts/api/scopes.openapi.yaml` |
| 12 | No voluntary "leave group" path exists — removal is always owner-driven. | `grep -rln -i "leaveGroup\|leave_group" web/src backend/src/main contracts/api` (no hits) |
| 13 | The listing takes only `nodeId`, so a removal dialog cannot count the group's links without one call per Memory. | `grep -n "in: query" -B2 contracts/api/sharing.openapi.yaml` |
| 14 | Tests that pin today's re-activation and removal behaviour. | `grep -rn "reactivate\|Deactivates" backend/src/test/kotlin/com/veyq/app/features/sharing/ShareSharedScopeSliceTest.kt backend/src/test/kotlin/com/veyq/app/features/sharing/ReactivateShareCapabilitySliceTest.kt`; `grep -n "reactivate" web/src/features/memory/share.test.ts web/src/features/memory/components/ShareDialog.test.ts` |

*Remembered, not checked:* that no wiki page describes what happens to links when someone leaves.
`grep -rln -i "reactivat\|removed member" wiki` found nothing, but a page may say it in other words; the docs phase
re-reads `wiki/en/is-my-data-safe.md`.

**Row 3 carries the argument** (reach = every link). An implementer spot-checks it first
([changes/README.md](README.md) § *don't believe every row*).

## Decisions (for the recommendations)

1. **`POST /api/shares/{shareId}/reissue` — one request, one transaction.** The body is the new share's create
   body without `nodeId` and `role`: the new `shareId`, `wrap` or `sealedDropPublicKey`, `sealedName`, the
   **required** `sealedShareKey` + `sealedShareKeyEpoch`, and the optional note, label, password fields and
   expiry. The server:
   - takes `nodeId`, `scopeId` and `role` **from the old row**, so a re-issue cannot move a link or flip it into
     or out of UPLOADER;
   - checks the keys match that role (`shareKeys`), and that the epoch is the scope's current one
     (`409 stale_epoch`);
   - checks the caller: scope WRITE access; if the old share is **enabled**, the caller must own it (as revoke
     does); if it is **disabled**, any member may re-issue it and becomes the new share's owner (as re-activation
     adopts today);
   - checks the instance capability for the role (`422 capability_disabled`);
   - creates the new row (`409 id_taken` on a collision) and sets `enabled = false` on the old one, in one
     transaction. Nothing is written on any refusal.
   - Answers `201 { shareId }`. An unknown share, or one the caller may not re-issue, is the same
     non-enumerable `404 share_not_found`.
2. **Re-activation refuses a link a departed member may hold** (question 5): `409 reissue_required` when the
   share's owner has no membership in its scope, or `sealedShareKeyEpoch < currentEpoch`. A null epoch falls to the
   first test alone.
3. **The removal-rotation takes `revokeLinks: true`** (question 1b). It is valid only together with `removesUserId`
   (`400` otherwise). In the same transaction as the membership delete, it sets `enabled = false` on every enabled
   share of the scope. It adds no operator-visible signal: `enabled` is already plain.
4. **The client re-issue** (`share.ts`): recover the old `K_share` from its copy and read the note, label and
   password. Mint a new `K_share` and share id. Re-seal the note and label, rebuild the password fields with a
   fresh salt, and seal the recovery copy at the write epoch. For VIEWER/CONTRIBUTOR, wrap `K_mem`; for UPLOADER,
   seal the Memory's **existing** drop public key (read fresh, as `createShare` does). Post. On `stale_epoch`,
   re-open the scope key and retry once, as `createShare` does. The new link is shown once, as after a create.
5. **The hint** is client-side, from data the list already returns: `sealedShareKeyEpoch < epochs.current`. It is
   the same predicate as Decision 2, so a share that shows the hint is exactly one that re-activation refuses. No
   new server state.
6. **ADR home:** a new ADR (next free number at implement time), *"A link a departed member could copy is
   re-issued, never revived"*. It supersedes ADR 0029 §6's re-activation clause (0029 gets a pointer) and replaces
   ADR 0036 point 4's "a product question of its own" with a link to it.

## Phases

1. **Phase 0 — the decision on record.** Write the owner's answers into the new ADR and point ADR 0029 §6 and
   ADR 0036 point 4 at it. No code.
   · Done when: all `scripts/validate_*.py` green.
2. **Phase 1 — contracts** (`sharing.openapi.yaml`, `scopes.openapi.yaml`). Add `reissueShare` + its request
   schema, add `409 reissue_required` to `reactivateShare`, and rewrite that operation's description (row 5's
   sentence goes). Add `revokeLinks` to `RotateScopeRequest` and to the removal paragraph.
   · `reissueShare` is a new operation on the **existing** `sharing` tag, so the backend's generated interface
     goes red until Phase 2 (changes/README.md § phases). The web client gains an optional field and a new
     method, so it stays green; run `npm run check` once to see that.
   · Done when: `npm run generate:api` and the contracts guards are green; the backend red is the expected one
     (`SharingResource` does not implement `reissueShare`).
3. **Phase 2 — backend.** `SharingHandler.reissue` + `SharingStore.reissue` (Decision 1), the re-activation
   predicate (Decision 2), `revokeLinks` through `ScopesHandler.rotateScope` → `ScopesStore.rotate`
   (Decision 3). Fix the two stale `Entities.kt` comments (row 9).
   · Done when: `./gradlew check` green, including B1–B4.
4. **Phase 3 — web.**
   - `reissueShare` in `share.ts` (Decision 4).
   - `ShareDialog.vue`: a re-issue action (own share, enabled or disabled; a co-member's share only when
     disabled), the hint badge (Decision 5), and no re-activate button where Decision 2 refuses.
   - The pre-filled create dialog in re-issue mode, with its warning copy: the old link stops working; hand out
     the new one; guests keep their photos but give their name again.
   - `GroupMembersPanel.vue`: a removal confirmation carrying the unchecked "also turn off every link on this
     group" box.
   - i18n in EN and DE.
   · Done when: `npm run check` green in `web/`, including W1–W2.
5. **Phase 4 — docs.**
   - security-model: § Sharing, the § What holds today row and § What stays open get the measured residual —
     reach = every link on the group, use needs no dump, re-issue cuts it against an honest operator; dump plus
     `K_mem` stays the ADR 0012 class.
   - `uc.sharing.share-shared-scope`: A2 rewritten; new A3 (re-issue) and A4 (turn off every link at removal);
     the matching coverage YAML.
   - `wiki/en/is-my-data-safe.md` + `wiki/de/sind-meine-daten-sicher.md`: one plain paragraph — links made before
     someone left keep working until you renew them.
   - The stale personal-scope wording (row 9).
   - Inbox #196 → done; the CR is deleted after its harvest.
   · Done when: `python3 scripts/validate_coverage.py` green and all `scripts/validate_*.py` green.

## Definition of done

- Every phase done. **Baseline before the first edit** — run it, don't trust this line:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done` printed nothing on
  `faf9ef4` at refine.
- **B1 — a departed member's link is not revived (the red-first demonstration, a live defect today).** In
  `ShareSharedScopeSliceTest`:
  - **Setup:** member M creates share S in a group. The owner removes M through the removal-rotation (the body
    `removingGroupMemberDeactivatesOnlyTheirShares` already posts); S now 404s.
  - **Action:** the owner `POST /api/shares/S/reactivate`.
  - **Red today:** `204`, and `GET /api/shares/S` answers `200` — M's own link works again. **The assertion
    expected to fail first** is the `409` status on the reactivate.
  - **After:** `409` with code `reissue_required`, and `GET /api/shares/S` still `404`.
  - **Positive control, same predicate:** a member revokes their own share minted at the current epoch and
    re-activates it → `204`, then `200`. (`reactivateByAnotherMemberTransfersOwnershipAndResolves` keeps passing:
    its creator is still a member and its share carries no copy.)
  - **Second lever, the epoch half:** a co-member's self-revoked share whose copy is at epoch 0, after a
    removal-rotation to epoch 1 → `409 reissue_required`. Anchor both on status plus code, never on "it failed".
  - **Bite on the fixed tree:** delete either half of the predicate → its own lever goes red, and the other
    stays green.
- **B2 — re-issue is one transaction.**
  - A re-issue of S answers `201`: the new id resolves `200`, S resolves `404`, and the new row carries S's role
    and node.
  - **Negative with its control:** a re-issue at a stale epoch is `409 stale_epoch`. S still resolves `200` and
    the new id `404`s: nothing was written. The same request at the current epoch succeeds.
  - A co-member's **enabled** share → `404 share_not_found`; the same share after its owner's revoke → `201`, and
    the caller owns the new share.
  - An UPLOADER re-issue without `sealedDropPublicKey`, or with a `wrap`, is `400`. A valid one leaves the node's
    `sealedDropPrivateKey` unchanged.
  - **A refusal after the lookup writes nothing either:** a re-issue whose new `shareId` is already taken →
    `409 id_taken`, and S still resolves `200`.
  - **Bite:** in a snapshot copy, turn S off *before* the id check inside the store method → the `id_taken`
    case's "S still `200`" goes red. That is the half-written re-issue this op exists to rule out. Restore.
- **B3 — `revokeLinks`.** A removal-rotation with `revokeLinks: true` 404s the owner's own and a co-member's
  share; without it, only the removed member's (the existing test is the control). `revokeLinks` without
  `removesUserId` → `400`, nothing stored.
- **B4 — the tenant half.** A removed tenant member's own tenant-scope share: re-activation by a remaining
  member → `409 reissue_required`. This is the owner-membership half with no rotation involved; the tenant epoch
  never moved.
- **W1 — the re-issued share carries its settings and nothing of the old key.** In `share.test.ts`, through the
  real `reissueShare`, capturing the request:
  - the note, label and password open under the **new** `K_share`;
  - the recovery copy opens at `epochs.current`, bound to the new share id;
  - the old `K_share` opens none of the new fields (`OpenRefused`, anchored on the type);
  - **positive control:** a member with the epoch keys reveals the new password;
  - an UPLOADER re-issue seals the **same** drop public key the node carries.
  - **Bite:** reuse the old `K_share` inside `reissueShare` → the `OpenRefused` assertion goes red.
- **W2 — the panel follows the predicate.** In `ShareDialog.test.ts`:
  - a share with `sealedShareKeyEpoch < epochs.current` shows the hint and no re-activate button;
  - **control:** a current-epoch share shows no hint, and when disabled it keeps its re-activate button;
  - a co-member's enabled share offers no re-issue.
- Existing assertions carry over per test, not by count (row 14). `reactivateByAnotherMemberTransfersOwnershipAndResolves`
  and `ReactivateShareCapabilitySliceTest` keep passing unchanged. If one needs a change, the change is named in
  the phase commit with why.
- `grep -rn -i "share they opened\|who ever opened" docs` finds nothing outside the inbox row's history. Row 9's
  grep finds nothing.

**Not proven:**
- Any of this against an **active** operator. Revocation is layer 1: a malicious operator can keep serving a
  revoked id. What the new `K_share` adds against a dump is only that later edits of the new share's password,
  label and note are out of the departed member's reach. The Memory's content under `K_mem` is not, and that
  stays the ADR 0012 class.
- That curators actually redistribute the new link. That is product copy, not a test.
