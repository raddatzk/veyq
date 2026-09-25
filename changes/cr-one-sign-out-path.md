---
id: cr-one-sign-out-path
title: One sign-out, one ending — a new document, a rejected refresh, and every other tab
status: in-progress
satisfies: [uc.account.sign-out]
adrs: ["0070", "0004", "0047", "0058"]
invariants: [operator-blind E2EE]
---

## Goal

Signing out today resets a hand-kept list of stores, and the list is wrong in every direction:
- the decrypted tree is never reset, and several stores have a reset nobody calls, or none at all;
- `TermsAcceptView.vue` carries its own copy of the sequence;
- `LoginView.vue` and `LockedSignOut.vue` skip most of it;
- a refresh the server rejects drops the tokens and keeps the Master Key and everything decrypted;
- no other tab hears of any of it (inbox #168).

After this CR, [ADR 0070](../docs/decisions/0070-a-session-ends-with-a-new-document.md) is built:
- **One ending**, run by all three triggers: sign-out, a rejected refresh, and another tab's announcement.
  It drops the local convenience tiers, tells the media service worker to drop every playback, announces the
  end to other tabs, and loads the sign-in page as a **new document**. The new document is the wipe.
- **One sign-out sequence** in front of the ending, called by all five sign-out entry points.

## Context & constraints

- **The behaviour is written down:** [uc.account.sign-out](../docs/features/account/use-cases/uc.account.sign-out.md)
  (triggers, main flow, the ending, E1–E4, what stays on the device) and ADR 0070 (why a new document, and
  what was rejected). This CR builds both. It invents nothing.
- **Order:** `refreshSession` already lives in `web/src/app/sessionRefresh.ts` (moved out of the login feature by
  `cr-session-refresh-into-app`); phase 3 edits it there.
  It also edits the head of `signOut.ts`, whose `resetServerRole` call `cr-admin-api-only-through-the-admin-container`
  deletes — text overlap only; whichever lands second rebases.
- **The ending imports no feature and not the router** (ADR 0070 § Decision 1). That is what lets the refresh
  path call it without a cycle. Take the sign-in path as a literal or a parameter.
- **Measured at refine, so no phase re-derives it. Spot-check the first bullet**, because the whole argument
  rests on it:
  - A rejected refresh drops only the tokens: the `catch` of the rotation calls `clearTokens()` and rethrows.
    `grep -n -A4 "} catch (e)" web/src/app/sessionRefresh.ts`.
  - No `BroadcastChannel` exists: `grep -rn BroadcastChannel web/src` finds nothing.
  - The service worker keeps a decrypted read-ahead per playback, in its own memory
    (`grep -n "class PlaintextCache" web/src/sw/mediaSw.ts`), and today revokes only one handle at a time
    (`grep -n "veyq-revoke-playback" web/src/sw/*.ts`).
- **The five entry points and every bare call:**
  `grep -rn "signOut\|logout()" web/src --include='*.vue' --include='*.ts' | grep -v '\.test\.'`.
  They are `ProfileMenu.vue`, `SessionsPanel.vue` (revoking your own family), `TermsAcceptView.vue` (the copy),
  `LoginView.vue` (its own `signOut` function), and `LockedSignOut.vue`. `LockedSignOut` emits
  `done(signedOut)` so that its parent can report a failed logout. The new document replaces that parent, so the
  report becomes E4's notice on the sign-in page. Check what the parent does with `done` before deleting it
  (`grep -rn "LockedSignOut" web/src --include='*.vue'`).
- **What the ending clears in storage** is what sign-out already clears locally: the session vault and the
  local remember-me key (`grep -n "clearSessionVault\|clearDeviceKey" web/src/features/account/login/login.ts web/src/app/boot.ts`).
  **What stays** is uc.account.sign-out § *What stays on the device*. Check that list once against
  `grep -rn "localStorage\|sessionStorage\|indexedDB.open" web/src | grep -v '\.test\.'`: a key the list
  doesn't name is a finding. Either the ending clears it or the use-case names it. Don't wave it through.
- **Out of scope:**
  - `admin-web/src/shared/handle401.ts` has a comment pointing at a `signOut` that admin-web doesn't have.
    admin-web holds no keys, so that is hygiene for inbox #170 (#165 is done).
  - A sign-in to a different account in another tab (ADR 0070, *Not covered*).
  - The unbuilt idle re-lock that `uc.account.unlock`'s state diagram names (`Unlocked --> Locked : … timeout`).

## Phases

- [x] 1 web — **the ending.**
      - Add `web/src/app/sessionEnd.ts` with one function. It runs the four steps of uc.account.sign-out
        § *The ending*, in that order, and only when the tab holds a session (an access token in memory or an
        unlocked vault).
      - The service-worker step is a new protocol message: drop every playback, reject its pending chunk
        requests, clear its caches. It must reach the active worker whether or not that worker controls this
        page, since another tab may be the one playing. The protocol version moves with the new message, so a
        stale worker is not trusted with playback (ADR 0047's handshake).
      - The announcement goes out on one same-origin `BroadcastChannel` and carries nothing but its type.
        Install the listener at boot. A received announcement runs the ending **without** re-announcing and
        without calling the server.
      - The last step is `location.replace` to the sign-in page. Put a comment at that line with ADR 0070's
        reason, because a change there silently voids the guarantee.

      Tests (web unit):
      - the order of the steps;
      - a tab with no session does nothing;
      - a received announcement runs the ending without echoing it;
      - the navigation is a document load.

      **Bite:** replace `location.replace` with a router push and the navigation test must go red; drop the
      has-a-session check and the no-session test must go red.
      · Done when: `npm run check` is green.
- [x] 2 web — **the one sign-out sequence.**
      - `features/account/login/signOut.ts` absorbs `logout()`: it deletes the convenience wraps and revokes the
        family while the session works, then calls the ending, whether or not those calls succeeded. A failed
        revoke is carried into the new document as E4's notice. The notice carries no account data and is read
        once. The sign-in page shows it.
      - `logout` stops being exported, so a bare `logout()` no longer compiles.
      - All five entry points call `signOut`. Delete the copy in `TermsAcceptView.vue` and `LoginView.vue`'s own
        function.
      - `signOut.ts`'s header drops what it says about `resetServerRole` and `clearActiveSpace`: the new document
        makes both redundant.
      - **Guard (permanent, in `npm run check`):** an ESLint `no-restricted-imports` rule for `**/*.vue` forbids
        importing the ending. A screen then cannot assemble its own sign-out, and the one it has to call is
        `signOut`. Its limit, stated in a comment beside the rule: a `.ts` composable is not covered, and neither
        is a screen that calls the session API's revoke directly.

      Tests: `signOut` itself (it has none today); the E4 path; and one test per entry point that it calls
      `signOut` (`TermsAcceptView` has no test at all today).

      **Bite:** re-insert the old `TermsAcceptView` sequence, importing the ending, and lint must go red.
      Snapshot and restore the file; don't use `git checkout`, per [changes/README](README.md) § *Bite-proof a
      guard by snapshot-and-restore*.
      · Done when: `npm run check` is green, and
        `grep -rn "logout()" web/src --include='*.vue' | grep -v '\.test\.'` finds nothing.
- [x] 3 web — **a rejected refresh ends the session.**
      - In `app/sessionRefresh.ts`, a 4xx from the refresh endpoint in a tab that holds a session drops the
        tokens and runs the ending.
      - A network failure or a 5xx keeps the tokens and rethrows. Today it drops them.
      - At boot the tab holds no session yet, so a rejected refresh stays boot's ordinary *not signed in* path.
        That is E2, and it is what stops the sign-in page from reloading itself.

      Tests: 401 → ending; a fetch `TypeError` and a 503 → tokens kept, no ending; a 401 with no session held →
      no ending.

      **Bite:** make the network error count as a rejection and its test must go red.
      · Done when: `npm run check` is green.
- [ ] 4 docs + coverage:
      - `uc.account.sign-out.yaml` loses `status: planned`. Each criterion cites the phase-1 to phase-3 tests.
        The backend gap stays, because the server half is `uc.account.login`'s `logout-revokes-family`.
      - uc.account.sessions E2 (revoking your own family) has no criterion of its own. `one-sign-out-sequence`
        names that entry point, so the `SessionsPanel` entry-point test is cited there.
      - In `docs/features/account/_index.md`, uc.account.sign-out moves from *not yet built* to built.
      · Done when: `python3 scripts/validate_coverage.py` and the whole trust-chain are green.

## Definition of done

- Every sign-out entry point runs `signOut`.
- A rejected refresh and another tab's announcement run the same ending.
- The ending ends in a document load, and its test proves that.
- The ESLint rule is the permanent guard against a screen-level copy. The compiler is the guard against a bare
  `logout()`, because it is no longer exported.
- `npm run check` is green; `validate_coverage.py` and the trust-chain are green.
- **A live check, because jsdom can't prove the one thing that matters.** Sign out in the running app
  (`veyq.localhost` with S3) with two tabs open and a video having played in one of them. Both tabs must land
  on the sign-in page. Check the service worker's state in the browser's devtools (Application → Service
  workers): no playback survives. Then run it again with the network offline: the E4 notice must show, and after
  reconnecting the sign-in card must come back signed in and offer its sign-out. Prefer a text check to a
  screenshot, and wait for a state, not a duration.

## Retrospective

