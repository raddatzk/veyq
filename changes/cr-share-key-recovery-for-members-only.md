---
id: cr-share-key-recovery-for-members-only
title: A share's key opens for the Memory's members only — no sibling link reads its password, label or note
status: in-progress
satisfies: [uc.sharing.share-note, uc.sharing.share-password]
adrs: ["0036", "0037", "0012", "0024", "0072", "0031"]
invariants: [operator-blind E2EE, API-first]
---

## Open for the owner

**Answered by the owner, 2026-09-28 — every recommendation taken:** 1 → **(b2)**; 2 → **keep the reveal**;
3 → **one CR** (password, label, note and link reconstruction); 4 → **development reset, no migration** (the
owner approved the reset; the coordinating session performs it after landing, the implementer does not);
5 → **no crypto-gate here**. Implementation has the go and starts after `cr-guest-names-for-members-only`
(#190) lands, because both touch `sharing.openapi.yaml`, `share.ts` and security-model's "What holds today".
Phase 0 records these answers in ADR 0036 before any code changes.

**Also settled with the owner — the removal residual, stated precisely.** Re-sealing the recovery copy under a
new epoch on rotation would not help: a removed member who ever opened a share already holds its `K_share`,
and `K_share` does not change. So a removed member *with a dump* keeps reading every share that existed while
they were a member — including later edits of its password, label or note, which stay under that same
`K_share` — and can still use its link. Shares minted after the removal are out of their reach. Cutting a
removed member off an existing link means re-issuing the link (a new `K_share`); that is a product question
for its own inbox row, not this CR. The docs this CR writes say so.

The original questions, kept for the record:

1. **Which fix.** The options, with their trade-offs, are in § Options below. **Recommendation: (b2) —
   seal the recovery copy of `K_share` under the scope key `K_SC` instead of under `K_mem`.** The defect is not
   in the password. It is in ADR 0036's premise that whoever holds `K_mem` is a scope member. VIEWER and
   CONTRIBUTOR links hold `K_mem` too. The password is one of three fields that fall through that gap: the
   private share label, which the contract calls management-only, and the curator note fall through it the same
   way. (a) and (b1) fix the password and leave the other two, plus the reconstruction of every sibling link, for
   a second CR on the same root cause. (b2) closes all of it in one move and keeps every feature a member uses
   today: reveal, edit, copy link.
2. **Keep the password reveal at all?** With (b2) only scope members can open the password, and they already
   hold the whole Memory. **Recommendation: keep it.** You asked for the reveal in cr-0118, and after (b2) it
   leaks to no one who does not already read the content. If you would rather the server hold no copy of a
   human-chosen password at all, (a) can be added on top of (b2); it does not replace it.
3. **The scope of this CR.** Inbox #194 names the password. (b2) also takes the label, the note and link
   reconstruction out of a sibling link's reach. **Recommendation: yes, one CR.** It is one mechanism, and it is
   the same edit.
4. **Existing development data.** Shares minted before this CR carry a recovery copy sealed under `K_mem`. The
   new code cannot open it, so such a share shows no label, note or password and cannot be edited or re-copied.
   Its link keeps working for guests. **Recommendation: a development reset, no data migration.** This is before
   V1 (measured: `git tag | wc -l` → 0). The schema migration only adds a column. Tell the owner; do not wipe
   the instance yourself.
5. **The password as a cryptographic gate.** ADR 0037 kept the password server-enforced and named a crypto-gate
   as a future upgrade. **Recommendation: not in this CR.** It solves a different threat (an operator that serves
   a locked share's `wrap` to a colluding link holder), and it would need a new link format.

## Goal

A VIEWER or CONTRIBUTOR link of a Memory, together with a database dump, opens every other share of that
Memory. It yields that share's `K_share`, and through it the share's password in plain text, its private label
and its note, as well as the full link `/s/{id}#k=<K_share>`. The server never serves those fields to a guest, so
the attack needs the dump. In practice that means an operator who holds a link, or anyone holding a leaked
backup plus a link.

What the password loses by this is measured below. A dump-holder already has the Memory's content through
`K_mem`, because the password never gates it cryptographically. What it loses is:
- the password itself, a human secret that may be reused elsewhere. ADR 0037 says *"password reuse isn't
  exposed to the operator"*.
- the one barrier between the dump and live use of a sibling link on an honest server. The dump holds every
  share id in clear, so a password-protected CONTRIBUTOR sibling turns a VIEWER link plus a leaked backup into
  write access. ADR 0037 prices that barrier as *"an Argon2id-per-guess brute-force"*. The sealed copy makes it
  free.

After this CR, the recovery copy of `K_share` is sealed under `K_SC` at the scope's current epoch. `K_SC` is a
key no share carries. Members keep revealing, editing and copying links exactly as today. A link holder, with
or without a dump, opens nothing of another share.

## Context & constraints

- **The decision this corrects:** [ADR 0036](../docs/decisions/0036-editable-share-note-recoverable-share-key.md)
  Decision 1, and its *Consequences* bullet, which says *"now a **scope member** (who holds `K_mem`) can recover
  `K_share` … This grants nothing new in practice"*. The same premise is repeated in
  [security-model](../docs/crosscutting/security-model.md) (`grep -n "holder (a scope member)" docs/crosscutting/security-model.md`) and in `uc.sharing.share-note`, alternate flow *"A co-member
  reconstructs the link"*.
- **The password's contract:** [ADR 0037](../docs/decisions/0037-password-protected-shares.md). The *Reveal*
  line and the *Operator-blindness* bullet change wording. The gate itself does not change.
- **The key tiers:** [ADR 0012](../docs/decisions/0012-content-key-hierarchy.md) says `K_SC` stays in the
  owner circle and a share wraps only `K_mem`. [ADR 0024](../docs/decisions/0024-scope-key-rotation.md) covers
  epochs: content is tagged with the epoch it was sealed under, and a write under a stale epoch is refused
  (Decision 6, `NodesHandler.kt`: `grep -n "epoch != store.currentEpoch"
  backend/src/main/kotlin/com/veyq/app/features/content/NodesHandler.kt`).
- **The binding:** [ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md). The `share-key-recovery` row
  gains the epoch. Changing a row's fields changes stored bytes. Before V1 that is a vector update; after V1 it
  would be a suite v2 (`packages/crypto/src/seal.ts` header).
- **No precedent — corrected at implement time.** This paragraph used to cite `cr-guest-names-for-members-only`
  (inbox #190) as sealing a name private key under `K_SC(epoch)`. That CR landed (`aa34ab0`) without it: the owner
  chose to stop serving guest names and accept the residual (ADR 0030 point 8, security-model § What stays open).
  So this is the first seal under `K_SC(epoch)` in sharing, and the reasoning stands on its own: `K_SC` is the key
  no share carries (ADR 0012). #190 did move `sharing.openapi.yaml` (`ShareIdentity`, `ShareMedia.identityId`),
  `share.ts` and security-model; the locators were re-run on `aa34ab0`.
  - *Remembered, not checked:* `cr-trust-state-never-goes-backwards` (landed, `ea1cc98`) put a per-scope epoch
    floor on the device. A recovery copy read at an old epoch goes through `keyAt` like any other
    epoch-tagged seal. Re-check at implement time that `keyAt` is the floor-checked path.
- **Unaffected:** UPLOADER links. They carry no `wrap` of `K_mem`, so they never reached a sibling's
  `K_share`. This is the "Cannot" row *"reads an uploader link's Memory"* in security-model § What holds today,
  and it stays true.

### Inventory — measured on `main` @ `061dbcd`, 2026-09-28

Every claim below is *read, not run*. The command after it re-derives it.

**The key chain a VIEWER/CONTRIBUTOR link opens:**

| Step | What | Where |
|---|---|---|
| 1 | The link fragment is `K_share_A`. | `web/src/features/memory/shareLink.ts` (`buildShareLink`) |
| 2 | `wrap_A = seal('share-key', K_share_A, K_mem)` holds `K_mem`. It is null for UPLOADER. | `grep -n "const wrap = " web/src/features/memory/share.ts` |
| 3 | `sealed_share_key_B = seal('share-key-recovery'{scope,node,share_B}, K_mem, K_share_B)`. It is written on **every** share, of every role. | `grep -n "const sealedShareKey = " web/src/features/memory/share.ts` |
| 4 | `sealed_password_B = seal('share-password', K_share_B, pw)` is purpose-only (Decision 2: `K_share` is the binding). | `grep -n "sealedPassword: b64std" web/src/features/memory/share.ts`; `grep -n "'share-password'" packages/crypto/src/seal.ts` |
| 5 | `sealed_share_name_B` and `sealed_note_B` are sealed the same way under `K_share_B`. | `grep -n "function sealShareName\|function sealNoteMap" web/src/features/memory/share.ts` |
| 6 | The row also holds the share id in clear (the `@Id`). The dump therefore yields link B whole. | `grep -n "lateinit var id" backend/src/main/kotlin/com/veyq/app/features/sharing/Entities.kt` |

**Siblings.** Only a MEMORY node can be public-shared: `grep -n "node.kind != NodeEntity.MEMORY"
backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt`. So "sibling" means the other shares
on the same Memory, of any role, UPLOADER included (step 3). A chapter carries its own `K_mem`
([ADR 0012](../docs/decisions/0012-content-key-hierarchy.md) *Recursive `K_mem`*), but no share sits on one.

**Who is served what, without a dump:**
- **The guest resolve.** `PublicShare` carries none of `sealedShareKey`, `sealedShareName` or `sealedPassword`.
  Its own `sealedNote` is served to its own guests by design. Check: `awk '/^    PublicShare:/,/^    ShareNode:/'
  contracts/api/sharing.openapi.yaml | grep -n "^        [a-zA-Z]*:"`.
- **The manage list.** `ShareSummary` carries all four fields. It is gated to scope members:
  `grep -n "access.check(userId" backend/src/main/kotlin/com/veyq/app/features/sharing/SharingHandler.kt`
  shows `fun list` and `fun listForNode`, both at READ.

**Does the password gate anything cryptographically? No.**
- `pwToken = Argon2id(pw, salt)` feeds only `pwVerifier = SHA-256(pwToken)`
  (`grep -n "function buildPasswordFields" -A12 web/src/features/memory/share.ts`).
- `wrap` is stored whether or not a password is set. The locked resolve omits it only in the response
  (ADR 0037 *Guest gating*).
- So a dump plus any `K_mem` already reads the content, and this CR does not change that. The sealed
  password adds only the two losses named in the Goal.

**The title and note: the same kind of problem, or intended?** The same kind.
- The private label is contractually *"management-only … never served to a viewer"*
  (`grep -n "never served to a viewer" backend/src/main/kotlin/com/veyq/app/features/sharing/Entities.kt`).
  A sibling link holder reads it by the same chain.
- The note is written for B's guests. A's guests reading it is a cross-audience leak by the same mechanism.
- The Memory title (`sealedName`) is not a leak, because A is served the same title.
- Nothing in the docs intends either leak. ADR 0036 simply never counted link holders among `K_mem` holders.
  [ADR 0031](../docs/decisions/0031-uploader-public-share.md) point 7 already relies on the converse: an
  uploader cannot reach sibling keys *"because `sealed_share_key` opens only under `K_mem`"*.

**Every reader and writer of the recovery copy** (this is the phase-3 blast radius). Run
`grep -rln 'sealedShareKey\|sealed_share_key\|recoverShareKey\|recoverShareLink\|readSharePassword\|readShareName\|readShareNoteMap\|share-key-recovery' web/src backend/src packages contracts scripts --exclude-dir=generated --exclude-dir=node_modules`.
It lists:
- **Backend:** the four sharing sources, `V37__share_note.sql` and `ShareNoteSliceTest`.
- **Crypto:** `contracts/crypto/vectors/v1.json`, `seal.ts` and `v1.conformance.test.ts`.
- **Web:**
  - `share.ts` and `shareLink.ts`;
  - `ShareDialog.vue` and `ShareCreateDialog.vue`, with their tests;
  - `share.test.ts`;
  - `shareContributor.e2e.test.ts`, which reads a label through `K_mem`.

Second angle, over the callers: `grep -n ":memory-key=" web/src/views/MemoryHomeView.vue`. It shows the two
`ShareDialog` mounts. The view already holds `epochs` from `activeEpochKeys()` and imports `keyAt`.

**No per-Memory member share is built.** ADR 0012 describes one, and its recipient would hold `K_mem`
without `K_SC`. The hit of `grep -rln -i 'memberShare\|member-share\|recipientIdentityPub' backend/src/main
web/src` is the scope grant in `web/src/features/spaces/grant.ts`, not a Memory grant. When one is built, its
recipient does not manage shares: `listForNode` is scope-member-gated.

## Options

| | What | Closes | Keeps | Costs |
|---|---|---|---|---|
| **(a) Verifier-only** | Drop `sealed_password`. The server keeps `pw_salt` and `pw_verifier`, and a curator who forgot the password sets a new one. | The password, and ADR 0037's reuse claim, fully, even against members. | Everything except the reveal. | Remove one field from 3 schemas and 1 column. The edit dialog can no longer pre-fill the password, so its untouched/change/remove logic (`currentPassword`) becomes "set / keep / remove". UC A4 is rewritten. **Leaves** the label, the note and link reconstruction open. |
| **(b1) Password under `K_SC`** | Seal only `sealed_password` under `K_SC(epoch)`. | The password. | The reveal, for members. | An epoch column plus a new purpose. It costs more than (a) and closes the same field. **Leaves** the label, the note and the link. |
| **(b2) Recovery copy under `K_SC`** *(recommended)* | `sealed_share_key = seal('share-key-recovery'{scope,node,share,epoch}, K_SC(epoch), K_share)`. | The password, the label, the note and sibling-link reconstruction. It is the root cause, ADR 0036's premise. | Reveal, edit and copy link, for every scope member. | One row change plus its vector, one column, a stale-epoch refusal at create, and the manage UI opening through `epochs` instead of `memoryKey`. Co-curators: nothing changes. Every scope member holds the epoch chain (that is what membership is), and today's list is already member-gated. |
| **(c) Accept and document** | Correct ADR 0036, ADR 0037, security-model and the UC, and add a "Can" row. | Nothing. | Everything. | The docs only. It contradicts ADR 0037's stated DB-leak bound, and the password line of the share-password coverage `operator-blind` criterion becomes false. |

**The residual (b2) keeps, stated:** a member removed from a group scope keeps the epochs they held. With a
dump, they still recover the `K_share` of shares minted at those epochs. That is the same class as `K_mem` not
rotating on removal (ADR 0012 *Rotation / revocation*), and it is no worse than today. Shares minted after the
rotation are out of their reach.

## Decisions (for the recommendation)

- **The recovery copy is sealed under `K_SC` at the scope's current write epoch**, as
  `activeWriteKey()` returns it (`web/src/app/activeSpace.ts`). It is bound to (scope, node, share, epoch).
  - The `share-key-recovery` row gains `['epoch', 'uint']`, and its vector is regenerated.
  - No new purpose name. Before V1 the old row has no reader left to confuse.
  - The implementer confirms with the conformance test's purposes↔vectors comparison.
- **The epoch is stored beside it.** `veyq_share.sealed_share_key_epoch` is a nullable int, and it is non-null
  exactly when `sealed_share_key` is.
  - `CreateShareRequest` and `ShareSummary` gain `sealedShareKeyEpoch`.
  - A key without an epoch, or an epoch without a key, is `InvalidRequest` (400).
  - An epoch that is not the scope's current one is refused `409 stale_epoch`. This mirrors `NodesHandler`'s
    ADR 0024 Decision 6 check and reuses `Failures.staleEpoch`.
  - The web create path treats `stale_epoch` like any other stale write: it re-reads the scope key and retries
    once. The implementer mirrors an existing handler (`grep -rn "stale_epoch" web/src --exclude-dir=generated`).
- **`updateShare` does not touch the recovery copy.** It re-seals only `K_share`-sealed fields, so it needs
  no epoch. `reactivateShare` (adopt) does not touch it either.
- **Openers read through the epoch chain.** `recoverShareKey`, `readShareName`, `readShareNoteMap`,
  `readSharePassword`, `recoverShareLink` and `updateShare` take `EpochKeys` plus the row's epoch, and open with
  `keyAt`, not with `memoryKey`.
  - `createShare` still takes `memoryKey` for `wrap` and the drop keys, plus the write key for the recovery
    copy.
  - `ShareDialog` and `ShareCreateDialog` gain an `epochs` prop, and `MemoryHomeView` passes it.
- **A recovery copy that does not open still degrades to "no label / no note / no password / no link"**, as
  today (the `try … catch → ''` in the read helpers). This is deliberately not turned into a refusal. The only
  honest producer of an unopenable copy is a pre-CR share on an un-reset instance (owner question 4), and
  guests of that share are unaffected.
- **Nothing else moves.**
  - The password gate, `pw_salt` and `pw_verifier` stay as they are.
  - `sealed_password`, `sealed_share_name` and `sealed_note` stay under `K_share`: the key is still their
    binding (ADR 0072 Decision 2), only its recovery path moves.
  - The resolve and `PublicShare` are unchanged.

## Phases

- [x] 0 decision (docs only, after the owner answers):
      - Record the chosen option in ADR 0036 as a new Decision point, and correct its *Consequences* premise
        ("a `K_mem`-holder" includes every VIEWER/CONTRIBUTOR link).
      - Add the row below to security-model § What holds today, reading **Can**. Wait until
        `cr-guest-names-for-members-only` has merged its edit there, or merge onto it:
        *"reads another share's password, private label or note, or rebuilds its link — with a viewer or
        contributor link of the same Memory and a dump"*.
      · Done when: all `scripts/validate_*.py` green.
- [ ] 1 contracts:
      - **Crypto:** the `share-key-recovery` row gains the epoch (`packages/crypto/src/seal.ts`), and its vector
        is regenerated in `contracts/crypto/vectors/v1.json`.
      - **API:** `sealedShareKeyEpoch` on `CreateShareRequest` and `ShareSummary`, plus a `409 stale_epoch` on
        `createShare`. The descriptions of `sealedShareKey`, `sealedPassword`, `sealedShareName` and `sealedNote`
        in `sharing.openapi.yaml` say `K_SC(epoch)`, not `K_mem`
        (`grep -n "K_mem" contracts/api/sharing.openapi.yaml`, and read each hit).
      - Run `git status` first: `sharing.openapi.yaml` is also edited by `cr-guest-names-for-members-only`.
      · Done when: `npm run check` green in `packages/crypto` (the conformance test), and the generators run in
      `backend/` and `web/`.
- [ ] 2 backend:
      - Add the migration `V58__share_key_recovery_epoch.sql`; take the next free number at implement time
        (`ls backend/src/main/resources/db/migration | sort -V | tail -1`).
      - Add the entity field, the pairing check and the stale-epoch check in `SharingHandler.create`, and serve
        the epoch on the list.
      - Update the comments on `sealedShareKey` and `sealedPassword` in `Entities.kt`, `SharingResource.kt` and
        `V37`/`V39` (a migration's comment is not edited: add the correction to the new migration's header).
      · Done when: `./gradlew check` green, including B1.
- [ ] 3 web:
      - Implement the decisions in `share.ts`, `shareLink.ts`, both dialogs and `MemoryHomeView.vue`.
      - Update every test the phase-3 inventory grep lists, including `shareContributor.e2e.test.ts`
        (`grep -n "readShareName(kMem" web/src/features/memory/shareContributor.e2e.test.ts`).
      - Write W1 first and record its red (DoD).
      · Done when: `npm run check` green in `web/`, and `python3 scripts/run_web_e2e.py` green, started from the
      **main checkout** (memory: a worktree start recreates the user's dev-compose services), with its log
      showing the contributor suite executed.
- [ ] 4 docs + coverage:
      - **The new criterion lands in `uc.sharing.share-note`**, because that UC owns the recovery copy:
        *"A share's `K_share` is recoverable only by the Memory's scope members: its recovery copy is sealed
        under `K_SC(epoch)`, which no share link carries. So no link, with or without a dump, opens another
        share's password, private label or note, or rebuilds its link."* It is covered by W1 and B1.
      - **Edited:** `uc.sharing.share-password` (preconditions, flow step 4, A4, the Quality notes; its coverage
        criteria `edit-and-reveal-in-place` and `operator-blind` say `K_SC`, not `K_mem`), and
        `uc.sharing.share-note` (preconditions, the recovery paragraph, the alternate flow *"A co-member
        reconstructs the link"*). Also ADR 0037 *Reveal*, ADR 0031 point 7's parenthesis, the sharing
        `_index.md`, the security-model sharing bullets, and the entity and contract comments.
      - **Flip** the phase-0 row to **Cannot**.
      - Sites: `grep -rnE "symWrap\(K_mem, K_share\)|via .?K_mem|recover(s|ing)? .?K_share|K_mem.-recoverable|scope member\) can" docs contracts backend/src web/src wiki`.
        Re-run it here, because phases 1–3 write new prose. The wiki (EN+DE) mentions only whatever the grep
        finds. The user-facing promise ("you can look the password up later") is unchanged.
      - Tell the owner their development instance needs a reset.
      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- Every phase done. The baseline before the first edit was measured green on `061dbcd` at refine:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done` printed nothing.
- **W1 — the link's keys open nothing of a sibling share.** A web unit test in `share.test.ts`, a **live
  defect** (red on the untouched tree).
  - **Setup:** mint share B through the real `createShare`, with a password, a label and a note. The mocked
    `sharesApi().createShare` captures the request.
  - **Action:** try to open B's `sealedShareKey` with every key a VIEWER/CONTRIBUTOR link of the same Memory
    yields: `K_mem`, and that link's own `K_share`.
  - **Red today:** `K_mem` opens it, and the recovered `K_share_B` opens `sealedPassword` to the plaintext.
    **The assertion expected to fail first** is the `OpenRefused` expectation on the `K_mem` attempt.
  - **After:** every attempt throws `OpenRefused`, anchored on that type, not on "it threw".
  - **Positive control, same predicate:** a member with `EpochKeys` opens B's copy at the request's
    `sealedShareKeyEpoch` and reveals the password.
  - **The lever on both trees:** on the untouched tree the context is `{scope, node, share}`. After phase 1 it
    also carries `epoch`. Record the red with the old context, then re-point the test to the context the
    request's epoch gives.
  - **W1 is the permanent guard.** It runs the real create path, so a regression that seals the recovery copy
    under `memoryKey` again reddens it; no grep could tell the two `Uint8Array`s apart.
    - **Bite it by injection** on the fixed tree (changes/README.md § bite-proof a guard): in a snapshot copy,
      seal the recovery copy under `memoryKey` inside `createShare`.
    - **Expected:** W1 red on the `OpenRefused` assertion, not on a setup step. Restore from the snapshot.
- **B1 — the server holds the epoch honestly.** A backend slice test in `ShareNoteSliceTest` (or beside it), a
  **live defect** once phase 1 has made the tree compile (the ordering rule in changes/README.md).
  - A create whose `sealedShareKeyEpoch` is below the scope's current epoch is `409 stale_epoch`, and no row
    is written.
  - A key without an epoch, and an epoch without a key, are each `400`.
  - **Positive control:** a create at the current epoch succeeds, and the manage list returns the same epoch.
  - **Anchor:** `isHttpStatus` plus the error code, never "it failed".
  - What B1 does not claim: it proves nothing against an operator, because the epoch check is a server
    check. The operator-facing guarantee is W1's.
- **Existing tests carry over per assertion, not by count.** `SharePasswordSliceTest.manageListCarriesSealedPasswordForReveal`
  and `ShareNoteSliceTest.createWithoutNoteResolvesNullAndTheManageListCarriesTheRecoverableKey` keep their
  assertions and gain the epoch. Every `share.test.ts` case that recovers `K_share` through `kMem` moves to `K_SC(epoch)`. Find them with
  `grep -n "readShare\|recoverShare\|updateShare(\|share-key-recovery" web/src/features/memory/share.test.ts`
  (cases at 330, 352, 365, 376, 384 and 424 on `061dbcd`).
- `grep -rn "memoryKey, kShare))" web/src/features/memory/share.ts` finds no recovery seal, and the phase-4
  sites grep finds no stale `K_mem` recovery wording.

**Not proven:**
- **A page the operator rewrites.** The member's manage page is operator-served on the web, and passive-safe
  only ([ADR 0026](../docs/decisions/0026-client-trust-tiers.md)). A rewritten page can read what the member's
  keys open. No seal prevents that.
- **A removed member's old epochs.** Stated in § Options, and not fixed here.

## Retrospective

(filled when done)
