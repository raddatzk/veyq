---
id: cr-app-account-settings
title: The apps' Settings — the account, this device, devices and sessions, sign out; the entry to "Add a device"
status: planned
satisfies: []
adrs: ["0004", "0005", "0058", "0059", "0122", "0130", "0131"]
invariants:
  - "every rule is the core's (ADR 0130 decision 1): the Settings page is one core screen model per signed-in account, each app only draws it"
  - "a session family carries no name on the server (ADR 0004): names come from the Master-Key-sealed device register, joined on the device; a locked or unregistered family shows its dates"
  - "a revoke never takes key material (ADR 0058): signing a device out and removing its saved key are two named steps, as on the web"
  - "the bridge only carries — no key, seal, token or password outward (SecretFreeSchemaTest)"
  - "no word in an app but from src/mobile/words/words.json; each app follows its platform's guideline (ADR 0123 decision 9)"
  - "the web and the apps' existing screens (Manage servers, the sign-in, the offer) behave as before; core, bridge and both desktops' checks green in every phase"
---

## Goal

The owner wants sign-in finished on every app before Memories (owner 2026-11-12). Every app has a **Settings** row in
its drawer that does nothing (`native-apps.md` § Account). This CR makes it the account's settings page, on Android,
iOS, macOS, Linux and Windows, holding what signing in needs around it:

- **Account** — the email address this account signs in with, and whether it is confirmed;
- **This device** — its name (renamed here), how it stays signed in (the trust level `Manage servers` already offers,
  the same core state), "Stop trusting this device";
- **Devices and sessions** — every active session family of the account, named from the device register, this one
  marked; sign one out; sign out every other; "Sign out and remove saved key"; the devices with a saved key and no
  session, each removable; "Remove every saved key except this device's" — the web's `SessionsPanel`, the behaviour
  `uc.account.sessions` owns;
- **Add a device** — the entry `cr-app-device-linking` fills (ADR 0059 §1: on the unlocked device, in settings);
  offered only while the instance has `core.device-linking`;
- **Sign out** of this account on this device.

What the web's settings hold beyond that (password, email change, key rotation, erasure, profile, maps, moderation,
the trust reset) is **not** in this CR; see Owner question 1.

## Context & constraints

- **Read first:** `uc.account.sessions` (the list, the two layers, E1–E5, § Devices with a saved key),
  `uc.account.sign-out`, `uc.account.trust-device`, `uc.settings.appearance-language`, `en.account.device-register`;
  ADR 0004 (§ Session family), ADR 0058 (§5, why "every other saved key" is defined by exclusion), ADR 0130, ADR 0131,
  `src/mobile/CLAUDE.md` whole.
- **The web's reference:** `src/web/src/features/account/sessions/SessionsPanel.vue`, `deviceRegister.ts`,
  `savedKeys.ts`, `trustDevice/DeviceTrustControl.vue`, `login/signOut.ts`, `src/web/src/features/settings/sections.ts`
  (the gates), `src/web/src/views/SettingsHubView.vue`.
- **Where what runs:** as in `cr-core-qr-decoder` § Context. The live tests sign a **second** session of the dev
  principal in (a core login, its own family) and revoke only that one; the principal's own family and the owner's
  browser sessions are never revoked. "Sign out every other" is driven only against a throwaway account the test
  creates through the dev seeding (`bootstrap:user`), never the principal.

## Measured at refine (2026-11-12)

Commands from the repo root unless named.

| # | What | Where / command |
|---|---|---|
| R1 | **The drawer's Settings row exists and does nothing** on all four shells: Android's `Row(... drawer_settings ...) {}`, Apple's `say(.drawerSettings)`, Windows' `_settings` with `SelectsOnInvoked = false`, Linux's `ActionRow`. | `grep -rn -i 'drawer_settings\|drawerSettings\|DrawerSettings' src/mobile/android/src src/mobile/apple/Veyq src/mobile/windows/Veyq src/mobile/linux/src` |
| R2 | **`Manage servers` already holds per account:** tenant/space visibility, the trust level and its change, "Back to your password", "Stop trusting this device", Sign out, Add server — `SessionModel.managed()` with `ManagedServer`/`ManagedTenant`, drawn by `ManageScreen.kt`, `ManageView.swift`, `manage.rs`, `ManageDialog.cs`; bridged as `Manage.*` and `Watched.Manage`. | `grep -n 'class Managed\|fun managed\|fun signOut\|fun forgetDevice' src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/session/SessionModel.kt`; `grep -n '"Manage\.' src/mobile/bridge/src/commonMain/kotlin/me/raddatz/veyq/mobile/bridge/Wire.kt` |
| R3 | **The core already writes the device register** (`DeviceTrust.kt` `remember(deviceId, label, familyId, wrapId, now)`, at each unlock and enrolment), revokes its own family on sign-out and deletes its own wrap; **it never lists families**, never reads another device's entry, never removes another device's wrap. | `grep -rn 'listSessionFamilies\|revokeSessionFamily\|revokeOtherSessionFamilies\|deleteAccountWrap\|getAccountKeyMaterial\|getAccountEmail' src/mobile/core/src/commonMain` |
| R4 | **The API has every call:** `listSessionFamilies` (GET /api/sessions, a `current` flag per family), `revokeSessionFamily`, `revokeOtherSessionFamilies`, `logout`; `getDeviceRegister`/`putDeviceRegister`; `getAccountKeyMaterial`, `deleteAccountWrap`; `getAccountEmail`; `listInstanceCapabilities`. No contract or backend change. | `grep -n 'operationId: \(listSessionFamilies\|revokeSessionFamily\|revokeOtherSessionFamilies\|logout\)' contracts/api/auth.openapi.yaml`; `grep -n 'operationId: \(getDeviceRegister\|getAccountEmail\|deleteAccountWrap\|getAccountKeyMaterial\)' contracts/api/account.openapi.yaml`; `grep -n 'operationId: listInstanceCapabilities' contracts/api/instance.openapi.yaml` |
| R5 | **Theme and language follow the system on every app**, with no picker: Android `isSystemInDarkTheme`, Apple `Theme.swift`, libadwaita's style manager, Windows the system theme; words from `words/words.json` by the system locale. The web offers both as a choice (`AppearancePanel.vue`). | `grep -rn 'isSystemInDarkTheme' src/mobile/android/src`; `grep -n 'VEYQ_THEME\|VEYQ_LANGUAGE' src/mobile/windows/Veyq/DebugEnv.cs` |
| R6 | **The Use area's pages describe the web only:** `account/devices-and-sessions.mdx` and `account/appearance-and-language.mdx` (each with its `de/` twin) name no app. | `git grep -li 'devices and sessions\|Geräte und Sitzungen' -- site/src/content/docs`; `ls site/src/content/docs/account` |

## Decisions

### D1 — One core screen model, `AccountSettings`, per signed-in account

`session/AccountSettings.kt` in commonMain (ADR 0130 decision 2's shape, tested in `commonTest` over a played door):
- **State:** the email and its confirmation; this device (label, its family id, the trust level and the levels it can
  move to — read from the same source `Manage servers` reads, not a copy); the families (id, created, last used,
  `current`, the register's label or none); the devices with a saved key and no session; whether "Add a device" is
  offered (`core.device-linking` on the instance); a working flag and the last refusal.
- **Actions:** `rename(label)` (this device's own register entry only — the single-writer rule of
  `en.account.device-register`), `signOut(family)`, `signOutOthers()`, `signOutAndRemoveKey(family)`,
  `removeSavedKey(device)`, `removeEveryOtherSavedKey()`, `signOutHere()`, `refresh()`, and the trust changes that
  `Manage servers` already has, delegated to it.
- **Locked is not a case here:** the page is reached from the drawer of a signed-in, unlocked account. A locked
  device's "sign every device out" (uc.account.sessions E3) stays the sign-in screen's and is not in this CR.
- **The orderings are the web's and the use case's:** "Sign out and remove saved key" revokes first, then deletes the
  wrap, and a half-done escalation (E5) leaves the device in "devices with a saved key" for the retry; signing this
  device's own family out is a sign-out here (E2); an already-gone family (E1) is refreshed away without an error.

### D2 — Settings is the account's; `Manage servers` stays the list of accounts

`Manage servers` holds every account on the device, locked ones included, with what works without an unlock (the
visibility switches, Sign out, Forget). Settings holds one account, unlocked, with what needs the vault (the register's
names) or a session (the list). The trust level appears in both, as one core state with two entry points; nothing of
`Manage servers` moves in this CR. Two homes for one control were weighed against moving the trust row out of Manage:
moving it would leave a locked account's "Stop trusting this device" nowhere, which is where it is needed most.

### D3 — Theme and language keep following the system

Apple's HIG and Material 3 both have an app follow the system's appearance; Android 13+ and iOS keep a per-app
language in the system's settings. So the page gets an **"Appearance and language"** row only on the phones, which
opens the system's per-app page (`Settings.ACTION_APP_LOCALE_SETTINGS` on Android with a `locales_config`,
`UIApplication.openSettingsURLString` on iOS); the desktops show neither, and `uc.settings.appearance-language` stays
the web's. Whether the desktops get an in-app choice is Owner question 2.

### D4 — The bridge and the desktops

`Settings.Open(connection)` → `Answer.SettingsOpened(settings)`, one call per action (D1), `Settings.Close`,
`Watched.Settings(settings)` → `Update.Settings(shown)`; a family's id, dates, label and `current` are plain fields,
no token. Linux draws it as an `AdwPreferencesDialog` page set (as `manage.rs` does), Windows as a page in the shell's
`NavigationView` content (the settings item's own page, WinUI's guideline), Android as a full-screen destination,
Apple as a pushed view (iOS) and a Settings scene-style window (macOS `Settings { }`).

## Phases

- [ ] **1 the core** — `AccountSettings` (D1), its `commonTest` (each action, the E1/E2/E5 paths, the label join, a
  family the register does not know shown with its dates, `Add a device` gated on the capability), and
  `LiveAccountSettingsTest` (`VEYQ_LIVE`): a second login of the principal is listed, renamed through the register,
  signed out, and gone from the list; the principal's own family still refreshes afterwards (the control). · Done when:
  `./gradlew :core:jvmTest :core:macosArm64Test` green and the live test green with `VEYQ_LIVE=http://veyq.localhost:8080`;
  a bite — `signOutOthers` that also revokes `current` — turns the live control red.
  **Status: built and unit-proven (2026-11-13); the live run is pending (the dev backend was down, not to be started by
  this phase) — the box stays open until it is green.**
  **Built:** core `session/AccountSettings.kt` — `AccountSettings<A, T>`, one per signed-in account, `state` (a
  `StateFlow` of a `@Serializable` `State`, ready for phase 3's bridge) and `watch`; state: the email and `emailConfirmed`
  (the server's copy, `getAccountEmail`; the account's own address until read), `Device(label, familyId, Trust)`,
  `families` (id, created/last used as epoch ms, `current`, the register's label or null, `removesKey`), this device
  first, `savedKeyDevices`, `otherSavedKeys`, `addDevice`, `working`, `refusal`, `registerRefusal`, `hasOthers`; actions as
  D1 names them, plus `stopTrusting()` (Manage's `forgetDevice`). Two seams: `Door` (the server; `Over` is the real one
  over `VeyqAccount`, every call through the generated client) and `Here` (`Held` over `SessionModel`: the trust read
  from `managed()` — Manage servers' source — and `stay`/`trustWithPassword`/`forgetDevice`/`signOut` delegated);
  `AccountSettings.of(session, account)` is the platforms' entry. `refresh` is the web's loader: the list first, then the
  register half reconciled (write moment 3, which also joins the server's `current` family to this device — a resume never
  learns its family id at a login) and read, never able to take the list down (E4; a refused register is named, an
  unreadable wrap list prunes nothing and offers no wrap action). `DeviceRegisterStore` gained `reconcile` (deltas frozen
  from the first read, as the web's), `rename` (this device's own entry only), the pure `view` (the web's
  `readRegisterView`) and one shared revision-guarded `write`, which `remember` now uses unchanged in behaviour.
  `VeyqAccount.register()` became internal and `thisDevice` was added. Seven `account_settings_*` refusal words in
  `words/words.json` (the web's English and German; "your memories" said as "your data", the app holds every application).
  **Deviation:** "Add a device" reads `core.device-linking` from `GET /api/setup`'s manifest (the web's
  `hasCapability`), not `listInstanceCapabilities` as R4 named — that one is the admin channel, Server-Owner only.
  **Met:** `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest :core:compileKotlinLinuxX64
  :core:compileKotlinMingwX64` green (`AccountSettingsTest` 14 cases on the JVM and macosArm64); the guards
  (`scripts/validate_*.py` but guard_gaps) green. **Bites** (one edit each, `/bin/cp -f` restore): `signOutOthers` also
  revoking `current` → `signingOutEveryOtherKeepsThisOne` red; the escalation's removal before its revoke →
  `theEscalationRevokesFirst…`, `aFailedRevokeIsSaid…`, `aHalfDoneEscalation…` red; `others` without this device's wrap
  excluded → `everyOtherSavedKey…` red; the reconcile's family prune unfrozen → `aReconcileRetriedAfterAConflict…` red;
  `addDevice` on any capability → `addADeviceIsOffered…` red; the register's label join removed → `theListIs…` and
  `aRenameIs…` red. `LiveAccountSettingsTest` compiles and **reports as skipped** without `VEYQ_LIVE` (and its "every other" case without
  `VEYQ_LIVE_OTHERS_ENV`): a JUnit `Assume` before `runTest` (`needs(name)`), not an early return, which had shown both
  cases as passed (fixed 2026-11-13 by `cr-app-kit-sign-in` phase 4; the test XML: `tests="2" skipped="2"`). **Pending, live:**
  `VEYQ_LIVE=http://veyq.localhost:8080 ./gradlew :core:jvmTest --tests '*LiveAccountSettingsTest*'` (from `src/mobile`;
  `VEYQ_ENV_FILE=<main checkout>/.run/.env` from a worktree), and the "every other" case against a throwaway account the
  dev seeding made: `VEYQ_LIVE_OTHERS_ENV=<its env file>` added — never the principal; then the live bite (`signOutOthers`
  also revoking `current`) must turn that case's control red.
  **Retrospective:** R4's `listInstanceCapabilities` was a refine slip a read of its `x-veyq-channel: admin` would have
  caught; a capability a member's screen gates on is always `/api/setup`'s (or the tenant's) manifest. The CR's live bite
  needs "every other" driven live, which its own Context reserves for a throwaway account — so the bite lives in the
  throwaway case, not the principal's.
- [ ] **2 Android and Apple** — the drawer row opens the page (D3, D4's phone forms); the words added to
  `words/words.json` with German. · Done when: both builds green; on the throwaway AVD, the simulator and the macOS build
  the page is opened, this device renamed, a second session (signed in by the live test's helper) signed out, and
  "Sign out" taken; each pass recorded in `native-apps.md`'s measured list.
- [x] **3 the bridge** — D4's calls with `WireTest` samples, `DispatchTest` (open, an action, close, a tenant switch
  closing it) and `SecretFreeSchemaTest` unchanged. · Done when: `./gradlew :bridge:jvmTest` green.
  **Status: built and proven over played doors (2026-11-13); the live case is pending (the dev backend was down).**
  **Built:** `Wire.kt` — `Settings.Open(connection)` → `Answer.SettingsOpened(settings)` (refused `NO_SUCH_CONNECTION`, or
  `NO_TENANT_OPEN`: the page is reached from an open tenant's drawer), `Settings.Close/Refresh/Rename/SignOut/SignOutOthers/
  SignOutAndRemoveKey/RemoveSavedKey/RemoveOtherSavedKeys/StaySignedIn/PasswordTrust/StopTrusting/SignOutHere` (each answered
  once it ended; why it did not come through is the state's `refusal`; the trust calls answer as `Manage.*`'s), `Watched.Settings`
  → `Update.Settings(settings, SettingsShown?)`, `Why.NO_SUCH_ACCOUNT_PAGE`. `SettingsShown` (with `SettingsFamily`,
  `SettingsKeptDevice`) is built field for field from `AccountSettings.State` (`hasOthers` included; the device's family id,
  which no UI draws, left out) — not the core's state sent as it is, whose nested `State`/`Device`/`Trust` names would be the
  hosts' type names. `Bridge.kt`: `Composition.settings` (`AccountSettings.of` in `live`), the pages by id
  (`HeldAccount`, closed with the tenant: its watch then delivers null once), the core reads the page as it opens; signing
  this device's own family out, or `SignOutHere`, closes the page and runs `Session.SignOut`'s way in. Core: `AccountSettings`'
  constructor, `Door` (now an abstract class whose `register()` stays internal — a door without one shows no names) and `Here`
  became public, so the bridge's tests play them. **Deviation:** `SecretFreeSchemaTest` is not unchanged: two flags are
  named like a key (`SettingsShown.otherSavedKeys`, `SettingsFamily.removesKey`) and `Settings.PasswordTrust.password` is typed
  in — each allowed by name with why, as its rule asks.
  **Met:** `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` green; `DispatchTest`
  `anAccountsSettingsAreOpenedReadActedOnAndClosedWithTheirPageTheirTenantAndTheirSignOut` (open refused without a tenant and
  for an unknown connection; read: address, trust, families this device's first, dates as epoch ms, the flags; a sign-out,
  every other saved key, a rename refused by a register-less door, a level change and a wrong password through the
  session's trust, Stop trusting; close → null and refused calls; a tenant switch closes it; this device's own family signed
  out → the sign-in) and `signOutOnASettingsPageIsThisAccountsSignOutHere`; `WireTest` samples of every new variant;
  `SecretFreeSchemaTest` over the new shapes. **Bites** (one edit each, `/bin/cp -f` restore, green after): `Settings.SignOut` made a no-op, `closeTenant` keeping the pages, the watch ignoring the close → `anAccountsSettings…` red; `Settings.SignOutHere` a no-op → `signOutOnASettingsPage…` red; the `removesKey` allowance or the typed password's removed, and a `wrapId` added to `SettingsKeptDevice` → `SecretFreeSchemaTest` red; the `Watched.Settings` sample dropped → `WireTest` red (by derivation). A rerun of `DispatchTest` after the bites once failed `aLinkIsMinted…` (an existing case, untouched here) and was green twice on `--rerun`: a timing flake under load, named here.
  `LiveBridgeTest.anAccountsSettingsAreReadRenamedAndSignedOutThroughCalls` compiles and reports as skipped without `VEYQ_LIVE`
  (a JUnit `Assume`). **Pending, live:** `VEYQ_LIVE=http://veyq.localhost:8080 VEYQ_ENV_FILE=<main checkout>/.run/.env
  ./gradlew :bridge:jvmTest --tests '*LiveBridgeTest.anAccountsSettings*' --rerun` (from `src/mobile`): a fresh login of the
  principal reads its page, renames this device, signs out from the page; only that login's family is touched.
  **Retrospective:** the CR's "SecretFreeSchemaTest unchanged" overlooked that the state it named carries two `…Key…` flags —
  a refine reads the field names against the check's regex.
- [x] **4 Linux and Windows** — the pages (D4). · Done when: `python3 linux/run.py build` and `python3 windows/run.py
  build`/`test` green; each desktop's pass does phase 2's steps.
  **Status: built, both builds green, the visual passes done over a played state (2026-11-13); the live passes (phase 2's
  steps against a server) are pending — the dev backend was down. The box is ticked for the build; the live steps stay below.**
  **Built:** Linux `account_settings.rs` — an `AdwPreferencesDialog` from the sidebar's Settings (`win.account-settings` with the
  shell's connection), groups Account (address, the confirmation as the group's line), This device (an `AdwEntryRow` with apply
  for the name, kept across redraws; Manage's trust line, its level and password rows — `manage::ask_password_then` now shared —
  and Stop trusting), Devices and sessions (a row per family, its menu's sign-out / "Sign out and remove …", each asked in an
  `AdwAlertDialog`; "Sign out everywhere else"), Devices with a saved key, Sign out; a refusal as a toast; a null update closes
  it. Windows `AccountSettingsPage.cs` — the pane's Settings item now selects and shows the page in the `NavigationView`'s
  content (back and any other item leave it; the core closing it does too), cards under the web's section titles, More's
  `MenuFlyout` per family, every destructive step through `Dialogs.Confirm`, the password asked in place as Manage's, a refusal
  in an `InfoBar`. Both follow the system's theme and language (D3: no row for them). **Deviation:** "Add a device" is left
  out on both desktops although the state carries `addDevice`: `src/mobile/CLAUDE.md` § Each platform's guidelines draws no
  control whose screen the app lacks; `cr-app-device-linking` adds the row with its screen. Debug-only harnesses
  `VEYQ_DEV_SETTINGS=1` (`linux/src/dev_settings.rs`, `windows/Veyq/DevSettings.cs`, passed through both `run.py run`s) open the
  page over a played state; a release build has no such door. **Words** (for the merge with phase 2): 31 keys
  `account_page_*` in `words/words.json` (EN/DE, the web's `sessions.*`, `emailConfirm.*`, `settings.sessions` copy; "your
  memories" said as "your data"), reused `drawer_settings`, `drawer_sign_out`, `manage_*`, `signin_cancel`, `node_rename`,
  `trust_failed`.
  **Met:** `python3 linux/run.py build` green (clippy `-D warnings`, `cargo test` incl. `account_settings::tests`, bitten: the last-used line dropped from `family_dates` → red, restored → green);
  `python3 windows/run.py build` green; guards green. Visual passes: debug harness `VEYQ_DEV_SETTINGS=1` over a played state — Linux in the Broadway container (`run.py run`, headless system Chrome), EN/light and DE/dark: every group, a family's menu, the escalation's question (its button first cut, so `account_page_escalate_accept`, the web's short words, now answers it on both desktops), Cancel sending nothing; Windows on the VM (`run.py run`, `Veyq.Drive`), EN/light and DE/dark: every card, More's menu (first drawn past the window's edge, now `BottomEdgeAlignedRight`), the escalation's `ContentDialog`, Cancel sending nothing (the log). Not seen yet: the drawer's entry into the page over a signed-in shell (Linux's sidebar row, Windows' pane item) — it needs a session, so it is the live pass's first step. `kit_drawing::tests::the_printed_kits_page_drawn_here_is_read_by_the_core_over_the_bridge` (from `cr-app-kit-sign-in` phase 8, not touched here) hung idle in 3 of 7 `run.py build`s on this Mac (killed by PID; the others green in about 1.4 s): it waits on the session watch without a timeout — a flake for that CR to fix, named here.
  **Pending, live** (dev backend up, `VEYQ_ENV_FILE=<main checkout>/.run/.env`, `VEYQ_LIVE=http://veyq.localhost:8080`): Linux
  `python3 linux/run.py run --devlogin`, Windows `python3 windows/run.py run --devlogin`; on each: Settings opens with the
  principal's address and this device first ("You are here"); rename this device (stderr/log `settings rename: Done`, the row's
  name follows); a second session signed in by `LiveAccountSettingsTest`'s helper (or the web) appears, is signed out from
  its menu after the question, and drops out; "Sign out" ends the page and shows the sign-in. Record each in
  `native-apps.md`'s measured list.
  **Retrospective:** WinUI's Settings item was a non-selecting item; making it a selecting one with a `Target` of its own let the
  page live in the content with the pane's own selection and back, which is how Windows' apps show settings.
- [ ] **5 docs and harvest** — `native-apps.md` (§ Built, § Account's open items reduced to what is left);
  `uc.account.sessions` notes the apps hold the panel too (no coverage change: the apps are no coverage surface);
  `site/src/content/docs/account/devices-and-sessions.mdx` and its `de/` twin say where the apps keep it, and
  `account/appearance-and-language.mdx` (EN, DE) that the apps follow the system (D3). `cr-app-device-linking` edits
  `account/adding-another-device.mdx` and the sign-in page, not these. · Done
  when: the trust-chain guards green (`for f in scripts/validate_*.py; do /usr/bin/python3 "$f"; done`), the site's
  `pnpm run check` green; this CR deleted.

## Definition of done

On every app, the drawer's Settings opens the signed-in account's page with its address, this device, its devices and
sessions and a sign-out, each action measured live once per app, and the "Add a device" entry present behind
`core.device-linking` for `cr-app-device-linking` to fill.

## Owner questions

1. **What else of the web's settings before Memories?** This CR holds only what surrounds signing in. Candidates, in
   the order I would take them: change password (needs the login challenge and the Secret Key proof), change email,
   replace the keys (needs an Emergency Kit to be *shown* in the app), erase the account, the per-tenant profile.
2. **Appearance and language on the desktops:** follow the system only (this CR), or an in-app choice as the web has?
   Windows and GNOME have no per-app language setting of their own.
3. **Copy:** the page's section titles follow the web's English and German ("Devices and sessions", "Geräte und
   Sitzungen"); confirm or name the apps' own.

**Decided** (the coordinator, 2026-11-12, under the owner's standing mandate to decide and report; the owner may overturn any):
- 1: this CR holds only what surrounds signing in; change password, change email, replace the keys, erase the account and
  the profile follow after Memories as their own CR (inbox #863).
- 2: the desktops follow the system only.
- 3: the web's titles, English and German.
