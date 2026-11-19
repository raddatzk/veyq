---
id: cr-app-account-rest
title: The account flows' rest in the apps — the sign-in's Secret Key control in every flow that asks for the key, and the web's breached-password check straight from the device
status: in-progress
satisfies: []
adrs: ["0148", "0088", "0003", "0136", "0137", "0026"]
invariants:
  - "operator-blind E2EE: the breach check never goes through the veyq server — the device asks the address the Server Owner configured, as the browser does (security-model § the breached-password check: proxying is disallowed)"
  - "only a 5-hex SHA-1 prefix of the new password leaves the device, with Add-Padding; the password, its full hash and the Secret Key are in no request, no log, no store and no bridge update"
  - "the check advises, never gates: a hit is passed by *Use it anyway*, a check that cannot run passes"
  - "the Secret Key lives in the core's memory for the call only and is zeroed after it; none crosses the bridge outward (ADR 0148's Consequences)"
  - "the apps behave exactly like the web; only the look is the platform's (owner 2026-11-19)"
  - "no word in an app but from src/mobile/words/words.json"
  - "no pass signs in as the seeded principal or member: every pass is a throwaway's (ADR 0148 decision 12, `pnpm run throwaway:account` in src/web)"
---

## Goal

Inbox #970 and #969, the two account follow-ups block F left (ADR 0148's Consequences). One CR, because both change the
same three flows (the password change, the address change, the rotation's gate and new password) on the same four
screens per platform, the same bridge calls and the same Use pages, and one pass per platform proves both.

- **#970:** ADR 0148 decision 6 asks for the sign-in's Secret Key control — typed, pasted, a kit file, a scan — in the
  password change, the address change and the rotation's gate. Built, they take the key typed only, and a **pasted kit
  token is refused** there (R4) though the sign-in takes it.
- **#969:** the web warns of a new password found in known breaches; the apps check its length only.

## Context & constraints

- **Read first:** ADR 0148 (decisions 5, 6, 7, 10, 11, 12 and the Consequences), ADR 0088 decisions 7 and 12,
  `docs/crosscutting/security-model.md` § the breached-password check, the web's `src/web/src/shared/ui/SecretKeyField.vue`,
  `src/web/src/shared/pwned.ts`, `src/web/src/features/account/passwordWarning.ts` and its test,
  `src/mobile/CLAUDE.md` § Nothing on disk, § Each platform's guidelines, § Prove your work.
- **Where what runs** (from `src/mobile/`): `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest`;
  `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :android:assembleDebug`; the Apple builds after
  `./gradlew :core:assembleVeyqCoreDebugXCFramework`; `python3 linux/run.py test|build`; `python3 windows/run.py build`
  over ssh to the VM; live cases as a throwaway (`VEYQ_LIVE`, `VEYQ_LIVE_THROWAWAY_ENV`). **No iOS simulator boots on
  this Mac (#928)**; the macOS app runs here when the screen is unlocked (else named open); the Windows VM maps
  `tw-win.localhost` only (a throwaway with `--tenant tw-win`, as ADR 0148's Windows pass).
- **The row's premises re-derived** (R6–R8): the server has **no** range API (the device calls the configured address
  directly), and the capability is **instance-only**, not the tenant's. The apps' sign-in makes no check — and neither
  does the web's (R9), so that clause of #969 is no gap.
- **Out of scope:** the apps have no join, recover or first-run setup (R10); when one comes, it takes D5's check.

## Measured at refine (2026-11-19)

Branch `claude/refine-open` at `6de08a8d0`. `C=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`,
`B=src/mobile/bridge/src/commonMain/kotlin/me/raddatz/veyq/mobile/bridge`, `W=src/web/src`.

| # | What | Where / command |
|---|---|---|
| R1 | **The sign-in's control in the core:** `SessionModel.readKit(bytes, pictures, typedServer)`, `takeKit(found)`, `clearKit()`, `scanKit(typedServer, decode)`; the key held in a private `heldKey` only while the gate shows a kit; the bridge sees `KitShown(email, server, serverFrom, typedServer)`, no key; a typed field reads `EmergencyKit.read` (a pasted `veyqkit:` token) before a bare key. | `grep -n 'fun readKit\|fun scanKit\|fun takeKit\|fun clearKit\|heldKey' $C/session/SessionModel.kt`; `grep -n 'class Found\|suspend fun read' $C/account/KitImport.kt` |
| R2 | **The sign-in's platforms:** Android `OpenDocument(pdf, image/*)` + `KitScanner`; Apple `.fileImporter([.pdf, .image])` + `KitScannerView`; Linux `gtk::FileDialog` → `Session.SignInKit{path, server}` + `ScanView(ScanKind::Kit)`; Windows `FileOpenPicker` → the same call + `QrCamera.Open(ScanKind.Kit)`; the scan offered only where a camera is present. | `grep -n 'OpenDocument\|pickKit' src/mobile/android/src/main/kotlin/me/raddatz/veyq/mobile/android/AppRoot.kt`; `grep -n 'fileImporter\|KitScannerView' src/mobile/apple/Veyq/SignInView.swift`; `grep -n 'SessionSignInKit\|ScanKind' src/mobile/linux/src/sign_in.rs src/mobile/windows/Veyq/SignInView.cs` |
| R3 | **The flows take the key as bytes, the bridge as typed text:** `PasswordChange.run(…, secretKey: ByteArray)`, `AddressChange.request/kit(…, secretKey)`, `KeyRotation.prove(password, secretKey, …)`; the wire's `PasswordChange.Run`, `AddressChange.Request`/`.Kit`, `KeyRotation.Prove` carry `secretKey: String`, listed in `SecretFreeSchemaTest`'s `typedIn`. | `grep -n 'suspend fun \|secretKey: ByteArray' $C/session/PasswordChange.kt $C/session/AddressChange.kt $C/session/rotation/KeyRotation.kt`; `sed -n 1447,1490p $B/Wire.kt`; `sed -n 34,52p src/mobile/bridge/src/jvmTest/kotlin/me/raddatz/veyq/mobile/bridge/SecretFreeSchemaTest.kt` |
| R4 | **A pasted kit token is refused in the flows:** the bridge's `typedKey` is `parts.readKey(text) ?: ByteArray(32)` (`SecretKeyText` only); Android's and Apple's own `typedKey` likewise — a `veyqkit:` token becomes 32 zero bytes, refused as a wrong key. | `grep -n 'fun typedKey' $B/Bridge.kt src/mobile/android/src/main/kotlin/me/raddatz/veyq/mobile/android/AppModel.kt src/mobile/core/src/appleMain/kotlin/me/raddatz/veyq/mobile/account/AppleAccount.kt` |
| R5 | **The web's one control on all five screens:** `SecretKeyField.vue` (upload `application/pdf,image/*`, scan where `getUserMedia` exists, a pasted token swapped for its key), used by the password change, the address change and the rotation with `expected-email` — a kit of another account **warns** (`unlock.kitEmailMismatch`), never blocks; a kit of another server informs (`kitOtherServer`). | `grep -rn '<SecretKeyField' --include='*.vue' $W`; `grep -n 'mismatchedEmail\|kitOtherServer' $W/shared/ui/SecretKeyField.vue` |
| R6 | **No range API on the server:** the web asks the configured address straight from the browser, never through veyq; no contract operation, no backend handler. | `sed -n 1,10p $W/shared/pwned.ts`; `git grep -n -i 'pwned\|range/' -- src/backend/src/main contracts/api` → only the `serviceUrl` descriptions |
| R7 | **The capability is instance-only:** `core.breached-password`, source `external-service`, `levels: ["instance"]` ("the join and recover screens run it before sign-in"); its address is a `veyq_external_service` row seeded `https://api.pwnedpasswords.com/range/`, the Server Owner's to unset or mirror; served as `Capability.serviceUrl` in `getSetupStatus` and `getTenantCapabilities`. | `grep -n -A17 '"core.breached-password"' contracts/platform/capabilities.json`; `grep -n 'pwnedpasswords' src/backend/core/src/main/resources/db/migration/V202611070831__core_baseline.sql`; `sed -n 232,244p contracts/api/_common.openapi.yaml` |
| R8 | **The web's check:** SHA-1 of the UTF-8 password, upper hex; `GET <serviceUrl><first 5>` with `Add-Padding: true`; the suffix matched locally (a count-0 padding row never hits); a failure or non-2xx is `null` (unavailable), never 0; no timeout. Run on the field's **blur** and again on submit if edited, never while typing, nothing under 10 characters, one request in flight shared; states `idle`, `checking`, `clean`, `hit{count}`, `unavailable`, `accepted{count}`; `proceed()` passes clean, unavailable and accepted; a hit waits for *Use it anyway*; no address → no check, nothing asked. | `cat -n $W/shared/pwned.ts`; `cat -n $W/features/account/passwordWarning.ts`; `grep -n "it(" $W/features/account/passwordWarning.test.ts` |
| R9 | **Where the web checks:** first-run setup (admin-web), join, change password, recover, key rotation — not the sign-in. `security-model.md` says "four dialogs" (the rotation missing: stale). | `git grep -n 'checkBreaches\|breachCheckPasses()' -- 'src/web/src/**/*.vue' 'src/admin-web/src/**/*.vue'`; `sed -n 86,88p docs/crosscutting/security-model.md` |
| R10 | **The apps check length only**, in `PasswordChange` and `KeyRotation`; they have no join, recover or setup. | `git grep -n 'MIN_PASSWORD_LENGTH' -- src/mobile`; `git grep -c 'completePasswordRecovery\|redeemInvite\|acceptTenantInvite' -- $C` → none |
| R11 | **The core has no SHA-1** (libsodium has none; `Primitives` hashes SHA-256 only); it has its own SHA-256 (`export/Sha256.kt`, no dependency). | `git grep -n -i 'sha1\|sha-1' -- src/mobile/core` → none; `grep -n 'fun ' $C/crypto/Primitives.kt` |
| R12 | **The core's door to a third party:** `VeyqAccount.outside` (no bearer, no cookie) and `MapHttp` over it — https only, `User-Agent: veyq/<version> (+https://veyq.app; <instance origin>)`, no `Referer`, never throws; the instance manifest read by `AccountSettings` through `door.capabilities(): Set<String>` (`getSetupStatus`) — the ids only, no `serviceUrl`. | `sed -n 112,120p $C/session/Account.kt`; `sed -n 15,40p $C/map/MapHttp.kt`; `grep -n 'capabilities()' $C/session/AccountSettings.kt` |
| R13 | **The words:** the web's `password.pwnedWarning` / `pwnedAnyway` / `pwnedUnavailable` (EN+DE) and `unlock.kitEmailMismatch`; the apps have `signin_kit_other_server` and no breach or kit-mismatch word. | `git grep -n 'pwned\|kitEmailMismatch' -- $W/i18n/index.ts`; `grep -n -i 'pwned\|breach\|kit_email\|signin_kit_other' src/mobile/words/words.json` |
| R14 | **Each platform's flow screen** (one file each): Android `AccountSecretFlows.kt`, Apple `AccountFlows.swift`, Linux `account_flows.rs`, Windows `AccountFlows.cs`; a hint line under a field exists on each (`supportingText`, a `Text` in the Section, `dim(…)`, `Caption(…)`). | `git grep -l -i 'password_change_new\|PasswordChangeNew\|rotation_new_password' -- src/mobile \| grep -v words.json` |
| R15 | **The Use pages say both are missing:** `account/change-password.mdx` ("typed or pasted … not yet check … breaches"), `account/change-email.mdx`, `account/replace-your-keys.mdx`; the privacy page and the Run page say the check goes "from the browser". | `grep -n -i 'typed or pasted\|breach' site/src/content/docs/account/{change-password,change-email,replace-your-keys}.mdx`; `grep -n -i 'browser' site/src/content/docs/privacy/is-my-data-safe.md site/src/content/docs/run/data-protection.md \| grep -i -E 'breach\|pwned\|tells it'` |

## Decisions

Written into **ADR 0151** in phase 1 (the next number: 0147 is taken on another branch, 0150 is this branch's highest,
`git log --all --name-only --format= | grep -o 'docs/decisions/01[4-9][0-9]-' | sort -u` — re-run in phase 1, and take
the next free one if a neighbour took 0151 meanwhile). Its title: *The apps' account rest: the sign-in's Secret Key
control in every flow that asks for the key, and the breached-password check straight from the device.* ADR 0148's
Consequences lines on #969 and #970 are pointed at it.

### D1 — One key intake, the sign-in's, held by each flow

The sign-in's kit handling moves out of `SessionModel` into a core **`account/KeyIntake`**: the held key, `readKit(bytes,
pictures)`, `scanKit(decode)`, `takeKit(found)`, `clear()`, the shown `KitShown`, and **`typed(text)`** — a `veyqkit:`
token first, then a bare key (the web's paste swap, R5; this fixes R4). `SessionModel` keeps its behaviour on top of it
(its existing tests the refactor's proof). `PasswordChange`, `AddressChange` (request and kit step) and `KeyRotation`
(gate) each own one intake: their actions read the key from it instead of a parameter, and the intake zeroes the key after
the call and when the flow closes. Rejected: one intake per account shared by the flows — a key would outlive the flow
that asked for it.

### D2 — A kit of another account warns, as the web

Given the account's address, the intake states the web's warn-only mismatch (`kit_email_mismatch`, the web's
`unlock.kitEmailMismatch`), and a kit of another server the existing `signin_kit_other_server`. Neither blocks: only
the wrap can judge a key (the web's reason, `SecretKeyField.vue`'s header).

### D3 — The bridge carries a path in and the shown kit out, never the key

`Kit.Read(flow, path)` and `Kit.Clear(flow)` for a flow's intake (the core reads the path capped at `KitImport.FILE_CAP`
and draws through the host's existing `PdfPages`/`PdfPage`/`PictureLuma` requests, as `Session.SignInKit`); `ScanKind`
gains `FLOW_KIT`, routed by `Scan.Open`'s `link` to the flow's intake. Each flow's `Update` carries `kit: KitShown?`.
The typed `secretKey` fields stay (empty while a kit is held); `SecretFreeSchemaTest`'s `typedIn` gains nothing.
Android and Apple, which call the core directly, drop their own `typedKey` for the intake's `typed`.

### D4 — Each platform offers the sign-in's controls in the flows' Secret Key section

The same file picker, scanner and camera rule as the platform's sign-in (R2), under the flow's Secret Key field; a held
kit shows as at the sign-in (the address, *Use another*). Phone flows keep their secure window (ADR 0148 decision 10).

### D5 — The breached-password check: the web's, ported into the core

`account/BreachCheck` ports `useBreachCheck` and `pwned.ts` exactly (R8): the same states, the same triggers (a check
when the field is left and again at submit if edited, never while typing, nothing under `MIN_PASSWORD_LENGTH`, one
request in flight), the same `proceed()`. It runs on the **new password** of `PasswordChange` and of `KeyRotation` when
one is chosen. SHA-1 is a pure-Kotlin common `account/Sha1` (the `export/Sha256.kt` form; no dependency — libsodium has
none, R11), pinned by FIPS 180-4's examples and the web test's oracle; it is no suite primitive and gets no vector.

### D6 — Where it asks, and what that tells whom

The address is the instance manifest's `core.breached-password` `serviceUrl` (`getSetupStatus`, read when the flow opens — a
read of the address beside `AccountSettings`' ids, R12) — instance-only, so the tenant's set adds nothing. None: no check and no
notice, as the web. The request leaves through the core's one third-party door — `MapHttp` generalised to
**`OutsideHttp`** (the account's `outside` client, https only, the app's `User-Agent` naming the instance's origin, no
`Referer`, no cookie, no bearer), the map its first user, unchanged — with `Add-Padding: true`. **The operator learns
nothing** (never proxied, R6); the range service (Have I Been Pwned by default, the Server Owner's mirror, or none) learns
a 5-hex prefix, the device's address and the instance's origin — what the browser's `Origin` tells it. The switch is the
Server Owner's alone (instance-only, R7); the web offers a person no switch, so the apps offer none. Self-hostable as
today: an instance without internet unsets it and the apps simply do not check.

### D7 — The bridge for the check

`PasswordChange.Check(flow, newPassword)` and `KeyRotation.Check(flow, newPassword)` — the password typed in, listed in
`typedIn` — sent when the field is left; `…Edited(flow)` on any edit (no text); `…UseAnyway(flow, on)`. Each flow's
`Update` carries `breach: BreachShown?` (the state and the count). The submit calls are unchanged; the core refuses a
submit `proceed()` does not pass (the core decides, the platform only draws).

### D8 — Words

New in `src/mobile/words/words.json`, the web's text in EN and DE: `password_pwned_warning` (plural over the count, the
web's `password.pwnedWarning`), `password_pwned_anyway`, `password_pwned_unavailable`, `kit_email_mismatch`; the kit
controls reuse the sign-in's words. No owner question: the words are the web's.

## Phases

- [x] **1 ADR 0151 and the docs it binds** — ADR 0151 (D1–D8), its row in `docs/decisions/README.md`; ADR 0148's
  Consequences lines on #969/#970 pointed at it; `security-model.md` § the breached-password check corrected to the
  web's five screens (R9) plus the apps' two, and the apps' `User-Agent` beside the browser's `Origin`;
  `native-apps.md` § Open's line narrowed; `uc.account.change-password`, `uc.account.change-email` and
  `uc.account.rotate-keys` gain the apps in their alternate flows. · Done when: every guard but guard_gaps green
  (`/usr/bin/python3 scripts/validate_*.py`).
  **Built 2026-11-19 (branch `claude/acct-rest`):** ADR 0151 was free (re-measured); every guard but guard_gaps green.
- [x] **2 core — the key intake** (D1, D2) — `KeyIntake`; `SessionModel` on it (`SessionModelTest`'s kit cases and
  `LiveSignInTest` unchanged and green: the refactor's proof); the three flows on their own intake. Tests: a pasted
  `veyqkit:` token opens each flow (the R4 defect: written first, red on the untouched tree, then green); a kit read in a
  flow; another account's kit warns and proceeds; the key zeroed after the call and at close. **Bites:** (a) `typed`
  reading `SecretKeyText` first → the pasted-token test red; (b) the zeroing removed → the test that inspects the
  intake's held bytes after `run` red.
  **Built 2026-11-19 (branch `claude/acct-rest`):** `account/KeyIntake` (`typed`, `key`, `read`, `take`, `scan`,
  `clear`, `spent`; `IntakeState`/`HeldKit` — names unique for the bindings); `SessionModel` on it; each flow's `intake`, its
  actions taking the field's text (the byte overloads `internal`); the bridge, Android and Apple pass the text (their
  `typedKey` gone). The R4 test (`AccountFlowsDispatchTest.aPastedKitTokenOpensEachFlow`) red on the untouched tree
  (`PASSWORD_CHANGE_ERROR_CREDENTIAL`), green after; `FlowKeyIntakeTest` the core's. Bite (a) as written cannot bite —
  reading `SecretKeyText` first still falls through to the token, which no bare-key reader accepts — so it was run as
  `typed` reading `SecretKeyText` **only** (the R4 defect): red; (b) red (and the kit-file case with it).
- [x] **3 core — the breach check** (D5, D6) — `Sha1`, `OutsideHttp` (`MapHttp`'s tests unchanged: the refactor's
  proof), `BreachCheck`, wired into `PasswordChange` and `KeyRotation`. Tests: the web test's cases one for one (a mock
  engine: nothing while typing, one padded request on leaving the field, no re-query, one in flight, a re-query at
  submit after an edit, nothing under the floor, a hit blocks until accepted, an edit clears the acceptance, unavailable
  and non-2xx pass, no address no request). **Bites:** (a) `Add-Padding` dropped → red; (b) **the canary**: the mock
  engine records every request of a flow and the test asserts no request holds the password, its full SHA-1 or its
  suffix, and none carries `Authorization` or goes to the veyq server — bitten by sending the full hash, and by sending
  through the bearer `http` client; (c) `proceed()` passing a hit → red; (d) unavailable blocking → red.
  **Built 2026-11-19 (branch `claude/acct-rest`):** `account/Sha1` (FIPS 180-4's examples, the padding edges, the web's
  oracle), `session/OutsideHttp` (`MapHttp` renamed and moved, the map's tests unchanged but for the name),
  `account/BreachCheck` (`BreachState`/`BreachKind`; the password it speaks about `@Transient`, never printed),
  `BreachCheck.of(account)` wired into `passwordChange()` and `keyRotation()`; each flow's `check`, `edited`,
  `useAnyway`. `BreachCheckTest` (the web test's cases), `BreachCanaryTest` (a signed-in account over `PlayedTenant`).
  Bites (a)–(d) each red, (b) both ways.
- [x] **4 bridge** (D3, D7) — the calls, the updates, `ScanKind.FLOW_KIT`; the generated Rust and C# bindings.
  · Done when: `:bridge:jvmTest` green with `SecretFreeSchemaTest` holding the new `typedIn` entries. **Bite:** a
  flow's `Update` serialised with the held key or the new password → the test that scans every update of a flow session
  for their canaries red.
  **Built 2026-11-19 (branch `claude/acct-rest`):** `Kit.Read(flow, path)` (`NO_KIT` where none read), `Kit.Clear(flow)`,
  `ScanKind.FLOW_KIT` (`Scan.Open`'s `link` the flow), `PasswordChange.Check/Edited/UseAnyway`,
  `KeyRotation.Check/Edited/UseAnyway`; `Update.PasswordChange`/`.KeyRotation` carry `kit: IntakeState?` and
  `breach: BreachState?`, `Update.AddressChange` `kit`; a flow's `*.Open` reads the range address; the password and
  address flows close their intake. `:bridge:generateBindings` writes the new types under their own names (each simple
  name new, none renamed). `AccountFlowsDispatchTest.aFlowSessionTakesTheKitByPathAndChecksTheNewPasswordAndSendsNeither`
  scans every update for the key's forms, the token, the new password and its SHA-1 — bitten by serialising the check's
  password (red); `SecretFreeSchemaTest` red without the two new `typedIn` entries. The words (D8) are left to the
  platform phases.
- [ ] **5 Android** (D4, D5's triggers) — the kit picker and scanner in the three flows; the check on focus loss, its
  notice under the new password (`supportingText`) and *Use it anyway* (a checkbox row). · Done when:
  `:android:assembleDebug` and the guidelines and words guards green; § Passes' Android row.
- [ ] **6 Apple** — the same (`.fileImporter`, `KitScannerView`, `@FocusState`, a `Toggle`). · Done when: both Apple
  builds green; the macOS row run here or named open; iOS the owner's.
- [ ] **7 Linux** — the same over the bridge (`gtk::FileDialog`, `ScanView`, the entry's focus-leave controller, an
  `adw::SwitchRow`/check row). · Done when: `python3 linux/run.py test` and `build` green; § Passes' Linux row.
- [ ] **8 Windows** — the same (`FileOpenPicker`, `QrCamera`, `LostFocus`, a `CheckBox`). · Done when:
  `python3 windows/run.py build` green on the VM; § Passes' Windows row.
- [ ] **9 the passes** — § Passes, each a throwaway, erased after; results into ADR 0151's Consequences.
- [ ] **10 harvest** — the Use pages EN+DE: `account/change-password.mdx`, `account/change-email.mdx`,
  `account/replace-your-keys.mdx` (*In the app*: the kit file and the scan; the breach check), `privacy/is-my-data-safe.md`
  (the app names the instance in its `User-Agent` where the browser sends its `Origin`; the rotation among the screens),
  `run/data-protection.md`'s row ("from the browser or the app"); `native-apps.md` § Built; #969's and #970's rows
  deleted; this CR deleted. · Done when: `pnpm run check` in `site/` and every guard but guard_gaps green.

No contracts phase: no endpoint, field or vector changes (R6; D5's SHA-1 is no suite primitive).

## Passes

| Pass | Who | What it proves |
|---|---|---|
| Android AVD, a throwaway | agent | the rotation's gate opened with the throwaway's kit **file** (made by the web's kit or the address change's kit step), the new kit saved; the password change with the new kit **scanned** from the screen of the host (the emulator's virtual scene) and with its token **pasted**; a known-breached new password (a common one of ≥ 10 characters) warns with the count, the submit waits for *Use it anyway*, then succeeds; a clean one passes silently; with the emulator offline the notice says it could not be checked and the change goes on; afterwards `run-as app.veyq` greps the app's files and `logcat` for the password, the Secret Key and the full hash: none |
| Linux (Flatpak build), a throwaway | agent | the same over the bridge, the kit through GTK's dialog and the scan through the test camera (block D's) |
| Windows VM, a throwaway on `tw-win.localhost` | agent | the same; if the VM reaches no range service, the *could not be checked* notice is the measured result and the hit is named open |
| macOS, a throwaway | agent (screen unlocked) | the kit file through the open panel in each flow, a breached password warned |
| iPhone and iPad | **owner** | the kit scanned with the real camera in the password change; a breached password warned |

## Owner questions

None. The behaviour and the words are the web's (R5, R8, R13); where the request goes and what it tells is the web's
rule (R6, R7) applied to the app (D6), and the switch stays the Server Owner's as on the web.

## Definition of done

Phases 1–10 checked; every bite seen red and green; the passes run as throwaways and erased, the owner's named open
where not run; the Use pages in EN and DE; every guard but guard_gaps green. `git diff --stat` over `src/backend/`,
`src/web/` and `contracts/` is empty.

## Retrospective

_(filled at the end of the implementation)_
