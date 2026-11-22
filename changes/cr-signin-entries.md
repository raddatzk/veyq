---
id: cr-signin-entries
title: The apps' sign-in gains the web's locked entry (sign every device out without the Secret Key) and Forgot your password (the recovery link asked for), in the core and on all four apps
status: in-progress
satisfies: ["uc.account.sessions", "uc.account.recover-access"]
adrs: ["0154", "0148", "0150", "0054", "0055", "0058", "0004", "0136"]
invariants:
  - "the locked sign-in reads no key material, opens no wrap, enrols no trust and keeps nothing on the device; its tokens live in memory only (uc.account.sessions E3)"
  - "the locked sign-out revokes every other family first and only then signs this device out; a revoke that fails signs nothing out (uc.account.sessions E3)"
  - "the way back's sign-in enrols no trust and makes no offer (uc.account.sessions E3)"
  - "the recovery ask answers the same for every address and says only a tenant's missing mail apart (ADR 0054 §6, ADR 0055 §4)"
  - "no endpoint, field or server behaviour is added: the apps send what the web sends"
---

The follow-up ADR 0154 § As built: the plans aligned, P9 names. Phases: core (plans, `SessionModel`, `VeyqAccount`,
tests) → bridge → Android, Apple, Linux, Windows → harvest into ADR 0154, ADR 0148 and the use-cases, then this CR is
deleted.

## Decision log

1. **The link's half of the recovery stays the web's.** The token arrives only in the mailed link, which opens the web's
   `/recover` page (ADR 0054's fragment); ADR 0150 § O rejected a custom scheme or an app link carrying a secret, and the
   owner rejected pasting a mailed link into the app for the address change (ADR 0148 decision 6, 2026-11-18). So the
   app asks for the link (`requestPasswordRecovery`, the web's `requestRecovery`) and says that the browser finishes the
   reset with the kit; the web's page offers *Open in the veyq app* after it (ADR 0150 § O2). Porting release, open and
   complete into the core would be dead code without a transport no ADR allows — an owner question (inbox #1106).
2. **The locked sign-in is the login alone** (`VeyqAccount.signInLocked`): `connect` without the device (not even the
   instance salt is kept), `login`, no `key-material`; a `LockedSession` holds the tokens in memory and renews an expired
   access token once over its refresh token.
3. **The way back signs in anew.** The web unlocks over the session the locked entry holds; the app's sign-in is one
   login and unlock, so the way back signs the locked session out first (best effort) and the sign-in after it
   (`Gate.SignIn.keepsNothing`) enrols no trust and makes no offer. One family fewer stays live; nothing is kept either way.
   Traces the way back's sign-in still leaves: the register's device entry and family join (`recordThisDevice`, as the
   web names `noteThisDevice`) and the instance salt (`keepInstanceSalt`, public).
4. **A sign-out of this device that fails keeps the session and says so** (`locked_done_still_here`, app-own: the web
   points at its card's own Sign out, which the app has not); the action runs again (the revoke is idempotent).
5. **Two plans, not fixed buttons**: `LockedPlan` (sign out everywhere, back) and `RecoverPlan` (send until sent, back),
   each `@Plan`, so every platform draws them whole and the structure guard holds them.
6. **Words**: eighteen twins of the web's `sessions.locked*` and `recovery.*`; `locked_scope_note` and
   `locked_done_still_here` apart (a browser's cookie and its toggle, a card's Sign out); `recovery_on_web` app-only.
