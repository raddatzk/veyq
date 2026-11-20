---
id: cr-app-background-sync
title: A login-level account syncs its pins and sends its queued writes on the system's schedule while the app is not running — Android's periodic work, iOS's app refresh, never at the presence level
status: planned
satisfies: []
adrs: ["0153", "0140", "0005", "0123", "0058", "0004"]
invariants:
  - "the Master Key opens in the background only where an app start at the login level opens it without asking (ADR 0123 decision 8); never at the presence level, never with the password trust, never by changing a key's protection class"
  - "a run resumes with the refresh token as a start does: no new session family; a refused refresh ends the record and wipes the store"
  - "the Master Key and every opened key are zeroed when the run ends"
  - "every rule is the core's; a host only schedules and hands the run its time"
  - "no word in an app but from src/mobile/words/words.json; each platform's guideline (ADR 0123 decision 9)"
  - "no pass signs in as the seeded principal: every pass is a throwaway's (ADR 0148 decision 12)"
---

## Goal

Inbox #896, ADR 0153 decision 8. Pins sync and queued writes go out only while the app runs with the account open
(ADR 0140 decision 9). After this CR, an account trusted at the login level with *Sync in the background* on is opened
by the system's schedule — Android's periodic WorkManager work, iOS's `BGAppRefreshTask` — refreshes its pins, sends its
queue and closes. The web has nothing like it. **Waits on owner question 1**: no phase starts before it is answered.

## Context & constraints

- **Read first:** ADR 0153 decision 8 and its § Context's platform lines, ADR 0123 decision 8 (the levels), ADR 0140
  decisions 4 and 9, `src/mobile/CLAUDE.md` § Nothing on disk (WorkManager's own records), § Prove your work.
- **Order:** after ADR 0153 decisions 1–5 (built 2026-11-20: `OutboxSender`, what a run calls).
- **Out of scope:** the desktops (ADR 0153 decision 8); the presence level and the password trust.

## Measured at refine (2026-11-19)

Branch `claude/offline-refine` at `4f3b57ecd`. `M=src/mobile`, `C=$M/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`.

| # | What | Where / command |
|---|---|---|
| R1 | The login level's keys per platform: Android no user-auth parameters; Apple `WhenPasscodeSetThisDeviceOnly`; refresh token `AfterFirstUnlockThisDeviceOnly`. | `sed -n 78,96p $M/android/src/main/kotlin/me/raddatz/veyq/mobile/android/KeystoreDeviceKeys.kt`; `sed -n 44,58p $M/apple/Veyq/KeychainStore.swift`; `grep -n "passcode\|protected" $M/core/src/appleMain/kotlin/me/raddatz/veyq/mobile/session/Apple.kt` |
| R2 | The resume an app start runs, and its offline twin. | `grep -n "fun resume\|fun resumeOffline\|fun reconnect" $C/session/Account.kt` |
| R3 | Today's workers hold a running account and open none. | `sed -n 20,95p $M/android/src/main/kotlin/me/raddatz/veyq/mobile/android/OfflineWork.kt`; `sed -n 1,40p $M/apple/Veyq/ContinuedWork.swift` |
| R4 | The pin sync's entry. | `sed -n 175,200p $C/offline/OfflinePins.kt` |
| R5 | WorkManager's floor: `MIN_PERIODIC_INTERVAL_MILLIS = 900000`. | `javap -constants` on `androidx.work.PeriodicWorkRequest` in `work-runtime-2.12.0.aar`'s `classes.jar` (the version in `$M/gradle/libs.versions.toml`) |
| R6 | iOS's requests and identifiers. | `grep -n "BGAppRefreshTaskRequest\|BGProcessingTaskRequest\|briefly\|favorable" $(xcrun --sdk iphoneos --show-sdk-path)/System/Library/Frameworks/BackgroundTasks.framework/Headers/BGTaskRequest.h`; `grep -n -A4 BGTaskSchedulerPermittedIdentifiers $M/apple/Veyq/Info.plist` |

## Decisions

ADR 0153 decision 8 holds them; beside it:

- **B1 — one core entry, `BackgroundRun.run(deadline)`**: for every remembered account whose record is `DEVICE_KEY` at
  `LOGIN` and whose switch is on, the start's resume (never the offline twin: a run without the server does nothing),
  a pin sync pass, the sender, close; stops at the deadline, leaving what is unfinished for the next run or the app.
  An account already open in the process is used, never opened twice (`OfflineStores.openShared`'s rule).
- **B2 — the switch:** per account on this device, in Settings' "Storage on this device", shown only at the login level;
  its default is owner question 2.
- **B3 — Android:** `enqueueUniquePeriodicWork("veyq-background-sync", UPDATE, …)` with `NetworkType.CONNECTED` and
  `setRequiresBatteryNotLow(true)`, cancelled when no account has the switch on; the worker runs `BackgroundRun` inside
  the worker's own time, no `setForeground`. Measured first: that a periodic worker started with the app dead can open the
  Keystore login key after a reboot and the first unlock, screen locked.
- **B4 — iOS:** `BGAppRefreshTaskRequest` (`app.veyq.background-sync`, in `BGTaskSchedulerPermittedIdentifiers`),
  rescheduled at each run and each app start; the expiration handler cancels the run; a Keychain refusal
  (`errSecInteractionNotAllowed`, the device locked) ends the run having done nothing.

## Phases

- [x] **1 core — `BackgroundRun`** (B1, B2) — Tests: a login-level account runs, a presence-level and a password-trusted
  one are skipped without a prompt (`SoftwareDeviceKeys.prompts` stays 0); a refused refresh ends the record and wipes;
  the deadline stops the run between writes; the keys are zeroed after (the account's `closed` state). · Done when:
  `:core:jvmTest :core:macosArm64Test` green. **Bites:** (a) the level check dropped → the presence test red (a prompt
  counted); (b) the offline resume used → the "no server, nothing done" test red.
  **Built** 2026-11-20 (`claude/bgsync`): `session/BackgroundRun.kt` — `run(deadline)` over `ThisDevice.backgroundAccounts()`
  (`BackgroundRun.runs`: DEVICE_KEY at LOGIN; `ThisDevice.backgroundRuns` false on the desktops), each through
  `VeyqAccount.resumeOnline` (now internal, `send = false`) or the account `OpenAccounts` holds open in the process (used,
  left open, an offline one reconnected first); before any request the kept wrap is tried under the **login** key
  (`keyOpens`: a closed key — a locked iPhone, B4 — ends as `KEY_CLOSED` with nothing sent, and is a second guard behind
  the level check); then the queue (`sendQueued(go)`, `OutboxSender`'s `go` asked between writes), **then** the pins
  (`withTimeoutOrNull` to the deadline) — the order reversed from B1 so a long pin fetch never holds the person's writes
  to the next run; the run's own account closed under `NonCancellable` (`retire`: no logout, the kept family stays). The
  account's close now zeroes every scope key it opened (`openedScopeKeys`) beside the Master Key and the identity. B2:
  `ThisDevice.backgroundSync`/`setBackgroundSync` (on by default, an off-list per account, cleared when the record is
  forgotten), `backgroundWanted` (a flow the hosts schedule by); `AccountSettings.State.backgroundSync` and
  `backgroundSyncMissing` (`account_settings_background_sync_missing` at the presence level or the password trust on a
  phone; nothing on a desktop). Words `account_settings_background_sync`, `_hint`, `_missing` (drafts, the owner's to
  change). `BackgroundRunTest` (7). Bites, each seen red then restored: (a) `runs` without the level → the presence test
  red ("a presence-level account has a switch"; no prompt is reached even so: the login-key probe refuses a presence
  wrap); (b) `VeyqAccount.resume` in place of `resumeOnline` → the no-server test red (an account opened offline);
  (c) no `retire` → the login test red (the account still open); (d) `go` not asked → the deadline test red (both
  writes sent).
- [ ] **2 bridge** — the switch on the wire (the desktops draw nothing; it is absent there). · Done when: `:bridge:jvmTest`
  green.
- [ ] **3 Android** — B3; the switch. First the measurement named in B3, stop-and-ask if the key does not open. · Done
  when: `:android:assembleDebug`, the guidelines and words guards green; a throwaway AVD run: the app force-stopped,
  `adb shell cmd jobscheduler run -f app.veyq <job id>` (or WorkManager's test driver) runs the work, a pin changed on the
  web arrives and a queued write is sent, the screen locked after a reboot and one unlock.
- [ ] **4 Apple** — B4; the switch. · Done when: both Apple builds green; the run on a real iPhone (the debugger's
  `_simulateLaunchForTaskWithIdentifier`) is the owner's, named open.
- [ ] **5 harvest** — ADR 0153's `Built:`; `native-apps.md`; `src/mobile/CLAUDE.md` § Nothing on disk (the periodic work's
  records); the Use page `getting-started/use-content-offline.mdx` EN+DE (the switch, and why it is missing at the
  presence level); `security/what-the-server-sees.md` (requests while the app is closed), `threat-model.md` (the run's
  window); inbox #896 deleted. · Done when: every guard but guard_gaps green.

## Owner questions

1. **May a login-level account be opened by the system's schedule, without the person starting the app?** It opens
   nothing a login-level start would not open, and nothing outside the app can start a run; the cost is the Master Key
   in memory for a run the person did not start, and requests the operator sees at times the app was closed. iOS will
   rarely run (its login key is closed while the phone is locked, and we keep it so). *Recommendation:* yes, at the login
   level only, as ADR 0153 decision 8 says; the alternative that would make iOS reliable (the key readable after the
   first unlock) is a weakening and stays rejected.
2. **The switch's default.** *Recommendation:* on for an account at the login level (it chose not to be asked; pins that
   go stale defeat "available offline"), off and absent elsewhere; the Use page says what it does.

**Answered (owner 2026-11-19): every question as recommended.**

## Definition of done

Phases 1–5 checked (after question 1's answer); every bite seen red and green; Android's run as a throwaway, iOS's named
open; the Use page EN+DE; every guard but guard_gaps green; `git diff --stat` over `src/backend/`, `src/web/` and
`contracts/` empty.

## Retrospective

_(filled at the end of the implementation)_
