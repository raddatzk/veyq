# ADR 0151 — The apps' account rest: the sign-in's Secret Key control in every flow that asks for the key, and the breached-password check straight from the device
- Status: Accepted
- Built: yes — on every app (Android, iOS, macOS, Linux, Windows), 2026-11-19; passed on Android, Linux and Windows as
  throwaways; the macOS pass is open and the iPhone's and iPad's are the owner's; a hit drawn live on no platform
  (§ Consequences)
- Date: 2026-11-19
- Model: [native-apps](../crosscutting/native-apps.md) (the check: [security-model](../crosscutting/security-model.md) § the breached-password check)

## Context

Inbox #970 and #969, the two account follow-ups [ADR 0148](0148-the-apps-account-the-webs-flows-ported-into-the-core-the-kit-written-by-the-core-into-the-file-the-person-chose.md)
left in its Consequences. ADR 0148 decision 6 asks for the sign-in's Secret Key control — typed, pasted, a kit file, a
scan — in the password change, the address change and the rotation's gate. As built, those three take the key typed only,
and a **pasted kit token is refused** there although the sign-in takes it: each flow read its field as a bare key, so a
`veyqkit:` token became 32 zero bytes the floor refused as a wrong key. The web's one control, `SecretKeyField.vue`, takes
all four on all five of its screens, swaps a pasted token for its key, and **warns** — never blocks — when a kit carries
another account's address (`unlock.kitEmailMismatch`).

The web also warns of a new password found in known breaches (`useBreachCheck`, `pwned.ts`), on first-run setup, join,
the password change, recovery and the rotation — not on the sign-in. The apps held the new password to the length policy
only. The check's address is the instance's (`core.breached-password`, an `external-service` capability of
`levels: ["instance"]`, its `serviceUrl` served by `getSetupStatus`); the server has **no** range API of its own — the
browser asks the configured address directly, and a proxy through veyq is ruled out (security-model § the breached-password
check). The core has no SHA-1: libsodium has none, and `Primitives` hashes SHA-256 only.

## Decision

### 1. One key intake, the sign-in's, held by each flow

The sign-in's kit handling moves out of `SessionModel` into the core's **`account/KeyIntake`**: the held key, the read of
a kit file (`KitImport`), a scan, the take of a kit read, the clear, what a screen shows of a held kit — and **`typed`**:
a `veyqkit:` token first, then a bare key (the web's paste swap). `SessionModel` keeps its behaviour on top of it.
`PasswordChange`, `AddressChange` (its request and its kit step) and `KeyRotation` (its gate) each own **one intake**:
their actions take the field's text — empty while a kit is held — and read the key from the intake, never from a
parameter of bytes. The key handed to the call is a copy the call zeroes; the held key is zeroed once a call took it to
success, at a clear and when the flow closes. A refused call keeps the held kit for the retry, as the sign-in keeps it.

### 2. A kit of another account warns, as the web

The intake knows the account's address and server. A kit carrying another address shows the web's warn-only notice
(`kit_email_mismatch`), a kit made at another server the existing `signin_kit_other_server`. Neither blocks and neither
clears the key: only the wrap can judge a key, and the alternative is a wrong-password refusal for the person who picked
up the wrong kit (the web's reason).

### 3. The bridge carries a path in and the shown kit out, never the key

`Kit.Read(flow, path)` and `Kit.Clear(flow)` for a flow's intake — the core reads the path, capped at
`KitImport.FILE_CAP`, and draws through the host's existing `PdfPages`/`PdfPage`/`PictureLuma` requests, as at
`Session.SignInKit`. `ScanKind` gains `FLOW_KIT`, routed by `Scan.Open`'s `link` to that flow's intake. Each flow's
`Update` carries what the intake shows (the kit's address and server, the notices, whether a read found nothing). The
typed `secretKey` fields stay — empty while a kit is held — and no secret is added to what the bridge sends out. Android
and Apple, which call the core directly, pass the field's text to the flows and drop their own reading of it.

### 4. Each platform offers the sign-in's controls in the flows' Secret Key section

The same file picker, scanner and camera rule as the platform's own sign-in, under the flow's Secret Key field; a held kit
shows as at the sign-in (its address, *Use another*). Phone flows keep their secure window (ADR 0148 decision 10).

### 5. The breached-password check: the web's, ported into the core

`account/BreachCheck` ports `useBreachCheck` and `pwned.ts` exactly: the states (`idle`, `checking`, `clean`, `hit` with
its count, `unavailable`, `accepted`), the triggers — a check when the field is left and again at submit if the password
was edited since, never while typing, nothing under `MIN_PASSWORD_LENGTH`, one request in flight shared —, the uppercase
hex SHA-1 of the UTF-8 password, `GET <address><first 5>` with `Add-Padding: true`, the suffix matched on the device, a
failure or a non-2xx **unavailable**, never zero. `proceed()` passes clean, unavailable and accepted; a hit waits for
*Use it anyway*, which an edit takes back. It runs on the **new password** of `PasswordChange` and of `KeyRotation` when
one is chosen, and the core refuses a submit `proceed()` does not pass — the platform only draws. SHA-1 is a pure-Kotlin
common `account/Sha1` (no dependency), pinned by FIPS 180-4's examples and the web test's oracle; it is no suite
primitive and gets no vector.

### 6. Where it asks, and what that tells whom

The address is the instance manifest's `core.breached-password` `serviceUrl` (`getSetupStatus`, read once per flow when
it opens). The capability is instance-only, so the tenant's set adds nothing. None served: no check and no notice, as on
the web. The request leaves through the core's one door to a third party, **`OutsideHttp`** — the map's `MapHttp`
generalised, the map its first user: the account's `outside` client, `https` only, no bearer, no cookie, no `Referer`,
`User-Agent: veyq/<major> (+https://veyq.app; <instance origin>)`. **The operator learns nothing** — nothing is proxied.
The range service (Have I Been Pwned by default, the Server Owner's mirror, or none) learns a 5-hex prefix, the device's
address and the instance's origin — what the browser's `Origin` tells it. The switch is the Server Owner's alone, as on
the web: the apps offer a person none. Self-hostable as today: an instance without internet unsets the address and the
apps do not check.

### 7. The bridge for the check

`PasswordChange.Check(flow, newPassword)` and `KeyRotation.Check(flow, newPassword)` — the password typed in, inbound
only — sent when the field is left; `…Edited(flow)` on any edit, carrying no text; `…UseAnyway(flow, on)`. Each flow's
`Update` carries the check's state and count. The submit calls are unchanged.

### 8. Words

New in `src/mobile/words/words.json`, the web's text in English and German: `password_pwned_warning` (plural over the
count), `password_pwned_anyway`, `password_pwned_unavailable`, `kit_email_mismatch`. The kit controls reuse the sign-in's
words.

## Consequences

- **The server learns nothing new**: no endpoint, field or vector changes; the backend is unchanged.
- **One more party may be asked, by the instance's choice:** the range service the Server Owner configured learns a
  5-hex prefix, the device's address and the instance's origin when a person chooses a password in an app — what it
  already learns from the browser.
- **The password, its full hash and the Secret Key are in no request, no log, no store and no bridge update**; the key
  lives in the core's memory for the call (or, a kit held, until the flow took it or closed) and is zeroed after it.
- **Out of scope:** the apps have no join, recovery or first-run setup; when one comes, it takes decision 5's check.
- **Built 2026-11-19 on every app** (cr-app-account-rest): the core's `account/KeyIntake` (`typed`, `key`, `read`,
  `take`, `scan`, `clear`, `spent`; what a screen sees, `IntakeState` and `HeldKit`), `SessionModel` on it, and one
  intake in each of `PasswordChange`, `AddressChange` (its request and its kit step share it) and `KeyRotation`, whose
  actions take the field's text (the byte overloads `internal`); `account/Sha1`, `session/OutsideHttp` (the map's
  `MapHttp` renamed and moved, its tests unchanged but for the name), `account/BreachCheck` (`BreachState`, `BreachKind`;
  the password it speaks about `@Transient`, never printed), wired into `passwordChange()` and `keyRotation()` by
  `BreachCheck.of(account)`. The bridge: `Kit.Read(flow, path)` (`NO_KIT` where none was read), `Kit.Clear(flow)`,
  `ScanKind.FLOW_KIT`, `PasswordChange.Check`/`Edited`/`UseAnyway` and `KeyRotation`'s alike; `Update.PasswordChange`
  and `.KeyRotation` carry `kit: IntakeState?` and `breach: BreachState?`, `Update.AddressChange` `kit`. The screens:
  Android `AccountSecretFlows.kt` (`FlowKeyField`, `NewPasswordField`, the scanner drawn once over the flow); Apple
  `AccountFlows.swift` (`FlowKeySection` with `.fileImporter([.pdf, .image])` and `KitScannerView` over appleMain's
  `AppleKit.scan(intake:timing:)`, `NewPasswordRows` with `@FocusState` and a `Toggle`); Linux `account_flows.rs`
  (`KeyControl` through `gtk::FileDialog` and `ScanView::open_flow_kit`, `NewPassword` with an
  `EventControllerFocus` and a `gtk::CheckButton`); Windows `AccountFlows.cs` (`FlowKeyControl` through
  `FileOpenPicker` and `QrCamera.OpenFlowKit` in a `ContentDialog`, `NewPasswordField` with `LostFocus` and a
  `CheckBox`). The words of decision 8, the web's EN and DE. No permission, entitlement or dependency was added.
- **Where it was built otherwise than decided.** *Decision 4's "Use another":* a held kit offers the sign-in's
  *Enter it instead* (`signin_kit_clear`), which clears it back to the typed field. *The desktops' rows:* the password
  change's and the rotation gate's rows stay filled until the core took them — the web's form; emptying a row first
  would send `Edited` and drop an acceptance before the submit —, and Windows builds both forms once and draws them in
  place, so a verdict arriving while the next box is typed in never takes the focus.
- **What the passes proved** (each as a throwaway owner with a member, made and erased by the pass). The instance's range
  address is the real Have I Been Pwned (`/api/setup`), so every pass blocked it on its own platform and **no live
  query was sent** (`src/mobile/CLAUDE.md` § Prove your work says how). *Android* (a throwaway API 36 emulator, deleted
  after): the password changed with the kit **file** picked in DocumentsUI and no key typed, then again with the
  `veyqkit:` token entered into the Secret Key field; another account's kit in the address change warned with both
  addresses, and *Enter it instead* went back to typing; with `-dns-server 192.0.2.1` leaving the new password showed
  *could not be checked* and the change went through; afterwards the app's 20 files and logcat held no password (old,
  both new), no Secret Key (printed or compact), no token, no SHA-1 (upper, lower) nor suffix, no `pwnedpasswords`.
  *Linux* (Broadway in the Fedora 44 container, the debug build; `VEYQ_UNREACHABLE=api.pwnedpasswords.com`): the same
  three — the kit file through GTK's dialog, the token typed into the Secret Key row, another account's kit warned and
  cleared —, *could not be checked* (`Checking` → `Unavailable`) in the password change and on the rotation's gate, the
  change through; the app's stderr and its home volume (19 files) held none of the same canaries. *Windows* (the VM
  through UI Automation, a throwaway on `tw-win.localhost`): the password changed three times — the kit file through
  the open picker, the token typed, the kit **scanned** (`VEYQ_DEV_CAMERA`, the token's QR) —; another account's kit
  warned and cleared; the rotation's gate proved with the kit file (`KeyRotation.Prove` → the kit step; left there,
  nothing published); with the instance's range address rewritten to one under `.invalid` by a pass-only proxy,
  *could not be checked* in the password change and the rotation, the changes through; the app's data, `TEMP`,
  `INetCache` and `Recent` (1167 files) held none of the canaries, either account's key included.
- **What no pass has proved yet.** **A hit drawn live** — the count, the submit held, *Use it anyway*, an edit taking
  the acceptance back — on any platform: no played range service reaches an app, so these states are
  `BreachCheckTest`'s and `BreachCanaryTest`'s (the request's shape against a played tenant), not a screen's.
  **macOS: no pass ran** — the agents' sessions have no full-screen control (`src/mobile/CLAUDE.md` § Traps); the
  coordinator runs it from the main session (the kit file through the open panel in each flow, the notice under the new
  password). **The owner's:** an iPhone and an iPad — the kit scanned with the real camera in the password change, the
  notice under a new password (no simulator boots here, #928). **Not run:** a scan on Android (no virtual-scene kit) and
  on Linux (no camera in the container; a `VEYQ_DEV_CAMERA` scan of a flow is open); the rotation's gate with a kit on
  Android and Linux, and on no platform the rotation run to its end with a kit; the address change sent with a kit
  (every pass read one there for the warning only); Linux's Flatpak build (the container's debug build instead).

## Alternatives considered

- **One intake per account, shared by the flows** — a key would outlive the flow that asked for it.
- **The range API proxied by the veyq server** — the operator would see every prefix with the account that asked; the
  security model rules it out.
- **A SHA-1 from a platform library or a dependency** — four implementations, or a dependency for one hash; a common
  Kotlin one is the `export/Sha256.kt` form.
- **A per-person switch for the check** — the web offers none; the switch is the Server Owner's.
- **A check while typing** — it sends the prefix of every partial password (security-model's reason).
