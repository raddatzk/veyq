---
id: cr-device-trust-on-linux-and-windows
title: Device trust on Linux and Windows — the core holds the Secret Service and Windows Hello itself, the key never crossing the bridge
status: in-progress
satisfies: []
adrs: ["0123", "0127", "0128", "0005", "0058"]
invariants:
  - no key, seal, factor or token crosses the bridge — DeviceKeys lives in the core's linuxMain/mingwMain, never behind a host request (ADR 0127's Consequences; SecretFreeSchemaTest)
  - the flows stay the core's — enrol, resume, unlock, sign-out and stop-trusting are Account/SessionModel's as on Android and Apple; a desktop adds a DeviceKeys and screens, no rule
  - a trust that fails never fails the sign-in, and with no secure store the app works as today, signing in at every start (ADR 0127 decision 4)
  - every guarantee a desktop store gives less of than Android's Keystore is written down where the security model is, never implied
  - Android, the Apple app and the web behave as before; `./gradlew :core:jvmTest :core:macosArm64Test` and both desktops' `run.py test` stay green in every phase
---

## Goal

CR 2 of the owner's cut for bringing Linux and Windows to the Android and Apple apps (2026-11-11, "passt"). Linux and
Windows gain the device trust that Android and Apple have had since ADR 0123 decision 8. It has two parts:
- **The password trust:** the account opens with the password alone, the Secret Key replaced by this device's factor.
- **Staying signed in:** the Master Key is sealed under an unlock key that opens after the person authenticated.

With them come the screens around it:
- "Stay signed in" at the sign-in;
- the unlock screen;
- "Another account";
- "Manage servers" with "Stop trusting this device".

ADR 0127's Consequences set the shape. The core reaches the secure store itself, the Secret Service through libsecret
and Windows Hello with DPAPI, so the key never leaves the library and never crosses the bridge.

## Context & constraints

- **Substrate:** branch `claude/windows-cr-f6c73c` at `09582d6a8`. It holds CR 1 (ADR 0128) and both desktops' first
  slices, harvested.
- **Read first:**
  - ADR 0123 decision 8;
  - ADR 0127 decisions 1, 4 and 5, and its Consequences on the Secret Service and DPAPI;
  - ADR 0005 §4, §5 and §9;
  - ADR 0058;
  - `uc.account.trust-device`, `uc.account.remember-this-device`;
  - `core/…/session/DeviceTrust.kt`, `Account.kt` and `SessionModel.kt`;
  - `src/mobile/CLAUDE.md`, the staying-signed-in traps;
  - `docs/crosscutting/native-apps.md` § Open, Linux (oo7 on the Fedora VM) and Windows.
- **The flows exist and stay untouched.** Account and SessionModel already enrol, resume, unlock, sign out and stop
  trusting through the `DeviceKeys` interface (R2). This CR adds two `DeviceKeys` implementations, the bridge calls,
  and the screens. A rule found missing moves into the core with its test, as in every CR since ADR 0127.
- **Where what runs:**
  - Kotlin cross-compiles on this Mac.
  - Linux runs in the Fedora 44 container (`linux/run.py`).
  - Windows runs on the ARM64 VM (`windows/run.py`), where the app reaches session 1 through the scheduled tasks.
  - The owner declared both VMs disposable (2026-11-10).
  - The dev backend (`veyq.localhost:8080`) is never started twice or stopped by this CR.
- **Never typed by an agent:** a password, the Secret Key, a Windows Hello PIN. The sign-in is the debug devlogin. A
  Hello prompt in a pass is answered by the owner (O2).

## Measured at refine (2026-11-11)

| # | What | How |
|---|---|---|
| R1 | The `DeviceKeys` contract (`session/DeviceTrust.kt:27-49`) has three parts. **Bind key:** `createBindKey`, `bindHmac` (never prompts), `deleteBindKey`. **Unlock key:** `sealUnlockable`/`openUnlockable` (may prompt; `DeviceAuthFailed(cancelled)`), `deleteUnlockKey`. **Small secrets:** `putSecret`/`secret`/`deleteSecret`, the refresh token, kept without prompting. | read |
| R2 | The flows and their ops (`Account.kt`, `SessionModel.kt`). **Enrol:** `remember` → `sealUnlockable` (prompts) + DEVICE_KEY wrap + `putSecret`; a `DeviceAuthFailed` falls back to the password trust (`createBindKey` → `bindHmac` → Argon2id(factor, password) → DEVICE wrap). **Resume:** `secret` → refresh → `putSecret` → `openUnlockable`. **Unlock with the password:** `bindHmac`. **Sign-out:** `forget` for DEVICE_KEY, then `deleteUnlockKey` once no DEVICE_KEY record remains. **Stop trusting:** `forget` for either. Every trust failure is swallowed: a sign-in never fails for it. | `Account.kt:283-319, 551-632`; `SessionModel.kt:120-208, 255, 394-403` |
| R3 | **What each platform's store guarantees.** **Android:** Keystore HMAC and AES-GCM keys, non-exportable; the unlock key needs `BIOMETRIC_STRONG\|DEVICE_CREDENTIAL` with a 15 s window and is invalidated by a new enrolment. **Apple:** the bytes are Keychain items, the bind key plain and the unlock key under `.userPresence`; HMAC and GCM run in libsodium, so the bytes enter memory. **Web:** non-extractable WebCrypto keys in IndexedDB, **no presence check at all**. | `KeystoreDeviceKeys.kt`, `Apple.kt:46-106`, `KeychainStore.swift`, `src/web/…/rememberMe/deviceKeyWrap.ts` |
| R4 | The bridge today: `Session.SignIn` hard-codes `remember = false` (`Bridge.kt`) and `Bridge.live` builds `ThisDevice(store, NoDeviceKeys, …)` (`Host.kt:104`). There is no Unlock, OtherAccount, ForgetDevice or Manage call. `Gate.Unlock(record)` already serializes, and its record is non-secret. | `Wire.kt`, `Bridge.kt` |
| R5 | `DeviceTrustLiveTest` asserts on the fake's own fields (`SoftwareDeviceKeys.bindKeys`, `.prompts`, `.authenticates`, `.unlockKey`), so it cannot run over a real store as written. | `core/jvmTest/…/DeviceTrustLiveTest.kt`, `core/commonTest/…/TestDevice.kt` |
| R6 | **The Windows VM:** Windows Hello was not set up at first; the owner set a PIN (O1, 2026-11-11). **`dsregcmd /status` is no test for it on a local account:** it said `NgcSet : NO` before and after. The PIN credential provider's per-user key is: `HKLM:\…\Credential Providers\{D6886603-9D2F-4EB2-B667-1971041FA96B}\<SID>` exists with `LogonCredsAvailable : 1` once a PIN is set. There is no Entra ID. The key storage providers are present: `Microsoft Passport Key Storage Provider`, `Microsoft Platform Crypto Provider`, `Microsoft Software Key Storage Provider` (`certutil -csplist`). There is a TPM 2.0 (`Win32_Tpm`, SpecVersion 2.0). | ssh on the VM |
| R7 | Kotlin/Native's mingw sysroot carries `dpapi.h`, `wincrypt.h`, `ncrypt.h`, `libcrypt32.a` and `libncrypt.a`. `ncrypt.h` has no `MS_NGC_KEY_STORAGE_PROVIDER` define: the provider's name is a string the cinterop declares. | `find ~/.konan/dependencies/msys2-mingw-w64-x86_64-2` |
| R8 | **Fedora 44:** `libsecret-devel` 0.21.8 and `gnome-keyring` 50.0 are available and not in the build image (`veyq-linux-dev`). native-apps § Open measured the Fedora VM's oo7 0.7.0: `pam_oo7` unlocks the login collection at sign-in, and a libsecret item stores and reads **with no prompt**. | `docker run veyq-linux-dev dnf list …`; native-apps |
| R9 | `docs/crosscutting/security-model.md` has no device-trust row in "What holds today on a client the operator does not serve". | read |

## Decisions

### D1 — Windows: the bind key and the secrets under DPAPI, the unlock key behind Windows Hello

- **Bind key and secrets: DPAPI** (`CryptProtectData`/`CryptUnprotectData`, current user, no UI flag). The device
  store keeps the protected blob. HMAC-SHA256 runs in libsodium in process. **This is Apple's level** (R3): the bytes
  enter memory while in use, bound to this Windows user, never prompting.
  - A domain's roaming profile carries DPAPI's master key to another PC, so the trust would roam. That is written down
    as a residual, and only with the profile itself.
- **The unlock key: Windows Hello, enforced by its key, not by a dialog.**
  - At the first seal, the core creates an RSA key in the **Microsoft Passport Key Storage Provider** (NCrypt). That
    key lives in Hello's container, TPM-backed where a TPM is.
  - The core draws 32 random bytes, the AES-256-GCM unlock key, encrypts them under that key's public half
    (`NCryptEncrypt`, no prompt) and keeps the result in the device store.
  - `openUnlockable` decrypts it with `NCryptDecrypt`, which **Windows Hello prompts for**, with the host's window as
    the prompt's owner.
  - The opened key is held in memory for 15 s, so one prompt per start covers every remembered account, as Android's
    and Apple's windows do.
  - A cancelled prompt (`NTE_USER_CANCELLED`, `SCARD_W_CANCELLED_BY_USER`) becomes `DeviceAuthFailed(cancelled = true)`.
    Everything else, a missing key included, becomes `false`.
- **Why Passport KSP over the alternatives:**
  - `UserConsentVerifier` is a dialog only: advisory, not enforcing, so the bytes would still open without it.
  - `KeyCredentialManager` is WinRT, which Kotlin/Native reaches only through raw COM activation.
  - The Passport KSP is plain C (NCrypt), in the sysroot already (R7), and Windows itself refuses the private key's use
    without Hello.
- **No Hello set up (owner 2026-11-11, Q2): the unlock key falls back to DPAPI.** No presence check: the Windows sign-in
  is the authentication, Linux's level (D2) and the web's. "Stay signed in uses the strongest the device has." The switch
  says which one it is (D5), so the weaker trust is never silent. Which path a key took is kept beside it in the device
  store (`hello` / `dpapi`), so an open uses the path its seal used. A Hello set up later applies from the next enrolment.
- **The host's window handle** goes to the core as a number in `Config` (`window`), not a secret. A prompt without an
  owner may open behind the app.
- **Phase 1's first measurement:** with the owner's PIN set (O1), an unpackaged x64 process under Prism, of a local
  account, creates a Passport KSP key, and a decrypt prompts in session 1. If that fails, the phase stops and asks; the
  plan does not quietly fall back to `UserConsentVerifier`.

### D2 — Linux: everything in the Secret Service, libsecret reached by `dlopen`

- **Bind key, unlock key and secrets** are items of the default collection under one schema
  (`me.raddatz.veyq.DeviceKey`, attribute `alias`). HMAC and GCM run in libsodium in process. **This is Apple's level
  for the bind key** (R3).
- **The unlock key has no presence check (owner 2026-11-11, Q1: allowed, written down):** the Secret Service has no
  such concept. Its collection is unlocked by the desktop sign-in, and the login session is the authentication (R8), the
  web's level. D6 writes it into the security model.
- **libsecret is reached by `dlopen("libsecret-1.so.0")`,** with its few functions and its `SecretSchema` declared by a
  small header of our own in the cinterop (opaque `GError`/`GCancellable`). No link-time dependency, so:
  - The bridge library still links against Kotlin/Native's old sysroot. No glib or libsecret headers or stubs are
    carried into the Mac's build.
  - The app runs where libsecret is absent, behaving as today (ADR 0127 decision 4).
  - The Flatpak's runtime (CR 6) carries libsecret. Its portal or the host's Secret Service answers through D-Bus.
  - The ABI is small and stable: `secret_password_storev_sync`, `secret_password_lookupv_sync`,
    `secret_password_clearv_sync`, `secret_password_free`, `g_error_free`, and `g_hash_table_*` for the attributes.
- **No Secret Service on the bus, or a locked collection the user does not open:** creating a key raises
  `DeviceAuthFailed(false)`. Then the password trust is kept, or, with no store at all, each start signs in, as today.

### D3 — One contract test, run over every store

- R5's obstacle is lifted by moving the assertions of `DeviceTrustLiveTest` and a new **`DeviceKeysContract`**
  (`commonTest`) onto `DeviceKeys`' observable behaviour:
  - a bind HMAC is deterministic, replaced by `createBindKey`, and gone after delete;
  - secrets round-trip and delete;
  - `seal` → `open` round-trips under its AAD and fails under another;
  - `open` after `deleteUnlockKey` raises `DeviceAuthFailed(false)`.
- **The contract runs over:** `SoftwareDeviceKeys` (`commonTest`), Linux's store (`linuxTest`, in the container with
  `gnome-keyring-daemon` under `dbus-run-session`, its collection unlocked by a throwaway password generated for the
  container), and Windows' DPAPI half (`mingwTest`, session 0).
- **The Hello half cannot run unattended:** a PIN is never typed by an agent. It is proved in the app pass, the owner
  answering the prompt (O2).
- `SoftwareDeviceKeys` keeps its counters for the flows' own tests (`prompts`, `authenticates`). They become the
  fake's extras, not the contract.

### D4 — The bridge: the trust's calls, nothing secret outward

- **New calls:**
  - `Session.SignIn` gains `remember: Boolean`;
  - `Session.Unlock(password)`: the password for a DEVICE record, `""` for DEVICE_KEY, where the core prompts itself;
  - `Session.OtherAccount`;
  - `Session.ForgetDevice(connection)`;
  - `Session.AddServer` / `Session.CancelAdd`;
  - `Manage.Open` / `Manage.Close`, with the managed servers and each one's trust method (`ManagedServer.trusted`).
- **`Config` gains** `window`, Windows' HWND, `0` elsewhere.
- **`Bridge.live` builds** `ThisDevice(store, platformDeviceKeys(config), …)`: an `expect` in `bridgedMain`, actual in
  `linuxMain`/`mingwMain`. `NoDeviceKeys` stays for tests and as the answer where no store opens.
- **`SecretFreeSchemaTest` holds:** `Gate.Unlock`'s record is the non-secret `TrustedDevice` (R4). The password and
  Secret Key cross inward only, as `Session.SignIn`'s do.

### D5 — The screens, as Android and Apple draw them, to each desktop's guideline

- **The sign-in:** a "Stay signed in" switch (`AdwSwitchRow` on Linux; a `ToggleSwitch` on Windows), off by default as
  on the apps. **Its label says what it uses:** "Stay signed in with Windows Hello" where Hello is set up, plain "Stay
  signed in" otherwise. Which one is the core's answer (`DeviceKeys`' capability, sent beside the sign-in gate), never a
  host's own probe.
- **The unlock screen** (`Gate.Unlock`), with "Another account":
  - for a DEVICE record, the password field;
  - for DEVICE_KEY, one button that asks the core, which raises Hello's prompt;
  - `DEVICE_AUTH_CANCELLED` keeps the screen, `DEVICE_NOT_TRUSTED` goes to the sign-in with server and email filled in.
- **"Manage servers"** from the switcher: each server with its trust method, "Stop trusting this device", sign out,
  "Add server". It is what Android's `ManageScreen` / Apple's `ManageView` show, and the gap list's "Manage servers"
  row.
- **Every word comes from `words.json`;** the apps' existing keys are reused.

### D6 — The security model says what each desktop holds

- `security-model.md` gains a device-trust row (R9) per platform, with the guarantee of each key:
  - Android: hardware, presence enforced;
  - Apple: Keychain, presence enforced, bytes in memory;
  - Windows: DPAPI bind key; unlock key behind Hello's key, enforced, or DPAPI with no presence where Hello is not set up;
  - Linux: Secret Service, unlocked by the login, no presence (or none, per Q1);
  - web: non-extractable, no presence.
- ADR 0123 decision 8's Consequences say the same, as built.

## Owner steps (each an approval)

- **O1 — Set up Windows Hello on the Windows VM (done, owner 2026-11-11):** Settings → Accounts → Sign-in options → PIN (Windows Hello), at the
  VM's console. An agent never sets or types a PIN. Needed before Phase 1's first measurement.
- **O2 — Answer the Hello prompt during the Windows pass** (Phase 5), when the driver says it is waiting. An agent
  reads the result, never the PIN.

## Inventory (locators — run at implement time)

- `grep -rn "NoDeviceKeys\|remember = false" src/mobile/bridge/src`: what D4 replaces.
- `grep -rn "SoftwareDeviceKeys\|keys\.\(bindKeys\|prompts\|authenticates\|unlockKey\)" src/mobile/core/src`: what D3 moves.
- `grep -rn "ManageScreen\|ManageView\|forgetDevice\|otherAccount" src/mobile/android src/mobile/apple`: what D5 mirrors.

## Phases

### Phase 1 — Windows' DeviceKeys in the core (`mingwMain`)

1. D1's first measurement (O1 done): the Passport KSP key and its prompt in session 1.
2. Then the DPAPI half and the Hello half.
3. D3's contract over the DPAPI half in `mingwTest`.

**Done when:**
- `windows/run.py test` is green, the contract included.
- A probe driven in session 1 (through `Veyq.Drive`'s launch) seals, then opens with the owner's PIN, and a cancelled
  prompt gives `cancelled = true`. Measured with O2's help, or deferred to Phase 5's pass if the owner prefers one
  sitting.
- Android, Apple and Linux are unchanged and green.

**Built (Phase 1):**
- **The cinterop `wincred`** (`core/wincred/wincred.def`, `veyq_wincred.h`): only the header's C helpers are exposed, so
  windows.h's types stay platform.windows'; every Windows call is made in C, and only integers, bytes and UTF-16 strings
  cross. It declares the provider's name itself (R7) and links ncrypt, crypt32, advapi32 and ole32. The header is
  declared the cinterop task's input (an include directory is not one).
- **`WindowsDeviceKeys`** (`mingwMain/…/session/WindowsDeviceKeys.kt`), as D1 says:
  - bind keys and secrets under DPAPI (current user, `CRYPTPROTECT_UI_FORBIDDEN`, an entropy per use, so a bind blob
    never opens as a secret);
  - the unlock key behind an RSA-2048 key `<SID>//veyq//unlock` in the Passport provider, created with NgcCacheType
    AUTH_MANDATORY; the 32 bytes encrypted with `NCryptEncrypt` (PKCS#1 v1.5, the padding the provider is known to
    take; OAEP untried), decrypted with the HWND, the Use Context and `PinCacheIsGestureRequired` set;
  - DPAPI where Hello is not set up; the path kept as `veyq.wincred.unlock.path`; 15 s held behind a mutex;
  - `helloAvailable` (D5's capability): cryptngc's `NgcGetDefaultDecryptionKeyName` names a key and the provider opens.
    That is KeePassWinHello's check, an undocumented export loaded at run time; R6's registry key is not read.
- **D3:** `DeviceKeysContract` (`commonTest`) over `SoftwareDeviceKeys` on every target (`testPrimitives`, an `expect`
  with jvmTest/nativeTest actuals) and over the DPAPI half in `mingwTest`. `DeviceTrustLiveTest` asserts the dropped
  bind key through `bindHmac` and the deleted unlock key through a canary seal; the fake's maps are private.
- **The probe:** `mingwTest`'s `HelloProbe` (inert without `VEYQ_HELLO_PROBE`) and `run.py hello-probe [--check]`,
  which starts the core's test binary through `veyq-app`; Veyq.Drive's launch takes `"args"`. It waits until the probe
  ends, never for a clock: a step waits for a person.

**Met (2026-11-11, commits `7c12cc50f`, `c872e839d`, `a9546d0d0`, `758f3ce5e`):**
- [x] `/usr/bin/python3 src/mobile/windows/run.py test` → `200 tests from 44 test cases ran`, `PASSED 200`, the
  contract over the DPAPI half included. **Bites**, each red then restored: `deleteBindKey` a no-op (the contract), the
  SCARD code dropped from the mapping, a 30 s window, the path chosen by today's availability instead of the kept one,
  the secrets' DPAPI entropy the bind keys', a cancel swallowed into `false` (each its own test); on the JVM a no-op
  `deleteUnlockKey` in the fake turned the contract red.
- [x] The Hello half, with the owner at the VM's console (O2):
  `/usr/bin/python3 src/mobile/windows/run.py hello-probe` → `hello-available true`, `seal ok 649415` (`path hello`),
  `open ok 4022`, `open-in-window ok 0`, `open-after-window DeviceAuthFailed(cancelled=true) Passport decrypt:
  0x80090036`, `open-after-delete DeviceAuthFailed(cancelled=false) no unlock key`, `passport-key-after-delete
  DeviceAuthFailed(cancelled=false) Passport key open: 0x8009000d`, `done`. D1's first measurement holds: an unpackaged
  x64 process under Prism, of a local account, creates the Passport key, and its decryption prompts in session 1.
  **`NCryptFinalizeKey` asks for the PIN too:** enrolment asks once (the seal), which `sealUnlockable` allows ("may
  ask"); Phase 5's pass expects it.
- [x] "Hello available" answers `true` in session 0 (`run.py test`, `helloAvailabilityIsAnswered`) and in session 1
  (`run.py hello-probe --check`). The "not set up" answer is unmeasured: the VM has a PIN.
- [x] Unchanged elsewhere: `./gradlew :core:jvmTest :core:macosArm64Test` → `BUILD SUCCESSFUL`;
  `/usr/bin/python3 src/mobile/linux/run.py test` → `PASSED 189`; `windows/run.py bridge` → the export gate and the
  smoke green. The verification-metadata write from an empty Gradle home left the file unchanged.
- [ ] Not run: `DeviceTrustLiveTest` against the dev instance (a worktree has no `.run/.env`); it compiles. Phase 3
  runs it.

### Phase 2 — Linux's DeviceKeys in the core (`linuxMain`)

D2: the `dlopen` layer and its header, the store, the absent-store case. The image gains `libsecret` and
`gnome-keyring` (test only; the app's runtime needs only libsecret).

**Done when:**
- `linux/run.py test` is green with the contract over the real Secret Service in the container.
- An absent `libsecret-1.so.0` (the library moved away) answers `DeviceAuthFailed(false)` on enrol and nothing breaks.
- The bridge library's exports are unchanged (`run.py bridge`).

### Phase 3 — The bridge

D4: the calls, `Config.window`, `platformDeviceKeys`, the generated Rust and C#.

**Done when:**
- `./gradlew :bridge:jvmTest` is green: `DispatchTest` covers each new call over the played door, with
  `SoftwareDeviceKeys`. `SecretFreeSchemaTest` holds, with its bite.
- `LiveBridgeTest` signs in with `remember` over `SoftwareDeviceKeys`, resumes and unlocks.
- `DeviceTrustLiveTest` runs over the contract (D3) and is still green.
- Both `run.py bridge` are green.

### Phase 4 — Linux's screens

D5 on Linux.

**Done when** a pass in the container has run, against the dev instance and the container's own Secret Service
(gnome-keyring under the session bus, its data on a volume so a second start finds it):
- sign in with "Stay signed in", quit, start again: resumed with no sign-in (per Q1), or the unlock screen with the
  password (password trust);
- "Stop trusting" in Manage servers, and the next start signs in;
- the same with the libsecret library absent: every start signs in, nothing fails;
- de/en, light/dark;
- `run.py build` and both guards are green.

### Phase 5 — Windows' screens

D5 on Windows.

**Done when** a pass on the VM has run:
- sign in with "Stay signed in with Windows Hello", quit, start: Hello's prompt, answered by the owner (O2), and the
  shell resumed;
- the prompt cancelled: the unlock screen stays;
- the password trust where "Stay signed in" is off;
- "Stop trusting";
- de/en, light/dark;
- `run.py build` and both guards are green.

### Phase 6 — The docs

- D6: the security model's row.
- ADR 0123 decision 8's Consequences.
- ADR 0127's Consequences: the Secret Service and DPAPI as built.
- `src/mobile/CLAUDE.md`: the stores, the traps, the contract test.
- native-apps: the rows, and § Open (KWallet and KeePassXC still untried, D2's ABI on another distribution).
- The CR's harvest, then the CR is deleted.

**Done when** the trust-chain guards are green.

## Not in this CR

- Packaging (CR 6: the Flatpak's libsecret and portal, MSIX).
- Unlocking through a fingerprint reader on Linux (fprintd/polkit): no standard path to a key, so not planned.
- The device register's own screen. It is written by every unlock and enrolment (ADR 0058) and shown on the web.

## Owner questions

- **Q1 + Q2 (settled, owner 2026-11-11, "passt so"): one rule for both desktops — "Stay signed in" uses the strongest
  the device has.**
  - Windows with Hello: Hello's key.
  - Windows without Hello, and Linux: the desktop sign-in as the authentication (DPAPI, the Secret Service), the web's
    level.
  - The switch's label tells which (D5), and the security model writes it down (D6).
  - The refine's premise that "almost every Windows 11 PC has Hello" was unmeasured and withdrawn: local accounts without
    a PIN, policies that turn Hello off, and VMs are common enough.

## Retrospective

- **Phase 1, execution: `run.py hello-probe` timed the person out.** The first version waited 600 s on the Mac, as
  `drive` waits for a machine; the owner reached the console after the seal's prompt had waited 649 s, the wait ended,
  and the outcome was fetched by hand, though the probe had finished on the VM. Root cause: a wait sized for a program
  was put in front of a person. Fixed in `758f3ce5e` (wait for the probe's end, fetch last).
- **Phase 1, planning: whether creating a Passport key prompts was left open** until the probe; it does, so enrolment
  asks for the PIN once. Cheap to have asked of the probe's design explicitly, so the owner's brief said "two PINs, then
  Cancel" up front.
- **Next time:** a verb that waits for a person waits for the process it started and fetches its outcome whatever
  ended the wait; its brief to the owner lists every prompt the run may raise, the uncertain ones marked so.
