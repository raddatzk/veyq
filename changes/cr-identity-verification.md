---
id: cr-identity-verification
title: Show your own safety number, verify another member's, and see who is verified
status: planned
satisfies: [uc.spaces.enforce-verified-identities, uc.spaces.verify-identity]
adrs: ["0028", "0017", "0026"]
invariants: [operator-blind E2EE everywhere]
---

## Goal

A member sees their own safety number and its QR on their profile page; on another member's profile they compare
that member's number - by scanning the other's QR or reading the digits aloud - and mark them verified. A member with
no pin is pinned at verification. "Verified" shows on the profile and in the member lists, and a verified contact's
key change is said as such and needs a new compare. Strict mode stays out. Owner decisions 2026-10-14 (inbox #56).

## Context & constraints

- [ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md) Addendum (2026-10-14) owns the
  decisions; [uc.spaces.enforce-verified-identities](../docs/features/spaces/use-cases/uc.spaces.enforce-verified-identities.md)
  (main flow 0-3, A1, A3, E1) the flow; [uc.spaces.verify-identity](../docs/features/spaces/use-cases/uc.spaces.verify-identity.md)
  the changed-key review this extends.
- **Built and to be driven, not rebuilt** (`web/src/features/account/identityPins.ts`): the sealed, versioned,
  account-synced pin store (`pinned` / `verified`, keyed by user id), `checkAndPin`, `markVerified(userId, key)` (only
  if the pin still holds that key, `IdentityMovedError` otherwise, re-checked after a `409`), `safetyNumber(b64)`
  (6x5 digits over `v1.fingerprint`, pinned by `contracts/crypto/vectors/v1.json`), `myIdentityPublicKeyB64()`,
  `acceptKeyChange`. No UI calls `markVerified` today.
- **QR:** `qrcode` draws, `jsqr` decodes (the scanner of the Emergency Kit / device pairing). The payload is a new
  veyq marker plus the fingerprint hash only - `web/CLAUDE.md`'s `jsqr` exception holds because our payloads stay
  small; a QR that carried the identity bundle would void it.
- **No contract or backend change** expected: the pin store's endpoints and the member directory's
  `identityPublicKey` exist. If the directory model the profile reads lacks the key, it is added on the client side
  (`core/people/directory.ts` drops it today).
- Core and application: the profile page and the people directory are core (`core/people/`), the pin store is
  `features/account` (core too) - no application involved.
- **Invariant:** operator-blind. Verification makes no server call beyond the pin store's sealed write; the operator
  never learns who verified whom.

## Inventory (commands)

- `grep -rnE "checkAndPin|markVerified|safetyNumber|myIdentityPublicKeyB64|trustStates?|acceptKeyChange|getPin\(" web/src`
- `grep -rln "jsqr\|from 'qrcode'" web/src` (the scanners and QR drawing to reuse: `emergencyKit/KitScanner.vue`,
  `pairing/AddDevicePanel.vue`).
- `ls web/src/features/account/{identityPins,trustMarks}*.ts web/src/features/spaces/{ChangedKeyReviewDialog,useMemberDirectory,acceptChangedKey}* web/src/core/people/`
  and the member panels: `grep -rln "trustState\|changed" web/src/features/spaces/*MembersPanel.vue`.
- Docs: `grep -rln "verify-identity\|enforce-verified-identities\|identity-pinning\|0028-identity" docs wiki`, the
  coverage YAMLs of both use cases under `docs/coverage/features/spaces/use-cases/`, and the stale "cr-0100" named as
  strict mode's CR (`grep -rn "cr-0100" docs web/src`; cr-0100 was the public viewer share).

## Decisions

1. **My number.** My own profile page shows my safety number (grouped 6x5 digits, selectable) and its QR, and says in
   one sentence what it is for. Nothing to edit.
2. **Another member's.** Their profile page shows their number as my pin (or, unpinned, the directory) holds it, the
   state (`unpinned` / `pinned` / `verified` / `changed`), and two ways to compare: **Scan** (camera, the QR scanner;
   a QR of another payload or of a different fingerprint is said as such, never accepted) and **Compare digits** (the
   number shown large, "They match" / "They don't match"). A match calls `markVerified`; if there is no pin yet, the
   key the compare confirmed is pinned first (`checkAndPin`) and then verified - one user action. A mismatch never
   writes and points at the changed-key review when the pin differs from the directory.
3. **A changed key of a verified contact.** `trustStates` / the review know the pin was `verified`; the badge and the
   review say "a verified contact's key changed"; accepting re-pins as `pinned` (as today) and the profile then offers
   the compare again.
4. **Where "verified" shows:** the profile and the tenant's and groups' member panels, next to the existing "key
   changed" badge (the comment in `useMemberDirectory.ts` that suppresses `verified`/`pinned` goes). Participant chips
   and share pages show nothing new. A share view never shows a profile (unchanged).
5. **The QR payload** is versioned: marker, version, the fingerprint (base64 of the hash), nothing else; a test pins
   its size well below the `jsqr` bound.

## Phases

- [ ] 1 web: Decisions 1, 2 and 5 (profile: mine, theirs, scan, compare, mark verified, pin-at-verify).
  - Tests: my number equals `safetyNumber(myIdentityPublicKeyB64())` (the vector-pinned function); the QR round-trips
    through the decoder; a foreign or mismatching QR is refused and writes nothing; a match on an unpinned member pins
    then verifies (one sealed write, or the store's merge on `409`); a match on a member whose directory key moved meanwhile
    throws `IdentityMovedError` and says so; the payload's size bound. Bitten.
  - Done when `npm run check` is green and a real browser showed my number and verified another member by digits
    on the dev instance (a second member of the tenant is needed: the dev seeder can create one).
  - **Built and unit-proven, browser pass OPEN** (the implementer ran in a worktree the browser pane cannot serve):
    `npm run check` green; the browser pass is the main session's after the merge, and its record goes into the use
    case. Built as: `v1.fingerprintHash` (the digest the digits render from) in `@veyq/crypto`, `features/account/safetyCode.ts`
    (`veyqid:1:<digest>`), `markVerified(…, { pinIfUnpinned })` (pin-at-verify in ONE sealed write instead of
    `checkAndPin` + `markVerified`, so no pinned-but-unverified state can be left behind), `core/people/{MySafetyNumber,
    PersonSafetyNumber,SafetyDigits}.vue`, `loadIdentityKey` in `core/people/directory.ts`.
- [ ] 2 web: Decisions 3 and 4 (badges, the louder changed-key review).
  - Tests: the member panels show "verified"; a verified contact with a moved directory key reads as such in the
    badge and the review; accepting leaves `pinned` and the profile offers the compare again. Bitten.
  - Done when `npm run check` is green.
- [ ] 3 docs: both use cases (the ceremony's criteria move from `enforce-verified-identities`' planned coverage to
  refs, with strict mode left planned - split the file if the coverage rules require it), the enabler
  `en.account.identity-pinning` (its "descoped" reasoning replaced by the Addendum's), the stale "cr-0100" references,
  ADR 0028 Addendum's "not yet built", both wikis' safety page (`wiki/en/is-my-data-safe.md` and its DE twin).
  - Done when every `scripts/validate_*.py` is green.

## Definition of done

- `npm run check` is green in `web/`.
- Every `scripts/validate_*.py` is green, run on a detached worktree.
- The browser run of phase 1 is recorded in the use case.
- Coverage is updated; strict mode stays `planned`.

## Retrospective

_(filled at implementation)_
